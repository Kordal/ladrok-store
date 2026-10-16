#!/usr/bin/env python3
# SPDX-License-Identifier: Apache-2.0
"""Resolve catalog/apps.yaml into catalog.json, the Catalog Ladrok Store downloads.

With --awesome, resolve the Awesome Android TV FOSS Apps list into foss.json instead: the FOSS
Catalog, a separate screen in the Store (see resolver/awesome.py and docs/adr/0003).

For each App: find its newest APK (GitHub release, F-Droid, or a download page) and check it with the
Android SDK tools: right package name, a TV launcher entry, an ABI the TV runs, the same signing
certificate as before, and no downgrade. When the source publishes the APK's SHA-256 (GitHub release
assets, F-Droid's index), only the parts the checks need are read, with HTTP range requests: the
zip directory, the manifest, the resource table, the signing block and the icon. Anything else is
downloaded whole, once per URL. The Store gets the source's own URL; nothing is re-hosted. An App that fails keeps its last good
entry, so one broken release never takes an App off the TV. Signing catalog.json is the workflow's
job (.github/workflows/catalog.yml), not this script's.

    python3 resolver/resolve.py --catalog catalog/apps.yaml --previous previous.json \
        --out out --base-url https://github.com/Kordal/ladrok-store/releases/download/catalog
    python3 resolver/resolve.py --awesome --exclude out/catalog.json --previous previous-foss.json \
        --name foss.json --out out --base-url https://github.com/Kordal/ladrok-store/releases/download/catalog
"""
import argparse
import concurrent.futures
import datetime
import glob
import gzip
import hashlib
import html
import http.client
import json
import os
import re
import struct
import subprocess
import sys
import tempfile
import threading
import time
import urllib.error
import urllib.parse
import urllib.request
import zipfile
import zlib

import awesome

FORMAT = 1
# Candidate APKs tried per App before giving up: a download page may link several (phone, TV, ABIs).
MAX_CANDIDATES = 6
# APKs resolved at once: most of the time goes into downloads.
WORKERS = 8
# An APK URL the checks rejected isn't downloaded again for this long (--remember-rejected).
REJECTED_DAYS = 7
USER_AGENT = "ladrok-store-resolver"
FDROID_INDEX = "https://f-droid.org/repo/index-v2.json"


class Rejected(Exception):
    """This APK can't go in the Catalog; the message says why."""


class NoPartial(Exception):
    """This APK can't be checked from a few parts of it; the message says why. It is downloaded whole."""


# --- Fetching ---------------------------------------------------------------------------------

def http_get(url, accept=None, token=None):
    headers = {"User-Agent": USER_AGENT, "Accept-Encoding": "gzip"}
    if accept:
        headers["Accept"] = accept
    if token:
        headers["Authorization"] = "Bearer " + token
    with urllib.request.urlopen(urllib.request.Request(url, headers=headers), timeout=60) as response:
        data = response.read()
        # F-Droid's index is tens of MB of JSON: compressed it comes in a fraction of the time.
        if response.headers.get("Content-Encoding") == "gzip":
            data = gzip.decompress(data)
        return data


def download(url, path):
    request = urllib.request.Request(url, headers={"User-Agent": USER_AGENT})
    with urllib.request.urlopen(request, timeout=300) as response, open(path, "wb") as out:
        while chunk := response.read(1 << 20):
            out.write(chunk)


# --- Sources: each yields candidate APKs, newest first, as {"url", "notes"} --------------------

def asset_rank(name):
    """How likely an APK asset is the one the TV runs: 64-bit ARM first, then universal builds, then
    other ABIs, debug builds last. Only for entries without an `asset` pattern (the FOSS Catalog)."""
    n = name.lower()
    if "debug" in n:
        return 3
    if "arm64" in n or "aarch64" in n:
        return 0
    if re.search(r"x86|armeabi|armv7|arm7|v7a|mips", n):
        return 2
    return 1


def github_candidates(entry, releases):
    """The matching assets of the newest release that has any. Skips drafts and pre-releases."""
    asset = re.compile(entry.get("asset", r"\.apk$"))
    for release in releases:
        if release.get("draft") or (release.get("prerelease") and not entry.get("prerelease")):
            continue
        matches = [a for a in release.get("assets", []) if asset.search(a["name"])]
        if "asset" not in entry:
            matches.sort(key=lambda a: asset_rank(a["name"]))
        if matches:
            notes = (release.get("body") or "").strip()
            return [{"url": a["browser_download_url"], "notes": notes, "size": a.get("size"),
                     "sha256": digest(a.get("digest"))} for a in matches]
    return []


def digest(value):
    """The hex SHA-256 from GitHub's "sha256:<hex>" asset digest; None for older assets without one."""
    if value and value.startswith("sha256:"):
        return value[7:].lower()
    return None


def fdroid_candidates(entry, package, files=None):
    """F-Droid's suggested version; several APKs when F-Droid builds one per ABI. [files] (file name ->
    (sha256, size), from F-Droid's index) adds each APK's SHA-256 and size where the index has them."""
    suggested = package["suggestedVersionCode"]
    name = next((p["versionName"] for p in package["packages"] if p["versionCode"] == suggested), None)
    codes = sorted({p["versionCode"] for p in package["packages"] if p["versionName"] == name} | {suggested}, reverse=True)
    found = []
    for code in codes:
        file = f"{entry['id']}_{code}.apk"
        sha, size = (files or {}).get(file, (None, None))
        found.append({"url": f"https://f-droid.org/repo/{file}", "notes": "", "sha256": sha, "size": size})
    return found


_fdroid_files, _fdroid_lock = None, threading.Lock()


def fdroid_files():
    """APK file name -> (sha256, size) for every APK in F-Droid's index, fetched once per run.
    Empty if the index can't be read: the APKs are then downloaded whole."""
    global _fdroid_files
    with _fdroid_lock:
        if _fdroid_files is None:
            try:
                started = time.monotonic()
                _fdroid_files = index_files(json.loads(http_get(FDROID_INDEX)))
                print(f"F-Droid index: {len(_fdroid_files)} APKs in {time.monotonic() - started:.0f} s")
            except (OSError, ValueError) as e:
                print(f"F-Droid index unavailable, F-Droid APKs are downloaded whole: {e}")
                _fdroid_files = {}
        return _fdroid_files


def index_files(index):
    """File name -> (sha256, size) from an F-Droid index-v2."""
    files = {}
    for package in index.get("packages", {}).values():
        for version in package.get("versions", {}).values():
            file = version.get("file") or {}
            if file.get("name") and file.get("sha256"):
                files[file["name"].lstrip("/")] = (file["sha256"].lower(), file.get("size"))
    return files


def page_candidates(entry, page):
    """Links on a download page that match the entry's `link` pattern, in page order."""
    links = []
    for match in re.finditer(entry["link"], page):
        url = urllib.parse.urljoin(entry["page"], html.unescape(match.group(0)))
        if url not in links:
            links.append(url)
    return [{"url": url, "notes": ""} for url in links]


def candidates(entry, token):
    source = entry.get("source", "github")
    if source == "github":
        api = f"https://api.github.com/repos/{entry['repo']}/releases?per_page=20"
        return github_candidates(entry, json.loads(http_get(api, "application/vnd.github+json", token)))
    if source == "fdroid":
        api = f"https://f-droid.org/api/v1/packages/{entry['id']}"
        return fdroid_candidates(entry, json.loads(http_get(api)), fdroid_files())
    if source == "page":
        page = http_get(entry["page"]).decode("utf-8", "replace")
        found = page_candidates(entry, page)
        if not found:  # say what is there, so the pattern can be fixed
            apks = sorted(set(re.findall(r'[^"\'\s<>]+\.apk', page)))
            raise Rejected("no link matches; APK links on the page: " + (", ".join(apks[:12]) or "none"))
        return found
    raise Rejected(f"unknown source {source!r}")


# --- Inspecting an APK ------------------------------------------------------------------------

def build_tool(name):
    home = os.environ.get("ANDROID_HOME") or os.environ.get("ANDROID_SDK_ROOT")
    if not home:
        sys.exit("ANDROID_HOME is not set: aapt2 and apksigner come from the Android SDK build-tools")
    found = sorted(glob.glob(os.path.join(home, "build-tools", "*", name)),
                   key=lambda p: [int(x) if x.isdigit() else 0 for x in re.split(r"[.-]", p.split(os.sep)[-2])])
    if not found:
        sys.exit(f"{name} not found in {home}/build-tools")
    return found[-1]


def parse_badging(text):
    """The parts of `aapt2 dump badging` the Catalog needs."""
    info = {"abis": [], "icons": {}, "leanback": False, "label": None}
    for line in text.splitlines():
        if line.startswith("package:"):
            info["package"] = re.search(r"name='([^']*)'", line).group(1)
            info["versionCode"] = int(re.search(r"versionCode='(\d+)'", line).group(1))
            info["versionName"] = re.search(r"versionName='([^']*)'", line).group(1)
        elif line.startswith("application-label:"):
            info["label"] = line.split(":", 1)[1].strip().strip("'")
        elif m := re.match(r"application-icon-(\d+):'([^']*)'", line):
            info["icons"][int(m.group(1))] = m.group(2)
        elif line.startswith("leanback-launchable-activity:"):
            info["leanback"] = True
        elif line.startswith("native-code:"):
            info["abis"] = re.findall(r"'([^']*)'", line)
    return info


def parse_signer(text):
    """SHA-256 of the first signer's certificate, from `apksigner verify --print-certs`."""
    # "Signer #1 certificate ...", "V2 Signer: certificate ..." (build-tools 36+), or
    # "Signer (minSdkVersion=..) certificate ..." with key rotation.
    m = re.search(r"^[^\n]*Signer\b[^\n]*? certificate SHA-256 digest: ([0-9a-fA-F]+)", text, re.MULTILINE)
    if not m:
        raise Rejected("apksigner found no signer: " + " | ".join(text.strip().splitlines()[:4]))
    return m.group(1).lower()


def dump(path):
    """Badging and resource table of the APK (or the manifest-and-resources zip) at [path]."""
    badging = subprocess.run([build_tool("aapt2"), "dump", "badging", path], capture_output=True, text=True)
    if badging.returncode != 0:
        raise Rejected("aapt2 can't read it: " + badging.stderr.strip()[:200])
    info = parse_badging(badging.stdout)
    resources = subprocess.run([build_tool("aapt2"), "dump", "resources", path], capture_output=True, text=True)
    info["resources"] = resources.stdout if resources.returncode == 0 else ""
    return info


def inspect(path):
    """Everything the checks need from a downloaded APK."""
    info = dump(path)
    signer = subprocess.run([build_tool("apksigner"), "verify", "--print-certs", path], capture_output=True, text=True)
    if signer.returncode != 0:
        raise Rejected("signature doesn't verify: " + (signer.stderr or signer.stdout).strip()[:200])
    info["signer"] = parse_signer(signer.stdout + "\n" + signer.stderr)
    return info


# --- Reading parts of an APK where it is published ---------------------------------------------

SIG_BLOCK_MAGIC = b"APK Sig Block 42"
# v3.1, v3 and v2 signature schemes, in the order Android picks the signer it reports.
SIG_SCHEMES = (0x1B93AD61, 0xF05368C0, 0x7109871A)


class RemoteApk:
    """An APK read in parts with HTTP range requests: its zip directory, single files and its APK
    Signing Block. [fetch(offset, length)] returns those bytes; [size] is the file's length."""

    def __init__(self, fetch, size):
        self.fetch, self.size = fetch, size
        tail_len = min(size, 65536 + 22)  # the end record, and a zip comment of up to 64 KiB
        tail = fetch(size - tail_len, tail_len)
        end = tail.rfind(b"PK\x05\x06")
        if end < 0 or len(tail) - end < 22:
            raise NoPartial("no zip end record")
        _, _, _, count, cd_size, cd_offset, _ = struct.unpack("<HHHHIIH", tail[end + 4:end + 22])
        if cd_offset == 0xFFFFFFFF or count == 0xFFFF:
            raise NoPartial("zip64")
        tail_start = size - tail_len
        directory = (tail[cd_offset - tail_start:cd_offset - tail_start + cd_size] if cd_offset >= tail_start
                     else fetch(cd_offset, cd_size))
        self.cd_offset, self.entries, pos = cd_offset, {}, 0
        for _ in range(count):
            if directory[pos:pos + 4] != b"PK\x01\x02":
                raise NoPartial("broken zip directory")
            method, csize = struct.unpack("<H", directory[pos + 10:pos + 12])[0], struct.unpack("<I", directory[pos + 20:pos + 24])[0]
            nlen, elen, clen = struct.unpack("<HHH", directory[pos + 28:pos + 34])
            offset = struct.unpack("<I", directory[pos + 42:pos + 46])[0]
            name = directory[pos + 46:pos + 46 + nlen].decode("utf-8", "replace")
            self.entries[name] = (method, csize, offset)
            pos += 46 + nlen + elen + clen

    def read(self, name):
        """The contents of the file [name] in the APK, or None if there is none."""
        if name not in self.entries:
            return None
        method, csize, offset = self.entries[name]
        header = self.fetch(offset, 30)
        if header[:4] != b"PK\x03\x04":
            raise NoPartial(f"broken zip entry {name}")
        nlen, elen = struct.unpack("<HH", header[26:30])
        data = self.fetch(offset + 30 + nlen + elen, csize) if csize else b""
        if method == 0:
            return data
        if method == 8:
            return zlib.decompressobj(-15).decompress(data)
        raise NoPartial(f"{name} is compressed with method {method}")

    def abis(self):
        """The ABIs the APK has native code for: its lib/<abi>/ folders."""
        return sorted({n.split("/")[1] for n in self.entries if n.startswith("lib/") and n.count("/") >= 2})

    def signer(self):
        """SHA-256 of the signer's certificate from the APK Signing Block, as apksigner prints it.
        Not verified here: Android verifies the signature when it installs the APK, and a different
        certificate fails the signer check."""
        if self.cd_offset < 32:
            raise NoPartial("no APK Signing Block")
        footer = self.fetch(self.cd_offset - 24, 24)
        if footer[8:] != SIG_BLOCK_MAGIC:
            raise NoPartial("no APK Signing Block (v1 signature only)")
        block_size = struct.unpack("<Q", footer[:8])[0]
        if block_size > self.cd_offset - 8 or block_size < 24:
            raise NoPartial("broken APK Signing Block")
        block = self.fetch(self.cd_offset - block_size - 8, block_size + 8)
        pairs, pos = {}, 8
        while pos + 12 <= len(block) - 24:
            length, scheme = struct.unpack("<QI", block[pos:pos + 12])
            pairs[scheme] = block[pos + 12:pos + 8 + length]
            pos += 8 + length
        for scheme in SIG_SCHEMES:
            if scheme in pairs:
                return hashlib.sha256(first_certificate(pairs[scheme])).hexdigest()
        raise NoPartial("no v2 or v3 signature")


def length_prefixed(buf, pos):
    """(the uint32-length-prefixed bytes at [pos], the position after them)."""
    if pos + 4 > len(buf):
        raise NoPartial("broken APK Signing Block")
    n = struct.unpack("<I", buf[pos:pos + 4])[0]
    if pos + 4 + n > len(buf):
        raise NoPartial("broken APK Signing Block")
    return buf[pos + 4:pos + 4 + n], pos + 4 + n


def first_certificate(scheme_block):
    """The first signer's first certificate (DER) in a v2/v3 signature scheme block."""
    signers, _ = length_prefixed(scheme_block, 0)
    signer, _ = length_prefixed(signers, 0)
    signed_data, _ = length_prefixed(signer, 0)
    _, pos = length_prefixed(signed_data, 0)  # digests
    certificates, _ = length_prefixed(signed_data, pos)
    certificate, _ = length_prefixed(certificates, 0)
    return certificate


def http_fetcher(url):
    """fetch(offset, length) over HTTP range requests, and the stats of what it read."""
    state = {"url": url, "bytes": 0}

    def fetch(offset, length):
        request = urllib.request.Request(state["url"], headers={
            "User-Agent": USER_AGENT, "Range": f"bytes={offset}-{offset + length - 1}"})
        try:
            with urllib.request.urlopen(request, timeout=60) as response:
                if response.status != 206:
                    raise NoPartial(f"no range requests (HTTP {response.status})")
                # GitHub redirects to a signed URL: ask it directly next time.
                state["url"] = response.geturl()
                data = response.read()
        except urllib.error.HTTPError as e:
            raise NoPartial(f"range request failed: HTTP {e.code}")
        if len(data) != length:
            raise NoPartial(f"asked for {length} bytes, got {len(data)}")
        state["bytes"] += length
        return data

    return fetch, state


def inspect_remote(apk, tmp):
    """Everything the checks need, from the parts of [apk] (a RemoteApk) that hold it: aapt2 reads a
    zip of just its manifest and resource table."""
    skeleton = os.path.join(tmp, "parts.apk")
    with zipfile.ZipFile(skeleton, "w") as z:
        for name in ("AndroidManifest.xml", "resources.arsc"):
            data = apk.read(name)
            if data is not None:
                z.writestr(name, data, zipfile.ZIP_STORED)
    try:
        info = dump(skeleton)
    except Rejected as e:
        raise NoPartial(str(e))
    info["abis"] = apk.abis()
    info["signer"] = apk.signer()
    return info


def check(entry, info, previous, abis):
    """Raises Rejected unless this APK may replace [previous] (the last good entry, or None)."""
    if entry.get("id") and info.get("package") != entry["id"]:
        raise Rejected(f"package is {info.get('package')}, expected {entry['id']}")
    if not info["leanback"] and not entry.get("home"):
        raise Rejected("no Android TV launcher entry: it would never show on the home screen")
    if info["abis"] and not set(info["abis"]) & set(abis):
        raise Rejected(f"native code for {', '.join(info['abis'])} only; the TV runs {', '.join(abis)}")
    pinned = (entry.get("signer") or (previous or {}).get("signer") or "").replace(":", "").lower()
    if pinned and info["signer"] != pinned:
        raise Rejected(f"signed with {info['signer']}, expected {pinned}")
    if previous and info["versionCode"] < previous["versionCode"]:
        raise Rejected(f"versionCode {info['versionCode']} is older than {previous['versionCode']}")


DENSITIES = {"ldpi": 120, "mdpi": 160, "tvdpi": 213, "hdpi": 240, "xhdpi": 320, "xxhdpi": 480, "xxxhdpi": 640}
RASTER = (".png", ".webp")


def resource_files(resources, wanted):
    """{density: file} of the resource that has [wanted] among its files, from `aapt2 dump resources`.

    Apps with an adaptive launcher icon list only its XML in the badging; their PNG or WebP versions
    for older Android are other configurations of the same resource, often under shortened names.
    """
    blocks, files = [], None
    for line in resources.splitlines():
        if re.match(r"\s*resource 0x[0-9a-fA-F]+ ", line):
            files = []
            blocks.append(files)
        elif files is not None and (m := re.match(r"\s*\(([^)]*)\)(?:\s*\(file\))?\s+(res/\S+)", line)):
            density = next((DENSITIES[q] for q in re.split(r"[-_]", m.group(1)) if q in DENSITIES), 160)
            files.append((density, m.group(2)))
    for block in blocks:
        if any(path == wanted for _, path in block):
            return {density: path for density, path in sorted(block)}
    return {}


def icon_files(info):
    """Launcher icon files to try, best first: raster icons from the badging, then raster versions of
    the (adaptive) icon resource."""
    found = {}
    for density, name in info["icons"].items():
        if name.lower().endswith(RASTER):
            found.setdefault(density, name)
        else:
            for d, path in resource_files(info.get("resources", ""), name).items():
                if path.lower().endswith(RASTER):
                    found.setdefault(d, path)
    return [found[d] for d in sorted(found, reverse=True)]


def zip_reader(path):
    """read(name) for a downloaded APK: the file's contents, or None if there is none."""
    def read(name):
        with zipfile.ZipFile(path) as apk:
            try:
                return apk.read(name)
            except KeyError:
                return None
    return read


def extract_icon(read, info, out_dir, app_id, prefix=""):
    """The largest PNG or WebP launcher icon, saved as icons/<prefix><id>.<ext>. None if only vectors.
    [read(name)] reads a file from the APK. [prefix] keeps the FOSS Catalog's icons apart from the
    curated Catalog's in the shared release."""
    for name in icon_files(info):
        data = read(name)
        if data is None:
            continue
        ext = os.path.splitext(name)[1].lower()
        os.makedirs(os.path.join(out_dir, "icons"), exist_ok=True)
        file = f"{prefix}{app_id}{ext}"
        with open(os.path.join(out_dir, "icons", file), "wb") as f:
            f.write(data)
        return file
    return None


# --- Putting it together ----------------------------------------------------------------------

def today():
    return datetime.date.today().isoformat()


def key(entry):
    """What an entry is known by between runs: its package name, or (FOSS Catalog entries from a
    GitHub repo, whose package name only the APK tells) its repo."""
    return entry.get("id") or f"github:{entry['repo']}"

def resolve_app(entry, previous, abis, out_dir, base_url, token, log, icon_prefix="", rejected=None):
    """The Catalog entry for one App, or raises Rejected with every candidate's reason.
    [rejected] (URL -> {"reason", "since"}) skips URLs rejected recently and records new ones."""
    found = candidates(entry, token)
    if not found:
        raise Rejected("no matching APK found")
    reasons = []
    # An entry without an icon is looked at again, so a resolver that finds more icons fills it in.
    keep = previous if previous and previous.get("icon") else None
    for candidate in found[:MAX_CANDIDATES]:
        # Same file as last time (GitHub and F-Droid URLs carry the version): keep the checked entry.
        if keep and keep.get("source_url") == candidate["url"] and entry.get("source", "github") != "page":
            return describe(entry, keep)
        if rejected is not None and candidate["url"] in rejected:
            reasons.append(f"{candidate['url']}: {rejected[candidate['url']]['reason']} (since {rejected[candidate['url']]['since']})")
            continue
        with tempfile.TemporaryDirectory() as tmp:
            try:
                found_apk = fetch_apk(candidate, tmp, log)
                info = found_apk["info"]
                check(entry, info, previous, abis)
                if previous and info["package"] != previous["id"]:
                    raise Rejected(f"package is {info['package']}, was {previous['id']}")
            except Rejected as e:
                reasons.append(f"{candidate['url']}: {e}")
                if rejected is not None:
                    rejected[candidate["url"]] = {"reason": str(e), "since": today()}
                continue
            except (OSError, http.client.HTTPException) as e:
                reasons.append(f"{candidate['url']}: {e!r}")
                continue
            if keep and keep["versionCode"] == info["versionCode"] and keep["sha256"] == found_apk["sha256"]:
                return describe(entry, keep)
            app_id = info["package"]
            try:
                icon = extract_icon(found_apk["read"], info, out_dir, app_id, icon_prefix)
            except (NoPartial, OSError) as e:
                icon = None
                log(f"{app_id}: icon unreadable: {e}")
            if not icon:
                log(f"{app_id}: no PNG or WebP launcher icon in the APK")
            log(f"{app_id}: {info['versionName']} ({info['versionCode']}) from {candidate['url']}{found_apk['how']}")
            if entry.get("id") and not entry.get("signer") and not (previous or {}).get("signer"):
                log(f"{app_id}: first seen signer {info['signer']}; pin it in catalog/apps.yaml")
            return describe(entry, {
                "id": app_id,
                "origin": key(entry),
                "label": info["label"] or app_id,
                "versionCode": info["versionCode"],
                "versionName": info["versionName"],
                "url": candidate["url"],
                "source_url": candidate["url"],
                "size": found_apk["size"],
                "sha256": found_apk["sha256"],
                "signer": info["signer"],
                "icon": f"{base_url}/{icon}" if icon else None,
                "notes": candidate["notes"][:2000],
            })
    raise Rejected("; ".join(reasons))


def fetch_apk(candidate, tmp, log):
    """{"info", "read", "size", "sha256", "how"} for a candidate APK. With a SHA-256 from its source,
    only the parts the checks need are read where it is published; otherwise it is downloaded."""
    if candidate.get("sha256"):
        fetch, stats = http_fetcher(candidate["url"])
        try:
            size = candidate.get("size") or content_length(candidate["url"])
            apk = RemoteApk(fetch, size)
            info = inspect_remote(apk, tmp)
            return {"info": info, "read": apk.read, "size": size, "sha256": candidate["sha256"],
                    "how": f" (read {stats['bytes'] // 1024} KiB of {size // 1024} KiB)"}
        except (NoPartial, OSError, http.client.HTTPException, struct.error, zlib.error) as e:
            log(f"{candidate['url']}: downloading it whole: {e}")
    path = os.path.join(tmp, "app.apk")
    download(candidate["url"], path)
    digest_hex = sha256(path)
    if candidate.get("sha256") and candidate["sha256"] != digest_hex:
        raise Rejected(f"SHA-256 is {digest_hex}, its source says {candidate['sha256']}")
    return {"info": inspect(path), "read": zip_reader(path), "size": os.path.getsize(path),
            "sha256": digest_hex, "how": " (downloaded)"}


def content_length(url):
    """The size of the file at [url], from a one-byte range request."""
    request = urllib.request.Request(url, headers={"User-Agent": USER_AGENT, "Range": "bytes=0-0"})
    try:
        with urllib.request.urlopen(request, timeout=60) as response:
            match = re.search(r"/(\d+)$", response.headers.get("Content-Range", ""))
            if response.status != 206 or not match:
                raise NoPartial(f"no range requests (HTTP {response.status})")
            return int(match.group(1))
    except urllib.error.HTTPError as e:
        raise NoPartial(f"range request failed: HTTP {e.code}")


def describe(entry, resolved):
    """[resolved] with the parts that come from apps.yaml (label, summary, category) applied."""
    app = dict(resolved)
    app["label"] = entry.get("label") or resolved.get("label") or resolved["id"]
    app["summary"] = entry.get("summary", "")
    app["category"] = entry.get("category", "Apps")
    return app


def own_icon(entry, app, catalog_dir, out_dir, base_url, prefix=""):
    """[app] with the icon apps.yaml gives it (a PNG next to apps.yaml), for Apps whose launcher icon
    is a vector the resolver can't draw. Copied into out/icons on every run, so a new one replaces it."""
    if not entry.get("icon"):
        return app
    ext = os.path.splitext(entry["icon"])[1]
    file = f"{prefix}{app['id']}{ext.lower()}"
    os.makedirs(os.path.join(out_dir, "icons"), exist_ok=True)
    with open(os.path.join(catalog_dir, entry["icon"]), "rb") as src, open(os.path.join(out_dir, "icons", file), "wb") as dst:
        dst.write(src.read())
    return {**app, "icon": f"{base_url}/{file}"}


def sha256(path):
    digest = hashlib.sha256()
    with open(path, "rb") as f:
        while chunk := f.read(1 << 20):
            digest.update(chunk)
    return digest.hexdigest()


def main():
    parser = argparse.ArgumentParser(description=__doc__.splitlines()[0])
    parser.add_argument("--catalog", default="catalog/apps.yaml", help="also where the ABIs come from")
    parser.add_argument("--awesome", nargs="?", const=awesome.LIST, metavar="URL_OR_FILE",
                        help="resolve the Awesome Android TV FOSS Apps list instead of the Catalog's Apps")
    parser.add_argument("--exclude", help="a catalog.json whose Apps to leave out (the curated Catalog)")
    parser.add_argument("--previous", help="the catalog.json published last time, if any")
    parser.add_argument("--name", default="catalog.json", help="the file to write in --out")
    parser.add_argument("--out", default="out")
    parser.add_argument("--remember-rejected", action="store_true",
                        help=f"don't download an APK URL the checks rejected in the last {REJECTED_DAYS} days again")
    parser.add_argument("--base-url", required=True, help="where out/icons/* will be published")
    args = parser.parse_args()

    import yaml  # only needed here, so the tests run without it
    with open(args.catalog) as f:
        config = yaml.safe_load(f)
    summary = []
    log = lambda line: (print(line), summary.append(f"- {line}"))  # noqa: E731
    entries = config["apps"]
    if args.awesome:
        if os.path.exists(args.awesome):
            with open(args.awesome) as f:
                markdown = f.read()
        else:
            markdown = http_get(args.awesome).decode("utf-8")
        entries, skipped = awesome.parse(markdown)
        for line in skipped:
            log(f"skipped {line}")
    previous, owners, previous_rejected = {}, {}, {}
    if args.previous and os.path.exists(args.previous):
        with open(args.previous) as f:
            last = json.load(f)
            previous_rejected = last.get("rejected", {})
            for app in last.get("apps", []):
                previous[app["id"]] = app
                # The origin a package was first published from keeps it: another repo on the list
                # can't take over a package name (and with it, Installs) by building the same one.
                owners[app["id"]] = app.get("origin") or app["id"]
                if app.get("origin"):
                    previous[app["origin"]] = app
    excluded = set()
    if args.exclude and os.path.exists(args.exclude):
        with open(args.exclude) as f:
            excluded = {app["id"] for app in json.load(f).get("apps", [])}
    abis = config.get("abis", ["arm64-v8a"])
    token = os.environ.get("GITHUB_TOKEN")
    os.makedirs(args.out, exist_ok=True)

    # Curated icons are icons/<package>.<ext>; anything else gets its own prefix, so it can't replace them.
    icon_prefix = "" if args.name == "catalog.json" else os.path.splitext(args.name)[0] + "-"
    # Remembered rejections (only with --remember-rejected: for the curated Catalog a fix to
    # apps.yaml must take effect at once). Each expires after REJECTED_DAYS, so checks get rerun.
    rejected = None
    if args.remember_rejected:
        cutoff = (datetime.date.today() - datetime.timedelta(days=REJECTED_DAYS)).isoformat()
        rejected = {url: r for url, r in previous_rejected.items() if r.get("since", "") > cutoff}

    todo = []
    for entry in entries:
        if entry.get("id") in excluded:
            log(f"{key(entry)}: already in the Catalog")
        else:
            todo.append(entry)

    def attempt(entry):
        try:
            return resolve_app(entry, previous.get(key(entry)), abis, args.out, args.base_url, token, log,
                               icon_prefix, rejected)
        except Exception as e:  # one App's failure never stops the others
            return e

    # In parallel; the results are then taken in list order, so which App wins a package is stable.
    with concurrent.futures.ThreadPoolExecutor(WORKERS) as pool:
        results = list(pool.map(attempt, todo))

    apps, failed, seen = [], [], set(excluded)
    for entry, app in zip(todo, results):
        name = key(entry)
        if isinstance(app, Exception):
            e = app
            failed.append(name)
            log(f"**{name} failed**: {e}")
            if name not in previous:
                continue
            app = describe(entry, previous[name])
            log(f"{name}: keeping {app['versionName']}")
        # An entry that names the package itself (an F-Droid link) may take it over from its old
        # origin; the signer pinned in the previous entry still has to match.
        if owners.get(app["id"], name) != name and entry.get("id") != app["id"]:
            log(f"{name}: {app['id']} belongs to {owners[app['id']]}, left out")
            continue
        if app["id"] in seen:  # listed twice, or a GitHub repo of an App already in the Catalog
            log(f"{name}: {app['id']} is already in")
            continue
        seen.add(app["id"])
        apps.append(own_icon(entry, app, os.path.dirname(args.catalog), args.out, args.base_url, icon_prefix))

    catalog = {
        "format": FORMAT,
        "generated": datetime.datetime.now(datetime.timezone.utc).strftime("%Y-%m-%dT%H:%M:%SZ"),
        "apps": apps,
    }
    if rejected is not None:
        catalog["rejected"] = dict(sorted(rejected.items()))
    with open(os.path.join(args.out, args.name), "w") as f:
        json.dump(catalog, f, indent=1, ensure_ascii=False)
        f.write("\n")
    if os.environ.get("GITHUB_STEP_SUMMARY"):
        with open(os.environ["GITHUB_STEP_SUMMARY"], "a") as f:
            f.write(f"## {args.name}\n\n" + "\n".join(summary) + "\n")
    print(f"{len(apps)} apps in {args.name}, {len(failed)} failed")
    sys.exit(1 if failed else 0)


if __name__ == "__main__":
    main()

#!/usr/bin/env python3
# SPDX-License-Identifier: Apache-2.0
"""Resolve catalog/apps.yaml into catalog.json, the Catalog Ladrok Store downloads.

With --awesome, the entries come from the Awesome Android TV FOSS Apps list instead (resolver/awesome.py),
for the Store's separate FOSS screen: each list App tries GitHub releases, then F-Droid, then
IzzyOnDroid, is pinned to the signer it first had, and failures don't fail the run (--lenient).

For each App: find its newest APK (GitHub release, F-Droid, or a download page), download it and
check it with the Android SDK tools: right package name, a TV launcher entry, an ABI the TV runs,
the same signing certificate as before, and no downgrade. An App that fails keeps its last good
entry, so one broken release never takes an App off the TV. Signing catalog.json is the workflow's
job (.github/workflows/catalog.yml), not this script's.

    python3 resolver/resolve.py --catalog catalog/apps.yaml --previous previous.json \
        --out out --base-url https://github.com/Kordal/ladrok-store/releases/download/catalog
"""
import argparse
import datetime
import glob
import hashlib
import html
import json
import os
import re
import subprocess
import sys
import tempfile
import urllib.parse
import urllib.request
import zipfile

FORMAT = 1
# Candidate APKs tried per App before giving up: a download page may link several (phone, TV, ABIs).
MAX_CANDIDATES = 6
USER_AGENT = "ladrok-store-resolver"


class Rejected(Exception):
    """This APK can't go in the Catalog; the message says why."""


# --- Fetching ---------------------------------------------------------------------------------

def http_get(url, accept=None, token=None):
    headers = {"User-Agent": USER_AGENT}
    if accept:
        headers["Accept"] = accept
    if token:
        headers["Authorization"] = "Bearer " + token
    with urllib.request.urlopen(urllib.request.Request(url, headers=headers), timeout=60) as response:
        return response.read()


def download(url, path):
    request = urllib.request.Request(url, headers={"User-Agent": USER_AGENT})
    with urllib.request.urlopen(request, timeout=300) as response, open(path, "wb") as out:
        while chunk := response.read(1 << 20):
            out.write(chunk)


# --- Sources: each yields candidate APKs, newest first, as {"url", "notes"} --------------------

def github_candidates(entry, releases):
    """The matching assets of the newest release that has any. Skips drafts and pre-releases."""
    asset = re.compile(entry.get("asset", r"\.apk$"))
    for release in releases:
        if release.get("draft") or (release.get("prerelease") and not entry.get("prerelease")):
            continue
        matches = [a for a in release.get("assets", []) if asset.search(a["name"])]
        if "asset" not in entry:
            matches = by_abi(matches)
        if matches:
            notes = (release.get("body") or "").strip()
            return [{"url": a["browser_download_url"], "notes": notes} for a in matches]
    return []


OTHER_ABI = re.compile(r"x86|armeabi|armv7|arm-v7|v7a|arm32|mips", re.IGNORECASE)
DEBUG = re.compile(r"debug|unsigned", re.IGNORECASE)


def by_abi(assets):
    """Assets for any list app whose entry names no asset pattern: arm64 builds first, then builds
    for every ABI; builds only for other ABIs, debug and unsigned builds are left out."""
    def rank(asset):
        return 0 if re.search(r"arm64|aarch64|v8a", asset["name"], re.IGNORECASE) else 1
    keep = [a for a in assets if not DEBUG.search(a["name"])
            and (re.search(r"arm64|aarch64|v8a", a["name"], re.IGNORECASE) or not OTHER_ABI.search(a["name"]))]
    return sorted(keep, key=rank)


def fdroid_candidates(entry, package, repo="https://f-droid.org/repo"):
    """F-Droid's suggested version; several APKs when F-Droid builds one per ABI."""
    suggested = package["suggestedVersionCode"]
    name = next((p["versionName"] for p in package["packages"] if p["versionCode"] == suggested), None)
    codes = sorted({p["versionCode"] for p in package["packages"] if p["versionName"] == name} | {suggested}, reverse=True)
    return [{"url": f"{repo}/{entry['id']}_{code}.apk", "notes": ""} for code in codes]


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
        return fdroid_candidates(entry, json.loads(http_get(api)))
    if source == "izzy":  # IzzyOnDroid: an F-Droid repository with the same API
        api = f"https://apt.izzysoft.de/fdroid/api/v1/packages/{entry['id']}"
        return fdroid_candidates(entry, json.loads(http_get(api)), "https://apt.izzysoft.de/fdroid/repo")
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


def inspect(path):
    badging = subprocess.run([build_tool("aapt2"), "dump", "badging", path], capture_output=True, text=True)
    if badging.returncode != 0:
        raise Rejected("aapt2 can't read it: " + badging.stderr.strip()[:200])
    signer = subprocess.run([build_tool("apksigner"), "verify", "--print-certs", path], capture_output=True, text=True)
    if signer.returncode != 0:
        raise Rejected("signature doesn't verify: " + (signer.stderr or signer.stdout).strip()[:200])
    info = parse_badging(badging.stdout)
    resources = subprocess.run([build_tool("aapt2"), "dump", "resources", path], capture_output=True, text=True)
    info["resources"] = resources.stdout if resources.returncode == 0 else ""
    info["signer"] = parse_signer(signer.stdout + "\n" + signer.stderr)
    return info


def check(entry, info, previous, abis):
    """Raises Rejected unless this APK may replace [previous] (the last good entry, or None)."""
    if entry.get("id") and info.get("package") != entry["id"]:
        raise Rejected(f"package is {info.get('package')}, expected {entry['id']}")
    if not info["leanback"]:
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


def extract_icon(path, info, out_dir, app_id):
    """The largest PNG or WebP launcher icon, saved as icons/<id>.<ext>. None if only vectors."""
    with zipfile.ZipFile(path) as apk:
        for name in icon_files(info):
            try:
                data = apk.read(name)
            except KeyError:
                continue
            ext = os.path.splitext(name)[1].lower()
            os.makedirs(os.path.join(out_dir, "icons"), exist_ok=True)
            file = f"{app_id}{ext}"
            with open(os.path.join(out_dir, "icons", file), "wb") as f:
                f.write(data)
            return file
    return None


# --- Putting it together ----------------------------------------------------------------------

def key(entry):
    """What identifies an entry across runs: its package name, or the key the list importer gave it."""
    return entry.get("key") or entry["id"]


def resolve_app(entry, previous, abis, out_dir, base_url, token, log):
    """The Catalog entry for one App, from the first of its sources with an APK that passes the
    checks (an apps.yaml entry is its own one source). Raises Rejected with every source's reason."""
    reasons = []
    for source in entry.get("sources") or [entry]:
        attempt = entry if source is entry else {**entry, **source}
        try:
            return resolve_source(attempt, previous, abis, out_dir, base_url, token, log)
        except Exception as e:  # noqa: BLE001 (a source that is down or gone: try the next one)
            reasons.append(f"{source.get('source', 'github')}: {e}" if source is not entry else str(e))
    raise Rejected("; ".join(reasons))


def resolve_source(entry, previous, abis, out_dir, base_url, token, log):
    """The Catalog entry for one App from [entry]'s source, or raises Rejected with every candidate's reason."""
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
        with tempfile.TemporaryDirectory() as tmp:
            apk = os.path.join(tmp, "app.apk")
            try:
                download(candidate["url"], apk)
                info = inspect(apk)
                check(entry, info, previous, abis)
            except Rejected as e:
                reasons.append(f"{candidate['url']}: {e}")
                continue
            except OSError as e:
                reasons.append(f"{candidate['url']}: {e}")
                continue
            if keep and keep["versionCode"] == info["versionCode"] and keep["sha256"] == sha256(apk):
                return describe(entry, keep)
            package = info["package"]
            icon = extract_icon(apk, info, out_dir, package)
            if not icon:
                log(f"{package}: no PNG or WebP launcher icon in the APK")
            log(f"{package}: {info['versionName']} ({info['versionCode']}) from {candidate['url']}")
            # List Apps are pinned on first sight by the previous catalog; only apps.yaml needs a hand.
            if not entry.get("key") and not entry.get("signer") and not (previous or {}).get("signer"):
                log(f"{package}: first seen signer {info['signer']}; pin it in catalog/apps.yaml")
            return describe(entry, {
                "key": key(entry),
                "id": package,
                "label": info["label"] or package,
                "versionCode": info["versionCode"],
                "versionName": info["versionName"],
                "url": candidate["url"],
                "source_url": candidate["url"],
                "size": os.path.getsize(apk),
                "sha256": sha256(apk),
                "signer": info["signer"],
                "icon": f"{base_url}/{icon}" if icon else None,
                "notes": candidate["notes"][:2000],
            })
    raise Rejected("; ".join(reasons))


def describe(entry, resolved):
    """[resolved] with the parts that come from apps.yaml (label, summary, category) applied."""
    app = dict(resolved)
    app["label"] = entry.get("label") or resolved.get("label") or resolved["id"]
    app["summary"] = entry.get("summary", "")
    app["category"] = entry.get("category", "Apps")
    return app


def sha256(path):
    digest = hashlib.sha256()
    with open(path, "rb") as f:
        while chunk := f.read(1 << 20):
            digest.update(chunk)
    return digest.hexdigest()


def main():
    parser = argparse.ArgumentParser(description=__doc__.splitlines()[0])
    parser.add_argument("--catalog", default="catalog/apps.yaml")
    parser.add_argument("--awesome", metavar="URL",
                        help="build the entries from this Awesome Android TV FOSS Apps README instead of --catalog")
    parser.add_argument("--name", default="catalog", help="writes <out>/<name>.json")
    parser.add_argument("--lenient", action="store_true", help="exit 0 even if some Apps fail")
    parser.add_argument("--previous", help="the catalog.json published last time, if any")
    parser.add_argument("--out", default="out")
    parser.add_argument("--base-url", required=True, help="where out/icons/* will be published")
    args = parser.parse_args()

    if args.awesome:
        import awesome
        entries, skipped = awesome.parse(http_get(args.awesome).decode("utf-8"))
        config = {"apps": entries}
    else:
        import yaml  # only needed here, so the tests run without it
        with open(args.catalog) as f:
            config = yaml.safe_load(f)
        skipped = []
    previous = {}
    if args.previous and os.path.exists(args.previous):
        with open(args.previous) as f:
            previous = {app.get("key") or app["id"]: app for app in json.load(f).get("apps", [])}
    abis = config.get("abis", ["arm64-v8a"])
    token = os.environ.get("GITHUB_TOKEN")
    os.makedirs(args.out, exist_ok=True)
    summary = []
    log = lambda line: (print(line), summary.append(f"- {line}"))  # noqa: E731

    for name, reason in skipped:
        log(f"{name}: left out ({reason})")
    apps, failed = [], []
    for entry in config["apps"]:
        k = key(entry)
        try:
            apps.append(resolve_app(entry, previous.get(k), abis, args.out, args.base_url, token, log))
        except Exception as e:  # one App's failure never stops the others
            failed.append(k)
            log(f"**{entry.get('label') or k} failed**: {e}")
            if k in previous:
                apps.append(describe(entry, previous[k]))
                log(f"{k}: keeping {previous[k]['versionName']}")
    # Two list entries can turn out to be the same package; the Store shows each package once.
    seen = set()
    for app in list(apps):
        if app["id"] in seen:
            apps.remove(app)
            log(f"{app['label']}: same package as an earlier App ({app['id']}), left out")
        seen.add(app["id"])

    catalog = {
        "format": FORMAT,
        "generated": datetime.datetime.now(datetime.timezone.utc).strftime("%Y-%m-%dT%H:%M:%SZ"),
        "apps": apps,
    }
    with open(os.path.join(args.out, args.name + ".json"), "w") as f:
        json.dump(catalog, f, indent=1, ensure_ascii=False)
        f.write("\n")
    if os.environ.get("GITHUB_STEP_SUMMARY"):
        with open(os.environ["GITHUB_STEP_SUMMARY"], "a") as f:
            f.write(f"## {args.name}.json\n\n" + "\n".join(summary) + "\n")
    print(f"{len(apps)} apps in {args.name}.json, {len(failed)} failed")
    sys.exit(1 if failed and not args.lenient else 0)


if __name__ == "__main__":
    main()

# SPDX-License-Identifier: Apache-2.0
import os
import sys

import pytest

sys.path.insert(0, os.path.join(os.path.dirname(__file__), ".."))
import resolve  # noqa: E402

BADGING = """package: name='com.nuvio.tv' versionCode='1203' versionName='1.2.3' platformBuildVersionName='16'
sdkVersion:'26'
application-label:'Nuvio'
application-icon-160:'res/mipmap-mdpi/ic_launcher.png'
application-icon-640:'res/mipmap-anydpi/ic_launcher.xml'
application-icon-480:'res/mipmap-xxhdpi/ic_launcher.webp'
leanback-launchable-activity: name='com.nuvio.tv.MainActivity'  label='Nuvio' icon=''
native-code: 'arm64-v8a'
"""

SIGNER = """Signer #1 certificate DN: CN=Nuvio
Signer #1 certificate SHA-256 digest: AABBCC
Signer #1 certificate SHA-1 digest: 0011
"""


def info(**changes):
    base = resolve.parse_badging(BADGING)
    base["signer"] = "aabbcc"
    base.update(changes)
    return base


def test_parse_badging():
    i = resolve.parse_badging(BADGING)
    assert i["package"] == "com.nuvio.tv"
    assert i["versionCode"] == 1203
    assert i["versionName"] == "1.2.3"
    assert i["label"] == "Nuvio"
    assert i["leanback"] is True
    assert i["abis"] == ["arm64-v8a"]
    assert i["icons"][480] == "res/mipmap-xxhdpi/ic_launcher.webp"


def test_parse_signer():
    assert resolve.parse_signer(SIGNER) == "aabbcc"
    with pytest.raises(resolve.Rejected):
        resolve.parse_signer("nothing here")


ENTRY = {"id": "com.nuvio.tv"}
ABIS = ["arm64-v8a"]


def test_check_accepts_a_good_apk():
    resolve.check(ENTRY, info(), None, ABIS)
    resolve.check(ENTRY, info(abis=[]), None, ABIS)  # no native code runs anywhere


@pytest.mark.parametrize("changes, previous, why", [
    ({"package": "com.evil"}, None, "package"),
    ({"leanback": False}, None, "TV launcher"),
    ({"abis": ["x86_64"]}, None, "native code"),
    ({}, {"signer": "ddeeff", "versionCode": 1}, "signed with"),
    ({}, {"signer": "aabbcc", "versionCode": 2000}, "older"),
])
def test_check_rejects(changes, previous, why):
    with pytest.raises(resolve.Rejected, match=why):
        resolve.check(ENTRY, info(**changes), previous, ABIS)


def test_check_pinned_signer_ignores_colons_and_case():
    resolve.check({"id": "com.nuvio.tv", "signer": "AA:BB:CC"}, info(), None, ABIS)
    with pytest.raises(resolve.Rejected):
        resolve.check({"id": "com.nuvio.tv", "signer": "AA:BB:CD"}, info(), None, ABIS)


def test_github_skips_drafts_prereleases_and_releases_without_a_match():
    releases = [
        {"draft": True, "assets": [{"name": "app-arm64-v8a.apk", "browser_download_url": "draft"}]},
        {"prerelease": True, "assets": [{"name": "app-arm64-v8a.apk", "browser_download_url": "beta"}]},
        {"assets": [{"name": "source.zip", "browser_download_url": "zip"}]},
        {"body": "Fixes", "assets": [
            {"name": "app-armeabi-v7a.apk", "browser_download_url": "v7"},
            {"name": "app-arm64-v8a.apk", "browser_download_url": "v8", "size": 7, "digest": "sha256:ABC"},
        ]},
    ]
    found = resolve.github_candidates({"asset": r"arm64-v8a.*\.apk$"}, releases)
    assert found == [{"url": "v8", "notes": "Fixes", "size": 7, "sha256": "abc"}]
    beta = resolve.github_candidates({"asset": r"arm64-v8a.*\.apk$", "prerelease": True}, releases)
    assert beta[0]["url"] == "beta"


def test_fdroid_suggested_version_and_its_abi_builds():
    package = {"suggestedVersionCode": 12, "packages": [
        {"versionName": "1.2", "versionCode": 13},  # newer, not suggested yet
        {"versionName": "1.1", "versionCode": 12},
        {"versionName": "1.1", "versionCode": 11},
        {"versionName": "1.0", "versionCode": 10},
    ]}
    urls = [c["url"] for c in resolve.fdroid_candidates({"id": "com.tailscale.ipn"}, package)]
    assert urls == ["https://f-droid.org/repo/com.tailscale.ipn_12.apk", "https://f-droid.org/repo/com.tailscale.ipn_11.apk"]


def test_page_links_in_order_unique_and_absolute():
    page = '<a href="/dl/tv.apk">TV</a> <a href="https://cdn.example.com/a.apk?x=1&amp;y=2">x</a> <a href="/dl/tv.apk">again</a>'
    entry = {"page": "https://example.com/downloads", "link": r'(?<=href=")[^"]+\.apk[^"]*'}
    urls = [c["url"] for c in resolve.page_candidates(entry, page)]
    assert urls == ["https://example.com/dl/tv.apk", "https://cdn.example.com/a.apk?x=1&y=2"]


def test_describe_takes_label_summary_category_from_yaml():
    app = resolve.describe({"id": "x", "summary": "S", "category": "Video"}, {"id": "x", "label": "From APK"})
    assert (app["label"], app["summary"], app["category"]) == ("From APK", "S", "Video")
    assert resolve.describe({"id": "x", "label": "Mine"}, {"label": "From APK"})["label"] == "Mine"


def test_parse_signer_with_key_rotation():
    text = "Signer (minSdkVersion=33, maxSdkVersion=2147483647) certificate SHA-256 digest: DDEE\n"
    assert resolve.parse_signer(text) == "ddee"


def test_parse_signer_build_tools_36_format():
    text = ("V2 Signer: certificate DN: CN=NuvioTV\n"
            "V2 Signer: certificate SHA-256 digest: 1CBA06\n"
            "V2 Signer: certificate SHA-1 digest: c8f5\n")
    assert resolve.parse_signer(text) == "1cba06"


RESOURCES = """Binary APK
Package name=com.stremio.one id=7f
  type mipmap id=0d entryCount=2
    resource 0x7f0d0000 mipmap/ic_banner
      () (file) res/a1.png type=PNG
    resource 0x7f0d0001 mipmap/ic_launcher
      (mdpi-v4) (file) res/Xy.png type=PNG
      (xxxhdpi-v4) (file) res/Qz.webp type=PNG
      (hdpi-v4) (file) res/Ab.png type=PNG
      (anydpi-v26) (file) res/Mk.xml type=XML
"""


def test_adaptive_icon_falls_back_to_its_raster_versions():
    i = info(icons={160: "res/Mk.xml", 640: "res/Mk.xml", 65534: "res/Mk.xml"}, resources=RESOURCES)
    assert resolve.icon_files(i) == ["res/Qz.webp", "res/Ab.png", "res/Xy.png"]


def test_raster_badging_icons_come_first_largest_first():
    assert resolve.icon_files(info(resources="")) == ["res/mipmap-xxhdpi/ic_launcher.webp", "res/mipmap-mdpi/ic_launcher.png"]


def test_vector_only_icon_has_no_files():
    i = info(icons={640: "res/Mk.xml"}, resources="    resource 0x7f0d0001 mipmap/ic_launcher\n      (anydpi-v26) (file) res/Mk.xml type=XML\n")
    assert resolve.icon_files(i) == []


def test_check_without_a_package_name_takes_the_apks():
    resolve.check({"repo": "o/r"}, info(package="org.any.tv"), None, ABIS)


def test_github_ranks_assets_without_a_pattern():
    releases = [{"assets": [
        {"name": "app-debug.apk", "browser_download_url": "debug"},
        {"name": "app-x86_64-release.apk", "browser_download_url": "x86"},
        {"name": "app-universal-release.apk", "browser_download_url": "universal"},
        {"name": "app-arm64-v8a-release.apk", "browser_download_url": "arm64"},
    ]}]
    urls = [c["url"] for c in resolve.github_candidates({"repo": "o/r"}, releases)]
    assert urls == ["arm64", "universal", "x86", "debug"]


def test_key_is_the_package_name_or_the_repo():
    assert resolve.key({"id": "org.x"}) == "org.x"
    assert resolve.key({"repo": "o/r", "source": "github"}) == "github:o/r"


def test_check_home_app_needs_no_tv_launcher_entry():
    resolve.check({"id": "com.nuvio.tv", "home": True}, info(leanback=False), None, ["arm64-v8a"])


def test_describe_without_a_label_or_id_falls_back_to_the_package():
    assert resolve.describe({"repo": "o/r"}, {"id": "org.any.tv", "label": ""})["label"] == "org.any.tv"


def test_icon_prefix_keeps_foss_icons_apart(tmp_path):
    import zipfile
    apk = tmp_path / "app.apk"
    with zipfile.ZipFile(apk, "w") as z:
        z.writestr("res/mipmap-xxhdpi/ic_launcher.webp", b"icon")
    i = info()
    assert resolve.extract_icon(resolve.zip_reader(str(apk)), i, str(tmp_path), "com.nuvio.tv", "foss-") == "foss-com.nuvio.tv.webp"
    assert (tmp_path / "icons" / "foss-com.nuvio.tv.webp").read_bytes() == b"icon"


def test_remembered_rejections_are_not_downloaded_again(monkeypatch):
    monkeypatch.setattr(resolve, "candidates", lambda entry, token: [{"url": "https://x/old.apk", "notes": ""}])
    downloads = []
    monkeypatch.setattr(resolve, "download", lambda url, path: downloads.append(url))
    rejected = {"https://x/old.apk": {"reason": "no Android TV launcher entry", "since": "2026-10-16"}}
    with pytest.raises(resolve.Rejected, match="no Android TV launcher entry"):
        resolve.resolve_app({"repo": "o/r"}, None, ABIS, "out", "", None, print, "foss-", rejected)
    assert downloads == []


def test_new_rejections_are_remembered(monkeypatch):
    monkeypatch.setattr(resolve, "candidates", lambda entry, token: [{"url": "https://x/new.apk", "notes": ""}])
    monkeypatch.setattr(resolve, "download", lambda url, path: open(path, "wb").write(b"apk"))
    monkeypatch.setattr(resolve, "inspect", lambda path: info(leanback=False))
    rejected = {}
    with pytest.raises(resolve.Rejected):
        resolve.resolve_app({"repo": "o/r"}, None, ABIS, "out", "", None, print, "foss-", rejected)
    assert rejected["https://x/new.apk"]["reason"].startswith("no Android TV launcher entry")


# --- Reading parts of an APK ---

def fake_apk(signing_block=True, scheme=0x7109871A):
    """A zip with a manifest, a native lib and (optionally) an APK Signing Block whose first
    certificate is b"CERT", and fetch(offset, length) over its bytes."""
    import io
    import struct
    import zipfile
    buf = io.BytesIO()
    with zipfile.ZipFile(buf, "w") as z:
        z.writestr("AndroidManifest.xml", b"manifest" * 100, zipfile.ZIP_DEFLATED)
        z.writestr("resources.arsc", b"table", zipfile.ZIP_STORED)
        z.writestr("lib/arm64-v8a/libx.so", b"so")
    data = buf.getvalue()
    if signing_block:
        lp = lambda b: struct.pack("<I", len(b)) + b  # noqa: E731
        signed_data = lp(lp(b"digest")) + lp(lp(b"CERT"))
        value = lp(lp(lp(signed_data) + lp(b"sigs") + lp(b"key")))
        pair = struct.pack("<QI", len(value) + 4, scheme) + value
        size = len(pair) + 24
        block = struct.pack("<Q", size) + pair + struct.pack("<Q", size) + b"APK Sig Block 42"
        end = data.rfind(b"PK\x05\x06")
        cd_offset = struct.unpack("<I", data[end + 16:end + 20])[0]
        data = (data[:cd_offset] + block + data[cd_offset:end + 16]
                + struct.pack("<I", cd_offset + len(block)) + data[end + 20:])
    fetched = []

    def fetch(offset, length):
        fetched.append((offset, length))
        return data[offset:offset + length]
    return data, fetch, fetched


def test_remote_apk_reads_files_abis_and_signer():
    import hashlib
    data, fetch, fetched = fake_apk()
    apk = resolve.RemoteApk(fetch, len(data))
    assert apk.read("AndroidManifest.xml") == b"manifest" * 100
    assert apk.read("resources.arsc") == b"table"
    assert apk.read("res/missing.png") is None
    assert apk.abis() == ["arm64-v8a"]
    assert apk.signer() == hashlib.sha256(b"CERT").hexdigest()


def test_remote_apk_prefers_the_v3_signer():
    import hashlib
    data, fetch, _ = fake_apk(scheme=0xF05368C0)
    assert resolve.RemoteApk(fetch, len(data)).signer() == hashlib.sha256(b"CERT").hexdigest()


def test_remote_apk_without_a_signing_block_is_downloaded_instead():
    data, fetch, _ = fake_apk(signing_block=False)
    with pytest.raises(resolve.NoPartial):
        resolve.RemoteApk(fetch, len(data)).signer()


def test_fetch_apk_reads_parts_when_the_source_has_a_sha256(monkeypatch, tmp_path):
    data, fetch, _ = fake_apk()
    monkeypatch.setattr(resolve, "http_fetcher", lambda url: (fetch, {"bytes": 0}))
    monkeypatch.setattr(resolve, "dump", lambda path: info())
    monkeypatch.setattr(resolve, "download", lambda url, path: pytest.fail("downloaded"))
    found = resolve.fetch_apk({"url": "u", "sha256": "ab", "size": len(data)}, str(tmp_path), print)
    assert found["sha256"] == "ab" and found["size"] == len(data)
    assert found["info"]["abis"] == ["arm64-v8a"]


def test_fetch_apk_downloads_whole_when_parts_fail(monkeypatch, tmp_path):
    data, fetch, _ = fake_apk(signing_block=False)
    monkeypatch.setattr(resolve, "http_fetcher", lambda url: (fetch, {"bytes": 0}))
    monkeypatch.setattr(resolve, "dump", lambda path: info())
    monkeypatch.setattr(resolve, "download", lambda url, path: open(path, "wb").write(data))
    monkeypatch.setattr(resolve, "inspect", lambda path: info())
    import hashlib
    sha = hashlib.sha256(data).hexdigest()
    assert resolve.fetch_apk({"url": "u", "sha256": sha, "size": len(data)}, str(tmp_path), print)["sha256"] == sha
    with pytest.raises(resolve.Rejected, match="its source says"):
        resolve.fetch_apk({"url": "u", "sha256": "00", "size": len(data)}, str(tmp_path), print)


def test_fdroid_index_gives_sha256_and_size():
    index = {"packages": {"a.b": {"versions": {"x": {"file": {"name": "/a.b_12.apk", "sha256": "AB", "size": 5}}}}}}
    files = resolve.index_files(index)
    assert files == {"a.b_12.apk": ("ab", 5)}
    package = {"suggestedVersionCode": 12, "packages": [{"versionName": "1", "versionCode": 12}]}
    found = resolve.fdroid_candidates({"id": "a.b"}, package, files)
    assert found[0]["sha256"] == "ab" and found[0]["size"] == 5


def test_fetch_apk_downloads_whole_when_a_range_request_drops(monkeypatch, tmp_path):
    import hashlib
    import http.client
    data, _, _ = fake_apk()

    def dropped(offset, length):
        raise http.client.IncompleteRead(b"")
    monkeypatch.setattr(resolve, "http_fetcher", lambda url: (dropped, {"bytes": 0}))
    monkeypatch.setattr(resolve, "download", lambda url, path: open(path, "wb").write(data))
    monkeypatch.setattr(resolve, "inspect", lambda path: info())
    sha = hashlib.sha256(data).hexdigest()
    found = resolve.fetch_apk({"url": "u", "sha256": sha, "size": len(data)}, str(tmp_path), print)
    assert found["how"] == " (downloaded)"


def test_own_icon_from_apps_yaml_replaces_none(tmp_path):
    (tmp_path / "icons").mkdir()
    (tmp_path / "icons" / "store.png").write_bytes(b"png")
    out = tmp_path / "out"
    app = resolve.own_icon({"icon": "icons/store.png"}, {"id": "tv.x", "icon": None}, str(tmp_path), str(out), "https://b")
    assert app["icon"] == "https://b/tv.x.png"
    assert (out / "icons" / "tv.x.png").read_bytes() == b"png"
    assert resolve.own_icon({}, {"id": "tv.x", "icon": None}, str(tmp_path), str(out), "https://b")["icon"] is None

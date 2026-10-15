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
            {"name": "app-arm64-v8a.apk", "browser_download_url": "v8"},
        ]},
    ]
    found = resolve.github_candidates({"asset": r"arm64-v8a.*\.apk$"}, releases)
    assert found == [{"url": "v8", "notes": "Fixes"}]
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

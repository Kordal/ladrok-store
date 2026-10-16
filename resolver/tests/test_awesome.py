# SPDX-License-Identifier: Apache-2.0
import os
import sys

sys.path.insert(0, os.path.join(os.path.dirname(__file__), ".."))
import awesome  # noqa: E402

LIST = """# Awesome Android TV FOSS Apps

## Table of Contents
- **Not:** an app [[Source](https://github.com/a/b)]

## Apps

### Video Players
- **Kodi:** Media center. [[Source](https://github.com/xbmc/xbmc)] [[F-Droid](https://f-droid.org/packages/org.xbmc.kodi/)] 📺
- **Rooted:** Needs root 🤖 [[Source](https://github.com/r/oot)]
- **PlayOnly:** Only on Play [[Play](https://play.google.com/store/apps/details?id=a.b)]

### Tools
- **Izzy:** From Izzy [[IzzyOnDroid](https://apt.izzysoft.de/fdroid/index/apk/com.izzy.app)]
- **Mislabeled:** Wrong link [[F-Droid](https://f-droid.org/en/packages/org.xbmc.kodi/)] [[Source](https://github.com/m/l.git)]

## License
- **Nope:** [[Source](https://github.com/c/d)]
"""


def test_parse():
    entries, skipped = awesome.parse(LIST)
    by_label = {e["label"]: e for e in entries}
    assert list(by_label) == ["Kodi", "Izzy", "Mislabeled"]

    kodi = by_label["Kodi"]
    assert kodi["key"] == kodi["id"] == "org.xbmc.kodi"
    assert kodi["category"] == "Video Players"
    assert kodi["summary"] == "Media center."
    assert kodi["sources"] == [{"source": "github", "repo": "xbmc/xbmc"}, {"source": "fdroid", "id": "org.xbmc.kodi"}]

    assert by_label["Izzy"]["sources"] == [{"source": "izzy", "id": "com.izzy.app"}]
    # Kodi's package on another app is a mislabeled link: dropped, the app is keyed by its repository.
    assert by_label["Mislabeled"]["key"] == "github:m/l"
    assert "id" not in by_label["Mislabeled"]

    assert dict(skipped) == {"Rooted": "needs root", "PlayOnly": "no APK on GitHub, F-Droid or IzzyOnDroid"}


def test_real_list_parses():
    path = os.environ.get("AWESOME_MD")
    if not path:
        return
    with open(path) as f:
        entries, _ = awesome.parse(f.read())
    assert len(entries) > 50
    assert len({e["key"] for e in entries}) == len(entries)

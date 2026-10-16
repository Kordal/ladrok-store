# SPDX-License-Identifier: Apache-2.0
import os
import sys

sys.path.insert(0, os.path.join(os.path.dirname(__file__), ".."))
import awesome  # noqa: E402

LIST = """> A curated list

## Table of Contents

- [Apps](#apps)

## Apps

### Media Player

- **Kodi:** A media player [[Source](https://github.com/xbmc/xbmc)] [[F-Droid](https://f-droid.org/en/packages/org.xbmc.kodi/)] [[Google Play](https://play.google.com/store/apps/details?id=org.xbmc.kodi)]
- **Ghosten Player:** A video player for Jellyfin/Emby servers [[Source](https://github.com/GhostenEditor/Ghosten-Player)] 🛑

### Launcher

- **FLauncher:** Alternative launcher [[Source](https://gitlab.com/flauncher/flauncher)] [[Google Play](https://play.google.com/store/apps/details?id=me.efesser.flauncher)]

### Streaming - Self-hosted

- **Jellyfin 3rd Party Clients:** [Awesome Jellyfin](https://github.com/awesome-jellyfin/awesome-jellyfin/blob/main/CLIENTS.md#android-tv)
- **Tubular:** A fork of NewPipe [[Source](https://github.com/polymorphicshade/Tubular.git)] [[IzzyOnDroid](https://apt.izzysoft.de/fdroid/index/apk/org.polymorphicshade.tubular)]

## Contributing

- **Not an app:** [[Source](https://github.com/o/r)]
"""


def test_parse():
    entries, skipped = awesome.parse(LIST)
    assert entries == [
        {"label": "Kodi", "summary": "A media player", "category": "Media Player", "source": "fdroid", "id": "org.xbmc.kodi"},
        {"label": "Ghosten Player", "summary": "A video player for Jellyfin/Emby servers", "category": "Media Player",
         "source": "github", "repo": "GhostenEditor/Ghosten-Player"},
        {"label": "Tubular", "summary": "A fork of NewPipe", "category": "Streaming - Self-hosted",
         "source": "github", "repo": "polymorphicshade/Tubular"},
    ]
    assert skipped == ["FLauncher: no F-Droid or GitHub release to fetch from"]

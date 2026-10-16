#!/usr/bin/env python3
# SPDX-License-Identifier: Apache-2.0
"""Screenshots of Ladrok Store on a running Android TV emulator: the main screen, an App's page
and its hold-OK menu, a search with results from both Catalogs, and the FOSS screen. Run by .github/workflows/screenshots.yml.

    python3 tools/screenshots.py LadrokStore.apk out/
"""
import html
import os
import re
import subprocess
import sys
import time

PACKAGE = "tv.ladrok.store"


def adb(*args, out=None):
    result = subprocess.run(["adb", *args], capture_output=True, check=True)
    if out:
        with open(out, "wb") as f:
            f.write(result.stdout)
    return result.stdout.decode("utf-8", "replace")


def shot(out_dir, name):
    adb("exec-out", "screencap", "-p", out=os.path.join(out_dir, name))
    print(f"saved {name}")


def screen_text():
    adb("shell", "uiautomator", "dump", "/sdcard/ui.xml")
    return adb("shell", "cat", "/sdcard/ui.xml")


def focused_text():
    """The text inside the focused node, or ''."""
    ui = screen_text()
    m = re.search(r'<node [^>]*focused="true"[^>]*>(.*?)</node>|<node [^>]*focused="true"[^>]*/>', ui, re.S)
    if not m:
        return ""
    # An App's row names itself in content-desc; text rows in text.
    return " ".join(re.findall(r'(?:text|content-desc)="([^"]+)"', m.group(0)))


def wait_for(text, seconds=90):
    """The centre (x, y) of the first node whose text starts with [text], once it shows."""
    deadline = time.time() + seconds
    while time.time() < deadline:
        try:
            ui = screen_text()
        except subprocess.CalledProcessError:
            ui = ""
        for node in re.finditer(r'<node [^>]*>', ui):
            n = node.group(0)
            if re.search(r'(text|content-desc)="' + re.escape(html.escape(text, quote=True)), n):
                x1, y1, x2, y2 = map(int, re.search(r'bounds="\[(\d+),(\d+)\]\[(\d+),(\d+)\]"', n).groups())
                return (x1 + x2) // 2, (y1 + y2) // 2
        time.sleep(3)
    sys.exit(f"'{text}' never showed up on screen")


def press(key, times=1, long=False):
    for _ in range(times):
        adb("shell", "input", "keyevent", *(["--longpress"] if long else []), key)
        time.sleep(1)


def focus_on(text, key="KEYCODE_DPAD_DOWN", tries=16):
    """Presses [key] until the focused node's text starts with [text], like the remote."""
    for _ in range(tries):
        if focused_text().startswith(text):
            return
        press(key)
    sys.exit(f"couldn't move focus to {text} (focus is on '{focused_text()}')")


def main():
    apk, out_dir = sys.argv[1], sys.argv[2]
    os.makedirs(out_dir, exist_ok=True)
    adb("install", "-r", apk)
    adb("shell", "am", "start", "-n", f"{PACKAGE}/.StoreActivity")
    wait_for("Jellyfin")  # the curated Catalog has loaded
    time.sleep(3)  # icons
    shot(out_dir, "1-store.png")

    # Down the list to Jellyfin, then hold OK for its menu.
    focus_on("Jellyfin")
    time.sleep(2)
    shot(out_dir, "2-app-page.png")
    press("KEYCODE_DPAD_CENTER", long=True)
    time.sleep(2)
    shot(out_dir, "3-hold-ok-menu.png")
    press("KEYCODE_BACK")

    # The remote's search key opens Search; type a word that matches curated and FOSS Apps.
    press("KEYCODE_SEARCH")
    wait_for("App name or what it does", 30)
    adb("shell", "input", "text", "movies")
    wait_for("Nuvio", 30)
    time.sleep(2)
    shot(out_dir, "4-search-with-keyboard.png")
    press("KEYCODE_BACK")  # closes the on-screen keyboard
    press("KEYCODE_DPAD_DOWN")  # into the results
    time.sleep(2)
    shot(out_dir, "5-search-results.png")

    press("KEYCODE_BACK")  # closes Search
    wait_for("FOSS apps", 30)
    focus_on("FOSS apps")
    press("KEYCODE_DPAD_CENTER")
    wait_for("Watch & listen", 60)
    time.sleep(3)
    press("KEYCODE_DPAD_RIGHT")  # into the group's Apps
    time.sleep(4)  # icons
    shot(out_dir, "6-foss.png")

    # What the Launcher's "Updates" on a Tile sends: the App's page, here one from the FOSS Catalog.
    adb("shell", "am", "start", "-a", "android.intent.action.SHOW_APP_INFO", "-p", PACKAGE,
        "--es", "android.intent.extra.PACKAGE_NAME", "org.xbmc.kodi")
    time.sleep(4)
    if not focused_text().startswith("Kodi"):
        sys.exit(f"SHOW_APP_INFO didn't focus Kodi (focus is on '{focused_text()}')")
    shot(out_dir, "7-show-app-info.png")


if __name__ == "__main__":
    main()

#!/usr/bin/env python3
# SPDX-License-Identifier: Apache-2.0
"""Screenshots of Ladrok Store on a running Android TV emulator: the main screen, a search with
results from both Catalogs, and the FOSS screen. Run by .github/workflows/screenshots.yml.

    python3 tools/screenshots.py LadrokStore.apk out/
"""
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


def wait_for(text, seconds=90):
    """Bounds (x, y) of the centre of the first node showing [text], once it shows."""
    deadline = time.time() + seconds
    while time.time() < deadline:
        try:
            ui = screen_text()
        except subprocess.CalledProcessError:
            ui = ""
        for node in re.finditer(r'<node [^>]*>', ui):
            n = node.group(0)
            if re.search(r'(text|content-desc)="' + re.escape(text) + r'"', n):
                x1, y1, x2, y2 = map(int, re.search(r'bounds="\[(\d+),(\d+)\]\[(\d+),(\d+)\]"', n).groups())
                return (x1 + x2) // 2, (y1 + y2) // 2
        time.sleep(3)
    sys.exit(f"'{text}' never showed up on screen")


def main():
    apk, out_dir = sys.argv[1], sys.argv[2]
    os.makedirs(out_dir, exist_ok=True)
    adb("install", "-r", apk)
    adb("shell", "am", "start", "-n", f"{PACKAGE}/.StoreActivity")
    wait_for("Jellyfin")  # the curated Catalog has loaded
    time.sleep(3)  # icons
    shot(out_dir, "1-store.png")

    # The remote's search key opens Search; type a word that matches curated and FOSS Apps.
    adb("shell", "input", "keyevent", "KEYCODE_SEARCH")
    wait_for("App name or what it does", 30)
    adb("shell", "input", "text", "movies")
    wait_for("Nuvio", 30)
    time.sleep(2)
    shot(out_dir, "2-search-with-keyboard.png")
    adb("shell", "input", "keyevent", "KEYCODE_BACK")  # closes the on-screen keyboard
    time.sleep(2)
    shot(out_dir, "3-search-results.png")

    adb("shell", "input", "keyevent", "KEYCODE_BACK")  # closes Search
    x, y = wait_for("FOSS apps", 30)
    adb("shell", "input", "tap", str(x), str(y))
    wait_for("Kodi", 60)
    time.sleep(3)
    shot(out_dir, "4-foss.png")


if __name__ == "__main__":
    main()

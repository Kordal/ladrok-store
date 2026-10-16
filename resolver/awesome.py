# SPDX-License-Identifier: Apache-2.0
"""Turn the Awesome Android TV FOSS Apps list into resolver entries, for the FOSS Catalog.

The list is a Markdown README: a `### Category` heading, then one line per app:

    - **Name:** What it does [[Source](https://github.com/o/r)] [[F-Droid](https://f-droid.org/packages/id/)] 🛑

Each app becomes an F-Droid entry when the list links its F-Droid page (the package name is in the
link), else a GitHub entry when its source is on GitHub, else nothing: the resolver can't fetch it.
The resolver's checks then decide what makes it into the FOSS Catalog.
"""
import re

LIST = "https://raw.githubusercontent.com/Generator/Awesome-Android-TV-FOSS-Apps/master/README.md"

ITEM = re.compile(r"^- \*\*(?P<label>.+?):\*\*\s*(?P<rest>.*)$")
LINK = re.compile(r"\[\[([^\]]+)\]\((https?://[^)\s]+)\)\]")
FDROID = re.compile(r"^https?://f-droid\.org/(?:[a-z]{2}(?:[-_][A-Za-z]+)?/)?packages/([A-Za-z0-9_.]+)/?$")
GITHUB = re.compile(r"^https?://github\.com/([A-Za-z0-9_.-]+)/([A-Za-z0-9_.-]+?)(?:\.git)?/?$")
# Sections after the apps: no apps in them.
END = re.compile(r"^## (?!Apps\b)")


def parse(markdown):
    """(entries, skipped): resolver entries in list order, and "Name: why" for apps it can't fetch."""
    entries, skipped, category = [], [], None
    for line in markdown.splitlines():
        if line.startswith("### "):
            category = line[4:].strip()
            continue
        if END.match(line):
            category = None
            continue
        m = ITEM.match(line.strip())
        if not m or category is None:
            continue
        label, rest = m.group("label").strip(), m.group("rest")
        links = LINK.findall(rest)
        if not links:
            continue  # a pointer to another list, not an app
        summary = LINK.sub("", rest)
        summary = re.sub(r"\s+", " ", re.sub(r"[^\w\s.,;:!?()'’/&+-]", "", summary)).strip()
        entry = {"label": label, "summary": summary, "category": category}
        fdroid = next((FDROID.match(url) for _, url in links if FDROID.match(url)), None)
        github = next((GITHUB.match(url) for name, url in links if name == "Source" and GITHUB.match(url)), None)
        if fdroid:
            entry.update(source="fdroid", id=fdroid.group(1))
        elif github:
            entry.update(source="github", repo=f"{github.group(1)}/{github.group(2)}")
        else:
            skipped.append(f"{label}: no F-Droid or GitHub release to fetch from")
            continue
        entries.append(entry)
    return entries, skipped

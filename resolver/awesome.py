# SPDX-License-Identifier: Apache-2.0
"""Turn the Awesome Android TV FOSS Apps list (its README) into resolver entries.

github.com/Generator/Awesome-Android-TV-FOSS-Apps lists apps as Markdown, one per line, under a
"### Section" heading:

    - **Name:** What it does [[Source](https://github.com/o/r)] [[F-Droid](https://f-droid.org/packages/x.y/)] 📺

Each app becomes an entry with every place its APK can come from, in the order the resolver tries
them: GitHub releases (the developer's own build), then F-Droid, then IzzyOnDroid. Apps with none
of those (Play-only, GitLab, websites) and apps that need root are left out; [skipped] says why.
"""
import re

LIST_URL = "https://raw.githubusercontent.com/Generator/Awesome-Android-TV-FOSS-Apps/master/README.md"

ITEM = re.compile(r"^\s*-\s+\*\*(?P<name>[^*]+?):?\*\*:?\s*(?P<rest>.*)$")
LINK = re.compile(r"\[\[[^\]]*\]\((?P<url>[^)\s]+)\)\]")
GITHUB = re.compile(r"^https?://github\.com/([\w.-]+)/([\w.-]+?)(?:\.git)?(?:/.*)?$")
FDROID = re.compile(r"^https?://f-droid\.org/(?:[a-z]{2}(?:[-_][A-Za-z]+)?/)?packages/([\w.]+)/?$")
IZZY = re.compile(r"^https?://apt\.izzysoft\.de/(?:fdroid/index/apk|packages)/([\w.]+)/?$")
# Sections that are not apps for the TV, or just point at another list.
NOT_APPS = {"Contributing", "More Awesome Lists", "License", "Table of Contents"}


def parse(markdown):
    """(entries, skipped): entries in list order, and (name, reason) for each app left out."""
    entries, skipped, section, packages = [], [], None, set()
    for line in markdown.splitlines():
        if line.startswith("### "):
            section = line[4:].strip()
            continue
        if line.startswith("## "):
            section = None if line[3:].strip() in NOT_APPS | {"Apps"} else line[3:].strip()
            continue
        m = ITEM.match(line)
        if not m or not section:
            continue
        name, rest = m.group("name").strip(), m.group("rest")
        if "🤖" in rest:
            skipped.append((name, "needs root"))
            continue
        sources = []
        for url in (u.group("url") for u in LINK.finditer(rest)):
            if g := GITHUB.match(url):
                source = {"source": "github", "repo": f"{g.group(1)}/{g.group(2)}"}
            elif f := FDROID.match(url):
                source = {"source": "fdroid", "id": f.group(1)}
            elif i := IZZY.match(url):
                source = {"source": "izzy", "id": i.group(1)}
            else:
                continue
            # A package an earlier app already has: a mislabeled link, not this app.
            if source not in sources and source.get("id") not in packages:
                sources.append(source)
        if not sources:
            skipped.append((name, "no APK on GitHub, F-Droid or IzzyOnDroid"))
            continue
        order = {"github": 0, "fdroid": 1, "izzy": 2}
        sources.sort(key=lambda s: order[s["source"]])
        package = next((s["id"] for s in sources if "id" in s), None)
        entry = {
            # Stable across runs, so each app keeps its pinned signer: the package name when the list
            # gives one, else the GitHub repository.
            "key": package or "github:" + sources[0]["repo"].lower(),
            "label": name,
            "summary": summary(rest),
            "category": section,
            "sources": sources,
        }
        if package:
            entry["id"] = package
            packages.add(package)
        entries.append(entry)
    return entries, skipped


def summary(rest):
    """The description: the text before the first [[link]], without the list's emoji flags."""
    text = rest.split("[[", 1)[0]
    return re.sub(r"\s+", " ", text).strip().rstrip(".") + ("." if text.strip() else "")

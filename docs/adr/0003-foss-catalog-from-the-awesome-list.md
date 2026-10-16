# The FOSS Catalog follows the Awesome Android TV FOSS Apps list

Besides the curated Catalog, the Store has a second screen, *FOSS apps*: every app on [Awesome Android TV FOSS Apps](https://github.com/Generator/Awesome-Android-TV-FOSS-Apps) that the TV can run. Nobody picks these by hand. The Resolver reads the list once a day and turns each app into an entry: F-Droid when the list links an F-Droid page, else the app's GitHub releases. Apps on GitLab or other hosts, or only on IzzyOnDroid or Google Play, are skipped.

Each APK gets the same checks as a Catalog App: an Android TV launcher entry, an ABI the TV runs, a signing certificate that never changes, and no downgrade. That leaves out a good part of the list (launchers, remotes, keyboards, phone apps). The job summary names each one and says why. The result is `foss.json`, signed with the Store's key like `catalog.json` (ADR 0002), so nothing but the Resolver can add to it.

What the list's maintainers accept, this screen shows the next day. Its apps aren't vetted the way the curated Catalog's are, and the screen says so. The protections that do hold: the viewer still installs each App on purpose, the first signing certificate seen is kept, and an update signed by anyone else is refused. Apps in the curated Catalog are left out of the FOSS Catalog, so each App appears once.

`foss.json` is a separate file rather than a section of `catalog.json`, so Stores from before this screen keep showing only the curated Catalog.

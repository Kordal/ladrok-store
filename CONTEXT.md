# Ladrok Store

Where Apps come from on Ladrok TV. It follows the Ladrok mantra: only what you need, nothing that shouts, little to set up.

## Language

**Store**:
Ladrok Store, the app on the TV.
_Avoid_: Market, shop, app center

**Catalog**:
The curated list of Apps the Store offers: `catalog/apps.yaml` as edited, `catalog.json` as published and signed.
_Avoid_: Index, repo, feed

**App**:
A TV app in the Catalog. Each has one package name and one signing certificate, for good.
_Avoid_: Application, package, listing

**FOSS Catalog**:
Every app from the Awesome Android TV FOSS Apps list that passes the Resolver's checks, on its own screen ("FOSS apps"). Not curated: `foss.json`, published and signed next to `catalog.json`.
_Avoid_: Extras, community catalog

**Resolver**:
The workflow that turns `apps.yaml` into `catalog.json`: it finds each App's newest APK and checks it.
_Avoid_: Crawler, indexer, bot

**Source**:
Where an App's APKs come from: a GitHub repo's releases, F-Droid, or a download page.
_Avoid_: Origin, provider, upstream

**Update**:
A newer version of an installed App, from the Catalog. Automatic unless the viewer switches automatic updates off.
_Avoid_: Upgrade, patch

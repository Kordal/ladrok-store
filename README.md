# Ladrok Store

The app store of [Ladrok TV](https://github.com/Kordal/ladrok-tv). It offers a short, curated list of TV Apps whose APKs come from their own GitHub releases (or F-Droid, or a vendor's download page), installs them with the remote, and keeps them up to date.

- **The Catalog is one file**: [catalog/apps.yaml](catalog/apps.yaml). Adding an App is a pull request.
- **Checked before it reaches the TV**: a workflow downloads each new APK and checks the package name, the Android TV launcher entry (without one an App never shows on the Launcher), the ABI, the signing certificate and the version. An App that fails keeps its last good version.
- **FOSS apps, on their own screen**: every app on [Awesome Android TV FOSS Apps](https://github.com/Generator/Awesome-Android-TV-FOSS-Apps) that passes the same checks, refreshed daily. Not curated: see [ADR 0003](docs/adr/0003-foss-catalog-from-the-awesome-list.md).
- **Silent on Ladrok TV**: the Store is a privileged system app there, so installs and updates don't ask. Updates run once a day and wait until the App isn't in use (Android 14+). *Automatic updates* at the top of the Store switches them off.
- **The TV's one app updater**: the Store also updates itself and Ladrok Launcher from the same signed Catalog. Both are built into the system image, so the Store never offers to uninstall them.
- **Anywhere else** (a stock Android TV box with Android 12 or newer, the emulator) it works too, but Android asks you to allow the Store to install apps and confirms each install.

The repo is called `ladrok-store`. The app and package (`tv.ladrok.store`) are *Ladrok Store*.

Terms are defined in [CONTEXT.md](CONTEXT.md), decisions in [docs/adr](docs/adr).

## How it fits together

```
 catalog/apps.yaml ──► Catalog workflow (on change + daily) ──► "catalog" release:
                        resolver/resolve.py: find, download,       catalog.json
                        check each APK; sign with the Store's      catalog.json.sig
                        release key                                icons/
                                                                      │
 Ladrok Store on the TV ◄─────────────────────────────────────────────┘
   checks the signature against its own signing key, shows the Catalog,
   downloads each APK from its source, checks its SHA-256, installs it with PackageInstaller
```

## Build and run

You need the Android SDK and JDK 21 or newer (the one bundled with Android Studio works).

```bash
./gradlew testDebugUnitTest   # Catalog parsing and signature, install status
./gradlew installDebug        # onto a connected Android TV device or emulator
```

A debug build skips the Catalog signature check: it is signed with the debug key, which never signs a Catalog.

The resolver needs Python 3 with PyYAML, and `ANDROID_HOME` pointing at an SDK with build-tools:

```bash
python3 -m pytest resolver/tests
python3 resolver/resolve.py --previous previous.json --out out \
  --base-url https://github.com/Kordal/ladrok-store/releases/download/catalog
```

## Releases

Repo secrets: `LADROK_KEYSTORE_B64` and `LADROK_KEYSTORE_PASSWORD`, the same as ladrok-start's (key alias `ladrok-launcher`). The Catalog workflow signs `catalog.json` with that key too.

Push a tag such as `v0.1.0`, or run the Release workflow by hand with that version (Actions → Release → Run workflow). CI builds `LadrokStore.apk`, signs it and attaches it with its SHA-256 to a GitHub Release.

Nothing to bump in ladrok-tv: its `scripts/fetch-prebuilts.sh` builds the newest release into the next system image as a privileged app, and accepts only an APK signed with the pinned certificate. Between system updates, the Store installs its own new releases, and the Launcher's, from the Catalog. Catalog changes need no release at all: the Catalog workflow publishes them and the TV picks them up on its next check.

## License

Apache License 2.0, see [LICENSE](LICENSE).

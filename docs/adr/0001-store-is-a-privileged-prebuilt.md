# The Store is a privileged prebuilt APK

The Store needs INSTALL_PACKAGES and DELETE_PACKAGES to install and update without asking. Those are privileged permissions: an app gets them by being on the system image under `priv-app` and listed in a `privapp-permissions` file, not by being signed with the platform key.

So, like the Launcher, the Store is built with Gradle in its own repo and pulled into ladrok-tv with `android_app_import` (`privileged: true`), signed with our own release key. A UI change can be tried on an emulator in minutes, and the same APK runs, with confirmations, on any Android TV box.

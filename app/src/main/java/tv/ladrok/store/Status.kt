// SPDX-License-Identifier: Apache-2.0
package tv.ladrok.store

/** A Catalog App's package as installed on this TV. */
data class InstalledPackage(val versionCode: Long, val versionName: String, val signer: String?)

/** Where a Catalog App stands on this TV. */
sealed interface Status {
    data object NotInstalled : Status
    data class UpToDate(val version: String) : Status
    data class UpdateAvailable(val from: String, val to: String) : Status

    /** Installed from somewhere else, signed with another key: Android refuses to update it. */
    data class OtherSigner(val version: String) : Status

    companion object {
        fun of(app: CatalogApp, installed: InstalledPackage?): Status = when {
            installed == null -> NotInstalled
            installed.signer != null && installed.signer != app.signer -> OtherSigner(installed.versionName)
            installed.versionCode < app.versionCode -> UpdateAvailable(installed.versionName, app.versionName)
            else -> UpToDate(installed.versionName)
        }
    }
}

/** What the Store is doing with an App right now. */
sealed interface Work {
    /** [percent] is null until the size is known. */
    data class Downloading(val percent: Int?) : Work
    data object Installing : Work

    /** An update the system holds until the App is closed (Android 14+). */
    data object Waiting : Work
    data object Removing : Work
    data class Failed(val message: String) : Work
}

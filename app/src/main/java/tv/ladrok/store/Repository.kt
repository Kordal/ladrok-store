// SPDX-License-Identifier: Apache-2.0
package tv.ladrok.store

import android.content.Context
import android.content.pm.ApplicationInfo
import android.content.pm.PackageInfo
import android.content.pm.PackageManager
import java.io.ByteArrayInputStream
import java.io.File
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL
import java.security.PublicKey
import java.security.cert.CertificateFactory
import java.util.Base64

/** Which Catalog: the curated one, or the FOSS Catalog on its own screen (docs/adr/0003). */
enum class Shelf(val url: String, val cacheName: String) {
    CURATED(BuildConfig.CATALOG_URL, "catalog.json"),
    FOSS(BuildConfig.FOSS_URL, "foss.json"),
}

/** The Catalogs (downloaded, checked, kept for offline use), the installed packages, and the settings. */
class Repository(private val context: Context) {
    private val prefs = context.getSharedPreferences("store", Context.MODE_PRIVATE)

    /** Whether Apps update by themselves once a day. On unless the viewer switches it off. */
    var autoUpdate: Boolean
        get() = prefs.getBoolean(AUTO_UPDATE, true)
        set(value) = prefs.edit().putBoolean(AUTO_UPDATE, value).apply()

    /** The last [shelf] Catalog that was downloaded and checked, if any. */
    fun cached(shelf: Shelf = Shelf.CURATED): Catalog? = runCatching { Catalog.parse(cache(shelf).readText()) }.getOrNull()

    /**
     * Downloads the [shelf] Catalog and its signature, and keeps it if the signature is good.
     * Throws [IOException] when offline and [SecurityException] when the signature is wrong.
     * Slow: call it off the main thread.
     */
    fun refresh(shelf: Shelf = Shelf.CURATED): Catalog {
        val json = fetch(shelf.url)
        val signature = Base64.getMimeDecoder().decode(fetch(shelf.url + ".sig"))
        val key = ownKey()
        // A debug build is signed with the debug key, which never signs a Catalog.
        if (!BuildConfig.DEBUG && (key == null || !Catalog.verify(json, signature, key))) {
            throw SecurityException(context.getString(R.string.error_signature))
        }
        val catalog = Catalog.parse(String(json))
        cache(shelf).writeBytes(json)
        return catalog
    }

    private fun cache(shelf: Shelf) = File(context.filesDir, shelf.cacheName)

    /** The installed version and signer of each of [apps] that is installed. */
    fun installed(apps: List<CatalogApp>): Map<String, InstalledPackage> = apps.mapNotNull { app ->
        val info = packageInfo(app.id) ?: return@mapNotNull null
        val system = (info.applicationInfo?.flags ?: 0) and ApplicationInfo.FLAG_SYSTEM != 0
        val pm = context.packageManager
        val launchable = app.id != context.packageName &&
            (pm.getLeanbackLaunchIntentForPackage(app.id) ?: pm.getLaunchIntentForPackage(app.id)) != null
        app.id to InstalledPackage(info.longVersionCode, info.versionName.orEmpty(), info.signer(), system, launchable)
    }.toMap()

    private fun packageInfo(name: String): PackageInfo? = try {
        @Suppress("DEPRECATION") // the flags overload needs API 33
        context.packageManager.getPackageInfo(name, PackageManager.GET_SIGNING_CERTIFICATES)
    } catch (_: PackageManager.NameNotFoundException) {
        null
    }

    /** SHA-256 of the current signing certificate, as the resolver computes it with apksigner. */
    private fun PackageInfo.signer(): String? =
        signingInfo?.apkContentsSigners?.firstOrNull()?.toByteArray()?.let(::sha256Hex)

    /** The public key the Store itself is signed with: the only key it accepts a Catalog from. */
    private fun ownKey(): PublicKey? {
        val cert = packageInfo(context.packageName)?.signingInfo?.apkContentsSigners?.firstOrNull() ?: return null
        return CertificateFactory.getInstance("X.509").generateCertificate(ByteArrayInputStream(cert.toByteArray())).publicKey
    }

    private fun fetch(url: String): ByteArray {
        val connection = URL(url).openConnection() as HttpURLConnection
        connection.connectTimeout = 15_000
        connection.readTimeout = 30_000
        connection.useCaches = false
        try {
            if (connection.responseCode != HttpURLConnection.HTTP_OK) throw IOException("HTTP ${connection.responseCode}")
            return connection.inputStream.use { it.readBytes() }
        } finally {
            connection.disconnect()
        }
    }

    private companion object {
        const val AUTO_UPDATE = "auto_update"
    }
}

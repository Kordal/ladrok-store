// SPDX-License-Identifier: Apache-2.0
package tv.ladrok.store

import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.pm.PackageInstaller
import android.content.pm.PackageManager
import android.os.Build
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.withContext
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL
import java.security.DigestInputStream
import java.security.MessageDigest
import java.util.concurrent.TimeUnit

/**
 * Installs, updates and removes Catalog Apps through [PackageInstaller].
 *
 * On Ladrok TV the Store is a privileged system app with INSTALL_PACKAGES and DELETE_PACKAGES, so
 * nothing asks. Anywhere else the system shows its own confirmation, and asks once to allow the
 * Store to install apps.
 */
object Installer {
    /** What the Store is doing with each App, by package name. */
    val work = MutableStateFlow<Map<String, Work>>(emptyMap())

    /** Goes up each time an install or removal finishes, so the screen reads the packages again. */
    val finished = MutableStateFlow(0)

    /** Installs outlive the screen that started them. */
    val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    /**
     * Downloads [app], checks its SHA-256 against the Catalog and hands it to the system.
     * [interactive]: started from the screen, so a system confirmation may show. In the background
     * an update waits until the App is not in use (Android 14+), so nobody is thrown out of a film.
     */
    suspend fun install(context: Context, app: CatalogApp, interactive: Boolean) = withContext(Dispatchers.IO) {
        val installer = context.packageManager.packageInstaller
        val params = PackageInstaller.SessionParams(PackageInstaller.SessionParams.MODE_FULL_INSTALL).apply {
            setAppPackageName(app.id)
            setSize(app.size)
            setInstallReason(PackageManager.INSTALL_REASON_USER)
            setRequireUserAction(PackageInstaller.SessionParams.USER_ACTION_NOT_REQUIRED)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                setPackageSource(PackageInstaller.PACKAGE_SOURCE_STORE)
            }
            // Other installers (an App's own updater, adb aside) must ask before replacing it.
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) setRequestUpdateOwnership(true)
        }
        set(app.id, Work.Downloading(0))
        val id = try {
            installer.createSession(params)
        } catch (e: Exception) {
            set(app.id, Work.Failed(context.getString(R.string.failed_session, e.message ?: "")))
            return@withContext
        }
        val session = installer.openSession(id)
        try {
            download(app, session)
        } catch (e: Exception) {
            session.abandon()
            val message = when (e) {
                is HashMismatch -> context.getString(R.string.failed_hash)
                is IOException -> context.getString(R.string.failed_download, e.message ?: "")
                else -> e.message ?: e.toString()
            }
            set(app.id, Work.Failed(message))
            return@withContext
        }
        set(app.id, Work.Installing)
        val receiver = statusReceiver(context, id, app.id, interactive)
        if (!interactive && Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            val constraints = PackageInstaller.InstallConstraints.Builder()
                .setAppNotForegroundRequired()
                .setAppNotInteractingRequired()
                .setAppNotTopVisibleRequired()
                .build()
            session.close()
            set(app.id, Work.Waiting)
            installer.commitSessionAfterInstallConstraintsAreMet(id, receiver, constraints, TimeUnit.DAYS.toMillis(1))
        } else {
            session.commit(receiver)
            session.close()
        }
    }

    /** Removes [packageName]. */
    fun uninstall(context: Context, packageName: String) {
        set(packageName, Work.Removing)
        context.packageManager.packageInstaller.uninstall(packageName, statusReceiver(context, 0, packageName, interactive = true))
    }

    private class HashMismatch : Exception()

    private fun download(app: CatalogApp, session: PackageInstaller.Session) {
        val connection = URL(app.url).openConnection() as HttpURLConnection
        connection.connectTimeout = 15_000
        connection.readTimeout = 30_000
        try {
            if (connection.responseCode != HttpURLConnection.HTTP_OK) throw IOException("HTTP ${connection.responseCode}")
            val digest = MessageDigest.getInstance("SHA-256")
            DigestInputStream(connection.inputStream, digest).use { input ->
                session.openWrite("base.apk", 0, app.size).use { output ->
                    val buffer = ByteArray(64 * 1024)
                    var done = 0L
                    var shown = -1
                    while (true) {
                        val n = input.read(buffer)
                        if (n < 0) break
                        output.write(buffer, 0, n)
                        done += n
                        val percent = if (app.size > 0) (done * 100 / app.size).toInt().coerceAtMost(100) else null
                        if (percent != null && percent != shown) {
                            shown = percent
                            set(app.id, Work.Downloading(percent))
                        }
                    }
                    session.fsync(output)
                }
            }
            if (digest.digest().toHex() != app.sha256) throw HashMismatch()
        } finally {
            connection.disconnect()
        }
    }

    private fun statusReceiver(context: Context, sessionId: Int, packageName: String, interactive: Boolean) =
        PendingIntent.getBroadcast(
            context,
            // One PendingIntent per package and per kind, so their extras don't overwrite each other.
            (packageName + sessionId).hashCode(),
            Intent(context, InstallResultReceiver::class.java)
                .putExtra(EXTRA_APP, packageName)
                .putExtra(EXTRA_INTERACTIVE, interactive),
            // Mutable: the system adds the result to it. Explicit, so nobody else can receive it.
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_MUTABLE,
        ).intentSender

    internal fun set(packageName: String, value: Work?) {
        work.update { if (value == null) it - packageName else it + (packageName to value) }
    }

    internal const val EXTRA_APP = "tv.ladrok.store.extra.APP"
    internal const val EXTRA_INTERACTIVE = "tv.ladrok.store.extra.INTERACTIVE"
}

/** The system's answer to an install or removal. */
class InstallResultReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val app = intent.getStringExtra(Installer.EXTRA_APP) ?: return
        val status = intent.getIntExtra(PackageInstaller.EXTRA_STATUS, PackageInstaller.STATUS_FAILURE)
        when (status) {
            PackageInstaller.STATUS_PENDING_USER_ACTION -> {
                // Not a privileged install: the system wants the viewer's OK.
                @Suppress("DEPRECATION") // the typed overload needs API 33
                val confirm = intent.getParcelableExtra<Intent>(Intent.EXTRA_INTENT)
                if (confirm != null && intent.getBooleanExtra(Installer.EXTRA_INTERACTIVE, false)) {
                    context.startActivity(confirm.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
                } else {
                    Installer.set(app, Work.Failed(context.getString(R.string.failed_needs_ok)))
                }
            }
            PackageInstaller.STATUS_SUCCESS -> {
                Installer.set(app, null)
                Installer.finished.update { it + 1 }
            }
            else -> {
                val message = when (status) {
                    PackageInstaller.STATUS_FAILURE_CONFLICT -> context.getString(R.string.failed_conflict)
                    PackageInstaller.STATUS_FAILURE_STORAGE -> context.getString(R.string.failed_storage)
                    PackageInstaller.STATUS_FAILURE_ABORTED -> null // the viewer said no
                    else -> intent.getStringExtra(PackageInstaller.EXTRA_STATUS_MESSAGE) ?: context.getString(R.string.failed_unknown)
                }
                Installer.set(app, message?.let { Work.Failed(it) })
                Installer.finished.update { it + 1 }
            }
        }
    }
}

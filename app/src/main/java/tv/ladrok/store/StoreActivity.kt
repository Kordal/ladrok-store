// SPDX-License-Identifier: Apache-2.0
package tv.ladrok.store

import android.content.ActivityNotFoundException
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.net.Uri
import android.os.Bundle
import android.provider.Settings
import android.speech.RecognizerIntent
import android.view.KeyEvent
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.core.content.ContextCompat
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.IOException

/**
 * Ladrok Store: the curated Apps, what is installed, and what can be updated. The FOSS Catalog is
 * a second screen, opened from the first; Back returns. Search (its row, or the remote's search
 * key) looks through both.
 */
class StoreActivity : ComponentActivity() {
    private lateinit var repository: Repository
    private val catalogs = mutableStateMapOf<Shelf, Catalog>()
    private val errors = mutableStateMapOf<Shelf, String>()
    private val shelf = mutableStateOf(Shelf.CURATED)
    private val installed = mutableStateOf<Map<String, InstalledPackage>>(emptyMap())
    private val autoUpdate = mutableStateOf(true)
    /** What is being searched for; null when not searching. */
    private val query = mutableStateOf<String?>(null)
    /** An App to show, asked for by another app (with when, so asking twice shows it twice). */
    private val show = mutableStateOf<Pair<String, Long>?>(null)

    private val voiceIntent = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH)
        .putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_WEB_SEARCH)
    private val voice = registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
        result.data?.getStringArrayListExtra(RecognizerIntent.EXTRA_RESULTS)?.firstOrNull()?.let { query.value = it }
    }

    // Apps installed, removed or updated by anyone while the Store is open.
    private val packages = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) = readInstalled()
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        repository = Repository(this)
        for (shelf in Shelf.entries) repository.cached(shelf)?.let { catalogs[shelf] = it }
        autoUpdate.value = repository.autoUpdate
        UpdateJob.schedule(this)
        val filter = IntentFilter().apply {
            addAction(Intent.ACTION_PACKAGE_ADDED)
            addAction(Intent.ACTION_PACKAGE_REMOVED)
            addAction(Intent.ACTION_PACKAGE_REPLACED)
            addDataScheme("package")
        }
        ContextCompat.registerReceiver(this, packages, filter, ContextCompat.RECEIVER_NOT_EXPORTED)
        lifecycleScope.launch { Installer.finished.collect { readInstalled() } }
        showAppInfo(intent)
        setContent {
            val work by Installer.work.collectAsState()
            val current = shelf.value
            val searching = query.value
            BackHandler(enabled = searching != null || current != Shelf.CURATED) {
                if (searching != null) query.value = null else shelf.value = Shelf.CURATED
            }
            StoreScreen(
                shelf = current,
                catalogs = catalogs,
                errors = errors,
                installed = installed.value,
                work = work,
                autoUpdate = autoUpdate.value,
                query = searching,
                results = if (searching == null) emptyList() else search(searching, catalogs),
                onSearch = { query.value = "" },
                onQueryChange = { query.value = it },
                onVoice = if (canListen) ({ startVoice() }) else null,
                onOpenFoss = { shelf.value = Shelf.FOSS },
                onInstall = ::install,
                onOpen = ::open,
                onUninstall = { Installer.uninstall(applicationContext, it.id) },
                onAppInfo = ::appInfo,
                onUpdateAll = ::updateAll,
                onToggleAutoUpdate = {
                    repository.autoUpdate = !repository.autoUpdate
                    autoUpdate.value = repository.autoUpdate
                },
                show = show.value,
            )
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        showAppInfo(intent)
    }

    /**
     * ACTION_SHOW_APP_INFO (the Launcher's "Updates" on a Tile): that App's page, in whichever Catalog
     * has it, or the Store's first screen when neither does.
     */
    private fun showAppInfo(intent: Intent?) {
        if (intent?.action != Intent.ACTION_SHOW_APP_INFO) return
        val id = intent.getStringExtra(Intent.EXTRA_PACKAGE_NAME) ?: return
        query.value = null
        val inFoss = catalogs[Shelf.CURATED]?.apps?.none { it.id == id } != false &&
            catalogs[Shelf.FOSS]?.apps?.any { it.id == id } == true
        shelf.value = if (inFoss) Shelf.FOSS else Shelf.CURATED
        show.value = id to System.nanoTime()
    }

    /** Whether something on this device turns speech into text (Google's TV apps do; plain AOSP may not). */
    private val canListen by lazy { voiceIntent.resolveActivity(packageManager) != null }

    private fun startVoice() {
        try {
            voice.launch(voiceIntent)
        } catch (_: ActivityNotFoundException) {
            // The speech app went away since canListen was read: the keyboard still works.
        }
    }

    // The remote's search key (or a keyboard's) opens Search from anywhere in the Store.
    override fun onKeyDown(keyCode: Int, event: KeyEvent): Boolean {
        if (keyCode == KeyEvent.KEYCODE_SEARCH) {
            if (query.value == null) query.value = ""
            return true
        }
        return super.onKeyDown(keyCode, event)
    }

    override fun onStart() {
        super.onStart()
        refresh()
    }

    override fun onDestroy() {
        unregisterReceiver(packages)
        super.onDestroy()
    }

    /** Downloads both Catalogs again; keeps showing the last good one of each that fails. */
    private fun refresh() {
        for (shelf in Shelf.entries) lifecycleScope.launch {
            withContext(Dispatchers.IO) { runCatching { repository.refresh(shelf) } }
                .onSuccess { catalogs[shelf] = it; errors.remove(shelf) }
                .onFailure {
                    errors[shelf] = when (it) {
                        is SecurityException -> it.message.orEmpty()
                        is IOException -> getString(R.string.error_offline)
                        else -> getString(R.string.error_catalog, it.message ?: it.toString())
                    }
                }
            readInstalled()
        }
    }

    private fun readInstalled() {
        val apps = catalogs.values.flatMap { it.apps }
        if (apps.isEmpty()) return
        lifecycleScope.launch {
            installed.value = withContext(Dispatchers.IO) { repository.installed(apps) }
        }
    }

    private fun install(app: CatalogApp) {
        Installer.scope.launch { Installer.install(applicationContext, app, interactive = true) }
    }

    /** The shown Catalog's, one after the other, so the system's confirmations (if any) come one at a time. */
    private fun updateAll() {
        val now = catalogs[shelf.value] ?: return
        val apps = now.apps.filter { Status.of(it, installed.value[it.id]) is Status.UpdateAvailable }
        Installer.scope.launch {
            for (app in apps.sortedBy { it.id == packageName }) Installer.install(applicationContext, app, interactive = true)
        }
    }

    /** The system's page for the App: storage, permissions, force stop. */
    private fun appInfo(app: CatalogApp) {
        try {
            startActivity(Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.fromParts("package", app.id, null)))
        } catch (_: ActivityNotFoundException) {
            // No Settings app on this device.
        }
    }

    private fun open(app: CatalogApp) {
        val intent = packageManager.getLeanbackLaunchIntentForPackage(app.id)
            ?: packageManager.getLaunchIntentForPackage(app.id)
            ?: return
        try {
            startActivity(intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        } catch (_: ActivityNotFoundException) {
            // Removed a moment ago: the package receiver updates the screen.
        }
    }
}

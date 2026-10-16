// SPDX-License-Identifier: Apache-2.0
package tv.ladrok.store

import android.content.ActivityNotFoundException
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.core.content.ContextCompat
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.IOException

/** Ladrok Store: the curated Apps (and the FOSS list on its own screen), what is installed, and what can be updated. */
class StoreActivity : ComponentActivity() {
    private lateinit var repository: Repository
    private lateinit var fossRepository: Repository
    private val catalog = mutableStateOf<Catalog?>(null)
    private val fossCatalog = mutableStateOf<Catalog?>(null)
    private val screen = mutableStateOf(Source.Main)
    private val installed = mutableStateOf<Map<String, InstalledPackage>>(emptyMap())
    private val error = mutableStateOf<String?>(null)
    private val autoUpdate = mutableStateOf(true)

    // Apps installed, removed or updated by anyone while the Store is open.
    private val packages = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) = readInstalled()
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        repository = Repository(this)
        fossRepository = Repository(this, Source.Foss)
        catalog.value = repository.cached()
        fossCatalog.value = fossRepository.cached()
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
        setContent {
            val work by Installer.work.collectAsState()
            val foss = screen.value == Source.Foss
            BackHandler(enabled = foss) { screen.value = Source.Main }
            // A fresh screen (focus on its first App, no App page open) on every switch.
            key(screen.value) {
                StoreScreen(
                    foss = foss,
                    catalog = if (foss) fossCatalog.value else catalog.value,
                    installed = installed.value,
                    work = work,
                    error = error.value,
                    autoUpdate = autoUpdate.value,
                    onInstall = ::install,
                    onOpen = ::open,
                    onUninstall = { Installer.uninstall(applicationContext, it.id) },
                    onUpdateAll = ::updateAll,
                    onToggleAutoUpdate = {
                        repository.autoUpdate = !repository.autoUpdate
                        autoUpdate.value = repository.autoUpdate
                    },
                    onOpenFoss = { screen.value = Source.Foss },
                )
            }
        }
    }

    override fun onStart() {
        super.onStart()
        refresh()
    }

    override fun onDestroy() {
        unregisterReceiver(packages)
        super.onDestroy()
    }

    /** Downloads both Catalogs again; keeps showing the last ones if that fails. */
    private fun refresh() {
        lifecycleScope.launch {
            // The FOSS list is a bonus: its failures show nowhere but its own empty screen.
            withContext(Dispatchers.IO) { runCatching { fossRepository.refresh() } }
                .onSuccess { fossCatalog.value = it }
            withContext(Dispatchers.IO) { runCatching { repository.refresh() } }
                .onSuccess { catalog.value = it; error.value = null }
                .onFailure {
                    error.value = when (it) {
                        is SecurityException -> it.message
                        is IOException -> getString(R.string.error_offline)
                        else -> getString(R.string.error_catalog, it.message ?: it.toString())
                    }
                }
            readInstalled()
        }
    }

    private fun readInstalled() {
        val main = catalog.value
        val foss = fossCatalog.value
        lifecycleScope.launch {
            installed.value = withContext(Dispatchers.IO) {
                main?.let(repository::installed).orEmpty() + foss?.let(fossRepository::installed).orEmpty()
            }
        }
    }

    private fun install(app: CatalogApp) {
        Installer.scope.launch { Installer.install(applicationContext, app, interactive = true) }
    }

    /** One after the other, so the system's confirmations (if any) come one at a time. */
    private fun updateAll() {
        val now = (if (screen.value == Source.Foss) fossCatalog.value else catalog.value) ?: return
        val apps = now.apps.filter { Status.of(it, installed.value[it.id]) is Status.UpdateAvailable }
        Installer.scope.launch {
            for (app in apps) Installer.install(applicationContext, app, interactive = true)
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

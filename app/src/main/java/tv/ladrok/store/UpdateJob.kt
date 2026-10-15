// SPDX-License-Identifier: Apache-2.0
package tv.ladrok.store

import android.app.job.JobInfo
import android.app.job.JobParameters
import android.app.job.JobScheduler
import android.app.job.JobService
import android.content.BroadcastReceiver
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import java.util.concurrent.TimeUnit

/**
 * Once a day: download the Catalog and update every App that has a newer version, if automatic
 * updates are on. Each update waits until its App is not in use (see [Installer.install]).
 */
class UpdateJob : JobService() {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    override fun onStartJob(params: JobParameters): Boolean {
        scope.launch {
            runCatching { updateAll(this@UpdateJob) }
            jobFinished(params, false)
        }
        return true
    }

    override fun onStopJob(params: JobParameters): Boolean {
        scope.cancel()
        return true // try again later
    }

    override fun onDestroy() {
        scope.cancel()
        super.onDestroy()
    }

    companion object {
        private const val ID = 1

        /** Schedules the daily job, unless it already is. */
        fun schedule(context: Context) {
            val jobs = context.getSystemService(JobScheduler::class.java)
            if (jobs.getPendingJob(ID) != null) return
            jobs.schedule(
                JobInfo.Builder(ID, ComponentName(context, UpdateJob::class.java))
                    .setPeriodic(TimeUnit.DAYS.toMillis(1))
                    .setRequiredNetworkType(JobInfo.NETWORK_TYPE_ANY)
                    .setPersisted(true)
                    .build(),
            )
        }

        /** Updates every App with a newer version in the Catalog, if automatic updates are on. */
        suspend fun updateAll(context: Context) {
            val repository = Repository(context)
            if (!repository.autoUpdate) return
            val catalog = repository.refresh()
            val installed = repository.installed(catalog)
            for (app in catalog.apps) {
                if (Status.of(app, installed[app.id]) is Status.UpdateAvailable) {
                    Installer.install(context, app, interactive = false)
                }
            }
        }
    }
}

/** Schedules [UpdateJob] after boot, so updates run even if the Store is never opened. */
class BootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action == Intent.ACTION_BOOT_COMPLETED) UpdateJob.schedule(context)
    }
}

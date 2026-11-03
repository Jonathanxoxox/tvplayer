package tv.own.owntv.launcher

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.ExistingWorkPolicy
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import androidx.work.workDataOf
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.withTimeoutOrNull

/**
 * Keeps the launcher rows current: every 15 minutes (the shortest period Android allows), after a
 * reboot, when the launcher asks for the app's rows, and whenever the app goes to the background.
 * Reads only the local database and guide, so it costs no playlist download.
 *
 * The launcher shows the rows only while the app is NOT on screen, and a refresh scans up to 20,000
 * channels and their guide. So while the app is in front (zapping, watching a match) a run that is not
 * forced does nothing; leaving the app refreshes at once, so the rows are fresh exactly when they show.
 */
class LauncherRowsWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {
    override suspend fun doWork(): Result = try {
        // A run that is due when the process starts (the forced one the launcher asks for after an update, or
        // the one queued on leaving the app and left over when the box killed it, or one that ran before
        // MainActivity.onStart) scanned every channel during the first picture (Start-Protokoll 1.0.218:
        // recent-decorate @ 746 ms). Let the start through first, then decide; a process nobody
        // opens (boot, the launcher asking) waits a few seconds at most. Found the app on screen after that:
        // a forced run is not owed either, since leaving the app queues the next one.
        val atStart = !tv.own.owntv.StartupGate.isSettled
        if (atStart) withTimeoutOrNull(START_WAIT_MS) { tv.own.owntv.StartupGate.awaitSettled() }
        if (!RowsViewer.present(applicationContext)) {
            Log.d(TAG, "launcher rows skipped: Mayo TV is the home screen")
        } else if (!AppVisibility.foreground || (inputData.getBoolean(KEY_FORCE, false) && !atStart)) {
            LauncherRows(applicationContext).refresh()
        }
        Result.success()
    } catch (e: Exception) {
        Log.w(TAG, "launcher rows not refreshed", e)
        if (runAttemptCount < 2) Result.retry() else Result.failure()
    }

    companion object {
        private const val TAG = "LauncherRows"
        private const val PERIODIC = "tvplayer-launcher-rows"
        private const val NOW = "tvplayer-launcher-rows-now"
        private const val KEY_FORCE = "force"
        private const val START_WAIT_MS = 10_000L

        fun schedule(context: Context) {
            val request = PeriodicWorkRequestBuilder<LauncherRowsWorker>(15, TimeUnit.MINUTES).build()
            WorkManager.getInstance(context).enqueueUniquePeriodicWork(PERIODIC, ExistingPeriodicWorkPolicy.KEEP, request)
        }

        /**
         * A refresh soon; calls in quick succession collapse into the last one. Not [force]d, it waits
         * while the app is on screen and happens when the app is left (see [AppVisibility]).
         */
        fun refreshSoon(context: Context, delaySeconds: Long = 5, force: Boolean = false) {
            val request = OneTimeWorkRequestBuilder<LauncherRowsWorker>()
                .setInitialDelay(delaySeconds, TimeUnit.SECONDS)
                .setInputData(workDataOf(KEY_FORCE to force))
                .build()
            WorkManager.getInstance(context).enqueueUniqueWork(NOW, ExistingWorkPolicy.REPLACE, request)
        }
    }
}

/** Boot and the launcher's INITIALIZE_PROGRAMS: put the rows up without the app having been opened. */
class LauncherRowsReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        LauncherRowsWorker.schedule(context)
        LauncherRowsWorker.refreshSoon(context, delaySeconds = 0, force = true)
    }
}

/**
 * Whether a launcher shows the rows at all. With Mayo TV as the home screen nobody does: Mayo's own Home
 * leaves out its own channels (LauncherApps.loadChannels), and the stock launcher is not on screen. A
 * refresh then scans up to 20,000 channels and their guide for nothing, each time the app is left (the
 * screensaver, another app) and every 15 minutes. Switching the home screen back brings the rows with
 * the next run.
 */
object RowsViewer {
    fun present(context: Context): Boolean {
        val home = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_HOME)
        val pkg = runCatching {
            context.packageManager.resolveActivity(home, android.content.pm.PackageManager.MATCH_DEFAULT_ONLY)?.activityInfo?.packageName
        }.getOrNull()
        return pkg != context.packageName
    }
}

/**
 * Whether the app is on screen, set by MainActivity's onStart/onStop. Per process, so a fresh process
 * (boot, a worker woken in the background) starts as "not on screen" and refreshes normally.
 */
object AppVisibility {
    @Volatile var foreground = false

    /** MainActivity.onStart. */
    fun entered() {
        foreground = true
    }

    /**
     * MainActivity.onStop: the launcher is about to show, so bring the rows up to date now. A quick
     * return within the delay collapses into one run (REPLACE), which then still runs: it is forced.
     */
    fun left(context: Context) {
        foreground = false
        LauncherRowsWorker.refreshSoon(context, delaySeconds = 2, force = true)
    }
}

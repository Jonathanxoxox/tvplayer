package tv.own.owntv.launcher

import android.content.Context
import android.database.ContentObserver
import android.util.Log
import androidx.tvprovider.media.tv.TvContractCompat
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/**
 * Puts the programme on core's "Zuletzt gesehen" row the moment core has written it. Core republishes
 * the row with bare logos on zapping, after a sync and at the app's start; this sees the change in the
 * TvProvider and lets [LauncherRows.decorateRecent] redo just those cards, so the launcher never shows
 * the bare row for longer than a second or two.
 *
 * Changes arrive in bursts (one per card, and other apps' rows notify too), so they are collected and
 * handled in one pass. A pass that finds every card already done writes nothing and ends there.
 */
object RecentRowWatcher {
    private const val TAG = "LauncherRows"
    private const val SETTLE_MS = 1_500L

    fun start(context: Context, scope: CoroutineScope) {
        val app = context.applicationContext
        val changes = Channel<Unit>(Channel.CONFLATED)
        val observer = object : ContentObserver(null) {
            override fun onChange(selfChange: Boolean) {
                changes.trySend(Unit)
            }
        }
        runCatching { app.contentResolver.registerContentObserver(TvContractCompat.PreviewPrograms.CONTENT_URI, true, observer) }
            .onFailure { Log.w(TAG, "recent row watcher not registered", it); return }
        scope.launch(Dispatchers.IO) {
            for (change in changes) {
                // Logos and tiles for the launcher are not worth the first seconds of a start.
                tv.own.owntv.StartupGate.awaitSettled()
                // Let core finish the whole row first.
                delay(SETTLE_MS)
                changes.tryReceive()
                if (!RowsViewer.present(app)) continue
                runCatching { LauncherRows(app).decorateRecent(force = false) }
                    .onFailure { Log.w(TAG, "recent row not decorated", it) }
            }
        }
        // No pass of its own at start: core republishes the row then anyway, and that wakes this.
    }
}

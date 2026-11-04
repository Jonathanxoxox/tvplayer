package tv.own.owntv.launcher.apps

import android.content.ComponentName
import android.content.Context
import android.media.session.MediaSessionManager
import android.media.session.PlaybackState
import android.service.notification.NotificationListenerService

/**
 * Lets Mayo TV see other apps' playback: Android hands out their media sessions only to an app whose
 * notification listener the user (or ADB, `cmd notification allow_listener`) has allowed. It reads no
 * notifications itself.
 */
class MediaListener : NotificationListenerService()

/**
 * Other apps' playback, stopped when Mayo TV starts its own (Jonathan, 4 Nov 2026), so two sounds never
 * play at once. Mayo TV may not close other apps on Android 14. An app that offers Stop is stopped, which
 * lets most of them end their foreground service; one that does not (Spotify for TV lists no Stop, and keeps
 * its service while paused) is paused when it plays. Without the listener allowed this does nothing.
 */
object OtherPlayback {
    private val holding = setOf(
        PlaybackState.STATE_PLAYING,
        PlaybackState.STATE_PAUSED,
        PlaybackState.STATE_BUFFERING,
        PlaybackState.STATE_CONNECTING,
    )

    fun stop(context: Context) {
        runCatching {
            val sessions = context.getSystemService(MediaSessionManager::class.java)
                ?.getActiveSessions(ComponentName(context, MediaListener::class.java))
                .orEmpty()
            sessions
                .filter { it.packageName != context.packageName && it.playbackState?.state in holding }
                .forEach { c ->
                    val state = c.playbackState ?: return@forEach
                    when {
                        state.actions and PlaybackState.ACTION_STOP != 0L -> c.transportControls.stop()
                        state.state == PlaybackState.STATE_PLAYING || state.state == PlaybackState.STATE_BUFFERING -> c.transportControls.pause()
                    }
                }
        }
    }
}

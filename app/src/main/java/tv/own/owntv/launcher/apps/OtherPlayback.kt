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
 * Other apps' playback, stopped when Mayo TV starts its own (Jonathan, 4 Nov 2026). Mayo TV may not close
 * other apps on Android 14, but it may stop what they play: Spotify left paused keeps a foreground service
 * with some 340 MB and the audio path, and stopping it lets it go. Without the listener allowed this does
 * nothing.
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
                .forEach { it.transportControls.stop() }
        }
    }
}

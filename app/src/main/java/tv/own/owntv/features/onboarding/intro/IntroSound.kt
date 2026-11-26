package tv.own.owntv.features.onboarding.intro

import android.content.Context
import android.media.AudioAttributes
import android.media.AudioManager
import android.media.MediaPlayer
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import tv.own.owntv.R

/**
 * The sound logo (res/raw/mayo_intro.ogg) on the media stream, so the box's media volume applies.
 *
 * Every call is safe at any time and never throws: a box that cannot decode or play the file simply
 * stays silent. After [close] nothing plays again, and a player that finishes loading after [close]
 * is released at once, so playback can never outlive the intro.
 */
internal class IntroSound(context: Context) {
    private val appContext = context.applicationContext
    private val lock = Any()
    private var player: MediaPlayer? = null
    private var closed = false
    private var silenced = false

    /** Loads the file off the main thread. True when it is ready to play. */
    suspend fun load(): Boolean = withContext(Dispatchers.IO) {
        val created = runCatching { create() }.getOrNull() ?: return@withContext false
        synchronized(lock) {
            if (closed) {
                runCatching { created.release() }
                false
            } else {
                player = created
                true
            }
        }
    }

    private fun create(): MediaPlayer? {
        val attributes = AudioAttributes.Builder()
            .setUsage(AudioAttributes.USAGE_MEDIA)
            .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
            .build()
        return MediaPlayer.create(appContext, R.raw.mayo_intro, attributes, AudioManager.AUDIO_SESSION_ID_GENERATE)
            ?.apply { setVolume(VOLUME, VOLUME) }
    }

    /**
     * Plays from [positionMs] into the file, so the sound stays in step with the picture after a pause.
     * Does nothing once the file is over, when silenced, or before [load].
     */
    fun playFrom(positionMs: Long) = synchronized(lock) {
        val p = player ?: return@synchronized
        if (silenced || closed) return@synchronized
        runCatching {
            val duration = p.duration
            if (duration > 0 && positionMs >= duration) return@runCatching
            if (positionMs > 0L) p.seekTo(positionMs.toInt())
            p.start()
        }
        Unit
    }

    fun pause() = synchronized(lock) {
        runCatching { player?.let { if (it.isPlaying) it.pause() } }
        Unit
    }

    /** Silent from now on (the remote's Mute key, or a skip). */
    fun silence() = synchronized(lock) {
        silenced = true
        runCatching { player?.let { if (it.isPlaying) it.pause() } }
        Unit
    }

    /** Turns the volume down over [durationMs], then stops. Used for a skip, so the cut is not abrupt. */
    suspend fun fadeOut(durationMs: Int) {
        val steps = (durationMs / FADE_STEP_MS).coerceAtLeast(1)
        for (i in steps - 1 downTo 0) {
            val v = VOLUME * i / steps
            val playing = synchronized(lock) {
                val p = player
                if (p == null || closed || silenced) false else runCatching { p.setVolume(v, v); p.isPlaying }.getOrDefault(false)
            }
            if (!playing) break
            delay(FADE_STEP_MS.toLong())
        }
        silence()
    }

    /** Releases the player for good. */
    fun close() = synchronized(lock) {
        closed = true
        val p = player
        player = null
        if (p != null) {
            runCatching { p.stop() }
            runCatching { p.release() }
        }
        Unit
    }

    private companion object {
        /** A little under full, so the logo sits politely next to whatever volume the box is set to. */
        const val VOLUME = 0.85f
        const val FADE_STEP_MS = 20
    }
}

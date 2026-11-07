package tv.own.owntv.launcher.apps

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.media.MediaMetadata
import android.media.session.MediaController
import android.media.session.MediaSessionManager
import android.media.session.PlaybackState
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.background
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextOverflow
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.repeatOnLifecycle
import androidx.tv.material3.Text
import coil3.compose.AsyncImage
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import tv.own.owntv.ui.components.OwnTVIcon
import tv.own.owntv.ui.stage.StageFocus
import tv.own.owntv.ui.stage.StageSurface
import tv.own.owntv.ui.theme.StageColors
import tv.own.owntv.ui.theme.mpx
import tv.own.owntv.ui.theme.stageText

/** What another app plays, or has paused: enough for Home's "Now playing" pill. */
data class NowPlayingInfo(
    val pkg: String,
    val title: String,
    val artist: String?,
    /** The cover as the app hands it over, or its address. */
    val art: Bitmap?,
    val artUri: String?,
    val playing: Boolean,
    /** The app offers ⏮ / ⏭ (Spotify does; a video app may not). */
    val canPrevious: Boolean = false,
    val canNext: Boolean = false,
)

/**
 * Other apps' playback as Home shows it (Jonathan, 4 Nov 2026: "what Spotify or YouTube plays in the
 * background"). Read from the media sessions Android hands out to [MediaListener]; without the listener
 * allowed there is nothing, and the pill stays away.
 */
object NowPlaying {
    private val shown = setOf(PlaybackState.STATE_PLAYING, PlaybackState.STATE_BUFFERING, PlaybackState.STATE_PAUSED)

    private fun controllers(context: Context): List<MediaController> = runCatching {
        context.getSystemService(MediaSessionManager::class.java)
            ?.getActiveSessions(ComponentName(context, MediaListener::class.java))
            .orEmpty()
            .filter { it.packageName != context.packageName && it.playbackState?.state in shown && it.metadata != null }
    }.getOrDefault(emptyList())

    /** The one playing, else the last paused; Android lists the most recently active first. */
    fun read(context: Context): NowPlayingInfo? {
        val all = controllers(context)
        val c = all.firstOrNull { it.playbackState?.state != PlaybackState.STATE_PAUSED } ?: all.firstOrNull() ?: return null
        val m = c.metadata ?: return null
        val title = m.getString(MediaMetadata.METADATA_KEY_DISPLAY_TITLE) ?: m.getString(MediaMetadata.METADATA_KEY_TITLE) ?: return null
        if (title.isBlank()) return null
        val artist = m.getString(MediaMetadata.METADATA_KEY_ARTIST)
            ?: m.getString(MediaMetadata.METADATA_KEY_DISPLAY_SUBTITLE)
            ?: m.getString(MediaMetadata.METADATA_KEY_ALBUM_ARTIST)
        val art = m.getBitmap(MediaMetadata.METADATA_KEY_ART)
            ?: m.getBitmap(MediaMetadata.METADATA_KEY_ALBUM_ART)
            ?: m.getBitmap(MediaMetadata.METADATA_KEY_DISPLAY_ICON)
        val uri = listOf(MediaMetadata.METADATA_KEY_ART_URI, MediaMetadata.METADATA_KEY_ALBUM_ART_URI, MediaMetadata.METADATA_KEY_DISPLAY_ICON_URI)
            .firstNotNullOfOrNull { k -> m.getString(k)?.takeIf { it.startsWith("http") } }
        val playing = c.playbackState?.state != PlaybackState.STATE_PAUSED
        val actions = c.playbackState?.actions ?: 0L
        return NowPlayingInfo(
            c.packageName, title, artist?.takeIf { it.isNotBlank() && it != title }, art, uri, playing,
            canPrevious = actions and PlaybackState.ACTION_SKIP_TO_PREVIOUS != 0L,
            canNext = actions and PlaybackState.ACTION_SKIP_TO_NEXT != 0L,
        )
    }

    /** Counts the pill's own presses, so it reads the new title at once instead of at the next poll. */
    internal val pressed = MutableStateFlow(0)

    /** The previous or the next track. */
    fun skip(context: Context, pkg: String, next: Boolean) {
        val c = controllers(context).firstOrNull { it.packageName == pkg } ?: return
        if (next) c.transportControls.skipToNext() else c.transportControls.skipToPrevious()
        pressed.value++
    }

    /** Play or pause the app's playback. */
    fun toggle(context: Context, pkg: String) {
        val c = controllers(context).firstOrNull { it.packageName == pkg } ?: return
        if (c.playbackState?.state == PlaybackState.STATE_PAUSED) c.transportControls.play() else c.transportControls.pause()
        pressed.value++
    }

    /** Bring the playing app to the front. */
    fun open(context: Context, pkg: String) {
        val pm = context.packageManager
        val intent = pm.getLeanbackLaunchIntentForPackage(pkg) ?: pm.getLaunchIntentForPackage(pkg) ?: return
        runCatching { context.startActivity(intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) }
    }
}

/** Polled every two seconds while Home is in front: sessions come and go without Mayo TV being told. */
@Composable
fun rememberNowPlaying(): NowPlayingInfo? {
    val context = LocalContext.current
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    val info by produceState<NowPlayingInfo?>(null, lifecycle) {
        lifecycle.repeatOnLifecycle(Lifecycle.State.RESUMED) {
            while (true) {
                value = withContext(Dispatchers.IO) { NowPlaying.read(context) }
                // The next poll, or a moment after a press of the pill's own (the app needs that long to switch).
                val presses = NowPlaying.pressed.value
                if (withTimeoutOrNull(POLL_MS) { NowPlaying.pressed.first { it != presses } } != null) delay(AFTER_PRESS_MS)
            }
        }
    }
    return info
}

private const val POLL_MS = 2000L
private const val AFTER_PRESS_MS = 350L

/**
 * Home's "Now playing" pill: cover, title and artist, ▶/❚❚, then ⏮ and ⏭ where the app offers them
 * (Jonathan, 7 Nov 2026), all in one capsule. OK on the title plays or pauses, held OK opens the app;
 * ◀ ▶ reach the two buttons. [requester] lands on the title.
 */
@Composable
internal fun NowPlayingPill(info: NowPlayingInfo, requester: FocusRequester, modifier: Modifier = Modifier) {
    val context = LocalContext.current
    Row(
        modifier.height(60.mpx).background(StageColors.GlassOff, RoundedCornerShape(30.mpx)),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        NowPlayingMain(info, Modifier.focusRequester(requester))
        if (info.canPrevious) SkipButton(OwnTVIcon.SKIP_PREVIOUS) { NowPlaying.skip(context, info.pkg, next = false) }
        if (info.canNext) SkipButton(OwnTVIcon.SKIP_NEXT) { NowPlaying.skip(context, info.pkg, next = true) }
        if (info.canPrevious || info.canNext) androidx.compose.foundation.layout.Spacer(Modifier.size(4.mpx))
    }
}

/** ⏮ or ⏭: a round button inside the pill. */
@Composable
private fun SkipButton(icon: OwnTVIcon, onClick: () -> Unit) {
    StageSurface(
        onClick = onClick,
        radius = 26.mpx,
        focusStyle = StageFocus.FILLED,
        contentAlignment = Alignment.Center,
        modifier = Modifier.size(52.mpx),
    ) { focused ->
        OwnTVIcon(icon, if (focused) StageColors.FxText else StageColors.Text, Modifier.size(24.mpx), filled = true)
    }
}

/** The pill's title part: cover, ▶/❚❚, title and artist. */
@Composable
private fun NowPlayingMain(info: NowPlayingInfo, modifier: Modifier) {
    val context = LocalContext.current
    StageSurface(
        onClick = { NowPlaying.toggle(context, info.pkg) },
        onLongClick = { NowPlaying.open(context, info.pkg) },
        radius = 30.mpx,
        focusStyle = StageFocus.FILLED,
        contentAlignment = Alignment.Center,
        modifier = modifier.height(60.mpx),
    ) { focused ->
        val ink = if (focused) StageColors.FxText else StageColors.Text
        Row(
            Modifier.padding(start = 10.mpx, end = 22.mpx),
            horizontalArrangement = Arrangement.spacedBy(12.mpx),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            val cover = Modifier.size(40.mpx).clip(RoundedCornerShape(8.mpx))
            when {
                info.art != null -> Image(info.art.asImageBitmap(), contentDescription = null, contentScale = ContentScale.Crop, modifier = cover)
                info.artUri != null -> AsyncImage(model = info.artUri, contentDescription = null, contentScale = ContentScale.Crop, modifier = cover)
                else -> AppBadge(info.pkg, Modifier.padding(start = 6.mpx), size = 32.mpx)
            }
            OwnTVIcon(if (info.playing) OwnTVIcon.PAUSE else OwnTVIcon.PLAY, ink, Modifier.size(22.mpx), filled = true)
            Text(info.title, style = stageText(18, 800), color = ink, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.widthIn(max = 300.mpx))
            info.artist?.let {
                Text(it, style = stageText(16, 600), color = if (focused) ink else StageColors.Dim, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.widthIn(max = 220.mpx))
            }
        }
    }
}

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
import kotlinx.coroutines.withContext
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
        return NowPlayingInfo(c.packageName, title, artist?.takeIf { it.isNotBlank() && it != title }, art, uri, playing)
    }

    /** Play or pause the app's playback. */
    fun toggle(context: Context, pkg: String) {
        val c = controllers(context).firstOrNull { it.packageName == pkg } ?: return
        if (c.playbackState?.state == PlaybackState.STATE_PAUSED) c.transportControls.play() else c.transportControls.pause()
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
                delay(POLL_MS)
            }
        }
    }
    return info
}

private const val POLL_MS = 2000L

/** Home's "Now playing" pill: cover, title and artist, ▶/❚❚. OK plays or pauses; held OK opens the app. */
@Composable
internal fun NowPlayingPill(info: NowPlayingInfo, modifier: Modifier = Modifier) {
    val context = LocalContext.current
    StageSurface(
        onClick = { NowPlaying.toggle(context, info.pkg) },
        onLongClick = { NowPlaying.open(context, info.pkg) },
        radius = 30.mpx,
        focusStyle = StageFocus.FILLED,
        idle = Modifier.clip(RoundedCornerShape(30.mpx)).background(StageColors.GlassOff),
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

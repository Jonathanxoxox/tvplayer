package tv.own.owntv.player

import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shadow
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.tv.material3.Text
import tv.own.owntv.R
import tv.own.owntv.core.live.EpgNowNext
import tv.own.owntv.ui.format.rememberSystemTimeFormatter
import tv.own.owntv.ui.theme.LocalMainFontFamily

/**
 * Pixels of a 1920×1080 frame on this screen. The player's OK panel is measured off the classic IPTV
 * player's at full HD (Jonathan, 27 Oct 2026), and it must take the same share of the picture on any
 * box, whatever its density or the app's zoom, so it is sized in these rather than in design pixels.
 */
@Immutable
internal class ScreenUnits(private val unit: Dp, private val density: Density) {
    fun dp(px: Number): Dp = unit * px.toFloat()
    fun sp(px: Number): TextUnit = with(density) { dp(px).toSp() }
    fun toPx(px: Number): Float = with(density) { dp(px).toPx() }
}

@Composable
internal fun rememberScreenUnits(): ScreenUnits {
    val width = LocalConfiguration.current.screenWidthDp
    val density = LocalDensity.current
    return remember(width, density) { ScreenUnits((width / 1920f).dp, density) }
}

/** Type on the picture: the main font at [px] frame pixels, with a tight shadow to lift it off bright video. */
@Composable
internal fun ScreenUnits.text(px: Number, weight: Int): TextStyle = TextStyle(
    fontFamily = LocalMainFontFamily.current,
    fontSize = sp(px),
    fontWeight = FontWeight(weight),
    shadow = Shadow(Color.Black.copy(alpha = 0.6f), Offset(0f, toPx(2)), toPx(4)),
)

/**
 * What the info bar shows for the channel on screen. [channel] names the channel just picked, before
 * its stream (and with it the engine's metadata) is open; [guidePending] is true while its programme
 * is still being looked up.
 */
@Immutable
data class ZapInfo(val guide: EpgNowNext?, val channel: MediaMeta? = null, val guidePending: Boolean = false)

/** The aspect and the bitrate are the player's own business; the card shows FHD, FPS and sound. */
internal fun panelChips(chips: List<String>): List<String> = chips.filterNot { it.contains(':') || it.endsWith("Mbps") }

/**
 * The band's head and line for the channel on screen: the logo, the channel's number and name in small
 * capitals over the programme, its times, the minutes left and the stream's badges, and what follows at
 * the right; under it the programme's progress between its start and its end. The engine's metadata as
 * long as it is about that channel (it carries the rewind line); until the new stream is open, the picked
 * channel's own name, number and logo, and no badges, since those still describe the stream before.
 */
@Composable
internal fun LiveCardBody(
    player: PlaybackEngine,
    info: ZapInfo?,
    nowMs: Long,
    /** The programme's own description under its title: in the panel OK and Info open, not on the zap band. */
    description: Boolean = false,
) {
    val engineMeta by player.currentMeta.collectAsStateWithLifecycle()
    val chips by player.streamChips.collectAsStateWithLifecycle()
    val picked = info?.channel
    val engineCaughtUp = picked == null || engineMeta.title == picked.title
    val meta = if (engineCaughtUp || picked == null) engineMeta else picked
    val name = meta.title?.takeIf { it.isNotBlank() }
    val guide = info?.guide
    val now = guide?.now
    val formatTime = rememberSystemTimeFormatter()
    val separator = stringResource(R.string.content_metadata_separator)
    val facts = buildList {
        if (now != null) {
            add(stringResource(R.string.content_live_time_range, formatTime(now.startMs), formatTime(now.stopMs)))
            val left = (((now.stopMs - nowMs).coerceAtLeast(0) + 59_999) / 60_000).toInt()
            add(pluralStringResource(R.plurals.tvplayer_card_left, left, left))
        } else if (info?.guidePending != true) {
            add(stringResource(R.string.tvplayer_no_guide))
        }
    }
    val next = guide?.next
    CardHeader(
        title = now?.title ?: name.orEmpty(),
        facts = facts,
        badges = if (engineCaughtUp) panelChips(chips) else emptyList(),
        eyebrow = listOfNotNull(meta.localizedSubtitle()?.takeIf { it.isNotBlank() }, name.takeIf { now != null }).joinToString(separator),
        logo = CardLogo(meta.logoUrl, name),
        nextLabel = next?.let { stringResource(R.string.tvplayer_next_at, formatTime(it.startMs)) },
        nextValue = next?.title,
    )
    // What the programme is about, as the guide's block shows it, without leaving the channel (Jonathan,
    // 31 Oct 2026). Under the title, beside the logo, three lines at most; the band after a channel change
    // stays short and leaves it out.
    val about = now?.description?.trim()?.takeIf { description && it.isNotEmpty() }
    if (about != null) {
        val u = rememberScreenUnits()
        Text(
            about,
            style = u.cardText(20, 400),
            color = Color.White.copy(alpha = 0.74f),
            maxLines = 3,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.padding(start = u.dp(96 + 28), top = u.dp(18)).widthIn(max = u.dp(1180)),
        )
    }
    // Without a programme the room stays, so the band does not change height when the guide arrives.
    val span = now?.let { (it.stopMs - it.startMs).toFloat() } ?: 0f
    CardLine(
        start = now?.let { formatTime(it.startMs) }.orEmpty(),
        end = now?.let { formatTime(it.stopMs) }.orEmpty(),
        progress = { if (now == null || span <= 0f) 0f else (nowMs - now.startMs) / span },
        modifier = Modifier.alpha(if (now == null) 0f else 1f),
    )
}

/** The band along the bottom after a channel change: the programme and its line, nothing to choose. */
@Composable
internal fun ZapInfoBar(player: PlaybackEngine, info: ZapInfo?, modifier: Modifier = Modifier) {
    // The card is up for a few seconds, so the moment it opened is precise enough.
    val nowMs = remember(info?.guide) { System.currentTimeMillis() }
    PlayerCard(modifier) { LiveCardBody(player, info, nowMs) }
}

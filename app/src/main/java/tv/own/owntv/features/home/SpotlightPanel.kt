package tv.own.owntv.features.home

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.tv.material3.Text
import coil3.compose.AsyncImage
import tv.own.owntv.R
import tv.own.owntv.core.home.HeroItem
import tv.own.owntv.ui.components.OwnTVIcon
import tv.own.owntv.ui.stage.StageFocus
import tv.own.owntv.ui.stage.StageSurface
import tv.own.owntv.ui.theme.StageColors
import tv.own.owntv.ui.theme.mpx
import tv.own.owntv.ui.theme.stageAccent
import tv.own.owntv.ui.theme.stageText

/**
 * The Spotlight's text side for a [Highlight]: why it is there, what it is, and its one button — Watch, To
 * the match, Watch on. ◀ ▶ on the button go through the highlights; the dots say how many there are.
 */
@Composable
internal fun HighlightInfo(
    hl: Highlight,
    count: Int,
    index: Int,
    onStep: (Int) -> Unit,
    onOpen: () -> Unit,
    onFocus: () -> Unit,
    /** Home's way into the row, when it has one. */
    focusRequester: FocusRequester?,
    /** The stage's own handle on the button, for ▲ from the strip. */
    ownRequester: FocusRequester,
    track: (FocusRequester) -> Unit,
    now: Long,
    modifier: Modifier = Modifier,
) {
    val accent = stageAccent.accent
    Column(modifier, verticalArrangement = Arrangement.spacedBy(14.mpx)) {
        Text(reasonLabel(hl, now), style = stageText(17, 800), color = accent, maxLines = 1, overflow = TextOverflow.Ellipsis)
        Text(titleOf(hl), style = stageText(64, 800), color = StageColors.Text, maxLines = 2, overflow = TextOverflow.Ellipsis)
        lineOf(hl, now)?.let { Text(it, style = stageText(19, 600), color = StageColors.Dim, maxLines = 1, overflow = TextOverflow.Ellipsis) }
        // What it is about, from the guide: two lines, as the streaming apps show it.
        (hl as? Highlight.Pick)?.pick?.description?.let {
            Text(it, style = stageText(18, 500), color = StageColors.Text, maxLines = 2, overflow = TextOverflow.Ellipsis)
        }
        Row(Modifier.padding(top = 10.mpx), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(20.mpx)) {
            StageSurface(
                onClick = onOpen,
                radius = 30.mpx,
                focusStyle = StageFocus.FILLED,
                idle = Modifier.clip(RoundedCornerShape(30.mpx)).background(StageColors.ControlFill),
                contentAlignment = Alignment.Center,
                modifier = Modifier
                    .height(60.mpx)
                    .then(if (focusRequester != null) Modifier.focusRequester(focusRequester) else Modifier)
                    .focusRequester(ownRequester)
                    .tracked(ownRequester, track)
                    .onFocusChanged { if (it.hasFocus) onFocus() }
                    .onPreviewKeyEvent { e ->
                        if (e.type != KeyEventType.KeyDown) return@onPreviewKeyEvent false
                        when {
                            e.key == Key.DirectionRight && index < count - 1 -> { onStep(1); true }
                            e.key == Key.DirectionLeft && index > 0 -> { onStep(-1); true }
                            else -> false
                        }
                    },
            ) { focused ->
                Row(Modifier.padding(horizontal = 28.mpx), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.mpx)) {
                    OwnTVIcon(OwnTVIcon.PLAY, if (focused) StageColors.FxText else StageColors.Text, Modifier.size(22.mpx), filled = true)
                    Text(buttonLabel(hl, now), style = stageText(20, 800), color = if (focused) StageColors.FxText else StageColors.Text, maxLines = 1, overflow = TextOverflow.Ellipsis)
                }
            }
            if (count > 1) Row(horizontalArrangement = Arrangement.spacedBy(8.mpx), verticalAlignment = Alignment.CenterVertically) {
                repeat(count) { i ->
                    Box(Modifier.size(if (i == index) 24.mpx else 8.mpx, 8.mpx).clip(RoundedCornerShape(4.mpx)).background(if (i == index) accent else StageColors.Dim))
                }
            }
        }
    }
}

/** The picture side for a highlight that has no live picture: the teams' crests, or the film's still. */
@Composable
internal fun HighlightPicture(hl: Highlight) {
    when (hl) {
        is Highlight.Sport -> Row(
            Modifier.fillMaxSize(),
            horizontalArrangement = Arrangement.spacedBy(60.mpx, Alignment.CenterHorizontally),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            listOfNotNull(hl.match.home, hl.match.away).forEach { c ->
                Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(14.mpx)) {
                    AsyncImage(model = c.team.logoUrl, contentDescription = null, contentScale = ContentScale.Fit, modifier = Modifier.size(180.mpx))
                    Text(c.score?.text.orEmpty(), style = stageText(54, 800), color = StageColors.Text, maxLines = 1, overflow = TextOverflow.Ellipsis)
                }
            }
        }
        is Highlight.Resume -> AsyncImage(
            model = when (val item = hl.item) {
                is HeroItem.MovieHero -> item.movie.backdropUrl ?: item.movie.posterUrl
                is HeroItem.SeriesHero -> item.series.backdropUrl ?: item.series.posterUrl
                is HeroItem.LiveHero -> null
            },
            contentDescription = null,
            contentScale = ContentScale.Crop,
            modifier = Modifier.fillMaxSize(),
        )
        is Highlight.Live, is Highlight.Pick -> Unit
    }
}

@Composable
private fun reasonLabel(hl: Highlight, now: Long): String {
    val pick = (hl as? Highlight.Pick)?.pick
    // Later today: when and where, whatever kind of pick it is.
    if (pick != null && pick.startMs > now) {
        return stringResource(R.string.home_hl_later, clock(pick.startMs), pick.channel.name)
    }
    return when (hl.reason) {
    HighlightReason.SPORT_LIVE -> stringResource(R.string.home_hl_live, (hl as? Highlight.Sport)?.match?.leagueName.orEmpty())
    HighlightReason.SPORT_LATER -> stringResource(
        R.string.home_hl_later,
        clock((hl as? Highlight.Sport)?.match?.startMs ?: 0L),
        (hl as? Highlight.Sport)?.match?.leagueName.orEmpty(),
    )
    HighlightReason.SPORT_SOON -> stringResource(
        R.string.home_hl_soon,
        android.text.format.DateFormat.getTimeFormat(LocalContext.current).format(java.util.Date((hl as? Highlight.Sport)?.match?.startMs ?: 0L)),
    )
    HighlightReason.HIGHLIGHT -> stringResource(R.string.home_hl_highlight, pick?.channel?.name.orEmpty())
    HighlightReason.TIP -> stringResource(R.string.home_hl_tip, pick?.channel?.name.orEmpty())
    HighlightReason.DOC -> stringResource(R.string.home_hl_doc, pick?.channel?.name.orEmpty())
    HighlightReason.HABIT -> stringResource(R.string.home_hl_habit, (hl as? Highlight.Live)?.channel?.name.orEmpty())
    HighlightReason.RESUME -> stringResource(R.string.home_hl_resume)
    HighlightReason.NOW -> stringResource(R.string.home_hl_now, (hl as? Highlight.Live)?.channel?.name.orEmpty())
    }
}

/** A time of day as the box shows it (20:15). */
@Composable
private fun clock(ms: Long): String = android.text.format.DateFormat.getTimeFormat(LocalContext.current).format(java.util.Date(ms))

@Composable
private fun titleOf(hl: Highlight): String = when (hl) {
    is Highlight.Sport -> listOfNotNull(hl.match.home?.team?.shortName, hl.match.away?.team?.shortName)
        .takeIf { it.size == 2 }?.let { stringResource(R.string.home_hl_versus, it[0], it[1]) } ?: hl.match.title
    is Highlight.Live -> hl.programme?.title ?: hl.channel.name
    is Highlight.Pick -> hl.pick.title
    is Highlight.Resume -> when (val item = hl.item) {
        is HeroItem.MovieHero -> item.movie.name
        is HeroItem.SeriesHero -> item.series.name
        is HeroItem.LiveHero -> item.channel.name
    }
}

@Composable
private fun lineOf(hl: Highlight, now: Long): String? = when (hl) {
    is Highlight.Sport -> hl.match.clock?.display ?: hl.match.statusText.ifBlank { null }
    is Highlight.Live -> hl.programme?.let { timeLeftText(((it.stopMs - now).coerceAtLeast(0L) + 59_999L) / 60_000L) }
    // On now: how long it still runs; later: from when to when.
    is Highlight.Pick -> if (hl.pick.startMs <= now) {
        timeLeftText(((hl.pick.stopMs - now).coerceAtLeast(0L) + 59_999L) / 60_000L)
    } else {
        stringResource(R.string.home_stage_time_span, clock(hl.pick.startMs), clock(hl.pick.stopMs))
    }
    is Highlight.Resume -> (hl.item as? HeroItem.SeriesHero)?.let {
        stringResource(R.string.content_season_episode, it.episode.seasonNumber, it.episode.episodeNumber)
    }
}

@Composable
private fun buttonLabel(hl: Highlight, now: Long): String = stringResource(
    when (hl) {
        is Highlight.Live -> R.string.home_hl_watch
        // Not on yet: the button takes you to the channel, which shows what runs before it.
        is Highlight.Pick -> if (hl.pick.startMs > now) R.string.home_hl_to_channel else R.string.home_hl_watch
        is Highlight.Sport -> R.string.home_hl_to_match
        is Highlight.Resume -> R.string.home_hl_continue
    },
)

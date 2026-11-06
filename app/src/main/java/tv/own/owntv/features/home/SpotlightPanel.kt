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
 * the match, Watch on. ◀ ▶ on the button go through the highlights, round from the last to the first; the
 * dots say how many there are. A film shows its [rating] (TMDB), year and kind.
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
    rating: Double? = null,
) {
    val accent = stageAccent.accent
    Column(modifier, verticalArrangement = Arrangement.spacedBy(14.mpx)) {
        Text(reasonLabel(hl, now), style = stageText(17, 800), color = accent, maxLines = 1, overflow = TextOverflow.Ellipsis)
        Text(titleOf(hl), style = stageText(64, 800), color = StageColors.Text, maxLines = 2, overflow = TextOverflow.Ellipsis)
        FilmFacts(hl, rating)
        lineOf(hl, now)?.let { Text(it, style = stageText(19, 600), color = StageColors.Dim, maxLines = 1, overflow = TextOverflow.Ellipsis) }
        // What it is about: two lines, as the streaming apps show it (Jonathan, 6 Nov 2026: on every highlight
        // that has one, not only the guide's picks).
        descriptionOf(hl)?.let { SpotlightDescription(it) }
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
                            e.key == Key.DirectionRight && count > 1 -> { onStep(1); true }
                            e.key == Key.DirectionLeft && count > 1 -> { onStep(-1); true }
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
                    // Before kick-off there is no score: "0 0" read as a match already on.
                    if (hl.match.state != tv.own.owntv.core.sports.model.MatchState.PRE) {
                        Text(c.score?.text.orEmpty(), style = stageText(54, 800), color = StageColors.Text, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    }
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
    HighlightReason.FILM -> stringResource(R.string.home_hl_film, pick?.channel?.name.orEmpty())
    HighlightReason.PREMIERE -> stringResource(R.string.home_hl_premiere, pick?.channel?.name.orEmpty())
    HighlightReason.HIGHLIGHT -> stringResource(R.string.home_hl_highlight, pick?.channel?.name.orEmpty())
    HighlightReason.TIP -> stringResource(R.string.home_hl_tip, pick?.channel?.name.orEmpty())
    HighlightReason.DOC -> stringResource(R.string.home_hl_doc, pick?.channel?.name.orEmpty())
    HighlightReason.HABIT -> stringResource(R.string.home_hl_habit, (hl as? Highlight.Live)?.channel?.name.orEmpty())
    HighlightReason.RESUME -> stringResource(R.string.home_hl_resume)
    HighlightReason.NOW -> stringResource(R.string.home_hl_now, (hl as? Highlight.Live)?.channel?.name.orEmpty())
    }
}

/**
 * A film's facts under its title: ★ 7,8 (TMDB, in the rating chips' gold) · 2023 · Thriller. Nothing for a
 * highlight that is not a film, nor for a film the guide and TMDB say nothing more about.
 */
@Composable
private fun FilmFacts(hl: Highlight, rating: Double?) {
    val pick = (hl as? Highlight.Pick)?.pick?.takeIf { it.film } ?: return
    val rest = listOfNotNull(pick.year?.toString(), pick.genre).joinToString(" · ")
    if (rating == null && rest.isEmpty()) return
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.mpx)) {
        if (rating != null) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.mpx)) {
                OwnTVIcon(OwnTVIcon.STAR, StageColors.RatingStar, Modifier.size(20.mpx), filled = true)
                Text(
                    tv.own.owntv.features.shell.components.vodRating(rating),
                    style = stageText(20, 800), color = StageColors.Text, maxLines = 1, overflow = TextOverflow.Ellipsis,
                )
            }
        }
        if (rating != null && rest.isNotEmpty()) {
            Text("·", style = stageText(19, 600), color = StageColors.Dim, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
        if (rest.isNotEmpty()) {
            Text(rest, style = stageText(19, 600), color = StageColors.Dim, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
    }
}

/**
 * A highlight's short description: the guide's for a pick, the EPG's for a channel's programme, the episode's
 * (else the series') plot or the film's for watching on. A match has none: its score and minute say it.
 */
internal fun descriptionOf(hl: Highlight): String? = when (hl) {
    is Highlight.Pick -> hl.pick.description
    is Highlight.Live -> hl.programme?.description
    is Highlight.Resume -> when (val item = hl.item) {
        is HeroItem.MovieHero -> item.movie.plot
        is HeroItem.SeriesHero -> item.episode.plot ?: item.series.plot
        is HeroItem.LiveHero -> null
    }
    is Highlight.Sport -> null
}?.let(::shortDescription)

/** One paragraph of plain text, line breaks and runs of spaces folded; null when nothing is left. */
internal fun shortDescription(text: String): String? =
    text.replace(WHITESPACE, " ").trim().takeIf { it.length >= MIN_DESCRIPTION }

private val WHITESPACE = Regex("\\s+")

/** Shorter than this it is a label ("Spielfilm", "n/a"), not a description. */
private const val MIN_DESCRIPTION = 12

/** The two lines of what it is about, under the title and its facts. */
@Composable
internal fun SpotlightDescription(text: String, modifier: Modifier = Modifier) {
    Text(text, style = stageText(18, 500), color = StageColors.Text, maxLines = 2, overflow = TextOverflow.Ellipsis, modifier = modifier)
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
    // Before kick-off neither a minute ("0'") nor a status: the label above already says when.
    is Highlight.Sport -> if (hl.match.state == tv.own.owntv.core.sports.model.MatchState.PRE) null
        else hl.match.clock?.display ?: hl.match.statusText.ifBlank { null }
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

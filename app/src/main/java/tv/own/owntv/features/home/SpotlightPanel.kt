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
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
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
import tv.own.owntv.ui.theme.mpxSp
import tv.own.owntv.ui.theme.stageAccent
import tv.own.owntv.ui.theme.stageText

/**
 * The Spotlight's text side for a [Highlight]: why it is there, what it is, and its one button — Watch, To
 * the match, Watch on. ▶ on the button goes through the highlights, round from the last to the first; ◀ goes
 * back, and on the first one on to the menu at the left. The dots say how many there are. A film shows its [rating] (TMDB), year and kind.
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
    /**
     * A second button: a film also in the playlist's library opens its page in Movies; a match opens its page
     * in Sports ([secondIcon] says which).
     */
    onOnDemand: (() -> Unit)? = null,
    secondIcon: OwnTVIcon = OwnTVIcon.MOVIES,
    /** The top tier, when this highlight is in it: a gold badge before its label. */
    tier: HighlightTier? = null,
    /** A match's text, which [descriptionOf] cannot know: matchday, venue, table ([sportText]). */
    sportText: String? = null,
) {
    val accent = stageAccent.accent
    val onDemandButton = remember { FocusRequester() }
    Column(modifier, verticalArrangement = Arrangement.spacedBy(14.mpx)) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(14.mpx)) {
            if (tier != null) TierBadge(tier)
            Text(reasonLabel(hl, now, badged = tier != null), style = stageText(17, 800), color = accent, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
        Text(titleOf(hl), style = stageText(64, 800), color = StageColors.Text, maxLines = 2, overflow = TextOverflow.Ellipsis)
        FilmFacts(hl, rating)
        FilmCast(hl)
        lineOf(hl, now)?.let { Text(it, style = stageText(19, 600), color = StageColors.Dim, maxLines = 1, overflow = TextOverflow.Ellipsis) }
        // What it is about: two lines, as the streaming apps show it (Jonathan, 6 Nov 2026: on every highlight
        // that has one, not only the guide's picks).
        (descriptionOf(hl) ?: sportText)?.let { SpotlightDescription(it) }
        Row(Modifier.padding(top = 10.mpx), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(20.mpx)) {
            // Round buttons with their icon alone (Jonathan, 7 Nov 2026: "cleaner").
            StageSurface(
                onClick = onOpen,
                radius = 30.mpx,
                focusStyle = StageFocus.FILLED,
                idle = Modifier.clip(RoundedCornerShape(30.mpx)).background(StageColors.ControlFill),
                contentAlignment = Alignment.Center,
                modifier = Modifier
                    .size(60.mpx)
                    .then(if (focusRequester != null) Modifier.focusRequester(focusRequester) else Modifier)
                    .focusRequester(ownRequester)
                    .tracked(ownRequester, track)
                    .onFocusChanged { if (it.hasFocus) onFocus() }
                    .onPreviewKeyEvent { e ->
                        if (e.type != KeyEventType.KeyDown) return@onPreviewKeyEvent false
                        when {
                            // ▶ goes to On demand when the film has it; from there ▶ goes on to the next highlight.
                            e.key == Key.DirectionRight && onOnDemand != null -> { runCatching { onDemandButton.requestFocus() }; true }
                            e.key == Key.DirectionRight && count > 1 -> { onStep(1); true }
                            // ◀ goes back through the highlights, but on the first it leaves for the menu on
                            // the left as from every other row, rather than round to the last (Jonathan, 7 Nov
                            // 2026: "I'm caught in a loop and have to go down to the channels to get left").
                            e.key == Key.DirectionLeft && index > 0 -> { onStep(-1); true }
                            else -> false
                        }
                    },
            ) { focused ->
                OwnTVIcon(OwnTVIcon.PLAY, if (focused) StageColors.FxText else StageColors.Text, Modifier.size(24.mpx), filled = true)
            }
            // The same film in the playlist's library: its page in Movies, to watch it from the start or keep it for
            // later (Jonathan, 6 and 7 Nov 2026).
            if (onOnDemand != null) StageSurface(
                onClick = onOnDemand,
                radius = 30.mpx,
                focusStyle = StageFocus.FILLED,
                idle = Modifier.clip(RoundedCornerShape(30.mpx)).background(StageColors.ControlFill),
                contentAlignment = Alignment.Center,
                modifier = Modifier
                    .size(60.mpx)
                    .focusRequester(onDemandButton)
                    .onFocusChanged { if (it.hasFocus) onFocus() }
                    .onPreviewKeyEvent { e ->
                        if (e.type != KeyEventType.KeyDown) return@onPreviewKeyEvent false
                        when (e.key) {
                            // ◀ back to the main button; ▶ on to the next highlight, focus on its main button.
                            Key.DirectionLeft -> { runCatching { ownRequester.requestFocus() }; true }
                            Key.DirectionRight -> {
                                if (count > 1) { runCatching { ownRequester.requestFocus() }; onStep(1) }
                                true
                            }
                            else -> false
                        }
                    },
            ) { focused ->
                OwnTVIcon(secondIcon, if (focused) StageColors.FxText else StageColors.Text, Modifier.size(24.mpx))
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
/**
 * A match without a programme picture (Jonathan, 6 Nov 2026, variant A "Vereinsfarben"): the screen split on a
 * slant between the two clubs' colours, a white seam between them, each crest large on its own colour; the score
 * under them once the match is on. The Spotlight's washes keep the text on the left readable.
 */
@Composable
internal fun SportColorsBackdrop(
    match: tv.own.owntv.core.sports.model.Match,
    /** The crests' size: the Spotlight's 240; a tile on the sports page passes its own. */
    crestSize: androidx.compose.ui.unit.Dp = 240.mpx,
    /** The score under the crests once the match is on; a tile shows it in its own line instead. */
    showScore: Boolean = true,
    /** Where the crests' middles sit, across and down, as fractions of the box. */
    crestX: Pair<Float, Float> = 0.50f to 0.80f,
    crestY: Float = 0.42f,
) {
    val (homeColor, awayColor) = remember(match.key) { ClubColors.pair(match.home?.team, match.away?.team) }
    Box(Modifier.fillMaxSize()) {
        androidx.compose.foundation.Canvas(Modifier.fillMaxSize()) {
            val w = size.width
            val h = size.height
            // The seam leans like the board: further right at the top than at the bottom.
            val top = w * 0.66f
            val bottom = w * 0.56f
            val home = androidx.compose.ui.graphics.Path().apply {
                moveTo(0f, 0f); lineTo(top, 0f); lineTo(bottom, h); lineTo(0f, h); close()
            }
            val away = androidx.compose.ui.graphics.Path().apply {
                moveTo(top, 0f); lineTo(w, 0f); lineTo(w, h); lineTo(bottom, h); close()
            }
            drawPath(home, androidx.compose.ui.graphics.Brush.linearGradient(listOf(homeColor, homeColor.darken(0.35f)), end = androidx.compose.ui.geometry.Offset(top, h)))
            drawPath(away, androidx.compose.ui.graphics.Brush.linearGradient(listOf(awayColor.darken(0.2f), awayColor), start = androidx.compose.ui.geometry.Offset(bottom, 0f), end = androidx.compose.ui.geometry.Offset(w, h)))
            drawLine(Color.White.copy(alpha = 0.85f), androidx.compose.ui.geometry.Offset(top, 0f), androidx.compose.ui.geometry.Offset(bottom, h), strokeWidth = 6.mpx.toPx())
        }
        val live = showScore && match.state != tv.own.owntv.core.sports.model.MatchState.PRE
        listOf(match.home to crestX.first, match.away to crestX.second).forEach { (c, x) ->
            val team = c?.team ?: return@forEach
            androidx.compose.foundation.layout.BoxWithConstraints(Modifier.fillMaxSize()) {
                Column(
                    Modifier.padding(start = (maxWidth * x - crestSize / 2 - 10.mpx).coerceAtLeast(0.mpx), top = (maxHeight * crestY - crestSize / 2 - 30.mpx).coerceAtLeast(0.mpx)).width(crestSize + 20.mpx),
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.spacedBy(14.mpx),
                ) {
                    AsyncImage(model = team.logoUrl, contentDescription = null, contentScale = ContentScale.Fit, modifier = Modifier.size(crestSize))
                    if (live) Text(c.score?.text.orEmpty(), style = stageText(54, 800), color = StageColors.Text, maxLines = 1, overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis)
                }
            }
        }
    }
}

/** The two clubs' colours for [SportColorsBackdrop]: usable on screen, and told apart from each other. */
internal object ClubColors {
    private val homeFallback = Color(0xFF1D4F91)
    private val awayFallback = Color(0xFF2B2B2B)

    fun pair(home: tv.own.owntv.core.sports.model.Team?, away: tv.own.owntv.core.sports.model.Team?): Pair<Color, Color> {
        val h = pick(home) ?: homeFallback
        var a = pick(away) ?: awayFallback
        // Two red clubs: the away side takes its second colour, else a dark neutral.
        if (distance(h, a) < 0.18f) a = away?.alternateColor?.let(::parse)?.takeIf { usable(it) && distance(h, it) >= 0.18f } ?: awayFallback
        return h to a
    }

    /** The club's colour, or its second one when the first is near white (a white half would swallow the crest). */
    private fun pick(team: tv.own.owntv.core.sports.model.Team?): Color? =
        listOfNotNull(team?.color, team?.alternateColor).mapNotNull(::parse).firstOrNull(::usable)

    private fun usable(c: Color): Boolean = luminance(c) < 0.75f

    fun parse(hex: String): Color? = hex.removePrefix("#").takeIf { it.length == 6 }?.toLongOrNull(16)?.let { Color(0xFF000000 or it) }

    private fun luminance(c: Color) = 0.2126f * c.red + 0.7152f * c.green + 0.0722f * c.blue

    private fun distance(a: Color, b: Color): Float {
        val dr = a.red - b.red; val dg = a.green - b.green; val db = a.blue - b.blue
        return kotlin.math.sqrt((dr * dr + dg * dg + db * db) / 3f)
    }
}

private fun Color.darken(by: Float) = Color(red * (1 - by), green * (1 - by), blue * (1 - by), alpha)

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

/**
 * The top tier's badge: ★ TV-PREMIERE, ★ HIGHLIGHT, ★ TOP-BEWERTET, ★ DEIN SPORT, filled in the gold of the
 * rating star (unchanged in light, like the rating chips), dark ink on it, so it reads from the sofa.
 */
@Composable
private fun TierBadge(tier: HighlightTier) {
    val label = stringResource(
        when (tier) {
            HighlightTier.PREMIERE -> R.string.home_hl_badge_premiere
            HighlightTier.HIGHLIGHT -> R.string.home_hl_badge_highlight
            HighlightTier.TOP_RATED -> R.string.home_hl_badge_top_rated
            HighlightTier.SPORT -> R.string.home_hl_badge_sport
        },
    )
    Row(
        Modifier.clip(RoundedCornerShape(8.mpx)).background(StageColors.RatingStar).padding(horizontal = 12.mpx, vertical = 5.mpx),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(7.mpx),
    ) {
        OwnTVIcon(OwnTVIcon.STAR, BadgeInk, Modifier.size(17.mpx), filled = true)
        Text(label, style = stageText(16, 900), color = BadgeInk, maxLines = 1, overflow = TextOverflow.Ellipsis)
    }
}

/** The ink on the gold badge: near-black, warm, in every Theme. */
private val BadgeInk = androidx.compose.ui.graphics.Color(0xFF1F1500)

@Composable
private fun reasonLabel(hl: Highlight, now: Long, badged: Boolean = false): String {
    val pick = (hl as? Highlight.Pick)?.pick
    // The badge already says premiere or highlight: on now, the label only says where.
    if (badged && pick != null && pick.startMs <= now && (hl.reason == HighlightReason.PREMIERE || hl.reason == HighlightReason.HIGHLIGHT)) {
        return stringResource(R.string.home_hl_now, pick.channel.name)
    }
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

/** What it is about, under the title and its facts: up to eight lines, the Spotlight has the room (Jonathan, 7 Nov 2026). */
@Composable
internal fun SpotlightDescription(text: String, modifier: Modifier = Modifier) {
    Text(
        text, style = stageText(18, 500).copy(lineHeight = (18 * 1.45f).mpxSp), color = StageColors.Text,
        maxLines = 8, overflow = TextOverflow.Ellipsis, modifier = modifier,
    )
}

/**
 * A match's lines for the Spotlight: the matchday and where ("9. Spieltag · Signal Iduna Park"), then both
 * sides' places in the table. Null when nothing is known.
 */
@Composable
internal fun sportText(m: tv.own.owntv.core.sports.model.Match, notes: MatchNotes?, table: tv.own.owntv.core.sports.model.StandingsTable?): String? {
    val place = listOfNotNull(
        notes?.round,
        m.venue?.takeIf { it.isNotBlank() }?.let { stringResource(R.string.home_sport_venue, it) } ?: notes?.line,
    ).joinToString(stringResource(R.string.content_epg_bits_separator)).takeIf { it.isNotBlank() }
    val rows = table?.groups?.flatMap { it.rows }.orEmpty()
    fun row(c: tv.own.owntv.core.sports.model.Competitor?) = c?.let { comp -> rows.firstOrNull { it.team.id == comp.team.id } }
    val home = row(m.home)
    val away = row(m.away)
    val standing = if (home != null && away != null) {
        stringResource(R.string.home_sport_table, sideName(m.home), home.rank, home.points, sideName(m.away), away.rank, away.points)
    } else null
    return listOfNotNull(place, standing).joinToString(stringResource(R.string.home_sport_sentence_separator)).takeIf { it.isNotBlank() }
}

private fun sideName(c: tv.own.owntv.core.sports.model.Competitor?): String =
    c?.team?.let { t -> t.shortName?.takeIf { it.isNotBlank() } ?: t.name }.orEmpty()

/** A film's leads under its facts: "Mit Jason Statham, Li Bingbing, Ruby Rose" (TMDB's billing order). */
@Composable
private fun FilmCast(hl: Highlight) {
    val pick = (hl as? Highlight.Pick)?.pick?.takeIf { it.film } ?: return
    // "Mit Tom Hanks, Matt Damon", and "Regie: Steven Spielberg" on a line of its own: after the actors it was
    // cut off (Jonathan, 7 Nov 2026).
    pick.cast.takeIf { it.isNotEmpty() }?.let {
        Text(
            stringResource(R.string.home_hl_cast, it.joinToString(stringResource(R.string.home_hl_cast_separator))),
            style = stageText(18, 600), color = StageColors.Dim, maxLines = 1, overflow = TextOverflow.Ellipsis,
        )
    }
    pick.director?.let {
        Text(stringResource(R.string.home_hl_director, it), style = stageText(18, 600), color = StageColors.Dim, maxLines = 1, overflow = TextOverflow.Ellipsis)
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


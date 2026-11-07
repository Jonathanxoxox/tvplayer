package tv.own.owntv.features.sports

import androidx.compose.foundation.background
import androidx.compose.foundation.focusGroup
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusProperties
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.focusRestorer
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.tv.material3.Text
import tv.own.owntv.R
import tv.own.owntv.core.database.entity.ChannelEntity
import tv.own.owntv.core.sports.FollowedTeam
import tv.own.owntv.core.sports.GuideSportItem
import tv.own.owntv.core.sports.MatchNotifications
import tv.own.owntv.core.sports.SportsConfig
import tv.own.owntv.core.sports.WatchOption
import tv.own.owntv.core.sports.model.Match
import tv.own.owntv.core.sports.model.MatchState
import tv.own.owntv.core.sports.model.Sport
import tv.own.owntv.ui.components.ChannelLogoTile
import tv.own.owntv.ui.components.OwnTVIcon
import tv.own.owntv.ui.components.OwnTVPopup
import tv.own.owntv.ui.components.longPressMenuGuard
import tv.own.owntv.ui.components.trapAllFocusExit
import tv.own.owntv.ui.stage.StageFocus
import tv.own.owntv.ui.stage.StageMenu
import tv.own.owntv.ui.stage.StageMenuHeader
import tv.own.owntv.ui.stage.StageMenuItem
import tv.own.owntv.ui.stage.StageProgress
import tv.own.owntv.ui.stage.StageRow
import tv.own.owntv.ui.stage.StageSurface
import tv.own.owntv.ui.stage.StageTag
import tv.own.owntv.ui.stage.drawInnerRing
import tv.own.owntv.ui.stage.stageStillBack
import tv.own.owntv.ui.theme.StageColors
import tv.own.owntv.ui.theme.mpx
import tv.own.owntv.ui.theme.stageAccent
import tv.own.owntv.ui.theme.stageText

/** One chip of the hub's first row: the followed teams, or one sport (none chosen = everything). */
internal sealed interface HubChip {
    data object MyTeams : HubChip
    data class One(val sport: Sport) : HubChip
}

/** Sports whose score is a line (sets, innings), not one number a side. */
private val LINE_SCORE_SPORTS = setOf(Sport.TENNIS, Sport.CRICKET, Sport.GOLF, Sport.RACING)

/** How long past kick-off a match still waiting for ESPN's live mark stays under "Heute noch". */
private const val KICKOFF_GRACE_MS = 15 * 60_000L

/** Chips shown before "+N": eight sports. */
private const val SPORT_CHIPS = 8

/**
 * The Sports hub: title and today's counts, sport chips ending in ⟳ (rebuild),
 * *Live now* with the featured first card, *Sport on your channels now* from the guide, *Starting soon*,
 * and the key hints. Focus enters on the featured card. OK and the Play key open a match (channels are
 * found there), Hold OK follows or reminds. With nothing followed, a note says where to choose.
 */
@Composable
fun SportsHubScreen(
    vm: SportsViewModel,
    onOpenMatch: (Match) -> Unit,
    onPlayChannel: (ChannelEntity, List<ChannelEntity>) -> Unit,
    onChildFocused: () -> Unit,
    onEntryHook: (((() -> Boolean)?) -> Unit)?,
    contentStart: Dp,
    modifier: Modifier = Modifier,
    /** Back from a match: the card it was opened from takes focus again. */
    restoreKey: String? = null,
) {
    val state by vm.hub.collectAsStateWithLifecycle()
    val cardFocus = remember { HashMap<String, FocusRequester>() }
    fun focusOf(key: String) = cardFocus.getOrPut(key) { FocusRequester() }
    androidx.compose.runtime.LaunchedEffect(restoreKey) {
        val key = restoreKey ?: return@LaunchedEffect
        for (attempt in 0 until 10) {
            androidx.compose.runtime.withFrameNanos { }
            if (runCatching { focusOf(key).requestFocus() }.isSuccess) break
        }
    }
    val config = state.config
    // Ticks every 30 s: the progress bars move, and a kick-off that has passed leaves "Heute noch" even
    // when no score changes (before, `now` froze at the last recomposition).
    val now by androidx.compose.runtime.produceState(System.currentTimeMillis()) {
        while (true) { kotlinx.coroutines.delay(30_000L); value = System.currentTimeMillis() }
    }
    val playback = org.koin.compose.koinInject<SportsPlayback>()
    // Both chip rows are multi-select: OK toggles a chip, nothing chosen = All. Kept while the app runs;
    // the first visit opens on My teams and Football, so the league row is on screen at once (Jonathan, 7 Nov 2026).
    @Suppress("UNCHECKED_CAST")
    var chips by remember { mutableStateOf(playback.hubChip as? Set<HubChip> ?: setOf(HubChip.MyTeams, HubChip.One(Sport.SOCCER))) }
    @Suppress("UNCHECKED_CAST")
    var leagues by remember { mutableStateOf(playback.hubLeague as? Set<String> ?: emptySet()) }
    androidx.compose.runtime.LaunchedEffect(chips, leagues) { playback.hubChip = chips; playback.hubLeague = leagues }
    var showAllSports by rememberSaveable { mutableStateOf(false) }
    var menuFor by remember { mutableStateOf<Match?>(null) }
    // The menu's Remind me needs a channel: one Find channels already found for that match, if any.
    var menuChannels by remember { mutableStateOf<List<WatchOption>>(emptyList()) }
    androidx.compose.runtime.LaunchedEffect(menuFor) { menuChannels = menuFor?.let { vm.channelsFound(it) }.orEmpty() }

    val myTeamKeys = remember(config.teams) { config.teams.map { it.key }.toSet() }
    fun Match.isMine() = competitors.any { FollowedTeam(sport, it.team.id, it.team.name).key in myTeamKeys }
    val chosenSports = chips.filterIsInstance<HubChip.One>().map { it.sport }.toSet()
    val sportLeagues = config.followedLeagues().mapNotNull(tv.own.owntv.core.sports.SportsCatalog::league).filter { it.sport in chosenSports }
        .sortedWith(compareBy({ config.rankOf(it.sport, it.key).first }, { config.rankOf(it.sport, it.key).second }))
    // A remembered league the profile no longer follows filters nothing.
    val activeLeagues = leagues.filterTo(HashSet()) { k -> sportLeagues.any { it.key == k } }
    fun toggle(c: HubChip) { chips = if (c in chips) chips - c else chips + c }
    fun toggleLeague(k: String) { leagues = if (k in activeLeagues) activeLeagues - k else activeLeagues + k }
    /**
     * Row 1: a match passes when no chip is chosen, or its sport is chosen, or My teams is chosen and it
     * is one of theirs. Row 2: a chosen sport with leagues chosen keeps only those leagues.
     */
    fun Match.passes(): Boolean {
        if (chips.isEmpty()) return true
        val bySport = sport in chosenSports && activeLeagues.filterTo(HashSet()) { k -> sportLeagues.any { it.key == k && it.sport == sport } }
            .let { mine -> mine.isEmpty() || feed in mine }
        return bySport || (HubChip.MyTeams in chips && isMine())
    }
    // ESPN's tennis and golf feeds list a whole tournament; "today" is what is live or starts today.
    val today = state.board.today.values.flatten().distinctBy { it.key }.filter { it.isLive || isToday(it.startMs) }
    val live = today.filter { it.isLive && it.passes() }
        .sortedWith(compareBy<Match>({ config.rankOf(it.sport, it.feed).first }, { config.rankOf(it.sport, it.feed).second }, { !it.isMine() }, { it.startMs }))
    // Coming up, by day: "Heute noch", "Morgen", then the rest. Within a day by kick-off, so the row reads
    // like a schedule; a row headed "Gleich geht's los" showed tomorrow's matches.
    // A match stays here until ESPN marks it live (often a few minutes after kick-off), so it never drops out of both rows.
    val soonByDay = state.board.matches.filter { it.state == MatchState.PRE && it.startMs > now - KICKOFF_GRACE_MS && it.passes() }
        .sortedWith(compareBy<Match>({ it.startMs }, { config.rankOf(it.sport, it.feed).first }, { config.rankOf(it.sport, it.feed).second }))
        .groupBy { daysFromToday(it.startMs).coerceAtMost(2) }
        .mapValues { it.value.take(12) }
    val soon = soonByDay.values.flatten()
    // Today's finished matches with their final score: in the evening the hub showed "8 matches" but only
    // the one still live. Own teams first, then the latest.
    val finished = today.filter { it.state == MatchState.POST && it.passes() }
        .sortedWith(compareBy<Match>({ !it.isMine() }, { -it.startMs })).take(12)
    // The rows under Live and the channels: later today, today's results, tomorrow, then the rest.
    val scheduleRows = ArrayList<Triple<String, Int, List<Match>>>()
    var resultsPlaced = finished.isEmpty()
    for ((day, matches) in soonByDay.toSortedMap()) {
        if (day >= 1 && !resultsPlaced) { scheduleRows += Triple("results", R.string.sports_tab_results, finished); resultsPlaced = true }
        val title = if (day == 0) R.string.tvplayer_sports_later_today else if (day == 1) R.string.content_epg_tomorrow else R.string.tvplayer_sports_coming_up
        scheduleRows += Triple("soon$day", title, matches)
    }
    if (!resultsPlaced) scheduleRows += Triple("results", R.string.sports_tab_results, finished)
    // "Now" means running: what starts later on those channels is in the guide.
    val onNow = state.onNow.values.flatten().filter { it.startMs <= now && now < it.stopMs }.take(6)
    val followedSports = remember(config) { vm.followedSports(config) }

    val featuredFocus = remember { FocusRequester() }
    val chipFocus = remember { FocusRequester() }
    val entry by rememberUpdatedState(if (live.isNotEmpty()) featuredFocus else chipFocus)
    DisposableEffect(onEntryHook) {
        onEntryHook?.invoke { runCatching { entry.requestFocus() }.getOrDefault(false) }
        onDispose { onEntryHook?.invoke(null) }
    }
    Box(modifier.fillMaxSize().onFocusChanged { if (it.hasFocus) onChildFocused() }) {
        LazyColumn(
            Modifier.fillMaxSize().focusGroup(),
            contentPadding = PaddingValues(top = 39.mpx, bottom = 110.mpx),
        ) {
            item(key = "head") {
                Column(Modifier.padding(start = contentStart, end = 360.mpx)) {
                    Text(stringResource(R.string.sports_title), style = stageText(48, 800), color = StageColors.Text, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    if (state.board.paused) {
                        Spacer(Modifier.height(6.mpx))
                        Text(stringResource(R.string.sports_paused), style = stageText(18, 600), color = StageColors.Warn, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    } else if ((state.failed || state.board.failing.isNotEmpty()) && state.board.matches.isNotEmpty()) {
                        // ESPN did not answer: what shows is the stored board, so say how old it is.
                        state.board.rebuiltAt?.let { at ->
                            Spacer(Modifier.height(6.mpx))
                            Text(stringResource(R.string.sports_last_updated, timeText(at)), style = stageText(18, 600), color = StageColors.Warn, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        }
                    }
                }
            }
            item(key = "chips") {
                val shownSports = if (showAllSports) followedSports else followedSports.take(SPORT_CHIPS)
                LazyRow(
                    // Nothing above the chips takes focus; without this, focus search found the rail.
                    Modifier.padding(top = 24.mpx).focusRestorer().focusProperties { up = FocusRequester.Cancel }.focusGroup(),
                    contentPadding = PaddingValues(start = contentStart, end = 64.mpx),
                    horizontalArrangement = Arrangement.spacedBy(12.mpx),
                ) {
                    item { SportsChip(stringResource(R.string.content_epg_all), chips.isEmpty(), Modifier.focusRequester(chipFocus)) { chips = emptySet(); leagues = emptySet() } }
                    item { SportsChip(stringResource(R.string.sports_my_teams), HubChip.MyTeams in chips, icon = OwnTVIcon.STAR) { toggle(HubChip.MyTeams) } }
                    items(shownSports, key = { it.name }) { s ->
                        SportsChip("${sportEmoji(s)} ${sportLabel(s)}", HubChip.One(s) in chips) { toggle(HubChip.One(s)) }
                    }
                    if (!showAllSports && followedSports.size > SPORT_CHIPS) {
                        item { SportsChip("+${followedSports.size - SPORT_CHIPS}", false, dim = true) { showAllSports = true } }
                    }
                    // ⟳: build the whole board again; its progress shows in the bottom status pill.
                    item { SportsChip("", false, icon = OwnTVIcon.REFRESH) { vm.rebuild() } }
                }
            }

            if (sportLeagues.size > 1) {
                item(key = "leagues") {
                    LazyRow(
                        Modifier.padding(top = 12.mpx).focusRestorer().focusGroup(),
                        contentPadding = PaddingValues(start = contentStart, end = 64.mpx),
                        horizontalArrangement = Arrangement.spacedBy(12.mpx),
                    ) {
                        item { SportsChip(stringResource(R.string.content_epg_all), activeLeagues.isEmpty()) { leagues = emptySet() } }
                        items(sportLeagues, key = { it.key }) { l ->
                            val name = if (l.key == "cricket/all") stringResource(R.string.sports_sport_cricket) else l.name
                            SportsChip(name, l.key in activeLeagues) { toggleLeague(l.key) }
                        }
                    }
                }
            }

            val nothing = live.isEmpty() && soon.isEmpty() && onNow.isEmpty() && finished.isEmpty()
            if (config.followedLeagues().isEmpty()) {
                item(key = "none") { NothingFollowedNote(Modifier.padding(start = contentStart, top = 80.mpx, end = 360.mpx)) }
            } else if (nothing) {
                item(key = "empty") {
                    // Never "no matches today" before the board is known: the stored board is still being read,
                    // or (first start) built, and ESPN being unreachable is not the same as a quiet day.
                    val known = state.board.rebuiltAt != null || state.board.matches.isNotEmpty()
                    val unreachable = (state.failed && !known) || (state.board.failing.isNotEmpty() && state.board.today.isEmpty())
                    val text = when {
                        unreachable && !state.rebuilding -> R.string.sports_empty_unreachable
                        !known || state.rebuilding -> R.string.sports_rebuilding
                        else -> R.string.sports_empty_nothing
                    }
                    Text(
                        stringResource(text),
                        style = stageText(22, 600), color = StageColors.Muted,
                        modifier = Modifier.padding(start = contentStart, top = 80.mpx),
                    )
                }
            }

            if (live.isNotEmpty()) {
                item(key = "live") {
                    Column {
                        RowHeader(contentStart, stringResource(R.string.sports_live_now), null, live = true)
                        LazyRow(
                            Modifier.focusRestorer().focusGroup(),
                            contentPadding = PaddingValues(start = contentStart, end = 64.mpx, top = 6.mpx, bottom = 6.mpx),
                            horizontalArrangement = Arrangement.spacedBy(18.mpx),
                        ) {
                            itemsIndexed(live, key = { _, m -> m.key }) { i, m ->
                                MatchCard(
                                    m, featured = i == 0, config = config, now = now,
                                    reminded = false,
                                    onClick = { onOpenMatch(m) }, onPlay = { onOpenMatch(m) }, onHold = { menuFor = m },
                                    modifier = (if (i == 0) Modifier.focusRequester(featuredFocus) else Modifier).focusRequester(focusOf(m.key)),
                                )
                            }
                        }
                    }
                }
            }

            if (onNow.isNotEmpty()) {
                item(key = "onNow") {
                    Column {
                        RowHeader(contentStart, stringResource(R.string.sports_on_channels_now), stringResource(R.string.sports_on_channels_now_hint))
                        Column(Modifier.padding(start = contentStart - 18.mpx, end = 40.mpx).focusGroup()) {
                            onNow.chunked(3).forEach { row ->
                                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(21.mpx)) {
                                    row.forEach { item ->
                                        OnChannelsRow(item, now, Modifier.weight(1f)) {
                                            onPlayChannel(item.channels.first(), item.channels)
                                        }
                                    }
                                    repeat(3 - row.size) { Spacer(Modifier.weight(1f)) }
                                }
                            }
                        }
                    }
                }
            }

            for ((rowKey, title, matches) in scheduleRows) {
                item(key = rowKey) {
                    val context = LocalContext.current
                    Column {
                        RowHeader(contentStart, stringResource(title), null)
                        // A new filter starts the row at its first card, not where the old list was scrolled.
                        val soonState = androidx.compose.foundation.lazy.rememberLazyListState()
                        androidx.compose.runtime.LaunchedEffect(chips, leagues) { soonState.scrollToItem(0) }
                        LazyRow(
                            state = soonState,
                            modifier = Modifier.focusRestorer().focusGroup(),
                            contentPadding = PaddingValues(start = contentStart, end = 64.mpx, top = 6.mpx, bottom = 6.mpx),
                            horizontalArrangement = Arrangement.spacedBy(18.mpx),
                        ) {
                            items(matches, key = { it.key }) { m ->
                                MatchCard(
                                    m, featured = false, config = config, now = now,
                                    reminded = SportsViewModel.reminderKey(m.startMs, MatchNotifications.pair(context, m)) in state.reminders,
                                    onClick = { onOpenMatch(m) }, onPlay = { onOpenMatch(m) }, onHold = { menuFor = m },
                                    modifier = Modifier.focusRequester(focusOf(m.key)),
                                )
                            }
                        }
                    }
                }
            }
        }
    }

    menuFor?.let { m ->
        FollowRemindMenu(
            match = m, config = config, channels = menuChannels, now = now,
            onFollow = { vm.toggleTeam(it) },
            onRemind = { vm.remind(m, menuChannels) },
            onDismiss = { menuFor = null },
        )
    }
}

/** The hub with nothing followed (new users follow nothing): what to do, where, and why to choose with care. */
@Composable
private fun NothingFollowedNote(modifier: Modifier) {
    Column(modifier, verticalArrangement = Arrangement.spacedBy(12.mpx)) {
        Text(stringResource(R.string.sports_empty_nothing_followed), style = stageText(30, 800), color = StageColors.Text, maxLines = 2, overflow = TextOverflow.Ellipsis)
        Text(stringResource(R.string.sports_empty_choose_in), style = stageText(22, 600), color = StageColors.Body, maxLines = 2, overflow = TextOverflow.Ellipsis)
        Text(stringResource(R.string.sports_load_time_warning), style = stageText(19, 400), color = StageColors.Muted, maxLines = 2, overflow = TextOverflow.Ellipsis)
    }
}

/** `.rowh`: a 25.5/800 title (with the LIVE mark on Live now) and a muted 18 px hint on its baseline. */
@Composable
private fun RowHeader(start: Dp, title: String, hint: String?, live: Boolean = false) {
    Row(
        Modifier.padding(start = start, top = 27.mpx, bottom = 10.mpx),
        horizontalArrangement = Arrangement.spacedBy(15.mpx),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (live) LiveMark(stringResource(R.string.player_live), 18f)
        Text(title, style = stageText(25.5f, 800), color = StageColors.Text, maxLines = 1, overflow = TextOverflow.Ellipsis)
        if (hint != null) Text(hint, style = stageText(18, 400), color = StageColors.Muted, maxLines = 1, overflow = TextOverflow.Ellipsis)
    }
}

/** `.chip`: 51 high, pill-round; the chosen one accent 22% with a 1.5 px accent ring. Focused = FILLED. */
@Composable
internal fun SportsChip(
    text: String,
    on: Boolean,
    modifier: Modifier = Modifier,
    icon: OwnTVIcon? = null,
    dim: Boolean = false,
    onLongClick: (() -> Unit)? = null,
    onClick: () -> Unit,
) {
    val a = stageAccent
    val r = 26.mpx
    StageSurface(
        onClick = onClick,
        onLongClick = onLongClick,
        radius = r,
        modifier = modifier.height(51.mpx),
        idle = if (on) {
            Modifier.background(a.accent.copy(alpha = 0.22f), RoundedCornerShape(r))
                .drawBehind { drawInnerRing(a.accent.copy(alpha = 0.7f), 1.5f * 1.mpx.toPx(), r.toPx()) }
        } else Modifier.background(StageColors.ControlFill, RoundedCornerShape(r)),
    ) { focused ->
        val color = when {
            focused -> a.onAccent
            dim -> StageColors.Muted
            on -> StageColors.Text
            else -> StageColors.Body
        }
        Row(
            Modifier.padding(horizontal = 21.mpx),
            horizontalArrangement = Arrangement.spacedBy(10.mpx),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            if (icon != null) tv.own.owntv.ui.components.OwnTVIcon(icon, color, Modifier.size(20.mpx))
            if (text.isNotEmpty()) Text(text, style = stageText(19.5f, 600), color = color, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
    }
}

/**
 * `.mc`: a match card, 324 wide (the featured first card 648, with the big score). League line and
 * minute or kick-off on top, both sides with crest and score, then a Reminder tag or the followed star.
 * No channels: they are found on the match page.
 */
@Composable
internal fun MatchCard(
    m: Match,
    featured: Boolean,
    config: SportsConfig,
    now: Long,
    reminded: Boolean,
    onClick: () -> Unit,
    onPlay: () -> Unit,
    onHold: () -> Unit,
    modifier: Modifier = Modifier,
) {
    // Two sides: a matchday poster (Jonathan, 7 Nov 2026). A race or a tournament stays the plain card.
    if (m.home != null && m.away != null) MatchPoster(m, featured, config, reminded, onClick, onPlay, onHold, modifier)
    else MatchCardPlain(m, featured, config, now, reminded, onClick, onPlay, onHold, modifier)
}

/**
 * A match as a poster: MagentaTV's or TheSportsDB's picture of it ([SportPosters]), else the clubs' colours with
 * their crests; over its lower part the league and kick-off or LIVE with the minute, the two sides and the score.
 */
@Composable
private fun MatchPoster(
    m: Match,
    featured: Boolean,
    config: SportsConfig,
    reminded: Boolean,
    onClick: () -> Unit,
    onPlay: () -> Unit,
    onHold: () -> Unit,
    modifier: Modifier,
) {
    val r = 20.mpx
    val locale = LocalConfiguration.current.locales[0]
    val posters = org.koin.compose.koinInject<SportPosters>()
    val url by androidx.compose.runtime.produceState(posters.peek(m)?.ifEmpty { null }, m.key) { value = posters.posterFor(m) }
    val ink = Color.White
    val soft = Color(0xFFD3E2DF)
    StageSurface(
        onClick = onClick,
        onLongClick = onHold,
        radius = r,
        // A picture fills the tile, so FX's tint showed nothing: the posters' white frame (Jonathan, 7 Nov 2026).
        focusStyle = StageFocus.POSTER,
        contentAlignment = Alignment.TopStart,
        modifier = modifier
            .width((if (featured) 576 else 384).mpx)
            .height((if (featured) 324 else 216).mpx)
            .onPreviewKeyEvent { e ->
                val playKey = e.key == Key.MediaPlay || e.key == Key.MediaPlayPause || e.key == Key.MediaFastForward
                if (playKey && e.type == KeyEventType.KeyUp) onPlay()
                playKey
            },
        idle = Modifier.background(StageColors.Ink.copy(alpha = 0.045f), RoundedCornerShape(r)),
    ) { _ ->
        Box(Modifier.fillMaxSize().clip(RoundedCornerShape(r))) {
            val picture = url
            if (picture != null) {
                coil3.compose.AsyncImage(
                    model = picture, contentDescription = null,
                    contentScale = androidx.compose.ui.layout.ContentScale.Crop, modifier = Modifier.fillMaxSize(),
                )
            } else {
                tv.own.owntv.features.home.SportColorsBackdrop(
                    m, crestSize = (if (featured) 120 else 78).mpx, showScore = false, crestX = 0.30f to 0.72f, crestY = 0.36f,
                )
            }
            // The lower part darkened for the text, whatever the picture.
            Box(
                Modifier.fillMaxSize().background(
                    androidx.compose.ui.graphics.Brush.verticalGradient(
                        0f to Color.Transparent, 0.38f to Color.Transparent, 0.72f to Color(0xB3050C10), 1f to Color(0xF2050C10),
                    ),
                ),
            )
            val followed = m.competitors.any { c -> config.teams.any { it.sport == m.sport && it.id == c.team.id } }
            Box(Modifier.align(Alignment.TopEnd).padding(12.mpx)) {
                if (reminded) StageTag(stringResource(R.string.sports_reminder_set))
                else if (followed) tv.own.owntv.ui.components.OwnTVIcon(OwnTVIcon.STAR, Color(0xFFE7C56A), Modifier.size(22.mpx))
            }
            Column(
                Modifier.align(Alignment.BottomStart).fillMaxWidth().padding(horizontal = 18.mpx, vertical = 14.mpx),
                verticalArrangement = Arrangement.spacedBy(4.mpx),
            ) {
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.mpx)) {
                    if (m.isLive) LiveMark(stringResource(R.string.player_live) + stringResource(R.string.content_epg_bits_separator) + clockText(m), 16f)
                    val day = if (m.isLive) null else dayOf(m.startMs, locale)
                    val time = when {
                        m.isLive -> null
                        m.off || m.state == MatchState.POST -> statusText(m)
                        else -> timeText(m.startMs)
                    }
                    val line = listOfNotNull(leagueName(m), m.subtitle?.takeIf { featured }, day, time).joinToString(stringResource(R.string.content_epg_bits_separator))
                    Text(line, style = stageText(16, 700), color = soft, maxLines = 1, overflow = TextOverflow.Ellipsis)
                }
                Row(verticalAlignment = Alignment.CenterVertically) {
                    val home = m.home!!.team
                    val away = m.away!!.team
                    Text(
                        teamLabel(home) + " – " + teamLabel(away),
                        style = stageText(if (featured) 26 else 20, 800), color = ink, maxLines = 1, overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.weight(1f),
                    )
                    if (m.state != MatchState.PRE && !m.off && m.sport !in LINE_SCORE_SPORTS) {
                        val score = if (config.spoilerFree) "– : –" else "${scoreText(m.sport, m.home!!.score)} – ${scoreText(m.sport, m.away!!.score)}"
                        Text(score, style = stageText(if (featured) 32 else 24, 800), color = ink, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.padding(start = 12.mpx))
                    }
                }
            }
        }
    }
}

/** The card as it was: a race or anything without two sides. */
@Composable
private fun MatchCardPlain(
    m: Match,
    featured: Boolean,
    config: SportsConfig,
    now: Long,
    reminded: Boolean,
    onClick: () -> Unit,
    onPlay: () -> Unit,
    onHold: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val r = 20.mpx
    val locale = LocalConfiguration.current.locales[0]
    StageSurface(
        onClick = onClick,
        onLongClick = onHold,
        radius = r,
        focusStyle = StageFocus.FX,
        contentAlignment = Alignment.TopStart,
        modifier = modifier
            .width((if (featured) 648 else 324).mpx)
            .height(if (featured) 216.mpx else 204.mpx)
            .onPreviewKeyEvent { e ->
                val playKey = e.key == Key.MediaPlay || e.key == Key.MediaPlayPause || e.key == Key.MediaFastForward
                if (playKey && e.type == KeyEventType.KeyUp) onPlay()
                playKey
            },
        idle = Modifier
            .background(StageColors.Ink.copy(alpha = 0.045f), RoundedCornerShape(r))
            .drawBehind { drawInnerRing(Color.White.copy(alpha = 0.05f), 1.mpx.toPx(), r.toPx()) },
    ) { _ ->
        Column(Modifier.fillMaxSize().padding(horizontal = 20.mpx, vertical = 16.mpx)) {
            // League line · minute (red while live) or kick-off.
            Row(verticalAlignment = Alignment.CenterVertically) {
                val day = if (m.isLive) null else dayOf(m.startMs, locale)
                val league = listOfNotNull(leagueName(m), m.subtitle?.takeIf { featured }, day).joinToString(stringResource(R.string.content_epg_bits_separator))
                Text(league, style = stageText(16.5f, 600), color = StageColors.Muted, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f))
                val right = when {
                    m.off || m.state == MatchState.POST -> statusText(m)
                    else -> timeText(m.startMs)
                }
                // Live: the red dot and LIVE, so a live card stands out among ones still to come.
                if (m.isLive) LiveMark(stringResource(R.string.player_live) + stringResource(R.string.content_epg_bits_separator) + clockText(m), 17f)
                else Text(right, style = stageText(17, 800), maxLines = 1, overflow = TextOverflow.Ellipsis, color = StageColors.Muted)
            }
            val spoiler = config.spoilerFree
            // The big "2 – 1" only for one score a side; sets and innings read as the two sides' lines.
            if (featured && m.sport !in LINE_SCORE_SPORTS) FeaturedScore(m, spoiler) else Sides(m, spoiler)
            Spacer(Modifier.weight(1f))
            val followed = m.competitors.any { c -> config.teams.any { it.sport == m.sport && it.id == c.team.id } }
            CardFooter(m.venue, reminded, followed)
        }
    }
}

/** Two sides, each a 42 px crest, the name and the score; the losing side in muted grey. */
@Composable
private fun Sides(m: Match, spoiler: Boolean) {
    if (m.competitors.size < 2) {
        // Racing, a card: the session and its subtitle.
        Spacer(Modifier.height(12.mpx))
        Text(m.title, style = stageText(21, 700), color = StageColors.Text, maxLines = 1, overflow = TextOverflow.Ellipsis)
        m.subtitle?.let { Text(it, style = stageText(19, 600), color = StageColors.Muted, maxLines = 1, overflow = TextOverflow.Ellipsis) }
        return
    }
    listOfNotNull(m.home, m.away).forEach { c ->
        Spacer(Modifier.height(12.mpx))
        val lost = c.winner == false
        Row(horizontalArrangement = Arrangement.spacedBy(13.mpx), verticalAlignment = Alignment.CenterVertically) {
            Crest(c.team, 42)
            Text(teamLabel(c.team), style = stageText(21, 700), color = if (lost) StageColors.Muted else StageColors.Text, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f))
            if (m.state != MatchState.PRE && !m.off) {
                val s = if (spoiler) "–" else scoreText(m.sport, c.score)
                Text(s, style = stageText(if (s.length > 2) 21f else 27f, 800), color = if (lost) StageColors.Muted else StageColors.Text, maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
        }
    }
}

/** The featured card's middle: crest and name each side, the score 51 px in between. */
@Composable
private fun FeaturedScore(m: Match, spoiler: Boolean) {
    val home = m.home
    val away = m.away
    if (home == null || away == null) { Sides(m, spoiler); return }
    Row(Modifier.fillMaxWidth().padding(top = 12.mpx), verticalAlignment = Alignment.CenterVertically) {
        Row(Modifier.weight(1f), horizontalArrangement = Arrangement.spacedBy(13.mpx), verticalAlignment = Alignment.CenterVertically) {
            Crest(home.team, 60)
            Text(teamLabel(home.team), style = stageText(21, 700), color = StageColors.Text, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
        val score = if (spoiler) "– : –" else "${scoreText(m.sport, home.score)} – ${scoreText(m.sport, away.score)}"
        Text(score, style = stageText(51, 800), color = StageColors.Text, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.padding(horizontal = 18.mpx))
        Row(Modifier.weight(1f), horizontalArrangement = Arrangement.spacedBy(13.mpx, Alignment.End), verticalAlignment = Alignment.CenterVertically) {
            Text(teamLabel(away.team), style = stageText(21, 700), color = StageColors.Text, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Crest(away.team, 60)
        }
    }
}

/** `.ft`: the card's foot — the venue (ESPN's), then a Reminder tag or the star of a followed side. */
@Composable
private fun CardFooter(venue: String?, reminded: Boolean, followed: Boolean) {
    Box(Modifier.fillMaxWidth().height(1.mpx).background(Color.White.copy(alpha = 0.07f)))
    Spacer(Modifier.height(12.mpx))
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.mpx)) {
        Text(venue.orEmpty(), style = stageText(16, 500), color = StageColors.Muted, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f))
        if (reminded) StageTag(stringResource(R.string.sports_reminder_set))
        else if (followed) tv.own.owntv.ui.components.OwnTVIcon(OwnTVIcon.STAR, Color(0xFFE7C56A), Modifier.size(20.mpx))
    }
}

/** `.lrow` in "Sport on your channels now": the logo, "Handball · THW Kiel – SC Magdeburg", "DE| DYN SPORT 1 · 19:00–20:45" and how far it is. */
@Composable
private fun OnChannelsRow(item: GuideSportItem, now: Long, modifier: Modifier, onClick: () -> Unit) {
    val ch = item.channels.first()
    val locale = LocalConfiguration.current.locales[0]
    StageRow(onClick = onClick, height = 96.mpx, modifier = modifier, horizontalPadding = 18.mpx) { _ ->
        ChannelLogoTile(
            logoUrl = ch.logoUrl,
            modifier = Modifier.size(78.mpx, 48.mpx).background(stageStillBack, RoundedCornerShape(10.mpx)),
            fill = Color.Transparent,
        ) {}
        Column(Modifier.weight(1f)) {
            val sep = stringResource(R.string.content_epg_bits_separator)
            Text(guideSportLabel(item.sport) + sep + item.title, style = stageText(20, 700), color = StageColors.Text, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Text(
                ch.name + sep + stringResource(R.string.content_epg_time_range, timeText(item.startMs), timeText(item.stopMs)),
                style = stageText(18, 400), color = StageColors.Muted, maxLines = 1, overflow = TextOverflow.Ellipsis,
            )
            val span = (item.stopMs - item.startMs).coerceAtLeast(1)
            StageProgress(((now - item.startMs).toFloat() / span).coerceIn(0f, 1f), Modifier.padding(top = 7.mpx).fillMaxWidth(0.7f), flat = true)
        }
    }
}

/** Hold OK on a card: follow either side (or stop following), and "Remind me" for a match still to come. */
@Composable
private fun FollowRemindMenu(
    match: Match,
    config: SportsConfig,
    channels: List<WatchOption>,
    now: Long,
    onFollow: (FollowedTeam) -> Unit,
    onRemind: () -> Unit,
    onDismiss: () -> Unit,
) {
    val first = remember { FocusRequester() }
    androidx.compose.runtime.LaunchedEffect(Unit) { kotlinx.coroutines.delay(60); runCatching { first.requestFocus() } }
    OwnTVPopup(onDismissRequest = onDismiss, stageLayout = true) {
        Box(Modifier.fillMaxSize().background(StageColors.Scrim).longPressMenuGuard(), contentAlignment = Alignment.Center) {
            StageMenu(Modifier.width(560.mpx).trapAllFocusExit().focusGroup()) {
                StageMenuHeader(MatchNotifications.pair(LocalContext.current, match), leagueName(match))
                match.competitors.forEachIndexed { i, c ->
                    val team = FollowedTeam(match.sport, c.team.id, c.team.name, c.team.abbreviation)
                    val following = config.teams.any { it.key == team.key }
                    StageMenuItem(
                        text = c.team.name,
                        icon = OwnTVIcon.STAR,
                        iconFilled = following,
                        value = stringResource(if (following) R.string.sports_following else R.string.sports_follow),
                        onClick = { onFollow(team); onDismiss() },
                        modifier = if (i == 0) Modifier.focusRequester(first) else Modifier,
                    )
                }
                if (match.state == MatchState.PRE && match.startMs > now && channels.isNotEmpty()) {
                    StageMenuItem(
                        text = stringResource(R.string.content_remind_me),
                        icon = OwnTVIcon.BELL,
                        onClick = { onRemind(); onDismiss() },
                        modifier = if (match.competitors.isEmpty()) Modifier.focusRequester(first) else Modifier,
                    )
                }
            }
        }
    }
}

/** A side's name on a card: the short name, unless ESPN gives only a code ("GHA" in cricket), then the full name. */
internal fun teamLabel(t: tv.own.owntv.core.sports.model.Team): String =
    if (t.shortName.length <= 4 && t.shortName == t.shortName.uppercase() && t.name.length > t.shortName.length) t.name else t.shortName

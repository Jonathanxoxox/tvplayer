package tv.own.owntv.features.home

import androidx.compose.foundation.background
import androidx.compose.foundation.focusGroup
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.activity.compose.BackHandler
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.graphics.graphicsLayer
import tv.own.owntv.ui.theme.gradientWash
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.focusRestorer
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.layout.boundsInRoot
import androidx.compose.ui.layout.positionInRoot
import androidx.compose.ui.layout.layout
import androidx.compose.ui.zIndex
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import tv.own.owntv.ui.components.OwnTVIcon
import androidx.tv.material3.Text
import tv.own.owntv.R
import tv.own.owntv.core.database.entity.ChannelEntity
import tv.own.owntv.core.database.entity.EpgProgrammeEntity
import tv.own.owntv.core.epg.displayLogoUrl
import tv.own.owntv.features.epg.GuidePreviewSpot
import tv.own.owntv.features.epg.GuideVideo
import tv.own.owntv.features.live.LiveViewModel
import tv.own.owntv.ui.components.ChannelLogoTile
import tv.own.owntv.ui.stage.StageFocus
import tv.own.owntv.ui.stage.StageSurface
import tv.own.owntv.ui.theme.StageColors
import tv.own.owntv.ui.theme.StageRadii
import tv.own.owntv.ui.theme.mpx
import tv.own.owntv.ui.theme.stageAccent
import tv.own.owntv.ui.theme.stageText

/**
 * The Stage layout's channel row (Jonathan, 4 Nov 2026): the focused channel's programme large, a picture
 * beside it, over a strip of logos. ▲ ▼ leave the strip as from any row; ◀ ▶ walk the channels and the
 * stage follows. A held OK opens the row's Hide / Move menu, as on the cards.
 *
 * It plays as the guide and the Sports match page do: OK plays the channel in the picture, with its sound;
 * OK again on the same channel takes it full screen, and Back from there glides it back into the picture,
 * still playing. The picture is the shell's (see [GuidePreviewSpot]): this only says where it goes.
 *
 * [spotlight] is the Spotlight layout (Jonathan, 4 Nov 2026, after the streaming apps): the picture twice as
 * large, and the focused channel starts in it by itself, muted, once focus rests on its logo; OK then takes
 * it full screen with its sound straight away.
 */
@Composable
internal fun HomeStage(
    row: HomeCatalog.Row,
    onNow: Map<Long, EpgProgrammeEntity>,
    onLongClick: (ChannelEntity) -> Unit,
    onFocus: () -> Unit,
    firstItemFocusRequester: FocusRequester?,
    start: Dp,
    track: (FocusRequester) -> Unit,
    liveVm: LiveViewModel,
    /** OK on the channel already playing in the picture: full screen, growing out of it. */
    onPlayFullscreen: (ChannelEntity, List<ChannelEntity>) -> Unit,
    /** False while the player is full screen over Home: Back then belongs to the player. */
    active: Boolean,
    modifier: Modifier = Modifier,
    spotlight: Boolean = false,
    /** Spotlight: what it shows while focus is not in the strip ([SpotlightHighlights]), best first. */
    highlights: List<Highlight> = emptyList(),
    onOpenMatch: (tv.own.owntv.core.sports.model.Match) -> Unit = {},
    onResume: (tv.own.owntv.core.home.HeroItem) -> Unit = {},
    /** The films' TMDB ratings by [GuidePick.key], shown on their highlight; empty when ratings are off. */
    ratings: Map<String, Double> = emptyMap(),
    /** Seconds after which the next highlight shows by itself; 0 = only with ◀ ▶ (Settings › Spotlight). */
    autoAdvanceSecs: Int = 0,
    /** Films the playlist also has on demand: [GuidePick.key] → the movie's id ([FilmLibrary]). */
    onDemand: Map<String, Long> = emptyMap(),
    /** Opens the playlist's movie on its page in Movies. */
    onOpenOnDemand: (Long) -> Unit = {},
    /** The top tier's gold badge over the title ([HighlightTier]; Settings › Spotlight). */
    badges: Boolean = true,
    /** Changes to ask for the Spotlight itself: its first highlight, focus on its button (start, Home button). */
    spotlightFocusRequest: Long = 0L,
    /** A match's first channel as Watch live lists it, searching if needed: the match highlight's play button. */
    findMatchChannel: suspend (tv.own.owntv.core.sports.model.Match) -> ChannelEntity? = { null },
) {
    val stageScope = androidx.compose.runtime.rememberCoroutineScope()
    // A match's channel being looked up for its play button: a second press meanwhile does nothing.
    var matchSearching by remember { mutableStateOf(false) }
    val channels = row.channels
    if (channels.isEmpty()) return
    // Keyed on the category, not the row object: Home builds the row anew (coming back from full screen) and
    // that must not put the text back on the first channel.
    var shownId by remember(row.category.key) { mutableLongStateOf(channels.first().id) }
    val shown = channels.firstOrNull { it.id == shownId } ?: channels.first()
    // The channel in the picture: none until one is picked with OK. It stays while the player is full screen
    // over Home (the page is kept behind the picture), so Back lands on it playing.
    var picked by remember(row.category.key) { mutableStateOf<ChannelEntity?>(null) }
    // Picked with OK it plays with its sound; started by Spotlight on its own, muted.
    var pickedAudible by remember { mutableStateOf(true) }
    // Picked from a highlight's button (a programme on now or tonight): it plays in the picture beside the
    // highlight, as a channel from the strip does (Jonathan, 7 Nov 2026), and stays while focus is on the button.
    var pickedFromHighlight by remember { mutableStateOf(false) }
    var stripFocused by remember { mutableStateOf(false) }
    val previewOn by liveVm.livePreviewEnabled.collectAsStateWithLifecycle()
    LaunchedEffect(picked?.id, previewOn) {
        val ch = picked ?: return@LaunchedEffect
        if (previewOn) liveVm.playPreview(ch, audible = pickedAudible)
    }
    // Spotlight starts nothing by itself (Jonathan, 5 Nov 2026): focus on a logo shows that programme's picture,
    // OK plays it here with its sound, OK again takes it full screen.
    // Spotlight's highlights, Netflix-like: one at a time with its picture, ◀ ▶ on its button go through them.
    // Held by its key, not its place: the list ranks again as the films' ratings come in, and the highlight
    // on screen must stay the one shown rather than jump back to the first.
    var hlKey by remember { mutableStateOf<String?>(null) }
    val hlIndex = highlights.indexOfFirst { it.key == hlKey }.coerceAtLeast(0)
    // ▶ goes round: past the last comes the first again (6 Nov 2026). ◀ steps back, but not past the first:
    // there it leaves for the menu (HighlightInfo, 7 Nov 2026). stepHighlight(-1) is only asked off the first.
    fun stepHighlight(d: Int) {
        if (highlights.isNotEmpty()) hlKey = highlights[(hlIndex + d).mod(highlights.size)].key
    }
    val hl = if (spotlight && !stripFocused) highlights.getOrNull(hlIndex) else null
    // The automatic advance, when switched on: the next highlight after a while, round and round. It waits
    // while the player is full screen and while focus is in the strip; any step starts the wait again.
    if (spotlight && autoAdvanceSecs > 0) LaunchedEffect(hlKey, highlights, stripFocused, active, autoAdvanceSecs, picked) {
        if (!active || stripFocused || highlights.size < 2 || picked != null) return@LaunchedEffect
        delay(autoAdvanceSecs * 1000L)
        stepHighlight(1)
    }
    // ▲ from the strip goes to the highlights' button, which is only there once the strip has let go of focus.
    val hlButton = remember { FocusRequester() }
    var toButton by remember { mutableStateOf(false) }
    LaunchedEffect(toButton) {
        if (!toButton) return@LaunchedEffect
        withFrameNanos { }
        runCatching { hlButton.requestFocus() }
        toButton = false
    }
    // Focus out of the strip (to the highlights, or on to other rows): the channel playing here stops, and the
    // highlights show their pictures again. A moment's grace, so coming back from full screen keeps it playing.
    // Only while Home is in front: full screen takes the focus too, and must keep its picture.
    if (spotlight) LaunchedEffect(stripFocused, active) {
        if (!active || stripFocused || picked == null || pickedFromHighlight) return@LaunchedEffect
        delay(STRIP_LEFT_GRACE_MS)
        liveVm.stopPreview()
        picked = null
    }
    // Back from full screen: focus to the logo of the channel playing here, not wherever Home would put it.
    val pickedLogo = remember { FocusRequester() }
    LaunchedEffect(active) {
        val back = picked ?: return@LaunchedEffect
        if (!active) return@LaunchedEffect
        // The text beside the picture follows: Home may have built the row anew, back on its first channel.
        shownId = back.id
        delay(RETURN_FOCUS_MS)
        runCatching { pickedLogo.requestFocus() }
    }
    // Start and the Home button land on the Spotlight, not on the first channel (Jonathan, 6 Nov 2026): the
    // channel playing here stops, the first highlight shows, focus goes to its button. After the Back-from-
    // full-screen refocus above, which the Home button's way out of the player would otherwise win.
    LaunchedEffect(spotlightFocusRequest) {
        if (spotlightFocusRequest == 0L || !spotlight || highlights.isEmpty()) return@LaunchedEffect
        // Once per request: the stage leaves the list's composition when scrolled away, and coming back must
        // not pull focus up to the Spotlight (or stop its channel) for a request already served.
        if (!StageOnce.first("focus", spotlightFocusRequest)) return@LaunchedEffect
        if (picked != null) { liveVm.stopPreview(); picked = null }
        stripFocused = false
        hlKey = highlights.firstOrNull()?.key
        delay(RETURN_FOCUS_MS + 50)
        withFrameNanos { }
        runCatching { hlButton.requestFocus() }
    }
    // Woken from standby: the channel picked here is not coming back; the highlights show again.
    val wake by tv.own.owntv.launcher.apps.LauncherSystem.wake.collectAsState()
    LaunchedEffect(wake) {
        if (wake == 0L || picked == null) return@LaunchedEffect
        if (!StageOnce.first("wake", wake)) return@LaunchedEffect
        liveVm.stopPreview()
        picked = null
    }
    // Another highlight, or focus into the strip: the programme picked from a highlight stops.
    LaunchedEffect(hlKey, stripFocused) {
        if (!pickedFromHighlight || picked == null) return@LaunchedEffect
        liveVm.stopPreview()
        picked = null
        pickedFromHighlight = false
    }
    // Back first ends the picture, then (with nothing playing) goes on to the menu as before.
    BackHandler(enabled = active && picked != null) { liveVm.stopPreview(); picked = null }
    // No corner left behind for the shell to draw into once the stage is gone.
    DisposableEffect(Unit) { onDispose { GuidePreviewSpot.rect = null } }
    // A highlight other than a live channel shows no picture; the strip's channel shows the one picked.
    val video = picked?.takeIf {
        previewOn && (
            hl == null || (hl is Highlight.Live && hl.channel.id == it.id) || (hl is Highlight.Pick && hl.pick.channel.id == it.id) ||
                (hl is Highlight.Sport && pickedFromHighlight)
            )
    }
    val programme = onNow[shown.id]
    // The picture until the channel plays: the programme's own, from TMDB (ProgrammeArt), else the logo.
    val art = org.koin.compose.koinInject<ProgrammeArt>()
    val picChannel = when (hl) {
        is Highlight.Live -> hl.channel
        is Highlight.Pick -> hl.pick.channel
        else -> shown
    }
    // A pick from the guide brings its own picture.
    val pickArt = (hl as? Highlight.Pick)?.pick
    // A match: its programme's picture from the guide's sports channels, when one shows it.
    val magenta = org.koin.compose.koinInject<MagentaGuide>()
    val sportMatch = (hl as? Highlight.Sport)?.match
    var sportNotes by remember(sportMatch?.key) { mutableStateOf<MatchNotes?>(null) }
    val sportArt by androidx.compose.runtime.produceState<String?>(null, sportMatch?.key) {
        value = null
        val m = sportMatch ?: return@produceState
        fun names(c: tv.own.owntv.core.sports.model.Competitor?) =
            listOfNotNull(c?.team?.name, c?.team?.shortName, c?.team?.abbreviation).filter { it.isNotBlank() }
        value = magenta.matchPicture(m.key, names(m.home), names(m.away), m.startMs)
        sportNotes = magenta.matchNotes(m.key)
    }
    // The table, for the match's text: the board's, as the sports page has it.
    val sportsRepo = org.koin.compose.koinInject<tv.own.owntv.core.sports.SportsRepository>()
    // Only that league's table, so live scores elsewhere on the board do not redraw the stage.
    val sportTable by remember(sportMatch?.feed) {
        val feed = sportMatch?.feed
        sportsRepo.board.map { b -> feed?.let { b.standings[it] } }.distinctUntilChanged()
    }.collectAsState(null)
    val picProgramme = if (hl is Highlight.Live) hl.programme else if (hl == null) programme else null
    // The row's pictures ahead, once Home has settled: browsing then shows them at once. Only their
    // addresses and the files on disk; decoded, thirty 1080p pictures would take some 240 MB.
    val artContext = androidx.compose.ui.platform.LocalContext.current
    LaunchedEffect(row.category.key, onNow.size, highlights.size) {
        delay(ART_PREFETCH_DELAY_MS)
        val wanted = highlights.mapNotNull { (it as? Highlight.Live)?.let { h -> h.programme?.let { p -> p to h.channel.name } } } +
            channels.take(ART_PREFETCH_COUNT).mapNotNull { ch -> onNow[ch.id]?.let { it to ch.name } }
        val loader = coil3.SingletonImageLoader.get(artContext)
        // The picks' own pictures first: they open the carousel.
        highlights.mapNotNull { (it as? Highlight.Pick)?.pick?.picture }.forEach { url ->
            loader.enqueue(
                coil3.request.ImageRequest.Builder(artContext).data(url)
                    .memoryCachePolicy(coil3.request.CachePolicy.DISABLED).build(),
            )
        }
        for ((p, name) in wanted.distinctBy { it.second }) {
            val url = art.backdrop(p, name) ?: continue
            loader.enqueue(
                coil3.request.ImageRequest.Builder(artContext).data(url)
                    .memoryCachePolicy(coil3.request.CachePolicy.DISABLED).build(),
            )
        }
    }
    // A picture known already shows at once; one still to be looked up waits for focus to rest, and the
    // picture shown stays meanwhile (SpotlightArt), so walking the strip never flashes a logo.
    val artState by androidx.compose.runtime.produceState<ArtState>(ArtState.Loading, picChannel.id, picProgramme?.startMs, pickArt?.key) {
        if (pickArt != null) {
            value = pickArt.picture?.let(ArtState::Picture) ?: ArtState.None
            return@produceState
        }
        val p = picProgramme ?: run { value = ArtState.None; return@produceState }
        val known = art.peek(p, picChannel.name)
        if (known != null) {
            value = if (known.isEmpty()) ArtState.None else ArtState.Picture(known)
            return@produceState
        }
        value = ArtState.Loading
        delay(ART_DWELL_MS)
        value = art.backdrop(p, picChannel.name)?.let(ArtState::Picture) ?: ArtState.None
    }
    val now = System.currentTimeMillis()
    // Spotlight: how far the stage's top is below the screen's, in px. Its picture reaches up that far, behind the
    // app buttons and the clock (Jonathan, 7 Nov 2026: "the Spotlight up to the top edge, very faint up there").
    // Read only in layout, so scrolling moves the picture without recomposing the stage.
    var stageTop by remember { androidx.compose.runtime.mutableFloatStateOf(0f) }
    // Where the stage sits with the page at rest (scrolled to the top): the lowest top it has had.
    var restTop by remember { androidx.compose.runtime.mutableFloatStateOf(0f) }
    val rootView = androidx.compose.ui.platform.LocalView.current
    val reach = { stageTop.coerceAtLeast(0f).toInt() }
    val topFadePast = with(androidx.compose.ui.platform.LocalDensity.current) { TOP_FADE_PAST.roundToPx() }
    Box(
        if (!spotlight) modifier else modifier
            // Behind the rows above it (the app buttons), which its picture now runs under.
            .zIndex(-1f)
            .onGloballyPositioned { stageTop = it.positionInRoot().y; restTop = maxOf(restTop, stageTop) },
    ) {
    // Spotlight (Jonathan, 5 Nov 2026, after Netflix): the picture fills the whole width and the text lies over
    // it, the page's wash fading in from the left for the text and from below for the channel strip; above the
    // stage it runs on to the top of the screen under a wash that leaves only a hint of it.
    // Its lower part fades to nothing, picture and washes alike, so the page's own background runs on under the
    // channel strip without a seam: a wash ending in a colour showed a step where the page's darker one began
    // (Jonathan, 7 Nov 2026: "komplett fließend").
    // It also runs on below the stage, through the room Home leaves under it, to the screen's bottom edge, and fades
    // out exactly there: at rest it spans the whole screen, whatever the stage's height (Jonathan, 7 Nov 2026).
    // Measured from the resting position, so scrolling moves it with the stage instead of stretching it.
    val below = { slot: Int -> (rootView.height - restTop.toInt() - slot).coerceAtLeast(0) }
    if (spotlight) Box(Modifier.matchParentSize().reachUp(reach, below).fadeOutBelow(SPOTLIGHT_FADE_FROM)) {
        when (hl) {
            // A match with a picture shows it full width like any highlight; without one, its crests (below).
            is Highlight.Sport -> sportArt?.let { url ->
                SpotlightArt(state = ArtState.Picture(url), channel = picChannel, logo = {}, corner = {})
            } ?: SportColorsBackdrop(hl.match)
            is Highlight.Resume -> HighlightPicture(hl)
            else -> SpotlightArt(
                state = artState,
                channel = picChannel,
                // No picture at all: the channel's logo, large, where the picture would be.
                logo = { ch ->
                    Box(Modifier.fillMaxSize()) {
                        ChannelLogoTile(
                            logoUrl = ch.displayLogoUrl,
                            modifier = Modifier.align(Alignment.CenterEnd).padding(end = 300.mpx).size(360.mpx, 200.mpx),
                            fill = Color.Transparent,
                        ) { Initials(ch, 56) }
                    }
                },
                corner = {},
            )
        }
        val wash = StageColors.Wash
        // The wash from the left, for the text, runs up to the top too: ending at the stage's top it drew a line
        // across the picture under the app buttons.
        Box(
            Modifier.fillMaxSize().gradientWash(
                false,
                0f to wash.copy(alpha = 0.94f), 0.32f to wash.copy(alpha = 0.78f), 0.6f to wash.copy(alpha = 0.12f), 1f to wash.copy(alpha = 0f),
            ),
        )
        // The wash from below, for the channel strip, on the stage alone (not stretched over the part above it).
        Box(Modifier.fillMaxSize().belowReach(reach)) {
            Box(
                Modifier.fillMaxSize().gradientWash(
                    true,
                    0f to wash.copy(alpha = 0f), 0.58f to wash.copy(alpha = 0f), 0.84f to wash.copy(alpha = 0.88f), 1f to wash,
                ),
            )
        }
        // Above the stage: the page's colour over the picture, nearly opaque at the top edge, so the app buttons and
        // the clock read as before, thinning out to nothing a little way into the stage.
        Box(
            Modifier.fillMaxWidth().topReach(reach, topFadePast).gradientWash(
                true,
                0f to wash.copy(alpha = 0.93f), 0.55f to wash.copy(alpha = 0.78f), 0.8f to wash.copy(alpha = 0.4f), 1f to wash.copy(alpha = 0f),
            ),
        )
    }
    // Clear of the clock in the top-right corner, which the big plate would otherwise run into.
    Column(Modifier.padding(top = 24.mpx)) {
        Row(
            Modifier.padding(start = start, end = 64.mpx).height(if (spotlight) 660.mpx else 330.mpx),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(56.mpx),
        ) {
            if (hl != null) {
                HighlightInfo(
                    hl = hl,
                    count = highlights.size,
                    index = hlIndex,
                    onStep = { d -> stepHighlight(d) },
                    rating = (hl as? Highlight.Pick)?.let { ratings[it.key] },
                    // A match's second button: its page in Sports (Jonathan, 7 Nov 2026); the first plays it here.
                    onOnDemand = if (hl is Highlight.Sport) ({ onOpenMatch(hl.match) })
                        else (hl as? Highlight.Pick)?.let { onDemand[it.key] }?.let { id -> { onOpenOnDemand(id) } },
                    secondIcon = if (hl is Highlight.Sport) OwnTVIcon.SPORTS else OwnTVIcon.MOVIES,
                    tier = if (badges) HighlightTier.of(hl, (hl as? Highlight.Pick)?.let { ratings[it.key] }) else null,
                    sportText = sportMatch?.let { sportText(it, sportNotes, sportTable) },
                    onOpen = {
                        // A channel's programme, on now or later: the first OK plays the channel in the picture
                        // here, as the strip does; the second takes it full screen.
                        fun open(channel: ChannelEntity) {
                            if (previewOn && picked?.id != channel.id) {
                                pickedAudible = true
                                pickedFromHighlight = true
                                picked = channel
                            } else onPlayFullscreen(channel, channels)
                        }
                        when (hl) {
                            is Highlight.Live -> open(hl.channel)
                            is Highlight.Pick -> open(hl.pick.channel)
                            // A match: its first channel (as Watch live lists them) plays here; OK again full screen.
                            // No channel shows it, or no preview: its page in Sports, as before.
                            is Highlight.Sport -> {
                                val playing = picked?.takeIf { pickedFromHighlight }
                                when {
                                    playing != null -> onPlayFullscreen(playing, channels)
                                    !previewOn -> onOpenMatch(hl.match)
                                    !matchSearching -> stageScope.launch {
                                        matchSearching = true
                                        val ch = try { findMatchChannel(hl.match) } finally { matchSearching = false }
                                        if (ch != null) {
                                            pickedAudible = true
                                            pickedFromHighlight = true
                                            picked = ch
                                        } else onOpenMatch(hl.match)
                                    }
                                }
                            }
                            is Highlight.Resume -> onResume(hl.item)
                        }
                    },
                    onFocus = onFocus,
                    focusRequester = firstItemFocusRequester,
                    ownRequester = hlButton,
                    track = track,
                    now = now,
                    modifier = Modifier.weight(1f),
                )
            } else Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(14.mpx)) {
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(16.mpx)) {
                    Box(Modifier.size(64.mpx).clip(RoundedCornerShape(10.mpx)).background(StageColors.ControlFill)) {
                        ChannelLogoTile(logoUrl = shown.displayLogoUrl, modifier = Modifier.fillMaxSize().padding(6.mpx), fill = Color.Transparent) {
                            Initials(shown, 18)
                        }
                    }
                    Text(stringResource(R.string.home_stage_live), style = stageText(17, 800), color = stageAccent.accent, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    Text(shown.name, style = stageText(18, 600), color = StageColors.Dim, maxLines = 1, overflow = TextOverflow.Ellipsis)
                }
                Text(
                    programme?.title ?: shown.name, style = stageText(if (spotlight) 68 else 58, 800), color = StageColors.Text,
                    maxLines = 2, overflow = TextOverflow.Ellipsis,
                )
                if (programme != null) {
                    Row(horizontalArrangement = Arrangement.spacedBy(18.mpx)) {
                        Text(timeSpan(programme), style = stageText(19, 600), color = StageColors.Dim, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        Text(
                            timeLeftText(((programme.stopMs - now).coerceAtLeast(0L) + 59_999L) / 60_000L),
                            style = stageText(19, 600), color = StageColors.Dim, maxLines = 1, overflow = TextOverflow.Ellipsis,
                        )
                    }
                    Box(Modifier.width(520.mpx).height(6.mpx).clip(RoundedCornerShape(3.mpx)).background(StageColors.ControlFill)) {
                        Box(Modifier.fillMaxHeight().fillMaxWidth(programmeProgress(programme, now)).background(stageAccent.accent))
                    }
                    // Spotlight: what the focused channel's programme is about, as on the highlights.
                    if (spotlight) programme.description?.let(::shortDescription)?.let { SpotlightDescription(it) }
                }
            }
            Box(
                // Spotlight: 1024 × 576 since it grew taller (Jonathan, 7 Nov 2026), still 16:9.
                Modifier.size(if (spotlight) 1024.mpx else 540.mpx, if (spotlight) 576.mpx else 304.mpx)
                    .clip(RoundedCornerShape(StageRadii.Poster))
                    // Spotlight: only the playing channel has a frame here; otherwise the picture behind shows through.
                    .then(if (!spotlight || video != null) Modifier.background(StageColors.ControlFill) else Modifier),
                contentAlignment = Alignment.Center,
            ) {
                // The picture alone: the shell draws it over this spot with the view full screen uses, so it grows
                // into full screen and shrinks back here without the stream changing surface.
                GuideVideo(
                    channel = video,
                    previewEngine = liveVm.previewEngine,
                    showVideo = video != null,
                    shellDrawsVideo = true,
                    // Spotlight with nothing playing: unseen (its black would cover the picture behind), but still
                    // laid out, so the shell knows where to grow full screen from.
                    modifier = Modifier.fillMaxSize().alpha(if (spotlight && video == null) 0f else 1f).onGloballyPositioned {
                        GuidePreviewSpot.rect = it.boundsInRoot().translate(-GuidePreviewSpot.shift(), 0f)
                    },
                )
                if (spotlight) {
                    // The picture is the full-width one behind; a match shows its crests here.
                    // A match is drawn full width behind (picture, or the clubs' colours with their crests).
                } else if (video == null && hl != null && hl !is Highlight.Live) {
                    HighlightPicture(hl)
                } else if (video == null) {
                    SpotlightArt(
                        state = artState,
                        channel = picChannel,
                        logo = { ch ->
                            ChannelLogoTile(
                                logoUrl = ch.displayLogoUrl,
                                modifier = Modifier.fillMaxSize().padding(horizontal = 150.mpx, vertical = 70.mpx),
                                fill = Color.Transparent,
                            ) { Initials(ch, 56) }
                        },
                        // Whose programme it is, small in the corner.
                        corner = { ch ->
                            Box(Modifier.fillMaxSize()) {
                                Box(
                                    Modifier.align(Alignment.BottomStart).padding(24.mpx).size(96.mpx, 64.mpx)
                                        .clip(RoundedCornerShape(10.mpx)).background(StageColors.ControlFill),
                                ) {
                                    ChannelLogoTile(logoUrl = ch.displayLogoUrl, modifier = Modifier.fillMaxSize().padding(8.mpx), fill = Color.Transparent) {
                                        Initials(ch, 18)
                                    }
                                }
                            }
                        },
                    )
                }
            }
        }
        LazyRow(
            horizontalArrangement = Arrangement.spacedBy(18.mpx),
            contentPadding = PaddingValues(start = start, end = 64.mpx, top = 10.mpx, bottom = 10.mpx),
            modifier = Modifier.focusRestorer().focusGroup().onFocusChanged { stripFocused = it.hasFocus }
                .onPreviewKeyEvent { e ->
                    if (spotlight && highlights.isNotEmpty() && e.type == KeyEventType.KeyDown && e.key == Key.DirectionUp) {
                        stripFocused = false
                        toButton = true
                        true
                    } else false
                },
        ) {
            itemsIndexed(channels, key = { _, c -> c.id }) { index, channel ->
                StageSurface(
                    // As in the guide: the first OK plays it here, the second takes it full screen.
                    onClick = {
                        when {
                            previewOn && picked?.id != channel.id -> { pickedAudible = true; pickedFromHighlight = false; picked = channel }
                            else -> onPlayFullscreen(channel, channels)
                        }
                    },
                    onLongClick = { onLongClick(channel) },
                    radius = StageRadii.Poster,
                    focusStyle = StageFocus.POSTER,
                    idle = Modifier.clip(RoundedCornerShape(StageRadii.Poster)).background(StageColors.ControlFill),
                    contentAlignment = Alignment.Center,
                    modifier = Modifier
                        .size(200.mpx, 112.mpx)
                        // With highlights, the row's way in is their button above the strip.
                        .then(if (index == 0 && firstItemFocusRequester != null && !(spotlight && highlights.isNotEmpty())) Modifier.focusRequester(firstItemFocusRequester) else Modifier)
                        .then(if (channel.id == picked?.id) Modifier.focusRequester(pickedLogo) else Modifier)
                        .tracked(track = track)
                        .onFocusChanged { if (it.hasFocus) { onFocus(); shownId = channel.id } },
                ) { _ ->
                    ChannelLogoTile(
                        logoUrl = channel.displayLogoUrl,
                        modifier = Modifier.fillMaxSize().padding(horizontal = 44.mpx, vertical = 16.mpx),
                        fill = Color.Transparent,
                    ) { Initials(channel, 26) }
                }
            }
        }
    }
    }
}

/** Laid out [reach] px taller than its slot and moved up by as much, and [below] px longer: it draws on up to the screen's top and past its slot's bottom. */
private fun Modifier.reachUp(reach: () -> Int, below: (slot: Int) -> Int = { 0 }): Modifier = layout { measurable, constraints ->
    val up = reach()
    val placeable = measurable.measure(androidx.compose.ui.unit.Constraints.fixed(constraints.maxWidth, constraints.maxHeight + up + below(constraints.maxHeight)))
    layout(constraints.maxWidth, constraints.maxHeight) { placeable.place(0, -up) }
}

/** Everything drawn in it fades out from [from] (a fraction of its height) to nothing at its bottom edge. */
private fun Modifier.fadeOutBelow(from: Float): Modifier = this
    .graphicsLayer { compositingStrategy = androidx.compose.ui.graphics.CompositingStrategy.Offscreen }
    .drawWithContent {
        drawContent()
        drawRect(
            androidx.compose.ui.graphics.Brush.verticalGradient(0f to Color.Black, from to Color.Black, 1f to Color.Transparent),
            blendMode = androidx.compose.ui.graphics.BlendMode.DstIn,
        )
    }

/** Inside a [reachUp] box: only its lower part, the stage's own slot. */
private fun Modifier.belowReach(reach: () -> Int): Modifier = layout { measurable, constraints ->
    val up = reach().coerceAtMost(constraints.maxHeight)
    val placeable = measurable.measure(androidx.compose.ui.unit.Constraints.fixed(constraints.maxWidth, constraints.maxHeight - up))
    layout(constraints.maxWidth, constraints.maxHeight) { placeable.place(0, up) }
}

/** Inside a [reachUp] box: from its top down past the stage's top by [past] px. */
private fun Modifier.topReach(reach: () -> Int, past: Int): Modifier = layout { measurable, constraints ->
    val height = (reach() + past).coerceAtMost(constraints.maxHeight)
    val placeable = measurable.measure(androidx.compose.ui.unit.Constraints.fixed(constraints.maxWidth, height))
    layout(constraints.maxWidth, height) { placeable.place(0, 0) }
}

/** How far into the stage the wash from the top still reaches. */
private val TOP_FADE_PAST = 140.mpx

/** Where the Spotlight starts fading into the page, as a fraction of its height counted from the screen's top. */
private const val SPOTLIGHT_FADE_FROM = 0.76f

/** The room Home leaves under the Spotlight's channel strip: the strip ends as far above the screen's bottom as the app buttons begin below its top (Jonathan, 7 Nov 2026), and the next row starts past the edge. */
internal val SpotlightFold = 40.mpx

/** Home settles first; then the row's programme pictures are fetched ahead. */
private const val ART_PREFETCH_DELAY_MS = 4000L
private const val ART_PREFETCH_COUNT = 30

/** How long focus rests on a channel before its programme's picture is looked up. */
private const val ART_DWELL_MS = 350L

/** Focus that leaves the strip only to come straight back (full screen and Back) does not stop the channel. */
private const val STRIP_LEFT_GRACE_MS = 800L

/** After Home puts focus back on its own (60 ms), the stage takes it to the playing channel's logo. */
private const val RETURN_FOCUS_MS = 150L

/** A logo's stand-in: the channel's number, else the first letters of its name. */
@Composable
private fun Initials(channel: ChannelEntity, size: Int) {
    Text(
        channel.number?.toString() ?: channel.name.take(2).uppercase(),
        style = stageText(size, 800), color = StageColors.Text, maxLines = 1, overflow = TextOverflow.Ellipsis,
    )
}

/** "14:10 – 15:00" in the TV's own clock format. */
@Composable
private fun timeSpan(programme: EpgProgrammeEntity): String {
    val format = android.text.format.DateFormat.getTimeFormat(LocalContext.current)
    return stringResource(R.string.home_stage_time_span, format.format(java.util.Date(programme.startMs)), format.format(java.util.Date(programme.stopMs)))
}

/** Tokens the stage has acted on, per kind, for the app's lifetime: a re-composed stage does not act on one again. */
private object StageOnce {
    private val last = HashMap<String, Long>()

    @Synchronized
    fun first(kind: String, token: Long): Boolean {
        if (last[kind] == token) return false
        last[kind] = token
        return true
    }
}

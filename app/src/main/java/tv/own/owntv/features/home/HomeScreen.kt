package tv.own.owntv.features.home

import tv.own.owntv.ui.theme.stageTone
import tv.own.owntv.ui.stage.backdropLeftWash
import android.graphics.SurfaceTexture
import android.view.Surface
import android.view.TextureView
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.focusGroup
import androidx.compose.foundation.focusable
import androidx.compose.foundation.gestures.BringIntoViewSpec
import androidx.compose.foundation.gestures.LocalBringIntoViewSpec
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.wrapContentHeight
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusProperties
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.focusRestorer
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.LineHeightStyle
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.repeatOnLifecycle
import androidx.tv.material3.Text
import coil3.compose.AsyncImage
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import org.koin.compose.koinInject
import tv.own.owntv.R
import tv.own.owntv.core.database.dao.TrendingDao
import tv.own.owntv.core.database.entity.ChannelEntity
import tv.own.owntv.core.database.entity.MovieEntity
import tv.own.owntv.core.database.entity.SeriesEntity
import tv.own.owntv.core.database.entity.MetadataCacheEntity
import tv.own.owntv.core.home.HeroItem
import tv.own.owntv.core.home.TrendingHomeItem
import tv.own.owntv.core.launcher.LauncherContinuationItem
import tv.own.owntv.core.metadata.MetadataImages
import tv.own.owntv.core.model.HomeLiveRowMode
import tv.own.owntv.core.model.HomeRow
import tv.own.owntv.core.model.HomeTrendingStyle
import tv.own.owntv.core.trending.ProviderVariantParser
import tv.own.owntv.features.shell.components.MediaDetailsScreen
import tv.own.owntv.features.shell.components.MediaDetailsUi
import tv.own.owntv.player.HeroPreviewEngine
import tv.own.owntv.ui.components.BrandLockup
import tv.own.owntv.ui.components.InAppToast
import tv.own.owntv.ui.components.OwnTVIcon
import tv.own.owntv.ui.components.TrailerPlayerScreen
import tv.own.owntv.ui.components.rememberInAppToast
import tv.own.owntv.ui.stage.StageButton
import tv.own.owntv.ui.stage.StageFocus
import tv.own.owntv.ui.stage.StagePoster
import tv.own.owntv.ui.stage.StageStill
import tv.own.owntv.ui.stage.StageSurface
import tv.own.owntv.ui.stage.StageTag
import tv.own.owntv.ui.theme.StageColors
import tv.own.owntv.ui.theme.dissolveEdges
import tv.own.owntv.ui.theme.mpx
import tv.own.owntv.ui.theme.mpxSp
import tv.own.owntv.ui.theme.stageAccent
import tv.own.owntv.ui.theme.stageText
import java.util.Locale

/*
 * Home, Stage (hero, rows, pager). With Trending drawn full-bleed, the top of the screen
 * is the trending title over its dissolving backdrop, and Keep watching sits under it in large stills.
 * Once focus goes down into the rows, the hero folds into one line (eyebrow + title) and the rows move up.
 * Both states are one list, so the focused card is the same composable before and after the fold.
 */

/** The row gap; the headings' design positions below already include it. */
private val RowGap = 40.mpx

/** How far the full hero's text sits above the design's (`top: 170px`), and the rows with it: the
 *  top-left corner is empty, and the rows below are what the screen is short of. */
private val HeroLift = 60.mpx

/** Screen-top to the Keep watching heading under the full hero (`top: 744px`, less [HeroLift]), less the row gap. */
private val HeroHeight = 744.mpx - HeroLift - RowGap

/** Screen-top to the first row heading once the hero is folded (`top: 228px`), less the row gap. */
private val CompactHeroHeight = 228.mpx - RowGap

/** Where the rows start when there is no hero: just below the top-right cluster. */
private val NoHeroTop = 110.mpx
private val RoomBelowFocus = 36.mpx

/** Where the app buttons of the Classic and Stage layouts sit: level with the clock. */
private val AppPillsTop = 30.mpx

@OptIn(ExperimentalFoundationApi::class)
@Composable
fun HomeScreen(
    vm: HomeViewModel,
    onPlayMovie: (movieId: Long, positionMs: Long) -> Unit,
    onPlayEpisode: (seriesId: Long, episodeId: Long, positionMs: Long) -> Unit,
    onPlayChannel: (channelId: Long, zapChannels: List<ChannelEntity>) -> Unit,
    onActivateTrending: (TrendingHomeItem, onUnavailable: () -> Unit) -> Unit,
    onOpenMovie: (MovieEntity) -> Unit,
    onOpenSeries: (SeriesEntity) -> Unit,
    onOpenTrendingSearch: (String) -> Unit,
    onChildFocused: () -> Unit,
    restoreFocus: Boolean = false,
    restoreTrendingSearchFocus: Boolean = false,
    onRestored: () -> Unit = {},
    previewEnabled: Boolean = true,
    firstRowFocusRequester: FocusRequester? = null,
    /** The content's left edge: beside the resting capsule (150), or 64 inside a docked rail's reserve. */
    contentStart: Dp = 150.mpx,
    /**
     * Hands the shell Home's own way in, for ▶ out of the rail: the control that last had focus, or Play.
     * (A plain request on the content area drills to the nearest card instead.) Null on leaving.
     */
    onEntryHook: (((() -> Boolean)?) -> Unit)? = null,
    /** Sports Hub: ▶ on a Live sports card opens its match page. */
    onOpenMatch: (tv.own.owntv.core.sports.model.Match) -> Unit = {},
    /** The Apps row's All apps card: the Apps section. */
    onOpenApps: () -> Unit = {},
    /** The Stage layout plays its channels as the guide does: in its picture, then full screen from there. */
    liveVm: tv.own.owntv.features.live.LiveViewModel? = null,
    onPlayFromStage: (ChannelEntity, List<ChannelEntity>) -> Unit = { _, _ -> },
    modifier: Modifier = Modifier,
    sportsVm: tv.own.owntv.features.sports.SportsViewModel = org.koin.androidx.compose.koinViewModel(),
) {
    val state by vm.uiState.collectAsStateWithLifecycle()
    // Asked for only while the row is switched on (Layout → Home rows); off costs nothing.
    // The Spotlight layout ranks the followed matches too, so it asks for them as well.
    val wantMatches = HomeRow.LIVE_SPORTS in state.config.visibleOrder ||
        tv.own.owntv.launcher.apps.rememberLauncherConfig().homeLayout == tv.own.owntv.launcher.apps.LauncherConfig.LAYOUT_SPOTLIGHT
    val matches = if (wantMatches) sportsVm.homeRow.collectAsStateWithLifecycle().value else emptyList()
    val sportsCards = if (HomeRow.LIVE_SPORTS in state.config.visibleOrder) matches else emptyList()
    val trendingUnavailableMessage = stringResource(R.string.home_trending_unavailable)
    val heroPreviewEngine = koinInject<HeroPreviewEngine>()
    val engineState by heroPreviewEngine.state.collectAsStateWithLifecycle()
    val isPreviewActive by vm.isPreviewActive.collectAsStateWithLifecycle()
    val lastInteractionMs by vm.lastHeroInteractionMs.collectAsStateWithLifecycle()
    val favoriteMovies by vm.favoriteMovieIds.collectAsStateWithLifecycle()
    val favoriteSeries by vm.favoriteSeriesIds.collectAsStateWithLifecycle()
    val listState = androidx.compose.foundation.lazy.rememberLazyListState()
    val homeScope = rememberCoroutineScope()
    val heroFocus = remember { FocusRequester() }
    val fallbackFocus = remember { FocusRequester() }
    val trendingPrimaryFocus = remember { FocusRequester() }
    val trendingTrailerFocus = remember { FocusRequester() }
    val trendingDetailsFocus = remember { FocusRequester() }
    val trendingVersionsFocus = remember { FocusRequester() }
    val trendingToast = rememberInAppToast()
    var trailerVideoKey by remember { mutableStateOf<String?>(null) }
    var trailerTitle by remember { mutableStateOf<String?>(null) }
    var detailsItem by remember { mutableStateOf<TrendingHomeItem?>(null) }
    var detailsMetadata by remember { mutableStateOf<MetadataCacheEntity?>(null) }
    var detailsTmdbWins by remember { mutableStateOf(false) }
    // Focus is on the hero (its buttons or pager): the list must not scroll, and the hero stays unfolded.
    val heroFocused = remember { mutableStateOf(false) }
    // Focus has gone down into the rows: the hero folds into its one-line form.
    var inRows by remember { mutableStateOf(false) }
    val rowFirstFocusRequesters = remember { HomeRow.entries.associateWith { FocusRequester() } }
    // A Keep watching card plays its preview only after 3 s of continuous focus (the dwell below).
    var previewIndex by remember { mutableStateOf(-1) }
    var focusedHeroIndex by remember { mutableStateOf(-1) }
    val orderedRows = state.config.visibleOrder
    val heroVisible = HomeRow.HERO in orderedRows
    val hasNonHeroContent = orderedRows.any { it != HomeRow.HERO && rowHasData(it, state, sportsCards.size) }
    val showHeroFallback = heroVisible && state.heroItems.isEmpty() && !hasNonHeroContent
    val renderRows = orderedRows.filter { rowCanRender(it, state, showHeroFallback, sportsCards.size) }
    val firstDataRow = renderRows.firstOrNull { it != HomeRow.HERO && rowHasData(it, state, sportsCards.size) }
    val launcherConfig = tv.own.owntv.launcher.apps.rememberLauncherConfig()
    // Mayo TV's layouts (Jonathan, 4 Nov 2026): Classic and Stage put the apps on top as small buttons and
    // their channels (YouTube, Nuvio, Spotify) right after the first row; Stage also draws the channel row
    // as a stage.
    val compactApps = launcherConfig.homeLayout != tv.own.owntv.launcher.apps.LauncherConfig.LAYOUT_STANDARD
    val spotlightLayout = launcherConfig.homeLayout == tv.own.owntv.launcher.apps.LauncherConfig.LAYOUT_SPOTLIGHT
    val stageLayout = launcherConfig.homeLayout == tv.own.owntv.launcher.apps.LauncherConfig.LAYOUT_STAGE || spotlightLayout
    val showAppsRow = launcherConfig.appsRow && renderRows.isNotEmpty() && !compactApps
    val showAppPills = launcherConfig.appsRow && renderRows.isNotEmpty() && compactApps
    val earlyChannels = compactApps && launcherConfig.appChannels
    // The other apps' rows where the user put them among Home's own (Jonathan, 4 Nov 2026), see HomeSlots.
    val appChannelList by tv.own.owntv.launcher.apps.LauncherApps.channels.collectAsStateWithLifecycle()
    val visibleAppKeys = if (launcherConfig.appChannels) {
        appChannelList.map { it.key }.filter { it !in launcherConfig.hiddenChannels }
    } else emptyList()
    val placedApps = launcherConfig.channelSlots.mapNotNull(HomeSlots::decode).filter { it.second in visibleAppKeys }
    val placedAppKeys = placedApps.map { it.second }.toSet()
    val appsAfter = HomeSlots.place(state.config.order, renderRows, placedApps)
    // Rows never placed keep their old spot; once the user has placed some, a new one joins at the end.
    val defaultEarly = earlyChannels && launcherConfig.channelSlots.isEmpty()
    // The LazyColumn's items, in order, as far as finding a row's index needs.
    val lazyKeys = buildList {
        if (showAppPills) add("launcher_app_pills")
        if (HomeSlots.TOP in appsAfter) add("apps_top")
        renderRows.forEachIndexed { i, row ->
            add(row.name)
            if (i == 0) {
                if (showAppsRow) add("launcher_apps")
                if (defaultEarly) add("launcher_channels")
            }
            if (row.name in appsAfter) add("apps_after_${row.name}")
        }
    }
    // The Spotlight's highlights (Jonathan, 4 Nov 2026): followed matches, the evening, habits, watching on.
    // The Spotlight's own list: the followed matches also before one is near (the row waits for that).
    val spotlightMatches = if (spotlightLayout) sportsVm.spotlightRow.collectAsStateWithLifecycle().value else emptyList()
    // Today's picks from MagentaTV's guide (its tips and highlights, arte's documentaries) on the shown channels and
    // arte (Jonathan, 5 Nov 2026). Kept app-wide and on the device ([SpotlightPicks]): a start shows the last ones at
    // once, coming back to Home keeps them, and they are asked again once the start has settled, every half hour,
    // and on waking from standby (7 Nov 2026).
    val spotlightPicks = koinInject<SpotlightPicks>()
    val pickChannels = remember(state.catalog) {
        val row = state.catalog
        val arte = row?.all.orEmpty().filter { MagentaNames.key(it.name) == ARTE_KEY }
        (row?.channels.orEmpty() + arte).distinctBy { it.id }
    }
    val docsOn = remember(pickChannels) { pickChannels.filter { MagentaNames.key(it.name) == ARTE_KEY }.map { it.id }.toSet() }
    // The film channels (Sky Cinema …) for the Spotlight's Films, wherever they are in the playlist (Jonathan,
    // 6 Nov 2026). Looked for once per row, and only while Films is on; null until looked for, once Home has its row.
    val homeCatalog = koinInject<HomeCatalog>()
    val filmsWanted = SpotlightSource.FILMS.key !in launcherConfig.spotlightOff
    val filmChannels by androidx.compose.runtime.produceState<List<tv.own.owntv.core.database.entity.ChannelEntity>?>(
        null, spotlightLayout, filmsWanted, state.isLoading, state.catalog?.category?.key,
    ) {
        if (state.isLoading) return@produceState
        value = if (!spotlightLayout || !filmsWanted) emptyList() else runCatching { homeCatalog.filmChannels(state.catalog) }.getOrDefault(emptyList())
    }
    val guideChannels = remember(pickChannels, filmChannels) { (pickChannels + filmChannels.orEmpty()).distinctBy { it.id } }
    val filmsOn = remember(filmChannels) { filmChannels.orEmpty().map { it.id }.toSet() }
    // Asked for only once all the channels are known: one that came a moment later would only start the round anew.
    val picksRequest = remember(spotlightLayout, state.isLoading, filmChannels == null, guideChannels, docsOn, filmsOn) {
        if (!spotlightLayout || state.isLoading || filmChannels == null) null else SpotlightPicks.Request(guideChannels, docsOn, filmsOn)
    }
    // The stored guide (MagentaTV's) fetches these channels' programmes first.
    val magentaEpg = koinInject<MagentaEpg>()
    LaunchedEffect(picksRequest) {
        picksRequest?.let {
            spotlightPicks.want(it)
            magentaEpg.prioritise(guideChannels)
        }
    }
    val picksState by spotlightPicks.state.collectAsStateWithLifecycle()
    val picks = if (spotlightLayout) picksState.picks else emptyList()
    val spotlightSources = remember(launcherConfig.spotlightOrder, launcherConfig.spotlightOff) {
        SpotlightSource.ordered(launcherConfig.spotlightOrder, launcherConfig.spotlightOff)
    }
    // What TMDB knows of the films (Jonathan, 6 Nov 2026): their rating, year and genre. Those kept on the device in
    // one go, then the rest looked up one by one and handed on together every [FILM_BATCH_MS]: each hand-over ranks
    // the Spotlight again, and on a small box one ranking per answer was a redraw per answer (7 Nov 2026). Also a
    // long tip the guide files as nothing: TMDB decides whether it is a film (7 Nov 2026, "Meg" on ProSieben).
    val programmeArt = koinInject<ProgrammeArt>()
    val filmLookups = remember(picks) { picks.filter { it.film || it.filmCandidate } }
    val filmLookupKeys = remember(filmLookups) { filmLookups.map { it.key } }
    val filmInfoState by androidx.compose.runtime.produceState(FilmInfos(), filmLookupKeys) {
        val byTitle = filmLookups.groupBy { ProgrammeTitles.key(it.title) }
        fun spread(found: Map<String, FilmInfo>) = filmLookups.mapNotNull { p -> found[ProgrammeTitles.key(p.title)]?.let { p.key to it } }.toMap()
        val found = HashMap<String, FilmInfo>()
        for ((k, ps) in byTitle) programmeArt.storedFilm(ps.first().title)?.let { found[k] = it }
        value = FilmInfos(filmLookupKeys, spread(found))
        var handedAt = android.os.SystemClock.elapsedRealtime()
        var unhanded = false
        for ((k, ps) in byTitle) {
            val p = ps.first()
            if (programmeArt.filmKnown(p.title)) continue
            val f = programmeArt.film(p.title, p.year) ?: continue
            found[k] = f
            unhanded = true
            if (android.os.SystemClock.elapsedRealtime() - handedAt >= FILM_BATCH_MS) {
                value = FilmInfos(filmLookupKeys, spread(found))
                handedAt = android.os.SystemClock.elapsedRealtime()
                unhanded = false
            }
        }
        if (unhanded) value = FilmInfos(filmLookupKeys, spread(found))
    }
    val filmInfos = filmInfoState.infos
    // The kept ratings are in for these picks (one read of the device's store, a few milliseconds).
    val filmInfosRead = filmInfoState.keys == filmLookupKeys
    val guidePicks = remember(picks, filmInfos) { SpotlightHighlights.withFilms(picks, filmInfos) }
    val filmPicks = remember(guidePicks) { guidePicks.filter { it.film } }
    val filmRatings = remember(filmInfos) { filmInfos.mapNotNull { (k, f) -> f.rating?.let { k to it } }.toMap() }
    // Which of tonight's films the playlist also has on demand: the Spotlight offers them from the start.
    val filmLibrary = koinInject<FilmLibrary>()
    val onDemandScope = androidx.compose.runtime.rememberCoroutineScope()
    val onDemand by androidx.compose.runtime.produceState(emptyMap<String, Long>(), filmPicks.map { it.key }) {
        value = runCatching { filmLibrary.find(filmPicks) }.getOrDefault(emptyMap())
    }
    // Ranked again on waking from standby too: the time has moved on, and what has ended goes.
    val wake by tv.own.owntv.launcher.apps.LauncherSystem.wake.collectAsStateWithLifecycle()
    // The minute the Spotlight is ranked and shown for: a programme that has ended goes, and "noch 12 Min." counts
    // down, without waiting for some other input to change. Wakes once a minute, on the minute, and only while
    // the app is on screen (a stopped app does not tick); only with the Spotlight layout.
    val lifecycle = androidx.lifecycle.compose.LocalLifecycleOwner.current.lifecycle
    val minute by androidx.compose.runtime.produceState(System.currentTimeMillis() / MINUTE_MS, spotlightLayout, lifecycle) {
        if (!spotlightLayout) return@produceState
        lifecycle.repeatOnLifecycle(androidx.lifecycle.Lifecycle.State.STARTED) {
            while (true) {
                val now = System.currentTimeMillis()
                value = now / MINUTE_MS
                kotlinx.coroutines.delay(MINUTE_MS - now % MINUTE_MS)
            }
        }
    }
    val spotlightHighlights = remember(
        spotlightLayout, spotlightSources, spotlightMatches, state.catalog, state.recentOnNow, state.spotlightWatched, state.heroItems, guidePicks,
        launcherConfig.spotlightMax, launcherConfig.spotlightHideSeries, filmRatings, wake, minute,
    ) {
        if (!spotlightLayout) emptyList()
        else SpotlightHighlights.rank(
            System.currentTimeMillis(), spotlightSources, spotlightMatches, state.catalog?.channels.orEmpty(), state.recentOnNow,
            state.spotlightWatched, state.heroItems, guidePicks,
            max = launcherConfig.spotlightMax, hideSeries = launcherConfig.spotlightHideSeries, ratings = filmRatings,
        )
    }
    // The Spotlight waits a moment before it shows its highlights and Home lands on it (7 Nov 2026), so they do not
    // swap under the viewer as the parts come in: at the start until the kept picks and ratings are read (a few
    // milliseconds, at most [SpotlightHold.START_MS]); on waking from standby until the scores of the followed leagues
    // have been asked again, while the television is still lighting up (at most [SpotlightHold.WAKE_MS]): what was
    // live last night is not shown as live this morning, and a match on now lands first (14 Nov 2026). It costs
    // nothing to wait.
    val sportsCheckedAt = if (spotlightLayout) sportsVm.sportsCheckedAt.collectAsStateWithLifecycle().value else 0L
    val followsSports = spotlightLayout && sportsVm.followsSports.collectAsStateWithLifecycle().value
    // When this wake was first seen here, on the wall clock the scoreboard's answer is stamped with.
    val wakeSeenAt = remember(wake) { System.currentTimeMillis() }
    val holdKey: Any? = when {
        !spotlightLayout -> null
        wake != 0L && (wake != picksState.wakeServed || (followsSports && sportsCheckedAt < wakeSeenAt)) -> wake
        !SpotlightHold.startReleased && (!picksState.ready || !filmInfosRead) -> SpotlightHold.START
        else -> null
    }
    var holdOver by remember { mutableStateOf<Any?>(null) }
    LaunchedEffect(holdKey) {
        if (holdKey == null) { if (spotlightLayout && picksState.ready) SpotlightHold.startReleased = true; return@LaunchedEffect }
        // The start's limit counts from Home's first data: before it there is no stage to show anything on.
        if (holdKey == SpotlightHold.START) vm.uiState.first { !it.isLoading }
        kotlinx.coroutines.delay(if (holdKey == SpotlightHold.START) SpotlightHold.START_MS else SpotlightHold.WAKE_MS)
        holdOver = holdKey
        if (holdKey == SpotlightHold.START) SpotlightHold.startReleased = true
    }
    val spotlightHeld = holdKey != null && holdOver != holdKey
    // At the start nothing is shown yet while it waits (the stage shows its first channel, as before); on a wake
    // the highlights stay and only the landing waits.
    val stageHighlights = if (spotlightHeld && holdKey == SpotlightHold.START) emptyList() else spotlightHighlights
    // The Spotlight is where Home starts and where the Home button lands (Jonathan, 6 Nov 2026): once per
    // start, as soon as it has highlights, and on every Home press. Switching to Home in the rail stays as it was.
    val homePress by tv.own.owntv.launcher.apps.LauncherSystem.homePress.collectAsStateWithLifecycle()
    var spotlightFocusRequest by remember { androidx.compose.runtime.mutableLongStateOf(0L) }
    val spotlightReady = spotlightLayout && stageHighlights.isNotEmpty() && !spotlightHeld
    LaunchedEffect(homePress, spotlightReady) {
        if (!spotlightReady) return@LaunchedEffect
        val request = SpotlightLanding.take(homePress) ?: return@LaunchedEffect
        runCatching { listState.scrollToItem(0) }
        spotlightFocusRequest = request
    }
    val showAllHiddenState = orderedRows.isEmpty()
    val showEmptyState = orderedRows.isNotEmpty() && renderRows.isEmpty()
    val fullBleed = renderRows.firstOrNull() == HomeRow.TRENDING &&
        state.config.trendingStyle == HomeTrendingStyle.HERO
    // Trending layout = Posters only: the folded line on top follows the focused poster.
    val posterHero = renderRows.firstOrNull() == HomeRow.TRENDING &&
        state.config.trendingStyle == HomeTrendingStyle.POSTERS
    var posterIndex by remember { mutableIntStateOf(0) }
    val rowFocusRequester: (HomeRow) -> FocusRequester? = { row ->
        if (row == renderRows.firstOrNull() && firstRowFocusRequester != null) {
            firstRowFocusRequester
        } else when (row) {
            HomeRow.TRENDING -> trendingPrimaryFocus
            HomeRow.HERO -> when {
                state.heroItems.isNotEmpty() -> heroFocus
                showHeroFallback -> fallbackFocus
                else -> null
            }
            else -> rowFirstFocusRequesters[row]
        }
    }
    val playFocus = rowFocusRequester(HomeRow.TRENDING) ?: trendingPrimaryFocus
    // The control that last had focus on Home, so ▶ out of the rail comes back to exactly it (rail
    // carry-over). Compose's own restorer cannot follow a card inside a lazy row inside a lazy column.
    var lastFocus by remember { mutableStateOf<FocusRequester?>(null) }
    val track: (FocusRequester) -> Unit = { lastFocus = it }
    // The category row's card whose Hold-OK menu is open.
    var catalogMenu by remember { mutableStateOf<ChannelEntity?>(null) }
    // Home's rows while their Move window is open, with the carried row's place.
    var arrangeStart by remember { mutableStateOf<HomeSlot?>(null) }
    // Home's rows and the other apps' rows in one window, starting at [appKey]'s row or the channel row.
    val openArrange: (String?) -> Unit = { appKey ->
        arrangeStart = if (appKey != null) HomeSlot.App(appKey) else HomeSlot.Row(HomeRow.RECENT_CHANNELS)
    }
    // For the window opened from Settings: where Home puts app rows nobody placed (Spotlight has no hero).
    androidx.compose.runtime.SideEffect { HomeDrawnRows.first = renderRows.firstOrNull { it in state.config.settingsRows } }
    val homeSettingsVm: tv.own.owntv.features.settings.HomeSettingsViewModel = org.koin.androidx.compose.koinViewModel()
    val latestEntry by androidx.compose.runtime.rememberUpdatedState(
        lastFocus ?: if (fullBleed) playFocus else renderRows.firstOrNull()?.let(rowFocusRequester),
    )
    DisposableEffect(onEntryHook) {
        onEntryHook?.invoke { latestEntry?.let { runCatching { it.requestFocus() }.getOrDefault(false) } ?: false }
        onDispose { onEntryHook?.invoke(null) }
    }

    val onNonHeroFocused = remember(vm, heroPreviewEngine) {
        {
            vm.setHeroFocused(false)
            heroPreviewEngine.stop()
            previewIndex = -1
            inRows = true
            onChildFocused()
        }
    }

    LaunchedEffect(focusedHeroIndex) {
        if (focusedHeroIndex != -1) return@LaunchedEffect
        // Focus moves between cards very quickly (old loses focus before new gains). Debounce the
        // "left the row" signal so the preview state does not flap while navigating within the row.
        kotlinx.coroutines.delay(40L)
        if (focusedHeroIndex != -1) return@LaunchedEffect
        vm.setHeroFocused(false)
        heroPreviewEngine.stop()
        previewIndex = -1
    }

    // Dwell-to-preview: only after 3 s of uninterrupted focus, so quick D-pad sweeps never start a video.
    LaunchedEffect(focusedHeroIndex) {
        val index = focusedHeroIndex
        if (index < 0) return@LaunchedEffect
        kotlinx.coroutines.delay(3_000L)
        if (focusedHeroIndex == index) previewIndex = index
    }

    LaunchedEffect(previewEnabled) {
        vm.setPreviewEnabled(previewEnabled)
        if (!previewEnabled) vm.stopPreview()
    }

    // The engine is an app-scoped singleton; make sure the preview can't outlive the Home screen.
    DisposableEffect(heroPreviewEngine) {
        onDispose { heroPreviewEngine.stop() }
    }

    // The fold: the list starts again at the top, so the rows sit right under the one-line hero.
    LaunchedEffect(inRows) {
        if (fullBleed) runCatching { listState.scrollToItem(0) }
    }

    LaunchedEffect(orderedRows, state.trendingItems, state.heroItems, state.recentLive, state.catalog, state.favoriteLive, state.recentGuide, state.favoriteGuide, state.continueMovies, state.continueSeries, state.favoriteMovies, state.favoriteSeries, restoreFocus, restoreTrendingSearchFocus) {
        if (orderedRows.isEmpty()) {
            if (restoreFocus) onRestored()
            return@LaunchedEffect
        }

        val targetRow = when {
            restoreTrendingSearchFocus && state.trendingItems.isNotEmpty() -> HomeRow.TRENDING
            restoreFocus && heroVisible && state.heroItems.isNotEmpty() -> HomeRow.HERO
            restoreFocus && showHeroFallback -> HomeRow.HERO
            restoreFocus -> firstDataRow
            else -> null
        }
        // The row's place among everything Home lists (app buttons, Apps row, other apps' rows).
        // No row to return to: the very top, app buttons included.
        val targetIndex = targetRow?.let { lazyKeys.indexOf(it.name) }?.takeIf { it >= 0 } ?: 0
        runCatching { listState.scrollToItem(targetIndex.coerceAtLeast(0)) }

        // Only pull focus INTO the Home content when returning from the player (restoreFocus). On a cold
        // start or a tab switch, leave focus on the rail's Home item so the nav is immediately navigable.
        if (restoreFocus || restoreTrendingSearchFocus) {
            kotlinx.coroutines.delay(60)
            val focusTarget = when {
                restoreTrendingSearchFocus && state.trendingItems.isNotEmpty() -> trendingVersionsFocus
                heroVisible && state.heroItems.isNotEmpty() -> rowFocusRequester(HomeRow.HERO)
                showHeroFallback -> rowFocusRequester(HomeRow.HERO)
                firstDataRow != null -> rowFocusRequester(firstDataRow)
                else -> null
            }
            if (focusTarget != null) runCatching { focusTarget.requestFocus() }
            onRestored()
        }
    }

    // While the first load runs, nothing but the Stage background behind: not the empty state (which
    // would look wrong, and vanish, for a user who does have history), and no placeholder blocks.
    if (state.isLoading) return
    if (showAllHiddenState) {
        AllRowsHiddenState(modifier = modifier.fillMaxSize())
        return
    }
    if (showEmptyState) {
        EmptyHomeState(modifier = modifier.fillMaxSize())
        return
    }

    val hero = state.heroItems.getOrNull(state.activeHeroIndex)
    // Scroll only as far as the focused row needs to be fully on screen (focus on Favourite
    // channels leaves Keep watching and the folded title where they are). The TV default instead pins
    // the focused item a third of the way down, which slid the whole page up on every ▼. The hero
    // itself never scrolls.
    val density = androidx.compose.ui.platform.LocalDensity.current
    val homeBringIntoViewSpec = remember(density) {
        object : BringIntoViewSpec {
            override fun calculateScrollDistance(offset: Float, size: Float, containerSize: Float): Float {
                if (heroFocused.value) return 0f
                // The top-right cluster (clock, date) sits over the list's top: a row scrolled back up stops below
                // it instead of sliding under the clock (Jonathan, 1 Nov 2026).
                val top = with(density) { NoHeroTop.toPx() }
                // A row scrolled up into view keeps some room under it: the focused card grows (stageLift) past
                // its own bounds, and its subtitle was cut by the screen's edge (Jonathan, 6 Nov 2026).
                val trailing = offset + size - containerSize + with(density) { RoomBelowFocus.toPx() }
                return when {
                    offset >= top && trailing <= 0f -> 0f
                    offset < top || size > containerSize - top -> offset - top
                    else -> trailing
                }
            }
        }
    }
    val trendingItem = state.trendingItems.getOrNull(state.activeTrendingIndex)
        ?.takeIf { fullBleed && state.trendingItems.size >= TrendingDao.MIN_ELIGIBLE_ITEMS }
    val posterItem = state.trendingItems.getOrNull(posterIndex)
        ?.takeIf { posterHero && state.trendingItems.size >= TrendingDao.MIN_ELIGIBLE_ITEMS }
    // ▲ from the row right under a folded hero unfolds it and lands on Play — there is nothing focusable
    // in the one-line form for focus search to find.
    val firstRowUnderHero = renderRows.getOrNull(1).takeIf { fullBleed && !showAppsRow }
    val unfoldOnUp = Modifier.onPreviewKeyEvent { e ->
        if (e.type == KeyEventType.KeyDown && e.key == Key.DirectionUp) {
            inRows = false
            homeScope.launch {
                listState.scrollToItem(0)
                kotlinx.coroutines.delay(30)
                runCatching { playFocus.requestFocus() }
            }
            true
        } else false
    }

    // The Stage page itself is painted by the shell, full width, so it also runs under a docked rail.
    Box(modifier.fillMaxSize()) {
        trendingItem?.let { TrendingBackdrop(it, folded = inRows, modifier = Modifier.align(Alignment.TopEnd)) }
        posterItem?.let { TrendingBackdrop(it, folded = true, modifier = Modifier.align(Alignment.TopEnd)) }
        CompositionLocalProvider(LocalBringIntoViewSpec provides homeBringIntoViewSpec) {
            LazyColumn(
                modifier = Modifier
                    .fillMaxSize()
                    .onFocusChanged { if (it.hasFocus) onChildFocused() }
                    // ▶ out of the rail comes back to the card it left from (rail carry-over): every level
                    // of the list remembers its focused child.
                    .focusGroup(),
                state = listState,
                contentPadding = PaddingValues(
                    top = when {
                        fullBleed || posterItem != null -> 0.dp
                        // The app buttons sit level with the clock, left of it.
                        showAppPills -> AppPillsTop
                        else -> NoHeroTop
                    },
                    bottom = 48.mpx,
                ),
                verticalArrangement = Arrangement.spacedBy(RowGap),
            ) {
                val rowContent: @Composable (HomeRow) -> Unit = { row ->
                    val firstItemFocusRequester = rowFocusRequester(row)
                    val rowModifier = if (row == firstRowUnderHero) unfoldOnUp else Modifier
                    when (row) {
                        HomeRow.TRENDING -> if (state.trendingItems.size < TrendingDao.MIN_ELIGIBLE_ITEMS) {
                            // Too few titles to be a "trending" row at all, so the row is not drawn.
                        } else if (!fullBleed || trendingItem == null) {
                            posterItem?.let { FoldedHeroLine(it, contentStart, height = CompactHeroHeight + RowGap) }
                            TrendingPosterRow(
                                // Room for the focused poster's lift under the heading when the line sits above.
                                headerGap = if (posterItem != null) 16.mpx else 0.dp,
                                // Compose's one-line poster title is 10 px shorter than Chrome's (measured).
                                bottomGap = if (posterItem != null) 10.mpx else 0.dp,
                                onItemFocused = { posterIndex = it },
                                title = row.displayTitle(),
                                items = state.trendingItems,
                                start = contentStart,
                                onItemClick = { item ->
                                    onActivateTrending(item) {
                                        trendingToast.show(trendingUnavailableMessage)
                                        vm.refresh()
                                    }
                                },
                                onFocus = onNonHeroFocused,
                                firstItemFocusRequester = firstItemFocusRequester ?: trendingPrimaryFocus,
                                track = track,
                            )
                        } else {
                            val favourite = when (trendingItem) {
                                is TrendingHomeItem.Movie -> trendingItem.movie.id in favoriteMovies
                                is TrendingHomeItem.Series -> trendingItem.series.id in favoriteSeries
                            }
                            TrendingHero(
                                item = trendingItem,
                                items = state.trendingItems,
                                activeIndex = state.activeTrendingIndex,
                                extras = state.trendingExtras[trendingItem.stableKey] ?: TrendingExtras(),
                                seasonCount = (trendingItem as? TrendingHomeItem.Series)?.let { state.trendingSeasonCounts[it.series.id] },
                                favourite = favourite,
                                folded = inRows,
                                start = contentStart,
                                primaryFocusRequester = playFocus,
                                trailerFocusRequester = trendingTrailerFocus,
                                detailsFocusRequester = trendingDetailsFocus,
                                versionsFocusRequester = trendingVersionsFocus,
                                onNavigate = vm::navigateTrending,
                                onActivate = { item ->
                                    onActivateTrending(item) {
                                        trendingToast.show(trendingUnavailableMessage)
                                        vm.refresh()
                                    }
                                },
                                onTrailer = { item ->
                                    vm.stopPreview()
                                    trailerTitle = item.snapshot.localizedTitle
                                    trailerVideoKey = item.snapshot.trailerKey
                                },
                                onDetails = { item ->
                                    vm.stopPreview()
                                    homeScope.launch {
                                        val current = vm.revalidateTrendingItem(item) ?: return@launch
                                        val resolved = vm.resolveTrendingDetails(current)
                                        detailsMetadata = resolved.cache
                                        detailsTmdbWins = resolved.tmdbWins
                                        detailsItem = current
                                    }
                                },
                                onAllVersions = { item ->
                                    vm.stopPreview()
                                    onOpenTrendingSearch(item.snapshot.canonicalTitle)
                                },
                                onToggleFavourite = vm::toggleTrendingFavorite,
                                track = track,
                                onFocusChanged = { focused ->
                                    heroFocused.value = focused
                                    if (focused) {
                                        inRows = false
                                        vm.setHeroFocused(false)
                                        heroPreviewEngine.stop()
                                        previewIndex = -1
                                        onChildFocused()
                                    }
                                },
                            )
                        }

                        HomeRow.HERO -> if (state.heroItems.isNotEmpty()) {
                            KeepWatchingRow(
                                title = row.displayTitle(),
                                items = state.heroItems,
                                big = fullBleed && !inRows,
                                start = contentStart,
                                metadata = state.heroMetadata,
                                previewIndex = previewIndex,
                                engine = heroPreviewEngine,
                                engineState = engineState,
                                firstFocusRequester = firstItemFocusRequester ?: heroFocus,
                                onCardFocusChanged = { index, hasFocus ->
                                    if (hasFocus) {
                                        if (previewIndex != index) {
                                            heroPreviewEngine.stop() // stop the previous card's video first
                                            previewIndex = -1 // the dwell timer starts it again after 3 s
                                        }
                                        inRows = true
                                        focusedHeroIndex = index
                                        vm.onHeroUserNavigate(index)
                                        vm.setHeroFocused(true)
                                        onChildFocused()
                                    } else if (focusedHeroIndex == index) {
                                        focusedHeroIndex = -1
                                    }
                                },
                                onPlay = { item ->
                                    when (item) {
                                        is HeroItem.MovieHero -> onPlayMovie(item.movie.id, item.positionMs)
                                        is HeroItem.SeriesHero -> onPlayEpisode(item.series.id, item.episode.id, item.positionMs)
                                        is HeroItem.LiveHero -> onPlayChannel(item.channel.id, state.recentLive)
                                    }
                                },
                                track = track,
                                modifier = rowModifier,
                            )
                        } else {
                            HeroFallbackPane(
                                modifier = Modifier.fillMaxWidth().padding(horizontal = contentStart).then(rowModifier),
                                focusRequester = firstItemFocusRequester ?: fallbackFocus,
                                onChildFocused = onNonHeroFocused,
                            )
                        }

                        HomeRow.LIVE_SPORTS -> tv.own.owntv.features.sports.LiveSportsRow(
                            cards = sportsCards,
                            config = sportsVm.sportsConfig.collectAsStateWithLifecycle().value,
                            start = contentStart,
                            onOpenMatch = onOpenMatch,
                            onFocus = onNonHeroFocused,
                            firstItemFocusRequester = firstItemFocusRequester,
                            track = track,
                            modifier = rowModifier,
                        )

                        // The Stage layout: the category's channels as a stage, the focused one large.
                        HomeRow.RECENT_CHANNELS -> if (stageLayout && state.catalog != null && liveVm != null) HomeStage(
                            row = state.catalog!!,
                            onNow = state.recentOnNow,
                            onLongClick = { channel -> catalogMenu = channel },
                            onFocus = onNonHeroFocused,
                            firstItemFocusRequester = firstItemFocusRequester,
                            start = contentStart,
                            track = track,
                            liveVm = liveVm,
                            onPlayFullscreen = onPlayFromStage,
                            active = previewEnabled,
                            modifier = rowModifier,
                            spotlight = spotlightLayout,
                            highlights = stageHighlights,
                            minute = minute,
                            ratings = if (launcherConfig.spotlightRatings) filmRatings else emptyMap(),
                            autoAdvanceSecs = launcherConfig.spotlightAutoSecs,
                            onDemand = onDemand,
                            badges = launcherConfig.spotlightBadges,
                            // To the film's page in Movies, not straight into the player (Jonathan, 7 Nov 2026): from
                            // there it plays, or goes to the favourites for later.
                            onOpenOnDemand = { movieId -> onDemandScope.launch { filmLibrary.movie(movieId)?.let(onOpenMovie) } },
                            spotlightFocusRequest = spotlightFocusRequest,
                            onOpenMatch = onOpenMatch,
                            findMatchChannel = { m -> sportsVm.firstChannel(m) },
                            onResume = { item ->
                                when (item) {
                                    is HeroItem.MovieHero -> onPlayMovie(item.movie.id, item.positionMs)
                                    is HeroItem.SeriesHero -> onPlayEpisode(item.series.id, item.episode.id, item.positionMs)
                                    is HeroItem.LiveHero -> Unit
                                }
                            },
                        ) else
                        // Mayo TV: a category's channels in place of Recently watched, when one is set.
                        HomeLiveRow(
                            title = state.catalog?.category?.name ?: row.displayTitle(),
                            cardsNow = state.recentOnNow,
                            mode = if (state.catalog != null) HomeLiveRowMode.CARDS else state.config.recentLiveMode,
                            channels = state.catalog?.channels ?: state.recentLive,
                            guide = state.recentGuide,
                            onLongClick = if (state.catalog != null) { channel -> catalogMenu = channel } else null,
                            onChannelClick = onPlayChannel,
                            onFocus = onNonHeroFocused,
                            firstItemFocusRequester = firstItemFocusRequester,
                            start = contentStart,
                            track = track,
                            modifier = rowModifier,
                        )

                        HomeRow.FAVORITE_CHANNELS -> HomeLiveRow(
                            title = row.displayTitle(),
                            cardsNow = state.favoriteOnNow,
                            mode = state.config.favoriteLiveMode,
                            channels = state.favoriteLive,
                            guide = state.favoriteGuide,
                            onChannelClick = onPlayChannel,
                            onFocus = onNonHeroFocused,
                            firstItemFocusRequester = firstItemFocusRequester,
                            start = contentStart,
                            track = track,
                            modifier = rowModifier,
                        )

                        HomeRow.FAVORITE_MOVIES -> FavoritePosterRow(
                            title = row.displayTitle(),
                            items = state.favoriteMovies,
                            key = { it.id },
                            name = { it.name },
                            poster = { it.posterUrl },
                            start = contentStart,
                            onItemClick = onOpenMovie,
                            onFocus = onNonHeroFocused,
                            firstItemFocusRequester = firstItemFocusRequester,
                            track = track,
                            modifier = rowModifier,
                        )

                        HomeRow.FAVORITE_SERIES -> FavoritePosterRow(
                            title = row.displayTitle(),
                            items = state.favoriteSeries,
                            key = { it.id },
                            name = { it.name },
                            poster = { it.posterUrl },
                            start = contentStart,
                            onItemClick = onOpenSeries,
                            onFocus = onNonHeroFocused,
                            firstItemFocusRequester = firstItemFocusRequester,
                            track = track,
                            modifier = rowModifier,
                        )

                        HomeRow.CONTINUE_MOVIES -> ContinuePosterRow(
                            title = row.displayTitle(),
                            items = state.continueMovies,
                            start = contentStart,
                            onItemClick = { onPlayMovie(it.sourceItemId, it.positionMs) },
                            onFocus = onNonHeroFocused,
                            firstItemFocusRequester = firstItemFocusRequester,
                            track = track,
                            modifier = rowModifier,
                        )

                        HomeRow.CONTINUE_SERIES -> ContinuePosterRow(
                            title = row.displayTitle(),
                            items = state.continueSeries,
                            start = contentStart,
                            onItemClick = { onPlayEpisode(0L, it.targetItemId, it.positionMs) },
                            onFocus = onNonHeroFocused,
                            firstItemFocusRequester = firstItemFocusRequester,
                            track = track,
                            modifier = rowModifier,
                        )
                    }
                }
                if (showAppPills) item(key = "launcher_app_pills") {
                    tv.own.owntv.launcher.apps.HomeAppPills(start = contentStart, onFocus = onNonHeroFocused, onAllApps = onOpenApps, track = track)
                }
                val appRows: @Composable (List<String>?) -> Unit = { keys ->
                    tv.own.owntv.launcher.apps.AppChannelRows(
                        start = contentStart, onFocus = onNonHeroFocused, track = track,
                        only = keys, exclude = placedAppKeys, onArrange = { openArrange(it) },
                    )
                }
                appsAfter[HomeSlots.TOP]?.let { keys -> item(key = "apps_top") { appRows(keys) } }
                renderRows.forEachIndexed { i, row ->
                item(key = row.name) { rowContent(row) }
                // Spotlight: the first screen ends with the channel strip, a little room under it; the next row
                // comes into view only on the way down (Jonathan, 7 Nov 2026: "a clean start page").
                if (i == 0 && spotlightLayout && row == HomeRow.RECENT_CHANNELS) item(key = "spotlight_fold") {
                    androidx.compose.foundation.layout.Spacer(Modifier.height(SpotlightFold))
                }
                if (i == 0) {
                // Mayo TV as start screen: the Apps row right under the big picture (Jonathan, 1 Nov 2026).
                if (showAppsRow) item(key = "launcher_apps") {
                    tv.own.owntv.launcher.apps.HomeAppsRow(
                        start = contentStart,
                        onFocus = onNonHeroFocused,
                        onAllApps = onOpenApps,
                        firstFocusRequester = null,
                        track = track,
                        modifier = if (fullBleed) unfoldOnUp else Modifier,
                    )
                }
                // Classic and Stage: the other apps' channels right after the first row.
                if (defaultEarly) item(key = "launcher_channels") { appRows(null) }
                }
                // The ones the user put after this row.
                appsAfter[row.name]?.let { keys -> item(key = "apps_after_${row.name}") { appRows(keys) } }
                }
                // …and the other apps' channels at the very end.
                if (launcherConfig.appChannels && (!defaultEarly || renderRows.isEmpty())) item(key = "launcher_channels") { appRows(null) }
            }
        }
    }

    // The preview starts after the card has settled, so decoder setup does not compete with the focus
    // lift. A trailer counts as "not previewing": leaving the preview decoding behind the trailer window
    // costs a second video pipeline for a picture nobody can see.
    LaunchedEffect(isPreviewActive, hero, previewIndex, focusedHeroIndex, lastInteractionMs, trailerVideoKey) {
        if (!isPreviewActive || trailerVideoKey != null || hero == null || previewIndex < 0 ||
            focusedHeroIndex != previewIndex
        ) {
            heroPreviewEngine.stop()
            return@LaunchedEffect
        }

        val scheduledIndex = previewIndex
        val scheduledHero = hero
        val interactionStamp = lastInteractionMs

        heroPreviewEngine.stop()
        kotlinx.coroutines.delay(520L)
        if (!isPreviewActive || interactionStamp != lastInteractionMs) return@LaunchedEffect
        if (focusedHeroIndex != scheduledIndex || previewIndex != scheduledIndex) return@LaunchedEffect
        if (scheduledHero != state.heroItems.getOrNull(state.activeHeroIndex)) return@LaunchedEffect

        vm.startPreview(scheduledHero)
    }

    trailerVideoKey?.let { key ->
        TrailerPlayerScreen(videoKey = key, title = trailerTitle) {
            trailerVideoKey = null
            homeScope.launch {
                kotlinx.coroutines.delay(60)
                runCatching { trendingTrailerFocus.requestFocus() }
            }
        }
    }
    detailsItem?.let { item ->
        MediaDetailsScreen(details = item.toDetailsUi(detailsMetadata, detailsTmdbWins), onExit = {
            detailsItem = null
            detailsMetadata = null
            homeScope.launch {
                kotlinx.coroutines.delay(60)
                runCatching { trendingDetailsFocus.requestFocus() }
            }
        })
    }
    state.catalog?.let { row ->
        HomeCatalogMenu(
            row = row,
            channel = catalogMenu,
            onHide = vm::hideFromHome,
            onReorder = vm::reorderOnHome,
            onArrangeRows = { openArrange(null) },
            onClose = {
                catalogMenu = null
                homeScope.launch {
                    kotlinx.coroutines.delay(60)
                    // Closed for "Arrange rows": focus belongs to that window, which opens with the row in hand.
                    if (arrangeStart != null) return@launch
                    // A hidden channel's card is gone: the row's first card then.
                    val back = runCatching { lastFocus?.requestFocus() == true }.getOrDefault(false)
                    if (!back) rowFocusRequester(HomeRow.RECENT_CHANNELS)?.let { runCatching { it.requestFocus() } }
                }
            },
        )
    }
    // Home's rows and the other apps' rows, moved from Home: the same window Settings › Home opens.
    arrangeStart?.let { start ->
        ArrangeRowsWindow(
            rows = state.config.settingsRows,
            catalogName = state.catalog?.category?.name,
            startAt = start,
            onSaveRows = homeSettingsVm::setRowOrder,
            onClose = { arrangeStart = null },
        )
    }
    InAppToast(trendingToast)
}

private fun rowHasData(row: HomeRow, state: HomeUiState, sportsCards: Int): Boolean = when (row) {
    HomeRow.TRENDING -> state.trendingItems.size >= TrendingDao.MIN_ELIGIBLE_ITEMS
    HomeRow.LIVE_SPORTS -> sportsCards > 0
    HomeRow.HERO -> state.heroItems.isNotEmpty()
    HomeRow.RECENT_CHANNELS -> if (state.catalog != null) state.catalog.channels.isNotEmpty() else when (state.config.recentLiveMode) {
        HomeLiveRowMode.CARDS -> state.recentLive.isNotEmpty()
        HomeLiveRowMode.ON_NOW -> state.recentGuide.hasContent
    }
    HomeRow.FAVORITE_CHANNELS -> when (state.config.favoriteLiveMode) {
        HomeLiveRowMode.CARDS -> state.favoriteLive.isNotEmpty()
        HomeLiveRowMode.ON_NOW -> state.favoriteGuide.hasContent
    }
    HomeRow.FAVORITE_MOVIES -> state.favoriteMovies.isNotEmpty()
    HomeRow.FAVORITE_SERIES -> state.favoriteSeries.isNotEmpty()
    HomeRow.CONTINUE_MOVIES -> state.continueMovies.isNotEmpty()
    HomeRow.CONTINUE_SERIES -> state.continueSeries.isNotEmpty()
}

private fun rowCanRender(row: HomeRow, state: HomeUiState, showHeroFallback: Boolean, sportsCards: Int): Boolean =
    when (row) {
        HomeRow.HERO -> state.heroItems.isNotEmpty() || showHeroFallback
        else -> rowHasData(row, state, sportsCards)
    }

private fun HeroItem.heroKey(): String = when (this) {
    is HeroItem.MovieHero -> "movie:${movie.id}"
    is HeroItem.SeriesHero -> "episode:${episode.id}"
    is HeroItem.LiveHero -> "live:${channel.id}"
}

private fun trendingBackdrop(item: TrendingHomeItem): String? =
    MetadataImages.backdrop(item.snapshot.backdropPath, size = "w1280")
        ?: when (item) {
            is TrendingHomeItem.Movie -> item.movie.backdropUrl ?: item.movie.posterUrl
            is TrendingHomeItem.Series -> item.series.backdropUrl ?: item.series.posterUrl
        }

/** A rating as the design writes it: one decimal, in the user's number format ("8.4", "8,4"). */
private fun ratingText(rating: Double): String = String.format(Locale.getDefault(), "%.1f", rating)

/**
 * `.fadeimg` behind the hero: right-aligned, 1500 of the 1920 width at 16:9, dissolving into the page on
 * its left (34%) and bottom (38%), with the 70% → 0 wash across its left 60% so the title always reads.
 * Folded, it is 560 high at 55%, without the wash.
 */
@Composable
private fun TrendingBackdrop(item: TrendingHomeItem, folded: Boolean, modifier: Modifier = Modifier) {
    val url = trendingBackdrop(item) ?: return
    BoxWithConstraints(modifier.fillMaxWidth(1500f / 1920f)) {
        val height = maxWidth * (if (folded) 560f else 844f) / 1500f
        Box(
            Modifier
                .fillMaxWidth()
                .height(height)
                .alpha(if (folded) 0.55f else 1f)
                .dissolveEdges(start = 0.34f, bottom = 0.38f),
        ) {
            AsyncImage(model = url, contentDescription = null, contentScale = ContentScale.Crop, modifier = Modifier.fillMaxSize())
            if (!folded) {
                Box(Modifier.fillMaxSize().backdropLeftWash(0f to 0.70f, 0.30f to 0.35f, 0.60f to 0f, 1f to 0f))
            }
        }
    }
}

/** "↗ #3 in Trending · Movies": 18/700 in the accent. */
@Composable
private fun TrendingEyebrow(item: TrendingHomeItem, withTag: Boolean) {
    val accent = stageAccent.accent
    Row(horizontalArrangement = Arrangement.spacedBy(10.mpx), verticalAlignment = Alignment.CenterVertically) {
        OwnTVIcon(OwnTVIcon.TREND, tint = accent, modifier = Modifier.size(18.mpx))
        Text(
            stringResource(
                R.string.home_trending_rank,
                item.snapshot.trendingRank,
                stringResource(if (item is TrendingHomeItem.Movie) R.string.common_nav_movies else R.string.common_nav_series),
            ),
            style = stageText(18, 700, 0.02f.em()), color = accent, maxLines = 1, overflow = TextOverflow.Ellipsis,
        )
        if (withTag) {
            Spacer(Modifier.width(6.mpx))
            StageTag(stringResource(R.string.home_trending_in_playlist))
        }
    }
}

/**
 * Reports this control to Home when it takes focus, as the place ▶ out of the rail returns to. Pass the
 * control's own [requester] when it already has one; otherwise one is made for it.
 */
@Composable
internal fun Modifier.tracked(requester: FocusRequester? = null, track: (FocusRequester) -> Unit): Modifier {
    val own = remember { FocusRequester() }
    return (if (requester == null) focusRequester(own) else this)
        .onFocusChanged { if (it.hasFocus) track(requester ?: own) }
}

private fun Float.em() = androidx.compose.ui.unit.TextUnit(this, androidx.compose.ui.unit.TextUnitType.Em)

@Composable
private fun TrendingHero(
    item: TrendingHomeItem,
    items: List<TrendingHomeItem>,
    activeIndex: Int,
    extras: TrendingExtras,
    seasonCount: Int?,
    favourite: Boolean,
    folded: Boolean,
    start: Dp,
    primaryFocusRequester: FocusRequester,
    trailerFocusRequester: FocusRequester,
    detailsFocusRequester: FocusRequester,
    versionsFocusRequester: FocusRequester,
    onNavigate: (Int) -> Unit,
    onActivate: (TrendingHomeItem) -> Unit,
    onTrailer: (TrendingHomeItem) -> Unit,
    onDetails: (TrendingHomeItem) -> Unit,
    onAllVersions: (TrendingHomeItem) -> Unit,
    onToggleFavourite: (TrendingHomeItem) -> Unit,
    onFocusChanged: (Boolean) -> Unit,
    track: (FocusRequester) -> Unit,
) {
    var heroHasFocus by remember { mutableStateOf(false) }
    var resetClock by remember { mutableIntStateOf(0) }
    // Animated, read only where the pager draws: the hero itself never recomposes for it.
    val progress = remember { androidx.compose.animation.core.Animatable(0f) }
    var restart by remember { mutableStateOf(false) }
    val intervalMs = 10_000L

    fun navigate(index: Int) {
        onNavigate(index)
        restart = true
        resetClock++
    }

    // The automatic advance, every 10 s. It waits while the hero holds focus, so the title never changes
    // under the user's finger — and, folded, nothing of it is on screen to advance.
    LaunchedEffect(activeIndex, heroHasFocus, folded, resetClock, items.size) {
        if (restart) { progress.snapTo(0f); restart = false }
        if (heroHasFocus || folded || items.size < 2) return@LaunchedEffect
        val duration = (intervalMs * (1f - progress.value)).toInt().coerceAtLeast(1)
        // A linear run drawn by the frame clock; the system's animator scale must not speed the 10 s up.
        kotlinx.coroutines.withContext(object : androidx.compose.ui.MotionDurationScale { override val scaleFactor = 1f }) {
            progress.animateTo(1f, androidx.compose.animation.core.tween(duration, easing = androidx.compose.animation.core.LinearEasing))
        }
        progress.snapTo(0f)
        onNavigate((activeIndex + 1) % items.size)
    }

    if (folded) {
        FoldedHeroLine(item, start)
        return
    }

    val snapshot = item.snapshot
    val quality = ProviderVariantParser.displaySignals(snapshot.providerRawName).quality.label
    val runtime = when (item) {
        is TrendingHomeItem.Movie -> item.movie.durationSecs?.takeIf { it >= 60 }?.let { durationText(it / 60L) }
        is TrendingHomeItem.Series -> seasonCount?.let { pluralStringResource(R.plurals.home_trending_seasons, it, it) }
    }
    val meta = listOfNotNull(
        snapshot.year?.toString(),
        extras.genres.take(2).joinToString(", ").ifBlank { null },
        runtime,
    )
    var titleLines by remember(snapshot.localizedTitle) { mutableIntStateOf(1) }

    Box(
        Modifier
            .fillMaxWidth()
            .height(HeroHeight)
            .onFocusChanged {
                heroHasFocus = it.hasFocus
                onFocusChanged(it.hasFocus)
            }
            .focusRestorer()
            .focusGroup(),
    ) {
        // Never squeezed by the fixed height: a child that did not fit would be measured to nothing.
        Column(
            Modifier.padding(start = start, top = 170.mpx - HeroLift).width(900.mpx)
                .wrapContentHeight(Alignment.Top, unbounded = true),
        ) {
            TrendingEyebrow(item, withTag = true)
            // 18 in the design; Compose's trimmed 112 line box starts its glyphs 10 px lower than
            // Chrome's `line-height: 1` box (measured on the TV).
            Spacer(Modifier.height(8.mpx))
            // The title art when TMDB has it, as the films and series hero do; the text title otherwise,
            // and if the art fails to load. 170 high, the room the films hero gives it.
            var logoFailed by remember(extras.logoUrl) { mutableStateOf(false) }
            val showLogo = !extras.logoUrl.isNullOrBlank() && !logoFailed
            if (showLogo) {
                tv.own.owntv.ui.stage.StageTitleArt(
                    url = extras.logoUrl,
                    contentDescription = snapshot.localizedTitle,
                    onFailed = { logoFailed = true },
                    modifier = Modifier.fillMaxWidth().height(170.mpx),
                )
            } else {
                Text(
                    snapshot.localizedTitle,
                    style = stageText(112, 800, (-3).mpxSp).copy(
                        lineHeight = 112.mpxSp,
                        lineHeightStyle = LineHeightStyle(LineHeightStyle.Alignment.Center, LineHeightStyle.Trim.Both),
                    ),
                    color = StageColors.Text,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                    onTextLayout = { titleLines = it.lineCount },
                )
            }
            Spacer(Modifier.height(18.mpx))
            Row(horizontalArrangement = Arrangement.spacedBy(16.mpx), verticalAlignment = Alignment.CenterVertically) {
                val metaStyle = stageText(21, 600)
                val metaColor = stageTone(Color(0xFFD3DCD8), StageColors.Body)
                meta.forEachIndexed { i, part ->
                    if (i > 0) Text("•", style = metaStyle, color = metaColor.copy(alpha = 0.4f))
                    Text(part, style = metaStyle, color = metaColor, maxLines = 1, overflow = TextOverflow.Ellipsis)
                }
                snapshot.rating?.let { rating ->
                    if (meta.isNotEmpty()) Text("•", style = metaStyle, color = metaColor.copy(alpha = 0.4f))
                    Row(horizontalArrangement = Arrangement.spacedBy(8.mpx), verticalAlignment = Alignment.CenterVertically) {
                        Text("★", style = metaStyle, color = StageColors.RatingStar)
                        Text(ratingText(rating), style = metaStyle, color = metaColor)
                    }
                }
                quality?.let { StageTag(it) }
            }
            snapshot.overview?.takeIf { it.isNotBlank() }?.let { overview ->
                Spacer(Modifier.height(20.mpx))
                Text(
                    overview,
                    style = stageText(22, 400).copy(lineHeight = (22 * 1.55f).mpxSp),
                    color = StageColors.Body,
                    // Four lines as drawn; a title that needs a second line (112 high) takes three of
                    // them, so the pager still ends above the Keep watching heading.
                    // The title art is 170 high, 58 more than one title line, so it costs two of them.
                    maxLines = if (titleLines > 1) 1 else if (showLogo) 2 else 4,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.widthIn(max = 760.mpx),
                )
            }
            Spacer(Modifier.height(36.mpx))
            Row(horizontalArrangement = Arrangement.spacedBy(14.mpx), verticalAlignment = Alignment.CenterVertically) {
                val isMovie = item is TrendingHomeItem.Movie
                // The labelled buttons get what the two round ones leave, as one group: long French or
                // German labels otherwise took the whole row and Info and ♥ got none (#4). Inside the group
                // each keeps its own width; only when they do not all fit does the last one shorten to "…".
                Row(Modifier.weight(1f, fill = false), horizontalArrangement = Arrangement.spacedBy(14.mpx), verticalAlignment = Alignment.CenterVertically) {
                    StageButton(
                        text = stringResource(if (isMovie) R.string.home_trending_play else R.string.home_trending_open_episodes),
                        onClick = { onActivate(item) },
                        icon = if (isMovie) OwnTVIcon.PLAY else OwnTVIcon.SERIES,
                        iconFilled = isMovie,
                        modifier = Modifier.focusRequester(primaryFocusRequester).tracked(primaryFocusRequester, track),
                    )
                    if (!snapshot.trailerKey.isNullOrBlank()) {
                        StageButton(
                            text = stringResource(R.string.home_trending_trailer),
                            onClick = { onTrailer(item) },
                            icon = OwnTVIcon.PLAY_CIRCLE,
                            modifier = Modifier.focusRequester(trailerFocusRequester).tracked(trailerFocusRequester, track),
                        )
                    }
                    StageButton(
                        text = stringResource(R.string.home_trending_all_versions),
                        onClick = { onAllVersions(item) },
                        icon = OwnTVIcon.LAYERS,
                        trailing = extras.versions.takeIf { it > 1 }?.toString(),
                        modifier = Modifier.focusRequester(versionsFocusRequester).tracked(versionsFocusRequester, track),
                    )
                }
                StageButton(
                    text = null,
                    onClick = { onDetails(item) },
                    icon = OwnTVIcon.INFO,
                    round = true,
                    modifier = Modifier.focusRequester(detailsFocusRequester).tracked(detailsFocusRequester, track),
                )
                StageButton(
                    text = null,
                    onClick = { onToggleFavourite(item) },
                    icon = OwnTVIcon.FAVORITE,
                    iconFilled = favourite,
                    round = true,
                    modifier = Modifier.tracked(track = track),
                )
            }
            Spacer(Modifier.height(32.mpx))
            TrendingPager(
                count = items.size,
                activeIndex = activeIndex,
                progress = { progress.value },
                onNavigate = ::navigate,
                modifier = Modifier.tracked(track = track),
            )
        }
    }
}

/** The hero folded to its eyebrow and a 60/800 title, nothing focusable. */
@Composable
private fun FoldedHeroLine(item: TrendingHomeItem, start: Dp, height: Dp = CompactHeroHeight) {
    Box(Modifier.fillMaxWidth().height(height).padding(start = start, top = 64.mpx)) {
        Column {
            TrendingEyebrow(item, withTag = false)
            // 8 in the design; Compose's 60 px line box starts its glyphs 13 px higher than
            // Chrome's (measured on the TV).
            Spacer(Modifier.height(21.mpx))
            Text(
                item.snapshot.localizedTitle,
                style = stageText(60, 800, (-1.5).mpxSp), color = StageColors.Text,
                maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.widthIn(max = 1100.mpx),
            )
        }
    }
}

/**
 * The segmented progress under the hero buttons, one segment per trending title. The current one is
 * 70 wide and fills over the 10 s; the ones before it are full. ▼ from the buttons focuses it (the FX
 * look, current segment in the focus colour) and ◀ ▶ then change the title directly — see
 * [trendingPagerTarget].
 */
@Composable
private fun TrendingPager(count: Int, activeIndex: Int, progress: () -> Float, onNavigate: (Int) -> Unit, modifier: Modifier = Modifier) {
    val a = stageAccent
    StageSurface(
        onClick = {},
        radius = 14.mpx,
        focusStyle = StageFocus.FX,
        modifier = modifier
            .offset(x = (-14).mpx)
            .onPreviewKeyEvent { e ->
                if (e.type != KeyEventType.KeyDown) return@onPreviewKeyEvent false
                val delta = when (e.key) {
                    Key.DirectionLeft -> -1
                    Key.DirectionRight -> 1
                    else -> return@onPreviewKeyEvent false
                }
                trendingPagerTarget(activeIndex, count, delta)?.let { onNavigate(it); true } ?: false
            },
    ) { focused ->
        Row(Modifier.padding(horizontal = 14.mpx, vertical = 12.mpx), horizontalArrangement = Arrangement.spacedBy(8.mpx)) {
            repeat(count) { i ->
                val fillColor = if (focused && i == activeIndex) a.focus else a.accent
                Box(
                    Modifier
                        .size(if (i == activeIndex) 70.mpx else 34.mpx, 5.mpx)
                        .clip(RoundedCornerShape(3.mpx))
                        .background(StageColors.Ink.copy(alpha = if (focused) 0.28f else 0.18f))
                        // The running segment's fill is read here, in the draw pass, every frame.
                        .drawBehind {
                            val fill = when {
                                i < activeIndex -> 1f
                                i == activeIndex -> progress()
                                else -> 0f
                            }
                            if (fill <= 0f) return@drawBehind
                            val w = size.width * fill
                            val x = if (layoutDirection == androidx.compose.ui.unit.LayoutDirection.Rtl) size.width - w else 0f
                            drawRect(fillColor, topLeft = androidx.compose.ui.geometry.Offset(x, 0f), size = androidx.compose.ui.geometry.Size(w, size.height))
                        },
                )
            }
        }
    }
}

/**
 * Keep watching: 16:9 stills, 384 × 216 under the full hero, 336 × 189 once folded. After 3 s on a
 * card its preview plays inside the still (the card does not widen, nothing is drawn over it).
 */
@Composable
private fun KeepWatchingRow(
    title: String,
    items: List<HeroItem>,
    big: Boolean,
    start: Dp,
    metadata: Map<String, HomeHeroMetadata>,
    previewIndex: Int,
    engine: HeroPreviewEngine,
    engineState: HeroPreviewEngine.State,
    firstFocusRequester: FocusRequester,
    onCardFocusChanged: (index: Int, hasFocus: Boolean) -> Unit,
    onPlay: (HeroItem) -> Unit,
    track: (FocusRequester) -> Unit,
    modifier: Modifier = Modifier,
) {
    val w = if (big) 384 else 336
    val h = if (big) 216 else 189
    Column(modifier) {
        HomeRowHeader(title, small = if (big) null else items.size.toString(), start = start, big = big)
        LazyRow(
            horizontalArrangement = Arrangement.spacedBy((if (big) 24 else 20).mpx),
            contentPadding = PaddingValues(start = start, end = 64.mpx),
            modifier = Modifier.focusRestorer().focusGroup(),
        ) {
            itemsIndexed(items, key = { _, item -> item.heroKey() }) { index, item ->
                val meta = metadata[item.heroKey()]
                val still = when (item) {
                    is HeroItem.MovieHero -> homeStill(meta?.backdropUrl, item.movie.backdropUrl, item.movie.posterUrl, null)
                    is HeroItem.SeriesHero -> homeStill(meta?.backdropUrl, item.series.backdropUrl, item.series.posterUrl, null)
                    is HeroItem.LiveHero -> homeStill(null, null, null, item.channel.logoUrl)
                }
                StageStill(
                    title = when (item) {
                        is HeroItem.MovieHero -> item.item.title
                        is HeroItem.SeriesHero -> item.series.name
                        is HeroItem.LiveHero -> item.channel.name
                    },
                    line = keepWatchingLine(item),
                    onClick = { onPlay(item) },
                    width = w.mpx,
                    height = h.mpx,
                    titleSize = if (big) 20 else 19,
                    lineSize = if (big) 16 else 15,
                    progress = item.durationMs.takeIf { it > 0 }?.let { item.positionMs.toFloat() / it },
                    modifier = Modifier
                        .then(if (index == 0) Modifier.focusRequester(firstFocusRequester) else Modifier)
                        .tracked(track = track)
                        .onFocusChanged { onCardFocusChanged(index, it.hasFocus) },
                ) {
                    // The video sits under the still, which lifts off once frames arrive. (A TextureView
                    // hidden at alpha 0 is never drawn, so it never gets the surface the engine needs.)
                    if (index == previewIndex) PreviewTexture(engine, Modifier.fillMaxSize())
                    if (index != previewIndex || engineState != HeroPreviewEngine.State.PLAYING) StillArtwork(still)
                }
            }
        }
    }
}

/** "S1 · E2 · 31 min left", "1 h 05 min left", or when a channel was last watched. */
@Composable
private fun keepWatchingLine(item: HeroItem): String? {
    val left = if (item.durationMs > 0 && item.positionMs > 0) {
        timeLeftText(((item.durationMs - item.positionMs).coerceAtLeast(0L) + 59_999L) / 60_000L)
    } else null
    return when (item) {
        is HeroItem.MovieHero -> left
        is HeroItem.SeriesHero -> listOfNotNull(
            stringResource(R.string.content_season_episode, item.episode.seasonNumber, item.episode.episodeNumber),
            left,
        ).joinToString(" · ")
        is HeroItem.LiveHero -> relativeLastWatchedLabel(item.lastEngagementAt, System.currentTimeMillis())
    }
}

@Composable
private fun StillArtwork(still: HomeStill) {
    when (still) {
        is HomeStill.Picture -> AsyncImage(model = still.url, contentDescription = null, contentScale = ContentScale.Crop, modifier = Modifier.fillMaxSize())
        is HomeStill.Logo -> Box(Modifier.fillMaxSize().background(tv.own.owntv.ui.stage.stageStillBack), contentAlignment = Alignment.Center) {
            tv.own.owntv.ui.components.ChannelLogoTile(
                logoUrl = still.url,
                modifier = Modifier.fillMaxSize(0.5f).clip(RoundedCornerShape(12.mpx)),
                imageModifier = Modifier.fillMaxSize().padding(8.mpx),
                fill = Color.Transparent,
            ) {}
        }
        HomeStill.None -> Box(Modifier.fillMaxSize().background(tv.own.owntv.ui.stage.stageStillBack), contentAlignment = Alignment.Center) {
            OwnTVIcon(OwnTVIcon.PLAY_CIRCLE, tint = StageColors.Dim, modifier = Modifier.size(40.mpx))
        }
    }
}

/**
 * The preview's video. A TextureView, not a SurfaceView: the focused card is lifted by 1.08 and clipped
 * to its corners, and only a TextureView is drawn with the card — a SurfaceView would sit unscaled and
 * square behind it.
 */
@Composable
private fun PreviewTexture(engine: HeroPreviewEngine, modifier: Modifier = Modifier) {
    AndroidView(
        modifier = modifier,
        factory = { ctx ->
            TextureView(ctx).apply {
                surfaceTextureListener = object : TextureView.SurfaceTextureListener {
                    private var surface: Surface? = null
                    override fun onSurfaceTextureAvailable(st: SurfaceTexture, width: Int, height: Int) {
                        surface = Surface(st).also(engine::setSurface)
                    }
                    override fun onSurfaceTextureSizeChanged(st: SurfaceTexture, width: Int, height: Int) = Unit
                    override fun onSurfaceTextureDestroyed(st: SurfaceTexture): Boolean {
                        engine.setSurface(null)
                        surface?.release()
                        surface = null
                        return true
                    }
                    override fun onSurfaceTextureUpdated(st: SurfaceTexture) = Unit
                }
            }
        },
    )
}

@Composable
internal fun relativeLastWatchedLabel(lastEngagementAt: Long, nowMs: Long): String {
    val elapsedMs = nowMs - lastEngagementAt
    if (elapsedMs < 60_000L) return stringResource(R.string.home_last_watched_now)

    val elapsedMinutes = elapsedMs / 60_000L
    if (elapsedMinutes < 60L) {
        return pluralStringResource(R.plurals.home_last_watched_minutes, elapsedMinutes.toInt(), elapsedMinutes.toInt())
    }

    val elapsedHours = elapsedMinutes / 60L
    if (elapsedHours < 24L) {
        return pluralStringResource(R.plurals.home_last_watched_hours, elapsedHours.toInt(), elapsedHours.toInt())
    }

    val elapsedDays = elapsedHours / 24L
    return pluralStringResource(R.plurals.home_last_watched_days, elapsedDays.toInt(), elapsedDays.toInt())
}

/** Continue watching movies / series: 196 × 294 posters (`.pc`), as the series row draws them. */
@Composable
private fun ContinuePosterRow(
    title: String,
    items: List<LauncherContinuationItem>,
    start: Dp,
    onItemClick: (LauncherContinuationItem) -> Unit,
    onFocus: () -> Unit,
    firstItemFocusRequester: FocusRequester?,
    track: (FocusRequester) -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(modifier) {
        HomeRowHeader(title, small = items.size.toString(), start = start)
        LazyRow(
            horizontalArrangement = Arrangement.spacedBy(20.mpx),
            contentPadding = PaddingValues(start = start, end = 64.mpx),
            modifier = Modifier.focusRestorer().focusGroup(),
        ) {
            itemsIndexed(items, key = { _, item -> item.stableKey }) { index, item ->
                StagePoster(
                    title = item.title,
                    onClick = { onItemClick(item) },
                    width = 196.mpx,
                    height = 294.mpx,
                    modifier = Modifier
                        .then(if (index == 0 && firstItemFocusRequester != null) Modifier.focusRequester(firstItemFocusRequester) else Modifier)
                        .tracked(track = track)
                        .onFocusChanged { if (it.hasFocus) onFocus() },
                ) {
                    AsyncImage(model = item.posterUrl, contentDescription = null, contentScale = ContentScale.Crop, modifier = Modifier.fillMaxSize())
                }
            }
        }
    }
}

/** Favourite films or shows, in the user's Favorites order. OK opens the item, not a resume. */
@Composable
private fun <T> FavoritePosterRow(
    title: String,
    items: List<T>,
    key: (T) -> Long,
    name: (T) -> String,
    poster: (T) -> String?,
    start: Dp,
    onItemClick: (T) -> Unit,
    onFocus: () -> Unit,
    firstItemFocusRequester: FocusRequester?,
    track: (FocusRequester) -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(modifier) {
        HomeRowHeader(title, small = items.size.toString(), start = start)
        LazyRow(
            horizontalArrangement = Arrangement.spacedBy(20.mpx),
            contentPadding = PaddingValues(start = start, end = 64.mpx),
            modifier = Modifier.focusRestorer().focusGroup(),
        ) {
            itemsIndexed(items, key = { _, item -> key(item) }) { index, item ->
                StagePoster(
                    title = name(item),
                    onClick = { onItemClick(item) },
                    width = 196.mpx,
                    height = 294.mpx,
                    modifier = Modifier
                        .then(if (index == 0 && firstItemFocusRequester != null) Modifier.focusRequester(firstItemFocusRequester) else Modifier)
                        .tracked(track = track)
                        .onFocusChanged { if (it.hasFocus) onFocus() },
                ) {
                    AsyncImage(model = poster(item), contentDescription = null, contentScale = ContentScale.Crop, modifier = Modifier.fillMaxSize())
                }
            }
        }
    }
}

/**
 * Now Trending drawn as posters — the alternative to the full-bleed hero, for anyone who wants Home to
 * be one consistent set of rows. Read-only, like the hero: core's worker decides what is in it.
 */
@Composable
private fun TrendingPosterRow(
    title: String,
    items: List<TrendingHomeItem>,
    start: Dp,
    onItemClick: (TrendingHomeItem) -> Unit,
    onFocus: () -> Unit,
    firstItemFocusRequester: FocusRequester?,
    track: (FocusRequester) -> Unit,
    headerGap: Dp = 0.dp,
    bottomGap: Dp = 0.dp,
    onItemFocused: (Int) -> Unit = {},
) {
    Column {
        HomeRowHeader(title, small = null, start = start)
        Spacer(Modifier.height(headerGap))
        LazyRow(
            horizontalArrangement = Arrangement.spacedBy(20.mpx),
            contentPadding = PaddingValues(start = start, end = 64.mpx),
            modifier = Modifier.focusRestorer().focusGroup(),
        ) {
            itemsIndexed(items, key = { _, item -> item.stableKey }) { index, item ->
                StagePoster(
                    title = when (item) {
                        is TrendingHomeItem.Movie -> item.movie.name
                        is TrendingHomeItem.Series -> item.series.name
                    },
                    onClick = { onItemClick(item) },
                    width = 196.mpx,
                    height = 294.mpx,
                    rating = item.snapshot.rating?.let(::ratingText),
                    modifier = Modifier
                        .then(if (index == 0 && firstItemFocusRequester != null) Modifier.focusRequester(firstItemFocusRequester) else Modifier)
                        .tracked(track = track)
                        .onFocusChanged {
                            if (it.hasFocus) {
                                onItemFocused(index)
                                onFocus()
                            }
                        },
                ) {
                    AsyncImage(
                        model = when (item) {
                            is TrendingHomeItem.Movie -> item.movie.posterUrl
                            is TrendingHomeItem.Series -> item.series.posterUrl
                        },
                        contentDescription = null,
                        contentScale = ContentScale.Crop,
                        modifier = Modifier.fillMaxSize(),
                    )
                }
            }
        }
        Spacer(Modifier.height(bottomGap))
    }
}

@Composable
private fun TrendingHomeItem.toDetailsUi(meta: MetadataCacheEntity?, tmdbWins: Boolean): MediaDetailsUi {
    val snapshot = snapshot
    val providerPoster = when (this) {
        is TrendingHomeItem.Movie -> movie.posterUrl
        is TrendingHomeItem.Series -> series.posterUrl
    }
    val providerBackdrop = when (this) {
        is TrendingHomeItem.Movie -> movie.backdropUrl
        is TrendingHomeItem.Series -> series.backdropUrl
    }
    val providerTitle = when (this) {
        is TrendingHomeItem.Movie -> movie.name
        is TrendingHomeItem.Series -> series.name
    }
    val providerPlot = when (this) {
        is TrendingHomeItem.Movie -> movie.plot?.takeIf { it.isNotBlank() }
        is TrendingHomeItem.Series -> series.plot?.takeIf { it.isNotBlank() }
    }
    val tmdbPlot = meta?.overview?.takeIf { it.isNotBlank() } ?: snapshot.overview
    return MediaDetailsUi(
        title = providerTitle,
        subtitle = stringResource(if (this is TrendingHomeItem.Movie) R.string.home_trending_movie else R.string.home_trending_series),
        backdropUrl = MetadataImages.backdrop(meta?.backdropPath ?: snapshot.backdropPath, size = "w1280") ?: providerBackdrop,
        posterUrl = if (tmdbWins) {
            MetadataImages.poster(meta?.posterPath ?: snapshot.posterPath, size = "w500") ?: providerPoster
        } else {
            providerPoster ?: MetadataImages.poster(meta?.posterPath ?: snapshot.posterPath, size = "w500")
        },
        metaLine = listOfNotNull(snapshot.year?.toString(), snapshot.rating?.let { stringResource(R.string.content_rating, it) }).joinToString(" · "),
        genres = trendingJsonList(meta?.genresJson),
        plot = if (tmdbWins) tmdbPlot ?: providerPlot else providerPlot ?: tmdbPlot,
        cast = tv.own.owntv.core.metadata.MetadataCast.parse(meta?.castJson),
        directors = tv.own.owntv.core.metadata.MetadataCast.directors(meta?.castJson),
        directorsAreCreators = meta?.type == "tv",
        tmdbType = meta?.type?.takeIf { it == "movie" || it == "tv" },
        tmdbId = meta?.tmdbId,
    )
}

private fun trendingJsonList(json: String?): List<String> {
    if (json.isNullOrBlank()) return emptyList()
    return runCatching {
        val array = org.json.JSONArray(json)
        (0 until array.length()).mapNotNull { array.optString(it).takeIf(String::isNotBlank) }
    }.getOrDefault(emptyList())
}

@Composable
private fun HeroFallbackPane(
    modifier: Modifier = Modifier,
    focusRequester: FocusRequester,
    onChildFocused: () -> Unit,
) {
    Box(
        modifier = modifier
            .aspectRatio(16f / 9f)
            .focusRequester(focusRequester)
            .focusable()
            .onFocusChanged { if (it.hasFocus) onChildFocused() }
            .clip(RoundedCornerShape(20.dp))
            .background(tv.own.owntv.ui.theme.StageColors.GlassOff)
            .padding(24.dp),
        contentAlignment = Alignment.Center,
    ) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            BrandLockup(markSize = 72)
            Spacer(Modifier.height(14.dp))
            Text(
                text = stringResource(R.string.home_no_preview),
                style = tv.own.owntv.ui.theme.stageText(29, 400),
                color = tv.own.owntv.ui.theme.StageColors.Muted,
                maxLines = 3,
                overflow = TextOverflow.Ellipsis,
                // Wrapped in the middle, clear of the menu when it opens over the left edge.
                textAlign = androidx.compose.ui.text.style.TextAlign.Center,
                modifier = Modifier.widthIn(max = 1100.mpx),
            )
            Spacer(Modifier.height(8.dp))
            Text(
                text = stringResource(R.string.home_continue_empty),
                style = tv.own.owntv.ui.theme.stageText(25, 400),
                color = tv.own.owntv.ui.theme.StageColors.Muted,
                maxLines = 3,
                overflow = TextOverflow.Ellipsis,
                // Wrapped in the middle, clear of the menu when it opens over the left edge.
                textAlign = androidx.compose.ui.text.style.TextAlign.Center,
                modifier = Modifier.widthIn(max = 1100.mpx),
            )
        }
    }
}

@Composable
private fun EmptyHomeState(
    modifier: Modifier = Modifier,
) {
    Box(
        modifier = modifier
            .focusProperties { canFocus = false }
            .background(tv.own.owntv.ui.theme.StageColors.GlassOff),
        contentAlignment = Alignment.Center,
    ) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            BrandLockup(markSize = 84)
            Spacer(Modifier.height(16.dp))
            Text(
                text = stringResource(R.string.home_start_watching),
                style = tv.own.owntv.ui.theme.stageText(40, 600),
                color = tv.own.owntv.ui.theme.StageColors.Text,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Spacer(Modifier.height(8.dp))
            Text(
                text = stringResource(R.string.home_continue_empty),
                style = tv.own.owntv.ui.theme.stageText(29, 400),
                color = tv.own.owntv.ui.theme.StageColors.Muted,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
                textAlign = androidx.compose.ui.text.style.TextAlign.Center,
                modifier = Modifier.widthIn(max = 1100.mpx),
            )
        }
    }
}

@Composable
private fun AllRowsHiddenState(
    modifier: Modifier = Modifier,
) {
    Box(
        modifier = modifier
            .focusProperties { canFocus = false }
            .background(tv.own.owntv.ui.theme.StageColors.GlassOff),
        contentAlignment = Alignment.Center,
    ) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            BrandLockup(markSize = 84)
            Spacer(Modifier.height(16.dp))
            Text(
                text = stringResource(R.string.home_no_rows),
                style = tv.own.owntv.ui.theme.stageText(40, 600),
                color = tv.own.owntv.ui.theme.StageColors.Text,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Spacer(Modifier.height(8.dp))
            Text(
                text = stringResource(R.string.home_enable_rows),
                style = tv.own.owntv.ui.theme.stageText(29, 400),
                color = tv.own.owntv.ui.theme.StageColors.Muted,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
        }
    }
}

/** arte, as MagentaTV's guide and the playlist both name it (MagentaNames.key). */
private const val ARTE_KEY = "arte"
/** TMDB's answers on tonight's films are handed to the Spotlight together, at most this often. */
private const val FILM_BATCH_MS = 2_000L
private const val MINUTE_MS = 60_000L

/** What TMDB knows of the films, by [GuidePick.key]; [keys] are the picks it was read for (null: none yet). */
@androidx.compose.runtime.Immutable
private data class FilmInfos(val keys: List<String>? = null, val infos: Map<String, FilmInfo> = emptyMap())

/**
 * How long the Spotlight waits before it shows its highlights and Home lands on it (HomeScreen). Process-wide: the
 * start's wait happens once, not again when Home is built anew (back from another section).
 */
internal object SpotlightHold {
    /** At the start: the kept picks and ratings are read in milliseconds; this is only the limit. */
    const val START_MS = 1_500L
    /** On waking from standby: the scores are asked again while the television lights up (the Wi-Fi may take a few seconds). */
    const val WAKE_MS = 4_000L
    /** The start's wait, as a key beside the wakes' (their elapsedRealtime). */
    const val START = "start"
    @Volatile var startReleased = false
}

/**
 * When Home lands on the Spotlight: once per app start, and once for each Home button press (the shell counts
 * them in [tv.own.owntv.launcher.apps.LauncherSystem.homePress]). Process-wide, so a Home screen built anew
 * (back from another section) does not take the same press again.
 */
internal object SpotlightLanding {
    private var started = false
    private var handledPress = 0L

    /** A new request token, or null when nothing is new. */
    @Synchronized
    fun take(homePress: Long): Long? {
        if (!started) {
            started = true
            handledPress = homePress
            return android.os.SystemClock.elapsedRealtime()
        }
        if (homePress == 0L || homePress == handledPress) return null
        handledPress = homePress
        return homePress
    }
}

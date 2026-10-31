package tv.own.owntv.features.shell

import tv.own.owntv.ui.stage.stageGlass
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.focusGroup
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.focusable
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.produceState
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusProperties
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.focusRestorer
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onKeyEvent
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import tv.own.owntv.core.epg.displayLogoUrl
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.launch
import org.koin.compose.koinInject
import tv.own.owntv.R
import tv.own.owntv.core.database.entity.ChannelEntity
import tv.own.owntv.core.launcher.LauncherDeepLink
import tv.own.owntv.core.nav.MainSection
import tv.own.owntv.core.launcher.LauncherIntegrationRepository
import tv.own.owntv.core.launcher.LauncherLaunch
import tv.own.owntv.core.update.UpdateManager
import tv.own.owntv.features.update.UpdateDialog
import tv.own.owntv.features.update.UpdateStatusToast
import tv.own.owntv.features.downloads.DownloadsScreen
import tv.own.owntv.features.epg.EpgScreen
import tv.own.owntv.features.home.HomeScreen
import tv.own.owntv.features.home.HomeViewModel
import tv.own.owntv.features.live.LiveViewModel
import tv.own.owntv.features.movies.MoviesScreen
import tv.own.owntv.features.movies.MovieViewModel
import tv.own.owntv.features.search.SearchScreen
import tv.own.owntv.features.search.SearchViewModel
import tv.own.owntv.core.home.TrendingHomeItem
import tv.own.owntv.features.series.SeriesScreen
import tv.own.owntv.features.series.SeriesViewModel
import tv.own.owntv.core.settings.RemoteShortcutAction
import tv.own.owntv.core.settings.RemoteShortcutBindings
import tv.own.owntv.player.OwnTVPlayer
import tv.own.owntv.player.PlayerHud
import tv.own.owntv.features.shell.components.AvatarPickerDialog
import tv.own.owntv.features.shell.components.ExitDialog
import tv.own.owntv.features.shell.components.IncompleteRestoreDialog
import tv.own.owntv.features.shell.components.SettingsScreen
import tv.own.owntv.features.shell.components.SolidAmbientBackdrop
import androidx.tv.material3.Text
import androidx.compose.ui.res.stringResource
import tv.own.owntv.ui.theme.mpx
import tv.own.owntv.ui.stage.stageBackground
import tv.own.owntv.ui.components.OwnTVIcon
import tv.own.owntv.ui.components.LocalRemoteShortcuts
import tv.own.owntv.ui.components.RemoteShortcutEnvironment
import tv.own.owntv.ui.theme.LocalGlass
import tv.own.owntv.core.theme.ThemeMode
import tv.own.owntv.core.live.LiveKey
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue

/** Which layer currently holds focus (drives Back navigation). */
private enum class ShellLayer { SIDEBAR, CONTENT }

/** Player presentation: hidden, or full screen. */
internal enum class PlayerMode { NONE, FULLSCREEN }

/**
 * The MD3 shell: a fixed navigation panel (Layer 1) plus the active destination. Settings is a
 * single-pane sectioned screen; browse sections keep the Folder Rail → Content → Preview layout.
 */
@Composable
fun OwnTVShell(
    selectedSection: MainSection,
    visibleSections: Set<MainSection>,
    onSelectSection: (MainSection) -> Unit,
    themeMode: ThemeMode,
    uiZoomPercent: Int,
    onSetZoom: (Int) -> Unit,
    fontCustomization: tv.own.owntv.core.theme.FontCustomization,
    onSetFontCustomization: (tv.own.owntv.core.theme.FontCustomization) -> Unit,
    avatarId: Int,
    onSetAvatar: (Int) -> Unit,
    // The active profile's own picture, and the two things that can be done to it. Blank = none.
    avatarPath: String = "",
    onSetCustomAvatar: (java.io.File) -> Unit = {},
    onClearCustomAvatar: () -> Unit = {},
    profileName: String,
    sourceSummary: String?,
    playlists: List<tv.own.owntv.core.database.entity.SourceEntity> = emptyList(),
    activePlaylistId: Long = -1L,
    onSelectPlaylist: (Long) -> Unit = {},
    weatherInfo: tv.own.owntv.core.weather.WeatherInfo? = null,
    weatherFahrenheit: Boolean = false,
    activeProfileId: Long?,
    pendingDeepLink: LauncherDeepLink?,
    startedByLink: Boolean = false,
    onDeepLinkConsumed: () -> Unit,
    isOffline: Boolean = false,
    onExitApp: () -> Unit,
    onSwitchProfile: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val noSourceLabel = stringResource(R.string.shell_no_source)
    val subtitleLoadFailed = stringResource(R.string.content_subtitle_load_failed)

    val scope = rememberCoroutineScope()
    val sidebarFocus = remember { FocusRequester() }
    // When focus was last sent to the rail on purpose: ◀ / ▶ from the content, or the app itself
    // ([focusRail]). A rail visit that follows neither does not open the floating rail (#7.1).
    var railKeyAt by remember { mutableLongStateOf(0L) }
    val focusRail = {
        railKeyAt = android.os.SystemClock.uptimeMillis()
        runCatching { sidebarFocus.requestFocus() }
        Unit
    }
    val contentFocusManager = androidx.compose.ui.platform.LocalFocusManager.current
    val contentLtr = androidx.compose.ui.platform.LocalLayoutDirection.current == androidx.compose.ui.unit.LayoutDirection.Ltr
    val homeFirstRowFocus = remember { FocusRequester() }
    var focusedLayer by remember { mutableStateOf(ShellLayer.SIDEBAR) }
    // Whether the rail really holds focus right now. [focusedLayer] is only the last place focus was seen, so
    // it still says SIDEBAR after focus has been lost (a list that came up empty), and Back would then ask
    // "Quit?" without the user having been on the rail.
    var railTrulyFocused by remember { mutableStateOf(false) }
    var showExit by remember { mutableStateOf(false) }
    var showAvatarPicker by remember { mutableStateOf(false) }
    var showAvatarChooser by remember { mutableStateOf(false) }
    var showPlaylistPicker by remember { mutableStateOf(false) }
    // Held by the Activity-scoped ShellViewModel, so a recreation (a script-family language switch)
    // does not close a film that is playing.
    val shellVm = org.koin.androidx.compose.koinViewModel<ShellViewModel>()
    var playerMode by shellVm.playerMode
    // Deep-link: the Guide's "Add EPG" button switches to Settings and opens EPG Sources → add.
    var openEpgAdd by remember { mutableStateOf(false) }
    // Setup's "Add a TV guide" lands on the same page once the shell is up.
    LaunchedEffect(Unit) {
        if (PendingShellRequest.addEpg) {
            PendingShellRequest.addEpg = false
            openEpgAdd = true
            onSelectSection(MainSection.SETTINGS)
        }
    }
    // One-shot: set when leaving the player so the returning browse screen re-focuses the item you played.
    var restoreFocus by remember { mutableStateOf(false) }
    // The picture is on its way between full screen and the guide's preview corner (see exitToList and
    // guidePlayChannel): [handoverGrow] says which way, [handoverShrink] runs 0 → 1 either way.
    var guideHandover by remember { mutableStateOf(false) }
    var handoverGrow by remember { mutableStateOf(false) }
    val handoverShrink = remember { androidx.compose.animation.core.Animatable(0f) }
    val handoverSpec = tv.own.owntv.ui.theme.stageSlide<Float>(360)
    var searchReturn by remember { mutableStateOf<MainSection?>(null) }
    var restoreTrendingSearchFocus by remember { mutableStateOf(false) }
    var trendingSearchActive by remember { mutableStateOf(false) }
    val player = koinInject<OwnTVPlayer>()
    val settingsRepo = koinInject<tv.own.owntv.core.settings.SettingsRepository>()
    val remoteShortcutsEnabled by settingsRepo.chNavEnabled.collectAsStateWithLifecycle(initialValue = true)
    val clockColors by settingsRepo.clockColors.collectAsStateWithLifecycle(initialValue = tv.own.owntv.core.settings.ClockColors())
    val remoteShortcutBindings by settingsRepo.remoteShortcutBindings.collectAsStateWithLifecycle(
        initialValue = RemoteShortcutBindings.defaults,
    )
    val ambientGlowEnabled by settingsRepo.ambientGlowEnabled.collectAsStateWithLifecycle(initialValue = false)
    val ambientGlowPulse by settingsRepo.ambientGlowPulse.collectAsStateWithLifecycle(initialValue = true)
    val shellAnimationLevel by settingsRepo.animationLevel.collectAsStateWithLifecycle(initialValue = tv.own.owntv.core.theme.AnimationLevel.FULL)
    // Stage navigation: Mayo TV has one rail (26 Oct 2026): the icon strip always at the left edge, opening
    // with names while it holds focus. No floating or docked variants, no counts or detail lines.
    // Where ▶ out of the Stage rail returns to: the content, exactly as it was left.
    val contentAreaFocus = remember { FocusRequester() }
    var homeEntry by remember { mutableStateOf<(() -> Boolean)?>(null) }
    // The guide's entry hook: the TV Guide, or Live TV in Guide view (null while Live TV shows its list).
    var guideEntry by remember { mutableStateOf<(() -> Boolean)?>(null) }
    var vodEntry by remember { mutableStateOf<(() -> Boolean)?>(null) }
    var downloadsEntry by remember { mutableStateOf<(() -> Boolean)?>(null) }
    var moreEntry by remember { mutableStateOf<(() -> Boolean)?>(null) }
    var sportsEntry by remember { mutableStateOf<(() -> Boolean)?>(null) }
    // Home's Live sports row opens a match page through the same request the player's Match button uses.
    val sportsMatchRequests = koinInject<tv.own.owntv.features.sports.SportsPlayback>()
    // More › Settings opens Settings at a group, or with its search open.
    var settingsStart by remember { mutableStateOf<tv.own.owntv.features.shell.components.SettingsStart?>(null) }
    // The playlist pill's place on screen, where the playlist menu redraws it above its scrim.
    var playlistPillBounds by remember { mutableStateOf<androidx.compose.ui.geometry.Rect?>(null) }
    val subtitleController = koinInject<tv.own.owntv.core.subtitles.SubtitleController>()
    val subtitleContext by subtitleController.current.collectAsStateWithLifecycle()
    var showSubtitleSearch by remember { mutableStateOf(false) }
    // Local subtitle-file picker — the same TV-safe in-app browser local M3U import uses.
    var showLocalSubPicker by remember { mutableStateOf(false) }
    val localSubToast = tv.own.owntv.ui.components.rememberInAppToast()
    // Metadata allowance: tell the user ONCE per app start that their daily share of the shared
    // metadata service is gone, rather than letting posters and plots quietly stop appearing. Lives in
    // the shell because it can happen on any screen, and the shell is the one toast that is always
    // composed. `remember` (not rememberSaveable) is the once-per-launch scope we want.
    val metadataBudget = koinInject<tv.own.owntv.core.metadata.MetadataBudget>()
    val budgetRefusedAt by metadataBudget.refusedAt.collectAsStateWithLifecycle()
    var budgetNoticeShown by remember { mutableStateOf(false) }
    val budgetNotice = androidx.compose.ui.res.stringResource(tv.own.owntv.R.string.settings_metadata_limit_reached)
    LaunchedEffect(budgetRefusedAt) {
        if (budgetRefusedAt > 0L && !budgetNoticeShown) {
            budgetNoticeShown = true
            localSubToast.show(budgetNotice)
        }
    }
    val mpvEngine = remember(player) { tv.own.owntv.player.MpvPlaybackEngine(player) }
    // Audio focus + MediaSession. This is the only place that knows which engine currently owns
    // the speaker, so it hands that engine over and takes it back when the player closes.
    val playbackSession = koinInject<tv.own.owntv.player.PlaybackSession>()
    val launcherIntegrationRepository = koinInject<LauncherIntegrationRepository>()
    val homeVm = org.koin.androidx.compose.koinViewModel<HomeViewModel>()
    val movieVm = org.koin.androidx.compose.koinViewModel<MovieViewModel>()
    val seriesVm = org.koin.androidx.compose.koinViewModel<SeriesViewModel>()
    val searchVm = org.koin.androidx.compose.koinViewModel<SearchViewModel>()
    // Same activity-scoped instances the Live/Guide screens use — lets the fullscreen HUD zap channels
    // up/down (CH+/CH-). Guide tunes start through LiveViewModel too (they set zapSource = LIVE_TV), so
    // there is exactly ONE zap path: liveVm's. The Guide keeps its own EpgViewModel only for the grid.
    val liveVm = org.koin.androidx.compose.koinViewModel<LiveViewModel>()
    // "Previous" from the system media controls on a live channel goes back a channel.
    DisposableEffect(playbackSession, liveVm) {
        playbackSession.livePrevious = { liveVm.previousChannel() }
        onDispose { playbackSession.livePrevious = null }
    }
    val epgVm = org.koin.androidx.compose.koinViewModel<tv.own.owntv.features.epg.EpgViewModel>()
    val liveCanZap by liveVm.canZap.collectAsStateWithLifecycle()
    // Full-screen is running on the ExoPlayer engine (a promoted Live preview) rather than mpv.
    val liveOnExo by liveVm.liveOnExo.collectAsStateWithLifecycle()
    val previousLiveChannel by liveVm.previousChannelTarget.collectAsStateWithLifecycle()
    // A catch-up archive programme is playing (Guide "Watch from start" or the Live TV catch-up picker)
    // rather than the live stream — the HUD swaps live-only controls for the VOD ones.
    val catchupActive by liveVm.catchupActive.collectAsStateWithLifecycle()
    val vodExoActive by player.exoActiveState.collectAsStateWithLifecycle()
    // Auto frame rate: only ever applied to the FULL-SCREEN surface (never the in-pane Live
    // preview) — see FrameRateController.
    val autoFrameRate by settingsRepo.autoFrameRate.collectAsStateWithLifecycle(initialValue = false)
    val afrMatchResolution by settingsRepo.afrMatchResolution.collectAsStateWithLifecycle(initialValue = false)
    val afrPauseSecs by settingsRepo.afrPauseSecs.collectAsStateWithLifecycle(initialValue = 0)
    // ...and the one-time suggestion to turn it on, for the 25-fps-on-60-Hz judder the direct render path
    // cannot fix by itself. `true` until the flag is read, so it can never flash on first frame.
    // Direct tune (type a channel number on the remote). Settings › Live TV › Channel numbers; default on.
    val directTuneEnabled by settingsRepo.directTune.collectAsStateWithLifecycle(initialValue = true)
    // "Prefer EPG logos": start following the setting once, here rather than in Application.onCreate —
    // the store queries nothing at all while the toggle is off, so cold start stays free of EPG reads.
    val epgDaoForLogos = koinInject<tv.own.owntv.core.database.dao.EpgDao>()
    val customizeForLogos = koinInject<tv.own.owntv.core.customize.CustomizationStore>()
    val logoScope = rememberCoroutineScope()
    LaunchedEffect(Unit) {
        tv.own.owntv.core.epg.EpgLogoStore.start(logoScope, settingsRepo, epgDaoForLogos, customizeForLogos)
    }
    // Live rewind / timeshift: whether the live channel supports catch-up, and how far behind live we are.
    val canRewindLive by liveVm.canRewindLive.collectAsStateWithLifecycle()
    // The offset itself ticks once a second while an archive plays. Held as State and deliberately NOT
    // read here — reading it in the shell's own scope invalidated the whole shell body every second.
    // It is handed to the HUD as a lambda, so only the HUD reads the ticking value; the shell needs
    // just the on/off fact, which changes when the user enters or leaves the rewind and no oftener.
    val timeshiftOffsetState = liveVm.timeshiftOffsetSec.collectAsStateWithLifecycle()
    val watchingWallState = liveVm.watchingWallMs.collectAsStateWithLifecycle()
    val onLocalCopy by liveVm.onLocalCopy.collectAsStateWithLifecycle()
    val timeshifted by remember(liveVm) {
        liveVm.timeshiftOffsetSec.map { it != null }.distinctUntilChanged()
    }.collectAsStateWithLifecycle(false)
    // Which section armed the current fullscreen stream — picks whose channel list CH+/CH- step through.
    var zapSource by shellVm.zapSource
    // The multi-day guide for the rewind timeline is read only while it can be shown (the same test
    // the HUD applies below); otherwise its query would re-run on every channel-list focus step.
    val wantTimeline = playerMode == PlayerMode.FULLSCREEN && zapSource == MainSection.LIVE_TV && !catchupActive && canRewindLive
    val timelineProgrammes by remember(liveVm, wantTimeline) {
        if (wantTimeline) liveVm.timelineProgrammes else flowOf(emptyList())
    }.collectAsStateWithLifecycle(emptyList())
    // In-player channel-list overlay (Left while controls hidden, live only).
    var showChannelList by remember { mutableStateOf(false) }
    // In-player watch-history list (Right while controls hidden, live only).
    var showHistoryList by remember { mutableStateOf(false) }
    // Multiview: the grid, while it is up, and which tile is waiting for a channel to be picked.
    val multiviewEnabled by settingsRepo.multiviewEnabled.collectAsStateWithLifecycle(false)
    val recordWatchingEnabled by settingsRepo.recordWhatImWatching.collectAsStateWithLifecycle(false)
    val liveLeftRightRewinds by settingsRepo.liveLeftRightRewinds.collectAsStateWithLifecycle(false)
    val multiviewTileCount by settingsRepo.multiviewTiles.collectAsStateWithLifecycle(
        tv.own.owntv.core.live.DEFAULT_MULTIVIEW_TILES,
    )
    val enginePool = koinInject<tv.own.owntv.player.LiveEnginePool>()
    val streamRegistry = koinInject<tv.own.owntv.core.live.OpenStreamRegistry>()
    var multiview by remember { mutableStateOf<tv.own.owntv.features.multiview.MultiviewState?>(null) }
    var multiviewPickFor by remember { mutableStateOf<Int?>(null) }
    // Publish the active engine to the system (audio focus + MediaSession), and detach when the player
    // is closed — an inactive session must not keep answering the TV's transport keys or the Assistant.
    // During Multiview that is the tile with the sound; the preview engine it used to stay on is stopped.
    val multiviewAudibleEngine = multiview?.let { it.pool.peek(it.audible) }
    LaunchedEffect(liveOnExo, playerMode, multiviewAudibleEngine) {
        playbackSession.attach(
            when {
                multiview != null -> multiviewAudibleEngine
                playerMode == PlayerMode.NONE -> null
                liveOnExo -> liveVm.previewEngine
                else -> mpvEngine
            },
        )
    }
    // The pool outlives this composable (it is app-wide), the grid state does not. If the shell is torn
    // down with the grid up, release its engines and stream claims, or they play on as ghosts that
    // refuse later tiles and recordings.
    val currentMultiview by rememberUpdatedState(multiview)
    DisposableEffect(Unit) {
        onDispose { currentMultiview?.releaseAll() }
    }
    // Channels kept from the Live list (the second way into the grid). Pressing play on any channel is
    // what says "now": the grid opens with them already in it, and the selection is spent.
    val multiviewSelection by liveVm.multiviewSelection.collectAsStateWithLifecycle()
    val zapChannels by liveVm.zapChannels.collectAsStateWithLifecycle()
    val zapListTitle by liveVm.zapListTitle.collectAsStateWithLifecycle()
    val zapListKey by liveVm.zapListKey.collectAsStateWithLifecycle()
    // Favorites and History have no provider name to show — their labels are UI strings, so the overlay
    // used to head both of them "All channels".
    val zapOverlayTitle = zapListTitle ?: when (zapListKey) {
        LiveKey.Favorites -> stringResource(R.string.content_category_favorites)
        LiveKey.History -> stringResource(R.string.content_category_history)
        LiveKey.Catchup -> stringResource(R.string.content_catchup)
        else -> stringResource(R.string.content_category_all_channels)
    }
    val showCategoryBrowser by liveVm.showCategoryBrowser.collectAsStateWithLifecycle()
    val browserRailItems by liveVm.railItems.collectAsStateWithLifecycle()
    val browserRailCounts by liveVm.railCounts.collectAsStateWithLifecycle()
    val previewChannel by liveVm.previewChannel.collectAsStateWithLifecycle()
    val playerRecording by liveVm.playerRecording.collectAsStateWithLifecycle()
    val overlayProgrammes by liveVm.nowProgrammes.collectAsStateWithLifecycle()
    val overlayFavoriteIds by liveVm.favoriteIds.collectAsStateWithLifecycle()
    val overlayPlaylistMarks by liveVm.playlistMarks.collectAsStateWithLifecycle()
    // Favorite state for the player HUD's in-stream favorite toggle (live channel / movie / series).
    val liveFavoriteIds by liveVm.favoriteIds.collectAsStateWithLifecycle()
    val playingMovie by movieVm.playingMovie.collectAsStateWithLifecycle()
    val movieFavoriteIds by movieVm.favoriteIds.collectAsStateWithLifecycle()
    val playingSeries by seriesVm.playingSeries.collectAsStateWithLifecycle()
    val seriesFavoriteIds by seriesVm.favoriteIds.collectAsStateWithLifecycle()
    // Favorite toggle for whatever is playing: the live channel, the movie, or the series (episodes
    // favorite their parent series). Picked by the section that armed the stream. Hoisted here because
    // the audio capsule in the top bar carries the same control as the fullscreen HUD.
    val favToggle: (() -> Unit)? = when (zapSource) {
        MainSection.LIVE_TV -> previewChannel?.let { ch -> { liveVm.toggleFavorite(ch) } }
        MainSection.MOVIES -> playingMovie?.let { m -> { movieVm.toggleFavorite(m) } }
        MainSection.SERIES -> playingSeries?.let { s -> { seriesVm.toggleFavorite(s) } }
        else -> null
    }
    val favActive = when (zapSource) {
        MainSection.LIVE_TV -> previewChannel?.let { liveFavoriteIds.contains(it.id) } ?: false
        MainSection.MOVIES -> playingMovie?.let { movieFavoriteIds.contains(it.id) } ?: false
        MainSection.SERIES -> playingSeries?.let { seriesFavoriteIds.contains(it.id) } ?: false
        else -> false
    }
    // Tell core which playlist is on screen, so its background catalogue drain steps aside while the
    // user is watching. Core cannot work this out alone: the player engines are handed
    // a URL and have no notion of a sourceId, and fullscreen playback deliberately never claims a
    // connection in OpenStreamRegistry — that register is the Multiview/recording budget. This screen
    // holds the row, so this is the only place the answer exists.
    val watchSession = koinInject<tv.own.owntv.core.live.WatchSession>()
    val watchingSourceId = if (playerMode == PlayerMode.NONE) {
        null
    } else {
        when (zapSource) {
            MainSection.LIVE_TV -> previewChannel?.sourceId
            MainSection.MOVIES -> playingMovie?.sourceId
            MainSection.SERIES -> playingSeries?.sourceId
            else -> null
        }
    }
    // DisposableEffect, not LaunchedEffect: zapping to another playlist has to close the old session
    // before opening the new one, and leaving the shell has to close the last one — a leaked session
    // would hold the drain off for good.
    DisposableEffect(watchingSourceId) {
        watchingSourceId?.let { watchSession.open(it) }
        onDispose { watchingSourceId?.let { watchSession.close(it) } }
    }
    // Current programme per channel for the in-player channel list overlay (small subtitle under each row).
    // Only resolved while the overlay is actually open. Keyed on the channel set so a zap-list change re-resolves.
    // Shares the Live list's resolved titles, so opening the overlay over a list already on screen
    // asks for nothing, and only genuinely new channels cost a query.
    val overlayNowPlaying by liveVm.nowPlaying.collectAsStateWithLifecycle()
    LaunchedEffect(showChannelList, zapChannels) {
        if (showChannelList && zapChannels.size > 1) liveVm.ensureNowPlaying(zapChannels)
    }
    // Recently-watched channels for the right-hand history overlay — re-read each time it opens (and
    // after a zap, since tuning writes a new history row) so the newest channel is always on top.
    val historyEntries by produceState(emptyList<tv.own.owntv.core.database.dao.ChannelWithWatchedAt>(), showHistoryList, previewChannel?.id) {
        if (!showHistoryList) { value = emptyList(); return@produceState }
        value = runCatching { liveVm.historyChannels() }.getOrDefault(emptyList())
    }
    val historyChannels = remember(historyEntries) { historyEntries.map { it.channel } }
    val historyWatchedAt = remember(historyEntries) { historyEntries.associate { it.channel.id to it.watchedAt } }
    val historyNowPlaying = overlayNowPlaying // one shared map; the history rail adds to it below
    LaunchedEffect(historyChannels) { liveVm.ensureNowPlaying(historyChannels) }
    // The single most-recent resumable item, surfaced as a shared top-bar "Continue" chip.
    // Verlauf plus Film- und Serien-Nachschlagen: not while the first picture comes up.
    val continueTarget by produceState<tv.own.owntv.features.home.ContinueTarget?>(null) {
        tv.own.owntv.StartupGate.awaitSettled()
        homeVm.continueTarget.collect { value = it }
    }

    // Completed when the startup action below has decided what the start looks like (a picture, or none).
    val startModeDone = remember { kotlinx.coroutines.CompletableDeferred<Unit>() }
    // Per-profile startup action runs once when the authenticated shell first appears. With one unlocked
    // profile that is immediately; profile/PIN gates keep the shell out of composition until authorized.
    val resumeSettings = koinInject<tv.own.owntv.core.settings.SettingsRepository>()
    val startupChannelUnavailable = androidx.compose.ui.res.stringResource(tv.own.owntv.R.string.settings_startup_channel_unavailable)
    LaunchedEffect(Unit) {
        // Settled whichever way this ends: the parts of the start that depend on the start mode wait for it.
        coroutineContext[kotlinx.coroutines.Job]?.invokeOnCompletion { startModeDone.complete(Unit) }
        // A launcher tile chose what to show; the start mode would only overwrite it (last channel over a match page).
        if (startedByLink || playerMode != PlayerMode.NONE) return@LaunchedEffect
        val pid = resumeSettings.activeProfileId.first()
        val mode = resumeSettings.startupMode(pid).first()
        // shell-composed → tune-start took 575 ms on the TV: these two say which part.
        tv.own.owntv.perf.StartLog.stampOnce("start-mode")
        when (mode) {
            tv.own.owntv.core.settings.StartupMode.LAST_CHANNEL -> {
                val ch = liveVm.lastWatchedLiveChannel()
                tv.own.owntv.perf.StartLog.stampOnce("last-channel")
                if (ch != null && playerMode == PlayerMode.NONE && sportsMatchRequests.pendingMatch.value == null) {
                    zapSource = MainSection.LIVE_TV
                    // There is no caller-owned browse rail here. Let LiveViewModel build the channel's
                    // provider context instead of permanently arming a one-item list that disables CH+/CH-.
                    liveVm.watchFullscreen(ch, emptyList())
                    // Live TV is built behind the picture from the start, so Back finds the guide ready.
                    onSelectSection(MainSection.LIVE_TV)
                    playerMode = PlayerMode.FULLSCREEN
                }
            }
            // Open straight to Live TV on the Favorites folder, with focus landing inside the channel list
            // (restoreFocus drives LiveScreen to focus the first/last channel, not the nav panel).
            tv.own.owntv.core.settings.StartupMode.FAVORITES -> {
                onSelectSection(MainSection.LIVE_TV)
                liveVm.select(tv.own.owntv.core.live.LiveKey.Favorites)
                restoreFocus = true
            }
            tv.own.owntv.core.settings.StartupMode.SPECIFIC_CHANNEL -> {
                val ref = resumeSettings.startupChannel(pid).first()
                var launch: LauncherLaunch? = null
                if (ref != null) {
                    if (!ref.remoteId.isNullOrBlank()) {
                        launch = launcherIntegrationRepository.resolveLaunch(
                            pid,
                            LauncherDeepLink.Live(sourceId = ref.sourceId, remoteId = ref.remoteId),
                        )
                    }
                    if (launch == null) {
                        launch = launcherIntegrationRepository.resolveLaunch(
                            pid,
                            LauncherDeepLink.Live(sourceId = ref.sourceId, name = ref.name),
                        )
                    }
                    if (launch == null && ref.itemId > 0L) {
                        launch = launcherIntegrationRepository.resolveLaunch(
                            pid,
                            LauncherDeepLink.Live(sourceId = ref.sourceId, itemId = ref.itemId),
                        )
                    }
                }
                val channel = (launch as? LauncherLaunch.Live)?.channel
                if (channel != null && liveVm.isVisibleToActiveProfile(channel) && playerMode == PlayerMode.NONE) {
                    zapSource = MainSection.LIVE_TV
                    liveVm.watchFullscreen(channel, emptyList())
                    onSelectSection(MainSection.LIVE_TV)
                    playerMode = PlayerMode.FULLSCREEN
                } else {
                    onSelectSection(MainSection.HOME)
                    localSubToast.show(startupChannelUnavailable)
                }
            }
            tv.own.owntv.core.settings.StartupMode.HOME -> Unit
        }
    }

    // No separate TV Guide section any more: like TiviMate's "TV", Live TV is the guide. Whatever still
    // asks for the guide (a shortcut, the player, the start screen) lands on Live TV.
    LaunchedEffect(selectedSection) {
        if (selectedSection == MainSection.EPG) onSelectSection(MainSection.LIVE_TV)
    }

    // Movies/Series/Live load on first open via their reactive Paging flows — their indexed first page is
    // cheap, so they need NO preloading (a Live-TV-only user pays nothing for them). The TV Guide is the ONE
    // exception: load() pulls every guide channel + a programme window, which is heavy enough that doing it on
    // open felt slow. So warm EPG in the background shortly after the shell renders — opening the Guide is then
    // instant, matching how it behaved before. (EpgScreen also calls load() on mount, so this is a pure pre-warm
    // and is skipped if the user is already on EPG.)
    val sectionNow by rememberUpdatedState(selectedSection)
    LaunchedEffect(Unit) {
        tv.own.owntv.StartupGate.awaitFirstFrame()
        // Live TV is the guide now: it loads itself, and a second load() would cancel that one and start over.
        val guide = epgVm.state.value
        if (sectionNow != MainSection.LIVE_TV && guide.channels.isEmpty() && !guide.loading) {
            tv.own.owntv.perf.StartLog.stamp("epg-preload"); epgVm.load()
        }
    }

    // Opening content from a browse screen goes fullscreen.
    fun openFullscreen(source: MainSection = selectedSection) {
        restoreFocus = false
        zapSource = source
        homeVm.stopPreview()
        // Only Live TV promotes a channel to the ExoPlayer engine. Movies/Series/Search/EPG/Downloads all
        // play on mpv — clear any stale live-on-ExoPlayer flag so the shell renders mpv, not the old channel.
        if (source != MainSection.LIVE_TV) liveVm.clearLiveOnExo()
        // Only Movies/Series/Downloads carry an external-subtitle item context (set by their play
        // paths). Anything else (Live/EPG/Search-channel) clears it so ADD SUBTITLES never shows stale.
        if (source != MainSection.MOVIES && source != MainSection.SERIES && source != MainSection.DOWNLOADS) {
            subtitleController.clear()
        }
        playerMode = PlayerMode.FULLSCREEN
    }
    // Start something from a tile, the Home row, Search or a reminder: tune it, then go full screen. One copy
    // of each, so every way in behaves the same (an external player takes the item instead of the screen).
    val playLiveById: (Long, List<ChannelEntity>) -> Unit = { id, zap ->
        scope.launch { if (liveVm.ensurePlayingByIdAsync(id, zap)) openFullscreen(MainSection.LIVE_TV) }
    }
    val playMovieById: (Long, Long) -> Unit = { id, pos ->
        scope.launch { if (movieVm.playByIdAsync(id, pos) && !movieVm.externalPlayerOn.value) openFullscreen(MainSection.MOVIES) }
    }
    val playEpisodeById: (Long, Long, Long) -> Unit = { seriesId, epId, pos ->
        scope.launch { if (seriesVm.playFromHomeAsync(seriesId, epId, pos) && !seriesVm.externalPlayerOn.value) openFullscreen(MainSection.SERIES) }
    }
    // [toRail] false: the guide takes focus itself (its row, the channel carrying on in its corner), so the
    // menu does not flash open on the way.
    // [intoLive]: the caller has just chosen Live TV, but this function still sees the section it was composed
    // with, so it must not take that for "leaving Live TV" and tear the live stream down.
    fun closePlayer(toRail: Boolean, intoLive: Boolean = false) {
        // Flush the resume position BEFORE the stream is torn down — stop() drops the loaded item's
        // identity, after which neither view model can tell the position was theirs. Both calls are
        // no-ops unless the player is on that section's item.
        movieVm.saveProgressNow()
        seriesVm.saveEpisodeProgressNow()
        playerMode = PlayerMode.NONE
        showChannelList = false
        showHistoryList = false
        liveVm.hideCategoryBrowser()
        liveVm.onFullscreenExited() // no longer full-screen on ExoPlayer → let the preview re-take the engine
        player.stop()
        subtitleController.clear() // leaving the player drops the OpenSubtitles item context
        if (!intoLive && selectedSection != MainSection.LIVE_TV) liveVm.clearLiveOnExo()
        restoreFocus = true
        if (toRail) focusRail()
    }
    val exitPlayer = { closePlayer(toRail = true) }
    // Back out of a live channel lands in the guide, with the channel carrying on in its preview, as
    // Jonathan wants it. Films, series and a hidden list keep the plain exit.
    // As in TiviMate the picture does not just vanish: it shrinks into the guide's preview corner and plays
    // on there, sound and all (Jonathan, 27 Oct 2026). The guide is laid out under it meanwhile, so the
    // corner it lands in is the one the guide reports.
    val livePreviewOn by liveVm.livePreviewEnabled.collectAsStateWithLifecycle()
    // Where focus waits when the guide has no row to take it yet (it is still loading, or not built): with the
    // player's controls gone and nobody focused, Compose hands focus to the first thing on screen, the menu.
    var handoverParking by remember { mutableStateOf(false) }
    val handoverFocus = remember { FocusRequester() }
    val parkFocus = {
        handoverParking = true
        runCatching { handoverFocus.requestFocus() }
        Unit
    }
    // The same place while one screen gives way to another in the content area (see LocalParkFocus). The next
    // screen takes the focus within a frame or two; if none does, the content area gets it rather than nobody.
    val parkForScreenChange: () -> Unit = {
        parkFocus()
        scope.launch {
            repeat(20) {
                withFrameNanos { }
                if (!handoverParking) return@launch
            }
            runCatching { contentAreaFocus.requestFocus() }
        }
    }
    // Back is pressed and the guide is still getting ready behind the picture (see GuidePreviewSpot.ready).
    var exitPending by remember { mutableStateOf(false) }
    val exitToList: () -> Unit = exit@{
        if (guideHandover || exitPending) return@exit
        // A channel played from a match page in Sports goes back to that page, the same glide as into the guide:
        // the page is laid out behind the picture (sportsBehind) and reports its corner like the guide.
        val toSports = selectedSection == MainSection.SPORTS && zapSource == MainSection.LIVE_TV && playerMode == PlayerMode.FULLSCREEN
        val toList = zapSource == MainSection.LIVE_TV && MainSection.LIVE_TV in visibleSections
        val intoGuide = (toSports || toList) && playerMode == PlayerMode.FULLSCREEN && liveOnExo && !catchupActive &&
            livePreviewOn && multiview == null
        if (!intoGuide) {
            if (toSports) {
                // The page stayed behind the picture when the stream is on the preview engine: nothing to rebuild it from.
                if (liveOnExo && multiview == null) sportsMatchRequests.returnToMatch = null
                closePlayer(toRail = false, intoLive = livePreviewOn)
                return@exit
            }
            if (!toList) { exitPlayer(); return@exit }
            // A live channel without the glide (on mpv, catch-up, no corner preview) still lands in the guide on
            // its channel, not on the menu: the guide is chosen first, so leaving another section does not stop
            // the stream on the way, and it takes the focus itself.
            // The guide's row takes focus before the player's controls leave, so focus never falls to the menu.
            val guideFocused = selectedSection == MainSection.LIVE_TV && guideEntry?.invoke() == true
            if (selectedSection != MainSection.LIVE_TV) onSelectSection(MainSection.LIVE_TV)
            if (!guideFocused) parkFocus()
            closePlayer(toRail = false, intoLive = true)
            return@exit
        }
        // Opened from the guide, the guide is still laid out behind the picture (guideBehind) and its corner
        // known: the shrink starts on the very next frame. Building the guide afresh first is what kept the
        // shrink from showing on the television: its first frames were spent laying the grid out.
        val fromScratch = !toSports && selectedSection != MainSection.LIVE_TV
        if (fromScratch) {
            onSelectSection(MainSection.LIVE_TV)
            tv.own.owntv.features.epg.GuidePreviewSpot.rect = null
        }
        exitPending = true
        scope.launch {
            // The guide gets ready behind the picture first (loaded, on the playing row, axis at now), so the
            // glide lands on a grid that does not move any more. Bounded: a guide that cannot load must not
            // hold Back; the picture simply stays full screen meanwhile.
            var frames = 0
            // Back in the first seconds after the start is the one case where the guide is not standing yet: it
            // was kept from loading until the picture came. Longer for that window only.
            val maxFrames = if (tv.own.owntv.StartupGate.isSettled) 45 else 90
            // (The match page is built already: it stayed behind the picture, so there is nothing to wait for.)
            if (!toSports) while (!tv.own.owntv.features.epg.GuidePreviewSpot.ready && frames++ < maxFrames) withFrameNanos { }
            // Closed some other way while it got ready (a shortcut, the Home key): nothing left to glide.
            if (playerMode != PlayerMode.FULLSCREEN) { exitPending = false; return@launch }
            // The guide's row takes focus before the player's controls leave: with the controls gone and nobody
            // focused, Compose hands focus to the first thing on screen, the top of the menu, which flashed open.
            if ((if (toSports) sportsEntry else guideEntry)?.invoke() != true) parkFocus()
            guideHandover = true
            handoverGrow = false
            exitPending = false
            handoverShrink.snapTo(0f)
            frames = 0
            while (tv.own.owntv.features.epg.GuidePreviewSpot.rect == null && frames++ < 30) withFrameNanos { }
            if (tv.own.owntv.features.epg.GuidePreviewSpot.rect != null) handoverShrink.animateTo(1f, handoverSpec)
            closePlayer(toRail = false, intoLive = true)
            // The picture stays where it is until the guide claims it for its corner (inCorner), so the view
            // showing it is never dropped in between.
            frames = 0
            while (!tv.own.owntv.features.epg.GuidePreviewSpot.video && frames++ < 20) withFrameNanos { }
            guideHandover = false
            handoverShrink.snapTo(0f)
            // The guide could not take the picture (its preview is off, the stream failed): a stream still
            // running with no corner to show it would play on as sound alone. Given time first, as a guide
            // built from scratch on a slow box can take a while to claim it.
            kotlinx.coroutines.delay(2_500)
            // The page is back for good: what it was reopened from the player for is spent.
            if (toSports) sportsMatchRequests.returnToMatch = null
            if (playerMode == PlayerMode.NONE && (sectionNow == MainSection.LIVE_TV || sectionNow == MainSection.SPORTS) &&
                !tv.own.owntv.features.epg.GuidePreviewSpot.video) {
                val engineState = liveVm.previewEngine.state.value
                if (engineState == tv.own.owntv.player.LivePreviewEngine.State.PLAYING ||
                    engineState == tv.own.owntv.player.LivePreviewEngine.State.LOADING) liveVm.stopPreview()
            }
        }
    }
    // Back from full screen returns to where the channel was opened. From Sports that is the match page
    // (exitToList), with the channel carrying on in its corner; Back used to land in the guide
    // (Jonathan, 27 Oct 2026). Long Menu's "guide" still goes to the guide.
    val exitBack: () -> Unit = exit@{
        if (guideHandover) return@exit
        exitToList()
    }
    // A one-stream playlist that a download was using: core paused the download; this asks what next.
    tv.own.owntv.features.downloads.DownloadWatchPrompt(onClosePlayer = { if (playerMode != PlayerMode.NONE) exitPlayer() })
    /**
     * Turn the channel on screen into tile 1 of the grid, with [extra] filling the tiles after it.
     *
     * The fullscreen stream is stopped first, always: tile 1 opens an engine of its own, and a
     * playlist that allows two streams would otherwise be asked for three.
     */
    val openMultiview = { first: tv.own.owntv.core.database.entity.ChannelEntity?,
                          extra: List<tv.own.owntv.core.database.entity.ChannelEntity> ->
        liveVm.previewEngine.stop()
        player.stop()
        val state = tv.own.owntv.features.multiview.MultiviewState(
            pool = enginePool,
            registry = streamRegistry,
            live = liveVm,
            maxTiles = multiviewTileCount,
        )
        var tile = 0
        first?.let { state.fill(tile++, it) }
        // Channels kept with "Add to Multiview" grow the grid as they are placed, up to the ceiling —
        // the user asked for these by name, so they are the one case that sizes the grid itself.
        extra.filter { it.id != first?.id }.forEach { channel ->
            if (tile >= state.tiles.size) state.addTile()
            if (tile < state.tiles.size) state.fill(tile++, channel)
        }
        liveVm.clearMultiviewSelection()
        state
    }
    // The sleep timer's "stop" on a television. Closing the player alone is not a stop here: a
    // live channel carries on in the preview pane, and a Multiview grid is not the player at all. So
    // all three go, in the order Multiview's own Back uses, and the preview last.
    val sleepTimer = koinInject<tv.own.owntv.player.SleepTimer>()
    val stopForSleep by rememberUpdatedState {
        multiview?.let { grid ->
            grid.releaseAll()
            multiview = null
            multiviewPickFor = null
        }
        if (playerMode != PlayerMode.NONE) exitPlayer()
        liveVm.stopPreview()
    }
    DisposableEffect(sleepTimer) {
        sleepTimer.stopPlayback = { stopForSleep() }
        onDispose { sleepTimer.stopPlayback = null }
    }
    // The kept selection is spent the moment a channel actually starts playing full screen: that
    // press is the "and now open it" the plan describes, and nothing else in the app claims it.
    LaunchedEffect(playerMode, previewChannel?.id, multiviewSelection.size) {
        if (multiviewEnabled &&
            multiview == null &&
            multiviewSelection.isNotEmpty() &&
            playerMode == PlayerMode.FULLSCREEN &&
            zapSource == MainSection.LIVE_TV &&
            previewChannel != null
        ) {
            multiview = openMultiview(previewChannel, multiviewSelection)
        }
    }

    // Whichever engine is on the speaker right now — what the play/pause shortcut acts on.
    val activeEngine = if (liveOnExo) liveVm.previewEngine else mpvEngine
    // Focus on a top-right pill (reached from the rail) is not "in the rail": the rail closes, so its
    // scrim never covers the pill that holds focus.
    var clusterFocused by remember { mutableStateOf(false) }
    val railHasFocus = focusedLayer == ShellLayer.SIDEBAR && !clusterFocused
    // Whether the rail *looks* focused. Focus also lands on the rail by itself — for a moment while a
    // page swaps, or for as long as a series is still loading and has nothing to focus — and showing
    // those visits opened the floating rail and restarted its hide delay, so it popped up on every
    // page (#7.1). So a visit opens it only when it was meant: within [RAIL_KEY_WINDOW_MS] of ◀ / ▶ or
    // of [focusRail], or once a key is pressed while the rail holds focus.
    var railFocused by remember { mutableStateOf(false) }
    // Keyed on [railKeyAt] too: at start-up focus already counts as on the rail before [focusRail] runs.
    // Never while the player is on screen: after a start in full screen focus still counts as on the rail, and
    // the menu (with the guide's categories) must not slide in behind the picture when Back brings it down.
    LaunchedEffect(railHasFocus, railKeyAt, playerMode) {
        railFocused = railHasFocus && playerMode == PlayerMode.NONE &&
            android.os.SystemClock.uptimeMillis() - railKeyAt <= RAIL_KEY_WINDOW_MS
    }
    // The strip at rest, the menu with names while it holds focus.
    val railState = if (railFocused && playerMode == PlayerMode.NONE) tv.own.owntv.features.shell.components.RailState.OPEN else tv.own.owntv.features.shell.components.RailState.CAPSULE
    val railPush = tv.own.owntv.features.shell.components.rememberRailPush(railState)
    // The guide, and a match page: pages that take the strip's room while they hold focus.
    val fullPage = selectedSection == MainSection.SPORTS && tv.own.owntv.features.shell.components.StageRailOpen.fullPage
    val guideSection = selectedSection == MainSection.LIVE_TV || fullPage
    // Only the strip itself brings the strip back: with nobody focused (the moment after full screen shrinks
    // onto the guide) it must stay out, or it flashes in for the frames before a guide row takes focus.
    val guideStripHidden = guideSection &&
        railState != tv.own.owntv.features.shell.components.RailState.OPEN &&
        // Focus the menu gets by itself while full screen hands over to the guide is not the menu being used.
        (!railTrulyFocused || guideHandover || playerMode == PlayerMode.FULLSCREEN)
    // The guide does not jump when the strip steps aside or comes back: it slides, as TiviMate's does.
    // Keyed on the section: another page keeps its padding and must not slide in from the guide's offset.
    val guideStripRoom = androidx.compose.runtime.key(guideSection) {
        androidx.compose.animation.core.animateDpAsState(
            if (guideSection && !guideStripHidden) tv.own.owntv.features.shell.components.StageRailStripWidth else 0.mpx,
            tv.own.owntv.ui.theme.stageSlide(),
        )
    }
    val railPushTarget = tv.own.owntv.features.shell.components.railPushTarget(railState)
    val guideStripTarget = if (guideSection && !guideStripHidden) tv.own.owntv.features.shell.components.StageRailStripWidth else 0.mpx
    // The guide's corner picture moves with the page in the same frame (see GuidePreviewSpot.shift).
    val shiftDensity = androidx.compose.ui.platform.LocalDensity.current
    androidx.compose.runtime.SideEffect {
        tv.own.owntv.features.epg.GuidePreviewSpot.shellShift = {
            with(shiftDensity) { (railPush.value + guideStripRoom.value).toPx() } * if (contentLtr) 1f else -1f
        }
    }
    // The playlist chip reflects the active filter: "All playlists" when none is chosen (id <= 0), the
    // chosen playlist's name otherwise. With a single playlist there's nothing to switch, so just its name.
    val playlistLabel = when {
        playlists.size <= 1 -> sourceSummary ?: noSourceLabel
        activePlaylistId <= 0L -> stringResource(R.string.content_all_playlists)
        else -> playlists.firstOrNull { it.id == activePlaylistId }?.name ?: (sourceSummary ?: noSourceLabel)
    }
    val continueLastWatched = {
        continueTarget?.let { target ->
            when (target.kind) {
                tv.own.owntv.features.home.ContinueKind.LIVE -> playLiveById(target.channelId, emptyList())
                tv.own.owntv.features.home.ContinueKind.MOVIE -> playMovieById(target.movieId, target.positionMs)
                tv.own.owntv.features.home.ContinueKind.EPISODE -> playEpisodeById(target.seriesId, target.episodeId, target.positionMs)
            }
        }
        Unit
    }
    val openShortcutSection: (MainSection) -> Unit = { section ->
        // Out of full screen first, as with Back; the section change follows.
        if (playerMode == PlayerMode.FULLSCREEN) exitPlayer()
        if (section == MainSection.SEARCH) {
            searchVm.setQuery("")
            trendingSearchActive = false
            restoreTrendingSearchFocus = false
        }
        onSelectSection(section)
        // A shortcut may be fired by a control inside the destination being replaced (most notably
        // Settings). Once that screen leaves composition its focused node disappears, so hand focus
        // to the persistent sidebar after the new destination has rendered.
        scope.launch {
            withFrameNanos { }
            focusRail()
        }
    }
    val dispatchRemoteShortcut: (RemoteShortcutAction) -> Unit = { action ->
        when (action) {
            RemoteShortcutAction.OPEN_HOME -> openShortcutSection(MainSection.HOME)
            RemoteShortcutAction.OPEN_LIVE_TV -> openShortcutSection(MainSection.LIVE_TV)
            RemoteShortcutAction.OPEN_MOVIES -> openShortcutSection(MainSection.MOVIES)
            RemoteShortcutAction.OPEN_SERIES -> openShortcutSection(MainSection.SERIES)
            RemoteShortcutAction.OPEN_DOWNLOADS -> openShortcutSection(MainSection.DOWNLOADS)
            RemoteShortcutAction.OPEN_GUIDE -> openShortcutSection(MainSection.LIVE_TV)
            RemoteShortcutAction.OPEN_SEARCH -> openShortcutSection(MainSection.SEARCH)
            RemoteShortcutAction.OPEN_SETTINGS -> openShortcutSection(MainSection.SETTINGS)
            RemoteShortcutAction.OPEN_PROFILE_SWITCHER -> {
                if (playerMode == PlayerMode.FULLSCREEN) exitPlayer()
                onSwitchProfile()
            }
            RemoteShortcutAction.OPEN_PLAYLIST_SWITCHER -> {
                if (playerMode == PlayerMode.FULLSCREEN) exitPlayer()
                if (playlists.size > 1) showPlaylistPicker = true
            }
            RemoteShortcutAction.CONTINUE_LAST_WATCHED -> continueLastWatched()
            // The mini player and audio mode are gone: a stored binding does nothing.
            RemoteShortcutAction.FOCUS_NOW_PLAYING,
            RemoteShortcutAction.EXPAND_NOW_PLAYING,
            RemoteShortcutAction.ENTER_MINI_PLAYER,
            RemoteShortcutAction.ENTER_AUDIO_MODE,
            -> Unit
            RemoteShortcutAction.PLAY_PAUSE -> if (playerMode != PlayerMode.NONE) activeEngine.togglePlayPause()
            RemoteShortcutAction.RETURN_TO_LIVE -> if (playerMode != PlayerMode.NONE && zapSource == MainSection.LIVE_TV && timeshifted) liveVm.goToLive()
            RemoteShortcutAction.PREVIOUS_CHANNEL -> if (playerMode != PlayerMode.NONE && zapSource == MainSection.LIVE_TV) liveVm.previousChannel()
            RemoteShortcutAction.PAGE_TOWARD_FIRST,
            RemoteShortcutAction.PAGE_TOWARD_LAST,
            RemoteShortcutAction.JUMP_TO_FIRST,
            RemoteShortcutAction.JUMP_TO_LAST,
            RemoteShortcutAction.OPEN_SUBTITLE_CONTROLS,
            RemoteShortcutAction.OPEN_AUDIO_CONTROLS,
            RemoteShortcutAction.OPEN_ASPECT_CONTROLS,
            RemoteShortcutAction.TOGGLE_PLAYBACK_INFO,
            -> Unit
        }
    }
    val latestRemoteShortcutDispatch by rememberUpdatedState(dispatchRemoteShortcut)
    val remoteShortcutEnvironment = remember(remoteShortcutsEnabled, remoteShortcutBindings) {
        RemoteShortcutEnvironment(
            enabled = remoteShortcutsEnabled,
            bindings = remoteShortcutBindings,
            dispatch = { action -> latestRemoteShortcutDispatch(action) },
        )
    }

    // The screen the tour's frame report and the start log's per-area frames count against.
    LaunchedEffect(selectedSection, playerMode) {
        val area = selectedSection.name + ":" + playerMode.name
        tv.own.owntv.ui.FrameJankLog.screen = area
        tv.own.owntv.perf.StartLog.area = area
    }

    // The first picture of the process, for the start log (either engine).
    LaunchedEffect(Unit) {
        kotlinx.coroutines.flow.merge(
            liveVm.previewEngine.state.map { it == tv.own.owntv.player.LivePreviewEngine.State.PLAYING },
            player.isPlaying,
        ).first { it }
        tv.own.owntv.perf.StartLog.stampOnce("player-first-frame")
        tv.own.owntv.StartupGate.markFirstFrame()
    }
    // No player a second after the start mode decided against one (start page): nothing to wait for. Not counted
    // from the shell's first frame: the start mode needs a database read first and opens the player later.
    LaunchedEffect(Unit) {
        startModeDone.await()
        kotlinx.coroutines.delay(1_000)
        if (playerMode == PlayerMode.NONE) tv.own.owntv.StartupGate.markFirstFrame()
    }

    LaunchedEffect(sidebarFocus) {
        tv.own.owntv.perf.StartLog.stamp("shell-composed")
        withFrameNanos { }
        // Focus only: [focusRail] would stamp railKeyAt and open the menu with its names on every start.
        runCatching { sidebarFocus.requestFocus() }
    }

    LaunchedEffect(pendingDeepLink, activeProfileId) {
        val deepLink = pendingDeepLink ?: return@LaunchedEffect
        val pid = activeProfileId ?: return@LaunchedEffect
        if (pid < 0) return@LaunchedEffect
        when (deepLink) {
            LauncherDeepLink.OpenLiveSection -> {
                onSelectSection(MainSection.LIVE_TV)
                onDeepLinkConsumed()
            }
            else -> when (val launch = launcherIntegrationRepository.resolveLaunch(pid, deepLink)) {
                is LauncherLaunch.Movie -> {
                    onSelectSection(MainSection.MOVIES)
                    movieVm.play(launch.movie, launch.startPositionMs)
                    openFullscreen(MainSection.MOVIES)
                    onDeepLinkConsumed()
                }
                is LauncherLaunch.Episode -> {
                    onSelectSection(MainSection.SERIES)
                    seriesVm.playEpisodeQueue(launch.show, launch.queue, launch.episode, launch.startPositionMs)
                    openFullscreen(MainSection.SERIES)
                    onDeepLinkConsumed()
                }
                is LauncherLaunch.Live -> {
                    onSelectSection(MainSection.LIVE_TV)
                    // A launcher tile has no browse rail, so arm the channel's provider context before
                    // opening the player. A bare ensurePlaying() leaves CH+/CH- with no list to step through.
                    liveVm.watchFullscreen(launch.channel, emptyList())
                    openFullscreen(MainSection.LIVE_TV)
                    onDeepLinkConsumed()
                }
                is LauncherLaunch.Series -> {
                    onSelectSection(MainSection.SERIES)
                    seriesVm.openSeries(launch.show)
                    onDeepLinkConsumed()
                }
                null -> {
                    onDeepLinkConsumed()
                }
            }
        }
    }

    // A "Für dich" match tile on the launcher: its match page in Sports, where a channel is picked. Back
    // from a channel played there returns to the page, as for any match opened in Sports.
    // Collected for as long as a profile is active, so it calls the shell's current closePlayer and section.
    val closeForMatch by rememberUpdatedState { closePlayer(toRail = false) }
    val selectForMatch by rememberUpdatedState(onSelectSection)
    val profileReady = activeProfileId?.let { it >= 0 } == true
    LaunchedEffect(profileReady) {
        if (!profileReady) return@LaunchedEffect
        sportsMatchRequests.launcherMatch.filterNotNull().collect { key ->
            val match = sportsMatchRequests.matchByKey(key)
            if (playerMode != PlayerMode.NONE) closeForMatch()
            sportsMatchRequests.returnToMatch = null
            if (match != null) sportsMatchRequests.pendingMatch.value = match to null
            selectForMatch(MainSection.SPORTS)
            sportsMatchRequests.launcherMatch.compareAndSet(key, null)
        }
    }

    // Stop a leftover live preview when you leave the Live section or the Guide, which previews through it
    // too (but never while fullscreen/mini plays).
    // The preview runs on the ExoPlayer live engine, not mpv: stopping only mpv here (as when the preview
    // was mpv) left it decoding and holding a provider connection after a shortcut or deep link out.
    LaunchedEffect(selectedSection, playerMode) {
        // Back from a channel played out of a match page: the page reopens next (SportsScreen) and carries the
        // channel on in its preview, so it must not be stopped, and forgotten, here first.
        val matchPageNext = selectedSection == MainSection.SPORTS &&
            (sportsMatchRequests.returnToMatch != null || sportsMatchRequests.returnedFromPlayer)
        if (selectedSection != MainSection.LIVE_TV && playerMode == PlayerMode.NONE && !matchPageNext) liveVm.stopPreview()
        if (selectedSection != MainSection.HOME || playerMode != PlayerMode.NONE) homeVm.stopPreview()
    }

    LaunchedEffect(selectedSection, playerMode, activeProfileId, activePlaylistId) {
        if (selectedSection == MainSection.HOME && playerMode == PlayerMode.NONE && (activeProfileId?.let { it >= 0 } == true)) {
            // Home is only the section before the start mode has moved to Live TV: do not load its rails first.
            startModeDone.await()
            homeVm.refresh(activeProfileId)
        }
    }

    // The section a Search result opened ("Go to"), so the first Back there returns to Search. Leaving
    // that section any other way forgets it. The series page closes itself on Back (its own handler),
    // so for a series the return follows the show closing.
    fun returnToSearch() {
        searchReturn = null
        onSelectSection(MainSection.SEARCH)
    }
    LaunchedEffect(selectedSection) {
        if (selectedSection != MainSection.SEARCH && selectedSection != searchReturn) searchReturn = null
    }
    val openedSeries by seriesVm.openedSeries.collectAsStateWithLifecycle()
    LaunchedEffect(openedSeries == null) {
        if (openedSeries == null && searchReturn == MainSection.SERIES && selectedSection == MainSection.SERIES) returnToSearch()
    }

    BackHandler {
        when {
            playerMode == PlayerMode.FULLSCREEN -> exitBack()
            showAvatarPicker -> showAvatarPicker = false
            showPlaylistPicker -> showPlaylistPicker = false
            showExit -> showExit = false
            // Settings is reached through More now, so Back out of its root goes back there — one
            // level out, not two. `SettingsScreen` passes its own `onBack` for the same purpose, but
            // its root handler does not always win against this one, which left Back looking dead on
            // the first press: the fallback below simply moved focus to the rail and nothing else
            // happened. Handled here as well so the answer is the same whichever handler fires; a
            // sub-screen of Settings still wins, because its handler is composed deeper than this.
            selectedSection == MainSection.SETTINGS -> { parkForScreenChange(); restoreFocus = true; onSelectSection(MainSection.MORE) }
            // Reached from a Search result: Back returns to Search once, its query, tab and row kept.
            searchReturn == selectedSection && focusedLayer != ShellLayer.SIDEBAR -> returnToSearch()
            focusedLayer == ShellLayer.SIDEBAR && railTrulyFocused -> showExit = true
            else -> focusRail()
        }
    }

    // With Glass on, the shell's own base paints must be transparent so the background (rendered in
    // MainActivity behind this shell) shows through the gaps between and around panels. Solid otherwise.
    val shellBase = if (LocalGlass.current.enabled) Color.Transparent else tv.own.owntv.ui.theme.LocalStagePalette.current.page

    // Long-press Back (the second way in). Deliberately timed from the FIRST KeyDown rather than counted from
    // key repeats: several TVs re-fire KeyDown while a key is held, so a repeat-counting version fires
    // the long-press the instant Back is touched. Short Back is never consumed, so every existing Back
    // handler — HUD hide, search clear, dialog dismiss, direct-tune cancel — behaves exactly as before.
    val backDispatcher = androidx.activity.compose.LocalOnBackPressedDispatcherOwner.current?.onBackPressedDispatcher
    var backHoldJob by remember { mutableStateOf<kotlinx.coroutines.Job?>(null) }
    var backLongFired by remember { mutableStateOf(false) }
    var shortcutKeyCode by remember { mutableStateOf(android.view.KeyEvent.KEYCODE_UNKNOWN) }
    var shortcutLongFired by remember { mutableStateOf(false) }
    var shortcutHoldJob by remember { mutableStateOf<kotlinx.coroutines.Job?>(null) }
    // Holding Back anywhere in the menus goes straight back to the channel watched last, full screen:
    // the one in the preview, else the last one this session, else the newest in the history.
    var lastLiveChannel by remember { mutableStateOf<tv.own.owntv.core.database.entity.ChannelEntity?>(null) }
    LaunchedEffect(previewChannel) { previewChannel?.let { lastLiveChannel = it } }
    // Back in Live TV after a visit elsewhere: the corner shows the channel watched last again, as it was,
    // instead of staying black until OK (leaving stops the preview). Nothing else moves: not the focus, not
    // the channel, so this is the previous state coming back, not zapping.
    LaunchedEffect(selectedSection) {
        if (selectedSection == MainSection.LIVE_TV && playerMode == PlayerMode.NONE) {
            lastLiveChannel?.let { liveVm.restorePreview(it) }
        }
    }
    // A live channel to full screen. The channel already playing in the guide's corner grows from there into
    // full screen, the same picture, no new stream (Jonathan, 27 Oct 2026); anything else just opens. The guide's
    // OK and a long Back both come through here, so both glide.
    val openLiveFullscreen: (tv.own.owntv.core.database.entity.ChannelEntity, List<ChannelEntity>) -> Unit = open@{ ch, zap ->
        if (guideHandover) return@open
        val grow = playerMode == PlayerMode.NONE && previewChannel?.id == ch.id &&
            liveVm.previewEngine.state.value == tv.own.owntv.player.LivePreviewEngine.State.PLAYING &&
            tv.own.owntv.features.epg.GuidePreviewSpot.rect != null && tv.own.owntv.features.epg.GuidePreviewSpot.video
        restoreFocus = false
        liveVm.watchFromGuide(ch, zap)
        zapSource = MainSection.LIVE_TV
        homeVm.stopPreview()
        // Live TV set to play externally → the channel went to another app;
        // don't mount the fullscreen player over it.
        if (!liveVm.externalPlayerOn.value) {
            if (grow && liveVm.liveOnExo.value && multiview == null) {
                guideHandover = true
                handoverGrow = true
                scope.launch {
                    handoverShrink.snapTo(0f)
                    handoverShrink.animateTo(1f, handoverSpec)
                    guideHandover = false
                    handoverGrow = false
                    handoverShrink.snapTo(0f)
                }
            }
            playerMode = PlayerMode.FULLSCREEN
        }
    }
    val backToChannelArmed = playerMode == PlayerMode.NONE && !showExit && !showAvatarPicker && !showPlaylistPicker
    val backToChannel: () -> Unit = {
        scope.launch {
            val ch = previewChannel ?: lastLiveChannel
                ?: runCatching { liveVm.historyChannels(1).firstOrNull()?.channel }.getOrNull()
            if (ch != null && playerMode == PlayerMode.NONE) {
                // Back out of it lands in the guide on this channel, wherever Back was held (Jonathan, 27 Oct
                // 2026): the guide is put behind the picture now, so it is there when the picture glides back.
                if (selectedSection != MainSection.LIVE_TV && MainSection.LIVE_TV in visibleSections) onSelectSection(MainSection.LIVE_TV)
                openLiveFullscreen(ch, emptyList())
            }
        }
    }
    CompositionLocalProvider(
        LocalRemoteShortcuts provides remoteShortcutEnvironment,
        tv.own.owntv.features.shell.components.LocalParkFocus provides parkForScreenChange,
        tv.own.owntv.features.shell.components.LocalClockWeather provides (weatherInfo to weatherFahrenheit),
    ) {
    Box(
        modifier = modifier.fillMaxSize().background(shellBase)
            .onPreviewKeyEvent { e ->
                // The picture is gliding between full screen and the guide's corner: the guide behind it is
                // not on screen, so a key now would move or open something nobody can see. Volume is not a
                // navigation key: it goes through to the player.
                if (guideHandover) return@onPreviewKeyEvent e.key != Key.VolumeUp && e.key != Key.VolumeDown && e.key != Key.VolumeMute
                if (e.type == KeyEventType.KeyDown) {
                    if (e.key == Key.DirectionLeft || e.key == Key.DirectionRight) railKeyAt = android.os.SystemClock.uptimeMillis()
                    // A key pressed on the rail is the user using it, however focus got there.
                    if (railHasFocus && playerMode == PlayerMode.NONE) railFocused = true
                }
                if (e.key != Key.Back) return@onPreviewKeyEvent false
                when (e.type) {
                    KeyEventType.KeyDown -> {
                        if (backToChannelArmed && backHoldJob == null) {
                            backLongFired = false
                            backHoldJob = scope.launch {
                                kotlinx.coroutines.delay(600)
                                backLongFired = true
                                backToChannel()
                            }
                        }
                        false
                    }
                    KeyEventType.KeyUp -> {
                        backHoldJob?.cancel()
                        backHoldJob = null
                        val fired = backLongFired
                        backLongFired = false
                        fired // consume ONLY when the long-press already acted, so Back isn't run twice
                    }
                    else -> false
                }
            }
            .onKeyEvent { e ->
                val shortcutKey = e.nativeKeyEvent.keyCode
                val shellBindings = remoteShortcutBindings.filter {
                    it.keyCode == shortcutKey &&
                        it.action != RemoteShortcutAction.PAGE_TOWARD_FIRST &&
                        it.action != RemoteShortcutAction.PAGE_TOWARD_LAST &&
                        it.action != RemoteShortcutAction.JUMP_TO_FIRST &&
                        it.action != RemoteShortcutAction.JUMP_TO_LAST
                }
                if (remoteShortcutsEnabled && shellBindings.isNotEmpty()) {
                    when (e.type) {
                        KeyEventType.KeyDown -> {
                            if (shortcutKeyCode != shortcutKey) {
                                shortcutHoldJob?.cancel()
                                shortcutKeyCode = shortcutKey
                                shortcutLongFired = false
                                shellBindings.firstOrNull { it.press == tv.own.owntv.core.settings.RemoteShortcutPress.LONG }
                                    ?.let { binding ->
                                        shortcutHoldJob = scope.launch {
                                            kotlinx.coroutines.delay(600)
                                            if (shortcutKeyCode == shortcutKey) {
                                                shortcutLongFired = true
                                                dispatchRemoteShortcut(binding.action)
                                            }
                                        }
                                    }
                            }
                            return@onKeyEvent true
                        }
                        KeyEventType.KeyUp -> if (shortcutKeyCode == shortcutKey) {
                            shortcutHoldJob?.cancel()
                            shortcutHoldJob = null
                            if (!shortcutLongFired) {
                                shellBindings.firstOrNull { it.press == tv.own.owntv.core.settings.RemoteShortcutPress.SHORT }
                                    ?.let { dispatchRemoteShortcut(it.action) }
                            }
                            shortcutKeyCode = android.view.KeyEvent.KEYCODE_UNKNOWN
                            shortcutLongFired = false
                            return@onKeyEvent true
                        }
                    }
                }
                // Back that bubbled all the way up here is a Back nothing nearer wanted as a key, so it
                // is ours to turn into a back-press. Compose would otherwise map it to
                // FocusDirection.Exit and spend it moving focus one level out of whatever focus target
                // holds it — silently, because the highlight usually does not visibly move. That
                // consumed the KeyDown before Activity.onKeyDown could startTracking(), so the KeyUp
                // was discarded as untracked and NO BackHandler ran: Back out of Settings looked dead
                // until a second press, by which time the exit had nowhere left to go. Running the
                // dispatcher here keeps the answer the same on the first press, everywhere.
                // Deeper key handlers (a text field's Back-to-close, the direct-tune cancel) still win,
                // because this is a bubbling handler and they have already declined by the time it runs.
                if (e.key == Key.Back) {
                    if (e.type == KeyEventType.KeyUp && !e.nativeKeyEvent.isCanceled) backDispatcher?.onBackPressed()
                    return@onKeyEvent backDispatcher != null
                }
                false
            },
    ) {
      // Browse UI — hidden while the player is fullscreen.
      // A live channel opened from the guide keeps the guide laid out behind it, unseen, so the picture can
      // glide straight back onto its corner (exitToList). Not drawn meanwhile: the picture covers it all.
      val guideBehind = playerMode == PlayerMode.FULLSCREEN && multiview == null && liveOnExo &&
          zapSource == MainSection.LIVE_TV && selectedSection == MainSection.LIVE_TV
      // The same for a match page in Sports: it stays behind the picture, so the picture can glide back onto its
      // corner and the page is not built again.
      val sportsBehind = playerMode == PlayerMode.FULLSCREEN && multiview == null && liveOnExo &&
          zapSource == MainSection.LIVE_TV && selectedSection == MainSection.SPORTS
      if (playerMode != PlayerMode.FULLSCREEN || guideHandover || guideBehind || sportsBehind) {
        val browseHidden = playerMode == PlayerMode.FULLSCREEN && !guideHandover
        Column(
            modifier = Modifier.fillMaxSize()
                .graphicsLayer { alpha = if (browseHidden) 0f else 1f }
                // One group with a restorer: leaving for full screen and coming back lands on the
                // exact control that was focused, without every screen having to remember its own.
                .focusRestorer()
                .focusGroup(),
        ) {
          if (isOffline) OfflineBanner()
          val selectFromNav: (MainSection) -> Unit = { section ->
              if (trendingSearchActive || section == MainSection.SEARCH) {
                  searchVm.setQuery("")
                  trendingSearchActive = false
                  restoreTrendingSearchFocus = false
              }
              onSelectSection(section)
          }
          Box(Modifier.weight(1f).fillMaxWidth()) {
          Row(modifier = Modifier.fillMaxSize()) {
            Column(
                modifier = Modifier
                    .weight(1f)
                    .fillMaxSize()
                    // Transparent while Glass is on, so the picture or the Stage page shows through the gaps
                    // between the panels; the page colour otherwise.
                    .background(shellBase)
                    // The Stage page, across the whole width — under a docked rail's reserve too.
                    // Over a picture it draws only the Accent light; the picture shows through.
                    .stageBackground(tv.own.owntv.ui.theme.stageAccent.accent),
            ) {
                // How the guide (Live TV) starts a channel or an archive programme: through LiveViewModel,
                // the one live path.
                val guidePlayChannel: (ChannelEntity, List<ChannelEntity>) -> Unit = { ch, _ -> openLiveFullscreen(ch, emptyList()) }
                val guidePlayCatchup: (ChannelEntity, tv.own.owntv.core.database.entity.EpgProgrammeEntity) -> Unit = { ch, prog ->
                    restoreFocus = false
                    liveVm.playCatchupProgramme(ch, prog)
                    zapSource = MainSection.LIVE_TV
                    homeVm.stopPreview()
                    playerMode = PlayerMode.FULLSCREEN
                }
                Box(
                    modifier = Modifier.weight(1f).fillMaxWidth()
                        .then(
                            // Every screen starts clear of the menu's icon strip (or beside a docked rail), so
                            // the strip never lies over text. Only the open menu covers the content.
                            Modifier
                                .padding(
                                    // The guide takes the strip's room while it has focus. It slides over by the
                                    // strip's width instead of padding: a sliding padding would lay the whole grid
                                    // out again on every frame.
                                    start = if (guideSection) 0.mpx else tv.own.owntv.features.shell.components.StageRailStripWidth,
                                )
                                // The open menu pushes the page aside instead of covering its left edge.
                                .graphicsLayer {
                                    translationX = (railPush.value + guideStripRoom.value).toPx() * if (contentLtr) 1f else -1f
                                    // The guide slides as one picture (its corner video is drawn by the shell, not in here).
                                    compositingStrategy = tv.own.owntv.ui.theme.slideCompositing(
                                        guideSection && (railPush.value != railPushTarget || guideStripRoom.value != guideStripTarget),
                                    )
                                }
                                // ◀ with nothing further left in the content goes back to the rail, however the
                                // content was entered. The D-pad search alone missed it whenever the open rail
                                // overlapped the content's left edge, which left Back as the only way out.
                                .onKeyEvent { e ->
                                    val back = if (contentLtr) Key.DirectionLeft else Key.DirectionRight
                                    if (e.key != back || e.type != KeyEventType.KeyDown) return@onKeyEvent false
                                    if (!contentFocusManager.moveFocus(if (contentLtr) androidx.compose.ui.focus.FocusDirection.Left else androidx.compose.ui.focus.FocusDirection.Right)) {
                                        focusRail()
                                    }
                                    true
                                }
                                .focusRequester(contentAreaFocus)
                                .focusRestorer()
                                .focusGroup(),
                        ),
                ) {
                    when {
                        // More — the hub the rail's last item opens. Settings is a row in it.
                        selectedSection == MainSection.MORE -> tv.own.owntv.features.more.MoreScreen(
                            profileName = profileName.ifBlank { stringResource(R.string.common_own_tv_user) },
                            playlistLabel = playlistLabel,
                            avatarId = avatarId,
                            avatarPath = avatarPath,
                            onSwitchProfile = onSwitchProfile,
                            onPickAvatar = { showAvatarPicker = true },
                            onOpenSettings = { group, search ->
                                settingsStart = tv.own.owntv.features.shell.components.SettingsStart(group, search)
                                parkForScreenChange()
                                onSelectSection(MainSection.SETTINGS)
                            },
                            // The same paths Home uses, so the engine ladder, resume and Continue all apply.
                            onPlayChannel = { id -> playLiveById(id, emptyList()) },
                            onPlayMovie = playMovieById,
                            onPlayEpisode = playEpisodeById,
                            onOpenSeries = { id -> seriesVm.openSeriesById(id); restoreFocus = true; onSelectSection(MainSection.SERIES) },
                            onChildFocused = { focusedLayer = ShellLayer.CONTENT },
                            onEntryHook = { moreEntry = it },
                            restoreFocus = restoreFocus,
                            onRestored = { restoreFocus = false },
                            modifier = Modifier
                                .fillMaxSize()
                                .onFocusChanged { if (it.hasFocus) focusedLayer = ShellLayer.CONTENT }
                                .focusGroup(),
                        )

                        selectedSection == MainSection.SETTINGS -> SettingsScreen(
                            themeMode = themeMode,
                            uiZoomPercent = uiZoomPercent,
                            onSetZoom = onSetZoom,
                            fontCustomization = fontCustomization,
                            onSetFontCustomization = onSetFontCustomization,
                            onOpenPlaylist = {},
                            // Settings is reached through More now, so Back out of its root goes
                            // back there rather than to the rail — one level out, not two.
                            onBack = { parkForScreenChange(); restoreFocus = true; onSelectSection(MainSection.MORE) },
                            openEpgAdd = openEpgAdd,
                            onEpgAddConsumed = { openEpgAdd = false },
                            start = settingsStart,
                            onStartConsumed = { settingsStart = null },
                            modifier = Modifier
                                .fillMaxSize()
                                .onFocusChanged { if (it.hasFocus) focusedLayer = ShellLayer.CONTENT }
                                .focusGroup(),
                        )

                        selectedSection == MainSection.HOME -> HomeScreen(
                            vm = homeVm,
                            // Skip the fullscreen player when the global external-player toggle is on
                            // (mounting it spins up mpv even though playback went to the external app).
                            onPlayMovie = playMovieById,
                            onPlayEpisode = playEpisodeById,
                            onPlayChannel = playLiveById,
                            onActivateTrending = { selected, onUnavailable ->
                                scope.launch {
                                    when (val current = homeVm.revalidateTrendingItem(selected)) {
                                        is TrendingHomeItem.Movie -> {
                                            val played = movieVm.playByIdAsync(current.movie.id)
                                            if (!played) onUnavailable()
                                            else if (!movieVm.externalPlayerOn.value) openFullscreen(MainSection.MOVIES)
                                        }
                                        is TrendingHomeItem.Series -> {
                                            seriesVm.openSeries(current.series)
                                            restoreFocus = true
                                            onSelectSection(MainSection.SERIES)
                                        }
                                        null -> onUnavailable()
                                    }
                                }
                            },
                            onOpenMovie = { movie ->
                                restoreFocus = false
                                movieVm.revealMovie(movie)
                                onSelectSection(MainSection.MOVIES)
                            },
                            onOpenSeries = { series ->
                                seriesVm.openSeries(series)
                                restoreFocus = true
                                onSelectSection(MainSection.SERIES)
                            },
                            onOpenTrendingSearch = { query ->
                                searchVm.setQuery(query)
                                trendingSearchActive = true
                                restoreTrendingSearchFocus = false
                                onSelectSection(MainSection.SEARCH)
                            },
                            onChildFocused = { focusedLayer = ShellLayer.CONTENT },
                            restoreFocus = restoreFocus,
                            restoreTrendingSearchFocus = restoreTrendingSearchFocus,
                            onRestored = {
                                restoreFocus = false
                                restoreTrendingSearchFocus = false
                            },
                            previewEnabled = playerMode == PlayerMode.NONE,
                            firstRowFocusRequester = homeFirstRowFocus,
                            // Beside the resting capsule; a docked rail already reserves its own width.
                            onEntryHook = { homeEntry = it },
                            onOpenMatch = { m ->
                                sportsMatchRequests.pendingMatch.value = m to null
                                onSelectSection(MainSection.SPORTS)
                            },
                            contentStart = 40.mpx,
                            modifier = Modifier.fillMaxSize(),
                        )

                        selectedSection == MainSection.SEARCH -> SearchScreen(
                            vm = searchVm,
                            liveVm = liveVm,
                            previewEnabled = playerMode == PlayerMode.NONE,
                            // OK on a result goes to it and Back comes back here once
                            // (searchReturn). The series page is the same SeriesViewModel the Series
                            // section shows; a channel or movie is revealed in its own category.
                            onOpenSeries = { series ->
                                seriesVm.openSeries(series)
                                searchReturn = MainSection.SERIES
                                onSelectSection(MainSection.SERIES)
                            },
                            onGoToChannel = { ch ->
                                restoreFocus = false
                                liveVm.revealChannel(ch)
                                searchReturn = MainSection.LIVE_TV
                                onSelectSection(MainSection.LIVE_TV)
                            },
                            onGoToMovie = { movie ->
                                restoreFocus = false
                                movieVm.revealMovie(movie)
                                searchReturn = MainSection.MOVIES
                                onSelectSection(MainSection.MOVIES)
                            },
                            onChildFocused = { focusedLayer = ShellLayer.CONTENT },
                            returnToHomeOnBack = trendingSearchActive,
                            onReturnToHome = {
                                trendingSearchActive = false
                                restoreTrendingSearchFocus = true
                                restoreFocus = false
                                onSelectSection(MainSection.HOME)
                            },
                            modifier = Modifier.fillMaxSize(),
                        )

                        // Live TV is always the guide (26 Oct 2026): no List view and no layout variants.
                        selectedSection == MainSection.LIVE_TV -> EpgScreen(
                            onPlayChannel = guidePlayChannel,
                            onPlayCatchup = guidePlayCatchup,
                            onOpenSearch = { onSelectSection(MainSection.SEARCH) },
                            onOpenSettings = { parkForScreenChange(); onSelectSection(MainSection.SETTINGS) },
                            onAddEpg = { openEpgAdd = true; parkForScreenChange(); onSelectSection(MainSection.SETTINGS) },
                            previewEnabled = playerMode == PlayerMode.NONE,
                            behindFullscreen = guideBehind,
                            logosPaused = guideBehind && !guideHandover,
                            liveMode = true,
                            onEntryHook = { guideEntry = it },
                            restoreFocus = restoreFocus,
                            onRestored = { restoreFocus = false },
                            modifier = Modifier
                                .fillMaxSize()
                                .onFocusChanged { if (it.hasFocus) focusedLayer = ShellLayer.CONTENT }
                                .focusGroup(),
                        )

                        selectedSection == MainSection.MOVIES -> MoviesScreen(
                            onFullscreen = { openFullscreen() },
                            onChildFocused = { focusedLayer = ShellLayer.CONTENT },
                            restoreFocus = restoreFocus,
                            onRestored = { restoreFocus = false },
                            onEntryHook = { vodEntry = it },
                            modifier = Modifier.fillMaxSize(),
                        )

                        selectedSection == MainSection.SERIES -> SeriesScreen(
                            onFullscreen = { openFullscreen() },
                            onChildFocused = { focusedLayer = ShellLayer.CONTENT },
                            restoreFocus = restoreFocus,
                            onRestored = { restoreFocus = false },
                            onEntryHook = { vodEntry = it },
                            modifier = Modifier.fillMaxSize(),
                        )

                        selectedSection == MainSection.DOWNLOADS -> DownloadsScreen(
                            onFullscreen = { openFullscreen() },
                            onChildFocused = { focusedLayer = ShellLayer.CONTENT },
                            onOpenGuide = { onSelectSection(MainSection.LIVE_TV) },
                            restoreFocus = restoreFocus,
                            onRestored = { restoreFocus = false },
                            onEntryHook = { downloadsEntry = it },
                            contentStart = 40.mpx,
                            modifier = Modifier.fillMaxSize(),
                        )

                        selectedSection == MainSection.SPORTS -> tv.own.owntv.features.sports.SportsScreen(
                            // Through the guide's way in: the channel in the page's corner grows into full screen.
                            onPlayChannel = { ch, zap -> openLiveFullscreen(ch, zap) },
                            onAddPlaylist = { onSelectSection(MainSection.SETTINGS) },
                            onChildFocused = { focusedLayer = ShellLayer.CONTENT },
                            onEntryHook = { sportsEntry = it },
                            active = playerMode == PlayerMode.NONE,
                            contentStart = 40.mpx,
                            modifier = Modifier.fillMaxSize(),
                        )

                    }
                }
            }
          }
            // The guide shows no clock and no pills at all, as in TiviMate (Jonathan, 27 Oct 2026: "unnötig").
            // A match page neither: its Watch live column needs the room.
            val clusterAside = selectedSection == MainSection.LIVE_TV || fullPage
            val clusterAlpha by androidx.compose.animation.core.animateFloatAsState(if (clusterAside) 0f else 1f, tv.own.owntv.ui.theme.stageSlide())
            tv.own.owntv.features.shell.components.StageStatusCluster(
                continuePill = continueTarget?.let { target ->
                    val live = target.kind == tv.own.owntv.features.home.ContinueKind.LIVE
                    tv.own.owntv.features.shell.components.ContinuePill(
                        icon = if (live) OwnTVIcon.LIVE_TV else OwnTVIcon.PLAY,
                        iconFilled = !live,
                        action = stringResource(
                            when (target.action) {
                                tv.own.owntv.features.home.ContinueAction.RESUME -> R.string.content_action_resume
                                tv.own.owntv.features.home.ContinueAction.PLAY -> R.string.content_action_play
                                tv.own.owntv.features.home.ContinueAction.NEXT_UP -> R.string.content_action_next_episode
                                tv.own.owntv.features.home.ContinueAction.LAST_CHANNEL -> R.string.content_action_last_channel
                            },
                        ),
                        title = target.name,
                        onClick = continueLastWatched,
                    )
                },
                playlistLabel = playlistLabel,
                playlistInteractive = playlists.size > 1,
                onPlaylistClick = { showPlaylistPicker = true },
                // Out of reach from deep in a list: from the rail or on Home only.
                pillsFocusable = !clusterAside && (focusedLayer == ShellLayer.SIDEBAR || clusterFocused || selectedSection == MainSection.HOME),
                weatherInfo = weatherInfo,
                weatherFahrenheit = weatherFahrenheit,
                clockColors = clockColors,
                playlistDownFocusRequester = homeFirstRowFocus.takeIf { selectedSection == MainSection.HOME },
                onPlaylistPillBounds = { playlistPillBounds = it },
                modifier = Modifier.align(Alignment.TopEnd).graphicsLayer { alpha = clusterAlpha }.onFocusChanged { clusterFocused = it.hasFocus }.focusGroup(),
            )
            tv.own.owntv.features.shell.components.StageRail(
                state = railState,
                selected = selectedSection,
                visibleSections = visibleSections,
                onSelect = selectFromNav,
                profileName = profileName.ifBlank { stringResource(R.string.common_own_tv_user) },
                profileLine = stringResource(R.string.shell_rail_profile_line, playlistLabel),
                onSwitchProfile = onSwitchProfile,
                onPickAvatar = { showAvatarPicker = true },
                avatarId = avatarId,
                avatarPath = avatarPath,
                selectedItemFocusRequester = sidebarFocus,
                contentFocusRequester = contentAreaFocus,
                enterContent = when (selectedSection) {
                    MainSection.HOME -> homeEntry
                    MainSection.LIVE_TV -> guideEntry
                    MainSection.MOVIES, MainSection.SERIES -> vodEntry
                    MainSection.DOWNLOADS -> downloadsEntry
                    MainSection.MORE -> moreEntry
                    MainSection.SPORTS -> sportsEntry
                    else -> null
                },
                onFocused = { focusedLayer = ShellLayer.SIDEBAR },
                itemsFocusable = { playerMode == PlayerMode.NONE && !guideHandover },
                // In the guide the strip steps aside by sliding off the edge together with the page, not by fading:
                // a fade drew the whole screen once more offscreen on every frame, nested with the page's own
                // layer, which the Nokia 8010 could not keep up with between the categories and the menu
                // (28 Oct 2026). The open menu then comes in glued to the page's edge.
                modifier = Modifier.graphicsLayer {
                    if (guideSection) {
                        translationX = (guideStripRoom.value - tv.own.owntv.features.shell.components.StageRailStripWidth).toPx() *
                            if (contentLtr) 1f else -1f
                    }
                }.onFocusChanged { railTrulyFocused = it.hasFocus },
            )
          }
        }
        // The solid-mode wizard aura is deliberately drawn after the opaque browse surfaces so it
        // remains visible, exactly like the approved concept. It never intercepts input; fullscreen
        // video and glass mode skip it entirely. Global Animations Off also freezes the slow pulse.
        SolidAmbientBackdrop(
            glowEnabled = ambientGlowEnabled,
            pulseEnabled = ambientGlowPulse && shellAnimationLevel != tv.own.owntv.core.theme.AnimationLevel.OFF,
            modifier = Modifier.fillMaxSize(),
        )
      }

      // Unobtrusive background-sync pill (bottom middle): visible while any catalog sync runs —
      // backgrounded first import, remainder worker, auto refresh — but never over fullscreen video.
      if (playerMode != PlayerMode.FULLSCREEN) {
          tv.own.owntv.features.shell.components.SyncStatusPill(modifier = Modifier.align(Alignment.BottomCenter))
      }

      // Player surface — hoisted so it persists between the guide's corner and full screen (same call site =
      // the SurfaceView isn't recreated, so playback never blips).
      // Multiview draws over everything, including the player it grew out of, and owns its own
      // engines. The player surface below is not composed while it is up: its stream was stopped when
      // the grid opened, so there is nothing behind the tiles to keep alive.
      multiview?.let { grid ->
          tv.own.owntv.features.multiview.MultiviewScreen(
              state = grid,
              // Categories first, then that category's channels. The player's overlay starts at the
              // channels because it already has one playing and its category is the obvious place to
              // look; an empty tile has no such context, and a flat list of every channel across
              // every playlist is tens of thousands of rows to scroll.
              onPickChannel = { tile -> multiviewPickFor = tile; liveVm.showCategories() },
              onFullscreen = { channel ->
                  grid.releaseAll()
                  multiview = null
                  multiviewPickFor = null
                  liveVm.ensurePlaying(channel)
                  playerMode = PlayerMode.FULLSCREEN
              },
              // Leaving the grid leaves *playback*, by design.
              //
              // It used to promote the focused tile to full screen, which is what the plan asked for.
              // In use that is wrong twice over: a grid of four is put away by someone who has
              // finished watching, and being handed one of the four full screen means a stream is
              // still running and still costing a connection when the user believes they closed
              // everything. Back now lands on the Live TV list, with nothing playing.
              onExit = {
                  grid.releaseAll()
                  multiview = null
                  multiviewPickFor = null
                  exitPlayer()
              },
              modifier = Modifier.fillMaxSize(),
          )
          // Filling a tile reuses the very list the Live screen and the in-player overlay use, so the
          // categories, ordering and hidden channels can never disagree between them.
          multiviewPickFor?.let { tile ->
              if (showCategoryBrowser) {
                  // Step one, and where the picker always starts: every Live TV category across every
                  // playlist, so a tile can be filled from a playlist the user was not browsing —
                  // which is the whole point of the grid. Back here leaves the picker entirely.
                  tv.own.owntv.features.shell.components.CategoryBrowserOverlay(
                      railItems = browserRailItems,
                      railCounts = browserRailCounts,
                      currentKey = grid.tiles.getOrNull(tile)?.channel?.categoryId?.let { tv.own.owntv.core.live.LiveKey.Folder(it) },
                      onSelect = { key -> liveVm.loadChannelsForCategory(key) },
                      onDismiss = { liveVm.hideCategoryBrowser(); multiviewPickFor = null },
                      modifier = Modifier.fillMaxSize(),
                  )
              } else if (zapChannels.isNotEmpty()) {
                  tv.own.owntv.features.shell.components.ChannelListOverlay(
                      channels = zapChannels,
                      currentId = grid.tiles.getOrNull(tile)?.channel?.id,
                      title = zapOverlayTitle,
                      showNumbers = directTuneEnabled,
                      nowProgrammes = overlayProgrammes,
                      favoriteIds = overlayFavoriteIds,
                      playlistMarks = overlayPlaylistMarks,
                      alignEnd = true,
                      // Step two: that category's channels. Back goes up to the categories rather than
                      // out, so a wrong turn costs one press instead of starting again.
                      onOpenCategories = { liveVm.showCategories() },
                      onSelect = { grid.fill(tile, it); multiviewPickFor = null },
                      onDismiss = { liveVm.showCategories() },
                      modifier = Modifier.fillMaxSize(),
                  )
              } else {
                  // A category that turns out to be empty must not leave the picker with nothing
                  // drawn: Back would fall past it to the grid's own handler and close Multiview
                  // altogether. Go back to the categories instead, which is where the user was.
                  LaunchedEffect(Unit) { liveVm.showCategories() }
              }
          }
      }

      // The guide's corner picture is drawn here as well, by the same view as full screen: between the corner
      // and the whole screen the stream never changes surface, so it plays straight on instead of stalling
      // and reloading (Jonathan, 27 Oct 2026: "in TiviMate spielt der Sender einfach weiter"). The guide says
      // where (GuidePreviewSpot.rect) and whether (GuidePreviewSpot.video).
      val inCorner = playerMode == PlayerMode.NONE && multiview == null &&
          tv.own.owntv.features.epg.GuidePreviewSpot.video && tv.own.owntv.features.epg.GuidePreviewSpot.rect != null
      // ExoPlayer's picture is on screen: a promoted live channel, the guide's corner, or the move between them.
      val exoShown = liveOnExo || inCorner || guideHandover
      if (multiview == null && (playerMode == PlayerMode.FULLSCREEN || inCorner || guideHandover)) {
        ShellVideoLayer(
            playerMode = playerMode,
            inCorner = inCorner,
            guideHandover = guideHandover,
            handoverGrow = handoverGrow,
            handoverShrink = handoverShrink,
            exoShown = exoShown,
            liveVm = liveVm,
            player = player,
            autoFrameRate = autoFrameRate,
            afrMatchResolution = afrMatchResolution,
            afrPauseSecs = afrPauseSecs,
        ) { isFull ->
            if (isFull && !guideHandover) {
                // Engine state still distinguishes the live edge from catch-up/archive playback for
                // controls such as direct number tuning. It must not gate the physical CH keys below.
                val isLiveStream = liveOnExo || player.isLiveContent
                // Dedicated CH+/CH- belong to a Live TV/catch-up player for its whole lifetime. Do not
                // derive this callback from the active engine or current list readiness: both legitimately
                // go through short false/empty windows during startup and ExoPlayer/mpv handoffs. Browse
                // panels keep their configurable paging because PlayerHud exists only in full-screen here.
                val zap: ((Int) -> Unit)? =
                    if (zapSource == MainSection.LIVE_TV) liveVm::zap else null
                // Live rewind controls apply to a Live-TV channel (live OR its timeshift archive).
                val isLiveChannel = zapSource == MainSection.LIVE_TV
                // ...but NOT to a catch-up archive programme. That's VOD-style playback of a past
                // programme, so it gets the VOD engine toggle (reloads the same archive URL at the same
                // position on the other engine) rather than Live TV's compatibility toggle, which would
                // re-tune the live stream and jump the user to the current programme.
                val isTunedLive = isLiveChannel && !catchupActive
                // Sports Hub: the extras exist only while the playing channel is one of the match's.
                val sportsPlayback = koinInject<tv.own.owntv.features.sports.SportsPlayback>()
                val sportsSession by sportsPlayback.session.collectAsStateWithLifecycle()
                val sportsConfig by settingsRepo.activeSportsConfig.collectAsStateWithLifecycle(tv.own.owntv.core.sports.SportsConfig())
                val matchSession = sportsSession?.takeIf { s -> isTunedLive && s.options.any { it.channel.id == previewChannel?.id } }
                var showOtherChannels by remember { mutableStateOf(false) }
                var goalToast by remember { mutableStateOf<tv.own.owntv.core.sports.MatchAlert?>(null) }
                LaunchedEffect(isTunedLive) { if (isTunedLive) sportsPlayback.goals().collect { goalToast = it } }
                LaunchedEffect(goalToast) { if (goalToast != null) { kotlinx.coroutines.delay(GOAL_TOAST_MS); goalToast = null } }
                // A goal elsewhere: play that match's best channel if Find channels already found one,
                // else open its match page, where they can be found.
                val switchToMatch: (tv.own.owntv.core.sports.model.Match) -> Unit = { m ->
                    goalToast = null
                    scope.launch {
                        val options = sportsPlayback.channelsFor(m)
                        val best = options.firstOrNull()
                        if (best == null) {
                            sportsPlayback.pendingMatch.value = m to previewChannel
                            exitPlayer()
                            onSelectSection(MainSection.SPORTS)
                        } else if (liveVm.ensurePlayingByIdAsync(best.channel.id, options.map { it.channel })) {
                            sportsPlayback.start(m, options)
                        }
                    }
                }
                PlayerHud(
                    player = if (liveOnExo) liveVm.previewEngine else mpvEngine, // HUD drives the active engine
                    onBack = exitBack,
                    // The channel-list overlay draws ABOVE the HUD; while it's open the HUD goes inert so
                    // its hide/error focus grabs can't yank D-pad focus off the overlay.
                    inert = showChannelList || showHistoryList || showCategoryBrowser || showSubtitleSearch || showLocalSubPicker || showOtherChannels,
                    onChannelUp = zap?.let { z -> { z(-1) } },
                    onChannelDown = zap?.let { z -> { z(1) } },
                    onOpenChannelList = if (isTunedLive && liveCanZap) { { showChannelList = true } } else null,
                    // Live channels only, and only once Multiview is switched on: the channel on screen
                    // becomes tile 1 and the grid takes over. Everything it needs is already tuned.
                    onMultiview = if (multiviewEnabled && isTunedLive && previewChannel != null) {
                        { multiview = openMultiview(previewChannel, emptyList()) }
                    } else {
                        null
                    },
                    // The button exists only once the setting is on, and only on a live
                    // channel: there is nothing to record off a film that is already a file.
                    // The engine does not come into it. Recording fetches the channel itself rather
                    // than copying the open stream, so it works the same whichever engine is playing.
                    onRecordThis = previewChannel
                        ?.takeIf { recordWatchingEnabled && isTunedLive }
                        ?.let { channel -> { liveVm.togglePlayerRecording(channel) } },
                    recordingThis = playerRecording != null,
                    onOpenHistoryList = if (isTunedLive) { { showHistoryList = true } } else null,
                    onRewindLive = if (isTunedLive && canRewindLive) liveVm::rewindLive else null,
                    onForwardLive = if (isTunedLive) liveVm::forwardLive else null,
                    onGoToLive = if (isTunedLive) liveVm::goToLive else null,
                    onScrubLive = if (isTunedLive && canRewindLive) liveVm::scrubLive else null,
                    // Only collected where there is a timeline to draw them on.
                    liveProgrammes = if (isTunedLive && canRewindLive) timelineProgrammes else emptyList(),
                    liveGaps = liveVm::timeshiftGaps,
                    // "Go back to…" is the provider archive's: a copy saved on this device rewinds with the
                    // bar and the buttons, and must not show a catch-up control on a channel without catch-up.
                    jumpBackOptions = if (isTunedLive && previewChannel?.catchup == true) liveVm::currentJumpOptions else null,
                    onJumpBack = if (isTunedLive && previewChannel?.catchup == true) liveVm::jumpBackTo else null,
                    onPreviousChannel = if (isTunedLive && previousLiveChannel != null) liveVm::previousChannel else null,
                    jumpBackWindowSec = if (isTunedLive && previewChannel?.catchup == true) liveVm::currentCatchupWindowSec else null,
                    // Non-null only while an archive is on screen, so movies, episodes and live TV get
                    // the single real clock and catch-up gets the pair.
                    watchingWallMs = { watchingWallState.value },
                    // "End of programme" only at the live edge: rewound, the programme on air is not the one being watched.
                    sleepProgrammeEndMs = if (isTunedLive && !timeshifted) { { liveVm.nowNext.value?.now?.stopMs } } else null,
                    liveLeftRightRewinds = liveLeftRightRewinds,
                    timeshiftOffsetSec = if (isTunedLive) { { timeshiftOffsetState.value } } else null,
                    onTuneToNumber = if (directTuneEnabled && isTunedLive && isLiveStream && !timeshifted && previewChannel != null) liveVm::tuneByNumber else null,
                    directTuneContextKey = previewChannel?.id ?: 0L,
                    // Show the ACTUAL running engine (mpv when pinned OR auto-fallen-back), not just the pin —
                    // otherwise an auto-fallback to mpv still read "EXO". true = on mpv (pill shows MPV, teal).
                    compatMode = if (isTunedLive) !liveOnExo else null,
                    // Hidden while rewound into the archive (same `!timeshifted` rule direct
                    // tune follows above): switching engine restarts the channel at the live edge, which
                    // threw the user out of the rewind with the HUD still counting "behind live".
                    // Also hidden for a protected channel: only ExoPlayer can license it, so the
                    // toggle's other position is not a compatibility choice but a guaranteed failure.
                    // A copy saved on this device is the exception: the other engine re-opens the same copy
                    // at the same moment, so the rewind survives the switch.
                    onToggleCompatMode = if (isTunedLive && (!timeshifted || onLocalCopy) && previewChannel?.drmConfig == null) liveVm::toggleForceMpv else null,
                    // VOD engine toggle (movies/series only — live and catch-up channels keep their own
                    // engine handling above): flip the current item between mpv and ExoPlayer.
                    vodOnExo = if (!isLiveStream && !isTunedLive) vodExoActive else null,
                    onToggleVodEngine = if (!isLiveStream && !isTunedLive) player::toggleVodEngine else null,
                    // ADD SUBTITLES entry: movies/episodes only, and only when the play path set an
                    // item context. Opens the OpenSubtitles search overlay below.
                    onSearchSubtitles = if (!isLiveStream && !isLiveChannel && subtitleContext != null) {
                        { showSubtitleSearch = true }
                    } else null,
                    // Local subtitle file: same movie/episode gating, no account needed.
                    onSelectLocalSubtitle = if (!isLiveStream && !isLiveChannel && subtitleContext != null) {
                        { showLocalSubPicker = true }
                    } else null,
                    // In-stream favorite toggle for the current channel/movie/series.
                    favorite = favActive,
                    onToggleFavorite = favToggle,
                    // The zap banner's now/next. Collected inside the slot, so only the banner redraws.
                    zapGuide = if (isLiveChannel) {
                        {
                            val guide by liveVm.zapGuide.collectAsStateWithLifecycle()
                            tv.own.owntv.features.shell.components.ZapGuideLines(guide)
                        }
                    } else null,
                    // The bottom info bar after a channel change, and with it the list-first remote.
                    // Tuned live only: a catch-up programme keeps the classic controls, its seek bar.
                    zapInfo = if (isTunedLive) {
                        {
                            tv.own.owntv.player.ZapInfo(
                                guide = liveVm.zapGuide.collectAsStateWithLifecycle().value,
                                channel = liveVm.zapChannel.collectAsStateWithLifecycle().value,
                                guidePending = liveVm.zapGuidePending.collectAsStateWithLifecycle().value,
                            )
                        }
                    } else null,
                    remote = tv.own.owntv.player.LiveRemoteActions(
                        // Long Menu and the control panel's first tile: the guide, with the channel in its preview.
                        onOpenGuide = if (isLiveChannel && MainSection.LIVE_TV in visibleSections) {
                            { exitToList() }
                        } else null,
                        // Search (a key bound to it): the player closes first, and leaving Live TV stops the stream.
                        onOpenSearch = if (isLiveChannel) { { exitPlayer(); onSelectSection(MainSection.SEARCH) } } else null,
                        // The control panel's channel tiles: the channels watched last, with what is on now.
                        panelChannels = if (isTunedLive) {
                            {
                                val playingId = previewChannel?.id
                                val recent by produceState(emptyList<tv.own.owntv.core.database.entity.ChannelEntity>(), playingId) {
                                    value = runCatching { liveVm.historyChannels(PANEL_CHANNELS + 1) }.getOrDefault(emptyList())
                                        .map { it.channel }.filter { it.id != playingId }.take(PANEL_CHANNELS)
                                }
                                LaunchedEffect(recent) { liveVm.ensureNowPlayingFetched(recent) }
                                val now by liveVm.nowPlaying.collectAsStateWithLifecycle()
                                recent.map { ch ->
                                    tv.own.owntv.player.PanelTile(ch.id, ch.name, logoUrl = ch.displayLogoUrl, caption = now[ch.id]) { liveVm.playInPlayer(ch) }
                                }
                            }
                        } else null,
                    ),
                    // Guide card for the playing channel (nowNext follows previewChannel = what's playing).
                    liveEpgCard = if (isLiveChannel) {
                        {
                            val epg by liveVm.nowNext.collectAsStateWithLifecycle()
                            val archiveEpg by liveVm.archiveNowNext.collectAsStateWithLifecycle()
                            val watching by liveVm.watchingWallMs.collectAsStateWithLifecycle()
                            // Stacked: what was on air at the replayed moment, then what is on air now.
                            // The live row is dimmed while an archive plays — it is context, not the
                            // thing being watched — and returns to full strength back at the live edge.
                            androidx.compose.foundation.layout.Column(
                                horizontalAlignment = androidx.compose.ui.Alignment.End,
                                verticalArrangement = androidx.compose.foundation.layout.Arrangement.spacedBy(7.dp),
                            ) {
                                if (watching != null) {
                                    tv.own.owntv.features.shell.components.LiveEpgCard(
                                        epg = archiveEpg,
                                        variant = tv.own.owntv.features.shell.components.EpgCardVariant.ARCHIVE,
                                        atMs = watching,
                                    )
                                }
                                tv.own.owntv.features.shell.components.LiveEpgCard(
                                    epg = epg,
                                    modifier = if (watching == null) Modifier else Modifier.alpha(0.55f),
                                )
                            }
                        }
                    } else null,
                    matchLine = matchSession?.takeIf { sportsConfig.scoreInInfoBar }?.let { s ->
                        { tv.own.owntv.features.sports.MatchScoreLine(sportsPlayback, s.match, sportsConfig.spoilerFree) }
                    },
                    onOtherChannels = matchSession?.takeIf { it.options.size > 1 }?.let { { showOtherChannels = true } },
                    otherChannelsCount = (matchSession?.options?.size ?: 1) - 1,
                    onToastOk = goalToast?.let { a -> { switchToMatch(a.match) } },
                    modifier = Modifier.fillMaxSize(),
                )
                goalToast?.let { a ->
                    tv.own.owntv.features.sports.GoalToast(a, Modifier.align(androidx.compose.ui.Alignment.TopEnd).padding(top = 45.mpx, end = 60.mpx))
                }
                if (showOtherChannels) {
                    val s = matchSession
                    if (s == null) showOtherChannels = false
                    else tv.own.owntv.features.sports.OtherChannelsPanel(
                        session = s,
                        playingId = previewChannel?.id,
                        onSwitch = { session, i -> liveVm.ensurePlaying(session.options[i].channel); showOtherChannels = false },
                        onDismiss = { showOtherChannels = false },
                    )
                }
                // OpenSubtitles search overlay (movies/episodes) — drawn above the HUD; the HUD is inert
                // while it's open so the D-pad stays on the overlay.
                if (showSubtitleSearch) {
                    tv.own.owntv.features.subtitles.SubtitleSearchScreen(
                        onDismiss = { showSubtitleSearch = false },
                        modifier = Modifier.fillMaxSize(),
                    )
                }
                // Local subtitle-file picker — hosted in a Dialog window, so D-pad focus
                // can't fall through to the HUD behind; picking imports a managed UTF-8 copy and
                // attaches it live to whichever engine is playing.
                if (showLocalSubPicker) {
                    tv.own.owntv.ui.components.StorageBrowser(
                        title = stringResource(R.string.content_subtitle_select_file),
                        mode = tv.own.owntv.ui.components.BrowseMode.FILE,
                        fileExtensions = setOf("srt", "ass", "ssa", "vtt", "webvtt"),
                        onPick = { file ->
                            showLocalSubPicker = false
                            scope.launch {
                                runCatching { subtitleController.applyLocal(file) }
                                    .onFailure { e ->
                                        localSubToast.show(e.message ?: subtitleLoadFailed)
                                    }
                            }
                        },
                        onDismiss = { showLocalSubPicker = false },
                    )
                }
                tv.own.owntv.ui.components.InAppToast(localSubToast)
                // Programme reminders: when one comes up, ask to switch, switch, or only say so (the
                // Programme reminders setting) — over whatever is on screen, the player included.
                val reminderManager = koinInject<tv.own.owntv.core.reminder.ReminderManager>()
                val dueReminders by reminderManager.due.collectAsStateWithLifecycle()
                val reminderMode by settingsRepo.reminderMode.collectAsStateWithLifecycle(tv.own.owntv.core.settings.SettingsRepository.ReminderMode.ASK)
                val dueReminder = dueReminders.firstOrNull { it.profileId == activeProfileId }
                val watchReminder: (tv.own.owntv.core.database.entity.ReminderEntity) -> Unit = { r ->
                    scope.launch {
                        reminderManager.dismiss(r.id)
                        playLiveById(r.channelId, emptyList())
                    }
                }
                val reminderText = dueReminder?.let { listOfNotNull(it.title, tv.own.owntv.features.epg.reminderEyebrow(it)).joinToString(" · ") }
                LaunchedEffect(dueReminder?.id, reminderMode) {
                    val r = dueReminder ?: return@LaunchedEffect
                    when (reminderMode) {
                        tv.own.owntv.core.settings.SettingsRepository.ReminderMode.SWITCH -> watchReminder(r)
                        tv.own.owntv.core.settings.SettingsRepository.ReminderMode.NOTIFY -> {
                            reminderText?.let(localSubToast::show)
                            reminderManager.dismiss(r.id)
                        }
                        tv.own.owntv.core.settings.SettingsRepository.ReminderMode.ASK -> Unit
                    }
                }
                if (dueReminder != null && reminderMode == tv.own.owntv.core.settings.SettingsRepository.ReminderMode.ASK) {
                    tv.own.owntv.features.epg.ReminderPrompt(
                        reminder = dueReminder,
                        onWatch = { watchReminder(dueReminder) },
                        onDismiss = { scope.launch { reminderManager.dismiss(dueReminder.id) } },
                    )
                }
                // A catch-up pick that could not be resolved. Raised from Live TV wherever an archive
                // URL comes back null — "Watch from start", "Go back to…" and the external hand-off —
                // all of which used to fail in complete silence.
                val catchupUnavailable = stringResource(R.string.content_epg_catchup_unavailable)
                LaunchedEffect(liveVm) {
                    liveVm.catchupUnavailable.collect { localSubToast.show(catchupUnavailable) }
                }
                // A saved copy of the channel was kept: no question about it, the picture stays live. Rewinding
                // is the pause key's job (Jonathan, 27 Oct 2026: nothing pops up by itself).
                val timeshiftResumeAt by liveVm.timeshiftResumeAt.collectAsStateWithLifecycle()
                LaunchedEffect(timeshiftResumeAt) { if (timeshiftResumeAt != null) liveVm.dismissTimeshiftResume() }
                // Left — the playing channel's own provider category.
                if (showChannelList && isLiveChannel) {
                    if (showCategoryBrowser) {
                        // Second Left — every Live TV category.
                        tv.own.owntv.features.shell.components.CategoryBrowserOverlay(
                            railItems = browserRailItems,
                            railCounts = browserRailCounts,
                            // The list the player zaps in: a built-in rail or custom category when it came from one.
                            currentKey = zapListKey ?: previewChannel?.categoryId?.let { tv.own.owntv.core.live.LiveKey.Folder(it) },
                            onSelect = { key -> liveVm.loadChannelsForCategory(key) },
                            onDismiss = { liveVm.hideCategoryBrowser() },
                            modifier = Modifier.fillMaxSize(),
                        )
                    } else if (zapChannels.isNotEmpty()) {
                        // First Left — the channels of the current category. A browsed-to category may
                        // hold a single channel, so this renders for any non-empty list.
                        tv.own.owntv.features.shell.components.ChannelListOverlay(
                            channels = zapChannels,
                            currentId = previewChannel?.id,
                            nowPlaying = overlayNowPlaying,
                            nowProgrammes = overlayProgrammes,
                            favoriteIds = overlayFavoriteIds,
                            playlistMarks = overlayPlaylistMarks,
                            title = zapOverlayTitle,
                            showNumbers = directTuneEnabled,
                            onSelect = { liveVm.ensurePlaying(it); showChannelList = false },
                            onDismiss = { showChannelList = false },
                            onOpenCategories = { liveVm.showCategories() },
                            guideFor = liveVm::guideFor,
                            modifier = Modifier.fillMaxSize(),
                        )
                    }
                }
                // Right — recently watched, to hop straight back to the previous channel.
                if (showHistoryList && isLiveChannel && historyChannels.isNotEmpty()) {
                    tv.own.owntv.features.shell.components.ChannelListOverlay(
                        channels = historyChannels,
                        currentId = previewChannel?.id,
                        nowPlaying = historyNowPlaying,
                        nowProgrammes = overlayProgrammes,
                        favoriteIds = overlayFavoriteIds,
                        playlistMarks = overlayPlaylistMarks,
                        watchedAt = historyWatchedAt,
                        title = stringResource(R.string.content_category_recently_watched),
                        showNumbers = directTuneEnabled,
                        alignEnd = true,
                        onSelect = { liveVm.playInPlayer(it); showHistoryList = false },
                        onDismiss = { showHistoryList = false },
                        modifier = Modifier.fillMaxSize(),
                    )
                }
            }
        }
      }

    // An invisible place for focus to wait while the guide catches up after full screen (see parkFocus).
    Box(
        Modifier.size(1.dp)
            .focusRequester(handoverFocus)
            .focusProperties { canFocus = handoverParking }
            .onFocusChanged { if (!it.hasFocus) handoverParking = false }
            .focusable(),
    )
    if (showExit) {
        ExitDialog(onConfirm = onExitApp, onDismiss = { showExit = false })
    }
    if (showAvatarPicker) {
        AvatarPickerDialog(
                selectedId = avatarId,
                onSelect = onSetAvatar,
                customPath = avatarPath,
                onPickCustom = { showAvatarPicker = false; showAvatarChooser = true },
                onClearCustom = { onClearCustomAvatar(); showAvatarPicker = false },
                onDismiss = { showAvatarPicker = false },
        )
    }
    if (showAvatarChooser) {
        tv.own.owntv.features.profiles.ProfilePictureChooser(
                hasImage = avatarPath.isNotBlank(),
                onPicked = { file -> onSetCustomAvatar(file); showAvatarChooser = false },
                onClear = { onClearCustomAvatar(); showAvatarChooser = false },
                onDismiss = { showAvatarChooser = false },
            )
    }
    if (showPlaylistPicker) {
        tv.own.owntv.ui.components.OwnTVPopup(onDismissRequest = { showPlaylistPicker = false }, stageLayout = true) {
            tv.own.owntv.features.shell.components.StagePlaylistMenu(
                playlists = playlists,
                activeId = activePlaylistId,
                pillLabel = playlistLabel,
                pillBounds = playlistPillBounds,
                onSelect = onSelectPlaylist,
                onDismiss = { showPlaylistPicker = false },
            )
        }
    }

    // A Guide auto-match keeps running when the Guide is left, and its review waits there for the
    // Guide to come back — but finishing off the Guide said nothing at all. Say how it went, here
    // because this layer is on screen everywhere, the player included. Not on the Guide itself:
    // there the line beside the button, or the review window, already says it.
    val epgMatchToast = tv.own.owntv.ui.components.rememberInAppToast()
    val guideOnScreen = playerMode != PlayerMode.FULLSCREEN && multiview == null &&
        selectedSection == MainSection.LIVE_TV
    val currentGuideOnScreen by rememberUpdatedState(guideOnScreen)
    var finishedMatch by remember { mutableStateOf<tv.own.owntv.features.epg.EpgMatchSummary?>(null) }
    LaunchedEffect(epgVm) {
        epgVm.matchFinished.collect { if (!currentGuideOnScreen) finishedMatch = it }
    }
    finishedMatch?.let { summary ->
        val text = tv.own.owntv.features.epg.epgMatchSummaryText(summary)
        LaunchedEffect(summary) {
            epgMatchToast.show(text, durationMs = EPG_MATCH_TOAST_MS)
            finishedMatch = null
        }
    }
    tv.own.owntv.ui.components.InAppToast(epgMatchToast)

    // Automatic update check (GitHub Releases) shortly after launch, once per session: a small
        // top-right status card shows "Checking… / up to date" (auto-hides) or stays with
        // Update now / Later when a release is newer. Hidden while in Settings (its manual
        // "Check for updates" dialog drives the same state machine) and during playback.
        // Interrupted restore: the marker outlives the process, so if it's still set at launch
        // the last restore didn't complete. Acknowledging clears it.
        val restoreSettings = koinInject<tv.own.owntv.core.settings.SettingsRepository>()
    val incompleteRestore by restoreSettings.restoreInProgress.collectAsStateWithLifecycle(initialValue = null)
    var restoreNoticeDismissed by remember { mutableStateOf(false) }
    incompleteRestore?.takeIf { !restoreNoticeDismissed }?.let { description ->
        val dismissRestoreNotice: () -> Unit = {
            restoreNoticeDismissed = true
            scope.launch { restoreSettings.clearRestoreMarker() }
        }
        IncompleteRestoreDialog(
                description = description,
                onDismiss = dismissRestoreNotice,
            )
    }

        val updateManager = koinInject<UpdateManager>()
        var showStartupToast by remember { mutableStateOf(false) }
        var showChangelog by remember { mutableStateOf(false) }
        val settingsRepo = koinInject<tv.own.owntv.core.settings.SettingsRepository>()
        val updateCheckOnStart by settingsRepo.updateCheckOnStart.collectAsStateWithLifecycle(initialValue = false)
        LaunchedEffect(updateCheckOnStart) {
            // Once per launch: a rebuilt shell must not bring the "latest version" card back.
            if (updateCheckOnStart && !showStartupToast && !shellVm.startupUpdateCheckDone) {
                // After the start window, with a pause so the card does not land while the guide comes up.
                tv.own.owntv.StartupGate.awaitSettled()
                kotlinx.coroutines.delay(2_000)
                shellVm.startupUpdateCheckDone = true
                updateManager.check()
                // The card shows up only when there is something to update to: no "Checking…" and no "latest
                // version" on every start (Jonathan, 27 Oct 2026: nothing pops up by itself).
                val outcome = updateManager.state.first {
                    it is UpdateManager.State.Available || it is UpdateManager.State.UpToDate || it is UpdateManager.State.Failed
                }
                if (outcome is UpdateManager.State.Available) showStartupToast = true else updateManager.reset()
            }
        }
        if (showChangelog) {
            // Full "What's New" changelog (same dialog the manual Settings check uses), shown when
            // the startup card's "What's New" is pressed. No re-check — the release is already loaded.
            val dismissChangelog: () -> Unit = {
                showChangelog = false
                showStartupToast = false
                updateManager.reset()
            }
            UpdateDialog(onDismiss = dismissChangelog, checkOnOpen = false)
        } else if (showStartupToast && selectedSection != MainSection.SETTINGS && playerMode == PlayerMode.NONE) {
            Box(Modifier.fillMaxSize(), contentAlignment = Alignment.TopEnd) {
                UpdateStatusToast(
                    onDone = { showStartupToast = false; updateManager.reset() },
                    onViewChangelog = { showChangelog = true },
                )
            }
        }
    }
    }
}

/** Channel tiles in the player's control panel, after Guide and History. */
private const val PANEL_CHANNELS = 10

/** A goal toast stays this long. */
private const val GOAL_TOAST_MS = 8_000L

/** Long enough to read "Auto-matched 12 channels. 40 channels need review." from the sofa. */
private const val EPG_MATCH_TOAST_MS = 6_000L

/** A thin bar shown above the browse UI when the device loses internet. */
@Composable
private fun OfflineBanner() {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .stageGlass(0.dp, overContent = true)
            .padding(horizontal = 24.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.Center,
    ) {
        Text(
            stringResource(R.string.content_offline_banner),
            style = tv.own.owntv.ui.theme.stageText(25, 600),
            color = tv.own.owntv.ui.theme.StageColors.Text,
        ) }
    }

/** How recently ◀ / ▶ or [focusRail] must have happened for a rail visit to open the rail. */
private const val RAIL_KEY_WINDOW_MS = 400L

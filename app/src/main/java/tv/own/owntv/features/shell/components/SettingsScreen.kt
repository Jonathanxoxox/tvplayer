package tv.own.owntv.features.shell.components

import tv.own.owntv.ui.theme.StageColors
import tv.own.owntv.features.settings.SettingValue
import tv.own.owntv.features.settings.labelRes
import tv.own.owntv.features.settings.AccentSwatches
import tv.own.owntv.ui.theme.mpx
import tv.own.owntv.features.settings.StageSettingRow
import tv.own.owntv.features.settings.StageSettingsPage
import tv.own.owntv.features.settings.StageSettingsHeading
import tv.own.owntv.features.settings.StageSettingsNote
import tv.own.owntv.features.settings.StageSearchResultRow
import tv.own.owntv.features.settings.VideoGroup
import tv.own.owntv.features.settings.VideoPlayerGroupRows
import tv.own.owntv.features.settings.settingHelp
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.focusable
import androidx.compose.foundation.border
import androidx.compose.foundation.focusGroup
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.withFrameNanos
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.type
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.koin.androidx.compose.koinViewModel
import androidx.tv.material3.Text
import tv.own.owntv.R
import tv.own.owntv.features.customize.CustomizeScreen
import tv.own.owntv.core.i18n.SupportedLocales
import tv.own.owntv.core.player.SurroundMode
import tv.own.owntv.features.settings.HomeSettingsScreen
import tv.own.owntv.features.settings.NO_ARGS
import tv.own.owntv.features.settings.LanguageSettingsScreen
import tv.own.owntv.features.settings.LanguageSettingsViewModel
import tv.own.owntv.core.settings.SettingsRepository
import tv.own.owntv.features.update.UpdateDialog
import tv.own.owntv.features.settings.ManageSourcesScreen
import tv.own.owntv.features.settings.SettingsViewModel
import tv.own.owntv.core.nav.MainSection
import tv.own.owntv.ui.components.BrowseMode
import tv.own.owntv.ui.components.OwnTVIcon
import tv.own.owntv.ui.components.StorageBrowser
import tv.own.owntv.ui.components.BackgroundImageChooserDialog
import tv.own.owntv.ui.components.ingestBackgroundImage
import tv.own.owntv.ui.components.trapAllFocusExit
import tv.own.owntv.ui.components.longPressMenuGuard
import tv.own.owntv.core.theme.GlassConfig
import tv.own.owntv.core.theme.AppFontFamily
import tv.own.owntv.core.theme.FontCustomization
import tv.own.owntv.core.theme.PopupSizeScale
import tv.own.owntv.core.theme.ThemeMode
import tv.own.owntv.core.theme.UiZoom
import kotlin.math.roundToInt
import java.io.File
import java.util.Locale
import tv.own.owntv.ui.theme.labelRes
import tv.own.owntv.ui.theme.primary

internal enum class TileTone { PRIMARY, SECONDARY, TERTIARY }

private enum class SettingsTab { ROOT, LANGUAGE, SOURCES, EPG, BACKUP, LOCAL_SYNC, CUSTOMIZE, HOME, NETWORK, DNS, METADATA, OPEN_SUBTITLES, WEATHER, CH_NAV, PANEL_WIDTH, GUIDE_WIDTH, GLASS_EFFECT, CONTENT_MENUS, FONTS, BROWSING, SUBTITLE_STYLE, SPORTS, PER_PLAYLIST, RESETS }

@Composable
internal fun surroundModeLabel(mode: SurroundMode): String = stringResource(
    when (mode) {
        SurroundMode.AUTO -> R.string.settings_auto
        SurroundMode.STEREO -> R.string.settings_surround_stereo
        SurroundMode.SURROUND -> R.string.settings_surround_sound
    },
)

@Composable
private fun epgShiftLabel(minutes: Int): String {
    if (minutes == 0) return stringResource(R.string.common_off)
    val locale = androidx.compose.ui.platform.LocalConfiguration.current.locales[0] ?: java.util.Locale.US
    val number = java.text.NumberFormat.getIntegerInstance(locale)
    val sign = if (minutes < 0) "−" else "+"
    val absolute = kotlin.math.abs(minutes)
    val hours = absolute / 60
    val remainder = absolute % 60
    return when {
        hours == 0 -> stringResource(R.string.content_epg_shift_minutes, sign, number.format(remainder))
        remainder == 0 -> stringResource(R.string.content_epg_shift_hours, sign, number.format(hours))
        else -> stringResource(
            R.string.content_epg_shift_hours_minutes,
            sign,
            number.format(hours),
            number.format(remainder),
        )
    }
}

/**
 * The MD3 Settings screen (shown when [MainSection.SETTINGS] is active): grouped sections, each row
 * a tonal icon tile + title/description + a trailing chip or chevron. Theme / UI Zoom are live;
 * unfinished features show a "Soon" chip.
 */
@Composable
fun SettingsScreen(
    themeMode: ThemeMode,
    uiZoomPercent: Int,
    onSetZoom: (Int) -> Unit,
    fontCustomization: FontCustomization,
    onSetFontCustomization: (FontCustomization) -> Unit,
    onOpenPlaylist: () -> Unit,
    modifier: Modifier = Modifier,
    /**
     * Where Back from the **root** of this screen goes. Settings sits behind More, so leaving
     * it should land on More rather than throw focus all the way out to the rail. Null keeps the old
     * behaviour, which is the shell's own handler.
     */
    onBack: (() -> Unit)? = null,
    openEpgAdd: Boolean = false,
    onEpgAddConsumed: () -> Unit = {},
    /** More › Settings: open at a root group, or with search open. */
    start: SettingsStart? = null,
    onStartConsumed: () -> Unit = {},
    /** Mayo TV: More's profile, places and pages in the list on the left (More and Settings are one screen). */
    more: tv.own.owntv.features.settings.SettingsMoreHost? = null,
) {
    // A cross-script language change recreates the Activity so Android can apply the new script's
    // shaping and font fallback. Keep the open settings sub-screen across that configuration change
    // instead of dropping back to the Settings root/sidebar.
    var tab by rememberSaveable { mutableStateOf(SettingsTab.ROOT) }
    // Deep-link from the Guide's "Add EPG" button: jump straight to EPG Sources in add mode.
    var consumeEpgAdd by remember { mutableStateOf(false) }
    var showZoom by remember { mutableStateOf(false) }
    var showAppIcon by remember { mutableStateOf(false) }
    var showPopupSize by remember { mutableStateOf(false) }
    var showTheme by remember { mutableStateOf(false) }
    var showAccent by remember { mutableStateOf(false) }
    var showFocusHighlight by remember { mutableStateOf(false) }
    var showUpdate by remember { mutableStateOf(false) }
    var showCatchupTime by remember { mutableStateOf(false) }
    // Per-playlist catch-up time zone: the playlist list, then the value picker for [catchupSource].
    var showCatchupSources by remember { mutableStateOf(false) }
    var showCatchupSourceValue by remember { mutableStateOf(false) }
    var catchupSource by remember { mutableStateOf<tv.own.owntv.core.database.entity.SourceEntity?>(null) }
    var showEpgOffset by remember { mutableStateOf(false) }
    var showGuideDays by remember { mutableStateOf(false) }
    var showAnimations by remember { mutableStateOf(false) }
    var showNavigation by remember { mutableStateOf(false) }
    // The three Stage pickers (Live TV opens in, Programme reminders, Reminder time): their state, rows,
    // search entries and dialogs live outside this function, which sits at the JVM's 64 KB method limit.
    val stageSettings = remember { StageSettingsState() }
    var showStartup by remember { mutableStateOf(false) }
    var showStartupChannelPicker by remember { mutableStateOf(false) }
    var showAfrWarning by remember { mutableStateOf(false) }
    var showLivePreviewPanelWarning by remember { mutableStateOf(false) }
    var showBgImageChooser by remember { mutableStateOf(false) }
    var showBgPicker by remember { mutableStateOf(false) }
    var showBgRemote by remember { mutableStateOf(false) }
    var showAmbientGlow by remember { mutableStateOf(false) }
    // Background-image ingest copies a multi-megabyte file; it runs here, off the main thread.
    val ingestScope = rememberCoroutineScope()

    // Settings search + quick toggles. Empty query = normal grouped list; a non-blank
    // query swaps the list for flat results that carry their group context ("Playback › HDR").
    var searchQuery by remember { mutableStateOf("") }
    // Search opened (from More, or a full page's search): the plain search page, even while the query
    // is still empty — not the group that happens to be selected behind it.
    var searchMode by remember { mutableStateOf(false) }
    var searchFromMore by remember { mutableStateOf(false) }
    val searchFieldFocus = remember { FocusRequester() }
    // While searching, Back clears the query (and returns focus to the field) instead of leaving Settings.
    BackHandler(enabled = tab == SettingsTab.ROOT && searchQuery.isNotBlank()) {
        searchQuery = ""
        runCatching { searchFieldFocus.requestFocus() }
    }

    // Dialog-close focus return: closing a dialog/picker refocuses the row that opened it (focus
    // would otherwise fall spatially back to the sidebar).
    val themeRowFocus = remember { FocusRequester() }
    val homeLayoutRowFocus = remember { FocusRequester() }
    var showHomeLayout by remember { mutableStateOf(false) }
    val launcherConfig = tv.own.owntv.launcher.apps.rememberLauncherConfig()
    val launcherContext = LocalContext.current
    val homeLayouts = listOf(
        tv.own.owntv.launcher.apps.LauncherConfig.LAYOUT_STANDARD,
        tv.own.owntv.launcher.apps.LauncherConfig.LAYOUT_CLASSIC,
        tv.own.owntv.launcher.apps.LauncherConfig.LAYOUT_STAGE,
        tv.own.owntv.launcher.apps.LauncherConfig.LAYOUT_SPOTLIGHT,
    )
    val stepHomeLayout: (Int) -> Unit = { step ->
        tv.own.owntv.launcher.apps.LauncherPrefs.update(launcherContext) { c ->
            val i = homeLayouts.indexOf(c.homeLayout).coerceAtLeast(0)
            c.copy(homeLayout = homeLayouts[Math.floorMod(i + step, homeLayouts.size)])
        }
    }
    val accentRowFocus = remember { FocusRequester() }
    val focusHighlightRowFocus = remember { FocusRequester() }
    val zoomRowFocus = remember { FocusRequester() }
    val popupSizeRowFocus = remember { FocusRequester() }
    val updateRowFocus = remember { FocusRequester() }
    val catchupRowFocus = remember { FocusRequester() }
    val catchupSourcesRowFocus = remember { FocusRequester() }
    val epgOffsetRowFocus = remember { FocusRequester() }
    val guideDaysRowFocus = remember { FocusRequester() }
    val animationsRowFocus = remember { FocusRequester() }
    val navigationRowFocus = remember { FocusRequester() }
    val startupRowFocus = remember { FocusRequester() }
    val livePreviewQuickFocus = remember { FocusRequester() }
    val ambientGlowRowFocus = remember { FocusRequester() }
    // Hoisted list state for the root settings list. We snapshot its position the instant a row is
    // clicked (in onClick, before any recomposition) and restore it on dialog close, so the list
    // doesn't visibly jump/scroll when the dialog opens or when we refocus the opener row afterward.
    // A lazy list carries its position as item index + offset into that item, so both are saved.
    val pageScroll = rememberScrollState()
    // These belong to the two-pane Settings root, but stay remembered while a detail screen replaces
    // it. Otherwise Back briefly rebuilds Quick at row zero before restoring the real group/row.
    // Start on the card More opened, not Quick: waiting for the start effect drew Quick for a few frames first.
    var selectedGroup by rememberSaveable { mutableIntStateOf(start?.group ?: 0) }
    var displayedGroup by rememberSaveable { mutableIntStateOf(start?.group ?: 0) }
    var savedScrollPx by remember { mutableIntStateOf(0) }
    val saveScroll = { savedScrollPx = pageScroll.value }
    // The rows column: fresh entry and a lost focus land here, on the page's first row.
    val rowsFocus = remember { FocusRequester() }
    // Opens the search field's keyboard as it appears, so one OK reaches typing (More's search box too).
    var searchAutoEdit by remember { mutableStateOf(false) }
    LaunchedEffect(searchAutoEdit) { if (searchAutoEdit) { kotlinx.coroutines.delay(1000); searchAutoEdit = false } }
    LaunchedEffect(start) {
        if (start == null) return@LaunchedEffect
        tab = SettingsTab.ROOT
        start.group?.let { selectedGroup = it; displayedGroup = it }
        if (start.search) {
            searchMode = true
            searchFromMore = true
            searchAutoEdit = true
            for (attempt in 0 until 10) { kotlinx.coroutines.delay(50); if (runCatching { searchFieldFocus.requestFocus() }.getOrDefault(false)) break }
        }
        onStartConsumed()
    }
    val anyDialogOpen = showZoom || showPopupSize || showTheme || showAccent || showUpdate || showCatchupTime || showCatchupSources || showCatchupSourceValue || showEpgOffset || showGuideDays || showAnimations || showStartup || showStartupChannelPicker || showAfrWarning || showLivePreviewPanelWarning || showBgImageChooser || showBgPicker || showAmbientGlow || showFocusHighlight || showBgRemote || showNavigation || stageSettings.open != null
    // When a dialog closes, restore focus to the row that opened it. NOTE: this restore crosses
    // INTO the root focus group from outside (the dialog), but onEnter does NOT fire for programmatic
    // requestsFocus (only for directional entry) — so dialogReturn must be cleared HERE, not in onEnter.
    // If it's left set, the next directional entry (e.g. sidebar→here) would re-route to a stale row.
    var dialogReturn by remember { mutableStateOf<FocusRequester?>(null) }
    LaunchedEffect(showZoom, showPopupSize, showTheme, showAccent, showUpdate, showCatchupTime, showCatchupSources, showCatchupSourceValue, showEpgOffset, showGuideDays, showAnimations, showStartup, showStartupChannelPicker, showAfrWarning, showLivePreviewPanelWarning, showBgImageChooser, showBgPicker, showAmbientGlow, showFocusHighlight, showBgRemote, showNavigation, stageSettings.open) {
        // Only after a popup really closed: on first composition there is no opener, and holding a stale
        // offset for the settle frames bounced the page.
        if (!anyDialogOpen && dialogReturn != null) {
            // Focus back on the opener row, with the scroll offset held still the whole way — see
            // [restoreAfterDialogClose] for why doing those two in sequence made the highlight travel.
            tv.own.owntv.ui.components.restoreAfterDialogClose(dialogReturn, pageScroll, savedScrollPx)
            dialogReturn = null
        }
    }
    val settingsVm: SettingsViewModel = koinViewModel()
    val appIcon by settingsVm.appIcon.collectAsStateWithLifecycle()
    val brandAccent by settingsVm.brandAccentTriangle.collectAsStateWithLifecycle()
    val languageVm: LanguageSettingsViewModel = koinViewModel()
    val currentLocaleTag by languageVm.currentTag.collectAsStateWithLifecycle()
    val languageChip = languageChipText(currentLocaleTag)
    val livePreview by settingsVm.livePreviewEnabled.collectAsStateWithLifecycle()
    val livePreviewPanelActive by settingsVm.livePreviewPanelActive.collectAsStateWithLifecycle()
    val previewAudio by settingsVm.livePreviewAudio.collectAsStateWithLifecycle()
    val hdr by settingsVm.preferSdr.collectAsStateWithLifecycle()
    val autoFrameRate by settingsVm.autoFrameRate.collectAsStateWithLifecycle()
    val surroundMode by settingsVm.surroundMode.collectAsStateWithLifecycle()
    val autoPlayNext by settingsVm.autoPlayNext.collectAsStateWithLifecycle()
    val updateCheckOnStart by settingsVm.updateCheckOnStart.collectAsStateWithLifecycle()
    val channelNumbers by settingsVm.directTune.collectAsStateWithLifecycle()
    val catchupTz by settingsVm.catchupTimezone.collectAsStateWithLifecycle()
    val catchupOffset by settingsVm.catchupOffsetMinutes.collectAsStateWithLifecycle()
    val epgOffset by settingsVm.epgOffsetMinutes.collectAsStateWithLifecycle()
    val guideDays by settingsVm.guideDaysToKeep.collectAsStateWithLifecycle()
    val catchupChannels by settingsVm.catchupChannelCount.collectAsStateWithLifecycle()
    val catchupPlayer by settingsVm.catchupPlayer.collectAsStateWithLifecycle()
    val playlistSources by settingsVm.sources.collectAsStateWithLifecycle()
    val catchupOverrides = playlistSources.count { it.catchupTimezone != null }
    val accent by settingsVm.accent.collectAsStateWithLifecycle()
    val customAccent by settingsVm.customAccent.collectAsStateWithLifecycle()
    val focusHighlight by settingsVm.focusHighlight.collectAsStateWithLifecycle()
    val focusHighlightWidth by settingsVm.focusHighlightWidth.collectAsStateWithLifecycle()
    val bgImagePath by settingsVm.bgImagePath.collectAsStateWithLifecycle()
    val glassConfig by settingsVm.glassConfig.collectAsStateWithLifecycle()
    val glassOn = glassConfig.enabled
    val animationLevel by settingsVm.animationLevel.collectAsStateWithLifecycle()
    val ambientGlowEnabled by settingsVm.ambientGlowEnabled.collectAsStateWithLifecycle()
    val ambientGlowPulse by settingsVm.ambientGlowPulse.collectAsStateWithLifecycle()
    LaunchedEffect(glassOn, themeMode) {
        if (glassOn || themeMode != ThemeMode.DARK) showAmbientGlow = false
    }
    val weatherEnabled by settingsVm.weatherEnabled.collectAsStateWithLifecycle()
    val startupMode by settingsVm.startupMode.collectAsStateWithLifecycle()
    val startupChannel by settingsVm.startupChannel.collectAsStateWithLifecycle()
    val chNavEnabled by settingsVm.chNavEnabled.collectAsStateWithLifecycle()
    val rememberLastLive by settingsVm.rememberLastLive.collectAsStateWithLifecycle()
    val rememberLastMovies by settingsVm.rememberLastMovies.collectAsStateWithLifecycle()
    val rememberLastSeries by settingsVm.rememberLastSeries.collectAsStateWithLifecycle()
    // "Custom" on the Panel Width row as soon as any one of the three sections is switched on.
    val panelWidthLive by settingsVm.panelWidthEnabled.getValue(tv.own.owntv.core.settings.PanelSection.LIVE).collectAsStateWithLifecycle()
    val panelWidthMovies by settingsVm.panelWidthEnabled.getValue(tv.own.owntv.core.settings.PanelSection.MOVIES).collectAsStateWithLifecycle()
    val panelWidthSeries by settingsVm.panelWidthEnabled.getValue(tv.own.owntv.core.settings.PanelSection.SERIES).collectAsStateWithLifecycle()
    val panelWidthCustom = panelWidthLive || panelWidthMovies || panelWidthSeries
    val guideWidthCustom by settingsVm.guideWidthEnabled.collectAsStateWithLifecycle()

    // Auto frame rate is the one toggle that can make the picture visibly worse on the wrong hardware:
    // below Android 12 there is no way to ask the display which refresh rates it can reach without
    // blanking. Turning it on there therefore asks first; turning it off remains immediate.
    val afrNeedsWarning = android.os.Build.VERSION.SDK_INT < android.os.Build.VERSION_CODES.S
    val toggleAutoFrameRate: (FocusRequester) -> Unit = { returnFocus ->
        if (!autoFrameRate && afrNeedsWarning) {
            saveScroll()
            dialogReturn = returnFocus
            showAfrWarning = true
        } else {
            settingsVm.setAutoFrameRate(!autoFrameRate)
        }
    }
    val toggleLivePreview: (FocusRequester) -> Unit = { returnFocus ->
        if (livePreview) {
            settingsVm.setLivePreviewEnabled(false)
        } else if (!livePreviewPanelActive) {
            saveScroll()
            dialogReturn = returnFocus
            showLivePreviewPanelWarning = true
        } else {
            settingsVm.setLivePreviewEnabled(true)
        }
    }

    // Restore focus to the row a sub-screen was opened from when the user navigates back.
    var lastTab by rememberSaveable { mutableStateOf<SettingsTab?>(null) }
    // The Sports hub's Settings button: straight to Settings → Sports; Back lands on its row. After the
    // start effect above (which resets to the root), so this one wins.
    // Built from the enum, not hand-listed. It used to be a literal map of twenty entries, and
    // adding SettingsTab.RECORDING without adding a line to it crashed the whole Settings screen
    // with "Key RECORDING is missing in the map" — a `getValue` on a map that had quietly gone
    // stale. From the entries there is nothing to keep in step. ROOT gets one it never uses, which
    // is cheaper than a list that can be wrong.
    val rowFocus = remember { SettingsTab.entries.associateWith { FocusRequester() } }
    // A search result that jumps to a Video player row: the row to focus on its page.
    var videoRowKey by rememberSaveable { mutableStateOf<String?>(null) }
    // A search result that jumps to a row of the page itself (rule 18): its key until focus has landed on it.
    var jumpRowKey by remember { mutableStateOf<String?>(null) }
    val jumpVideo: (String, Boolean) -> Unit = jump@{ key, _ ->
        // Rows gathered on a page of their own open that page.
        if (key in tv.own.owntv.features.settings.PER_PLAYLIST_KEYS) { searchQuery = ""; searchMode = false; lastTab = SettingsTab.PER_PLAYLIST; videoRowKey = null; tab = SettingsTab.PER_PLAYLIST; return@jump }
        val ref = tv.own.owntv.features.settings.VIDEO_QUICK_ROWS.first { it.key == key }
        videoRowKey = key
        // Leave search too: an empty query while still searching draws no page at all.
        searchQuery = ""; searchMode = false
        // The remembered values sit on System, not in the group their section belongs to.
        selectedGroup = if (key in tv.own.owntv.features.settings.RESET_KEYS) SettingsGroup.APP.ordinal
            else SettingsGroup.of(tv.own.owntv.features.settings.videoGroupOf(ref.section)).ordinal
    }
    val jumpRow: (String) -> Unit = { key -> searchQuery = ""; searchMode = false; lastTab = null; videoRowKey = null; jumpRowKey = key }
    // Between the page and a sub-page the focused row goes with the screen: the focus waits out of sight
    // meanwhile instead of lighting up the menu for a frame (see LocalParkFocus).
    val parkFocus = LocalParkFocus.current
    val open: (SettingsTab) -> Unit = { parkFocus(); lastTab = it; videoRowKey = null; tab = it }
    val toRoot: () -> Unit = { parkFocus(); tab = SettingsTab.ROOT }
    LaunchedEffect(openEpgAdd) {
        if (openEpgAdd) { consumeEpgAdd = true; open(SettingsTab.EPG); onEpgAddConsumed() }
    }

    // Sub-screens sit below the status cluster (StageContentTop), 6 dp in from the edges.
    val sub = modifier.padding(start = 6.dp, end = 6.dp, bottom = 6.dp, top = StageContentTop)
    // A full page's search hands over to Settings search, keyboard up.
    val openSearch: () -> Unit = { parkFocus(); lastTab = null; searchQuery = ""; searchMode = true; searchFromMore = false; searchAutoEdit = true; tab = SettingsTab.ROOT }
    // Mayo TV: the categories down the left (SettingsSidebar). Back from the rows goes to them first, then out.
    val side = remember { SidebarState() }
    if (tab != SettingsTab.ROOT) {
    CompositionLocalProvider(tv.own.owntv.features.settings.LocalSettingsSearch provides openSearch) {
    when (tab) {
        SettingsTab.LANGUAGE -> { LanguageSettingsScreen(onBack = { toRoot() }, modifier = modifier) }
        SettingsTab.SOURCES -> { ManageSourcesScreen(onBack = { toRoot() }, modifier = modifier) }
        SettingsTab.EPG -> { tv.own.owntv.features.settings.EpgSourcesScreen(onBack = { toRoot(); consumeEpgAdd = false }, modifier = modifier, startOnAdd = consumeEpgAdd) }
        // Tabs whose page moved (rule 21: a restored tab lands where its page lives now).
        SettingsTab.BACKUP, SettingsTab.LOCAL_SYNC, SettingsTab.RESETS -> TabRedirect(tab, side, { selectedGroup = it }) { tab = SettingsTab.ROOT }
        SettingsTab.CUSTOMIZE -> { CustomizeScreen(onBack = { toRoot() }, modifier = modifier) }
        SettingsTab.HOME -> { HomeSettingsScreen(onBack = { toRoot() }, modifier = modifier) }
        SettingsTab.NETWORK -> { tv.own.owntv.features.settings.NetworkSettingsScreen(onBack = { toRoot() }, modifier = modifier) }
        SettingsTab.DNS -> { tv.own.owntv.features.settings.DnsSettingsScreen(onBack = { toRoot() }, modifier = modifier) }
        SettingsTab.METADATA -> { tv.own.owntv.features.settings.MetadataSettingsScreen(onBack = { toRoot() }, modifier = modifier) }
        SettingsTab.OPEN_SUBTITLES -> { tv.own.owntv.features.settings.OpenSubtitlesAccountScreen(onBack = { toRoot() }, modifier = modifier) }
        SettingsTab.WEATHER -> { tv.own.owntv.features.settings.WeatherSettingsScreen(onBack = { toRoot() }, modifier = modifier) }
        SettingsTab.CH_NAV -> { tv.own.owntv.features.settings.ChNavSettingsScreen(onBack = { toRoot() }, modifier = modifier) }
        SettingsTab.CONTENT_MENUS -> { tv.own.owntv.features.settings.ContentMenuSettingsScreen(onBack = { toRoot() }, modifier = modifier) }
            SettingsTab.PANEL_WIDTH -> { tv.own.owntv.features.settings.PanelWidthSettingsScreen(onBack = { toRoot() }, modifier = modifier) }
            SettingsTab.GUIDE_WIDTH -> { tv.own.owntv.features.settings.GuideWidthSettingsScreen(onBack = { toRoot() }, modifier = modifier) }
            SettingsTab.GLASS_EFFECT -> { GlassEffectSettingsScreen(onBack = { toRoot() }, modifier = modifier) }
            SettingsTab.SUBTITLE_STYLE -> tv.own.owntv.features.settings.SubtitleStylePage(onBack = { toRoot() }, modifier = modifier)
            SettingsTab.BROWSING -> tv.own.owntv.features.settings.BrowsingListsSettingsScreen(onBack = { toRoot() }, modifier = modifier)
            SettingsTab.SPORTS -> tv.own.owntv.features.sports.SportsSettingsScreen(onBack = { toRoot() }, modifier = modifier)
            // Mayo TV: the gathered pages (1 Nov 2026).
            SettingsTab.PER_PLAYLIST -> tv.own.owntv.features.settings.VideoGatherPage(
                VideoGroup.PER_PLAYLIST, tv.own.owntv.features.settings.PER_PLAYLIST_KEYS, stringResource(R.string.settings_mayo_group_sources),
                onBack = { toRoot() }, modifier = modifier,
            ) { CatchupPerPlaylistRow(settingsVm) }
            SettingsTab.FONTS -> tv.own.owntv.features.settings.FontSettingsScreen(
                current = fontCustomization, onSet = onSetFontCustomization, familyLabel = { fontFamilyLabel(it) },
                onBack = { toRoot() }, modifier = modifier,
            )
            SettingsTab.ROOT -> Unit
    }
    }
    return
    }

    // Only at the root: every sub-screen registers its own handler, which is nested deeper and
    // therefore wins while one is open.
    // It is composed after the search-clearing handler above, and the last enabled one wins,
    // so it stands aside while that one has something to undo first.
    if (onBack != null) BackHandler(enabled = searchQuery.isBlank()) { side.back(onBack) }


    // The root rows, as data (see [RootItem]). Building 40 small objects is a fraction of the cost of
    // composing 40 rows, and it is what lets the list below stay lazy while both focus restores can
    // still find a row that is scrolled out of view.
    val rootItems: List<RootItem> = listOfNotNull(
        RootGroup("group_profile", stringResource(R.string.settings_profile_group), OwnTVIcon.PERSON, stringResource(R.string.settings_group_summary_profile)),
        RootGroup("group_sources", stringResource(R.string.settings_mayo_group_sources), OwnTVIcon.PLAYLIST, stringResource(R.string.settings_group_summary_sources)),
        RootRow(
            tabRowKey(SettingsTab.SOURCES), TileTone.PRIMARY, OwnTVIcon.PLAYLIST,
            heading = stringResource(R.string.settings_sources_title),
            title = stringResource(R.string.settings_playlists), desc = stringResource(R.string.settings_playlists_description),
            focus = rowFocus.getValue(SettingsTab.SOURCES),
            onClick = { open(SettingsTab.SOURCES) },
        ),
        RootRow(
            tabRowKey(SettingsTab.EPG), TileTone.PRIMARY, OwnTVIcon.EPG,
            title = stringResource(R.string.settings_epg_sources), desc = stringResource(R.string.settings_epg_sources_nav_description),
            focus = rowFocus.getValue(SettingsTab.EPG),
            onClick = { open(SettingsTab.EPG) },
        ),
        // Mayo TV: every per-playlist override on one page (players, latency, pre-buffer, give-up time, catch-up).
        if (playlistSources.isNotEmpty()) RootRow(
            tabRowKey(SettingsTab.PER_PLAYLIST), TileTone.SECONDARY, OwnTVIcon.PLAYLIST,
            title = stringResource(R.string.settings_mayo_per_playlist), desc = stringResource(R.string.settings_mayo_per_playlist_line),
            focus = rowFocus.getValue(SettingsTab.PER_PLAYLIST),
            onClick = { open(SettingsTab.PER_PLAYLIST) },
        ) else null,
        // The guide's own settings, under a divider of their own: they tune the guide, they are not
        // sources, and sitting straight under the two source lists they read as if they were.
        RootRow(
            "epg_offset", TileTone.SECONDARY, OwnTVIcon.EPG,
            heading = stringResource(R.string.settings_mayo_heading_guide),
            title = stringResource(R.string.content_epg_time_offset),
            desc = stringResource(R.string.settings_epg_offset_root_description),
            chip = epgShiftLabel(epgOffset),
            chipTone = if (epgOffset == 0) TileTone.SECONDARY else TileTone.PRIMARY,
            focus = epgOffsetRowFocus,
            onClick = { saveScroll(); dialogReturn = epgOffsetRowFocus; showEpgOffset = true },
        ),
        *stageGuideRows(settingsVm, stageSettings) { saveScroll(); dialogReturn = it }.toTypedArray(),
        // How far ahead the guide is stored. Global rather than per-source: it is one horizon that
        // every feed is trimmed to, so it sits beside the other guide-wide setting, not inside a feed.
        RootRow(
            "guide_days", TileTone.SECONDARY, OwnTVIcon.EPG,
            title = stringResource(R.string.settings_epg_guide_days),
            chip = pluralStringResource(R.plurals.settings_epg_guide_days_value, guideDays, guideDays),
            chipTone = TileTone.PRIMARY,
            focus = guideDaysRowFocus,
            onClick = { saveScroll(); dialogReturn = guideDaysRowFocus; showGuideDays = true },
        ),
        RootRow(
            tabRowKey(SettingsTab.CUSTOMIZE), TileTone.PRIMARY, OwnTVIcon.SORT,
            heading = stringResource(R.string.settings_mayo_heading_contents),
            title = stringResource(R.string.settings_customize), desc = stringResource(R.string.settings_customize_nav_description),
            focus = rowFocus.getValue(SettingsTab.CUSTOMIZE),
            onClick = { open(SettingsTab.CUSTOMIZE) },
        ),
        RootRow(
            tabRowKey(SettingsTab.METADATA), TileTone.PRIMARY, OwnTVIcon.IMAGE,
            title = stringResource(R.string.settings_metadata), desc = stringResource(R.string.settings_metadata_root_description),
            focus = rowFocus.getValue(SettingsTab.METADATA),
            onClick = { open(SettingsTab.METADATA) },
        ),
        RootGroup("group_live", stringResource(R.string.settings_mayo_group_live), OwnTVIcon.LIVE_TV, ""),
        // Sits with the EPG offset, not with Playback: both answer "the guide/archive clock is wrong",
        // and a user fixing one almost always looks at the other next.
        RootRow(
            "catchup", TileTone.SECONDARY, OwnTVIcon.CATCHUP,
            heading = stringResource(R.string.settings_mayo_heading_catchup_sport),
            title = stringResource(R.string.settings_catchup),
            desc = if (catchupChannels > 0) pluralStringResource(R.plurals.settings_catchup_supported, catchupChannels, catchupChannels)
                else stringResource(R.string.settings_catchup_unavailable),
            chip = when (catchupTz) {
                SettingsRepository.CatchupTimezone.AUTOMATIC -> stringResource(R.string.settings_catchup_timezone_automatic)
                SettingsRepository.CatchupTimezone.DEVICE -> stringResource(R.string.settings_device)
                SettingsRepository.CatchupTimezone.MANUAL -> utcOffsetLabel(catchupOffset)
            },
            chipTone = TileTone.PRIMARY,
            focus = catchupRowFocus,
            onClick = { saveScroll(); dialogReturn = catchupRowFocus; showCatchupTime = true },
        ),
        RootRow(
            tabRowKey(SettingsTab.SPORTS), TileTone.PRIMARY, OwnTVIcon.SPORTS,
            title = stringResource(R.string.sports_title), desc = stringResource(R.string.sports_leagues),
            focus = rowFocus.getValue(SettingsTab.SPORTS),
            onClick = { open(SettingsTab.SPORTS) },
        ),
        RootGroup("group_playback", stringResource(R.string.settings_mayo_group_playback), OwnTVIcon.PLAY, ""),
        RootGroup("group_picture_sound", stringResource(R.string.settings_mayo_group_picture_sound), OwnTVIcon.VIDEO, ""),
        RootGroup("group_language", stringResource(R.string.settings_mayo_group_language), OwnTVIcon.LANGUAGE, ""),
        RootRow(
            tabRowKey(SettingsTab.LANGUAGE), TileTone.PRIMARY, OwnTVIcon.LANGUAGE,
            title = stringResource(R.string.settings_language),
            desc = stringResource(R.string.settings_language_description),
            chip = languageChip,
            chipTone = TileTone.PRIMARY,
            focus = rowFocus.getValue(SettingsTab.LANGUAGE),
            onClick = { open(SettingsTab.LANGUAGE) },
        ),
        RootRow(
            tabRowKey(SettingsTab.OPEN_SUBTITLES), TileTone.PRIMARY, OwnTVIcon.SUBTITLE,
            title = stringResource(R.string.settings_open_subtitles), desc = stringResource(R.string.settings_open_subtitles_description),
            focus = rowFocus.getValue(SettingsTab.OPEN_SUBTITLES),
            onClick = { open(SettingsTab.OPEN_SUBTITLES) },
        ),
        RootGroup("group_appearance", stringResource(R.string.settings_group_appearance_home), OwnTVIcon.PALETTE, stringResource(R.string.settings_group_summary_appearance)),
        // Home first: its layout and row titles here, everything else about its rows one page further.
        RootRow(
            "home_layout", TileTone.PRIMARY, OwnTVIcon.GRID,
            heading = stringResource(R.string.settings_heading_home),
            title = stringResource(R.string.home_layout_title), desc = stringResource(R.string.home_layout_description),
            // ◀ ▶ (or OK) step through the layouts in place, so the live preview beside it changes as they go
            // by (Jonathan, 4 Nov 2026), as Design does.
            // The chosen one's name only: four segments pushed the row's own title off it.
            value = tv.own.owntv.features.settings.SettingValue.Choice(
                listOf(
                    stringResource(R.string.home_layout_standard),
                    stringResource(R.string.home_layout_classic),
                    stringResource(R.string.home_layout_stage),
                    stringResource(R.string.home_layout_spotlight),
                )[homeLayouts.indexOf(launcherConfig.homeLayout).coerceAtLeast(0)],
            ),
            onStep = { step -> stepHomeLayout(step) },
            focus = homeLayoutRowFocus,
            onClick = { stepHomeLayout(1) },
        ),
        RootRow(
            "home_row_titles", TileTone.SECONDARY, OwnTVIcon.TEXT_SIZE,
            title = stringResource(R.string.home_row_titles), desc = stringResource(R.string.home_row_titles_description),
            value = tv.own.owntv.features.settings.SettingValue.Switch(launcherConfig.rowTitles),
            onClick = { tv.own.owntv.launcher.apps.LauncherPrefs.update(launcherContext) { it.copy(rowTitles = !it.rowTitles) } },
        ),
        RootRow(
            tabRowKey(SettingsTab.HOME), TileTone.SECONDARY, OwnTVIcon.HOME,
            title = stringResource(R.string.settings_home_more), desc = stringResource(R.string.settings_home_more_line),
            focus = rowFocus.getValue(SettingsTab.HOME),
            onClick = { open(SettingsTab.HOME) },
        ),
        RootRow(
            "theme", TileTone.PRIMARY, OwnTVIcon.THEME,
            heading = stringResource(R.string.settings_heading_colors_style),
            title = stringResource(R.string.settings_theme), desc = stringResource(R.string.settings_theme_description),
            chip = themeLabel(themeMode), chipTone = TileTone.PRIMARY,
            focus = themeRowFocus,
            onClick = { saveScroll(); dialogReturn = themeRowFocus; showTheme = true },
        ),
        RootRow(
            "accent", TileTone.SECONDARY, OwnTVIcon.PALETTE,
            title = stringResource(R.string.settings_accent), desc = stringResource(R.string.settings_accent_description),
            chip = if (customAccent.isNotBlank()) customAccent.uppercase() else stringResource(accent.labelRes),
            chipTone = TileTone.SECONDARY,
            focus = accentRowFocus,
            onClick = { saveScroll(); dialogReturn = accentRowFocus; showAccent = true },
        ),
        RootRow(
            "focus_highlight", TileTone.SECONDARY, OwnTVIcon.FOCUS_HIGHLIGHT,
            title = stringResource(R.string.settings_focus_highlight),
            desc = stringResource(R.string.settings_focus_highlight_description),
            chip = focusHighlightChip(focusHighlight, focusHighlightWidth),
            chipTone = TileTone.SECONDARY,
            focus = focusHighlightRowFocus,
            onClick = { saveScroll(); dialogReturn = focusHighlightRowFocus; showFocusHighlight = true },
        ),
        // Glass & background has enough controls to be a page of its own; the root row only summarizes it.
        RootRow(
            tabRowKey(SettingsTab.GLASS_EFFECT), TileTone.PRIMARY, OwnTVIcon.SPARKLE,
            title = stringResource(R.string.settings_glass_bg_title), desc = stringResource(R.string.settings_line_glass_bg),
            chip = glassBackgroundSummary(settingsVm.backgroundConfig.collectAsStateWithLifecycle().value, glassConfig),
            chipTone = if (glassOn) TileTone.PRIMARY else TileTone.SECONDARY,
            focus = rowFocus.getValue(SettingsTab.GLASS_EFFECT),
            onClick = { open(SettingsTab.GLASS_EFFECT) },
        ),
        if (themeMode == ThemeMode.DARK && !glassOn) RootRow(
            "ambient_glow", TileTone.PRIMARY, OwnTVIcon.GLOW,
            title = stringResource(R.string.settings_ambient_glow),
            desc = stringResource(R.string.settings_ambient_glow_description),
            chip = stringResource(if (ambientGlowEnabled) R.string.common_on else R.string.common_off),
            chipTone = if (ambientGlowEnabled) TileTone.PRIMARY else TileTone.SECONDARY,
            focus = ambientGlowRowFocus,
            onClick = { saveScroll(); dialogReturn = ambientGlowRowFocus; showAmbientGlow = true },
        ) else null,
        RootRow(
            "fonts", TileTone.SECONDARY, OwnTVIcon.TEXT_SIZE,
            heading = stringResource(R.string.settings_mayo_heading_size),
            title = stringResource(R.string.settings_font_customization),
            desc = stringResource(R.string.settings_font_customization_description),
            chip = stringResource(R.string.common_percent, fontCustomization.sizePercent),
            chipTone = TileTone.SECONDARY,
            focus = rowFocus.getValue(SettingsTab.FONTS),
            onClick = { open(SettingsTab.FONTS) },
        ),
        RootRow(
            "popup_size", TileTone.SECONDARY, OwnTVIcon.ZOOM,
            title = stringResource(R.string.settings_popup_size),
            desc = stringResource(R.string.settings_popup_size_description),
            chip = stringResource(R.string.common_percent, fontCustomization.popupSizePercent),
            chipTone = TileTone.SECONDARY,
            focus = popupSizeRowFocus,
            onClick = { saveScroll(); dialogReturn = popupSizeRowFocus; showPopupSize = true },
        ),
        RootRow(
            "ui_zoom", TileTone.SECONDARY, OwnTVIcon.ZOOM,
            title = stringResource(R.string.settings_ui_zoom), desc = stringResource(R.string.settings_ui_zoom_description),
            chip = stringResource(R.string.common_percent, uiZoomPercent), chipTone = TileTone.SECONDARY,
            focus = zoomRowFocus,
            onClick = { saveScroll(); dialogReturn = zoomRowFocus; showZoom = true },
        ),
        RootRow(
            "animations", TileTone.SECONDARY, OwnTVIcon.MOTION,
            title = stringResource(R.string.settings_animations), desc = stringResource(R.string.settings_animations_description),
            chip = stringResource(animationLevel.labelRes), chipTone = TileTone.SECONDARY,
            focus = animationsRowFocus,
            onClick = { saveScroll(); dialogReturn = animationsRowFocus; showAnimations = true },
        ),
        RootRow(
            tabRowKey(SettingsTab.WEATHER), TileTone.SECONDARY, OwnTVIcon.WEATHER,
            heading = stringResource(R.string.settings_heading_clock_weather),
            title = stringResource(R.string.settings_weather),
            desc = stringResource(R.string.settings_weather_description_root),
            chip = if (weatherEnabled) stringResource(R.string.common_on) else stringResource(R.string.common_off),
            chipTone = if (weatherEnabled) TileTone.PRIMARY else TileTone.SECONDARY,
            focus = rowFocus.getValue(SettingsTab.WEATHER),
            onClick = { open(SettingsTab.WEATHER) },
        ),
        RootRow(
            tabRowKey(SettingsTab.PANEL_WIDTH), TileTone.PRIMARY, OwnTVIcon.PANEL_WIDTH,
            heading = stringResource(R.string.settings_heading_widths),
            title = stringResource(R.string.settings_panel_width),
            desc = stringResource(R.string.settings_panel_width_description),
            chip = if (panelWidthCustom) stringResource(R.string.settings_live_latency_custom) else stringResource(R.string.settings_subtitle_default),
            chipTone = if (panelWidthCustom) TileTone.PRIMARY else TileTone.SECONDARY,
            focus = rowFocus.getValue(SettingsTab.PANEL_WIDTH),
            onClick = { open(SettingsTab.PANEL_WIDTH) },
        ),
        RootRow(
            tabRowKey(SettingsTab.GUIDE_WIDTH), TileTone.PRIMARY, OwnTVIcon.EPG,
            title = stringResource(R.string.settings_guide_width),
            desc = stringResource(R.string.settings_guide_width_description),
            chip = if (guideWidthCustom) stringResource(R.string.settings_live_latency_custom) else stringResource(R.string.settings_subtitle_default),
            chipTone = if (guideWidthCustom) TileTone.PRIMARY else TileTone.SECONDARY,
            focus = rowFocus.getValue(SettingsTab.GUIDE_WIDTH),
            onClick = { open(SettingsTab.GUIDE_WIDTH) },
        ),
        // Controls (Jonathan, 4 Nov 2026): how Mayo TV is worked, out of Appearance.
        RootGroup("group_controls", stringResource(R.string.settings_group_controls), OwnTVIcon.MENU, ""),
        navigationRootRow(settingsVm, navigationRowFocus) { saveScroll(); dialogReturn = navigationRowFocus; showNavigation = true }
            .copy(heading = stringResource(R.string.settings_heading_sidebar)),
        RootRow(
            "sidebar_apps", TileTone.SECONDARY, OwnTVIcon.GRID,
            title = stringResource(R.string.launcher_apps_menu), desc = stringResource(R.string.launcher_apps_menu_line),
            value = tv.own.owntv.features.settings.SettingValue.Switch(launcherConfig.appsInMenu),
            onClick = { tv.own.owntv.launcher.apps.LauncherPrefs.update(launcherContext) { it.copy(appsInMenu = !it.appsInMenu) } },
        ),
        RootRow(
            tabRowKey(SettingsTab.CH_NAV), TileTone.PRIMARY, OwnTVIcon.CH_NAV,
            heading = stringResource(R.string.settings_heading_remote),
            title = stringResource(R.string.settings_ch_paging), desc = stringResource(R.string.settings_ch_paging_description),
            chip = if (chNavEnabled) stringResource(R.string.common_on) else stringResource(R.string.common_off),
            chipTone = if (chNavEnabled) TileTone.PRIMARY else TileTone.SECONDARY,
            focus = rowFocus.getValue(SettingsTab.CH_NAV),
            onClick = { open(SettingsTab.CH_NAV) },
        ),
        RootRow(
            "browsing_lists", TileTone.PRIMARY, OwnTVIcon.LIST_GRID,
            heading = stringResource(R.string.settings_heading_menus_lists),
            title = stringResource(R.string.settings_browsing_lists), desc = stringResource(R.string.settings_browsing_description),
            chevron = true,
            focus = rowFocus.getValue(SettingsTab.BROWSING),
            onClick = { open(SettingsTab.BROWSING) },
        ),
        RootRow(
            tabRowKey(SettingsTab.CONTENT_MENUS), TileTone.PRIMARY, OwnTVIcon.MENU,
            title = stringResource(R.string.settings_content_menus_title),
            desc = stringResource(R.string.settings_content_menus_description),
            focus = rowFocus.getValue(SettingsTab.CONTENT_MENUS),
            onClick = { open(SettingsTab.CONTENT_MENUS) },
        ),
        // About and the error log live in More. A page of facts and a log are
        // not preferences; both are More rows now, opening the very same dialogs.
        // Mayo TV as the TV's start screen. LauncherSettingsRows draws the group; these two rows sit inside it.
        RootGroup("group_homescreen", stringResource(R.string.launcher_settings_group), OwnTVIcon.POWER, ""),
        RootRow(
            "app_startup", TileTone.SECONDARY, OwnTVIcon.POWER,
            title = stringResource(R.string.settings_app_startup), desc = stringResource(R.string.settings_app_startup_description),
            chip = if (startupMode == tv.own.owntv.core.settings.StartupMode.SPECIFIC_CHANNEL) {
                startupChannel?.name ?: startupLabel(startupMode)
            } else startupLabel(startupMode),
            chipTone = TileTone.PRIMARY,
            focus = startupRowFocus,
            onClick = { saveScroll(); dialogReturn = startupRowFocus; showStartup = true },
        ),
        // No "Data" group: Backup and Local sync are places, not preferences,
        // and are More rows now; Clear history moved onto the History screen it acts on; the download
        // folder moved to the Downloads screen. With all four gone the group had nothing left in it.
        RootGroup("group_app", stringResource(R.string.settings_cleanup_group_system), OwnTVIcon.GEAR, ""),
        // System: Updates and Network here; the remembered values and Diagnostics are drawn after them.
        *systemRootRows(settingsVm, updateCheckOnStart, updateRowFocus, rowFocus, open) { saveScroll(); dialogReturn = updateRowFocus; showUpdate = true }.toTypedArray(),
    )

    // --- Two-pane root: the flat list above is the single source of truth for icon, chip and click of every
    // row. Here it is only gathered by group marker into (group, its rows); the order of the groups is
    // SettingsGroup's, not the markers' (see [categoriesOf]).
    val categories: List<Pair<RootGroup, List<RootRow>>> = remember(rootItems) { categoriesOf(rootItems) }
    /** Which category each row key lives in, so a Back from a sub-screen or a search jump can reselect its group. */
    val groupOfKey: Map<String, Int> = remember(categories) {
        buildMap { categories.forEachIndexed { g, (_, rows) -> rows.forEach { put(it.key, g) } } }
    }
    val selectedRows = categories.getOrNull(selectedGroup)?.second.orEmpty()
    val jumpRowFocus = remember { FocusRequester() }
    RowJumpEffect(jumpRowKey, jumpRowKey?.let(groupOfKey::get), jumpRowFocus, onGroup = { side.more = null; selectedGroup = it }) { jumpRowKey = null }
    // A genuinely new group starts at its top. Recreating the page after a sub-screen does not:
    // its group and scroll were kept above the sub-screen dispatch, so leave them untouched.
    LaunchedEffect(selectedGroup) {
        if (displayedGroup != selectedGroup) {
            runCatching { pageScroll.scrollTo(0) }
            displayedGroup = selectedGroup
            // A category picked by moving through the list keeps focus in the list.
            if (videoRowKey == null && jumpRowKey == null && !side.fromSidebar) for (attempt in 0 until 10) { withFrameNanos { }; if (runCatching { rowsFocus.requestFocus() }.getOrDefault(false)) break }
        }
        side.fromSidebar = false
    }

    // Restore focus to the row a sub-screen was opened from when the user navigates back. This block
    // only exists while the page is showing, so coming back from a sub-screen is exactly when it runs.
    val returningRowFocus = when {
        searchQuery.isNotBlank() && lastTab != null -> searchFieldFocus
        else -> lastTab?.let { rowFocus[it] }
    }
    LaunchedEffect(Unit) {
        val key = lastTab?.let(::tabRowKey)
        val target = returningRowFocus ?: return@LaunchedEffect
        val group = key?.let(groupOfKey::get)
        if (searchQuery.isBlank() && group != null) selectedGroup = group
        // Subtitle appearance's row sits in the Sound & subtitles rows, below the fold: the page comes
        // back at the top, so put the offset it had back while focus lands.
        if (lastTab == SettingsTab.SUBTITLE_STYLE && searchQuery.isBlank()) {
            tv.own.owntv.ui.components.restoreAfterDialogClose(target, pageScroll, savedScrollPx)
            lastTab = null
            return@LaunchedEffect
        }
        // By frames: the page may need a layout pass once its group is selected; once focus lands,
        // hold it a few frames so a late entry cannot move it.
        var settledFrames = 0
        repeat(10) {
            withFrameNanos { }
            if (runCatching { target.requestFocus() }.getOrDefault(false)) {
                settledFrames++
                if (settledFrames >= 3) {
                    lastTab = null
                    return@LaunchedEffect
                }
            } else {
                settledFrames = 0
            }
        }
    }

    // A fresh entry from More: the page's first row (a search start focuses the field instead).
    if (onBack != null) {
        LaunchedEffect(Unit) {
            if (returningRowFocus != null || start?.search == true) return@LaunchedEffect
            // Mayo TV: a fresh entry lands on the open category in the list on the left.
            repeat(10) {
                withFrameNanos { }
                if (runCatching { side.focus.requestFocus() }.getOrDefault(false)) return@LaunchedEffect
            }
        }
    }

        // Search results — flat, group-context-prefixed rows ("Appearance › Theme").
        // Dialog-opening entries return focus to the search field on close (their normal row
        // isn't composed while searching). Toggle entries keep the results visible so the chip
        // updates live.
        //
        // Settings under a sub-heading name it too ("Sound & subtitles › Languages & subtitles"),
        // built from the same format string the row itself uses, so the separator and its spacing
        // stay translated rather than hard-coded here.
    val searchResults: List<SettingsSearchEntry> = if (searchQuery.isBlank()) emptyList() else {
        val entries = listOfNotNull(
            SettingsSearchEntry(stringResource(R.string.settings_mayo_group_language), stringResource(R.string.settings_language), stringResource(R.string.settings_search_keywords_language), OwnTVIcon.LANGUAGE, TileTone.PRIMARY,
                chip = languageChip, chipTone = TileTone.PRIMARY) { open(SettingsTab.LANGUAGE) },
            SettingsSearchEntry(stringResource(R.string.more_group_your_things), stringResource(R.string.profiles_title), stringResource(R.string.settings_search_keywords_profiles), OwnTVIcon.PERSON, TileTone.SECONDARY) { searchQuery = ""; searchMode = false; side.more = null; selectedGroup = SettingsGroup.PROFILE.ordinal },
            SettingsSearchEntry(stringResource(R.string.settings_mayo_group_sources), stringResource(R.string.settings_playlists), stringResource(R.string.settings_search_keywords_playlists), OwnTVIcon.PLAYLIST, TileTone.PRIMARY) { open(SettingsTab.SOURCES) },
            SettingsSearchEntry(stringResource(R.string.settings_mayo_group_sources), stringResource(R.string.settings_epg_sources), stringResource(R.string.settings_search_keywords_epg), OwnTVIcon.EPG, TileTone.PRIMARY) { open(SettingsTab.EPG) },
            SettingsSearchEntry(stringResource(R.string.settings_mayo_group_sources), stringResource(R.string.content_epg_time_offset), stringResource(R.string.settings_search_keywords_epg_offset), OwnTVIcon.EPG, TileTone.SECONDARY,
                chip = epgShiftLabel(epgOffset), chipTone = if (epgOffset == 0) TileTone.SECONDARY else TileTone.PRIMARY) { saveScroll(); dialogReturn = searchFieldFocus; showEpgOffset = true },
            SettingsSearchEntry(stringResource(R.string.settings_mayo_group_sources), stringResource(R.string.settings_epg_guide_days), stringResource(R.string.settings_search_keywords_epg), OwnTVIcon.EPG, TileTone.SECONDARY,
                chip = pluralStringResource(R.plurals.settings_epg_guide_days_value, guideDays, guideDays), chipTone = TileTone.PRIMARY) { saveScroll(); dialogReturn = searchFieldFocus; showGuideDays = true },
            SettingsSearchEntry(stringResource(R.string.settings_mayo_group_sources), stringResource(R.string.settings_search_guide_logos), stringResource(R.string.settings_search_keywords_logos), OwnTVIcon.EPG, TileTone.SECONDARY) { open(SettingsTab.EPG) },
            SettingsSearchEntry(stringResource(R.string.settings_mayo_group_sources), stringResource(R.string.settings_customize), stringResource(R.string.settings_search_keywords_customize), OwnTVIcon.SORT, TileTone.PRIMARY) { open(SettingsTab.CUSTOMIZE) },
            navigationSearchEntry(settingsVm) { saveScroll(); dialogReturn = searchFieldFocus; showNavigation = true },
            SettingsSearchEntry(stringResource(R.string.settings_mayo_group_sources), stringResource(R.string.settings_reminders), stringResource(R.string.settings_search_keywords_reminders), OwnTVIcon.BELL, TileTone.SECONDARY) { saveScroll(); dialogReturn = searchFieldFocus; stageSettings.open = StageSettingsDialog.REMINDERS },
            SettingsSearchEntry(stringResource(R.string.settings_group_controls), stringResource(R.string.settings_ch_paging), stringResource(R.string.settings_search_keywords_ch), OwnTVIcon.CH_NAV, TileTone.PRIMARY,
                chip = if (chNavEnabled) stringResource(R.string.common_on) else stringResource(R.string.common_off), chipTone = if (chNavEnabled) TileTone.PRIMARY else TileTone.SECONDARY) { open(SettingsTab.CH_NAV) },
            SettingsSearchEntry(stringResource(R.string.settings_group_appearance_home), stringResource(R.string.settings_panel_width), stringResource(R.string.settings_search_keywords_panel_width), OwnTVIcon.PANEL_WIDTH, TileTone.PRIMARY,
                chip = if (panelWidthCustom) stringResource(R.string.settings_live_latency_custom) else stringResource(R.string.settings_subtitle_default), chipTone = if (panelWidthCustom) TileTone.PRIMARY else TileTone.SECONDARY) { open(SettingsTab.PANEL_WIDTH) },
        SettingsSearchEntry(stringResource(R.string.settings_group_appearance_home), stringResource(R.string.settings_guide_width), stringResource(R.string.settings_search_keywords_guide_width), OwnTVIcon.EPG, TileTone.PRIMARY,
            chip = if (guideWidthCustom) stringResource(R.string.settings_live_latency_custom) else stringResource(R.string.settings_subtitle_default), chipTone = if (guideWidthCustom) TileTone.PRIMARY else TileTone.SECONDARY) { open(SettingsTab.GUIDE_WIDTH) },
        SettingsSearchEntry(stringResource(R.string.settings_group_controls), stringResource(R.string.settings_browsing_lists), stringResource(R.string.settings_search_keywords_browsing), OwnTVIcon.LIST_GRID, TileTone.PRIMARY) { open(SettingsTab.BROWSING) },
            SettingsSearchEntry(stringResource(R.string.settings_group_appearance_home), stringResource(R.string.settings_home_more), stringResource(R.string.settings_search_keywords_home), OwnTVIcon.HOME, TileTone.SECONDARY) { open(SettingsTab.HOME) },
            SettingsSearchEntry(stringResource(R.string.settings_group_appearance_home), stringResource(R.string.home_layout_title), stringResource(R.string.settings_search_keywords_home), OwnTVIcon.GRID, TileTone.PRIMARY) { saveScroll(); dialogReturn = searchFieldFocus; showHomeLayout = true },
            SettingsSearchEntry(stringResource(R.string.settings_group_appearance_home), stringResource(R.string.home_row_titles), stringResource(R.string.settings_search_keywords_home), OwnTVIcon.TEXT_SIZE, TileTone.SECONDARY) { searchQuery = ""; searchMode = false; selectedGroup = SettingsGroup.APPEARANCE.ordinal },
            SettingsSearchEntry(stringResource(R.string.settings_group_controls), stringResource(R.string.launcher_apps_menu), stringResource(R.string.settings_search_keywords_home), OwnTVIcon.GRID, TileTone.SECONDARY) { searchQuery = ""; searchMode = false; selectedGroup = SettingsGroup.CONTROLS.ordinal },
            SettingsSearchEntry(stringResource(R.string.settings_mayo_group_sources), stringResource(R.string.settings_metadata), stringResource(R.string.settings_search_keywords_metadata), OwnTVIcon.IMAGE, TileTone.PRIMARY) { open(SettingsTab.METADATA) },
            // No entries for the download folder, Backup, Local sync or Clear history. They
            // are not in Settings any more, and a result for something that is not here is a lie
            // about where it lives. The no-results state deliberately says nothing else either.
            SettingsSearchEntry(stringResource(R.string.settings_group_appearance_home), stringResource(R.string.settings_theme), stringResource(R.string.settings_search_keywords_theme), OwnTVIcon.THEME, TileTone.PRIMARY,
                chip = themeLabel(themeMode)) { saveScroll(); dialogReturn = searchFieldFocus; showTheme = true },
            SettingsSearchEntry(stringResource(R.string.settings_group_appearance_home), stringResource(R.string.settings_accent), stringResource(R.string.settings_search_keywords_accent), OwnTVIcon.PALETTE, TileTone.SECONDARY,
                chip = if (customAccent.isNotBlank()) customAccent.uppercase() else stringResource(accent.labelRes), chipTone = TileTone.SECONDARY) { saveScroll(); dialogReturn = searchFieldFocus; showAccent = true },
            SettingsSearchEntry(stringResource(R.string.settings_group_appearance_home), stringResource(R.string.settings_focus_highlight), stringResource(R.string.settings_search_keywords_focus), OwnTVIcon.FOCUS_HIGHLIGHT, TileTone.SECONDARY,
                chip = focusHighlightChip(focusHighlight, focusHighlightWidth), chipTone = TileTone.SECONDARY) { saveScroll(); dialogReturn = searchFieldFocus; showFocusHighlight = true },
            if (themeMode == ThemeMode.DARK && !glassOn) SettingsSearchEntry(stringResource(R.string.settings_group_appearance_home), stringResource(R.string.settings_ambient_glow), stringResource(R.string.settings_ambient_glow_description), OwnTVIcon.GLOW, TileTone.PRIMARY,
                chip = stringResource(if (ambientGlowEnabled) R.string.common_on else R.string.common_off), chipTone = if (ambientGlowEnabled) TileTone.PRIMARY else TileTone.SECONDARY) { saveScroll(); dialogReturn = searchFieldFocus; showAmbientGlow = true } else null,
        SettingsSearchEntry(
            stringResource(R.string.settings_group_appearance_home),
            stringResource(R.string.settings_font_customization),
                stringResource(R.string.settings_search_keywords_fonts),
                OwnTVIcon.TEXT_SIZE,
                TileTone.SECONDARY,
                chip = stringResource(R.string.common_percent, fontCustomization.sizePercent),
            chipTone = TileTone.SECONDARY,
        ) { open(SettingsTab.FONTS) },
        SettingsSearchEntry(
            stringResource(R.string.settings_group_appearance_home),
            stringResource(R.string.settings_popup_size),
            stringResource(R.string.settings_popup_size_description),
            OwnTVIcon.ZOOM,
            TileTone.SECONDARY,
            chip = stringResource(R.string.common_percent, fontCustomization.popupSizePercent),
            chipTone = TileTone.SECONDARY,
        ) { saveScroll(); dialogReturn = searchFieldFocus; showPopupSize = true },
        SettingsSearchEntry(stringResource(R.string.settings_group_appearance_home), stringResource(R.string.settings_ui_zoom), stringResource(R.string.settings_search_keywords_zoom), OwnTVIcon.ZOOM, TileTone.SECONDARY,
                chip = stringResource(R.string.common_percent, uiZoomPercent), chipTone = TileTone.SECONDARY) { saveScroll(); dialogReturn = searchFieldFocus; showZoom = true },
            SettingsSearchEntry(stringResource(R.string.settings_group_appearance_home), stringResource(R.string.settings_animations), stringResource(R.string.settings_search_keywords_animation), OwnTVIcon.MOTION, TileTone.SECONDARY,
                chip = stringResource(animationLevel.labelRes), chipTone = TileTone.SECONDARY) { saveScroll(); dialogReturn = searchFieldFocus; showAnimations = true },
            SettingsSearchEntry(stringResource(R.string.settings_group_appearance_home), stringResource(R.string.settings_weather), stringResource(R.string.settings_search_keywords_weather), OwnTVIcon.WEATHER, TileTone.SECONDARY,
                chip = if (weatherEnabled) stringResource(R.string.common_on) else stringResource(R.string.common_off), chipTone = if (weatherEnabled) TileTone.PRIMARY else TileTone.SECONDARY) { open(SettingsTab.WEATHER) },
            SettingsSearchEntry(tv.own.owntv.features.settings.videoRowPath("vp_live_preview"), stringResource(R.string.settings_quick_live_preview), stringResource(R.string.settings_search_keywords_live_preview), OwnTVIcon.LIVE_TV, TileTone.TERTIARY,
                chip = if (livePreview) stringResource(R.string.common_on) else stringResource(R.string.common_off), chipTone = if (livePreview) TileTone.PRIMARY else TileTone.SECONDARY, showChevron = false) { toggleLivePreview(searchFieldFocus) },
            SettingsSearchEntry(tv.own.owntv.features.settings.videoRowPath("vp_preview_audio"), stringResource(R.string.settings_preview_audio), stringResource(R.string.settings_search_keywords_sound), OwnTVIcon.AUDIO, TileTone.SECONDARY,
                chip = if (previewAudio) stringResource(R.string.common_on) else stringResource(R.string.common_off), chipTone = if (previewAudio) TileTone.PRIMARY else TileTone.SECONDARY, showChevron = false) { settingsVm.setLivePreviewAudio(!previewAudio) },
            SettingsSearchEntry(tv.own.owntv.features.settings.videoRowPath("vp_channel_numbers"), stringResource(R.string.settings_quick_channel_numbers), stringResource(R.string.settings_search_keywords_channel_numbers), OwnTVIcon.LIVE_TV, TileTone.PRIMARY,
                chip = if (channelNumbers) stringResource(R.string.common_on) else stringResource(R.string.common_off), chipTone = if (channelNumbers) TileTone.PRIMARY else TileTone.SECONDARY, showChevron = false) { settingsVm.setDirectTune(!channelNumbers) },
            SettingsSearchEntry(tv.own.owntv.features.settings.videoRowPath("vp_hdr"), stringResource(R.string.settings_quick_prefer_sdr), stringResource(R.string.settings_search_keywords_prefer_sdr), OwnTVIcon.VIDEO, TileTone.PRIMARY,
                chip = if (hdr) stringResource(R.string.common_on) else stringResource(R.string.common_off), chipTone = if (hdr) TileTone.PRIMARY else TileTone.SECONDARY, showChevron = false) { settingsVm.setPreferSdr(!hdr) },
            SettingsSearchEntry(tv.own.owntv.features.settings.videoRowPath("vp_afr"), stringResource(R.string.settings_auto_frame_rate), stringResource(R.string.settings_search_keywords_afr), OwnTVIcon.VIDEO, TileTone.PRIMARY,
                chip = if (autoFrameRate) stringResource(R.string.common_on) else stringResource(R.string.common_off), chipTone = if (autoFrameRate) TileTone.PRIMARY else TileTone.SECONDARY, showChevron = false) { toggleAutoFrameRate(searchFieldFocus) },
            SettingsSearchEntry(tv.own.owntv.features.settings.videoRowPath("vp_surround"), stringResource(R.string.settings_surround_sound), stringResource(R.string.settings_search_keywords_surround), OwnTVIcon.AUDIO, TileTone.SECONDARY,
                chip = surroundModeLabel(surroundMode), chipTone = if (surroundMode == SurroundMode.STEREO) TileTone.SECONDARY else TileTone.PRIMARY, showChevron = false) { settingsVm.cycleSurroundMode() },
            SettingsSearchEntry(tv.own.owntv.features.settings.videoRowPath("vp_autoplay"), stringResource(R.string.settings_autoplay_next), stringResource(R.string.settings_search_keywords_autoplay), OwnTVIcon.AUTOPLAY_NEXT, TileTone.SECONDARY,
                chip = if (autoPlayNext) stringResource(R.string.common_on) else stringResource(R.string.common_off), chipTone = if (autoPlayNext) TileTone.PRIMARY else TileTone.SECONDARY, showChevron = false) { settingsVm.setAutoPlayNext(!autoPlayNext) },
            SettingsSearchEntry(stringResource(R.string.settings_mayo_group_live), stringResource(R.string.settings_catchup), stringResource(R.string.settings_search_keywords_catchup), OwnTVIcon.CATCHUP, TileTone.SECONDARY,
                chip = when (catchupTz) {
                    SettingsRepository.CatchupTimezone.AUTOMATIC -> stringResource(R.string.settings_catchup_timezone_automatic)
                    SettingsRepository.CatchupTimezone.DEVICE -> stringResource(R.string.settings_device)
                    SettingsRepository.CatchupTimezone.MANUAL -> utcOffsetLabel(catchupOffset)
                }) { saveScroll(); dialogReturn = searchFieldFocus; showCatchupTime = true },
            if (playlistSources.isNotEmpty()) SettingsSearchEntry(stringResource(R.string.settings_breadcrumb, stringResource(R.string.settings_mayo_group_sources), stringResource(R.string.settings_mayo_per_playlist)), stringResource(R.string.settings_catchup_timezone_per_playlist), stringResource(R.string.settings_search_keywords_catchup), OwnTVIcon.CATCHUP, TileTone.SECONDARY,
                chip = if (catchupOverrides == 0) stringResource(R.string.common_off)
                    else pluralStringResource(R.plurals.settings_live_preroll_overrides, catchupOverrides, catchupOverrides),
                chipTone = if (catchupOverrides > 0) TileTone.PRIMARY else TileTone.SECONDARY) { saveScroll(); dialogReturn = searchFieldFocus; showCatchupSources = true } else null,
            // Four screens that had no entry at all, so nothing on them could be found by name.
            SettingsSearchEntry(stringResource(R.string.settings_mayo_group_playback), stringResource(R.string.recording_settings_group), stringResource(R.string.settings_search_keywords_recording), OwnTVIcon.LIVE_TV, TileTone.TERTIARY) { searchQuery = ""; searchMode = false; selectedGroup = SettingsGroup.PLAYBACK.ordinal },
            SettingsSearchEntry(stringResource(R.string.settings_mayo_group_language), stringResource(R.string.settings_open_subtitles), stringResource(R.string.settings_search_keywords_subtitle_appearance), OwnTVIcon.SUBTITLE, TileTone.PRIMARY) { open(SettingsTab.OPEN_SUBTITLES) },
            SettingsSearchEntry(stringResource(R.string.settings_group_appearance_home), stringResource(R.string.settings_glass_bg_title), stringResource(R.string.settings_search_keywords_glass), OwnTVIcon.SPARKLE, TileTone.PRIMARY,
                chip = glassBackgroundSummary(settingsVm.backgroundConfig.collectAsStateWithLifecycle().value, glassConfig), chipTone = if (glassOn) TileTone.PRIMARY else TileTone.SECONDARY) { open(SettingsTab.GLASS_EFFECT) },
            SettingsSearchEntry(stringResource(R.string.settings_group_controls), stringResource(R.string.settings_content_menus_title), stringResource(R.string.settings_search_keywords_customize), OwnTVIcon.MENU, TileTone.PRIMARY) { open(SettingsTab.CONTENT_MENUS) },
            SettingsSearchEntry(tv.own.owntv.features.settings.videoRowPath("vp_sub_style"), stringResource(R.string.settings_subtitle_appearance), stringResource(R.string.settings_search_keywords_subtitle_appearance), OwnTVIcon.SUBTITLE, TileTone.TERTIARY) { open(SettingsTab.SUBTITLE_STYLE) },
            SettingsSearchEntry(tv.own.owntv.features.settings.videoRowPath("vp_live_latency"), stringResource(R.string.settings_live_latency), stringResource(R.string.settings_search_keywords_latency), OwnTVIcon.LIVE_TV, TileTone.TERTIARY) { jumpVideo("vp_live_latency", false) },
            SettingsSearchEntry(tv.own.owntv.features.settings.videoRowPath("vp_preroll"), stringResource(R.string.settings_live_preroll), stringResource(R.string.settings_search_keywords_live_preroll), OwnTVIcon.LIVE_TV, TileTone.TERTIARY) { jumpVideo("vp_preroll", false) },
            SettingsSearchEntry(tv.own.owntv.features.settings.videoRowPath("vp_logging"), stringResource(R.string.settings_detailed_playback_logging), stringResource(R.string.settings_search_keywords_detailed_logging), OwnTVIcon.INFO, TileTone.SECONDARY) { jumpVideo("vp_logging", false) },
            // Mayo TV's gathering pages.
            if (playlistSources.isNotEmpty()) SettingsSearchEntry(stringResource(R.string.settings_mayo_group_sources), stringResource(R.string.settings_mayo_per_playlist), stringResource(R.string.settings_mayo_per_playlist_line), OwnTVIcon.PLAYLIST, TileTone.SECONDARY) { open(SettingsTab.PER_PLAYLIST) } else null,
            // System's own rows and More's places (kept out of line: this function is at the JVM size limit).
            *systemSearchEntries(jumpRow).toTypedArray(),
            *placeSearchEntries(more?.extras.orEmpty()) { key -> searchQuery = ""; searchMode = false; side.more = key; side.pendingPlace = true }.toTypedArray(),
            SettingsSearchEntry(stringResource(R.string.settings_group_app), stringResource(R.string.sports_title), stringResource(R.string.sports_leagues), OwnTVIcon.SPORTS, TileTone.PRIMARY) { open(SettingsTab.SPORTS) },
            // Settings inside a sub-screen, by their own names (kept out of line: this function is at the JVM size limit).
            *subScreenSearchEntries { t -> open(t) }.toTypedArray(),
            SettingsSearchEntry(stringResource(R.string.settings_group_app), stringResource(R.string.settings_app_startup), stringResource(R.string.settings_search_keywords_startup), OwnTVIcon.POWER, TileTone.SECONDARY,
                chip = startupLabel(startupMode)) { saveScroll(); dialogReturn = searchFieldFocus; showStartup = true },
        )
        // Rows that already have a bespoke entry above. Those are richer than a generic one — they flip
        // in place, or carry a confirmation the search field has to own — so the catalogue skips them
        // rather than listing them a second time.
        val bespokeVideoKeys = setOf(
            "vp_live_preview", "vp_preview_audio", "vp_channel_numbers", "vp_hdr", "vp_afr",
            "vp_surround", "vp_autoplay", "vp_sub_style", "vp_live_latency", "vp_preroll", "vp_logging",
        )
        // …and every OTHER player-group row, taken straight from the catalogue those groups already draw
        // from. Twenty-four settings had no entry of any kind — Multiview, the engine pickers, the seek
        // and volume steps, the language preferences, the per-playlist overrides — so searching for them
        // by name found nothing, on a screen whose own header offers to search "every setting". Deriving
        // them means a row added there is searchable the day it exists, instead of the day someone
        // remembers to type it out here a second time.
        val videoKeywords = stringResource(R.string.settings_search_keywords_video)
        // Volume, audio sync and audio language: "lip sync" or "loud" is what someone types for these.
        val audioKeywords = stringResource(R.string.settings_search_keywords_audio)
        fun jumpTo(ref: tv.own.owntv.features.settings.VideoQuickRef): () -> Unit = { lastTab = null; jumpVideo(ref.key, false) }
        val videoEntries = tv.own.owntv.features.settings.VIDEO_QUICK_ROWS
            .filterNot { it.key in bespokeVideoKeys }
            .map { ref ->
                // The same binding Quick uses, so a result shows the row's live value and a plain toggle
                // flips right here in the results instead of sending the user to the screen to do it.
                val binding = androidx.compose.runtime.key(ref.key) {
                    tv.own.owntv.features.settings.videoQuickBinding(ref.key, settingsVm)
                }
                val jump = jumpTo(ref)
                SettingsSearchEntry(
                    tv.own.owntv.features.settings.videoRowPath(ref.key),
                    stringResource(ref.titleRes),
                    if (ref.section == tv.own.owntv.features.settings.SECTION_SOUND || ref.key == "vp_audio_lang") audioKeywords else videoKeywords,
                    ref.icon,
                    TileTone.TERTIARY,
                    chip = binding?.chip,
                    chipTone = if (binding?.primaryChip == true) TileTone.PRIMARY else TileTone.SECONDARY,
                    showChevron = binding?.onToggle == null,
                    onClick = binding?.onToggle ?: jump,
                )
            }
        // The rows inside hand-written screens and popups, by their own titles — before, only the
        // screen's name found them ("font" found nothing, although the subtitle popup has a Font row).
        // Each list sits beside its screen's rows. The result opens the screen that holds the row.
        @Composable fun rowsOf(group: String, titles: List<Int>, keywords: Int, icon: OwnTVIcon, onClick: () -> Unit) =
            titles.map { SettingsSearchEntry(group, stringResource(it), stringResource(keywords), icon, TileTone.TERTIARY, onClick = onClick) }
        val subStyleRef = tv.own.owntv.features.settings.VIDEO_QUICK_ROWS.first { it.key == "vp_sub_style" }
        val screenRowEntries =
            rowsOf(stringResource(R.string.settings_breadcrumb, stringResource(R.string.settings_mayo_group_playback), stringResource(R.string.recording_settings_group)),
                tv.own.owntv.features.settings.RECORDING_SEARCH_ROWS, R.string.settings_search_keywords_recording, OwnTVIcon.LIVE_TV) { searchQuery = ""; searchMode = false; selectedGroup = SettingsGroup.PLAYBACK.ordinal } +
            rowsOf(stringResource(R.string.settings_breadcrumb, tv.own.owntv.features.settings.videoRowPath("vp_sub_style"), stringResource(R.string.settings_subtitle_appearance)),
                tv.own.owntv.features.settings.SUBTITLE_APPEARANCE_SEARCH_ROWS, R.string.settings_search_keywords_subtitle_appearance, OwnTVIcon.SUBTITLE, jumpTo(subStyleRef)) +
            rowsOf(settingsCrumb(SettingsGroup.APP, stringResource(R.string.settings_group_network), stringResource(R.string.common_proxy)),
                tv.own.owntv.features.settings.PROXY_SEARCH_ROWS, R.string.settings_search_keywords_proxy, OwnTVIcon.NETWORK) { open(SettingsTab.NETWORK) } +
            rowsOf(settingsCrumb(SettingsGroup.APP, stringResource(R.string.settings_group_network), stringResource(R.string.settings_dns)),
                tv.own.owntv.features.settings.DNS_SEARCH_ROWS, R.string.settings_search_keywords_dns, OwnTVIcon.DNS) { open(SettingsTab.DNS) } +
            // The Catch-up popup's second row; its first (the time zone) is the Catch-up entry above.
            rowsOf(stringResource(R.string.settings_breadcrumb, stringResource(R.string.settings_mayo_group_live), stringResource(R.string.settings_catchup)),
                listOf(R.string.settings_catchup_player), R.string.settings_search_keywords_catchup, OwnTVIcon.CATCHUP) { saveScroll(); dialogReturn = searchFieldFocus; showCatchupTime = true }
        // A setting is found by its own name only: "play" lists every setting with "play" in its name.
        val needle = searchQuery.trim().lowercase()
        (entries + videoEntries + screenRowEntries)
            .filter { e -> e.title.lowercase().contains(needle) }
            .distinctBy { it.group + it.title }
    }
    val searching = searchQuery.isNotBlank() || searchMode
    // Back from an empty search: to More when it was opened there, else to the group page.
    BackHandler(enabled = tab == SettingsTab.ROOT && searchMode && searchQuery.isBlank()) {
        searchMode = false
        if (searchFromMore) { searchFromMore = false; onBack?.invoke() }
    }
    val group = SettingsGroup.entries[selectedGroup.coerceIn(0, SettingsGroup.entries.size - 1)]
    // No row counts on settings groups (rule 19): only search's matches and the number of profiles.
    val pageCount = when {
        searching && searchQuery.isBlank() -> ""
        searching -> pluralStringResource(R.plurals.settings_match_count, searchResults.size, searchResults.size)
        group == SettingsGroup.PROFILE -> tv.own.owntv.features.settings.profileCount().let { n ->
            pluralStringResource(R.plurals.settings_profile_count, n, n)
        }
        else -> ""
    }
    val extras = stageRowExtras(
        settingsVm, themeMode, uiZoomPercent, onSetZoom,
        onOpenZoom = { saveScroll(); dialogReturn = zoomRowFocus; showZoom = true },
        fontCustomization, onSetFontCustomization, playlistSources,
    )
    // Shared with the dialogs below, so the simple ones open in the page's panel.
    val panel = remember { tv.own.owntv.features.settings.SettingsPanelState() }
    CompositionLocalProvider(
        // Subtitle appearance is a page of its own, opened from the Sound & subtitles rows.
        tv.own.owntv.features.settings.LocalOpenSubtitleStyle provides { saveScroll(); open(SettingsTab.SUBTITLE_STYLE) },
        tv.own.owntv.features.settings.LocalSubtitleStyleRowFocus provides rowFocus.getValue(SettingsTab.SUBTITLE_STYLE),
    ) {
        StageSettingsPage(
            panel = panel,
            group = if (searching) stringResource(R.string.common_nav_settings) else stringResource(group.titleRes),
            count = pageCount,
            searchQuery = searchQuery,
            onSearchQuery = { searchQuery = it },
            scroll = pageScroll,
            crumb = !searching,
            searchFocus = searchFieldFocus,
            rowsFocus = rowsFocus,
            modifier = modifier,
            searchAutoEdit = searchAutoEdit,
            panelTop = if (group == SettingsGroup.APPEARANCE && !searching) ({ tv.own.owntv.features.settings.SettingsLivePreview() }) else null,
            showSearch = searching,
            pageInstead = side.more?.let { key -> more?.let { host -> { m -> host.page(key, m, side.moreFocus) { side.more = null; side.fromSidebar = false; selectedGroup = SettingsGroup.LANGUAGE.ordinal } } } },
            sidebar = { m ->
                SettingsSidebarHost(
                    categories, selectedGroup, searching, settingsVm, side, m, more,
                    onSelect = { side.fromSidebar = true; side.more = null; searchQuery = ""; searchMode = false; selectedGroup = it },
                    onEnterRows = { if (side.more != null) side.moreFocus.enter() else runCatching { (if (searching) searchFieldFocus else rowsFocus).requestFocus() } },
                    onSearch = { side.more = null; searchQuery = ""; searchMode = true; searchAutoEdit = true; side.pendingSearch = true },
                    onExtra = { searchQuery = ""; searchMode = false },
                    searchField = searchFieldFocus,
                )
            },
        ) {
            val rootRow: @Composable (RootRow) -> Unit = { row ->
                RootRowStage(
                    row,
                    extra = extras[row.key],
                    focus = if (row.key == jumpRowKey) jumpRowFocus else null,
                )
            }
            when {
                searching && searchQuery.isBlank() -> Unit
                searching && searchResults.isEmpty() -> StageSettingsNote(stringResource(R.string.settings_no_settings_match, searchQuery.trim()), null)
                searching -> searchResults.forEach { e ->
                    StageSearchResultRow(e.icon, e.group, e.title, e.chip, onClick = e.onClick)
                }
                group == SettingsGroup.PROFILE -> tv.own.owntv.features.settings.ProfileSettingsRows()
                // Mayo TV as start screen, with Mayo TV's own App start and Home rows in their places.
                group == SettingsGroup.HOMESCREEN -> tv.own.owntv.features.settings.LauncherSettingsRows(
                    part = tv.own.owntv.features.settings.LauncherRowsPart.BOOT,
                    afterBoot = { selectedRows.firstOrNull { it.key == "app_startup" }?.let { rootRow(it) } },
                    androidTvHome = { tv.own.owntv.features.settings.AndroidTvHomeRows() },
                )
                else -> {
                    // Mayo TV (1 Nov 2026): a group holds its own rows and its Video player rows, in either order.
                    val videoFirst = group.video != null && !group.rootFirst
                    if (videoFirst) {
                        VideoPlayerGroupRows(group.video!!, pageScroll, focusRowKey = videoRowKey)
                        if (group == SettingsGroup.PLAYBACK) tv.own.owntv.features.settings.RecordingSettingsRows(pageScroll)
                    }
                    selectedRows.forEachIndexed { i, row ->
                        row.heading?.let { heading ->
                            val under = selectedRows.drop(i).let { rest -> 1 + rest.drop(1).takeWhile { it.heading == null }.size }
                            StageSettingsHeading(heading, under, first = i == 0 && !videoFirst)
                        }
                        rootRow(row)
                    }
                    // System: the remembered values between Network and Diagnostics.
                    if (group == SettingsGroup.APP) SystemSavedRows(pageScroll, videoRowKey)
                    if (!videoFirst && group.video != null) VideoPlayerGroupRows(group.video, pageScroll, focusRowKey = videoRowKey?.takeUnless { it in tv.own.owntv.features.settings.RESET_KEYS }, firstHeading = selectedRows.isEmpty() && group != SettingsGroup.APP)
                }
            }
        }
    }

    // Every dialog below shares the page's panel: the simple ones open there instead of as a popup.
    CompositionLocalProvider(tv.own.owntv.features.settings.LocalSettingsPanel provides panel) {

    if (showUpdate) {
        UpdateDialog(onDismiss = { showUpdate = false }, checkOnOpen = true)
    }
    if (showCatchupTime) CatchupTimeHost(settingsVm, catchupTz, catchupOffset, catchupPlayer) { showCatchupTime = false }
    if (showCatchupSources) {
        CatchupSourcesHost(playlistSources, catchupSource, onPick = { picked ->
            catchupSource = picked
            showCatchupSourceValue = picked != null
            showCatchupSources = false
        }, onDismiss = { catchupSource = null; showCatchupSources = false })
    }
    if (showCatchupSourceValue) {
        CatchupSourceValueHost(settingsVm, playlistSources.firstOrNull { it.id == catchupSource?.id } ?: catchupSource) {
            showCatchupSources = true
            showCatchupSourceValue = false
        }
    }
    if (showEpgOffset) EpgOffsetRootDialog(settingsVm, epgOffset, onClose = { showEpgOffset = false })
    if (showGuideDays) GuideDaysDialog(settingsVm, guideDays, onClose = { showGuideDays = false })
    if (showTheme) ThemeDialogHost(settingsVm, themeMode, onClose = { showTheme = false })
    if (showHomeLayout) {
        val options = listOf(
            tv.own.owntv.launcher.apps.LauncherConfig.LAYOUT_STANDARD to stringResource(R.string.home_layout_standard),
            tv.own.owntv.launcher.apps.LauncherConfig.LAYOUT_CLASSIC to stringResource(R.string.home_layout_classic),
            tv.own.owntv.launcher.apps.LauncherConfig.LAYOUT_STAGE to stringResource(R.string.home_layout_stage),
            tv.own.owntv.launcher.apps.LauncherConfig.LAYOUT_SPOTLIGHT to stringResource(R.string.home_layout_spotlight),
        )
        tv.own.owntv.features.settings.PickerDialog(
            title = stringResource(R.string.home_layout_title),
            options = options,
            selected = launcherConfig.homeLayout,
            onSelect = { key -> tv.own.owntv.launcher.apps.LauncherPrefs.update(launcherContext) { it.copy(homeLayout = key) }; showHomeLayout = false },
            onDismiss = { showHomeLayout = false },
        )
    }
    if (showStartup) StartupDialogHost(settingsVm, startupMode, onClose = { showStartup = false }, onPickChannel = { showStartupChannelPicker = true })
    if (showStartupChannelPicker) StartupChannelPickerHost(settingsVm, startupChannel, onClose = { showStartupChannelPicker = false })
    if (showAnimations) AnimationsDialogHost(settingsVm, animationLevel, onClose = { showAnimations = false })
    if (showNavigation) {
        NavigationPopupHost(settingsVm, onDismiss = { showNavigation = false })
    }
    StageSettingsDialogHost(settingsVm, stageSettings)
    if (showFocusHighlight) {
        FocusHighlightDialog(
            highlight = focusHighlight,
            widthDp = focusHighlightWidth,
            onPickColor = { settingsVm.setFocusHighlight(it) },
            onPickWidth = { settingsVm.setFocusHighlightWidth(it) },
            onDismiss = { showFocusHighlight = false },
        )
    }
    if (showAccent) {
        tv.own.owntv.features.settings.AccentPopup(
            accent = accent,
            customAccent = customAccent,
            onPickPreset = { settingsVm.setAccent(it) },
            onPickCustom = { settingsVm.setCustomAccent(it) },
            onDismiss = { showAccent = false },
        )
    }
    if (showZoom) {
        ZoomDialog(current = uiZoomPercent, onSet = onSetZoom, onDismiss = { showZoom = false })
    }
    if (showAppIcon) {
        tv.own.owntv.ui.components.AppIconSettingsDialog(
            chosen = appIcon,
            onPick = settingsVm::setAppIcon,
            onDismiss = { showAppIcon = false },
        )
    }
    if (showPopupSize) {
        PopupSizeDialog(
            current = fontCustomization.popupSizePercent,
            onSet = { onSetFontCustomization(fontCustomization.copy(popupSizePercent = it)) },
            onDismiss = { showPopupSize = false },
        )
    }
    if (showAmbientGlow) AmbientGlowHost(settingsVm, ambientGlowEnabled, ambientGlowPulse, onClose = { showAmbientGlow = false })
    if (showAfrWarning) {
        AutoFrameRateWarningDialog(
            onEnable = { settingsVm.setAutoFrameRate(true); showAfrWarning = false },
            onDismiss = { showAfrWarning = false },
        )
    }
    if (showLivePreviewPanelWarning) {
        LivePreviewPanelHiddenDialog(onDismiss = { showLivePreviewPanelWarning = false })
    }
    if (showBgImageChooser) {
        BackgroundImageChooserDialog(
            hasImage = bgImagePath.isNotBlank(),
            onPickLocal = { showBgImageChooser = false; showBgPicker = true },
            onPickRemote = { showBgImageChooser = false; showBgRemote = true },
            onClear = { settingsVm.setBgImagePath(""); showBgImageChooser = false },
            onDismiss = { showBgImageChooser = false },
        )
    }
    if (showBgRemote) {
        val context = LocalContext.current
        val remoteState by settingsVm.remoteState.collectAsStateWithLifecycle()
        tv.own.owntv.ui.components.RemoteBackgroundDialog(
            state = remoteState,
            images = settingsVm.remoteImages,
            onStart = settingsVm::startRemoteImageListener,
            onStop = settingsVm::stopRemoteListener,
            onImageReceived = { file ->
                // Same ingest as the local pick: copy into app-private storage, then drop the cache temp.
                val destDir = File(context.filesDir, "backgrounds")
                ingestScope.launch {
                    val path = withContext(Dispatchers.IO) {
                        runCatching { ingestBackgroundImage(file, destDir) }.getOrNull()
                            .also { runCatching { file.delete() } }
                    }
                    if (path != null) settingsVm.setNewBackgroundPicture(path)
                }
                showBgRemote = false
            },
            onDismiss = { showBgRemote = false; showBgImageChooser = true }, // back one level
        )
    }
    if (showBgPicker) {
        val context = LocalContext.current
        StorageBrowser(
            title = stringResource(R.string.settings_pick_background_title),
            mode = BrowseMode.FILE,
            fileExtensions = setOf("png", "jpg", "jpeg", "webp", "bmp"),
            onPick = { file ->
                // Copy into app-private storage so USB unplug / source-folder delete can't blank it.
                val destDir = File(context.filesDir, "backgrounds")
                ingestScope.launch {
                    val path = withContext(Dispatchers.IO) {
                        runCatching { ingestBackgroundImage(file, destDir) }.getOrNull()
                    }
                    if (path != null) settingsVm.setNewBackgroundPicture(path)
                }
                showBgPicker = false
            },
            // Back goes back one level, to the local/remote/clear chooser this was opened from —
            // closing both would leave the user on the Glass & background row two steps up.
            onDismiss = { showBgPicker = false; showBgImageChooser = true },
        )
    }
    }
}

@Composable
private fun StartupChannelPickerDialog(
    query: String,
    channels: List<tv.own.owntv.core.database.entity.ChannelEntity>,
    selected: tv.own.owntv.core.settings.StartupChannelRef?,
    onQueryChange: (String) -> Unit,
    onSelect: (tv.own.owntv.core.database.entity.ChannelEntity) -> Unit,
    onDismiss: () -> Unit,
) {
    // A long list picked from: in the page's panel on a settings page, a Stage popup elsewhere.
    val list: @Composable androidx.compose.foundation.layout.ColumnScope.() -> Unit = {
        StartupChannelList(query, channels, selected, onQueryChange, onSelect)
    }
    if (tv.own.owntv.features.settings.panelEditor(onDismiss) { list() }) return
    tv.own.owntv.ui.stage.StagePopup(onDismiss = onDismiss, title = stringResource(R.string.settings_startup_specific_channel), scroll = false, content = list)
}

/** The search field, then the channels as radio rows (number, name); focus starts in the search. */
@Composable
private fun androidx.compose.foundation.layout.ColumnScope.StartupChannelList(
    query: String,
    channels: List<tv.own.owntv.core.database.entity.ChannelEntity>,
    selected: tv.own.owntv.core.settings.StartupChannelRef?,
    onQueryChange: (String) -> Unit,
    onSelect: (tv.own.owntv.core.database.entity.ChannelEntity) -> Unit,
) {
    val searchFocus = remember { FocusRequester() }
    LaunchedEffect(Unit) { kotlinx.coroutines.delay(80); runCatching { searchFocus.requestFocus() } }
    tv.own.owntv.ui.stage.StageSearchField(
        query = query,
        onQueryChange = onQueryChange,
        placeholder = stringResource(R.string.common_search_hint),
        modifier = Modifier.fillMaxWidth().padding(bottom = 12.mpx).focusRequester(searchFocus),
    )
    if (channels.isEmpty()) {
        Text(
            if (query.isBlank()) stringResource(R.string.content_no_channels_here) else stringResource(R.string.content_no_channels_found, query),
            style = tv.own.owntv.ui.theme.stageText(17, 500), color = tv.own.owntv.ui.theme.StageColors.Muted,
            modifier = Modifier.padding(vertical = 20.mpx),
        )
        return
    }
    LazyColumn(Modifier.fillMaxWidth().weight(1f, fill = false), verticalArrangement = Arrangement.spacedBy(2.mpx)) {
        items(channels, key = { it.id }) { channel ->
            val isSelected = selected?.let { ref ->
                ref.sourceId == channel.sourceId &&
                    if (!ref.remoteId.isNullOrBlank() && !channel.remoteId.isNullOrBlank()) ref.remoteId == channel.remoteId else ref.name == channel.name
            } == true
            tv.own.owntv.ui.stage.StageSurface(
                onClick = { onSelect(channel) },
                radius = 14.mpx,
                focusStyle = tv.own.owntv.ui.stage.StageFocus.FX,
                modifier = Modifier.fillMaxWidth().height(52.mpx),
            ) { focused ->
                Row(Modifier.padding(horizontal = 14.mpx), horizontalArrangement = Arrangement.spacedBy(12.mpx), verticalAlignment = Alignment.CenterVertically) {
                    tv.own.owntv.features.settings.StageRadio(isSelected)
                    Text(
                        channel.number?.toString().orEmpty(), style = tv.own.owntv.ui.theme.stageText(16, 700),
                        color = tv.own.owntv.ui.theme.StageColors.Dim, modifier = Modifier.width(54.mpx), maxLines = 1, overflow = TextOverflow.Ellipsis,
                    )
                    Text(
                        channel.name, style = tv.own.owntv.ui.theme.stageText(18, 600),
                        color = if (isSelected || focused) tv.own.owntv.ui.theme.StageColors.Text else tv.own.owntv.ui.theme.StageColors.Muted,
                        maxLines = 1, overflow = TextOverflow.Ellipsis,
                    )
                }
            }
        }
    }
}

@Composable
private fun themeLabel(mode: ThemeMode): String = stringResource(
    when (mode) {
        ThemeMode.DARK -> R.string.settings_theme_dark
        ThemeMode.LIGHT -> R.string.settings_theme_light
        ThemeMode.SYSTEM -> R.string.settings_theme_system
    },
)

@Composable
private fun startupLabel(mode: tv.own.owntv.core.settings.StartupMode): String = stringResource(
    when (mode) {
        tv.own.owntv.core.settings.StartupMode.HOME -> R.string.settings_startup_home
        tv.own.owntv.core.settings.StartupMode.LAST_CHANNEL -> R.string.settings_startup_last_channel
        tv.own.owntv.core.settings.StartupMode.FAVORITES -> R.string.settings_startup_favorites
        tv.own.owntv.core.settings.StartupMode.SPECIFIC_CHANNEL -> R.string.settings_startup_specific_channel
    },
)

/** Settings › Layout › Navigation, hosted outside [SettingsScreen] (whose body is at the JVM method-size limit). */
@Composable
private fun NavigationPopupHost(settingsVm: SettingsViewModel, onDismiss: () -> Unit) {
    val navMenuMode by settingsVm.navMenuMode.collectAsStateWithLifecycle()
    val navMenuHidden by settingsVm.navMenuHidden.collectAsStateWithLifecycle()
    tv.own.owntv.features.settings.NavigationSettingsPopup(
        menuMode = navMenuMode,
        onMenuMode = settingsVm::setNavMenuMode,
        hiddenSections = navMenuHidden,
        onSectionHidden = settingsVm::setNavSectionHidden,
        onDismiss = onDismiss,
    )
}

/** The Layout group's Navigation row, built here to keep [SettingsScreen] under the JVM method-size limit. */
@Composable
private fun navigationRootRow(settingsVm: SettingsViewModel, focus: FocusRequester, onClick: () -> Unit): RootRow {
    val mode by settingsVm.navMenuMode.collectAsStateWithLifecycle()
    return RootRow(
        "navigation", TileTone.PRIMARY, OwnTVIcon.MENU,
        title = stringResource(R.string.settings_navigation),
        desc = stringResource(R.string.settings_navigation_description),
        chip = stringResource(mode.labelRes),
        chipTone = TileTone.PRIMARY,
        focus = focus,
        onClick = onClick,
    )
}

/** The Navigation row's settings-search entry (see [navigationRootRow]). */
@Composable
private fun navigationSearchEntry(settingsVm: SettingsViewModel, onClick: () -> Unit): SettingsSearchEntry {
    val mode by settingsVm.navMenuMode.collectAsStateWithLifecycle()
    return SettingsSearchEntry(
        stringResource(R.string.settings_group_controls), stringResource(R.string.settings_navigation),
        stringResource(R.string.settings_search_keywords_navigation), OwnTVIcon.MENU, TileTone.PRIMARY,
        chip = stringResource(mode.labelRes), chipTone = TileTone.PRIMARY, onClick = onClick,
    )
}

// Moved out of SettingsScreen unchanged: that function sits at the JVM's 64 KB method limit.
@Composable
private fun ThemeDialogHost(settingsVm: SettingsViewModel, themeMode: ThemeMode, onClose: () -> Unit) {
    tv.own.owntv.features.settings.PickerDialog(
        title = stringResource(R.string.settings_theme_dialog),
        options = ThemeMode.entries.map { it.name to themeLabel(it) },
        selected = themeMode.name,
        onSelect = { settingsVm.setThemeMode(ThemeMode.valueOf(it)); onClose() },
        onDismiss = { onClose() },
    )
}

@Composable
private fun StartupDialogHost(settingsVm: SettingsViewModel, startupMode: tv.own.owntv.core.settings.StartupMode, onClose: () -> Unit, onPickChannel: () -> Unit) {
    tv.own.owntv.features.settings.PickerDialog(
        title = stringResource(R.string.settings_app_startup_dialog),
        options = tv.own.owntv.core.settings.StartupMode.entries.map { it.name to startupLabel(it) },
        selected = startupMode.name,
        onSelect = {
            val mode = tv.own.owntv.core.settings.StartupMode.valueOf(it)
            onClose()
            if (mode == tv.own.owntv.core.settings.StartupMode.SPECIFIC_CHANNEL) {
                settingsVm.setStartupChannelQuery("")
                settingsVm.refreshStartupChannelPicker()
                onPickChannel()
            } else {
                settingsVm.setStartupMode(mode)
            }
        },
        onDismiss = { onClose() },
    )
}

@Composable
private fun StartupChannelPickerHost(settingsVm: SettingsViewModel, startupChannel: tv.own.owntv.core.settings.StartupChannelRef?, onClose: () -> Unit) {
    val startupChannelQuery by settingsVm.startupChannelQuery.collectAsStateWithLifecycle()
    val startupChannelResults by settingsVm.startupChannelResults.collectAsStateWithLifecycle()
    StartupChannelPickerDialog(
        query = startupChannelQuery,
        channels = startupChannelResults,
        selected = startupChannel,
        onQueryChange = settingsVm::setStartupChannelQuery,
        onSelect = {
            settingsVm.setStartupChannel(it)
            onClose()
        },
        onDismiss = { onClose() },
    )
}

@Composable
private fun AnimationsDialogHost(settingsVm: SettingsViewModel, animationLevel: tv.own.owntv.core.theme.AnimationLevel, onClose: () -> Unit) {
    tv.own.owntv.features.settings.PickerDialog(
        title = stringResource(R.string.settings_animations_dialog),
        options = tv.own.owntv.core.theme.AnimationLevel.entries.map { it.name to stringResource(it.labelRes) },
        selected = animationLevel.name,
        onSelect = { settingsVm.setAnimationLevel(tv.own.owntv.core.theme.AnimationLevel.valueOf(it)); onClose() },
        onDismiss = { onClose() },
    )
}

@Composable
private fun AmbientGlowHost(settingsVm: SettingsViewModel, ambientGlowEnabled: Boolean, ambientGlowPulse: Boolean, onClose: () -> Unit) {
    AmbientGlowDialog(
        glowEnabled = ambientGlowEnabled,
        pulseEnabled = ambientGlowPulse,
        onToggleGlow = { settingsVm.setAmbientGlowEnabled(!ambientGlowEnabled) },
        onTogglePulse = { settingsVm.setAmbientGlowPulse(!ambientGlowPulse) },
        onDismiss = onClose,
    )
}

@Composable
private fun EpgOffsetRootDialog(settingsVm: SettingsViewModel, epgOffset: Int, onClose: () -> Unit) {
    EpgOffsetSettingDialog(
        offsetMinutes = epgOffset,
        offsetRange = settingsVm.epgOffsetRangeMinutes,
        onAdjust = settingsVm::adjustEpgOffset,
        onReset = { settingsVm.setEpgOffsetMinutes(0) },
        onDismiss = onClose,
    )
}

@Composable
private fun GuideDaysDialog(settingsVm: SettingsViewModel, guideDays: Int, onClose: () -> Unit) {
    tv.own.owntv.ui.components.DayStepperDialog(
        title = stringResource(R.string.settings_epg_guide_days),
        hint = stringResource(
            R.string.settings_epg_guide_days_hint,
            tv.own.owntv.core.settings.GuideRetention.MIN_DAYS,
            tv.own.owntv.core.settings.GuideRetention.MAX_DAYS,
        ),
        initialDays = guideDays,
        minDays = tv.own.owntv.core.settings.GuideRetention.MIN_DAYS,
        maxDays = tv.own.owntv.core.settings.GuideRetention.MAX_DAYS,
        label = { days -> pluralStringResource(R.plurals.settings_epg_guide_days_value, days, days) },
        onConfirm = { settingsVm.setGuideDaysToKeep(it); onClose() },
        onDismiss = onClose,
    )
}

private enum class StageSettingsDialog { REMINDERS, REMINDER_LEAD }

/** Which Stage picker is open, and each row's focus to come back to. */
@androidx.compose.runtime.Stable
private class StageSettingsState {
    var open by mutableStateOf<StageSettingsDialog?>(null)
    val focus = StageSettingsDialog.entries.associateWith { FocusRequester() }
}

/** The guide block: Programme reminders, Reminder time. */
@Composable
private fun stageGuideRows(settingsVm: SettingsViewModel, state: StageSettingsState, onOpen: (FocusRequester) -> Unit): List<RootRow> {
    val r = state.focus.getValue(StageSettingsDialog.REMINDERS)
    val l = state.focus.getValue(StageSettingsDialog.REMINDER_LEAD)
    return listOf(
        remindersRootRow(settingsVm, r) { onOpen(r); state.open = StageSettingsDialog.REMINDERS },
        reminderLeadRootRow(settingsVm, l) { onOpen(l); state.open = StageSettingsDialog.REMINDER_LEAD },
    )
}

@Composable
private fun StageSettingsDialogHost(settingsVm: SettingsViewModel, state: StageSettingsState) {
    val close = { state.open = null }
    when (state.open) {
        StageSettingsDialog.REMINDERS -> RemindersDialog(settingsVm, close)
        StageSettingsDialog.REMINDER_LEAD -> ReminderLeadDialog(settingsVm, close)
        null -> Unit
    }
}

/** The guide block's Programme reminders: Ask to switch · Switch · Notify only. */
@Composable
private fun remindersRootRow(settingsVm: SettingsViewModel, focus: FocusRequester, onClick: () -> Unit): RootRow {
    val mode by settingsVm.reminderMode.collectAsStateWithLifecycle()
    return RootRow(
        "reminders", TileTone.SECONDARY, OwnTVIcon.BELL,
        title = stringResource(R.string.settings_reminders),
        desc = stringResource(R.string.settings_reminders_description),
        chip = stringResource(reminderModeLabelRes(mode)),
        chipTone = TileTone.PRIMARY,
        focus = focus,
        onClick = onClick,
    )
}

private fun reminderModeLabelRes(mode: tv.own.owntv.core.settings.SettingsRepository.ReminderMode): Int = when (mode) {
    tv.own.owntv.core.settings.SettingsRepository.ReminderMode.ASK -> R.string.settings_reminder_ask
    tv.own.owntv.core.settings.SettingsRepository.ReminderMode.SWITCH -> R.string.settings_reminder_switch
    tv.own.owntv.core.settings.SettingsRepository.ReminderMode.NOTIFY -> R.string.settings_reminder_notify
}

@Composable
private fun RemindersDialog(settingsVm: SettingsViewModel, onClose: () -> Unit) {
    val current by settingsVm.reminderMode.collectAsStateWithLifecycle()
    tv.own.owntv.features.settings.PickerDialog(
        title = stringResource(R.string.settings_reminders),
        subtitle = stringResource(R.string.settings_reminders_description),
        options = tv.own.owntv.core.settings.SettingsRepository.ReminderMode.entries.map { it.name to stringResource(reminderModeLabelRes(it)) },
        selected = current.name,
        onSelect = { settingsVm.setReminderMode(tv.own.owntv.core.settings.SettingsRepository.ReminderMode.valueOf(it)); onClose() },
        onDismiss = onClose,
    )
}

/** Reminder time: at the start, or 1 or 5 minutes before. */
@Composable
private fun reminderLeadRootRow(settingsVm: SettingsViewModel, focus: FocusRequester, onClick: () -> Unit): RootRow {
    val lead by settingsVm.reminderLeadMinutes.collectAsStateWithLifecycle()
    return RootRow(
        "reminder_lead", TileTone.SECONDARY, OwnTVIcon.CLOCK,
        title = stringResource(R.string.settings_reminder_lead),
        desc = stringResource(R.string.settings_reminder_lead_description),
        chip = tv.own.owntv.features.live.reminderLeadText(lead),
        chipTone = TileTone.PRIMARY,
        focus = focus,
        onClick = onClick,
    )
}

@Composable
private fun ReminderLeadDialog(settingsVm: SettingsViewModel, onClose: () -> Unit) {
    val current by settingsVm.reminderLeadMinutes.collectAsStateWithLifecycle()
    tv.own.owntv.features.settings.PickerDialog(
        title = stringResource(R.string.settings_reminder_lead),
        subtitle = stringResource(R.string.settings_reminder_lead_description),
        options = tv.own.owntv.core.reminder.ReminderSchedule.LEAD_CHOICES.map { it.toString() to tv.own.owntv.features.live.reminderLeadText(it) },
        selected = current.toString(),
        onSelect = { settingsVm.setReminderLeadMinutes(it.toInt()); onClose() },
        onDismiss = onClose,
    )
}

/** Chip text for the Language settings row: system-default label, or the selected locale's endonym. */
@Composable
private fun languageChipText(tag: String): String {
    if (tag.isEmpty()) return stringResource(R.string.settings_language_system_default)
    return SupportedLocales.all.find { it.languageTag == tag }?.endonym
        ?: stringResource(R.string.settings_language_system_default)
}

/** The six quick presets shown at the top of the accent picker. */
private val AccentPresetChoices: List<tv.own.owntv.core.theme.AccentColor> =
    tv.own.owntv.core.theme.AccentColor.entries.take(6)

/**
 * Focus highlight presets: the six accent presets plus gold and white, which are the two
 * colors people actually ask for when they want the cursor to shout. Hex, so a preset and a
 * hand-typed color are the same stored value — there is no second "preset" concept to keep in sync.
 */
private val FocusHighlightPresets: List<String> = listOf("#F5B400", "#FFFFFF") +
    AccentPresetChoices.map { ac -> "#%06X".format(java.util.Locale.ROOT, ac.primary(true).toArgb() and 0xFFFFFF) }

/** Row chip for the focus highlight, e.g. "#F5B400 · Thick" or "Default · Normal". */
@Composable
private fun focusHighlightChip(highlight: String, widthDp: Int): String = stringResource(
    R.string.settings_focus_highlight_chip,
    // Only the hex is uppercased — a translated "Default" must keep its own casing.
    if (highlight.isBlank()) stringResource(R.string.settings_subtitle_default) else highlight.uppercase(),
    focusWidthLabel(widthDp),
)

/** Short label for a focus ring width, for the chip on the row and the thickness buttons. */
@Composable
private fun focusWidthLabel(dp: Int): String = stringResource(
    when (dp) {
        1 -> R.string.settings_focus_width_thin
        4 -> R.string.settings_focus_width_thick
        6 -> R.string.settings_focus_width_extra
        else -> R.string.settings_focus_width_normal
    },
)

/**
 * Focus highlight picker: presets, hex field and the shared HSV palette pick the ring color;
 * four buttons pick its width. A live sample sits under the controls because the dialog itself is
 * still drawn with the *saved* values — without it you could not judge a color before committing.
 * "Reset" clears the color back to the accent.
 */
@Composable
private fun FocusHighlightDialog(
    highlight: String,
    widthDp: Int,
    onPickColor: (String) -> Unit,
    onPickWidth: (Int) -> Unit,
    onDismiss: () -> Unit,
) {
    // The Stage colour popup, as Accent colour and the clock colours; its preview is the ring
    // itself and the thickness under it.
    val start = remember { highlight }
    val ring = tv.own.owntv.ui.theme.parseAccentHex(highlight) ?: tv.own.owntv.ui.theme.stageAccent.focus
    val defaultRing = tv.own.owntv.ui.theme.stageAccent.focus
    tv.own.owntv.features.settings.StageColorPopup(
        eyebrow = stringResource(R.string.settings_group_appearance_home),
        title = stringResource(R.string.settings_focus_highlight),
        presets = listOf(tv.own.owntv.features.settings.ColorChoice(defaultRing, stringResource(R.string.settings_subtitle_default)) { onPickColor("") }) +
            // The same seven as before, named rather than written as codes.
            (listOf(R.string.settings_subtitle_color_yellow, R.string.settings_clock_color_white) + AccentPresetChoices.map { it.labelRes })
                .zip(FocusHighlightPresets) { label, hex ->
                    val c = tv.own.owntv.ui.theme.parseAccentHex(hex) ?: defaultRing
                    tv.own.owntv.features.settings.ColorChoice(c, stringResource(label)) { onPickColor(hex) }
                },
        start = ring,
        current = ring,
        onLive = onPickColor,
        onCancel = { onPickColor(start) },
        onDone = { hex -> if (hex != null) onPickColor(hex) },
        onDismiss = onDismiss,
    ) {
        Column(Modifier.padding(top = 20.mpx)) {
            Box(
                Modifier.fillMaxWidth().height(64.mpx)
                    .background(StageColors.Ink.copy(alpha = 0.04f), RoundedCornerShape(18.mpx))
                    .border(widthDp.dp, ring, RoundedCornerShape(18.mpx)),
                contentAlignment = Alignment.Center,
            ) {
                Text(stringResource(R.string.settings_focus_highlight_sample), style = tv.own.owntv.ui.theme.stageText(19, 700), color = tv.own.owntv.ui.theme.StageColors.Text)
            }
            Text(
                stringResource(R.string.settings_focus_thickness).uppercase(),
                style = tv.own.owntv.ui.theme.stageText(14, 800, androidx.compose.ui.unit.TextUnit(0.12f, androidx.compose.ui.unit.TextUnitType.Em)),
                color = tv.own.owntv.ui.theme.StageColors.Dim,
                modifier = Modifier.padding(top = 18.mpx, bottom = 4.mpx),
            )
            val widths = tv.own.owntv.ui.theme.FocusBorderWidthChoices
            tv.own.owntv.ui.stage.StageMenuChoice(
                options = widths.map { focusWidthLabel(it) },
                selected = widths.indexOf(widthDp),
                onSelect = { onPickWidth(widths[it]) },
            )
        }
    }
}


/** The global catch-up time and player dialog, moved out of [SettingsScreen] unchanged (its body is at the JVM method limit). */
@Composable
private fun CatchupTimeHost(
    settingsVm: SettingsViewModel,
    mode: SettingsRepository.CatchupTimezone,
    offsetMinutes: Int,
    player: SettingsRepository.CatchupPlayer,
    onDismiss: () -> Unit,
) {
    CatchupTimeDialog(
            mode = mode,
            offsetMinutes = offsetMinutes,
            offsetRange = settingsVm.catchupOffsetRangeMinutes,
            offsetStep = settingsVm.catchupOffsetStepMinutes,
            onSetMode = settingsVm::setCatchupTimezone,
            onAdjustOffset = settingsVm::adjustCatchupOffset,
            player = player,
            onSetPlayer = settingsVm::setCatchupPlayer,
            onDismiss = onDismiss,
        )
}

/** The playlist list behind "Catch-up time zone per playlist", moved out of [SettingsScreen] unchanged. */
@Composable
private fun CatchupSourcesHost(
    sources: List<tv.own.owntv.core.database.entity.SourceEntity>,
    current: tv.own.owntv.core.database.entity.SourceEntity?,
    onPick: (tv.own.owntv.core.database.entity.SourceEntity?) -> Unit,
    onDismiss: () -> Unit,
) {
    tv.own.owntv.features.settings.PickerDialog(
        title = stringResource(R.string.settings_live_preroll_playlist_picker),
        options = sources.map { src -> src.id.toString() to "${src.name}  ·  ${catchupOverrideLabel(src)}" },
        selected = current?.id?.toString() ?: "",
        onSelect = { id -> onPick(sources.firstOrNull { it.id.toString() == id }) },
        onDismiss = onDismiss,
    )
}

/** More's way into this screen: a root group to open at, or the search field. */
data class SettingsStart(val group: Int?, val search: Boolean)

/** One playlist's catch-up time zone, moved out of [SettingsScreen] unchanged (its body is at the JVM method limit). */
@Composable
private fun CatchupSourceValueHost(settingsVm: SettingsViewModel, src: tv.own.owntv.core.database.entity.SourceEntity?, onDone: () -> Unit) {
    tv.own.owntv.features.settings.PickerDialog(
        title = src?.name ?: stringResource(R.string.settings_catchup_timezone_per_playlist),
        options = listOf(
            CATCHUP_FOLLOW to stringResource(R.string.settings_live_preroll_follow),
            SettingsRepository.CatchupTimezone.DEVICE.name to stringResource(R.string.settings_catchup_timezone_device),
        ) + settingsVm.catchupOffsetChoicesMinutes.map { "$CATCHUP_MANUAL_PREFIX$it" to utcOffsetLabel(it) },
        selected = when (src?.catchupTimezone) {
            null -> CATCHUP_FOLLOW
            SettingsRepository.CatchupTimezone.MANUAL.name -> "$CATCHUP_MANUAL_PREFIX${src.catchupOffsetMin ?: 0}"
            else -> src.catchupTimezone ?: CATCHUP_FOLLOW
        },
        onSelect = { value ->
            src?.let {
                when {
                    value == CATCHUP_FOLLOW -> settingsVm.setSourceCatchupTimezone(it.id, null, null)
                    value.startsWith(CATCHUP_MANUAL_PREFIX) -> settingsVm.setSourceCatchupTimezone(
                        it.id, SettingsRepository.CatchupTimezone.MANUAL.name,
                        value.removePrefix(CATCHUP_MANUAL_PREFIX).toIntOrNull() ?: 0,
                    )
                    else -> settingsVm.setSourceCatchupTimezone(it.id, value, null)
                }
            }
            onDone()
        },
        // Back goes back one level, to the playlist list — same as the Video player overrides.
        onDismiss = onDone,
    )
}

/** The repository line More › About shows. */
internal const val GITHUB_REPO = "github.com/ahXN00/OwnTV"

/**
 * Read-only viewer for the persisted playback error history: the last ~10 failures with their
 * plain-English reason, media spec, raw engine text, engine, stream type and device info — so users
 * who can't pull logcat can read/report what happened after dismissing the error screen.
 */
@Composable
internal fun String.playbackDisplayName(): String = when (trim().lowercase(java.util.Locale.ROOT)) {
    "mpv" -> stringResource(R.string.settings_player_mpv)
    "exoplayer", "exo" -> stringResource(R.string.settings_player_exoplayer)
    else -> this
}

/**
 * Warn before enabling Auto frame rate below Android 12, where smooth refresh-rate alternatives cannot
 * be queried and a mode switch can trigger a visible HDMI re-handshake.
 */
@Composable
internal fun AutoFrameRateWarningDialog(onEnable: () -> Unit, onDismiss: () -> Unit) {
    tv.own.owntv.ui.stage.StageConfirm(
        title = stringResource(R.string.settings_auto_frame_rate_warning_title),
        body = stringResource(R.string.settings_auto_frame_rate_warning_description, android.os.Build.VERSION.RELEASE),
        cancel = stringResource(R.string.settings_auto_frame_rate_keep_off),
        confirm = stringResource(R.string.settings_auto_frame_rate_turn_on_anyway),
        onConfirm = onEnable,
        onCancel = onDismiss,
        focusCancel = true,
    )
}

@Composable
internal fun LivePreviewPanelHiddenDialog(onDismiss: () -> Unit) {
    tv.own.owntv.ui.stage.StageNotice(
        title = stringResource(R.string.settings_live_preview_panel_hidden_title),
        body = stringResource(R.string.settings_live_preview_panel_hidden_description, *NO_ARGS),
        onDismiss = onDismiss,
    )
}

@Composable
internal fun fontFamilyLabel(family: AppFontFamily): String = stringResource(
    when (family) {
        AppFontFamily.LORA -> R.string.settings_font_lora
        AppFontFamily.SYSTEM_SANS -> R.string.settings_font_system_sans
        AppFontFamily.MONOSPACE -> R.string.settings_font_monospace
        AppFontFamily.PLAYFAIR_DISPLAY -> R.string.settings_font_playfair_display
        AppFontFamily.DANCING_SCRIPT -> R.string.settings_font_dancing_script
        AppFontFamily.POPPINS -> R.string.settings_font_poppins
        AppFontFamily.PLUS_JAKARTA_SANS -> R.string.settings_font_plus_jakarta_sans
    },
)

/** A stepper for shared popup geometry. Changes apply live to this dialog too. */
@Composable
private fun PopupSizeDialog(current: Int, onSet: (Int) -> Unit, onDismiss: () -> Unit) {
    // The value with a real-size sample popup above it: in the page's panel on a settings page,
    // a Stage popup elsewhere.
    val stepper: @Composable () -> Unit = {
        tv.own.owntv.features.settings.PanelStepper(
            value = stringResource(R.string.common_percent, current),
            onStep = { d -> onSet(PopupSizeScale.clamp(current + d * PopupSizeScale.STEP)) },
            onReset = { onSet(PopupSizeScale.DEFAULT) },
            onDone = onDismiss,
            preview = { tv.own.owntv.features.settings.PopupSizeSample(current) },
        )
    }
    if (tv.own.owntv.features.settings.panelEditor(onDismiss) { stepper() }) return
    tv.own.owntv.ui.stage.StagePopup(onDismiss = onDismiss, title = stringResource(R.string.settings_popup_size), body = stringResource(R.string.settings_popup_size_range, PopupSizeScale.MIN, PopupSizeScale.MAX)) { stepper() }
}

/** A stepper for the global UI scale. Changes apply live (the whole UI re-scales as you adjust). */
@Composable
private fun ZoomDialog(current: Int, onSet: (Int) -> Unit, onDismiss: () -> Unit) {
    // Zoom below LOW_RAM_WARN doubles the on-screen item count, which can OOM-crash 2 GB devices
    // the first step under it asks first (a Stage question, focus on Cancel). Accepting once
    // arms the rest of this session; if it was opened already below the line, don't nag.
    var lowZoomAccepted by remember { mutableStateOf(current < UiZoom.LOW_RAM_WARN) }
    var pendingLowZoom by remember { mutableStateOf<Int?>(null) }
    val stepper: @Composable () -> Unit = {
        tv.own.owntv.features.settings.PanelStepper(
            value = stringResource(R.string.common_percent, current),
            onStep = { d ->
                val next = UiZoom.clamp(current + d * UiZoom.STEP)
                if (d < 0 && next < UiZoom.LOW_RAM_WARN && !lowZoomAccepted) pendingLowZoom = next else onSet(next)
            },
            onReset = { onSet(UiZoom.DEFAULT) },
            onDone = onDismiss,
        )
    }
    // In the page's panel on a settings page, a Stage popup elsewhere.
    if (!tv.own.owntv.features.settings.panelEditor(onDismiss) { stepper() }) {
        tv.own.owntv.ui.stage.StagePopup(onDismiss = onDismiss, title = stringResource(R.string.settings_ui_zoom), body = stringResource(R.string.settings_ui_zoom_range, UiZoom.MIN, UiZoom.MAX)) { stepper() }
    }
    pendingLowZoom?.let { target ->
        tv.own.owntv.ui.stage.StageConfirm(
            title = stringResource(R.string.settings_low_zoom_warning_title),
            body = stringResource(R.string.settings_low_zoom_warning, UiZoom.LOW_RAM_WARN, UiZoom.LOW_RAM_WARN),
            confirm = stringResource(R.string.settings_low_zoom_accept),
            onConfirm = { lowZoomAccepted = true; pendingLowZoom = null; onSet(target) },
            onCancel = { pendingLowZoom = null },
            focusCancel = true,
        )
    }
}

/** Solid-interface radiance controls, kept together in one compact TV-safe popup. */
@Composable
private fun AmbientGlowDialog(
    glowEnabled: Boolean,
    pulseEnabled: Boolean,
    onToggleGlow: () -> Unit,
    onTogglePulse: () -> Unit,
    onDismiss: () -> Unit,
) {
    // Two switches: in the page's panel, a Stage popup elsewhere.
    val rows: @Composable () -> Unit = {
        val first = remember { FocusRequester() }
        LaunchedEffect(Unit) { kotlinx.coroutines.delay(60); runCatching { first.requestFocus() } }
        Column(Modifier.trapAllFocusExit().focusGroup()) {
            tv.own.owntv.ui.stage.StagePopupOption(
                title = stringResource(R.string.settings_ambient_glow_effect), onClick = onToggleGlow, modifier = Modifier.focusRequester(first),
                trailing = { tv.own.owntv.ui.stage.StageSwitch(glowEnabled) },
            )
            if (glowEnabled) tv.own.owntv.ui.stage.StagePopupOption(
                title = stringResource(R.string.settings_ambient_glow_pulse), onClick = onTogglePulse,
                trailing = { tv.own.owntv.ui.stage.StageSwitch(pulseEnabled) },
            )
            Row(Modifier.fillMaxWidth().padding(top = 16.mpx), horizontalArrangement = Arrangement.End) {
                tv.own.owntv.ui.stage.StageButton(stringResource(R.string.settings_done), onClick = onDismiss, height = 52.mpx, textSize = 18, tinted = true)
            }
        }
    }
    if (tv.own.owntv.features.settings.panelEditor(onDismiss) { rows() }) return
    tv.own.owntv.ui.stage.StagePopup(onDismiss = onDismiss, title = stringResource(R.string.settings_ambient_glow), body = stringResource(R.string.settings_ambient_glow_dialog_description)) { rows() }
}

/** The Glass & background page. Background selection stays nested here, so Back returns here. */
@Composable
private fun GlassEffectSettingsScreen(onBack: () -> Unit, modifier: Modifier = Modifier) {
    val settingsVm: SettingsViewModel = koinViewModel()
    val glassConfig by settingsVm.glassConfig.collectAsStateWithLifecycle()
    val bgImagePath by settingsVm.bgImagePath.collectAsStateWithLifecycle()
    var showBackgroundChooser by remember { mutableStateOf(false) }
    var showLocalPicker by remember { mutableStateOf(false) }
    var showRemotePicker by remember { mutableStateOf(false) }
    val ingestScope = rememberCoroutineScope()

    val background by settingsVm.backgroundConfig.collectAsStateWithLifecycle()
    GlassBackgroundPage(
        background = background,
        glassOn = glassConfig.enabled,
        alphaPercent = (glassConfig.alpha * 100).roundToInt(),
        onSetStyle = { style ->
            // Picture with no picture yet goes straight to the picker.
            settingsVm.setBackgroundStyle(style)
            if (style == tv.own.owntv.core.theme.BackgroundStyle.PICTURE && bgImagePath.isBlank()) showBackgroundChooser = true
        },
        onOpenPicture = { showBackgroundChooser = true },
        onSetLook = settingsVm::setPictureLook,
        onSetDim = settingsVm::setBackgroundDim,
        onSetBlur = settingsVm::setBackgroundBlur,
        onSetAccentLight = settingsVm::setBackgroundAccentLight,
        onToggleGlass = {
            settingsVm.setGlassScopeBitmask(
                if (glassConfig.enabled) 0 else GlassConfig.ON_BITS,
            )
        },
        onSetAlpha = settingsVm::setGlassAlphaPercent,
        onReset = settingsVm::resetGlassAndBackground,
        onBack = onBack,
        modifier = modifier,
    )

    if (showBackgroundChooser) {
        BackgroundImageChooserDialog(
                hasImage = bgImagePath.isNotBlank(),
                onPickLocal = { showBackgroundChooser = false; showLocalPicker = true },
                onPickRemote = { showBackgroundChooser = false; showRemotePicker = true },
                onClear = { settingsVm.setBgImagePath(""); showBackgroundChooser = false },
                onDismiss = { showBackgroundChooser = false },
            )
    }
    if (showRemotePicker) {
        val context = LocalContext.current
        val remoteState by settingsVm.remoteState.collectAsStateWithLifecycle()
        tv.own.owntv.ui.components.RemoteBackgroundDialog(
            state = remoteState,
            images = settingsVm.remoteImages,
            onStart = settingsVm::startRemoteImageListener,
            onStop = settingsVm::stopRemoteListener,
            onImageReceived = { file ->
                val destDir = File(context.filesDir, "backgrounds")
                ingestScope.launch {
                    val path = withContext(Dispatchers.IO) {
                        runCatching { ingestBackgroundImage(file, destDir) }.getOrNull()
                            .also { runCatching { file.delete() } }
                    }
                    if (path != null) settingsVm.setNewBackgroundPicture(path)
                }
                showRemotePicker = false
            },
            onDismiss = { showRemotePicker = false; showBackgroundChooser = true },
        )
    }
    if (showLocalPicker) {
        val context = LocalContext.current
        StorageBrowser(
            title = stringResource(R.string.settings_pick_background_title),
            mode = BrowseMode.FILE,
            fileExtensions = setOf("png", "jpg", "jpeg", "webp", "bmp"),
            onPick = { file ->
                val destDir = File(context.filesDir, "backgrounds")
                ingestScope.launch {
                    val path = withContext(Dispatchers.IO) {
                        runCatching { ingestBackgroundImage(file, destDir) }.getOrNull()
                    }
                    if (path != null) settingsVm.setNewBackgroundPicture(path)
                }
                showLocalPicker = false
            },
            onDismiss = { showLocalPicker = false; showBackgroundChooser = true },
        )
    }
}

/** Per-playlist catch-up picker keys: follow the global setting, or a UTC offset in minutes. */
private const val CATCHUP_FOLLOW = ""
private const val CATCHUP_MANUAL_PREFIX = "MANUAL:"

/** A playlist's own catch-up time zone as the playlist list shows it. */
@Composable
private fun catchupOverrideLabel(source: tv.own.owntv.core.database.entity.SourceEntity): String =
    when (source.catchupTimezone) {
        null -> stringResource(R.string.settings_live_preroll_follow)
        SettingsRepository.CatchupTimezone.MANUAL.name -> utcOffsetLabel(source.catchupOffsetMin ?: 0)
        else -> stringResource(R.string.settings_device)
    }

/** "UTC", "UTC+05:00", "UTC-03:30" — labels a UTC offset (in minutes) for catch-up. */
private fun utcOffsetLabel(minutes: Int): String {
    if (minutes == 0) return "UTC"
    val sign = if (minutes < 0) "-" else "+"
    val abs = kotlin.math.abs(minutes)
    return "UTC$sign%02d:%02d".format(Locale.ROOT, abs / 60, abs % 60)
}

@Composable
private fun CatchupTimeDialog(
    mode: SettingsRepository.CatchupTimezone,
    offsetMinutes: Int,
    offsetRange: IntRange,
    onSetMode: (SettingsRepository.CatchupTimezone) -> Unit,
    onAdjustOffset: (Int) -> Unit,
    /** One −/+ press, in minutes (a quarter hour). */
    offsetStep: Int,
    player: SettingsRepository.CatchupPlayer,
    onSetPlayer: (SettingsRepository.CatchupPlayer) -> Unit,
    onDismiss: () -> Unit,
) {
    // Three simple values: in the page's panel, a Stage popup elsewhere.
    val manual = mode == SettingsRepository.CatchupTimezone.MANUAL
    val form: @Composable () -> Unit = {
        val first = remember { FocusRequester() }
        LaunchedEffect(Unit) { kotlinx.coroutines.delay(60); runCatching { first.requestFocus() } }
        val modes = SettingsRepository.CatchupTimezone.entries
        Column(Modifier.trapAllFocusExit().focusGroup()) {
            tv.own.owntv.ui.stage.StageMenuChoice(
                options = listOf(
                    stringResource(R.string.settings_catchup_timezone_automatic),
                    stringResource(R.string.settings_catchup_timezone_device),
                    stringResource(R.string.settings_manual),
                ),
                selected = modes.indexOf(mode),
                onSelect = { onSetMode(modes[it]) },
                focusRequester = first,
            )
            if (manual) {
                // ◀ ▶ move the offset by a quarter hour; clamped at the ends.
                tv.own.owntv.ui.stage.StageSurface(
                    onClick = {},
                    radius = 18.mpx,
                    focusStyle = tv.own.owntv.ui.stage.StageFocus.FX,
                    modifier = Modifier.fillMaxWidth().padding(top = 8.mpx).height(72.mpx).onPreviewKeyEvent { e ->
                        val d = when (e.key) { Key.DirectionLeft -> -1; Key.DirectionRight -> 1; else -> 0 }
                        if (d != 0 && e.type == KeyEventType.KeyDown) onAdjustOffset(d * offsetStep)
                        d != 0
                    },
                    contentAlignment = Alignment.Center,
                ) {
                    Row(Modifier.fillMaxWidth().padding(horizontal = 22.mpx), verticalAlignment = Alignment.CenterVertically) {
                        OwnTVIcon(OwnTVIcon.CHEVRON, tv.own.owntv.ui.theme.StageColors.Muted, Modifier.size(24.mpx).graphicsLayer { rotationZ = 180f })
                        Text(
                            utcOffsetLabel(offsetMinutes), style = tv.own.owntv.ui.theme.stageText(30, 800), color = tv.own.owntv.ui.theme.stageAccent.accent,
                            textAlign = androidx.compose.ui.text.style.TextAlign.Center, modifier = Modifier.weight(1f),
                        )
                        OwnTVIcon(OwnTVIcon.CHEVRON, tv.own.owntv.ui.theme.StageColors.Muted, Modifier.size(24.mpx))
                    }
                }
            }
            // Which player takes an archive programme — "Ask" puts the choice on "Watch from start" itself.
            Text(
                stringResource(R.string.settings_catchup_player).uppercase(),
                style = tv.own.owntv.ui.theme.stageText(14, 800, androidx.compose.ui.unit.TextUnit(0.12f, androidx.compose.ui.unit.TextUnitType.Em)),
                color = tv.own.owntv.ui.theme.StageColors.Dim,
                modifier = Modifier.padding(start = 8.mpx, top = 18.mpx, bottom = 4.mpx),
            )
            val players = SettingsRepository.CatchupPlayer.entries
            tv.own.owntv.ui.stage.StageMenuChoice(
                options = players.map {
                    when (it) {
                        SettingsRepository.CatchupPlayer.ASK -> stringResource(R.string.settings_catchup_player_ask)
                        SettingsRepository.CatchupPlayer.INTERNAL -> stringResource(R.string.settings_catchup_player_internal)
                        SettingsRepository.CatchupPlayer.EXTERNAL -> stringResource(R.string.settings_catchup_player_external)
                    }
                },
                selected = players.indexOf(player),
                onSelect = { onSetPlayer(players[it]) },
            )
            Row(Modifier.fillMaxWidth().padding(top = 16.mpx), horizontalArrangement = Arrangement.End) {
                tv.own.owntv.ui.stage.StageButton(stringResource(R.string.settings_done), onClick = onDismiss, height = 52.mpx, textSize = 18, tinted = true)
            }
        }
    }
    if (tv.own.owntv.features.settings.panelEditor(onDismiss) { form() }) return
    tv.own.owntv.ui.stage.StagePopup(onDismiss = onDismiss, title = stringResource(R.string.settings_catchup), body = stringResource(R.string.settings_catchup_timezone_description)) { form() }
}

/**
 * Global guide shift. Some XMLTV feeds publish in a timezone the channels don't actually air in;
 * this moves every programme by a fixed amount. A per-channel override (channel long-press → EPG
 * time offset) wins over it — that's what a lineup carrying both East and West feeds needs, since
 * one global shift can only ever fix one of the two.
 */
@Composable
private fun EpgOffsetSettingDialog(
    offsetMinutes: Int,
    offsetRange: IntRange,
    onAdjust: (Int) -> Unit,
    onReset: () -> Unit,
    onDismiss: () -> Unit,
) {
    // A number: in the page's panel on a settings page, a Stage popup elsewhere.
    val stepper: @Composable () -> Unit = {
        tv.own.owntv.features.settings.PanelStepper(
            value = epgShiftLabel(offsetMinutes),
            onStep = { d -> onAdjust(d * 30) },
            onReset = onReset,
            onDone = onDismiss,
        )
    }
    if (tv.own.owntv.features.settings.panelEditor(onDismiss) { stepper() }) return
    tv.own.owntv.ui.stage.StagePopup(
        onDismiss = onDismiss,
        title = stringResource(R.string.content_epg_time_offset),
        body = stringResource(R.string.settings_epg_offset_dialog_description),
    ) { stepper() }
}

/**
 * One entry in the Settings root list, as data rather than as an inline call.
 *
 * The root is a lazy list, which needs a stable key per entry — and both focus restores (closing a
 * dialog, and Back from a sub-screen) need the *index* of a row that may be scrolled off-screen and
 * therefore not composed at all. Neither is knowable from a plain Column of 36 inline rows.
 */
private sealed interface RootItem {
    val key: String
}

/** A group heading — one entry in the left spine, with the icon and summary the sheet is headed by. */
private data class RootGroup(
    override val key: String,
    val label: String,
    val icon: OwnTVIcon,
    val summary: String,
) : RootItem

private data class RootRow(
    override val key: String,
    val tone: TileTone,
    val icon: OwnTVIcon,
    val title: String,
    val desc: String? = null,
    val chip: String? = null,
    val chipTone: TileTone = TileTone.PRIMARY,
    /** Set only by pinned Video player rows, which decide row by row whether they open a screen. */
    val chevron: Boolean? = null,
    val focus: FocusRequester? = null,
    /** A divider label drawn above this row, starting a sub-section inside its group. Not a row itself. */
    val heading: String? = null,
    /** What the Stage row shows on its right when the chip alone does not say (switch, stepper, …). */
    val value: SettingValue? = null,
    /** The context panel's choices for a value picked from a list, and the recommended one. */
    val choices: List<String> = emptyList(),
    val recommended: Int = -1,
    /** ◀ ▶ on a stepper or segmented value. */
    val onStep: ((Int) -> Unit)? = null,
    val onClick: () -> Unit,
) : RootItem {
    /**
     * Honest chevrons: the arrow promises another screen, so only the rows that open one carry it.
     * Derived from the key rather than declared per row — [tabRowKey] is used by exactly the rows that
     * push a [SettingsTab], so a new sub-screen row gets its chevron with no extra flag to forget.
     * Rows pinned to Quick from the player groups are the exception and say so themselves
     * in [chevron]: the ones that toggle in place must not promise a screen they never open.
     */
    val showChevron: Boolean get() = chevron ?: key.startsWith("tab_")
}

/** The settings that live inside a sub-screen, findable by their own names; each opens its screen. */
@Composable
private fun subScreenSearchEntries(open: (SettingsTab) -> Unit): List<SettingsSearchEntry> = listOf(
        SettingsSearchEntry(stringResource(R.string.settings_group_sources), stringResource(R.string.settings_epg_sources_auto_refresh_title), stringResource(R.string.settings_search_keywords_epg), OwnTVIcon.EPG, TileTone.SECONDARY) { open(SettingsTab.EPG) },
        SettingsSearchEntry(stringResource(R.string.settings_group_appearance_home), stringResource(R.string.home_row_now_trending), stringResource(R.string.settings_search_keywords_home), OwnTVIcon.HOME, TileTone.SECONDARY) { open(SettingsTab.HOME) },
        SettingsSearchEntry(stringResource(R.string.settings_group_appearance_home), stringResource(R.string.home_trending_style), stringResource(R.string.settings_search_keywords_home), OwnTVIcon.HOME, TileTone.SECONDARY) { open(SettingsTab.HOME) },
        SettingsSearchEntry(stringResource(R.string.settings_group_appearance_home), stringResource(R.string.settings_hero_preview), stringResource(R.string.settings_search_keywords_home), OwnTVIcon.HOME, TileTone.SECONDARY) { open(SettingsTab.HOME) },
        SettingsSearchEntry(stringResource(R.string.settings_group_appearance_home), stringResource(R.string.settings_live_keep_watching), stringResource(R.string.settings_search_keywords_home), OwnTVIcon.HOME, TileTone.SECONDARY) { open(SettingsTab.HOME) },
        SettingsSearchEntry(stringResource(R.string.settings_group_appearance_home), stringResource(R.string.settings_movies_keep_watching), stringResource(R.string.settings_search_keywords_home), OwnTVIcon.HOME, TileTone.SECONDARY) { open(SettingsTab.HOME) },
        SettingsSearchEntry(stringResource(R.string.settings_group_appearance_home), stringResource(R.string.settings_series_keep_watching), stringResource(R.string.settings_search_keywords_home), OwnTVIcon.HOME, TileTone.SECONDARY) { open(SettingsTab.HOME) },
        SettingsSearchEntry(stringResource(R.string.settings_group_controls), stringResource(R.string.settings_ch_nav_up), stringResource(R.string.settings_search_keywords_home), OwnTVIcon.CH_NAV, TileTone.SECONDARY) { open(SettingsTab.CH_NAV) },
        SettingsSearchEntry(stringResource(R.string.settings_group_controls), stringResource(R.string.settings_ch_nav_down), stringResource(R.string.settings_search_keywords_home), OwnTVIcon.CH_NAV, TileTone.SECONDARY) { open(SettingsTab.CH_NAV) },
        SettingsSearchEntry(stringResource(R.string.settings_group_controls), stringResource(R.string.settings_remote_shortcuts), stringResource(R.string.settings_search_keywords_home), OwnTVIcon.CH_NAV, TileTone.SECONDARY) { open(SettingsTab.CH_NAV) },
        SettingsSearchEntry(stringResource(R.string.settings_group_appearance_home), stringResource(R.string.settings_show_weather), stringResource(R.string.settings_search_keywords_weather), OwnTVIcon.WEATHER, TileTone.SECONDARY) { open(SettingsTab.WEATHER) },
        SettingsSearchEntry(stringResource(R.string.settings_group_appearance_home), stringResource(R.string.settings_temperature_unit), stringResource(R.string.settings_search_keywords_weather), OwnTVIcon.WEATHER, TileTone.SECONDARY) { open(SettingsTab.WEATHER) },
        SettingsSearchEntry(stringResource(R.string.settings_group_appearance_home), stringResource(R.string.settings_custom_location), stringResource(R.string.settings_search_keywords_weather), OwnTVIcon.WEATHER, TileTone.SECONDARY) { open(SettingsTab.WEATHER) },
        SettingsSearchEntry(stringResource(R.string.settings_group_content_metadata), stringResource(R.string.settings_metadata_source), stringResource(R.string.settings_search_keywords_metadata), OwnTVIcon.IMAGE, TileTone.SECONDARY) { open(SettingsTab.METADATA) },
        SettingsSearchEntry(stringResource(R.string.settings_group_content_metadata), stringResource(R.string.settings_metadata_language), stringResource(R.string.settings_search_keywords_metadata), OwnTVIcon.IMAGE, TileTone.SECONDARY) { open(SettingsTab.METADATA) },
        SettingsSearchEntry(stringResource(R.string.settings_group_content_metadata), stringResource(R.string.player_subtitles_search_language), stringResource(R.string.settings_search_keywords_subtitle_appearance), OwnTVIcon.SUBTITLE, TileTone.SECONDARY) { open(SettingsTab.OPEN_SUBTITLES) },
        SettingsSearchEntry(stringResource(R.string.settings_group_content_metadata), stringResource(R.string.player_subtitles_filter_title), stringResource(R.string.settings_search_keywords_subtitle_appearance), OwnTVIcon.SUBTITLE, TileTone.SECONDARY) { open(SettingsTab.OPEN_SUBTITLES) },
        SettingsSearchEntry(stringResource(R.string.settings_group_content_metadata), stringResource(R.string.player_subtitles_stay_signed_in), stringResource(R.string.settings_search_keywords_subtitle_appearance), OwnTVIcon.SUBTITLE, TileTone.SECONDARY) { open(SettingsTab.OPEN_SUBTITLES) },
)

/** The list key of the row that opens [tab], so a Back from that sub-screen can find its index. */
private fun tabRowKey(tab: SettingsTab) = "tab_${tab.name}"

/**
 * A Settings row on a Stage group page. The value is the row's own, or read from its chip: On / Off
 * in place is a switch, a row that opens a screen keeps its › (honest chevrons), anything else was
 * picked in a popup (▾).
 */
@Composable
private fun RootRowStage(
    item: RootRow,
    /** Always false since Quick is gone (6 Nov 2026); kept so older calls still compile. */
    pinned: Boolean = false,
    /** Null: a held OK does nothing (no hold-OK on settings rows, rule 11). */
    onLongClick: (() -> Unit)? = null,
    /** Takes precedence over the row's own requester: a search jump focuses the row through it. */
    focus: FocusRequester? = null,
    extra: RowExtra? = null,
) {
    val on = stringResource(R.string.common_on)
    val off = stringResource(R.string.common_off)
    val value = extra?.value ?: item.value ?: when {
        item.showChevron || item.key == "catchup_sources" -> SettingValue.Opens(item.chip)
        item.key == "epg_offset" -> SettingValue.Choice(item.chip.orEmpty())
        item.chip == on || item.chip == off -> SettingValue.Switch(item.chip == on)
        item.chip != null -> SettingValue.Choice(item.chip)
        else -> null
    }
    val words = tv.own.owntv.features.settings.settingWords(item.key, item.title, item.desc)
    StageSettingRow(
        icon = item.icon,
        title = words.title,
        desc = words.line,
        value = value,
        onClick = extra?.onClick ?: item.onClick,
        onLongClick = onLongClick,
        onStep = extra?.onStep ?: item.onStep,
        pinned = pinned,
        help = settingHelp(item.key, words.title, item.desc, value, extra?.choices ?: item.choices, chosen = extra?.chosen ?: -1, recommended = extra?.recommended ?: item.recommended, pinnable = false).let { h ->
            h.copy(text = extra?.help ?: h.text, hints = extra?.hints ?: h.hints, extra = extra?.extra)
        },
        modifier = (focus ?: item.focus)?.let { Modifier.focusRequester(it) } ?: Modifier,
    )
}
/**
 * Hold OK on a sheet row: pin it to Quick, take it back out, or move it within Quick. Actions that
 * cannot apply are left out rather than greyed — a focusable row that refuses to do anything is worse
 * on a remote than one that is simply not there.
 */
@Composable
internal fun SettingsRowMenu(
    title: String,
    pinned: Boolean,
    canMoveUp: Boolean,
    canMoveDown: Boolean,
    onPinToggle: () -> Unit,
    onMoveUp: () -> Unit,
    onMoveDown: () -> Unit,
    onDismiss: () -> Unit,
) {
    BackHandler { onDismiss() }
    val first = remember { FocusRequester() }
    LaunchedEffect(Unit) { withFrameNanos { }; runCatching { first.requestFocus() } }
    // The Stage menu (playbook 11), as every other hold-OK menu.
    tv.own.owntv.ui.components.OwnTVPopup(onDismissRequest = onDismiss, stageLayout = true) {
        Box(
            Modifier.fillMaxSize().background(tv.own.owntv.ui.theme.StageColors.Scrim).longPressMenuGuard(),
            contentAlignment = Alignment.Center,
        ) {
            tv.own.owntv.ui.stage.StageMenu(Modifier.width(520.mpx).trapAllFocusExit().focusGroup()) {
                tv.own.owntv.ui.stage.StageMenuHeader(title = title, subtitle = null)
                tv.own.owntv.ui.stage.StageMenuItem(
                    stringResource(if (pinned) R.string.settings_row_menu_unpin else R.string.settings_row_menu_pin),
                    onClick = { onPinToggle(); onDismiss() },
                    icon = OwnTVIcon.SPARKLE,
                    modifier = Modifier.focusRequester(first),
                )
                if (canMoveUp) {
                    tv.own.owntv.ui.stage.StageMenuItem(
                        stringResource(R.string.settings_row_menu_move_up),
                        onClick = { onMoveUp(); onDismiss() },
                        icon = OwnTVIcon.CHEVRON_UP,
                    )
                }
                if (canMoveDown) {
                    tv.own.owntv.ui.stage.StageMenuItem(
                        stringResource(R.string.settings_row_menu_move_down),
                        onClick = { onMoveDown(); onDismiss() },
                        icon = OwnTVIcon.CHEVRON_DOWN,
                    )
                }
            }
        }
    }
}

/** One searchable settings row: its group breadcrumb, title, extra keywords, and action. */
private class SettingsSearchEntry(
    val group: String,
    val title: String,
    keywords: String,
    val icon: OwnTVIcon,
    val tone: TileTone,
    val chip: String? = null,
    val chipTone: TileTone = TileTone.PRIMARY,
    val showChevron: Boolean = true,
    val onClick: () -> Unit,
)

/** What a root row shows and does on its Stage page beyond its chip. */
private class RowExtra(
    val value: SettingValue? = null,
    val onStep: ((Int) -> Unit)? = null,
    /** Replaces the row's click: a switch or segmented value changes in place instead of opening a popup. */
    val onClick: (() -> Unit)? = null,
    val choices: List<String> = emptyList(),
    val chosen: Int = -1,
    val recommended: Int = -1,
    /** Panel text that needs an argument (Language: how many languages). */
    val help: String? = null,
    val hints: List<Pair<String, String>>? = null,
    val extra: (@Composable () -> Unit)? = null,
)

/**
 * The Stage values of the root rows, by row key: switches, steppers and segmented choices that change
 * in place, the accent swatches, and the "opens a screen" values. Kept out of [SettingsScreen], which
 * sits at the JVM's method size limit.
 */
@Composable
private fun stageRowExtras(
    vm: SettingsViewModel,
    themeMode: ThemeMode,
    uiZoomPercent: Int,
    onSetZoom: (Int) -> Unit,
    onOpenZoom: () -> Unit,
    font: FontCustomization,
    onSetFont: (FontCustomization) -> Unit,
    playlists: List<tv.own.owntv.core.database.entity.SourceEntity>,
): Map<String, RowExtra> {
    val accent by vm.accent.collectAsStateWithLifecycle()
    val customAccent by vm.customAccent.collectAsStateWithLifecycle()
    val glass by vm.glassConfig.collectAsStateWithLifecycle()
    val animation by vm.animationLevel.collectAsStateWithLifecycle()
    val weatherOn by vm.weatherEnabled.collectAsStateWithLifecycle()
    val weatherPlace by vm.weatherLocation.collectAsStateWithLifecycle()
    val fahrenheit by vm.weatherFahrenheit.collectAsStateWithLifecycle()
    val navMenuMode by vm.navMenuMode.collectAsStateWithLifecycle()
    val appIcon by vm.appIcon.collectAsStateWithLifecycle()
    val brandAccent by vm.brandAccentTriangle.collectAsStateWithLifecycle()
    val focusHighlight by vm.focusHighlight.collectAsStateWithLifecycle()
    val focusWidth by vm.focusHighlightWidth.collectAsStateWithLifecycle()
    val sep = stringResource(R.string.content_epg_bits_separator)
    fun <T> cycle(all: List<T>, now: T, step: Int): T = all[(all.indexOf(now) + step).mod(all.size)]

    val themes = ThemeMode.entries
    val themeNames = themes.map { themeLabel(it) }
    val presets = tv.own.owntv.core.theme.AccentColor.entries
    val presetColors = presets.map { it.primary(true) }
    val current = tv.own.owntv.ui.theme.stageAccent.accent
    val hex = if (customAccent.isNotBlank()) customAccent.uppercase() else String.format(java.util.Locale.ROOT, "#%06X", 0xFFFFFF and current.toArgb())
    val focusColor = tv.own.owntv.ui.theme.stageAccent.focus
    val accentStep: (Int) -> Unit = { step ->
        vm.setCustomAccent("")
        vm.setAccent(if (customAccent.isNotBlank()) (if (step > 0) presets.first() else presets.last()) else cycle(presets, accent, step))
    }
    val zoomStep: (Int) -> Unit = { step ->
        val next = tv.own.owntv.core.theme.UiZoom.clamp(uiZoomPercent + step * tv.own.owntv.core.theme.UiZoom.STEP)
        // Below the low-memory line the zoom popup asks first; the row never steps past it alone.
        if (next < tv.own.owntv.core.theme.UiZoom.LOW_RAM_WARN && uiZoomPercent >= tv.own.owntv.core.theme.UiZoom.LOW_RAM_WARN) onOpenZoom() else onSetZoom(next)
    }
    val popupStep: (Int) -> Unit = { step ->
        onSetFont(font.copy(popupSizePercent = tv.own.owntv.core.theme.PopupSizeScale.clamp(font.popupSizePercent + step * tv.own.owntv.core.theme.PopupSizeScale.STEP)))
    }
    val languages = tv.own.owntv.core.i18n.SupportedLocales.all.count { it.packaged }
    val valueStyle = tv.own.owntv.ui.theme.stageText(18, 700)
    return mapOf(
        "tab_SOURCES" to RowExtra(
            SettingValue.Opens(pluralStringResource(R.plurals.more_playlist_count, playlists.size, playlists.size)),
            extra = { tv.own.owntv.features.settings.PlaylistSyncList(playlists) },
        ),
        "theme" to RowExtra(
            SettingValue.Segmented(themeNames, themes.indexOf(themeMode)),
            onStep = { vm.setThemeMode(cycle(themes, themeMode, it)) },
            onClick = { vm.setThemeMode(cycle(themes, themeMode, 1)) },
        ),
        "accent" to RowExtra(
            SettingValue.Custom {
                AccentSwatches(presetColors, current)
                Text(hex, style = valueStyle, color = current, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.padding(start = 6.mpx))
            },
            onStep = accentStep,
            hints = listOf(
                stringResource(R.string.common_ok) to stringResource(R.string.settings_key_palette),
                "◀ ▶" to stringResource(R.string.settings_presets),
                stringResource(R.string.content_key_hold_ok) to stringResource(R.string.settings_row_menu_pin),
                stringResource(R.string.common_back) to stringResource(R.string.common_nav_settings),
            ),
        ),
        "focus_highlight" to RowExtra(SettingValue.Custom {
            Box(Modifier.size(22.mpx).background(focusColor, androidx.compose.foundation.shape.CircleShape))
            Text(focusWidthLabel(focusWidth), style = valueStyle, color = current, maxLines = 1, overflow = TextOverflow.Ellipsis)
            OwnTVIcon(OwnTVIcon.CHEVRON, tv.own.owntv.ui.theme.StageColors.Muted, Modifier.size(20.mpx))
        }),
        "tab_GLASS_EFFECT" to RowExtra(SettingValue.Opens(glassBackgroundSummary(vm.backgroundConfig.collectAsStateWithLifecycle().value, glass))),
        "fonts" to RowExtra(SettingValue.Opens(fontFamilyLabel(font.mainFamily) + sep + stringResource(R.string.common_percent, font.sizePercent))),
        // The sample popup shows as soon as the row has focus, so ◀ ▶ on the row resize it live.
        "popup_size" to RowExtra(
            SettingValue.Stepper(stringResource(R.string.common_percent, font.popupSizePercent)), onStep = popupStep,
            extra = { Box(Modifier.fillMaxWidth().padding(top = 18.mpx), contentAlignment = Alignment.Center) { tv.own.owntv.features.settings.PopupSizeSample(font.popupSizePercent) } },
        ),
        "ui_zoom" to RowExtra(SettingValue.Stepper(stringResource(R.string.common_percent, uiZoomPercent)), onStep = zoomStep),
        "animations" to RowExtra(
            SettingValue.Switch(animation == tv.own.owntv.core.theme.AnimationLevel.FULL),
            onClick = {
                vm.setAnimationLevel(
                    if (animation == tv.own.owntv.core.theme.AnimationLevel.FULL) tv.own.owntv.core.theme.AnimationLevel.OFF
                    else tv.own.owntv.core.theme.AnimationLevel.FULL,
                )
            },
        ),
        "tab_WEATHER" to RowExtra(SettingValue.Opens(
            if (!weatherOn) stringResource(R.string.common_off)
            else listOfNotNull(weatherPlace.ifBlank { null }, stringResource(if (fahrenheit) R.string.settings_degree_fahrenheit else R.string.settings_degree_celsius)).joinToString(sep),
        )),
        "navigation" to RowExtra(SettingValue.Opens(stringResource(navMenuMode.labelRes))),
        "app_icon" to RowExtra(SettingValue.Opens(stringResource(appIcon.label))),
        "brand_accent" to RowExtra(SettingValue.Switch(brandAccent), onClick = { vm.setBrandAccentTriangle(!brandAccent) }),
        "check_updates" to RowExtra(SettingValue.Action(stringResource(R.string.settings_check_now))),
        "tab_LANGUAGE" to RowExtra(help = pluralStringResource(R.plurals.settings_help_language, languages, languages)),
    ) + serviceRowExtras(vm)
}

/** EPG Sources, Metadata, OpenSubtitles, Proxy and DNS: what each is set to, and the details in the panel. */
@Composable
private fun serviceRowExtras(vm: SettingsViewModel): Map<String, RowExtra> {
    val epgVm: tv.own.owntv.features.settings.EpgSourcesViewModel = org.koin.androidx.compose.koinViewModel()
    val feeds by epgVm.sources.collectAsStateWithLifecycle()
    val mode by vm.metadataMode.collectAsStateWithLifecycle()
    val tier by vm.metadataTier.collectAsStateWithLifecycle()
    val budget by vm.metadataBudgetStatus.collectAsStateWithLifecycle()
    LaunchedEffect(tier) { if (tier == tv.own.owntv.core.metadata.MetadataConfig.Tier.DEFAULT_WORKER) vm.refreshMetadataBudget() }
    val osVm: tv.own.owntv.features.settings.OpenSubtitlesViewModel = org.koin.androidx.compose.koinViewModel()
    val os by osVm.state.collectAsStateWithLifecycle()
    val proxy by vm.proxyConfig.collectAsStateWithLifecycle()
    val dns by vm.dnsConfig.collectAsStateWithLifecycle()
    val on = stringResource(R.string.common_on)
    val off = stringResource(R.string.common_off)
    val small = tv.own.owntv.ui.theme.stageText(17, 600)
    @Composable fun line(label: String, value: String) = Row(Modifier.padding(top = 10.mpx)) {
        Text(label, style = small, color = tv.own.owntv.ui.theme.StageColors.Muted, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f))
        Text(value, style = small, color = tv.own.owntv.ui.theme.StageColors.Text, maxLines = 1, overflow = TextOverflow.Ellipsis)
    }
    val signedIn = os as? tv.own.owntv.features.settings.OpenSubtitlesViewModel.UiState.SignedIn
    val dnsHost = dns.dohUrl.ifBlank { dns.host }.removePrefix("https://").substringBefore('/')
    return mapOf(
        "tab_EPG" to RowExtra(
            SettingValue.Opens(pluralStringResource(R.plurals.settings_epg_feed_count, feeds.size, feeds.size)),
            extra = {
                // As Playlists draws its list: the name in bold, when it last synced in small muted type.
                Column(Modifier.padding(top = 18.mpx), verticalArrangement = Arrangement.spacedBy(10.mpx)) {
                    feeds.forEach { f ->
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Text(f.name, style = tv.own.owntv.ui.theme.stageText(18, 700), color = tv.own.owntv.ui.theme.StageColors.Text, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f))
                            f.lastSyncAt?.let {
                                Text(
                                    stringResource(R.string.settings_synced_at, tv.own.owntv.features.downloads.recordingWhen(it)),
                                    style = tv.own.owntv.ui.theme.stageText(15, 500), color = tv.own.owntv.ui.theme.StageColors.Muted,
                                    maxLines = 1, overflow = TextOverflow.Ellipsis,
                                )
                            }
                        }
                    }
                }
            },
        ),
        "tab_METADATA" to RowExtra(
            SettingValue.Opens(if (!mode.enrich) off else when (tier) {
                tv.own.owntv.core.metadata.MetadataConfig.Tier.DEFAULT_WORKER -> stringResource(R.string.settings_shared)
                tv.own.owntv.core.metadata.MetadataConfig.Tier.OWN_KEY -> stringResource(R.string.settings_tier_key)
                tv.own.owntv.core.metadata.MetadataConfig.Tier.SELF_HOST -> stringResource(R.string.settings_tier_self_host)
            }),
            extra = {
                val b = budget
                if (mode.enrich) Column(Modifier.padding(top = 8.mpx)) {
                    line(stringResource(R.string.settings_metadata_connection), stringResource(when (tier) {
                        tv.own.owntv.core.metadata.MetadataConfig.Tier.DEFAULT_WORKER -> R.string.settings_tier_default
                        tv.own.owntv.core.metadata.MetadataConfig.Tier.OWN_KEY -> R.string.settings_tier_key
                        tv.own.owntv.core.metadata.MetadataConfig.Tier.SELF_HOST -> R.string.settings_tier_self_host
                    }))
                    // The shared service has a daily allowance; an own key or self-host has none to show.
                    if (tier == tv.own.owntv.core.metadata.MetadataConfig.Tier.DEFAULT_WORKER && b != null) {
                        line(stringResource(R.string.settings_allowance_day), pluralStringResource(R.plurals.settings_allowance_value, b.remainingDay, b.remainingDay, b.limitDay))
                    }
                }
            },
        ),
        "tab_OPEN_SUBTITLES" to RowExtra(
            SettingValue.Opens(stringResource(if (signedIn != null) R.string.settings_open_subtitles_connected else R.string.settings_signed_out)),
            extra = {
                signedIn?.session?.let { s ->
                    Column(Modifier.padding(top = 8.mpx)) {
                        line(stringResource(R.string.player_subtitles_connected_as), s.username)
                        s.remainingDownloads?.let { r ->
                            line(stringResource(R.string.player_subtitles_downloads), pluralStringResource(R.plurals.player_subtitles_remaining_short, r, r))
                        }
                    }
                }
            },
        ),
        "tab_NETWORK" to RowExtra(SettingValue.Opens(if (proxy.usable) "${proxy.host}:${proxy.port}" else off)),
        "tab_DNS" to RowExtra(SettingValue.Opens(if (dns.enabled && dnsHost.isNotBlank()) dnsHost else if (dns.enabled) on else off)),
    )
}

/** The Settings root's category list: where focus is, and whether a group change came from moving through it. */
@androidx.compose.runtime.Stable
private class SidebarState {
    val focus = FocusRequester()
    var hasFocus by mutableStateOf(false)
    var fromSidebar = false
    /** Search was picked in the list: its field takes focus once it is on screen. */
    var pendingSearch by mutableStateOf(false)
    /** One of More's places is open beside the list instead of a settings group (its key), and how to enter it. */
    var more by mutableStateOf<String?>(null)
    /** A search result opened a place: focus goes into its page once it is on screen. */
    var pendingPlace by mutableStateOf(false)
    val moreFocus = tv.own.owntv.features.settings.MorePageFocus()

    /** Back: from the rows to the open category; from the list, out of Settings. */
    fun back(onBack: () -> Unit) {
        if (hasFocus || !runCatching { focus.requestFocus() }.getOrDefault(false)) onBack()
    }
}

/**
 * The categories of [categories] for [tv.own.owntv.features.settings.SettingsSidebar]: no counts on settings groups
 * (rule 19), only the number of profiles. Kept out of [SettingsScreen], whose body is at the JVM method limit.
 */
@Composable
private fun SettingsSidebarHost(
    categories: List<Pair<RootGroup, List<RootRow>>>,
    selected: Int,
    searching: Boolean,
    settingsVm: SettingsViewModel,
    side: SidebarState,
    modifier: Modifier,
    more: tv.own.owntv.features.settings.SettingsMoreHost?,
    onSelect: (Int) -> Unit,
    onEnterRows: () -> Unit,
    onSearch: () -> Unit,
    onExtra: () -> Unit,
    searchField: FocusRequester,
) {
    // Search picked in the list: the field appears above the rows, then takes focus with the keyboard up.
    LaunchedEffect(side.pendingSearch, searching) {
        if (!side.pendingSearch || !searching) return@LaunchedEffect
        repeat(10) {
            kotlinx.coroutines.delay(50)
            if (runCatching { searchField.requestFocus() }.getOrDefault(false)) { side.pendingSearch = false; return@LaunchedEffect }
        }
        side.pendingSearch = false
    }
    // A place opened from search: into its page (or onto its entry, when the page has no way in yet).
    LaunchedEffect(side.pendingPlace, side.more) {
        if (!side.pendingPlace || side.more == null) return@LaunchedEffect
        repeat(20) {
            withFrameNanos { }
            if (side.moreFocus.enter()) { side.pendingPlace = false; return@LaunchedEffect }
        }
        runCatching { side.focus.requestFocus() }
        side.pendingPlace = false
    }
    val profiles = tv.own.owntv.features.settings.profileCount()
    val items = SettingsGroup.entries.mapNotNull { g ->
        val (root, _) = categories.getOrNull(g.ordinal) ?: return@mapNotNull null
        tv.own.owntv.features.settings.SettingsCategory(
            g.ordinal, stringResource(g.titleRes), root.icon, if (g == SettingsGroup.PROFILE) profiles else null, mine = g.mine,
        )
    }
    // The menu's first section and the list's first entry on Live TV's line, as on every page with a list beside
    // the menu (Jonathan, 1 Nov 2026).
    androidx.compose.runtime.DisposableEffect(Unit) {
        StageRailOpen.itemsCentre = tv.own.owntv.features.epg.GuideFirstRowCentre
        onDispose { StageRailOpen.itemsCentre = null }
    }
    tv.own.owntv.features.settings.SettingsSidebar(
        alignCentre = tv.own.owntv.features.epg.GuideFirstRowCentre,
        categories = items,
        extras = more?.extras.orEmpty(),
        selected = selected,
        selectedExtra = side.more,
        searching = searching,
        onSelect = onSelect,
        onSelectExtra = { e -> side.more = e.key; onExtra() },
        header = more?.header,
        onEnterRows = onEnterRows,
        onSearch = onSearch,
        selectedFocus = side.focus,
        onFocusChanged = { side.hasFocus = it },
        modifier = modifier,
    )
}

/**
 * The root rows gathered under their group, in [SettingsGroup]'s order: each RootGroup marker starts its
 * group's rows (by [SettingsGroup.key]), wherever it sits in the list. A group without a marker is empty.
 */
private fun categoriesOf(rootItems: List<RootItem>): List<Pair<RootGroup, List<RootRow>>> {
    val markers = HashMap<String, RootGroup>()
    val rows = HashMap<String, MutableList<RootRow>>()
    var current: MutableList<RootRow>? = null
    rootItems.forEach { item ->
        when (item) {
            is RootGroup -> { markers[item.key] = item; current = rows.getOrPut(item.key) { mutableListOf() } }
            is RootRow -> current?.add(item)
        }
    }
    return SettingsGroup.entries.map { g ->
        (markers[g.key] ?: RootGroup(g.key, "", OwnTVIcon.SETTINGS, "")) to rows[g.key].orEmpty()
    }
}

/**
 * A search result that jumps to a row of a group page (rule 18): selects the row's [group], then puts the
 * cursor on it through [focus] and holds it there a few frames, then calls [onDone].
 */
@Composable
private fun RowJumpEffect(key: String?, group: Int?, focus: FocusRequester, onGroup: (Int) -> Unit, onDone: () -> Unit) {
    LaunchedEffect(key) {
        if (key == null) return@LaunchedEffect
        group?.let(onGroup)
        var settled = 0
        repeat(20) {
            withFrameNanos { }
            if (runCatching { focus.requestFocus() }.getOrDefault(false)) {
                if (++settled >= 3) { onDone(); return@LaunchedEffect }
            } else {
                settled = 0
            }
        }
        onDone()
    }
}

/** Settings search's entries for System's own rows: each jumps to its row (rule 18). */
@Composable
private fun systemSearchEntries(jumpRow: (String) -> Unit): List<SettingsSearchEntry> {
    val updates = settingsCrumb(SettingsGroup.APP, stringResource(R.string.settings_mayo_heading_updates))
    val network = settingsCrumb(SettingsGroup.APP, stringResource(R.string.settings_group_network))
    return listOf(
        SettingsSearchEntry(updates, stringResource(R.string.settings_check_updates), stringResource(R.string.settings_search_keywords_updates), OwnTVIcon.REFRESH, TileTone.PRIMARY) { jumpRow("check_updates") },
        SettingsSearchEntry(updates, stringResource(R.string.settings_update_startup), stringResource(R.string.settings_search_keywords_update_auto), OwnTVIcon.REFRESH, TileTone.SECONDARY) { jumpRow("update_startup") },
        SettingsSearchEntry(network, stringResource(R.string.common_proxy), stringResource(R.string.settings_search_keywords_proxy), OwnTVIcon.NETWORK, TileTone.SECONDARY) { jumpRow(tabRowKey(SettingsTab.NETWORK)) },
        SettingsSearchEntry(network, stringResource(R.string.settings_dns), stringResource(R.string.settings_search_keywords_dns), OwnTVIcon.DNS, TileTone.SECONDARY) { jumpRow(tabRowKey(SettingsTab.DNS)) },
    )
}

/**
 * Settings search's entries for More's places (Favourites, Backup, About …), found by their name and by
 * what can be done there; the result opens the place beside the list, as its sidebar entry does.
 */
@Composable
private fun placeSearchEntries(
    extras: List<tv.own.owntv.features.settings.SidebarExtra>,
    onOpen: (String) -> Unit,
): List<SettingsSearchEntry> {
    val sections = listOf(
        stringResource(R.string.more_group_your_things),
        stringResource(R.string.more_group_keep_safe),
        stringResource(R.string.settings_mayo_heading_info),
    )
    return extras.flatMap { e ->
        (listOf(e.label) + e.aliases).map { title ->
            SettingsSearchEntry(sections.getOrElse(e.section) { "" }, title, "", e.icon, TileTone.SECONDARY, chip = e.value) { onOpen(e.key) }
        }
    }
}

/**
 * A [SettingsTab] whose page moved (rule 21: rememberSaveable can still bring it back): Backup and Local sync
 * are places beside the list now, the remembered values sit on System.
 */
@Composable
private fun TabRedirect(tab: SettingsTab, side: SidebarState, onGroup: (Int) -> Unit, toRoot: () -> Unit) {
    LaunchedEffect(tab) {
        when (tab) {
            SettingsTab.BACKUP -> side.more = "BACKUP"
            SettingsTab.LOCAL_SYNC -> side.more = "LOCAL_SYNC"
            else -> onGroup(SettingsGroup.APP.ordinal)
        }
        toRoot()
    }
}

/** System's own rows: Updates (check now, check at start) and Network (Proxy, DNS; their state comes from stageRowExtras). */
@Composable
private fun systemRootRows(
    settingsVm: SettingsViewModel,
    updateCheckOnStart: Boolean,
    updateRowFocus: FocusRequester,
    rowFocus: Map<SettingsTab, FocusRequester>,
    open: (SettingsTab) -> Unit,
    onCheckUpdates: () -> Unit,
): List<RootRow> = listOf(
    RootRow(
        "check_updates", TileTone.PRIMARY, OwnTVIcon.REFRESH,
        heading = stringResource(R.string.settings_mayo_heading_updates),
        title = stringResource(R.string.settings_check_updates), desc = stringResource(R.string.settings_check_updates_description),
        focus = updateRowFocus,
        onClick = onCheckUpdates,
    ),
    RootRow(
        "update_startup", TileTone.SECONDARY, OwnTVIcon.REFRESH,
        title = stringResource(R.string.settings_update_startup), desc = stringResource(R.string.settings_update_startup_description),
        value = SettingValue.Switch(updateCheckOnStart),
        onClick = { settingsVm.setUpdateCheckOnStart(!updateCheckOnStart) },
    ),
    RootRow(
        tabRowKey(SettingsTab.NETWORK), TileTone.SECONDARY, OwnTVIcon.NETWORK,
        heading = stringResource(R.string.settings_group_network),
        title = stringResource(R.string.common_proxy), desc = stringResource(R.string.settings_proxy_description),
        focus = rowFocus.getValue(SettingsTab.NETWORK),
        onClick = { open(SettingsTab.NETWORK) },
    ),
    RootRow(
        tabRowKey(SettingsTab.DNS), TileTone.SECONDARY, OwnTVIcon.DNS,
        title = stringResource(R.string.settings_dns), desc = stringResource(R.string.settings_dns_description),
        focus = rowFocus.getValue(SettingsTab.DNS),
        onClick = { open(SettingsTab.DNS) },
    ),
)

/**
 * System › Remembered values: the five "reset what the player remembered" rows, right on the page (no page of
 * their own any more). [focusRowKey]: a search result for one of them puts the cursor on it.
 */
@Composable
private fun SystemSavedRows(scroll: androidx.compose.foundation.ScrollState, focusRowKey: String?) {
    val keys = tv.own.owntv.features.settings.RESET_KEYS
    StageSettingsHeading(stringResource(R.string.settings_cleanup_heading_saved), keys.size)
    CompositionLocalProvider(tv.own.owntv.features.settings.LocalVideoRowFilter provides { it in keys }) {
        VideoPlayerGroupRows(VideoGroup.RESETS, scroll, focusRowKey = focusRowKey?.takeIf { it in keys })
    }
}

/**
 * Catch-up time zone per playlist, on Settings › Playlists & EPG › Per playlist with the other overrides: the
 * same two pickers the root row used to open (the playlist, then its zone).
 */
@Composable
private fun CatchupPerPlaylistRow(settingsVm: SettingsViewModel) {
    val sources by settingsVm.sources.collectAsStateWithLifecycle()
    var pickSource by remember { mutableStateOf(false) }
    var editing by remember { mutableStateOf<tv.own.owntv.core.database.entity.SourceEntity?>(null) }
    val overrides = sources.count { it.catchupTimezone != null }
    val row = RootRow(
        "catchup_sources", TileTone.SECONDARY, OwnTVIcon.CATCHUP,
        title = stringResource(R.string.settings_catchup_timezone_per_playlist),
        desc = stringResource(R.string.settings_catchup_timezone_per_playlist_description),
        chip = if (overrides == 0) stringResource(R.string.common_off)
            else pluralStringResource(R.plurals.settings_live_preroll_overrides, overrides, overrides),
        chipTone = if (overrides > 0) TileTone.PRIMARY else TileTone.SECONDARY,
        onClick = { pickSource = true },
    )
    RootRowStage(row, pinned = false, onLongClick = null)
    if (pickSource) {
        CatchupSourcesHost(sources, editing, onPick = { picked ->
            editing = picked
            pickSource = false
        }, onDismiss = { editing = null; pickSource = false })
    }
    editing?.let { src ->
        if (!pickSource) CatchupSourceValueHost(settingsVm, sources.firstOrNull { it.id == src.id } ?: src) {
            editing = null
            pickSource = true
        }
    }
}

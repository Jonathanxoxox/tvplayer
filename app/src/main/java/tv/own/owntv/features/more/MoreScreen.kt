package tv.own.owntv.features.more

import androidx.compose.foundation.layout.size
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.focusGroup
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.withFrameNanos
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.focus.focusProperties
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.em
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.tv.material3.Text
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.koin.androidx.compose.koinViewModel
import tv.own.owntv.BuildConfig
import tv.own.owntv.R
import tv.own.owntv.features.settings.BackupScreen
import tv.own.owntv.features.settings.DeveloperScreen
import tv.own.owntv.features.settings.LocalSyncScreen
import tv.own.owntv.features.settings.SettingsViewModel
import tv.own.owntv.features.shell.components.RailAvatar
import tv.own.owntv.player.PlaybackErrorLog
import tv.own.owntv.ui.components.OwnTVIcon
import tv.own.owntv.ui.stage.StageGroupItem
import tv.own.owntv.ui.stage.StageKeyHints
import tv.own.owntv.ui.stage.StageSurface
import tv.own.owntv.ui.theme.StageColors
import tv.own.owntv.ui.theme.mpx
import tv.own.owntv.ui.theme.stageAccent
import tv.own.owntv.ui.theme.stageText

/** The sheet's items, top to bottom. [DEVELOPER] exists only in local builds (BuildConfig.DEV_TOOLS). */
private enum class MoreItem { SETTINGS, FAVORITES, HISTORY, BACKUP, LOCAL_SYNC, ERROR_LOG, ABOUT, DEVELOPER }

/**
 * More: the glass section sheet, topped by the profile, and one page on the right
 * for the item chosen with OK. Two layers, never three: ▲ ▼ move focus, OK selects the item (focus stays), ▶ enters the page, Back returns to
 * the sheet, Back again leaves More. Nothing opens full screen any more — Settings is the one door out,
 * through its group cards.
 */
@Composable
fun MoreScreen(
    profileName: String,
    playlistLabel: String,
    avatarId: Int,
    avatarPath: String,
    onSwitchProfile: () -> Unit,
    /** OK on the avatar: the avatar picker, with "Your own picture". */
    onPickAvatar: () -> Unit,
    onOpenSettings: (group: Int?, search: Boolean) -> Unit,
    onPlayChannel: (Long) -> Unit,
    onPlayMovie: (Long, Long) -> Unit,
    onPlayEpisode: (seriesId: Long, episodeId: Long, positionMs: Long) -> Unit,
    onOpenSeries: (Long) -> Unit,
    onChildFocused: () -> Unit,
    onEntryHook: (((() -> Boolean)?) -> Unit)? = null,
    /** Back from Settings: land on the Settings page's first card instead of the rail. */
    restoreFocus: Boolean = false,
    onRestored: () -> Unit = {},
    modifier: Modifier = Modifier,
    vm: MoreCountsViewModel = koinViewModel(),
    settingsVm: SettingsViewModel = koinViewModel(),
) {
    // Mayo TV (1 Nov 2026): Settings opens straight away (its categories are its own list now), so the page
    // shown beside the sheet starts on Favourites.
    var selected by rememberSaveable { mutableStateOf(MoreItem.FAVORITES) }
    val itemFocus = remember { MoreItem.entries.associateWith { FocusRequester() } }
    // What OK / ▶ focuses on the page: a fixed target, or the page's own choice (its first row).
    val pageTarget = remember { FocusRequester() }
    var pageEntry by remember { mutableStateOf<(() -> Boolean)?>(null) }
    var pageFocused by remember { mutableStateOf(false) }
    val enterPage: () -> Boolean = { pageEntry?.invoke() ?: runCatching { pageTarget.requestFocus() }.getOrDefault(false) }
    val backToSheet: () -> Unit = { runCatching { itemFocus.getValue(selected).requestFocus() } }
    // Like the rail: ▲ ▼ move focus, OK selects the item (its page shows, focus stays on the item),
    // ▶ goes into the item's page. ▶ on an item not chosen yet chooses it first: it used to enter the page still
    // shown, so ▶ on History opened Settings.
    val choose: (MoreItem) -> Unit = { item -> if (item != selected) { selected = item; pageEntry = null } }
    var focusedItem by remember { mutableStateOf<MoreItem?>(null) }
    val scope = rememberCoroutineScope()
    val enterItem: () -> Boolean = enter@{
        // On the profile row ▶ moves along the row (picture → Switch), as everywhere else.
        val item = focusedItem ?: return@enter false
        if (item == MoreItem.SETTINGS) { onOpenSettings(null, false); return@enter true }
        if (item == selected) return@enter enterPage()
        choose(item)
        // The new page is composed on the next frame; its entry is only known after that.
        scope.launch {
            withFrameNanos { }
            withFrameNanos { }
            enterPage()
        }
        true
    }

    // The rail's ▶ lands on the item the sheet was last on.
    DisposableEffect(onEntryHook) {
        onEntryHook?.invoke { runCatching { itemFocus.getValue(selected).requestFocus() }.getOrDefault(false) }
        onDispose { onEntryHook?.invoke(null) }
    }
    LaunchedEffect(restoreFocus) {
        if (!restoreFocus) return@LaunchedEffect
        withFrameNanos { }
        withFrameNanos { }
        // Back from Settings lands on its item in the sheet.
        runCatching { itemFocus.getValue(MoreItem.SETTINGS).requestFocus() }
        onRestored()
    }
    // Composed before the page, so a page's own Back (a step popup, a dialog) still wins over it.
    BackHandler(enabled = pageFocused) { backToSheet() }

    val favorites by vm.favorites.collectAsStateWithLifecycle()
    val history by vm.history.collectAsStateWithLifecycle()
    val sync by vm.sync.collectAsStateWithLifecycle()
    val lastBackup by vm.lastBackup.collectAsStateWithLifecycle()
    val playlists by vm.playlistCount.collectAsStateWithLifecycle()
    val pinned by settingsVm.quickPinnedKeys.collectAsStateWithLifecycle()
    val context = LocalContext.current
    var logVersion by remember { mutableIntStateOf(0) }
    val logCount by produceState(0, logVersion) { value = withContext(Dispatchers.IO) { PlaybackErrorLog.read(context).size } }

    Box(modifier.fillMaxSize().onFocusChanged { if (it.hasFocus) onChildFocused() }) {
        // --- The sheet: a fixed panel, 440 wide over the full height, a shade apart with a line at its edge —
        // the same panel the guide's categories sit on (Jonathan, 27 Oct 2026: no floating card here either).
        val palette = tv.own.owntv.ui.theme.LocalStagePalette.current
        Column(
            Modifier
                .width(440.mpx)
                .fillMaxHeight()
                .background(palette.glassTint)
                .drawBehind {
                    val line = 2.mpx.toPx()
                    drawRect(palette.ink.copy(alpha = 0.12f), Offset(size.width - line, 0f), Size(line, size.height))
                }
                .padding(start = 20.mpx, end = 20.mpx, top = 48.mpx, bottom = 32.mpx)
                // ◀ back from the page lands on the item that page belongs to.
                .focusProperties { onEnter = { runCatching { itemFocus.getValue(selected).requestFocus() } } }
                .onPreviewKeyEvent { e ->
                    e.type == KeyEventType.KeyDown && e.key == Key.DirectionRight && enterItem()
                }
                .focusGroup(),
        ) {
            ProfileRow(profileName, playlistLabel, playlists, avatarId, avatarPath, onSwitchProfile, onPickAvatar)
            val item = @Composable { it: MoreItem, icon: OwnTVIcon, label: String, value: String?, warn: Boolean ->
                StageGroupItem(
                    text = label, icon = icon, value = value, warn = warn,
                    selected = selected == it,
                    onClick = { if (it == MoreItem.SETTINGS) onOpenSettings(null, false) else choose(it) },
                    modifier = Modifier.focusRequester(itemFocus.getValue(it))
                        .onFocusChanged { f -> if (f.hasFocus) focusedItem = it else if (focusedItem == it) focusedItem = null },
                )
            }
            item(MoreItem.SETTINGS, OwnTVIcon.SETTINGS, stringResource(R.string.common_nav_settings), null, false)
            GroupLabel(stringResource(R.string.more_group_your_things))
            item(MoreItem.FAVORITES, OwnTVIcon.FAVORITE, stringResource(R.string.content_category_favorites), favorites.total.toString(), false)
            item(MoreItem.HISTORY, OwnTVIcon.HISTORY, stringResource(R.string.content_category_history), history.total.toString(), false)
            GroupLabel(stringResource(R.string.more_group_keep_safe))
            item(
                MoreItem.BACKUP, OwnTVIcon.DOWNLOADS, stringResource(R.string.tvplayer_more_backup),
                lastBackup?.let { relativeShort(it.at) } ?: stringResource(R.string.common_never), lastBackup == null,
            )
            item(MoreItem.LOCAL_SYNC, OwnTVIcon.PHONE, stringResource(R.string.local_sync_title), stringResource(if (sync.listening) R.string.common_on else R.string.common_off), false)
            GroupLabel(stringResource(R.string.settings_app_group))
            item(MoreItem.ERROR_LOG, OwnTVIcon.WARNING, stringResource(R.string.settings_playback_error_log), logCount.toString(), false)
            item(MoreItem.ABOUT, OwnTVIcon.INFO, stringResource(R.string.settings_about), BuildConfig.VERSION_NAME, false)
            // Maintainer-only; DEV_TOOLS is a compile-time constant, so R8 removes it from published APKs.
            if (BuildConfig.DEV_TOOLS) item(MoreItem.DEVELOPER, OwnTVIcon.GEAR, "Developer", "DEV", false)
            Spacer(Modifier.weight(1f))
            StageKeyHints(
                listOf(
                    stringResource(R.string.common_ok) to stringResource(R.string.content_key_select),
                    "▶" to stringResource(R.string.content_key_enter),
                    stringResource(R.string.common_back) to stringResource(R.string.content_key_menu),
                ),
                Modifier.padding(start = 14.mpx),
                textSize = 15,
            )
        }

        // --- The page: everything right of the sheet, below the top-right cluster.
        Box(
            Modifier
                .padding(start = 504.mpx, top = 128.mpx, end = 64.mpx)
                .fillMaxSize()
                .onFocusChanged { pageFocused = it.hasFocus },
        ) {
            val setEntry: (() -> Boolean) -> Unit = { pageEntry = it }
            when (selected) {
                // Opens Settings itself (see the sheet's item); a page restored from before shows Favourites.
                MoreItem.SETTINGS -> FavouritesPage(
                    vm = vm, setEntry = setEntry,
                    onPlayChannel = onPlayChannel, onPlayMovie = { onPlayMovie(it, 0L) }, onOpenSeries = onOpenSeries,
                )
                MoreItem.FAVORITES -> FavouritesPage(
                    vm = vm, setEntry = setEntry,
                    onPlayChannel = onPlayChannel, onPlayMovie = { onPlayMovie(it, 0L) }, onOpenSeries = onOpenSeries,
                )
                MoreItem.HISTORY -> HistoryPage(
                    vm = vm, setEntry = setEntry,
                    onClearHistory = { settingsVm.clearWatchHistory(it) },
                    onPlayChannel = onPlayChannel, onPlayMovie = onPlayMovie, onPlayEpisode = onPlayEpisode, onOpenSeries = onOpenSeries,
                )
                MoreItem.BACKUP -> BackupScreen(entry = pageTarget, modifier = Modifier.fillMaxSize())
                MoreItem.LOCAL_SYNC -> LocalSyncScreen(entry = pageTarget, modifier = Modifier.fillMaxSize())
                MoreItem.ERROR_LOG -> ErrorLogPage(setEntry = setEntry, onCountChanged = { logVersion++ })
                MoreItem.ABOUT -> AboutPage(vm = vm, entry = pageTarget, onOpenLanguage = { onOpenSettings(tv.own.owntv.features.shell.components.SettingsGroup.LANGUAGE.ordinal, false) })
                MoreItem.DEVELOPER -> Box(Modifier.focusRequester(pageTarget).focusGroup()) { DeveloperScreen(onBack = backToSheet) }
            }
        }
    }
}

/** The sheet's head: avatar 58 (OK changes it), name 24/800, "All playlists · 3 playlists", and **Switch**. */
@Composable
private fun ProfileRow(name: String, playlistLabel: String, playlists: Int, avatarId: Int, avatarPath: String, onSwitch: () -> Unit, onPickAvatar: () -> Unit) {
    Row(
        Modifier.fillMaxWidth().padding(start = 14.mpx, end = 14.mpx, top = 4.mpx, bottom = 18.mpx),
        horizontalArrangement = Arrangement.spacedBy(16.mpx),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        StageSurface(onClick = onPickAvatar, radius = 29.mpx, modifier = Modifier.size(58.mpx), focusStyle = tv.own.owntv.ui.stage.StageFocus.POSTER, contentAlignment = Alignment.Center) {
            RailAvatar(name.take(1).uppercase(), size = 58, textSize = 24, avatarId = avatarId, imagePath = avatarPath)
        }
        Column(Modifier.weight(1f)) {
            Text(name, style = stageText(24, 800), color = StageColors.Text, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Text(
                playlistLabel + stringResource(R.string.content_epg_bits_separator) + pluralStringResource(R.plurals.more_playlist_count, playlists, playlists),
                style = stageText(15.5f, 500), color = StageColors.Muted, maxLines = 1, overflow = TextOverflow.Ellipsis,
            )
        }
        StageSurface(
            onClick = onSwitch, radius = 15.mpx, modifier = Modifier.height(40.mpx),
            idle = Modifier.background(StageColors.ControlFill, RoundedCornerShape(15.mpx)),
            contentAlignment = Alignment.Center,
        ) { focused ->
            Text(
                stringResource(R.string.more_profile_switch), style = stageText(15, 700),
                color = if (focused) stageAccent.onAccent else StageColors.Text, maxLines = 1, overflow = TextOverflow.Ellipsis,
                modifier = Modifier.padding(horizontal = 16.mpx),
            )
        }
    }
}

/** `.gsheet .gl`: 12.5/800 +0.13em in dim, upper-cased in the user's own locale. */
@Composable
private fun GroupLabel(text: String) {
    Text(
        text.uppercase(androidx.compose.ui.platform.LocalConfiguration.current.locales[0]),
        style = stageText(12.5f, 800, 0.13.em), color = StageColors.Dim, maxLines = 1, overflow = TextOverflow.Ellipsis,
        modifier = Modifier.padding(start = 16.mpx, end = 16.mpx, top = 16.mpx, bottom = 6.mpx),
    )
}

/** "2 days ago" — Android's own relative time, so it is localised without a string of ours. */
private fun relativeShort(at: Long): String =
    android.text.format.DateUtils.getRelativeTimeSpanString(
        at, System.currentTimeMillis(), android.text.format.DateUtils.MINUTE_IN_MILLIS,
    ).toString()

// ---------------------------------------------------------------------------------------------
// Mayo TV (1 Nov 2026): More and Settings are one screen. Settings' list on the left carries More's
// profile and places; these draw them there.
// ---------------------------------------------------------------------------------------------

/** More's places for Settings' list: your things first, then keeping it safe, then the app. */
@Composable
fun moreSidebarExtras(vm: MoreCountsViewModel = koinViewModel()): List<tv.own.owntv.features.settings.SidebarExtra> {
    val favorites by vm.favorites.collectAsStateWithLifecycle()
    val history by vm.history.collectAsStateWithLifecycle()
    val sync by vm.sync.collectAsStateWithLifecycle()
    val lastBackup by vm.lastBackup.collectAsStateWithLifecycle()
    val context = LocalContext.current
    val logCount by produceState(0) { value = withContext(Dispatchers.IO) { PlaybackErrorLog.read(context).size } }
    return listOf(
        tv.own.owntv.features.settings.SidebarExtra(MoreItem.FAVORITES.name, 0, OwnTVIcon.FAVORITE, stringResource(R.string.content_category_favorites), favorites.total.toString()),
        tv.own.owntv.features.settings.SidebarExtra(MoreItem.HISTORY.name, 0, OwnTVIcon.HISTORY, stringResource(R.string.content_category_history), history.total.toString()),
        tv.own.owntv.features.settings.SidebarExtra(
            MoreItem.BACKUP.name, 1, OwnTVIcon.DOWNLOADS, stringResource(R.string.tvplayer_more_backup),
            lastBackup?.let { relativeShort(it.at) } ?: stringResource(R.string.common_never), warn = lastBackup == null,
        ),
        tv.own.owntv.features.settings.SidebarExtra(MoreItem.LOCAL_SYNC.name, 1, OwnTVIcon.PHONE, stringResource(R.string.local_sync_title), stringResource(if (sync.listening) R.string.common_on else R.string.common_off)),
        tv.own.owntv.features.settings.SidebarExtra(MoreItem.ERROR_LOG.name, 2, OwnTVIcon.WARNING, stringResource(R.string.settings_playback_error_log), logCount.toString()),
        tv.own.owntv.features.settings.SidebarExtra(MoreItem.ABOUT.name, 2, OwnTVIcon.INFO, stringResource(R.string.settings_about), BuildConfig.VERSION_NAME),
    )
}

/** The profile at the top of Settings' list: avatar (OK changes it), name, playlists and Switch. */
@Composable
fun MoreProfileHeader(
    profileName: String,
    playlistLabel: String,
    avatarId: Int,
    avatarPath: String,
    onSwitchProfile: () -> Unit,
    onPickAvatar: () -> Unit,
    vm: MoreCountsViewModel = koinViewModel(),
) {
    val playlists by vm.playlistCount.collectAsStateWithLifecycle()
    ProfileRow(profileName, playlistLabel, playlists, avatarId, avatarPath, onSwitchProfile, onPickAvatar)
}

/** One of More's pages beside Settings' list, by the key of its [tv.own.owntv.features.settings.SidebarExtra]. */
@Composable
fun MorePlacePage(
    key: String,
    focus: tv.own.owntv.features.settings.MorePageFocus,
    onOpenLanguage: () -> Unit,
    onPlayChannel: (Long) -> Unit,
    onPlayMovie: (Long, Long) -> Unit,
    onPlayEpisode: (seriesId: Long, episodeId: Long, positionMs: Long) -> Unit,
    onOpenSeries: (Long) -> Unit,
    modifier: Modifier = Modifier,
    vm: MoreCountsViewModel = koinViewModel(),
    settingsVm: SettingsViewModel = koinViewModel(),
) {
    // A page hands over its own way in; a new page starts without one.
    DisposableEffect(key) { onDispose { focus.entry = null } }
    val setEntry: (() -> Boolean) -> Unit = { focus.entry = it }
    Box(modifier) {
        when (key) {
            MoreItem.FAVORITES.name -> FavouritesPage(
                vm = vm, setEntry = setEntry,
                onPlayChannel = onPlayChannel, onPlayMovie = { onPlayMovie(it, 0L) }, onOpenSeries = onOpenSeries,
            )
            MoreItem.HISTORY.name -> HistoryPage(
                vm = vm, setEntry = setEntry,
                onClearHistory = { settingsVm.clearWatchHistory(it) },
                onPlayChannel = onPlayChannel, onPlayMovie = onPlayMovie, onPlayEpisode = onPlayEpisode, onOpenSeries = onOpenSeries,
            )
            MoreItem.BACKUP.name -> BackupScreen(entry = focus.target, modifier = Modifier.fillMaxSize())
            MoreItem.LOCAL_SYNC.name -> LocalSyncScreen(entry = focus.target, modifier = Modifier.fillMaxSize())
            MoreItem.ERROR_LOG.name -> ErrorLogPage(setEntry = setEntry, onCountChanged = {})
            MoreItem.ABOUT.name -> AboutPage(vm = vm, entry = focus.target, onOpenLanguage = onOpenLanguage)
        }
    }
}

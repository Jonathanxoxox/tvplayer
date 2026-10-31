package tv.own.owntv.features.epg

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.focusGroup
import androidx.compose.foundation.focusable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.draw.drawBehind
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.gestures.scrollBy
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.layout.boundsInRoot
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusProperties
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.tv.material3.Text
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import org.koin.androidx.compose.koinViewModel
import tv.own.owntv.R
import tv.own.owntv.core.database.entity.ChannelEntity
import tv.own.owntv.core.database.entity.EpgProgrammeEntity
import tv.own.owntv.core.live.LiveKey
import tv.own.owntv.core.model.RecordingStatus
import tv.own.owntv.core.settings.SettingsRepository
import tv.own.owntv.features.live.LiveCategories
import tv.own.owntv.features.live.LiveViewModel
import tv.own.owntv.core.live.ProviderTags
import tv.own.owntv.features.shell.components.CategoryContextMenu
import tv.own.owntv.ui.components.MoveOrderOverlay
import tv.own.owntv.features.live.liveCategoryEntries
import tv.own.owntv.features.live.liveEpgShiftLabel
import tv.own.owntv.ui.components.OwnTVIcon
import tv.own.owntv.ui.components.LandmarkLoader
import tv.own.owntv.ui.stage.StageButton
import tv.own.owntv.ui.stage.shownColor
import tv.own.owntv.ui.theme.StageColors
import tv.own.owntv.ui.theme.mpx
import tv.own.owntv.ui.theme.stageAccent
import tv.own.owntv.ui.theme.stageText

@Composable
private fun epgMessageText(message: EpgMessage): String = when (message) {
    EpgMessage.CreateProfile -> stringResource(R.string.content_epg_create_profile)
    EpgMessage.AddPlaylist -> stringResource(R.string.content_epg_add_playlist)
    is EpgMessage.NoChannelsForQuery -> stringResource(R.string.content_epg_no_channels_query, message.query)
    EpgMessage.MismatchedIds -> stringResource(R.string.content_epg_mismatched_ids)
}

/** The one line an auto-match outcome reads as — beside the Guide's button, and in the shell's toast off it. */
@Composable
internal fun epgMatchSummaryText(summary: EpgMatchSummary): String = when (summary) {
    EpgMatchSummary.CatchupUnavailable -> stringResource(R.string.content_epg_catchup_unavailable)
    EpgMatchSummary.MatchedNoProgrammes -> stringResource(R.string.content_epg_matched_no_programmes)
    EpgMatchSummary.AddPlaylist -> stringResource(R.string.content_epg_add_playlist_first)
    EpgMatchSummary.NoData -> stringResource(R.string.content_epg_no_match_data)
    EpgMatchSummary.AllMatched -> stringResource(R.string.content_epg_all_matched)
    is EpgMatchSummary.NoMatch -> stringResource(R.string.content_epg_no_match, summary.channelName)
    is EpgMatchSummary.AutoMatched -> if (summary.review > 0) {
        stringResource(
            R.string.content_epg_auto_matched,
            pluralStringResource(R.plurals.content_epg_auto_matched_applied, summary.applied, summary.applied),
            pluralStringResource(R.plurals.content_epg_auto_matched_review, summary.review, summary.review),
        )
    } else {
        pluralStringResource(R.plurals.content_epg_auto_matched_no_review, summary.applied, summary.applied)
    }
}

/** The programme on at [time] in a row, or null in a stretch with nothing on. */
private fun programmeAt(progs: List<EpgProgrammeEntity>?, time: Long): EpgProgrammeEntity? =
    progs?.firstOrNull { time in it.startMs until it.stopMs }

/** Across a gap the cursor moves by half an hour, rather than leaping hours to the next programme. */
private const val GAP_STEP_MS = 30L * 60 * 1000

/** Where ◀ ([dir] -1) or ▶ (+1) takes the cursor: programme by programme, half an hour at a time across gaps. */
private fun cursorStep(progs: List<EpgProgrammeEntity>, cursor: Long, dir: Int, windowStart: Long, windowEnd: Long): Long {
    val cur = programmeAt(progs, cursor)
    val target = if (dir > 0) {
        val from = cur?.stopMs ?: cursor
        val next = progs.firstOrNull { it.startMs >= from && it.startMs > cursor }
        when {
            cur != null && (next == null || next.startMs - cur.stopMs > GAP_STEP_MS) -> cur.stopMs
            cur != null -> next!!.startMs
            next != null && next.startMs - cursor <= GAP_STEP_MS -> next.startMs
            else -> cursor + GAP_STEP_MS
        }
    } else {
        val until = cur?.startMs ?: cursor
        val prev = progs.lastOrNull { it.stopMs <= until }
        when {
            cur != null && (prev == null || cur.startMs - prev.stopMs > GAP_STEP_MS) -> cur.startMs - GAP_STEP_MS
            cur != null -> prev!!.startMs
            prev != null && cursor - prev.stopMs <= GAP_STEP_MS -> prev.startMs
            else -> cursor - GAP_STEP_MS
        }
    }
    return target.coerceIn(windowStart, windowEnd - 1)
}

/**
 * The TV Guide in the Stage design: the focused channel's preview and the focused programme
 * with its actions on top; one control line (Today · Now · Category · Order · Search · Auto-match EPG);
 * the ruler with the now-line; 8 slim rows of channel labels and programme cells.
 *
 * Each row's programmes are one focus target with a time cursor (◀ ▶ move it programme by programme,
 * ▲ ▼ step rows at the same time), so a week of guide stays one node per row. OK watches — the channel,
 * or a programme that has been on from the archive; holding OK or ☰ opens the programme menu.
 *
 * With [liveMode] this is Live TV's Guide view: the category is Live TV's own, the title says
 * Live TV, and List view goes back to the channel list.
 */
@OptIn(androidx.compose.foundation.ExperimentalFoundationApi::class)
@Composable
fun EpgScreen(
    modifier: Modifier = Modifier,
    onFullscreen: () -> Unit = {},
    onAddEpg: () -> Unit = {},
    restoreFocus: Boolean = false,
    onRestored: () -> Unit = {},
    /** Required: every live tune in the app goes through the one shared path in LiveViewModel, so a
     *  channel gets the same Prefer HLS handling, ExoPlayer→mpv ladder, per-channel engine pin and
     *  external-player routing however the user reached it. */
    onPlayChannel: (channel: ChannelEntity, channels: List<ChannelEntity>) -> Unit,
    /** "Watch from start" on a catch-up programme. Required for the same reason, and additionally so the
     *  archive is tracked as catch-up playback (which decides what engine toggle the player HUD offers). */
    onPlayCatchup: (channel: ChannelEntity, programme: EpgProgrammeEntity) -> Unit,
    /** False while the full-screen or mini player owns the video surface. */
    previewEnabled: Boolean = true,
    /** Laid out unseen behind full screen: it follows the channel playing, so Back finds its row in place. */
    behindFullscreen: Boolean = false,
    /** Waiting behind full screen, not yet gliding back: the rows load no channel logos (they start with the glide). */
    logosPaused: Boolean = false,
    liveMode: Boolean = false,
    /** How ▶ from the rail enters the guide: at the grid row focus was on (the rail's entry hook, as Home's). */
    onEntryHook: (((() -> Boolean)?) -> Unit)? = null,
    /** Holding ☰ in the grid: search. Blue: settings. */
    onOpenSearch: () -> Unit = {},
    onOpenSettings: () -> Unit = {},
) {
    val vm: EpgViewModel = koinViewModel()
    // Live TV's view model: its preview player (no second decoder), its category list and playlist marks.
    val liveVm: LiveViewModel = koinViewModel()
    val state by vm.state.collectAsStateWithLifecycle()
    // Ticks on the minute, not every 30 s from whenever the guide opened: the clock chip, the now-line and
    // the programme lit as "on now" then change when the minute does, not up to half a minute after it.
    val liveNow by produceState(initialValue = System.currentTimeMillis()) {
        while (true) {
            delay(60_000L - System.currentTimeMillis() % 60_000L + 20L)
            value = System.currentTimeMillis()
        }
    }
    val query by vm.query.collectAsStateWithLifecycle()
    // The groups' search, which the guide's channels follow (see the grid below).
    val groupsQuery by vm.categoryQuery.collectAsStateWithLifecycle()
    val searching = groupsQuery.isNotBlank()
    val matching by vm.matching.collectAsStateWithLifecycle()
    val review by vm.review.collectAsStateWithLifecycle()
    val matchSummary by vm.matchSummary.collectAsStateWithLifecycle()
    val sortGuide by vm.sortGuide.collectAsStateWithLifecycle()
    val categoryFilter by vm.categoryFilter.collectAsStateWithLifecycle()
    val guideWidthShares by vm.guideWidthShares.collectAsStateWithLifecycle()
    val catchupPlayer by vm.catchupPlayer.collectAsStateWithLifecycle()
    val recordingRows by vm.recordingRows.collectAsStateWithLifecycle()
    val reminderRows by vm.reminderRows.collectAsStateWithLifecycle()
    val leadMinutes by vm.reminderLeadMinutes.collectAsStateWithLifecycle()
    val allRailItems by liveVm.railItems.collectAsStateWithLifecycle()
    // The guide's second column lists the groups, with Favourites on top: no All channels, Catch-up or
    // Recently watched (Jonathan, 27 Oct 2026; Favourites stay so the start mode "Favourites" has its group).
    val railItems = remember(allRailItems) {
        allRailItems.filter { it.key is LiveKey.Folder || it.key is LiveKey.Custom || it.key == LiveKey.Favorites }
    }
    // Where the guide falls back to when its list is not on offer: the first provider group, not Favourites.
    val firstGroupKey = remember(railItems) { (railItems.firstOrNull { it.key != LiveKey.Favorites } ?: railItems.firstOrNull())?.key }
    val liveSelected by liveVm.selectedKey.collectAsStateWithLifecycle()
    val playlistMarks by liveVm.playlistMarks.collectAsStateWithLifecycle()
    val livePreviewSetting by liveVm.livePreviewEnabled.collectAsStateWithLifecycle()
    val previewChannel by liveVm.previewChannel.collectAsStateWithLifecycle()
    val previewArmed by liveVm.previewArmed.collectAsStateWithLifecycle()
    val guidePreview by vm.guidePreview.collectAsStateWithLifecycle()

    // Live TV's Guide view shows Live TV's own category; the TV Guide keeps its own choice.
    val categoryKey: LiveKey? = if (liveMode) liveSelected.takeUnless { it == LiveKey.All } else categoryFilter
    LaunchedEffect(liveMode, liveSelected) { if (liveMode) vm.setCategoryFilter(liveSelected) }
    // With only the groups on offer, the guide never stays on a list it cannot show: it opens on the playing
    // channel's group, else on the first group.
    LaunchedEffect(liveMode, liveSelected, categoryFilter, railItems) {
        val current = if (liveMode) liveSelected else categoryFilter
        if (railItems.isEmpty() || railItems.any { it.key == current }) return@LaunchedEffect
        val own = previewChannel?.categoryId?.let { LiveKey.Folder(it) }?.takeIf { key -> railItems.any { it.key == key } }
        val target = own ?: firstGroupKey ?: return@LaunchedEffect
        if (liveMode) liveVm.select(target) else vm.setCategoryFilter(target)
    }
    // The provider's short guide covers a few hours only, and the rows keep what they read: a guide left open
    // (or waiting behind full screen) for an hour drew programmes that had ended and blanks after them. Off
    // the picture, rows read from the provider are read again every few minutes; behind it, nothing is.
    LaunchedEffect(liveNow, behindFullscreen) {
        if (behindFullscreen) return@LaunchedEffect
        delay(1_500) // not while the picture is still gliding back into the corner
        vm.refreshProviderRows()
    }
    LaunchedEffect(Unit) {
        // Behind the first full-screen picture of the process the guide would be built while the decoder starts:
        // it waits for the picture (a gate that is open from then on, so later visits cost nothing).
        if (behindFullscreen) tv.own.owntv.StartupGate.awaitFirstFrame()
        vm.load() // reload from DB each time the guide is opened
    }

    // The preview stays on its channel while the guide is browsed; OK on a row brings that row's channel
    // into it, and OK again on the channel already there watches it full screen. Following the focus made
    // every row passed on the way to another one play for a moment.
    val effectivePreview = previewEnabled && livePreviewSetting && guidePreview
    val previewNeeds: (ChannelEntity) -> Boolean = { ch -> effectivePreview && (!previewArmed || previewChannel?.id != ch.id) }
    // Switching the Guide's own preview off stops what is already playing, not only the next channel.
    LaunchedEffect(guidePreview) { if (!guidePreview && previewEnabled) liveVm.stopPreview() }
    LaunchedEffect(previewChannel?.id, effectivePreview, previewArmed) {
        if (!effectivePreview || !previewArmed) return@LaunchedEffect
        val ch = previewChannel ?: return@LaunchedEffect
        delay(700)
        // With its sound, as in TiviMate: the guide's preview is a channel picked with OK (or the one just
        // watched full screen), never one merely passed on the way (Jonathan, 27 Oct 2026).
        liveVm.playPreview(ch, audible = true)
    }

    val scope = rememberCoroutineScope()
    val density = LocalDensity.current
    val rowListState = rememberLazyListState()
    // How the rows scroll to a focused one: Live TV's edge scroll, but with a whole row and its gap of room,
    // the same room the instant steps in stepTo keep, so a glide and a step end in the same place.
    val rowGapPx = with(density) { GuideGridDefaults.RowGap.toPx() }
    val guideScrollSpec = remember(rowGapPx) {
        object : androidx.compose.foundation.gestures.BringIntoViewSpec {
            override fun calculateScrollDistance(offset: Float, size: Float, containerSize: Float): Float {
                val room = (size + rowGapPx).coerceAtMost((containerSize - size).coerceAtLeast(0f) / 2f)
                val lead = offset - room
                val trailing = offset + size + room - containerSize
                return when {
                    lead >= 0f && trailing <= 0f -> 0f
                    lead < 0f || size > containerSize -> lead
                    else -> trailing
                }
            }
        }
    }

    // --- The time axis: one scroll position shared by the ruler, the now-line and every row. ----------
    val ppm = with(density) { GuideGridDefaults.PxPerMin.toPx() }
    var timelinePx by remember { mutableIntStateOf(0) }
    var scrollPx by remember { mutableIntStateOf(0) }
    fun pxAt(t: Long): Float = (t - state.windowStart) / 60_000f * ppm
    fun maxScroll(): Int = (pxAt(state.windowEnd) - timelinePx).toInt().coerceAtLeast(0)
    fun timeAt(px: Float): Long = state.windowStart + (px / ppm * 60_000f).toLong()
    // As in TiviMate the grid starts at the half hour a time falls in (14:00 at 14:05), so what is on now
    // fills the screen instead of an hour of what has been (Jonathan, 27 Oct 2026).
    fun halfHourOf(t: Long): Long = t - Math.floorMod(t, GuideGridDefaults.SlotMin * 60_000L)
    fun showTime(t: Long) {
        scrollPx = pxAt(halfHourOf(t)).toInt().coerceIn(0, maxScroll())
    }
    LaunchedEffect(state.windowStart, timelinePx, state.channels.isNotEmpty()) {
        if (timelinePx > 0 && state.channels.isNotEmpty()) showTime(System.currentTimeMillis())
    }

    // --- Rows, focus and the cursor ------------------------------------------------------------------
    val rowRequesters = remember { HashMap<Int, FocusRequester>() }
    fun rowFocus(i: Int): FocusRequester = rowRequesters.getOrPut(i) { FocusRequester() }
    var focusedRow by remember { mutableIntStateOf(0) }
    // The channel Back or EPG from the player restored to, held across the silent reload that follows.
    var anchorId by remember { mutableStateOf<Long?>(null) }
    // The category the cursor is on in the groups column (applied a moment later, or at once by ▶).
    var browsedGroup by remember { mutableStateOf<Int?>(null) }
    // The group just applied, until its list is in (see the effect beside applyGroup), and whether the grid
    // takes the focus then (OK, or ▶ before the list was in).
    var openingGroup by remember { mutableStateOf<LiveKey?>(null) }
    var focusOnOpen by remember { mutableStateOf(false) }
    // A channel's number is its place in the list on screen: each category counts from 1, as in TiviMate
    // (Jonathan, 27 Oct 2026: the provider's 1351 in a category of twenty read like the whole list).
    fun listNumber(ch: ChannelEntity): Int? = state.channels.indexOfFirst { it.id == ch.id }.takeIf { it >= 0 }?.plus(1)
    var gridHasFocus by remember { mutableStateOf(false) }
    var cursorTime by remember { mutableStateOf(0L) }
    // Row stage (false): ▲ ▼ move between channels, ◀ leaves for the menu. Cell stage (true): ◀ ▶ go
    // through the programmes. Without it, reaching the channel list meant ◀ past every programme.
    var inCellMode by remember { mutableStateOf(false) }
    BackHandler(enabled = inCellMode && gridHasFocus) { inCellMode = false }
    // The axis keeps up with the clock: unless the programmes are being browsed, the half hour that is now
    // stays at the left edge, as when the guide opens. It was set on opening only, so a guide left on for an
    // evening (or waiting behind full screen) still started at 18:30 with now at its right edge, and the
    // provider's rows, which begin with what is on, showed blanks before it (Jonathan, 31 Oct 2026).
    LaunchedEffect(liveNow, inCellMode, timelinePx, state.windowStart) {
        if (inCellMode || timelinePx <= 0 || state.channels.isEmpty()) return@LaunchedEffect
        showTime(liveNow)
    }
    // The programme the block on top shows and the buttons act on: the one under the cursor.
    var selected by remember { mutableStateOf<Pair<ChannelEntity, EpgProgrammeEntity?>?>(null) }

    // ▲ ▼ by row number (playbook 2): scroll just enough, wait a frame, focus. A held key keeps counting
    // from the target, and the newest press cancels the step still in flight.
    var stepTarget by remember { mutableStateOf<Int?>(null) }
    var stepJob by remember { mutableStateOf<kotlinx.coroutines.Job?>(null) }
    fun stepTo(i: Int, glide: Boolean = false) {
        anchorId = null
        stepTarget = i
        stepJob?.cancel()
        stepJob = scope.launch {
            val info = rowListState.layoutInfo
            val row = info.visibleItemsInfo.firstOrNull { it.index == i }
            // A single press onto a row already on screen: it lights at once and the list glides after it
            // (guideScrollSpec), as TiviMate's does. Held keys and pages keep the instant steps below, which
            // never outrun the list.
            if (glide && row != null) {
                runCatching { rowFocus(i).requestFocus() }
                stepTarget = null
                return@launch
            }
            val step = ((info.visibleItemsInfo.firstOrNull()?.size ?: 0) + info.mainAxisItemSpacing).toFloat()
            val delta = when {
                row == null -> if (i > (info.visibleItemsInfo.lastOrNull()?.index ?: 0)) step else -step
                row.offset - step < info.viewportStartOffset -> row.offset - step - info.viewportStartOffset
                row.offset + row.size + step > info.viewportEndOffset -> row.offset + row.size + step - info.viewportEndOffset
                else -> 0f
            }
            if (delta != 0f) runCatching { rowListState.scrollBy(delta) }
            if (rowListState.layoutInfo.visibleItemsInfo.none { it.index == i }) runCatching { rowListState.scrollToItem(i) }
            withFrameNanos { }
            runCatching { rowFocus(i).requestFocus() }
            stepTarget = null
        }
    }
    // Back to the grid at the row focus was on (after a menu, the sheet, a jump).
    fun focusGrid(row: Int = focusedRow) {
        val i = row.coerceIn(0, (state.channels.size - 1).coerceAtLeast(0))
        scope.launch {
            val visible = rowListState.layoutInfo.visibleItemsInfo.any { it.index == i }
            // Two rows of the channels before it stay in view, so a jump never opens on a lone row at the top.
            if (!visible) runCatching { rowListState.scrollToItem((i - 2).coerceAtLeast(0)) }
            withFrameNanos { }
            if (!runCatching { rowFocus(i).requestFocus() }.getOrDefault(false)) {
                withFrameNanos { }
                runCatching { rowFocus(i).requestFocus() }
            }
        }
    }
    // The cursor stays in view: scroll only when it reaches an edge, by one slot of room. Watched in a
    // snapshotFlow, not as an effect key, so a ◀ ▶ step does not recompose the whole guide to restart it.
    LaunchedEffect(timelinePx) {
        snapshotFlow { cursorTime }.collect { cursor ->
            if (!gridHasFocus || timelinePx <= 0 || cursor <= 0L) return@collect
            val x = pxAt(cursor)
            val margin = GuideGridDefaults.SlotMin * ppm
            when {
                // Back past the left edge: that half hour becomes the edge, as on entry.
                x < scrollPx -> scrollPx = pxAt(halfHourOf(cursor)).toInt().coerceIn(0, maxScroll())
                x > scrollPx + timelinePx - margin -> scrollPx = (x - timelinePx + margin).toInt().coerceIn(0, maxScroll())
            }
        }
    }

    // Back from a channel tuned in the guide: its row again, once the reload has the grid on screen.
    // The channel playing is the one to land on (Jonathan, 27 Oct 2026: "genau auf dem Sender"), not the row
    // last browsed: one zapped to or picked from the player's list counts too. If it is not in the category on
    // screen, its own category opens (or all channels), and the row is focused once that list is in.
    var restoreTo by remember { mutableStateOf<Long?>(null) }
    LaunchedEffect(restoreFocus, state.loading, state.channels.size) {
        if (!restoreFocus || state.loading || state.channels.isEmpty()) return@LaunchedEffect
        val playingId = previewChannel?.id ?: vm.lastTunedChannelId
        val found = playingId?.let { id -> state.channels.indexOfFirst { it.id == id } } ?: -1
        val playing = previewChannel
        if (found < 0 && liveMode && playing != null) {
            val own = playing.categoryId?.let { LiveKey.Folder(it) }?.takeIf { key -> railItems.any { it.key == key } }
            val target = own ?: firstGroupKey ?: LiveKey.All
            if (target != liveSelected) {
                anchorId = playing.id
                restoreTo = playing.id
                liveVm.select(target)
                onRestored()
                return@LaunchedEffect
            }
        }
        val idx = found.coerceAtLeast(0)
        focusedRow = idx
        anchorId = state.channels.getOrNull(idx)?.id
        focusGrid(idx)
        onRestored()
    }
    // The guide keeps the list it had on screen and reloads it silently, so the restore above can run on
    // the old list (from the player: EPG landed past the end of the new one, Jonathan 27 Oct 2026). The
    // channel it restored to is found again in each fresh list, until the user moves on.
    LaunchedEffect(state.channels) {
        val id = anchorId ?: return@LaunchedEffect
        val idx = state.channels.indexOfFirst { it.id == id }.takeIf { it >= 0 } ?: 0
        if (state.channels.isEmpty()) return@LaunchedEffect
        focusedRow = idx
        runCatching { rowListState.scrollToItem((idx - 2).coerceAtLeast(0)) }
        val pending = restoreTo == id && idx >= 0 && state.channels.getOrNull(idx)?.id == id
        if (pending) restoreTo = null
        if (gridHasFocus || pending) focusGrid(idx)
    }

    // A favourite taken out of Favoriten with the red key: its row goes, and the one that moves up into its place
    // keeps the focus (and the lit number), instead of the focus falling out of the grid with the row.
    var keepFocusOn by remember { mutableStateOf<Long?>(null) }
    LaunchedEffect(state.channels) {
        val id = keepFocusOn ?: return@LaunchedEffect
        val idx = state.channels.indexOfFirst { it.id == id }
        if (idx < 0) return@LaunchedEffect
        keepFocusOn = null
        focusedRow = idx
        focusGrid(idx)
    }

    // Behind full screen the guide gets ready for the way back: on the channel playing (its category loaded, its
    // row in view) and with the axis at now. No focus is taken, the player keeps it; Back then finds the row in
    // place instead of loading from the top and jumping. [GuidePreviewSpot.ready] tells the shell it can glide.
    LaunchedEffect(behindFullscreen, liveMode, previewChannel?.id, state.loading, state.channels) {
        val loaded = !state.loading && state.channels.isNotEmpty()
        if (!behindFullscreen || !liveMode) { GuidePreviewSpot.ready = loaded; return@LaunchedEffect }
        GuidePreviewSpot.ready = false
        if (!loaded) return@LaunchedEffect
        val id = previewChannel?.id
        if (id == null) { GuidePreviewSpot.ready = true; return@LaunchedEffect }
        val idx = state.channels.indexOfFirst { it.id == id }
        if (idx < 0) {
            // Its own category (or the first group), reloaded now, unseen, so Back finds the row in place.
            val own = previewChannel?.categoryId?.let { LiveKey.Folder(it) }?.takeIf { k -> railItems.any { it.key == k } }
            val target = own ?: firstGroupKey
            if (target == null || target == liveSelected) { GuidePreviewSpot.ready = true; return@LaunchedEffect }
            anchorId = id
            restoreTo = id
            liveVm.select(target)
            return@LaunchedEffect
        }
        focusedRow = idx
        anchorId = id
        if (rowListState.layoutInfo.visibleItemsInfo.none { it.index == idx }) {
            runCatching { rowListState.scrollToItem((idx - 2).coerceAtLeast(0)) }
        }
        // The axis moves on to the half hour that is now, if now has left the screen.
        val nowMs = System.currentTimeMillis()
        if (timelinePx > 0 && pxAt(nowMs) !in scrollPx.toFloat()..(scrollPx + timelinePx).toFloat()) showTime(nowMs)
        GuidePreviewSpot.ready = true
    }
    DisposableEffect(Unit) { onDispose { GuidePreviewSpot.ready = false; GuidePreviewSpot.rect = null } }

    // --- Overlays --------------------------------------------------------------------------------------
    var menuFor by remember { mutableStateOf<Pair<ChannelEntity, EpgProgrammeEntity>?>(null) }
    var channelMenuFor by remember { mutableStateOf<ChannelEntity?>(null) }
    var recordMenuFor by remember { mutableStateOf<Pair<ChannelEntity, EpgProgrammeEntity>?>(null) }
    var askPlayerFor by remember { mutableStateOf<Pair<ChannelEntity, EpgProgrammeEntity>?>(null) }
    var matchingChannel by remember { mutableStateOf<ChannelEntity?>(null) }
    var offsetChannel by remember { mutableStateOf<ChannelEntity?>(null) }
    var orderOpen by remember { mutableStateOf(false) }
    // Hold OK on a group: hide or move it; Hold OK on a channel row: its menu, with move and hide.
    var groupMenuFor by remember { mutableStateOf<Pair<LiveKey, String>?>(null) }
    val channelMove by liveVm.moveState.collectAsStateWithLifecycle()
    val groupMove by liveVm.categoryMoveState.collectAsStateWithLifecycle()
    // The menus stay up until their move list is loaded, then hand over to it.
    LaunchedEffect(channelMove != null) { if (channelMove != null) channelMenuFor = null }
    LaunchedEffect(groupMove != null) { if (groupMove != null) groupMenuFor = null }
    // The groups stay out while their menu is up, and focus goes back to the group it was opened on.
    var groupsHeld by remember { mutableStateOf(false) }
    var groupsFocusIndex by remember { mutableStateOf<Int?>(null) }
    var dayOpen by remember { mutableStateOf(false) }
    // Where focus goes back to when an overlay closes: the control or button that opened it.
    var returnTo by remember { mutableStateOf<(() -> Unit)?>(null) }
    val anyOverlay = menuFor != null || channelMenuFor != null || recordMenuFor != null || askPlayerFor != null || matchingChannel != null ||
        offsetChannel != null || orderOpen || dayOpen || review.isNotEmpty() || groupMenuFor != null || channelMove != null || groupMove != null
    var hadOverlay by remember { mutableStateOf(false) }
    LaunchedEffect(anyOverlay) {
        if (anyOverlay) { hadOverlay = true; return@LaunchedEffect }
        if (!hadOverlay) return@LaunchedEffect
        hadOverlay = false
        delay(60)
        (returnTo ?: { focusGrid() }).invoke()
    }
    // Leaving the grid (not for one of its own menus) goes back to the row stage.
    LaunchedEffect(gridHasFocus, anyOverlay) { if (!gridHasFocus && !anyOverlay) inCellMode = false }
    // The one-line outcome beside Auto-match EPG goes after a while (the review, when there is one, says it instead).
    LaunchedEffect(matchSummary, review.isEmpty()) {
        if (matchSummary != null && review.isEmpty()) { delay(8_000); vm.clearSummary() }
    }

    val channelsNow = state.channels
    val watch: (ChannelEntity) -> Unit = { ch -> vm.noteChannelTuned(ch); onPlayChannel(ch, channelsNow) }
    val playFromStart: (ChannelEntity, EpgProgrammeEntity) -> Unit = { ch, p ->
        when (catchupPlayer) {
            SettingsRepository.CatchupPlayer.ASK -> askPlayerFor = ch to p
            SettingsRepository.CatchupPlayer.INTERNAL -> { vm.noteChannelTuned(ch); onPlayCatchup(ch, p) }
            SettingsRepository.CatchupPlayer.EXTERNAL -> { vm.noteChannelTuned(ch); vm.playCatchupExternal(ch, p) }
        }
    }
    // OK on a programme: watch — the archive for one that has been on and can be replayed, else the channel.
    val onOk: (ChannelEntity, EpgProgrammeEntity?) -> Unit = { ch, p ->
        val now = System.currentTimeMillis()
        if (p != null && p.stopMs <= now && vm.canCatchup(ch, p, now)) playFromStart(ch, p) else watch(ch)
    }

    /** What [p] on [ch] can do right now — the programme menu, Record ▾ and the buttons on top share it. */
    @Composable
    fun actionsFor(ch: ChannelEntity, p: EpgProgrammeEntity): GuideProgrammeActions = guideProgrammeActions(
        vm, ch, p, liveNow,
        onWatch = { watch(ch) },
        onPlayFromStart = { playFromStart(ch, p) },
        onPickEpg = { matchingChannel = ch },
        onEpgOffset = { offsetChannel = ch },
    )

    // Channel names as the rows show them: the whole name with only the provider tags ("DE:") parsed off.
    // The category's name is no longer cut from the front: "DAZN 1 HD" read "1 HD" (27 Oct 2026).
    val (entries, groupsHeading) = liveCategoryEntries(railItems, emptyMap())
    val categoryEntry = entries.firstOrNull { it.item.key == (categoryKey ?: LiveKey.All) }
    val categoryLabel = categoryEntry?.label ?: stringResource(R.string.content_category_all_channels)
    fun rowName(ch: ChannelEntity): String = ProviderTags.parse(ch.name).name
    val remindersByChannel = remember(reminderRows) { reminderRows.groupBy({ it.channelId }, { it.startMs }).mapValues { it.value.toSet() } }
    val recordingsByChannel = remember(recordingRows) {
        recordingRows.filter { it.status != RecordingStatus.CANCELLED }
            .groupBy({ it.channelId }, { it.programmeStartMs }).mapValues { it.value.toSet() }
    }

    // The second of the three levels (menu · groups · guide): the groups column, shown beside the open menu
    // and while it holds focus. ◀ from the guide lands on it, ◀ again on the menu, ▶ goes the other way.
    val groupsFocus = remember { FocusRequester() }
    // The empty guide's one button ("Add EPG"): ▶ from the rail lands on it, there is nothing else to reach.
    val emptyAction = remember { FocusRequester() }
    val groupsListState = rememberLazyListState()
    var groupsFocused by remember { mutableStateOf(false) }
    // Set while the groups are being entered (▶ from the menu, ◀ from the guide): the list then focuses
    // its selected group itself.
    var groupsEnter by remember { mutableStateOf(false) }
    // Set while focus goes from the groups to the menu: the groups stay where they are until the menu is open
    // beside them. Between the two, for a frame or two, nothing held them out, so they began to slide away and
    // came straight back, and the whole guide was redrawn on every frame of the menu's slide (28 Oct 2026).
    var groupsToMenu by remember { mutableStateOf(false) }
    val focusRows = {
        if (state.channels.isEmpty()) runCatching { emptyAction.requestFocus() }.getOrDefault(false)
        else runCatching { rowFocus(focusedRow.coerceIn(0, state.channels.size - 1)).requestFocus() }.getOrDefault(false)
    }
    // The list hands focus on to its selected group a frame later, so look again before giving up on it.
    val enterGroups = {
        groupsEnter = true
        scope.launch {
            repeat(8) { if (!groupsFocused) withFrameNanos { } }
            groupsEnter = false
            if (!groupsFocused) focusRows()
        }
        Unit
    }

    DisposableEffect(onEntryHook) {
        // ▶ from the open menu goes to the groups beside it, as in the classic IPTV player.
        onEntryHook?.invoke {
            if (tv.own.owntv.features.shell.components.StageRailOpen.open) { enterGroups(); true } else focusRows()
        }
        onDispose { onEntryHook?.invoke(null) }
    }

    // Back goes one level out, like ◀: from a channel row to the groups (from the groups the shell takes it on
    // to the menu). Inside the programmes it first leaves them for the row (the handler above).
    BackHandler(enabled = gridHasFocus && !inCellMode && !groupsFocused) { enterGroups() }

    BoxWithConstraints(
        modifier
            .fillMaxSize()
            .onFocusChanged { tv.own.owntv.features.shell.components.StageRailOpen.guideFocused = it.hasFocus }
            // Entering from the rail lands in the grid, on the row focus was on. Not while the search field
            // is opening: the Search button it replaces held focus, so the field's own request enters from
            // outside too, and redirecting it would send focus to the grid instead. Nor when ▶ from the open
            // menu heads for the groups beside it.
            .focusProperties {
                onEnter = {
                    if (state.channels.isNotEmpty() && !groupsEnter) {
                        runCatching { rowFocus(focusedRow.coerceIn(0, state.channels.size - 1)).requestFocus() }
                    }
                }
            }
            .focusGroup(),
    ) {
        val screenW = maxWidth
        fun fx(px: Int) = screenW * (px / 1920f)
        val labelW = guideWidthShares?.let { (screenW - fx(112)) * (it.channels / 100f) } ?: (screenW * GuideGridDefaults.ChannelColShare)
        val timelineW = screenW - fx(48) - labelW - fx(64)
        LaunchedEffect(timelineW) { timelinePx = with(density) { timelineW.roundToPx() } }

        // With the menu open, or the groups in use, they stand at the left and the guide moves over by their width.
        // Derived, so the menu opening beside groups already out recomposes none of the guide.
        val showGroups by remember {
            derivedStateOf { tv.own.owntv.features.shell.components.StageRailOpen.open || groupsFocused || groupsEnter || groupsHeld || groupsToMenu }
        }
        val groupsW = fx(400)
        LaunchedEffect(showGroups) { tv.own.owntv.features.shell.components.StageRailOpen.guideGroups = showGroups }
        // Menu and groups begin on the first channel row, so the three columns start on one line (30 Oct 2026).
        DisposableEffect(Unit) {
            tv.own.owntv.features.shell.components.StageRailOpen.itemsCentre = GuideFirstRowCentre
            onDispose { tv.own.owntv.features.shell.components.StageRailOpen.itemsCentre = null }
        }
        DisposableEffect(Unit) { onDispose { tv.own.owntv.features.shell.components.StageRailOpen.guideGroups = false; tv.own.owntv.features.shell.components.StageRailOpen.guideFocused = false } }
        val groupsShift = androidx.compose.animation.core.animateDpAsState(if (showGroups) groupsW else 0.mpx, tv.own.owntv.ui.theme.stageSlide())
        val groupsTarget = if (showGroups) groupsW else 0.mpx
        androidx.compose.runtime.SideEffect { GuidePreviewSpot.guideShift = { with(density) { groupsShift.value.toPx() } } }
        androidx.compose.runtime.DisposableEffect(Unit) { onDispose { GuidePreviewSpot.guideShift = { 0f } } }
        Column(
            Modifier.fillMaxSize().graphicsLayer {
                translationX = groupsShift.value.toPx()
                compositingStrategy = tv.own.owntv.ui.theme.slideCompositing(groupsShift.value != groupsTarget)
            },
        ) {
        // Top (40 → 280): the video, and beside it the programme under the cursor. Kept low (Jonathan,
        // 27 Oct 2026) so the grid below shows more channels.
        GuideTopArea(
            vm = vm,
            // Read inside the top area, so ▲ ▼ and the cursor redraw only it, not the whole guide around it.
            top = { selected ?: state.channels.getOrNull(focusedRow)?.let { ch -> ch to programmeAt(vm.cachedProgrammes(ch), liveNow) } },
            previewChannel = if (effectivePreview) previewChannel else null,
            previewEngine = liveVm.previewEngine,
            showVideo = effectivePreview && previewArmed,
            now = { liveNow },
            endClear = groupsTarget,
            modifier = Modifier.padding(start = fx(64), end = fx(64), top = 40.mpx).fillMaxWidth().height(240.mpx),
        )

        // No control line above the grid any more (Jonathan, 27 Oct 2026: "die Filter oberhalb des Guides
        // können auch weg"): the categories are the second level, the rest lives in the menus, as in TiviMate.
        // The guide's search is the groups' field: it lists the channels whose name holds the words or on
        // which they run now. Back from the player the field still holds them, so the guide shows them too.
        LaunchedEffect(Unit) { if (query != groupsQuery) vm.setQuery(groupsQuery) }

        // The grid: ruler at 518, rows from 566.
        Box(Modifier.fillMaxWidth().weight(1f).padding(top = 18.mpx)) {
            when {
                state.loading -> Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) { LandmarkLoader(sizeDp = 64) }
                // Live TV lists every channel of the group, guide or not: an empty grid is an empty group.
                liveMode && state.channels.isEmpty() && (state.message == null || state.message == EpgMessage.MismatchedIds) ->
                    GuideEmpty(
                        title = stringResource(
                            if (categoryKey == LiveKey.Favorites) R.string.tvplayer_guide_no_favorites else R.string.content_no_channels_here,
                        ),
                    )
                // No EPG feed added yet → a guide it can't fill. Point the user to EPG Sources.
                !state.hasEpgSources && state.channels.isEmpty() -> GuideEmpty(
                    title = stringResource(R.string.content_epg_empty),
                    body = stringResource(R.string.content_epg_add_description),
                    action = stringResource(R.string.content_epg_add), onAction = onAddEpg, actionFocus = emptyAction,
                )
                state.channels.isEmpty() -> GuideEmpty(title = state.message?.let { epgMessageText(it) } ?: stringResource(R.string.content_epg_no_guide))
                else -> {
                    val gx = fx(48) + labelW
                    // The number column fits the last place in the list (44 = three digits at the list size).
                    val numberWidth = maxOf(44f, state.channels.size.toString().length * 13f).mpx
                    // TiviMate's corner: the day and time the grid is at, left of the ruler ("Di., 27. Okt., 14:05").
                    // Now while the grid is on today's present, else the half hour at its left edge.
                    val is24h = android.text.format.DateFormat.is24HourFormat(androidx.compose.ui.platform.LocalContext.current)
                    val formatCorner = tv.own.owntv.ui.format.rememberBestDateFormatter(
                        tv.own.owntv.ui.format.combinedDateTimeSkeleton("EEEdMMM", is24h),
                    )
                    // The timeline's scroll is read only by the corner text, the ruler's placement, the
                    // now-line's offset and each row's drawing: scrolling ◀ ▶ recomposes none of the guide.
                    Box(Modifier.fillMaxWidth().height(40.mpx)) {
                        GuideCornerText(
                            text = {
                                // Now while it is on screen; else the time at the left edge, earlier days (the archive)
                                // included. maxOf(now, edge) showed today's date over yesterday's programmes.
                                val left = timeAt(scrollPx.toFloat())
                                formatCorner(if (liveNow in left..timeAt((scrollPx + timelinePx).toFloat())) liveNow else left)
                            },
                            modifier = Modifier.padding(start = fx(48) + 8.mpx, top = 4.mpx).width(labelW - 16.mpx),
                        )
                        GuideRuler(
                            windowStart = state.windowStart, windowEnd = state.windowEnd, now = liveNow, scrollPx = { scrollPx },
                            modifier = Modifier.padding(start = gx).width(timelineW).height(40.mpx),
                        )
                    }
                    CompositionLocalProvider(androidx.compose.foundation.gestures.LocalBringIntoViewSpec provides guideScrollSpec) {
                        LazyColumn(
                            state = rowListState,
                            modifier = Modifier
                                .padding(start = fx(48), end = fx(64), top = 48.mpx)
                                .fillMaxSize()
                                .onFocusChanged { gridHasFocus = it.hasFocus }
                                .focusProperties { onEnter = { runCatching { rowFocus(focusedRow.coerceIn(0, state.channels.size - 1)).requestFocus() } } }
                                .focusGroup(),
                            verticalArrangement = Arrangement.spacedBy(GuideGridDefaults.RowGap),
                            contentPadding = PaddingValues(bottom = 24.mpx),
                        ) {
                            itemsIndexed(state.channels, key = { _, ch -> ch.id }) { index, channel ->
                                GuideRow(
                                    vm = vm,
                                    channel = channel,
                                    number = index + 1,
                                    name = rowName(channel),
                                    dot = playlistMarks[channel.sourceId]?.shownColor,
                                    labelWidth = labelW,
                                    numberWidth = numberWidth,
                                    logos = !logosPaused,
                                    windowStart = state.windowStart,
                                    windowEnd = state.windowEnd,
                                    now = liveNow,
                                    scrollPx = { scrollPx },
                                    focusRequester = rowFocus(index),
                                    selectedRow = gridHasFocus && index == focusedRow,
                                    cursorTime = { cursorTime },
                                    cellMode = inCellMode,
                                    onEnterCell = {
                                        cursorTime = System.currentTimeMillis()
                                        inCellMode = true
                                    },
                                    recordingStarts = recordingsByChannel[channel.id].orEmpty(),
                                    reminderStarts = remindersByChannel[channel.id].orEmpty(),
                                    onFocused = {
                                        focusedRow = index
                                        if (cursorTime !in state.windowStart until state.windowEnd) cursorTime = System.currentTimeMillis()
                                        // Only with the guide's preview switched off does the focus pick the channel. Off for a
                                        // moment is not enough: behind full screen and on the way back from it (previewEnabled
                                        // false) the focus took the corner over, and the guide then played whatever row focus
                                        // passed (Jonathan, 27 Oct 2026). The corner changes only with OK.
                                        if (!(livePreviewSetting && guidePreview)) liveVm.onChannelFocused(channel)
                                    },
                                    onCursor = { cursorTime = it },
                                    onShow = { p -> selected = channel to p },
                                    onStep = { delta, glide ->
                                        val from = stepTarget ?: index
                                        // A page (CH+/CH−, RW/FF) stops at the first or last row instead of leaving.
                                        val to = (from + delta).let { if (kotlin.math.abs(delta) > 1) it.coerceIn(0, state.channels.size - 1) else it }
                                        when {
                                            to < 0 -> false // ▲ from the first row: up to the control line
                                            to >= state.channels.size -> true
                                            else -> { stepTo(to, glide); true }
                                        }
                                    },
                                    onOk = { p -> if (previewNeeds(channel)) liveVm.onChannelFocused(channel) else onOk(channel, p) },
                                    onMenu = { p -> returnTo = null; if (p != null) menuFor = channel to p else channelMenuFor = channel },
                                    onChannelMenu = { returnTo = null; channelMenuFor = channel },
                                    onFavorite = {
                                        if (categoryKey == LiveKey.Favorites) {
                                            val next = if (index + 1 < state.channels.size) index + 1 else index - 1
                                            state.channels.getOrNull(next)?.let { keepFocusOn = it.id; runCatching { rowFocus(next).requestFocus() } }
                                                ?: enterGroups() // the last favourite: the list is empty now, the groups take the focus
                                        }
                                        vm.toggleFavoriteChannel(channel)
                                    },
                                    onGroups = enterGroups,
                                    onSearch = onOpenSearch,
                                    onSettings = onOpenSettings,
                                )
                            }
                        }
                    }
                    // The now-line, 3 px with its glow, over every row. Placed by an offset read at layout
                    // and hidden off the timeline in its layer, so following the scroll costs no recomposition.
                    if (liveNow in state.windowStart..state.windowEnd) {
                        val timelineWidthPx = with(density) { timelineW.toPx() }
                        Box(
                            Modifier
                                .padding(start = gx - 1.5.mpx, top = 38.mpx, bottom = 24.mpx)
                                .offset { androidx.compose.ui.unit.IntOffset((pxAt(liveNow) - scrollPx).toInt(), 0) }
                                .graphicsLayer {
                                    val x = pxAt(liveNow) - scrollPx
                                    alpha = if (x >= 0f && x <= timelineWidthPx) 1f else 0f
                                }
                                .width(3.mpx)
                                .fillMaxHeight()
                                .guideNowLine(stageAccent.accent),
                        )
                    }
                }
            }
        }

        }

        // As in TiviMate the category under the cursor is the guide's list: moving through the categories shows
        // each one's channels a moment later, and ▶ goes into the one in focus. ▶ went back to the list that
        // was open before (Jonathan, 27 Oct 2026: "Sky Bundesliga" still showed every channel).
        val applyGroup: (Int) -> Unit = { idx ->
            val key = railItems.getOrNull(idx)?.key
            if (key != null && key != (categoryKey ?: LiveKey.All)) {
                if (liveMode) liveVm.select(key) else vm.setCategoryFilter(key)
                anchorId = null
                openingGroup = key
                focusOnOpen = false
            }
        }
        // The group's list is read off the main thread and arrives a moment after the pick; until then the grid
        // still shows the old one. OK used to focus row 0 of that old list at once: the new list then replaced the
        // row and the focus went with it, so the groups slid out and straight back in (or the menu came), and the
        // old list jumped to its top first. The grid now goes to the top, and takes the focus, once the new list is in.
        LaunchedEffect(openingGroup, state) {
            val key = openingGroup ?: return@LaunchedEffect
            if (state.loading || state.category != key.takeUnless { it == LiveKey.All }) return@LaunchedEffect
            focusedRow = 0
            runCatching { rowListState.scrollToItem(0) }
            // Only while the focus is still on the groups: ◀ to the menu meanwhile is not to be undone.
            if (focusOnOpen && groupsFocused) focusGrid(0)
            openingGroup = null
            focusOnOpen = false
        }
        // A pick whose list never comes (a pinned Live TV ignores it) does not hold the focus in the groups.
        LaunchedEffect(openingGroup) {
            if (openingGroup == null) return@LaunchedEffect
            delay(4_000)
            if (focusOnOpen && groupsFocused) focusGrid()
            openingGroup = null
            focusOnOpen = false
        }
        LaunchedEffect(browsedGroup) {
            val idx = browsedGroup ?: return@LaunchedEffect
            // Entering the groups focuses the open one: nothing to load, and no work while they slide in.
            if (railItems.getOrNull(idx)?.key == (categoryKey ?: LiveKey.All)) return@LaunchedEffect
            // Its first rows start loading once the focus rests on it, so a held key does not start a load per row.
            delay(150)
            railItems.getOrNull(idx)?.key?.let(vm::prefetchCategory)
            delay(250)
            applyGroup(idx)
        }
        // Kept on screen while it slides out again, so leaving the groups is as smooth as opening them.
        val groupsOnScreen by remember { derivedStateOf { groupsShift.value > 0.5.mpx } }
        if (showGroups || groupsOnScreen) {
            val railCounts by liveVm.railCounts.collectAsStateWithLifecycle()
            val (groupEntries, _) = liveCategoryEntries(railItems, railCounts)
            // The groups on their own panel, a shade apart from the guide with a line at its edge, so the two
            // columns do not run into each other.
            val palette = tv.own.owntv.ui.theme.LocalStagePalette.current
            Box(
                Modifier
                    .width(groupsW)
                    .fillMaxHeight()
                    // Only moved, never faded: they come in from beyond the screen's edge, and a fading layer
                    // needs a picture of its own drawn offscreen.
                    .graphicsLayer { translationX = (groupsShift.value - groupsW).toPx() }
                    .background(palette.glassTint)
                    .drawBehind {
                        val line = 1.mpx.toPx()
                        drawRect(palette.ink.copy(alpha = 0.07f), Offset(size.width - line, 0f), Size(line, size.height))
                    },
            )
            LiveCategories(
                searchQuery = groupsQuery,
                onSearchQueryChange = { vm.setCategoryQuery(it); vm.setQuery(it) },
                searchPlaceholder = stringResource(R.string.tvplayer_guide_search),
                searchLeavesRight = true,
                searchGap = GroupsSearchGap,
                entries = groupEntries,
                selectedIndex = railItems.indexOfFirst { it.key == (categoryKey ?: LiveKey.All) }.coerceAtLeast(0),
                groupsHeading = groupsHeading,
                showGroupsHeading = false,
                sheet = false,
                listState = groupsListState,
                onSelect = { idx ->
                    browsedGroup = null
                    // A group chosen from the search opens as itself: the search makes way for it.
                    if (searching) vm.clearSearch()
                    val key = railItems.getOrNull(idx)?.key
                    when {
                        key != null && key != (categoryKey ?: LiveKey.All) -> {
                            applyGroup(idx)
                            focusOnOpen = true
                        }
                        // The open group again, from the search: its list comes back once the search is gone.
                        key != null && searching -> {
                            anchorId = null
                            openingGroup = key
                            focusOnOpen = true
                        }
                        // Applied by the rest a moment ago, its list not in yet.
                        openingGroup != null -> focusOnOpen = true
                        else -> {
                            focusedRow = 0
                            anchorId = null
                            scope.launch { runCatching { rowListState.scrollToItem(0) } }
                            focusGrid(0)
                        }
                    }
                },
                onLongSelect = { idx ->
                    val key = railItems.getOrNull(idx)?.key
                    if (key != null && (key is LiveKey.Folder || key is LiveKey.Custom)) {
                        groupsHeld = true
                        returnTo = {
                            val at = railItems.indexOfFirst { it.key == key }
                            if (at >= 0) groupsFocusIndex = at else enterGroups()
                            scope.launch { delay(400); groupsFocusIndex = null; groupsHeld = false }
                            returnTo = null
                        }
                        groupMenuFor = key to groupEntries.getOrNull(idx)?.label.orEmpty()
                    }
                },
                onNavigateRight = {
                    // While searching the guide holds the results: ▶ goes to them, not to the group browsed past.
                    if (!searching) browsedGroup?.let(applyGroup)
                    browsedGroup = null
                    when {
                        searching -> focusGrid(0)
                        openingGroup != null -> focusOnOpen = true
                        else -> focusGrid()
                    }
                },
                onRowFocus = { idx ->
                    browsedGroup = idx
                    // Moved on before the list came: it opens without taking the focus from here.
                    if (railItems.getOrNull(idx)?.key != openingGroup) focusOnOpen = false
                },
                focusRequester = groupsFocus,
                focusRowIndex = groupsFocusIndex
                    ?: if (groupsEnter) railItems.indexOfFirst { it.key == (categoryKey ?: LiveKey.All) }.coerceAtLeast(0) else null,
                modifier = Modifier
                    // The search field on the ruler's line, the first group centred on the first channel row (see [GroupsTop]).
                    .padding(start = fx(24), end = fx(16), top = GroupsTop, bottom = 24.mpx)
                    .width(groupsW - fx(40))
                    .fillMaxHeight()
                    .graphicsLayer {
                        translationX = (groupsShift.value - groupsW).toPx()
                        compositingStrategy = tv.own.owntv.ui.theme.slideCompositing(groupsShift.value != groupsTarget)
                    }
                    .onFocusChanged {
                        if (groupsFocused && !it.hasFocus) {
                            groupsToMenu = true
                            scope.launch {
                                // Back into the guide (▶): let them go a frame later. To the menu: until it is open.
                                withFrameNanos { }
                                repeat(10) {
                                    val waiting = !tv.own.owntv.features.shell.components.StageRailOpen.guideFocused &&
                                        !tv.own.owntv.features.shell.components.StageRailOpen.open
                                    if (waiting) withFrameNanos { }
                                }
                                groupsToMenu = false
                            }
                        }
                        groupsFocused = it.hasFocus
                    },
            )
        }

    }

    menuFor?.let { (ch, p) ->
        val formatTime = tv.own.owntv.ui.format.rememberSystemTimeFormatter()
        GuideProgrammeMenu(
            title = p.title,
            subtitle = listOf(
                stringResource(R.string.content_live_time_range_plain, formatTime(p.startMs), formatTime(p.stopMs)),
                listOfNotNull(listNumber(ch)?.toString(), ProviderTags.parse(ch.name).name).joinToString(" "),
            ).joinToString(" · "),
            actions = actionsFor(ch, p),
            leadMinutes = leadMinutes,
            onDismiss = { menuFor = null },
        )
    }
    recordMenuFor?.let { (ch, p) -> GuideRecordMenu(title = p.title, actions = actionsFor(ch, p), onDismiss = { recordMenuFor = null }) }
    askPlayerFor?.let { (ch, p) ->
        GuideCatchupChooser(
            onInternal = { vm.noteChannelTuned(ch); onPlayCatchup(ch, p) },
            onExternal = { vm.noteChannelTuned(ch); vm.playCatchupExternal(ch, p) },
            onDismiss = { askPlayerFor = null },
        )
    }
    if (orderOpen) {
        val showEmpty by vm.showEmpty.collectAsStateWithLifecycle()
        GuideOrderMenu(
            current = sortGuide, onPick = vm::setGuideSort,
            showEmpty = showEmpty, onShowEmpty = vm::setShowEmpty,
            showPreview = guidePreview, onShowPreview = vm::setGuidePreview,
            onDismiss = { orderOpen = false },
        )
    }
    channelMenuFor?.takeIf { channelMove == null }?.let { ch ->
        GuideChannelMenu(
            channel = ch,
            number = listNumber(ch),
            subtitle = categoryLabel,
            isFavorite = ch.id in vm.favoriteChannelIds.collectAsStateWithLifecycle().value,
            epgOffsetValue = vm.currentEpgShift(ch)?.let { liveEpgShiftLabel(it) } ?: stringResource(R.string.content_epg_offset_global_short),
            onWatch = { watch(ch) },
            onFavorite = { vm.toggleFavoriteChannel(ch) },
            onAutoMatch = { vm.autoMatchOne(ch) },
            onPickEpg = { matchingChannel = ch },
            onEpgOffset = { offsetChannel = ch },
            // Only a group has an order of its own to move in (All and Recently watched follow the provider).
            onMove = categoryKey.takeIf { it is LiveKey.Folder || it is LiveKey.Custom || it == LiveKey.Favorites }?.let { key ->
                { liveVm.enterMoveMode(ch, key) }
            },
            onHide = { liveVm.hideChannel(ch) },
            onDismiss = { channelMenuFor = null },
        )
    }
    channelMove?.let { ms ->
        MoveOrderOverlay(
            title = stringResource(R.string.content_reorder_channel),
            itemNames = ms.items.map { ProviderTags.parse(it.name).name },
            activeIndex = ms.activeIndex,
            onMove = liveVm::move,
            onCommit = {
                val at = ms.activeIndex
                returnTo = { focusGrid(at); returnTo = null }
                // The group's own order shows in the provider's order mode.
                if (sortGuide != SettingsRepository.GuideSort.PROVIDER && sortGuide != SettingsRepository.GuideSort.LIVE_TV) {
                    vm.setGuideSort(SettingsRepository.GuideSort.PROVIDER)
                }
                liveVm.commitMove(onSaved = vm::load)
            },
            onCancel = liveVm::cancelMove,
        )
    }
    groupMenuFor?.takeIf { groupMove == null }?.let { (key, label) ->
        CategoryContextMenu(
            categoryName = label,
            canHide = true,
            onHide = { liveVm.hideCategory(key); groupMenuFor = null },
            onMove = { liveVm.enterCategoryMoveMode(key) },
            onDismiss = { groupMenuFor = null },
        )
    }
    groupMove?.let { ms ->
        MoveOrderOverlay(
            title = stringResource(R.string.content_move),
            itemNames = ms.items,
            activeIndex = ms.activeIndex,
            onMove = liveVm::moveCategory,
            onCommit = liveVm::commitCategoryMove,
            onCancel = liveVm::cancelCategoryMove,
        )
    }
    if (dayOpen) {
        val days = remember(state.windowStart, state.windowEnd) {
            generateSequence(localMidnight(state.windowStart)) { localMidnight(it + 36L * 60 * 60 * 1000) }.takeWhile { it < state.windowEnd }.toList()
        }
        GuideDayMenu(
            days = days,
            currentDay = localMidnight(timeAt(scrollPx + timelinePx * 3f / 8f)),
            onPick = { day ->
                val now = System.currentTimeMillis()
                showTime(day + (now - localMidnight(now)))
            },
            onDismiss = { dayOpen = false },
        )
    }
    offsetChannel?.let { channel ->
        tv.own.owntv.features.live.EpgOffsetDialog(
            channelName = channel.name,
            currentMinutes = vm.currentEpgShift(channel),
            globalMinutes = vm.globalEpgShift(),
            onSet = { vm.setEpgShift(channel, it) },
            onDismiss = { offsetChannel = null },
        )
    }
    matchingChannel?.let { channel ->
        tv.own.owntv.features.live.EpgMatchDialog(
            channelName = channel.name,
            currentMatch = vm.currentEpgMatch(channel),
            loadChannels = { vm.availableEpgChannels(channel.name, it) },
            onPick = { vm.setEpgMatch(channel, it); matchingChannel = null },
            onClear = { vm.setEpgMatch(channel, null); matchingChannel = null },
            onDismiss = { matchingChannel = null },
        )
    }
    if (review.isNotEmpty()) {
        val includeLogos by vm.includeGuideLogos.collectAsStateWithLifecycle()
        val settingsVm: tv.own.owntv.features.settings.SettingsViewModel = koinViewModel()
        val chNavEnabled by settingsVm.chNavEnabled.collectAsStateWithLifecycle()
        val chNavUpSkip by settingsVm.chNavUpSkip.collectAsStateWithLifecycle()
        val chNavDownSkip by settingsVm.chNavDownSkip.collectAsStateWithLifecycle()
        GuideReviewPopup(
            suggestions = review,
            matchedCount = (matchSummary as? EpgMatchSummary.AutoMatched)?.applied,
            includeLogos = includeLogos,
            onIncludeLogos = vm::setIncludeGuideLogos,
            onAccept = vm::acceptSuggestion,
            onSkip = vm::dismissSuggestion,
            onAcceptAll = vm::acceptAllSuggestions,
            onSkipAll = vm::clearReview,
            onDone = vm::clearReview,
            chNavEnabled = chNavEnabled,
            chNavUpSkip = chNavUpSkip,
            chNavDownSkip = chNavDownSkip,
        )
    }
}

/**
 * One guide row: the channel label, then the programmes as one focus target with a cursor. ◀ ▶ move the
 * cursor programme by programme (◀ from the first leaves to the rail), ▲ ▼ step rows ([onStep]), OK
 * watches, holding OK or ☰ opens the programme menu. Programmes are read when the row comes into view.
 */
@Composable
private fun GuideRow(
    vm: EpgViewModel,
    channel: ChannelEntity,
    number: Int,
    name: String,
    dot: Color?,
    labelWidth: androidx.compose.ui.unit.Dp,
    numberWidth: androidx.compose.ui.unit.Dp,
    /** False while the guide only waits behind the full-screen picture: no logo is loaded for it then. */
    logos: Boolean,
    windowStart: Long,
    windowEnd: Long,
    now: Long,
    /** Read only while drawing the row's programmes. */
    scrollPx: () -> Int,
    focusRequester: FocusRequester,
    selectedRow: Boolean,
    /** Read in composition by the focused row only, so moving the cursor recomposes that row alone. */
    cursorTime: () -> Long,
    cellMode: Boolean,
    onEnterCell: () -> Unit,
    recordingStarts: Set<Long>,
    reminderStarts: Set<Long>,
    onFocused: () -> Unit,
    onCursor: (Long) -> Unit,
    onShow: (EpgProgrammeEntity?) -> Unit,
    /** Rows to move by; glide for a single ▲ ▼ press (not held, not a page). */
    onStep: (delta: Int, glide: Boolean) -> Boolean,
    onOk: (EpgProgrammeEntity?) -> Unit,
    onMenu: (EpgProgrammeEntity?) -> Unit,
    onChannelMenu: () -> Unit,
    onFavorite: () -> Unit,
    onGroups: () -> Unit,
    onSearch: () -> Unit,
    onSettings: () -> Unit,
) {
    // Cache peek first, so a row scrolled back into view draws at once; a miss reads this one channel.
    val cacheRevision by vm.cacheRevision.collectAsStateWithLifecycle()
    val programmes by produceState(initialValue = vm.cachedProgrammes(channel), channel.id, windowStart, cacheRevision) {
        value = vm.cachedProgrammes(channel) ?: vm.programmesFor(channel)
        // Still empty: ask again a little later, twice, before the row settles on "No program".
        repeat(2) {
            if (value?.isNotEmpty() == true) return@produceState
            delay(3_000)
            value = vm.programmesFor(channel)
        }
    }
    var focused by remember { mutableStateOf(false) }
    var okDown by remember { mutableStateOf(false) }
    var menuDown by remember { mutableStateOf(false) }
    // On the row stage the row stands on what is on now, as its lit cell does; only in the programmes does the
    // cursor's time count. The cursor kept the time it was last given (the guide's opening minute, or the evening
    // browsed in the programmes), so the block on top described an ended programme beside a row lit on another.
    val rowCursor = if (!focused) 0L else if (cellMode) cursorTime() else now
    fun cursorAt(): Long = if (cellMode) cursorTime() else System.currentTimeMillis()
    LaunchedEffect(focused, rowCursor, programmes) { if (focused) onShow(programmeAt(programmes, rowCursor)) }
    // Its own layer: while the list glides, the row is moved as it was drawn instead of drawn again each frame.
    Row(Modifier.fillMaxWidth().height(GuideGridDefaults.RowHeight).graphicsLayer { }, verticalAlignment = Alignment.CenterVertically) {
        GuideChannelLabel(channel, number, name, dot, selected = selectedRow, numberWidth = numberWidth, modifier = Modifier.width(labelWidth), logo = logos)
        Box(
            Modifier
                .weight(1f)
                .fillMaxHeight()
                .focusRequester(focusRequester)
                .onFocusChanged { focused = it.isFocused; if (it.isFocused) onFocused() }
                .onPreviewKeyEvent { e ->
                    val progs = programmes.orEmpty()
                    val down = e.type == KeyEventType.KeyDown
                    when (e.key) {
                        Key.DirectionCenter, Key.Enter, Key.NumPadEnter -> {
                            val repeat = e.nativeKeyEvent.repeatCount
                            if (down && repeat == 0) okDown = true
                            // Held: on the row the channel's menu (move, hide…), in the programmes the programme's.
                            else if (down && okDown) { okDown = false; if (cellMode) onMenu(programmeAt(progs, cursorAt())) else onChannelMenu() }
                            // OK watches the channel, as in the classic IPTV guide; in the programmes it acts on the one under the cursor.
                            else if (!down && okDown) { okDown = false; onOk(programmeAt(progs, cursorAt())) }
                            true
                        }
                        // ☰ opens the programme menu on release; held, it opens the search.
                        Key.Menu -> {
                            val repeat = e.nativeKeyEvent.repeatCount
                            if (down && repeat == 0) menuDown = true
                            else if (down && menuDown) { menuDown = false; onSearch() }
                            else if (!down && menuDown) { menuDown = false; onMenu(programmeAt(progs, cursorAt())) }
                            true
                        }
                        Key.Guide, Key.ProgramYellow -> { if (down) onGroups(); true }
                        Key.ProgramBlue -> { if (down) onSettings(); true }
                        // The classic remote's guide keys: Info and Play show the programme, Red and Stop
                        // toggle the favourite, Green the channel options, CH± and RW/FF scroll a page.
                        Key.Info, Key.MediaPlayPause, Key.MediaPlay -> { if (down) onMenu(programmeAt(progs, cursorAt())); true }
                        Key.ProgramRed, Key.MediaStop -> { if (down) onFavorite(); true }
                        Key.ProgramGreen -> { if (down) onChannelMenu(); true }
                        Key.ChannelUp, Key.PageUp, Key.MediaRewind -> { if (down) onStep(-GUIDE_PAGE_ROWS, false); true }
                        Key.ChannelDown, Key.PageDown, Key.MediaFastForward -> { if (down) onStep(GUIDE_PAGE_ROWS, false); true }
                        // Physical by design: the timeline runs left to right in every language.
                        // Row stage: ◀ shows the groups (◀ again reaches the menu), ▶ steps into the programmes.
                        Key.DirectionLeft -> if (!cellMode) { if (down) onGroups(); true } else {
                            if (down) onCursor(cursorStep(progs, cursorTime(), -1, windowStart, windowEnd))
                            true
                        }
                        Key.DirectionRight -> {
                            if (down && !cellMode) onEnterCell()
                            else if (down) onCursor(cursorStep(progs, cursorTime(), +1, windowStart, windowEnd))
                            true
                        }
                        Key.DirectionUp -> if (down) onStep(-1, e.nativeKeyEvent.repeatCount == 0) else true
                        Key.DirectionDown -> if (down) onStep(+1, e.nativeKeyEvent.repeatCount == 0) else true
                        else -> false
                    }
                }
                .focusable(),
        ) {
            programmes?.let { progs ->
                val catchupIds = remember(progs, channel, now) { progs.filter { vm.canCatchup(channel, it, now) }.mapTo(HashSet()) { it.id } }
                ProgrammeStripCanvas(
                    programmes = progs,
                    windowStart = windowStart,
                    windowEnd = windowEnd,
                    now = now,
                    // The row in focus lights the programme on now (its cell, dark text on the light fill);
                    // in the programmes, the one under the cursor. A whole light row hid its white titles.
                    focusTime = if (!focused) null else if (cellMode) rowCursor else now,
                    catchupIds = catchupIds,
                    recordingStarts = recordingStarts,
                    reminderStarts = reminderStarts,
                    scrollPx = scrollPx,
                )
            }
        }
    }
}

/** The day and time at the guide's corner, in a scope of its own: the timeline's scroll recomposes this text alone. */
@Composable
private fun GuideCornerText(text: () -> String, modifier: Modifier = Modifier) {
    Text(
        text(),
        style = stageText(tv.own.owntv.ui.theme.StageListText, 700), color = stageAccent.accent,
        maxLines = 1, overflow = TextOverflow.Ellipsis,
        modifier = modifier,
    )
}

/**
 * The guide's top area: the video at the left, as in the classic IPTV player, and beside it the programme
 * under the cursor ([top]: the channel and programme to show). Its own composable so that the reads of the
 * focused row and the cursor happen here: each ▲ ▼ then rebuilds this area alone instead of the whole guide.
 */
@Composable
private fun GuideTopArea(
    vm: EpgViewModel,
    top: () -> Pair<ChannelEntity, EpgProgrammeEntity?>?,
    /** The preview's channel while the preview is on; else the video follows [top]. */
    previewChannel: ChannelEntity?,
    previewEngine: tv.own.owntv.player.LivePreviewEngine,
    showVideo: Boolean,
    now: () -> Long,
    /**
     * How far the open groups push the guide past the right edge of the screen. The programme text keeps clear
     * of it, so a long title ends in "…" on screen instead of being cut off by the edge.
     */
    endClear: androidx.compose.ui.unit.Dp = 0.mpx,
    modifier: Modifier = Modifier,
) {
    val shownTop = top()
    val topChannel = shownTop?.first
    val topProgramme = shownTop?.second
    val full by produceState<EpgProgrammeEntity?>(null, topProgramme?.id) {
        value = topProgramme?.id?.takeIf { it > 0 }?.let { vm.programmeFull(it) }
    }
    val shown = full ?: topProgramme
    Row(modifier) {
        GuideVideo(
            // No channel logo in the corner: it changed with every row passed. Black until a channel plays, then
            // only its picture (Jonathan, 27 Oct 2026).
            channel = null,
            previewEngine = previewEngine,
            showVideo = showVideo,
            shellDrawsVideo = true,
            // Where the picture lands when full screen closes (the shell shrinks it into this corner).
            modifier = Modifier.width(426.mpx).onGloballyPositioned {
                GuidePreviewSpot.rect = it.boundsInRoot().translate(-GuidePreviewSpot.shift(), 0f)
            },
        )
        Box(Modifier.weight(1f).fillMaxHeight()) {
            if (topChannel != null) {
                val chName = ProviderTags.parse(topChannel.name).name
                GuideProgrammeBlock(
                    // No time line over the title (30 Oct 2026): the grid below says when, the title what.
                    eyebrow = null,
                    title = shown?.title ?: chName,
                    details = if (shown == null) stringResource(R.string.content_epg_no_programme) else programmeDetailsLine(shown),
                    synopsis = shown?.description,
                    modifier = Modifier.padding(start = 46.mpx, top = 8.mpx, end = endClear).widthIn(max = 1080.mpx),
                )
            }
        }
    }
}

/** An empty guide: what is missing, and — with no EPG added — the way to add one. */
@Composable
private fun GuideEmpty(
    title: String,
    body: String? = null,
    action: String? = null,
    onAction: () -> Unit = {},
    actionFocus: FocusRequester? = null,
) {
    Box(Modifier.fillMaxSize().padding(bottom = 80.mpx), contentAlignment = Alignment.Center) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Text(title, style = stageText(24, 700), color = StageColors.Text, textAlign = TextAlign.Center, modifier = Modifier.widthIn(max = 900.mpx))
            if (body != null) {
                Text(body, style = stageText(19, 400), color = StageColors.Muted, textAlign = TextAlign.Center, modifier = Modifier.widthIn(max = 900.mpx).padding(top = 8.mpx))
            }
            if (action != null) {
                Spacer(Modifier.height(24.mpx))
                StageButton(
                    action, onClick = onAction, icon = OwnTVIcon.ADD, height = 56.mpx, textSize = 19,
                    modifier = if (actionFocus != null) Modifier.focusRequester(actionFocus) else Modifier,
                )
            }
        }
    }
}

/** Rows a page key (CH±, RW/FF) moves the guide by. */
private const val GUIDE_PAGE_ROWS = 7

/**
 * Where the guide's first channel row begins: the top area (40 + 240), the grid's gap (18), the ruler and
 * the rows' gap below it (48). Menu and groups start here too, so the three columns begin on one line.
 */
internal val GuideRowsTop = (40 + 240 + 18 + 48).mpx

/**
 * The middle of the guide's first channel row. The menu's first section and the first group are centred on it,
 * not started at its top: their rows are 68 and 52 high against the guide's 56, so started at the top the
 * group's count sat 18 below the row number and the menu 6 below (Jonathan, 31 Oct 2026).
 */
internal val GuideFirstRowCentre = GuideRowsTop + GuideGridDefaults.RowHeight / 2

/** Between the groups' search field and the first group: small, so the field stays on the ruler's line. */
private val GroupsSearchGap = 8.mpx

/** The groups column's top: the list's padding (4), the search field (48) and the gap above the first group (52, centred). */
private val GroupsTop = GuideFirstRowCentre - 26.mpx - GroupsSearchGap - 48.mpx - 4.mpx

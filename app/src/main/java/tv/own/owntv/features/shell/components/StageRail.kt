package tv.own.owntv.features.shell.components

import androidx.compose.ui.draw.clip
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
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.withFrameNanos
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.focus.FocusDirection
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusProperties
import androidx.compose.ui.input.key.type
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.onKeyEvent
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.LayoutDirection
import androidx.tv.material3.Text
import kotlinx.coroutines.launch
import tv.own.owntv.R
import tv.own.owntv.core.nav.MainSection
import tv.own.owntv.ui.components.BrandMark
import tv.own.owntv.ui.components.OwnTVIcon
import tv.own.owntv.ui.stage.StageSurface
import tv.own.owntv.ui.theme.StageColors
import tv.own.owntv.ui.theme.mpx
import tv.own.owntv.ui.theme.stageAccent
import tv.own.owntv.ui.theme.stageText

/** What the Stage rail shows right now: the Navigation setting plus where focus is (see OwnTVShell). */
enum class RailState {
    /** A 7 px accent glow at the screen edge; the items stay focusable so ◀ can still reach them. */
    HIDDEN,
    /** The 84 px icon capsule: Floating at rest, or Size = Compact. */
    CAPSULE,
    /** The chosen Size over a scrim: the floating rail while it holds focus. */
    OPEN,
    /** The chosen Size without the scrim: Navigation = Docked. */
    PINNED,
}

/** The menu order: Home on top (Jonathan's start page), then TV (which opens as the guide), More last. Search
 *  stands apart above them, right under the logo. */
private val StageRailOrder = listOf(
    MainSection.HOME, MainSection.LIVE_TV, MainSection.SPORTS,
    MainSection.MOVIES, MainSection.SERIES, MainSection.DOWNLOADS,
)

/**
 * Where content starts below the top-right cluster (34 + its 68 height + a gap) on screens that have not
 * been redrawn for Stage yet. Each screen's own phase takes this away as it gets the full canvas.
 */
val StageContentTop = 110.mpx

/**
 * The Stage navigation rail: a strip of icons down the left edge that opens with the names while it holds
 * focus. Entering the rail from the content always lands on the selected section, and ▶ out of it returns
 * to exactly where the content was ([contentFocusRequester]).
 */
@Composable
fun StageRail(
    state: RailState,
    selected: MainSection,
    visibleSections: Set<MainSection>,
    onSelect: (MainSection) -> Unit,
    profileName: String,
    profileLine: String,
    onSwitchProfile: () -> Unit,
    onPickAvatar: () -> Unit,
    /** The profile's drawn avatar (-1 = none) and its own picture (blank = none). */
    avatarId: Int = -1,
    avatarPath: String = "",
    selectedItemFocusRequester: FocusRequester,
    contentFocusRequester: FocusRequester,
    /** A screen's own way in, when it has one (Home returns to the exact control left from). */
    enterContent: (() -> Boolean)? = null,
    onFocused: () -> Unit,
    /** False while the player is on screen or handing over: no item may take focus then, whatever Compose falls back to. */
    itemsFocusable: () -> Boolean = { true },
    modifier: Modifier = Modifier,
) {
    // TV Player: the menu as the classic IPTV player draws it. At rest a narrow strip of icons down the
    // left edge; with focus it widens to icons and names, the app name on top and Settings at the bottom.
    // Focus is the near-white bar every list uses; the open section keeps a lighter bar.
    val expanded = state == RailState.OPEN || state == RailState.PINNED
    val rtl = LocalLayoutDirection.current == LayoutDirection.Rtl
    val scope = rememberCoroutineScope()
    var hasFocus by remember { mutableStateOf(false) }
    // Search, Settings and More all land on a rail item: Search has its own, Settings lives behind More.
    val focusSection = when {
        selected == MainSection.SETTINGS || selected == MainSection.MORE -> MainSection.MORE
        selected == MainSection.SEARCH || selected in visibleSections -> selected
        else -> StageRailOrder.firstOrNull { it in visibleSections } ?: MainSection.MORE
    }
    val items = StageRailOrder.filter { it in visibleSections }

    // Opened over the page (Floating, or a widened Compact): the page slides aside ([rememberRailPush]),
    // bright as before, as in the classic IPTV player. Pages that add a column beside the menu read it.
    val openOverPage = state == RailState.OPEN
    androidx.compose.runtime.SideEffect { StageRailOpen.open = openOverPage }
    // Opening, the menu slides in from the left edge alongside the page it pushes, as in TiviMate. Closing,
    // it slides back out the same way: it keeps its names until it is in, instead of snapping to the strip
    // on the first frame while the page still slid (28 Oct 2026).
    val openness = androidx.compose.animation.core.animateFloatAsState(if (expanded) 1f else 0f, tv.own.owntv.ui.theme.stageSlide())
    val stillOut by remember { androidx.compose.runtime.derivedStateOf { openness.value > 0f } }
    val drawnOpen = expanded || stillOut
    Box(modifier.fillMaxSize()) {
        Column(
            modifier = Modifier
                .align(Alignment.CenterStart)
                .fillMaxHeight()
                .width(if (drawnOpen) MenuWidth else StripWidth)
                .graphicsLayer {
                    if (drawnOpen) translationX = -(1f - openness.value) * (MenuWidth - StripWidth).toPx() * if (rtl) -1f else 1f
                    compositingStrategy = tv.own.owntv.ui.theme.slideCompositing(openness.value != 0f && openness.value != 1f)
                }
                .onFocusChanged {
                    // Every entry from the content lands on the selected section, not on whatever item
                    // happens to line up; moves inside the rail do not re-trigger this.
                    val entered = it.hasFocus && !hasFocus
                    hasFocus = it.hasFocus
                    if (it.hasFocus) onFocused()
                    if (entered) scope.launch { runCatching { selectedItemFocusRequester.requestFocus() } }
                }
                .focusProperties {
                    onExit = {
                        val out = if (rtl) FocusDirection.Left else FocusDirection.Right
                        if (requestedFocusDirection == out && enterContent != null) {
                            // Focus cannot be moved from inside a focus change: stop this one, and let
                            // the screen place focus itself on the next frame.
                            cancelFocusChange()
                            scope.launch {
                                withFrameNanos { }
                                if (!enterContent()) runCatching { contentFocusRequester.requestFocus() }
                            }
                        } else if (requestedFocusDirection == out && runCatching { contentFocusRequester.requestFocus() }.getOrDefault(false)) {
                            cancelFocusChange()
                        }
                    }
                }
                // ▶ always leaves for the content (the open menu covers the content's left column).
                .onKeyEvent { e ->
                    val out = if (rtl) Key.DirectionLeft else Key.DirectionRight
                    if (e.key != out) return@onKeyEvent false
                    if (e.type == KeyEventType.KeyDown) scope.launch {
                        if (enterContent?.invoke() != true) runCatching { contentFocusRequester.requestFocus() }
                    }
                    true
                }
                .focusGroup()
                .background(tv.own.owntv.ui.theme.inScene(if (drawnOpen) MenuPanel else StripPanel))
                // A hairline at its inner edge parts the menu from the page instead of a brighter panel.
                .drawBehind {
                    val w = 1.mpx.toPx()
                    val x = if (layoutDirection == LayoutDirection.Rtl) 0f else size.width - w
                    drawRect(Color.White.copy(alpha = 0.07f), topLeft = Offset(x, 0f), size = Size(w, size.height))
                }
                .padding(horizontal = if (drawnOpen) 26.mpx else 14.mpx, vertical = 40.mpx),
            horizontalAlignment = if (drawnOpen) Alignment.Start else Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(8.mpx),
        ) {
            MenuLogo(drawnOpen)
            // Search right under the logo, apart from the sections (Jonathan, 30 Oct 2026: further up).
            RailItem(
                icon = MainSection.SEARCH.stageIcon,
                label = stringResource(MainSection.SEARCH.labelRes),
                expanded = drawnOpen,
                active = selected == MainSection.SEARCH,
                onClick = { onSelect(MainSection.SEARCH) },
                modifier = (if (focusSection == MainSection.SEARCH) Modifier.focusRequester(selectedItemFocusRequester) else Modifier)
                    .focusProperties { canFocus = itemsFocusable() },
            )
            // Beside the guide the items start on its first channel row, so menu, groups and guide begin on
            // one line (30 Oct 2026); elsewhere they sit a little above the middle as before. The logo stays on top.
            val itemsTop = StageRailOpen.itemsTop
            if (itemsTop != null) Spacer(Modifier.height((itemsTop - RailItemsOffset).coerceAtLeast(0.mpx))) else Spacer(Modifier.weight(0.55f))
            items.forEach { section ->
                RailItem(
                    icon = section.stageIcon,
                    label = stringResource(section.labelRes),
                    expanded = drawnOpen,
                    active = section == selected,
                    onClick = { onSelect(section) },
                    modifier = (if (section == focusSection) Modifier.focusRequester(selectedItemFocusRequester) else Modifier)
                        .focusProperties { canFocus = itemsFocusable() },
                )
            }
            Spacer(Modifier.weight(1f))
            // Settings at the bottom, like the classic player's gear (More holds Settings and the rest).
            RailItem(
                icon = OwnTVIcon.GEAR,
                label = stringResource(MainSection.MORE.labelRes),
                expanded = drawnOpen,
                active = selected == MainSection.MORE || selected == MainSection.SETTINGS,
                onClick = { onSelect(MainSection.MORE) },
                modifier = (if (focusSection == MainSection.MORE) Modifier.focusRequester(selectedItemFocusRequester) else Modifier)
                    .focusProperties { canFocus = itemsFocusable() },
            )
        }
    }
}

/** The open menu and the resting strip, each only a little lighter than the calm page (30 Oct 2026). */
private val MenuPanel = Color(0xFF0F5056)
private val StripPanel = Color(0xFF0E4950)
private val MenuWidth = 440.mpx
private val StripWidth = 110.mpx

/** Where the first section would start without a spacer: the menu's top padding, the logo (56 + 4 around),
 *  Search under it (68) and the gaps between them. */
private val RailItemsOffset = (40 + 64 + 8 + 68 + 8 + 8).mpx

/** What every screen leaves free at its left edge for the resting icon strip. */
val StageRailStripWidth = StripWidth

/** Whether the menu is open over the page right now, for a page that shows its own column beside it. */
object StageRailOpen {
    var open by mutableStateOf(false)
    /** The guide's categories are out (focused or being entered): the status cluster steps aside for them too. */
    var guideGroups by mutableStateOf(false)
    /** The guide holds focus: the strip steps aside, as in TiviMate, and the guide takes its room. ◀ brings it back. */
    var guideFocused by mutableStateOf(false)
    /** A page that takes the whole width like the guide is shown (a match page). */
    var fullPage by mutableStateOf(false)
    /** That page holds focus: the strip steps aside as for the guide. ◀ brings it back. */
    var fullPageFocused by mutableStateOf(false)
    /** Where a page wants the menu's items to begin (the guide: its first channel row); null = the menu's own place. */
    var itemsTop by mutableStateOf<androidx.compose.ui.unit.Dp?>(null)
}

/**
 * How far the page slides aside while the menu is open over it, as in the classic IPTV player: the page
 * moves over by the menu's extra width instead of lying cut off under it. A docked menu keeps its own
 * room and pushes nothing. Read it inside a graphics layer, so the slide redraws without recomposing.
 */
@Composable
fun rememberRailPush(state: RailState): androidx.compose.runtime.State<androidx.compose.ui.unit.Dp> =
    androidx.compose.animation.core.animateDpAsState(railPushTarget(state), tv.own.owntv.ui.theme.stageSlide())

/** Where [rememberRailPush] comes to rest for [state]: a slide is under way while the push is anywhere else. */
fun railPushTarget(state: RailState): androidx.compose.ui.unit.Dp = if (state == RailState.OPEN) MenuWidth - StripWidth else 0.mpx

/** The app's name in the accent at the top of the open menu; its first letters on the strip. */
@Composable
private fun MenuLogo(expanded: Boolean) {
    // Mayo TV: the floodlight mark on the strip, with the name beside it when the menu is open.
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier.padding(start = if (expanded) 10.mpx else 0.mpx),
    ) {
        // In the theme's muted ink, so the logo belongs to the menu instead of standing out of it.
        BrandMark(56.mpx, Modifier.padding(4.mpx), subtle = true)
        if (expanded) {
            Text(
                stringResource(R.string.tvplayer_app_name),
                style = stageText(40, 800),
                color = StageColors.Muted,
                maxLines = 1, overflow = TextOverflow.Ellipsis,
                modifier = Modifier.padding(start = 18.mpx),
            )
        }
    }
}

private val MainSection.stageIcon: OwnTVIcon
    get() = when (this) {
        MainSection.SEARCH -> OwnTVIcon.SEARCH
        MainSection.HOME -> OwnTVIcon.HOME
        MainSection.LIVE_TV -> OwnTVIcon.LIVE_TV
        MainSection.EPG -> OwnTVIcon.EPG
        MainSection.SPORTS -> OwnTVIcon.SPORTS
        MainSection.MOVIES -> OwnTVIcon.MOVIES
        MainSection.SERIES -> OwnTVIcon.SERIES
        MainSection.DOWNLOADS -> OwnTVIcon.DOWNLOADS
        MainSection.MORE, MainSection.SETTINGS -> OwnTVIcon.TILES
    }

/**
 * One menu row: on the strip only the icon (64 square), open the icon and the name. Focus is the white
 * bar with near-black icon and text; the open section a faint bar, the other icons a step quieter (30 Oct 2026).
 */
@Composable
private fun RailItem(
    icon: OwnTVIcon,
    label: String,
    expanded: Boolean,
    active: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val a = stageAccent
    val r = 10.mpx
    StageSurface(
        onClick = onClick,
        radius = r,
        idle = if (active) Modifier.background(Color.White.copy(alpha = 0.08f), RoundedCornerShape(r)) else Modifier,
        modifier = modifier.then(if (expanded) Modifier.fillMaxWidth().heightIn(min = 68.mpx) else Modifier.size(68.mpx)),
        contentAlignment = if (expanded) Alignment.CenterStart else Alignment.Center,
    ) { focused ->
        val tint = if (focused) a.onAccent else Color.White
        val iconTint = if (focused || active) tint else Color.White.copy(alpha = 0.62f)
        if (!expanded) {
            OwnTVIcon(icon, iconTint, Modifier.size(26.mpx))
            return@StageSurface
        }
        Row(
            Modifier.fillMaxWidth().padding(horizontal = 20.mpx, vertical = 8.mpx),
            horizontalArrangement = Arrangement.spacedBy(24.mpx),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            OwnTVIcon(icon, iconTint, Modifier.size(26.mpx))
            Text(
                label,
                style = stageText(tv.own.owntv.ui.theme.StageListText, 600),
                color = tint,
                maxLines = 1, overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f),
            )
        }
    }
}

/**
 * `.frail .av`: the profile's avatar or own picture in a circle; with neither, its initial on the orange
 * gradient, 20/800 in #2A0D12. More's profile row draws it 58 / 24.
 */
@Composable
internal fun RailAvatar(initial: String, size: Int = 46, textSize: Int = 20, avatarId: Int = -1, imagePath: String = "") {
    if (avatarId != -1 || imagePath.isNotBlank()) {
        tv.own.owntv.ui.components.OwnTVAvatar(avatarId = avatarId, imagePath = imagePath, modifier = Modifier.size(size.mpx).clip(CircleShape))
        return
    }
    Box(
        Modifier
            .size(size.mpx)
            .background(Brush.linearGradient(listOf(Color(0xFFFFB35C), Color(0xFFFF5F7A))), CircleShape),
        contentAlignment = Alignment.Center,
    ) {
        Text(initial, style = stageText(textSize, 800), color = Color(0xFF2A0D12), maxLines = 1, overflow = TextOverflow.Ellipsis)
    }
}

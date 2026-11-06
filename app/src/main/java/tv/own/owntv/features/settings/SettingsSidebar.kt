package tv.own.owntv.features.settings

import androidx.compose.foundation.background
import androidx.compose.foundation.focusGroup
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.height
import androidx.compose.ui.layout.boundsInRoot
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusProperties
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.em
import androidx.tv.material3.Text
import tv.own.owntv.R
import tv.own.owntv.ui.components.OwnTVIcon
import tv.own.owntv.ui.stage.StageGroupItem
import tv.own.owntv.ui.theme.LocalStagePalette
import tv.own.owntv.ui.theme.StageColors
import tv.own.owntv.ui.theme.mpx
import tv.own.owntv.ui.theme.stageText

/**
 * One category of the Settings root: its position in the page's groups, its name, icon and a value (only
 * Profiles has one, their number: settings groups show no counts). [mine] lists it under "Your things".
 */
class SettingsCategory(val index: Int, val label: String, val icon: OwnTVIcon, val count: Int?, val mine: Boolean = false)

/**
 * One of More's places in the same list (Favourites, History, Backup …): [section] 0 = your things (above the
 * settings), 1 = keep it safe, 2 = about the app (both below). Settings search finds it by [label] and by
 * [aliases] (what can be done there: "Restore", "Clear history").
 */
class SidebarExtra(
    val key: String,
    val section: Int,
    val icon: OwnTVIcon,
    val label: String,
    val value: String?,
    val warn: Boolean = false,
    val aliases: List<String> = emptyList(),
)

/** Where focus goes into a More page shown beside the list: its own way in, or a fixed target. */
class MorePageFocus {
    val target = FocusRequester()
    var entry: (() -> Boolean)? = null
    fun enter(): Boolean = entry?.invoke() ?: runCatching { target.requestFocus() }.getOrDefault(false)
}

/**
 * Mayo TV (1 Nov 2026): More and Settings are one screen. The shell hands Settings More's head (the profile),
 * its places, and how to draw a place's page.
 */
class SettingsMoreHost(
    val header: @Composable () -> Unit,
    val extras: List<SidebarExtra>,
    val page: @Composable (key: String, modifier: Modifier, focus: MorePageFocus, onOpenLanguage: () -> Unit) -> Unit,
)

/**
 * The list down the left of Settings, drawn as More's sheet: the theme's panel colour full height with a line
 * at its edge, the profile on top, then Search, your things, the settings groups, keeping it safe and the app.
 * The open entry follows focus (as the guide's categories do); ▶ or OK goes into its page. Entering the list
 * from the page, or from the rail, always lands on the open entry.
 */
@Composable
fun SettingsSidebar(
    categories: List<SettingsCategory>,
    extras: List<SidebarExtra>,
    selected: Int,
    selectedExtra: String?,
    searching: Boolean,
    onSelect: (Int) -> Unit,
    onSelectExtra: (SidebarExtra) -> Unit,
    onEnterRows: () -> Unit,
    onSearch: () -> Unit,
    selectedFocus: FocusRequester,
    onFocusChanged: (Boolean) -> Unit,
    header: (@Composable () -> Unit)?,
    modifier: Modifier = Modifier,
    /**
     * The line the first entry is centred on (from the screen's top): Live TV's, where the menu's first section,
     * the first group and the first channel sit, so the lists start on one height on every page.
     */
    alignCentre: androidx.compose.ui.unit.Dp? = null,
) {
    val density = androidx.compose.ui.platform.LocalDensity.current
    // Measured, as in Live TV: the first entry's real middle against the line, the space above moved by the difference.
    var fixPx by androidx.compose.runtime.remember { androidx.compose.runtime.mutableStateOf(0f) }
    val listScroll = rememberScrollState()
    val alignFirst = Modifier.then(
        if (alignCentre != null) {
            Modifier.onGloballyPositioned { c ->
                // Only with the list at its top: scrolled, the entry is meant to be elsewhere.
                val off = with(density) { alignCentre.toPx() } - c.boundsInRoot().center.y
                if (listScroll.value == 0 && kotlin.math.abs(off) > 1f) fixPx = (fixPx + off).coerceIn(-400f, 600f)
            }
        } else Modifier,
    )
    val requesters = remember { HashMap<String, FocusRequester>() }
    val searchItem = remember { FocusRequester() }
    fun requesterOf(id: String) = requesters.getOrPut(id) { FocusRequester() }
    val palette = LocalStagePalette.current
    // The entry that holds [selectedFocus]: a More place, or the open settings group.
    val openId = when {
        searching -> null
        selectedExtra != null -> "x:$selectedExtra"
        else -> "g:$selected"
    }
    @Composable
    fun Extra(e: SidebarExtra, first: Boolean = false) {
        val id = "x:${e.key}"
        StageGroupItem(
            text = e.label, icon = e.icon, value = e.value, warn = e.warn,
            selected = openId == id,
            onClick = onEnterRows,
            modifier = (if (first) alignFirst else Modifier)
                .focusRequester(if (openId == id) selectedFocus else requesterOf(id))
                .onFocusChanged { if (it.isFocused && openId != id) onSelectExtra(e) },
        )
    }
    Column(
        modifier
            .width(440.mpx)
            .fillMaxHeight()
            .background(palette.glassTint)
            .drawBehind {
                val line = 2.mpx.toPx()
                drawRect(palette.ink.copy(alpha = 0.12f), Offset(size.width - line, 0f), Size(line, size.height))
            }
            .padding(start = 20.mpx, end = 20.mpx, top = 48.mpx, bottom = 32.mpx)
            .onFocusChanged { onFocusChanged(it.hasFocus) }
            .focusProperties {
                onEnter = { runCatching { (if (searching) searchItem else selectedFocus).requestFocus() } }
            }
            .onPreviewKeyEvent { e ->
                if (e.key == Key.DirectionRight && e.type == KeyEventType.KeyDown) { onEnterRows(); true }
                else e.key == Key.DirectionRight
            }
            .focusGroup()
            .verticalScroll(listScroll),
        verticalArrangement = Arrangement.spacedBy(2.mpx),
    ) {
        // The space that brings the first entry onto the line (see [alignCentre]).
        if (alignCentre != null) androidx.compose.foundation.layout.Spacer(
            Modifier.height(with(density) { fixPx.coerceAtLeast(0f).toDp() }),
        )
        header?.invoke()
        StageGroupItem(
            text = stringResource(R.string.more_settings_search),
            icon = OwnTVIcon.SEARCH,
            selected = searching,
            onClick = onSearch,
            modifier = Modifier.focusRequester(searchItem),
        )
        @Composable
        fun Category(c: SettingsCategory, first: Boolean) {
            val id = "g:${c.index}"
            StageGroupItem(
                text = c.label, icon = c.icon, value = c.count?.toString(),
                selected = openId == id,
                onClick = onEnterRows,
                modifier = (if (first) alignFirst else Modifier)
                    .focusRequester(if (openId == id) selectedFocus else requesterOf(id))
                    .onFocusChanged { if (it.isFocused && openId != id) onSelect(c.index) },
            )
        }
        // Your things: Profiles (a settings group drawn here), then Favourites and History.
        val mineGroups = categories.filter { it.mine }
        val mine = extras.filter { it.section == 0 }
        if (mineGroups.isNotEmpty() || mine.isNotEmpty()) {
            SidebarLabel(stringResource(R.string.more_group_your_things))
            mineGroups.forEachIndexed { i, c -> Category(c, first = i == 0) }
            mine.forEachIndexed { i, e -> Extra(e, first = i == 0 && mineGroups.isEmpty()) }
        }
        SidebarLabel(stringResource(R.string.common_nav_settings))
        categories.filterNot { it.mine }.forEachIndexed { i, c ->
            Category(c, first = i == 0 && mineGroups.isEmpty() && mine.isEmpty())
        }
        val safe = extras.filter { it.section == 1 }
        if (safe.isNotEmpty()) {
            SidebarLabel(stringResource(R.string.more_group_keep_safe))
            safe.forEach { Extra(it) }
        }
        val app = extras.filter { it.section == 2 }
        if (app.isNotEmpty()) {
            SidebarLabel(stringResource(R.string.settings_mayo_heading_info))
            app.forEach { Extra(it) }
        }
    }
}

/** More's group label: 12.5/800 caps in dim. */
@Composable
private fun SidebarLabel(text: String) {
    Text(
        text.uppercase(androidx.compose.ui.platform.LocalConfiguration.current.locales[0]),
        style = stageText(12.5f, 800, 0.13.em), color = StageColors.Dim, maxLines = 1, overflow = TextOverflow.Ellipsis,
        modifier = Modifier.padding(start = 14.mpx, top = 18.mpx, bottom = 6.mpx),
    )
}

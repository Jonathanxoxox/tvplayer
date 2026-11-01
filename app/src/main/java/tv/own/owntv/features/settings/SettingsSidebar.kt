package tv.own.owntv.features.settings

import androidx.compose.foundation.background
import androidx.compose.foundation.focusGroup
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusProperties
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.tv.material3.Text
import tv.own.owntv.R
import tv.own.owntv.ui.components.OwnTVIcon
import tv.own.owntv.ui.stage.StageFocus
import tv.own.owntv.ui.stage.StageSurface
import tv.own.owntv.ui.theme.StageColors
import tv.own.owntv.ui.theme.StageListText
import tv.own.owntv.ui.theme.mpx
import tv.own.owntv.ui.theme.stageAccent
import tv.own.owntv.ui.theme.stageText
import tv.own.owntv.ui.theme.stageTone

/** One category of the Settings root: its position in the page's groups, its name, icon and how many settings it holds. */
class SettingsCategory(val index: Int, val label: String, val icon: OwnTVIcon, val count: Int?)

/**
 * Mayo TV (1 Nov 2026, Jonathan picked draft A): the Settings root's categories as a list down the left, drawn
 * like the open menu: a panel a shade lighter than the page, one row per category with its icon, name and
 * count, the near-white bar on focus and a faint bar on the open one. Search comes first.
 *
 * The open category follows focus (as the guide's categories do), so ▲ ▼ show each one's rows at once;
 * ▶ or OK goes into them. Entering the list from the rows, or from the rail, always lands on the open
 * category rather than whichever row happens to line up.
 */
@Composable
fun SettingsSidebar(
    categories: List<SettingsCategory>,
    selected: Int,
    searching: Boolean,
    onSelect: (Int) -> Unit,
    onEnterRows: () -> Unit,
    onSearch: () -> Unit,
    selectedFocus: FocusRequester,
    onFocusChanged: (Boolean) -> Unit,
    modifier: Modifier = Modifier,
) {
    val requesters = remember { HashMap<Int, FocusRequester>() }
    val searchItem = remember { FocusRequester() }
    fun requesterOf(index: Int) = requesters.getOrPut(index) { FocusRequester() }
    Column(
        modifier
            .background(SidebarPanel, RoundedCornerShape(18.mpx))
            .padding(10.mpx)
            .onFocusChanged { onFocusChanged(it.hasFocus) }
            .focusProperties {
                onEnter = {
                    runCatching { (if (searching) searchItem else selectedFocus).requestFocus() }
                }
            }
            .onPreviewKeyEvent { e ->
                if (e.key == Key.DirectionRight && e.type == KeyEventType.KeyDown) { onEnterRows(); true }
                else e.key == Key.DirectionRight
            }
            .focusGroup()
            .verticalScroll(rememberScrollState()),
        verticalArrangement = Arrangement.spacedBy(4.mpx),
    ) {
        SidebarItem(
            icon = OwnTVIcon.SEARCH,
            label = stringResource(R.string.more_settings_search),
            count = null,
            selected = searching,
            onClick = onSearch,
            modifier = Modifier.focusRequester(searchItem),
        )
        categories.forEach { c ->
            val isSelected = !searching && c.index == selected
            SidebarItem(
                icon = c.icon,
                label = c.label,
                count = c.count,
                selected = isSelected,
                onClick = onEnterRows,
                modifier = Modifier
                    .focusRequester(if (c.index == selected) selectedFocus else requesterOf(c.index))
                    .onFocusChanged { if (it.isFocused && (searching || c.index != selected)) onSelect(c.index) },
            )
        }
    }
}

@Composable
private fun SidebarItem(
    icon: OwnTVIcon,
    label: String,
    count: Int?,
    selected: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val a = stageAccent
    StageSurface(
        onClick = onClick,
        radius = 12.mpx,
        focusStyle = StageFocus.FX,
        idle = if (selected) Modifier.background(Color.White.copy(alpha = 0.12f), RoundedCornerShape(12.mpx)) else Modifier,
        modifier = modifier.fillMaxWidth().height(56.mpx),
    ) { focused ->
        Row(
            Modifier.fillMaxWidth().padding(horizontal = 18.mpx),
            horizontalArrangement = Arrangement.spacedBy(16.mpx),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            OwnTVIcon(icon, if (focused || selected) StageColors.Text else StageColors.Muted, Modifier.size(24.mpx))
            Text(
                label, style = stageText(StageListText, 700), color = StageColors.Text,
                maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f),
            )
            if (count != null) {
                Text(
                    count.toString(), style = stageText(17, 700),
                    color = if (selected && !focused) a.accent else StageColors.Dim,
                    maxLines = 1, overflow = TextOverflow.Ellipsis,
                )
            }
        }
    }
}

/** The menu's panel colour, a shade lighter than the page (the light theme's control fill). */
private val SidebarPanel: Color @Composable get() = stageTone(Color(0xFF0F5056), StageColors.ControlFill)

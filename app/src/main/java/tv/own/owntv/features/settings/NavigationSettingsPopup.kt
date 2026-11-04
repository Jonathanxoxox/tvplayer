package tv.own.owntv.features.settings

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.res.stringResource
import androidx.tv.material3.Text
import tv.own.owntv.R
import tv.own.owntv.core.nav.MainSection
import tv.own.owntv.core.settings.SettingsRepository.NavMenuMode
import tv.own.owntv.ui.components.OwnTVIcon
import tv.own.owntv.ui.stage.StagePopup
import tv.own.owntv.ui.stage.StagePopupOption
import tv.own.owntv.ui.stage.StagePill
import tv.own.owntv.ui.theme.StageColors
import tv.own.owntv.ui.theme.mpx

/**
 * Settings › Layout › Navigation: the rail's Menu items (Dynamic / Static; Static lists the sections as
 * toggles). Mayo TV has one rail, the icon strip that opens with names on focus, so there is no size, length
 * or Floating / Docked choice. OK on the value row steps to its next choice.
 */
@Composable
fun NavigationSettingsPopup(
    menuMode: NavMenuMode,
    onMenuMode: (NavMenuMode) -> Unit,
    hiddenSections: Set<MainSection>,
    onSectionHidden: (MainSection, Boolean) -> Unit,
    onDismiss: () -> Unit,
) {
    val first = remember { FocusRequester() }
    LaunchedEffect(Unit) { runCatching { first.requestFocus() } }
    BackHandler { onDismiss() }
    val locale = LocalConfiguration.current.locales[0]
    val eyebrow = stringResource(
        R.string.settings_breadcrumb_eyebrow,
        stringResource(R.string.settings_title),
        stringResource(R.string.settings_group_controls),
    ).uppercase(locale)

    StagePopup(onDismiss = onDismiss, title = stringResource(R.string.settings_navigation), eyebrow = eyebrow) {
        val static = menuMode == NavMenuMode.STATIC
        StagePopupOption(
            title = stringResource(R.string.settings_nav_menu_items),
            subtitle = stringResource(R.string.settings_nav_menu_items_desc),
            value = stringResource(if (static) R.string.settings_static else R.string.settings_dynamic),
            onClick = { onMenuMode(if (static) NavMenuMode.DYNAMIC else NavMenuMode.STATIC) },
            modifier = Modifier.focusRequester(first),
            leading = { OwnTVIcon(OwnTVIcon.GRID, StageColors.Text, Modifier.size(21.mpx)) },
        )
        // Static: the browse sections as toggles, in rail order; Search and More can never be hidden.
        if (static) {
            FlowRow(
                Modifier.padding(start = 61.mpx, end = 22.mpx, top = 6.mpx, bottom = 14.mpx),
                horizontalArrangement = Arrangement.spacedBy(12.mpx),
                verticalArrangement = Arrangement.spacedBy(12.mpx),
            ) {
                MenuSections.forEach { (section, icon) ->
                    val shown = section !in hiddenSections
                    StagePill(
                        text = stringResource(section.labelRes),
                        onClick = { onSectionHidden(section, shown) },
                        icon = icon,
                        trailingIcon = if (shown) OwnTVIcon.CHECK else OwnTVIcon.EYE_OFF,
                        dimmed = !shown,
                    )
                }
            }
        }
    }
}

private val MenuSections = listOf(
    MainSection.HOME to OwnTVIcon.HOME,
    MainSection.LIVE_TV to OwnTVIcon.LIVE_TV,
    MainSection.SPORTS to OwnTVIcon.SPORTS,
    MainSection.MOVIES to OwnTVIcon.MOVIES,
    MainSection.SERIES to OwnTVIcon.SERIES,
    MainSection.DOWNLOADS to OwnTVIcon.DOWNLOADS,
)

internal val NavMenuMode.labelRes: Int
    get() = if (this == NavMenuMode.STATIC) R.string.settings_static else R.string.settings_dynamic

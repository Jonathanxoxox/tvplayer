package tv.own.owntv.features.settings

import androidx.compose.foundation.focusGroup
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.res.stringResource
import tv.own.owntv.R
import tv.own.owntv.core.nav.MainSection
import tv.own.owntv.core.settings.SettingsRepository.NavMenuMode
import tv.own.owntv.ui.components.OwnTVIcon
import tv.own.owntv.ui.components.trapAllFocusExit
import tv.own.owntv.ui.stage.StagePopup
import tv.own.owntv.ui.stage.StagePopupOption
import tv.own.owntv.ui.stage.StageSwitch
import tv.own.owntv.ui.theme.StageColors
import tv.own.owntv.ui.theme.mpx

/**
 * Settings › Bedienung › Menüeinträge (6 Nov 2026): what the menu on the left lists, in the page's panel.
 * Automatisch (the sections that have something in them) or Eigene Auswahl (each section ticked by hand),
 * then Apps, the grid of every app, as one more tick. Search and More are always there.
 */
@Composable
fun NavigationSettingsPopup(
    menuMode: NavMenuMode,
    onMenuMode: (NavMenuMode) -> Unit,
    hiddenSections: Set<MainSection>,
    onSectionHidden: (MainSection, Boolean) -> Unit,
    appsInMenu: Boolean,
    onAppsInMenu: (Boolean) -> Unit,
    onDismiss: () -> Unit,
) {
    val content: @Composable () -> Unit = {
        val first = remember { FocusRequester() }
        LaunchedEffect(Unit) { kotlinx.coroutines.delay(60); runCatching { first.requestFocus() } }
        val static = menuMode == NavMenuMode.STATIC
        Column(Modifier.trapAllFocusExit().focusGroup()) {
            StagePopupOption(
                title = stringResource(R.string.settings_ca_menu_auto),
                onClick = { onMenuMode(NavMenuMode.DYNAMIC) },
                modifier = Modifier.focusRequester(first),
                leading = { StageRadio(!static) },
            )
            StagePopupOption(
                title = stringResource(R.string.settings_ca_menu_own),
                onClick = { onMenuMode(NavMenuMode.STATIC) },
                leading = { StageRadio(static) },
            )
            // Own choice: the browse sections as ticks, in the menu's order.
            if (static) {
                MenuSections.forEach { (section, icon) ->
                    val shown = section !in hiddenSections
                    StagePopupOption(
                        title = stringResource(section.labelRes),
                        onClick = { onSectionHidden(section, shown) },
                        leading = { OwnTVIcon(icon, StageColors.Muted, Modifier.size(21.mpx)) },
                        trailing = { StageSwitch(shown) },
                    )
                }
            }
            StagePopupOption(
                title = stringResource(R.string.settings_ca_menu_apps),
                onClick = { onAppsInMenu(!appsInMenu) },
                leading = { OwnTVIcon(OwnTVIcon.GRID, StageColors.Muted, Modifier.size(21.mpx)) },
                trailing = { StageSwitch(appsInMenu) },
            )
            Row(Modifier.fillMaxWidth().padding(top = 16.mpx), horizontalArrangement = Arrangement.End) {
                tv.own.owntv.ui.stage.StageButton(stringResource(R.string.settings_done), onClick = onDismiss, height = 52.mpx, textSize = 18, tinted = true)
            }
        }
    }
    if (panelEditor(onDismiss) { content() }) return
    StagePopup(onDismiss = onDismiss, title = stringResource(R.string.settings_ca_menu_items)) { content() }
}

private val MenuSections = listOf(
    MainSection.HOME to OwnTVIcon.HOME,
    MainSection.LIVE_TV to OwnTVIcon.LIVE_TV,
    MainSection.SPORTS to OwnTVIcon.SPORTS,
    MainSection.MOVIES to OwnTVIcon.MOVIES,
    MainSection.SERIES to OwnTVIcon.SERIES,
    MainSection.DOWNLOADS to OwnTVIcon.DOWNLOADS,
)

/** The Menüeinträge row's value: Automatisch or Eigene Auswahl. */
internal val NavMenuMode.labelRes: Int
    get() = if (this == NavMenuMode.STATIC) R.string.settings_ca_menu_own else R.string.settings_ca_menu_auto

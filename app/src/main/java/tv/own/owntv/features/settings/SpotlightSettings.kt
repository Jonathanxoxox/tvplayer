package tv.own.owntv.features.settings

import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import tv.own.owntv.R
import tv.own.owntv.features.home.SpotlightSource
import tv.own.owntv.launcher.apps.LauncherPrefs
import tv.own.owntv.launcher.apps.rememberLauncherConfig
import tv.own.owntv.ui.components.MoveOrderOverlay
import tv.own.owntv.ui.components.OwnTVIcon

/**
 * Settings › Home › Spotlight: where the Spotlight's highlights come from, each switched on or off with OK,
 * in an order the user sets by holding OK on one (Jonathan, 5 Nov 2026).
 */
@Composable
internal fun SpotlightSourceRows() {
    val context = LocalContext.current
    val config = rememberLauncherConfig()
    val order = SpotlightSource.ordered(config.spotlightOrder, off = emptySet())
    // The order while its Move window is open, with the carried source's place.
    var moving by remember { mutableStateOf<Pair<List<SpotlightSource>, Int>?>(null) }
    val holdHint = stringResource(R.string.home_spotlight_hold_hint)
    StageSettingsHeading(stringResource(R.string.home_spotlight_heading), order.size)
    order.forEachIndexed { index, source ->
        val on = source.key !in config.spotlightOff
        val title = stringResource(source.title())
        val desc = stringResource(source.description())
        StageSettingRow(
            icon = source.icon(),
            title = title,
            desc = desc,
            value = SettingValue.Switch(on),
            onClick = {
                LauncherPrefs.update(context) { c ->
                    c.copy(spotlightOff = if (on) c.spotlightOff + source.key else c.spotlightOff - source.key)
                }
            },
            onLongClick = { moving = order to index },
            help = SettingHelp(title, desc + "\n" + holdHint, hints = settingHints(null, pinnable = false)),
        )
    }
    moving?.let { (list, index) ->
        val names = list.map { stringResource(it.title()) }
        MoveOrderOverlay(
            title = stringResource(R.string.home_spotlight_order),
            itemNames = names,
            activeIndex = index,
            openedByHold = true,
            onMove = { from, to ->
                val now = moving?.first
                if (now != null && from in now.indices && to in now.indices && from != to) {
                    moving = now.toMutableList().apply { add(to, removeAt(from)) } to to
                }
            },
            onCommit = {
                moving?.first?.let { done -> LauncherPrefs.update(context) { c -> c.copy(spotlightOrder = done.map { it.key }) } }
                moving = null
            },
            onCancel = { moving = null },
        )
    }
}

private fun SpotlightSource.title(): Int = when (this) {
    SpotlightSource.SPORT -> R.string.home_spotlight_sport
    SpotlightSource.TIPS -> R.string.home_spotlight_tips
    SpotlightSource.DOCS -> R.string.home_spotlight_docs
    SpotlightSource.RESUME -> R.string.home_spotlight_resume
    SpotlightSource.HABIT -> R.string.home_spotlight_habit
    SpotlightSource.NOW -> R.string.home_spotlight_now
}

private fun SpotlightSource.description(): Int = when (this) {
    SpotlightSource.SPORT -> R.string.home_spotlight_sport_desc
    SpotlightSource.TIPS -> R.string.home_spotlight_tips_desc
    SpotlightSource.DOCS -> R.string.home_spotlight_docs_desc
    SpotlightSource.RESUME -> R.string.home_spotlight_resume_desc
    SpotlightSource.HABIT -> R.string.home_spotlight_habit_desc
    SpotlightSource.NOW -> R.string.home_spotlight_now_desc
}

private fun SpotlightSource.icon(): OwnTVIcon = when (this) {
    SpotlightSource.SPORT -> OwnTVIcon.LIVE_DOT
    SpotlightSource.TIPS -> OwnTVIcon.STAR
    SpotlightSource.DOCS -> OwnTVIcon.VIDEO
    SpotlightSource.RESUME -> OwnTVIcon.PLAY
    SpotlightSource.HABIT -> OwnTVIcon.HISTORY
    SpotlightSource.NOW -> OwnTVIcon.LIVE_TV
}

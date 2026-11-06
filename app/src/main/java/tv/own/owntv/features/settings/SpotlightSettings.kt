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
import tv.own.owntv.launcher.apps.LauncherConfig
import tv.own.owntv.launcher.apps.LauncherPrefs
import tv.own.owntv.launcher.apps.rememberLauncherConfig
import tv.own.owntv.ui.components.MoveOrderOverlay
import tv.own.owntv.ui.components.OwnTVIcon

/**
 * Settings › Aussehen & Startseite › Spotlight (a page of its own since 6 Nov 2026, shown while the layout is
 * Spotlight). Display: how many highlights, whether they move on by themselves, films' ratings, series left
 * out. Sources: where the highlights come from, each switched on or off with OK, in an order the user sets
 * by holding OK on one (Jonathan, 5 and 6 Nov 2026).
 */
@Composable
internal fun SpotlightSourcesScreen(onBack: () -> Unit, modifier: androidx.compose.ui.Modifier = androidx.compose.ui.Modifier) {
    StageFullPage(
        parents = listOf(stringResource(R.string.settings_group_appearance_home)),
        title = stringResource(R.string.settings_ca_spotlight_sources),
        count = "",
        onBack = onBack,
        modifier = modifier,
    ) {
        SpotlightDisplayRows()
        SpotlightSourceRows()
    }
}

/** The Spotlight row's value: how many of the sources are on, "5 von 7 an". */
@Composable
internal fun spotlightSourcesValue(): String {
    val config = rememberLauncherConfig()
    val all = SpotlightSource.ordered(config.spotlightOrder, off = emptySet())
    val on = all.count { it.key !in config.spotlightOff }
    return stringResource(R.string.launcher_app_channels_count, on, all.size)
}

/** How many, how fast, ratings, series: the Spotlight's own settings. */
@Composable
private fun SpotlightDisplayRows() {
    val context = LocalContext.current
    val config = rememberLauncherConfig()
    val change = stringResource(R.string.settings_key_change)
    val back = stringResource(R.string.common_back) to stringResource(R.string.settings_group_appearance_home)
    val stepHints = listOf("◀ ▶" to change, back)
    val okHints = listOf(stringResource(R.string.common_ok) to stringResource(R.string.settings_key_switch), back)
    StageSettingsHeading(stringResource(R.string.home_spotlight_display), 5, first = true)

    // How many highlights: 4 … 12, ◀ ▶ in place, OK goes round.
    val maxSteps = LauncherConfig.SPOTLIGHT_MAX_STEPS
    val maxIndex = maxSteps.indexOf(config.spotlightMax).takeIf { it >= 0 } ?: maxSteps.indexOf(LauncherConfig.SPOTLIGHT_MAX_DEFAULT)
    val setMax = { i: Int -> LauncherPrefs.update(context) { it.copy(spotlightMax = maxSteps[i]) } }
    val maxTitle = stringResource(R.string.home_spotlight_count)
    val maxDesc = stringResource(R.string.home_spotlight_count_desc)
    StageSettingRow(
        icon = OwnTVIcon.LIST,
        title = maxTitle,
        desc = maxDesc,
        value = SettingValue.Stepper(stringResource(R.string.home_spotlight_count_value, maxSteps[maxIndex])),
        onClick = { setMax((maxIndex + 1) % maxSteps.size) },
        onStep = { d -> setMax((maxIndex + d).coerceIn(0, maxSteps.lastIndex)) },
        help = SettingHelp(maxTitle, maxDesc, hints = stepHints),
    )

    // The automatic advance: off, or every 8 … 30 s.
    val autoSteps = LauncherConfig.SPOTLIGHT_AUTO_STEPS
    val autoIndex = autoSteps.indexOf(config.spotlightAutoSecs).coerceAtLeast(0)
    val setAuto = { i: Int -> LauncherPrefs.update(context) { it.copy(spotlightAutoSecs = autoSteps[i]) } }
    val autoTitle = stringResource(R.string.home_spotlight_auto)
    val autoDesc = stringResource(R.string.home_spotlight_auto_desc)
    val autoSecs = autoSteps[autoIndex]
    StageSettingRow(
        icon = OwnTVIcon.CLOCK,
        title = autoTitle,
        desc = autoDesc,
        value = SettingValue.Stepper(
            if (autoSecs == 0) stringResource(R.string.home_spotlight_auto_off) else stringResource(R.string.home_spotlight_auto_value, autoSecs),
        ),
        onClick = { setAuto((autoIndex + 1) % autoSteps.size) },
        onStep = { d -> setAuto((autoIndex + d).coerceIn(0, autoSteps.lastIndex)) },
        help = SettingHelp(autoTitle, autoDesc, hints = stepHints),
    )

    val ratingsTitle = stringResource(R.string.home_spotlight_ratings)
    val ratingsDesc = stringResource(R.string.home_spotlight_ratings_desc)
    StageSettingRow(
        icon = OwnTVIcon.STAR,
        title = ratingsTitle,
        desc = ratingsDesc,
        value = SettingValue.Switch(config.spotlightRatings),
        onClick = { LauncherPrefs.update(context) { it.copy(spotlightRatings = !it.spotlightRatings) } },
        help = SettingHelp(ratingsTitle, ratingsDesc, hints = okHints),
    )

    val badgesTitle = stringResource(R.string.home_spotlight_badges)
    val badgesDesc = stringResource(R.string.home_spotlight_badges_desc)
    StageSettingRow(
        icon = OwnTVIcon.SPARKLE,
        title = badgesTitle,
        desc = badgesDesc,
        value = SettingValue.Switch(config.spotlightBadges),
        onClick = { LauncherPrefs.update(context) { it.copy(spotlightBadges = !it.spotlightBadges) } },
        help = SettingHelp(badgesTitle, badgesDesc, hints = okHints),
    )

    val seriesTitle = stringResource(R.string.home_spotlight_hide_series)
    val seriesDesc = stringResource(R.string.home_spotlight_hide_series_desc)
    StageSettingRow(
        icon = OwnTVIcon.SERIES,
        title = seriesTitle,
        desc = seriesDesc,
        value = SettingValue.Switch(config.spotlightHideSeries),
        onClick = { LauncherPrefs.update(context) { it.copy(spotlightHideSeries = !it.spotlightHideSeries) } },
        help = SettingHelp(seriesTitle, seriesDesc, hints = okHints),
    )
}

@Composable
private fun SpotlightSourceRows() {
    val context = LocalContext.current
    val config = rememberLauncherConfig()
    val order = SpotlightSource.ordered(config.spotlightOrder, off = emptySet())
    // The order while its Move window is open, with the carried source's place.
    var moving by remember { mutableStateOf<Pair<List<SpotlightSource>, Int>?>(null) }
    val holdHint = stringResource(R.string.home_spotlight_hold_hint)
    StageSettingsHeading(stringResource(R.string.home_spotlight_sources), order.size)
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
            help = SettingHelp(
                title, desc + "\n" + holdHint,
                hints = listOf(
                    stringResource(R.string.common_ok) to stringResource(R.string.settings_key_switch),
                    stringResource(R.string.content_key_hold_ok) to stringResource(R.string.content_move),
                    stringResource(R.string.common_back) to stringResource(R.string.settings_group_appearance_home),
                ),
            ),
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
    SpotlightSource.FILMS -> R.string.home_spotlight_films
    SpotlightSource.TIPS -> R.string.home_spotlight_tips
    SpotlightSource.DOCS -> R.string.home_spotlight_docs
    SpotlightSource.RESUME -> R.string.home_spotlight_resume
    SpotlightSource.HABIT -> R.string.home_spotlight_habit
    SpotlightSource.NOW -> R.string.home_spotlight_now
}

private fun SpotlightSource.description(): Int = when (this) {
    SpotlightSource.SPORT -> R.string.home_spotlight_sport_desc
    SpotlightSource.FILMS -> R.string.home_spotlight_films_desc
    SpotlightSource.TIPS -> R.string.home_spotlight_tips_desc
    SpotlightSource.DOCS -> R.string.home_spotlight_docs_desc
    SpotlightSource.RESUME -> R.string.home_spotlight_resume_desc
    SpotlightSource.HABIT -> R.string.home_spotlight_habit_desc
    SpotlightSource.NOW -> R.string.home_spotlight_now_desc
}

private fun SpotlightSource.icon(): OwnTVIcon = when (this) {
    SpotlightSource.SPORT -> OwnTVIcon.LIVE_DOT
    SpotlightSource.FILMS -> OwnTVIcon.MOVIES
    SpotlightSource.TIPS -> OwnTVIcon.STAR
    SpotlightSource.DOCS -> OwnTVIcon.VIDEO
    SpotlightSource.RESUME -> OwnTVIcon.PLAY
    SpotlightSource.HABIT -> OwnTVIcon.HISTORY
    SpotlightSource.NOW -> OwnTVIcon.LIVE_TV
}

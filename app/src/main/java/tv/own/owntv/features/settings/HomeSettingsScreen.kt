package tv.own.owntv.features.settings

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusProperties
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.tv.material3.Text
import org.koin.androidx.compose.koinViewModel
import tv.own.owntv.R
import tv.own.owntv.core.model.HeroKind
import tv.own.owntv.core.model.HomeRow
import tv.own.owntv.core.model.HomeTrendingStyle
import tv.own.owntv.core.trending.TrendingAvailability
import tv.own.owntv.features.home.HomeCatalog
import tv.own.owntv.features.home.displayLabel
import tv.own.owntv.features.home.displayTitle
import tv.own.owntv.features.home.settingsDescription
import tv.own.owntv.ui.components.OwnTVIcon
import tv.own.owntv.ui.theme.StageColors
import tv.own.owntv.ui.theme.stageAccent
import tv.own.owntv.ui.theme.stageText

/**
 * Settings › Aussehen & Startseite › Reihen der Startseite (6 Nov 2026): Now trending and its layout, then
 * Home's rows (shown or hidden, in Home's order). ▶ on a row reaches its own options in the panel: Hide /
 * Move for every row, Keep watching's Live / Movies / Series / video preview, the channel row's category.
 * Moving opens the one arrange window, Home's rows and the other apps' rows together (Hold OK = Move).
 * Then the Apps row and the other apps' channels.
 */
@Composable
fun HomeSettingsScreen(onBack: () -> Unit, modifier: Modifier = Modifier) {
    val vm: HomeSettingsViewModel = koinViewModel()
    val config by vm.config.collectAsStateWithLifecycle()
    val trendingAvailability by vm.trendingAvailability.collectAsStateWithLifecycle()
    val heroPreviewEnabled by vm.heroPreviewEnabled.collectAsStateWithLifecycle()
    val trendingEnabled = HomeRow.TRENDING !in config.hidden
    val trendingStatus = trendingStatusText(hidden = !trendingEnabled, availability = trendingAvailability)

    val rowsFocus = remember { FocusRequester() }
    val actionsFocus = remember { FocusRequester() }
    val rowFocus = remember { HomeRow.entries.associateWith { FocusRequester() } }
    // The arrange window, opened on a row; true when a held OK opened it (its release must not commit).
    var arranging by remember { mutableStateOf<Pair<HomeRow, Boolean>?>(null) }
    // The row whose actions the panel shows while focus is in them.
    var acting by remember { mutableStateOf<HomeRow?>(null) }
    var pickingCatalog by remember { mutableStateOf(false) }
    val panel = LocalSettingsPanel.current
    LaunchedEffect(Unit) { kotlinx.coroutines.delay(60); runCatching { rowsFocus.requestFocus() } }

    val ok = stringResource(R.string.common_ok)
    val back = stringResource(R.string.common_back)
    val layout = stringResource(R.string.settings_group_appearance_home)
    val rowCount = (if (trendingEnabled) 1 else 0) + config.settingsRows.count { it !in config.hidden }
    val catalogChoice by vm.catalogChoice.collectAsStateWithLifecycle()
    val recentTitle = HomeRow.RECENT_CHANNELS.displayTitle()
    StageFullPage(
        parents = listOf(layout),
        title = stringResource(R.string.settings_ca_home_rows),
        count = pluralStringResource(R.plurals.settings_home_row_count, rowCount, rowCount),
        onBack = onBack,
        modifier = modifier,
        rowsFocus = rowsFocus,
    ) {
        StageSettingsHeading(stringResource(R.string.home_row_now_trending), if (trendingEnabled) 2 else 1, first = true)
        val trendingTitle = stringResource(R.string.home_row_now_trending)
        val trendingValue = SettingValue.Switch(trendingEnabled)
        StageSettingRow(
            icon = OwnTVIcon.STAR,
            title = trendingTitle,
            desc = trendingStatus,
            value = trendingValue,
            onClick = { vm.setRowHidden(HomeRow.TRENDING, trendingEnabled) },
            help = settingHelp(null, trendingTitle, HomeRow.TRENDING.settingsDescription(), trendingValue, pinnable = false),
        )
        // Only while the row is on: with Trending off there is nothing for the choice to apply to.
        if (trendingEnabled) {
            val styleTitle = stringResource(R.string.home_trending_style)
            val styleValue = SettingValue.Segmented(
                listOf(stringResource(R.string.home_trending_style_full_bleed), stringResource(R.string.home_trending_style_posters)),
                if (config.trendingStyle == HomeTrendingStyle.HERO) 0 else 1,
            )
            val flip = {
                vm.setTrendingStyle(if (config.trendingStyle == HomeTrendingStyle.HERO) HomeTrendingStyle.POSTERS else HomeTrendingStyle.HERO)
            }
            StageSettingRow(
                icon = OwnTVIcon.MOVIES,
                title = styleTitle,
                desc = stringResource(R.string.settings_line_trending_layout),
                value = styleValue,
                onClick = flip,
                onStep = { step -> vm.setTrendingStyle(if (step < 0) HomeTrendingStyle.HERO else HomeTrendingStyle.POSTERS) },
                help = settingHelp(null, styleTitle, stringResource(R.string.home_row_trending_description), styleValue, choices = styleValue.options, pinnable = false),
            )
        }

        StageSettingsHeading(stringResource(R.string.settings_ca_rows_heading), config.settingsRows.size)
        val hiddenNote = stringResource(R.string.settings_hidden_sections)
        val on = stringResource(R.string.common_on)
        val off = stringResource(R.string.common_off)
        val state: @Composable (Int, Boolean) -> String = { label, value -> stringResource(R.string.settings_ca_action_value, stringResource(label), if (value) on else off) }
        config.settingsRows.forEach { row ->
            key(row) {
                val hidden = row in config.hidden
                val liveMode = when (row) {
                    HomeRow.RECENT_CHANNELS -> config.recentLiveMode
                    HomeRow.FAVORITE_CHANNELS -> config.favoriteLiveMode
                    else -> null
                }
                val focus = rowFocus.getValue(row)
                val actions = buildList {
                    add(
                        StageAction(
                            if (hidden) OwnTVIcon.PLAY_CIRCLE else OwnTVIcon.EYE_OFF,
                            stringResource(if (hidden) R.string.settings_home_show_row else R.string.settings_home_hide_row),
                            { vm.setRowHidden(row, !hidden) },
                        ),
                    )
                    add(StageAction(OwnTVIcon.MOVE, stringResource(R.string.settings_home_move_row), { acting = null; arranging = row to false }))
                    liveMode?.let { mode -> add(StageAction(OwnTVIcon.GRID, mode.displayLabel(), { vm.setLiveRowMode(row, mode.toggled()) })) }
                    // Keep watching's own options (6 Nov 2026: were a section of their own under the rows).
                    if (row == HomeRow.HERO) {
                        add(StageAction(OwnTVIcon.LIVE_TV, state(R.string.settings_ca_keep_live, config.heroIncludeLive), { vm.setHeroInclude(HeroKind.LIVE, !config.heroIncludeLive) }))
                        add(StageAction(OwnTVIcon.MOVIES, state(R.string.settings_ca_keep_movies, config.heroIncludeMovies), { vm.setHeroInclude(HeroKind.MOVIES, !config.heroIncludeMovies) }))
                        add(StageAction(OwnTVIcon.SERIES, state(R.string.settings_ca_keep_series, config.heroIncludeSeries), { vm.setHeroInclude(HeroKind.SERIES, !config.heroIncludeSeries) }))
                        add(StageAction(OwnTVIcon.PLAY, state(R.string.settings_ca_keep_video, heroPreviewEnabled), { vm.setHeroPreviewEnabled(!heroPreviewEnabled) }))
                    }
                    // The channel row's category (6 Nov 2026: was the Senderreihe section).
                    if (row == HomeRow.RECENT_CHANNELS) {
                        add(
                            StageAction(
                                OwnTVIcon.LIVE_TV,
                                stringResource(R.string.settings_ca_action_value, stringResource(R.string.home_catalog_title), catalogChoice.current?.name ?: recentTitle),
                                { panel?.opener = focus; acting = null; pickingCatalog = true },
                            ),
                        )
                        if (catalogChoice.hidden > 0) add(StageAction(OwnTVIcon.EYE_OFF, stringResource(R.string.home_catalog_show_hidden), vm::showHiddenOnHome))
                    }
                }
                // The channel row is named after the category it shows (Magenta TV), not "Recently watched".
                val title = if (row == HomeRow.RECENT_CHANNELS) catalogChoice.current?.name ?: row.displayTitle() else row.displayTitle()
                val hiddenWord = stringResource(R.string.settings_hidden)
                StageSettingRow(
                    icon = row.icon,
                    title = title,
                    desc = if (hidden) listOfNotNull(hiddenWord, liveMode?.displayLabel()).joinToString(dotSeparator())
                        else row.settingsDescription(),
                    value = SettingValue.Custom {
                        Text(
                            if (hidden) hiddenWord else stringResource(R.string.settings_home_shown),
                            style = stageText(18, 700),
                            color = if (hidden) StageColors.Dim else stageAccent.accent,
                            maxLines = 1, overflow = TextOverflow.Ellipsis,
                        )
                    },
                    onClick = { acting = row; runCatching { actionsFocus.requestFocus() } },
                    onLongClick = { acting = null; arranging = row to true },
                    keepPanel = acting == row,
                    help = SettingHelp(
                        title = title,
                        text = row.settingsDescription() + "\n" + hiddenNote,
                        hints = listOf(
                            "▶" to stringResource(R.string.settings_key_actions),
                            stringResource(R.string.content_key_hold_ok) to stringResource(R.string.content_move),
                            back to layout,
                        ),
                        extra = { StageActionColumn(actions, focus, actionsFocus) },
                    ),
                    modifier = Modifier
                        .focusRequester(focus)
                        .focusProperties { right = actionsFocus }
                        .onFocusChanged { if (it.isFocused) acting = null },
                )
            }
        }

        // The Apps row and the other apps' channels, moved here from the start-screen settings.
        LauncherSettingsRows(part = LauncherRowsPart.HOME_APPS)

        // In the page's panel, so drawn inside the page.
        if (pickingCatalog) {
            val catalogOptions = listOf(HomeCatalog.RECENT to recentTitle) + catalogChoice.categories.map { it.key to it.name }
            PickerDialog(
                title = stringResource(R.string.home_catalog_title),
                options = catalogOptions,
                selected = catalogChoice.current?.key ?: HomeCatalog.RECENT,
                onSelect = { key -> vm.chooseCatalog(key); pickingCatalog = false },
                onDismiss = { pickingCatalog = false },
                searchable = catalogOptions.size > 12,
            )
        }
        // Home's rows together with the other apps' rows (YouTube, Nuvio …), in one window (Jonathan, 4 Nov 2026):
        // the only way to move a row since 6 Nov 2026.
        arranging?.let { (row, byHold) ->
            tv.own.owntv.features.home.ArrangeRowsWindow(
                rows = config.settingsRows,
                catalogName = catalogChoice.current?.name,
                startAt = tv.own.owntv.features.home.HomeSlot.Row(row),
                onSaveRows = vm::setRowOrder,
                onClose = { arranging = null; runCatching { rowFocus.getValue(row).requestFocus() } },
                openedByHold = byHold,
            )
        }
    }
}

/**
 * Android TV's own start screen: Mayo TV's rows there, and rebuilding them now. Under Settings › Power-on &
 * Home button since Settings were sorted anew (Jonathan, 4 Nov 2026).
 */
@Composable
internal fun AndroidTvHomeRows() {
    val settingsVm: SettingsViewModel = koinViewModel()
    val androidTvHomeEnabled by settingsVm.androidTvHomeEnabled.collectAsStateWithLifecycle()
    val tvHomeRefresh by settingsVm.tvHomeRefresh.collectAsStateWithLifecycle()
    StageSettingsHeading(stringResource(R.string.settings_ca_heading_android_tv), if (androidTvHomeEnabled) 2 else 1)
    val cardsTitle = stringResource(R.string.settings_ca_tv_cards)
    val cardsValue = SettingValue.Switch(androidTvHomeEnabled)
    StageSettingRow(
        icon = OwnTVIcon.HISTORY,
        title = cardsTitle,
        desc = stringResource(R.string.settings_ca_line_tv_cards),
        value = cardsValue,
        onClick = { settingsVm.setAndroidTvHomeEnabled(!androidTvHomeEnabled) },
        help = settingHelp(null, cardsTitle, stringResource(R.string.settings_android_tv_home_description), cardsValue, pinnable = false),
    )
    if (androidTvHomeEnabled) {
        val refreshTitle = stringResource(R.string.settings_refresh_now)
        val refreshValue = when (tvHomeRefresh) {
            SettingsViewModel.TvHomeRefresh.REFRESHING -> SettingValue.Action(stringResource(R.string.settings_rebuilding))
            SettingsViewModel.TvHomeRefresh.DONE -> SettingValue.Action(stringResource(R.string.settings_done_check))
            else -> null
        }
        val refreshDesc = stringResource(R.string.settings_refresh_description)
        StageSettingRow(
            icon = OwnTVIcon.REFRESH,
            title = refreshTitle,
            desc = stringResource(R.string.settings_ca_line_refresh),
            value = refreshValue,
            onClick = { if (tvHomeRefresh == SettingsViewModel.TvHomeRefresh.IDLE) settingsVm.refreshAndroidTvHome() },
            help = SettingHelp(refreshTitle, refreshDesc, hints = settingHints(null, pinnable = false)),
        )
    }
}

private val HomeRow.icon: OwnTVIcon get() = when (this) {
    HomeRow.TRENDING -> OwnTVIcon.STAR
    HomeRow.HERO -> OwnTVIcon.PLAY_CIRCLE
    HomeRow.LIVE_SPORTS -> OwnTVIcon.SPORTS
    HomeRow.RECENT_CHANNELS -> OwnTVIcon.LIVE_TV
    HomeRow.FAVORITE_CHANNELS -> OwnTVIcon.FAVORITE
    HomeRow.FAVORITE_MOVIES -> OwnTVIcon.MOVIES
    HomeRow.FAVORITE_SERIES -> OwnTVIcon.SERIES
    HomeRow.CONTINUE_MOVIES -> OwnTVIcon.MOVIES
    HomeRow.CONTINUE_SERIES -> OwnTVIcon.SERIES
}

@Composable
private fun trendingStatusText(hidden: Boolean, availability: TrendingAvailability): String = when {
    hidden -> stringResource(R.string.settings_trending_status_off)
    availability == TrendingAvailability.Building -> stringResource(R.string.settings_trending_status_building)
    availability == TrendingAvailability.MetadataDisabled -> stringResource(R.string.settings_trending_status_metadata_disabled)
    availability == TrendingAvailability.NoVodScope -> stringResource(R.string.settings_trending_status_no_vod)
    availability == TrendingAvailability.Failed -> stringResource(R.string.settings_trending_status_failed)
    availability == TrendingAvailability.WaitingForSync -> stringResource(R.string.settings_trending_status_waiting)
    availability is TrendingAvailability.BelowThreshold && availability.matched == 0 -> stringResource(
        R.string.settings_trending_status_no_matches,
    )
    availability is TrendingAvailability.BelowThreshold -> pluralStringResource(
        R.plurals.settings_trending_status_below_threshold,
        availability.matched,
        availability.matched,
    )
    availability is TrendingAvailability.Showing && availability.refreshFailed -> pluralStringResource(
        R.plurals.settings_trending_status_showing_refresh_failed,
        availability.count,
        availability.count,
    )
    availability is TrendingAvailability.Showing -> pluralStringResource(
        R.plurals.settings_trending_status_showing,
        availability.count,
        availability.count,
    )
    else -> stringResource(R.string.settings_trending_status_waiting)
}

package tv.own.owntv.features.onboarding

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.tv.material3.Text
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.launch
import org.koin.compose.koinInject
import tv.own.owntv.R
import tv.own.owntv.core.settings.SettingsRepository
import tv.own.owntv.core.theme.AccentColor
import tv.own.owntv.features.onboarding.appearance.AccentChoice
import tv.own.owntv.features.onboarding.appearance.AccentSwatch
import tv.own.owntv.features.onboarding.appearance.HomeMiniature
import tv.own.owntv.features.onboarding.appearance.LayoutCard
import tv.own.owntv.features.onboarding.appearance.MayoAccent
import tv.own.owntv.features.onboarding.appearance.MiniHome
import tv.own.owntv.features.onboarding.appearance.writeAccent
import tv.own.owntv.features.settings.SettingValue
import tv.own.owntv.features.settings.StageSettingRow
import tv.own.owntv.launcher.apps.LauncherApps
import tv.own.owntv.launcher.apps.LauncherConfig
import tv.own.owntv.launcher.apps.LauncherPrefs
import tv.own.owntv.launcher.apps.rememberLauncherConfig
import tv.own.owntv.ui.components.OwnTVIcon
import tv.own.owntv.ui.theme.LocalStagePalette
import tv.own.owntv.ui.theme.StageColors
import tv.own.owntv.ui.theme.labelRes
import tv.own.owntv.ui.theme.mpx
import tv.own.owntv.ui.theme.primary
import tv.own.owntv.ui.theme.stageAccent
import tv.own.owntv.ui.theme.stageText

/**
 * The first chapter after the profile: how Home is laid out and how Mayo TV looks, on two short pages.
 *
 * 1. Layout: the four Home layouts side by side, each a drawn miniature of Home. OK chooses one and
 *    moves focus to Continue.
 * 2. Colour and Home: the accent colour (the whole page re-tints as soon as one is chosen), then the
 *    switches that change Home the most (the apps, other apps' channels, row titles, and with the
 *    Spotlight layout its automatic advance), beside a miniature of the chosen Home that follows them.
 *
 * Every choice is stored the moment it is made, through the same stores Settings uses
 * ([LauncherPrefs], [SettingsRepository]), so the chapter can be left at any point and Settings shows
 * the same values. What is preselected is what the install already has, so Continue straight through
 * keeps the shipped look. Back on page 2 returns to page 1; on page 1 it goes to [onBack], which is
 * null here (the profile stays), leaving Back to the system.
 */
@Composable
fun AppearanceChapter(onNext: () -> Unit, onBack: (() -> Unit)?) {
    val settings = koinInject<SettingsRepository>()
    val appScope = koinInject<CoroutineScope>()
    val config = rememberLauncherConfig()
    var page by rememberSaveable { mutableIntStateOf(PAGE_LAYOUT) }
    // The accent chosen on this visit. Stored at once (the page re-tints), and once more on the way out
    // so the install's one-time look defaults, applied as the profile appears, cannot win over it.
    var picked by remember { mutableStateOf<AccentChoice?>(null) }

    when (page) {
        PAGE_LAYOUT -> LayoutPage(
            config = config,
            onContinue = { page = PAGE_LOOK },
            onBack = onBack,
        )
        else -> LookPage(
            config = config,
            settings = settings,
            onPick = { choice ->
                picked = choice
                appScope.launch { runCatching { writeAccent(settings, choice) } }
            },
            onContinue = {
                picked?.let { choice -> appScope.launch { runCatching { writeAccent(settings, choice) } } }
                onNext()
            },
            onBack = { page = PAGE_LAYOUT },
        )
    }
}

/** Page 1: the four Home layouts as miniatures; the one in use is preselected and takes focus. */
@Composable
private fun LayoutPage(config: LauncherConfig, onContinue: () -> Unit, onBack: (() -> Unit)?) {
    val context = LocalContext.current
    val first = rememberChapterFocus()
    val continueFocus = remember { FocusRequester() }
    val chosen = Layouts.firstOrNull { it.key == config.homeLayout } ?: Layouts.first()
    ChapterFrame(
        chapter = OnboardingChapter.APPEARANCE,
        title = stringResource(R.string.onboarding_appearance_title),
        subtitle = stringResource(R.string.onboarding_appearance_layout_sub, stringResource(R.string.settings_group_appearance_home)),
        primary = ChapterAction(stringResource(R.string.onboarding_action_continue), onContinue, continueFocus),
        secondary = onBack?.let { ChapterAction(stringResource(R.string.common_back), it) },
        width = CHAPTER_WIDTH.mpx,
    ) {
        Row(
            Modifier.fillMaxWidth().height(IntrinsicSize.Min).padding(vertical = 8.mpx),
            horizontalArrangement = Arrangement.spacedBy(16.mpx),
        ) {
            Layouts.forEach { layout ->
                LayoutCard(
                    title = stringResource(layout.titleRes),
                    line = stringResource(layout.lineRes),
                    home = MiniHome(layout.key, config.appsRow, config.appChannels, config.rowTitles),
                    selected = layout == chosen,
                    onClick = {
                        LauncherPrefs.update(context) { it.copy(homeLayout = layout.key) }
                        runCatching { continueFocus.requestFocus() }
                    },
                    modifier = Modifier
                        .weight(1f)
                        .fillMaxHeight()
                        .then(if (layout == chosen) Modifier.focusRequester(first) else Modifier),
                )
            }
        }
    }
}

/**
 * Page 2: the accent colour, the Home switches that matter most, and a miniature of the chosen Home
 * that follows both.
 */
@Composable
private fun LookPage(
    config: LauncherConfig,
    settings: SettingsRepository,
    onPick: (AccentChoice) -> Unit,
    onContinue: () -> Unit,
    onBack: () -> Unit,
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val first = rememberChapterFocus()
    val stored by remember(settings) {
        combine(settings.accent, settings.customAccent) { preset, custom -> AccentChoice.of(preset, custom) }
    }.collectAsStateWithLifecycle<AccentChoice?>(initialValue = null)
    // The colour under focus, named beside the heading while focus is on the swatches.
    var focusedChoice by remember { mutableStateOf<AccentChoice?>(null) }

    // Other apps' channels need the TV's permission to read them; asked for when switched on, as in Settings.
    var resumes by remember { mutableIntStateOf(0) }
    val canReadChannels = remember(resumes) { LauncherApps.canReadChannels(context) }
    val askChannels = rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) {
        scope.launch { runCatching { LauncherApps.refreshChannels(context) } }
        resumes++
    }

    val isDark = !LocalStagePalette.current.isLight
    val own = stored as? AccentChoice.Own
    val choices: List<AccentChoice> = listOf<AccentChoice>(AccentChoice.Mayo) +
        AccentColor.entries.map { AccentChoice.Preset(it) } +
        listOfNotNull(own)
    val mayoName = stringResource(R.string.onboarding_appearance_accent_mayo)
    val yoursName = stringResource(R.string.settings_accent_yours)
    val presetNames = AccentColor.entries.associateWith { stringResource(it.labelRes) }
    fun nameOf(choice: AccentChoice): String = when (choice) {
        AccentChoice.Mayo -> mayoName
        is AccentChoice.Preset -> presetNames.getValue(choice.accent)
        is AccentChoice.Own -> yoursName
    }
    fun colorOf(choice: AccentChoice) = when (choice) {
        AccentChoice.Mayo -> MayoAccent
        is AccentChoice.Preset -> choice.accent.primary(isDark)
        is AccentChoice.Own -> choice.color
    }

    val compact = config.homeLayout != LauncherConfig.LAYOUT_STANDARD
    val layout = Layouts.firstOrNull { it.key == config.homeLayout } ?: Layouts.first()
    fun update(change: (LauncherConfig) -> LauncherConfig) = LauncherPrefs.update(context, change)

    ChapterFrame(
        chapter = OnboardingChapter.APPEARANCE,
        title = stringResource(R.string.onboarding_appearance_look_title),
        subtitle = stringResource(R.string.onboarding_appearance_look_sub, stringResource(R.string.settings_group_appearance_home)),
        primary = ChapterAction(stringResource(R.string.onboarding_action_continue), onContinue),
        secondary = ChapterAction(stringResource(R.string.common_back), onBack),
        width = CHAPTER_WIDTH.mpx,
    ) {
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(36.mpx)) {
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(6.mpx)) {
                Row(
                    Modifier.padding(start = 4.mpx),
                    horizontalArrangement = Arrangement.spacedBy(12.mpx),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(stringResource(R.string.settings_accent), style = stageText(20, 800), color = StageColors.Text, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    (focusedChoice ?: stored)?.let { shown ->
                        Text(nameOf(shown), style = stageText(18, 700), color = stageAccent.accent, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    }
                }
                Row(
                    Modifier.padding(horizontal = 4.mpx, vertical = 14.mpx),
                    horizontalArrangement = Arrangement.spacedBy(18.mpx),
                ) {
                    choices.forEach { choice ->
                        val selected = choice == stored
                        AccentSwatch(
                            color = colorOf(choice),
                            selected = selected,
                            onClick = { onPick(choice) },
                            modifier = Modifier
                                .then(if (selected || (stored == null && choice == AccentChoice.Mayo)) Modifier.focusRequester(first) else Modifier)
                                .onFocusChanged { state ->
                                    if (state.isFocused) focusedChoice = choice
                                    else if (focusedChoice == choice) focusedChoice = null
                                },
                        )
                    }
                }

                StageSettingRow(
                    icon = OwnTVIcon.TILES,
                    title = stringResource(R.string.onboarding_appearance_apps_title),
                    desc = stringResource(
                        if (compact) R.string.onboarding_appearance_apps_line_compact else R.string.onboarding_appearance_apps_line_standard,
                    ),
                    value = SettingValue.Switch(config.appsRow),
                    onClick = { update { it.copy(appsRow = !it.appsRow) } },
                )
                StageSettingRow(
                    icon = OwnTVIcon.LAYERS,
                    title = stringResource(R.string.launcher_app_channels),
                    desc = stringResource(
                        if (config.appChannels && !canReadChannels) R.string.launcher_app_channels_allow_line else R.string.onboarding_appearance_channels_line,
                    ),
                    value = if (config.appChannels && !canReadChannels) SettingValue.Action(stringResource(R.string.launcher_app_channels_allow))
                    else SettingValue.Switch(config.appChannels),
                    onClick = {
                        when {
                            !canReadChannels -> {
                                update { it.copy(appChannels = true) }
                                runCatching { askChannels.launch(LauncherApps.PERMISSIONS) }
                            }
                            else -> update { it.copy(appChannels = !it.appChannels) }
                        }
                    },
                )
                StageSettingRow(
                    icon = OwnTVIcon.TEXT_SIZE,
                    title = stringResource(R.string.settings_ca_row_titles),
                    desc = stringResource(R.string.settings_ca_line_row_titles),
                    value = SettingValue.Switch(config.rowTitles),
                    onClick = { update { it.copy(rowTitles = !it.rowTitles) } },
                )
                if (config.homeLayout == LauncherConfig.LAYOUT_SPOTLIGHT) {
                    val autoOn = config.spotlightAutoSecs > 0
                    StageSettingRow(
                        icon = OwnTVIcon.STAR,
                        title = stringResource(R.string.home_spotlight_auto),
                        desc = pluralStringResource(R.plurals.onboarding_appearance_spotlight_auto_line, SPOTLIGHT_AUTO_SECS, SPOTLIGHT_AUTO_SECS),
                        value = SettingValue.Switch(autoOn),
                        onClick = { update { it.copy(spotlightAutoSecs = if (autoOn) 0 else SPOTLIGHT_AUTO_SECS) } },
                    )
                }
            }

            // The chosen Home as it will look; drawn only, nothing here takes focus.
            Column(Modifier.width(PREVIEW_WIDTH.mpx).padding(top = 4.mpx)) {
                Text(
                    stringResource(R.string.settings_ca_preview),
                    style = stageText(15, 800),
                    color = StageColors.Muted,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                HomeMiniature(
                    MiniHome(config.homeLayout, config.appsRow, config.appChannels, config.rowTitles),
                    Modifier.fillMaxWidth().padding(top = 12.mpx),
                )
                Text(
                    stringResource(layout.titleRes),
                    style = stageText(18, 700),
                    color = StageColors.Text,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.padding(top = 12.mpx),
                )
            }
        }
    }
}

/** A Home layout the chapter offers, in Settings' order, with its name and its line here. */
private class LayoutOption(val key: String, val titleRes: Int, val lineRes: Int)

private val Layouts = listOf(
    LayoutOption(LauncherConfig.LAYOUT_STANDARD, R.string.home_layout_standard, R.string.onboarding_appearance_layout_standard_line),
    LayoutOption(LauncherConfig.LAYOUT_CLASSIC, R.string.home_layout_classic, R.string.onboarding_appearance_layout_classic_line),
    LayoutOption(LauncherConfig.LAYOUT_STAGE, R.string.home_layout_stage, R.string.onboarding_appearance_layout_stage_line),
    LayoutOption(LauncherConfig.LAYOUT_SPOTLIGHT, R.string.home_layout_spotlight, R.string.onboarding_appearance_layout_spotlight_line),
)

private const val PAGE_LAYOUT = 0
private const val PAGE_LOOK = 1

/** Wider than the default frame, so four miniatures fit side by side at a size that reads from the sofa. */
private const val CHAPTER_WIDTH = 1240
private const val PREVIEW_WIDTH = 420

/** The Spotlight's advance when switched on here: one of [LauncherConfig.SPOTLIGHT_AUTO_STEPS]. */
private const val SPOTLIGHT_AUTO_SECS = 12

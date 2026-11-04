package tv.own.owntv.features.settings

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import kotlinx.coroutines.launch
import tv.own.owntv.R
import tv.own.owntv.launcher.apps.LauncherApps
import tv.own.owntv.launcher.apps.LauncherConfig
import tv.own.owntv.launcher.apps.LauncherPrefs
import tv.own.owntv.launcher.apps.LauncherSystem
import tv.own.owntv.launcher.apps.rememberLauncherConfig
import tv.own.owntv.launcher.apps.rowApps
import tv.own.owntv.ui.components.OwnTVIcon
import tv.own.owntv.ui.stage.StagePopup
import tv.own.owntv.ui.stage.StagePopupCheck
import tv.own.owntv.ui.stage.StagePopupOption
import tv.own.owntv.ui.theme.mpx

/** How many rows Settings › Power-on & Home button has, for the page's count. */
internal const val LAUNCHER_SETTING_COUNT = 5

/** The parts of these rows, drawn where they belong since Settings were sorted anew (Jonathan, 4 Nov 2026). */
internal enum class LauncherRowsPart {
    /** Settings › Power-on & Home button: Mayo TV as start screen, the guard, what opens at power-on. */
    BOOT,
    /** Settings › Appearance & Home › Home: the Apps row, its apps, the other apps' channels. */
    HOME_APPS,
}

/**
 * Mayo TV as the TV's start screen (Home button, guard, power-on), and what Home shows besides Mayo TV's own
 * rows (the Apps row, other apps' channels): each [LauncherRowsPart] on its own page.
 */
@Composable
internal fun LauncherSettingsRows(
    part: LauncherRowsPart,
    /** Mayo TV's own App start row (what it opens with), right under Open at power-on. */
    afterBoot: @Composable () -> Unit = {},
    /** Under the start-screen rows: Android TV's own start screen (Mayo TV's rows there). */
    androidTvHome: @Composable () -> Unit = {},
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val config = rememberLauncherConfig()
    val apps by LauncherApps.apps.collectAsStateWithLifecycle()
    val channels by LauncherApps.channels.collectAsStateWithLifecycle()
    // What the TV itself decides (the guard in its accessibility list, the permissions) is read again
    // whenever Mayo TV comes back to the front, e.g. from the accessibility settings.
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    var resumes by remember { mutableIntStateOf(0) }
    DisposableEffect(lifecycle) {
        val observer = LifecycleEventObserver { _, e -> if (e == Lifecycle.Event.ON_RESUME) resumes++ }
        lifecycle.addObserver(observer)
        onDispose { lifecycle.removeObserver(observer) }
    }
    LaunchedEffect(resumes) {
        LauncherApps.refreshApps(context)
        LauncherApps.refreshChannels(context)
    }
    val guardOn = remember(resumes, config) { LauncherSystem.isGuardOn(context) }
    val canWrite = remember(resumes) { LauncherSystem.canWriteSecure(context) }
    val canRead = remember(resumes) { LauncherApps.canReadChannels(context) }
    val askChannels = rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) {
        scope.launch { LauncherApps.refreshChannels(context) }
        resumes++
    }
    var pickBoot by remember { mutableStateOf(false) }
    var pickRow by remember { mutableStateOf(false) }
    var pickChannels by remember { mutableStateOf(false) }
    fun update(change: (LauncherConfig) -> LauncherConfig) = LauncherPrefs.update(context, change)

    // Shared with the dialogs at the end, whichever part is drawn.
    val inRow = rowApps(config, apps)
    // TizenTube is called YouTube here as on Home (Jonathan, 4 Nov 2026).
    val youtube = stringResource(R.string.launcher_youtube)
    val nameOf = { app: tv.own.owntv.launcher.apps.LauncherApp -> if (tv.own.owntv.launcher.apps.isTizenTube(app.pkg)) youtube else app.label }

    if (part == LauncherRowsPart.BOOT) {
    StageSettingsHeading(stringResource(R.string.launcher_heading_button), 4, first = true)
    StageSettingRow(
        icon = OwnTVIcon.HOME,
        title = stringResource(R.string.launcher_as_start),
        desc = stringResource(R.string.launcher_as_start_line),
        value = SettingValue.Switch(config.launcher),
        onClick = { update { it.copy(launcher = !it.launcher) } },
        help = SettingHelp(
            title = stringResource(R.string.launcher_as_start),
            text = stringResource(R.string.launcher_as_start_help, LauncherSystem.HOME_COMMAND),
        ),
    )
    StageSettingRow(
        icon = OwnTVIcon.FOCUS_HIGHLIGHT,
        title = stringResource(R.string.launcher_guard),
        desc = stringResource(R.string.launcher_guard_line),
        value = if (!guardOn && !canWrite) SettingValue.Action(stringResource(R.string.launcher_guard_needs_setup))
        else SettingValue.Switch(guardOn && config.guard),
        enabled = config.launcher,
        onClick = {
            if (guardOn && config.guard) {
                // Off: only our own entry leaves the list; without the permission the TV's page does it.
                update { it.copy(guard = false) }
                if (!LauncherSystem.switchGuardOff(context)) LauncherSystem.openAccessibilitySettings(context)
            } else {
                update { it.copy(guard = true) }
                if (!guardOn && !LauncherSystem.switchGuardOn(context)) LauncherSystem.openAccessibilitySettings(context)
            }
            resumes++
        },
        help = SettingHelp(
            title = stringResource(R.string.launcher_guard),
            text = stringResource(R.string.launcher_guard_help, LauncherSystem.grantCommand(context)),
        ),
    )
    val bootLabel = when (config.boot) {
        LauncherConfig.BOOT_HOME, LauncherConfig.BOOT_LIVE -> stringResource(R.string.launcher_boot_home)
        LauncherConfig.BOOT_OFF -> stringResource(R.string.launcher_boot_off)
        else -> apps.firstOrNull { it.pkg == config.boot }?.label ?: config.boot
    }
    StageSettingRow(
        icon = OwnTVIcon.POWER,
        title = stringResource(R.string.launcher_boot),
        desc = stringResource(R.string.launcher_boot_line),
        value = SettingValue.Choice(bootLabel),
        enabled = config.launcher,
        onClick = { pickBoot = true },
    )

    afterBoot()
    androidTvHome()
    }

    if (part == LauncherRowsPart.HOME_APPS) {
    StageSettingsHeading(stringResource(R.string.settings_heading_home_apps), 3)
    StageSettingRow(
        icon = OwnTVIcon.TILES,
        title = stringResource(R.string.launcher_apps_row),
        desc = stringResource(R.string.launcher_apps_row_line),
        value = SettingValue.Switch(config.appsRow),
        onClick = { update { it.copy(appsRow = !it.appsRow) } },
    )
    StageSettingRow(
        icon = OwnTVIcon.MOVE,
        title = stringResource(R.string.launcher_row_apps_setting),
        desc = stringResource(R.string.launcher_row_apps_setting_line),
        value = SettingValue.Opens(inRow.joinToString(stringResource(R.string.content_epg_bits_separator), transform = nameOf)),
        enabled = config.appsRow,
        onClick = { pickRow = true },
    )
    val channelsValue = when {
        !canRead -> SettingValue.Action(stringResource(R.string.launcher_app_channels_allow))
        channels.isEmpty() -> SettingValue.Opens(stringResource(R.string.launcher_app_channels_none))
        !config.appChannels -> SettingValue.Switch(false)
        else -> SettingValue.Opens(
            stringResource(R.string.launcher_app_channels_count, channels.count { it.key !in config.hiddenChannels }, channels.size),
        )
    }
    StageSettingRow(
        icon = OwnTVIcon.LAYERS,
        title = stringResource(R.string.launcher_app_channels),
        desc = stringResource(R.string.launcher_app_channels_line),
        value = channelsValue,
        onClick = {
            if (!canRead) askChannels.launch(LauncherApps.PERMISSIONS) else pickChannels = true
        },
        help = SettingHelp(
            title = stringResource(R.string.launcher_app_channels),
            text = stringResource(R.string.launcher_app_channels_help, LauncherSystem.channelsCommand(context)),
        ),
    )
    }

    if (pickBoot) {
        val fixed = listOf(
            // Mayo TV opens with what App start (the row below) says; Live TV is one of its choices.
            LauncherConfig.BOOT_HOME to stringResource(R.string.launcher_boot_home),
            LauncherConfig.BOOT_OFF to stringResource(R.string.launcher_boot_off),
        )
        PickerDialog(
            title = stringResource(R.string.launcher_boot),
            options = fixed + apps.map { it.pkg to nameOf(it) },
            selected = config.boot,
            onSelect = { value -> update { it.copy(boot = value) }; pickBoot = false },
            onDismiss = { pickBoot = false },
        )
    }
    if (pickRow) {
        val current = inRow.map { it.pkg }
        StagePopup(onDismiss = { pickRow = false }, title = stringResource(R.string.launcher_row_apps_setting)) {
            Column(verticalArrangement = Arrangement.spacedBy(4.mpx), modifier = Modifier.padding(top = 18.mpx)) {
                apps.forEach { app ->
                    val on = app.pkg in current
                    StagePopupOption(
                        title = nameOf(app),
                        onClick = { update { it.copy(rowApps = if (on) current - app.pkg else current + app.pkg) } },
                        leading = { f -> StagePopupCheck(on, f) },
                    )
                }
            }
        }
    }
    if (pickChannels) {
        val labels = apps.associate { it.pkg to nameOf(it) }
        val continueTitle = stringResource(R.string.launcher_row_continue)
        StagePopup(onDismiss = { pickChannels = false }, title = stringResource(R.string.launcher_app_channels)) {
            Column(verticalArrangement = Arrangement.spacedBy(4.mpx), modifier = Modifier.padding(top = 18.mpx)) {
                StagePopupOption(
                    title = stringResource(R.string.launcher_app_channels),
                    onClick = { update { it.copy(appChannels = !it.appChannels) } },
                    leading = { f -> StagePopupCheck(config.appChannels, f) },
                )
                channels.forEach { ch ->
                    val on = ch.key !in config.hiddenChannels
                    val app = labels[ch.pkg] ?: ch.pkg
                    StagePopupOption(
                        title = if (ch.key.endsWith("/${LauncherApps.WATCH_NEXT}")) continueTitle else ch.name.ifBlank { app },
                        subtitle = app,
                        enabled = config.appChannels,
                        onClick = {
                            update { it.copy(hiddenChannels = if (on) it.hiddenChannels + ch.key else it.hiddenChannels - ch.key) }
                        },
                        leading = { f -> StagePopupCheck(on, f) },
                    )
                }
            }
        }
    }
}

/**
 * A page that gathers Video player rows from several places: Per playlist, Reset remembered settings. Only the
 * rows in [keys] are drawn (from [group]'s sections, with their own dialogs), then [extraRows].
 */
@Composable
internal fun VideoGatherPage(
    group: VideoGroup,
    keys: Set<String>,
    parent: String,
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
    extraRows: @Composable androidx.compose.foundation.layout.ColumnScope.() -> Unit = {},
) {
    val scroll = androidx.compose.foundation.rememberScrollState()
    StageFullPage(
        parents = listOf(parent),
        title = stringResource(group.titleRes),
        count = "",
        onBack = onBack,
        modifier = modifier,
        scroll = scroll,
    ) {
        androidx.compose.runtime.CompositionLocalProvider(LocalVideoRowFilter provides { it in keys }) {
            VideoPlayerGroupRows(group, scroll)
        }
        extraRows()
    }
}

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

/** How many rows Settings › Home screen has, for the page's count. */
internal const val LAUNCHER_SETTING_COUNT = 7

/**
 * Settings › Home screen: Mayo TV as the TV's start screen (Home button, guard, power-on), then what
 * Home shows besides Mayo TV's own rows (the Apps row, other apps' channels) and the Apps section.
 */
@Composable
internal fun LauncherSettingsRows() {
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

    StageSettingsHeading(stringResource(R.string.launcher_heading_button), 3, first = true)
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
        LauncherConfig.BOOT_HOME -> stringResource(R.string.launcher_boot_home)
        LauncherConfig.BOOT_LIVE -> stringResource(R.string.launcher_boot_live)
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

    StageSettingsHeading(stringResource(R.string.launcher_heading_home), 4)
    StageSettingRow(
        icon = OwnTVIcon.TILES,
        title = stringResource(R.string.launcher_apps_row),
        desc = stringResource(R.string.launcher_apps_row_line),
        value = SettingValue.Switch(config.appsRow),
        onClick = { update { it.copy(appsRow = !it.appsRow) } },
    )
    val inRow = rowApps(config, apps)
    StageSettingRow(
        icon = OwnTVIcon.MOVE,
        title = stringResource(R.string.launcher_row_apps_setting),
        desc = stringResource(R.string.launcher_row_apps_setting_line),
        value = SettingValue.Opens(inRow.joinToString(stringResource(R.string.content_epg_bits_separator)) { it.label }),
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
    StageSettingRow(
        icon = OwnTVIcon.GRID,
        title = stringResource(R.string.launcher_apps_menu),
        desc = stringResource(R.string.launcher_apps_menu_line),
        value = SettingValue.Switch(config.appsInMenu),
        onClick = { update { it.copy(appsInMenu = !it.appsInMenu) } },
    )

    if (pickBoot) {
        val fixed = listOf(
            LauncherConfig.BOOT_HOME to stringResource(R.string.launcher_boot_home),
            LauncherConfig.BOOT_LIVE to stringResource(R.string.launcher_boot_live),
            LauncherConfig.BOOT_OFF to stringResource(R.string.launcher_boot_off),
        )
        PickerDialog(
            title = stringResource(R.string.launcher_boot),
            options = fixed + apps.map { it.pkg to it.label },
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
                        title = app.label,
                        onClick = { update { it.copy(rowApps = if (on) current - app.pkg else current + app.pkg) } },
                        leading = { f -> StagePopupCheck(on, f) },
                    )
                }
            }
        }
    }
    if (pickChannels) {
        val labels = apps.associate { it.pkg to it.label }
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

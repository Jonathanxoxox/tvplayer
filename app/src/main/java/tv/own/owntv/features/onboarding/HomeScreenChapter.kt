package tv.own.owntv.features.onboarding

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import org.koin.compose.koinInject
import tv.own.owntv.R
import tv.own.owntv.core.settings.SettingsRepository
import tv.own.owntv.features.onboarding.homescreen.BenefitRow
import tv.own.owntv.features.onboarding.homescreen.CommandLine
import tv.own.owntv.features.onboarding.homescreen.HintLine
import tv.own.owntv.features.onboarding.homescreen.HomeSetupProgress
import tv.own.owntv.features.onboarding.homescreen.HomeSetupSystem
import tv.own.owntv.features.onboarding.homescreen.HomeStatus
import tv.own.owntv.features.onboarding.homescreen.HomeStep
import tv.own.owntv.features.onboarding.homescreen.NumberedStep
import tv.own.owntv.features.onboarding.homescreen.PowerOn
import tv.own.owntv.features.onboarding.homescreen.SettingsOpened
import tv.own.owntv.features.onboarding.homescreen.StatusCard
import tv.own.owntv.features.onboarding.homescreen.rememberHomeStatus
import tv.own.owntv.features.settings.SettingValue
import tv.own.owntv.features.settings.StageSettingRow
import tv.own.owntv.launcher.apps.LauncherSystem
import tv.own.owntv.ui.components.OwnTVIcon
import tv.own.owntv.ui.stage.StagePill
import tv.own.owntv.ui.theme.mpx
import tv.own.owntv.ui.theme.stageAccent

/**
 * Mayo TV as the box's start screen: the Home button, the accessibility Home guard and power-on.
 * Recommended but skippable; never a dead end on boxes that refuse it. The user may leave for the
 * system's settings here: the journey resumes on this chapter even after the app was killed, and the
 * chapter itself resumes on the page it was on ([HomeSetupProgress]).
 *
 * Three pages:
 * - **Pitch**: what the user gets and what the guard can see; "Set up" or "Later".
 * - **Guard**: the guided switch-on with a live status. One click where WRITE_SECURE_SETTINGS is
 *   granted; otherwise numbered steps and a button into the accessibility settings (with fallbacks and
 *   the manual route), the Home app picker where the box has one, and an ADB hint for experts.
 * - **Power-on**: Mayo TV's Home, live TV or nothing, written as Settings › Power-on & Home button does.
 *
 * "Later" leaves the chapter from any page; whatever was switched on for a guard that never came on
 * is switched off again, so nothing half set up stays behind.
 *
 * [onNext] moves on to the next chapter (after this chapter has saved what the user chose);
 * [onBack] returns to the previous chapter, or is null where Back must not move (see [ChapterFrame]).
 */
@Composable
fun HomeScreenChapter(onNext: () -> Unit, onBack: (() -> Unit)?) {
    val context = LocalContext.current.applicationContext
    val progress = remember { HomeSetupProgress(context) }
    var step by remember { mutableStateOf(progress.step) }
    val statusState = rememberHomeStatus()
    val status = statusState.value

    fun go(to: HomeStep) {
        progress.step = to
        step = to
    }
    val later: () -> Unit = {
        HomeSetupSystem.disarmUnlessActive(context, progress, statusState.value)
        onNext()
    }

    // A Home button that now leads here is recorded as Settings would, once the user asked for it.
    LaunchedEffect(status.active) {
        if (status.active && progress.armed) {
            HomeSetupSystem.adopt(context, status)
            progress.attempts = 0
        }
    }

    when (step) {
        HomeStep.PITCH -> PitchPage(
            status = status,
            onSetUp = {
                HomeSetupSystem.arm(context, progress)
                val now = statusState.value
                if (now.active) {
                    // Already working: recorded now, and straight on to the power-on choice.
                    HomeSetupSystem.adopt(context, now)
                    go(HomeStep.BOOT)
                } else {
                    go(HomeStep.GUARD)
                }
            },
            onLater = later,
            onBack = onBack?.let { back ->
                {
                    HomeSetupSystem.disarmUnlessActive(context, progress, statusState.value)
                    back()
                }
            },
        )
        HomeStep.GUARD -> GuardPage(
            status = status,
            progress = progress,
            refresh = { statusState.value = HomeSetupSystem.read(context) },
            onContinue = { go(HomeStep.BOOT) },
            onLater = later,
            onBack = { go(HomeStep.PITCH) },
        )
        HomeStep.BOOT -> BootPage(
            status = status,
            onDone = onNext,
            onLater = later,
            onBack = { go(HomeStep.GUARD) },
            progress = progress,
        )
    }
}

/** Where the later note points: Settings › Power-on & Home button. */
@Composable
private fun LaterNote() {
    HintLine(
        stringResource(R.string.onboarding_home_screen_later_note, stringResource(R.string.launcher_settings_group)),
        modifier = Modifier.padding(top = 14.mpx),
    )
}

@Composable
private fun PitchPage(status: HomeStatus, onSetUp: () -> Unit, onLater: () -> Unit, onBack: (() -> Unit)?) {
    val first = rememberChapterFocus()
    ChapterFrame(
        chapter = OnboardingChapter.HOME_SCREEN,
        title = stringResource(R.string.onboarding_home_screen_title),
        subtitle = stringResource(R.string.onboarding_home_screen_sub),
        primary = ChapterAction(
            stringResource(if (status.active) R.string.onboarding_action_continue else R.string.onboarding_home_screen_action_set_up),
            onSetUp,
            first,
        ),
        secondary = onBack?.let { ChapterAction(stringResource(R.string.common_back), it) },
        skip = ChapterAction(stringResource(R.string.onboarding_action_later), onLater),
    ) {
        Column(verticalArrangement = Arrangement.spacedBy(22.mpx)) {
            BenefitRow(
                OwnTVIcon.HOME,
                stringResource(R.string.onboarding_home_screen_pitch_home_title),
                stringResource(R.string.onboarding_home_screen_pitch_home_line),
            )
            BenefitRow(
                OwnTVIcon.POWER,
                stringResource(R.string.onboarding_home_screen_pitch_power_title),
                stringResource(R.string.onboarding_home_screen_pitch_power_line),
            )
            BenefitRow(
                OwnTVIcon.EYE_OFF,
                stringResource(R.string.onboarding_home_screen_pitch_privacy_title),
                stringResource(R.string.onboarding_home_screen_pitch_privacy_line),
            )
        }
        if (status.active) StatusCard(status, Modifier.padding(top = 18.mpx))
        LaterNote()
    }
}

/** Which control holds focus on the guard page. */
private enum class GuardFocus { ACTION, CONTINUE }

@Composable
private fun GuardPage(
    status: HomeStatus,
    progress: HomeSetupProgress,
    refresh: () -> Unit,
    onContinue: () -> Unit,
    onLater: () -> Unit,
    onBack: () -> Unit,
) {
    val context = LocalContext.current
    val continueFocus = remember { FocusRequester() }
    val actionFocus = remember { FocusRequester() }
    var attempts by remember { mutableStateOf(progress.attempts) }
    var openFailed by remember { mutableStateOf(progress.openFailed) }
    var writeFailed by remember { mutableStateOf(false) }
    var showExperts by remember { mutableStateOf(false) }

    // The way in the user has on this box right now: one click, the settings button, or neither.
    val canOpen = !openFailed
    // The steps by hand: no one-click switch here, or it did not take.
    val manual = !status.canWrite || writeFailed
    val hasAction = !status.active && (status.canWrite || canOpen)
    val focusOn = if (hasAction) GuardFocus.ACTION else GuardFocus.CONTINUE
    // Initial focus, and again whenever the way in changes: the guard came on (on to Continue) or an
    // ADB grant turned the settings button into the one-click switch.
    LaunchedEffect(focusOn, status.canWrite) {
        delay(FOCUS_DELAY_MS)
        runCatching { (if (focusOn == GuardFocus.ACTION) actionFocus else continueFocus).requestFocus() }
    }

    val service = stringResource(R.string.launcher_guard_service_label)
    val subtitle = stringResource(
        when {
            status.active -> R.string.onboarding_home_screen_guard_sub_active
            status.canWrite -> R.string.onboarding_home_screen_guard_sub_write
            else -> R.string.onboarding_home_screen_guard_sub
        },
    )
    // The steps by hand and the hints about earlier tries fill a left column beside the status. Without
    // them (the guard is on, or one click switches it on) the status and its action stand alone.
    val stepsShown = !status.active && manual
    val hintShown = !status.active && (writeFailed || attempts >= 1)
    val twoColumns = stepsShown || hintShown
    // The live status and the ways in.
    val waysIn: @Composable ColumnScope.() -> Unit = {
        StatusCard(status)
        if (!status.active) {
            if (status.canWrite) {
                StagePill(
                    stringResource(R.string.onboarding_home_screen_switch_on),
                    onClick = {
                        writeFailed = !LauncherSystem.switchGuardOn(context)
                        refresh()
                    },
                    icon = OwnTVIcon.POWER,
                    modifier = Modifier.focusRequester(actionFocus),
                )
            }
            if (canOpen && manual) {
                StagePill(
                    stringResource(R.string.onboarding_home_screen_open_settings),
                    onClick = {
                        val opened = HomeSetupSystem.openAccessibility(context)
                        if (opened == SettingsOpened.NONE) {
                            openFailed = true
                            progress.openFailed = true
                        } else {
                            attempts += 1
                            progress.attempts = attempts
                        }
                    },
                    icon = OwnTVIcon.SETTINGS,
                    modifier = if (status.canWrite) Modifier else Modifier.focusRequester(actionFocus),
                )
            }
            if (status.homePicker) {
                StagePill(
                    stringResource(R.string.onboarding_home_screen_choose_home),
                    onClick = { HomeSetupSystem.openHomePicker(context) },
                    icon = OwnTVIcon.HOME,
                )
                HintLine(stringResource(R.string.onboarding_home_screen_choose_home_line))
            }
            if (!status.canWrite) {
                StagePill(
                    stringResource(R.string.onboarding_home_screen_experts),
                    onClick = { showExperts = !showExperts },
                    trailingIcon = if (showExperts) OwnTVIcon.CHEVRON_UP else OwnTVIcon.CHEVRON_DOWN,
                    dimmed = !showExperts,
                )
            }
        }
    }
    ChapterFrame(
        chapter = OnboardingChapter.HOME_SCREEN,
        title = stringResource(if (status.active) R.string.onboarding_home_screen_guard_title_active else R.string.onboarding_home_screen_guard_title),
        subtitle = subtitle,
        primary = ChapterAction(stringResource(R.string.onboarding_action_continue), onContinue, continueFocus),
        secondary = ChapterAction(stringResource(R.string.common_back), onBack),
        skip = if (status.active) null else ChapterAction(stringResource(R.string.onboarding_action_later), onLater),
        width = if (twoColumns) 1180.mpx else 1040.mpx,
    ) {
        if (!twoColumns) {
            Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(12.mpx)) {
                waysIn()
                if (status.active) LaterNote()
            }
        } else {
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(40.mpx)) {
                // Left: what to do, step by step, and how it went last time.
                Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(14.mpx)) {
                    if (stepsShown) {
                        val steps = buildList {
                            if (openFailed) {
                                add(stringResource(R.string.onboarding_home_screen_path, service))
                            } else {
                                add(stringResource(R.string.onboarding_home_screen_step_1))
                                add(stringResource(R.string.onboarding_home_screen_step_2, service))
                            }
                            add(stringResource(R.string.onboarding_home_screen_step_3))
                            add(stringResource(R.string.onboarding_home_screen_step_4))
                        }
                        steps.forEachIndexed { i, text -> NumberedStep(i + 1, text) }
                        if (openFailed) {
                            HintLine(stringResource(R.string.onboarding_home_screen_open_failed), warn = true)
                        } else {
                            // Some boxes only open their general settings: the route from there.
                            HintLine(stringResource(R.string.onboarding_home_screen_path, service))
                        }
                    }
                    if (hintShown) {
                        when {
                            writeFailed -> HintLine(stringResource(R.string.onboarding_home_screen_write_failed), warn = true)
                            attempts >= 2 -> HintLine(stringResource(R.string.onboarding_home_screen_retry_again), warn = true)
                            attempts == 1 -> HintLine(stringResource(R.string.onboarding_home_screen_retry_first), warn = true)
                        }
                    }
                }
                Column(Modifier.width(420.mpx), verticalArrangement = Arrangement.spacedBy(12.mpx), content = waysIn)
            }
        }
        if (showExperts && !status.active && !status.canWrite) {
            HintLine(stringResource(R.string.onboarding_home_screen_experts_text), modifier = Modifier.padding(top = 18.mpx))
            CommandLine(LauncherSystem.grantCommand(context))
        }
    }
}

@Composable
private fun BootPage(
    status: HomeStatus,
    onDone: () -> Unit,
    onLater: () -> Unit,
    onBack: () -> Unit,
    progress: HomeSetupProgress,
) {
    val context = LocalContext.current.applicationContext
    val settings: SettingsRepository = koinInject()
    val appScope: CoroutineScope = koinInject()
    val initial = remember { HomeSetupSystem.currentPowerOn(context, null) }
    var choice by remember { mutableStateOf(initial) }
    var touched by remember { mutableStateOf(false) }
    // The profile's App start decides between Home and live TV; read once, unless the user chose already.
    LaunchedEffect(Unit) {
        val startup = runCatching {
            val id = settings.activeProfileIdNow()
            if (id >= 0L) settings.startupMode(id).first() else null
        }.getOrNull()
        if (!touched && startup != null) choice = HomeSetupSystem.currentPowerOn(context, startup)
    }
    val first = rememberChapterFocus()

    val save: () -> Unit = {
        val picked = choice
        HomeSetupSystem.savePowerOn(context, picked, status)
        progress.armed = false
        // App start is per profile and lives in core's settings; the write outlives this page.
        appScope.launch {
            runCatching {
                val id = settings.activeProfileIdNow()
                if (id >= 0L) {
                    HomeSetupSystem.startupFor(picked, settings.startupMode(id).first())?.let { settings.setStartupMode(id, it) }
                }
            }
        }
        onDone()
    }

    ChapterFrame(
        chapter = OnboardingChapter.HOME_SCREEN,
        title = stringResource(R.string.onboarding_home_screen_boot_title),
        subtitle = stringResource(R.string.onboarding_home_screen_boot_sub),
        primary = ChapterAction(stringResource(R.string.onboarding_action_continue), save),
        secondary = ChapterAction(stringResource(R.string.common_back), onBack),
        skip = ChapterAction(stringResource(R.string.onboarding_action_later), onLater),
    ) {
        val selected = stringResource(R.string.onboarding_home_screen_selected)
        val accent = stageAccent.accent
        val options = listOf(
            Triple(PowerOn.HOME, OwnTVIcon.HOME, R.string.onboarding_home_screen_boot_home to R.string.onboarding_home_screen_boot_home_line),
            Triple(PowerOn.LIVE, OwnTVIcon.LIVE_TV, R.string.onboarding_home_screen_boot_live to R.string.onboarding_home_screen_boot_live_line),
            Triple(
                PowerOn.NOTHING, OwnTVIcon.CLOSE,
                R.string.onboarding_home_screen_boot_off to
                    if (status.guardOn) R.string.onboarding_home_screen_boot_off_line_guard else R.string.onboarding_home_screen_boot_off_line,
            ),
        )
        options.forEach { (option, icon, text) ->
            val on = option == choice
            StageSettingRow(
                icon = icon,
                title = stringResource(text.first),
                desc = stringResource(text.second),
                value = if (on) {
                    SettingValue.Custom {
                        OwnTVIcon(
                            OwnTVIcon.CHECK, accent,
                            Modifier.size(26.mpx).semantics { contentDescription = selected },
                        )
                    }
                } else null,
                onClick = {
                    touched = true
                    choice = option
                },
                modifier = if (option == initial) Modifier.focusRequester(first) else Modifier,
            )
        }
        if (choice != PowerOn.NOTHING && !status.guardOn && !HomeSetupSystem.canStartFromBackground(context)) {
            HintLine(stringResource(R.string.onboarding_home_screen_boot_note), modifier = Modifier.padding(top = 12.mpx))
        }
        LaterNote()
    }
}

private const val FOCUS_DELAY_MS = 60L

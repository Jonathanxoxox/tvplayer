package tv.own.owntv.features.setup

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.focusGroup
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.annotation.StringRes
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.em
import androidx.tv.material3.Text
import kotlinx.coroutines.launch
import tv.own.owntv.R
import tv.own.owntv.core.ui.findActivity
import tv.own.owntv.ui.components.OwnTVIcon
import tv.own.owntv.ui.stage.StageFocus
import tv.own.owntv.ui.stage.StageKeyHints
import tv.own.owntv.ui.stage.StagePill
import tv.own.owntv.ui.stage.StageSurface
import tv.own.owntv.ui.stage.stageBackground
import tv.own.owntv.ui.stage.stageGlass
import tv.own.owntv.ui.theme.StageColors
import tv.own.owntv.ui.theme.mpx
import tv.own.owntv.ui.theme.ownTvTween
import tv.own.owntv.ui.theme.stageAccent
import tv.own.owntv.ui.theme.stageText

/** One footer button of a wizard step or onboarding chapter: its label and what OK on it does. */
class WizardAction(val label: String, val onClick: () -> Unit, val focus: FocusRequester? = null, val enabled: Boolean = true)

/** The five steps the dots show: which one a page belongs to. */
internal enum class WizardStep { LANGUAGE, DISPLAY, PROFILE, PLAYLIST, READY }

/**
 * Every stop of the first-run journey as the progress indicator shows it: the inherited wizard's
 * steps, then the chapters that run once the profile exists. Order is display order.
 */
enum class JourneyStep(@StringRes val labelRes: Int) {
    LANGUAGE(R.string.settings_language),
    DISPLAY(R.string.setup_step_display),
    PROFILE(R.string.settings_group_profile),
    PLAYLIST(R.string.setup_step_playlist),
    APPEARANCE(R.string.onboarding_step_appearance),
    HOME_SCREEN(R.string.onboarding_step_home_screen),
    FOOTBALL(R.string.onboarding_step_football),
    EXTRAS(R.string.onboarding_step_extras),
    READY(R.string.setup_step_ready),
    ;

    companion object {
        /** The dots of the profile-only wizard ("Add profile", recovery): its own five steps. */
        val WIZARD_ONLY = listOf(LANGUAGE, DISPLAY, PROFILE, PLAYLIST, READY)
    }
}

/**
 * True inside the first-run journey (provided by the onboarding flow): the wizard's pages then show
 * the whole journey's progress, and the wizard's own "All set" counts as part of the playlist step
 * because more chapters follow.
 */
val LocalFirstRunJourney = staticCompositionLocalOf { false }

/** A wizard step's place in the indicator, inside or outside the first-run journey. */
private fun WizardStep.journeyStep(inJourney: Boolean): JourneyStep = when (this) {
    WizardStep.LANGUAGE -> JourneyStep.LANGUAGE
    WizardStep.DISPLAY -> JourneyStep.DISPLAY
    WizardStep.PROFILE -> JourneyStep.PROFILE
    WizardStep.PLAYLIST -> JourneyStep.PLAYLIST
    WizardStep.READY -> if (inJourney) JourneyStep.PLAYLIST else JourneyStep.READY
}

/**
 * Every setup step's frame: the Stage background, the Mayo TV lockup top left, the step
 * dots top right, one glass panel (1040 wide, centred, from y 170) with a 42 px title, 19 px text and the
 * step's rows or cards, then [back] bottom-left and [skip] and [next] bottom-right, and the key hints at the bottom.
 * Back on the remote calls [back] (or [onBack] where the step has no Back button).
 */
@Composable
internal fun WizardFrame(
    step: WizardStep,
    title: String,
    sub: String?,
    back: WizardAction?,
    next: WizardAction?,
    onBack: (() -> Unit)? = back?.onClick,
    width: Dp = 1040.mpx,
    /** More buttons beside Back (the display step's Reset). */
    extraLeft: List<WizardAction> = emptyList(),
    /** A quiet "not now" on the right, before [next]. */
    skip: WizardAction? = null,
    body: @Composable ColumnScope.() -> Unit = {},
) {
    val inJourney = LocalFirstRunJourney.current
    JourneyFrame(
        step = step.journeyStep(inJourney),
        steps = if (inJourney) JourneyStep.entries else JourneyStep.WIZARD_ONLY,
        title = title,
        sub = sub,
        back = back,
        next = next,
        onBack = onBack,
        width = width,
        extraLeft = extraLeft,
        skip = skip,
        showPosition = inJourney,
        body = body,
    )
}

/**
 * The frame shared by the setup wizard and the onboarding chapters: background, lockup, the
 * progress dots over [steps] with [step] current, the glass panel with an optional "Step n of m"
 * line ([showPosition]), title, [sub], the body, and the footer: [back] and [extraLeft] on the left,
 * [skip] and [next] on the right. Back on the remote calls [onBack]; with null it sends the app to the
 * background inside the first-run journey and is left to the system elsewhere.
 */
@Composable
internal fun JourneyFrame(
    step: JourneyStep,
    steps: List<JourneyStep>,
    title: String,
    sub: String?,
    back: WizardAction?,
    next: WizardAction?,
    onBack: (() -> Unit)? = back?.onClick,
    width: Dp = 1040.mpx,
    extraLeft: List<WizardAction> = emptyList(),
    skip: WizardAction? = null,
    showPosition: Boolean = false,
    body: @Composable ColumnScope.() -> Unit = {},
) {
    val inJourney = LocalFirstRunJourney.current
    val activity = LocalContext.current.findActivity()
    when {
        onBack != null -> BackHandler { onBack() }
        // Where the first-run journey has no step to go back to, Back never closes the app: on Android 11
        // and older that would finish the activity and cancel an import running in the background. The
        // app goes to the background instead (as Android 12 does on its own); the journey resumes here.
        inJourney -> BackHandler { activity?.moveTaskToBack(true) }
    }
    val a = stageAccent
    BoxWithConstraints(Modifier.fillMaxSize().stageBackground(a.accent)) {
        MayoLockup(Modifier.padding(start = 64.mpx, top = 56.mpx))
        StepDots(step, steps, Modifier.align(Alignment.TopEnd).padding(end = 64.mpx, top = 66.mpx))

        Column(
            Modifier
                .align(Alignment.TopCenter)
                .padding(top = 170.mpx, bottom = 120.mpx)
                .width(width.coerceAtMost(maxWidth - 128.mpx))
                .heightIn(max = maxHeight - 290.mpx)
                .stageGlass(32.mpx)
                .focusGroup()
                .padding(horizontal = 48.mpx, vertical = 44.mpx),
        ) {
            val position = steps.indexOf(step)
            if (showPosition && position >= 0) {
                Text(
                    stringResource(R.string.onboarding_step_position, position + 1, steps.size).uppercase(),
                    style = stageText(14, 800, 0.12f.em),
                    color = a.accent,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.padding(bottom = 10.mpx),
                )
            }
            Text(title, style = stageText(42, 800, (-1f / 46f).em), color = StageColors.Text)
            if (!sub.isNullOrBlank()) {
                Text(sub, style = stageText(19, 500).copy(lineHeight = 29.5f.mpxLine()), color = StageColors.Muted, modifier = Modifier.padding(top = 10.mpx))
            }
            // Only the body scrolls: on a long page (many crests, a large UI zoom) the title stays on top
            // and the footer stays on screen at the bottom of the panel. The inner vertical padding leaves
            // room for a focused row's scale at the scroll edges.
            val bodyScroll = rememberScrollState()
            val scope = rememberCoroutineScope()
            Column(
                Modifier
                    .padding(top = 20.mpx)
                    .weight(1f, fill = false)
                    .verticalScroll(bodyScroll)
                    .padding(vertical = 8.mpx),
                verticalArrangement = Arrangement.spacedBy(6.mpx),
                content = body,
            )
            if (back != null || next != null || skip != null || extraLeft.isNotEmpty()) {
                Row(
                    Modifier
                        .fillMaxWidth()
                        .padding(top = 22.mpx)
                        // Focus on the footer shows the end of the body, so text below the last focusable
                        // row (a note, the phone panel's address) is never left out of sight.
                        .onFocusChanged { if (it.hasFocus) scope.launch { bodyScroll.animateScrollTo(bodyScroll.maxValue) } },
                    horizontalArrangement = Arrangement.spacedBy(12.mpx),
                ) {
                    (listOfNotNull(back) + extraLeft).forEach { b ->
                        StagePill(b.label, onClick = b.onClick, enabled = b.enabled, modifier = b.focus?.let { Modifier.focusRequester(it) } ?: Modifier)
                    }
                    Spacer(Modifier.weight(1f))
                    if (skip != null) {
                        StagePill(
                            skip.label, onClick = skip.onClick, enabled = skip.enabled, dimmed = true,
                            modifier = skip.focus?.let { Modifier.focusRequester(it) } ?: Modifier,
                        )
                    }
                    if (next != null) {
                        StagePill(
                            next.label, onClick = next.onClick, trailingIcon = OwnTVIcon.CHEVRON, enabled = next.enabled,
                            modifier = next.focus?.let { Modifier.focusRequester(it) } ?: Modifier,
                        )
                    }
                }
            }
        }
        val backHint = when {
            onBack != null -> stringResource(R.string.setup_key_previous_step)
            inJourney -> stringResource(R.string.onboarding_key_pause)
            else -> null
        }
        StageKeyHints(
            listOfNotNull(
                stringResource(R.string.common_ok) to stringResource(R.string.content_key_select),
                backHint?.let { stringResource(R.string.common_back) to it },
            ),
            Modifier.align(Alignment.BottomCenter).padding(bottom = 44.mpx),
            textSize = 15,
        )
    }
}

/** The Mayo TV lockup: the play mark on its cream tile, the name beside it. [scale] grows it (the intro). */
@Composable
fun MayoLockup(modifier: Modifier = Modifier, scale: Float = 1f) {
    Row(
        modifier,
        horizontalArrangement = Arrangement.spacedBy((14 * scale).mpx),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(Modifier.size((44 * scale).mpx).background(LockupTile, RoundedCornerShape((12 * scale).mpx)), contentAlignment = Alignment.Center) {
            OwnTVIcon(OwnTVIcon.PLAY, LockupMark, Modifier.size((22 * scale).mpx))
        }
        Text(stringResource(R.string.tvplayer_app_name), style = stageText((24 * scale).toInt(), 800), color = StageColors.Text, maxLines = 1, overflow = TextOverflow.Ellipsis)
    }
}

/**
 * The dots: past steps accent, the current one a 26 px accent bar with its name, later ones white 18%.
 * The bar grows into place on [ownTvTween], so moving on reads as progress without any flourish.
 */
@Composable
private fun StepDots(current: JourneyStep, steps: List<JourneyStep>, modifier: Modifier) {
    val a = stageAccent
    val at = steps.indexOf(current)
    Row(modifier, horizontalArrangement = Arrangement.spacedBy(if (steps.size > 6) 10.mpx else 14.mpx), verticalAlignment = Alignment.CenterVertically) {
        steps.forEachIndexed { i, s ->
            val on = s == current
            val reached = i <= at
            val barWidth by animateDpAsState(if (on) 26.mpx else 10.mpx, ownTvTween(240), label = "journeyDot")
            Row(horizontalArrangement = Arrangement.spacedBy(8.mpx), verticalAlignment = Alignment.CenterVertically) {
                Box(
                    Modifier
                        .size(width = barWidth, height = 10.mpx)
                        .background(if (reached) a.accent else StageColors.Ink.copy(alpha = 0.18f), RoundedCornerShape(5.mpx)),
                )
                if (on) Text(stringResource(s.labelRes), style = stageText(15, 700), color = StageColors.Text, maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
        }
    }
}

/** One choice card of a step (`.tile2`): icon, 23 px title, 16 px line; focused = FX. */
internal class WizardCard(val icon: OwnTVIcon, val title: String, val line: String, val onClick: () -> Unit)

/** The cards side by side, equal widths and heights, 16 apart; the first takes [firstFocus]. */
@Composable
internal fun WizardCards(cards: List<WizardCard>, firstFocus: FocusRequester? = null) {
    Row(Modifier.height(androidx.compose.foundation.layout.IntrinsicSize.Min), horizontalArrangement = Arrangement.spacedBy(16.mpx)) {
        cards.forEachIndexed { i, card ->
            StageSurface(
                onClick = card.onClick,
                radius = 22.mpx,
                focusStyle = StageFocus.FX,
                idle = Modifier.background(StageColors.Ink.copy(alpha = 0.05f), RoundedCornerShape(22.mpx)),
                modifier = Modifier
                    .weight(1f)
                    .fillMaxHeight()
                    .heightIn(min = 170.mpx)
                    .then(if (i == 0 && firstFocus != null) Modifier.focusRequester(firstFocus) else Modifier),
            ) { focused ->
                Column(Modifier.fillMaxSize().padding(24.mpx)) {
                    OwnTVIcon(card.icon, if (focused) StageColors.Text else StageColors.Muted, Modifier.size(30.mpx))
                    Text(card.title, style = stageText(23, 800), color = StageColors.Text, maxLines = 2, overflow = TextOverflow.Ellipsis, modifier = Modifier.padding(top = 18.mpx))
                    Text(card.line, style = stageText(16, 500).copy(lineHeight = 23.mpxLine()), color = StageColors.Muted, modifier = Modifier.padding(top = 6.mpx))
                }
            }
        }
    }
}

private val LockupTile = Color(0xFFEFE6D2)
private val LockupMark = Color(0xFF14535C)

@Composable
private fun Float.mpxLine() = with(androidx.compose.ui.platform.LocalDensity.current) { this@mpxLine.mpx.toSp() }

@Composable
private fun Int.mpxLine() = toFloat().mpxLine()

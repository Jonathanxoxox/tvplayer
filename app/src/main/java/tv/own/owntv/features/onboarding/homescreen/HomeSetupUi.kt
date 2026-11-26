package tv.own.owntv.features.onboarding.homescreen

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.snap
import androidx.compose.animation.core.spring
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.MutableState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.tv.material3.Text
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import tv.own.owntv.R
import tv.own.owntv.ui.components.OwnTVIcon
import tv.own.owntv.ui.theme.StageColors
import tv.own.owntv.ui.theme.animationsOn
import tv.own.owntv.ui.theme.mpx
import tv.own.owntv.ui.theme.mpxSp
import tv.own.owntv.ui.theme.ownTvTween
import tv.own.owntv.ui.theme.stageAccent
import tv.own.owntv.ui.theme.stageText

/** How often the box is asked again while the chapter is in front. */
private const val POLL_MS = 1_500L

/**
 * The box's current [HomeStatus], read when Mayo TV comes back to the front (e.g. from the
 * accessibility settings) and every [POLL_MS] while it stays there, so the status follows an ADB grant
 * or a Home app chosen on another page. Nothing is polled while the app is in the background.
 * Callers may write the state to refresh it at once.
 */
@Composable
internal fun rememberHomeStatus(): MutableState<HomeStatus> {
    val context = LocalContext.current.applicationContext
    val status = remember { mutableStateOf(HomeSetupSystem.read(context)) }
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    var resumed by remember { mutableStateOf(lifecycle.currentState.isAtLeast(Lifecycle.State.RESUMED)) }
    DisposableEffect(lifecycle) {
        val observer = LifecycleEventObserver { _, event ->
            when (event) {
                Lifecycle.Event.ON_RESUME -> resumed = true
                Lifecycle.Event.ON_PAUSE -> resumed = false
                else -> Unit
            }
        }
        lifecycle.addObserver(observer)
        onDispose { lifecycle.removeObserver(observer) }
    }
    LaunchedEffect(resumed) {
        while (resumed) {
            val next = withContext(Dispatchers.IO) { HomeSetupSystem.read(context) }
            if (next != status.value) status.value = next
            delay(POLL_MS)
        }
    }
    return status
}

/** One promise of the pitch: an icon in a soft accent disc, a title and one line. Not focusable. */
@Composable
internal fun BenefitRow(icon: OwnTVIcon, title: String, line: String) {
    val a = stageAccent
    Row(horizontalArrangement = Arrangement.spacedBy(20.mpx), verticalAlignment = Alignment.CenterVertically) {
        Box(
            Modifier.size(56.mpx).background(a.accent.copy(alpha = 0.16f), CircleShape),
            contentAlignment = Alignment.Center,
        ) { OwnTVIcon(icon, a.accent, Modifier.size(28.mpx)) }
        Column(Modifier.weight(1f)) {
            Text(title, style = stageText(21, 700), color = StageColors.Text)
            Text(line, style = stageText(17, 500).copy(lineHeight = 25.mpxSp), color = StageColors.Muted, modifier = Modifier.padding(top = 2.mpx))
        }
    }
}

/** A numbered instruction: the number in an accent disc, the text beside it. Not focusable. */
@Composable
internal fun NumberedStep(number: Int, text: String) {
    val a = stageAccent
    Row(horizontalArrangement = Arrangement.spacedBy(16.mpx), verticalAlignment = Alignment.Top) {
        Box(Modifier.size(34.mpx).background(a.accent, CircleShape), contentAlignment = Alignment.Center) {
            Text(number.toString(), style = stageText(17, 800), color = a.onAccent)
        }
        Text(
            text,
            style = stageText(18, 500).copy(lineHeight = 26.mpxSp),
            color = StageColors.Body,
            modifier = Modifier.weight(1f).padding(top = 3.mpx),
        )
    }
}

/** A short line of guidance under the steps: [warn] draws it in the amber of a gentle warning. */
@Composable
internal fun HintLine(text: String, warn: Boolean = false, modifier: Modifier = Modifier) {
    Text(
        text,
        style = stageText(16, if (warn) 600 else 500).copy(lineHeight = 23.mpxSp),
        color = if (warn) StageColors.Warn else StageColors.Muted,
        modifier = modifier,
    )
}

/** A command to type on a computer, in a quiet code box. */
@Composable
internal fun CommandLine(command: String) {
    Text(
        command,
        style = stageText(16, 500).copy(fontFamily = FontFamily.Monospace, lineHeight = 23.mpxSp),
        color = StageColors.Text,
        modifier = Modifier
            .fillMaxWidth()
            .background(StageColors.Ink.copy(alpha = 0.08f), RoundedCornerShape(12.mpx))
            .padding(horizontal = 16.mpx, vertical = 12.mpx),
    )
}

/**
 * The live status: "Not active yet" in a quiet card, flipping to a green "Active" with a check that
 * pops in once (instant with animations off) when the box reports the Home button as Mayo TV's.
 */
@Composable
internal fun StatusCard(status: HomeStatus, modifier: Modifier = Modifier) {
    val active = status.active
    val tone = if (active) StageColors.Ok else StageColors.Muted
    val fill by animateColorAsState(
        if (active) StageColors.Ok.copy(alpha = 0.14f) else StageColors.Ink.copy(alpha = 0.05f),
        ownTvTween(300),
        label = "homeStatusFill",
    )
    val pop by animateFloatAsState(
        if (active) 1f else 0.82f,
        if (animationsOn) spring<Float>(dampingRatio = 0.45f, stiffness = Spring.StiffnessLow) else snap<Float>(),
        label = "homeStatusPop",
    )
    Row(
        modifier.fillMaxWidth().background(fill, RoundedCornerShape(22.mpx)).padding(horizontal = 22.mpx, vertical = 20.mpx),
        horizontalArrangement = Arrangement.spacedBy(18.mpx),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(
            Modifier
                .size(52.mpx)
                .graphicsLayer { scaleX = pop; scaleY = pop }
                .background(tone.copy(alpha = 0.18f), CircleShape),
            contentAlignment = Alignment.Center,
        ) {
            OwnTVIcon(if (active) OwnTVIcon.CHECK else OwnTVIcon.CLOCK, tone, Modifier.size(26.mpx))
        }
        Column(Modifier.weight(1f)) {
            Text(
                stringResource(if (active) R.string.onboarding_home_screen_status_on else R.string.onboarding_home_screen_status_off),
                style = stageText(if (active) 26 else 21, 800),
                color = if (active) StageColors.Ok else StageColors.Text,
            )
            val line = when {
                status.guardOn -> R.string.onboarding_home_screen_status_on_guard
                status.defaultHome -> R.string.onboarding_home_screen_status_on_home
                else -> R.string.onboarding_home_screen_status_off_line
            }
            Text(stringResource(line), style = stageText(16, 500).copy(lineHeight = 23.mpxSp), color = StageColors.Muted, modifier = Modifier.padding(top = 2.mpx))
        }
    }
}

package tv.own.owntv.features.onboarding

import androidx.activity.compose.BackHandler
import androidx.compose.animation.core.Animatable
import androidx.compose.foundation.focusable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.tv.material3.Text
import tv.own.owntv.R
import tv.own.owntv.features.setup.MayoLockup
import tv.own.owntv.ui.stage.stageBackground
import tv.own.owntv.ui.theme.StageColors
import tv.own.owntv.ui.theme.animationsOn
import tv.own.owntv.ui.theme.mpx
import tv.own.owntv.ui.theme.ownTvTween
import tv.own.owntv.ui.theme.stageAccent
import tv.own.owntv.ui.theme.stageText

/**
 * The brand intro on a true first run, before the setup wizard. Calls [onFinished] exactly once:
 * when the animation ends, or at once on any key (OK, the D-pad, Back), which skips it.
 *
 * It must never hold up the journey: with motion off (the app's Animations setting or the system
 * animator scale at 0, as on test emulators) it shows the lockup only briefly and finishes on its
 * own. The sound logo honours [OnboardingPrefs.state]'s `introMuted`.
 */
@Composable
fun MayoIntro(onFinished: () -> Unit, modifier: Modifier = Modifier) {
    val finish by rememberUpdatedFinish(onFinished)
    val motion = animationsOn && systemAnimatorScale() > 0f
    val focus = remember { FocusRequester() }
    val reveal = remember { Animatable(if (motion) 0f else 1f) }
    val revealSpec = ownTvTween<Float>(REVEAL_MS)

    BackHandler { finish() }
    LaunchedEffect(Unit) {
        runCatching { focus.requestFocus() }
        if (motion) reveal.animateTo(1f, revealSpec)
        kotlinx.coroutines.delay(if (motion) HOLD_MS else STILL_MS)
        finish()
    }

    Box(
        modifier
            .fillMaxSize()
            .stageBackground(stageAccent.accent)
            .focusRequester(focus)
            .onPreviewKeyEvent { event ->
                if (event.type == KeyEventType.KeyDown) finish()
                true
            }
            .focusable(),
        contentAlignment = Alignment.Center,
    ) {
        MayoLockup(
            Modifier.graphicsLayer {
                alpha = reveal.value
                val s = 0.94f + 0.06f * reveal.value
                scaleX = s
                scaleY = s
            },
            scale = 2.2f,
        )
        Text(
            stringResource(R.string.onboarding_intro_skip_hint),
            style = stageText(16, 600),
            color = StageColors.Dim,
            modifier = Modifier.align(Alignment.BottomCenter).padding(bottom = 56.mpx),
        )
    }
}

/** A single-shot wrapper: the first call runs [onFinished], every later one is ignored. */
@Composable
private fun rememberUpdatedFinish(onFinished: () -> Unit): androidx.compose.runtime.State<() -> Unit> {
    val latest = rememberUpdatedState(onFinished)
    return remember {
        var done = false
        androidx.compose.runtime.mutableStateOf({
            if (!done) {
                done = true
                latest.value()
            }
        })
    }
}

@Composable
private fun systemAnimatorScale(): Float {
    val context = LocalContext.current
    return remember(context) {
        runCatching {
            android.provider.Settings.Global.getFloat(context.contentResolver, android.provider.Settings.Global.ANIMATOR_DURATION_SCALE, 1f)
        }.getOrDefault(1f)
    }
}

private const val REVEAL_MS = 700
private const val HOLD_MS = 1_300L
/** Without motion: long enough to register the name, short enough never to feel like a wait. */
private const val STILL_MS = 800L

package tv.own.owntv.features.onboarding

import androidx.activity.compose.BackHandler
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.focusable
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.State
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Alignment
import androidx.compose.ui.BiasAlignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.CompositingStrategy
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.LayoutDirection
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.tv.material3.Text
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import org.koin.compose.koinInject
import tv.own.owntv.R
import tv.own.owntv.features.onboarding.intro.IntroScene
import tv.own.owntv.features.onboarding.intro.IntroSound
import tv.own.owntv.features.onboarding.intro.IntroTextures
import tv.own.owntv.features.onboarding.intro.IntroTimeline
import tv.own.owntv.features.onboarding.intro.LOGO_Y
import tv.own.owntv.features.onboarding.intro.TAGLINE_Y
import tv.own.owntv.features.setup.MayoLockup
import tv.own.owntv.ui.stage.stageBackground
import tv.own.owntv.ui.theme.LocalStagePalette
import tv.own.owntv.ui.theme.animationsOn
import tv.own.owntv.ui.theme.mpx
import tv.own.owntv.ui.theme.stageAccent
import tv.own.owntv.ui.theme.stageText

/**
 * The brand intro on a true first run, before the setup wizard (timeline in [IntroTimeline]).
 *
 * A stadium at night: four floodlights strike one after another, their beams swing in and meet in the
 * middle, and the Mayo TV lockup appears in the light with the sound logo's two-note motif, followed by
 * the tagline. Then the night dissolves into the setup page's background while the lockup glides to the
 * page's top corner, exactly where the wizard draws it, so the first setup page takes over seamlessly.
 *
 * Calls [onFinished] exactly once: at the end, or after a quick fade when any key is pressed (OK, the
 * D-pad, Back). The volume keys are left to the box; Mute also silences the sound logo and is
 * remembered. It never holds up the journey: with motion off (the app's Animations setting or the
 * system animator scale at 0, as on test emulators) it shows the finished picture briefly and goes on;
 * if drawing fails it goes on at once; and a wall-clock limit backs up the frame clock. While the
 * activity is paused, picture and sound wait and then continue together.
 */
@Composable
fun MayoIntro(onFinished: () -> Unit, modifier: Modifier = Modifier) {
    val context = LocalContext.current
    val prefs = koinInject<OnboardingPrefs>()
    val finish by rememberUpdatedFinish(onFinished)
    val motion = animationsOn && systemAnimatorScale() > 0f
    val focus = remember { FocusRequester() }
    val scope = rememberCoroutineScope()

    val scene = remember { runCatching { IntroScene(IntroTextures.create()) }.getOrNull() }
    val sound = remember { IntroSound(context) }
    val run = remember { IntroRun() }
    // Seconds into the timeline; read only while drawing, so a frame redraws without recomposing.
    var clock by remember { mutableFloatStateOf(if (motion) 0f else IntroTimeline.STILL_FRAME) }
    // 1 while shown, down to 0 on a skip: the scene dissolves into the page behind it.
    val exit = remember { Animatable(1f) }
    var broken by remember { mutableStateOf(scene == null) }
    var leaving by remember { mutableStateOf(false) }
    val resumed by rememberResumed()

    DisposableEffect(sound) { onDispose { sound.close() } }

    val skip: () -> Unit = {
        if (!leaving) {
            leaving = true
            if (!motion) {
                sound.silence()
                finish()
            } else {
                scope.launch {
                    try {
                        coroutineScope {
                            launch { sound.fadeOut(SKIP_FADE_MS) }
                            exit.animateTo(0f, tween(SKIP_FADE_MS, easing = LinearEasing))
                        }
                    } finally {
                        finish()
                    }
                }
            }
        }
    }

    BackHandler { skip() }

    LaunchedEffect(Unit) {
        runCatching { focus.requestFocus() }
    }

    LaunchedEffect(broken) {
        if (broken) {
            sound.close()
            finish()
        }
    }

    // The frame clock, with the sound started at the same moment so the clunks land on the lamps.
    LaunchedEffect(resumed, motion) {
        if (!resumed || broken) return@LaunchedEffect
        if (!motion) {
            delay(STILL_MS)
            finish()
            return@LaunchedEffect
        }
        if (!run.soundTried) {
            run.soundTried = true
            if (!prefs.state.value.introMuted) {
                // A box that is slow to open the file starts the show silent rather than late.
                if (withTimeoutOrNull(SOUND_LOAD_TIMEOUT_MS) { sound.load() } != true) sound.close()
            }
        }
        if (!leaving) sound.playFrom((clock * 1000f).toLong())
        try {
            var last = -1L
            while (clock < IntroTimeline.TOTAL) {
                withFrameNanos { now ->
                    if (last >= 0L) clock = (clock + (now - last) / 1_000_000_000f).coerceAtMost(IntroTimeline.TOTAL)
                    last = now
                }
            }
        } finally {
            sound.pause()
        }
        finish()
    }

    // Backstop: whatever happens to the frame clock, the intro ends a little after it should have.
    LaunchedEffect(resumed) {
        if (!resumed) return@LaunchedEffect
        val left = if (motion) IntroTimeline.TOTAL - clock else STILL_MS / 1000f
        delay((left * 1000f).toLong().coerceAtLeast(0L) + BACKSTOP_SLACK_MS)
        finish()
    }

    val density = LocalDensity.current
    val rtl = LocalLayoutDirection.current == LayoutDirection.Rtl
    // The lockup glides to the wizard's corner over the dark page; on the light page it would end up
    // pale on white, so there it fades out with the scene instead.
    val glide = !LocalStagePalette.current.isLight
    val lockupStart = with(density) { LOCKUP_START.mpx.toPx() }
    val lockupTop = with(density) { LOCKUP_TOP.mpx.toPx() }
    val taglineRise = with(density) { 16.mpx.toPx() }

    BoxWithConstraints(
        modifier
            .fillMaxSize()
            .stageBackground(stageAccent.accent)
            .focusRequester(focus)
            .onPreviewKeyEvent { event ->
                when (event.key) {
                    // The box's own volume; not a reason to skip.
                    Key.VolumeUp, Key.VolumeDown -> false
                    Key.VolumeMute -> {
                        if (event.type == KeyEventType.KeyDown) {
                            sound.silence()
                            prefs.setIntroMuted(true)
                        }
                        false
                    }
                    else -> {
                        if (event.type == KeyEventType.KeyDown) skip()
                        true
                    }
                }
            }
            .focusable(),
    ) {
        val width = constraints.maxWidth.toFloat()
        val height = constraints.maxHeight.toFloat()

        if (scene != null) {
            Canvas(Modifier.fillMaxSize()) {
                try {
                    val t = clock
                    with(scene) { drawScene(t, IntroTimeline.scene(t) * exit.value) }
                } catch (e: Exception) {
                    broken = true
                }
            }
        }

        MayoLockup(
            Modifier
                .align(Alignment.TopStart)
                .padding(start = LOCKUP_START.mpx, top = LOCKUP_TOP.mpx)
                .graphicsLayer {
                    val t = clock
                    val w = size.width
                    val h = size.height
                    val reveal = IntroTimeline.logo(t)
                    val move = if (glide) IntroTimeline.handover(t) else 0f
                    // From the middle of the screen (where the beams meet) to the wizard's corner.
                    val x0 = if (rtl) width - lockupStart - w else lockupStart
                    val dx = width / 2f - (x0 + w / 2f)
                    val dy = height * LOGO_Y - (lockupTop + h / 2f)
                    val big = LOGO_SCALE * (0.92f + 0.08f * reveal)
                    val s = big + (1f - big) * move
                    translationX = dx * (1f - move)
                    translationY = dy * (1f - move)
                    scaleX = s
                    scaleY = s
                    val leave = if (glide) 1f else 1f - IntroTimeline.handover(t)
                    alpha = reveal * leave * exit.value
                    compositingStrategy = CompositingStrategy.ModulateAlpha
                },
        )

        Text(
            stringResource(R.string.onboarding_intro_tagline),
            style = stageText(32, 600),
            color = Tagline,
            textAlign = TextAlign.Center,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier
                .align(BiasAlignment(0f, TAGLINE_Y * 2f - 1f))
                .fillMaxWidth()
                .padding(horizontal = 120.mpx)
                .graphicsLayer {
                    val t = clock
                    val shown = IntroTimeline.tagline(t)
                    alpha = shown * IntroTimeline.textOut(t) * exit.value
                    translationY = (1f - shown) * taglineRise
                    compositingStrategy = CompositingStrategy.ModulateAlpha
                },
        )

        Text(
            stringResource(R.string.onboarding_intro_skip_hint),
            style = stageText(19, 600),
            color = Hint,
            modifier = Modifier
                .align(Alignment.BottomCenter)
                .padding(bottom = 56.mpx)
                .graphicsLayer {
                    val t = clock
                    alpha = IntroTimeline.hint(t) * IntroTimeline.textOut(t) * exit.value
                    compositingStrategy = CompositingStrategy.ModulateAlpha
                }
                // A dark pill keeps the hint readable over the pitch's lit centre circle and halfway line.
                .background(HintBackdrop, RoundedCornerShape(percent = 50))
                .padding(horizontal = 20.mpx, vertical = 8.mpx),
        )
    }
}

/** What happened once per intro and must survive recomposition without causing one. */
private class IntroRun {
    var soundTried = false
}

/** A single-shot wrapper: the first call runs [onFinished], every later one is ignored. */
@Composable
private fun rememberUpdatedFinish(onFinished: () -> Unit): State<() -> Unit> {
    val latest = rememberUpdatedState(onFinished)
    return remember {
        var done = false
        mutableStateOf({
            if (!done) {
                done = true
                latest.value()
            }
        })
    }
}

/** True while the activity is resumed; the intro waits, picture and sound, while it is not. */
@Composable
private fun rememberResumed(): State<Boolean> {
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    val resumed = remember(lifecycle) { mutableStateOf(lifecycle.currentState.isAtLeast(Lifecycle.State.RESUMED)) }
    DisposableEffect(lifecycle) {
        val observer = LifecycleEventObserver { _, event ->
            when (event) {
                Lifecycle.Event.ON_RESUME -> resumed.value = true
                Lifecycle.Event.ON_PAUSE -> resumed.value = false
                else -> Unit
            }
        }
        lifecycle.addObserver(observer)
        onDispose { lifecycle.removeObserver(observer) }
    }
    return resumed
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

/** The wizard's lockup position (JourneyFrame), where the intro's lockup comes to rest. */
private const val LOCKUP_START = 64
private const val LOCKUP_TOP = 56

/** How large the lockup is while the beams meet on it, relative to the wizard's. */
private const val LOGO_SCALE = 2.8f

private const val SKIP_FADE_MS = 220

/** Without motion: long enough to register the name, short enough never to feel like a wait. */
private const val STILL_MS = 700L

/** How long the sound logo may take to open before the show starts without it. */
private const val SOUND_LOAD_TIMEOUT_MS = 600L

private const val BACKSTOP_SLACK_MS = SOUND_LOAD_TIMEOUT_MS + 1_200L

/** Floodlit cream for the tagline; the scene is always night, whatever the theme. */
private val Tagline = Color(0xFFF6E8CA)
private val Hint = Color.White.copy(alpha = 0.72f)
private val HintBackdrop = Color.Black.copy(alpha = 0.38f)

package tv.own.owntv.features.onboarding

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.core.snap
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import org.koin.compose.koinInject
import tv.own.owntv.features.setup.LocalFirstRunJourney
import tv.own.owntv.features.setup.Onboarding
import tv.own.owntv.ui.theme.ownTvTween

/**
 * The first-run journey: INTRO → the setup wizard → APPEARANCE → HOME_SCREEN → FOOTBALL → EXTRAS →
 * READY, then the shell. Only MainActivity composes it, for a true first run (no active profile yet)
 * and for the chapters after the profile while [OnboardingState.postProfilePending] is true. "Add
 * profile" never comes here: it keeps using the wizard alone.
 *
 * Which chapter shows comes from [OnboardingPrefs] read through [OnboardingJourney.resolve], so the
 * journey resumes where it was after a restart. [hasActiveProfile] is MainActivity's view of whether an
 * active profile exists and is known to Room.
 *
 * [onSetupDone] receives the wizard's result (the new profile's id, or null after a restore or a
 * sync, when the profile gate decides) so MainActivity can authenticate it. The chapters after the
 * profile run once that has happened; finishing READY completes the journey and MainActivity shows
 * the shell on its own.
 *
 * Chapters change with a short fade and a slight slide in the direction of travel (a plain fade
 * after the intro), instant when animations are off.
 */
@Composable
fun OnboardingFlow(
    hasActiveProfile: Boolean,
    onSetupDone: (profileId: Long?) -> Unit,
    modifier: Modifier = Modifier,
) {
    val prefs: OnboardingPrefs = koinInject()
    val stored by prefs.state.collectAsStateWithLifecycle()
    // A true first run starts here; a journey already under way is left where it is.
    LaunchedEffect(Unit) { prefs.begin() }
    val chapter = OnboardingJourney.resolve(stored, hasActiveProfile).chapter
    // Back from HOME_SCREEN returns to the page the user left APPEARANCE from (its second page).
    var appearanceFromBack by rememberSaveable { mutableStateOf(false) }

    val fade = ownTvTween<Float>(CHAPTER_FADE_MS)
    val slide = ownTvTween<androidx.compose.ui.unit.IntOffset>(CHAPTER_FADE_MS)
    CompositionLocalProvider(LocalFirstRunJourney provides true) {
        AnimatedContent(
            targetState = chapter,
            modifier = modifier.fillMaxSize(),
            transitionSpec = {
                val forward = targetState.ordinal >= initialState.ordinal
                // The intro hands its lockup over to the wizard's at the exact same spot, so that
                // step fades without the slide.
                val fromIntro = initialState == OnboardingChapter.INTRO
                // The new chapter fades in over the old one, which stays opaque until then: both draw
                // the full Stage background, and fading both at once would dip it in the middle.
                val enter = if (fromIntro) {
                    fadeIn(fade)
                } else {
                    fadeIn(fade) + slideInHorizontally(slide) { w -> (if (forward) w else -w) / SLIDE_FRACTION }
                }
                enter togetherWith fadeOut(snap(delayMillis = fade.durationMillis))
            },
            label = "onboardingChapter",
        ) { shown ->
            Box(Modifier.fillMaxSize()) {
                when (shown) {
                    OnboardingChapter.INTRO -> MayoIntro(
                        onFinished = {
                            prefs.markIntroSeen()
                            prefs.finishChapter(OnboardingChapter.INTRO)
                        },
                    )
                    OnboardingChapter.SETUP -> Onboarding(
                        firstRun = true,
                        onDone = { profileId ->
                            // Recorded before the gate hears of the profile, so the shell never
                            // appears between the wizard and the chapters after it.
                            prefs.finishChapter(OnboardingChapter.SETUP)
                            onSetupDone(profileId)
                        },
                        onCancel = {},
                        modifier = Modifier.fillMaxSize(),
                    )
                    OnboardingChapter.APPEARANCE -> AppearanceChapter(
                        onNext = {
                            appearanceFromBack = false
                            prefs.finishChapter(OnboardingChapter.APPEARANCE)
                        },
                        // Never undoes the profile: the frame sends the app to the background instead.
                        onBack = null,
                        startOnLook = appearanceFromBack,
                    )
                    OnboardingChapter.HOME_SCREEN -> HomeScreenChapter(
                        onNext = { prefs.finishChapter(OnboardingChapter.HOME_SCREEN) },
                        onBack = {
                            appearanceFromBack = true
                            prefs.backFrom(OnboardingChapter.HOME_SCREEN)
                        },
                    )
                    OnboardingChapter.FOOTBALL -> FootballChapter(
                        onNext = { prefs.finishChapter(OnboardingChapter.FOOTBALL) },
                        onBack = { prefs.backFrom(OnboardingChapter.FOOTBALL) },
                    )
                    OnboardingChapter.EXTRAS -> ExtrasChapter(
                        onNext = { prefs.finishChapter(OnboardingChapter.EXTRAS) },
                        onBack = { prefs.backFrom(OnboardingChapter.EXTRAS) },
                    )
                    OnboardingChapter.READY -> ReadyChapter(
                        onNext = { prefs.finishChapter(OnboardingChapter.READY) },
                        onBack = { prefs.backFrom(OnboardingChapter.READY) },
                    )
                }
            }
        }
    }
}

private const val CHAPTER_FADE_MS = 260
/** The slide covers 1/24 of the width: a hint of direction, not a page flip. */
private const val SLIDE_FRACTION = 24

package tv.own.owntv.features.onboarding

import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.unit.Dp
import tv.own.owntv.features.setup.JourneyFrame
import tv.own.owntv.features.setup.JourneyStep
import tv.own.owntv.features.setup.WizardAction
import tv.own.owntv.ui.theme.mpx

/**
 * One footer button of a chapter: its label, what OK on it does, an optional focus requester (pass
 * [rememberChapterFocus] to make it the initial focus) and whether it can be pressed.
 */
typealias ChapterAction = WizardAction

/** The progress dot a chapter lights. */
val OnboardingChapter.journeyStep: JourneyStep
    get() = when (this) {
        // The intro draws no frame; the wizard's pages light their own steps.
        OnboardingChapter.INTRO, OnboardingChapter.SETUP -> JourneyStep.LANGUAGE
        OnboardingChapter.APPEARANCE -> JourneyStep.APPEARANCE
        OnboardingChapter.HOME_SCREEN -> JourneyStep.HOME_SCREEN
        OnboardingChapter.FOOTBALL -> JourneyStep.FOOTBALL
        OnboardingChapter.EXTRAS -> JourneyStep.EXTRAS
        OnboardingChapter.READY -> JourneyStep.READY
    }

/**
 * The frame of every post-profile chapter: one family with the setup wizard's pages (same lockup,
 * the same progress dots over the whole journey, "Step n of 9", the glass panel, title and
 * [subtitle]). The footer holds [secondary] (normally Back) on the left, [skip] and [primary] on the
 * right.
 *
 * Back on the remote calls [onBack], which defaults to [secondary]'s action; pass null where Back
 * must not move (the first chapter after the profile). The body is a column with 6 mpx between
 * rows; put [tv.own.owntv.features.settings.StageSettingRow]s, cards or a custom layout there.
 *
 * Initial focus: give exactly one control a [rememberChapterFocus] requester, usually the first row
 * of the body or, on a chapter without rows, [primary].
 */
@Composable
fun ChapterFrame(
    chapter: OnboardingChapter,
    title: String,
    subtitle: String?,
    primary: ChapterAction?,
    secondary: ChapterAction? = null,
    skip: ChapterAction? = null,
    onBack: (() -> Unit)? = secondary?.onClick,
    width: Dp = 1040.mpx,
    body: @Composable ColumnScope.() -> Unit = {},
) {
    JourneyFrame(
        step = chapter.journeyStep,
        steps = JourneyStep.entries,
        title = title,
        sub = subtitle,
        back = secondary,
        next = primary,
        onBack = onBack,
        width = width,
        skip = skip,
        showPosition = true,
        body = body,
    )
}

/**
 * A focus requester that takes focus once the chapter is on screen. The short delay lets the
 * chapter transition attach the new content before focus moves into it.
 */
@Composable
fun rememberChapterFocus(): FocusRequester {
    val requester = remember { FocusRequester() }
    LaunchedEffect(Unit) {
        kotlinx.coroutines.delay(FIRST_FOCUS_DELAY_MS)
        runCatching { requester.requestFocus() }
    }
    return requester
}

private const val FIRST_FOCUS_DELAY_MS = 60L

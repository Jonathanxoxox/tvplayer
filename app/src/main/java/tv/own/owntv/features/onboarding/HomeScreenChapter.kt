package tv.own.owntv.features.onboarding

import androidx.compose.runtime.Composable
import androidx.compose.ui.res.stringResource
import tv.own.owntv.R

/**
 * Mayo TV as the box's start screen: the Home button, the accessibility Home guard and power-on.
 * Recommended but skippable; never a dead end on boxes that refuse it. The user may leave for the
 * system's settings here: the journey resumes on this chapter even after the app was killed.
 *
 * [onNext] moves on to the next chapter (after this chapter has saved what the user chose);
 * [onBack] returns to the previous chapter, or is null where Back must not move (see [ChapterFrame]).
 */
@Composable
fun HomeScreenChapter(onNext: () -> Unit, onBack: (() -> Unit)?) {
    ChapterFrame(
        chapter = OnboardingChapter.HOME_SCREEN,
        title = stringResource(R.string.onboarding_home_screen_title),
        subtitle = stringResource(R.string.onboarding_home_screen_sub),
        primary = ChapterAction(stringResource(R.string.onboarding_action_continue), onNext, rememberChapterFocus()),
        secondary = onBack?.let { ChapterAction(stringResource(R.string.common_back), it) },
        skip = ChapterAction(stringResource(R.string.onboarding_action_skip), onNext),
    )
}

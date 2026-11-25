package tv.own.owntv.features.onboarding

import androidx.compose.runtime.Composable
import androidx.compose.ui.res.stringResource
import tv.own.owntv.R

/**
 * The first chapter after the profile: the Home layout and everything about how surfaces look.
 * Back does not lead into the wizard again (the profile stays), so [onBack] is null here.
 *
 * [onNext] moves on to the next chapter (after this chapter has saved what the user chose);
 * [onBack] returns to the previous chapter, or is null where Back must not move (see [ChapterFrame]).
 */
@Composable
fun AppearanceChapter(onNext: () -> Unit, onBack: (() -> Unit)?) {
    ChapterFrame(
        chapter = OnboardingChapter.APPEARANCE,
        title = stringResource(R.string.onboarding_appearance_title),
        subtitle = stringResource(R.string.onboarding_appearance_sub),
        primary = ChapterAction(stringResource(R.string.onboarding_action_continue), onNext, rememberChapterFocus()),
        secondary = onBack?.let { ChapterAction(stringResource(R.string.common_back), it) },
    )
}

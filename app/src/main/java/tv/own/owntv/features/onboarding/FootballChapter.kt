package tv.own.owntv.features.onboarding

import androidx.compose.runtime.Composable
import androidx.compose.ui.res.stringResource
import tv.own.owntv.R

/**
 * Favourite teams and leagues, chosen by the user instead of built-in defaults.
 *
 * [onNext] moves on to the next chapter (after this chapter has saved what the user chose);
 * [onBack] returns to the previous chapter, or is null where Back must not move (see [ChapterFrame]).
 */
@Composable
fun FootballChapter(onNext: () -> Unit, onBack: (() -> Unit)?) {
    ChapterFrame(
        chapter = OnboardingChapter.FOOTBALL,
        title = stringResource(R.string.onboarding_football_title),
        subtitle = stringResource(R.string.onboarding_football_sub),
        primary = ChapterAction(stringResource(R.string.onboarding_action_continue), onNext, rememberChapterFocus()),
        secondary = onBack?.let { ChapterAction(stringResource(R.string.common_back), it) },
        skip = ChapterAction(stringResource(R.string.onboarding_action_skip), onNext),
    )
}

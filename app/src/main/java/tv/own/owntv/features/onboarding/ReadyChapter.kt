package tv.own.owntv.features.onboarding

import androidx.compose.runtime.Composable
import androidx.compose.ui.res.stringResource
import tv.own.owntv.R

/**
 * The last page of the journey: a short summary, then into the app. [onNext] completes the journey.
 *
 * [onNext] moves on to the next chapter (after this chapter has saved what the user chose);
 * [onBack] returns to the previous chapter, or is null where Back must not move (see [ChapterFrame]).
 */
@Composable
fun ReadyChapter(onNext: () -> Unit, onBack: (() -> Unit)?) {
    ChapterFrame(
        chapter = OnboardingChapter.READY,
        title = stringResource(R.string.onboarding_ready_title),
        subtitle = stringResource(R.string.onboarding_ready_sub),
        primary = ChapterAction(stringResource(R.string.onboarding_action_done), onNext, rememberChapterFocus()),
        secondary = onBack?.let { ChapterAction(stringResource(R.string.common_back), it) },
    )
}

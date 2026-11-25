package tv.own.owntv.features.onboarding

import androidx.compose.runtime.Composable
import androidx.compose.ui.res.stringResource
import tv.own.owntv.R

/**
 * Optional keys of the user's own (TMDB, ElevenLabs, TypeSafe), entered by phone or with the remote,
 * stored through [tv.own.owntv.features.keys.ApiKeys].
 *
 * [onNext] moves on to the next chapter (after this chapter has saved what the user chose);
 * [onBack] returns to the previous chapter, or is null where Back must not move (see [ChapterFrame]).
 */
@Composable
fun ExtrasChapter(onNext: () -> Unit, onBack: (() -> Unit)?) {
    ChapterFrame(
        chapter = OnboardingChapter.EXTRAS,
        title = stringResource(R.string.onboarding_extras_title),
        subtitle = stringResource(R.string.onboarding_extras_sub),
        primary = ChapterAction(stringResource(R.string.onboarding_action_continue), onNext, rememberChapterFocus()),
        secondary = onBack?.let { ChapterAction(stringResource(R.string.common_back), it) },
        skip = ChapterAction(stringResource(R.string.onboarding_action_skip), onNext),
    )
}

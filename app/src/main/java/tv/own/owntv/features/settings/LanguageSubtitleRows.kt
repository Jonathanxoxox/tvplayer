package tv.own.owntv.features.settings

import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import org.koin.compose.koinInject
import org.koin.androidx.compose.koinViewModel
import tv.own.owntv.R
import tv.own.owntv.core.i18n.SupportedLocales
import tv.own.owntv.core.settings.SettingsRepository
import tv.own.owntv.core.subtitles.OpenSubtitlesAccountManager
import tv.own.owntv.ui.components.OwnTVIcon
import tv.own.owntv.ui.format.localizedInteger

/** The full pages a Sprache & Untertitel row opens. The Settings screen owns the pages themselves. */
internal enum class LanguagePage { APP_LANGUAGE, OPEN_SUBTITLES, DOWNLOADED_SUBTITLES }

/**
 * How the Sprache & Untertitel rows open their pages, and the focus each row takes back when its page
 * closes. Provided by the Settings screen; without it (a gathering page) the rows do nothing.
 */
internal class LanguagePageLinks(
    val open: (LanguagePage) -> Unit,
    val focus: (LanguagePage) -> FocusRequester,
)

internal val LocalLanguagePageLinks = androidx.compose.runtime.compositionLocalOf<LanguagePageLinks?> { null }

@Composable
private fun linkFocus(page: LanguagePage): Modifier =
    LocalLanguagePageLinks.current?.focus?.invoke(page)?.let { Modifier.focusRequester(it) } ?: Modifier

/** Settings › Sprache & Untertitel › Sprache: the app's own language, first above the audio and subtitle language. */
@Composable
internal fun AppLanguageRow() {
    val links = LocalLanguagePageLinks.current
    val languageVm: LanguageSettingsViewModel = koinViewModel()
    val tag by languageVm.currentTag.collectAsStateWithLifecycle()
    val name = SupportedLocales.all.find { tag.isNotEmpty() && it.languageTag == tag }?.endonym
        ?: stringResource(R.string.settings_language_system_default)
    KeyedSettingRow(
        icon = OwnTVIcon.LANGUAGE,
        title = stringResource(R.string.settings_pb_app_language),
        desc = stringResource(R.string.settings_pb_line_app_language),
        value = SettingValue.Opens(name),
        helpKey = "tab_LANGUAGE",
        modifier = linkFocus(LanguagePage.APP_LANGUAGE),
        onClick = { links?.open?.invoke(LanguagePage.APP_LANGUAGE) },
    )
}

/**
 * Settings › Sprache & Untertitel › Untertitel, after Subtitle appearance: the OpenSubtitles account
 * and the subtitles downloaded with it — each a page of its own, one level deep.
 */
@Composable
internal fun SubtitleAccountRows() {
    val links = LocalLanguagePageLinks.current
    // The stored session only, so drawing the row never calls the provider (the account page refreshes it).
    val accounts: OpenSubtitlesAccountManager = koinInject()
    val settings: SettingsRepository = koinInject()
    val profileId by settings.activeProfileId.collectAsStateWithLifecycle(-1L)
    val sessions by accounts.sessions.collectAsStateWithLifecycle()
    val signedIn = remember(profileId, sessions) { profileId >= 0 && accounts.session(profileId) != null }
    KeyedSettingRow(
        icon = OwnTVIcon.SUBTITLE,
        title = stringResource(R.string.settings_open_subtitles),
        desc = stringResource(R.string.settings_line_open_subtitles),
        value = SettingValue.Opens(stringResource(if (signedIn) R.string.settings_pb_os_signed_in else R.string.settings_pb_os_signed_out)),
        helpKey = "tab_OPEN_SUBTITLES",
        modifier = linkFocus(LanguagePage.OPEN_SUBTITLES),
        onClick = { links?.open?.invoke(LanguagePage.OPEN_SUBTITLES) },
    )
    val deleteVm: DeleteSubtitlesViewModel = koinViewModel()
    val movies by deleteVm.movieCount.collectAsStateWithLifecycle()
    val series by deleteVm.seriesCount.collectAsStateWithLifecycle()
    val downloaded = movies + series
    KeyedSettingRow(
        icon = OwnTVIcon.TRASH,
        title = stringResource(R.string.settings_pb_downloaded_subtitles),
        desc = pluralStringResource(R.plurals.settings_subtitles_downloaded_count, downloaded, downloaded),
        value = SettingValue.Opens(localizedInteger(downloaded, grouping = false)),
        helpKey = "tab_DELETE_SUBTITLES",
        modifier = linkFocus(LanguagePage.DOWNLOADED_SUBTITLES),
        onClick = { links?.open?.invoke(LanguagePage.DOWNLOADED_SUBTITLES) },
    )
}

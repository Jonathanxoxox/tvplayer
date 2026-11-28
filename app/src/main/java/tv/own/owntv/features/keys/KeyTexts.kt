package tv.own.owntv.features.keys

import androidx.compose.runtime.Composable
import androidx.compose.ui.res.stringResource
import tv.own.owntv.R
import tv.own.owntv.ui.components.OwnTVIcon

/*
 * The words and marks for each service, shared by Settings › API keys and the first-run EXTRAS chapter,
 * so both say the same thing about a key.
 */

/** The service's name ("TMDB"). */
val ApiService.nameRes: Int
    get() = when (this) {
        ApiService.TMDB -> R.string.keys_tmdb_name
        ApiService.ELEVENLABS -> R.string.keys_elevenlabs_name
        ApiService.TYPESAFE -> R.string.keys_typesafe_name
    }

/** One short line: what the key is for ("Search by description"). */
val ApiService.lineRes: Int
    get() = when (this) {
        ApiService.TMDB -> R.string.keys_tmdb_line
        ApiService.ELEVENLABS -> R.string.keys_elevenlabs_line
        ApiService.TYPESAFE -> R.string.keys_typesafe_line
    }

/** What the key switches on, and what happens without it. */
val ApiService.unlocksRes: Int
    get() = when (this) {
        ApiService.TMDB -> R.string.keys_tmdb_unlocks
        ApiService.ELEVENLABS -> R.string.keys_elevenlabs_unlocks
        ApiService.TYPESAFE -> R.string.keys_typesafe_unlocks
    }

/** Where to get a key, and what it costs. */
val ApiService.whereRes: Int
    get() = when (this) {
        ApiService.TMDB -> R.string.keys_tmdb_where
        ApiService.ELEVENLABS -> R.string.keys_elevenlabs_where
        ApiService.TYPESAFE -> R.string.keys_typesafe_where
    }

/** The hint for the place the feature would be, while there is no key. */
val ApiService.missingHintRes: Int
    get() = when (this) {
        ApiService.TMDB -> R.string.keys_hint_tmdb_missing
        ApiService.ELEVENLABS -> R.string.keys_hint_elevenlabs_missing
        ApiService.TYPESAFE -> R.string.keys_hint_typesafe_missing
    }

/** The service's mark in lists. */
val ApiService.icon: OwnTVIcon
    get() = when (this) {
        ApiService.TMDB -> OwnTVIcon.IMAGE
        ApiService.ELEVENLABS -> OwnTVIcon.MIC
        ApiService.TYPESAFE -> OwnTVIcon.SEARCH
    }

/** "Not set" or "Set · ••••c3f9" for a stored [key]. */
@Composable
fun keyStateText(key: String): String =
    if (key.isEmpty()) stringResource(R.string.keys_state_not_set) else stringResource(R.string.keys_state_set, ApiKeys.mask(key))

/**
 * What a [check] of [key] for [service] means, in a sentence for the user. A rejected TMDB read access
 * token gets its own explanation, since it sits next to the API key on TMDB's page.
 */
@Composable
fun keyCheckMessage(service: ApiService, check: KeyCheck, key: String): String {
    val name = stringResource(service.nameRes)
    return when (check) {
        KeyCheck.Valid -> stringResource(R.string.keys_check_valid)
        KeyCheck.Invalid ->
            if (service == ApiService.TMDB && ApiKeyValidator.looksLikeTmdbReadToken(key)) stringResource(R.string.keys_check_tmdb_token)
            else stringResource(R.string.keys_check_invalid, name)
        is KeyCheck.Unreachable -> {
            val code = check.httpCode
            if (code == null) stringResource(R.string.keys_check_offline, name) else stringResource(R.string.keys_check_server, name, code)
        }
        KeyCheck.Unknown -> stringResource(R.string.keys_check_unknown)
    }
}

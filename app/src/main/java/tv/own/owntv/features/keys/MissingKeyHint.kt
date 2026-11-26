package tv.own.owntv.features.keys

import androidx.annotation.StringRes
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.tv.material3.Text
import org.koin.compose.koinInject
import tv.own.owntv.ui.components.OwnTVIcon
import tv.own.owntv.ui.theme.StageColors
import tv.own.owntv.ui.theme.mpx
import tv.own.owntv.ui.theme.stageText

/** The services that have no key right now; follows the key store, so a key added in Settings shows at once. */
@Composable
fun rememberMissingKeys(): Set<ApiService> {
    val keys: ApiKeys = koinInject()
    val configured by keys.configured.collectAsStateWithLifecycle()
    return ApiService.entries.toSet() - configured
}

/**
 * The quiet line in the place a feature would be while its key is missing: what is missing and where
 * to add it ([text], normally [ApiService.missingHintRes]). Not focusable.
 */
@Composable
fun MissingKeyHint(@StringRes text: Int, modifier: Modifier = Modifier, size: Int = 17) {
    Row(modifier, horizontalArrangement = Arrangement.spacedBy(10.mpx), verticalAlignment = Alignment.Top) {
        OwnTVIcon(OwnTVIcon.INFO, StageColors.Muted, Modifier.padding(top = 3.mpx).size((size + 2).mpx))
        Text(stringResource(text), style = stageText(size, 500), color = StageColors.Muted)
    }
}

/**
 * Filme / Serien: the line under the category column while there is no TMDB key (the catalogs and
 * posters are then missing), or null once a key is set.
 */
@Composable
fun rememberVodCatalogsHint(): (@Composable () -> Unit)? {
    if (ApiService.TMDB !in rememberMissingKeys()) return null
    return { MissingKeyHint(tv.own.owntv.R.string.keys_hint_tmdb_catalogs, Modifier.padding(start = 14.mpx, end = 14.mpx, top = 14.mpx), size = 15) }
}

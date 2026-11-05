package tv.own.owntv.features.home

import androidx.compose.animation.Crossfade
import androidx.compose.animation.core.tween
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.layout.ContentScale
import coil3.compose.AsyncImage
import tv.own.owntv.core.database.entity.ChannelEntity

/** Where a programme's picture stands: still being looked up, known to have none, or found. */
sealed interface ArtState {
    data object Loading : ArtState
    data object None : ArtState
    data class Picture(val url: String) : ArtState
}

/** What the picture area shows: a programme's picture for [channel], or with no [url] that channel's logo. */
private data class Shown(val url: String?, val channel: ChannelEntity)

/**
 * The Spotlight's picture, without flicker (Jonathan, 5 Nov 2026: the logo flashed up before each picture).
 * What is shown stays until the next picture has fully loaded, then the two cross-fade, as streaming apps do.
 * While a picture is still looked up nothing changes; the logo only comes, faded in too, when the programme
 * has no picture at all.
 */
@Composable
internal fun SpotlightArt(
    state: ArtState,
    channel: ChannelEntity,
    logo: @Composable (ChannelEntity) -> Unit,
    corner: @Composable (ChannelEntity) -> Unit,
) {
    var shown by remember { mutableStateOf<Shown?>(null) }
    LaunchedEffect(state, channel.id) {
        when (state) {
            ArtState.None -> shown = Shown(null, channel)
            // The same picture for another channel (ARD and ZDF share the Morgenmagazin): only the corner changes.
            is ArtState.Picture -> if (state.url == shown?.url) shown = Shown(state.url, channel)
            ArtState.Loading -> Unit
        }
    }
    Box(Modifier.fillMaxSize()) {
        Crossfade(targetState = shown, animationSpec = tween(FADE_MS), label = "spotlight-art") { s ->
            when {
                s == null -> Unit
                s.url != null -> Box(Modifier.fillMaxSize()) {
                    AsyncImage(model = s.url, contentDescription = null, contentScale = ContentScale.Crop, modifier = Modifier.fillMaxSize())
                    corner(s.channel)
                }
                else -> logo(s.channel)
            }
        }
        // The next picture loads unseen, at the same size (so the shown copy then comes from memory at once);
        // only when it is there does it take the place of the one shown.
        val next = (state as? ArtState.Picture)?.url
        if (next != null && next != shown?.url) {
            AsyncImage(
                model = next,
                contentDescription = null,
                contentScale = ContentScale.Crop,
                modifier = Modifier.fillMaxSize().alpha(0f),
                onSuccess = { shown = Shown(next, channel) },
                onError = { shown = Shown(null, channel) },
            )
        }
    }
}

/** Long enough to read as a fade, short enough not to hold up walking the strip. */
private const val FADE_MS = 320

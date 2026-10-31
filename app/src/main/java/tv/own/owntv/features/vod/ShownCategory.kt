package tv.own.owntv.features.vod

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.paging.LoadState
import androidx.paging.compose.LazyPagingItems
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withTimeoutOrNull
import tv.own.owntv.core.live.LiveKey

/**
 * Which category the titles on screen (Filme / Serien) are from.
 *
 * A category picked in the column is read off the main thread, and its titles take the old ones' place a
 * moment after the pick. Until then the old category's titles are still shown: ▶ right after OK focused one of
 * them, the new titles replaced it and the focus went with it, so it fell back into the column and was taken
 * back again (the screen jumped back and forth), and the hero, the scroll position and the remembered title
 * went to the old list's. [key] follows a pick ([picked]) only once its titles are in; the TV-Guide waits for a
 * group's list the same way. Any other change of the selection (the start-up restore, Search's reveal, a pin)
 * is followed at once, as before.
 */
@Stable
internal class ShownCategory(initial: LiveKey) {
    var key by mutableStateOf(initial)
        internal set

    /** The titles on screen when the category was picked; the next selection waits for them to be replaced. */
    internal var pickedFrom: Any? = null

    /** Call with the titles on screen just before selecting another category from the column. */
    fun picked(titles: Any) {
        pickedFrom = titles
    }

    /** Suspends until the titles on screen are [selected]'s (bounded: [rememberShownCategory] lets go after 2 s). */
    suspend fun awaitShown(selected: () -> LiveKey) {
        snapshotFlow { key == selected() }.first { it }
    }
}

@Composable
internal fun <T : Any> rememberShownCategory(selected: LiveKey, items: LazyPagingItems<T>): ShownCategory {
    val shown = remember { ShownCategory(selected) }
    LaunchedEffect(selected) {
        val before = shown.pickedFrom
        shown.pickedFrom = null
        if (before != null && shown.key != selected) {
            // The new titles are in once the list on screen is no longer the one from the pick and is done
            // loading. Two empty categories in a row present equal lists (no new list object), so a refresh
            // seen loading in between counts as well.
            var loading = false
            withTimeoutOrNull(2_000) {
                snapshotFlow { (items.itemSnapshotList !== before) to items.loadState.refresh }
                    .first { (replaced, refresh) ->
                        if (refresh is LoadState.Loading) loading = true
                        refresh is LoadState.NotLoading && (replaced || loading)
                    }
            }
        }
        shown.key = selected
    }
    return shown
}

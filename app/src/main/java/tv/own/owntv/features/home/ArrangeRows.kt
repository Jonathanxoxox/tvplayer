package tv.own.owntv.features.home

import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import tv.own.owntv.R
import tv.own.owntv.core.model.HomeRow
import tv.own.owntv.launcher.apps.LauncherApps
import tv.own.owntv.launcher.apps.LauncherConfig
import tv.own.owntv.launcher.apps.LauncherPrefs
import tv.own.owntv.launcher.apps.appRowNames
import tv.own.owntv.launcher.apps.rememberLauncherConfig
import tv.own.owntv.ui.components.MoveOrderOverlay

/**
 * The first of Home's rows Home drew, last time it was shown. Other apps' rows the user never placed sit
 * right after it in the compact layouts, and the arrange window opened from Settings has to list them
 * there too, without Home's state at hand.
 */
object HomeDrawnRows {
    @Volatile var first: HomeRow? = null
}

/**
 * Home's rows and the other apps' rows (YouTube, Nuvio, Spotify …) in one Move window (Jonathan, 4 Nov
 * 2026), opened from Home's hold-OK menus and from Settings › Home. It opens with [startAt] in hand. Save
 * stores Home's own order through [onSaveRows] and each app row's place as the row it follows ([HomeSlots]).
 */
@Composable
fun ArrangeRowsWindow(
    /** Home's own rows in their order (HomeConfig.settingsRows). */
    rows: List<HomeRow>,
    /** The channel row's name: the category it shows ("Magenta TV"). */
    catalogName: String?,
    startAt: HomeSlot,
    onSaveRows: (List<HomeRow>) -> Unit,
    onClose: () -> Unit,
    /** Opened by a held OK (Settings › Reihen der Startseite): its release must not put the row down. */
    openedByHold: Boolean = false,
) {
    val context = LocalContext.current
    val config = rememberLauncherConfig()
    val channels by LauncherApps.channels.collectAsStateWithLifecycle()
    var slots by remember { mutableStateOf<List<HomeSlot>?>(null) }
    var index by remember { mutableStateOf(0) }
    // Built once, from what Home shows now; the window then changes only its own list.
    if (slots == null) {
        val visible = if (config.appChannels) channels.map { it.key }.filter { it !in config.hiddenChannels } else emptyList()
        val placed = config.channelSlots.mapNotNull(HomeSlots::decode).filter { it.second in visible }
        val placedKeys = placed.map { it.second }.toSet()
        // Where Home draws never placed app rows: after its first drawn row in the compact layouts, else last.
        val early = config.appChannels && config.homeLayout != LauncherConfig.LAYOUT_STANDARD && config.channelSlots.isEmpty()
        val after = if (early) HomeDrawnRows.first?.takeIf { it in rows } ?: rows.firstOrNull() else null
        val mixed = HomeSlots.mixed(rows, placed, visible.filter { it !in placedKeys }, unplacedAfter = after)
        slots = mixed
        index = mixed.indexOf(startAt).coerceAtLeast(0)
    }
    val list = slots ?: return
    val appNames = appRowNames(channels)
    val names = list.map { s ->
        when (s) {
            is HomeSlot.Row -> if (s.row == HomeRow.RECENT_CHANNELS) catalogName ?: s.row.displayTitle() else s.row.displayTitle()
            is HomeSlot.App -> appNames[s.key] ?: s.key
        }
    }
    MoveOrderOverlay(
        title = stringResource(R.string.home_arrange_rows),
        itemNames = names,
        activeIndex = index,
        onMove = { from, to ->
            val now = slots
            if (now != null && from in now.indices && to in now.indices && from != to) {
                slots = now.toMutableList().apply { add(to, removeAt(from)) }
            }
        },
        onCommit = {
            slots?.let { done ->
                onSaveRows(done.mapNotNull { (it as? HomeSlot.Row)?.row })
                val inWindow = done.mapNotNull { (it as? HomeSlot.App)?.key }.toSet()
                if (inWindow.isNotEmpty()) LauncherPrefs.update(context) { c ->
                    c.copy(channelSlots = HomeSlots.anchors(done, keep = c.channelSlots.filter { e -> HomeSlots.decode(e)?.second !in inWindow }))
                }
            }
            onClose()
        },
        onCancel = onClose,
        openedByHold = openedByHold,
    )
}

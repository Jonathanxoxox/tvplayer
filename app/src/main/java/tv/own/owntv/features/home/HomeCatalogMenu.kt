package tv.own.owntv.features.home

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.focusGroup
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.width
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import tv.own.owntv.R
import tv.own.owntv.core.database.entity.ChannelEntity
import tv.own.owntv.ui.components.MoveOrderOverlay
import tv.own.owntv.ui.components.OwnTVIcon
import tv.own.owntv.ui.components.OwnTVPopup
import tv.own.owntv.ui.components.longPressMenuGuard
import tv.own.owntv.ui.components.trapAllFocusExit
import tv.own.owntv.ui.stage.StageMenu
import tv.own.owntv.ui.stage.StageMenuHeader
import tv.own.owntv.ui.stage.StageMenuItem
import tv.own.owntv.ui.theme.StageColors
import tv.own.owntv.ui.theme.mpx
import tv.own.owntv.ui.theme.stageTone

/**
 * Hold OK on a card of Home's category row: hide the channel from Home, or move it within the row, as
 * More › Favourites does it. Both change Home only ([HomeCatalog]).
 */
@Composable
internal fun HomeCatalogMenu(
    row: HomeCatalog.Row,
    channel: ChannelEntity?,
    onHide: (ChannelEntity) -> Unit,
    onReorder: (List<ChannelEntity>) -> Unit,
    /** The menu or the Move window closed; Home puts focus back on the row. */
    onClose: () -> Unit,
) {
    // The row's order while the Move window is open, with the carried channel's place.
    var moving by remember(row) { mutableStateOf<Pair<List<ChannelEntity>, Int>?>(null) }
    if (channel != null && moving == null) {
        OwnTVPopup(onDismissRequest = onClose, stageLayout = true) {
            val first = remember { FocusRequester() }
            LaunchedEffect(Unit) { withFrameNanos { }; runCatching { first.requestFocus() } }
            BackHandler { onClose() }
            // Opened by a held OK: that press's repeats and release must not click "Hide channel" (the
            // menu flashed up and closed again, the channel hidden; Jonathan, 4 Nov 2026).
            Box(
                Modifier.fillMaxSize().background(stageTone(Color.Black.copy(alpha = 0.45f), StageColors.Scrim)).trapAllFocusExit().focusGroup()
                    .longPressMenuGuard(),
                contentAlignment = Alignment.Center,
            ) {
                StageMenu(Modifier.width(440.mpx)) {
                    StageMenuHeader(channel.name, row.category.name)
                    StageMenuItem(
                        stringResource(R.string.content_hide_channel),
                        onClick = { onHide(channel); onClose() },
                        icon = OwnTVIcon.EYE_OFF,
                        modifier = Modifier.focusRequester(first),
                    )
                    StageMenuItem(
                        stringResource(R.string.content_move),
                        onClick = { row.channels.indexOf(channel).takeIf { it >= 0 }?.let { moving = row.channels to it } },
                        icon = OwnTVIcon.MOVE,
                    )
                }
            }
        }
    }
    moving?.let { (channels, index) ->
        MoveOrderOverlay(
            title = stringResource(R.string.content_move),
            itemNames = channels.map { it.name },
            activeIndex = index,
            // Read through the state: the window may hold on to the first lambdas it was given.
            onMove = { from, to ->
                val now = moving?.first
                if (now != null && from in now.indices && to in now.indices && from != to) {
                    moving = now.toMutableList().apply { add(to, removeAt(from)) } to to
                }
            },
            onCommit = { moving?.first?.let(onReorder); moving = null; onClose() },
            onCancel = { moving = null; onClose() },
        )
    }
}

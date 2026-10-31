package tv.own.owntv.ui.components

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.composed
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type

/**
 * Apply to the root of a menu/dialog opened by an OK press that may still be **held**. A held OK
 * auto-repeats, and those repeats land on the dialog while it is up: a repeat reaching the focused row
 * counts as that row being pressed, and the release then "clicks" it. The long-press menu of a film
 * closed again as OK was let go, after toggling its first row, Favourites (Jonathan, 30 Oct 2026).
 *
 * So OK only counts once it has gone down **in this dialog**: a KeyDown with no repeats before it. The
 * repeats of the press that opened it (repeatCount > 0) and that press's release are swallowed; the
 * user's next press is fresh and goes through at once.
 *
 * It used to arm on time instead: once no OK repeat had reached the dialog for a moment. The popup's
 * window takes the key focus a little after it appears, and the first repeats still went to the screen
 * underneath; the guard took that quiet for a released key, armed, and let the next repeats and the
 * release through. Waiting for a KeyUp is no better: the press that opens a dialog on KeyDown is often
 * released before the dialog is there, and the guard would then eat the next, deliberate press.
 */
fun Modifier.longPressMenuGuard(): Modifier = composed {
    // OK went down in this dialog: its repeats and its release belong to the row it went down on.
    var ownPress by remember { mutableStateOf(false) }
    onPreviewKeyEvent { e ->
        if (e.key == Key.DirectionCenter || e.key == Key.Enter || e.key == Key.NumPadEnter) {
            when (e.type) {
                KeyEventType.KeyDown -> {
                    if (e.nativeKeyEvent.repeatCount == 0) ownPress = true
                    !ownPress
                }
                KeyEventType.KeyUp -> {
                    val mine = ownPress
                    ownPress = false
                    !mine
                }
                else -> false
            }
        } else {
            false
        }
    }
}

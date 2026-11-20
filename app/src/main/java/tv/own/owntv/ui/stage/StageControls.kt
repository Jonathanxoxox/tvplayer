package tv.own.owntv.ui.stage

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsFocusedAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.relocation.BringIntoViewRequester
import androidx.compose.foundation.relocation.bringIntoViewRequester
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusProperties
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.em
import androidx.tv.material3.Text
import tv.own.owntv.ui.components.LocalTvImeMetrics
import tv.own.owntv.ui.components.LocalTvImeWatcher
import tv.own.owntv.ui.components.OwnTVIcon
import tv.own.owntv.ui.components.rememberVoiceSearch
import tv.own.owntv.ui.theme.StageColors
import tv.own.owntv.ui.theme.LocalStagePalette
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.isImeVisible
import tv.own.owntv.ui.theme.mpx
import tv.own.owntv.ui.theme.stageAccent
import tv.own.owntv.ui.theme.stageText

/**
 * `.srchf` (tool rows, 48 high, radius 15) and `.sheet .srch` (54, radius 16): a quiet white-6% field,
 * the search glyph and a dim placeholder. The field takes D-pad focus like a button, OK opens the
 * keyboard, Back returns to the field. Focused = the 2 px focus ring. Beside it, the same height square,
 * the microphone ([voice]): ▶ from the field reaches it, OK starts voice search, ◀ goes back.
 */
@Composable
fun StageSearchField(
    query: String,
    onQueryChange: (String) -> Unit,
    placeholder: String,
    modifier: Modifier = Modifier,
    height: Dp = 48.mpx,
    radius: Dp = 15.mpx,
    horizontalPadding: Dp = 18.mpx,
    /** Open the keyboard as soon as the field appears (Settings search reached from a full page). */
    autoEdit: Boolean = false,
    /** Set = OK calls this instead of editing here (a full page's search hands over to Settings search). */
    onActivate: (() -> Unit)? = null,
    /** Typing started (true) or ended (false) — so a host can leave ◀/▶ to the text cursor meanwhile. */
    onEditingChange: (Boolean) -> Unit = {},
    /** The microphone button (Jonathan's keyboard has its mic off). Not where OK hands over to another search. */
    voice: Boolean = onActivate == null,
    /** Turns what the mic heard (best guess first) into the search text; null = the best guess. */
    voiceResolve: (suspend (List<String>) -> String)? = null,
) {
    val interaction = remember { MutableInteractionSource() }
    val pillFocused by interaction.collectIsFocusedAsState()
    var editing by remember { mutableStateOf(false) }
    // After the page's own focus restore has run, so it cannot take the field straight back.
    LaunchedEffect(autoEdit) { if (autoEdit) { kotlinx.coroutines.delay(250); editing = true } }
    val pillFocus = remember { FocusRequester() }
    val fieldFocus = remember { FocusRequester() }
    val bringIntoView = remember { BringIntoViewRequester() }
    val keyboard = LocalSoftwareKeyboardController.current
    val imeWatcher = LocalTvImeWatcher.current
    val imeMetrics = LocalTvImeMetrics.current
    val focus = stageAccent.focus
    val voiceSearch = if (voice) rememberVoiceSearch(onQueryChange, voiceResolve) else null
    val voiceNote = voiceSearch?.heard
        ?: voiceSearch?.note?.let { note -> voiceSearch.noteArg?.let { stringResource(note, it) } ?: stringResource(note) }
    val micFocus = remember { FocusRequester() }
    val micInteraction = remember { MutableInteractionSource() }
    val micFocused by micInteraction.collectIsFocusedAsState()
    // The mic is reached only by ▶ from the field. A host asking for the field's focus (its requester sits on the
    // whole row) found the mic first, so opening Search landed on the microphone instead of the field.
    var micArmed by remember { mutableStateOf(false) }
    val rtl = LocalLayoutDirection.current == LayoutDirection.Rtl
    LaunchedEffect(editing) {
        onEditingChange(editing)
        if (editing) {
            imeWatcher?.onImeRequested()
            runCatching { fieldFocus.requestFocus() }
            keyboard?.show()
            kotlinx.coroutines.delay(120)
            runCatching { bringIntoView.bringIntoView() }
        } else {
            imeWatcher?.onImeDismissed()
        }
    }
    // The keyboard closed by itself (its own Back or Done): typing is over, the field is a button again, so the
    // next ◀ or Back moves on instead of going to the text cursor first (Jonathan, 20 Nov 2026: after a search
    // neither ◀ nor Back reached the menu). Only once the keyboard was seen, as some boxes never report it.
    @OptIn(androidx.compose.foundation.layout.ExperimentalLayoutApi::class)
    val imeShown = WindowInsets.isImeVisible
    var imeSeen by remember { mutableStateOf(false) }
    LaunchedEffect(editing, imeShown) {
        if (!editing) { imeSeen = false; return@LaunchedEffect }
        if (imeShown) {
            imeSeen = true
        } else if (imeSeen) {
            kotlinx.coroutines.delay(IME_CLOSE_SETTLE_MS)
            editing = false
            runCatching { pillFocus.requestFocus() }
        }
    }
    LaunchedEffect(editing, imeMetrics.keyboardTopPx, imeMetrics.visible) {
        if (editing && imeMetrics.visible) {
            kotlinx.coroutines.delay(32)
            runCatching { bringIntoView.bringIntoView() }
        }
    }
    Row(
        // Ahead of the host's own key handling: ▶ from the field reaches the mic first, ◀ from the mic returns.
        Modifier
            .onPreviewKeyEvent { e ->
                if (voiceSearch == null || editing || e.type != KeyEventType.KeyDown) return@onPreviewKeyEvent false
                val toMic = if (rtl) Key.DirectionLeft else Key.DirectionRight
                val toField = if (rtl) Key.DirectionRight else Key.DirectionLeft
                when {
                    e.key == toMic && pillFocused -> {
                        micArmed = true
                        runCatching { micFocus.requestFocus() }.getOrDefault(false).also { if (!it) micArmed = false }
                    }
                    e.key == toField && micFocused -> runCatching { pillFocus.requestFocus() }.isSuccess
                    else -> false
                }
            }
            .then(modifier)
            .height(height),
        horizontalArrangement = Arrangement.spacedBy(8.mpx),
        verticalAlignment = Alignment.CenterVertically,
    ) {
    Box(
        Modifier
            .weight(1f)
            .fillMaxHeight()
            .background(StageColors.ControlFill, RoundedCornerShape(radius))
            .then(if (pillFocused || editing) Modifier.drawBehind { drawOuterRing(focus, 2.mpx.toPx(), radius.toPx()) } else Modifier)
            .focusRequester(pillFocus)
            .clickable(interaction, null) { if (onActivate != null) onActivate() else editing = true },
        contentAlignment = Alignment.CenterStart,
    ) {
        Row(
            Modifier.fillMaxWidth().padding(horizontal = horizontalPadding),
            horizontalArrangement = Arrangement.spacedBy(12.mpx),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            OwnTVIcon(OwnTVIcon.SEARCH, if (pillFocused || editing) StageColors.Text else StageColors.Dim, Modifier.size(18.mpx))
            Box(Modifier.weight(1f)) {
                if (voiceNote != null) {
                    Text(voiceNote, style = stageText(18, 500), color = stageAccent.accent, maxLines = 1, overflow = TextOverflow.Ellipsis)
                } else if (query.isEmpty()) {
                    Text(placeholder, style = stageText(18, 400), color = StageColors.Dim, maxLines = 1, overflow = TextOverflow.Ellipsis)
                }
                BasicTextField(
                    value = query,
                    onValueChange = onQueryChange,
                    modifier = Modifier
                        .fillMaxWidth()
                        .alpha(if (voiceNote != null) 0f else 1f)
                        .bringIntoViewRequester(bringIntoView)
                        .focusRequester(fieldFocus)
                        .focusProperties { canFocus = editing }
                        .onFocusChanged { if (editing && !it.isFocused) editing = false }
                        .onPreviewKeyEvent {
                            if (it.key == Key.Back) {
                                if (it.type == KeyEventType.KeyUp) {
                                    editing = false
                                    keyboard?.hide()
                                    runCatching { pillFocus.requestFocus() }
                                }
                                true
                            } else {
                                false
                            }
                        },
                    textStyle = stageText(18, 500).copy(color = StageColors.Text),
                    singleLine = true,
                    cursorBrush = SolidColor(stageAccent.accent),
                    keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
                    keyboardActions = KeyboardActions(onSearch = {
                        editing = false
                        keyboard?.hide()
                        runCatching { pillFocus.requestFocus() }
                    }),
                )
            }
        }
    }
    if (voiceSearch != null) {
        val lit = micFocused || voiceSearch.busy
        Box(
            Modifier
                .size(height)
                .background(StageColors.ControlFill, RoundedCornerShape(radius))
                .then(if (lit) Modifier.drawBehind { drawOuterRing(focus, 2.mpx.toPx(), radius.toPx()) } else Modifier)
                .focusRequester(micFocus)
                .focusProperties { canFocus = micArmed }
                .onFocusChanged { if (!it.isFocused) micArmed = false }
                .clickable(micInteraction, null) { voiceSearch.start() },
            contentAlignment = Alignment.Center,
        ) {
            OwnTVIcon(
                OwnTVIcon.MIC,
                if (voiceSearch.busy) stageAccent.accent else if (micFocused) StageColors.Text else StageColors.Dim,
                Modifier.size(20.mpx),
            )
        }
    }
    }
}

/** How long a closed keyboard must stay closed before typing counts as over (a keyboard switching layouts blinks). */
private const val IME_CLOSE_SETTLE_MS = 150L

/** Whether the pages show their remote-key hints ([StageKeyHints]). */
private const val ShowKeyHints = false

/**
 * `.keys`: remote-key hints, 17 px muted, 26 apart; each key a `.kc` cap (14/800 on white 8%). In a
 * narrow column they wrap onto a second line rather than being cut.
 *
 * Mayo TV shows them nowhere since 30 Oct 2026 (Jonathan: "die benötige ich nicht"): the pages are
 * cleaner without, and the remote's keys do what they always did. [ShowKeyHints] brings them back.
 */
@OptIn(androidx.compose.foundation.layout.ExperimentalLayoutApi::class)
@Composable
fun StageKeyHints(hints: List<Pair<String, String>>, modifier: Modifier = Modifier, textSize: Int = 17) {
    if (!ShowKeyHints) return
    androidx.compose.foundation.layout.FlowRow(
        modifier,
        horizontalArrangement = Arrangement.spacedBy(26.mpx),
        verticalArrangement = Arrangement.spacedBy(8.mpx),
    ) {
        hints.forEach { (key, label) ->
            Row(verticalAlignment = Alignment.CenterVertically) {
                Box(
                    Modifier
                        .defaultMinSize(minWidth = 34.mpx)
                        .height(30.mpx)
                        // No 2 px bottom shade: on the TV it read as a stray bar.
                        .background(LocalStagePalette.current.keycap, RoundedCornerShape(8.mpx))
                        // `.mine.light .kc`: a white cap needs an ink rim to stand off the white glass.
                        .then(if (LocalStagePalette.current.isLight) Modifier.border(1.mpx, StageColors.Ink.copy(alpha = 0.1f), RoundedCornerShape(8.mpx)) else Modifier)
                        .padding(horizontal = 8.mpx),
                    contentAlignment = Alignment.Center,
                ) { Text(key, style = stageText(14, 800), color = StageColors.Text, maxLines = 1, overflow = TextOverflow.Ellipsis) }
                Box(Modifier.size(8.mpx, 1.mpx))
                Text(label, style = stageText(textSize, 400), color = StageColors.Muted, maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
        }
    }
}

/** `.live`: the red-dot LIVE badge on a video, 15/800 +0.08em on black 55%. */
@Composable
fun StageLiveBadge(text: String) {
    val red = Color(0xFFFF4D4D)
    Row(
        Modifier.background(Color.Black.copy(alpha = 0.55f), RoundedCornerShape(10.mpx)).padding(horizontal = 12.mpx, vertical = 6.mpx),
        horizontalArrangement = Arrangement.spacedBy(8.mpx),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(Modifier.size(9.mpx).drawBehind { drawBoxShadow(red, 10.mpx.toPx(), size.minDimension / 2f); drawCircle(red) })
        Text(text, style = stageText(15, 800, 0.08.em), color = Color.White, maxLines = 1, overflow = TextOverflow.Ellipsis)
    }
}

/** `.spec span`: one stream fact on a video ("FHD", "50 FPS"), 15/800 on black 50%. */
@Composable
fun StageSpecChip(text: String) {
    Text(
        text,
        style = stageText(15, 800, 0.05.em),
        color = Color(0xFFE7EFEC),
        maxLines = 1, overflow = TextOverflow.Ellipsis,
        modifier = Modifier.background(Color.Black.copy(alpha = 0.5f), RoundedCornerShape(8.mpx)).padding(horizontal = 9.mpx, vertical = 5.mpx),
    )
}

/**
 * A progress track: `.prog` (6 high, accent → focus sweep on white 12%) or, with [flat], the channel
 * row's `.pb` (4 high, plain accent on white 10%).
 */
@Composable
fun StageProgress(fraction: Float, modifier: Modifier = Modifier, flat: Boolean = false) {
    val a = stageAccent
    val ink = StageColors.Ink
    Box(
        modifier
            .height(if (flat) 4.mpx else 6.mpx)
            .drawBehind {
                val r = CornerRadius(size.height / 2f)
                drawRoundRect(ink.copy(alpha = if (flat) 0.10f else 0.12f), cornerRadius = r)

                val w = size.width * fraction.coerceIn(0f, 1f)
                if (w > 0f) {
                    val fill = if (flat) Brush.linearGradient(listOf(a.accent, a.accent))
                    else Brush.horizontalGradient(listOf(a.accent, a.focus), endX = w)
                    drawRoundRect(fill, size = Size(w, size.height), cornerRadius = r)
                }
            },
    )
}

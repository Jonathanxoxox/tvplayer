package tv.own.owntv.features.settings

import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import tv.own.owntv.R
import tv.own.owntv.core.theme.AppFontFamily
import tv.own.owntv.core.theme.FontCustomization
import tv.own.owntv.core.theme.PopupFontScale
import tv.own.owntv.core.theme.UiFontScale
import tv.own.owntv.ui.theme.MayoFont

/**
 * Settings › Aussehen & Startseite › Größe & Schrift (6 Nov 2026): Textgröße, Schriftart and Popup-Textgröße
 * are rows on the group's page now, not a page of their own. These are their values and what OK opens in the
 * page's panel: the font list, or the value between ◀ ▶ with Reset (popup text size with a real-size sample).
 */
internal enum class FontEdit { FONT, SIZE, POPUP }

/** Core's families, and Manrope, Mayo TV's own addition (MayoFont), which stands in for them while chosen. */
@Composable
internal fun fontChoices(familyLabel: @Composable (AppFontFamily) -> String): List<Pair<String, String>> =
    AppFontFamily.entries.map { it.name to familyLabel(it) } + (MayoFont.KEY to stringResource(R.string.tvplayer_font_manrope))

/** The Schriftart row's value: Manrope while it is chosen, else the main family. */
@Composable
internal fun fontChoiceLabel(current: FontCustomization, familyLabel: @Composable (AppFontFamily) -> String): String {
    val context = LocalContext.current
    val manrope by remember(context) { MayoFont.flow(context) }.collectAsStateWithLifecycle()
    return if (manrope) stringResource(R.string.tvplayer_font_manrope) else familyLabel(current.mainFamily)
}

internal fun stepTextSize(f: FontCustomization, d: Int) = f.copy(sizePercent = UiFontScale.clamp(f.sizePercent + d * UiFontScale.STEP))

internal fun stepPopupTextSize(f: FontCustomization, d: Int) =
    f.copy(popupFontSizePercent = PopupFontScale.clamp(f.popupFontSizePercent + d * PopupFontScale.STEP))

/** What OK on one of the three rows opens in the panel; each change applies at once. */
@Composable
internal fun FontEditHost(
    edit: FontEdit?,
    current: FontCustomization,
    onSet: (FontCustomization) -> Unit,
    familyLabel: @Composable (AppFontFamily) -> String,
    onClose: () -> Unit,
) {
    val context = LocalContext.current
    val manrope by remember(context) { MayoFont.flow(context) }.collectAsStateWithLifecycle()
    val percent: @Composable (Int) -> String = { stringResource(R.string.common_percent, it) }
    when (edit) {
        FontEdit.FONT -> PickerDialog(
            title = stringResource(R.string.settings_ca_font),
            options = fontChoices(familyLabel),
            selected = if (manrope) MayoFont.KEY else current.mainFamily.name,
            // One family for the interface and its popups: popups follow the main font.
            onSelect = { name ->
                if (name == MayoFont.KEY) {
                    MayoFont.set(context, true)
                } else {
                    MayoFont.set(context, false)
                    AppFontFamily.valueOf(name).let { onSet(current.copy(mainFamily = it, popupFamily = it)) }
                }
                onClose()
            },
            onDismiss = onClose,
        )
        FontEdit.SIZE -> panelEditor(onClose) {
            PanelStepper(
                percent(current.sizePercent), onStep = { onSet(stepTextSize(current, it)) },
                onReset = { onSet(current.copy(sizePercent = UiFontScale.DEFAULT)) }, onDone = onClose,
            )
        }
        FontEdit.POPUP -> panelEditor(onClose) {
            PanelStepper(
                percent(current.popupFontSizePercent), onStep = { onSet(stepPopupTextSize(current, it)) },
                onReset = { onSet(current.copy(popupFontSizePercent = PopupFontScale.DEFAULT)) }, onDone = onClose,
                preview = { PopupSizeSample(current.popupSizePercent) },
            )
        }
        null -> Unit
    }
}

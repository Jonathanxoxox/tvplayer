package tv.own.owntv.features.settings

import tv.own.owntv.ui.theme.StageColors
import androidx.compose.foundation.background
import androidx.compose.foundation.focusGroup
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.res.stringResource
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.tv.material3.Text
import tv.own.owntv.R
import tv.own.owntv.ui.theme.mpx
import tv.own.owntv.core.settings.GuideWidthLimits
import tv.own.owntv.core.settings.GuideWidthShares
import tv.own.owntv.ui.components.trapAllFocusExit

/**
 * Live-TV & Sport › TV-Guide › Spaltenbreiten im TV-Guide (6 Nov 2026: a row with a panel, no page of
 * its own): the TV guide's pinned channel column and its programme timeline, edited in the page's panel
 * with Reset beside them. Outside a settings page it falls back to a Stage popup.
 */
@Composable
internal fun GuideWidthPanel(vm: SettingsViewModel, onDismiss: () -> Unit) {
    val enabled by vm.guideWidthEnabled.collectAsStateWithLifecycle()
    val shares by vm.guideWidthShares.collectAsStateWithLifecycle()
    val current = shares ?: GuideWidthLimits.defaults
    val editor: @Composable () -> Unit = {
        GuideWidthDialog(savedEnabled = enabled, savedShares = current, onSave = vm::setGuideWidths, onDismiss = onDismiss)
    }
    if (panelEditor(onDismiss) { editor() }) return
    // The popup is headed by the row's own name, which Settings search finds as the row.
    val heading = stringResource(R.string.settings_guide_width)
    tv.own.owntv.ui.stage.StagePopup(
        onDismiss = onDismiss,
        title = heading,
        body = stringResource(R.string.settings_guide_width_help, *NO_ARGS),
    ) { editor() }
}

/** The row's value: "Standard", or the two widths when they are set by hand. */
@Composable
internal fun guideWidthValue(enabled: Boolean, shares: GuideWidthShares?): String {
    if (!enabled) return stringResource(R.string.settings_subtitle_default)
    val s = shares ?: GuideWidthLimits.defaults
    return stringResource(R.string.common_percent, s.channels) + stringResource(R.string.content_epg_bits_separator) + stringResource(R.string.common_percent, s.epg)
}

@Composable
private fun GuideWidthDialog(
    savedEnabled: Boolean,
    savedShares: GuideWidthShares,
    onSave: (Boolean, GuideWidthShares) -> Unit,
    onDismiss: () -> Unit,
) {
    // The two columns always add up to 100%, so there is one number to set: the channel column's
    // width; the guide takes the rest. In the page's panel, saved as it changes.
    val first = remember { FocusRequester() }
    LaunchedEffect(Unit) { kotlinx.coroutines.delay(60); runCatching { first.requestFocus() } }
    androidx.compose.foundation.layout.Column(Modifier.trapAllFocusExit().focusGroup()) {
        tv.own.owntv.ui.stage.StagePopupOption(
            title = stringResource(R.string.settings_panel_width_customize), onClick = { onSave(!savedEnabled, savedShares) },
            modifier = Modifier.focusRequester(first),
            trailing = { tv.own.owntv.ui.stage.StageSwitch(savedEnabled) },
        )
        if (savedEnabled) {
            StepRow(stringResource(R.string.settings_guide_width_channels), savedShares.channels, GuideWidthLimits.MIN, GuideWidthLimits.MAX, GuideWidthLimits.STEP) {
                onSave(true, GuideWidthShares(it, GuideWidthLimits.TOTAL - it))
            }
            Box(Modifier.padding(horizontal = 8.mpx, vertical = 14.mpx)) { GuideWidthDiagram(savedShares) }
        }
        androidx.compose.foundation.layout.Row(Modifier.fillMaxWidth().padding(top = 8.mpx), horizontalArrangement = Arrangement.spacedBy(12.mpx, Alignment.End)) {
            tv.own.owntv.ui.stage.StageButton(stringResource(R.string.common_reset), onClick = { onSave(savedEnabled, GuideWidthLimits.defaults) }, height = 52.mpx, textSize = 18)
            tv.own.owntv.ui.stage.StageButton(stringResource(R.string.common_done), onClick = onDismiss, height = 52.mpx, textSize = 18, tinted = true)
        }
    }
}

@Composable
private fun GuideWidthDiagram(shares: GuideWidthShares) {
    // The two columns at their widths, in the Stage colours: channels in accent, the guide plain.
    val a = tv.own.owntv.ui.theme.stageAccent
    androidx.compose.foundation.layout.Row(Modifier.fillMaxWidth().height(52.mpx), horizontalArrangement = Arrangement.spacedBy(6.mpx)) {
        listOf(shares.channels to a.accent.copy(alpha = 0.32f), shares.epg to tv.own.owntv.ui.theme.StageColors.Ink.copy(alpha = 0.08f)).forEach { (w, fill) ->
            Box(Modifier.weight(w.toFloat()).fillMaxSize().background(fill, RoundedCornerShape(10.mpx)), contentAlignment = Alignment.Center) {
                Text(stringResource(R.string.common_percent, w), style = tv.own.owntv.ui.theme.stageText(15, 700), color = tv.own.owntv.ui.theme.StageColors.Text, maxLines = 1, overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis)
            }
        }
    }
}

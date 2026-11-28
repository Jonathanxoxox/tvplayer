package tv.own.owntv.features.onboarding.appearance

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.text.style.TextOverflow
import androidx.tv.material3.Text
import tv.own.owntv.core.settings.SettingsRepository
import tv.own.owntv.core.theme.AccentColor
import tv.own.owntv.ui.components.OwnTVIcon
import tv.own.owntv.ui.stage.StageFocus
import tv.own.owntv.ui.stage.StageSurface
import tv.own.owntv.ui.theme.StageColors
import tv.own.owntv.ui.theme.mpx
import tv.own.owntv.ui.theme.parseAccentHex
import tv.own.owntv.ui.theme.stageAccent
import tv.own.owntv.ui.theme.stageText

/**
 * One Home layout to choose: its drawn miniature, its name and a short line. The chosen one carries
 * an accent frame, a tick and its name in the accent; focus is the poster's white frame.
 */
@Composable
internal fun LayoutCard(
    title: String,
    line: String,
    home: MiniHome,
    selected: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val a = stageAccent
    StageSurface(
        onClick = onClick,
        radius = 20.mpx,
        focusStyle = StageFocus.POSTER,
        idle = Modifier.background(StageColors.Ink.copy(alpha = 0.05f), RoundedCornerShape(20.mpx)),
        contentAlignment = Alignment.TopStart,
        modifier = modifier,
    ) { _ ->
        Column(Modifier.fillMaxWidth().padding(12.mpx)) {
            HomeMiniature(home, Modifier.fillMaxWidth(), selected = selected)
            Row(
                Modifier.padding(top = 14.mpx, start = 4.mpx, end = 4.mpx),
                horizontalArrangement = Arrangement.spacedBy(8.mpx),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                if (selected) OwnTVIcon(OwnTVIcon.CHECK, a.accent, Modifier.size(22.mpx))
                Text(
                    title,
                    style = stageText(22, 800),
                    color = if (selected) a.accent else StageColors.Text,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            Text(
                line,
                style = stageText(16, 500),
                color = StageColors.Muted,
                maxLines = 3,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.padding(top = 6.mpx, start = 4.mpx, end = 4.mpx, bottom = 4.mpx),
            )
        }
    }
}

/** One accent colour to choose: a round swatch, ticked while it is the one in use. */
@Composable
internal fun AccentSwatch(color: Color, selected: Boolean, onClick: () -> Unit, modifier: Modifier = Modifier) {
    StageSurface(
        onClick = onClick,
        radius = (SwatchSize / 2).mpx,
        focusStyle = StageFocus.POSTER,
        contentAlignment = Alignment.Center,
        modifier = modifier.size(SwatchSize.mpx),
    ) { _ ->
        Box(Modifier.size(SwatchSize.mpx).background(color, CircleShape), contentAlignment = Alignment.Center) {
            if (selected) {
                OwnTVIcon(OwnTVIcon.CHECK, if (color.luminance() > 0.5f) DarkMark else Color.White, Modifier.size(28.mpx))
            }
        }
    }
}

private const val SwatchSize = 60
private val DarkMark = Color(0xFF0B1418)

/**
 * An accent the chapter offers: Mayo TV's own cyan (the shipped default, stored as a custom
 * accent), one of core's presets, or a custom colour already in use (from a restore), kept as it is.
 */
internal sealed interface AccentChoice {
    data object Mayo : AccentChoice
    data class Preset(val accent: AccentColor) : AccentChoice
    data class Own(val color: Color, val hex: String) : AccentChoice

    companion object {
        /** The choice the stored [preset] and [custom] hex stand for. */
        fun of(preset: AccentColor, custom: String): AccentChoice {
            if (custom.isBlank()) return Preset(preset)
            val color = parseAccentHex(custom) ?: return Preset(preset)
            return if (rgb(color) == rgb(MayoAccent)) Mayo else Own(color, custom)
        }
    }
}

/** Mayo TV's cyan, the accent a fresh install starts with. */
internal val MayoAccent = Color(0xFF2BC0DC)

/** Stores [choice] through the same settings calls as Settings › Appearance & Home › Accent colour. */
internal suspend fun writeAccent(settings: SettingsRepository, choice: AccentChoice) {
    when (choice) {
        AccentChoice.Mayo -> settings.setCustomAccent(hexOf(MayoAccent))
        is AccentChoice.Preset -> settings.setAccent(choice.accent)
        is AccentChoice.Own -> settings.setCustomAccent(choice.hex)
    }
}

private fun rgb(color: Color): Int = color.toArgb() and 0xFFFFFF

/** The colour as #RRGGBB, the form core stores a custom accent in. */
private fun hexOf(color: Color): String = buildString {
    append('#')
    append(rgb(color).toString(16).padStart(6, '0').uppercase(java.util.Locale.ROOT))
}

package tv.own.owntv.features.onboarding.appearance

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import tv.own.owntv.launcher.apps.LauncherConfig
import tv.own.owntv.ui.theme.LocalStagePalette
import tv.own.owntv.ui.theme.inScene
import tv.own.owntv.ui.theme.mpx
import tv.own.owntv.ui.theme.stageAccent

/**
 * What a miniature Home shows: the layout ([LauncherConfig.homeLayout]) and the Home switches that
 * change what is on it.
 */
@Immutable
internal data class MiniHome(
    val layout: String,
    val appsRow: Boolean,
    val appChannels: Boolean,
    val rowTitles: Boolean,
)

/**
 * A drawn miniature of Mayo TV's Home in [home]'s layout: the rail, the clock, the channel row or
 * the stage, the apps and the other apps' channels, as blocks in the live accent. It mirrors the
 * real Home's proportions (one unit is 10 px of the 1920 × 1080 screen) without composing any of
 * it, so it costs one canvas draw and no images. [selected] draws an accent frame around it.
 */
@Composable
internal fun HomeMiniature(home: MiniHome, modifier: Modifier = Modifier, selected: Boolean = false) {
    val accent = stageAccent.accent
    val palette = LocalStagePalette.current
    val top = if (palette.isLight) palette.page else inScene(PetrolTop)
    val bottom = if (palette.isLight) palette.page else inScene(PetrolBottom)
    val ink = palette.ink
    Canvas(
        modifier
            .aspectRatio(16f / 9f)
            .clip(RoundedCornerShape(MiniRadius.mpx)),
    ) {
        drawHome(home, accent, ink, top, bottom)
        if (selected) {
            val w = 3.mpx.toPx()
            drawRoundRect(
                accent,
                topLeft = Offset(w / 2f, w / 2f),
                size = Size(size.width - w, size.height - w),
                cornerRadius = CornerRadius(MiniRadius.mpx.toPx() - w / 2f),
                style = Stroke(w),
            )
        }
    }
}

private fun DrawScope.drawHome(home: MiniHome, accent: Color, ink: Color, top: Color, bottom: Color) {
    val u = size.width / 192f
    val ring = (0.5f * u).coerceAtLeast(1.5f)
    fun block(x: Float, y: Float, w: Float, h: Float, color: Color, r: Float = 1.6f) =
        drawRoundRect(color, Offset(x * u, y * u), Size(w * u, h * u), CornerRadius(r * u))
    fun focusRing(x: Float, y: Float, w: Float, h: Float) =
        drawRoundRect(accent, Offset(x * u, y * u), Size(w * u, h * u), CornerRadius(1.6f * u), style = Stroke(ring))

    // The page and its faint accent light, top left as on the real Home.
    drawRect(Brush.verticalGradient(listOf(top, bottom)))
    drawCircle(
        Brush.radialGradient(listOf(accent.copy(alpha = 0.16f), Color.Transparent), center = Offset.Zero, radius = 90f * u),
        radius = 90f * u,
        center = Offset.Zero,
    )

    // The rail at rest, Home lit; the clock top right.
    block(2.6f, 23f, 6f, 50f, ink.copy(alpha = 0.08f), 3f)
    repeat(7) { i ->
        drawCircle(if (i == 1) accent else ink.copy(alpha = 0.35f), radius = 1.1f * u, center = Offset(5.6f * u, (27.5f + i * 6.6f) * u))
    }
    block(170f, 4.5f, 14f, 3.4f, ink.copy(alpha = 0.55f), 1.7f)

    val compact = home.layout != LauncherConfig.LAYOUT_STANDARD
    val spotlight = home.layout == LauncherConfig.LAYOUT_SPOTLIGHT
    val stage = home.layout == LauncherConfig.LAYOUT_STAGE || spotlight
    val x0 = 15f
    var y = if (compact) 4.4f else 13f
    fun title(w: Float) {
        if (home.rowTitles) {
            block(x0, y, w, 2.4f, ink.copy(alpha = 0.7f), 1.2f)
            y += 3.8f
        }
    }

    // Classic, Stage and Spotlight: the apps as small buttons on top.
    if (compact && home.appsRow) {
        var x = x0
        repeat(5) { i ->
            val w = if (i == 4) 12f else 10f
            block(x, y, w, 6f, ink.copy(alpha = 0.12f), 3f)
            x += w + 1.4f
        }
        y += 9f
    }

    if (stage) {
        // The focused channel large: its programme on the left, the picture on the right.
        val picW = if (spotlight) 96f else 54f
        val picH = picW * 9f / 16f
        val textTop = y + picH * 0.32f
        block(x0, textTop, 8f, 1.8f, accent, 0.9f)
        block(x0, textTop + 3.4f, if (spotlight) 40f else 44f, 5f, ink.copy(alpha = 0.85f), 1.5f)
        block(x0, textTop + 10f, 28f, 2f, ink.copy(alpha = 0.4f), 1f)
        val picX = 183f - picW
        block(picX, y, picW, picH, Color.Black, 1.8f)
        val cx = (picX + picW / 2f) * u
        val cy = (y + picH / 2f) * u
        val t = (if (spotlight) 4f else 3f) * u
        drawPath(
            Path().apply {
                moveTo(cx - t * 0.8f, cy - t)
                lineTo(cx + t, cy)
                lineTo(cx - t * 0.8f, cy + t)
                close()
            },
            accent,
        )
        y += picH + 3f
        // The strip of logos under it, the first one focused.
        var x = x0
        repeat(8) { i ->
            block(x, y, 20f, 11.2f, ink.copy(alpha = 0.08f))
            if (i == 0) focusRing(x, y, 20f, 11.2f)
            x += 21.8f
        }
        y += 14.2f
    } else {
        // Standard and Classic: the channel row, the first card focused, what is on under each.
        title(22f)
        var x = x0
        repeat(6) { i ->
            block(x, y, 30f, 16.9f, ink.copy(alpha = 0.08f))
            if (i == 0) focusRing(x, y, 30f, 16.9f)
            block(x, y + 18.2f, 20f, 1.8f, ink.copy(alpha = 0.5f), 0.9f)
            x += 32.2f
        }
        y += 23f
    }

    // Standard: the Apps row of large tiles.
    if (!compact && home.appsRow) {
        title(10f)
        var x = x0
        repeat(5) {
            block(x, y, 30f, 16.9f, ink.copy(alpha = 0.13f))
            x += 32.2f
        }
        y += 20f
    }

    // The other apps' channels: blocks only, their pictures are the apps' own.
    if (home.appChannels) {
        title(26f)
        var x = x0
        repeat(5) {
            block(x, y, 35.6f, 20f, ink.copy(alpha = 0.06f))
            x += 37.8f
        }
    }
}

/** The petrol wash of the real Home (see stageBackground), moved into the chosen theme's hue. */
private val PetrolTop = Color(0xFF0D484D)
private val PetrolBottom = Color(0xFF0B3D42)

private const val MiniRadius = 12

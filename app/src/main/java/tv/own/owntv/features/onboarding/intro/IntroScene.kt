package tv.own.owntv.features.onboarding.intro

import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.BlendMode
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.rotate
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import kotlin.math.PI
import kotlin.math.atan2
import kotlin.math.hypot
import kotlin.math.roundToInt
import kotlin.math.sin
import kotlin.math.tan
import kotlin.random.Random

/** Where the lockup sits while the beams meet on it, as a fraction of the screen height. */
internal const val LOGO_Y = 0.44f

/** Where the tagline sits, as a fraction of the screen height. */
internal const val TAGLINE_Y = 0.585f

/**
 * The night stadium of the brand intro, drawn for time [t] of [IntroTimeline].
 *
 * Everything is placed in fractions of the screen and sized in units of 1/1080 of its height, so it
 * looks the same at any resolution or UI zoom. About forty draw calls a frame, all solid shapes or
 * the two prepared [IntroTextures]: no shaders, no blur, no offscreen layers.
 */
internal class IntroScene(private val textures: IntroTextures) {

    private class Lamp(
        val x: Float,
        val y: Float,
        /** Head size relative to a near lamp; far lamps are smaller and lower. */
        val scale: Float,
        /** Where the beam points before the sweep (on the pitch) and after it (the lockup). */
        val aimX: Float,
        val aimY: Float,
        val mastBottom: Float,
        val beamAlpha: Float,
        val spreadDeg: Float,
    )

    // Near left, near right, far left, far right: the order the lamps strike in (IntroTimeline.LAMP_ON).
    private val lamps = listOf(
        Lamp(0.085f, 0.13f, 1f, 0.30f, 0.98f, 1.00f, 0.50f, 11f),
        Lamp(0.915f, 0.13f, 1f, 0.70f, 0.98f, 1.00f, 0.50f, 11f),
        Lamp(0.300f, 0.31f, 0.55f, 0.38f, 0.90f, 0.64f, 0.38f, 9f),
        Lamp(0.700f, 0.31f, 0.55f, 0.62f, 0.90f, 0.64f, 0.38f, 9f),
    )

    private class Star(val x: Float, val y: Float, val r: Float, val alpha: Float, val hz: Float, val phase: Float)

    private val stars: List<Star> = Random(1907).let { rnd ->
        List(STAR_COUNT) {
            Star(
                x = rnd.nextFloat(),
                y = rnd.nextFloat() * 0.56f,
                r = 0.9f + rnd.nextFloat() * 1.5f,
                alpha = 0.25f + rnd.nextFloat() * 0.65f,
                hz = 0.5f + rnd.nextFloat() * 1.2f,
                phase = rnd.nextFloat() * 2f * PI.toFloat(),
            )
        }
    }

    private val stands = Path()
    private var standsFor = Size.Zero

    /** Draws the scene at time [t], at [fade] opacity (the night coming up, the hand-over, a skip). */
    fun DrawScope.drawScene(t: Float, fade: Float) {
        if (fade <= 0.003f) return
        val w = size.width
        val h = size.height
        val u = h / 1080f
        val light = IntroTimeline.stadiumLight(t)
        val sweep = IntroTimeline.sweep(t)
        val logo = Offset(w * 0.5f, h * LOGO_Y)

        // The sky, and a low haze over the stadium that warms as the lights come on.
        drawRect(Sky, alpha = fade)
        glow(Offset(w * 0.5f, h * 0.68f), w * 0.85f, h * 0.30f, fade * (0.05f + 0.20f * light))

        // Stars, a little fainter once the floodlights are on.
        val starFade = fade * (1f - 0.6f * light)
        for (s in stars) {
            val twinkle = 0.75f + 0.25f * sin(t * s.hz * 2f * PI.toFloat() + s.phase)
            drawCircle(Color.White, radius = s.r * u, center = Offset(s.x * w, s.y * h), alpha = s.alpha * twinkle * starFade)
        }

        // The far lamps stand behind the stands.
        for (i in 2..3) mast(lamps[i], IntroTimeline.lamp(t, i), w, h, u, fade)
        for (i in 2..3) head(lamps[i], IntroTimeline.lamp(t, i), w, h, u, fade)

        // The stands: a dark bowl, higher at the sides.
        if (standsFor != size) {
            standsFor = size
            stands.reset()
            stands.moveTo(0f, h * 0.57f)
            stands.quadraticTo(w * 0.5f, h * 0.73f, w, h * 0.57f)
            stands.lineTo(w, h)
            stands.lineTo(0f, h)
            stands.close()
        }
        drawPath(stands, lerp(Stands, StandsLit, light * 0.6f), alpha = fade)

        // The pitch: a huge ellipse whose top edge is the touchline, with the centre circle and halfway line.
        val pitchTop = h * 0.78f
        val pitchRect = Offset(-w * 0.5f, pitchTop) to Size(w * 2f, h * 1.0f)
        drawOval(lerp(Pitch, PitchLit, light), topLeft = pitchRect.first, size = pitchRect.second, alpha = fade)
        drawArc(
            color = Rim,
            startAngle = 180f,
            sweepAngle = 180f,
            useCenter = false,
            topLeft = pitchRect.first,
            size = pitchRect.second,
            alpha = fade * (0.30f + 0.50f * light),
            style = Stroke(width = 3f * u),
        )
        val lineAlpha = fade * (0.10f + 0.30f * light)
        drawLine(Color.White, Offset(w * 0.5f, pitchTop), Offset(w * 0.5f, h), strokeWidth = 2f * u, alpha = lineAlpha)
        drawOval(
            Color.White,
            topLeft = Offset(w * 0.33f, h * 0.875f),
            size = Size(w * 0.34f, h * 0.12f),
            alpha = lineAlpha,
            style = Stroke(width = 2f * u),
        )
        glow(Offset(w * 0.5f, h * 0.88f), w * 0.70f, h * 0.22f, fade * 0.35f * light)

        // The near masts, then every beam over the stadium, then the near heads on top.
        for (i in 0..1) mast(lamps[i], IntroTimeline.lamp(t, i), w, h, u, fade)
        for (i in lamps.indices) beam(lamps[i], IntroTimeline.lamp(t, i), sweep, logo, w, h, fade)
        for (i in 0..1) head(lamps[i], IntroTimeline.lamp(t, i), w, h, u, fade)

        // Where the beams meet: a bloom behind the lockup and a thin flare through it.
        val bloom = IntroTimeline.bloom(t)
        if (bloom > 0f) {
            glow(logo, 520f * u, 330f * u, fade * 0.55f * bloom)
            glow(logo, 190f * u, 120f * u, fade * 0.35f * bloom)
        }
        val flare = IntroTimeline.flare(t)
        if (flare > 0f) glow(logo, 820f * u, 18f * u, fade * 0.9f * flare)
    }

    private fun DrawScope.mast(lamp: Lamp, level: Float, w: Float, h: Float, u: Float, fade: Float) {
        val x = lamp.x * w
        val top = lamp.y * h + 20f * u * lamp.scale
        val bottom = lamp.mastBottom * h
        drawLine(lerp(Mast, MastLit, level), Offset(x, top), Offset(x, bottom), strokeWidth = 7f * u * lamp.scale, cap = StrokeCap.Round, alpha = fade)
    }

    private fun DrawScope.head(lamp: Lamp, level: Float, w: Float, h: Float, u: Float, fade: Float) {
        val s = lamp.scale
        val c = Offset(lamp.x * w, lamp.y * h)
        val hw = 62f * u * s
        val hh = 30f * u * s
        drawRoundRect(
            lerp(HeadOff, HeadOn, level),
            topLeft = Offset(c.x - hw, c.y - hh),
            size = Size(hw * 2f, hh * 2f),
            cornerRadius = CornerRadius(12f * u * s),
            alpha = fade,
        )
        val bulb = lerp(BulbOff, BulbOn, level)
        val r = 8f * u * s
        for (row in 0..1) {
            for (col in 0..3) {
                val bx = c.x + (col - 1.5f) * 27f * u * s
                val by = c.y + (row - 0.5f) * 25f * u * s
                drawCircle(bulb, radius = r, center = Offset(bx, by), alpha = fade)
            }
        }
        if (level > 0f) glow(c, 210f * u * s, 170f * u * s, fade * 0.85f * level)
    }

    private fun DrawScope.beam(lamp: Lamp, level: Float, sweep: Float, logo: Offset, w: Float, h: Float, fade: Float) {
        if (level <= 0f) return
        val apex = Offset(lamp.x * w, lamp.y * h)
        val aim = Offset(
            lamp.aimX * w + (logo.x - lamp.aimX * w) * sweep,
            lamp.aimY * h + (logo.y - lamp.aimY * h) * sweep,
        )
        val dx = aim.x - apex.x
        val dy = aim.y - apex.y
        val length = hypot(dx, dy) * 1.45f
        if (length < 1f) return
        val endWidth = 2f * length * tan(lamp.spreadDeg * PI.toFloat() / 180f)
        // The texture's cone points down (+y); turn it to point at [aim].
        val degrees = atan2(-dx, dy) * 180f / PI.toFloat()
        rotate(degrees, pivot = apex) {
            drawImage(
                image = textures.beam,
                dstOffset = IntOffset((apex.x - endWidth / 2f).roundToInt(), apex.y.roundToInt()),
                dstSize = IntSize(endWidth.roundToInt().coerceAtLeast(1), length.roundToInt()),
                alpha = (fade * lamp.beamAlpha * level).coerceIn(0f, 1f),
                blendMode = BlendMode.Plus,
            )
        }
    }

    /** The warm glow texture stretched to an ellipse with radii [rx] and [ry], added to what is below. */
    private fun DrawScope.glow(center: Offset, rx: Float, ry: Float, alpha: Float) {
        if (alpha <= 0.003f || rx < 1f || ry < 1f) return
        drawTexture(textures.glow, center, rx, ry, alpha)
    }

    private fun DrawScope.drawTexture(image: ImageBitmap, center: Offset, rx: Float, ry: Float, alpha: Float) {
        drawImage(
            image = image,
            dstOffset = IntOffset((center.x - rx).roundToInt(), (center.y - ry).roundToInt()),
            dstSize = IntSize((rx * 2f).roundToInt(), (ry * 2f).roundToInt()),
            alpha = alpha.coerceIn(0f, 1f),
            blendMode = BlendMode.Plus,
        )
    }

    private companion object {
        const val STAR_COUNT = 72

        val Sky = Color(0xFF040A0E)
        val Stands = Color(0xFF07131A)
        val StandsLit = Color(0xFF0E2630)
        val Pitch = Color(0xFF071F1B)
        val PitchLit = Color(0xFF0F4136)
        /** The touchline's teal, the cyan of the Flutlicht mark. */
        val Rim = Color(0xFF6FE3D2)
        val Mast = Color(0xFF1C2A30)
        val MastLit = Color(0xFF4FBFB4)
        val HeadOff = Color(0xFF1E2B30)
        val HeadOn = Color(0xFFFFE6AE)
        val BulbOff = Color(0xFF2C3A40)
        val BulbOn = Color(0xFFFFFDF3)
    }
}

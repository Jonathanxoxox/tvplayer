package tv.own.owntv.features.onboarding.intro

import android.graphics.Bitmap
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import kotlin.math.abs
import kotlin.math.pow
import kotlin.math.sqrt

/**
 * The intro's soft shapes, rendered once on the CPU into small bitmaps and then only stretched,
 * rotated and faded each frame. Gradient brushes over large areas are expensive on weak boxes'
 * GPUs (see GradientTextures); scaled images are close to free.
 *
 * Both are warm floodlight white, so the scene needs no colour filter.
 */
internal class IntroTextures private constructor(
    /** A light cone: apex at the top centre, widening to the full width at the bottom, fading along its length. */
    val beam: ImageBitmap,
    /** A round glow: full in the middle, nothing at the rim. */
    val glow: ImageBitmap,
) {
    companion object {
        private const val BEAM_W = 64
        private const val BEAM_H = 256
        private const val GLOW_PX = 128

        // Floodlight white, a touch warm.
        private const val WARM_R = 255
        private const val WARM_G = 238
        private const val WARM_B = 204

        fun create(): IntroTextures = IntroTextures(beam(), glow())

        private fun smooth(x: Float): Float {
            val c = x.coerceIn(0f, 1f)
            return c * c * (3f - 2f * c)
        }

        private fun argb(alpha: Float): Int {
            val a = (alpha.coerceIn(0f, 1f) * 255f + 0.5f).toInt()
            return (a shl 24) or (WARM_R shl 16) or (WARM_G shl 8) or WARM_B
        }

        private fun beam(): ImageBitmap {
            val px = IntArray(BEAM_W * BEAM_H)
            for (y in 0 until BEAM_H) {
                val along = y / (BEAM_H - 1f)
                // The cone's half width at this point, as a fraction of the texture's half width.
                val half = 0.05f + 0.95f * along
                // Bright at the lamp, gone at the far end; the first few rows fade in so the apex is not a point.
                val lengthwise = (1f - along).pow(1.35f) * smooth(along / 0.03f)
                for (x in 0 until BEAM_W) {
                    val across = abs((x + 0.5f) / BEAM_W * 2f - 1f) / half
                    if (across >= 1f) continue
                    val edge = 1f - smooth((across - 0.45f) / 0.55f)
                    val core = 0.6f + 0.4f * (1f - across * across)
                    px[y * BEAM_W + x] = argb(lengthwise * edge * core)
                }
            }
            return bitmap(px, BEAM_W, BEAM_H)
        }

        private fun glow(): ImageBitmap {
            val px = IntArray(GLOW_PX * GLOW_PX)
            val c = (GLOW_PX - 1) / 2f
            for (y in 0 until GLOW_PX) {
                for (x in 0 until GLOW_PX) {
                    val dx = (x - c) / c
                    val dy = (y - c) / c
                    val r = sqrt(dx * dx + dy * dy)
                    if (r >= 1f) continue
                    // A soft falloff with a slightly brighter core, like light on haze.
                    val a = (1f - r).pow(2.2f) * 0.85f + (1f - smooth(r / 0.25f)) * 0.15f
                    px[y * GLOW_PX + x] = argb(a)
                }
            }
            return bitmap(px, GLOW_PX, GLOW_PX)
        }

        private fun bitmap(px: IntArray, w: Int, h: Int): ImageBitmap {
            val bitmap = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
            bitmap.setPixels(px, 0, w, 0, 0, w, h)
            return bitmap.asImageBitmap()
        }
    }
}

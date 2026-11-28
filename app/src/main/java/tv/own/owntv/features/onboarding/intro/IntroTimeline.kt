package tv.own.owntv.features.onboarding.intro

import kotlin.math.max
import kotlin.math.min

/**
 * The brand intro's timeline, in seconds from the moment the sound logo starts.
 *
 * A dark stadium at night: four floodlights strike one after another (each with a relay clunk in the
 * sound), their beams swing in and meet in the middle, the Mayo TV lockup appears where they meet as the
 * chord resolves and the two-note motif plays, the tagline follows, and the scene dissolves into the
 * setup page while the lockup glides to its place in the page's corner.
 *
 * The sound (res/raw/mayo_intro.ogg, made by tools/sound/mayo_intro.py) uses the same times; change
 * both together. Every function here is pure, so the timing can be tested without a device.
 */
internal object IntroTimeline {
    /** The whole intro, hand-over included. */
    const val TOTAL = 5.6f

    /** The stars and the dark stadium come up out of black. */
    const val NIGHT_IN = 0.6f

    /** When each floodlight strikes: near left, near right, far left, far right. */
    val LAMP_ON = floatArrayOf(0.40f, 0.70f, 1.00f, 1.20f)

    /** A lamp's flicker as it strikes, then it warms up to full over [LAMP_WARM]. */
    private const val FLICKER = 0.13f
    private const val LAMP_WARM = 0.6f

    /** The beams swing from the pitch towards the centre. */
    const val SWEEP_START = 1.35f
    const val SWEEP_END = 2.40f

    /** The beams meet: the lockup appears, the chord resolves. */
    const val REVEAL = 2.40f
    private const val LOGO_IN = 0.55f

    const val TAGLINE_START = 3.05f
    private const val TAGLINE_IN = 0.55f

    /** The skip hint, once the show has clearly started. */
    private const val HINT_START = 1.4f
    private const val HINT_IN = 0.5f

    /** The scene dissolves into the setup page; the lockup moves to the page's corner. */
    const val HANDOVER_START = 4.8f

    /** The moment drawn when motion is off: everything lit, the lockup and tagline in place. */
    const val STILL_FRAME = 3.8f

    fun clamp01(x: Float) = min(1f, max(0f, x))

    /** 0 before [from], 1 after [from] + [length], eased in between. */
    fun progress(t: Float, from: Float, length: Float): Float = if (length <= 0f) (if (t >= from) 1f else 0f) else clamp01((t - from) / length)

    fun smooth(x: Float): Float = clamp01(x).let { it * it * (3f - 2f * it) }
    fun easeOut(x: Float): Float = 1f - (1f - clamp01(x)).let { it * it * it }
    fun easeInOut(x: Float): Float = clamp01(x).let { if (it < 0.5f) 4f * it * it * it else 1f - (-2f * it + 2f).let { v -> v * v * v } / 2f }

    /** How far the night has come up out of black, 0..1. */
    fun night(t: Float): Float = smooth(progress(t, 0f, NIGHT_IN))

    /**
     * How bright floodlight [index] is, 0..1: off, then a short strike flicker (on, almost off, on),
     * then warming up from 75% to full.
     */
    fun lamp(t: Float, index: Int): Float {
        val on = LAMP_ON[index]
        val dt = t - on
        if (dt < 0f) return 0f
        if (dt < FLICKER) {
            return when {
                dt < 0.045f -> 0.85f
                dt < 0.085f -> 0.18f
                else -> 0.9f
            }
        }
        return 0.75f + 0.25f * smooth((dt - FLICKER) / LAMP_WARM)
    }

    /** All four lamps together, 0..1: how lit the stadium is. */
    fun stadiumLight(t: Float): Float {
        var sum = 0f
        for (i in LAMP_ON.indices) sum += lamp(t, i)
        return sum / LAMP_ON.size
    }

    /** How far the beams have swung from the pitch to the centre, 0..1. */
    fun sweep(t: Float): Float = easeInOut(progress(t, SWEEP_START, SWEEP_END - SWEEP_START))

    /** The flash where the beams meet: up fast at [REVEAL], settling to a steady glow behind the lockup. */
    fun bloom(t: Float): Float {
        val dt = t - REVEAL + 0.12f
        if (dt <= 0f) return 0f
        val rise = smooth(dt / 0.22f)
        val settle = 0.45f + 0.55f * (1f - smooth((dt - 0.22f) / 0.9f))
        return rise * settle
    }

    /** The thin horizontal flare at the moment the beams meet. */
    fun flare(t: Float): Float {
        val dt = t - REVEAL + 0.05f
        if (dt <= 0f) return 0f
        return smooth(dt / 0.12f) * (1f - smooth((dt - 0.12f) / 0.75f))
    }

    /** The lockup coming into the light, 0..1. */
    fun logo(t: Float): Float = easeOut(progress(t, REVEAL, LOGO_IN))

    fun tagline(t: Float): Float = easeOut(progress(t, TAGLINE_START, TAGLINE_IN))

    fun hint(t: Float): Float = smooth(progress(t, HINT_START, HINT_IN))

    /** The hand-over to the setup page, 0..1. */
    fun handover(t: Float): Float = easeInOut(progress(t, HANDOVER_START, TOTAL - HANDOVER_START))

    /** The night scene's opacity: it dissolves into the setup page's background during the hand-over. */
    fun scene(t: Float): Float = night(t) * (1f - smooth(progress(t, HANDOVER_START, (TOTAL - HANDOVER_START) * 0.8f)))

    /** Text that leaves early in the hand-over (tagline, hint) so only the lockup travels. */
    fun textOut(t: Float): Float = 1f - smooth(progress(t, HANDOVER_START, (TOTAL - HANDOVER_START) * 0.4f))
}

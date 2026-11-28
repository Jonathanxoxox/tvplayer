package tv.own.owntv.features.onboarding.intro

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** The brand intro's timing, without a device. */
class IntroTimelineTest {

    private val eps = 1e-4f

    @Test
    fun `the intro stays short enough for the smoke tour`() {
        // The tour waits up to about 8 s for the first setup page and presses OK after about 4 s.
        assertTrue(IntroTimeline.TOTAL in 4.5f..7f)
        assertTrue(IntroTimeline.HANDOVER_START < IntroTimeline.TOTAL)
    }

    @Test
    fun `lamps strike in order and are dark before`() {
        val on = IntroTimeline.LAMP_ON
        for (i in on.indices) {
            assertEquals(0f, IntroTimeline.lamp(on[i] - 0.01f, i), eps)
            assertTrue(IntroTimeline.lamp(on[i] + 0.01f, i) > 0.5f)
            assertEquals(1f, IntroTimeline.lamp(on[i] + 2f, i), eps)
            if (i > 0) assertTrue(on[i] > on[i - 1])
        }
        // All lamps are on before the beams start to swing in.
        assertTrue(on.last() < IntroTimeline.SWEEP_START)
    }

    @Test
    fun `the beams meet when the lockup appears`() {
        assertEquals(0f, IntroTimeline.sweep(IntroTimeline.SWEEP_START), eps)
        assertEquals(1f, IntroTimeline.sweep(IntroTimeline.REVEAL), eps)
        assertEquals(0f, IntroTimeline.logo(IntroTimeline.REVEAL), eps)
        assertEquals(1f, IntroTimeline.logo(IntroTimeline.TAGLINE_START), eps)
    }

    @Test
    fun `the still frame shows the finished picture`() {
        val t = IntroTimeline.STILL_FRAME
        assertEquals(1f, IntroTimeline.scene(t), eps)
        assertEquals(1f, IntroTimeline.logo(t), eps)
        assertEquals(1f, IntroTimeline.tagline(t), eps)
        assertEquals(1f, IntroTimeline.stadiumLight(t), eps)
        assertEquals(0f, IntroTimeline.handover(t), eps)
        assertTrue(t < IntroTimeline.HANDOVER_START)
    }

    @Test
    fun `the scene has dissolved and the lockup arrived at the end`() {
        val end = IntroTimeline.TOTAL
        assertEquals(0f, IntroTimeline.scene(end), eps)
        assertEquals(1f, IntroTimeline.handover(end), eps)
        assertEquals(0f, IntroTimeline.textOut(end), eps)
        assertEquals(0f, IntroTimeline.night(0f), eps)
    }

    @Test
    fun `every curve stays within 0 and 1`() {
        var t = -1f
        while (t <= IntroTimeline.TOTAL + 1f) {
            val values = listOf(
                IntroTimeline.night(t), IntroTimeline.stadiumLight(t), IntroTimeline.sweep(t), IntroTimeline.bloom(t),
                IntroTimeline.flare(t), IntroTimeline.logo(t), IntroTimeline.tagline(t), IntroTimeline.hint(t),
                IntroTimeline.handover(t), IntroTimeline.scene(t), IntroTimeline.textOut(t),
            )
            values.forEach { assertTrue("$it at $t", it in 0f..1f) }
            t += 0.01f
        }
    }
}

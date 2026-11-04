package tv.own.owntv.player

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** Which measured frame rates the per-channel memory may keep. */
class FrameRateMemoryTest {

    @Test
    fun `a reading at the display's own rate may be its ceiling`() {
        // 60p on a 50 Hz display counts at most 50 frames a second.
        assertFalse(FrameRateMemory.trustworthy(50f, 50f))
        assertFalse(FrameRateMemory.trustworthy(59.94f, 59.94006f))
    }

    @Test
    fun `readings away from the display's rate are kept`() {
        assertTrue(FrameRateMemory.trustworthy(60f, 50f))
        assertTrue(FrameRateMemory.trustworthy(50f, 60.000004f))
        assertTrue(FrameRateMemory.trustworthy(25f, 50f))
    }
}

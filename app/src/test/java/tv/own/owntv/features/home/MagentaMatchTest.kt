package tv.own.owntv.features.home

import org.junit.Assert.assertEquals
import org.junit.Test
import tv.own.owntv.features.home.MagentaMatch.Slot
import tv.own.owntv.features.home.MagentaMatch.Verdict

/** When MagentaTV's "on now" and the provider's agree. */
class MagentaMatchTest {
    private val min = 60_000L
    private fun slot(title: String, startMin: Long, stopMin: Long) = Slot(title, startMin * min, stopMin * min)

    @Test
    fun `a missing side or a blank title is unknown`() {
        assertEquals(Verdict.UNKNOWN, MagentaMatch.verdict(null, slot("A", 0, 60)))
        assertEquals(Verdict.UNKNOWN, MagentaMatch.verdict(slot("A", 0, 60), null))
        assertEquals(Verdict.UNKNOWN, MagentaMatch.verdict(slot("  ", 0, 60), slot("A", 0, 60)))
        assertEquals(Verdict.UNKNOWN, MagentaMatch.verdict(slot("A", 0, 60), slot("", 0, 60)))
    }

    @Test
    fun `same title and time matches, even with other case and punctuation`() {
        assertEquals(Verdict.MATCH, MagentaMatch.verdict(slot("Tatort: Borowski!", 0, 90), slot("tatort borowski", 2, 91)))
    }

    @Test
    fun `an episode title on one side still matches`() {
        assertEquals(Verdict.MATCH, MagentaMatch.verdict(slot("MacGyver", 0, 60), slot("MacGyver: Der Auftrag", 0, 60)))
    }

    @Test
    fun `a short contained key is not enough`() {
        assertEquals(Verdict.MISMATCH, MagentaMatch.verdict(slot("Tag", 0, 60), slot("Tagesschau", 0, 60)))
    }

    @Test
    fun `different shows at the same time mismatch`() {
        assertEquals(Verdict.MISMATCH, MagentaMatch.verdict(slot("Baywatch", 0, 60), slot("MacGyver", 0, 60)))
        assertEquals(Verdict.MISMATCH, MagentaMatch.verdict(slot("Baywatch", 0, 60), slot("MacGyver", 3, 63)))
    }

    @Test
    fun `same title at another time mismatches`() {
        assertEquals(Verdict.MISMATCH, MagentaMatch.verdict(slot("MacGyver", 0, 60), slot("MacGyver", 60, 120)))
    }

    @Test
    fun `overlap must be at least half of the shorter programme`() {
        // 30 of 60 minutes shared: exactly half.
        assertEquals(Verdict.MATCH, MagentaMatch.verdict(slot("Show", 0, 60), slot("Show", 30, 90)))
        assertEquals(Verdict.MISMATCH, MagentaMatch.verdict(slot("Show", 0, 60), slot("Show", 31, 91)))
        // A short programme inside a long one counts against the shorter one.
        assertEquals(Verdict.MATCH, MagentaMatch.verdict(slot("Show", 0, 120), slot("Show", 10, 20)))
    }

    @Test
    fun `overall needs enough observations and few mismatches`() {
        val m = Verdict.MATCH
        val x = Verdict.MISMATCH
        val u = Verdict.UNKNOWN
        assertEquals(Verdict.UNKNOWN, MagentaMatch.overall(emptyList()))
        assertEquals(Verdict.UNKNOWN, MagentaMatch.overall(listOf(m, m, u, u)))
        assertEquals(Verdict.MATCH, MagentaMatch.overall(listOf(m, m, m, u)))
        assertEquals(Verdict.MATCH, MagentaMatch.overall(listOf(m, m, m, x)))
        assertEquals(Verdict.UNKNOWN, MagentaMatch.overall(listOf(m, m, x)))
        assertEquals(Verdict.UNKNOWN, MagentaMatch.overall(listOf(m, x)))
        assertEquals(Verdict.MISMATCH, MagentaMatch.overall(listOf(x, x, m)))
        assertEquals(Verdict.MISMATCH, MagentaMatch.overall(listOf(x, x, x, u)))
    }
}

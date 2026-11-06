package tv.own.owntv.features.home

import org.junit.Assert.assertEquals
import org.junit.Test
import tv.own.owntv.core.sports.model.Team

/** Which colours the Spotlight paints a match in when it has no programme picture. */
class ClubColorsTest {
    private fun team(color: String?, alternate: String? = null) = Team("1", "Club", color = color, alternateColor = alternate)

    @Test
    fun `each club in its own colour`() {
        val (h, a) = ClubColors.pair(team("0a3f86"), team("00a651"))
        assertEquals(ClubColors.parse("0a3f86"), h)
        assertEquals(ClubColors.parse("00a651"), a)
    }

    @Test
    fun `a white first colour gives way to the second`() {
        val (h, _) = ClubColors.pair(team("ffffff", "c8102e"), team("000000"))
        assertEquals(ClubColors.parse("c8102e"), h)
    }

    @Test
    fun `two red clubs - the away side takes its second colour`() {
        val (_, a) = ClubColors.pair(team("d20515"), team("dc052d", "0b0b0b"))
        assertEquals(ClubColors.parse("0b0b0b"), a)
    }

    @Test
    fun `no colours known - neutral ones, still told apart`() {
        val (h, a) = ClubColors.pair(team(null), team(null))
        assertEquals(false, h == a)
    }
}

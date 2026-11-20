package tv.own.owntv.features.home

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** ESPN's English club names against MagentaTV's German match titles. */
class MagentaTeamNamesTest {
    private val title = "Live BL: FC Bayern München - 1. FC Köln, Flutlicht-Freitag, 10. Spieltag"

    @Test
    fun `English city names find the German title`() {
        assertTrue(MagentaNames.namesTeam(listOf("Cologne", "Cologne", "KOE"), title))
        assertTrue(MagentaNames.namesTeam(listOf("Bayern Munich", "Bayern", "MUN"), title))
        assertTrue(MagentaNames.namesTeam(listOf("AC Milan", "Milan"), "LIVE: AC Mailand - Inter"))
    }

    @Test
    fun `another club does not`() {
        assertFalse(MagentaNames.namesTeam(listOf("Borussia Dortmund", "Dortmund", "DOR"), title))
    }
}

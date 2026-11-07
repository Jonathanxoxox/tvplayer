package tv.own.owntv.features.sports

import org.json.JSONArray
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/** Which of TheSportsDB's events is ESPN's match, for the sports page's posters. */
class SportPosterMatchTest {
    private val events = JSONArray(
        """[
          {"strHomeTeam":"Atletico Madrid","strAwayTeam":"Real Betis","strThumb":"https://r2.thesportsdb.com/a.jpg"},
          {"strHomeTeam":"Inter Milan","strAwayTeam":"AC Milan","strThumb":"http://r2.thesportsdb.com/derby.jpg"},
          {"strHomeTeam":"Real Madrid","strAwayTeam":"Barcelona","strThumb":""}
        ]""",
    )

    @Test
    fun `ESPN's names find TheSportsDB's event, accents and long names included`() {
        assertEquals(
            "https://r2.thesportsdb.com/a.jpg",
            SportPosterMatch.pick(events, listOf("Atlético Madrid", "Atlético", "ATM"), listOf("Real Betis", "Betis", "BET")),
        )
        assertEquals(
            "https://r2.thesportsdb.com/derby.jpg",
            SportPosterMatch.pick(events, listOf("Internazionale", "Inter", "INT"), listOf("AC Milan", "Milan", "MIL")),
        )
    }

    @Test
    fun `home and away are not swapped, and an event without a picture gives none`() {
        assertNull(SportPosterMatch.pick(events, listOf("AC Milan", "Milan", "MIL"), listOf("Internazionale", "Inter", "INT")))
        assertNull(SportPosterMatch.pick(events, listOf("Real Madrid", "Real Madrid", "RMA"), listOf("Barcelona", "Barcelona", "BAR")))
    }
}

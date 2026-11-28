package tv.own.owntv.features.onboarding.football

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import tv.own.owntv.core.sports.FollowedTeam
import tv.own.owntv.core.sports.SportsConfig
import tv.own.owntv.core.sports.model.Sport
import tv.own.owntv.core.sports.model.Team
import java.util.Locale

class FootballPicksTest {

    private val bundesliga = "soccer/ger.1"
    private val pokal = "soccer/ger.dfb_pokal"
    private val champions = "soccer/uefa.champions"
    private val premierLeague = "soccer/eng.1"
    private val nba = "basketball/nba"

    @Test
    fun regionFromLocale() {
        assertEquals("DE", FootballPicks.regionOf(Locale.GERMANY))
        assertEquals("DE", FootballPicks.regionOf(Locale.GERMAN))
        assertEquals("AT", FootballPicks.regionOf(Locale("de", "AT")))
        assertEquals("", FootballPicks.regionOf(Locale.ENGLISH))
    }

    @Test
    fun germanLeaguesComeFirstInGermany() {
        val keys = FootballPicks.offered("DE", german = true).map { it.key }
        assertEquals(listOf(bundesliga, "soccer/ger.2", pokal), keys.take(3))
        assertTrue(champions in keys)
        assertEquals(keys.size, keys.distinct().size)
        assertTrue(keys.all { it.startsWith("soccer/") })
    }

    @Test
    fun germanSpeakersElsewhereGetTheirLeagueThenTheGermanOnes() {
        val keys = FootballPicks.offered("AT", german = true).map { it.key }
        assertEquals("soccer/aut.1", keys.first())
        assertTrue(keys.indexOf(bundesliga) < keys.indexOf(champions))
    }

    @Test
    fun englandStartsWithItsOwnLeague() {
        val keys = FootballPicks.offered("GB", german = false).map { it.key }
        assertEquals(premierLeague, keys.first())
        assertTrue(keys.indexOf(bundesliga) > keys.indexOf(champions))
    }

    @Test
    fun unknownRegionStillOffersTheBigCompetitions() {
        val keys = FootballPicks.offered("", german = false).map { it.key }
        assertEquals(champions, keys.first())
        assertTrue(premierLeague in keys && bundesliga in keys)
    }

    @Test
    fun preselectionIsTopLeagueCupAndChampionsLeague() {
        assertEquals(listOf(bundesliga, pokal, champions), FootballPicks.preselected("DE", emptyList()))
        assertEquals(listOf(champions), FootballPicks.preselected("", emptyList()))
    }

    @Test
    fun anEarlierChoiceIsKept() {
        assertEquals(listOf(premierLeague), FootballPicks.preselected("DE", listOf(nba, premierLeague)))
        val offered = FootballPicks.offered("DE", german = true, alsoChosen = listOf("soccer/sco.1", nba))
        assertTrue(offered.any { it.key == "soccer/sco.1" })
        assertFalse(offered.any { it.key == nba })
    }

    @Test
    fun choicesAreSavedWithoutTouchingOtherSports() {
        val basketballTeam = FollowedTeam(Sport.BASKETBALL, "1", "Lakers")
        val oldClub = FollowedTeam(Sport.SOCCER, "127", "Hamburg SV", "HSV")
        val config = SportsConfig(leagues = listOf(nba, pokal), teams = listOf(oldClub, basketballTeam))

        val leagues = FootballPicks.withLeagues(config, listOf(champions, bundesliga))
        assertEquals(listOf(champions, bundesliga, nba), leagues.leagues)

        val liverpool = FollowedTeam(Sport.SOCCER, "364", "Liverpool", "LIV")
        val teams = FootballPicks.withTeams(config, listOf(liverpool))
        assertEquals(listOf(liverpool, basketballTeam), teams.teams)

        val none = FootballPicks.withoutFootball(config)
        assertEquals(listOf(nba), none.leagues)
        assertEquals(listOf(basketballTeam), none.teams)
    }

    @Test
    fun searchFindsGermanSpellingsAndAbbreviations() {
        val bayern = Team("132", "Bayern Munich", "Bayern", "MUN", location = "Munich")
        val hsv = Team("127", "Hamburg SV", "Hamburg", "HSV", location = "Hamburg")
        val germany = Team("481", "Germany", "Germany", "GER")
        assertTrue(FootballPicks.matches(bayern, "München"))
        assertTrue(FootballPicks.matches(bayern, "bay"))
        assertTrue(FootballPicks.matches(hsv, "HSV"))
        assertTrue(FootballPicks.matches(germany, "Deutschland"))
        assertFalse(FootballPicks.matches(hsv, "Bayern"))
        assertFalse(FootballPicks.matches(hsv, "h"))
    }

    @Test
    fun gridShowsLeaguesBeforeCupsAndEachTeamOnce() {
        val bayern = Team("132", "Bayern Munich")
        val hsv = Team("127", "Hamburg SV")
        val real = Team("86", "Real Madrid")
        val loaded = mapOf(
            bundesliga to listOf(bayern, hsv),
            pokal to listOf(bayern, hsv, Team("999", "Amateur Club")),
            champions to listOf(real, bayern),
        )
        val sections = FootballPicks.sections(listOf(bundesliga, pokal, champions), loaded)
        assertEquals(listOf(bundesliga, champions), sections.map { it.league.key })
        assertEquals(listOf("86"), sections[1].teams.map { it.id })

        val cupsOnly = FootballPicks.sections(listOf(pokal), loaded)
        assertEquals(listOf(pokal), cupsOnly.map { it.league.key })

        val found = FootballPicks.search("amateur", listOf(bundesliga, pokal, champions), loaded)
        assertEquals(listOf("999"), found.map { it.id })
    }
}

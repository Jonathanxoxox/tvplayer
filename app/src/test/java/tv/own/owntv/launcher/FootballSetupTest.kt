package tv.own.owntv.launcher

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class FootballSetupTest {

    @Test
    fun aNewInstallGetsNoFixedTeams() {
        // Neither flag was ever written: nothing is seeded and no names are matched.
        assertFalse(FootballSetup.defaultTeamsPending(leaguesDone = false, teamsDone = false))
    }

    @Test
    fun anOlderInstallFinishesItsLookup() {
        assertTrue(FootballSetup.defaultTeamsPending(leaguesDone = true, teamsDone = false))
    }

    @Test
    fun aFinishedInstallIsLeftAlone() {
        assertFalse(FootballSetup.defaultTeamsPending(leaguesDone = true, teamsDone = true))
    }
}

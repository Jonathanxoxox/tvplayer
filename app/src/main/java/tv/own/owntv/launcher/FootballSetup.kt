package tv.own.owntv.launcher

import android.content.Context
import android.util.Log
import kotlinx.coroutines.flow.first
import org.koin.core.context.GlobalContext
import tv.own.owntv.core.settings.SettingsRepository
import tv.own.owntv.core.sports.FollowedTeam
import tv.own.owntv.core.sports.SportsCatalog
import tv.own.owntv.core.sports.SportsSource
import tv.own.owntv.core.sports.TeamNames
import tv.own.owntv.core.sports.model.Sport

/**
 * Football in Settings → Sports for "Für dich", the football group and the match pages.
 *
 * A new install starts with no football at all: the first-run journey's football chapter asks which
 * competitions and teams the user follows and saves them in the profile's sports settings, exactly
 * as Settings → Sports does (Sports and leagues, My teams). Nothing is chosen on the user's behalf.
 *
 * Installs that were set up before that chapter existed were given a fixed set of leagues and teams
 * ([DEFAULT_LEAGUES], [DEFAULT_TEAMS]) once. They keep exactly what they have: [seed] only finishes
 * that one-time setup where it had begun ([LEAGUES_DONE] written) but the teams could not be looked
 * up yet (no network), and until it has worked [useDefaultTeams] lets [FootballMatcher.from] match
 * those teams by name. On any other install neither does anything.
 */
internal object FootballSetup {

    /** A team of the fixed set older installs were given: its name as ESPN writes it, and where to look for it. */
    class DefaultTeam(val name: String, val leagues: List<String>)

    /** The teams older installs were given once; never applied to a new install. */
    val DEFAULT_TEAMS = listOf(
        DefaultTeam("Hamburg SV", listOf("soccer/ger.1", "soccer/ger.2")),
        DefaultTeam("Borussia Dortmund", listOf("soccer/ger.1")),
        DefaultTeam("Bayern Munich", listOf("soccer/ger.1")),
        DefaultTeam("Liverpool", listOf("soccer/eng.1")),
        DefaultTeam("Germany", listOf("soccer/uefa.nations", "soccer/fifa.worldq.uefa", "soccer/uefa.euro")),
        DefaultTeam("Denmark", listOf("soccer/uefa.nations", "soccer/fifa.worldq.uefa", "soccer/uefa.euro")),
        DefaultTeam("Barcelona", listOf("soccer/esp.1")),
    )

    /** The competitions older installs were given once; never applied to a new install. */
    val DEFAULT_LEAGUES = listOf(
        "soccer/ger.1", "soccer/uefa.champions", "soccer/ger.dfb_pokal", "soccer/eng.1", "soccer/esp.1",
        "soccer/uefa.nations", "soccer/fifa.worldq.uefa", "soccer/uefa.euro", "soccer/fifa.world", "soccer/fifa.friendly",
    )

    private const val PREFS = "tvplayer"
    private const val LEAGUES_DONE = "football_leagues_v1"
    private const val TEAMS_DONE = "football_teams_v1"
    const val GROUP_ON = "football_group_on"

    fun prefs(context: Context) = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    /** The football group in the channel list ("Fußball jetzt & heute"). On unless switched off. */
    fun groupOn(context: Context) = prefs(context).getBoolean(GROUP_ON, true)

    fun set(context: Context, key: String, on: Boolean) {
        prefs(context).edit().putBoolean(key, on).apply()
        LauncherRowsWorker.refreshSoon(context, delaySeconds = 1)
    }

    /**
     * True only on an install that was given the fixed teams and whose lookup has not worked yet:
     * those teams are matched by name meanwhile. Always false on an install set up through the
     * first-run journey.
     */
    fun useDefaultTeams(context: Context): Boolean = prefs(context).let { defaultTeamsPending(it.getBoolean(LEAGUES_DONE, false), it.getBoolean(TEAMS_DONE, false)) }

    /**
     * Whether an install still waits for its fixed teams, from its two flags: only where the one-time
     * setup had begun ([leaguesDone]) and the lookup has not worked yet. What [useDefaultTeams] and
     * [seed] decide on.
     */
    fun defaultTeamsPending(leaguesDone: Boolean, teamsDone: Boolean): Boolean = leaguesDone && !teamsDone

    /**
     * Finishes the one-time setup of an older install: looks its fixed teams up in the leagues' team
     * lists (the call the My teams search makes) and saves them, unless the profile follows teams of
     * its own by now. Returns at once on every other install, a new one included.
     */
    suspend fun seed(context: Context) {
        val prefs = prefs(context)
        if (!defaultTeamsPending(prefs.getBoolean(LEAGUES_DONE, false), prefs.getBoolean(TEAMS_DONE, false))) return
        val koin = GlobalContext.get()
        val settings = koin.get<SettingsRepository>()
        val profileId = settings.activeProfileId.first { it >= 0L }
        val config = settings.activeSportsConfig.first()
        if (config.teams.isNotEmpty()) {
            prefs.edit().putBoolean(TEAMS_DONE, true).apply()
            return
        }

        val source = koin.get<SportsSource>()
        val lists = HashMap<String, List<tv.own.owntv.core.sports.model.Team>>()
        suspend fun teamsOf(key: String) = lists.getOrPut(key) {
            SportsCatalog.league(key)?.let { league -> runCatching { source.teams(league) }.getOrDefault(emptyList()) }.orEmpty()
        }
        val found = DEFAULT_TEAMS.mapNotNull { wanted ->
            val tokens = TeamNames.tokens(wanted.name).toSet()
            wanted.leagues.firstNotNullOfOrNull { key ->
                val teams = teamsOf(key)
                teams.firstOrNull { TeamNames.tokens(it.name).toSet() == tokens } ?: teams.firstOrNull { TeamNames.tokens(it.name).containsAll(tokens) }
            }?.let { FollowedTeam(Sport.SOCCER, it.id, it.name, it.abbreviation) }
        }.distinctBy { it.key }
        if (found.isEmpty()) {
            Log.i(TAG, "default teams not found yet (no network?), trying again on the next start")
            return
        }
        settings.updateSportsConfig(profileId) { c -> if (c.teams.isEmpty()) c.copy(teams = found) else c }
        prefs.edit().putBoolean(TEAMS_DONE, true).apply()
        LauncherRowsWorker.refreshSoon(context, delaySeconds = 1)
    }

    private const val TAG = "FootballSetup"
}

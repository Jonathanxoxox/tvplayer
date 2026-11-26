package tv.own.owntv.features.onboarding.football

import tv.own.owntv.core.sports.FollowedTeam
import tv.own.owntv.core.sports.SportsCatalog
import tv.own.owntv.core.sports.SportsConfig
import tv.own.owntv.core.sports.TeamNames
import tv.own.owntv.core.sports.model.League
import tv.own.owntv.core.sports.model.Sport
import tv.own.owntv.core.sports.model.Team
import java.util.Locale

/**
 * The rules behind the football chapter, without Android: which competitions are offered and
 * preselected for a region, how the team grid is put together, the team search, and how a choice is
 * written into the profile's [SportsConfig]. Only football is touched; the profile's other sports are
 * kept as they are.
 */
internal object FootballPicks {

    /** International club and national-team competitions offered everywhere, most watched first. */
    private val INTERNATIONAL = listOf(
        "soccer/uefa.champions",
        "soccer/uefa.europa",
        "soccer/uefa.europa.conf",
        "soccer/uefa.nations",
        "soccer/fifa.world",
        "soccer/uefa.euro",
    )

    /** Europe's big leagues, offered after the user's own country. */
    private val TOP_LEAGUES = listOf(
        "soccer/ger.1",
        "soccer/eng.1",
        "soccer/esp.1",
        "soccer/ita.1",
        "soccer/fra.1",
        "soccer/ned.1",
        "soccer/por.1",
        "soccer/tur.1",
    )

    /** The Champions League: preselected everywhere. */
    private val CHAMPIONS: String get() = INTERNATIONAL.first()

    /** More teams than this in one league section are left to the search. */
    const val MAX_TEAMS_PER_SECTION = 48

    private val GERMANY: String = Locale.GERMANY.country

    /**
     * The region a [locale] stands for, as an ISO country code: its country, or Germany for German
     * without a country. Empty when unknown.
     */
    fun regionOf(locale: Locale): String = locale.country.uppercase(Locale.ROOT).let { country ->
        if (country.isEmpty() && isGerman(locale)) GERMANY else country
    }

    fun isGerman(locale: Locale): Boolean = locale.language == Locale.GERMAN.language

    /**
     * The competitions on the first page, in order: the [region]'s own (its two top leagues and its
     * main cup), the German ones next for a German speaker elsewhere, then the international
     * competitions and Europe's big leagues. [alsoChosen] (leagues the profile already follows) are
     * kept, at the end, so going back never hides a choice.
     */
    fun offered(region: String, german: Boolean, alsoChosen: List<String> = emptyList()): List<League> {
        val soccer = SportsCatalog.leagues.filter { it.sport == Sport.SOCCER }
        val keys = LinkedHashSet<String>()
        keys += domestic(soccer, region)
        if (german && region != GERMANY) keys += domestic(soccer, GERMANY)
        keys += INTERNATIONAL
        keys += TOP_LEAGUES
        keys += alsoChosen.filter(::isFootball)
        return keys.mapNotNull { SportsCatalog.league(it) }
    }

    /** A country's two top leagues and its first cup, in catalogue order (top league first). */
    private fun domestic(soccer: List<League>, region: String): List<String> {
        if (region.isEmpty()) return emptyList()
        val own = soccer.filter { it.country == region }
        return (own.filter { it.hasTable }.take(2) + own.filter { !it.hasTable }.take(1)).map { it.key }
    }

    /**
     * What is ticked when the page first opens: the region's top league and main cup and the
     * Champions League. A profile that already follows football keeps its own choice.
     */
    fun preselected(region: String, followed: List<String>): List<String> {
        val own = followed.filter(::isFootball)
        if (own.isNotEmpty()) return own
        val soccer = SportsCatalog.leagues.filter { it.sport == Sport.SOCCER && it.country == region && region.isNotEmpty() }
        return listOfNotNull(
            soccer.firstOrNull { it.hasTable }?.key,
            soccer.firstOrNull { !it.hasTable }?.key,
            CHAMPIONS,
        )
    }

    /** [chosen] in the order the page offers them. */
    fun inOfferedOrder(chosen: Collection<String>, offered: List<League>): List<String> {
        val order = offered.map { it.key }
        return chosen.distinct().sortedBy { k -> order.indexOf(k).let { if (it < 0) Int.MAX_VALUE else it } }
    }

    fun isFootball(leagueKey: String): Boolean =
        (SportsCatalog.league(leagueKey)?.sport ?: Sport.ofPath(leagueKey.substringBefore('/'))) == Sport.SOCCER

    /** One heading of the team grid: a competition and the teams shown under it. */
    class Section(val league: League, val teams: List<Team>)

    /**
     * The team grid for the [chosen] competitions, from the team lists loaded so far. Leagues come
     * first in the user's order; cups only when no league with a table is chosen (their teams are in
     * the leagues anyway). A team shows once, under its first competition.
     */
    fun sections(chosen: List<String>, loaded: Map<String, List<Team>>): List<Section> {
        val leagues = chosen.mapNotNull { SportsCatalog.league(it) }.filter { it.sport == Sport.SOCCER }
        val withTable = leagues.filter { it.hasTable }
        val shown = withTable.ifEmpty { leagues }
        val seen = HashSet<String>()
        return shown.mapNotNull { league ->
            val teams = loaded[league.key].orEmpty()
                .filter { !it.isAthlete && seen.add(it.id) }
                .take(MAX_TEAMS_PER_SECTION)
            if (teams.isEmpty()) null else Section(league, teams)
        }
    }

    /** The leagues whose team lists the grid and the search need, in the order to fetch them. */
    fun toLoad(chosen: List<String>): List<String> = chosen.filter(::isFootball).distinct()

    /**
     * The team search: every word of [query] starts a word of the team's name, short name or city,
     * compared the way guide titles are ("münchen" finds "Bayern Munich", "deutschland" finds
     * "Germany"), or [query] is the team's abbreviation ("HSV"). Fewer than two letters find nothing.
     */
    fun matches(team: Team, query: String): Boolean {
        val q = query.trim()
        if (q.length < 2) return false
        if (team.abbreviation.isNotBlank() && team.abbreviation.equals(q, ignoreCase = true)) return true
        val words = TeamNames.tokens(q)
        if (words.isEmpty()) return team.name.contains(q, ignoreCase = true)
        val own = (TeamNames.tokens(team.name) + TeamNames.tokens(team.shortName) + TeamNames.tokens(team.location.orEmpty())).toSet()
        return words.all { w -> own.any { it.startsWith(w) } }
    }

    /** The search over every loaded team list, each team once, in the order of the lists. */
    fun search(query: String, chosen: List<String>, loaded: Map<String, List<Team>>, limit: Int = 12): List<Team> {
        val seen = HashSet<String>()
        return chosen.asSequence()
            .flatMap { loaded[it].orEmpty().asSequence() }
            .filter { !it.isAthlete && matches(it, query) && seen.add(it.id) }
            .take(limit)
            .toList()
    }

    /** A team of a football competition as the profile follows it (the form My teams saves). */
    fun follow(team: Team): FollowedTeam = FollowedTeam(Sport.SOCCER, team.id, team.name, team.abbreviation)

    /** [chosen] become the profile's football competitions, first and in this order; other sports stay. */
    fun withLeagues(config: SportsConfig, chosen: List<String>): SportsConfig =
        config.copy(leagues = chosen.distinct() + config.leagues.filter { !isFootball(it) && it !in chosen })

    /** [chosen] become the profile's football teams, first and in this order; other sports' teams stay. */
    fun withTeams(config: SportsConfig, chosen: List<FollowedTeam>): SportsConfig {
        val teams = chosen.distinctBy { it.key }
        return config.copy(teams = teams + config.teams.filter { it.sport != Sport.SOCCER && teams.none { t -> t.key == it.key } })
    }

    /** No football: the profile follows no football competition and no football team. */
    fun withoutFootball(config: SportsConfig): SportsConfig =
        config.copy(leagues = config.leagues.filter { !isFootball(it) }, teams = config.teams.filter { it.sport != Sport.SOCCER })
}

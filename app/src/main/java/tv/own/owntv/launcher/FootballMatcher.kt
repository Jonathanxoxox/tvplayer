package tv.own.owntv.launcher

import tv.own.owntv.core.sports.SportsCatalog
import tv.own.owntv.core.sports.SportsConfig
import tv.own.owntv.core.sports.TeamNames
import tv.own.owntv.core.sports.model.League
import tv.own.owntv.core.sports.model.Match
import tv.own.owntv.core.sports.model.Team
import java.text.Normalizer

/**
 * Decides which programmes belong in "Für dich" and in the football group: the profile's teams above
 * everything, then its competitions, then any other football. Teams and competitions are the ones
 * chosen in Settings → Sports (My teams, Sports and leagues); their order there is their rank here.
 *
 * Names are compared as [TeamNames] tokens, the way core finds a match on a channel: accents folded,
 * "FC"/"SV" and numbers dropped, "München" = "Munich", "Deutschland" = "Germany". A team is found
 * when every token of one of its name variants is in the title.
 */
internal class FootballMatcher(
    private val teams: List<Interest>,
    private val competitions: List<Interest>,
) {

    enum class Kind { TEAM, COMPETITION, FOOTBALL }

    class Hit(val score: Int, val reason: String, val kind: Kind)

    /** One team or competition, with the token sets that name it. */
    class Interest(val name: String, val variants: List<Set<String>>) {
        fun foundIn(tokens: Set<String>): Boolean = variants.any { tokens.containsAll(it) }
    }

    /**
     * [title]'s place in "Für dich" and the football group, or null when it is not football.
     *
     * The title has to say football by itself: a football word ("Fußball", "Spieltag"), one of the
     * profile's competitions, or a pairing ("HSV – Werder") that names one of its teams — or, on a sports
     * channel ([sportsChannel]), any pairing. A description never makes a programme football on its own:
     * "Hart aber fair - Pflege zu Hause" with "Deutschland" in its text is not a Germany match. Once the
     * title is football, the description may still name the team ("Bundesliga" with "HSV – Werder" below).
     * Off sports channels a pairing with a team also needs football in its description.
     */
    fun match(title: String, description: String?, sportsChannel: Boolean = true): Hit? {
        val t = TeamNames.tokens(title).toSet()
        if (t.any { it in OTHER_SPORTS }) return null
        val ft = fold(title)
        val pairing = isPairing(title)
        val team = teams.indexOfFirst { it.foundIn(t) }
        val competition = competitions.indexOfFirst { it.foundIn(t) }
        val d = TeamNames.tokens(description.orEmpty()).toSet()
        // A pairing on ARD or ZDF still needs its text to say football: "Maischberger - Deutschland am Limit".
        val textSaysFootball = { fold(description.orEmpty()).let { fd -> FOOTBALL.any { containsPhrase(fd, it) } } || competitions.any { it.foundIn(d) } }
        val football = FOOTBALL.any { containsPhrase(ft, it) } || competition >= 0 ||
            (pairing && (sportsChannel || (team >= 0 && textSaysFootball())))
        if (!football) return null
        if (team >= 0) return Hit(100 + rankBonus(team, 9), teams[team].name, Kind.TEAM)
        // A title that names both sides already says who plays: a team in its text is only mentioned there.
        if (!pairing) teams.forEachIndexed { i, followed -> if (followed.foundIn(d)) return Hit(70 + rankBonus(i, 9), followed.name, Kind.TEAM) }
        if (competition >= 0) return Hit(45 + rankBonus(competition, 5), competitions[competition].name, Kind.COMPETITION)
        competitions.forEachIndexed { i, c -> if (c.foundIn(d)) return Hit(30 + rankBonus(i, 5), c.name, Kind.COMPETITION) }
        return Hit(15, "football", Kind.FOOTBALL)
    }

    private fun rankBonus(index: Int, max: Int) = (max - index).coerceAtLeast(0)

    companion object {

        /**
         * The matcher for [config]. Until an older install's fixed teams were looked up once
         * ([defaultTeams]), those teams are matched by name. New installs never use them.
         */
        fun from(config: SportsConfig, defaultTeams: Boolean): FootballMatcher {
            val teamNames = if (config.teams.isEmpty() && defaultTeams) FootballSetup.DEFAULT_TEAMS.map { it.name }
            else config.teams.map { it.name }
            val leagues = config.leagues.ifEmpty { if (defaultTeams) FootballSetup.DEFAULT_LEAGUES else emptyList() }
            return FootballMatcher(
                teams = teamNames.map(::team),
                competitions = leagues.mapNotNull { key ->
                    val league = SportsCatalog.league(key) ?: return@mapNotNull null
                    if (league.sport != tv.own.owntv.core.sports.model.Sport.SOCCER) return@mapNotNull null
                    interest(league.name, listOf(league.name) + COMPETITION_ALIASES[key].orEmpty())
                },
            )
        }

        fun team(name: String): Interest = interest(name, listOf(name) + TEAM_ALIASES[key(name)].orEmpty())

        /**
         * One match as guide titles name it, for a match page's "Übertragungen": both sides with their
         * German spellings ("Hamburger SV", "HSV" for ESPN's "Hamburg SV") and the competition.
         */
        class Fixture(val home: Interest?, val away: Interest?, val competition: Interest?) {

            /**
             * Whether [title] shows this match: both sides, or one side with the competition or as a
             * pairing ("DFB-Pokal: Hamburger SV - Eintracht Frankfurt", "HSV - SGE"). Only asked of
             * programmes on at kick-off, when a team plays no other match. Another sport never counts.
             */
            fun shownIn(title: String): Boolean {
                val t = titleTokens(title)
                if (t.any { it in OTHER_SPORTS }) return false
                val h = home?.foundIn(t) == true
                val a = away?.foundIn(t) == true
                return (h && a) || ((h || a) && (competition?.foundIn(t) == true || isPairing(title)))
            }
        }

        fun fixture(match: Match): Fixture {
            val league = SportsCatalog.league(League.keyOf(match.sport, match.leagueSlug))
            val names = listOfNotNull(league?.name, match.leagueName.takeIf { it.isNotBlank() }).distinct()
            val competition = names.firstOrNull()?.let { n ->
                interest(n, names + league?.let { COMPETITION_ALIASES[it.key] }.orEmpty())
            }
            return Fixture(match.home?.team?.let(::side), match.away?.team?.let(::side), competition)
        }

        /** A side by every name it goes by: ESPN's names and abbreviation, and the German aliases. */
        private fun side(t: Team): Interest {
            val aliases = listOf(t.name, t.shortName).flatMap { TEAM_ALIASES[key(it)].orEmpty() }
            val own = interest(t.name, listOf(t.name, t.shortName) + aliases).variants
            val core = TeamNames.variantsOf(t).filter { !(it.size == 1 && it.single() in GENERIC) }
            return Interest(t.name, (own + core).distinct())
        }

        /** A title's tokens, with German "-er" club names also as the city ("Hamburger" → "hamburg"). */
        private fun titleTokens(title: String): Set<String> {
            val tokens = TeamNames.tokens(title)
            return (tokens + tokens.filter { it.length >= 7 && it.endsWith("er") }.map { it.dropLast(2) }).toSet()
        }

        private fun interest(name: String, spellings: List<String>) = Interest(
            name,
            spellings.map { TeamNames.tokens(it).toSet() }.filter { it.isNotEmpty() && !(it.size == 1 && it.single() in GENERIC) }.distinct(),
        )

        private fun key(name: String) = TeamNames.tokens(name).joinToString(" ")

        /** Other spellings German guides use, by the team's name as ESPN writes it. */
        private val TEAM_ALIASES: Map<String, List<String>> by lazy {
            mapOf(
                "Hamburg SV" to listOf("HSV", "Hamburger SV"),
                "Borussia Dortmund" to listOf("BVB", "Dortmund"),
                "Bayern Munich" to listOf("FC Bayern", "Bayern"),
                "Liverpool" to listOf("LFC"),
                "Barcelona" to listOf("Barça"),
                "Germany" to listOf("DFB-Team", "DFB-Elf"),
                "Denmark" to listOf("Dänemark", "Danmark"),
                "Bayer Leverkusen" to listOf("Leverkusen"),
                "RB Leipzig" to listOf("Leipzig"),
                "Borussia Mönchengladbach" to listOf("Gladbach"),
                "Werder Bremen" to listOf("Werder"),
                "Eintracht Frankfurt" to listOf("SGE"),
                "Manchester City" to listOf("Man City"),
                "Manchester United" to listOf("Man United", "Man Utd"),
                "Paris Saint-Germain" to listOf("PSG"),
                "Internazionale" to listOf("Inter", "Inter Mailand"),
                "Atlético Madrid" to listOf("Atletico"),
                "Netherlands" to listOf("Holland"),
            ).mapKeys { key(it.key) }
        }

        /** German names of the competitions, by catalogue key; the catalogue's own name always counts. */
        private val COMPETITION_ALIASES = mapOf(
            "soccer/uefa.champions" to listOf("Königsklasse"),
            "soccer/fifa.worldq.uefa" to listOf("WM-Qualifikation", "WM Quali"),
            "soccer/uefa.euro" to listOf("Europameisterschaft", "EM-Qualifikation", "EM Quali"),
            "soccer/fifa.world" to listOf("Weltmeisterschaft", "WM"),
            "soccer/fifa.friendly" to listOf("Länderspiel", "Testspiel"),
            "soccer/esp.1" to listOf("Primera División"),
            "soccer/eng.1" to listOf("Premier League"),
        )

        /** Single words that name no team on their own ("City", "Real"). */
        private val GENERIC = setOf("city", "united", "utd", "real", "team", "sport", "sports", "liga", "league", "cup")

        /** A title with one of these is another sport, whatever team names it carries ("Handball: HSV Hamburg"). */
        private val OTHER_SPORTS: Set<String> by lazy {
            listOf(
                "handball", "basketball", "basket", "eishockey", "hockey", "volleyball", "tennis", "golf", "formel",
                "motogp", "radsport", "darts", "snooker", "nfl", "nba", "nhl", "boxen", "boxing", "ufc", "biathlon",
                "skispringen", "leichtathletik", "rugby", "cricket", "futsal", "esports", "efootball",
                "motorsport", "dtm", "nascar", "indycar", "wrestling", "wwe", "mma", "kampfsport", "poker",
                "ski", "schwimmen", "turnen", "reiten", "segeln", "badminton", "tischtennis", "squash",
            ).flatMapTo(HashSet()) { TeamNames.tokens(it) }
        }

        /** "HSV – Werder", "Liverpool vs. Arsenal", "Deutschland gegen Dänemark". */
        private val MATCH_PATTERN = Regex("\\s+(?:-|–|—|vs\\.?|v|gegen)\\s+", RegexOption.IGNORE_CASE)

        /** Words that make "A - B" a show rather than a match ("Sky Sport News - Das Magazin"). */
        private val SHOW_WORDS = setOf(
            "magazin", "news", "talk", "doku", "dokumentation", "studio", "folge", "staffel", "teil", "film",
            "serie", "show", "nachrichten", "wetter", "reportage", "spezial", "special", "best", "classics",
        )

        /**
         * Whether [title] pairs two sides like a match: after the last colon ("Live LaLiga 26/27: FC Sevilla -
         * CA Osasuna"), exactly two sides of one to four words each, none of them a show word.
         */
        fun isPairing(title: String): Boolean {
            val sides = title.substringAfterLast(':').split(MATCH_PATTERN)
            if (sides.size != 2) return false
            return sides.all { side ->
                val words = fold(side).split(' ').filter { it.isNotEmpty() }
                words.size in 1..4 && words.none { it in SHOW_WORDS }
            }
        }

        private val FOOTBALL = listOf(
            "futbol", "fussball", "football", "soccer", "jornada", "spieltag", "matchday",
        )

        /**
         * Words in a channel name that mark a sports channel worth reading the guide of. Only sports words:
         * "Sky", "Canal", "Magenta" or "TNT" alone also name film and series channels ("Sky Krimi").
         */
        private val SPORTS_CHANNEL = listOf(
            "sport", "dazn", "liga", "bein", "espn", "eleven", "futbol", "football", "fussball", "champions",
            "uefa", "real madrid", "rmtv", "barca", "lfctv", "mutv",
        )

        /** Free channels that show national-team matches; whole words only ("ard" is in "standard"). */
        private val NATIONAL_CHANNEL = listOf("ard", "das erste", "zdf", "rtl", "tv2", "tv 2", "dr1", "dr 1", "orf", "srf")

        fun isSportsChannel(name: String): Boolean = isSportsChannelFolded(fold(name))

        /** [isSportsChannel] for a name already passed through [fold]; the words are folded already. */
        fun isSportsChannelFolded(folded: String): Boolean = SPORTS_CHANNEL.any { folded.contains(it) }

        fun isNationalChannel(name: String): Boolean = isNationalChannelFolded(fold(name))

        fun isNationalChannelFolded(folded: String): Boolean = NATIONAL_CHANNEL.any { containsPhrase(folded, it) }

        private val MARKS = Regex("\\p{M}+")
        private val NON_ALNUM = Regex("[^a-z0-9]+")

        fun fold(s: String): String {
            val lower = s.lowercase().replace("ß", "ss")
            val noAccents = MARKS.replace(Normalizer.normalize(lower, Normalizer.Form.NFD), "")
            return NON_ALNUM.replace(noAccents, " ").trim()
        }

        private fun containsPhrase(folded: String, phrase: String): Boolean =
            phrase.isNotEmpty() && " $folded ".contains(" $phrase ")
    }
}

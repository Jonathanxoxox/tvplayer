package tv.own.owntv.features.home

import tv.own.owntv.core.database.entity.ChannelEntity
import tv.own.owntv.core.database.entity.EpgProgrammeEntity
import tv.own.owntv.core.home.HeroItem
import tv.own.owntv.core.home.homeKey
import tv.own.owntv.core.sports.model.Match
import java.util.Calendar
import java.util.TimeZone
import kotlin.math.abs
import kotlin.math.min

/**
 * The Spotlight's top tier (Jonathan, 6 Nov 2026: "premieres and special highlights should stand out"): what
 * gets the gold badge over its title. Everything else (tips, documentaries, on now) keeps its plain label.
 */
enum class HighlightTier { PREMIERE, HIGHLIGHT, TOP_RATED, SPORT;

    companion object {
        /** A film from this TMDB rating up is top-rated. */
        const val TOP_RATING = 8.0

        /**
         * [hl]'s tier, or null for an ordinary one: a (free-)TV premiere, MagentaTV's own highlight, a film rated
         * [TOP_RATING] or better ([rating], when known), a match of the user's sport. In that order: a premiere
         * that is also MagentaTV's highlight says premiere.
         */
        fun of(hl: Highlight, rating: Double?): HighlightTier? = when (hl) {
            is Highlight.Pick -> when {
                hl.pick.premiere -> PREMIERE
                hl.pick.highlight -> HIGHLIGHT
                hl.pick.film && rating != null && rating >= TOP_RATING -> TOP_RATED
                else -> null
            }
            is Highlight.Sport -> SPORT
            is Highlight.Live, is Highlight.Resume -> null
        }
    }
}

/** Why something is in the Spotlight; its label says so on screen. */
enum class HighlightReason { SPORT_LIVE, SPORT_SOON, SPORT_LATER, FILM, PREMIERE, HIGHLIGHT, TIP, DOC, HABIT, RESUME, NOW }

/**
 * Where the Spotlight takes its highlights from, in the order the user puts them (Settings › Home ›
 * Spotlight; Jonathan, 5 Nov 2026). Each can be switched off.
 */
enum class SpotlightSource(val key: String) {
    /** The followed teams' and leagues' matches: live, kicking off within a quarter of an hour, or later today. */
    SPORT("sport"),
    /**
     * Tonight's films on the film channels (Sky Cinema, MagentaTV's film channels) and MagentaTV's film tips,
     * best first: premieres, MagentaTV's highlights, the evening's main film, the TMDB rating (Jonathan, 6 Nov 2026).
     */
    FILMS("films"),
    /** MagentaTV's own tips and highlights of the day: on now, and still to come today. */
    TIPS("tips"),
    /** Documentaries on arte (and tips marked documentary) today. */
    DOCS("docs"),
    /** A film or an episode to watch on. */
    RESUME("resume"),
    /** The channel usually on at this time of day. */
    HABIT("habit"),
    /** What runs now on the row's leading channels: fills the carousel. */
    NOW("now");

    companion object {
        val DEFAULT = listOf(SPORT, FILMS, TIPS, DOCS, RESUME, HABIT, NOW)

        /**
         * The stored order (keys), without those switched off. A source it does not name yet (one added in an
         * update, like Films) goes in where it stands by default, after the source before it there, rather
         * than at the end behind "On TV now", which fills the Spotlight up.
         */
        fun ordered(keys: List<String>, off: Set<String>): List<SpotlightSource> {
            val out = keys.mapNotNull { k -> entries.firstOrNull { it.key == k } }.distinct().toMutableList()
            DEFAULT.forEachIndexed { i, source ->
                if (source in out) return@forEachIndexed
                // After the latest placed of the sources before it by default; with none of them, at the end.
                val after = DEFAULT.subList(0, i).filter { it in out }.maxOfOrNull { out.indexOf(it) }
                out.add(if (after == null) out.size else after + 1, source)
            }
            return out.filter { it.key !in off }
        }
    }
}

/** A programme from MagentaTV's guide worth showing: one of its tips, or a documentary. */
data class GuidePick(
    val channel: ChannelEntity,
    val title: String,
    val startMs: Long,
    val stopMs: Long,
    /** MagentaTV's "highlight" (its star), the strongest of its picks. */
    val highlight: Boolean,
    /** MagentaTV's "tip". */
    val tip: Boolean,
    /** A documentary: on arte, or a tip MagentaTV files under documentary. */
    val doc: Boolean,
    val picture: String?,
    val description: String?,
    /** A feature film: by the guide's genre, or a long programme on a film channel. */
    val film: Boolean = false,
    /** Not filed as a film, but a long tip that may be one: TMDB decides ([withFilms]). */
    val filmCandidate: Boolean = false,
    /** An episode of a series (season or episode number, or a series genre). */
    val series: Boolean = false,
    /** A (free-)TV premiere, or on Sky Cinema Premiere. */
    val premiere: Boolean = false,
    /** The year it was made, when the guide says. */
    val year: Int? = null,
    /** The guide's first genre ("Thriller"). */
    val genre: String? = null,
    /** A film's leading actors, from TMDB ([withFilms]). */
    val cast: List<String> = emptyList(),
) {
    val key: String get() = "pick:${channel.id}@$startMs"
}

/** One of the Spotlight's highlights: a match, a channel, a programme picked from the guide, or something to watch on. */
sealed interface Highlight {
    val reason: HighlightReason
    val key: String

    data class Sport(val match: Match, override val reason: HighlightReason) : Highlight {
        override val key: String get() = "sport:${match.key}"
    }

    data class Live(val channel: ChannelEntity, val programme: EpgProgrammeEntity?, override val reason: HighlightReason) : Highlight {
        override val key: String get() = "live:${channel.id}"
    }

    /** A tip, highlight or documentary from the guide, on now or later today. */
    data class Pick(val pick: GuidePick, override val reason: HighlightReason) : Highlight {
        override val key: String get() = pick.key
    }

    data class Resume(val item: HeroItem, override val reason: HighlightReason = HighlightReason.RESUME) : Highlight {
        override val key: String get() = "resume:${item.homeKey}"
    }
}

/**
 * What the Spotlight shows before focus goes into its channel strip: highlights from each source in the
 * user's order ([SpotlightSource]), a few from each, at most [MAX], nothing twice. What runs now and what
 * comes later today both count (Jonathan, 5 Nov 2026: "nicht nur das, was jetzt gerade läuft").
 */
object SpotlightHighlights {
    const val MAX = 8
    private const val SOON_MS = 15 * 60_000L
    private const val TIPS_MAX = 4
    private const val DOCS_MAX = 3
    /** A channel last watched within this much of the time of day now, in the last [HABIT_DAYS]. */
    private const val HABIT_WINDOW_MIN = 60
    private const val HABIT_DAYS = 8
    /** "On now" fills with programmes at least this long: a show or a film, not the news. */
    private const val NOW_MIN_MS = 40 * 60_000L
    private const val NOW_CHANNELS = 12
    private const val FILMS_MAX = 3
    private const val FILMS_PER_CHANNEL = 1
    /** A film begun up to this long ago still counts as tonight's. */
    private const val FILM_LATE_MS = 20 * 60_000L
    /** The evening's main slot, by start time: 19:45 to 20:45. */
    private const val PRIME_FROM_MIN = 19 * 60 + 45
    private const val PRIME_TO_MIN = 20 * 60 + 45
    private const val DAY_MS = 24 * 60 * 60_000L

    fun rank(
        now: Long,
        sources: List<SpotlightSource>,
        matches: List<Match>,
        channels: List<ChannelEntity>,
        onNow: Map<Long, EpgProgrammeEntity>,
        /** Live channels with when each was last watched, newest first. */
        watched: List<Pair<ChannelEntity, Long>>,
        resume: List<HeroItem>,
        /** Today's picks from the guide ([GuidePick]). */
        picks: List<GuidePick>,
        zone: TimeZone = TimeZone.getDefault(),
        /** At most this many highlights (Settings › Spotlight). */
        max: Int = MAX,
        /** Series and episodes stay out (Settings › Spotlight; Jonathan, 6 Nov 2026). */
        hideSeries: Boolean = true,
        /** TMDB ratings of the film picks, by [GuidePick.key], as far as they are known. */
        ratings: Map<String, Double> = emptyMap(),
    ): List<Highlight> {
        val out = ArrayList<Highlight>()
        val keys = HashSet<String>()
        val nowChannels = HashSet<Long>()
        fun add(h: Highlight): Boolean {
            if (out.size >= max || !keys.add(h.key)) return false
            // A channel on now is shown once (a pick on now and the same channel as a habit are one thing).
            val liveChannel = when (h) {
                is Highlight.Live -> h.channel.id
                is Highlight.Pick -> h.pick.channel.id.takeIf { h.pick.startMs <= now }
                else -> null
            }
            if (liveChannel != null && !nowChannels.add(liveChannel)) { keys.remove(h.key); return false }
            out += h
            return true
        }
        // Still to come or running, not over; on now first, then by start; within each, highlights before tips.
        val ahead = picks.filter { it.stopMs > now && !(hideSeries && it.series) }
            .sortedWith(compareBy<GuidePick> { it.startMs > now }.thenBy { if (it.startMs > now) it.startMs else 0L }.thenBy { !it.highlight })
        for (source in sources) {
            when (source) {
                SpotlightSource.SPORT -> {
                    // Already in the user's order of sports and leagues.
                    matches.filter { it.isLive }.take(2).forEach { add(Highlight.Sport(it, HighlightReason.SPORT_LIVE)) }
                    matches.firstOrNull { !it.isLive && it.startMs in now..now + SOON_MS }
                        ?.let { add(Highlight.Sport(it, HighlightReason.SPORT_SOON)) }
                    // The followed team playing tonight is the day's highlight already in the afternoon, not only a
                    // quarter of an hour before kick-off (Jonathan, 6 Nov 2026: HSV in the evening was missing).
                    matches.firstOrNull { !it.isLive && it.startMs > now + SOON_MS && sameDay(it.startMs, now, zone) }
                        ?.let { add(Highlight.Sport(it, HighlightReason.SPORT_LATER)) }
                }
                SpotlightSource.FILMS -> films(ahead, now, zone, ratings).forEach { add(Highlight.Pick(it, if (it.premiere) HighlightReason.PREMIERE else HighlightReason.FILM)) }
                SpotlightSource.TIPS -> ahead.filter { it.highlight || it.tip }.take(TIPS_MAX).forEach {
                    add(Highlight.Pick(it, if (it.highlight) HighlightReason.HIGHLIGHT else HighlightReason.TIP))
                }
                SpotlightSource.DOCS -> ahead.filter { it.doc }.take(DOCS_MAX).forEach { add(Highlight.Pick(it, HighlightReason.DOC)) }
                SpotlightSource.RESUME -> resume.firstOrNull { it !is HeroItem.LiveHero }?.let { add(Highlight.Resume(it)) }
                SpotlightSource.HABIT -> watched
                    .filter { (_, at) -> now - at in 0..HABIT_DAYS * DAY_MS && minutesApart(at, now, zone) <= HABIT_WINDOW_MIN }
                    .firstOrNull { (ch, _) -> ch.id !in nowChannels }
                    ?.let { (ch, _) -> add(Highlight.Live(ch, onNow[ch.id], HighlightReason.HABIT)) }
                SpotlightSource.NOW -> channels.take(NOW_CHANNELS)
                    .mapNotNull { ch -> onNow[ch.id]?.let { ch to it } }
                    .filter { (_, p) -> p.startMs <= now && p.stopMs > now && p.stopMs - p.startMs >= NOW_MIN_MS }
                    .filter { (_, p) -> !(hideSeries && looksLikeEpisode(p)) }
                    .sortedByDescending { (_, p) -> p.stopMs - p.startMs }
                    .forEach { (ch, p) -> add(Highlight.Live(ch, p, HighlightReason.NOW)) }
            }
        }
        return out
    }

    /**
     * Tonight's films, best first, at most [FILMS_MAX] and [FILMS_PER_CHANNEL] from one channel: still to come
     * today, or begun no more than [FILM_LATE_MS] ago (nobody joins a film at half time). Points for a premiere,
     * MagentaTV's highlight or tip, a start in the evening's main slot, and the TMDB rating; the earlier start
     * wins a tie.
     */
    internal fun films(picks: List<GuidePick>, now: Long, zone: TimeZone, ratings: Map<String, Double>): List<GuidePick> {
        fun score(p: GuidePick): Double {
            var s = 0.0
            if (p.premiere) s += 4
            if (p.highlight) s += 3 else if (p.tip) s += 2
            if (minuteOfDay(p.startMs, zone) in PRIME_FROM_MIN..PRIME_TO_MIN) s += 2
            ratings[p.key]?.let { s += (it - 6.0).coerceIn(0.0, 3.0) }
            return s
        }
        val perChannel = HashMap<Long, Int>()
        return picks
            .filter { it.film && it.startMs >= now - FILM_LATE_MS && sameDay(it.startMs, now, zone) }
            .sortedWith(compareByDescending<GuidePick> { score(it) }.thenBy { it.startMs })
            .filter { p -> perChannel.merge(p.channel.id, 1) { a, b -> a + b }!! <= FILMS_PER_CHANNEL }
            .take(FILMS_MAX)
    }

    /**
     * [picks] with what TMDB knows of their films ([films], by [GuidePick.key]): a candidate TMDB has as a film
     * becomes one, and every film takes the year, genre and cast the guide left out.
     */
    fun withFilms(picks: List<GuidePick>, films: Map<String, FilmInfo>): List<GuidePick> = picks.map { p ->
        val info = films[p.key]?.takeIf { p.film || p.filmCandidate } ?: return@map p
        p.copy(film = true, filmCandidate = false, year = p.year ?: info.year, genre = p.genre ?: info.genre, cast = p.cast.ifEmpty { info.cast })
    }

    /** A programme from the provider's guide that is an episode of a series, by its numbering or its genre. */
    internal fun looksLikeEpisode(p: EpgProgrammeEntity): Boolean =
        p.episode != null || MagentaNames.seriesWords.any { it in p.categories.orEmpty().lowercase() }

    private fun sameDay(a: Long, b: Long, zone: TimeZone): Boolean {
        val ca = Calendar.getInstance(zone).apply { timeInMillis = a }
        val cb = Calendar.getInstance(zone).apply { timeInMillis = b }
        return ca.get(Calendar.YEAR) == cb.get(Calendar.YEAR) && ca.get(Calendar.DAY_OF_YEAR) == cb.get(Calendar.DAY_OF_YEAR)
    }

    private fun minuteOfDay(ms: Long, zone: TimeZone): Int =
        Calendar.getInstance(zone).apply { timeInMillis = ms }.let { it.get(Calendar.HOUR_OF_DAY) * 60 + it.get(Calendar.MINUTE) }

    /** How far apart two instants are by time of day alone, across midnight too. */
    internal fun minutesApart(a: Long, b: Long, zone: TimeZone): Int {
        val d = abs(minuteOfDay(a, zone) - minuteOfDay(b, zone))
        return min(d, 24 * 60 - d)
    }
}

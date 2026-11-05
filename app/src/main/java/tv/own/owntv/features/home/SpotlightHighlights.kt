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

/** Why something is in the Spotlight; its label says so on screen. */
enum class HighlightReason { SPORT_LIVE, SPORT_SOON, HIGHLIGHT, TIP, DOC, HABIT, RESUME, NOW }

/**
 * Where the Spotlight takes its highlights from, in the order the user puts them (Settings › Home ›
 * Spotlight; Jonathan, 5 Nov 2026). Each can be switched off.
 */
enum class SpotlightSource(val key: String) {
    /** The followed teams' and leagues' matches, live or kicking off within a quarter of an hour. */
    SPORT("sport"),
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
        val DEFAULT = listOf(SPORT, TIPS, DOCS, RESUME, HABIT, NOW)

        /** The stored order (keys) with any source it does not name after it, without those switched off. */
        fun ordered(keys: List<String>, off: Set<String>): List<SpotlightSource> =
            (keys.mapNotNull { k -> entries.firstOrNull { it.key == k } } + DEFAULT).distinct().filter { it.key !in off }
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
    ): List<Highlight> {
        val out = ArrayList<Highlight>()
        val keys = HashSet<String>()
        val nowChannels = HashSet<Long>()
        fun add(h: Highlight): Boolean {
            if (out.size >= MAX || !keys.add(h.key)) return false
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
        val ahead = picks.filter { it.stopMs > now }
            .sortedWith(compareBy<GuidePick> { it.startMs > now }.thenBy { if (it.startMs > now) it.startMs else 0L }.thenBy { !it.highlight })
        for (source in sources) {
            when (source) {
                SpotlightSource.SPORT -> {
                    // Already in the user's order of sports and leagues.
                    matches.filter { it.isLive }.take(2).forEach { add(Highlight.Sport(it, HighlightReason.SPORT_LIVE)) }
                    matches.firstOrNull { !it.isLive && it.startMs in now..now + SOON_MS }
                        ?.let { add(Highlight.Sport(it, HighlightReason.SPORT_SOON)) }
                }
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
                    .sortedByDescending { (_, p) -> p.stopMs - p.startMs }
                    .forEach { (ch, p) -> add(Highlight.Live(ch, p, HighlightReason.NOW)) }
            }
        }
        return out
    }

    private fun minuteOfDay(ms: Long, zone: TimeZone): Int =
        Calendar.getInstance(zone).apply { timeInMillis = ms }.let { it.get(Calendar.HOUR_OF_DAY) * 60 + it.get(Calendar.MINUTE) }

    /** How far apart two instants are by time of day alone, across midnight too. */
    internal fun minutesApart(a: Long, b: Long, zone: TimeZone): Int {
        val d = abs(minuteOfDay(a, zone) - minuteOfDay(b, zone))
        return min(d, 24 * 60 - d)
    }
}

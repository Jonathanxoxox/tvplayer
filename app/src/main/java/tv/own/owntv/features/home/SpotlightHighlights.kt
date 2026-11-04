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
enum class HighlightReason { SPORT_LIVE, SPORT_SOON, EVENING, HABIT, RESUME }

/** One of the Spotlight's highlights: a match, a channel, or something to watch on. */
sealed interface Highlight {
    val reason: HighlightReason
    val key: String

    data class Sport(val match: Match, override val reason: HighlightReason) : Highlight {
        override val key: String get() = "sport:${match.key}"
    }

    data class Live(val channel: ChannelEntity, val programme: EpgProgrammeEntity?, override val reason: HighlightReason) : Highlight {
        override val key: String get() = "live:${channel.id}"
    }

    data class Resume(val item: HeroItem, override val reason: HighlightReason = HighlightReason.RESUME) : Highlight {
        override val key: String get() = "resume:${item.homeKey}"
    }
}

/**
 * What the Spotlight layout shows before focus goes into its channel strip (Jonathan, 4 Nov 2026), in this
 * order: the followed teams' and leagues' matches live now, a match kicking off within a quarter of an hour,
 * the evening's big programme on the row's leading channels, the channel usually on at this time of day,
 * and something to watch on. At most [MAX] of them, no channel twice.
 */
object SpotlightHighlights {
    const val MAX = 5
    private const val SOON_MS = 15 * 60_000L
    /** An evening programme runs at least this long: a film, a crime drama, a big show — not the news. */
    private const val EVENING_MIN_MS = 80 * 60_000L
    /** How many of the row's leading channels the evening is looked for on. */
    private const val EVENING_CHANNELS = 12
    /** A channel last watched within this much of the time of day now, in the last [HABIT_DAYS]. */
    private const val HABIT_WINDOW_MIN = 60
    private const val HABIT_DAYS = 8
    private const val DAY_MS = 24 * 60 * 60_000L

    fun rank(
        now: Long,
        matches: List<Match>,
        channels: List<ChannelEntity>,
        onNow: Map<Long, EpgProgrammeEntity>,
        /** Live channels with when each was last watched, newest first. */
        watched: List<Pair<ChannelEntity, Long>>,
        resume: List<HeroItem>,
        zone: TimeZone = TimeZone.getDefault(),
    ): List<Highlight> {
        val out = ArrayList<Highlight>()
        val seenChannels = HashSet<Long>()
        fun add(h: Highlight) {
            if (out.size >= MAX) return
            if (h is Highlight.Live && !seenChannels.add(h.channel.id)) return
            out += h
        }
        // 1 and 2: the followed matches, already in the user's order of sports and leagues.
        matches.filter { it.isLive }.take(2).forEach { add(Highlight.Sport(it, HighlightReason.SPORT_LIVE)) }
        matches.firstOrNull { !it.isLive && it.startMs in now..now + SOON_MS }
            ?.let { add(Highlight.Sport(it, HighlightReason.SPORT_SOON)) }
        // 3: prime time, the long programme that began in the evening on one of the leading channels.
        if (isEvening(now, zone)) {
            channels.take(EVENING_CHANNELS)
                .mapNotNull { ch -> onNow[ch.id]?.let { ch to it } }
                .filter { (_, p) -> p.stopMs - p.startMs >= EVENING_MIN_MS && p.startMs <= now && p.stopMs > now && hourOf(p.startMs, zone) >= 19 }
                .take(2)
                .forEach { (ch, p) -> add(Highlight.Live(ch, p, HighlightReason.EVENING)) }
        }
        // 4: the channel usually on now, judged by when each was last watched.
        watched
            .filter { (_, at) -> now - at in 0..HABIT_DAYS * DAY_MS && minutesApart(at, now, zone) <= HABIT_WINDOW_MIN }
            .firstOrNull { (ch, _) -> ch.id !in seenChannels }
            ?.let { (ch, _) -> add(Highlight.Live(ch, onNow[ch.id], HighlightReason.HABIT)) }
        // 5: a film or an episode to watch on.
        resume.firstOrNull { it !is HeroItem.LiveHero }?.let { add(Highlight.Resume(it)) }
        return out
    }

    /** 19:45 to 22:30, local time. */
    private fun isEvening(now: Long, zone: TimeZone): Boolean {
        val m = minuteOfDay(now, zone)
        return m in (19 * 60 + 45)..(22 * 60 + 30)
    }

    private fun hourOf(ms: Long, zone: TimeZone): Int = minuteOfDay(ms, zone) / 60

    private fun minuteOfDay(ms: Long, zone: TimeZone): Int =
        Calendar.getInstance(zone).apply { timeInMillis = ms }.let { it.get(Calendar.HOUR_OF_DAY) * 60 + it.get(Calendar.MINUTE) }

    /** How far apart two instants are by time of day alone, across midnight too. */
    internal fun minutesApart(a: Long, b: Long, zone: TimeZone): Int {
        val d = abs(minuteOfDay(a, zone) - minuteOfDay(b, zone))
        return min(d, 24 * 60 - d)
    }
}

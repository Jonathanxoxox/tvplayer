package tv.own.owntv.features.home

/**
 * Whether MagentaTV's guide and a channel's own provider guide tell the same story about "on now", so
 * the Magenta guide is only trusted as the main source for a channel where the two agree.
 */
internal object MagentaMatch {
    enum class Verdict { MATCH, MISMATCH, UNKNOWN }

    /** One programme as either guide reports it. */
    data class Slot(val title: String, val startMs: Long, val stopMs: Long)

    /** Observations needed before a channel counts as matching. */
    const val MIN_SEEN = 3

    private const val START_TOLERANCE_MS = 5 * 60_000L
    private const val MIN_SUBSTRING_KEY = 4

    fun verdict(magenta: Slot?, provider: Slot?): Verdict {
        if (magenta == null || provider == null) return Verdict.UNKNOWN
        val a = ProgrammeTitles.key(magenta.title)
        val b = ProgrammeTitles.key(provider.title)
        if (magenta.title.isBlank() || provider.title.isBlank() || a.isEmpty() || b.isEmpty()) return Verdict.UNKNOWN
        // The same show, or an episode title on one side only ("MacGyver" / "MacGyver: Der Auftrag").
        val sameTitle = a == b || run {
            val (short, long) = if (a.length <= b.length) a to b else b to a
            short.length >= MIN_SUBSTRING_KEY && short in long
        }
        if (sameTitle && overlapsEnough(magenta, provider)) return Verdict.MATCH
        // Same time but another title, or the same title at another time: the guides disagree either way.
        return Verdict.MISMATCH
    }

    /** The slots share at least half of the shorter one's length. */
    private fun overlapsEnough(x: Slot, y: Slot): Boolean {
        val shorter = minOf(x.stopMs - x.startMs, y.stopMs - y.startMs)
        if (shorter <= 0) return kotlin.math.abs(x.startMs - y.startMs) <= START_TOLERANCE_MS
        val overlap = minOf(x.stopMs, y.stopMs) - maxOf(x.startMs, y.startMs)
        return overlap * 2 >= shorter
    }

    /**
     * MATCH only once at least [MIN_SEEN] observations exist and at most a quarter are MISMATCH; MISMATCH
     * when more than half are; else UNKNOWN. UNKNOWN observations are ignored.
     */
    fun overall(observations: List<Verdict>): Verdict {
        val seen = observations.filter { it != Verdict.UNKNOWN }
        val bad = seen.count { it == Verdict.MISMATCH }
        return when {
            seen.isNotEmpty() && bad * 2 > seen.size -> Verdict.MISMATCH
            seen.size >= MIN_SEEN && bad * 4 <= seen.size -> Verdict.MATCH
            else -> Verdict.UNKNOWN
        }
    }
}

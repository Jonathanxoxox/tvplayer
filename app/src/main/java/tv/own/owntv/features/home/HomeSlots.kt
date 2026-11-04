package tv.own.owntv.features.home

import tv.own.owntv.core.model.HomeRow

/** One line of Home's arrange window: one of Home's own rows, or another app's row ([key] = AppChannel.key). */
sealed interface HomeSlot {
    data class Row(val row: HomeRow) : HomeSlot
    data class App(val key: String) : HomeSlot
}

/**
 * Where the other apps' rows (YouTube, Nuvio, Spotify …) sit among Home's own (Jonathan, 4 Nov 2026). Each
 * app row the user has placed is stored as the Home row it follows, or [TOP], so it keeps its place while
 * rows above it come and go (an empty Favourites row, a row switched off). App rows never placed keep
 * Home's old spot for them: right after the first row, or at the very end.
 */
object HomeSlots {
    /** Above all of Home's rows. */
    const val TOP = "^"
    private const val SEP = '\t'

    fun encode(anchor: String, key: String): String = "$anchor$SEP$key"

    /** (anchor, key), or null for an entry that is not one. */
    fun decode(entry: String): Pair<String, String>? {
        val i = entry.indexOf(SEP)
        return if (i <= 0 || i == entry.lastIndex) null else entry.substring(0, i) to entry.substring(i + 1)
    }

    /**
     * The arrange window's list: Home's [rows], each followed by the app rows [placed] after it, the
     * [unplaced] ones where Home draws them now: after [unplacedAfter] (the first row Home draws, which is
     * not always the first in the list: Spotlight hides the hero), or at the end when null. A placed row
     * whose anchor is not among [rows] goes to the end too.
     */
    fun mixed(
        rows: List<HomeRow>,
        placed: List<Pair<String, String>>,
        unplaced: List<String>,
        unplacedAfter: HomeRow?,
    ): List<HomeSlot> {
        val byAnchor = placed.groupBy({ it.first }, { it.second })
        val names = rows.map { it.name }.toSet()
        return buildList {
            byAnchor[TOP]?.forEach { add(HomeSlot.App(it)) }
            rows.forEach { row ->
                add(HomeSlot.Row(row))
                byAnchor[row.name]?.forEach { add(HomeSlot.App(it)) }
                if (row == unplacedAfter) unplaced.forEach { add(HomeSlot.App(it)) }
            }
            placed.filter { it.first != TOP && it.first !in names }.forEach { add(HomeSlot.App(it.second)) }
            if (unplacedAfter == null || unplacedAfter !in rows) unplaced.forEach { add(HomeSlot.App(it)) }
        }
    }

    /**
     * Back from the window: every app row in [mixed] with the row it now follows, in order. [keep] are
     * stored entries for app rows not in the window (an app not installed right now): they stay as they were.
     */
    fun anchors(mixed: List<HomeSlot>, keep: List<String>): List<String> {
        var anchor = TOP
        val out = ArrayList<String>()
        for (slot in mixed) when (slot) {
            is HomeSlot.Row -> anchor = slot.row.name
            is HomeSlot.App -> out += encode(anchor, slot.key)
        }
        return out + keep
    }

    /**
     * For drawing: the app rows to draw after each of Home's [rendered] rows (by name), and before all of them
     * under [TOP]. [order] is Home's whole row order, hidden rows included: an app row whose anchor Home does
     * not draw right now goes after the drawn row before it (or to [TOP]); one whose anchor is unknown, last.
     */
    fun place(
        order: List<HomeRow>,
        rendered: Collection<HomeRow>,
        placed: List<Pair<String, String>>,
    ): Map<String, List<String>> {
        val byAnchor = placed.groupBy({ it.first }, { it.second })
        val out = LinkedHashMap<String, MutableList<String>>()
        fun put(at: String, keys: List<String>) { if (keys.isNotEmpty()) out.getOrPut(at) { mutableListOf() }.addAll(keys) }
        put(TOP, byAnchor[TOP].orEmpty())
        var last = TOP
        for (row in order) {
            if (row in rendered) last = row.name
            put(last, byAnchor[row.name].orEmpty())
        }
        val known = order.map { it.name }.toSet() + TOP
        put(last, placed.filter { it.first !in known }.map { it.second })
        return out
    }
}

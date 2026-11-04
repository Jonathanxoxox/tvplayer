package tv.own.owntv.features.home

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import tv.own.owntv.core.model.HomeRow
import tv.own.owntv.core.model.HomeRow.CONTINUE_MOVIES
import tv.own.owntv.core.model.HomeRow.FAVORITE_CHANNELS
import tv.own.owntv.core.model.HomeRow.HERO
import tv.own.owntv.core.model.HomeRow.RECENT_CHANNELS

/** The other apps' rows among Home's own: the window's list, what it stores, and where Home draws them. */
class HomeSlotsTest {
    private val rows = listOf(HERO, RECENT_CHANNELS, FAVORITE_CHANNELS, CONTINUE_MOVIES)

    private fun r(row: HomeRow) = HomeSlot.Row(row)
    private fun a(key: String) = HomeSlot.App(key)

    @Test
    fun `entries survive a round trip and junk is ignored`() {
        val e = HomeSlots.encode("RECENT_CHANNELS", "com.nuvio.tv/watch_next")
        assertEquals("RECENT_CHANNELS" to "com.nuvio.tv/watch_next", HomeSlots.decode(e))
        assertNull(HomeSlots.decode("no-separator"))
    }

    @Test
    fun `never placed app rows come right after the first row Home draws, or at the end`() {
        assertEquals(
            listOf(r(HERO), a("yt"), a("nuvio"), r(RECENT_CHANNELS), r(FAVORITE_CHANNELS), r(CONTINUE_MOVIES)),
            HomeSlots.mixed(rows, emptyList(), listOf("yt", "nuvio"), unplacedAfter = HERO),
        )
        // Spotlight draws no hero: the channel row is the first on screen, and the app rows follow it.
        assertEquals(
            listOf(r(HERO), r(RECENT_CHANNELS), a("yt"), r(FAVORITE_CHANNELS), r(CONTINUE_MOVIES)),
            HomeSlots.mixed(rows, emptyList(), listOf("yt"), unplacedAfter = RECENT_CHANNELS),
        )
        assertEquals(
            listOf(r(HERO), r(RECENT_CHANNELS), r(FAVORITE_CHANNELS), r(CONTINUE_MOVIES), a("yt")),
            HomeSlots.mixed(rows, emptyList(), listOf("yt"), unplacedAfter = null),
        )
    }

    @Test
    fun `what the window leaves is stored and read back the same`() {
        val window = listOf(a("spotify"), r(HERO), r(RECENT_CHANNELS), a("yt"), r(FAVORITE_CHANNELS), r(CONTINUE_MOVIES), a("nuvio"))
        val stored = HomeSlots.anchors(window, keep = listOf(HomeSlots.encode("HERO", "gone")))
        val placed = stored.mapNotNull(HomeSlots::decode).filter { it.second != "gone" }
        assertEquals(window, HomeSlots.mixed(rows, placed, emptyList(), unplacedAfter = HERO))
        assertEquals(HomeSlots.encode("HERO", "gone"), stored.last())
    }

    @Test
    fun `an app row after a row Home does not draw moves up to the drawn row before it`() {
        val placed = listOf(
            HomeSlots.TOP to "spotify",
            "FAVORITE_CHANNELS" to "yt",     // favourites empty right now
            "CONTINUE_MOVIES" to "nuvio",
            "SOMETHING_OLD" to "x",          // a row that no longer exists
        )
        val drawn = setOf(HERO, RECENT_CHANNELS, CONTINUE_MOVIES)
        assertEquals(
            mapOf(
                HomeSlots.TOP to listOf("spotify"),
                "RECENT_CHANNELS" to listOf("yt"),
                "CONTINUE_MOVIES" to listOf("nuvio", "x"),
            ),
            HomeSlots.place(rows, drawn, placed),
        )
    }
}

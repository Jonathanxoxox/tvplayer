package tv.own.owntv.features.home

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import tv.own.owntv.core.database.entity.ChannelEntity

/** The Spotlight's kept picks: written and read back whole, and shown only on Home's channels while still ahead. */
class SpotlightPicksStoreTest {
    private fun channel(id: Long) = ChannelEntity(id = id, sourceId = 1, categoryId = 9, name = "Ch $id", streamUrl = "http://x/$id.ts", remoteId = "$id")

    private val film = GuidePick(
        channel(1), "Meg", 1_000L, 8_000L, highlight = true, tip = false, doc = false,
        picture = "https://img/meg.jpg", description = "A shark.", film = true, premiere = true,
        year = 2018, genre = "Action", cast = listOf("Jason Statham", "Li Bingbing"), director = "Jon Turteltaub",
    )
    private val doc = GuidePick(channel(2), "Doku", 2_000L, 5_000L, highlight = false, tip = true, doc = true, picture = null, description = null)

    @Test
    fun `a kept round reads back as it was written`() {
        val kept = SpotlightPicksStore.Kept(123L, setOf("1:00", "2:10"), listOf(film, doc).map { SpotlightPicksStore.PickData.of(it) })
        assertEquals(kept, SpotlightPicksStore.decode(SpotlightPicksStore.encode(kept)))
    }

    @Test
    fun `anything unreadable keeps nothing`() {
        assertNull(SpotlightPicksStore.decode("not json"))
        assertNull(SpotlightPicksStore.decode("""{"v":99,"at":1,"asked":[],"picks":[]}"""))
    }

    @Test
    fun `picks come back on Home's channels, without those that have ended`() {
        val kept = SpotlightPicksStore.Kept(0L, emptySet(), listOf(film, doc).map { SpotlightPicksStore.PickData.of(it) })
        val request = SpotlightPicks.Request(listOf(channel(1), channel(2)), docsOn = setOf(2L), filmsOn = emptySet())
        assertEquals(listOf(film, doc), SpotlightPicksStore.resolve(request, kept, now = 1_500L))
        // The documentary ended at 5000.
        assertEquals(listOf(film), SpotlightPicksStore.resolve(request, kept, now = 6_000L))
        // A channel no longer on Home: its picks stay out.
        val fewer = request.copy(channels = listOf(channel(2)))
        assertEquals(listOf(doc), SpotlightPicksStore.resolve(fewer, kept, now = 1_500L))
        assertTrue(SpotlightPicksStore.resolve(null, kept, now = 0L).isEmpty())
    }

    @Test
    fun `a round serves a request whose channels it asked the same way`() {
        val all = SpotlightPicks.Request(listOf(channel(1), channel(2), channel(3)), docsOn = setOf(2L), filmsOn = setOf(3L))
        val asked = SpotlightPicksStore.asked(all)
        assertEquals(setOf("1:00", "2:10", "3:01"), asked)
        assertTrue(asked.containsAll(SpotlightPicksStore.asked(all.copy(channels = listOf(channel(1), channel(2))))))
        // Channel 3 asked for its films before; asked without them now, it is another question.
        assertTrue(!asked.containsAll(SpotlightPicksStore.asked(all.copy(filmsOn = emptySet()))))
    }
}

package tv.own.owntv.features.home

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import tv.own.owntv.core.database.entity.ChannelEntity
import tv.own.owntv.features.home.SpotlightPicksStore.Kept
import tv.own.owntv.features.home.SpotlightPicksStore.PickData

/** The Spotlight's kept picks: written and read back whole, kept per guide key, shown only on covered channels. */
class SpotlightPicksStoreTest {
    // Names that are their own guide key ("ch1" ...), so a test can read the key off the channel.
    private fun channel(id: Long, name: String = "Ch$id") = ChannelEntity(id = id, sourceId = 1, categoryId = 9, name = name, streamUrl = "http://x/$id.ts", remoteId = "$id")
    private fun key(c: ChannelEntity) = MagentaNames.key(c.name)

    private val ch1 = channel(1)
    private val ch2 = channel(2)
    private val film = GuidePick(
        ch1, "Meg", 1_000L, 8_000L, highlight = true, tip = false, doc = false,
        picture = "https://img/meg.jpg", description = "A shark.", film = true, premiere = true,
        year = 2018, genre = "Action", cast = listOf("Jason Statham", "Li Bingbing"), director = "Jon Turteltaub",
    )
    private val doc = GuidePick(ch2, "Doku", 2_000L, 5_000L, highlight = false, tip = true, doc = true, picture = null, description = null)
    private val filmData = PickData.of(film)
    private val docData = PickData.of(doc)
    private val k1 = key(ch1)
    private val k2 = key(ch2)

    private fun kept(
        picks: List<PickData> = listOf(filmData, docData),
        keys: Set<String> = setOf(k1, k2),
        docKeys: Set<String> = emptySet(),
        filmKeys: Set<String> = emptySet(),
    ) = Kept(123L, keys, docKeys, filmKeys, picks)

    private fun request(vararg channels: ChannelEntity, docsOn: Set<Long> = emptySet(), filmsOn: Set<Long> = emptySet()) =
        SpotlightPicks.Request(channels.toList(), docsOn, filmsOn)

    @Test
    fun `a kept round reads back as it was written, flags included`() {
        val k = kept(docKeys = setOf(k2), filmKeys = setOf(k1))
        assertEquals(k, SpotlightPicksStore.decode(SpotlightPicksStore.encode(k)))
    }

    @Test
    fun `anything unreadable or from another version keeps nothing`() {
        assertNull(SpotlightPicksStore.decode("not json"))
        assertNull(SpotlightPicksStore.decode("""{"v":99,"at":1,"keys":[],"docs":[],"films":[],"picks":[]}"""))
        // The first format (per channel id) is ignored and simply fetched again.
        assertNull(SpotlightPicksStore.decode("""{"v":1,"at":1,"asked":[],"picks":[]}"""))
    }

    @Test
    fun `picks come back on Home's channels by key, without those that have ended`() {
        val covered = setOf(k1, k2)
        val r = request(ch1, ch2)
        assertEquals(listOf(film, doc), SpotlightPicksStore.resolve(r, kept(), covered, now = 1_500L))
        // The documentary ended at 5000.
        assertEquals(listOf(film), SpotlightPicksStore.resolve(r, kept(), covered, now = 6_000L))
        // A channel no longer on Home: its picks stay out.
        assertEquals(listOf(doc), SpotlightPicksStore.resolve(request(ch2), kept(), covered, now = 1_500L))
        assertTrue(SpotlightPicksStore.resolve(null, kept(), covered, now = 0L).isEmpty())
    }

    @Test
    fun `a channel entity with a new id after a playlist sync still gets its picks`() {
        val resynced = channel(77, ch1.name)
        val picks = SpotlightPicksStore.resolve(request(resynced), kept(), setOf(k1), now = 0L)
        assertEquals(listOf(resynced), picks.map { it.channel })
    }

    @Test
    fun `only covered keys show picks`() {
        val r = request(ch1, ch2)
        assertEquals(listOf(doc), SpotlightPicksStore.resolve(r, kept(), setOf(k2), now = 0L))
        assertTrue(SpotlightPicksStore.resolve(r, kept(), emptySet(), now = 0L).isEmpty())
    }

    @Test
    fun `an offer replaces the picks of its keys only, and ended picks are dropped`() {
        val newFilm = filmData.copy(title = "Meg 2", stopMs = 9_000L)
        val merged = SpotlightPicksStore.merge(kept(), mapOf(k1 to listOf(newFilm)), at = 500L, now = 100L)
        assertEquals(listOf(docData, newFilm), merged.picks.sortedBy { it.title })
        assertEquals(500L, merged.at)
        // An answer without picks clears the key and notes it was read.
        val cleared = SpotlightPicksStore.merge(kept(keys = emptySet()), mapOf(k2 to emptyList()), at = 500L, now = 100L)
        assertEquals(listOf(filmData), cleared.picks)
        assertEquals(setOf(k2), cleared.keys)
        // The documentary has ended at 6000: it does not survive a save.
        val later = SpotlightPicksStore.merge(kept(), mapOf(k1 to listOf(newFilm)), at = 6_000L, now = 6_000L)
        assertEquals(listOf(newFilm), later.picks)
    }

    @Test
    fun `merging into nothing kept starts a new round`() {
        val m = SpotlightPicksStore.merge(null, mapOf(k1 to listOf(filmData)), at = 7L, now = 0L)
        assertEquals(Kept(7L, setOf(k1), emptySet(), emptySet(), listOf(filmData)), m)
    }

    @Test
    fun `flags are read off the request by guide key`() {
        val f = SpotlightPicksStore.flagsOf(request(ch1, ch2, docsOn = setOf(2L), filmsOn = setOf(1L)))
        assertEquals(SpotlightPicksStore.Flags(docKeys = setOf(k2), filmKeys = setOf(k1)), f)
    }

    @Test
    fun `a changed flag fetches that key again, a same request fetches nothing`() {
        val covered = setOf(k1, k2)
        val same = kept(docKeys = setOf(k2))
        assertEquals(emptySet<String>(), SpotlightPicksStore.refetchKeys(same, request(ch1, ch2, docsOn = setOf(2L)), covered))
        // Channel 1 now counts films; channel 2 no longer documentaries.
        assertEquals(
            setOf(k1, k2),
            SpotlightPicksStore.refetchKeys(same, request(ch1, ch2, filmsOn = setOf(1L)), covered),
        )
        assertEquals(setOf(k1), SpotlightPicksStore.refetchKeys(same, request(ch1, ch2, docsOn = setOf(2L), filmsOn = setOf(1L)), covered))
    }

    @Test
    fun `only covered keys are fetched again`() {
        val r = request(ch1, ch2, filmsOn = setOf(1L))
        assertEquals(emptySet<String>(), SpotlightPicksStore.refetchKeys(kept(), r, emptySet()))
        assertEquals(setOf(k1), SpotlightPicksStore.refetchKeys(kept(), r, setOf(k1)))
    }

    @Test
    fun `covered keys nothing is kept for are fetched, all of them when nothing is kept`() {
        val r = request(ch1, ch2)
        assertEquals(setOf(k1, k2), SpotlightPicksStore.refetchKeys(null, r, setOf(k1, k2)))
        assertEquals(setOf(k2), SpotlightPicksStore.refetchKeys(kept(keys = setOf(k1)), r, setOf(k1, k2)))
    }

    @Test
    fun `new flags are taken over and differ only when they change something`() {
        val flags = SpotlightPicksStore.Flags(setOf(k2), emptySet())
        assertTrue(SpotlightPicksStore.flagsDiffer(kept(), flags))
        val updated = SpotlightPicksStore.withFlags(kept(), flags)
        assertEquals(setOf(k2), updated.docKeys)
        assertTrue(!SpotlightPicksStore.flagsDiffer(updated, flags))
    }
}

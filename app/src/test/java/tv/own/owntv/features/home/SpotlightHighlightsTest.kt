package tv.own.owntv.features.home

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import tv.own.owntv.core.database.entity.ChannelEntity
import tv.own.owntv.core.database.entity.EpgProgrammeEntity
import tv.own.owntv.core.sports.model.Match
import tv.own.owntv.core.sports.model.MatchState
import tv.own.owntv.core.sports.model.Sport
import java.util.Calendar
import java.util.TimeZone

/** What the Spotlight shows, from which source, and in which order. */
class SpotlightHighlightsTest {
    private val zone = TimeZone.getTimeZone("Europe/Berlin")
    private val min = 60_000L

    private fun at(hour: Int, minute: Int = 0): Long =
        Calendar.getInstance(zone).apply { clear(); set(2026, 10, 5, hour, minute) }.timeInMillis

    private fun channel(id: Long) = ChannelEntity(id = id, sourceId = 1, categoryId = 9, name = "Ch $id", streamUrl = "http://x/$id.ts", remoteId = "$id")

    private fun programme(id: Long, start: Long, stop: Long) =
        EpgProgrammeEntity(id = id, sourceId = 1, epgChannelId = "c$id", startMs = start, stopMs = stop, title = "P $id")

    private fun match(id: String, live: Boolean, start: Long) = Match(
        id, Sport.SOCCER, "soccer/ger.1", "ger.1", "Bundesliga", start,
        if (live) MatchState.IN else MatchState.PRE, "", "HSV at Bayern",
    )

    private fun pick(ch: Long, start: Long, stop: Long, highlight: Boolean = false, tip: Boolean = false, doc: Boolean = false) =
        GuidePick(channel(ch), "T $ch", start, stop, highlight, tip, doc, null, null)

    private val channels = (1L..14L).map(::channel)
    private val all = SpotlightSource.DEFAULT

    private fun rank(
        now: Long,
        sources: List<SpotlightSource> = all,
        matches: List<Match> = emptyList(),
        onNow: Map<Long, EpgProgrammeEntity> = emptyMap(),
        watched: List<Pair<ChannelEntity, Long>> = emptyList(),
        picks: List<GuidePick> = emptyList(),
    ) = SpotlightHighlights.rank(now, sources, matches, channels, onNow, watched, emptyList(), picks, zone)

    @Test
    fun `live matches come first, then one kicking off soon`() {
        val now = at(15, 30)
        val h = rank(now, matches = listOf(match("1", true, now - 30 * min), match("2", false, now + 10 * min), match("3", false, now + 60 * min)))
        assertEquals(listOf(HighlightReason.SPORT_LIVE, HighlightReason.SPORT_SOON), h.map { it.reason })
    }

    @Test
    fun `tips on now come before later ones, highlights before tips, and later ones count too`() {
        val now = at(20, 30)
        val picks = listOf(
            pick(3, at(22, 0), at(23, 50), highlight = true), // Harry Potter, later
            pick(2, at(20, 15), at(21, 45), tip = true),       // a tip on now
            pick(1, at(20, 15), at(21, 45), highlight = true), // the crime drama on now
            pick(4, at(18, 0), at(18, 50), tip = true),        // over
        )
        val h = rank(now, sources = listOf(SpotlightSource.TIPS), picks = picks)
        assertEquals(listOf(1L, 2L, 3L), h.map { (it as Highlight.Pick).pick.channel.id })
        assertEquals(listOf(HighlightReason.HIGHLIGHT, HighlightReason.TIP, HighlightReason.HIGHLIGHT), h.map { it.reason })
    }

    @Test
    fun `the user's order decides, and a source switched off is left out`() {
        val now = at(20, 30)
        val picks = listOf(pick(1, at(20, 15), at(21, 45), highlight = true), pick(9, at(21, 0), at(22, 0), doc = true))
        val docsFirst = rank(now, sources = listOf(SpotlightSource.DOCS, SpotlightSource.TIPS), picks = picks)
        assertEquals(listOf(HighlightReason.DOC, HighlightReason.HIGHLIGHT), docsFirst.map { it.reason })
        val noDocs = rank(now, sources = SpotlightSource.ordered(listOf("docs", "tips"), off = setOf("docs")), picks = picks)
        assertTrue(noDocs.none { it.reason == HighlightReason.DOC })
    }

    @Test
    fun `stored order keeps sources it does not name, after it`() {
        assertEquals(
            listOf(SpotlightSource.DOCS, SpotlightSource.SPORT, SpotlightSource.TIPS, SpotlightSource.RESUME, SpotlightSource.HABIT, SpotlightSource.NOW),
            SpotlightSource.ordered(listOf("docs"), off = emptySet()),
        )
    }

    @Test
    fun `on now fills, longest first, and a channel on now is shown once`() {
        val now = at(15, 0)
        val onNow = mapOf(
            2L to programme(2, at(14, 0), at(16, 0)),   // two hours
            3L to programme(3, at(14, 50), at(15, 10)), // twenty minutes: not a highlight
            4L to programme(4, at(14, 30), at(15, 30)), // an hour
        )
        val picks = listOf(pick(2, at(14, 0), at(16, 0), tip = true))
        val h = rank(now, onNow = onNow, picks = picks)
        // Channel 2 as a tip, not again as on now; then channel 4.
        assertEquals(listOf("pick:2@${at(14, 0)}", "live:4"), h.map { it.key })
    }

    @Test
    fun `the channel watched around this time lately is a habit, an old one is not`() {
        val now = at(20, 30)
        val day = 24 * 60 * min
        val watched = listOf(
            channel(87) to at(20, 10) - day,      // yesterday around now
            channel(50) to at(9, 0) - day,        // yesterday morning
            channel(60) to at(20, 30) - 20 * day, // long ago
        )
        val h = rank(now, sources = listOf(SpotlightSource.HABIT), watched = watched)
        assertEquals(listOf(87L), h.map { (it as Highlight.Live).channel.id })
    }

    @Test
    fun `never more than the most`() {
        val now = at(20, 30)
        val picks = (1L..14L).map { pick(it, at(20, 0), at(22, 0), tip = true, doc = it % 2 == 0L) }
        val onNow = (1L..14L).associateWith { programme(it, at(20, 0), at(22, 0)) }
        assertEquals(SpotlightHighlights.MAX, rank(now, onNow = onNow, picks = picks).size)
    }
}

package tv.own.owntv.features.home

import org.junit.Assert.assertEquals
import org.junit.Test
import tv.own.owntv.core.database.entity.ChannelEntity
import tv.own.owntv.core.database.entity.EpgProgrammeEntity
import tv.own.owntv.core.sports.model.Match
import tv.own.owntv.core.sports.model.MatchState
import tv.own.owntv.core.sports.model.Sport
import java.util.Calendar
import java.util.TimeZone

/** What the Spotlight shows, and in which order. */
class SpotlightHighlightsTest {
    private val zone = TimeZone.getTimeZone("Europe/Berlin")
    private val min = 60_000L

    private fun at(hour: Int, minute: Int = 0): Long =
        Calendar.getInstance(zone).apply { clear(); set(2026, 10, 4, hour, minute) }.timeInMillis

    private fun channel(id: Long) = ChannelEntity(id = id, sourceId = 1, categoryId = 9, name = "Ch $id", streamUrl = "http://x/$id.ts", remoteId = "$id")

    private fun programme(id: Long, start: Long, stop: Long) =
        EpgProgrammeEntity(id = id, sourceId = 1, epgChannelId = "c$id", startMs = start, stopMs = stop, title = "P $id")

    private fun match(id: String, live: Boolean, start: Long) = Match(
        id, Sport.SOCCER, "soccer/ger.1", "ger.1", "Bundesliga", start,
        if (live) MatchState.IN else MatchState.PRE, "", "HSV at Bayern",
    )

    private val channels = (1L..14L).map(::channel)

    @Test
    fun `live matches come first, then one kicking off soon`() {
        val now = at(15, 30)
        val h = SpotlightHighlights.rank(
            now, listOf(match("1", true, now - 30 * min), match("2", false, now + 10 * min), match("3", false, now + 60 * min)),
            channels, emptyMap(), emptyList(), emptyList(), zone,
        )
        assertEquals(listOf(HighlightReason.SPORT_LIVE, HighlightReason.SPORT_SOON), h.map { it.reason })
    }

    @Test
    fun `in the evening the long programme on a leading channel is a highlight, the news is not`() {
        val now = at(20, 40)
        val onNow = mapOf(
            1L to programme(1, at(20, 0), at(20, 15)),   // news: too short, and over
            2L to programme(2, at(20, 15), at(21, 45)),  // the film
            3L to programme(3, at(20, 30), at(21, 0)),   // a half-hour show
        )
        val h = SpotlightHighlights.rank(now, emptyList(), channels, onNow, emptyList(), emptyList(), zone)
        assertEquals(listOf(2L), h.map { (it as Highlight.Live).channel.id })
        assertEquals(HighlightReason.EVENING, h.single().reason)
    }

    @Test
    fun `in the afternoon a long programme is on now, not tonight`() {
        val now = at(15, 0)
        val onNow = mapOf(
            2L to programme(2, at(14, 0), at(16, 0)),   // two hours
            3L to programme(3, at(14, 50), at(15, 10)), // twenty minutes: not a highlight
            4L to programme(4, at(14, 30), at(15, 30)), // an hour
        )
        val h = SpotlightHighlights.rank(now, emptyList(), channels, onNow, emptyList(), emptyList(), zone)
        assertEquals(listOf(HighlightReason.NOW, HighlightReason.NOW), h.map { it.reason })
        assertEquals(listOf(2L, 4L), h.map { (it as Highlight.Live).channel.id }) // longest first
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
        val h = SpotlightHighlights.rank(now, emptyList(), channels, emptyMap(), watched, emptyList(), zone)
        assertEquals(listOf(87L), h.map { (it as Highlight.Live).channel.id })
        assertEquals(HighlightReason.HABIT, h.single().reason)
    }

    @Test
    fun `a channel is never in it twice and there are at most five`() {
        val now = at(20, 40)
        val onNow = mapOf(2L to programme(2, at(20, 15), at(21, 45)))
        val watched = listOf(channel(2) to at(20, 20) - 24 * 60 * min)
        val matches = (1..4).map { match("$it", true, now) }
        val h = SpotlightHighlights.rank(now, matches, channels, onNow, watched, emptyList(), zone)
        assertEquals(3, h.size) // two live matches, the film; the habit is the film's channel again
        assertEquals(1, h.count { it is Highlight.Live })
    }
}

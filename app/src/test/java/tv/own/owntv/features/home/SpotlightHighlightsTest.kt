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
    fun `live matches come first, then one kicking off soon, then one later today`() {
        val now = at(15, 30)
        val h = rank(now, matches = listOf(match("1", true, now - 30 * min), match("2", false, now + 10 * min), match("3", false, now + 60 * min)))
        // The one at 16:30 counts as later today.
        assertEquals(listOf(HighlightReason.SPORT_LIVE, HighlightReason.SPORT_SOON, HighlightReason.SPORT_LATER), h.map { it.reason })
    }

    @Test
    fun `a followed match later today is a highlight, tomorrow's is not`() {
        val now = at(15, 30)
        val h = rank(now, matches = listOf(match("1", false, at(20, 30)), match("2", false, at(20, 30) + 24 * 60 * min)))
        assertEquals(listOf(HighlightReason.SPORT_LATER), h.map { it.reason })
        assertEquals("sport:${match("1", false, at(20, 30)).key}", h.single().key)
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
            listOf(
                SpotlightSource.DOCS, SpotlightSource.SPORT, SpotlightSource.FILMS, SpotlightSource.TIPS,
                SpotlightSource.RESUME, SpotlightSource.HABIT, SpotlightSource.NOW,
            ),
            SpotlightSource.ordered(listOf("docs"), off = emptySet()),
        )
    }

    @Test
    fun `a source added in an update goes in where it belongs, not behind on now`() {
        // An order saved before Films existed, with Tips moved to the top.
        val saved = listOf("tips", "sport", "docs", "resume", "habit", "now")
        assertEquals(
            listOf(
                SpotlightSource.TIPS, SpotlightSource.SPORT, SpotlightSource.FILMS, SpotlightSource.DOCS,
                SpotlightSource.RESUME, SpotlightSource.HABIT, SpotlightSource.NOW,
            ),
            SpotlightSource.ordered(saved, off = emptySet()),
        )
    }

    private fun film(
        ch: Long, start: Long, stop: Long, premiere: Boolean = false, highlight: Boolean = false, series: Boolean = false,
    ) = GuidePick(
        channel(ch), "F $ch@$start", start, stop, highlight, tip = false, doc = false, picture = null, description = null,
        film = !series, series = series, premiere = premiere,
    )

    @Test
    fun `films tonight - premiere and the main film first, one per channel, none half over`() {
        val now = at(18, 0)
        val picks = listOf(
            film(1, at(22, 0), at(23, 50)),                  // late film
            film(2, at(20, 15), at(22, 0)),                  // the main film
            film(3, at(21, 0), at(23, 0), premiere = true),  // a premiere
            film(2, at(22, 10), at(23, 55)),                 // the same channel again
            film(4, at(16, 0), at(18, 30)),                  // begun two hours ago
        )
        val h = rank(now, sources = listOf(SpotlightSource.FILMS), picks = picks)
        assertEquals(listOf(3L, 2L, 1L), h.map { (it as Highlight.Pick).pick.channel.id })
        assertEquals(HighlightReason.PREMIERE, h.first().reason)
        assertEquals(HighlightReason.FILM, h[1].reason)
    }

    @Test
    fun `a better rating lifts a film`() {
        val now = at(18, 0)
        val low = film(1, at(20, 15), at(22, 0))
        val high = film(2, at(22, 0), at(23, 50))
        val ranked = SpotlightHighlights.films(listOf(low, high), now, zone, ratings = mapOf(low.key to 5.9, high.key to 8.6))
        assertEquals(listOf(2L, 1L), ranked.map { it.channel.id })
    }

    @Test
    fun `series stay out when asked, and come back when not`() {
        val now = at(20, 30)
        val picks = listOf(
            GuidePick(channel(1), "Serie", at(20, 15), at(21, 0), highlight = true, tip = false, doc = false, picture = null, description = null, series = true),
            pick(2, at(20, 15), at(21, 45), tip = true),
        )
        val onNow = mapOf(5L to programme(5, at(20, 0), at(21, 0)).copy(episode = "S1 E2"))
        assertEquals(listOf(2L), rank(now, sources = listOf(SpotlightSource.TIPS, SpotlightSource.NOW), picks = picks, onNow = onNow)
            .map { (it as Highlight.Pick).pick.channel.id })
        val all = SpotlightHighlights.rank(
            now, listOf(SpotlightSource.TIPS, SpotlightSource.NOW), emptyList(), channels, onNow, emptyList(), emptyList(), picks, zone,
            hideSeries = false,
        )
        assertEquals(listOf("pick:1@${at(20, 15)}", "pick:2@${at(20, 15)}", "live:5"), all.map { it.key })
    }

    @Test
    fun `the user's length is kept`() {
        val now = at(20, 30)
        val onNow = (1L..14L).associateWith { programme(it, at(20, 0), at(22, 0)) }
        val h = SpotlightHighlights.rank(now, all, emptyList(), channels, onNow, emptyList(), emptyList(), emptyList(), zone, max = 4)
        assertEquals(4, h.size)
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

    @Test
    fun `every highlight with a description shows it, folded to one paragraph`() {
        val p = programme(2, at(20, 0), at(21, 0)).copy(description = "Ein Kommissar\nermittelt   in Kiel.")
        assertEquals("Ein Kommissar ermittelt in Kiel.", descriptionOf(Highlight.Live(channel(2), p, HighlightReason.NOW)))
        val pick = GuidePick(channel(3), "T", at(20, 0), at(21, 0), true, false, false, null, "Ein Tipp des Tages mit Text.")
        assertEquals("Ein Tipp des Tages mit Text.", descriptionOf(Highlight.Pick(pick, HighlightReason.HIGHLIGHT)))
        // A label is no description, and a programme without one shows none.
        assertEquals(null, descriptionOf(Highlight.Live(channel(2), p.copy(description = "Spielfilm"), HighlightReason.NOW)))
        assertEquals(null, descriptionOf(Highlight.Live(channel(2), null, HighlightReason.HABIT)))
    }

    @Test
    fun `the top tier - premiere before highlight, top-rated films, matches, nothing else`() {
        val now = at(18, 0)
        fun tier(p: GuidePick, rating: Double? = null) = HighlightTier.of(Highlight.Pick(p, HighlightReason.FILM), rating)
        assertEquals(HighlightTier.PREMIERE, tier(film(1, at(20, 15), at(22, 0), premiere = true, highlight = true)))
        assertEquals(HighlightTier.HIGHLIGHT, tier(pick(2, at(20, 15), at(21, 0), highlight = true)))
        assertEquals(HighlightTier.TOP_RATED, tier(film(3, at(20, 15), at(22, 0)), rating = 8.1))
        assertEquals(null, tier(film(3, at(20, 15), at(22, 0)), rating = 7.9))
        // A rating says nothing about a tip that is not a film.
        assertEquals(null, tier(pick(4, at(20, 15), at(21, 0), tip = true), rating = 9.0))
        assertEquals(HighlightTier.SPORT, HighlightTier.of(Highlight.Sport(match("1", true, now), HighlightReason.SPORT_LIVE), null))
        assertEquals(null, HighlightTier.of(Highlight.Live(channel(5), null, HighlightReason.NOW), null))
    }

    @Test
    fun `a long tip TMDB knows as a film becomes one, with its year and genre`() {
        val meg = GuidePick(channel(6), "Meg", at(20, 15), at(22, 20), highlight = false, tip = true, doc = false, picture = null, description = null, filmCandidate = true)
        val show = GuidePick(channel(7), "Die große Show", at(20, 15), at(23, 0), highlight = false, tip = true, doc = false, picture = null, description = null, filmCandidate = true)
        val films = SpotlightHighlights.withFilms(listOf(meg, show), mapOf(meg.key to FilmInfo(5.7, 2018, "Action", listOf("Jason Statham", "Li Bingbing"))))
        assertEquals(listOf(true, false), films.map { it.film })
        assertEquals(2018, films[0].year)
        assertEquals("Action", films[0].genre)
        assertEquals(listOf("Jason Statham", "Li Bingbing"), films[0].cast)
        // A tip that is neither a film nor a candidate stays a tip, whatever TMDB has under its title.
        val plain = pick(8, at(20, 15), at(21, 0), tip = true)
        assertEquals(false, SpotlightHighlights.withFilms(listOf(plain), mapOf(plain.key to FilmInfo(8.0, 2001, null))).single().film)
    }

    @Test
    fun `what TMDB knows of a film survives the cache, and none stays none`() {
        val f = FilmInfo(7.4, 2018, "Science Fiction", listOf("Jason Statham", "Li Bingbing", "Rainn Wilson"))
        assertEquals(f, FilmInfo.decode(f.encode()))
        assertTrue(FilmInfo.decode(FilmInfo.NONE.encode()) === FilmInfo.NONE)
        // A film TMDB has but says nothing more of is still a film.
        assertTrue(FilmInfo.decode(FilmInfo(null, null, null).copy().encode()) !== FilmInfo.NONE)
    }
}

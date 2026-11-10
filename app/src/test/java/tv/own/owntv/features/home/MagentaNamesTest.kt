package tv.own.owntv.features.home

import org.json.JSONArray
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.Calendar
import java.util.TimeZone

/** The playlist's channel names against MagentaTV's guide, and which picture a programme gets. */
class MagentaNamesTest {
    @Test
    fun `playlist names meet the guide's`() {
        assertEquals(MagentaNames.key("SAT.1"), MagentaNames.key("DE: SAT.1 HD"))
        assertEquals(MagentaNames.key("Das Erste"), MagentaNames.key("DE: Das Erste HD"))
        assertEquals(MagentaNames.key("Romance TV"), MagentaNames.key("DE: Romance TV HD"))
        assertEquals("prosieben", MagentaNames.key("ProSieben FHD"))
    }

    @Test
    fun `the index keeps the first channel of a name`() {
        val list = JSONArray("""[{"contentId":"1","name":"Das Erste"},{"contentId":"2","name":"Das Erste HD"},{"contentId":"3","name":"ZDF"}]""")
        assertEquals(mapOf("das erste" to "1", "zdf" to "3"), MagentaNames.index(list))
    }

    @Test
    fun `the index prefers plain or HD over SD over UHD whatever the order`() {
        val list = JSONArray("""[{"contentId":"1","name":"ProSieben UHD"},{"contentId":"2","name":"ProSieben HD"},{"contentId":"3","name":"ProSieben"},{"contentId":"4","name":"ProSieben MAXX"}]""")
        assertEquals(mapOf("prosieben" to "2", "prosieben maxx" to "4"), MagentaNames.index(list))
        val sdFirst = JSONArray("""[{"contentId":"1","name":"VOX SD"},{"contentId":"2","name":"VOX 4K"},{"contentId":"3","name":"VOX HD"}]""")
        assertEquals(mapOf("vox" to "3"), MagentaNames.index(sdFirst))
        val sdUhd = JSONArray("""[{"contentId":"1","name":"VOX UHD"},{"contentId":"2","name":"VOX SD"}]""")
        assertEquals(mapOf("vox" to "2"), MagentaNames.index(sdUhd))
        assertEquals(mapOf("kabel eins" to "5"), MagentaNames.index(JSONArray("""[{"contentId":"5","name":"kabel eins SD"}]""")))
    }

    @Test
    fun `the quality rank reads the raw name`() {
        assertEquals(0, MagentaNames.qualityRank("ProSieben"))
        assertEquals(0, MagentaNames.qualityRank("ProSieben HD"))
        assertEquals(1, MagentaNames.qualityRank("ProSieben SD"))
        assertEquals(2, MagentaNames.qualityRank("ProSieben UHD"))
        assertEquals(2, MagentaNames.qualityRank("ProSieben 4K"))
    }

    @Test
    fun `a time zone suffix moves the instant, none or +00 is UTC`() {
        val base = utc(20, 15) + 3 * 24 * 3_600_000L // 2026-11-07
        assertEquals(base, MagentaNames.parseTime("2026-11-07 20:15:00 UTC+00:00"))
        assertEquals(base, MagentaNames.parseTime("2026-11-07 20:15:00"))
        assertEquals(base - 3_600_000L, MagentaNames.parseTime("2026-11-07 20:15:00 UTC+01:00"))
        assertEquals(base - 3_600_000L, MagentaNames.parseTime("2026-11-07 20:15:00 +01:00"))
        assertEquals(base + 150 * 60_000L, MagentaNames.parseTime("2026-11-07 20:15:00 UTC-02:30"))
        assertNull(MagentaNames.parseTime("garbage"))
    }

    @Test
    fun `a listing with an offset is read at its true time`() {
        val list = JSONArray("""[{"starttime":"2026-11-04 20:15:00 UTC+01:00","name":"X","pictures":[{"imageType":"17","href":"http://a/x.jpg"}]}]""")
        assertEquals("https://a/x.jpg", MagentaNames.pictureFor(list, utc(19, 15)))
        assertNull(MagentaNames.pictureFor(list, utc(20, 15)))
    }

    private fun utc(h: Int, m: Int): Long =
        Calendar.getInstance(TimeZone.getTimeZone("UTC")).apply { clear(); set(2026, 10, 4, h, m) }.timeInMillis

    private val playbills = JSONArray(
        """[
          {"starttime":"2026-11-04 19:15:00 UTC+00:00","name":"The Taste","pictures":[
            {"imageType":"1","href":"http://ngiss.t-online.de/a_h9.jpg"},
            {"imageType":"17","href":"http://ngiss.t-online.de/a_h10.jpg"}]},
          {"starttime":"2026-11-04 21:30:00 UTC+00:00","name":"Late","pictures":[
            {"imageType":"18","href":"http://ngiss.t-online.de/b_b.jpg"}]}
        ]""",
    )

    @Test
    fun `the programme starting nearest gets its 16-9 picture over https`() {
        assertEquals("https://ngiss.t-online.de/a_h10.jpg", MagentaNames.pictureFor(playbills, utc(19, 14)))
        assertEquals("https://ngiss.t-online.de/b_b.jpg", MagentaNames.pictureFor(playbills, utc(21, 35)))
    }

    @Test
    fun `nothing near the start time, no picture`() {
        assertNull(MagentaNames.pictureFor(playbills, utc(20, 20)))
    }

    private val guideDay = JSONArray(
        """[
          {"name":"Wunschkind","starttime":"2026-11-05 19:15:00 UTC+00:00","endtime":"2026-11-05 20:45:00 UTC+00:00","tipType":"highlight",
           "pictures":[{"imageType":"17","href":"http://ngiss.t-online.de/w.jpg"}],"introduce":"Ein Krimi."},
          {"name":"Europa League","starttime":"2026-11-05 19:15:00 UTC+00:00","endtime":"2026-11-05 21:15:00 UTC+00:00","tipType":"tip","tipCategory":"sports"},
          {"name":"Tagesschau","starttime":"2026-11-05 19:00:00 UTC+00:00","endtime":"2026-11-05 19:15:00 UTC+00:00","genres":"Nachrichten"},
          {"name":"Die Alpen von oben","starttime":"2026-11-05 19:15:00 UTC+00:00","endtime":"2026-11-05 20:00:00 UTC+00:00","genres":"Dokumentation, Natur"},
          {"name":"Kurzdoku","starttime":"2026-11-05 20:00:00 UTC+00:00","endtime":"2026-11-05 20:10:00 UTC+00:00","genres":"Dokumentation"}
        ]""",
    )

    private val ch = tv.own.owntv.core.database.entity.ChannelEntity(id = 7, sourceId = 1, categoryId = 9, name = "DE: arte HD", streamUrl = "http://x/7.ts", remoteId = "7")

    @Test
    fun `tips and highlights are picked, documentaries only where asked and long enough`() {
        val elsewhere = MagentaNames.picks(guideDay, ch, docs = false)
        assertEquals(listOf("Wunschkind", "Europa League"), elsewhere.map { it.title })
        assertEquals(listOf(true, false), elsewhere.map { it.highlight })
        assertEquals("https://ngiss.t-online.de/w.jpg", elsewhere.first().picture)
        assertEquals("Ein Krimi.", elsewhere.first().description)
        val onArte = MagentaNames.picks(guideDay, ch, docs = true)
        assertEquals(listOf("Wunschkind", "Europa League", "Die Alpen von oben"), onArte.map { it.title })
        val nov5 = Calendar.getInstance(TimeZone.getTimeZone("UTC")).apply { clear(); set(2026, 10, 5, 19, 15) }.timeInMillis
        assertEquals(nov5, onArte.last().startMs)
    }

    private val cinemaDay = JSONArray(
        """[
          {"name":"Dune: Part Two","starttime":"2026-11-05 19:15:00 UTC+00:00","endtime":"2026-11-05 22:00:00 UTC+00:00",
           "genres":"Spielfilm, Science-Fiction","producedate":"2024-01-01","introduce":"TV-Premiere: Paul Atreides …"},
          {"name":"Kino-News","starttime":"2026-11-05 19:00:00 UTC+00:00","endtime":"2026-11-05 19:15:00 UTC+00:00","genres":"Magazin"},
          {"name":"The Last of Us","starttime":"2026-11-05 22:00:00 UTC+00:00","endtime":"2026-11-05 23:20:00 UTC+00:00",
           "genres":"Serie, Drama","seasonNO":"2","sitcomNO":"3"},
          {"name":"Ein Film ohne Genre","starttime":"2026-11-05 23:20:00 UTC+00:00","endtime":"2026-11-06 01:00:00 UTC+00:00"}
        ]""",
    )

    private val cinema = tv.own.owntv.core.database.entity.ChannelEntity(id = 8, sourceId = 1, categoryId = 9, name = "DE: Sky Cinema Highlights HD", streamUrl = "http://x/8.ts", remoteId = "8")

    @Test
    fun `on a film channel its films are picked, with year, kind and premiere, not its series or magazines`() {
        val picks = MagentaNames.picks(cinemaDay, cinema, docs = false, films = true)
        assertEquals(listOf("Dune: Part Two", "Ein Film ohne Genre"), picks.map { it.title })
        val dune = picks.first()
        assertTrue(dune.film && dune.premiere && !dune.series)
        assertEquals(2024, dune.year)
        assertEquals("Science-Fiction", dune.genre)
        // Elsewhere only MagentaTV's own tips count: none here.
        assertEquals(emptyList<String>(), MagentaNames.picks(cinemaDay, ch, docs = false).map { it.title })
    }

    private val privateDay = JSONArray(
        """[
          {"name":"Meg","starttime":"2026-11-06 19:14:00 UTC+00:00","endtime":"2026-11-06 21:20:00 UTC+00:00","tipType":"tip"},
          {"name":"Schlag den Star","starttime":"2026-11-06 19:15:00 UTC+00:00","endtime":"2026-11-06 23:00:00 UTC+00:00","tipType":"tip","genres":"Show"},
          {"name":"Kurzer Tipp","starttime":"2026-11-06 19:00:00 UTC+00:00","endtime":"2026-11-06 19:45:00 UTC+00:00","tipType":"tip"}
        ]""",
    )

    @Test
    fun `a long tip with no genre may be a film, a show or a short one not`() {
        val picks = MagentaNames.picks(privateDay, ch, docs = false)
        assertEquals(listOf("Meg", "Schlag den Star", "Kurzer Tipp"), picks.map { it.title })
        assertEquals(listOf(true, false, false), picks.map { it.filmCandidate })
        assertTrue(picks.none { it.film })
    }

    @Test
    fun `film channels by name`() {
        assertTrue(MagentaNames.isFilmChannel("DE: Sky Cinema Premiere HD"))
        assertTrue(MagentaNames.isFilmChannel("Sky Cinema Action"))
        assertTrue(MagentaNames.isFilmChannel("MagentaTV Kino"))
        assertTrue(!MagentaNames.isFilmChannel("Sky Sport Bundesliga 1"))
        assertTrue(!MagentaNames.isFilmChannel("DE: arte HD"))
    }

    @Test
    fun `a match's picture comes from the live broadcast naming both teams, not the countdown`() {
        val list = JSONArray(
            """[
              {"starttime":"2026-11-06 18:30:00 UTC+00:00","name":"Live BL: HSV - BMG: Countdown","pictures":[
                {"imageType":"17","href":"http://ngiss.t-online.de/countdown_h10.jpg"}]},
              {"starttime":"2026-11-06 19:25:00 UTC+00:00","name":"Live BL: Hamburger SV - Borussia M'gladbach, Flutlicht-Freitag","pictures":[
                {"imageType":"17","href":"http://ngiss.t-online.de/match_h10.jpg"}]},
              {"starttime":"2026-11-06 19:30:00 UTC+00:00","name":"Live BL: Werder Bremen - Borussia Dortmund","pictures":[
                {"imageType":"17","href":"http://ngiss.t-online.de/other_h10.jpg"}]}
            ]""".trimIndent(),
        )
        val kickoff = Calendar.getInstance(TimeZone.getTimeZone("UTC")).apply { clear(); set(2026, 10, 6, 19, 30) }.timeInMillis
        val home = listOf("Hamburg SV", "Hamburg", "HSV")
        val away = listOf("Borussia Mönchengladbach", "Gladbach", "BMG")
        assertEquals("https://ngiss.t-online.de/match_h10.jpg", MagentaNames.matchPicture(list, home, away, kickoff))
        assertNull(MagentaNames.matchPicture(list, listOf("Bayern München", "Bayern", "FCB"), away, kickoff))
    }

    @Test
    fun `a conference naming the match is not its picture`() {
        val list = JSONArray(
            """[
              {"starttime":"2026-11-08 13:50:00 UTC+00:00","name":"Live PL: Goal Rush, CRY - LIV, Premier League Konferenz, 10. Spieltag","pictures":[
                {"imageType":"17","href":"http://ngiss.t-online.de/goalrush_h10.jpg"}]}
            ]""".trimIndent(),
        )
        val kickoff = Calendar.getInstance(TimeZone.getTimeZone("UTC")).apply { clear(); set(2026, 10, 8, 14, 0) }.timeInMillis
        assertNull(MagentaNames.matchPicture(list, listOf("Crystal Palace", "Palace", "CRY"), listOf("Liverpool", "Liverpool", "LIV"), kickoff))
    }

    @Test
    fun `sports channels, the Bundesliga ones first`() {
        val index = linkedMapOf("das erste" to "1", "sky sport news" to "2", "dazn 1" to "3", "sky sport bundesliga 1" to "4")
        assertEquals(listOf("4", "3", "2"), MagentaNames.sportChannels(index))
    }
}

package tv.own.owntv.features.home

import org.json.JSONArray
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import tv.own.owntv.core.parser.EpgDetails
import tv.own.owntv.features.home.MagentaMatch.Verdict

/** MagentaTV's guide as the stored EPG: parsing its programmes, planning a round, rows, and the check's bookkeeping. */
class MagentaEpgTest {
    private fun list(vararg entries: String) = JSONArray("[" + entries.joinToString(",") + "]")
    private fun entry(
        name: String? = "Tatort",
        start: String = "2026-11-10 20:15:00",
        end: String = "2026-11-10 21:45:00",
        extra: String = "",
    ) = """{${if (name != null) "\"name\":\"$name\"," else ""}"starttime":"$start","endtime":"$end"$extra}"""

    @Test
    fun `programmes are parsed with their details`() {
        val p = MagentaNames.programmes(
            list(entry(extra = ",\"introduce\":\"Ein Fall.\",\"genres\":\"Krimi, Serie/Drama\",\"producedate\":\"2019-01-01\",\"seasonNO\":\"3\",\"sitcomNO\":\"7\"")),
        ).single()
        assertEquals("Tatort", p.title)
        assertEquals("Ein Fall.", p.description)
        assertEquals(listOf("Krimi", "Serie", "Drama"), p.genres)
        assertEquals(2019, p.year)
        assertEquals("S3 E7", p.episode)
        assertEquals(90 * 60_000L, p.stopMs - p.startMs)
    }

    @Test
    fun `bad entries are skipped and the rest sorted and de-duplicated by start`() {
        val out = MagentaNames.programmes(
            list(
                entry(name = "Zweite", start = "2026-11-10 21:45:00", end = "2026-11-10 22:30:00"),
                entry(name = null),
                entry(name = "  "),
                entry(name = "Rückwärts", start = "2026-11-10 23:00:00", end = "2026-11-10 22:00:00"),
                entry(name = "Leer", start = "2026-11-10 23:00:00", end = "2026-11-10 23:00:00"),
                entry(name = "Erste"),
                entry(name = "Doppelt", start = "2026-11-10 20:15:00", end = "2026-11-10 20:50:00"),
                """{"name":"Ohne Zeit"}""",
            ),
        )
        assertEquals(listOf("Erste", "Zweite"), out.map { it.title })
        assertTrue(out.zipWithNext().all { (a, b) -> a.startMs < b.startMs })
    }

    @Test
    fun `episode text follows the xmltv form`() {
        assertEquals("S2 E5", MagentaNames.episodeText("2", "5"))
        assertEquals("E5", MagentaNames.episodeText("0", "5"))
        assertEquals("E5", MagentaNames.episodeText(null, "5"))
        assertEquals("E5", MagentaNames.episodeText("null", "5"))
        assertNull(MagentaNames.episodeText("0", "0"))
        assertNull(MagentaNames.episodeText("", "null"))
    }

    @Test
    fun `year and genres and description are optional`() {
        val p = MagentaNames.programmes(list(entry(extra = ",\"producedate\":\"abcd\",\"genres\":\"null\",\"introduce\":\"null\""))).single()
        assertNull(p.year)
        assertTrue(p.genres.isEmpty())
        assertNull(p.description)
        assertNull(p.episode)
    }

    private val index = mapOf("das erste" to "1", "zdf" to "2", "vox" to "3", "arte" to "4")
    private fun plan(
        wanted: List<String>,
        football: List<String> = emptyList(),
        rejected: Map<String, Long> = emptyMap(),
        covered: Set<String> = emptySet(),
        full: Boolean = true,
        now: Long = 1_000_000_000L,
        cap: Int = 100,
        footballCap: Int = 40,
    ) = MagentaEpgRules.plan(index, wanted, football, rejected, covered, full, now, MagentaEpg.RECHECK_REJECTED_MS, cap, footballCap)

    @Test
    fun `the plan keeps only wanted keys the guide has, once each, in Home's order`() {
        val p = plan(listOf("zdf", "unbekannt", "das erste", "zdf", "vox"))
        assertEquals(listOf("zdf", "das erste", "vox"), p.keys)
        assertEquals(p.keys, p.fetch)
        assertTrue(p.forgotten.isEmpty())
        assertTrue(p.drop.isEmpty())
    }

    @Test
    fun `a partial round fetches only what is not covered yet`() {
        val p = plan(listOf("zdf", "vox", "arte"), covered = setOf("zdf", "vox"), full = false)
        assertEquals(listOf("zdf", "vox", "arte"), p.keys)
        assertEquals(listOf("arte"), p.fetch)
        assertEquals(listOf("zdf", "vox", "arte"), plan(listOf("zdf", "vox", "arte"), covered = setOf("zdf", "vox")).fetch)
    }

    @Test
    fun `covered keys that left Home are dropped`() {
        val p = plan(listOf("zdf"), covered = setOf("zdf", "vox", "arte"), full = false)
        assertEquals(setOf("vox", "arte"), p.drop)
        assertTrue(p.fetch.isEmpty())
        // A wanted key the guide no longer has is dropped as well.
        assertEquals(setOf("alt"), plan(listOf("zdf"), covered = setOf("zdf", "alt")).drop)
        assertEquals(setOf("zdf"), plan(emptyList(), covered = setOf("zdf")).drop)
    }

    @Test
    fun `a rejected key is left out until its time is up, then forgotten and fetched again`() {
        val now = 1_000_000_000_000L
        val wanted = listOf("zdf", "vox")
        val recent = plan(wanted, rejected = mapOf("zdf" to now - 1_000), now = now)
        assertEquals(listOf("vox"), recent.keys)
        assertTrue(recent.forgotten.isEmpty())
        val old = plan(wanted, rejected = mapOf("zdf" to now - MagentaEpg.RECHECK_REJECTED_MS), now = now)
        assertEquals(listOf("zdf", "vox"), old.keys)
        assertEquals(setOf("zdf"), old.forgotten)
    }

    @Test
    fun `the plan is capped`() {
        val p = plan(listOf("zdf", "vox", "das erste"), cap = 2)
        assertEquals(listOf("zdf", "vox"), p.keys)
        assertEquals(100, MagentaEpg.MAX_CHANNELS)
        assertEquals(40, MagentaEpg.MAX_FOOTBALL)
    }

    private val withSport = index + mapOf("sky sport bundesliga 1" to "5", "dazn 1" to "6", "sky sport top event" to "7")

    private fun sportPlan(wanted: List<String>, football: List<String>, footballCap: Int = 40, cap: Int = 100, covered: Set<String> = emptySet(), rejected: Map<String, Long> = emptyMap()) =
        MagentaEpgRules.plan(withSport, wanted, football, rejected, covered, true, 1_000L, MagentaEpg.RECHECK_REJECTED_MS, cap, footballCap)

    @Test
    fun `football keys follow Home's, once, only when the guide has them`() {
        val p = sportPlan(listOf("zdf", "dazn 1"), listOf("sky sport bundesliga 1", "dazn 1", "dazn 9", "sky sport top event"))
        assertEquals(listOf("zdf", "dazn 1", "sky sport bundesliga 1", "sky sport top event"), p.keys)
        // Without Home's channels the football ones alone still make a plan.
        assertEquals(listOf("sky sport bundesliga 1"), sportPlan(emptyList(), listOf("sky sport bundesliga 1")).keys)
    }

    @Test
    fun `football keys are capped separately and the overall cap cuts them last`() {
        val football = listOf("sky sport bundesliga 1", "dazn 1", "sky sport top event")
        assertEquals(listOf("zdf", "sky sport bundesliga 1"), sportPlan(listOf("zdf"), football, footballCap = 1).keys)
        assertEquals(listOf("zdf", "vox", "sky sport bundesliga 1"), sportPlan(listOf("zdf", "vox"), football, cap = 3).keys)
    }

    @Test
    fun `rejected football keys are left out and covered ones that left are dropped`() {
        val p = sportPlan(listOf("zdf"), listOf("dazn 1", "sky sport top event"), covered = setOf("zdf", "dazn 1", "vox"), rejected = mapOf("dazn 1" to 500L))
        assertEquals(listOf("zdf", "sky sport top event"), p.keys)
        assertEquals(setOf("dazn 1", "vox"), p.drop)
    }

    @Test
    fun `football channels are Sky Sport, Bundesliga and DAZN`() {
        for (name in listOf("Sky Sport Bundesliga 1 HD", "Sky Sport Top Event", "DAZN 1 HD", "DE: Sky Sport Premier League", "Sky Bundesliga")) {
            assertTrue(name, MagentaNames.isFootballChannel(MagentaNames.key(name)))
        }
        for (name in listOf("Sky Cinema Action", "Sport1", "Eurosport 1", "ZDF", "Das Erste HD")) {
            assertFalse(name, MagentaNames.isFootballChannel(MagentaNames.key(name)))
        }
    }

    @Test
    fun `rows carry the prefixed key, the source and the details as the core expects`() {
        val sep = EpgDetails.CATEGORY_SEPARATOR
        val rows = MagentaEpgRules.rows(
            "das erste",
            listOf(
                MagentaProgramme("Tatort", 1_000L, 2_000L, "Ein Fall.", listOf("Krimi", "krimi", "Serie", "Drama", "Extra"), 2019, "S3 E7"),
                MagentaProgramme("Nachrichten", 2_000L, 3_000L, null, emptyList(), null, null),
            ),
        )
        assertEquals(2, rows.size)
        val a = rows[0]
        assertEquals("magenta:das erste", a.epgChannelId)
        assertEquals(MagentaEpg.SOURCE_ID, a.sourceId)
        assertEquals(1_000L, a.startMs)
        assertEquals(2_000L, a.stopMs)
        assertEquals("Ein Fall.", a.description)
        assertEquals("Krimi${sep}Serie${sep}Drama", a.categories)
        assertEquals(listOf("Krimi", "Serie", "Drama"), a.details.categoryList)
        assertEquals(2019, a.year)
        assertEquals("S3 E7", a.episode)
        assertNull(rows[1].categories)
        assertTrue(MagentaEpg.SOURCE_ID < 0)
    }

    @Test
    fun `observations keep the last six and ignore unknown`() {
        var h = emptyList<Verdict>()
        repeat(8) { h = MagentaEpgRules.observe(h, Verdict.MATCH).history }
        assertEquals(6, h.size)
        assertEquals(h, MagentaEpgRules.observe(h, Verdict.UNKNOWN).history)
    }

    @Test
    fun `a channel is rejected only when most of at least three observations disagree`() {
        var o = MagentaEpgRules.observe(emptyList(), Verdict.MISMATCH)
        assertFalse(o.rejected)
        o = MagentaEpgRules.observe(o.history, Verdict.MISMATCH)
        assertFalse(o.rejected) // two seen: no verdict yet
        o = MagentaEpgRules.observe(o.history, Verdict.MISMATCH)
        assertTrue(o.rejected)
        val ok = MagentaEpgRules.observe(listOf(Verdict.MATCH, Verdict.MATCH, Verdict.MATCH), Verdict.MISMATCH)
        assertFalse(ok.rejected)
    }

    @Test
    fun `compare moves magenta's programme onto the user's clock`() {
        fun row(title: String, start: Long, stop: Long) =
            tv.own.owntv.core.database.entity.EpgProgrammeEntity(sourceId = 1, epgChannelId = "x", startMs = start, stopMs = stop, title = title)
        val hour = 3_600_000L
        val now = 10 * hour
        // Provider on the user's clock (shift +60 min); magenta stored on the feed's clock, one hour earlier.
        val provider = listOf(row("Tatort", 9 * hour, 11 * hour))
        val stored = row("Tatort", 8 * hour, 10 * hour)
        assertEquals(Verdict.MATCH, MagentaEpgRules.compare(provider, stored, now, 60))
        // Unshifted, the same programme would sit two hours earlier than the provider's: no overlap.
        assertEquals(Verdict.MISMATCH, MagentaEpgRules.compare(provider, row("Tatort", 6 * hour, 8 * hour), now, 0))
        assertEquals(Verdict.UNKNOWN, MagentaEpgRules.compare(emptyList(), stored, now, 60))
        assertEquals(Verdict.UNKNOWN, MagentaEpgRules.compare(provider, null, now, 60))
    }

    @Test
    fun `the persisted forms round-trip`() {
        val rejected = mapOf("zdf" to 5L, "das erste" to 9L)
        assertEquals(rejected, MagentaEpgRules.decodeRejected(MagentaEpgRules.encodeRejected(rejected)))
        val v = mapOf("zdf" to listOf(Verdict.MATCH, Verdict.MISMATCH), "vox" to emptyList())
        assertEquals(mapOf("zdf" to listOf(Verdict.MATCH, Verdict.MISMATCH)), MagentaEpgRules.decodeVerdicts(MagentaEpgRules.encodeVerdicts(v)))
    }
}

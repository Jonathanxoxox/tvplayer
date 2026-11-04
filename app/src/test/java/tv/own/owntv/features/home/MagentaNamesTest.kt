package tv.own.owntv.features.home

import org.json.JSONArray
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
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
}

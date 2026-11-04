package tv.own.owntv.features.home

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import tv.own.owntv.core.database.entity.EpgProgrammeEntity
import tv.own.owntv.core.metadata.MetadataSearchResult
import tv.own.owntv.core.metadata.MetadataType

/** Matching an EPG title to TMDB: only the very same title, so no picture is ever someone else's. */
class ProgrammeTitlesTest {
    private fun hit(id: Int, title: String, year: Int? = null, popularity: Double = 1.0, original: String? = null) =
        MetadataSearchResult(id, MetadataType.TV, title, original, year, null, null, popularity)

    private fun programme(title: String, minutes: Int, year: Int? = null, categories: String? = null, episode: String? = null) =
        EpgProgrammeEntity(
            sourceId = 1, epgChannelId = "c", startMs = 0, stopMs = minutes * 60_000L, title = title,
            categories = categories, year = year, episode = episode,
        )

    @Test
    fun `titles are compared without case, accents or punctuation`() {
        assertEquals("tatort borowski und das", ProgrammeTitles.key("Tatort: Borowski und das …"))
        assertEquals(ProgrammeTitles.key("Café Fürst"), ProgrammeTitles.key("cafe furst"))
    }

    @Test
    fun `the whole title is asked first, then the show's name`() {
        assertEquals(
            listOf("Plusminus - Das ARD-Wirtschaftsmagazin", "Plusminus"),
            ProgrammeTitles.queries("Plusminus - Das ARD-Wirtschaftsmagazin"),
        )
        assertEquals(listOf("The Taste"), ProgrammeTitles.queries("The Taste"))
    }

    @Test
    fun `only the same title matches, the right year first`() {
        val hits = listOf(hit(1, "The Taste of Things"), hit(2, "The Taste", 2013, 5.0), hit(3, "The Taste", 2021, 2.0))
        assertEquals(2, ProgrammeTitles.best("The Taste", null, hits)?.tmdbId)
        assertEquals(3, ProgrammeTitles.best("The Taste", 2021, hits)?.tmdbId)
        assertNull(ProgrammeTitles.best("Tagesschau", null, listOf(hit(4, "Tagesschau vor 20 Jahren"))))
        assertEquals(5, ProgrammeTitles.best("Das Boot", null, listOf(hit(5, "The Boat", original = "Das Boot")))?.tmdbId)
    }

    @Test
    fun `a film is told from a show`() {
        assertTrue(ProgrammeTitles.looksLikeFilm(programme("Inception", 150, year = 2010)))
        assertTrue(ProgrammeTitles.looksLikeFilm(programme("X", 95, categories = "Spielfilm")))
        assertFalse(ProgrammeTitles.looksLikeFilm(programme("Tatort", 90, year = 2024, episode = "S01E02")))
        assertFalse(ProgrammeTitles.looksLikeFilm(programme("The Taste", 110)))
    }
}

package tv.own.owntv.features.home

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import tv.own.owntv.core.catalog.ProviderCatalogMetadataParser
import tv.own.owntv.core.database.dao.TrendingCatalogRow

/** Which copy in the playlist's library a TV film on the Spotlight plays on demand. */
class FilmLibraryTest {
    private fun row(id: Long, year: Int?, quality: Int = 0, order: Int = 0) = TrendingCatalogRow(
        id = id, sourceId = 1, categoryId = null, name = "Dune: Part Two", year = year, remoteId = null, sortOrder = order,
        canonicalTitle = "Dune: Part Two", titleSignature = "dune part two", parsedYear = year, providerLanguage = null,
        qualityRank = quality, advertisedCapabilities = null,
    )

    @Test
    fun `the guide's title and the playlist's name meet on one signature`() {
        assertEquals(
            ProviderCatalogMetadataParser.parse("Dune: Part Two", 2024).titleSignature,
            ProviderCatalogMetadataParser.parse("Dune: Part Two (2024)", null).titleSignature,
        )
    }

    @Test
    fun `the best quality of the right year, not a remake of another year`() {
        val rows = listOf(row(1, 2024, quality = 2), row(2, 2024, quality = 4), row(3, 1984, quality = 5))
        assertEquals(2L, FilmLibrary.best(rows, 2024)?.id)
        // The guide does not say the year: the best copy whatever its year.
        assertEquals(3L, FilmLibrary.best(rows, null)?.id)
        // Only another year in the library: no match.
        assertNull(FilmLibrary.best(listOf(row(3, 1984)), 2024))
        // A copy with no year counts.
        assertEquals(4L, FilmLibrary.best(listOf(row(4, null)), 2024)?.id)
    }
}

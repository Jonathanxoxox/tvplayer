package tv.own.owntv.features.home

import kotlinx.coroutines.flow.first
import tv.own.owntv.core.catalog.ProviderCatalogMetadataParser
import tv.own.owntv.core.database.dao.MovieDao
import tv.own.owntv.core.database.dao.SourceDao
import tv.own.owntv.core.database.dao.TrendingCatalogRow
import tv.own.owntv.core.model.MediaType
import tv.own.owntv.core.repository.activeProfileSources
import tv.own.owntv.core.settings.SettingsRepository
import kotlin.math.abs

/**
 * Tonight's TV films that the playlist also has on demand (Jonathan, 6 Nov 2026: "the On demand button should
 * show on films that are in the library too"). The Spotlight then offers to watch the film from the start,
 * whenever, instead of waiting for 20:15 on Sky Cinema.
 *
 * Matched the way Trending finds its titles in the playlist: by the title's signature (its letters and digits,
 * the provider's tags and qualities stripped, [ProviderCatalogMetadataParser]), the year within one when both
 * are known. Of several copies the best quality wins, then the earlier in the provider's order.
 */
class FilmLibrary(
    private val settings: SettingsRepository,
    private val sourceDao: SourceDao,
    private val movieDao: MovieDao,
) {
    /** For each of [films] that the profile's movies have: its [GuidePick.key] → the movie's id. */
    suspend fun find(films: List<GuidePick>): Map<String, Long> {
        if (films.isEmpty()) return emptyMap()
        val sourceIds = activeProfileSources(settings, sourceDao).first { it.profileId >= 0 }.sourceIdsFor(MediaType.MOVIE)
        if (sourceIds.isEmpty()) return emptyMap()
        val signatures = films.associateWith { ProviderCatalogMetadataParser.parse(it.title, it.year).titleSignature }
        val rows = sourceIds.flatMap { movieDao.trendingExact(it, signatures.values.distinct()) }
        if (rows.isEmpty()) return emptyMap()
        val bySignature = rows.groupBy { it.titleSignature }
        return signatures.mapNotNull { (film, signature) ->
            bySignature[signature]?.let { best(it, film.year) }?.let { film.key to it.id }
        }.toMap()
    }

    internal companion object {
        /** The copy to play: the same year (within one) when both say, then the best quality, then the provider's order. */
        fun best(rows: List<TrendingCatalogRow>, year: Int?): TrendingCatalogRow? = rows
            .filter { row -> year == null || (row.parsedYear ?: row.year)?.let { abs(it - year) <= 1 } != false }
            .sortedWith(compareByDescending<TrendingCatalogRow> { it.qualityRank }.thenBy { it.sortOrder }.thenBy { it.id })
            .firstOrNull()
    }
}

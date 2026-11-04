package tv.own.owntv.features.home

import android.content.Context
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import tv.own.owntv.core.database.entity.EpgProgrammeEntity
import tv.own.owntv.core.metadata.MetadataImages
import tv.own.owntv.core.metadata.MetadataProvider
import tv.own.owntv.core.metadata.MetadataSearchResult
import java.text.Normalizer
import java.util.concurrent.ConcurrentHashMap

/**
 * A wide picture for a TV programme, for the Spotlight and the Stage (Jonathan, 4 Nov 2026: "it should
 * look good"). The provider's EPG carries no pictures. First choice is MagentaTV's guide ([MagentaGuide]);
 * when it has none, the programme's title is looked up on TMDB, as films and series already are. Only a title that is the same as TMDB's counts,
 * so a news programme or a match keeps its logo rather than showing someone else's picture.
 *
 * Kept on the device: a picture for [FOUND_TTL_MS], "none" for [NONE_TTL_MS]. A failed lookup (no network,
 * no key) is not kept, and is tried again next time.
 */
class ProgrammeArt(private val provider: MetadataProvider, private val magenta: MagentaGuide, context: Context) {
    private val prefs = context.applicationContext.getSharedPreferences(FILE, Context.MODE_PRIVATE)
    /** Title key → picture URL, or "" for none. */
    private val memory = ConcurrentHashMap<String, String>()

    /** MagentaTV's picture of this very airing on [channelName] first, then TMDB's by title. */
    suspend fun backdrop(programme: EpgProgrammeEntity, channelName: String? = null): String? {
        channelName?.let { magenta.picture(it, programme.startMs) }?.let { return it }
        val key = ProgrammeTitles.key(programme.title)
        if (key.isBlank()) return null
        memory[key]?.let { return it.ifEmpty { null } }
        val now = System.currentTimeMillis()
        prefs.getString(key, null)?.let { stored ->
            val at = stored.substringBefore(SEP).toLongOrNull() ?: 0L
            val url = stored.substringAfter(SEP, "")
            if (now - at < if (url.isEmpty()) NONE_TTL_MS else FOUND_TTL_MS) {
                memory[key] = url
                return url.ifEmpty { null }
            }
        }
        val url = withContext(Dispatchers.IO) { runCatching { lookup(programme) }.getOrNull() } ?: return null
        memory[key] = url
        prefs.edit().putString(key, "$now$SEP$url").apply()
        return url.ifEmpty { null }
    }

    /** The picture's URL, "" when TMDB has nothing that is this programme, null when TMDB could not be asked. */
    private suspend fun lookup(p: EpgProgrammeEntity): String? {
        val film = ProgrammeTitles.looksLikeFilm(p)
        for (query in ProgrammeTitles.queries(p.title)) {
            for (asFilm in if (film) listOf(true, false) else listOf(false, true)) {
                val hits = (if (asFilm) provider.searchMovie(query, p.year) else provider.searchTv(query)) ?: return null
                val hit = ProgrammeTitles.best(query, p.year.takeIf { asFilm }, hits) ?: continue
                val details = (if (asFilm) provider.movieDetails(hit.tmdbId) else provider.tvDetails(hit.tmdbId)) ?: return null
                val url = MetadataImages.backdrop(details.backdropPath, BACKDROP_SIZE) ?: continue
                return url
            }
        }
        return ""
    }

    private companion object {
        const val FILE = "mayo_programme_art"
        const val SEP = '|'
        const val FOUND_TTL_MS = 30L * 24 * 60 * 60_000
        const val NONE_TTL_MS = 3L * 24 * 60 * 60_000
        /** Wide enough for the Spotlight's 960 × 540 picture on a 4K panel without being heavy. */
        const val BACKDROP_SIZE = "w1280"
    }
}

/** How a programme's EPG title is matched to TMDB's. */
internal object ProgrammeTitles {
    private val nonWord = Regex("[^\\p{L}\\p{N}]+")
    private val marks = Regex("\\p{Mn}+")

    /** Lower case, accents and punctuation gone, single spaces: "Tatort: Borowski!" → "tatort borowski". */
    fun key(title: String): String =
        Normalizer.normalize(title, Normalizer.Form.NFD).replace(marks, "").lowercase().replace(nonWord, " ").trim()

    /** The whole title first, then the show's name before an episode title ("Tatort: Borowski …" → "Tatort"). */
    fun queries(title: String): List<String> = listOf(
        title,
        title.substringBefore(" - "),
        title.substringBefore(" – "),
        title.substringBefore(": "),
    ).map { it.trim() }.filter { key(it).length >= 2 }.distinctBy(::key)

    /** A film by the feed's own words, or by its length together with a year. */
    fun looksLikeFilm(p: EpgProgrammeEntity): Boolean {
        val cats = p.categories.orEmpty().lowercase()
        if (listOf("spielfilm", "film", "movie").any { it in cats } && "serie" !in cats) return true
        val minutes = p.lengthMin ?: ((p.stopMs - p.startMs) / 60_000L).toInt()
        return p.year != null && minutes >= 80 && p.episode == null
    }

    /** TMDB's hit with the very same title (or original title), the right year first, then the best known. */
    fun best(query: String, year: Int?, hits: List<MetadataSearchResult>): MetadataSearchResult? {
        val k = key(query)
        return hits
            .filter { key(it.title) == k || it.originalTitle?.let(::key) == k }
            .sortedWith(compareByDescending<MetadataSearchResult> { year != null && it.year == year }.thenByDescending { it.popularity })
            .firstOrNull()
    }
}

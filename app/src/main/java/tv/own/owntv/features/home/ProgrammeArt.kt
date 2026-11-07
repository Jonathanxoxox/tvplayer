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

    /**
     * The picture as far as it is known now, without asking anyone: its URL, "" when there is none, null when
     * it still has to be looked up. Lets the Spotlight show a fetched-ahead picture at once.
     */
    fun peek(programme: EpgProgrammeEntity, channelName: String? = null): String? {
        if (channelName != null) {
            val fromGuide = magenta.peek(channelName, programme.startMs) ?: return null
            if (fromGuide.isNotEmpty()) return fromGuide
        }
        return memory[ProgrammeTitles.key(programme.title)]
    }

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

    /** Film title key → what TMDB knows of it, or [FilmInfo.NONE] when it has no such film; kept like the pictures. */
    private val films = ConcurrentHashMap<String, FilmInfo>()

    /** What TMDB knows of the film called [title], as far as it is known now, without asking: null when none, or not yet asked. */
    fun peekFilm(title: String): FilmInfo? = films[ProgrammeTitles.key(title)]?.takeUnless { it === FilmInfo.NONE }

    /** Whether [title] has been asked about already (with or without a film found). */
    fun filmKnown(title: String): Boolean = films.containsKey(ProgrammeTitles.key(title))

    /**
     * The film called [title] (made in [year], when known) as TMDB has it: its rating, year and first genre, for
     * the Spotlight's films (Jonathan, 6 Nov 2026), and to tell a film from a long show when the guide does not
     * say (7 Nov 2026: "Meg" on ProSieben came as a tip with no genre). Only a film with the very same title
     * counts. Null when TMDB has none, or could not be asked.
     */
    suspend fun film(title: String, year: Int?): FilmInfo? {
        val key = ProgrammeTitles.key(title)
        if (key.isBlank()) return null
        films[key]?.let { return it.takeUnless { f -> f === FilmInfo.NONE } }
        val now = System.currentTimeMillis()
        prefs.getString(FILM + key, null)?.let { stored ->
            val at = stored.substringBefore(SEP).toLongOrNull() ?: 0L
            val value = FilmInfo.decode(stored.substringAfter(SEP, ""))
            if (now - at < if (value === FilmInfo.NONE) NONE_TTL_MS else FILM_TTL_MS) {
                films[key] = value
                return value.takeUnless { it === FilmInfo.NONE }
            }
        }
        val found = withContext(Dispatchers.IO) {
            runCatching {
                // Unanswered (no network, no key): nothing is kept, and it is asked again next time.
                val hits = provider.searchMovie(title, year) ?: return@runCatching null
                val hit = ProgrammeTitles.best(title, year, hits) ?: return@runCatching FilmInfo.NONE
                val details = provider.movieDetails(hit.tmdbId) ?: return@runCatching null
                FilmInfo(details.rating?.takeIf { it > 0.0 }, details.year ?: hit.year, details.genres.firstOrNull())
            }.getOrNull()
        } ?: return null
        films[key] = found
        prefs.edit().putString(FILM + key, "$now$SEP${found.encode()}").apply()
        return found.takeUnless { it === FilmInfo.NONE }
    }

    private companion object {
        const val FILE = "mayo_programme_art"
        const val FILM = "film:"
        /** A rating moves slowly: a week. */
        const val FILM_TTL_MS = 7L * 24 * 60 * 60_000
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

/** What TMDB knows of a TV film: its rating (0–10), year and first genre, each when it says. */
data class FilmInfo(val rating: Double?, val year: Int?, val genre: String?) {
    internal fun encode(): String =
        if (this === NONE) "" else listOf(rating?.toString().orEmpty(), year?.toString().orEmpty(), genre.orEmpty()).joinToString(FIELD)

    internal companion object {
        /** TMDB has no film of that title. */
        val NONE = FilmInfo(null, null, null)
        private const val FIELD = "\t"

        fun decode(text: String): FilmInfo {
            if (text.isEmpty()) return NONE
            val f = text.split(FIELD)
            return FilmInfo(f.getOrNull(0)?.toDoubleOrNull(), f.getOrNull(1)?.toIntOrNull(), f.getOrNull(2)?.ifBlank { null })
        }
    }
}

package tv.own.owntv.features.search

import android.content.Context
import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONArray
import org.json.JSONObject
import tv.own.owntv.BuildConfig
import tv.own.owntv.R
import tv.own.owntv.core.database.dao.MovieDao
import tv.own.owntv.core.database.dao.SeriesDao
import tv.own.owntv.core.database.entity.MovieEntity
import tv.own.owntv.core.database.entity.SeriesEntity
import tv.own.owntv.core.metadata.MetadataType
import tv.own.owntv.core.metadata.TrendingCandidate
import tv.own.owntv.core.model.MediaType
import tv.own.owntv.core.repository.ActiveProfileSources
import tv.own.owntv.core.settings.SettingsRepository
import tv.own.owntv.core.trending.TrendingMatcher
import tv.own.owntv.features.search.JevClient.Companion.choice
import tv.own.owntv.features.search.JevClient.Companion.noul
import java.net.URLEncoder
import java.util.concurrent.TimeUnit

/** What a described search ("Stonermovie", "was Gruseliges") found in the playlist, best fit first. */
data class MoodResults(val movies: List<MovieEntity> = emptyList(), val series: List<SeriesEntity> = emptyList()) {
    val isEmpty: Boolean get() = movies.isEmpty() && series.isEmpty()
}

/**
 * Search by description, with Jev deciding and code doing everything else:
 *
 * 1. Code looks the query's words up as TMDB keywords ("stoner" → "stoner comedy", "marijuana").
 * 2. Jev, in one request: is this a description at all (not a title)? Movie or series? Which TMDB
 *    genres and which of those keywords fit? It only picks from what code put in front of it.
 * 3. Code asks TMDB Discover for popular titles with the picked keywords and genres, and keeps the
 *    ones the playlist has — the same title matching the Trending row uses.
 * 4. Jev judges each kept title (genres + plot) against the wish; the best fits come first.
 *
 * The questions' wording lives in res/raw/jev_mood_search.json. Off without a TypeSafe key
 * (GitHub secret TYPESAFE_API_KEY) or a TMDB key.
 */
class MoodSearch(
    private val context: Context,
    okHttp: OkHttpClient,
    private val settings: SettingsRepository,
    private val movieDao: MovieDao,
    private val seriesDao: SeriesDao,
) {
    private val jev = JevClient(okHttp, BuildConfig.TYPESAFE_API_KEY)
    private val http = okHttp.newBuilder().callTimeout(15, TimeUnit.SECONDS).build()
    private val prompts: JSONObject by lazy {
        JSONObject(context.resources.openRawResource(R.raw.jev_mood_search).bufferedReader().use { it.readText() })
    }

    /** Last answers per query, so Back to Search and retyping cost nothing. */
    private val cache = object : LinkedHashMap<String, MoodResults>(16, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, MoodResults>?) = size > CACHE_SIZE
    }

    val available: Boolean get() = jev.available

    fun cached(query: String): MoodResults? = synchronized(cache) { cache[cacheKey(query)] }

    /** Null when a call failed (try again later); empty when the query is no description or nothing fits. */
    suspend fun search(query: String, sources: ActiveProfileSources): MoodResults? =
        search(query, sources.movieSourceIds, sources.seriesSourceIds, followKind = true)

    /**
     * The same search for one section (Filme or Serien: the other side's ids empty). [followKind]
     * false keeps the section's titles even when the wish says "Serie" in Filme.
     */
    private suspend fun search(query: String, movieSourceIds: List<Long>, seriesSourceIds: List<Long>, followKind: Boolean): MoodResults? {
        val wish = query.trim()
        if (!available || wish.length < MIN_LENGTH) return MoodResults()
        // The global search (both sides) and a section's search keep separate answers.
        val key = cacheKey(wish) + if (followKind) "" else "#" + movieSourceIds.isNotEmpty() + seriesSourceIds.isNotEmpty()
        synchronized(cache) { cache[key] }?.let { return it }
        val tmdb = tmdbAccess() ?: return null
        val found = withTimeoutOrNull(TOTAL_TIMEOUT_MS) { runSearch(wish, movieSourceIds, seriesSourceIds, followKind, tmdb) } ?: return null
        synchronized(cache) { cache[key] = found }
        return found
    }

    private suspend fun runSearch(
        wish: String,
        movieSourceIds: List<Long>,
        seriesSourceIds: List<Long>,
        followKind: Boolean,
        tmdb: Tmdb,
    ): MoodResults? = coroutineScope {
        // 1. Keyword candidates for Jev to choose from.
        val keywords = keywordTerms(wish)
            .map { term -> async { tmdb.keywords(term) } }.awaitAll()
            .flatten().distinctBy { it.first }.take(MAX_KEYWORDS)

        // 2. One request: description?, movie or series?, genres, keywords.
        val questions = JSONObject()
            .put(Q_DESCRIBES, prompts.getJSONObject("describes"))
            .put(Q_KIND, prompts.getJSONObject("kind"))
        GENRES.forEachIndexed { i, g -> questions.put("$Q_GENRE$i", template("genre", "{genre}", g.name)) }
        keywords.forEachIndexed { i, _ -> questions.put("$Q_KEYWORD$i", template("keyword", "{index}", i.toString())) }
        val state = JSONObject().put("wish", wish).put("keywords", JSONArray(keywords.map { it.second }))
        val first = jev.ask(state, questions) ?: return@coroutineScope null
        if (first.noul(Q_DESCRIBES) < DESCRIBES_THRESHOLD) return@coroutineScope MoodResults()

        val pickedKeywords = keywords.indices
            .map { it to first.noul("$Q_KEYWORD$it") }
            .filter { it.second >= PICK_THRESHOLD }.sortedByDescending { it.second }
            .take(MAX_PICKED_KEYWORDS).map { keywords[it.first].first }
        val pickedGenres = GENRES.indices
            .map { it to first.noul("$Q_GENRE$it") }
            .filter { it.second >= PICK_THRESHOLD }.sortedByDescending { it.second }
            .take(MAX_PICKED_GENRES).map { GENRES[it.first] }
        if (pickedKeywords.isEmpty() && pickedGenres.isEmpty()) return@coroutineScope MoodResults()
        Log.i(TAG, "keywords=${keywords.filter { it.first in pickedKeywords }.map { it.second }} genres=${pickedGenres.map { it.name }}")

        val kind = if (followKind) first.choice(Q_KIND) else null
        val wantMovies = kind != KIND_SERIES && movieSourceIds.isNotEmpty()
        val wantSeries = kind != KIND_MOVIE && seriesSourceIds.isNotEmpty()

        // 3 + 4 per type, both at once.
        val movies = async {
            if (!wantMovies) emptyList()
            else findInPlaylist(wish, MetadataType.MOVIE, pickedKeywords, pickedGenres.mapNotNull { it.movie }, tmdb, movieSourceIds)
        }
        val series = async {
            if (!wantSeries) emptyList()
            else findInPlaylist(wish, MetadataType.TV, pickedKeywords, pickedGenres.mapNotNull { it.tv }, tmdb, seriesSourceIds)
        }
        val movieIds = movies.await()
        val seriesIds = series.await()
        val movieRows = if (movieIds.isEmpty()) emptyMap() else movieDao.getByIds(movieIds).associateBy { it.id }
        val seriesRows = if (seriesIds.isEmpty()) emptyMap() else seriesDao.getSeriesByIds(seriesIds).associateBy { it.id }
        MoodResults(movieIds.mapNotNull { movieRows[it] }, seriesIds.mapNotNull { seriesRows[it] })
    }

    /** Playlist item ids for one type, best fit first. */
    private suspend fun findInPlaylist(
        wish: String,
        type: MetadataType,
        keywordIds: List<Int>,
        genreIds: List<Int>,
        tmdb: Tmdb,
        sourceIds: List<Long>,
    ): List<Long> = coroutineScope {
        val keywordParam = keywordIds.joinToString("|")
        // Genres AND-ed: "horror comedy" wants both, and two strong genres are rarely an accident.
        val genreParam = genreIds.distinct().joinToString(",")
        val pages = buildList {
            if (keywordParam.isNotEmpty()) for (p in 1..KEYWORD_PAGES) add(async { tmdb.discover(type, "with_keywords", keywordParam, p, minVotes = 5) })
            if (genreParam.isNotEmpty()) for (p in 1..GENRE_PAGES) add(async { tmdb.discover(type, "with_genres", genreParam, p, minVotes = 80) })
        }.awaitAll().flatten()
        val unique = pages.distinctBy { it.candidate.tmdbId }.take(MAX_CANDIDATES)
        if (unique.isEmpty()) return@coroutineScope emptyList()
        // The matcher walks candidates in rank order: keyword hits first, then genre hits by popularity.
        val ranked = unique.mapIndexed { i, d -> d.copy(candidate = d.candidate.copy(trendingRank = i + 1)) }
        val byTmdb = ranked.associateBy { it.candidate.tmdbId }

        val matched = matchInPlaylist(ranked.map { it.candidate }, type, sourceIds, MAX_MATCHES)
        if (matched.isEmpty()) return@coroutineScope emptyList()
        val inPlaylist = matched.keys.sortedBy { byTmdb.getValue(it).candidate.trendingRank }.take(MAX_MATCHES)

        // 4. Jev judges every kept title against the wish, a chunk per request, all at once.
        val chunks = inPlaylist.chunked(RERANK_CHUNK)
        val fits = chunks.map { chunk -> async { rerank(wish, chunk.map { byTmdb.getValue(it) }) } }.awaitAll()
        val scores = HashMap<Int, Double>()
        fits.forEachIndexed { c, answer ->
            if (answer != null) scores.putAll(answer) else chunks[c].forEach { scores[it] = FALLBACK_FIT }
        }
        inPlaylist
            .filter { (scores[it] ?: 0.0) >= FIT_THRESHOLD }
            .sortedWith(compareByDescending<Int> { scores[it] ?: 0.0 }.thenBy { byTmdb.getValue(it).candidate.trendingRank })
            .take(MAX_RESULTS)
            .map { matched.getValue(it) }
    }

    /** tmdbId → playlist item for [candidates] in rank order: the same title matching the Trending row uses. */
    private suspend fun matchInPlaylist(candidates: List<TrendingCandidate>, type: MetadataType, sourceIds: List<Long>, limit: Int): LinkedHashMap<Int, Long> {
        val mediaType = if (type == MetadataType.MOVIE) MediaType.MOVIE else MediaType.SERIES
        val language = settings.metadataConfig().resolvedLanguage
        val matched = LinkedHashMap<Int, Long>()
        for (sid in sourceIds) {
            val result = TrendingMatcher.matchMedia(
                candidates = candidates,
                mediaType = mediaType,
                preferredLanguage = language,
                limit = limit,
                exactLookup = { sigs -> if (type == MetadataType.MOVIE) movieDao.trendingExact(sid, sigs) else seriesDao.trendingExact(sid, sigs) },
                ftsLookup = { q, n -> if (type == MetadataType.MOVIE) movieDao.trendingFts(sid, q, n) else seriesDao.trendingFts(sid, q, n) },
            )
            result.selections.forEach { s -> matched.putIfAbsent(s.candidate.tmdbId, s.variant.item.id) }
        }
        return matched
    }

    // --- Filme / Serien: one search for titles, people and descriptions ---

    /** Last section extras per query and section, so retyping costs nothing. */
    private val sectionCache = object : LinkedHashMap<String, List<Long>>(16, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, List<Long>>?) = size > CACHE_SIZE
    }

    /**
     * What the Filme or Serien search shows after the title matches: the section's titles with a
     * person of that name (actor or director, "Tom Hanks", "Nolan"), then what the search by
     * description finds ("Horror", "Komödie mit Zombies"). Playlist ids of [type], best first;
     * empty without a TMDB key, offline, or when the query is neither.
     */
    suspend fun sectionExtras(query: String, type: MediaType, sourceIds: List<Long>): List<Long> {
        val wish = query.trim()
        if (wish.length < MIN_SECTION_LENGTH || sourceIds.isEmpty()) return emptyList()
        val key = "${cacheKey(wish)}#${type.name}#${sourceIds.sorted().joinToString(",")}"
        synchronized(sectionCache) { sectionCache[key] }?.let { return it }
        val tmdb = tmdbAccess() ?: return emptyList()
        val movie = type == MediaType.MOVIE
        val found = coroutineScope {
            val people = async { withTimeoutOrNull(PERSON_TIMEOUT_MS) { personTitles(wish, if (movie) MetadataType.MOVIE else MetadataType.TV, tmdb, sourceIds) } }
            val described = async {
                if (!available) emptyList()
                else search(wish, if (movie) sourceIds else emptyList(), if (movie) emptyList() else sourceIds, followKind = false)
                    ?.let { r -> if (movie) r.movies.map { it.id } else r.series.map { it.id } }
            }
            val p = people.await()
            val d = described.await()
            // A failed call is not cached, so the next keystroke tries again.
            if (p == null || d == null) return@coroutineScope (p.orEmpty() + d.orEmpty()).distinct()
            (p + d).distinct().also { ids -> synchronized(sectionCache) { sectionCache[key] = ids } }
        }
        return found
    }

    /**
     * The global search's actor and director finds: the playlist's films and series of a person called
     * [query] ("Tom Cruise", "Nolan"). Empty when no well-known person has that name; null when a call failed.
     */
    suspend fun people(query: String, sources: ActiveProfileSources): MoodResults? {
        // Spoken, a person comes wrapped: "Filme mit Tom Hanks" asks TMDB for "Tom Hanks".
        val wish = personName(query)
        if (wish.length < MIN_SECTION_LENGTH) return MoodResults()
        val key = cacheKey(wish) + "#people"
        synchronized(cache) { cache[key] }?.let { return it }
        val tmdb = tmdbAccess() ?: return MoodResults()
        val found = withTimeoutOrNull(PERSON_TIMEOUT_MS) {
            coroutineScope {
                val m = async { if (sources.movieSourceIds.isEmpty()) emptyList() else personTitles(wish, MetadataType.MOVIE, tmdb, sources.movieSourceIds) }
                val s = async { if (sources.seriesSourceIds.isEmpty()) emptyList() else personTitles(wish, MetadataType.TV, tmdb, sources.seriesSourceIds) }
                val movieIds = m.await() ?: return@coroutineScope null
                val seriesIds = s.await() ?: return@coroutineScope null
                val movieRows = if (movieIds.isEmpty()) emptyMap() else movieDao.getByIds(movieIds).associateBy { it.id }
                val seriesRows = if (seriesIds.isEmpty()) emptyMap() else seriesDao.getSeriesByIds(seriesIds).associateBy { it.id }
                MoodResults(movieIds.mapNotNull { movieRows[it] }, seriesIds.mapNotNull { seriesRows[it] })
            }
        } ?: return null
        synchronized(cache) { cache[key] = found }
        return found
    }

    /** Playlist ids of the best-known person called [wish]: their films or series, most popular first. Null = a call failed. */
    private suspend fun personTitles(wish: String, type: MetadataType, tmdb: Tmdb, sourceIds: List<Long>): List<Long>? {
        val personId = tmdb.person(wish) ?: return emptyList()
        val credits = tmdb.credits(personId, type) ?: return null
        if (credits.isEmpty()) return emptyList()
        val ranked = credits.mapIndexed { i, c -> c.copy(trendingRank = i + 1) }
        val rank = ranked.associate { it.tmdbId to it.trendingRank }
        val matched = matchInPlaylist(ranked, type, sourceIds, MAX_PERSON_MATCHES)
        return matched.keys.sortedBy { rank[it] ?: Int.MAX_VALUE }.map { matched.getValue(it) }
    }

    /** tmdbId → probability that the title fits the wish; null when the request failed. */
    private suspend fun rerank(wish: String, titles: List<Discovered>): Map<Int, Double>? {
        val list = JSONArray()
        val questions = JSONObject()
        titles.forEachIndexed { i, d ->
            list.put(
                JSONObject()
                    .put("title", d.candidate.localizedTitle)
                    .put("original_title", d.candidate.originalTitle ?: d.candidate.localizedTitle)
                    .put("year", d.candidate.year?.toString().orEmpty())
                    .put("genres", JSONArray(d.genres))
                    .put("overview", d.candidate.overview.orEmpty().take(OVERVIEW_CHARS)),
            )
            questions.put("$Q_FIT$i", template("fit", "{index}", i.toString()))
        }
        val answers = jev.ask(JSONObject().put("wish", wish).put("titles", list), questions) ?: return null
        return titles.withIndex().associate { (i, d) -> d.candidate.tmdbId to answers.noul("$Q_FIT$i") }
    }

    private fun template(id: String, placeholder: String, value: String): JSONObject =
        JSONObject(prompts.getJSONObject(id).toString().replace(placeholder, value))

    // --- TMDB ---

    private data class Discovered(val candidate: TrendingCandidate, val genres: List<String>)

    private inner class Tmdb(val key: String, val language: String) {
        private fun langParam() = if (language.isBlank()) "" else "&language=" + enc(language)

        suspend fun keywords(term: String): List<Pair<Int, String>> {
            val body = get("$TMDB_BASE/3/search/keyword?api_key=${enc(key)}&query=${enc(term)}") ?: return emptyList()
            val results = runCatching { JSONObject(body).optJSONArray("results") }.getOrNull() ?: return emptyList()
            return (0 until minOf(results.length(), KEYWORDS_PER_TERM)).mapNotNull { i ->
                val o = results.optJSONObject(i) ?: return@mapNotNull null
                val id = o.optInt("id", 0)
                val name = o.optString("name")
                if (id > 0 && name.isNotBlank()) id to name else null
            }
        }

        suspend fun discover(type: MetadataType, filter: String, value: String, page: Int, minVotes: Int): List<Discovered> {
            val path = if (type == MetadataType.MOVIE) "movie" else "tv"
            val url = "$TMDB_BASE/3/discover/$path?api_key=${enc(key)}&$filter=${enc(value)}" +
                "&sort_by=popularity.desc&include_adult=false&vote_count.gte=$minVotes&page=$page" + langParam()
            val body = get(url) ?: return emptyList()
            val results = runCatching { JSONObject(body).optJSONArray("results") }.getOrNull() ?: return emptyList()
            return (0 until results.length()).mapNotNull { i ->
                val o = results.optJSONObject(i) ?: return@mapNotNull null
                val candidate = candidate(o, type, (page - 1) * 20 + i + 1) ?: return@mapNotNull null
                val tv = type == MetadataType.TV
                val genreIds = o.optJSONArray("genre_ids")?.let { a -> (0 until a.length()).map { a.optInt(it) } }.orEmpty()
                Discovered(
                    candidate,
                    genres = genreIds.mapNotNull { g -> GENRES.firstOrNull { if (tv) it.tv == g else it.movie == g }?.name }.distinct(),
                )
            }
        }

        /** The best-known person whose name is [name] or contains its words ("hanks" → Tom Hanks); null = none. */
        suspend fun person(name: String): Int? {
            val body = get("$TMDB_BASE/3/search/person?api_key=${enc(key)}&query=${enc(name)}&include_adult=false") ?: return null
            val results = runCatching { JSONObject(body).optJSONArray("results") }.getOrNull() ?: return null
            val words = name.lowercase().split(' ').filter { it.isNotBlank() }
            return (0 until minOf(results.length(), PERSON_RESULTS)).firstNotNullOfOrNull { i ->
                val o = results.optJSONObject(i) ?: return@firstNotNullOfOrNull null
                val nameWords = o.optString("name").lowercase().split(' ')
                val matches = words.all { w -> nameWords.any { it.startsWith(w) } }
                val known = o.optString("known_for_department") in PERSON_DEPARTMENTS
                o.optInt("id", 0).takeIf { it > 0 && matches && known && o.optDouble("popularity", 0.0) >= MIN_PERSON_POPULARITY }
            }
        }

        /** [personId]'s films or series (cast, and what they directed), most popular first; null = the call failed. */
        suspend fun credits(personId: Int, type: MetadataType): List<TrendingCandidate>? {
            val path = if (type == MetadataType.MOVIE) "movie_credits" else "tv_credits"
            val body = get("$TMDB_BASE/3/person/$personId/$path?api_key=${enc(key)}" + langParam()) ?: return null
            val json = runCatching { JSONObject(body) }.getOrNull() ?: return null
            val entries = buildList {
                json.optJSONArray("cast")?.let { a -> for (i in 0 until a.length()) a.optJSONObject(i)?.let { add(it) } }
                json.optJSONArray("crew")?.let { a -> for (i in 0 until a.length()) a.optJSONObject(i)?.takeIf { it.optString("job") in CREW_JOBS }?.let { add(it) } }
            }
            return entries
                // Talk shows, news and reality TV a person only visited are not "their" series.
                .filter { o -> o.optJSONArray("genre_ids")?.let { a -> (0 until a.length()).none { a.optInt(it) in GUEST_GENRES } } ?: true }
                .mapNotNull { candidate(it, type, 0) }
                .distinctBy { it.tmdbId }
                .sortedByDescending { it.popularity }
                .take(MAX_PERSON_CANDIDATES)
        }

        private fun candidate(o: JSONObject, type: MetadataType, rank: Int): TrendingCandidate? {
            val id = o.optInt("id", 0).takeIf { it > 0 } ?: return null
            val tv = type == MetadataType.TV
            val title = o.optString(if (tv) "name" else "title").value() ?: o.optString(if (tv) "original_name" else "original_title").value() ?: return null
            val date = o.optString(if (tv) "first_air_date" else "release_date")
            return TrendingCandidate(
                tmdbId = id,
                type = type,
                localizedTitle = title,
                originalTitle = o.optString(if (tv) "original_name" else "original_title").value(),
                year = date.take(4).toIntOrNull(),
                overview = o.optString("overview").value(),
                posterPath = o.optString("poster_path").value(),
                backdropPath = o.optString("backdrop_path").value(),
                rating = o.optDouble("vote_average", 0.0).takeIf { it > 0.0 },
                popularity = o.optDouble("popularity", 0.0),
                trendingRank = rank,
            )
        }

        private suspend fun get(url: String): String? = withContext(Dispatchers.IO) {
            runCatching {
                http.newCall(Request.Builder().url(url).build()).execute().use { r -> if (r.isSuccessful) r.body.string() else null }
            }.onFailure { Log.w(TAG, "TMDB failed: ${it.message}") }.getOrNull()
        }
    }

    private suspend fun tmdbAccess(): Tmdb? {
        val cfg = settings.metadataConfig()
        if (!cfg.enabled) return null
        val key = cfg.tmdbApiKey.trim().ifBlank { BuildConfig.FLUTLICHT_TMDB_KEY }
        return if (key.isBlank()) null else Tmdb(key, cfg.resolvedLanguage)
    }

    private fun String.value(): String? = takeIf { it.isNotBlank() && it != "null" }

    /** One TMDB genre for Jev to weigh; [movie] / [tv] are TMDB's ids for it (null = that side has none). */
    private data class Genre(val name: String, val movie: Int?, val tv: Int?)

    companion object {
        private const val TAG = "MoodSearch"
        private const val TMDB_BASE = "https://api.themoviedb.org"
        private const val Q_DESCRIBES = "describes"
        private const val Q_KIND = "kind"
        private const val Q_GENRE = "genre_"
        private const val Q_KEYWORD = "keyword_"
        private const val Q_FIT = "fit_"
        private const val KIND_MOVIE = "movie"
        private const val KIND_SERIES = "series"

        const val MIN_LENGTH = 4
        private const val CACHE_SIZE = 20
        private const val TOTAL_TIMEOUT_MS = 30_000L
        private const val DESCRIBES_THRESHOLD = 0.5
        private const val PICK_THRESHOLD = 0.6
        private const val FIT_THRESHOLD = 0.5
        /** A title whose fit request failed still shows, behind every judged good fit. */
        private const val FALLBACK_FIT = 0.5
        private const val MAX_KEYWORDS = 16
        private const val KEYWORDS_PER_TERM = 6
        private const val MAX_PICKED_KEYWORDS = 5
        private const val MAX_PICKED_GENRES = 2
        private const val KEYWORD_PAGES = 3
        private const val GENRE_PAGES = 5
        private const val MAX_CANDIDATES = 200
        private const val MAX_MATCHES = 48
        private const val RERANK_CHUNK = 12
        private const val OVERVIEW_CHARS = 420
        private const val MAX_RESULTS = 24
        /** A Filme/Serien query shorter than this gets title matches only. */
        private const val MIN_SECTION_LENGTH = 3
        private const val PERSON_TIMEOUT_MS = 12_000L
        private const val PERSON_RESULTS = 5
        private const val MIN_PERSON_POPULARITY = 1.0
        private const val MAX_PERSON_CANDIDATES = 150
        private const val MAX_PERSON_MATCHES = 60
        private val PERSON_DEPARTMENTS = setOf("Acting", "Directing", "Writing", "Creator")
        private val CREW_JOBS = setOf("Director", "Creator")
        /** TMDB TV genres News, Reality, Talk. */
        private val GUEST_GENRES = setOf(10763, 10764, 10767)

        private const val MAX_TERMS = 6

        /** The frame of a spoken wish ("ich möchte einen … schauen"): no keyword lookup of their own. */
        private val SENTENCE_WORDS = ("ich möchte moechte will würde gerne gern ein einen eine einem einer den dem der die das des " +
            "mit und oder ohne schauen sehen gucken anschauen ansehen zeig zeige mir bitte irgendwas etwas was wie " +
            "gibt geben auf für von aus bei nach über heute abend wir uns want watch something with the and about some see " +
            "like").split(' ').toSet()

        /** Words that only say "a film" or "a show": "Stonermovie" → "stoner", "Zombieserie" → "zombie". */
        private val FILLER = listOf("movies", "movie", "filme", "films", "film", "serien", "series", "serie", "shows", "show", "streifen")

        private val GENRES = listOf(
            Genre("Action", 28, 10759),
            Genre("Adventure", 12, 10759),
            Genre("Animation", 16, 16),
            Genre("Comedy", 35, 35),
            Genre("Crime", 80, 80),
            Genre("Documentary", 99, 99),
            Genre("Drama", 18, 18),
            Genre("Family", 10751, 10751),
            Genre("Kids", 10751, 10762),
            Genre("Fantasy", 14, 10765),
            Genre("Science Fiction", 878, 10765),
            Genre("History", 36, null),
            Genre("Horror", 27, null),
            Genre("Music", 10402, null),
            Genre("Mystery", 9648, 9648),
            Genre("Romance", 10749, null),
            Genre("Thriller", 53, null),
            Genre("War", 10752, 10768),
            Genre("Western", 37, 37),
            Genre("Reality TV", null, 10764),
        )

        private fun enc(s: String): String = URLEncoder.encode(s, Charsets.UTF_8.name())

        private fun cacheKey(query: String) = query.trim().lowercase()

        /** The words that put a person after them: "Filme mit Tom Hanks", "movies by Nolan". */
        private val PERSON_MARKERS = setOf("mit", "von", "with", "by", "starring")

        /**
         * The person a query names: what follows its last "mit"/"von"/"with"/"by", without "Filme" or "Serien"
         * around it. A query without such a word stays whole ("Tom Hanks", "Will Smith").
         */
        internal fun personName(query: String): String {
            val words = query.trim().split(Regex("\\s+")).filter { it.isNotEmpty() }
            val marker = words.indexOfLast { it.lowercase() in PERSON_MARKERS }
            val rest = if (marker >= 0) words.drop(marker + 1) else words
            return rest.filter { it.lowercase().trim('-') !in FILLER }.joinToString(" ")
        }

        /** The query's words, and each without a filler ending, as TMDB keyword searches (at most four). */
        internal fun keywordTerms(query: String): List<String> {
            val words = query.lowercase().split(Regex("[^\\p{L}\\p{N}]+")).filter { it.length >= 3 && it !in SENTENCE_WORDS }
            val terms = LinkedHashSet<String>()
            for (w in words) {
                val stem = FILLER.firstOrNull { w.endsWith(it) && w.length - it.length >= 3 }?.let { w.removeSuffix(it).trimEnd('-') }
                if (stem != null) terms += stem
                if (w !in FILLER) terms += w
            }
            if (words.size in 2..3) terms += words.filter { it !in FILLER }.joinToString(" ")
            return terms.filter { it.length >= 3 }.take(MAX_TERMS)
        }
    }
}

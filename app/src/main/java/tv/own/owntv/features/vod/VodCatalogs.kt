@file:OptIn(ExperimentalCoroutinesApi::class)

package tv.own.owntv.features.vod

import android.content.Context
import android.util.Log
import androidx.paging.PagingSource
import androidx.paging.PagingState
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONArray
import org.json.JSONObject
import tv.own.owntv.BuildConfig
import tv.own.owntv.R
import tv.own.owntv.core.database.dao.MovieDao
import tv.own.owntv.core.database.dao.SeriesDao
import tv.own.owntv.core.live.LiveKey
import tv.own.owntv.core.metadata.MetadataType
import tv.own.owntv.core.metadata.TrendingCandidate
import tv.own.owntv.core.model.MediaType
import tv.own.owntv.core.settings.SettingsRepository
import tv.own.owntv.core.trending.TrendingMatcher
import java.io.File
import java.net.URLEncoder
import java.util.Locale
import java.util.concurrent.TimeUnit

/**
 * One list in the Movies / Series category column that is not a provider folder: "Weiterschauen",
 * "Gerade angesagt", a genre, a collection, a streaming service. [ids] are playlist titles in the
 * order shown, so a catalog only ever holds what can be played.
 */
data class VodCatalog(val id: String, val group: Group, val title: String, val ids: List<Long>) {
    enum class Group { DISCOVER, GENRES, COLLECTIONS, SERVICES }

    val key: LiveKey get() = VodCatalogKeys.key(id)
}

/**
 * Catalogs ride the column as [LiveKey.Custom] with their own prefix: [LiveKey] lives in core and is
 * sealed. Nothing that hides, renames or reorders custom categories ever sees these keys, because
 * the screens leave them out of every organise menu.
 */
object VodCatalogKeys {
    private const val PREFIX = "mayo-catalog:"

    fun key(id: String): LiveKey = LiveKey.Custom(PREFIX + id)

    fun idOf(key: LiveKey): String? = (key as? LiveKey.Custom)?.id?.takeIf { it.startsWith(PREFIX) }?.removePrefix(PREFIX)

    fun isCatalog(key: LiveKey?): Boolean = key != null && idOf(key) != null

    /** Which heading a catalog sits under in the column, read from its id. */
    fun groupOf(key: LiveKey): VodCatalog.Group? {
        val id = idOf(key) ?: return null
        return when {
            id.startsWith(GENRE) -> VodCatalog.Group.GENRES
            id.startsWith(COLLECTION) -> VodCatalog.Group.COLLECTIONS
            id.startsWith(SERVICE) -> VodCatalog.Group.SERVICES
            else -> VodCatalog.Group.DISCOVER
        }
    }

    internal const val GENRE = "genre_"
    internal const val COLLECTION = "collection_"
    internal const val SERVICE = "service_"
}

/**
 * The titles of one catalog, in the catalog's order, as a single page. Catalogs are short (at most
 * [VodCatalogSource.MAX_PER_CATALOG]), so there is nothing to page; a title that left the playlist
 * since the catalog was built is simply missing. [query] filters by name like the folders' search.
 */
internal class CatalogPagingSource<T : Any>(
    private val ids: List<Long>,
    private val query: String,
    private val fetch: suspend (List<Long>) -> List<T>,
    private val idOf: (T) -> Long,
    private val nameOf: (T) -> String,
) : PagingSource<Int, T>() {
    override fun getRefreshKey(state: PagingState<Int, T>): Int? = null

    override suspend fun load(params: LoadParams<Int>): LoadResult<Int, T> {
        if (params.key != null || ids.isEmpty()) return LoadResult.Page(emptyList(), null, null)
        val rows = runCatching { fetch(ids) }.getOrElse { return LoadResult.Error(it) }.associateBy(idOf)
        val q = query.trim()
        val items = ids.distinct().mapNotNull { rows[it] }.filter { q.isEmpty() || nameOf(it).contains(q, ignoreCase = true) }
        return LoadResult.Page(items, prevKey = null, nextKey = null)
    }
}

/**
 * A Filme/Serien search with extra finds: the title matches ([titles], up to [MAX_TITLES]) and then
 * the titles of [extraIds] that are not among them (an actor's films, a described search's picks),
 * as a single page. Without extras the title source is used as it is, paged.
 */
internal class SearchPagingSource<T : Any>(
    private val titles: PagingSource<Int, T>,
    private val extraIds: List<Long>,
    private val fetch: suspend (List<Long>) -> List<T>,
    private val idOf: (T) -> Long,
) : PagingSource<Int, T>() {
    override fun getRefreshKey(state: PagingState<Int, T>): Int? = null

    override suspend fun load(params: LoadParams<Int>): LoadResult<Int, T> {
        if (params.key != null) return LoadResult.Page(emptyList(), null, null)
        val byTitle = when (val r = titles.load(LoadParams.Refresh(null, MAX_TITLES, false))) {
            is LoadResult.Page -> r.data
            is LoadResult.Error -> return LoadResult.Error(r.throwable)
            is LoadResult.Invalid -> emptyList()
        }
        val seen = byTitle.mapTo(HashSet()) { idOf(it) }
        val wanted = extraIds.distinct().filter { it !in seen }
        val rows = runCatching { fetch(wanted) }.getOrElse { return LoadResult.Error(it) }.associateBy(idOf)
        return LoadResult.Page(byTitle + wanted.mapNotNull { rows[it] }, prevKey = null, nextKey = null)
    }

    private companion object {
        const val MAX_TITLES = 300
    }
}

/**
 * The catalogs a section shows, newest data first: "Weiterschauen", "Gerade angesagt", "Neu in deiner
 * Playlist", "Für dich", "Bestbewertet", then the streaming services, genres and collections.
 * Empty catalogs drop out; a TMDB catalog needs [MIN_TMDB] titles to stay.
 */
internal fun vodCatalogs(
    scope: CoroutineScope,
    context: Context,
    type: MediaType,
    ctx: StateFlow<VodCtx>,
    continueIds: (VodCtx) -> Flow<List<Long>>,
    newestIds: suspend (VodCtx) -> List<Long>,
    historyIds: suspend (VodCtx) -> List<Long>,
    source: VodCatalogSource,
): StateFlow<List<VodCatalog>> = ctx
    .flatMapLatest { c ->
        if (c.profileId < 0 || c.sourceIds.isEmpty()) return@flatMapLatest flowOf(emptyList())
        val built = flow {
            // One first emission with everything already on the box, so the column does not grow
            // under the cursor; a renewed TMDB build follows when the stored one was too old.
            val newest = runCatching { newestIds(c) }.getOrDefault(emptyList())
            val watched = runCatching { historyIds(c) }.getOrDefault(emptyList())
            val stored = source.stored(type, c.sourceIds)
            emit(BuiltCatalogs(newest = newest, tmdb = stored, forYou = forYou(stored, watched)))
            val tmdb = source.catalogs(type, c.sourceIds)
            if (tmdb != stored) emit(BuiltCatalogs(newest = newest, tmdb = tmdb, forYou = forYou(tmdb, watched)))
        }
        combine(continueIds(c), built) { cont, b ->
            val discover = VodCatalog.Group.DISCOVER
            buildList {
                if (cont.isNotEmpty()) add(VodCatalog(VodCatalogSource.ID_CONTINUE, discover, context.getString(R.string.vod_catalog_continue), cont.take(VodCatalogSource.MAX_PER_CATALOG)))
                b.tmdb.firstOrNull { it.id == VodCatalogSource.ID_TRENDING }?.let(::add)
                if (b.newest.size >= MIN_TMDB) add(VodCatalog("newest", discover, context.getString(R.string.vod_catalog_newest), b.newest))
                if (b.forYou.size >= MIN_TMDB) add(VodCatalog("for_you", discover, context.getString(R.string.vod_catalog_for_you), b.forYou))
                addAll(b.tmdb.filter { it.id != VodCatalogSource.ID_TRENDING })
            }
                // Entdecken first, then "Auch im Abo bei": the provider's own categories are poor, the
                // streaming services are what people look for next (Jonathan, 19 Nov 2026).
                .sortedBy { GROUP_ORDER.indexOf(it.group) }
        }
    }
    .flowOn(Dispatchers.Default)
    .stateIn(scope, SharingStarted.Eagerly, emptyList())

private data class BuiltCatalogs(
    val newest: List<Long> = emptyList(),
    val tmdb: List<VodCatalog> = emptyList(),
    val forYou: List<Long> = emptyList(),
)

private const val MIN_TMDB = 2

private val GROUP_ORDER = listOf(
    VodCatalog.Group.DISCOVER, VodCatalog.Group.SERVICES, VodCatalog.Group.GENRES, VodCatalog.Group.COLLECTIONS,
)

/**
 * "Für dich": the genres of what this profile watched last, weighted by how often they come up, and
 * the popular titles of those genres it has not watched yet, taken in turns. Worked out on the box from
 * the genre catalogs; nothing leaves the device for it.
 */
private fun forYou(catalogs: List<VodCatalog>, watched: List<Long>): List<Long> {
    if (watched.isEmpty()) return emptyList()
    val genres = catalogs.filter { it.group == VodCatalog.Group.GENRES }
    val seen = watched.toHashSet()
    val liked = genres
        .map { g -> g to watched.withIndex().sumOf { (i, id) -> if (id in g.ids) 1.0 / (1 + i / 10) else 0.0 } }
        .filter { it.second > 0 }
        .sortedByDescending { it.second }
        .take(3)
        .map { (g, _) -> g.ids.filter { it !in seen } }
    if (liked.isEmpty()) return emptyList()
    val out = LinkedHashSet<Long>()
    var i = 0
    while (out.size < VodCatalogSource.MAX_PER_CATALOG && liked.any { i < it.size }) {
        liked.forEach { list -> list.getOrNull(i)?.let(out::add) }
        i++
    }
    return out.toList()
}

/**
 * Builds the TMDB catalogs once a day per section and playlist set: TMDB lists popular titles
 * (trending, top rated, per genre, per streaming service, per collection), and the playlist's own
 * copies are found with the Trending matcher, so only playable titles end up in a catalog. Kept in a
 * file, so opening Movies never waits for TMDB.
 */
class VodCatalogSource(
    private val context: Context,
    okHttp: OkHttpClient,
    private val settings: SettingsRepository,
    private val movieDao: MovieDao,
    private val seriesDao: SeriesDao,
) {
    private val http = okHttp.newBuilder().callTimeout(20, TimeUnit.SECONDS).build()
    private val lock = Mutex()
    private val memory = java.util.concurrent.ConcurrentHashMap<String, Stored>()

    private data class Stored(val builtAt: Long, val language: String, val catalogs: List<VodCatalog>)

    /**
     * What was built last for this section, however old: shown at once while [catalogs] renews it.
     * Not behind [lock]: Serien opened while Filme builds would otherwise show no catalogs until then.
     */
    suspend fun stored(type: MediaType, sourceIds: List<Long>): List<VodCatalog> {
        val cacheKey = cacheKey(type, sourceIds)
        return (memory[cacheKey] ?: withContext(Dispatchers.IO) { read(cacheKey) })?.catalogs.orEmpty()
    }

    private fun cacheKey(type: MediaType, sourceIds: List<Long>) =
        (if (type == MediaType.MOVIE) MOVIE else TV) + "_" + sourceIds.sorted().joinToString("-")

    /** The section's TMDB catalogs: from memory or the file while fresh, else built now (empty without TMDB). */
    suspend fun catalogs(type: MediaType, sourceIds: List<Long>): List<VodCatalog> = lock.withLock {
        val cfg = settings.metadataConfig()
        val key = cfg.tmdbApiKey.trim().ifBlank { BuildConfig.FLUTLICHT_TMDB_KEY }
        if (!cfg.enabled || key.isBlank()) return@withLock emptyList()
        val language = cfg.resolvedLanguage
        val cacheKey = cacheKey(type, sourceIds)
        val now = System.currentTimeMillis()
        val known = memory[cacheKey] ?: withContext(Dispatchers.IO) { read(cacheKey) }
        if (known != null && known.language == language && now - known.builtAt < MAX_AGE_MS && stillValid(type, known.catalogs)) {
            memory[cacheKey] = known
            return@withLock known.catalogs
        }
        val built = runCatching { build(type, sourceIds, Tmdb(key, language)) }
            .onFailure { Log.w(TAG, "catalogs failed: ${it.message}") }
            .getOrNull()
        // Nothing came back (offline): keep the old catalogs rather than showing none.
        if (built.isNullOrEmpty()) return@withLock known?.catalogs.orEmpty()
        val stored = Stored(now, language, built)
        memory[cacheKey] = stored
        withContext(Dispatchers.IO) { write(cacheKey, stored) }
        built
    }

    /** A playlist re-sync gives titles new ids: catalogs whose titles mostly vanished are rebuilt. */
    private suspend fun stillValid(type: MediaType, catalogs: List<VodCatalog>): Boolean {
        val sample = catalogs.flatMap { it.ids }.distinct().take(VALIDITY_SAMPLE)
        if (sample.isEmpty()) return false
        val found = withContext(Dispatchers.IO) {
            if (type == MediaType.MOVIE) movieDao.getByIds(sample).size else seriesDao.getSeriesByIds(sample).size
        }
        return found * 2 >= sample.size
    }

    private suspend fun build(type: MediaType, sourceIds: List<Long>, tmdb: Tmdb): List<VodCatalog> = coroutineScope {
        val movie = type == MediaType.MOVIE
        val path = if (movie) MOVIE else TV
        val gate = Semaphore(PARALLEL)
        suspend fun pages(endpoint: String, params: String, count: Int): List<TrendingCandidate> =
            (1..count).map { p -> async { gate.withPermit { tmdb.titles(endpoint, params, p, movie) } } }.awaitAll().flatten()

        val trending = async { pages("trending/$path/week", "", TRENDING_PAGES) }
        val top = async { pages("$path/top_rated", "", TOP_PAGES) }
        val genres = gate.withPermit { tmdb.genres(path) }
        val byGenre = genres.map { (id, name) ->
            Triple(id, name, async { pages("discover/$path", "&with_genres=$id&sort_by=popularity.desc&vote_count.gte=$MIN_VOTES", GENRE_PAGES) })
        }
        val region = tmdb.region()
        val services = gate.withPermit { tmdb.services(path, region) }.take(MAX_SERVICES)
        val byService = services.map { (id, name) ->
            Triple(id, name, async {
                pages(
                    "discover/$path",
                    "&with_watch_providers=$id&watch_region=$region&with_watch_monetization_types=flatrate&sort_by=popularity.desc",
                    SERVICE_PAGES,
                )
            })
        }
        val collections = if (!movie) emptyList() else COLLECTION_IDS.map { id -> async { gate.withPermit { tmdb.collection(id) }?.let { id to it } } }

        val trendingList = trending.await()
        val topList = top.await()
        val genreLists = byGenre.map { (id, name, d) -> Triple(id, name, d.await()) }
        val serviceLists = byService.map { (id, name, d) -> Triple(id, name, d.await()) }
        val collectionLists = collections.awaitAll().filterNotNull()

        // Every candidate once, so the playlist is searched once per title however many catalogs share it.
        val all = (trendingList + topList + genreLists.flatMap { it.third } + serviceLists.flatMap { it.third } + collectionLists.flatMap { it.second.second })
            .distinctBy { it.tmdbId }
        val inPlaylist = match(all, type, sourceIds)
        fun ids(list: List<TrendingCandidate>) = list.mapNotNull { inPlaylist[it.tmdbId] }.distinct().take(MAX_PER_CATALOG)

        val discover = VodCatalog.Group.DISCOVER
        buildList {
            add(VodCatalog(ID_TRENDING, discover, context.getString(R.string.vod_catalog_trending), ids(trendingList)))
            add(VodCatalog("top_rated", discover, context.getString(R.string.vod_catalog_top_rated), ids(topList)))
            genreLists.forEach { (id, name, list) -> add(VodCatalog(VodCatalogKeys.GENRE + id, VodCatalog.Group.GENRES, name, ids(list))) }
            collectionLists.forEach { (id, c) -> add(VodCatalog(VodCatalogKeys.COLLECTION + id, VodCatalog.Group.COLLECTIONS, c.first, ids(c.second))) }
            serviceLists.forEach { (id, name, list) -> add(VodCatalog(VodCatalogKeys.SERVICE + id, VodCatalog.Group.SERVICES, name, ids(list))) }
        }.filter { it.ids.size >= MIN_TMDB }
    }

    /** tmdbId → the playlist title that plays it, over every playlist of the section. */
    private suspend fun match(candidates: List<TrendingCandidate>, type: MediaType, sourceIds: List<Long>): Map<Int, Long> {
        val language = settings.metadataConfig().resolvedLanguage
        val ranked = candidates.mapIndexed { i, c -> c.copy(trendingRank = i + 1) }
        val out = HashMap<Int, Long>()
        withContext(Dispatchers.IO) {
            for (sid in sourceIds) {
                // In chunks: one exact lookup takes every signature of the chunk as SQL parameters.
                for (chunk in ranked.filter { it.tmdbId !in out }.chunked(MATCH_CHUNK)) {
                    val result = runCatching {
                        TrendingMatcher.matchMedia(
                            candidates = chunk,
                            mediaType = type,
                            preferredLanguage = language,
                            limit = chunk.size,
                            exactLookup = { sigs -> if (type == MediaType.MOVIE) movieDao.trendingExact(sid, sigs) else seriesDao.trendingExact(sid, sigs) },
                            ftsLookup = { q, n -> if (type == MediaType.MOVIE) movieDao.trendingFts(sid, q, n) else seriesDao.trendingFts(sid, q, n) },
                        )
                    }.getOrNull() ?: continue
                    result.selections.forEach { s -> out.putIfAbsent(s.candidate.tmdbId, s.variant.item.id) }
                }
            }
        }
        return out
    }

    // --- cache file ---

    private fun file(cacheKey: String) = File(File(context.filesDir, DIR).apply { mkdirs() }, "$cacheKey.json")

    private fun read(cacheKey: String): Stored? = runCatching {
        val f = file(cacheKey)
        if (!f.exists()) return null
        val o = JSONObject(f.readText())
        val list = o.getJSONArray(F_CATALOGS)
        Stored(
            builtAt = o.getLong(F_BUILT),
            language = o.getString(F_LANGUAGE),
            catalogs = (0 until list.length()).map { i ->
                val c = list.getJSONObject(i)
                val ids = c.getJSONArray(F_IDS)
                VodCatalog(
                    id = c.getString(F_ID),
                    group = VodCatalog.Group.valueOf(c.getString(F_GROUP)),
                    title = c.getString(F_TITLE),
                    ids = (0 until ids.length()).map { ids.getLong(it) },
                )
            },
        )
    }.getOrNull()

    private fun write(cacheKey: String, stored: Stored) {
        runCatching {
            val list = JSONArray()
            stored.catalogs.forEach { c ->
                list.put(
                    JSONObject().put(F_ID, c.id).put(F_GROUP, c.group.name).put(F_TITLE, c.title).put(F_IDS, JSONArray(c.ids)),
                )
            }
            // Written aside and renamed, so [stored] never reads half a file.
            val target = file(cacheKey)
            val tmp = File(target.parentFile, target.name + ".tmp")
            tmp.writeText(
                JSONObject().put(F_BUILT, stored.builtAt).put(F_LANGUAGE, stored.language).put(F_CATALOGS, list).toString(),
            )
            if (!tmp.renameTo(target)) { target.delete(); tmp.renameTo(target) }
        }.onFailure { Log.w(TAG, "cache write failed: ${it.message}") }
    }

    // --- TMDB ---

    private inner class Tmdb(val key: String, val language: String) {
        fun region(): String = language.substringAfter('-', "").takeIf { it.length == 2 }?.uppercase(Locale.ROOT)
            ?: Locale.getDefault().country.takeIf { it.length == 2 }
            ?: DEFAULT_REGION

        private fun url(endpoint: String, params: String) =
            "$TMDB_BASE/3/$endpoint?api_key=${enc(key)}" + (if (language.isBlank()) "" else "&language=${enc(language)}") + params

        suspend fun titles(endpoint: String, params: String, page: Int, movie: Boolean): List<TrendingCandidate> {
            val body = get(url(endpoint, "$params&page=$page")) ?: return emptyList()
            val results = runCatching { JSONObject(body).optJSONArray(F_RESULTS) }.getOrNull() ?: return emptyList()
            return (0 until results.length()).mapNotNull { i -> results.optJSONObject(i)?.let { candidate(it, movie, (page - 1) * 20 + i + 1) } }
        }

        /** The section's genres in the metadata language: (TMDB id, name). */
        suspend fun genres(path: String): List<Pair<Int, String>> {
            val body = get(url("genre/$path/list", "")) ?: return emptyList()
            val list = runCatching { JSONObject(body).optJSONArray(F_GENRES) }.getOrNull() ?: return emptyList()
            return (0 until list.length()).mapNotNull { i ->
                val o = list.optJSONObject(i) ?: return@mapNotNull null
                val id = o.optInt(F_ID, 0)
                val name = o.optString(F_NAME).value()
                if (id > 0 && name != null) id to name else null
            }
        }

        /** The biggest streaming services in [region], TMDB's own order for that country. */
        suspend fun services(path: String, region: String): List<Pair<Int, String>> {
            val body = get(url("watch/providers/$path", "&watch_region=$region")) ?: return emptyList()
            val list = runCatching { JSONObject(body).optJSONArray(F_RESULTS) }.getOrNull() ?: return emptyList()
            return (0 until list.length()).mapNotNull { i ->
                val o = list.optJSONObject(i) ?: return@mapNotNull null
                val id = o.optInt(F_PROVIDER_ID, 0)
                val name = o.optString(F_PROVIDER_NAME).value() ?: return@mapNotNull null
                val priority = o.optJSONObject(F_PRIORITIES)?.optInt(region, Int.MAX_VALUE) ?: Int.MAX_VALUE
                if (id > 0 && id !in RENTAL_PROVIDERS && RENTAL_NAME_HINTS.none { name.contains(it, ignoreCase = true) }) {
                    Triple(id, name, priority)
                } else {
                    null
                }
            }.sortedBy { it.third }.map { it.first to it.second }
        }

        /** A film series ("John Wick", "Dune") with its parts in release order. */
        suspend fun collection(id: Int): Pair<String, List<TrendingCandidate>>? {
            val body = get(url("collection/$id", "")) ?: return null
            val o = runCatching { JSONObject(body) }.getOrNull() ?: return null
            val name = o.optString(F_NAME).value() ?: return null
            val parts = o.optJSONArray(F_PARTS) ?: return null
            val list = (0 until parts.length()).mapNotNull { i -> parts.optJSONObject(i)?.let { candidate(it, true, i + 1) } }
                .sortedBy { it.year ?: Int.MAX_VALUE }
            return name to list
        }

        private fun candidate(o: JSONObject, movie: Boolean, rank: Int): TrendingCandidate? {
            val id = o.optInt(F_ID, 0).takeIf { it > 0 } ?: return null
            val title = o.optString(if (movie) F_TITLE else F_NAME).value()
                ?: o.optString(if (movie) F_ORIGINAL_TITLE else F_ORIGINAL_NAME).value()
                ?: return null
            return TrendingCandidate(
                tmdbId = id,
                type = if (movie) MetadataType.MOVIE else MetadataType.TV,
                localizedTitle = title,
                originalTitle = o.optString(if (movie) F_ORIGINAL_TITLE else F_ORIGINAL_NAME).value(),
                year = o.optString(if (movie) F_RELEASE_DATE else F_FIRST_AIR_DATE).take(4).toIntOrNull(),
                overview = o.optString(F_OVERVIEW).value(),
                posterPath = o.optString(F_POSTER).value(),
                backdropPath = o.optString(F_BACKDROP).value(),
                rating = o.optDouble(F_VOTE_AVERAGE, 0.0).takeIf { it > 0.0 },
                popularity = o.optDouble(F_POPULARITY, 0.0),
                trendingRank = rank,
            )
        }

        private suspend fun get(url: String): String? = withContext(Dispatchers.IO) {
            runCatching {
                http.newCall(Request.Builder().url(url).build()).execute().use { r -> if (r.isSuccessful) r.body.string() else null }
            }.onFailure { Log.w(TAG, "TMDB failed: ${it.message}") }.getOrNull()
        }
    }

    private fun String.value(): String? = takeIf { it.isNotBlank() && it != "null" }

    companion object {
        private const val TAG = "VodCatalogs"
        private const val TMDB_BASE = "https://api.themoviedb.org"
        private const val DEFAULT_REGION = "DE"
        private const val DIR = "vod_catalogs"
        private const val MOVIE = "movie"
        private const val TV = "tv"
        const val ID_TRENDING = "trending"
        const val ID_CONTINUE = "continue"
        const val MAX_PER_CATALOG = 120
        private const val MAX_AGE_MS = 24L * 60 * 60 * 1000
        private const val VALIDITY_SAMPLE = 60
        private const val PARALLEL = 6
        private const val TRENDING_PAGES = 5
        private const val TOP_PAGES = 5
        private const val GENRE_PAGES = 3
        private const val SERVICE_PAGES = 3
        private const val MIN_VOTES = 50
        private const val MAX_SERVICES = 8
        private const val MATCH_CHUNK = 150

        /** Rent/buy shops and ad-tier duplicates: not a subscription, so not "Auch im Abo bei". */
        private val RENTAL_PROVIDERS = setOf(2, 3, 10, 35, 40, 68, 130, 178, 192, 1796)
        private val RENTAL_NAME_HINTS = listOf("Store", "with Ads", "Channel")

        private const val F_RESULTS = "results"
        private const val F_GENRES = "genres"
        private const val F_PARTS = "parts"
        private const val F_ID = "id"
        private const val F_NAME = "name"
        private const val F_TITLE = "title"
        private const val F_ORIGINAL_TITLE = "original_title"
        private const val F_ORIGINAL_NAME = "original_name"
        private const val F_RELEASE_DATE = "release_date"
        private const val F_FIRST_AIR_DATE = "first_air_date"
        private const val F_OVERVIEW = "overview"
        private const val F_POSTER = "poster_path"
        private const val F_BACKDROP = "backdrop_path"
        private const val F_VOTE_AVERAGE = "vote_average"
        private const val F_POPULARITY = "popularity"
        private const val F_PROVIDER_ID = "provider_id"
        private const val F_PROVIDER_NAME = "provider_name"
        private const val F_PRIORITIES = "display_priorities"
        private const val F_BUILT = "builtAt"
        private const val F_LANGUAGE = "language"
        private const val F_CATALOGS = "catalogs"
        private const val F_GROUP = "group"
        private const val F_IDS = "ids"

        /**
         * Film series on TMDB that IPTV playlists tend to carry. TMDB has no list of all collections,
         * so they are named here; a collection only shows when the playlist has two of its parts.
         */
        private val COLLECTION_IDS = listOf(
            404609, 87359, 726871, 1241, 119, 121938, 10, 645, 9485, 86311, 10194, 328, 295, 2344, 263,
            84, 528, 8091, 264, 131635, 1570, 86066, 2150, 8354, 448150, 284433, 1575, 553717, 77816, 87096,
            386382, 14740, 8650, 8945, 173710, 531241, 556, 748, 391860, 304, 86119, 14890, 523855, 313086,
            656, 2602, 33514, 535313, 945, 31562,
        )

        private fun enc(s: String): String = URLEncoder.encode(s, Charsets.UTF_8.name())
    }
}

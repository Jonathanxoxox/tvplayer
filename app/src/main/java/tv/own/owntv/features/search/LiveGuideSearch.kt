package tv.own.owntv.features.search

import android.content.Context
import android.util.Log
import okhttp3.OkHttpClient
import tv.own.owntv.R
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import org.json.JSONArray
import org.json.JSONObject
import tv.own.owntv.core.content.ProgrammeSearchResult
import tv.own.owntv.core.content.SearchReader
import tv.own.owntv.core.content.VisibleChannel
import tv.own.owntv.core.database.entity.EpgProgrammeEntity
import tv.own.owntv.core.repository.ActiveProfileSources
import tv.own.owntv.features.home.MagentaEpgRules
import tv.own.owntv.features.home.MagentaGuide
import tv.own.owntv.features.home.MagentaNames
import tv.own.owntv.features.keys.ApiKeys
import tv.own.owntv.features.search.JevClient.Companion.noul

/**
 * Search's "Im TV" over MagentaTV's whole guide, not only the channels whose guide the box stores
 * (Jonathan, 20 Nov 2026: "Die Suche findet Live-Sendungen nicht zuverlässig").
 *
 * The guide's own search is closed to guests, so its next [HORIZON_MS] are read in one sweep
 * ([MagentaGuide.sweep], some 1 MB) the first time Search asks, kept for [TTL_MS] and looked through
 * on the box. Nothing runs in the background and nothing is written to the database.
 *
 * - [byTitle]: programmes whose title holds the query.
 * - [byText]: programmes whose genres or description hold every word of it ("Fußball", "Champions League").
 * - [byWish]: a described wish ("Tierdoku", "Blockbuster"): Jev picks the guide's genres that fit, code
 *   gathers those programmes, Jev judges the gathered ones against the wish; the best fits first.
 */
class LiveGuideSearch(
    private val context: Context,
    okHttp: OkHttpClient,
    private val magenta: MagentaGuide,
    private val searchReader: SearchReader,
    keys: ApiKeys,
) {
    private val jev = JevClient(okHttp) { keys.typeSafe.value }
    private val prompts: JSONObject by lazy {
        JSONObject(context.resources.openRawResource(R.raw.jev_mood_search).bufferedReader().use { it.readText() })
    }

    /** One programme of the sweep with its folded text, ready to be matched. */
    private class Item(val row: EpgProgrammeEntity, val key: String, val title: String, val text: String, val genres: List<String>)

    private val lock = Mutex()
    @Volatile private var snapshot: Pair<Long, List<Item>>? = null

    /** The sweep, fetched when older than [TTL_MS]; the last one when the guide does not answer. */
    private suspend fun items(): List<Item> = lock.withLock {
        val now = System.currentTimeMillis()
        snapshot?.let { (at, list) -> if (now - at < TTL_MS) return@withLock list }
        val sweep = runCatching { magenta.sweep(now - BACK_MS, now + HORIZON_MS) }.getOrNull()
            ?: return@withLock snapshot?.second.orEmpty()
        val items = sweep.groupBy({ it.first }, { it.second }).flatMap { (key, list) ->
            val programmes = MagentaNames.programmes(JSONArray(list))
            MagentaEpgRules.rows(key, programmes).zip(programmes) { row, p ->
                Item(
                    row = row,
                    key = key,
                    title = MagentaNames.fold(p.title),
                    text = MagentaNames.fold(p.genres.joinToString(" ") + " " + p.description.orEmpty()),
                    genres = p.genres,
                )
            }
        }
        Log.i(TAG, "sweep: ${items.size} programmes on ${items.distinctBy { it.key }.size} channels")
        snapshot = now to items
        items
    }

    /** Reads the sweep ahead when it is missing or old; for the search page's opening. */
    suspend fun warm() { items() }

    /** Guide key → the channel Search shows for it: the first visible one of that name, plain/HD before SD/UHD. */
    private suspend fun channels(profileId: Long, sources: ActiveProfileSources): Map<String, VisibleChannel> =
        searchReader.visibleLiveChannels(profileId, sources)
            .groupBy { MagentaNames.key(it.channel.name) }
            .filterKeys { it.isNotEmpty() }
            .mapValues { (_, list) -> list.minBy { MagentaNames.qualityRank(it.channel.name) } }

    private suspend fun found(profileId: Long, sources: ActiveProfileSources, match: (Item) -> Boolean): List<ProgrammeSearchResult> {
        val now = System.currentTimeMillis()
        val byKey = channels(profileId, sources)
        if (byKey.isEmpty()) return emptyList()
        return items().asSequence()
            .filter { it.row.stopMs > now && it.row.startMs < now + HORIZON_MS && match(it) }
            .mapNotNull { item -> byKey[item.key]?.let { ProgrammeSearchResult(item.row, it.shown) } }
            .sortedWith(compareBy({ it.programme.startMs > now }, { it.programme.startMs }))
            .take(LIMIT)
            .toList()
    }

    suspend fun byTitle(query: String, profileId: Long, sources: ActiveProfileSources): List<ProgrammeSearchResult> {
        val q = MagentaNames.fold(query)
        if (q.length < 2) return emptyList()
        return found(profileId, sources) { q in it.title }
    }

    suspend fun byText(query: String, profileId: Long, sources: ActiveProfileSources): List<ProgrammeSearchResult> {
        val words = MagentaNames.fold(query).split(' ').filter { it.length >= MIN_WORD }
        if (words.isEmpty()) return emptyList()
        return found(profileId, sources) { item -> words.all { it in item.text } }
    }

    /** A described wish's programmes, best fit first; null when Jev could not be asked or it is no description. */
    suspend fun byWish(wish: String, profileId: Long, sources: ActiveProfileSources): List<ProgrammeSearchResult>? {
        if (!jev.available) return null
        val p = prompts
        val all = items()
        val genres = all.flatMap { it.genres }.groupingBy { it }.eachCount().entries
            .sortedByDescending { it.value }.map { it.key }.take(MAX_GENRES)
        if (genres.isEmpty()) return null
        // 1. Is it a description, and which of the guide's genres fit it?
        val questions = JSONObject().put(Q_DESCRIBES, p.getJSONObject("tv_describes"))
        genres.forEachIndexed { i, _ -> questions.put("$Q_GENRE$i", template(p, "tv_genre", "{index}", i.toString())) }
        val first = jev.ask(JSONObject().put("wish", wish).put("genres", JSONArray(genres)), questions) ?: return null
        if (first.noul(Q_DESCRIBES) < DESCRIBES_THRESHOLD) return null
        val picked = genres.indices.filter { first.noul("$Q_GENRE$it") >= PICK_THRESHOLD }.map { genres[it] }.toSet()
        if (picked.isEmpty()) return emptyList()
        // 2. Their programmes, on now and soonest first, one per title.
        val candidates = found(profileId, sources) { item -> item.genres.any { it in picked } }
            .distinctBy { MagentaNames.fold(it.programme.title) }
            .take(MAX_JUDGED)
        if (candidates.isEmpty()) return emptyList()
        // 3. Jev judges each against the wish.
        val list = JSONArray()
        val fit = JSONObject()
        candidates.forEachIndexed { i, r ->
            list.put(
                JSONObject()
                    .put("title", r.programme.title)
                    .put("genres", r.programme.categories.orEmpty())
                    .put("description", r.programme.description.orEmpty().take(DESCRIPTION_CHARS)),
            )
            fit.put("$Q_FIT$i", template(p, "tv_fit", "{index}", i.toString()))
        }
        val answers = jev.ask(JSONObject().put("wish", wish).put("programmes", list), fit) ?: return candidates
        return candidates.withIndex()
            .map { (i, r) -> r to answers.noul("$Q_FIT$i") }
            .filter { it.second >= FIT_THRESHOLD }
            .sortedByDescending { it.second }
            .map { it.first }
    }

    private fun template(p: JSONObject, id: String, placeholder: String, value: String): JSONObject =
        JSONObject(p.getJSONObject(id).toString().replace(placeholder, value))

    companion object {
        private const val TAG = "LiveGuideSearch"
        const val HORIZON_MS = 12 * 60 * 60_000L
        /** Programmes still running started up to this long ago. */
        private const val BACK_MS = 3 * 60 * 60_000L
        private const val TTL_MS = 15 * 60_000L
        private const val LIMIT = 40
        private const val MIN_WORD = 3
        private const val MAX_GENRES = 60
        private const val MAX_JUDGED = 30
        private const val DESCRIPTION_CHARS = 300
        private const val DESCRIBES_THRESHOLD = 0.5
        private const val PICK_THRESHOLD = 0.5
        private const val FIT_THRESHOLD = 0.5
        private const val Q_DESCRIBES = "describes"
        private const val Q_GENRE = "genre_"
        private const val Q_FIT = "fit_"
    }
}

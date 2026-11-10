package tv.own.owntv.features.home

import android.content.Context
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import tv.own.owntv.core.database.entity.ChannelEntity
import tv.own.owntv.launcher.apps.LauncherSystem
import java.io.File

/**
 * Today's picks from MagentaTV's guide for the Spotlight, kept for the whole app instead of for one Home screen
 * (Jonathan, 7 Nov 2026: after the box starts, the Spotlight takes long to be current).
 *
 * Since 10 Nov 2026 this class asks nobody: the programme lists [MagentaEpg] downloads for the stored guide are
 * handed over through [MagentaEpg.onPlaybills] ([offer]) and read for picks here, so the box downloads each
 * list once instead of twice (the picks used to ask the guide again every half hour).
 *
 * - **Per guide key**, not per database channel id: ids change when the playlist is synced again. [resolve] puts
 *   Home's channel for the key back in, and only for keys [MagentaEpg] still covers (a channel whose MagentaTV
 *   guide was rejected as disagreeing with the provider shows no MagentaTV picks either).
 * - **Kept on the device** ([FILE]): a start shows the last picks still to come at once, before anything is
 *   fetched. Programmes do not move, so a list from this afternoon is right in the evening; one from yesterday
 *   has nothing left to show and simply shows nothing. The documentary/film flags each key was read with are
 *   kept beside the picks, so a round after a cold start, before Home has sent its request, reads with the last
 *   known flags.
 * - **Flags that changed** (arte became a documentary channel, a film channel was added): the keys concerned are
 *   fetched again ([MagentaEpg.refetch]), as are covered keys nothing is kept for (first start after an update).
 *
 * [state] carries what Home needs to show the Spotlight without it jumping: whether the kept picks are read and
 * Home has sent its request ([State.ready]), and [State.wakeServed], which now simply follows
 * [LauncherSystem.wake] (there is no refresh of its own to wait for on waking any more).
 */
class SpotlightPicks(
    private val magentaEpg: MagentaEpg,
    context: Context,
) {
    /** What Home asks for: the channels, arte among them for documentaries, the film channels for films. */
    data class Request(val channels: List<ChannelEntity>, val docsOn: Set<Long>, val filmsOn: Set<Long>)

    data class State(
        val picks: List<GuidePick> = emptyList(),
        /** The kept picks are read and Home has said which channels it shows: [picks] is what there is for now. */
        val ready: Boolean = false,
        /** The last [LauncherSystem.wake]; nothing is refreshed on waking, so it is always served. */
        val wakeServed: Long = 0L,
    )

    private data class Inner(val read: Boolean = false, val kept: SpotlightPicksStore.Kept? = null)

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val file = File(context.applicationContext.filesDir, FILE)
    private val requests = MutableStateFlow<Request?>(null)
    private val inner = MutableStateFlow(Inner())
    private val saveLock = Mutex()

    val state: StateFlow<State> = combine(requests, inner, magentaEpg.coveredKeys, LauncherSystem.wake) { r, i, covered, wake ->
        State(
            picks = SpotlightPicksStore.resolve(r, i.kept, covered, System.currentTimeMillis()),
            ready = r != null && i.read,
            wakeServed = wake,
        )
    }.stateIn(scope, SharingStarted.Eagerly, State(wakeServed = LauncherSystem.wake.value))

    init {
        magentaEpg.onPlaybills = ::offer
        scope.launch {
            val kept = withContext(Dispatchers.IO) { runCatching { file.takeIf { it.exists() }?.readText() }.getOrNull() }
                ?.let(SpotlightPicksStore::decode)
            if (kept != null) tv.own.owntv.perf.StartLog.stampOnce("spotlight-kept")
            // Lists offered before the file was read win: they are newer.
            inner.update { it.copy(read = true, kept = it.kept ?: kept) }
            requests.filterNotNull().collect { r -> onRequest(r) }
        }
    }

    /** Home's channels for the picks. The same request again changes nothing. */
    fun want(request: Request) {
        requests.value = request
    }

    /** Stores [r]'s flags and has [MagentaEpg] fetch again the covered keys they changed or nothing is kept for. */
    private fun onRequest(r: Request) {
        if (r.channels.isEmpty()) return
        val covered = magentaEpg.coveredKeys.value
        val before = inner.value.kept
        val again = SpotlightPicksStore.refetchKeys(before, r, covered)
        val flags = SpotlightPicksStore.flagsOf(r)
        if (before == null || SpotlightPicksStore.flagsDiffer(before, flags)) {
            inner.update { it.copy(kept = SpotlightPicksStore.withFlags(it.kept, flags)) }
            saveLater()
        }
        if (again.isNotEmpty()) magentaEpg.refetch(again)
    }

    /**
     * [MagentaEpg]'s lists for [lists]' keys: read for picks with the kept flags and put in place of those keys'
     * picks. Called from the sync's coroutine, once per chunk; cheap (a chunk is a few dozen lists).
     */
    private fun offer(lists: Map<String, JSONArray>, fetchedAt: Long) {
        val flags = inner.value.kept
        // Only the keys Home shows (the stored guide also holds football channels, which have no place here); a
        // key Home asks for later was never kept and is fetched again then ([onRequest]).
        val shown = requests.value?.channels?.map { MagentaNames.key(it.name) }?.toSet()
        val parsed = lists.filterKeys { shown == null || it in shown }.mapValues { (key, list) ->
            MagentaNames.picksOf(list, key, docs = flags != null && key in flags.docKeys, films = flags != null && key in flags.filmKeys)
                // Late tonight at the most, as the picks have always been.
                .filter { it.startMs < fetchedAt + AHEAD_MS }
        }
        if (parsed.isEmpty()) return
        inner.update { it.copy(kept = SpotlightPicksStore.merge(it.kept, parsed, fetchedAt, System.currentTimeMillis())) }
        tv.own.owntv.perf.StartLog.stampOnce("spotlight-fresh")
        saveLater()
    }

    private fun saveLater() {
        scope.launch(Dispatchers.IO) {
            saveLock.withLock { inner.value.kept?.let { runCatching { save(it) } } }
        }
    }

    /** Written whole beside the old file, then put in its place: a cut-off write never leaves half a list. */
    private fun save(kept: SpotlightPicksStore.Kept) {
        val tmp = File(file.parentFile, "$FILE.tmp")
        tmp.writeText(SpotlightPicksStore.encode(kept))
        if (!tmp.renameTo(file)) { file.delete(); tmp.renameTo(file) }
    }

    private companion object {
        const val FILE = "spotlight_picks.json"
        /** Programmes starting later than this after the fetch are not picks yet ("late tonight"). */
        const val AHEAD_MS = 12 * 60 * 60_000L
    }
}

/** The kept picks: what is written to the device, and how it is read back against Home's channels. */
internal object SpotlightPicksStore {
    private const val VERSION = 2

    /**
     * The picks of the guide keys in [keys] (those whose list was read, also those without a pick), fetched at
     * [at]; [docKeys] and [filmKeys] are the keys read with documentaries / films counted.
     */
    data class Kept(
        val at: Long,
        val keys: Set<String>,
        val docKeys: Set<String>,
        val filmKeys: Set<String>,
        val picks: List<PickData>,
    )

    /** Which keys count documentaries and films. */
    data class Flags(val docKeys: Set<String>, val filmKeys: Set<String>)

    /** A [GuidePick] without its channel, which is found again by [channelKey] among Home's channels. */
    data class PickData(
        val channelKey: String,
        val title: String,
        val startMs: Long,
        val stopMs: Long,
        val highlight: Boolean,
        val tip: Boolean,
        val doc: Boolean,
        val picture: String?,
        val description: String?,
        val film: Boolean,
        val filmCandidate: Boolean,
        val series: Boolean,
        val premiere: Boolean,
        val year: Int?,
        val genre: String?,
        val cast: List<String> = emptyList(),
        val director: String? = null,
    ) {
        fun toPick(channel: ChannelEntity) = GuidePick(
            channel, title, startMs, stopMs, highlight, tip, doc, picture, description,
            film = film, filmCandidate = filmCandidate, series = series, premiere = premiere,
            year = year, genre = genre, cast = cast, director = director,
        )

        companion object {
            fun of(p: GuidePick, key: String = MagentaNames.key(p.channel.name)) = PickData(
                key, p.title, p.startMs, p.stopMs, p.highlight, p.tip, p.doc, p.picture, p.description,
                p.film, p.filmCandidate, p.series, p.premiere, p.year, p.genre, p.cast, p.director,
            )
        }
    }

    private fun keyOf(c: ChannelEntity) = MagentaNames.key(c.name)

    /** [r]'s channels' flags per guide key: arte for documentaries, the film channels for films. */
    fun flagsOf(r: SpotlightPicks.Request): Flags = Flags(
        docKeys = r.channels.filter { it.id in r.docsOn }.map(::keyOf).filter { it.isNotEmpty() }.toSet(),
        filmKeys = r.channels.filter { it.id in r.filmsOn }.map(::keyOf).filter { it.isNotEmpty() }.toSet(),
    )

    /**
     * The covered keys of [r] that must be fetched again: those [kept] has nothing for (never read: the first
     * start after an update, or the list came too early), and those it read with other flags than [r] asks.
     */
    fun refetchKeys(kept: Kept?, r: SpotlightPicks.Request, covered: Set<String>): Set<String> {
        val flags = flagsOf(r)
        val keys = r.channels.map(::keyOf).filter { it.isNotEmpty() && it in covered }.toSet()
        if (kept == null) return keys
        return keys.filterTo(HashSet()) { k ->
            k !in kept.keys || (k in kept.docKeys) != (k in flags.docKeys) || (k in kept.filmKeys) != (k in flags.filmKeys)
        }
    }

    /** Whether [flags] would change what [kept] remembers about the keys [flags] speaks of. */
    fun flagsDiffer(kept: Kept, flags: Flags): Boolean = withFlags(kept, flags) != kept

    /**
     * [kept] with [flags] taken over for the keys Home's request knows (the flags of other keys stay); a key
     * without a flag in [flags] and not in [Kept.keys] is simply not flagged.
     */
    fun withFlags(kept: Kept?, flags: Flags): Kept {
        val k = kept ?: Kept(0L, emptySet(), emptySet(), emptySet(), emptyList())
        return k.copy(docKeys = flags.docKeys, filmKeys = flags.filmKeys)
    }

    /**
     * [kept] with the picks of exactly the keys of [offered] replaced (a key's list may be empty), the other keys
     * left as they are, and picks whose programmes have all ended by [now] dropped.
     */
    fun merge(kept: Kept?, offered: Map<String, List<PickData>>, at: Long, now: Long): Kept {
        val k = kept ?: Kept(at, emptySet(), emptySet(), emptySet(), emptyList())
        val picks = (k.picks.filter { it.channelKey !in offered } + offered.values.flatten()).filter { it.stopMs > now }
        return k.copy(at = at, keys = k.keys + offered.keys, picks = picks)
    }

    /** [kept]'s picks on [r]'s channels for keys in [covered] that have not ended by [now]. */
    fun resolve(r: SpotlightPicks.Request?, kept: Kept?, covered: Set<String>, now: Long): List<GuidePick> {
        if (r == null || kept == null) return emptyList()
        val byKey = HashMap<String, ChannelEntity>()
        for (c in r.channels) { val k = keyOf(c); if (k.isNotEmpty()) byKey.putIfAbsent(k, c) }
        return kept.picks.mapNotNull { d ->
            if (d.stopMs <= now || d.channelKey !in covered) null else byKey[d.channelKey]?.let(d::toPick)
        }
    }

    private fun JSONObject.putStrings(name: String, v: Set<String>) = put(name, JSONArray(v.toList()))
    private fun JSONObject.strings(name: String): Set<String> =
        getJSONArray(name).let { a -> (0 until a.length()).map { a.getString(it) }.toSet() }

    fun encode(k: Kept): String {
        val picks = JSONArray()
        for (p in k.picks) {
            picks.put(
                JSONObject()
                    .put("c", p.channelKey).put("t", p.title).put("s", p.startMs).put("e", p.stopMs)
                    .put("h", p.highlight).put("tip", p.tip).put("doc", p.doc)
                    .putOpt("pic", p.picture).putOpt("d", p.description)
                    .put("f", p.film).put("fc", p.filmCandidate).put("ser", p.series).put("pr", p.premiere)
                    .putOpt("y", p.year).putOpt("g", p.genre).put("cast", JSONArray(p.cast)).putOpt("dir", p.director),
            )
        }
        return JSONObject().put("v", VERSION).put("at", k.at)
            .putStrings("keys", k.keys).putStrings("docs", k.docKeys).putStrings("films", k.filmKeys)
            .put("picks", picks).toString()
    }

    /** Null for anything unreadable or from another version: then nothing is kept, and the lists are fetched again. */
    fun decode(text: String): Kept? = runCatching {
        val o = JSONObject(text)
        if (o.optInt("v") != VERSION) return@runCatching null
        val list = o.getJSONArray("picks")
        val picks = (0 until list.length()).map { i ->
            val p = list.getJSONObject(i)
            val cast = p.optJSONArray("cast")?.let { a -> (0 until a.length()).map { a.getString(it) } }.orEmpty()
            PickData(
                channelKey = p.getString("c"),
                title = p.getString("t"),
                startMs = p.getLong("s"),
                stopMs = p.getLong("e"),
                highlight = p.optBoolean("h"),
                tip = p.optBoolean("tip"),
                doc = p.optBoolean("doc"),
                picture = p.optString("pic").ifEmpty { null }.takeIf { p.has("pic") },
                description = p.optString("d").ifEmpty { null }.takeIf { p.has("d") },
                film = p.optBoolean("f"),
                filmCandidate = p.optBoolean("fc"),
                series = p.optBoolean("ser"),
                premiere = p.optBoolean("pr"),
                year = if (p.has("y")) p.getInt("y") else null,
                genre = p.optString("g").ifEmpty { null }.takeIf { p.has("g") },
                cast = cast,
                director = p.optString("dir").ifEmpty { null }.takeIf { p.has("dir") },
            )
        }
        Kept(o.getLong("at"), o.strings("keys"), o.strings("docs"), o.strings("films"), picks)
    }.getOrNull()
}

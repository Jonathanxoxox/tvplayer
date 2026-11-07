package tv.own.owntv.features.home

import android.content.Context
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import org.json.JSONArray
import org.json.JSONObject
import tv.own.owntv.core.database.entity.ChannelEntity
import tv.own.owntv.core.network.ConnectivityObserver
import tv.own.owntv.launcher.apps.LauncherSystem
import java.io.File

/**
 * Today's picks from MagentaTV's guide for the Spotlight ([MagentaGuide.picks]), kept for the whole app instead
 * of for one Home screen (Jonathan, 7 Nov 2026: after the box starts, the Spotlight takes long to be current).
 *
 * - **Kept on the device** ([FILE]): a start shows the last picks still to come at once, before anything is
 *   asked. Programmes do not move, so a list from this afternoon is right in the evening; one from yesterday
 *   has nothing left to show and simply shows nothing.
 * - **Asked once the start has settled** ([tv.own.owntv.StartupGate]) and the network is there, never during
 *   the first picture. Not again while the kept picks are younger than [REFRESH_MS] by the wall clock.
 * - **Asked again on waking from standby** when the kept picks are older than [WAKE_STALE_MS]: a coroutine's
 *   wait stands still in standby, so after a night the half-hourly round would still be hours away.
 * - A failed round (the network says it is up, the guide does not answer yet) is tried again after [RETRY_MS],
 *   doubling up to [REFRESH_MS]. Without a network nothing is tried: the round waits for it to come back.
 *
 * [state] carries what Home needs to show the Spotlight without it jumping: whether the kept picks are read
 * ([State.ready]), and which wake has had its refresh ([State.wakeServed]).
 */
class SpotlightPicks(
    private val magenta: MagentaGuide,
    private val connectivity: ConnectivityObserver,
    context: Context,
) {
    /** What Home asks for: the channels, arte among them for documentaries, the film channels for films. */
    data class Request(val channels: List<ChannelEntity>, val docsOn: Set<Long>, val filmsOn: Set<Long>)

    data class State(
        val picks: List<GuidePick> = emptyList(),
        /** The kept picks are read and Home has said which channels it shows: [picks] is what there is for now. */
        val ready: Boolean = false,
        /** The last [LauncherSystem.wake] whose refresh is done (or was not needed). */
        val wakeServed: Long = 0L,
    )

    private data class Inner(
        val read: Boolean = false,
        val kept: SpotlightPicksStore.Kept? = null,
        val wakeServed: Long = 0L,
    )

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val file = File(context.applicationContext.filesDir, FILE)
    private val requests = MutableStateFlow<Request?>(null)
    private val inner = MutableStateFlow(Inner(wakeServed = LauncherSystem.wake.value))
    /** The wake that wants a refresh; differs from [Inner.wakeServed] until it has had it. */
    private val nudges = MutableStateFlow(LauncherSystem.wake.value)

    val state: StateFlow<State> = combine(requests, inner) { r, i ->
        State(
            picks = SpotlightPicksStore.resolve(r, i.kept, System.currentTimeMillis()),
            ready = r != null && i.read,
            wakeServed = i.wakeServed,
        )
    }.stateIn(scope, SharingStarted.Eagerly, State(wakeServed = LauncherSystem.wake.value))

    init {
        scope.launch {
            val kept = withContext(Dispatchers.IO) { runCatching { file.takeIf { it.exists() }?.readText() }.getOrNull() }
                ?.let(SpotlightPicksStore::decode)
            if (kept != null) tv.own.owntv.perf.StartLog.stampOnce("spotlight-kept")
            inner.update { it.copy(read = true, kept = it.kept ?: kept) }
            requests.collectLatest { r -> if (r != null) keepFresh(r) }
        }
        scope.launch {
            LauncherSystem.wake.collect { w ->
                if (w == 0L || w == inner.value.wakeServed) return@collect
                val r = requests.value
                val k = inner.value.kept
                val stale = k == null || System.currentTimeMillis() - k.at >= WAKE_STALE_MS
                if (r != null && r.channels.isNotEmpty() && stale) {
                    nudges.value = w
                } else {
                    // Served first, so the round's loop never sees this wake as one still owed a refresh.
                    inner.update { it.copy(wakeServed = w) }
                    nudges.value = w
                }
            }
        }
    }

    /** Home's channels for the picks. The same request again changes nothing. */
    fun want(request: Request) {
        requests.value = request
    }

    /** Keeps [r]'s picks current, for as long as it is what Home asks for. */
    private suspend fun keepFresh(r: Request) {
        if (r.channels.isEmpty()) return
        val asked = SpotlightPicksStore.asked(r)
        var retry = 0
        while (true) {
            val i = inner.value
            val nudge = nudges.value
            val wakeDue = nudge != i.wakeServed
            val k = i.kept?.takeIf { it.asked.containsAll(asked) }
            val age = k?.let { System.currentTimeMillis() - it.at } ?: Long.MAX_VALUE
            if (age < REFRESH_MS && !wakeDue) {
                // Current: wait until it is due, or until the box wakes and wants it sooner.
                withTimeoutOrNull(REFRESH_MS - age) { nudges.first { it != nudge } }
                continue
            }
            // Never during the box's first seconds; and only with a network to ask over (no CPU spent waiting).
            tv.own.owntv.StartupGate.awaitSettled()
            connectivity.isOnline.first { it }
            val now = System.currentTimeMillis()
            val fresh = magenta.picks(r.channels, r.docsOn, now - BACK_MS, now + AHEAD_MS, r.filmsOn)
            if (fresh != null) {
                val kept = SpotlightPicksStore.Kept(now, asked, fresh.map { SpotlightPicksStore.PickData.of(it) })
                inner.update { it.copy(kept = kept, wakeServed = nudge) }
                tv.own.owntv.perf.StartLog.stampOnce("spotlight-fresh")
                withContext(Dispatchers.IO) { runCatching { save(kept) } }
                retry = 0
                continue
            }
            // Nothing came back: whoever waits for this round waits no longer; again after a while, or on a wake.
            inner.update { it.copy(wakeServed = nudge) }
            val wait = (RETRY_MS shl retry).coerceAtMost(REFRESH_MS)
            retry = (retry + 1).coerceAtMost(RETRY_STEPS)
            withTimeoutOrNull(wait) { nudges.first { it != nudge } }
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
        /** A round every half hour while Mayo TV runs, as before. */
        const val REFRESH_MS = 30 * 60_000L
        /** Woken from standby with picks older than this: asked again at once. */
        const val WAKE_STALE_MS = 10 * 60_000L
        const val RETRY_MS = 10_000L
        /** 10, 20, 40 s, … about 21 min, then every half hour. */
        const val RETRY_STEPS = 8
        /** From a little back (programmes on now) to late tonight. */
        const val BACK_MS = 3 * 60 * 60_000L
        const val AHEAD_MS = 12 * 60 * 60_000L
    }
}

/** The kept picks: what is written to the device, and how it is read back against Home's channels. */
internal object SpotlightPicksStore {
    private const val VERSION = 1

    /** A round's picks, [at] when asked (wall clock), [asked] the channels it asked ([asked] of its request). */
    data class Kept(val at: Long, val asked: Set<String>, val picks: List<PickData>)

    /** A [GuidePick] without its channel, which is found again by [channelId] among Home's channels. */
    data class PickData(
        val channelId: Long,
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
        val cast: List<String>,
        val director: String?,
    ) {
        fun toPick(channel: ChannelEntity) = GuidePick(
            channel, title, startMs, stopMs, highlight, tip, doc, picture, description,
            film = film, filmCandidate = filmCandidate, series = series, premiere = premiere,
            year = year, genre = genre, cast = cast, director = director,
        )

        companion object {
            fun of(p: GuidePick) = PickData(
                p.channel.id, p.title, p.startMs, p.stopMs, p.highlight, p.tip, p.doc, p.picture, p.description,
                p.film, p.filmCandidate, p.series, p.premiere, p.year, p.genre, p.cast, p.director,
            )
        }
    }

    /**
     * What a request asks of each channel: its id, and whether its documentaries and films count. A kept round
     * serves a request whose channels it all asked the same way.
     */
    fun asked(r: SpotlightPicks.Request): Set<String> =
        r.channels.map { "${it.id}:${if (it.id in r.docsOn) 1 else 0}${if (it.id in r.filmsOn) 1 else 0}" }.toSet()

    /** [kept]'s picks on [r]'s channels that have not ended by [now]. */
    fun resolve(r: SpotlightPicks.Request?, kept: Kept?, now: Long): List<GuidePick> {
        if (r == null || kept == null) return emptyList()
        val byId = r.channels.associateBy { it.id }
        return kept.picks.mapNotNull { d -> if (d.stopMs <= now) null else byId[d.channelId]?.let(d::toPick) }
    }

    fun encode(k: Kept): String {
        val picks = JSONArray()
        for (p in k.picks) {
            picks.put(
                JSONObject()
                    .put("c", p.channelId).put("t", p.title).put("s", p.startMs).put("e", p.stopMs)
                    .put("h", p.highlight).put("tip", p.tip).put("doc", p.doc)
                    .putOpt("pic", p.picture).putOpt("d", p.description)
                    .put("f", p.film).put("fc", p.filmCandidate).put("ser", p.series).put("pr", p.premiere)
                    .putOpt("y", p.year).putOpt("g", p.genre).put("cast", JSONArray(p.cast)).putOpt("dir", p.director),
            )
        }
        return JSONObject().put("v", VERSION).put("at", k.at).put("asked", JSONArray(k.asked.toList())).put("picks", picks).toString()
    }

    /** Null for anything unreadable or from another version: then nothing is kept, and the guide is asked. */
    fun decode(text: String): Kept? = runCatching {
        val o = JSONObject(text)
        if (o.optInt("v") != VERSION) return@runCatching null
        val asked = o.getJSONArray("asked").let { a -> (0 until a.length()).map { a.getString(it) }.toSet() }
        val list = o.getJSONArray("picks")
        val picks = (0 until list.length()).map { i ->
            val p = list.getJSONObject(i)
            val cast = p.optJSONArray("cast")?.let { a -> (0 until a.length()).map { a.getString(it) } }.orEmpty()
            PickData(
                channelId = p.getLong("c"),
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
        Kept(o.getLong("at"), asked, picks)
    }.getOrNull()
}

package tv.own.owntv.features.home

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
import tv.own.owntv.core.live.GuideStats
import java.text.SimpleDateFormat
import java.util.Locale
import java.util.TimeZone
import java.util.concurrent.ConcurrentHashMap

/**
 * Programme pictures from MagentaTV's web guide (Jonathan, 4 Nov 2026: "use it first, TMDB as the
 * fallback"). The guide answers a guest without an account, the way its web page does, and carries
 * Gracenote's pictures for nearly every programme: 16:9 at 1920 × 1080, the TV magazines' own.
 *
 * Unofficial: the guide may change without notice. Every failure only means "no picture from here",
 * and [ProgrammeArt] then asks TMDB. Asked once per programme shown and kept in memory.
 */
class MagentaGuide(private val http: OkHttpClient) {
    private val lock = Mutex()
    // The guest session, only read and written in [session].
    private var token: String? = null
    private var cookies: String = ""
    private var sessionAt = 0L
    /** Channel key (see [MagentaNames.key]) → the guide's channel id. */
    @Volatile private var channels: Map<String, String> = emptyMap()
    private var channelsAt = 0L
    /** "channelId@startMs" → picture URL, or "" for none. */
    private val pictures = ConcurrentHashMap<String, String>()

    /** The picture for what starts on [channelName] at [startMs], or null. */
    suspend fun picture(channelName: String, startMs: Long): String? = withContext(Dispatchers.IO) {
        runCatching {
            // One call at a time: the guest session is shared.
            lock.withLock {
                val id = channelId(channelName) ?: return@withLock null
                val key = "$id@$startMs"
                pictures[key]?.let { return@withLock it.ifEmpty { null } }
                val url = lookup(id, startMs).orEmpty()
                pictures[key] = url
                url.ifEmpty { null }
            }
        }.getOrNull()
    }

    /**
     * What is known already, without asking: the picture's URL, "" when the guide has none for it (or does
     * not carry the channel), null when it has not been asked yet.
     */
    fun peek(channelName: String, startMs: Long): String? {
        val known = channels
        if (known.isEmpty()) return null
        val id = known[MagentaNames.key(channelName)] ?: return ""
        return pictures["$id@$startMs"]
    }

    /**
     * The raw `playbilllist` of guide channel [id] from [fromMs] to [toMs], null when the guide did not
     * answer. Season, episode and year are asked for too; should the guide ever refuse them, it is asked
     * again with the fields it has always answered, and from then on only with those.
     */
    private fun playbillList(id: String, fromMs: Long, toMs: Long): JSONArray? {
        fun ask(fields: String): JSONArray? {
            val body = JSONObject()
                .put("count", -1)
                .put("isFillProgram", 0)
                .put("offset", 0)
                .put("properties", JSONArray().put(JSONObject().put("include", fields).put("name", "playbill")))
                .put("type", 2)
                .put("begintime", utc(fromMs))
                .put("channelid", id)
                .put("endtime", utc(toMs))
            return post("PlayBillList", body)?.takeIf { it.optString("retcode", "0") == "0" }?.optJSONArray("playbilllist")
        }
        return (if (extraFields) ask(PICK_FIELDS + PICK_EXTRA_FIELDS) else null)
            ?: ask(PICK_FIELDS)?.also { if (extraFields) extraFields = false }
    }

    /**
     * The guide's channels, key (see [MagentaNames.key]) → guide channel id, for the stored guide
     * ([MagentaEpg]). Fills the channel list under the lock; null when the guide cannot be asked.
     */
    suspend fun guideIndex(): Map<String, String>? = withContext(Dispatchers.IO) {
        runCatching {
            lock.withLock { channelId("") } // fills the channel list
            channels.takeIf { it.isNotEmpty() }
        }.getOrNull()
    }

    /**
     * The raw programme lists of [guideChannelIds] from [fromMs] to [toMs], one call each, [PICKS_PARALLEL] at a
     * time on the low-priority [pickDispatcher]; the calls not yet begun are not made once the caller gives up.
     * A null value: that channel did not answer.
     */
    suspend fun playbills(guideChannelIds: List<String>, fromMs: Long, toMs: Long): Map<String, JSONArray?> {
        val ids = guideChannelIds.distinct()
        val lists = coroutineScope {
            ids.map { id ->
                async(pickDispatcher) {
                    ensureActive()
                    runCatching { playbillList(id, fromMs, toMs) }.getOrNull()
                }
            }.awaitAll()
        }
        return ids.zip(lists).toMap()
    }

    /**
     * The threads the picks are asked and read on: at most [PICKS_PARALLEL], at background priority, so on a
     * small box they give way to the interface and the decoder. Made when first needed, gone when idle.
     */
    private val pickDispatcher by lazy {
        java.util.concurrent.ThreadPoolExecutor(
            PICKS_PARALLEL, PICKS_PARALLEL, 30L, java.util.concurrent.TimeUnit.SECONDS, java.util.concurrent.LinkedBlockingQueue<Runnable>(),
            java.util.concurrent.ThreadFactory { r ->
                Thread({
                    runCatching { android.os.Process.setThreadPriority(android.os.Process.THREAD_PRIORITY_BACKGROUND) }
                    r.run()
                }, "spotlight-guide").apply { isDaemon = true }
            },
        ).apply { allowCoreThreadTimeOut(true) }.asCoroutineDispatcher()
    }

    /** Off once the guide has refused [PICK_EXTRA_FIELDS] and answered without them. */
    @Volatile private var extraFields = true

    /** Match key → picture URL, or "" when the sports channels were all asked and none shows it. */
    private val matchPictures = ConcurrentHashMap<String, String>()

    /** Match key → what the broadcast's listing says besides its picture: the matchday, the venue line. */
    private val matchNotes = ConcurrentHashMap<String, MatchNotes>()

    /** The matchday and the guide's line on the match, once [matchPicture] found its broadcast. */
    fun matchNotes(key: String): MatchNotes? = matchNotes[key]

    /**
     * A picture for a match, from the programme that shows it on one of the guide's sports channels (Sky Sport,
     * DAZN, MagentaSport …): "Live BL: Hamburger SV - Borussia M'gladbach" on Sky Sport Bundesliga 1 for HSV
     * against Gladbach (Jonathan, 6 Nov 2026). The guide's search is closed to guests, so the likely channels
     * are asked one by one around the kick-off, stopping at the first that has it. [home] and [away] are each
     * team's names (full, short, abbreviation). Null when none shows it or the guide cannot be asked.
     */
    suspend fun matchPicture(key: String, home: List<String>, away: List<String>, kickoffMs: Long): String? =
        withContext(Dispatchers.IO) {
            matchPictures[key]?.let { return@withContext it.ifEmpty { null } }
            // The shared board first: one read for every match around now (the sports page's posters use it too).
            boardPlaybill(home, away, kickoffMs)?.let { p ->
                matchNotes[key] = MagentaNames.matchNotes(p)
                MagentaNames.picture(p)?.let { matchPictures[key] = it; return@withContext it }
            }
            runCatching {
                lock.withLock {
                    channelId("") // fills the channel list
                    val known = channels
                    if (known.isEmpty()) return@withLock null
                    var answered = true
                    var found: String? = null
                    for (id in MagentaNames.sportChannels(known)) {
                        val body = JSONObject()
                            .put("count", -1)
                            .put("isFillProgram", 0)
                            .put("offset", 0)
                            .put("properties", JSONArray().put(JSONObject().put("include", MATCH_FIELDS).put("name", "playbill")))
                            .put("type", 2)
                            .put("begintime", utc(kickoffMs - MatchPictures.BEFORE_MS))
                            .put("channelid", id)
                            .put("endtime", utc(kickoffMs + MatchPictures.AFTER_MS))
                        val list = post("PlayBillList", body)?.optJSONArray("playbilllist")
                        if (list == null) { answered = false; continue }
                        val p = MagentaNames.matchPlaybill(list, home, away, kickoffMs) ?: continue
                        matchNotes[key] = MagentaNames.matchNotes(p)
                        found = MagentaNames.picture(p) ?: continue
                        break
                    }
                    // Remembered only when every channel answered: a network hiccup asks again next time.
                    if (found != null || answered) matchPictures[key] = found.orEmpty()
                    found
                }
            }.getOrNull()
        }

    /** The sports and free-TV channels' programmes from a few hours back to a day and a half ahead, read once. */
    @Volatile private var board: JSONArray? = null
    @Volatile private var boardFrom = 0L
    @Volatile private var boardTo = 0L
    @Volatile private var boardAt = 0L

    /**
     * A match's picture from the shared board (see [board]): one guide read per channel for all of the sports
     * page's posters instead of one per match (Jonathan, 7 Nov 2026). Null when the match lies outside the
     * board's window, nothing on it names both teams, or the guide cannot be asked.
     */
    suspend fun boardPicture(home: List<String>, away: List<String>, kickoffMs: Long): String? =
        boardPlaybill(home, away, kickoffMs)?.let(MagentaNames::picture)

    /** The match's broadcast on the shared board, see [boardPicture]. */
    private suspend fun boardPlaybill(home: List<String>, away: List<String>, kickoffMs: Long): JSONObject? = withContext(Dispatchers.IO) {
        runCatching {
            val b = sportsBoard() ?: return@runCatching null
            if (kickoffMs < boardFrom || kickoffMs > boardTo) return@runCatching null
            MagentaNames.matchPlaybill(b, home, away, kickoffMs)
        }.getOrNull()
    }

    private suspend fun sportsBoard(): JSONArray? {
        val fresh = { b: JSONArray? -> b?.takeIf { System.currentTimeMillis() - boardAt < BOARD_TTL_MS } }
        fresh(board)?.let { return it }
        return lock.withLock {
            fresh(board)?.let { return@withLock it }
            channelId("") // fills the channel list
            val known = channels
            if (known.isEmpty()) return@withLock null
            val now = System.currentTimeMillis()
            val from = now - BOARD_BACK_MS
            val to = now + BOARD_AHEAD_MS
            val all = JSONArray()
            var answered = 0
            for (id in MagentaNames.boardChannels(known)) {
                val body = JSONObject()
                    .put("count", -1)
                    .put("isFillProgram", 0)
                    .put("offset", 0)
                    .put("properties", JSONArray().put(JSONObject().put("include", MATCH_FIELDS).put("name", "playbill")))
                    .put("type", 2)
                    .put("begintime", utc(from))
                    .put("channelid", id)
                    .put("endtime", utc(to))
                val list = post("PlayBillList", body)?.optJSONArray("playbilllist") ?: continue
                answered++
                for (i in 0 until list.length()) all.put(list.get(i))
            }
            if (answered == 0) return@withLock null
            board = all
            boardFrom = from
            boardTo = to
            boardAt = now
            all
        }
    }

    private fun channelId(name: String): String? {
        val now = System.currentTimeMillis()
        if (channels.isEmpty() || now - channelsAt > CHANNELS_TTL_MS) {
            val list = post(
                "AllChannel",
                JSONObject()
                    .put("channelNamespace", 2)
                    .put("filterlist", JSONArray().put(JSONObject().put("key", "IsHide").put("value", "-1")))
                    .put("metaDataVer", "Channel/1.1")
                    .put(
                        "properties",
                        JSONArray().put(
                            JSONObject()
                                .put("include", "/channellist/logicalChannel/contentId,/channellist/logicalChannel/name")
                                .put("name", "logicalChannel"),
                        ),
                    )
                    .put("returnSatChannel", 0),
            )?.optJSONArray("channellist") ?: return null
            channels = MagentaNames.index(list)
            channelsAt = now
        }
        return channels[MagentaNames.key(name)]
    }

    private fun lookup(channelId: String, startMs: Long): String? {
        val body = JSONObject()
            .put("count", -1)
            .put("isFillProgram", 0)
            .put("offset", 0)
            .put("properties", JSONArray().put(JSONObject().put("include", "starttime,name,pictures").put("name", "playbill")))
            .put("type", 2)
            .put("begintime", utc(startMs - WINDOW_MS))
            .put("channelid", channelId)
            .put("endtime", utc(startMs + WINDOW_MS))
        val list = post("PlayBillList", body)?.optJSONArray("playbilllist") ?: return null
        return MagentaNames.pictureFor(list, startMs)
    }

    /**
     * One call to the guide, signing in as a guest first (again, when the session is old or refused). Safe from
     * several threads at once: the picks' calls run in parallel ([picks]), the session is shared ([session]).
     */
    private fun post(path: String, body: JSONObject, retry: Boolean = true): JSONObject? {
        val startedAt = System.nanoTime()
        var outcome = GuideStats.Outcome.FAILED
        try {
            return postUncounted(path, body, retry).also { if (it != null) outcome = GuideStats.Outcome.OK }
        } finally {
            // Every HTTP call lands in the on-device EPG report (More > About > Start log), thrown or not.
            GuideStats.record(GuideStats.SOURCE_MAGENTA, (System.nanoTime() - startedAt) / 1_000_000, outcome)
        }
    }

    private fun postUncounted(path: String, body: JSONObject, retry: Boolean): JSONObject? {
        val (t, c) = session() ?: return null
        val request = Request.Builder()
            .url(BASE + path)
            .header("X_CSRFTOKEN", t)
            .header("Cookie", c)
            .post(body.toString().toRequestBody(JSON))
            .build()
        val json = http.newCall(request).execute().use { r -> if (r.isSuccessful) r.body?.string() else null } ?: return null
        val o = JSONObject(json)
        // A session the guide no longer knows: sign in again once (once for all the calls that found it refused).
        if (o.optString("retcode", "0") != "0" && retry) {
            session(refused = t) ?: return null
            return postUncounted(path, body, retry = false)
        }
        return o
    }

    /**
     * The guest session's token and cookies, signing in when there is none, it is old, or [refused] is the one the
     * guide just turned down (and nobody has signed in again since). One thread at a time.
     */
    @Synchronized
    private fun session(refused: String? = null): Pair<String, String>? {
        if (refused != null && token == refused) token = null
        if (token == null || System.currentTimeMillis() - sessionAt > SESSION_TTL_MS) authenticate() ?: return null
        return token?.let { it to cookies }
    }

    private fun authenticate(): Unit? {
        val body = JSONObject()
            .put("terminalid", "00:00:00:00:00:00")
            .put("mac", "00:00:00:00:00:00")
            .put("terminaltype", "WEBTV")
            .put("utcEnable", 1)
            .put("timezone", "Etc/GMT0")
            .put("userType", 3)
            .put("terminalvendor", "Unknown")
        val request = Request.Builder()
            .url(BASE + "Authenticate?SID=firstup&T=Windows_chrome_118")
            .post(body.toString().toRequestBody(JSON))
            .build()
        val (text, setCookies) = http.newCall(request).execute().use { r ->
            (if (r.isSuccessful) r.body?.string() else null) to r.headers("Set-Cookie")
        }
        val t = text?.let { JSONObject(it).optString("csrfToken") }
        if (t.isNullOrBlank()) return null
        token = t
        cookies = setCookies.map { it.substringBefore(';') }.joinToString("; ")
        sessionAt = System.currentTimeMillis()
        return Unit
    }

    private fun utc(ms: Long): String = SimpleDateFormat("yyyyMMddHHmmss", Locale.US)
        .apply { timeZone = TimeZone.getTimeZone("UTC") }.format(ms)

    private companion object {
        const val BASE = "https://api.prod.sngtv.magentatv.de/EPG/JSON/"
        val JSON = "application/json".toMediaType()
        const val SESSION_TTL_MS = 30 * 60_000L
        const val CHANNELS_TTL_MS = 24 * 60 * 60_000L
        /** The shared sports board: read again after half an hour, from 3 h back to 36 h ahead. */
        const val BOARD_TTL_MS = 30 * 60_000L
        const val BOARD_BACK_MS = 3 * 60 * 60_000L
        const val BOARD_AHEAD_MS = 36 * 60 * 60_000L
        /** The guide's start times differ from the provider's by a few minutes now and then. */
        const val WINDOW_MS = 20 * 60_000L
        const val PICK_FIELDS = "name,starttime,endtime,tipType,tipCategory,genres,pictures,introduce"
        /** What tells a film from an episode, and its year. */
        const val PICK_EXTRA_FIELDS = ",seasonNO,sitcomNO,seriesID,producedate"
        /** The picks' calls at once: enough to be quick, few enough not to crowd a small box. */
        const val PICKS_PARALLEL = 3
    }
}

/** How far around the kick-off a match's broadcast is looked for (pre-match shows start up to an hour early). */
internal object MatchPictures {
    const val BEFORE_MS = 90 * 60_000L
    const val AFTER_MS = 20 * 60_000L
}

/** Matching the playlist's channel names to the guide's, and picking a programme's picture. */
internal object MagentaNames {
    /** The sports channels and the free channels national teams play on: what the shared board reads. */
    fun boardChannels(index: Map<String, String>): List<String> {
        val free = listOf("das erste", "zdf", "rtl", "prosieben", "sat 1", "vox", "nitro", "rtl nitro")
        return sportChannels(index) + free.mapNotNull { index[it] }.filter { it !in sportChannels(index) }
    }

    /** The guide's sports channels, the ones most likely to carry a football match first. */
    fun sportChannels(index: Map<String, String>): List<String> {
        val hints = listOf("sky sport", "dazn", "magentasport", "magenta sport", "sport1", "sport 1", "eurosport", "sky showcase")
        return index.entries
            .filter { (k, _) -> hints.any { k.startsWith(it) } }
            .sortedBy { (k, _) ->
                when {
                    "bundesliga" in k || "top event" in k -> 0
                    k.startsWith("dazn") -> 1
                    k.startsWith("sky sport") -> 2
                    else -> 3
                }
            }
            .map { it.value }
    }

    /**
     * The football rights holders' channels, by [key]: Sky Sport (its Bundesliga, Top Event and the rest), any
     * "Bundesliga" channel, and DAZN. Not Sport1, Eurosport or Sky Cinema.
     */
    fun isFootballChannel(key: String): Boolean = "sky sport" in key || "bundesliga" in key || key.startsWith("dazn")

    /** Lower case, umlauts folded, so "Mönchengladbach" and "M'gladbach" meet on "gladbach". */
    fun fold(s: String): String = java.text.Normalizer.normalize(
        s.lowercase(Locale.ROOT).replace("ß", "ss"),
        java.text.Normalizer.Form.NFD,
    ).filter { Character.getType(it) != Character.NON_SPACING_MARK.toInt() }

    /** A team's long name parts (4+ letters) to find inside a title, and its short codes as whole words. */
    /** Whether [title] names the team called [names]: an event list's own team name, for example. */
    fun namesTeam(names: List<String>, title: String): Boolean {
        val t = fold(title)
        return teamMatches(names, t, t.split(Regex("[^a-z0-9]+")).filter { it.isNotEmpty() }.toSet())
    }

    private fun teamMatches(names: List<String>, title: String, words: Set<String>): Boolean {
        val parts = names.flatMap { fold(it).split(' ', '-', '.', '\'', '/').filter { p -> p.length >= 4 } }
        val codes = names.map { fold(it).trim() }.filter { it.length in 2..4 && ' ' !in it }
        return parts.any { it in title } || codes.any { it in words }
    }

    /**
     * The picture of the programme that shows the match: both teams named, starting from an hour and a half
     * before to 20 minutes after [kickoffMs]; the live broadcast before a countdown, conference or highlights.
     */
    fun matchPicture(playbills: JSONArray, home: List<String>, away: List<String>, kickoffMs: Long): String? =
        matchPlaybill(playbills, home, away, kickoffMs)?.let(::pictureOf)

    /** A listing's picture, as [matchPicture] takes it. */
    fun picture(p: JSONObject): String? = pictureOf(p)

    /**
     * What a match broadcast's listing says besides its picture: the matchday after the last comma of its
     * title ("Live BL: Borussia Dortmund - SV Elversberg, 9. Spieltag") and its line on the match
     * ("Aus dem Signal Iduna Park, Dortmund, Deutschland").
     */
    fun matchNotes(p: JSONObject): MatchNotes = MatchNotes(
        round = p.optString("name").substringAfterLast(", ", "").trim().takeIf { it.isNotEmpty() && it.any(Char::isDigit) },
        line = p.optString("introduce").trim().takeIf { it.isNotEmpty() },
    )

    /** The listing of the programme that shows the match, see [matchPicture]. */
    fun matchPlaybill(playbills: JSONArray, home: List<String>, away: List<String>, kickoffMs: Long): JSONObject? {
        if (home.isEmpty() || away.isEmpty()) return null
        var best: JSONObject? = null
        var bestScore = Long.MAX_VALUE
        for (i in 0 until playbills.length()) {
            val p = playbills.optJSONObject(i) ?: continue
            val title = fold(p.optString("name"))
            val words = title.split(Regex("[^a-z0-9]+")).filter { it.isNotEmpty() }.toSet()
            if (!teamMatches(home, title, words) || !teamMatches(away, title, words)) continue
            val start = parseTime(p.optString("starttime")) ?: continue
            if (start < kickoffMs - MatchPictures.BEFORE_MS || start > kickoffMs + MatchPictures.AFTER_MS) continue
            // Not the match itself: a conference's picture is its own show's ("Live PL: Goal Rush, CRY - LIV,
            // Premier League Konferenz" gave Crystal Palace v Liverpool Goal Rush's, 7 Nov 2026).
            if (ASIDE.any { it in title }) continue
            val score = kotlin.math.abs(start - kickoffMs)
            if (score < bestScore) { best = p; bestScore = score }
        }
        return best
    }

    /** Programmes about a match that are not its broadcast. */
    private val ASIDE = listOf("countdown", "fantasy", "konferenz", "goal rush", "highlights", "vorbericht", "analyse", "multi")

    /** Words that say nothing about which channel it is: country tags, quality, codec. */
    private val noise = setOf("de", "ger", "germany", "hd", "fhd", "uhd", "sd", "4k", "hevc", "h265", "raw", "50fps", "tv")
    private val utcFormat = ThreadLocal.withInitial {
        SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.US).apply { timeZone = TimeZone.getTimeZone("UTC") }
    }

    /** "DE: SAT.1 HD" and "SAT.1" → "sat 1"; "Das Erste HD" → "das erste"; "Romance TV HD" → "romance". */
    fun key(name: String): String =
        ProgrammeTitles.key(name).split(' ').filter { it.isNotEmpty() && it !in noise }.joinToString(" ")

    /**
     * How much a guide channel's raw [name] is preferred when several share one [key]: 0 for plain or HD
     * (also FHD), 1 for SD, 2 for UHD/4K (a stream few boxes want as the default). Lower wins.
     */
    fun qualityRank(name: String): Int {
        val words = ProgrammeTitles.key(name).split(' ')
        return when {
            "uhd" in words || "4k" in words -> 2
            "sd" in words && "hd" !in words && "fhd" !in words -> 1
            else -> 0
        }
    }

    /**
     * The guide's channel list → key → channel id. Several guide channels can share a key once quality
     * words are dropped ("ProSieben", "ProSieben HD", "ProSieben UHD"); the best [qualityRank] wins and,
     * within the same rank, the first in list order, so the pick does not depend on the list's order alone.
     */
    fun index(list: JSONArray): Map<String, String> {
        val out = LinkedHashMap<String, String>()
        val ranks = HashMap<String, Int>()
        for (i in 0 until list.length()) {
            val c = list.optJSONObject(i) ?: continue
            val id = c.optString("contentId")
            val name = c.optString("name")
            val k = key(name)
            if (id.isBlank() || k.isEmpty()) continue
            val rank = qualityRank(name)
            val have = ranks[k]
            if (have == null || rank < have) { out[k] = id; ranks[k] = rank }
        }
        return out
    }

    /**
     * The picture of the programme starting nearest [startMs] (within 20 minutes): its 16:9 picture
     * first (type 17), then the wide banner (18), then any; over https.
     */
    fun pictureFor(playbills: JSONArray, startMs: Long): String? {
        var best: JSONObject? = null
        var bestGap = Long.MAX_VALUE
        for (i in 0 until playbills.length()) {
            val p = playbills.optJSONObject(i) ?: continue
            val start = parseTime(p.optString("starttime")) ?: continue
            val gap = kotlin.math.abs(start - startMs)
            if (gap < bestGap) { best = p; bestGap = gap }
        }
        if (best == null || bestGap > 20 * 60_000L) return null
        return pictureOf(best)
    }

    /** A programme's 16:9 picture (type 17), else its wide banner (18), else any; over https. */
    private fun pictureOf(p: JSONObject): String? {
        val pics = p.optJSONArray("pictures") ?: return null
        val all = (0 until pics.length()).mapNotNull { pics.optJSONObject(it) }
        val pick = listOf("17", "18").firstNotNullOfOrNull { t -> all.firstOrNull { it.optString("imageType") == t } } ?: all.firstOrNull()
        return pick?.optString("href")?.ifBlank { null }?.replaceFirst("http://", "https://")
    }

    private fun startOf(p: JSONObject, field: String): Long? = parseTime(p.optString(field))

    private val zoneSuffix = Regex("^\\s*(?:UTC)?\\s*([+-])(\\d{1,2}):?(\\d{2})\\s*$")

    /**
     * A guide time such as "2026-11-07 20:15:00 UTC+01:00" as a UTC instant. The first 19 characters are
     * the wall-clock time; the suffix ("UTC+hh:mm", "+hh:mm" or none, meaning UTC) says how far that clock
     * is from UTC, so it is taken off again.
     */
    fun parseTime(s: String): Long? {
        val local = runCatching { utcFormat.get()!!.parse(s.take(19))?.time }.getOrNull() ?: return null
        val m = zoneSuffix.find(s.drop(19)) ?: return local
        val minutes = m.groupValues[2].toInt() * 60 + m.groupValues[3].toInt()
        val sign = if (m.groupValues[1] == "-") -1 else 1
        return local - sign * minutes * 60_000L
    }

    /**
     * The guide's programmes on [channel] worth a Spotlight place: those MagentaTV marks as "highlight" or
     * "tip", with [docs] (arte) every documentary of half an hour or more, and with [films] (a film channel)
     * every film. Each says whether it is a film, an episode of a series, or a premiere.
     */
    fun picks(
        list: JSONArray,
        channel: tv.own.owntv.core.database.entity.ChannelEntity,
        docs: Boolean,
        films: Boolean = false,
    ): List<GuidePick> = picksOf(list, key(channel.name), docs, films).map { it.toPick(channel) }

    /**
     * [picks] without a channel, for the channel with guide key [channelKey]: what the Spotlight keeps per key
     * ([SpotlightPicksStore.PickData]), so a list can be read before Home has said which channel entities it shows.
     */
    fun picksOf(
        list: JSONArray,
        channelKey: String,
        docs: Boolean,
        films: Boolean = false,
    ): List<SpotlightPicksStore.PickData> {
        val out = ArrayList<SpotlightPicksStore.PickData>()
        for (i in 0 until list.length()) {
            val p = list.optJSONObject(i) ?: continue
            val start = startOf(p, "starttime") ?: continue
            val stop = startOf(p, "endtime") ?: continue
            val type = p.optString("tipType")
            val highlight = type == "highlight"
            val tip = type == "tip"
            val genreList = p.optString("genres").split(',', '/', '|').map { it.trim() }.filter { it.isNotEmpty() && it != "null" }
            val genres = genreList.joinToString(",").lowercase()
            val title = p.optString("name")
            if (title.isBlank()) continue
            val description = p.optString("introduce").ifBlank { null }
            val series = isSeries(p, genres, title)
            val long = stop - start >= FILM_MIN_MS
            // A documentary film ("Dokumentarfilm") is a documentary here, not one of tonight's films.
            val isFilm = !series && DOC_WORDS.none { it in genres } && (
                p.optString("tipCategory") == "movie" || (long && (films || filmWords.any { it in genres }))
                )
            val isDoc = p.optString("tipCategory") == "documentary" ||
                (docs && stop - start >= DOC_MIN_MS && DOC_WORDS.any { it in genres })
            if (!highlight && !tip && !isDoc && !(films && isFilm)) continue
            // A long tip the guide does not file as anything ("Meg" on ProSieben, 7 Nov 2026): maybe a film. TMDB
            // decides (ProgrammeArt.film) whether it is one; a series, documentary, sport or show never is.
            val candidate = !isFilm && !isDoc && !series && stop - start >= CANDIDATE_MIN_MS &&
                p.optString("tipCategory").let { it.isBlank() || it == "null" } &&
                NOT_FILM_WORDS.none { it in genres }
            val text = (title + " " + description.orEmpty()).lowercase()
            out += SpotlightPicksStore.PickData(
                channelKey = channelKey,
                title = title,
                startMs = start,
                stopMs = stop,
                highlight = highlight,
                tip = tip,
                doc = isDoc,
                picture = pictureOf(p),
                description = description,
                film = isFilm,
                filmCandidate = candidate,
                series = series,
                premiere = isFilm && (PREMIERE_WORDS.any { it in text } || "premiere" in channelKey),
                year = p.optString("producedate").take(4).toIntOrNull()?.takeIf { it in 1900..2100 },
                // "Thriller" rather than "Spielfilm": the kind of film, when the guide says more than that it is one.
                genre = genreList.firstOrNull { g -> filmWords.none { it == g.lowercase() } } ?: genreList.firstOrNull(),
            )
        }
        return out
    }

    /**
     * A channel's whole programme list for the stored guide: entries without a title or ending no later than
     * they start are skipped, the rest sorted by start and one per start (the guide repeats an entry now and
     * then; the first wins, and the table's key is channel + start).
     */
    fun programmes(list: JSONArray): List<MagentaProgramme> {
        val out = ArrayList<MagentaProgramme>(list.length())
        for (i in 0 until list.length()) {
            val p = list.optJSONObject(i) ?: continue
            val title = p.optString("name").trim()
            if (title.isEmpty() || title == "null") continue
            val start = startOf(p, "starttime") ?: continue
            val stop = startOf(p, "endtime") ?: continue
            if (stop <= start) continue
            out += MagentaProgramme(
                title = title,
                startMs = start,
                stopMs = stop,
                description = p.optString("introduce").trim().takeIf { it.isNotEmpty() && it != "null" },
                genres = p.optString("genres").split(',', '/', '|').map { it.trim() }.filter { it.isNotEmpty() && it != "null" },
                year = p.optString("producedate").take(4).toIntOrNull()?.takeIf { it in 1900..2100 },
                episode = episodeText(p.optString("seasonNO"), p.optString("sitcomNO")),
            )
        }
        return out.sortedBy { it.startMs }.distinctBy { it.startMs }
    }

    /** "S2 E5" as the core's XMLTV parser writes it; "E5" without a season, null when neither is a positive number. */
    fun episodeText(season: String?, episode: String?): String? {
        val s = season?.trim()?.toIntOrNull()?.takeIf { it > 0 }
        val e = episode?.trim()?.toIntOrNull()?.takeIf { it > 0 }
        return when {
            s != null && e != null -> "S$s E$e"
            e != null -> "E$e"
            s != null -> "S$s"
            else -> null
        }
    }

    /** An episode: the guide numbers it (season or episode), files it under a series genre, or its title says so. */
    private fun isSeries(p: JSONObject, genres: String, title: String): Boolean {
        val numbered = listOf("seasonNO", "sitcomNO").any { f -> p.optString(f).let { it.isNotBlank() && it != "null" && it != "0" } }
        return numbered || seriesWords.any { it in genres } || episodeTitle.containsMatchIn(title)
    }

    /**
     * A film channel by its name (as [key] folds it): Sky Cinema's channels, MagentaTV's own film channels,
     * and any other "Kino" / "Cinema" / "Movie" channel of the playlist.
     */
    fun isFilmChannel(name: String): Boolean {
        val k = key(name)
        return FILM_CHANNEL_WORDS.any { it in k }
    }

    private val FILM_CHANNEL_WORDS = listOf("cinema", "kino", "movie", "filmfest", "magenta film")

    /** How the guides name a series ("Serie", "Krimiserie", "Sitcom", "Soap", "Telenovela"). */
    val seriesWords = listOf("serie", "sitcom", "soap", "telenovela")

    /** How the guide names a film ("Spielfilm", "Fernsehfilm", "Kinofilm", "Film"). */
    private val filmWords = listOf("spielfilm", "fernsehfilm", "kinofilm", "film", "movie")

    /** "S2 E5", "Folge 12", "Staffel 3": an episode by its title. */
    private val episodeTitle = Regex("(?i)\\bS\\d{1,2}\\s?E\\d{1,3}\\b|\\b(folge|staffel|episode)\\s+\\d+")

    private val PREMIERE_WORDS = listOf("tv-premiere", "free-tv-premiere", "erstausstrahlung", "premiere:")

    /** A film is at least this long; shorter ones on a film channel are trailers and magazines. */
    private const val FILM_MIN_MS = 70 * 60_000L
    /** A tip this long that says nothing of what it is may be a film; TMDB decides. */
    private const val CANDIDATE_MIN_MS = 80 * 60_000L
    /** Genres that are never a film: shows, sport, news, magazines, music, reality. */
    private val NOT_FILM_WORDS = listOf("show", "sport", "nachricht", "magazin", "musik", "reality", "talk", "quiz", "doku", "reportage")
    private const val DOC_MIN_MS = 30 * 60_000L
    /** How the guide names documentary genres ("Dokumentation", "Doku-Reihe", "Reportage"). */
    private val DOC_WORDS = listOf("doku", "reportage")
}

/** One programme of MagentaTV's guide, as the stored guide ([MagentaEpg]) keeps it. */
data class MagentaProgramme(
    val title: String,
    val startMs: Long,
    val stopMs: Long,
    val description: String?,
    val genres: List<String>,
    val year: Int?,
    val episode: String?,
)

/** What a match broadcast's listing adds: its matchday ("9. Spieltag") and the guide's line on it. */
data class MatchNotes(val round: String?, val line: String?)

/** A match broadcast's listing: when, what, its picture and the guide's line on it. */
private const val MATCH_FIELDS = "starttime,name,pictures,introduce"

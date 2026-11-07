package tv.own.owntv.features.sports

import android.content.Context
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONArray
import org.json.JSONObject
import tv.own.owntv.core.sports.model.Competitor
import tv.own.owntv.core.sports.model.Match
import tv.own.owntv.features.home.MagentaGuide
import tv.own.owntv.features.home.MagentaNames
import java.io.File
import java.text.SimpleDateFormat
import java.util.Locale
import java.util.TimeZone
import java.util.concurrent.ConcurrentHashMap

/**
 * A match's poster for the sports page's tiles (Jonathan, 7 Nov 2026): MagentaTV's programme picture when a
 * sports or free channel shows the match (Sky's Bundesliga and Premier League, national teams on ARD/ZDF/RTL),
 * else TheSportsDB's picture of the match (LaLiga, Serie A, Champions and Europa League, internationals), else
 * none — the tile then paints the clubs' colours.
 *
 * TheSportsDB's free key allows few requests and answers 429 for a while once they run out, so it is asked once
 * per league and day, its answers are kept on disk, and a refusal pauses it for ten minutes.
 */
class SportPosters(
    private val context: Context,
    private val http: OkHttpClient,
    private val magenta: MagentaGuide,
) {
    /** Match key → (picture URL or "" for none, when it was decided). */
    private val known = ConcurrentHashMap<String, Pair<String, Long>>()
    private val days = ConcurrentHashMap<String, JSONArray>()
    private val tsdbLock = Mutex()
    @Volatile private var lastCall = 0L
    @Volatile private var pausedUntil = 0L

    /** What is known without asking: the URL, "" for none, null when not decided yet. */
    fun peek(m: Match): String? = known[m.key]?.takeIf { (url, at) -> url.isNotEmpty() || System.currentTimeMillis() - at < NONE_TTL_MS }?.first

    suspend fun posterFor(m: Match): String? {
        peek(m)?.let { return it.ifEmpty { null } }
        val home = names(m.home)
        val away = names(m.away)
        if (home.isEmpty() || away.isEmpty()) return null
        val url = magenta.boardPicture(home, away, m.startMs) ?: tsdbPicture(m, home, away)
        known[m.key] = url.orEmpty() to System.currentTimeMillis()
        return url
    }

    private fun names(c: Competitor?): List<String> =
        listOfNotNull(c?.team?.name, c?.team?.shortName, c?.team?.abbreviation).filter { it.isNotBlank() }

    private suspend fun tsdbPicture(m: Match, home: List<String>, away: List<String>): String? {
        val league = LEAGUES[m.leagueSlug] ?: return null
        val events = eventsOn(league, utcDay(m.startMs)) ?: return null
        return SportPosterMatch.pick(events, home, away)
    }

    /** The league's events on [day] (UTC "yyyy-MM-dd"): memory, then disk, then one request. */
    private suspend fun eventsOn(league: Int, day: String): JSONArray? = withContext(Dispatchers.IO) {
        val key = "$league@$day"
        days[key]?.let { return@withContext it }
        val file = File(File(context.cacheDir, "sport-posters").apply { mkdirs() }, "$key.json")
        runCatching { if (System.currentTimeMillis() - file.lastModified() < DAY_TTL_MS) JSONArray(file.readText()) else null }
            .getOrNull()?.let { days[key] = it; return@withContext it }
        tsdbLock.withLock {
            days[key]?.let { return@withLock it }
            if (System.currentTimeMillis() < pausedUntil) return@withLock null
            val wait = lastCall + MIN_GAP_MS - System.currentTimeMillis()
            if (wait > 0) delay(wait)
            lastCall = System.currentTimeMillis()
            runCatching {
                val request = Request.Builder().url("$BASE/eventsday.php?d=$day&l=$league").header("User-Agent", "MayoTV").build()
                http.newCall(request).execute().use { r ->
                    if (r.code == 429) { pausedUntil = System.currentTimeMillis() + PAUSE_MS; return@use null }
                    if (!r.isSuccessful) return@use null
                    val list = JSONObject(r.body?.string().orEmpty()).optJSONArray("events") ?: JSONArray()
                    runCatching { file.writeText(list.toString()) }
                    days[key] = list
                    list
                }
            }.getOrNull()
        }
    }

    private fun utcDay(ms: Long): String =
        SimpleDateFormat("yyyy-MM-dd", Locale.US).apply { timeZone = TimeZone.getTimeZone("UTC") }.format(ms)

    private companion object {
        const val BASE = "https://www.thesportsdb.com/api/v1/json/123"
        const val NONE_TTL_MS = 30 * 60_000L
        const val DAY_TTL_MS = 6 * 60 * 60_000L
        const val MIN_GAP_MS = 4_000L
        const val PAUSE_MS = 10 * 60_000L

        /** ESPN's league slugs → TheSportsDB's league ids (checked against lookupleague, 7 Nov 2026). */
        val LEAGUES = mapOf(
            "ger.1" to 4331, "eng.1" to 4328, "esp.1" to 4335, "ita.1" to 4332,
            "uefa.champions" to 4480, "uefa.europa" to 4481, "uefa.nations" to 4490,
            "uefa.euro" to 4502, "fifa.world" to 4429, "fifa.friendly" to 4562,
        )
    }
}

/** Which of TheSportsDB's events is the match: its home and away team named as ours, the one with a picture. */
internal object SportPosterMatch {
    fun pick(events: JSONArray, home: List<String>, away: List<String>): String? {
        for (i in 0 until events.length()) {
            val e = events.optJSONObject(i) ?: continue
            if (!MagentaNames.namesTeam(home, e.optString("strHomeTeam")) || !MagentaNames.namesTeam(away, e.optString("strAwayTeam"))) continue
            val thumb = e.optString("strThumb").takeIf { it.startsWith("http") } ?: continue
            return thumb.replaceFirst("http://", "https://")
        }
        return null
    }
}

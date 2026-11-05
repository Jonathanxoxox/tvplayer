package tv.own.owntv.features.home

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
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

    /** One call to the guide, signing in as a guest first (again, when the session is old or refused). */
    private fun post(path: String, body: JSONObject, retry: Boolean = true): JSONObject? {
        if (token == null || System.currentTimeMillis() - sessionAt > SESSION_TTL_MS) authenticate() ?: return null
        val request = Request.Builder()
            .url(BASE + path)
            .header("X_CSRFTOKEN", token.orEmpty())
            .header("Cookie", cookies)
            .post(body.toString().toRequestBody(JSON))
            .build()
        val json = http.newCall(request).execute().use { r -> if (r.isSuccessful) r.body?.string() else null } ?: return null
        val o = JSONObject(json)
        // A session the guide no longer knows: sign in again once.
        if (o.optString("retcode", "0") != "0" && retry) {
            token = null
            return post(path, body, retry = false)
        }
        return o
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
        /** The guide's start times differ from the provider's by a few minutes now and then. */
        const val WINDOW_MS = 20 * 60_000L
    }
}

/** Matching the playlist's channel names to the guide's, and picking a programme's picture. */
internal object MagentaNames {
    /** Words that say nothing about which channel it is: country tags, quality, codec. */
    private val noise = setOf("de", "ger", "germany", "hd", "fhd", "uhd", "sd", "4k", "hevc", "h265", "raw", "50fps", "tv")
    private val utcFormat = ThreadLocal.withInitial {
        SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.US).apply { timeZone = TimeZone.getTimeZone("UTC") }
    }

    /** "DE: SAT.1 HD" and "SAT.1" → "sat 1"; "Das Erste HD" → "das erste"; "Romance TV HD" → "romance". */
    fun key(name: String): String =
        ProgrammeTitles.key(name).split(' ').filter { it.isNotEmpty() && it !in noise }.joinToString(" ")

    /** The guide's channel list → key → channel id; the first of two with the same key wins. */
    fun index(list: JSONArray): Map<String, String> {
        val out = LinkedHashMap<String, String>()
        for (i in 0 until list.length()) {
            val c = list.optJSONObject(i) ?: continue
            val id = c.optString("contentId")
            val k = key(c.optString("name"))
            if (id.isNotBlank() && k.isNotEmpty() && k !in out) out[k] = id
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
            val start = runCatching { utcFormat.get()!!.parse(p.optString("starttime").take(19))?.time }.getOrNull() ?: continue
            val gap = kotlin.math.abs(start - startMs)
            if (gap < bestGap) { best = p; bestGap = gap }
        }
        if (best == null || bestGap > 20 * 60_000L) return null
        val pics = best.optJSONArray("pictures") ?: return null
        val all = (0 until pics.length()).mapNotNull { pics.optJSONObject(it) }
        val pick = listOf("17", "18").firstNotNullOfOrNull { t -> all.firstOrNull { it.optString("imageType") == t } } ?: all.firstOrNull()
        return pick?.optString("href")?.ifBlank { null }?.replaceFirst("http://", "https://")
    }
}

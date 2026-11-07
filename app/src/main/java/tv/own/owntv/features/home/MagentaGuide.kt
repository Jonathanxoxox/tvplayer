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

    /**
     * Today's picks on [channels] from [fromMs] to [toMs]: MagentaTV's tips and highlights, on the channels
     * in [docsOn] (arte) their documentaries, and on those in [filmsOn] (Sky Cinema …) their films. One call
     * per channel; empty when the guide cannot be asked.
     */
    suspend fun picks(
        channels: List<tv.own.owntv.core.database.entity.ChannelEntity>,
        docsOn: Set<Long>,
        fromMs: Long,
        toMs: Long,
        filmsOn: Set<Long> = emptySet(),
    ): List<GuidePick> = withContext(Dispatchers.IO) {
        runCatching {
            lock.withLock {
                channels.distinctBy { it.id }.flatMap { ch ->
                    val id = channelId(ch.name) ?: return@flatMap emptyList()
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
                    // Season, episode and year are asked for too; should the guide ever refuse them, it is asked
                    // again with the fields it has always answered, and from then on only with those.
                    val list = (if (extraFields) ask(PICK_FIELDS + PICK_EXTRA_FIELDS) else null)
                        ?: ask(PICK_FIELDS)?.also { if (extraFields) extraFields = false }
                        ?: return@flatMap emptyList()
                    MagentaNames.picks(list, ch, docs = ch.id in docsOn, films = ch.id in filmsOn)
                }
            }
        }.getOrDefault(emptyList())
    }

    /** Off once the guide has refused [PICK_EXTRA_FIELDS] and answered without them. */
    @Volatile private var extraFields = true

    /** Match key → picture URL, or "" when the sports channels were all asked and none shows it. */
    private val matchPictures = ConcurrentHashMap<String, String>()

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
                            .put("properties", JSONArray().put(JSONObject().put("include", "starttime,name,pictures").put("name", "playbill")))
                            .put("type", 2)
                            .put("begintime", utc(kickoffMs - MatchPictures.BEFORE_MS))
                            .put("channelid", id)
                            .put("endtime", utc(kickoffMs + MatchPictures.AFTER_MS))
                        val list = post("PlayBillList", body)?.optJSONArray("playbilllist")
                        if (list == null) { answered = false; continue }
                        found = MagentaNames.matchPicture(list, home, away, kickoffMs) ?: continue
                        break
                    }
                    // Remembered only when every channel answered: a network hiccup asks again next time.
                    if (found != null || answered) matchPictures[key] = found.orEmpty()
                    found
                }
            }.getOrNull()
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
        const val PICK_FIELDS = "name,starttime,endtime,tipType,tipCategory,genres,pictures,introduce"
        /** What tells a film from an episode, and its year. */
        const val PICK_EXTRA_FIELDS = ",seasonNO,sitcomNO,seriesID,producedate"
    }
}

/** How far around the kick-off a match's broadcast is looked for (pre-match shows start up to an hour early). */
internal object MatchPictures {
    const val BEFORE_MS = 90 * 60_000L
    const val AFTER_MS = 20 * 60_000L
}

/** Matching the playlist's channel names to the guide's, and picking a programme's picture. */
internal object MagentaNames {
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

    /** Lower case, umlauts folded, so "Mönchengladbach" and "M'gladbach" meet on "gladbach". */
    private fun fold(s: String): String = s.lowercase(Locale.ROOT)
        .replace("ä", "a").replace("ö", "o").replace("ü", "u").replace("ß", "ss")

    /** A team's long name parts (4+ letters) to find inside a title, and its short codes as whole words. */
    private fun teamMatches(names: List<String>, title: String, words: Set<String>): Boolean {
        val parts = names.flatMap { fold(it).split(' ', '-', '.', '\'', '/').filter { p -> p.length >= 4 } }
        val codes = names.map { fold(it).trim() }.filter { it.length in 2..4 && ' ' !in it }
        return parts.any { it in title } || codes.any { it in words }
    }

    /**
     * The picture of the programme that shows the match: both teams named, starting from an hour and a half
     * before to 20 minutes after [kickoffMs]; the live broadcast before a countdown, conference or highlights.
     */
    fun matchPicture(playbills: JSONArray, home: List<String>, away: List<String>, kickoffMs: Long): String? {
        if (home.isEmpty() || away.isEmpty()) return null
        var best: JSONObject? = null
        var bestScore = Long.MAX_VALUE
        for (i in 0 until playbills.length()) {
            val p = playbills.optJSONObject(i) ?: continue
            val title = fold(p.optString("name"))
            val words = title.split(Regex("[^a-z0-9]+")).filter { it.isNotEmpty() }.toSet()
            if (!teamMatches(home, title, words) || !teamMatches(away, title, words)) continue
            val start = runCatching { utcFormat.get()!!.parse(p.optString("starttime").take(19))?.time }.getOrNull() ?: continue
            if (start < kickoffMs - MatchPictures.BEFORE_MS || start > kickoffMs + MatchPictures.AFTER_MS) continue
            val aside = listOf("countdown", "fantasy", "konferenz", "highlights", "vorbericht", "analyse").any { it in title }
            val score = kotlin.math.abs(start - kickoffMs) + if (aside) 6 * 60 * 60_000L else 0L
            if (score < bestScore) { best = p; bestScore = score }
        }
        return best?.let(::pictureOf)
    }

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
        return pictureOf(best)
    }

    /** A programme's 16:9 picture (type 17), else its wide banner (18), else any; over https. */
    private fun pictureOf(p: JSONObject): String? {
        val pics = p.optJSONArray("pictures") ?: return null
        val all = (0 until pics.length()).mapNotNull { pics.optJSONObject(it) }
        val pick = listOf("17", "18").firstNotNullOfOrNull { t -> all.firstOrNull { it.optString("imageType") == t } } ?: all.firstOrNull()
        return pick?.optString("href")?.ifBlank { null }?.replaceFirst("http://", "https://")
    }

    private fun startOf(p: JSONObject, field: String): Long? =
        runCatching { utcFormat.get()!!.parse(p.optString(field).take(19))?.time }.getOrNull()

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
    ): List<GuidePick> {
        val out = ArrayList<GuidePick>()
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
            out += GuidePick(
                channel = channel,
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
                premiere = isFilm && (PREMIERE_WORDS.any { it in text } || "premiere" in key(channel.name)),
                year = p.optString("producedate").take(4).toIntOrNull()?.takeIf { it in 1900..2100 },
                // "Thriller" rather than "Spielfilm": the kind of film, when the guide says more than that it is one.
                genre = genreList.firstOrNull { g -> filmWords.none { it == g.lowercase() } } ?: genreList.firstOrNull(),
            )
        }
        return out
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

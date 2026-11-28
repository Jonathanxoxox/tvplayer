package tv.own.owntv.features.search

import android.content.Context
import android.util.Log
import okhttp3.OkHttpClient
import org.json.JSONArray
import org.json.JSONObject
import tv.own.owntv.R
import tv.own.owntv.core.content.SearchResults
import tv.own.owntv.core.live.ProviderTags
import tv.own.owntv.features.keys.ApiKeys
import tv.own.owntv.features.search.JevClient.Companion.choice

/**
 * What a spoken search meant. The recogniser on the box writes down a few guesses ("ha es vau gegen
 * schalke", "hsv gegen schalke"…) and often mishears names. Code looks the guesses and their words up
 * in the playlist (channels, programmes, movies, series); Jev then picks, in one request, which of the
 * guesses or found names the person wanted to search for. Jev only picks: the text that ends up in
 * the field is always a guess or a real name from the playlist.
 *
 * Without a TypeSafe key, or when nothing in the playlist sounds alike, the best guess is used.
 * The question's wording lives in res/raw/jev_voice_search.json.
 */
class VoiceQuery(private val context: Context, okHttp: OkHttpClient, keys: ApiKeys) {

    private val jev = JevClient(okHttp) { keys.typeSafe.value }
    private val prompt: JSONObject by lazy {
        JSONObject(context.resources.openRawResource(R.raw.jev_voice_search).bufferedReader().use { it.readText() })
    }

    /** Described to Jev by the matching entry of the JSON's "kinds". */
    private enum class Kind { HEARD, CHANNEL, PROGRAMME, MOVIE, SERIES }

    private val kindLabels: Map<Kind, String> by lazy {
        val labels = prompt.getJSONObject(KEY_KINDS)
        Kind.entries.associateWith { labels.optString(it.name.lowercase(), it.name) }
    }

    /** Words that say how to search, not what ("zeig mir"): no lookup of their own. */
    private val filler: Set<String> by lazy {
        val list = prompt.getJSONArray(KEY_FILLER)
        (0 until list.length()).map { list.getString(it) }.toSet()
    }

    private class Option(val text: String, val kind: Kind)

    /** [heard]: the recogniser's guesses, best first (never empty). [search]: the app's own search. */
    suspend fun resolve(heard: List<String>, search: suspend (String) -> SearchResults): String {
        val best = heard.first()
        // A whole sentence is a description ("ein gruseliger Film mit Hütte im Wald"): it stays as said,
        // for the search by description, instead of being swapped for a name that shares a word.
        if (best.split(' ').count { it.isNotBlank() } >= SENTENCE_WORDS) return best
        val options = LinkedHashMap<String, Option>()
        fun add(text: String, kind: Kind) {
            val clean = text.trim()
            if (clean.isNotEmpty() && options.size < MAX_OPTIONS) options.putIfAbsent(clean.lowercase(), Option(clean, kind))
        }
        heard.forEach { add(it, Kind.HEARD) }

        // Whole guesses first, then their single words: "zeig mir sky sport" finds nothing as a whole.
        val lookups = (heard.take(MAX_GUESS_LOOKUPS) + words(heard)).distinct()
        val found = ArrayList<Option>()
        for (q in lookups) {
            val r = runCatching { search(q) }.getOrNull() ?: continue
            r.channels.take(PER_KIND).forEach { found += Option(ProviderTags.parse(it.channel.name).name, Kind.CHANNEL) }
            r.programmes.take(PER_KIND).forEach { found += Option(it.programme.title, Kind.PROGRAMME) }
            r.movies.take(PER_KIND).forEach { found += Option(it.name, Kind.MOVIE) }
            r.series.take(PER_KIND).forEach { found += Option(it.name, Kind.SERIES) }
        }
        // A name heard exactly as the playlist writes it needs no judging.
        found.firstOrNull { it.text.equals(best, ignoreCase = true) }?.let { return it.text }
        found.forEach { add(it.text, it.kind) }
        if (!jev.available || options.size < 2) return best

        val list = options.values.toList()
        val criteria = JSONObject()
        list.forEachIndexed { i, o -> criteria.put(OPTION + i, describe(o)) }
        val question = JSONObject(prompt.getJSONObject(Q_MEANT).toString()).put(KEY_CRITERIA, criteria)
        val state = JSONObject()
            .put(KEY_HEARD, JSONArray(heard))
            .put(KEY_OPTIONS, JSONArray(list.map { describe(it) }))
        val answer = jev.ask(state, JSONObject().put(Q_MEANT, question)) ?: return best
        val picked = answer.choice(Q_MEANT)?.removePrefix(OPTION)?.toIntOrNull()?.let { list.getOrNull(it) } ?: return best
        Log.i(TAG, "heard=$heard picked=${describe(picked)}")
        return picked.text
    }

    /** `"Sky Sport Bundesliga" (a live TV channel in the app)`, as Jev reads an option. */
    private fun describe(o: Option): String = "\"${o.text}\" (${kindLabels.getValue(o.kind)})"

    /** The guesses' words worth a lookup on their own, longest first. */
    private fun words(heard: List<String>): List<String> = heard.take(MAX_GUESS_LOOKUPS)
        .flatMap { it.split(' ') }
        .map { it.trim() }
        .filter { it.length >= MIN_WORD && it.lowercase() !in filler }
        .distinctBy { it.lowercase() }
        .sortedByDescending { it.length }
        .take(MAX_WORD_LOOKUPS)

    private companion object {
        const val TAG = "VoiceQuery"
        const val Q_MEANT = "meant"
        const val OPTION = "o"
        const val MAX_OPTIONS = 24
        const val MAX_GUESS_LOOKUPS = 3
        const val MAX_WORD_LOOKUPS = 6
        const val PER_KIND = 3
        const val MIN_WORD = 3
        const val SENTENCE_WORDS = 5
        const val KEY_KINDS = "kinds"
        const val KEY_FILLER = "filler"
        const val KEY_CRITERIA = "criteria"
        const val KEY_HEARD = "heard"
        const val KEY_OPTIONS = "options"
    }
}

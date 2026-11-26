package tv.own.owntv.features.shell.components

import android.util.Log
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONObject
import org.koin.compose.koinInject
import tv.own.owntv.core.settings.SettingsRepository
import tv.own.owntv.features.keys.ApiKeys
import java.util.Locale
import java.util.concurrent.TimeUnit

/**
 * Where a title streams legally in the viewer's country ("Netflix", "Prime Video"), from TMDB's
 * watch-provider list (data by JustWatch). Only subscription, free and with-ads offers; renting and
 * buying are left out. Asked once per title and session.
 */
class WatchProviders(okHttp: OkHttpClient, private val settings: SettingsRepository) {

    private val http = okHttp.newBuilder().callTimeout(10, TimeUnit.SECONDS).build()
    private val cache = HashMap<String, List<String>>()

    /** [type] is TMDB's "movie" or "tv". Empty when nothing is known or TMDB is off. */
    suspend fun streaming(type: String, tmdbId: Int): List<String> {
        val id = "$type/$tmdbId"
        synchronized(cache) { cache[id] }?.let { return it }
        val cfg = settings.metadataConfig()
        // The user's own TMDB key, read at each call: none = no streaming services shown.
        val key = ApiKeys.sanitize(cfg.tmdbApiKey)
        if (!cfg.enabled || key.isEmpty()) return emptyList()
        val region = cfg.resolvedLanguage.substringAfter('-', "").takeIf { it.length == 2 }
            ?: Locale.getDefault().country.takeIf { it.length == 2 }
            ?: DEFAULT_REGION
        val names = withContext(Dispatchers.IO) {
            runCatching {
                val request = Request.Builder().url("$TMDB_BASE/3/$id/watch/providers?api_key=$key").build()
                http.newCall(request).execute().use { r -> if (r.isSuccessful) parse(r.body.string(), region.uppercase(Locale.ROOT)) else null }
            }.onFailure { Log.w(TAG, "TMDB failed: ${it.message}") }.getOrNull()
        } ?: return emptyList()
        synchronized(cache) { cache[id] = names }
        return names
    }

    private fun parse(body: String, region: String): List<String> {
        val country = JSONObject(body).optJSONObject("results")?.optJSONObject(region) ?: return emptyList()
        return OFFERS.flatMap { offer ->
            val list = country.optJSONArray(offer) ?: return@flatMap emptyList()
            (0 until list.length()).mapNotNull { i ->
                list.optJSONObject(i)?.let { it.optInt("display_priority", Int.MAX_VALUE) to it.optString("provider_name") }
            }.sortedBy { it.first }.map { it.second }
        }.filter { it.isNotBlank() }.distinct().take(MAX_NAMES)
    }

    companion object {
        private const val TAG = "WatchProviders"
        private const val TMDB_BASE = "https://api.themoviedb.org"
        private const val DEFAULT_REGION = "DE"
        private const val MAX_NAMES = 6
        private val OFFERS = listOf("flatrate", "free", "ads")
    }
}

/** The streaming services for a details page, filled in once TMDB answers. */
@Composable
internal fun rememberWatchProviders(type: String?, tmdbId: Int?): List<String> {
    val providers: WatchProviders = koinInject()
    val names by produceState(emptyList<String>(), type, tmdbId) {
        value = if (type == null || tmdbId == null || tmdbId <= 0) emptyList() else providers.streaming(type, tmdbId)
    }
    return names
}

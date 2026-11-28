package tv.own.owntv.features.keys

import android.content.Context
import android.content.SharedPreferences
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import tv.own.owntv.core.settings.SettingsRepository

/**
 * The outside services a user can bring a key of their own for. Each one is optional: without its
 * key the app works and only that feature is missing.
 */
enum class ApiService {
    /** Posters, backdrops, plot and cast for films and series (themoviedb.org). */
    TMDB,

    /** Voice search: speech to text (ElevenLabs Scribe). */
    ELEVENLABS,

    /** Search by description (TypeSafe's Jev). */
    TYPESAFE,
}

/**
 * The one place for the user's own service keys (a Koin single).
 *
 * - TMDB lives in core's [SettingsRepository] (it is part of core's metadata config and its backups);
 *   this store reads and writes it there.
 * - ElevenLabs and TypeSafe live in the app-private preference file `mayo_keys` (MODE_PRIVATE, the app
 *   has `allowBackup=false`).
 *
 * Keys are secrets: never log them, never put them in a crash report or a URL that is logged, and
 * show them masked ([mask]). Every write goes through [sanitize]; a blank value clears the key.
 *
 * The [tmdb] flow starts empty until DataStore has been read; for a decision at a single moment use
 * [current], which reads the stored value.
 */
class ApiKeys(
    context: Context,
    private val settings: SettingsRepository,
    private val scope: CoroutineScope,
) {
    private val appContext = context.applicationContext
    private val prefs: SharedPreferences by lazy { appContext.getSharedPreferences(FILE, Context.MODE_PRIVATE) }

    private val elevenLabsState = MutableStateFlow(readLocal(ApiService.ELEVENLABS))
    private val typeSafeState = MutableStateFlow(readLocal(ApiService.TYPESAFE))

    /** The TMDB key (core's setting), sanitised. Empty = no key. */
    val tmdb: StateFlow<String> =
        settings.tmdbApiKey.map(::sanitize).stateIn(scope, SharingStarted.Eagerly, "")

    /** The ElevenLabs key. Empty = no key (voice search uses the box's own recogniser, if any). */
    val elevenLabs: StateFlow<String> = elevenLabsState.asStateFlow()

    /** The TypeSafe key. Empty = no key (search by description is off). */
    val typeSafe: StateFlow<String> = typeSafeState.asStateFlow()

    /** Which services currently have a key. */
    val configured: StateFlow<Set<ApiService>> =
        combine(tmdb, elevenLabs, typeSafe) { t, e, s ->
            buildSet {
                if (t.isNotEmpty()) add(ApiService.TMDB)
                if (e.isNotEmpty()) add(ApiService.ELEVENLABS)
                if (s.isNotEmpty()) add(ApiService.TYPESAFE)
            }
        }.stateIn(scope, SharingStarted.Eagerly, emptySet())

    /** The key of [service] as a flow. */
    fun key(service: ApiService): StateFlow<String> = when (service) {
        ApiService.TMDB -> tmdb
        ApiService.ELEVENLABS -> elevenLabs
        ApiService.TYPESAFE -> typeSafe
    }

    /** Whether [service] has a key right now (see the class note on TMDB's first read). */
    fun isSet(service: ApiService): Boolean = key(service).value.isNotEmpty()

    /** Whether [service] has a key, as a flow (for "needs a key" hints where the feature would be). */
    fun isSetFlow(service: ApiService): Flow<Boolean> = key(service).map { it.isNotEmpty() }

    /** The stored key of [service], read from storage (TMDB without waiting for [tmdb] to warm up). */
    suspend fun current(service: ApiService): String = when (service) {
        ApiService.TMDB -> sanitize(settings.currentTmdbApiKey())
        else -> key(service).value
    }

    /**
     * Stores [raw] for [service] after [sanitize] and returns what was stored. A value that is blank
     * after sanitising clears the key.
     */
    suspend fun set(service: ApiService, raw: String): String {
        val clean = sanitize(raw)
        when (service) {
            ApiService.TMDB -> settings.setTmdbApiKey(clean)
            ApiService.ELEVENLABS, ApiService.TYPESAFE -> withContext(Dispatchers.IO) {
                prefs.edit().apply { if (clean.isEmpty()) remove(service.prefKey) else putString(service.prefKey, clean) }.commit()
            }
        }
        when (service) {
            ApiService.TMDB -> Unit // arrives through settings.tmdbApiKey
            ApiService.ELEVENLABS -> elevenLabsState.value = clean
            ApiService.TYPESAFE -> typeSafeState.value = clean
        }
        return clean
    }

    /** [set] without waiting: for UI callbacks. */
    fun apply(service: ApiService, raw: String) {
        scope.launch { set(service, raw) }
    }

    /** Removes the key of [service]. */
    suspend fun clear(service: ApiService) {
        set(service, "")
    }

    private fun readLocal(service: ApiService): String =
        runCatching { sanitize(prefs.getString(service.prefKey, null).orEmpty()) }.getOrDefault("")

    private val ApiService.prefKey: String
        get() = when (this) {
            ApiService.TMDB -> "tmdb" // never stored here; TMDB lives in core's settings
            ApiService.ELEVENLABS -> "elevenlabs"
            ApiService.TYPESAFE -> "typesafe"
        }

    companion object {
        private const val FILE = "mayo_keys"

        /** Longer than any of the three services' keys or tokens; anything longer is not a key. */
        const val MAX_LENGTH = 512

        /**
         * A key as typed, pasted or sent from a phone, cleaned: surrounding quotes and brackets go,
         * every whitespace (a pasted line break included) goes, and only the characters the three
         * services use in keys remain (ASCII letters and digits, `_`, `-`, `.`). Capped at [MAX_LENGTH].
         */
        fun sanitize(raw: String): String =
            raw.filter { it in 'A'..'Z' || it in 'a'..'z' || it in '0'..'9' || it in "_-." }.take(MAX_LENGTH)

        /** A key for display: its last four characters after a few dots ("••••c3f9"); empty stays empty. */
        fun mask(key: String): String = when {
            key.isEmpty() -> ""
            key.length <= 4 -> "••••"
            else -> "••••" + key.takeLast(4)
        }
    }
}

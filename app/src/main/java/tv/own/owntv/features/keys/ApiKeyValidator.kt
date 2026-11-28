package tv.own.owntv.features.keys

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONObject
import java.util.concurrent.TimeUnit

/** What a key check found. */
sealed interface KeyCheck {
    /** The service accepted the key. */
    data object Valid : KeyCheck

    /** The service rejected the key (or it was empty). */
    data object Invalid : KeyCheck

    /**
     * The service could not be asked. [httpCode] is null when no answer came at all (no network, no
     * DNS, a timeout), and the HTTP status when the service answered with something that says nothing
     * about the key (a server error, rate limiting).
     */
    data class Unreachable(val httpCode: Int? = null) : KeyCheck

    /** There is no way to check this service's key; it is stored as entered. */
    data object Unknown : KeyCheck
}

/**
 * Asks each service whether a key works, with one cheap read-only request that spends no credits (a
 * Koin single). Never logs the key or a URL that carries it, and never puts the key in a URL except
 * where the service requires it (TMDB's v3 `api_key`, which is how the whole app sends it).
 */
class ApiKeyValidator(okHttp: OkHttpClient) {

    private val http = okHttp.newBuilder().callTimeout(TIMEOUT_S, TimeUnit.SECONDS).build()

    /** Checks [key] (sanitised first) against [service]. */
    suspend fun check(service: ApiService, key: String): KeyCheck = when (service) {
        ApiService.TMDB -> checkTmdb(key)
        ApiService.ELEVENLABS -> checkElevenLabs(key)
        ApiService.TYPESAFE -> checkTypeSafe(key)
    }

    /**
     * TMDB: `GET /3/configuration?api_key=…`. Metadata, catalogs and search all send the key as the v3
     * `api_key` parameter, so that is exactly what is checked: a v4 read access token is rejected here
     * (see [looksLikeTmdbReadToken] to tell the user which of the two TMDB values to copy).
     */
    suspend fun checkTmdb(key: String): KeyCheck {
        val clean = ApiKeys.sanitize(key)
        if (clean.isEmpty()) return KeyCheck.Invalid
        val url = TMDB_CONFIGURATION.toHttpUrl().newBuilder().addQueryParameter(TMDB_KEY_PARAM, clean).build()
        val answer = call(Request.Builder().url(url).get().build()) ?: return KeyCheck.Unreachable()
        return tmdbResult(answer.code)
    }

    /**
     * ElevenLabs: `GET /v1/user` with `xi-api-key`. Reading the account costs no credits. See
     * [elevenLabsResult] for how a 401 is told apart.
     */
    suspend fun checkElevenLabs(key: String): KeyCheck {
        val clean = ApiKeys.sanitize(key)
        if (clean.isEmpty()) return KeyCheck.Invalid
        val request = Request.Builder().url(ELEVENLABS_USER).header(ELEVENLABS_HEADER, clean).get().build()
        val answer = call(request) ?: return KeyCheck.Unreachable()
        return elevenLabsResult(answer.code, answer.body)
    }

    /**
     * TypeSafe: `GET /v1/models` with the Bearer key, the account's model list. It needs a valid key
     * and runs no question, so it spends no credits.
     */
    suspend fun checkTypeSafe(key: String): KeyCheck {
        val clean = ApiKeys.sanitize(key)
        if (clean.isEmpty()) return KeyCheck.Invalid
        val request = Request.Builder().url(TYPESAFE_MODELS).header(AUTHORIZATION, BEARER + clean).get().build()
        val answer = call(request) ?: return KeyCheck.Unreachable()
        return typeSafeResult(answer.code)
    }

    private class Answer(val code: Int, val body: String)

    /** One request; null when no answer came (no network, DNS, timeout). Failures are not logged: the URL may carry the key. */
    private suspend fun call(request: Request): Answer? = withContext(Dispatchers.IO) {
        runCatching {
            http.newCall(request).execute().use { Answer(it.code, it.body.string().take(MAX_BODY)) }
        }.getOrNull()
    }

    companion object {
        private const val TIMEOUT_S = 10L
        private const val MAX_BODY = 4_096
        private const val TMDB_CONFIGURATION = "https://api.themoviedb.org/3/configuration"
        private const val TMDB_KEY_PARAM = "api_key"
        private const val ELEVENLABS_USER = "https://api.elevenlabs.io/v1/user"
        private const val ELEVENLABS_HEADER = "xi-api-key"
        private const val TYPESAFE_MODELS = "https://api.typesafe.ai/v1/models"
        private const val AUTHORIZATION = "Authorization"
        private const val BEARER = "Bearer "
        private const val MISSING_PERMISSIONS = "missing_permissions"
        private const val JWT_PREFIX = "eyJ"
        private const val READ_TOKEN_MIN_LENGTH = 100

        /** TMDB's answer: 200 = valid, 401 = invalid key, anything else says nothing about the key. */
        internal fun tmdbResult(code: Int): KeyCheck = when (code) {
            200 -> KeyCheck.Valid
            401 -> KeyCheck.Invalid
            else -> KeyCheck.Unreachable(code)
        }

        /**
         * ElevenLabs's answer. 200 = valid. A 401/403 is told apart by `detail.status`:
         * `missing_permissions` is a real key restricted to other scopes (speech to text only, say),
         * which is enough for voice search; anything else (`invalid_api_key` …) is a wrong key.
         */
        internal fun elevenLabsResult(code: Int, body: String): KeyCheck = when (code) {
            200 -> KeyCheck.Valid
            401, 403 -> {
                val status = runCatching { JSONObject(body).optJSONObject("detail")?.optString("status") }.getOrNull()
                if (status == MISSING_PERMISSIONS) KeyCheck.Valid else KeyCheck.Invalid
            }
            else -> KeyCheck.Unreachable(code)
        }

        /** TypeSafe's answer: 200 = valid, 401/403 = wrong or missing key, anything else says nothing about the key. */
        internal fun typeSafeResult(code: Int): KeyCheck = when (code) {
            200 -> KeyCheck.Valid
            401, 403 -> KeyCheck.Invalid
            else -> KeyCheck.Unreachable(code)
        }

        /**
         * Whether [key] looks like TMDB's long "API read access token" (a JWT) rather than the 32
         * character "API key" the app needs: both sit on the same TMDB page and are easily mixed up.
         */
        fun looksLikeTmdbReadToken(key: String): Boolean {
            val clean = ApiKeys.sanitize(key)
            return clean.startsWith(JWT_PREFIX) && clean.count { it == '.' } == 2 && clean.length >= READ_TOKEN_MIN_LENGTH
        }
    }
}

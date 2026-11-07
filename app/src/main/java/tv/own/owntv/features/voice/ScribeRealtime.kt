package tv.own.owntv.features.voice

import android.annotation.SuppressLint
import android.content.Context
import android.media.AudioDeviceInfo
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import android.os.SystemClock
import android.util.Base64
import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import org.json.JSONObject
import tv.own.owntv.BuildConfig
import tv.own.owntv.R
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.Locale
import java.util.concurrent.TimeUnit

/**
 * Spoken search the way TizenTube does it (Jonathan, 7 Nov 2026: "Der Soldat James Ryan" arrived as
 * "Tatort James Ryan"): the microphone streams to ElevenLabs Scribe v2 Realtime while the person speaks,
 * the words show as they come, and ElevenLabs hears when the sentence is over. No model on the box to load
 * first, and nothing is cut: sound recorded before the socket is open is kept and sent once it is.
 *
 * The remote's microphone reaches the app only some time after recording starts (the box bridges it in,
 * about 0.6 s on the Nokia box): [listen]'s `onReady` fires when it does, so "Speak now" is not shown
 * before anyone can hear it. Blank key = off (the on-box recogniser stands in, see [OfflineSpeech]).
 */
class ScribeRealtime(private val context: Context, okHttp: OkHttpClient) {

    private val http = okHttp.newBuilder().readTimeout(0, TimeUnit.MILLISECONDS).pingInterval(10, TimeUnit.SECONDS).build()
    private val apiKey = BuildConfig.ELEVENLABS_API_KEY

    val available: Boolean get() = apiKey.isNotBlank()

    private val keyterms: List<String> by lazy {
        runCatching {
            val list = JSONObject(context.resources.openRawResource(R.raw.voice_keyterms).bufferedReader().use { it.readText() })
                .getJSONArray(KEY_KEYTERMS)
            (0 until list.length()).map { list.getString(it) }.filter { it.length <= MAX_KEYTERM_LENGTH }.take(MAX_KEYTERMS)
        }.getOrDefault(emptyList())
    }

    /** What came back: the sentence, or why there is none. */
    sealed interface Outcome {
        data class Said(val text: String) : Outcome
        data object Nothing : Outcome
        data class Failed(val why: String) : Outcome
    }

    private sealed interface Event {
        data object Started : Event
        data class Partial(val text: String) : Event
        data class Committed(val text: String) : Event
        data class Error(val why: String) : Event
    }

    /**
     * Records until ElevenLabs commits the sentence, [stopRequested] turns true (then what was said so far
     * is committed) or nobody speaks. [onPartial] gets the words so far; [onReady] fires once, when the
     * microphone's sound really arrives. Throws [IllegalStateException] when the microphone cannot be opened.
     */
    @SuppressLint("MissingPermission") // The caller asks for RECORD_AUDIO first.
    suspend fun listen(stopRequested: () -> Boolean, onReady: () -> Unit, onPartial: (String) -> Unit): Outcome = withContext(Dispatchers.IO) {
        val events = Channel<Event>(Channel.UNLIMITED)
        val socket = http.newWebSocket(request(), object : WebSocketListener() {
            override fun onMessage(webSocket: WebSocket, text: String) {
                val o = runCatching { JSONObject(text) }.getOrNull() ?: return
                val type = o.optString(F_TYPE)
                when {
                    type == T_STARTED -> events.trySend(Event.Started)
                    type == T_PARTIAL -> events.trySend(Event.Partial(o.optString(F_TEXT)))
                    type.startsWith(T_COMMITTED) -> events.trySend(Event.Committed(o.optString(F_TEXT)))
                    type.contains(T_ERROR) || type.contains(T_EXCEEDED) -> events.trySend(Event.Error("$type ${o.optString(F_ERROR).take(200)}"))
                }
            }

            override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
                events.trySend(Event.Error("socket ${response?.code ?: -1} ${t.message}"))
            }

            override fun onClosed(webSocket: WebSocket, code: Int, reason: String) {
                events.trySend(Event.Error("closed $code $reason"))
            }
        })

        val minBuffer = AudioRecord.getMinBufferSize(SAMPLE_RATE, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT)
        val record = AudioRecord(
            MediaRecorder.AudioSource.VOICE_RECOGNITION, SAMPLE_RATE,
            AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT,
            maxOf(minBuffer, SAMPLE_RATE),
        )
        if (record.state != AudioRecord.STATE_INITIALIZED) {
            record.release()
            socket.cancel()
            throw IllegalStateException("microphone could not be opened")
        }
        val opened = SystemClock.elapsedRealtime()
        try {
            record.startRecording()
            if (record.recordingState != AudioRecord.RECORDSTATE_RECORDING) throw IllegalStateException("microphone is busy")
            val chunk = ShortArray(SAMPLE_RATE / 10) // 100 ms
            val bytes = ByteBuffer.allocate(chunk.size * 2).order(ByteOrder.LITTLE_ENDIAN)
            // Sound from before the session started: sent as soon as it has.
            val waiting = ArrayList<String>()
            var started = false
            var ready = false
            var readyAt = 0L
            var said = ""
            var committing = false
            var commitAt = 0L
            fun send(audio: String, commit: Boolean) {
                socket.send(
                    JSONObject().put(F_TYPE, T_AUDIO).put(F_AUDIO, audio).put(F_COMMIT, commit).put(F_RATE, SAMPLE_RATE).toString(),
                )
            }
            fun commit() {
                if (committing) return
                committing = true
                commitAt = SystemClock.elapsedRealtime()
                if (started) send("", true)
            }
            while (true) {
                ensureActive()
                // What the server said since the last chunk.
                while (true) {
                    when (val e = events.tryReceive().getOrNull() ?: break) {
                        Event.Started -> {
                            started = true
                            Log.i(TAG, "session after ${SystemClock.elapsedRealtime() - opened} ms, ${waiting.size} chunks waiting")
                            waiting.forEach { send(it, false) }
                            waiting.clear()
                            if (committing) send("", true)
                        }
                        is Event.Partial -> if (e.text.isNotBlank()) { said = e.text.trim(); onPartial(said) }
                        is Event.Committed -> {
                            val text = e.text.trim().trimEnd('.', '!', '?')
                            if (text.isNotEmpty()) {
                                Log.i(TAG, "said \"$text\" ${SystemClock.elapsedRealtime() - opened} ms after the mic opened")
                                return@withContext Outcome.Said(text)
                            }
                            // An empty segment (a cough, a pause before speaking): keep listening unless asked to stop.
                            if (committing) return@withContext Outcome.Nothing
                        }
                        is Event.Error -> {
                            Log.w(TAG, "realtime: ${e.why}")
                            return@withContext if (said.isNotEmpty()) Outcome.Said(said) else Outcome.Failed(e.why)
                        }
                    }
                }
                val n = record.read(chunk, 0, chunk.size)
                if (n < 0) throw IllegalStateException("microphone read failed: $n")
                val now = SystemClock.elapsedRealtime()
                if (n > 0) {
                    bytes.clear()
                    bytes.asShortBuffer().put(chunk, 0, n)
                    val audio = Base64.encodeToString(bytes.array(), 0, n * 2, Base64.NO_WRAP)
                    if (started) send(audio, false) else waiting += audio
                    if (!ready && (bridged(record) || now - opened > READY_FALLBACK_MS)) {
                        ready = true
                        readyAt = now
                        Log.i(TAG, "microphone ready after ${now - opened} ms (${record.routedDevice?.type})")
                        onReady()
                    }
                }
                when {
                    committing && now - commitAt > COMMIT_WAIT_MS ->
                        return@withContext if (said.isNotEmpty()) Outcome.Said(said) else Outcome.Nothing
                    committing -> Unit
                    stopRequested() -> commit()
                    ready && said.isEmpty() && now - readyAt > SILENCE_TIMEOUT_MS -> return@withContext Outcome.Nothing
                    now - opened > MAX_LISTEN_MS -> commit()
                }
            }
            @Suppress("UNREACHABLE_CODE")
            Outcome.Nothing
        } finally {
            runCatching { record.stop() }
            record.release()
            socket.close(NORMAL_CLOSE, null)
        }
    }

    private fun request(): Request {
        val url = URL.toHttpUrl().newBuilder()
            .addQueryParameter(P_MODEL, MODEL)
            .addQueryParameter(P_FORMAT, PCM_FORMAT)
            .addQueryParameter(P_COMMIT, COMMIT_VAD)
            .addQueryParameter(P_SILENCE, VAD_SILENCE_SECS)
            .addQueryParameter(P_LANGUAGE, language())
            .apply { keyterms.forEach { addQueryParameter(P_KEYTERMS, it) } }
            .build()
        return Request.Builder().url(url).header(H_KEY, apiKey).build()
    }

    /** The box has switched the recording over to the remote's microphone. */
    private fun bridged(record: AudioRecord): Boolean =
        record.routedDevice?.type.let { it != null && it != AudioDeviceInfo.TYPE_BUILTIN_MIC }

    private fun language(): String = if (Locale.getDefault().language == Locale.ENGLISH.language) Locale.ENGLISH.language else Locale.GERMAN.language

    private companion object {
        const val TAG = "ScribeRealtime"
        const val URL = "https://api.elevenlabs.io/v1/speech-to-text/realtime"
        const val MODEL = "scribe_v2_realtime"
        const val PCM_FORMAT = "pcm_16000"
        const val COMMIT_VAD = "vad"
        /** A short breath inside a title ("Der Soldat … James Ryan") is not the end of it. */
        const val VAD_SILENCE_SECS = "1.0"
        const val SAMPLE_RATE = 16_000
        const val H_KEY = "xi-api-key"
        const val P_MODEL = "model_id"
        const val P_FORMAT = "audio_format"
        const val P_COMMIT = "commit_strategy"
        const val P_SILENCE = "vad_silence_threshold_secs"
        const val P_LANGUAGE = "language_code"
        const val P_KEYTERMS = "keyterms"
        const val F_TYPE = "message_type"
        const val F_TEXT = "text"
        const val F_ERROR = "error"
        const val F_AUDIO = "audio_base_64"
        const val F_COMMIT = "commit"
        const val F_RATE = "sample_rate"
        const val T_STARTED = "session_started"
        const val T_PARTIAL = "partial_transcript"
        const val T_COMMITTED = "committed_transcript"
        const val T_AUDIO = "input_audio_chunk"
        const val T_ERROR = "error"
        const val T_EXCEEDED = "exceeded"
        const val KEY_KEYTERMS = "keyterms"
        const val MAX_KEYTERMS = 50
        const val MAX_KEYTERM_LENGTH = 20
        const val NORMAL_CLOSE = 1000
        /**
         * No switch to the remote's microphone after this long: say "Speak now" anyway (a box with a microphone
         * of its own never switches). The Nokia box switches after about 0.6 s; its empty built-in input hums
         * above silence before that, so sound alone does not tell.
         */
        const val READY_FALLBACK_MS = 1_200L
        /** Nothing said at all after "Speak now": stop. */
        const val SILENCE_TIMEOUT_MS = 8_000L
        /** However long the speaking, a search is one sentence. */
        const val MAX_LISTEN_MS = 15_000L
        /** After a commit, how long the final text may take. */
        const val COMMIT_WAIT_MS = 3_000L
    }
}

package tv.own.owntv.features.voice

import android.annotation.SuppressLint
import android.content.Context
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import android.os.SystemClock
import android.util.Log
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import org.json.JSONObject
import org.vosk.LibVosk
import org.vosk.LogLevel
import org.vosk.Model
import org.vosk.Recognizer
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.Locale
import java.util.zip.ZipInputStream

/**
 * Speech to text inside the app, the way TizenTube does it: the app opens the microphone itself
 * (16 kHz mono, the voice-recognition preset) instead of handing over to the box's voice search,
 * which Jonathan has switched off. TizenTube sends the sound to YouTube; here Vosk (Kaldi) turns it
 * into text on the box, so no account or key is needed.
 *
 * The language model (~45 MB) is not in the APK: the first mic press downloads it into the app's
 * files, after that it works offline. The loaded model stays in memory for a minute after the last
 * use, so a second search right after starts at once.
 */
object OfflineSpeech {

    /** A Vosk "small" model: the folder name inside its zip, and where to fetch it. */
    private class ModelSpec(val name: String) {
        val url get() = "https://alphacephei.com/vosk/models/$name.zip"
    }

    private val GERMAN = ModelSpec("vosk-model-small-de-0.15")
    private val ENGLISH = ModelSpec("vosk-model-small-en-us-0.15")

    private const val SAMPLE_RATE = 16_000
    private const val TAG = "OfflineSpeech"

    /** Nothing said at all after this long: stop listening. */
    private const val SILENCE_TIMEOUT_MS = 8_000L
    /** However long the speaking, a search is one sentence. */
    private const val MAX_LISTEN_MS = 15_000L
    private const val KEEP_LOADED_MS = 60_000L
    private const val ALTERNATIVES = 5

    private val lock = Mutex()
    private var loaded: Pair<String, Model>? = null
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var unloadJob: Job? = null

    init { runCatching { LibVosk.setLogLevel(LogLevel.WARNINGS) } }

    private fun spec(): ModelSpec = if (Locale.getDefault().language == "en") ENGLISH else GERMAN

    private fun modelDir(context: Context, spec: ModelSpec) = File(context.filesDir, "voice/${spec.name}")

    /** True when the first mic press still has to download the language model. */
    fun needsDownload(context: Context): Boolean = !File(modelDir(context, spec()), "conf").isDirectory

    /**
     * Loads the language model, downloading it first if needed; [progress] gets 0..100 while it
     * downloads. Throws [IOException] when the download fails (no internet).
     */
    suspend fun prepare(context: Context, progress: (Int) -> Unit): Model = lock.withLock {
        unloadJob?.cancel()
        val spec = spec()
        val current = loaded
        if (current != null && current.first == spec.name) return@withLock current.second
        current?.second?.close()
        loaded = null
        withContext(Dispatchers.IO) {
            val dir = modelDir(context, spec)
            if (!File(dir, "conf").isDirectory) download(context, spec, dir, progress)
            Model(dir.absolutePath).also { loaded = spec.name to it }
        }
    }

    /** After a search: frees the model a minute later unless the mic is pressed again. */
    fun release() {
        unloadJob?.cancel()
        unloadJob = scope.launch {
            delay(KEEP_LOADED_MS)
            lock.withLock { loaded?.second?.close(); loaded = null }
        }
    }

    private suspend fun download(context: Context, spec: ModelSpec, target: File, progress: (Int) -> Unit) {
        val root = File(context.filesDir, "voice").apply { mkdirs() }
        // Older models of other languages or versions go: one is plenty on a TV box.
        root.listFiles()?.filter { it.name != spec.name }?.forEach { it.deleteRecursively() }
        val zip = File(root, "${spec.name}.zip.part")
        val connection = (URL(spec.url).openConnection() as HttpURLConnection).apply {
            connectTimeout = 15_000
            readTimeout = 30_000
        }
        try {
            if (connection.responseCode != HttpURLConnection.HTTP_OK) throw IOException("HTTP ${connection.responseCode}")
            val total = connection.contentLengthLong
            connection.inputStream.use { input ->
                zip.outputStream().use { out ->
                    val buffer = ByteArray(64 * 1024)
                    var done = 0L
                    var shown = -1
                    while (true) {
                        currentCoroutineContext().ensureActive()
                        val n = input.read(buffer)
                        if (n < 0) break
                        out.write(buffer, 0, n)
                        done += n
                        val percent = if (total > 0) (done * 100 / total).toInt() else -1
                        if (percent != shown) { shown = percent; progress(percent.coerceIn(0, 100)) }
                    }
                }
            }
        } finally {
            connection.disconnect()
        }
        // Unpacked beside the target and renamed at the end, so a cut-off unpack never counts as done.
        val unpacking = File(root, "${spec.name}.unpacking").apply { deleteRecursively(); mkdirs() }
        ZipInputStream(zip.inputStream().buffered()).use { z ->
            while (true) {
                val entry = z.nextEntry ?: break
                // The zip holds one top folder named like the model; drop it.
                val inner = entry.name.substringAfter('/', "")
                if (inner.isEmpty()) continue
                val out = File(unpacking, inner)
                if (!out.canonicalPath.startsWith(unpacking.canonicalPath + File.separator)) continue
                if (entry.isDirectory) out.mkdirs() else { out.parentFile?.mkdirs(); out.outputStream().use { z.copyTo(it) } }
            }
        }
        zip.delete()
        target.deleteRecursively()
        if (!unpacking.renameTo(target)) throw IOException("could not move the voice model into place")
    }

    /** One spoken search: the model's guesses (best first, empty = nothing understood) and the sound. */
    class Heard(val guesses: List<String>, val pcm: ByteArray)

    /**
     * Listens until the speaker stops (or [stopRequested] turns true), showing what it hears so far
     * through [partial]. The recording comes back too (16 kHz mono 16-bit little-endian).
     * Throws [IllegalStateException] when the microphone cannot be opened.
     */
    @SuppressLint("MissingPermission") // The caller asks for RECORD_AUDIO first.
    suspend fun listen(model: Model, stopRequested: () -> Boolean, partial: (String) -> Unit): Heard = withContext(Dispatchers.IO) {
        val minBuffer = AudioRecord.getMinBufferSize(SAMPLE_RATE, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT)
        val record = AudioRecord(
            MediaRecorder.AudioSource.VOICE_RECOGNITION, SAMPLE_RATE,
            AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT,
            maxOf(minBuffer, SAMPLE_RATE), // half a second of 16-bit samples
        )
        if (record.state != AudioRecord.STATE_INITIALIZED) {
            record.release()
            throw IllegalStateException("microphone could not be opened")
        }
        try {
            Recognizer(model, SAMPLE_RATE.toFloat()).use { recognizer ->
                recognizer.setMaxAlternatives(ALTERNATIVES)
                record.startRecording()
                if (record.recordingState != AudioRecord.RECORDSTATE_RECORDING) throw IllegalStateException("microphone is busy")
                val chunk = ShortArray(SAMPLE_RATE / 10) // 100 ms
                val sound = ByteArrayOutputStream(SAMPLE_RATE * 2 * 4)
                val bytes = ByteBuffer.allocate(chunk.size * 2).order(ByteOrder.LITTLE_ENDIAN)
                val started = SystemClock.elapsedRealtime()
                var heardSomething = false
                while (true) {
                    ensureActive()
                    val n = record.read(chunk, 0, chunk.size)
                    if (n < 0) throw IllegalStateException("microphone read failed: $n")
                    if (n > 0) {
                        bytes.clear()
                        bytes.asShortBuffer().put(chunk, 0, n)
                        sound.write(bytes.array(), 0, n * 2)
                    }
                    if (n > 0 && recognizer.acceptWaveForm(chunk, n)) {
                        // A pause: the sentence is over, unless it was silence before speaking.
                        val said = alternatives(recognizer.result)
                        if (said.isNotEmpty()) return@withContext Heard(said, sound.toByteArray())
                    } else if (n > 0) {
                        val now = runCatching { JSONObject(recognizer.partialResult).optString("partial") }.getOrDefault("")
                        if (now.isNotBlank()) { heardSomething = true; partial(now) }
                    }
                    val elapsed = SystemClock.elapsedRealtime() - started
                    if (stopRequested() || elapsed > MAX_LISTEN_MS || (!heardSomething && elapsed > SILENCE_TIMEOUT_MS)) break
                }
                Heard(alternatives(recognizer.finalResult), sound.toByteArray())
            }
        } finally {
            runCatching { record.stop() }
            record.release()
        }
    }

    /** `{"alternatives":[{"text":…},…]}` with alternatives on, `{"text":…}` without. */
    private fun alternatives(json: String): List<String> = runCatching {
        val o = JSONObject(json)
        val list = o.optJSONArray("alternatives")
        val texts = if (list != null) (0 until list.length()).map { list.getJSONObject(it).optString("text") } else listOf(o.optString("text"))
        texts.map { it.trim() }.filter { it.isNotEmpty() }.distinct()
    }.onFailure { Log.w(TAG, "unreadable result: ${it.message}") }.getOrDefault(emptyList())
}

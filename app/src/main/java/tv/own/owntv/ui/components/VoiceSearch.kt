package tv.own.owntv.ui.components

import android.Manifest
import android.content.pm.PackageManager
import android.util.Log
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.annotation.StringRes
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext
import androidx.core.content.ContextCompat
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import org.koin.compose.koinInject
import tv.own.owntv.R
import tv.own.owntv.features.voice.OfflineSpeech
import tv.own.owntv.features.voice.ScribeRealtime
import java.io.IOException

/**
 * The microphone button beside a search field. Like TizenTube, the app records the microphone itself
 * and never hands over to the box's voice search (Jonathan has Google's switched off, which is why the
 * old hand-over did nothing). With an ElevenLabs key the sound streams to [ScribeRealtime] while the person
 * speaks: the words show as they come and go into the field as said. Without one, [OfflineSpeech] on the box
 * stands in (its language model downloaded on the first press). The microphone permission is asked for on
 * the first press.
 *
 * While it works, the field shows [heard] (the words so far) or [note] ("Speak now…", the download's
 * [noteArg] percent, or why nothing happened); both null = the field's own text.
 */
@Stable
class VoiceSearch internal constructor() {
    var listening by mutableStateOf(false)
        internal set
    /** Downloading, listening or working out what was meant: a second press cancels. */
    var busy by mutableStateOf(false)
        internal set
    @get:StringRes
    var note by mutableStateOf<Int?>(null)
        internal set
    var noteArg by mutableStateOf<Int?>(null)
        internal set
    var heard by mutableStateOf<String?>(null)
        internal set
    internal var launch: () -> Unit = {}
    fun start() = launch()
}

/**
 * [resolve] turns what the recogniser heard (best guess first) into the search text, e.g. Jev picking
 * the channel or title the speaker meant; without it, or when it takes too long, the best guess is used.
 */
@Composable
fun rememberVoiceSearch(
    onResult: (String) -> Unit,
    resolve: (suspend (List<String>) -> String)? = null,
): VoiceSearch {
    val context = LocalContext.current
    val result by rememberUpdatedState(onResult)
    val resolver by rememberUpdatedState(resolve)
    val voice = remember { VoiceSearch() }
    val realtime: ScribeRealtime = koinInject()
    val scope = rememberCoroutineScope()
    val job = remember { mutableStateOf<Job?>(null) }
    val stop = remember { mutableStateOf(false) }
    // A "why not" note goes after a few seconds; the working notes stay while it works.
    LaunchedEffect(voice.note, voice.busy) {
        if (voice.note != null && !voice.busy) { kotlinx.coroutines.delay(4_000); voice.note = null }
    }

    fun startListening() {
        stop.value = false
        job.value = scope.launch {
            voice.busy = true
            voice.heard = null
            try {
                if (realtime.available) {
                    // Listening from the press on; "Speak now" once the remote's microphone really delivers.
                    voice.note = R.string.voice_search_connecting
                    voice.listening = true
                    val outcome = realtime.listen(
                        stopRequested = { stop.value },
                        onReady = { scope.launch { if (voice.listening && voice.heard == null) voice.note = R.string.voice_search_listening } },
                        onPartial = { words -> scope.launch { if (voice.listening) voice.heard = words } },
                    )
                    voice.listening = false
                    voice.heard = null
                    // Scribe writes down what was said, names included: it goes into the field as said. The search
                    // itself finds titles, people and descriptions; swapping it for a similar-sounding name broke those.
                    if (outcome is ScribeRealtime.Outcome.Said) { voice.note = null; result(outcome.text) }
                    else voice.note = R.string.voice_search_not_understood
                    return@launch
                }
                if (OfflineSpeech.needsDownload(context)) { voice.note = R.string.voice_search_loading_model; voice.noteArg = 0 }
                // Both callbacks come from the recording thread; the field's state is set on the main thread.
                val model = OfflineSpeech.prepare(context) { p -> scope.launch { if (voice.note == R.string.voice_search_loading_model) voice.noteArg = p } }
                voice.noteArg = null
                voice.note = R.string.voice_search_listening
                voice.listening = true
                val heard = OfflineSpeech.listen(model, stopRequested = { stop.value }) { words ->
                    scope.launch { if (voice.listening) voice.heard = words }
                }
                voice.listening = false
                val said = heard.guesses
                if (said.isEmpty()) {
                    voice.heard = null
                    voice.note = R.string.voice_search_not_understood
                    return@launch
                }
                voice.heard = said.first()
                val text = resolver?.let { r ->
                    runCatching { withTimeoutOrNull(RESOLVE_TIMEOUT_MS) { r(said) } }
                        .onFailure { if (it is CancellationException) throw it; Log.w(TAG, "resolve failed: ${it.message}") }
                        .getOrNull()
                }?.takeIf { it.isNotBlank() } ?: said.first()
                voice.heard = null
                voice.note = null
                result(text)
            } catch (e: CancellationException) {
                voice.heard = null
                voice.note = null
                throw e
            } catch (e: IOException) {
                Log.w(TAG, "voice model: ${e.message}")
                voice.heard = null
                voice.note = R.string.voice_search_model_failed
            } catch (e: Exception) {
                Log.w(TAG, "microphone: ${e.message}")
                voice.heard = null
                voice.note = R.string.voice_search_mic_failed
            } finally {
                voice.noteArg = null
                voice.listening = false
                voice.busy = false
                OfflineSpeech.release()
            }
        }
    }

    val micPermission = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        if (granted) startListening() else { voice.note = R.string.voice_search_no_microphone }
    }

    voice.launch = launch@{
        if (voice.busy) {
            // While listening: done talking, search with what was heard. Otherwise: never mind.
            if (voice.listening) { stop.value = true } else { job.value?.cancel() }
            return@launch
        }
        voice.note = null
        if (ContextCompat.checkSelfPermission(context, Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED) startListening()
        else micPermission.launch(Manifest.permission.RECORD_AUDIO)
    }
    return voice
}

private const val RESOLVE_TIMEOUT_MS = 5_000L
private const val TAG = "VoiceSearch"

package tv.own.owntv.features.onboarding.extras

import androidx.compose.runtime.mutableStateMapOf
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import tv.own.owntv.features.keys.ApiKeyValidator
import tv.own.owntv.features.keys.ApiKeys
import tv.own.owntv.features.keys.ApiService
import tv.own.owntv.features.keys.KeyCheck

/** What the Extras chapter knows about one service's key in this visit. */
sealed interface ExtrasKeyStatus {
    /** Nothing happened here yet: the stored key (if any) is shown as saved. */
    data object Idle : ExtrasKeyStatus

    /** A key is being checked with the service. */
    data object Checking : ExtrasKeyStatus

    /**
     * The last check of [key] found [result]. A [KeyCheck.Invalid] key was not stored; any other
     * result was. [key] stays in memory only, for masking and for telling TMDB's two values apart.
     */
    class Checked(val result: KeyCheck, val key: String) : ExtrasKeyStatus
}

/**
 * Checks and stores the keys entered in the Extras chapter, by phone or with the remote.
 *
 * A new key is checked first and stored unless the service rejects it, so a wrong key never replaces
 * a working one; a key that cannot be checked right now (no network, the service is down) is stored
 * anyway and can be checked again. The work runs on [scope] (the app's scope), so a check that is
 * still running when the user moves on still stores its key.
 */
class ExtrasKeys(
    private val keys: ApiKeys,
    private val validator: ApiKeyValidator,
    private val scope: CoroutineScope,
) {
    private val statuses = mutableStateMapOf<ApiService, ExtrasKeyStatus>()
    private val jobs = HashMap<ApiService, Job>()

    /** The status of [service] (Compose state). */
    fun status(service: ApiService): ExtrasKeyStatus = statuses[service] ?: ExtrasKeyStatus.Idle

    /** Checks [raw] (sanitised) for [service] and stores it unless rejected. Blank input does nothing. */
    fun submit(service: ApiService, raw: String) {
        val clean = ApiKeys.sanitize(raw)
        if (clean.isEmpty()) return
        run(service) {
            val result = validator.check(service, clean)
            if (result != KeyCheck.Invalid) keys.set(service, clean)
            ExtrasKeyStatus.Checked(result, clean)
        }
    }

    /** Checks the stored key of [service] again. */
    fun recheck(service: ApiService) {
        run(service) {
            val stored = keys.current(service)
            if (stored.isEmpty()) ExtrasKeyStatus.Idle else ExtrasKeyStatus.Checked(validator.check(service, stored), stored)
        }
    }

    /** Removes the stored key of [service]. */
    fun remove(service: ApiService) {
        jobs.remove(service)?.cancel()
        statuses.remove(service)
        scope.launch(Dispatchers.Main) { runCatching { keys.clear(service) } }
    }

    private fun run(service: ApiService, work: suspend () -> ExtrasKeyStatus) {
        jobs.remove(service)?.cancel()
        statuses[service] = ExtrasKeyStatus.Checking
        jobs[service] = scope.launch(Dispatchers.Main) {
            statuses[service] = try {
                work()
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                // Storage or network trouble beyond what the validator reports: nothing was confirmed.
                ExtrasKeyStatus.Checked(KeyCheck.Unreachable(), "")
            }
        }
    }
}

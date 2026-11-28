package tv.own.owntv.features.onboarding.football

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.launch
import tv.own.owntv.core.sports.SportsCatalog
import tv.own.owntv.core.sports.SportsSource
import tv.own.owntv.core.sports.model.Team

/** Where the team lists of the chosen competitions stand. */
internal enum class TeamsPhase {
    /** Fetching for the first time, nothing to show yet. */
    LOADING,

    /** At least one list arrived. Some may still be missing ([FootballTeamLoader.failed]). */
    READY,

    /** Nothing arrived: offline, or the service did not answer. Stays so while a retry runs. */
    FAILED,
}

/**
 * Fetches the team lists of the chosen competitions (the same call Settings › Sports › My teams
 * searches) and keeps them for the chapter's lifetime, so going back and forth between its pages
 * fetches only what is new. Each list is fetched on its own: one competition that fails does not
 * hide the others. Compose reads the state directly.
 */
internal class FootballTeamLoader(private val source: SportsSource, private val scope: CoroutineScope) {

    var loaded by mutableStateOf<Map<String, List<Team>>>(emptyMap())
        private set

    /** Competitions whose list could not be fetched (or came back empty) on the last attempt. */
    var failed by mutableStateOf<Set<String>>(emptySet())
        private set

    var loading by mutableStateOf(false)
        private set

    private var job: Job? = null

    /** The phase for [keys]: what the teams page shows. */
    fun phase(keys: List<String>): TeamsPhase = when {
        keys.isEmpty() || keys.any { !loaded[it].isNullOrEmpty() } -> TeamsPhase.READY
        keys.all { it in failed } -> TeamsPhase.FAILED
        else -> TeamsPhase.LOADING
    }

    /**
     * Fetches the lists of [keys] not loaded yet; [retry] also fetches the ones that failed before.
     * A failed list stays failed until its retry succeeds, so the page keeps its Try again in place
     * (and the focus on it) while the retry runs. Ignored while a fetch is running.
     */
    fun load(keys: List<String>, retry: Boolean = false) {
        val missing = keys.filter { loaded[it].isNullOrEmpty() && (retry || it !in failed) }
        if (missing.isEmpty() || job?.isActive == true) return
        loading = true
        job = scope.launch {
            try {
                val results = missing.map { key ->
                    async {
                        val league = SportsCatalog.league(key) ?: return@async key to null
                        val teams = try {
                            source.teams(league)
                        } catch (e: CancellationException) {
                            throw e
                        } catch (e: Exception) {
                            null
                        }
                        key to teams?.sortedBy { it.name.lowercase() }
                    }
                }.awaitAll()
                val ok = results.filter { !it.second.isNullOrEmpty() }.associate { it.first to it.second.orEmpty() }
                loaded = loaded + ok
                failed = failed - ok.keys + results.filter { it.second.isNullOrEmpty() }.map { it.first }
            } finally {
                loading = false
            }
        }
    }
}

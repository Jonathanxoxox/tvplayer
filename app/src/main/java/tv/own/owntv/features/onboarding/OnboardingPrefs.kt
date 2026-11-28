package tv.own.owntv.features.onboarding

import android.content.Context
import android.content.SharedPreferences
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * The chapters of the first-run journey, in the order they run. [SETUP] is the inherited setup wizard
 * (language, display size, disclaimer, profile, playlist); everything after it runs once a profile
 * exists and is active, before the shell appears.
 *
 * [key] is what is persisted: a stable identifier, never shown and never translated.
 */
enum class OnboardingChapter(val key: String) {
    INTRO("intro"),
    SETUP("setup"),
    APPEARANCE("appearance"),
    HOME_SCREEN("home_screen"),
    FOOTBALL("football"),
    EXTRAS("extras"),
    READY("ready"),
    ;

    /** Runs after the profile exists (needs an active profile, never undoes it). */
    val afterProfile: Boolean get() = ordinal >= APPEARANCE.ordinal

    companion object {
        fun fromKey(key: String?): OnboardingChapter? = entries.firstOrNull { it.key == key }
    }
}

/** Where an install stands with the first-run journey. */
enum class OnboardingPhase(val key: String) {
    /** Never started. With a profile present this is an install from before the journey existed. */
    NOT_STARTED("not_started"),
    IN_PROGRESS("in_progress"),
    COMPLETED("completed"),
    ;

    companion object {
        fun fromKey(key: String?): OnboardingPhase? = entries.firstOrNull { it.key == key }
    }
}

/** The persisted first-run journey: phase, the chapter to show next, and the intro's own two flags. */
data class OnboardingState(
    val phase: OnboardingPhase = OnboardingPhase.NOT_STARTED,
    /** The chapter that is on screen / next to run. Meaningful while [phase] is IN_PROGRESS. */
    val chapter: OnboardingChapter = OnboardingChapter.INTRO,
    /** The brand intro has played (or was skipped) once on this install. */
    val introSeen: Boolean = false,
    /** The intro's sound logo is muted; the intro remembers the choice for its next showing. */
    val introMuted: Boolean = false,
) {
    val completed: Boolean get() = phase == OnboardingPhase.COMPLETED

    /** The chapters after the profile still have to run: the shell must not appear yet. */
    val postProfilePending: Boolean get() = phase == OnboardingPhase.IN_PROGRESS && chapter.afterProfile
}

/**
 * The pure rules of the journey: order, Back, and how a stored state is read against the profiles
 * that actually exist. No Android, so it is unit-tested directly.
 */
object OnboardingJourney {

    /** The chapter after [chapter], or null after [OnboardingChapter.READY] (the journey is over). */
    fun next(chapter: OnboardingChapter): OnboardingChapter? =
        OnboardingChapter.entries.getOrNull(chapter.ordinal + 1)

    /**
     * Where Back leads from [chapter], or null when Back does not move within the journey: the intro,
     * the wizard (it handles Back itself) and the first post-profile chapter, which must not undo the
     * profile that was just created.
     */
    fun previous(chapter: OnboardingChapter): OnboardingChapter? = when {
        !chapter.afterProfile -> null
        chapter == OnboardingChapter.APPEARANCE -> null
        else -> OnboardingChapter.entries[chapter.ordinal - 1]
    }

    /**
     * The stored state as it applies now. [hasActiveProfile] is true when an active profile is known
     * to exist (Room list loaded and containing the active id).
     *
     * - Never started but a profile exists: an install from before the journey, treated as completed.
     * - In progress, a profile exists, but the stored chapter is still before it: the profile was
     *   finished just before the process died, so the journey continues after the profile.
     * - In progress past the profile but no active profile: the wizard has to run (again) first.
     */
    fun resolve(stored: OnboardingState, hasActiveProfile: Boolean): OnboardingState = when (stored.phase) {
        OnboardingPhase.NOT_STARTED ->
            if (hasActiveProfile) stored.copy(phase = OnboardingPhase.COMPLETED) else stored
        OnboardingPhase.IN_PROGRESS -> when {
            hasActiveProfile && !stored.chapter.afterProfile -> stored.copy(chapter = OnboardingChapter.APPEARANCE)
            !hasActiveProfile && stored.chapter.afterProfile -> stored.copy(chapter = OnboardingChapter.SETUP)
            else -> stored
        }
        OnboardingPhase.COMPLETED -> stored
    }
}

/** Where [OnboardingPrefs] keeps its state; a seam so the rules are testable without Android. */
interface OnboardingStorage {
    fun read(): OnboardingState
    fun write(state: OnboardingState)
}

/** The app-private preference file `mayo_onboarding`. */
private class SharedPreferencesOnboardingStorage(context: Context) : OnboardingStorage {
    private val prefs: SharedPreferences by lazy {
        context.applicationContext.getSharedPreferences(FILE, Context.MODE_PRIVATE)
    }

    override fun read(): OnboardingState {
        val d = OnboardingState()
        return OnboardingState(
            phase = OnboardingPhase.fromKey(prefs.getString(K_PHASE, null)) ?: d.phase,
            chapter = OnboardingChapter.fromKey(prefs.getString(K_CHAPTER, null)) ?: d.chapter,
            introSeen = prefs.getBoolean(K_INTRO_SEEN, d.introSeen),
            introMuted = prefs.getBoolean(K_INTRO_MUTED, d.introMuted),
        )
    }

    override fun write(state: OnboardingState) {
        // apply(): written to disk off the main thread, and Android flushes pending writes before an
        // Activity stops, so a chapter reached just before leaving for system settings is kept.
        prefs.edit()
            .putString(K_PHASE, state.phase.key)
            .putString(K_CHAPTER, state.chapter.key)
            .putBoolean(K_INTRO_SEEN, state.introSeen)
            .putBoolean(K_INTRO_MUTED, state.introMuted)
            .apply()
    }

    private companion object {
        const val FILE = "mayo_onboarding"
        const val K_PHASE = "phase"
        const val K_CHAPTER = "chapter"
        const val K_INTRO_SEEN = "intro_seen"
        const val K_INTRO_MUTED = "intro_muted"
    }
}

/**
 * The persisted first-run journey (a Koin single). Survives process death: a user who leaves for the
 * system's accessibility settings in the Home screen chapter comes back to that chapter even if the
 * box killed the app meanwhile.
 *
 * Every write is idempotent and only moves forward where that matters, so a double OK, a stale
 * callback from a chapter that is fading out, or a resumed chapter cannot skip a chapter.
 */
class OnboardingPrefs(private val storage: OnboardingStorage) {

    constructor(context: Context) : this(SharedPreferencesOnboardingStorage(context))

    private val _state = MutableStateFlow(storage.read())

    /** The stored state. Read it through [OnboardingJourney.resolve] to decide what to show. */
    val state: StateFlow<OnboardingState> = _state.asStateFlow()

    /** A true first run starts: from NOT_STARTED to the intro. No effect once started or completed. */
    fun begin() = update { s ->
        if (s.phase == OnboardingPhase.NOT_STARTED) s.copy(phase = OnboardingPhase.IN_PROGRESS, chapter = OnboardingChapter.INTRO) else s
    }

    /**
     * [from] is done: move to the chapter after it. Only ever moves forward, and only while the
     * journey runs; finishing [OnboardingChapter.READY] completes the journey.
     */
    fun finishChapter(from: OnboardingChapter) = update { s ->
        if (s.phase != OnboardingPhase.IN_PROGRESS) return@update s
        val target = OnboardingJourney.next(from) ?: return@update s.copy(phase = OnboardingPhase.COMPLETED)
        if (s.chapter.ordinal < target.ordinal) s.copy(chapter = target) else s
    }

    /** Back from [from] within the post-profile chapters; ignored when [from] is not the current one. */
    fun backFrom(from: OnboardingChapter) = update { s ->
        val target = OnboardingJourney.previous(from)
        if (s.phase == OnboardingPhase.IN_PROGRESS && s.chapter == from && target != null) s.copy(chapter = target) else s
    }

    /** The intro played or was skipped. */
    fun markIntroSeen() = update { it.copy(introSeen = true) }

    fun setIntroMuted(muted: Boolean) = update { it.copy(introMuted = muted) }

    /** The journey is over; the shell may appear. */
    fun complete() = update { it.copy(phase = OnboardingPhase.COMPLETED) }

    /**
     * An install that already has a profile and never started the journey (it predates it) is
     * marked completed, so an update never shows it the first-run onboarding. A true first run has
     * called [begin] long before its wizard creates the first profile, so it is not affected.
     */
    fun adoptExistingInstall(hasProfile: Boolean) = update { s ->
        if (hasProfile && s.phase == OnboardingPhase.NOT_STARTED) s.copy(phase = OnboardingPhase.COMPLETED) else s
    }

    @Synchronized
    private fun update(change: (OnboardingState) -> OnboardingState) {
        val before = _state.value
        val after = change(before)
        if (after == before) return
        _state.value = after
        storage.write(after)
    }
}

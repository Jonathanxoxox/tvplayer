package tv.own.owntv.features.onboarding

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** The first-run journey's rules and its persisted state, without Android. */
class OnboardingPrefsTest {

    private class MemoryStorage(var stored: OnboardingState = OnboardingState()) : OnboardingStorage {
        var writes = 0
        override fun read() = stored
        override fun write(state: OnboardingState) { stored = state; writes++ }
    }

    @Test
    fun `chapters run in the agreed order`() {
        val order = generateSequence(OnboardingChapter.INTRO) { OnboardingJourney.next(it) }.toList()
        assertEquals(
            listOf(
                OnboardingChapter.INTRO, OnboardingChapter.SETUP, OnboardingChapter.APPEARANCE, OnboardingChapter.HOME_SCREEN,
                OnboardingChapter.FOOTBALL, OnboardingChapter.EXTRAS, OnboardingChapter.READY,
            ),
            order,
        )
    }

    @Test
    fun `back moves only between post-profile chapters and never undoes the profile`() {
        assertNull(OnboardingJourney.previous(OnboardingChapter.INTRO))
        assertNull(OnboardingJourney.previous(OnboardingChapter.SETUP))
        assertNull(OnboardingJourney.previous(OnboardingChapter.APPEARANCE))
        assertEquals(OnboardingChapter.APPEARANCE, OnboardingJourney.previous(OnboardingChapter.HOME_SCREEN))
        assertEquals(OnboardingChapter.EXTRAS, OnboardingJourney.previous(OnboardingChapter.READY))
    }

    @Test
    fun `an install from before the journey with a profile counts as completed`() {
        val resolved = OnboardingJourney.resolve(OnboardingState(), hasActiveProfile = true)
        assertTrue(resolved.completed)
        assertFalse(resolved.postProfilePending)

        val prefs = OnboardingPrefs(MemoryStorage())
        prefs.adoptExistingInstall(hasProfile = true)
        assertEquals(OnboardingPhase.COMPLETED, prefs.state.value.phase)
    }

    @Test
    fun `a journey that already started is not adopted as an old install`() {
        val storage = MemoryStorage()
        val prefs = OnboardingPrefs(storage)
        prefs.begin()
        prefs.adoptExistingInstall(hasProfile = true)
        assertEquals(OnboardingPhase.IN_PROGRESS, prefs.state.value.phase)
        assertEquals(OnboardingChapter.INTRO, prefs.state.value.chapter)
    }

    @Test
    fun `resolve maps the chapter to what the profiles allow`() {
        val inSetup = OnboardingState(OnboardingPhase.IN_PROGRESS, OnboardingChapter.SETUP)
        assertEquals(OnboardingChapter.APPEARANCE, OnboardingJourney.resolve(inSetup, hasActiveProfile = true).chapter)
        assertTrue(OnboardingJourney.resolve(inSetup, hasActiveProfile = true).postProfilePending)

        val inFootball = OnboardingState(OnboardingPhase.IN_PROGRESS, OnboardingChapter.FOOTBALL)
        assertEquals(OnboardingChapter.SETUP, OnboardingJourney.resolve(inFootball, hasActiveProfile = false).chapter)
        assertEquals(inFootball, OnboardingJourney.resolve(inFootball, hasActiveProfile = true))

        val fresh = OnboardingState()
        assertEquals(fresh, OnboardingJourney.resolve(fresh, hasActiveProfile = false))
    }

    @Test
    fun `the whole journey walks through and completes`() {
        val prefs = OnboardingPrefs(MemoryStorage())
        prefs.begin()
        var chapter: OnboardingChapter? = OnboardingChapter.INTRO
        while (chapter != null) {
            assertEquals(chapter, prefs.state.value.chapter)
            prefs.finishChapter(chapter)
            chapter = OnboardingJourney.next(chapter)
        }
        assertTrue(prefs.state.value.completed)
        assertFalse(prefs.state.value.postProfilePending)
    }

    @Test
    fun `finishing a chapter twice or a stale chapter never skips ahead`() {
        val prefs = OnboardingPrefs(MemoryStorage())
        prefs.begin()
        prefs.finishChapter(OnboardingChapter.INTRO)
        prefs.finishChapter(OnboardingChapter.SETUP)
        prefs.finishChapter(OnboardingChapter.APPEARANCE)
        prefs.finishChapter(OnboardingChapter.APPEARANCE) // double OK
        assertEquals(OnboardingChapter.HOME_SCREEN, prefs.state.value.chapter)
        prefs.finishChapter(OnboardingChapter.SETUP) // late callback from the wizard
        assertEquals(OnboardingChapter.HOME_SCREEN, prefs.state.value.chapter)
    }

    @Test
    fun `back from the current chapter only`() {
        val prefs = OnboardingPrefs(MemoryStorage(OnboardingState(OnboardingPhase.IN_PROGRESS, OnboardingChapter.FOOTBALL)))
        prefs.backFrom(OnboardingChapter.EXTRAS) // not current: ignored
        assertEquals(OnboardingChapter.FOOTBALL, prefs.state.value.chapter)
        prefs.backFrom(OnboardingChapter.FOOTBALL)
        assertEquals(OnboardingChapter.HOME_SCREEN, prefs.state.value.chapter)
        prefs.backFrom(OnboardingChapter.HOME_SCREEN)
        prefs.backFrom(OnboardingChapter.APPEARANCE) // the first post chapter does not go back
        assertEquals(OnboardingChapter.APPEARANCE, prefs.state.value.chapter)
    }

    @Test
    fun `state survives a restart at the same chapter`() {
        val storage = MemoryStorage()
        val before = OnboardingPrefs(storage)
        before.begin()
        before.finishChapter(OnboardingChapter.INTRO)
        before.finishChapter(OnboardingChapter.SETUP)
        before.finishChapter(OnboardingChapter.APPEARANCE)
        before.markIntroSeen()
        before.setIntroMuted(true)

        val after = OnboardingPrefs(storage) // process death: a new instance reads the stored state
        assertEquals(OnboardingChapter.HOME_SCREEN, after.state.value.chapter)
        assertEquals(OnboardingPhase.IN_PROGRESS, after.state.value.phase)
        assertTrue(after.state.value.introSeen)
        assertTrue(after.state.value.introMuted)
        assertTrue(after.state.value.postProfilePending)
    }

    @Test
    fun `nothing moves once completed, and unchanged state is not rewritten`() {
        val storage = MemoryStorage(OnboardingState(OnboardingPhase.COMPLETED, OnboardingChapter.READY))
        val prefs = OnboardingPrefs(storage)
        prefs.begin()
        prefs.finishChapter(OnboardingChapter.READY)
        prefs.backFrom(OnboardingChapter.READY)
        assertEquals(0, storage.writes)
        assertTrue(prefs.state.value.completed)
    }

    @Test
    fun `persisted keys are stable and read back`() {
        OnboardingChapter.entries.forEach { assertEquals(it, OnboardingChapter.fromKey(it.key)) }
        OnboardingPhase.entries.forEach { assertEquals(it, OnboardingPhase.fromKey(it.key)) }
        assertNull(OnboardingChapter.fromKey("unknown"))
        assertEquals("home_screen", OnboardingChapter.HOME_SCREEN.key)
    }
}

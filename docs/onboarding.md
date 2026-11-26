# First-run onboarding: the contract

This is the contract for everyone building a chapter of Mayo TV's first-run journey. The foundation
(state, orchestration, frame, key store) is in place and walkable end to end with placeholder chapters.
Each chapter is built inside its own file against the signatures below.

**Rule: no chapter edits `MainActivity.kt`, `OnboardingFlow.kt`, `OnboardingPrefs.kt`, `ChapterFrame.kt`
or `WizardFrame.kt`.** A chapter that needs something from them asks for it; it does not change the
gating, the order or the frame.

## The journey

```
 true first run (no profile)                                       once the profile is active
┌────────┐   ┌──────────────────────────────────────────────┐    ┌────────────┐   ┌─────────────┐   ┌──────────┐   ┌────────┐   ┌───────┐
│ INTRO  │──▶│ SETUP (inherited wizard)                     │──▶ │ APPEARANCE │──▶│ HOME_SCREEN │──▶│ FOOTBALL │──▶│ EXTRAS │──▶│ READY │──▶ shell
│MayoIntro│  │ language · display size · disclaimer ·       │    └────────────┘ ◀─└─────────────┘ ◀─└──────────┘ ◀─└────────┘ ◀─└───────┘
└────────┘   │ profile (or restore / sync) · playlist       │         ▲  Back on APPEARANCE does not move (the profile stays)
             └──────────────────────────────────────────────┘         │
                       │ wizard finishes: chapter := APPEARANCE,      │
                       │ then MainActivity authenticates the profile ─┘ (PIN gate first if the profile needs one)
```

- **True first run only.** The journey starts when the app opens with no profile at all
  (`OnboardingPrefs.begin()`). An install that already has a profile and never started the journey
  (any install from before it) is recorded as completed and never sees it, not even after an update.
- **"Add profile"** from the profile gate keeps using the wizard alone (`Onboarding(firstRun = false)`):
  no intro, no chapters after the profile.
- **Resume.** The chapter is persisted on every move. After process death (the user left for the
  system's accessibility settings in HOME_SCREEN and the box killed the app) the journey resumes at the
  same chapter; with a PIN-protected profile the profile gate asks first.
- **Restore / sync in the wizard.** They also finish the wizard; the chapters after the profile run
  once the restored profile is let in.
- The shell is not composed until READY is finished.

### Gating in MainActivity (for reference only)

```kotlin
(profile < 0L && firstRunJourney) || (journey.postProfilePending && shellReady) -> OnboardingFlow(...)
```

`journey = OnboardingJourney.resolve(stored, hasActiveProfile)`. The branch sits after "Add profile"
and before the recovery wizard, the profile gate and the shell. One call site, so the move from the
wizard to APPEARANCE keeps the flow (and its transition) alive.

## Files and signatures

Package `tv.own.owntv.features.onboarding` (app/src/main/java/tv/own/owntv/features/onboarding/):

| Chapter | File | Signature | Owner's job |
|---|---|---|---|
| INTRO | `IntroAnimation.kt` | `@Composable fun MayoIntro(onFinished: () -> Unit, modifier: Modifier = Modifier)` | Brand intro with sound logo, skippable, mutable |
| SETUP | `features/setup/SetupWizard.kt` | `@Composable fun Onboarding(firstRun: Boolean, onDone: (Long?) -> Unit, onCancel: () -> Unit, modifier: Modifier = Modifier)` | Inherited wizard, to be harmonised |
| APPEARANCE | `AppearanceChapter.kt` | `@Composable fun AppearanceChapter(onNext: () -> Unit, onBack: (() -> Unit)?)` | Home layout, everything surface-related |
| HOME_SCREEN | `HomeScreenChapter.kt` | `@Composable fun HomeScreenChapter(onNext: () -> Unit, onBack: (() -> Unit)?)` | Home button, Home guard (accessibility), power-on; recommended, skippable |
| FOOTBALL | `FootballChapter.kt` | `@Composable fun FootballChapter(onNext: () -> Unit, onBack: (() -> Unit)?)` | Favourite teams and leagues instead of the built-in defaults |
| EXTRAS | `ExtrasChapter.kt` | `@Composable fun ExtrasChapter(onNext: () -> Unit, onBack: (() -> Unit)?)` | Optional own API keys (phone via QR/companion page, or remote) |
| READY | `ReadyChapter.kt` | `@Composable fun ReadyChapter(onNext: () -> Unit, onBack: (() -> Unit)?)` | Summary; `onNext` completes the journey |

Orchestrator (not for chapters to edit): `OnboardingFlow.kt`

```kotlin
@Composable
fun OnboardingFlow(hasActiveProfile: Boolean, onSetupDone: (profileId: Long?) -> Unit, modifier: Modifier = Modifier)
```

### Chapter responsibilities

- **`onNext`**: call it when the chapter is done. Save the user's choices **before** calling it (or
  in the same click handler); the flow only moves the journey on. A skip also calls `onNext`, without
  saving. Calling it twice is harmless: the journey only ever moves forward one chapter per chapter.
- **`onBack`**: back to the previous chapter, or `null` on APPEARANCE (Back must not undo the profile).
  Pass it straight into `ChapterFrame(secondary = …)`; with `null`, Back on the remote is left to the
  system (the app goes to the background; the journey resumes on this chapter).
- A chapter keeps its working state in its own `remember`/ViewModel. It must not assume it stays
  composed: the user can leave and come back (process death); persist what matters immediately.
- Initial focus is explicit: exactly one control gets `rememberChapterFocus()`. No focus traps:
  every control is reachable with the D-pad and the footer is always reachable with ▼.
- HOME_SCREEN and every step a box may refuse must never be a dead end: when the box does not allow
  something, say so plainly and keep Continue/Skip available.
- Weak boxes (API 26, 1–2 GB): no blur, no shaders, no large images, no `infiniteRepeatable` on
  `ownTvTween` (see `ui/theme/Animations.kt`). Guard any API above 26.
- Never log a key, a PIN or a URL that carries one.

### The intro (`MayoIntro`)

- Calls `onFinished` exactly once: when it ends, or immediately on **any** key (OK, D-pad, Back).
- **Must finish on its own when motion is off.** The smoke test runs on emulators with animations
  disabled (`ANIMATOR_DURATION_SCALE = 0`) and the app's own Animations setting can be Off: then show
  the lockup still for under a second and finish. Never wait on an animation end callback alone; use a
  wall-clock timeout as the upper bound. With motion on the intro runs about 5.6 s (plus up to 0.6 s
  while the sound file opens; a slower sound starts the show silent), with motion off a still of the
  final picture shows for about 0.7 s. A wall-clock backstop ends it at most ~1.8 s after the plan.
- Sound logo (`res/raw/mayo_intro.ogg`, generated by `tools/sound/mayo_intro.py`): read and write the
  mute choice through `OnboardingPrefs.state.value.introMuted` / `setIntroMuted(…)`. The Mute key
  silences the logo, stores the choice and still reaches the box; Volume up/down go to the box and do
  not skip. Respect the box's media volume; never start playback that outlives the intro.
- The lockup ends exactly where `JourneyFrame` draws it (`padding(start = 64.mpx, top = 56.mpx)`,
  scale 1; `LOCKUP_START` / `LOCKUP_TOP` in `IntroAnimation.kt`). Keep both in step. The flow hands over
  from INTRO to SETUP with a plain fade, without the slide, so the lockups line up.
- The flow calls `markIntroSeen()` and moves on when `onFinished` fires.

## The frame

`ChapterFrame.kt` (onboarding) on top of `JourneyFrame` in `features/setup/WizardFrame.kt`. The
wizard's `WizardFrame` and the chapters' `ChapterFrame` are one family: Mayo TV lockup top left, the
progress dots of the **whole journey** top right (Language · Display · Profile · Playlist · Look ·
Start screen · Football · Extras · Ready, the current one named), "STEP n OF 9" above the title, the glass
panel, and the footer.

```kotlin
typealias ChapterAction = WizardAction // class WizardAction(label: String, onClick: () -> Unit, focus: FocusRequester? = null, enabled: Boolean = true)

@Composable
fun ChapterFrame(
    chapter: OnboardingChapter,
    title: String,
    subtitle: String?,
    primary: ChapterAction?,            // right, with a chevron: Continue / Done
    secondary: ChapterAction? = null,   // left: normally Back
    skip: ChapterAction? = null,        // right, quiet, before primary: Skip / Later
    onBack: (() -> Unit)? = secondary?.onClick, // remote Back; null = left to the system
    width: Dp = 1040.mpx,
    body: @Composable ColumnScope.() -> Unit = {},
)

@Composable fun rememberChapterFocus(): FocusRequester // takes focus ~60 ms after the chapter appears
@Composable fun MayoLockup(modifier: Modifier = Modifier, scale: Float = 1f) // features.setup; the intro uses it large
```

Usage:

```kotlin
@Composable
fun FootballChapter(onNext: () -> Unit, onBack: (() -> Unit)?) {
    val first = rememberChapterFocus()
    ChapterFrame(
        chapter = OnboardingChapter.FOOTBALL,
        title = stringResource(R.string.onboarding_football_title),
        subtitle = stringResource(R.string.onboarding_football_sub),
        primary = ChapterAction(stringResource(R.string.onboarding_action_continue), { save(); onNext() }),
        secondary = onBack?.let { ChapterAction(stringResource(R.string.common_back), it) },
        skip = ChapterAction(stringResource(R.string.onboarding_action_later), onNext),
    ) {
        StageSettingRow(icon = OwnTVIcon.SPORTS, title = …, desc = …, value = SettingValue.Opens(null),
            onClick = { … }, modifier = Modifier.focusRequester(first))
        // more rows / cards (WizardCards) / a custom layout
    }
}
```

The body is a scrolling column (6 mpx between children) inside the glass panel; settings rows
(`StageSettingRow`, `StageFieldRow` from `features/settings`) and `WizardCards` fit as they are. Dialogs
and popups (`StagePopup`, `StageConfirm`) are drawn by the chapter outside the frame call.

Chapter transitions (a 260 ms fade with a slight slide in the direction of travel, a plain fade from
the intro to the wizard, instant with animations off) are the flow's; chapters add none of their own
on entry.

`WizardFrame` takes the same optional `skip` (the playlist step's "Skip").

Button vocabulary across the whole journey: **Weiter / Continue** (primary), **Zurück / Back**
(`common_back`), **Überspringen / Skip** (`onboarding_action_skip`, a step that can be left out),
**Später / Later** (`onboarding_action_later`, a recommended chapter left for now) and **Los geht’s /
Let’s go** on the welcome page and on READY. READY has no footer primary: its own large "Los geht’s"
button in the body takes the chapter focus and completes the journey.

Inside the journey `LocalFirstRunJourney` is `true`: the wizard's pages then show the same nine dots
and the "Step n of 9" line; outside it (Add profile, recovery) they show the wizard's own five dots.

## OnboardingPrefs

`OnboardingPrefs.kt`, a Koin single (`koinInject<OnboardingPrefs>()`), file `mayo_onboarding` (app private).

```kotlin
enum class OnboardingChapter(val key: String) { INTRO, SETUP, APPEARANCE, HOME_SCREEN, FOOTBALL, EXTRAS, READY
    val afterProfile: Boolean }
enum class OnboardingPhase(val key: String) { NOT_STARTED, IN_PROGRESS, COMPLETED }

data class OnboardingState(
    val phase: OnboardingPhase = NOT_STARTED,
    val chapter: OnboardingChapter = INTRO,
    val introSeen: Boolean = false,
    val introMuted: Boolean = false,
) { val completed: Boolean; val postProfilePending: Boolean }

object OnboardingJourney {
    fun next(chapter: OnboardingChapter): OnboardingChapter?
    fun previous(chapter: OnboardingChapter): OnboardingChapter?   // null for INTRO, SETUP, APPEARANCE
    fun resolve(stored: OnboardingState, hasActiveProfile: Boolean): OnboardingState
}

class OnboardingPrefs(storage: OnboardingStorage) {
    constructor(context: Context)
    val state: StateFlow<OnboardingState>
    fun begin()                                  // NOT_STARTED → IN_PROGRESS at INTRO
    fun finishChapter(from: OnboardingChapter)   // forward only; READY → COMPLETED
    fun backFrom(from: OnboardingChapter)        // only when `from` is current
    fun markIntroSeen()
    fun setIntroMuted(muted: Boolean)
    fun complete()
    fun adoptExistingInstall(hasProfile: Boolean) // NOT_STARTED + a profile → COMPLETED
}
```

Chapters read `state` (e.g. the intro's mute flag); only the flow moves chapters. Pure rules are
covered by `app/src/test/.../onboarding/OnboardingPrefsTest.kt`.

## ApiKeys

`features/keys/ApiKeys.kt`, a Koin single (`get<ApiKeys>()` / `koinInject<ApiKeys>()`). TMDB stays in
core's `SettingsRepository` (`tmdbApiKey` / `setTmdbApiKey`); ElevenLabs and TypeSafe are kept in the
app-private file `mayo_keys`. The app has `allowBackup="false"`. Keys are never logged.

```kotlin
enum class ApiService { TMDB, ELEVENLABS, TYPESAFE }

class ApiKeys(context: Context, settings: SettingsRepository, scope: CoroutineScope) {
    val tmdb: StateFlow<String>          // "" until DataStore is read; use current() for one-off decisions
    val elevenLabs: StateFlow<String>
    val typeSafe: StateFlow<String>
    val configured: StateFlow<Set<ApiService>>
    fun key(service: ApiService): StateFlow<String>
    fun isSet(service: ApiService): Boolean
    fun isSetFlow(service: ApiService): Flow<Boolean>
    suspend fun current(service: ApiService): String
    suspend fun set(service: ApiService, raw: String): String  // sanitised; blank clears; returns what was stored
    fun apply(service: ApiService, raw: String)                // set() without waiting
    suspend fun clear(service: ApiService)
    companion object {
        const val MAX_LENGTH = 512
        fun sanitize(raw: String): String  // ASCII letters/digits and _ - . only; whitespace, quotes, brackets go
        fun mask(key: String): String      // "••••c3f9"
    }
}
```

`features/keys/ApiKeyValidator.kt`, a Koin single:

```kotlin
sealed interface KeyCheck {
    data object Valid; data object Invalid; data class Unreachable(val httpCode: Int? = null); data object Unknown
}

class ApiKeyValidator(okHttp: OkHttpClient) {
    suspend fun check(service: ApiService, key: String): KeyCheck
    suspend fun checkTmdb(key: String): KeyCheck        // GET /3/configuration?api_key= (v3 keys only; core sends api_key)
    suspend fun checkElevenLabs(key: String): KeyCheck  // GET /v1/user; "missing_permissions" counts as Valid
    suspend fun checkTypeSafe(key: String): KeyCheck    // GET /v1/models with Bearer; spends no credits
}
```

401/403 is `Invalid`, no answer `Unreachable(null)`, any other code `Unreachable(code)`.
`looksLikeTmdbReadToken()` lets the UI explain a v4 read token pasted by mistake.

No key is built into the app any more: the BuildConfig fields are gone and every consumer
(`ScribeRealtime`, `JevClient` in `MoodSearch`, `LiveGuideSearch`, `VoiceQuery`, TMDB in `MoodSearch`,
`WatchProviders`, `VodCatalogs`) reads the user's key at call time, so a key entered later works without
a restart. Shared texts for the keys page and the EXTRAS chapter (service name, what it unlocks,
where to get a key, the "missing" hints, the check results) live in `features/keys/KeyTexts.kt`. The
keys page is Settings › System › API keys (`ApiKeysSettingsScreen.kt`).

The EXTRAS chapter's phone link is its own small LAN-only server (`onboarding/extras/ExtrasPhoneServer.kt`,
ports 8090–8099, a fresh token in the path, closes after 12 wrong requests), not core's
`CompanionController`, which takes a single TMDB key only.

## Strings

- Shared journey strings (progress labels, "Step n of m", Continue / Skip / Later):
  `res/values/strings_onboarding.xml` (English, the source) and `res/values-de/strings_onboarding.xml`.
- Each chapter owns one pair: `res/values/strings_onboarding_<chapter>.xml` and
  `res/values-de/strings_onboarding_<chapter>.xml`, with `<chapter>` one of `intro`, `appearance`,
  `home_screen`, `football`, `extras`, `ready` (and `wizard` for the harmonised setup wizard; the keys
  page uses `strings_keys.xml`). The pairs exist already with the chapter's title and
  subtitle (`onboarding_<chapter>_title`, `onboarding_<chapter>_sub`); add keys there with the prefix
  `onboarding_<chapter>_`.
- English and German both, carefully written; German speaks to the user with "du". Follow
  [docs/i18n.md](i18n.md): positional placeholders with `<xliff:g>`, `<plurals>` for counts, a
  `Translators:` comment where a key is not obvious, no hard-coded UI text in Kotlin. Technical
  literals (preference keys, URLs, headers) are classified with
  `python3 tools/i18n/check_hardcoded_strings.py classify-safe --path … --text … --category …`.
- Reuse core's common strings (`common_back`, `common_ok` …) instead of copies.

## Smoke test (`tools/smoke/tour.py`)

The tour's `setup()` starts by looking for "Los geht's". With the journey in front of it:

- The intro shows first and finishes on its own within ~2 s (~0.8 s with animations off); any key
  skips it. The intro must keep it that way. `goto()` gives up at once when its target is not on
  screen, so the tour has to wait for the welcome page first: poll the dump for "Los geht" for up to
  ~8 s (pressing OK once if it is not there after ~4 s) before the first `goto`, and take a screenshot
  of the intro on the way.
- After the wizard (import done, "Alles bereit!" → Weiter) the chapters follow: APPEARANCE → … →
  READY. Each has "Weiter" as its primary action (READY: "Fertig"), focused first, so the tour's
  closing loop that presses "Weiter"/"Fertig" until "Live-TV"/"Startseite" appears walks through them
  with the stubs. As chapters gain real content, the tour needs an explicit step per chapter (take a
  screenshot, choose or skip, press the primary action) and more than 12 iterations in that loop;
  HOME_SCREEN must be skipped on the emulator (no accessibility settings round trip).

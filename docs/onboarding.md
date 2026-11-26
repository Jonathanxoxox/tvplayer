# First-run onboarding

How Mayo TV's first-run journey is put together, for whoever changes it next. The user-facing guide to
the optional service keys is [api-keys.md](api-keys.md); what to try on a real box before a release is
in [release-checklist.md](release-checklist.md).

## The journey

```
INTRO ─▶ SETUP (inherited wizard) ─▶ APPEARANCE ─▶ HOME_SCREEN ─▶ FOOTBALL ─▶ EXTRAS ─▶ READY ─▶ shell
         language · display size ·        ◀─────────── Back moves one chapter ───────────▶
         disclaimer · profile · playlist
```

- **True first run only.** The journey starts when the app opens with no profile at all
  (`OnboardingPrefs.begin()`). An install that already has a profile and never started it (every
  install from before it) is marked completed by `adoptExistingInstall()` and never sees it, not even
  after an update.
- **"Add profile"** from the profile gate uses the wizard alone (`Onboarding(firstRun = false)`): no
  intro, no chapters after the profile.
- **Resume.** The chapter is stored on every move (`mayo_onboarding`, app private). After the app was
  closed or killed (for example while the user was in the box's accessibility settings) the journey
  opens on the same chapter; a PIN-protected profile is asked for first. A first run that ended after
  its profile was made but before the playlist step was done carries on at the playlist step with that
  profile (`SetupViewModel.resumeInterruptedProfile()`), instead of creating a second one.
- **Restore / sync in the wizard** finish the wizard too; the chapters after the profile run once the
  restored profile is let in.
- The shell is not composed until READY is finished.

Gating, in `MainActivity` (one call site, so the move from the wizard to APPEARANCE keeps the flow and
its transition):

```kotlin
(profile < 0L && firstRunJourney) || (journey.postProfilePending && shellReady) -> OnboardingFlow(...)
```

## Where things live

Package `tv.own.owntv.features.onboarding`:

| Part | File | What it does |
|---|---|---|
| Flow | `OnboardingFlow.kt` | Picks the chapter from `OnboardingPrefs` via `OnboardingJourney.resolve`, runs the transitions (260 ms fade with a slight slide, a plain fade from the intro, instant with animations off) |
| State | `OnboardingPrefs.kt` | `OnboardingState` (phase, chapter, intro seen, intro muted), `OnboardingJourney.next/previous/resolve`; a Koin single. Tests: `OnboardingPrefsTest` |
| Frame | `ChapterFrame.kt` on `JourneyFrame` in `features/setup/WizardFrame.kt` | Lockup, nine progress dots, "Step n of 9", glass panel, footer, key hints |
| INTRO | `IntroAnimation.kt`, `intro/` | Night-stadium intro with sound logo (`res/raw/mayo_intro.ogg`, made by `tools/sound/mayo_intro.py`) |
| SETUP | `features/setup/SetupWizard.kt`, `SetupViewModel.kt` | The inherited wizard, in the journey's frame |
| APPEARANCE | `AppearanceChapter.kt`, `appearance/` | Home layout, then accent colour and the Home switches |
| HOME_SCREEN | `HomeScreenChapter.kt`, `homescreen/` | Home button, the accessibility "Home guard", what opens at power-on |
| FOOTBALL | `FootballChapter.kt`, `football/` | Competitions, then favourite clubs. Tests: `FootballPicksTest` |
| EXTRAS | `ExtrasChapter.kt`, `extras/` | Own keys by phone (QR, LAN page) or with the remote. Tests: `ExtrasPhoneServerTest` |
| READY | `ReadyChapter.kt` | Summary read back from the stores, three remote tips, "Los geht’s" |

## Rules every chapter follows

- **`onNext`** is called once the chapter has saved what the user chose; a skip calls it without
  saving. Calling it twice is harmless (the journey only moves forward one chapter per chapter).
- **`onBack`** goes back one chapter. APPEARANCE gets `null`: Back must not undo the profile. Where a
  journey page has no step to go back to (APPEARANCE's first page, the wizard's welcome and playlist
  pages), `JourneyFrame` sends the app to the background (`moveTaskToBack`) and the key hint says
  "Einrichtung pausieren". It never finishes the activity, so an import or guide download handed to
  the background keeps running. Those run in the app scope; `SetupViewModel.onCleared()` cancels only
  work the user did not hand off.
- Back from HOME_SCREEN returns to APPEARANCE's second page, the one the user left from.
- **Focus**: exactly one control per page gets `rememberChapterFocus()`. Every control is reachable
  with the D-pad. Only the body scrolls; title and footer stay on screen, and focus on the footer
  scrolls the body to its end so notes under the last row are seen.
- **Never a dead end**: whatever a box refuses (accessibility settings, Home app picker, network),
  the page says so plainly and Continue / Later stay available.
- **Weak boxes** (API 26, 1–2 GB): no blur, no shaders, no large images, no endless animations; guard
  any API above 26.
- **Never log** a key, a PIN or a URL that carries a token.
- Button words across the journey: Weiter / Continue, Zurück / Back, Überspringen / Skip (can be left
  out), Später / Later (recommended, left for now), Los geht’s / Let’s go (welcome page and READY).

## The intro

`MayoIntro(onFinished)` calls `onFinished` exactly once: when it ends, or at once on any key (OK,
D-pad, Back). The Mute key silences the logo, stores the choice (`OnboardingPrefs.setIntroMuted`) and
still reaches the box; Volume keys go to the box. With motion on it runs about 5.6 s (plus up to 0.6 s
while the sound opens); with motion off (system animator scale 0 or the app's Animations setting off)
a still of the last picture shows for about 0.7 s. A wall-clock backstop ends it at most ~1.8 s after
the plan, so it never waits on an animation callback alone. The lockup ends exactly where
`JourneyFrame` draws it (`LOCKUP_START` / `LOCKUP_TOP`); keep both in step.

## Own service keys

No service key is built into the app. `features/keys/ApiKeys.kt` (Koin single) holds the user's own:
TMDB in core's `SettingsRepository` (part of core's metadata config and its backups), ElevenLabs and
TypeSafe in the app-private file `mayo_keys`. Every write goes through `ApiKeys.sanitize`; keys are
shown masked (`ApiKeys.mask`) everywhere, including Settings › Metadata. `ApiKeyValidator` checks a
key with its service without spending credits (401/403 = rejected and not stored; no answer = stored
unchecked). Every consumer reads the key at call time, so a key added later works without a restart.

Without a key the feature says so where it would be (`features/keys/MissingKeyHint.kt`): the details
page of a film or series (TMDB), the search page's card and its "nothing found" view (TypeSafe, and
TMDB, which search by description also needs) and a voice search the box's own recogniser did not
understand (ElevenLabs). Texts shared by Settings › System › API keys and EXTRAS live in
`features/keys/KeyTexts.kt`.

EXTRAS' phone link is its own LAN-only server (`extras/ExtrasPhoneServer.kt`, ports 8090–8099): a
fresh token in the path, answers only private / link-local / loopback clients, closes itself after 12
wrong requests. It listens only while the chapter is composed and the app is in front; start and stop
run one after another on a single thread, so a stop never overtakes a start. Without a network it
tries again every few seconds and offers "Neuer Link".

## Strings

- Shared journey strings: `res/values/strings_onboarding.xml` (English, the source) and
  `res/values-de/strings_onboarding.xml`.
- One pair per chapter: `strings_onboarding_<chapter>.xml` with `<chapter>` one of `intro`, `wizard`,
  `appearance`, `home_screen`, `football`, `extras`, `ready`; the keys page and the missing-key hints
  use `strings_keys.xml`. Keys are prefixed `onboarding_<chapter>_`.
- English and German both; German says "du", writes "…" without a space before it and names Settings
  places as the Settings root shows them (group › page, e.g. "Einstellungen › System › Eigene
  Schlüssel"). Follow [i18n.md](i18n.md): `<xliff:g>` placeholders, `<plurals>` for counts, a
  `Translators:` comment where a key is not obvious, no hard-coded UI text in Kotlin.

## Smoke test

`tools/smoke/tour.py` waits through the intro for "Los geht’s", walks the wizard, then each chapter
with a screenshot: APPEARANCE and FOOTBALL with Continue, HOME_SCREEN and EXTRAS always with Later (the
emulator has no accessibility round trip and no keys), READY with "Los geht’s".

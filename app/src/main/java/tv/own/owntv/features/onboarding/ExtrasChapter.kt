package tv.own.owntv.features.onboarding

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.repeatOnLifecycle
import androidx.tv.material3.Text
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.koin.compose.koinInject
import tv.own.owntv.R
import tv.own.owntv.features.keys.ApiKeyValidator
import tv.own.owntv.features.keys.ApiKeys
import tv.own.owntv.features.keys.ApiService
import tv.own.owntv.features.onboarding.extras.ExtrasKeys
import tv.own.owntv.features.onboarding.extras.ExtrasPhonePage
import tv.own.owntv.features.onboarding.extras.ExtrasPhonePanel
import tv.own.owntv.features.onboarding.extras.ExtrasPhoneServer
import tv.own.owntv.features.onboarding.extras.ExtrasServiceCard
import tv.own.owntv.features.onboarding.extras.PhoneLinkState
import tv.own.owntv.features.onboarding.extras.KeyEntryPopup
import tv.own.owntv.features.onboarding.extras.extrasPhonePageTexts
import tv.own.owntv.ui.theme.StageColors
import tv.own.owntv.ui.theme.mpx
import tv.own.owntv.ui.theme.stageText

/**
 * Optional keys of the user's own (TMDB, ElevenLabs, TypeSafe), entered by phone or with the remote,
 * stored through [tv.own.owntv.features.keys.ApiKeys].
 *
 * Left: one card per service with what it switches on and the key's state. Right: a QR code and an
 * address for a small page on the home network where the phone pastes the keys; they arrive here at
 * once. OK on a card types the key with the remote instead. Every key is checked with its service and
 * stored unless the service rejects it. The phone link runs only while this chapter is on screen and
 * the app is in front.
 *
 * Continue (the initial focus) and Later both move on, with or without keys; Settings has the same
 * keys for later.
 *
 * [onNext] moves on to the next chapter (after this chapter has saved what the user chose);
 * [onBack] returns to the previous chapter, or is null where Back must not move (see [ChapterFrame]).
 */
@Composable
fun ExtrasChapter(onNext: () -> Unit, onBack: (() -> Unit)?) {
    val keys: ApiKeys = koinInject()
    val validator: ApiKeyValidator = koinInject()
    val appScope: CoroutineScope = koinInject()
    val model = remember(keys, validator, appScope) { ExtrasKeys(keys, validator, appScope) }

    val tmdb by keys.tmdb.collectAsStateWithLifecycle()
    val elevenLabs by keys.elevenLabs.collectAsStateWithLifecycle()
    val typeSafe by keys.typeSafe.collectAsStateWithLifecycle()
    fun stored(service: ApiService): String = when (service) {
        ApiService.TMDB -> tmdb
        ApiService.ELEVENLABS -> elevenLabs
        ApiService.TYPESAFE -> typeSafe
    }

    // The phone link: its page speaks the TV's language, and what it receives is checked like a typed key.
    val context = LocalContext.current
    val pageTexts by rememberUpdatedState(extrasPhonePageTexts())
    var received by remember { mutableIntStateOf(0) }
    val server = remember(model) {
        ExtrasPhoneServer(
            page = { sent -> ExtrasPhonePage.render(context, pageTexts, sent) },
            onKeys = { arrived ->
                appScope.launch(Dispatchers.Main) {
                    received = arrived.size
                    arrived.forEach { (service, key) -> model.submit(service, key) }
                }
            },
        )
    }
    // Every start and stop of the link runs on this one thread, in the order asked, so a stop can never
    // overtake an earlier start and leave a listener behind.
    val linkThread = remember { Dispatchers.IO.limitedParallelism(1) }
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    // The link listens only while the chapter is composed and the app is in front: it closes when the
    // app goes to the background (Home, another app, the screensaver) and opens with a new token on return.
    DisposableEffect(server, lifecycle) {
        val observer = LifecycleEventObserver { _, event ->
            when (event) {
                Lifecycle.Event.ON_START -> appScope.launch(linkThread) { server.start() }
                Lifecycle.Event.ON_STOP -> appScope.launch(linkThread) { server.stop() }
                else -> Unit
            }
        }
        lifecycle.addObserver(observer)
        onDispose {
            lifecycle.removeObserver(observer)
            appScope.launch(linkThread) { server.stop() }
        }
    }
    // Without a network on arrival (Wi-Fi still connecting after power-on), try again every few seconds.
    LaunchedEffect(server, lifecycle) {
        lifecycle.repeatOnLifecycle(Lifecycle.State.STARTED) {
            while (true) {
                delay(NETWORK_RETRY_MS)
                withContext(linkThread) {
                    if (server.state.value == PhoneLinkState.NoNetwork) server.start()
                }
            }
        }
    }
    val link by server.state.collectAsStateWithLifecycle()

    var editing by remember { mutableStateOf<ApiService?>(null) }
    var returnTo by remember { mutableStateOf<ApiService?>(null) }
    val cardFocus = remember { ApiService.entries.associateWith { FocusRequester() } }
    // Focus goes back to the service's card once its popup has closed.
    LaunchedEffect(editing) {
        if (editing != null) return@LaunchedEffect
        val service = returnTo ?: return@LaunchedEffect
        returnTo = null
        delay(FOCUS_RETURN_DELAY_MS)
        runCatching { cardFocus.getValue(service).requestFocus() }
    }

    ChapterFrame(
        chapter = OnboardingChapter.EXTRAS,
        title = stringResource(R.string.onboarding_extras_title),
        subtitle = stringResource(R.string.onboarding_extras_sub),
        primary = ChapterAction(stringResource(R.string.onboarding_action_continue), onNext, rememberChapterFocus()),
        secondary = onBack?.let { ChapterAction(stringResource(R.string.common_back), it) },
        skip = ChapterAction(stringResource(R.string.onboarding_action_later), onNext),
        width = CHAPTER_WIDTH.mpx,
    ) {
        Row(
            Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(24.mpx),
            verticalAlignment = Alignment.Top,
        ) {
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(10.mpx)) {
                ApiService.entries.forEach { service ->
                    ExtrasServiceCard(
                        service = service,
                        stored = stored(service),
                        status = model.status(service),
                        onClick = {
                            returnTo = service
                            editing = service
                        },
                        modifier = Modifier.focusRequester(cardFocus.getValue(service)),
                    )
                }
                Text(
                    stringResource(
                        R.string.onboarding_extras_later,
                        stringResource(R.string.settings_breadcrumb, stringResource(R.string.settings_cleanup_group_system), stringResource(R.string.keys_settings_title)),
                    ),
                    style = stageText(16, 500),
                    color = StageColors.Muted,
                    modifier = Modifier.padding(top = 6.mpx, start = 4.mpx),
                )
            }
            ExtrasPhonePanel(
                state = link,
                received = received,
                onRestart = {
                    // The pill goes away with the restart: hand focus to the first card before it does.
                    runCatching { cardFocus.getValue(ApiService.entries.first()).requestFocus() }
                    appScope.launch(linkThread) { server.start() }
                },
                modifier = Modifier.width(PHONE_PANEL_WIDTH.mpx),
            )
        }
    }

    editing?.let { service ->
        KeyEntryPopup(
            service = service,
            stored = stored(service),
            onSave = { key ->
                editing = null
                model.submit(service, key)
            },
            onRecheck = {
                editing = null
                model.recheck(service)
            },
            onRemove = {
                editing = null
                model.remove(service)
            },
            onDismiss = { editing = null },
        )
    }
}

private const val CHAPTER_WIDTH = 1240
private const val PHONE_PANEL_WIDTH = 380
private const val FOCUS_RETURN_DELAY_MS = 80L
private const val NETWORK_RETRY_MS = 4_000L

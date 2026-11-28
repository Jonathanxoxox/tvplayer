package tv.own.owntv.features.settings

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.tv.material3.Text
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import org.koin.compose.koinInject
import tv.own.owntv.R
import tv.own.owntv.core.companion.CompanionController
import tv.own.owntv.features.keys.ApiKeyValidator
import tv.own.owntv.features.keys.ApiKeys
import tv.own.owntv.features.keys.ApiService
import tv.own.owntv.features.keys.KeyCheck
import tv.own.owntv.features.keys.icon
import tv.own.owntv.features.keys.keyCheckMessage
import tv.own.owntv.features.keys.keyStateText
import tv.own.owntv.features.keys.lineRes
import tv.own.owntv.features.keys.nameRes
import tv.own.owntv.features.keys.unlocksRes
import tv.own.owntv.features.keys.whereRes
import tv.own.owntv.features.shell.components.SettingsGroup
import tv.own.owntv.ui.components.InAppToast
import tv.own.owntv.ui.components.OwnTVIcon
import tv.own.owntv.ui.components.rememberInAppToast
import tv.own.owntv.ui.stage.StageButton
import tv.own.owntv.ui.stage.StagePopup
import tv.own.owntv.ui.stage.StagePopupIcon
import tv.own.owntv.ui.stage.StagePopupOption
import tv.own.owntv.ui.theme.StageColors
import tv.own.owntv.ui.theme.mpx
import tv.own.owntv.ui.theme.stageText

/**
 * Settings › System › API keys: the user's own keys for TMDB (posters and plot), ElevenLabs (voice
 * search) and TypeSafe (search by description), all optional.
 *
 * One row per service shows what the key unlocks and whether one is set (masked to its last four
 * characters). OK opens the service's popup: enter or replace the key (TMDB can also come from a
 * phone), check it, or remove it. A new key is checked before it is stored; a key the service rejects
 * is not stored, one that cannot be checked right now (no network) is stored anyway.
 *
 * Everything is read from and written to [ApiKeys], the one store the features read at use time, so a
 * change works at once. Settings › Metadata edits the same TMDB value.
 */
@Composable
fun ApiKeysSettingsScreen(onBack: () -> Unit, modifier: Modifier = Modifier) {
    val keys: ApiKeys = koinInject()
    val validator: ApiKeyValidator = koinInject()
    val companion: CompanionController = koinInject()
    val tmdb by keys.tmdb.collectAsStateWithLifecycle()
    val elevenLabs by keys.elevenLabs.collectAsStateWithLifecycle()
    val typeSafe by keys.typeSafe.collectAsStateWithLifecycle()
    fun stored(service: ApiService): String = when (service) {
        ApiService.TMDB -> tmdb
        ApiService.ELEVENLABS -> elevenLabs
        ApiService.TYPESAFE -> typeSafe
    }

    var editing by remember { mutableStateOf<ApiService?>(null) }
    var draft by remember { mutableStateOf("") }
    var received by remember { mutableStateOf(false) }
    var fromPhone by remember { mutableStateOf(false) }
    var confirmRemove by remember { mutableStateOf<ApiService?>(null) }
    var returnTo by remember { mutableStateOf<ApiService?>(null) }
    val toast = rememberInAppToast()
    val scope = rememberCoroutineScope()
    val rowFocus = remember { ApiService.entries.associateWith { FocusRequester() } }
    val pageFocus = remember { FocusRequester() }
    LaunchedEffect(Unit) { runCatching { pageFocus.requestFocus() } }

    fun open(service: ApiService) {
        draft = ""
        received = false
        returnTo = service
        editing = service
    }

    // Focus goes back to the service's row once nothing is open over the page any more.
    LaunchedEffect(editing, fromPhone, confirmRemove) {
        if (editing != null || fromPhone || confirmRemove != null) return@LaunchedEffect
        val service = returnTo ?: return@LaunchedEffect
        returnTo = null
        delay(FOCUS_RETURN_DELAY_MS)
        runCatching { rowFocus.getValue(service).requestFocus() }
    }

    // A TMDB key sent from a phone fills the field; saving stays a deliberate press.
    LaunchedEffect(fromPhone) {
        if (!fromPhone) return@LaunchedEffect
        companion.tmdbConfigs.collect { config ->
            draft = ApiKeys.sanitize(config.apiKey)
            received = draft.isNotEmpty()
            fromPhone = false
        }
    }

    val parent = stringResource(SettingsGroup.APP.titleRes)
    val back = stringResource(R.string.common_back)
    val okHint = stringResource(R.string.common_ok) to stringResource(R.string.settings_key_open)
    StageFullPage(
        parents = listOf(parent),
        title = stringResource(R.string.keys_settings_title),
        count = "",
        onBack = onBack,
        modifier = modifier,
        rowsFocus = pageFocus,
        panelTop = {
            SettingPanelHeading(stringResource(R.string.keys_settings_about_heading))
            Text(stringResource(R.string.keys_settings_about), style = stageText(16, 500), color = StageColors.Muted)
            Text(
                stringResource(R.string.keys_settings_privacy),
                style = stageText(16, 500),
                color = StageColors.Muted,
                modifier = Modifier.padding(top = 8.mpx),
            )
            Box(Modifier.padding(top = 18.mpx, bottom = 18.mpx).fillMaxWidth().height(1.mpx).background(StageColors.Ink.copy(alpha = 0.1f)))
        },
    ) {
        StageSettingsHeading(stringResource(R.string.keys_settings_heading), null, first = true)
        ApiService.entries.forEach { service ->
            val title = stringResource(service.nameRes)
            val where = stringResource(service.whereRes)
            StageSettingRow(
                icon = service.icon,
                title = title,
                desc = stringResource(service.lineRes),
                value = SettingValue.Opens(keyStateText(stored(service))),
                onClick = { open(service) },
                modifier = Modifier.focusRequester(rowFocus.getValue(service)),
                help = SettingHelp(
                    title = title,
                    text = stringResource(service.unlocksRes),
                    hints = listOf(okHint, back to parent),
                    extra = { Text(where, style = stageText(16, 500), color = StageColors.Muted, modifier = Modifier.padding(top = 12.mpx)) },
                ),
            )
        }
    }

    val removedMessage = stringResource(R.string.keys_removed)
    val current = editing
    if (current != null && !fromPhone) {
        KeyPopup(
            service = current,
            stored = stored(current),
            draft = draft,
            received = received,
            onDraftChange = { draft = it; received = false },
            validator = validator,
            store = { value -> keys.set(current, value) },
            onSaved = { message -> editing = null; toast.show(message) },
            onPhone = if (current == ApiService.TMDB) ({ fromPhone = true }) else null,
            onRemove = if (stored(current).isNotEmpty()) ({ editing = null; confirmRemove = current }) else null,
            onDismiss = { editing = null },
        )
    }

    if (fromPhone) {
        CompanionKeyDialog(
            titleRes = R.string.keys_tmdb_name,
            state = companion.state.collectAsStateWithLifecycle().value,
            onStart = { port -> companion.startForTmdbConfig(port) },
            onStop = { companion.stop() },
            onDismiss = { fromPhone = false },
        )
    }

    confirmRemove?.let { target ->
        ConfirmDialog(
            title = stringResource(R.string.keys_remove_title, stringResource(target.nameRes)),
            message = stringResource(R.string.keys_remove_message),
            onConfirm = {
                confirmRemove = null
                scope.launch {
                    keys.clear(target)
                    toast.show(removedMessage)
                }
            },
            onDismiss = { confirmRemove = null },
            confirmLabel = R.string.keys_action_remove,
        )
    }

    InAppToast(toast)
}

/**
 * One service's key: what it unlocks and where to get it, the field (TMDB also from a phone), the
 * result of the last check, and Remove / Check / Check and save. Back closes it without saving; a
 * check still running then stops and nothing is stored.
 */
@Composable
private fun KeyPopup(
    service: ApiService,
    stored: String,
    draft: String,
    received: Boolean,
    onDraftChange: (String) -> Unit,
    validator: ApiKeyValidator,
    store: suspend (String) -> Unit,
    onSaved: (String) -> Unit,
    onPhone: (() -> Unit)?,
    onRemove: (() -> Unit)?,
    onDismiss: () -> Unit,
) {
    val scope = rememberCoroutineScope()
    var busy by remember { mutableStateOf(false) }
    var empty by remember { mutableStateOf(false) }
    // The last check and the key it was about.
    var result by remember { mutableStateOf<Pair<KeyCheck, String>?>(null) }
    val firstFocus = remember { FocusRequester() }
    val saveFocus = remember { FocusRequester() }
    LaunchedEffect(Unit) {
        delay(POPUP_FOCUS_DELAY_MS)
        // A key that just arrived from the phone waits on the save button; otherwise start at the top.
        runCatching { if (draft.isNotEmpty()) saveFocus.requestFocus() else firstFocus.requestFocus() }
    }

    // A changed key makes the last result stale.
    LaunchedEffect(draft) {
        if (!busy) {
            result = null
            empty = false
        }
    }

    val name = stringResource(service.nameRes)
    val savedValid = stringResource(R.string.keys_saved)
    val savedUnchecked = stringResource(R.string.keys_saved_unchecked, name)
    val savedUnknown = stringResource(R.string.keys_saved_unknown)

    fun checkKey() {
        if (busy) return
        val target = ApiKeys.sanitize(draft).ifEmpty { stored }
        empty = target.isEmpty()
        result = null
        if (empty) return
        busy = true
        scope.launch {
            val answer = validator.check(service, target)
            result = answer to target
            busy = false
        }
    }

    fun saveKey() {
        if (busy) return
        val clean = ApiKeys.sanitize(draft)
        // Nothing new typed: check the stored key instead.
        if (clean.isEmpty()) { checkKey(); return }
        empty = false
        result = null
        busy = true
        scope.launch {
            val answer = validator.check(service, clean)
            if (answer == KeyCheck.Invalid) {
                result = answer to clean
                busy = false
                return@launch
            }
            store(clean)
            busy = false
            onSaved(
                when (answer) {
                    KeyCheck.Valid -> savedValid
                    is KeyCheck.Unreachable -> savedUnchecked
                    else -> savedUnknown
                },
            )
        }
    }

    val shown = result
    val status: Pair<String, Color>? = when {
        busy -> stringResource(R.string.keys_check_running) to StageColors.Muted
        empty -> stringResource(R.string.keys_check_empty) to StageColors.Warn
        shown != null -> keyCheckMessage(service, shown.first, shown.second) to when (shown.first) {
            KeyCheck.Valid -> StageColors.Ok
            KeyCheck.Invalid -> StageColors.Danger
            else -> StageColors.Warn
        }
        received -> stringResource(R.string.keys_received) to StageColors.Ok
        else -> null
    }

    StagePopup(
        onDismiss = onDismiss,
        title = name,
        body = stringResource(service.unlocksRes),
        buttons = {
            if (onRemove != null) {
                StageButton(stringResource(R.string.keys_action_remove), onClick = { if (!busy) onRemove() }, height = 56.mpx, textSize = 19)
            }
            StageButton(stringResource(R.string.keys_action_check), onClick = { checkKey() }, height = 56.mpx, textSize = 19)
            StageButton(
                stringResource(R.string.keys_action_save),
                onClick = { saveKey() },
                height = 56.mpx,
                textSize = 19,
                tinted = true,
                modifier = Modifier.focusRequester(saveFocus),
            )
        },
    ) {
        Text(stringResource(service.whereRes), style = stageText(16, 500), color = StageColors.Muted)
        Column(Modifier.padding(top = 16.mpx)) {
            if (onPhone != null) {
                StagePopupOption(
                    title = stringResource(R.string.settings_metadata_key_from_phone),
                    subtitle = stringResource(R.string.settings_metadata_key_from_phone_desc),
                    onClick = { if (!busy) onPhone() },
                    modifier = Modifier.focusRequester(firstFocus),
                    leading = { StagePopupIcon(OwnTVIcon.PHONE) },
                )
            }
            StageFieldRow(
                icon = OwnTVIcon.PENCIL,
                label = stringResource(R.string.keys_field_label),
                value = draft,
                onValueChange = onDraftChange,
                modifier = Modifier
                    .padding(top = if (onPhone != null) 12.mpx else 0.mpx)
                    .fillMaxWidth()
                    .then(if (onPhone == null) Modifier.focusRequester(firstFocus) else Modifier),
                placeholder = if (stored.isEmpty()) stringResource(R.string.keys_field_placeholder)
                    else stringResource(R.string.keys_field_placeholder_replace, ApiKeys.mask(stored)),
                password = true,
            )
            // Reserved even while empty, so the buttons do not jump when a result appears.
            Text(
                status?.first.orEmpty(),
                style = stageText(17, 600),
                color = status?.second ?: StageColors.Muted,
                minLines = 2,
                modifier = Modifier.padding(start = 22.mpx, top = 14.mpx),
            )
        }
    }
}

private const val FOCUS_RETURN_DELAY_MS = 80L
private const val POPUP_FOCUS_DELAY_MS = 60L

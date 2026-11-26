package tv.own.owntv.features.onboarding.extras

import android.content.Context
import androidx.annotation.StringRes
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.em
import androidx.tv.material3.Text
import kotlinx.coroutines.delay
import tv.own.owntv.R
import tv.own.owntv.features.keys.ApiKeys
import tv.own.owntv.features.keys.ApiService
import tv.own.owntv.features.keys.KeyCheck
import tv.own.owntv.features.keys.keyCheckMessage
import tv.own.owntv.features.keys.whereRes
import tv.own.owntv.features.settings.StageFieldRow
import tv.own.owntv.ui.components.OwnTVIcon
import tv.own.owntv.ui.stage.StageButton
import tv.own.owntv.ui.stage.StageFocus
import tv.own.owntv.ui.stage.StagePill
import tv.own.owntv.ui.stage.StagePopup
import tv.own.owntv.ui.stage.StageSurface
import tv.own.owntv.ui.theme.StageColors
import tv.own.owntv.ui.theme.mpx
import tv.own.owntv.ui.theme.stageAccent
import tv.own.owntv.ui.theme.stageText

/** What the chapter calls each service: the feature it switches on. */
@get:StringRes
internal val ApiService.extrasTitleRes: Int
    get() = when (this) {
        ApiService.TMDB -> R.string.onboarding_extras_tmdb_title
        ApiService.ELEVENLABS -> R.string.onboarding_extras_elevenlabs_title
        ApiService.TYPESAFE -> R.string.onboarding_extras_typesafe_title
    }

/** One line: what the service switches on and which key it needs. */
@get:StringRes
private val ApiService.extrasLineRes: Int
    get() = when (this) {
        ApiService.TMDB -> R.string.onboarding_extras_tmdb_line
        ApiService.ELEVENLABS -> R.string.onboarding_extras_elevenlabs_line
        ApiService.TYPESAFE -> R.string.onboarding_extras_typesafe_line
    }

private val ApiService.extrasIcon: OwnTVIcon
    get() = when (this) {
        ApiService.TMDB -> OwnTVIcon.IMAGE
        ApiService.ELEVENLABS -> OwnTVIcon.MIC
        ApiService.TYPESAFE -> OwnTVIcon.SEARCH
    }

/**
 * One service: icon, the feature's name, what it needs, and on the right the key's state (never the
 * key itself, only its last four characters). A rejected or unchecked key adds the reason and what
 * OK does. OK opens [KeyEntryPopup].
 */
@Composable
internal fun ExtrasServiceCard(
    service: ApiService,
    stored: String,
    status: ExtrasKeyStatus,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    StageSurface(
        onClick = onClick,
        radius = 22.mpx,
        focusStyle = StageFocus.FX,
        idle = Modifier.background(StageColors.Ink.copy(alpha = 0.05f), RoundedCornerShape(22.mpx)),
        modifier = modifier.fillMaxWidth().heightIn(min = 104.mpx),
    ) { focused ->
        // Resolved inside the surface, so the colours follow its focused (light) palette.
        val (state, tone, detail) = statusTexts(service, stored, status)
        Row(
            Modifier.fillMaxWidth().padding(horizontal = 24.mpx, vertical = 16.mpx),
            horizontalArrangement = Arrangement.spacedBy(20.mpx),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            OwnTVIcon(service.extrasIcon, if (focused) StageColors.Text else StageColors.Muted, Modifier.size(30.mpx))
            Column(Modifier.weight(1f)) {
                Text(
                    stringResource(service.extrasTitleRes),
                    style = stageText(22, 800),
                    color = StageColors.Text,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                Text(
                    stringResource(service.extrasLineRes),
                    style = stageText(16, 500),
                    color = StageColors.Muted,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.padding(top = 4.mpx),
                )
                if (detail != null) {
                    Text(
                        detail,
                        style = stageText(15, 600),
                        color = tone,
                        maxLines = 3,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.padding(top = 6.mpx),
                    )
                }
            }
            Text(
                state,
                style = stageText(17, 700),
                color = tone,
                textAlign = TextAlign.End,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.widthIn(max = 220.mpx),
            )
        }
    }
}

/** The state on the right, its colour, and an optional explanation under the line. */
@Composable
private fun statusTexts(service: ApiService, stored: String, status: ExtrasKeyStatus): Triple<String, Color, String?> =
    when (status) {
        ExtrasKeyStatus.Checking -> Triple(stringResource(R.string.onboarding_extras_state_checking), StageColors.Muted, null)
        is ExtrasKeyStatus.Checked -> when (status.result) {
            KeyCheck.Valid -> Triple(stringResource(R.string.onboarding_extras_state_works, ApiKeys.mask(status.key)), StageColors.Ok, null)
            KeyCheck.Invalid -> Triple(
                stringResource(R.string.onboarding_extras_state_rejected),
                StageColors.Danger,
                keyCheckMessage(service, status.result, status.key) + " " + stringResource(R.string.onboarding_extras_detail_rejected),
            )
            is KeyCheck.Unreachable -> Triple(
                stringResource(R.string.onboarding_extras_state_unchecked),
                StageColors.Warn,
                keyCheckMessage(service, status.result, status.key) + " " + stringResource(R.string.onboarding_extras_detail_retry),
            )
            KeyCheck.Unknown -> Triple(stringResource(R.string.onboarding_extras_state_saved, ApiKeys.mask(status.key)), StageColors.Ok, null)
        }
        ExtrasKeyStatus.Idle ->
            if (stored.isEmpty()) Triple(stringResource(R.string.onboarding_extras_state_none), StageColors.Muted, null)
            else Triple(stringResource(R.string.onboarding_extras_state_saved, ApiKeys.mask(stored)), StageColors.Ok, null)
    }

/**
 * The phone side: the QR code and the address of [ExtrasPhoneServer]'s page, or why there is none.
 * A failed or locked link offers a new one ([onRestart]). [received] counts the keys of the last
 * submission from the phone.
 */
@Composable
internal fun ExtrasPhonePanel(
    state: PhoneLinkState,
    received: Int,
    onRestart: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(
        modifier
            .background(StageColors.Ink.copy(alpha = 0.05f), RoundedCornerShape(22.mpx))
            .padding(22.mpx),
    ) {
        Text(
            stringResource(R.string.onboarding_extras_phone_heading).uppercase(),
            style = stageText(14, 800, 0.12f.em),
            color = stageAccent.accent,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
        )
        when (state) {
            is PhoneLinkState.Ready -> {
                state.qr?.let { qr ->
                    Box(
                        Modifier
                            .padding(top = 14.mpx)
                            .size(184.mpx)
                            .clip(RoundedCornerShape(16.mpx))
                            .background(Color.White)
                            .padding(10.mpx),
                    ) {
                        Image(
                            bitmap = qr.asImageBitmap(),
                            contentDescription = stringResource(R.string.common_qr_code_companion_url),
                            contentScale = ContentScale.Fit,
                        )
                    }
                }
                PanelLine(stringResource(R.string.onboarding_extras_phone_scan), StageColors.Body, top = 14)
                PanelLine(stringResource(R.string.onboarding_extras_phone_or_open), StageColors.Muted, top = 10)
                Text(state.url, style = stageText(18, 800), color = StageColors.Text, modifier = Modifier.padding(top = 2.mpx))
                PanelLine(stringResource(R.string.onboarding_extras_phone_same_wifi), StageColors.Muted, top = 10, size = 14)
            }
            PhoneLinkState.Starting -> PanelLine(stringResource(R.string.onboarding_extras_phone_starting), StageColors.Muted, top = 14)
            PhoneLinkState.NoNetwork, PhoneLinkState.Failed, PhoneLinkState.Locked -> {
                val text = when (state) {
                    PhoneLinkState.NoNetwork -> R.string.onboarding_extras_phone_no_network
                    PhoneLinkState.Locked -> R.string.onboarding_extras_phone_locked
                    else -> R.string.onboarding_extras_phone_failed
                }
                PanelLine(stringResource(text), StageColors.Warn, top = 14)
                Box(Modifier.padding(top = 14.mpx)) {
                    StagePill(stringResource(R.string.onboarding_extras_phone_new_link), onClick = onRestart, icon = OwnTVIcon.REFRESH)
                }
            }
        }
        if (received > 0) {
            PanelLine(
                pluralStringResource(R.plurals.onboarding_extras_phone_received, received, received),
                StageColors.Ok,
                top = 12,
                weight = 700,
            )
        }
    }
}

@Composable
private fun PanelLine(text: String, color: Color, top: Int, size: Int = 16, weight: Int = 500) {
    Text(text, style = stageText(size, weight), color = color, modifier = Modifier.padding(top = top.mpx))
}

/**
 * Entering [service]'s key with the remote: where to get it, a masked field, and Check and save.
 * With a key already stored: Check again and Remove. Back closes it without changes.
 */
@Composable
internal fun KeyEntryPopup(
    service: ApiService,
    stored: String,
    onSave: (String) -> Unit,
    onRecheck: () -> Unit,
    onRemove: () -> Unit,
    onDismiss: () -> Unit,
) {
    var draft by remember { mutableStateOf("") }
    var empty by remember { mutableStateOf(false) }
    val fieldFocus = remember { FocusRequester() }
    LaunchedEffect(Unit) {
        delay(POPUP_FOCUS_DELAY_MS)
        runCatching { fieldFocus.requestFocus() }
    }
    fun save() {
        val clean = ApiKeys.sanitize(draft)
        if (clean.isEmpty()) {
            empty = true
        } else {
            onSave(clean)
        }
    }
    StagePopup(
        onDismiss = onDismiss,
        title = stringResource(service.extrasTitleRes),
        body = stringResource(service.whereRes),
        buttons = {
            if (stored.isNotEmpty()) {
                StageButton(stringResource(R.string.onboarding_extras_action_remove), onClick = onRemove, height = 56.mpx, textSize = 19)
                StageButton(stringResource(R.string.onboarding_extras_action_recheck), onClick = onRecheck, height = 56.mpx, textSize = 19)
            }
            StageButton(stringResource(R.string.onboarding_extras_action_save), onClick = { save() }, height = 56.mpx, textSize = 19, tinted = true)
        },
    ) {
        StageFieldRow(
            icon = OwnTVIcon.PENCIL,
            label = stringResource(R.string.onboarding_extras_field_label),
            value = draft,
            onValueChange = {
                draft = it
                empty = false
            },
            modifier = Modifier.fillMaxWidth().focusRequester(fieldFocus),
            placeholder = if (stored.isEmpty()) stringResource(R.string.onboarding_extras_field_placeholder)
                else stringResource(R.string.onboarding_extras_field_placeholder_replace, ApiKeys.mask(stored)),
            password = true,
        )
        Text(
            if (empty) stringResource(R.string.onboarding_extras_field_empty) else stringResource(R.string.onboarding_extras_field_tip),
            style = stageText(16, 500),
            color = if (empty) StageColors.Warn else StageColors.Muted,
            minLines = 2,
            modifier = Modifier.padding(start = 22.mpx, top = 14.mpx),
        )
    }
}

/**
 * The texts of the phone page, in the order of the template's placeholders (see
 * `res/raw/onboarding_extras_phone.html`), resolved in the TV's current language.
 */
@Composable
internal fun extrasPhonePageTexts(): List<String> = listOf(
    LocalConfiguration.current.locales[0].language,
    stringResource(R.string.onboarding_extras_web_title),
    stringResource(R.string.onboarding_extras_web_heading),
    stringResource(R.string.onboarding_extras_web_intro),
    stringResource(ApiService.TMDB.extrasTitleRes),
    stringResource(ApiService.TMDB.whereRes),
    stringResource(ApiService.ELEVENLABS.extrasTitleRes),
    stringResource(ApiService.ELEVENLABS.whereRes),
    stringResource(ApiService.TYPESAFE.extrasTitleRes),
    stringResource(ApiService.TYPESAFE.whereRes),
    stringResource(R.string.onboarding_extras_web_get_key),
    stringResource(R.string.onboarding_extras_web_placeholder),
    stringResource(R.string.onboarding_extras_web_send),
    stringResource(R.string.onboarding_extras_web_sent),
    stringResource(R.string.onboarding_extras_web_sent),
    stringResource(R.string.onboarding_extras_web_error),
    stringResource(R.string.onboarding_extras_web_privacy),
)

/** Fills the phone page's template with [texts] ([extrasPhonePageTexts]), HTML-escaped. */
internal object ExtrasPhonePage {
    /** The placeholder of the notice a plain form post shows; empty on a first visit. */
    private const val NOTICE = 13

    fun render(context: Context, texts: List<String>, sent: Boolean): String {
        var html = context.resources.openRawResource(R.raw.onboarding_extras_phone).bufferedReader().use { it.readText() }
        texts.forEachIndexed { index, text ->
            val value = if (index == NOTICE && !sent) "" else text
            html = html.replace("{{$index}}", escape(value))
        }
        return html
    }

    private fun escape(text: String): String = buildString(text.length) {
        text.forEach { c ->
            when (c) {
                '&' -> append("&amp;")
                '<' -> append("&lt;")
                '>' -> append("&gt;")
                '"' -> append("&quot;")
                '\'' -> append("&#39;")
                else -> append(c)
            }
        }
    }
}

private const val POPUP_FOCUS_DELAY_MS = 80L

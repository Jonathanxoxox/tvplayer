package tv.own.owntv.features.settings

import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import tv.own.owntv.R
import tv.own.owntv.player.PlayerKey
import tv.own.owntv.player.PlayerKeyAction
import tv.own.owntv.player.PlayerKeyMap
import tv.own.owntv.ui.components.OwnTVIcon

/**
 * Settings › Remote control: what OK, Menu and the arrows do in the fullscreen live player, short
 * and held, one row each, plus going back to the defaults. A row opens the list of actions.
 */
@Composable
fun PlayerKeySettingsSection() {
    val context = LocalContext.current
    val map by remember { PlayerKeyMap.flow(context) }.collectAsStateWithLifecycle()
    var editing by remember { mutableStateOf<Pair<PlayerKey, Boolean>?>(null) }
    // Restore default keys asks first (Cancel focused): it throws the user's own assignments away.
    var confirmReset by remember { mutableStateOf(false) }
    val about = stringResource(R.string.tvplayer_keys_help)
    val hints = settingHints(SettingValue.Choice(String()), pinnable = false)

    StageSettingsHeading(stringResource(R.string.tvplayer_keys_heading), PlayerKey.entries.size * 2)
    PlayerKey.entries.forEach { key ->
        val name = playerKeyLabel(key)
        listOf(false, true).forEach { long ->
            val binding = map.getValue(key)
            val title = if (long) stringResource(R.string.tvplayer_keys_long, name) else name
            val action = if (long) binding.long else binding.short
            StageSettingRow(
                icon = playerActionIcon(action),
                title = title,
                desc = playerActionLabel(action),
                value = SettingValue.Opens(null),
                onClick = { editing = key to long },
                help = SettingHelp(title, about, hints = hints),
            )
        }
    }
    val resetTitle = stringResource(R.string.tvplayer_keys_reset)
    val resetDesc = stringResource(R.string.tvplayer_keys_reset_desc)
    StageSettingRow(
        icon = OwnTVIcon.REFRESH,
        title = resetTitle,
        desc = resetDesc,
        value = null,
        onClick = { confirmReset = true },
        help = SettingHelp(resetTitle, resetDesc, hints = settingHints(null, pinnable = false)),
    )
    if (confirmReset) {
        tv.own.owntv.ui.stage.StageConfirm(
            title = resetTitle,
            body = stringResource(R.string.settings_ca_keys_restore_confirm),
            confirm = stringResource(R.string.settings_ca_keys_restore_action),
            onConfirm = { PlayerKeyMap.reset(context); confirmReset = false },
            onCancel = { confirmReset = false },
            focusCancel = true,
        )
    }

    editing?.let { (key, long) ->
        val binding = map.getValue(key)
        val name = playerKeyLabel(key)
        PickerDialog(
            title = if (long) stringResource(R.string.tvplayer_keys_long, name) else name,
            options = PlayerKeyAction.entries.map { it.name to playerActionLabel(it) },
            selected = (if (long) binding.long else binding.short).name,
            leadingIcons = PlayerKeyAction.entries.associate { it.name to playerActionIcon(it) },
            onSelect = { value ->
                PlayerKeyMap.set(context, key, long, PlayerKeyAction.valueOf(value))
                editing = null
            },
            onDismiss = { editing = null },
        )
    }
}

@Composable
private fun playerKeyLabel(key: PlayerKey) = stringResource(
    when (key) {
        PlayerKey.OK -> R.string.tvplayer_key_ok
        PlayerKey.MENU -> R.string.tvplayer_key_menu
        PlayerKey.LEFT -> R.string.tvplayer_key_left
        PlayerKey.RIGHT -> R.string.tvplayer_key_right
        PlayerKey.UP -> R.string.tvplayer_key_up
        PlayerKey.DOWN -> R.string.tvplayer_key_down
        PlayerKey.INFO -> R.string.tvplayer_key_info
        PlayerKey.GUIDE -> R.string.tvplayer_key_guide
        PlayerKey.RED -> R.string.tvplayer_key_red
        PlayerKey.GREEN -> R.string.tvplayer_key_green
        PlayerKey.YELLOW -> R.string.tvplayer_key_yellow
        PlayerKey.BLUE -> R.string.tvplayer_key_blue
    },
)

@Composable
private fun playerActionLabel(action: PlayerKeyAction) = stringResource(
    when (action) {
        PlayerKeyAction.NONE -> R.string.tvplayer_action_none
        PlayerKeyAction.INFO_PANEL -> R.string.tvplayer_action_info
        PlayerKeyAction.OPTIONS -> R.string.tvplayer_action_options
        PlayerKeyAction.GUIDE -> R.string.tvplayer_action_guide
        PlayerKeyAction.CHANNEL_LIST -> R.string.tvplayer_action_channels
        PlayerKeyAction.LAST_CHANNEL -> R.string.tvplayer_action_last
        PlayerKeyAction.CHANNEL_UP -> R.string.tvplayer_action_ch_up
        PlayerKeyAction.CHANNEL_DOWN -> R.string.tvplayer_action_ch_down
        PlayerKeyAction.HISTORY -> R.string.tvplayer_action_history
        PlayerKeyAction.SEARCH -> R.string.tvplayer_action_search
    },
)

private fun playerActionIcon(action: PlayerKeyAction) = when (action) {
    PlayerKeyAction.NONE -> OwnTVIcon.CLOSE
    PlayerKeyAction.INFO_PANEL -> OwnTVIcon.INFO
    PlayerKeyAction.OPTIONS -> OwnTVIcon.MENU
    PlayerKeyAction.GUIDE -> OwnTVIcon.EPG
    PlayerKeyAction.CHANNEL_LIST -> OwnTVIcon.LIVE_TV
    PlayerKeyAction.LAST_CHANNEL -> OwnTVIcon.SWAP
    PlayerKeyAction.CHANNEL_UP -> OwnTVIcon.REMOTE_CHANNEL_UP
    PlayerKeyAction.CHANNEL_DOWN -> OwnTVIcon.REMOTE_CHANNEL_DOWN
    PlayerKeyAction.HISTORY -> OwnTVIcon.HISTORY
    PlayerKeyAction.SEARCH -> OwnTVIcon.SEARCH
}

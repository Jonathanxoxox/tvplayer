package tv.own.owntv.features.settings

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.res.stringResource
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import org.koin.androidx.compose.koinViewModel
import tv.own.owntv.R
import tv.own.owntv.core.database.entity.FOLLOW_GLOBAL_LATENCY_SECS
import tv.own.owntv.core.database.entity.SourceEntity
import tv.own.owntv.core.player.EnginePreference
import tv.own.owntv.core.settings.LiveBuffer
import tv.own.owntv.core.settings.LiveLatency
import tv.own.owntv.core.settings.SettingsRepository
import tv.own.owntv.ui.components.OwnTVIcon

/*
 * Settings › Playlists & EPG › Playlists › ‹Name› › Wiedergabe (Mayo TV, settings clean-up 6 Nov 2026).
 *
 * The six settings one playlist can have of its own. They used to sit on a "Per playlist" page, one row per
 * setting, each asking "Which playlist?" before its value: setting → playlist → value. A playlist's settings
 * now live with the playlist, so it is playlist → setting → value, and every row shows that playlist's value.
 * Each one is a choice in the page's panel whose first option, "Same as general", is the stored null / -1
 * that has always meant "follow the global setting" — nothing about what is stored changed.
 */

/** Picker key for "follow the general setting": the state a null column reads as. */
private const val FOLLOW = ""
/** Catch-up picker keys for a fixed UTC offset carry the minutes after this prefix. */
private const val MANUAL_PREFIX = "MANUAL:"

/** How many of the six settings [PlaylistPlaybackPage] shows this playlist has of its own. */
internal fun SourceEntity.playbackOverrideCount(): Int = listOf(
    liveEnginePreference != null,
    vodEnginePreference != null,
    liveLatencyMode != null,
    livePrerollSecs >= 0,
    liveTuneTimeoutSecs != null,
    catchupTimezone != null,
).count { it }

private enum class PlaybackRow { LIVE_ENGINE, VOD_ENGINE, LATENCY, LATENCY_CUSTOM, PREROLL, TUNE_TIMEOUT, CATCHUP }

/** The titles of this page's rows, as Settings search finds them (each result opens Playlists). */
internal val PLAYLIST_PLAYBACK_SEARCH_ROWS: List<Int> = listOf(
    R.string.settings_src_pp_live_engine, R.string.settings_src_pp_vod_engine, R.string.settings_src_pp_latency,
    R.string.settings_src_pp_preroll, R.string.settings_src_pp_tune_timeout, R.string.settings_src_pp_catchup,
)

@Composable
internal fun PlaylistPlaybackPage(source: SourceEntity, onBack: () -> Unit, modifier: Modifier = Modifier) {
    val vm: SettingsViewModel = koinViewModel()
    val sources by vm.sources.collectAsStateWithLifecycle()
    // The live row, so a value picked here shows at once (the entity handed in is a snapshot).
    val src = sources.firstOrNull { it.id == source.id } ?: source
    var open by remember { mutableStateOf<PlaybackRow?>(null) }
    // The low-latency acknowledgement: what "I understand" does.
    var lowWarning by remember { mutableStateOf<(() -> Unit)?>(null) }
    val firstFocus = remember { FocusRequester() }
    val latencyFocus = remember { FocusRequester() }
    LaunchedEffect(Unit) { kotlinx.coroutines.delay(60); runCatching { firstFocus.requestFocus() } }

    val follow = stringResource(R.string.settings_src_follow)
    val help = stringResource(R.string.settings_src_pp_help)
    val seconds: @Composable (Int) -> String = { stringResource(R.string.settings_live_buffer_seconds, it) }

    val liveEngine = src.liveEnginePreference?.let { n -> EnginePreference.entries.firstOrNull { it.name == n } }
    val vodEngine = src.vodEnginePreference?.let { n -> EnginePreference.entries.firstOrNull { it.name == n } }
    val latency = src.liveLatencyMode?.let { LiveLatency.fromName(it) }
    val engineOptions = listOf(FOLLOW to follow) + EnginePreference.entries.map { it.name to engineLabel(it) }
    val latencyOptions = listOf(FOLLOW to follow) + LiveLatency.entries.map { it.name to stringResource(liveLatencyLabelRes(it)) }
    val prerollOptions = listOf("-1" to follow) + LiveBuffer.PREROLL_CHOICES.map {
        it.toString() to if (it <= 0) stringResource(R.string.common_off) else stringResource(R.string.settings_video_seconds, it)
    }
    val timeoutOptions = listOf(FOLLOW to follow) + tv.own.owntv.player.LiveLadder.BUDGET_CHOICES_SECS.map {
        it.toString() to if (it <= 0) stringResource(R.string.common_never) else seconds(it)
    }
    val catchupOptions = listOf(
        FOLLOW to follow,
        SettingsRepository.CatchupTimezone.DEVICE.name to stringResource(R.string.settings_catchup_timezone_device),
    ) + vm.catchupOffsetChoicesMinutes.map { "$MANUAL_PREFIX$it" to playlistUtcOffsetLabel(it) }

    val liveEngineKey = liveEngine?.name ?: FOLLOW
    val vodEngineKey = vodEngine?.name ?: FOLLOW
    val latencyKey = latency?.name ?: FOLLOW
    val prerollKey = if (src.livePrerollSecs >= 0) src.livePrerollSecs.toString() else "-1"
    val timeoutKey = src.liveTuneTimeoutSecs?.toString() ?: FOLLOW
    val catchupKey = when (src.catchupTimezone) {
        null -> FOLLOW
        SettingsRepository.CatchupTimezone.MANUAL.name -> "$MANUAL_PREFIX${src.catchupOffsetMin ?: 0}"
        else -> src.catchupTimezone ?: FOLLOW
    }
    fun label(options: List<Pair<String, String>>, key: String) = options.firstOrNull { it.first == key }?.second ?: follow

    StageFullPage(
        parents = listOf(src.name),
        title = stringResource(R.string.settings_src_playback),
        count = "",
        onBack = onBack,
        modifier = modifier,
        rowsFocus = firstFocus,
    ) {
        @Composable
        fun row(kind: PlaybackRow, icon: OwnTVIcon, titleRes: Int, lineRes: Int, options: List<Pair<String, String>>, key: String, value: String, focus: Modifier) {
            val title = stringResource(titleRes)
            val shown = SettingValue.Choice(value)
            StageSettingRow(
                icon = icon,
                title = title,
                desc = stringResource(lineRes),
                value = shown,
                onClick = { open = kind },
                modifier = focus,
                help = SettingHelp(
                    title, help, options.map { it.second }, options.indexOfFirst { it.first == key },
                    hints = settingHints(shown, pinnable = false),
                ),
            )
        }
        row(PlaybackRow.LIVE_ENGINE, OwnTVIcon.PLAY, R.string.settings_src_pp_live_engine, R.string.settings_src_pp_line_live_engine,
            engineOptions, liveEngineKey, label(engineOptions, liveEngineKey), Modifier.focusRequester(firstFocus))
        row(PlaybackRow.VOD_ENGINE, OwnTVIcon.PLAY, R.string.settings_src_pp_vod_engine, R.string.settings_src_pp_line_vod_engine,
            engineOptions, vodEngineKey, label(engineOptions, vodEngineKey), Modifier)
        row(PlaybackRow.LATENCY, OwnTVIcon.LIVE_TV, R.string.settings_src_pp_latency, R.string.settings_src_pp_line_latency,
            latencyOptions, latencyKey,
            if (latency == LiveLatency.CUSTOM) seconds(sourceLatencySecs(src)) else label(latencyOptions, latencyKey),
            Modifier.focusRequester(latencyFocus))
        row(PlaybackRow.PREROLL, OwnTVIcon.LIVE_TV, R.string.settings_src_pp_preroll, R.string.settings_src_pp_line_preroll,
            prerollOptions, prerollKey, label(prerollOptions, prerollKey), Modifier)
        row(PlaybackRow.TUNE_TIMEOUT, OwnTVIcon.LIVE_TV, R.string.settings_src_pp_tune_timeout, R.string.settings_src_pp_line_tune_timeout,
            timeoutOptions, timeoutKey, label(timeoutOptions, timeoutKey), Modifier)
        row(PlaybackRow.CATCHUP, OwnTVIcon.CATCHUP, R.string.settings_src_pp_catchup, R.string.settings_src_pp_line_catchup,
            catchupOptions, catchupKey, label(catchupOptions, catchupKey), Modifier)
    }

    val close = { open = null }
    when (open) {
        PlaybackRow.LIVE_ENGINE -> PickerDialog(
            title = stringResource(R.string.settings_src_pp_live_engine), options = engineOptions, selected = liveEngineKey,
            onSelect = { v -> vm.setSourceLiveEngine(src.id, v.takeIf { it != FOLLOW }); close() }, onDismiss = close,
        )
        PlaybackRow.VOD_ENGINE -> PickerDialog(
            title = stringResource(R.string.settings_src_pp_vod_engine), options = engineOptions, selected = vodEngineKey,
            onSelect = { v -> vm.setSourceVodEngine(src.id, v.takeIf { it != FOLLOW }); close() }, onDismiss = close,
        )
        PlaybackRow.LATENCY -> PickerDialog(
            title = stringResource(R.string.settings_src_pp_latency), options = latencyOptions, selected = latencyKey,
            onSelect = { name ->
                when (name) {
                    FOLLOW -> { vm.setSourceLiveLatency(src.id, null, FOLLOW_GLOBAL_LATENCY_SECS); close() }
                    // Same rules as the general setting: Low is acknowledged first, Custom asks for the seconds.
                    LiveLatency.LOW.name -> { close(); lowWarning = { vm.setSourceLiveLatency(src.id, name, FOLLOW_GLOBAL_LATENCY_SECS) } }
                    LiveLatency.CUSTOM.name -> { open = PlaybackRow.LATENCY_CUSTOM }
                    else -> { vm.setSourceLiveLatency(src.id, name, FOLLOW_GLOBAL_LATENCY_SECS); close() }
                }
            },
            onDismiss = close,
        )
        PlaybackRow.LATENCY_CUSTOM -> StepperDialog(
            title = stringResource(R.string.settings_custom_live_buffer),
            value = sourceLatencySecs(src),
            step = 1,
            min = LiveBuffer.CUSTOM_MIN,
            max = LiveBuffer.CUSTOM_MAX,
            format = { seconds(it) },
            onSet = { secs ->
                val commit = { vm.setSourceLiveLatency(src.id, LiveLatency.CUSTOM.name, secs) }
                // A below-Balanced number gets the same acknowledgement the general setting asks for.
                if (LiveBuffer.isLowLatency(secs)) { close(); lowWarning = commit } else commit()
            },
            onReset = { vm.setSourceLiveLatency(src.id, null, FOLLOW_GLOBAL_LATENCY_SECS) },
            onDismiss = close,
        )
        PlaybackRow.PREROLL -> PickerDialog(
            title = stringResource(R.string.settings_src_pp_preroll), options = prerollOptions, selected = prerollKey,
            onSelect = { v -> vm.setSourcePreroll(src.id, v.toIntOrNull() ?: -1); close() }, onDismiss = close,
        )
        PlaybackRow.TUNE_TIMEOUT -> PickerDialog(
            title = stringResource(R.string.settings_src_pp_tune_timeout), options = timeoutOptions, selected = timeoutKey,
            onSelect = { v -> vm.setSourceTuneTimeout(src.id, v.toIntOrNull()); close() }, onDismiss = close,
        )
        PlaybackRow.CATCHUP -> PickerDialog(
            title = stringResource(R.string.settings_src_pp_catchup), options = catchupOptions, selected = catchupKey,
            onSelect = { v ->
                when {
                    v == FOLLOW -> vm.setSourceCatchupTimezone(src.id, null, null)
                    v.startsWith(MANUAL_PREFIX) -> vm.setSourceCatchupTimezone(
                        src.id, SettingsRepository.CatchupTimezone.MANUAL.name, v.removePrefix(MANUAL_PREFIX).toIntOrNull() ?: 0,
                    )
                    else -> vm.setSourceCatchupTimezone(src.id, v, null)
                }
                close()
            },
            onDismiss = close,
        )
        null -> Unit
    }

    lowWarning?.let { commit ->
        val back = {
            lowWarning = null
            // The popup took focus from the page; give it back to the row it was about.
            runCatching { latencyFocus.requestFocus() }
            Unit
        }
        tv.own.owntv.ui.stage.StageConfirm(
            title = stringResource(R.string.settings_low_latency_warning),
            body = stringResource(R.string.settings_low_latency_warning_description),
            confirm = stringResource(R.string.settings_low_latency_understand),
            onConfirm = { commit(); back() },
            onCancel = back,
        )
    }
}

/** A playlist's stored custom-latency seconds, or the general default while it has none (the `-1` sentinel). */
private fun sourceLatencySecs(src: SourceEntity): Int =
    src.liveLatencyCustomSecs.takeIf { it >= LiveBuffer.CUSTOM_MIN } ?: LiveBuffer.CUSTOM_DEFAULT

/** "UTC", "UTC+05:00", "UTC-03:30": a catch-up offset in minutes. */
private fun playlistUtcOffsetLabel(minutes: Int): String {
    if (minutes == 0) return "UTC"
    val sign = if (minutes < 0) "-" else "+"
    val abs = kotlin.math.abs(minutes)
    return "UTC$sign%02d:%02d".format(java.util.Locale.ROOT, abs / 60, abs % 60)
}

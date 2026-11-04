package tv.own.owntv.features.home

import androidx.compose.foundation.background
import androidx.compose.foundation.focusGroup
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.activity.compose.BackHandler
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.focusRestorer
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.layout.boundsInRoot
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import kotlinx.coroutines.delay
import androidx.tv.material3.Text
import tv.own.owntv.R
import tv.own.owntv.core.database.entity.ChannelEntity
import tv.own.owntv.core.database.entity.EpgProgrammeEntity
import tv.own.owntv.core.epg.displayLogoUrl
import tv.own.owntv.features.epg.GuidePreviewSpot
import tv.own.owntv.features.epg.GuideVideo
import tv.own.owntv.features.live.LiveViewModel
import tv.own.owntv.ui.components.ChannelLogoTile
import tv.own.owntv.ui.stage.StageFocus
import tv.own.owntv.ui.stage.StageSurface
import tv.own.owntv.ui.theme.StageColors
import tv.own.owntv.ui.theme.StageRadii
import tv.own.owntv.ui.theme.mpx
import tv.own.owntv.ui.theme.stageAccent
import tv.own.owntv.ui.theme.stageText

/**
 * The Stage layout's channel row (Jonathan, 4 Nov 2026): the focused channel's programme large, a picture
 * beside it, over a strip of logos. ▲ ▼ leave the strip as from any row; ◀ ▶ walk the channels and the
 * stage follows. A held OK opens the row's Hide / Move menu, as on the cards.
 *
 * It plays as the guide and the Sports match page do: OK plays the channel in the picture, with its sound;
 * OK again on the same channel takes it full screen, and Back from there glides it back into the picture,
 * still playing. The picture is the shell's (see [GuidePreviewSpot]): this only says where it goes.
 *
 * [spotlight] is the Spotlight layout (Jonathan, 4 Nov 2026, after the streaming apps): the picture twice as
 * large, and the focused channel starts in it by itself, muted, once focus rests on its logo; OK then takes
 * it full screen with its sound straight away.
 */
@Composable
internal fun HomeStage(
    row: HomeCatalog.Row,
    onNow: Map<Long, EpgProgrammeEntity>,
    onLongClick: (ChannelEntity) -> Unit,
    onFocus: () -> Unit,
    firstItemFocusRequester: FocusRequester?,
    start: Dp,
    track: (FocusRequester) -> Unit,
    liveVm: LiveViewModel,
    /** OK on the channel already playing in the picture: full screen, growing out of it. */
    onPlayFullscreen: (ChannelEntity, List<ChannelEntity>) -> Unit,
    /** False while the player is full screen over Home: Back then belongs to the player. */
    active: Boolean,
    modifier: Modifier = Modifier,
    spotlight: Boolean = false,
) {
    val channels = row.channels
    if (channels.isEmpty()) return
    var shownId by remember(row) { mutableLongStateOf(channels.first().id) }
    val shown = channels.firstOrNull { it.id == shownId } ?: channels.first()
    // The channel in the picture: none until one is picked with OK. It stays while the player is full screen
    // over Home (the page is kept behind the picture), so Back lands on it playing.
    var picked by remember(row.category.key) { mutableStateOf<ChannelEntity?>(null) }
    // Picked with OK it plays with its sound; started by Spotlight on its own, muted.
    var pickedAudible by remember { mutableStateOf(true) }
    var stripFocused by remember { mutableStateOf(false) }
    val previewOn by liveVm.livePreviewEnabled.collectAsStateWithLifecycle()
    LaunchedEffect(picked?.id, previewOn) {
        val ch = picked ?: return@LaunchedEffect
        if (previewOn) liveVm.playPreview(ch, audible = pickedAudible)
    }
    // Spotlight: the channel focus rests on starts by itself; walking the strip opens no stream per logo passed.
    if (spotlight) LaunchedEffect(shown.id, stripFocused, previewOn) {
        if (!stripFocused || !previewOn || picked?.id == shown.id) return@LaunchedEffect
        delay(SPOTLIGHT_DWELL_MS)
        pickedAudible = false
        picked = shown
    }
    // Back first ends the picture, then (with nothing playing) goes on to the menu as before.
    BackHandler(enabled = active && picked != null) { liveVm.stopPreview(); picked = null }
    // No corner left behind for the shell to draw into once the stage is gone.
    DisposableEffect(Unit) { onDispose { GuidePreviewSpot.rect = null } }
    val video = picked?.takeIf { previewOn }
    val programme = onNow[shown.id]
    val now = System.currentTimeMillis()
    // Clear of the clock in the top-right corner, which the big plate would otherwise run into.
    Column(modifier.padding(top = 24.mpx)) {
        Row(
            Modifier.padding(start = start, end = 64.mpx).height(if (spotlight) 560.mpx else 330.mpx),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(56.mpx),
        ) {
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(14.mpx)) {
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(16.mpx)) {
                    Box(Modifier.size(64.mpx).clip(RoundedCornerShape(10.mpx)).background(StageColors.ControlFill)) {
                        ChannelLogoTile(logoUrl = shown.displayLogoUrl, modifier = Modifier.fillMaxSize().padding(6.mpx), fill = Color.Transparent) {
                            Initials(shown, 18)
                        }
                    }
                    Text(stringResource(R.string.home_stage_live), style = stageText(17, 800), color = stageAccent.accent, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    Text(shown.name, style = stageText(18, 600), color = StageColors.Dim, maxLines = 1, overflow = TextOverflow.Ellipsis)
                }
                Text(
                    programme?.title ?: shown.name, style = stageText(if (spotlight) 68 else 58, 800), color = StageColors.Text,
                    maxLines = 2, overflow = TextOverflow.Ellipsis,
                )
                if (programme != null) {
                    Row(horizontalArrangement = Arrangement.spacedBy(18.mpx)) {
                        Text(timeSpan(programme), style = stageText(19, 600), color = StageColors.Dim, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        Text(
                            timeLeftText(((programme.stopMs - now).coerceAtLeast(0L) + 59_999L) / 60_000L),
                            style = stageText(19, 600), color = StageColors.Dim, maxLines = 1, overflow = TextOverflow.Ellipsis,
                        )
                    }
                    Box(Modifier.width(520.mpx).height(6.mpx).clip(RoundedCornerShape(3.mpx)).background(StageColors.ControlFill)) {
                        Box(Modifier.fillMaxHeight().fillMaxWidth(programmeProgress(programme, now)).background(stageAccent.accent))
                    }
                }
            }
            Box(
                Modifier.size(if (spotlight) 960.mpx else 540.mpx, if (spotlight) 540.mpx else 304.mpx)
                    .clip(RoundedCornerShape(StageRadii.Poster)).background(StageColors.ControlFill),
                contentAlignment = Alignment.Center,
            ) {
                // The picture alone: the shell draws it over this spot with the view full screen uses, so it grows
                // into full screen and shrinks back here without the stream changing surface.
                GuideVideo(
                    channel = video,
                    previewEngine = liveVm.previewEngine,
                    showVideo = video != null,
                    shellDrawsVideo = true,
                    modifier = Modifier.fillMaxSize().onGloballyPositioned {
                        GuidePreviewSpot.rect = it.boundsInRoot().translate(-GuidePreviewSpot.shift(), 0f)
                    },
                )
                if (video == null) {
                    ChannelLogoTile(
                        logoUrl = shown.displayLogoUrl,
                        modifier = Modifier.fillMaxSize().padding(horizontal = 150.mpx, vertical = 70.mpx),
                        fill = Color.Transparent,
                    ) { Initials(shown, 56) }
                }
            }
        }
        LazyRow(
            horizontalArrangement = Arrangement.spacedBy(18.mpx),
            contentPadding = PaddingValues(start = start, end = 64.mpx, top = 10.mpx, bottom = 10.mpx),
            modifier = Modifier.focusRestorer().focusGroup().onFocusChanged { stripFocused = it.hasFocus },
        ) {
            itemsIndexed(channels, key = { _, c -> c.id }) { index, channel ->
                StageSurface(
                    // As in the guide: the first OK plays it here, the second takes it full screen.
                    onClick = {
                        when {
                            // Spotlight: straight to full screen, growing out of the picture when it is this channel.
                            spotlight -> onPlayFullscreen(channel, channels)
                            previewOn && picked?.id != channel.id -> { pickedAudible = true; picked = channel }
                            else -> onPlayFullscreen(channel, channels)
                        }
                    },
                    onLongClick = { onLongClick(channel) },
                    radius = StageRadii.Poster,
                    focusStyle = StageFocus.POSTER,
                    idle = Modifier.clip(RoundedCornerShape(StageRadii.Poster)).background(StageColors.ControlFill),
                    contentAlignment = Alignment.Center,
                    modifier = Modifier
                        .size(200.mpx, 112.mpx)
                        .then(if (index == 0 && firstItemFocusRequester != null) Modifier.focusRequester(firstItemFocusRequester) else Modifier)
                        .tracked(track = track)
                        .onFocusChanged { if (it.hasFocus) { onFocus(); shownId = channel.id } },
                ) { _ ->
                    ChannelLogoTile(
                        logoUrl = channel.displayLogoUrl,
                        modifier = Modifier.fillMaxSize().padding(horizontal = 44.mpx, vertical = 16.mpx),
                        fill = Color.Transparent,
                    ) { Initials(channel, 26) }
                }
            }
        }
    }
}

/** How long focus rests on a logo before Spotlight starts its channel. */
private const val SPOTLIGHT_DWELL_MS = 900L

/** A logo's stand-in: the channel's number, else the first letters of its name. */
@Composable
private fun Initials(channel: ChannelEntity, size: Int) {
    Text(
        channel.number?.toString() ?: channel.name.take(2).uppercase(),
        style = stageText(size, 800), color = StageColors.Text, maxLines = 1, overflow = TextOverflow.Ellipsis,
    )
}

/** "14:10 – 15:00" in the TV's own clock format. */
@Composable
private fun timeSpan(programme: EpgProgrammeEntity): String {
    val format = android.text.format.DateFormat.getTimeFormat(LocalContext.current)
    return stringResource(R.string.home_stage_time_span, format.format(java.util.Date(programme.startMs)), format.format(java.util.Date(programme.stopMs)))
}

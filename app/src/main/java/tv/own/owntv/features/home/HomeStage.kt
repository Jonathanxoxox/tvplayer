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
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
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
    /** Spotlight: what it shows while focus is not in the strip ([SpotlightHighlights]), best first. */
    highlights: List<Highlight> = emptyList(),
    onOpenMatch: (tv.own.owntv.core.sports.model.Match) -> Unit = {},
    onResume: (tv.own.owntv.core.home.HeroItem) -> Unit = {},
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
        // The channel before stops at once: the shell would go on drawing it here, over this channel's
        // programme picture, until the new stream starts.
        if (picked != null) { liveVm.stopPreview(); picked = null }
        delay(SPOTLIGHT_DWELL_MS)
        pickedAudible = false
        picked = shown
    }
    // Spotlight's highlights: one at a time, ◀ ▶ on its button go through them. They do not turn by themselves:
    // each would open a stream, and the provider allows one connection.
    var hlIndex by remember(highlights.map { it.key }) { mutableIntStateOf(0) }
    val hl = if (spotlight && !stripFocused) highlights.getOrNull(hlIndex) else null
    // ▲ from the strip goes to the highlights' button, which is only there once the strip has let go of focus.
    val hlButton = remember { FocusRequester() }
    var toButton by remember { mutableStateOf(false) }
    LaunchedEffect(toButton) {
        if (!toButton) return@LaunchedEffect
        withFrameNanos { }
        runCatching { hlButton.requestFocus() }
        toButton = false
    }
    if (spotlight) LaunchedEffect(hl?.key, stripFocused, previewOn) {
        if (stripFocused) return@LaunchedEffect
        when (hl) {
            is Highlight.Live -> if (previewOn && picked?.id != hl.channel.id) {
                if (picked != null) { liveVm.stopPreview(); picked = null }
                delay(HIGHLIGHT_DWELL_MS)
                pickedAudible = false
                picked = hl.channel
            }
            // A match or something to watch on has no live picture here: the one from before stops.
            is Highlight.Sport, is Highlight.Resume -> if (picked != null) { liveVm.stopPreview(); picked = null }
            null -> Unit
        }
    }
    // Back first ends the picture, then (with nothing playing) goes on to the menu as before.
    BackHandler(enabled = active && picked != null) { liveVm.stopPreview(); picked = null }
    // No corner left behind for the shell to draw into once the stage is gone.
    DisposableEffect(Unit) { onDispose { GuidePreviewSpot.rect = null } }
    // A highlight other than a live channel shows no picture; the strip's channel shows the one picked.
    val video = picked?.takeIf { previewOn && (hl == null || (hl is Highlight.Live && hl.channel.id == it.id)) }
    val programme = onNow[shown.id]
    // The picture until the channel plays: the programme's own, from TMDB (ProgrammeArt), else the logo.
    val art = org.koin.compose.koinInject<ProgrammeArt>()
    val picChannel = if (hl is Highlight.Live) hl.channel else shown
    val picProgramme = if (hl is Highlight.Live) hl.programme else if (hl == null) programme else null
    val backdrop by androidx.compose.runtime.produceState<String?>(null, picChannel.id, picProgramme?.startMs) {
        value = null
        val p = picProgramme ?: return@produceState
        delay(ART_DWELL_MS) // walking the strip asks only for where focus stops
        value = art.backdrop(p, picChannel.name)
    }
    val now = System.currentTimeMillis()
    // Clear of the clock in the top-right corner, which the big plate would otherwise run into.
    Column(modifier.padding(top = 24.mpx)) {
        Row(
            Modifier.padding(start = start, end = 64.mpx).height(if (spotlight) 560.mpx else 330.mpx),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(56.mpx),
        ) {
            if (hl != null) {
                HighlightInfo(
                    hl = hl,
                    count = highlights.size,
                    index = hlIndex,
                    onStep = { d -> hlIndex = (hlIndex + d).coerceIn(0, highlights.lastIndex) },
                    onOpen = {
                        when (hl) {
                            is Highlight.Live -> onPlayFullscreen(hl.channel, channels)
                            is Highlight.Sport -> onOpenMatch(hl.match)
                            is Highlight.Resume -> onResume(hl.item)
                        }
                    },
                    onFocus = onFocus,
                    focusRequester = firstItemFocusRequester,
                    ownRequester = hlButton,
                    track = track,
                    now = now,
                    modifier = Modifier.weight(1f),
                )
            } else
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
                if (video == null && hl != null && hl !is Highlight.Live) {
                    HighlightPicture(hl)
                } else if (video == null && backdrop != null) {
                    coil3.compose.AsyncImage(
                        model = backdrop,
                        contentDescription = null,
                        contentScale = androidx.compose.ui.layout.ContentScale.Crop,
                        modifier = Modifier.fillMaxSize(),
                    )
                    // Whose programme it is, small in the corner.
                    Box(
                        Modifier.align(Alignment.BottomStart).padding(24.mpx).size(96.mpx, 64.mpx)
                            .clip(RoundedCornerShape(10.mpx)).background(StageColors.ControlFill),
                    ) {
                        ChannelLogoTile(logoUrl = picChannel.displayLogoUrl, modifier = Modifier.fillMaxSize().padding(8.mpx), fill = Color.Transparent) {
                            Initials(picChannel, 18)
                        }
                    }
                } else if (video == null) {
                    ChannelLogoTile(
                        logoUrl = (if (hl is Highlight.Live) hl.channel else shown).displayLogoUrl,
                        modifier = Modifier.fillMaxSize().padding(horizontal = 150.mpx, vertical = 70.mpx),
                        fill = Color.Transparent,
                    ) { Initials(if (hl is Highlight.Live) hl.channel else shown, 56) }
                }
            }
        }
        LazyRow(
            horizontalArrangement = Arrangement.spacedBy(18.mpx),
            contentPadding = PaddingValues(start = start, end = 64.mpx, top = 10.mpx, bottom = 10.mpx),
            modifier = Modifier.focusRestorer().focusGroup().onFocusChanged { stripFocused = it.hasFocus }
                .onPreviewKeyEvent { e ->
                    if (spotlight && highlights.isNotEmpty() && e.type == KeyEventType.KeyDown && e.key == Key.DirectionUp) {
                        stripFocused = false
                        toButton = true
                        true
                    } else false
                },
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
                        // With highlights, the row's way in is their button above the strip.
                        .then(if (index == 0 && firstItemFocusRequester != null && !(spotlight && highlights.isNotEmpty())) Modifier.focusRequester(firstItemFocusRequester) else Modifier)
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

/** How long focus rests on a channel before its programme's picture is looked up. */
private const val ART_DWELL_MS = 350L

/** How long a live highlight is shown before its channel starts in the picture. */
private const val HIGHLIGHT_DWELL_MS = 2000L

/** How long focus rests on a logo before Spotlight starts its channel. */
private const val SPOTLIGHT_DWELL_MS = 1500L

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

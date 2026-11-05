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
    // Spotlight starts nothing by itself (Jonathan, 5 Nov 2026): focus on a logo shows that programme's picture,
    // OK plays it here with its sound, OK again takes it full screen.
    // Spotlight's highlights, Netflix-like: one at a time with its picture, ◀ ▶ on its button go through them.
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
    // Focus out of the strip (to the highlights, or on to other rows): the channel playing here stops, and the
    // highlights show their pictures again. A moment's grace, so coming back from full screen keeps it playing.
    // Only while Home is in front: full screen takes the focus too, and must keep its picture.
    if (spotlight) LaunchedEffect(stripFocused, active) {
        if (!active || stripFocused || picked == null) return@LaunchedEffect
        delay(STRIP_LEFT_GRACE_MS)
        liveVm.stopPreview()
        picked = null
    }
    // Back from full screen: focus to the logo of the channel playing here, not wherever Home would put it.
    val pickedLogo = remember { FocusRequester() }
    LaunchedEffect(active) {
        val back = picked ?: return@LaunchedEffect
        if (!active) return@LaunchedEffect
        // The text beside the picture follows: Home may have built the row anew, back on its first channel.
        shownId = back.id
        delay(RETURN_FOCUS_MS)
        runCatching { pickedLogo.requestFocus() }
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
    // The row's pictures ahead, once Home has settled: browsing then shows them at once. Only their
    // addresses and the files on disk; decoded, thirty 1080p pictures would take some 240 MB.
    val artContext = androidx.compose.ui.platform.LocalContext.current
    LaunchedEffect(row.category.key, onNow.size, highlights.size) {
        delay(ART_PREFETCH_DELAY_MS)
        val wanted = highlights.mapNotNull { (it as? Highlight.Live)?.let { h -> h.programme?.let { p -> p to h.channel.name } } } +
            channels.take(ART_PREFETCH_COUNT).mapNotNull { ch -> onNow[ch.id]?.let { it to ch.name } }
        val loader = coil3.SingletonImageLoader.get(artContext)
        for ((p, name) in wanted.distinctBy { it.second }) {
            val url = art.backdrop(p, name) ?: continue
            loader.enqueue(
                coil3.request.ImageRequest.Builder(artContext).data(url)
                    .memoryCachePolicy(coil3.request.CachePolicy.DISABLED).build(),
            )
        }
    }
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
                        .then(if (channel.id == picked?.id) Modifier.focusRequester(pickedLogo) else Modifier)
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

/** Home settles first; then the row's programme pictures are fetched ahead. */
private const val ART_PREFETCH_DELAY_MS = 4000L
private const val ART_PREFETCH_COUNT = 30

/** How long focus rests on a channel before its programme's picture is looked up. */
private const val ART_DWELL_MS = 350L

/** Focus that leaves the strip only to come straight back (full screen and Back) does not stop the channel. */
private const val STRIP_LEFT_GRACE_MS = 800L

/** After Home puts focus back on its own (60 ms), the stage takes it to the playing channel's logo. */
private const val RETURN_FOCUS_MS = 150L

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

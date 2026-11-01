package tv.own.owntv.launcher.apps

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.focusGroup
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.focusRestorer
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.core.graphics.drawable.toBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.tv.material3.Text
import coil3.compose.AsyncImage
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import tv.own.owntv.R
import tv.own.owntv.ui.components.OwnTVIcon
import tv.own.owntv.ui.components.longPressMenuGuard
import tv.own.owntv.ui.stage.StageFocus
import tv.own.owntv.ui.stage.StagePopup
import tv.own.owntv.ui.stage.StagePopupOption
import tv.own.owntv.ui.stage.StageStill
import tv.own.owntv.ui.stage.StageSurface
import tv.own.owntv.ui.theme.StageColors
import tv.own.owntv.ui.theme.StageRadii
import tv.own.owntv.ui.theme.mpx
import tv.own.owntv.ui.theme.stageText

/** The launcher settings, live. */
@Composable
fun rememberLauncherConfig(): LauncherConfig {
    val context = LocalContext.current
    return remember(context) { LauncherPrefs.config(context) }.collectAsStateWithLifecycle().value
}

/** Counts up each time Mayo TV comes back to the front, so lists can be read again then. */
@Composable
fun rememberResumeCount(): Int {
    val lifecycle = androidx.lifecycle.compose.LocalLifecycleOwner.current.lifecycle
    var resumes by remember { mutableStateOf(0) }
    androidx.compose.runtime.DisposableEffect(lifecycle) {
        val observer = androidx.lifecycle.LifecycleEventObserver { _, e ->
            if (e == androidx.lifecycle.Lifecycle.Event.ON_RESUME) resumes++
        }
        lifecycle.addObserver(observer)
        onDispose { lifecycle.removeObserver(observer) }
    }
    return resumes
}

/** The apps in the Apps row: the user's own list, or the usual suspects that are installed. */
fun rowApps(config: LauncherConfig, apps: List<LauncherApp>): List<LauncherApp> {
    val byPkg = apps.associateBy { it.pkg }
    val wanted = config.rowApps.ifEmpty { LauncherConfig.DEFAULT_ROW }
    return wanted.mapNotNull { byPkg[it] }
}

/** The grid's order: the user's own first, then the rest by name. */
fun orderedApps(config: LauncherConfig, apps: List<LauncherApp>): List<LauncherApp> {
    val byPkg = apps.associateBy { it.pkg }
    val first = config.appOrder.mapNotNull { byPkg[it] }
    val firstSet = first.map { it.pkg }.toSet()
    return first + apps.filter { it.pkg !in firstSet }
}

/** Puts the row's or the grid's list into an editable form: the shown order, so nothing jumps. */
internal fun List<String>.moved(pkg: String, by: Int): List<String> {
    val from = indexOf(pkg)
    if (from < 0) return this
    val to = (from + by).coerceIn(0, size - 1)
    if (to == from) return this
    return toMutableList().apply { add(to, removeAt(from)) }
}

/** The app's banner (or icon), loaded off the main thread once. */
@Composable
fun rememberAppArt(pkg: String): ImageBitmap? {
    val context = LocalContext.current
    return produceState<ImageBitmap?>(null, pkg) {
        value = withContext(Dispatchers.IO) { LauncherApps.artwork(context, pkg) }
    }.value
}

/**
 * One app as a 16:9 card: its TV banner edge to edge, or its icon and name on the glass when it has
 * none. No name under the card (Jonathan: only the banners).
 */
@Composable
fun AppCard(
    app: LauncherApp,
    width: Dp,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    onLongClick: (() -> Unit)? = null,
    dimmed: Boolean = false,
    lifted: Boolean = false,
) {
    val art = rememberAppArt(app.pkg)
    val height = width * 9f / 16f
    StageSurface(
        onClick = onClick,
        onLongClick = onLongClick,
        radius = StageRadii.Poster,
        focusStyle = StageFocus.POSTER,
        highlighted = lifted,
        contentAlignment = Alignment.Center,
        modifier = modifier.size(width, height),
    ) { _ ->
        Box(
            Modifier
                .fillMaxSize()
                .clip(RoundedCornerShape(StageRadii.Poster))
                .background(StageColors.GlassOff),
            contentAlignment = Alignment.Center,
        ) {
            if (art != null && LauncherApps.isBanner(art)) {
                Image(art, contentDescription = app.label, contentScale = ContentScale.Crop, modifier = Modifier.fillMaxSize())
            } else {
                Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(8.mpx)) {
                    if (art != null) Image(art, contentDescription = null, modifier = Modifier.size(height * 0.45f))
                    Text(
                        app.label, style = stageText(16, 700), color = StageColors.Text, maxLines = 1,
                        overflow = TextOverflow.Ellipsis, textAlign = TextAlign.Center,
                        modifier = Modifier.padding(horizontal = 10.mpx),
                    )
                }
            }
            if (dimmed) Box(Modifier.fillMaxSize().background(StageColors.Scrim))
        }
    }
}

/** The "All apps" card at the end of the Apps row. */
@Composable
fun AllAppsCard(width: Dp, onClick: () -> Unit, modifier: Modifier = Modifier) {
    StageSurface(
        onClick = onClick,
        radius = StageRadii.Poster,
        focusStyle = StageFocus.POSTER,
        contentAlignment = Alignment.Center,
        idle = Modifier.clip(RoundedCornerShape(StageRadii.Poster)).background(StageColors.GlassOff),
        modifier = modifier.size(width, width * 9f / 16f),
    ) { _ ->
        Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(10.mpx)) {
            OwnTVIcon(OwnTVIcon.GRID, StageColors.Text, Modifier.size(34.mpx))
            Text(stringResource(R.string.launcher_all_apps), style = stageText(17, 700), color = StageColors.Text, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
    }
}

private val RowCardWidth = 300.mpx

/**
 * Home's Apps row, right under the big picture: the chosen apps as banners, then All apps. A held OK
 * opens the app's menu: move it, take it out of the row, its app info.
 */
@Composable
fun HomeAppsRow(
    start: Dp,
    onFocus: () -> Unit,
    onAllApps: () -> Unit,
    firstFocusRequester: FocusRequester?,
    track: (FocusRequester) -> Unit,
    modifier: Modifier = Modifier,
) {
    val context = LocalContext.current
    val config = rememberLauncherConfig()
    val apps by LauncherApps.apps.collectAsStateWithLifecycle()
    val resumes = rememberResumeCount()
    LaunchedEffect(resumes) { LauncherApps.refreshApps(context) }
    val row = rowApps(config, apps)
    var menuFor by remember { mutableStateOf<LauncherApp?>(null) }
    Column(modifier) {
        tv.own.owntv.features.home.HomeRowHeader(stringResource(R.string.launcher_row_apps), small = null, start = start)
        LazyRow(
            horizontalArrangement = Arrangement.spacedBy(20.mpx),
            contentPadding = PaddingValues(start = start, end = 64.mpx, top = 6.mpx, bottom = 6.mpx),
            modifier = Modifier.focusRestorer().focusGroup(),
        ) {
            itemsIndexed(row, key = { _, app -> app.pkg }) { index, app ->
                val requester = remember { FocusRequester() }
                AppCard(
                    app = app,
                    width = RowCardWidth,
                    onClick = { LauncherApps.open(context, app) },
                    onLongClick = { menuFor = app },
                    modifier = Modifier
                        .then(if (index == 0 && firstFocusRequester != null) Modifier.focusRequester(firstFocusRequester) else Modifier.focusRequester(requester))
                        .onFocusChanged { if (it.hasFocus) { onFocus(); track(if (index == 0 && firstFocusRequester != null) firstFocusRequester else requester) } },
                )
            }
            item(key = "all_apps") {
                val requester = remember { FocusRequester() }
                AllAppsCard(
                    width = RowCardWidth,
                    onClick = onAllApps,
                    modifier = Modifier
                        .then(if (row.isEmpty() && firstFocusRequester != null) Modifier.focusRequester(firstFocusRequester) else Modifier.focusRequester(requester))
                        .onFocusChanged { if (it.hasFocus) { onFocus(); track(if (row.isEmpty() && firstFocusRequester != null) firstFocusRequester else requester) } },
                )
            }
        }
    }
    menuFor?.let { app ->
        val shown = row.map { it.pkg }
        AppMenu(
            app = app,
            onDismiss = { menuFor = null },
            entries = buildList {
                add(stringResource(R.string.launcher_menu_open) to { LauncherApps.open(context, app); Unit })
                if (shown.indexOf(app.pkg) > 0) add(stringResource(R.string.launcher_menu_left) to {
                    LauncherPrefs.update(context) { it.copy(rowApps = shown.moved(app.pkg, -1)) }
                })
                if (shown.indexOf(app.pkg) in 0 until shown.size - 1) add(stringResource(R.string.launcher_menu_right) to {
                    LauncherPrefs.update(context) { it.copy(rowApps = shown.moved(app.pkg, 1)) }
                })
                add(stringResource(R.string.launcher_menu_remove_row) to {
                    LauncherPrefs.update(context) { it.copy(rowApps = shown - app.pkg) }
                })
                add(stringResource(R.string.launcher_menu_info) to { LauncherApps.openAppInfo(context, app.pkg) })
            },
        )
    }
}

/** A held OK on an app: its name as the title, then the actions. */
@Composable
internal fun AppMenu(app: LauncherApp, onDismiss: () -> Unit, entries: List<Pair<String, () -> Unit>>) {
    // Opened by a held OK: that press's repeats and its release must not "click" the first row (Open),
    // which opened the app at once instead of leaving the menu up (Jonathan, 1 Nov 2026).
    StagePopup(onDismiss = onDismiss, title = app.label, width = 640.mpx, modifier = Modifier.longPressMenuGuard()) {
        Column(verticalArrangement = Arrangement.spacedBy(6.mpx), modifier = Modifier.padding(top = 18.mpx)) {
            entries.forEach { (label, action) ->
                StagePopupOption(title = label, onClick = { onDismiss(); action() })
            }
        }
    }
}

/**
 * The other apps' channels at the end of Home: the system's Continue watching per app, then each app's
 * own rows (Nuvio, TizenTube …), every one with the app's badge before its name.
 */
@Composable
fun AppChannelRows(
    start: Dp,
    onFocus: () -> Unit,
    track: (FocusRequester) -> Unit,
    modifier: Modifier = Modifier,
) {
    val context = LocalContext.current
    val config = rememberLauncherConfig()
    val channels by LauncherApps.channels.collectAsStateWithLifecycle()
    val apps by LauncherApps.apps.collectAsStateWithLifecycle()
    val resumes = rememberResumeCount()
    LaunchedEffect(config.appChannels, resumes) {
        if (!config.appChannels) return@LaunchedEffect
        LauncherApps.refreshChannels(context)
        if (apps.isEmpty()) LauncherApps.refreshApps(context)
    }
    val shown = channels.filter { it.key !in config.hiddenChannels }
    // Android shows other apps' rows only once Mayo TV may read them: until then, one card that asks.
    val canRead = androidx.compose.runtime.remember(resumes) { LauncherApps.canReadChannels(context) }
    val ask = androidx.activity.compose.rememberLauncherForActivityResult(
        androidx.activity.result.contract.ActivityResultContracts.RequestMultiplePermissions(),
    ) { LauncherApps.canReadChannels(context) }
    if (config.appChannels && !canRead) {
        Column(modifier) {
            tv.own.owntv.features.home.HomeRowHeader(stringResource(R.string.launcher_app_channels), small = null, start = start)
            val requester = remember { FocusRequester() }
            StageSurface(
                onClick = { ask.launch(LauncherApps.PERMISSIONS) },
                radius = StageRadii.Poster,
                focusStyle = StageFocus.FX,
                idle = Modifier.clip(RoundedCornerShape(StageRadii.Poster)).background(StageColors.GlassOff),
                modifier = Modifier
                    .padding(start = start)
                    .width(620.mpx)
                    .focusRequester(requester)
                    .onFocusChanged { if (it.hasFocus) { onFocus(); track(requester) } },
            ) { _ ->
                Column(Modifier.padding(horizontal = 26.mpx, vertical = 20.mpx), verticalArrangement = Arrangement.spacedBy(6.mpx)) {
                    Text(stringResource(R.string.launcher_app_channels_allow_title), style = stageText(20, 700), color = StageColors.Text, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    Text(stringResource(R.string.launcher_app_channels_allow_line), style = stageText(16, 500), color = StageColors.Muted, maxLines = 2, overflow = TextOverflow.Ellipsis)
                }
            }
        }
        return
    }
    if (!config.appChannels || shown.isEmpty()) return
    val labels = apps.associate { it.pkg to it.label }
    val continueTitle = stringResource(R.string.launcher_row_continue)
    Column(modifier, verticalArrangement = Arrangement.spacedBy(40.mpx)) {
        shown.forEach { channel ->
            val appLabel = labels[channel.pkg] ?: channel.pkg
            val title = if (channel.key.endsWith("/${LauncherApps.WATCH_NEXT}")) continueTitle else channel.name.ifBlank { appLabel }
            Column {
                Row(
                    Modifier.padding(start = start).height(46.mpx),
                    horizontalArrangement = Arrangement.spacedBy(14.mpx),
                    verticalAlignment = Alignment.Top,
                ) {
                    AppBadge(channel.pkg, Modifier.padding(top = 4.mpx))
                    Text(title, style = stageText(26, 800), color = StageColors.Text, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    Text(appLabel, style = stageText(17, 700), color = StageColors.Dim, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.padding(top = 8.mpx))
                }
                LazyRow(
                    horizontalArrangement = Arrangement.spacedBy(20.mpx),
                    contentPadding = PaddingValues(start = start, end = 64.mpx),
                    modifier = Modifier.focusRestorer().focusGroup(),
                ) {
                    itemsIndexed(channel.programs, key = { _, p -> p.id }) { _, program ->
                        val requester = remember { FocusRequester() }
                        val portrait = program.aspect < 1f
                        val h = if (portrait) 294.mpx else 180.mpx
                        val w = (h * program.aspect).coerceIn(160.mpx, 360.mpx)
                        StageStill(
                            title = program.title,
                            line = program.subtitle.ifBlank { null },
                            onClick = { LauncherApps.openProgram(context, channel.pkg, program) },
                            width = w,
                            height = h,
                            titleSize = 17,
                            lineSize = 14,
                            progress = program.progress,
                            modifier = Modifier
                                .focusRequester(requester)
                                .onFocusChanged { if (it.hasFocus) { onFocus(); track(requester) } },
                        ) {
                            AsyncImage(model = program.poster, contentDescription = null, contentScale = ContentScale.Crop, modifier = Modifier.fillMaxSize())
                        }
                    }
                }
            }
        }
    }
}

/** The app's icon, small, before a row's name. */
@Composable
private fun AppBadge(pkg: String, modifier: Modifier = Modifier) {
    val context = LocalContext.current
    val icon = produceState<ImageBitmap?>(null, pkg) {
        value = withContext(Dispatchers.IO) {
            runCatching { context.packageManager.getApplicationIcon(pkg).toBitmap(64, 64).asImageBitmap() }.getOrNull()
        }
    }.value ?: return
    Image(icon, contentDescription = null, modifier = modifier.size(30.mpx).clip(RoundedCornerShape(8.mpx)))
}

package tv.own.owntv.launcher.apps

import androidx.compose.foundation.focusGroup
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.itemsIndexed
import androidx.compose.foundation.lazy.grid.rememberLazyGridState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.tv.material3.Text
import tv.own.owntv.R
import tv.own.owntv.ui.stage.StageButton
import tv.own.owntv.ui.theme.StageColors
import tv.own.owntv.ui.theme.mpx
import tv.own.owntv.ui.theme.stageAccent
import tv.own.owntv.ui.theme.stageText

private const val COLUMNS = 5

/**
 * The Apps section: every installed app as a banner in a grid. OK opens it; a held OK opens its menu —
 * move it (then ◀ ▶ ▲ ▼ and OK), put it in Home's Apps row, hide it, its app info.
 */
@Composable
fun AppsScreen(
    onChildFocused: () -> Unit,
    onEntryHook: (((() -> Boolean)?) -> Unit)? = null,
    /** Opened from Home's All apps card: focus goes to the first app (from the menu it stays there). */
    takeFocus: Boolean = false,
    onFocusTaken: () -> Unit = {},
    contentStart: Dp = 40.mpx,
    modifier: Modifier = Modifier,
) {
    val context = LocalContext.current
    val config = rememberLauncherConfig()
    val apps by LauncherApps.apps.collectAsStateWithLifecycle()
    // The list again whenever Mayo TV comes back: an app may have been installed or removed meanwhile.
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    var resumes by remember { mutableStateOf(0) }
    DisposableEffect(lifecycle) {
        val observer = LifecycleEventObserver { _, e -> if (e == Lifecycle.Event.ON_RESUME) resumes++ }
        lifecycle.addObserver(observer)
        onDispose { lifecycle.removeObserver(observer) }
    }
    LaunchedEffect(resumes) { LauncherApps.refreshApps(context) }

    var showHidden by remember { mutableStateOf(false) }
    var menuFor by remember { mutableStateOf<LauncherApp?>(null) }
    var moving by remember { mutableStateOf<String?>(null) }
    var lastFocused by remember { mutableStateOf<String?>(null) }
    val requesters = remember { HashMap<String, FocusRequester>() }
    fun requesterOf(pkg: String) = requesters.getOrPut(pkg) { FocusRequester() }

    val ordered = orderedApps(config, apps)
    val visible = ordered.filter { it.pkg !in config.hiddenApps }
    val hidden = ordered.filter { it.pkg in config.hiddenApps }
    val shown = if (showHidden) visible + hidden else visible
    val rowSet = rowApps(config, apps).map { it.pkg }.toSet()
    val gridState = rememberLazyGridState()

    val entry by rememberUpdatedState((lastFocused ?: shown.firstOrNull()?.pkg)?.let { requesterOf(it) })
    DisposableEffect(onEntryHook) {
        onEntryHook?.invoke { entry?.let { runCatching { it.requestFocus() }.getOrDefault(false) } ?: false }
        onDispose { onEntryHook?.invoke(null) }
    }

    LaunchedEffect(takeFocus, shown.isNotEmpty()) {
        if (!takeFocus || shown.isEmpty()) return@LaunchedEffect
        withFrameNanos { }
        runCatching { requesterOf(shown.first().pkg).requestFocus() }
        onFocusTaken()
    }

    // Moving: the app follows the arrows through the grid's own order, and focus follows the app.
    fun move(by: Int) {
        val pkg = moving ?: return
        val order = visible.map { it.pkg }
        LauncherPrefs.update(context) { it.copy(appOrder = order.moved(pkg, by)) }
    }
    LaunchedEffect(config.appOrder, moving) {
        val pkg = moving ?: return@LaunchedEffect
        withFrameNanos { }
        runCatching { requesterOf(pkg).requestFocus() }
    }

    Column(modifier.fillMaxSize().padding(start = contentStart, top = 40.mpx)) {
        Row(Modifier.padding(start = 24.mpx, bottom = 24.mpx), horizontalArrangement = Arrangement.spacedBy(16.mpx), verticalAlignment = Alignment.Bottom) {
            Text(stringResource(R.string.launcher_apps_title), style = stageText(40, 800), color = StageColors.Text, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Text(
                if (moving != null) stringResource(R.string.launcher_apps_moving) else androidx.compose.ui.res.pluralStringResource(R.plurals.launcher_apps_line, visible.size, visible.size),
                style = stageText(18, 600),
                color = if (moving != null) stageAccent.accent else StageColors.Muted,
                maxLines = 1, overflow = TextOverflow.Ellipsis,
                modifier = Modifier.padding(bottom = 6.mpx),
            )
        }
        LazyVerticalGrid(
            columns = GridCells.Fixed(COLUMNS),
            state = gridState,
            horizontalArrangement = Arrangement.spacedBy(24.mpx),
            verticalArrangement = Arrangement.spacedBy(24.mpx),
            contentPadding = PaddingValues(start = 24.mpx, end = 140.mpx, top = 8.mpx, bottom = 60.mpx),
            modifier = Modifier
                .fillMaxSize()
                .onFocusChanged { if (it.hasFocus) onChildFocused() }
                .onPreviewKeyEvent { e ->
                    if (moving == null) return@onPreviewKeyEvent false
                    if (e.type != KeyEventType.KeyDown) return@onPreviewKeyEvent true
                    when (e.key) {
                        Key.DirectionLeft -> move(-1)
                        Key.DirectionRight -> move(1)
                        Key.DirectionUp -> move(-COLUMNS)
                        Key.DirectionDown -> move(COLUMNS)
                        Key.DirectionCenter, Key.Enter, Key.NumPadEnter, Key.Back, Key.Escape -> moving = null
                        else -> return@onPreviewKeyEvent false
                    }
                    true
                }
                .focusGroup(),
        ) {
            itemsIndexed(shown, key = { _, app -> app.pkg }) { _, app ->
                val isHidden = app.pkg in config.hiddenApps
                Column(horizontalAlignment = Alignment.Start) {
                    AppCard(
                        app = app,
                        width = 280.mpx,
                        dimmed = isHidden,
                        lifted = moving == app.pkg,
                        onClick = { if (moving == null) LauncherApps.open(context, app) },
                        onLongClick = { if (moving == null) menuFor = app },
                        modifier = Modifier
                            .focusRequester(requesterOf(app.pkg))
                            .onFocusChanged { if (it.hasFocus) lastFocused = app.pkg },
                    )
                    Text(
                        app.label,
                        style = stageText(15, 600),
                        color = if (lastFocused == app.pkg) StageColors.Text else StageColors.Dim,
                        maxLines = 1, overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.padding(top = 8.mpx, start = 4.mpx),
                    )
                }
            }
            if (hidden.isNotEmpty() && moving == null) {
                item(key = "hidden", span = { GridItemSpan(maxLineSpan) }) {
                    StageButton(
                        text = stringResource(R.string.launcher_show_hidden, hidden.size),
                        onClick = { showHidden = !showHidden },
                        modifier = Modifier.fillMaxWidth(0.3f),
                    )
                }
            }
        }
    }

    menuFor?.let { app ->
        val isHidden = app.pkg in config.hiddenApps
        val inRow = app.pkg in rowSet
        AppMenu(
            app = app,
            onDismiss = { menuFor = null },
            entries = buildList {
                add(stringResource(R.string.launcher_menu_open) to { LauncherApps.open(context, app); Unit })
                if (!isHidden) add(stringResource(R.string.launcher_menu_move) to { moving = app.pkg })
                add(
                    stringResource(if (inRow) R.string.launcher_menu_remove_row else R.string.launcher_menu_add_row) to {
                        val current = rowApps(config, apps).map { it.pkg }
                        LauncherPrefs.update(context) { it.copy(rowApps = if (inRow) current - app.pkg else current + app.pkg) }
                    },
                )
                add(
                    stringResource(if (isHidden) R.string.launcher_menu_show else R.string.launcher_menu_hide) to {
                        LauncherPrefs.update(context) { it.copy(hiddenApps = if (isHidden) it.hiddenApps - app.pkg else it.hiddenApps + app.pkg) }
                    },
                )
                add(stringResource(R.string.launcher_menu_info) to { LauncherApps.openAppInfo(context, app.pkg) })
            },
        )
    }
}

package tv.own.owntv.launcher.apps

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.database.Cursor
import android.net.Uri
import android.util.LruCache
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.core.graphics.drawable.toBitmap
import android.media.tv.TvContract
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.withContext

/** An app that can be opened from the TV's start screen. */
data class LauncherApp(
    val pkg: String,
    val label: String,
    val component: ComponentName,
)

/** One card of another app's channel, or of the system's Continue watching. */
data class AppProgram(
    val id: Long,
    val title: String,
    val subtitle: String,
    val poster: String?,
    /** Width over height of the artwork (16:9, 2:3 …). */
    val aspect: Float,
    val intentUri: String?,
    val progress: Float?,
)

/** A row another app puts on the TV's start screen (Nuvio's Continue watching, TizenTube's Recommended). */
data class AppChannel(
    val pkg: String,
    val name: String,
    /** Stays the same when the app sets the channel up again: what Settings hides a channel by. */
    val key: String,
    val programs: List<AppProgram>,
)

/**
 * The installed apps and the other apps' channels, read once per visit to Home (and on demand). The
 * channels come from Android's TV provider, which shows another app's rows only to an app holding
 * READ_TV_LISTINGS next to READ_EPG_DATA.
 */
object LauncherApps {
    private val appsState = MutableStateFlow<List<LauncherApp>>(emptyList())
    val apps: StateFlow<List<LauncherApp>> = appsState.asStateFlow()

    private val channelsState = MutableStateFlow<List<AppChannel>>(emptyList())
    val channels: StateFlow<List<AppChannel>> = channelsState.asStateFlow()

    private val art = LruCache<String, ImageBitmap>(64)

    const val READ_EPG = "com.android.providers.tv.permission.READ_EPG_DATA"
    const val READ_LISTINGS = "android.permission.READ_TV_LISTINGS"
    val PERMISSIONS = arrayOf(READ_EPG, READ_LISTINGS)

    fun canReadChannels(context: Context): Boolean = PERMISSIONS.all {
        context.checkSelfPermission(it) == PackageManager.PERMISSION_GRANTED
    }

    suspend fun refreshApps(context: Context) = withContext(Dispatchers.IO) {
        runCatching { appsState.value = loadApps(context.applicationContext) }
    }

    suspend fun refreshChannels(context: Context) = withContext(Dispatchers.IO) {
        if (!canReadChannels(context)) {
            channelsState.value = emptyList()
            return@withContext
        }
        runCatching { channelsState.value = loadChannels(context.applicationContext) }
    }

    private fun loadApps(context: Context): List<LauncherApp> {
        val pm = context.packageManager
        val found = LinkedHashMap<String, LauncherApp>()
        listOf(Intent.CATEGORY_LEANBACK_LAUNCHER, Intent.CATEGORY_LAUNCHER).forEach { category ->
            val intent = Intent(Intent.ACTION_MAIN).addCategory(category)
            pm.queryIntentActivities(intent, 0).forEach { ri ->
                val info = ri.activityInfo ?: return@forEach
                if (info.packageName == context.packageName || info.packageName in found) return@forEach
                found[info.packageName] = LauncherApp(
                    pkg = info.packageName,
                    label = ri.loadLabel(pm)?.toString().orEmpty().ifBlank { info.packageName },
                    component = ComponentName(info.packageName, info.name),
                )
            }
        }
        return found.values.sortedBy { it.label.lowercase() }
    }

    /** The app's TV banner (or, without one, its icon), drawn once and kept. */
    fun artwork(context: Context, pkg: String): ImageBitmap? {
        art.get(pkg)?.let { return it }
        val pm = context.packageManager
        val drawable = runCatching {
            pm.getApplicationBanner(pkg) ?: pm.getLeanbackLaunchIntentForPackage(pkg)?.let { pm.getActivityBanner(it) }
        }.getOrNull() ?: runCatching { pm.getApplicationIcon(pkg) }.getOrNull() ?: return null
        val wide = drawable.intrinsicWidth > drawable.intrinsicHeight
        val bitmap = runCatching {
            if (wide) drawable.toBitmap(320, 180) else drawable.toBitmap(192, 192)
        }.getOrNull()?.asImageBitmap() ?: return null
        art.put(pkg, bitmap)
        return bitmap
    }

    /** Whether [artwork] is a wide banner (drawn edge to edge) rather than an icon (drawn centred). */
    fun isBanner(art: ImageBitmap): Boolean = art.width > art.height

    fun open(context: Context, app: LauncherApp): Boolean {
        val pm = context.packageManager
        val intent = pm.getLeanbackLaunchIntentForPackage(app.pkg)
            ?: pm.getLaunchIntentForPackage(app.pkg)
            ?: Intent(Intent.ACTION_MAIN).setComponent(app.component)
        return start(context, intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
    }

    fun openProgram(context: Context, pkg: String, program: AppProgram): Boolean {
        val uri = program.intentUri
        val intent = if (uri.isNullOrBlank()) null else runCatching {
            if (uri.startsWith("intent:")) Intent.parseUri(uri, Intent.URI_INTENT_SCHEME)
            else Intent(Intent.ACTION_VIEW, Uri.parse(uri))
        }.getOrNull()
        val target = intent ?: context.packageManager.getLeanbackLaunchIntentForPackage(pkg)
            ?: context.packageManager.getLaunchIntentForPackage(pkg) ?: return false
        return start(context, target.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
    }

    fun openAppInfo(context: Context, pkg: String) {
        start(
            context,
            Intent(android.provider.Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.fromParts("package", pkg, null))
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
        )
    }

    private fun start(context: Context, intent: Intent): Boolean =
        runCatching { context.startActivity(intent); true }.getOrDefault(false)

    private fun loadChannels(context: Context): List<AppChannel> {
        val resolver = context.contentResolver
        val out = ArrayList<AppChannel>()
        val projection = arrayOf(
            TvContract.Channels._ID,
            TvContract.Channels.COLUMN_PACKAGE_NAME,
            TvContract.Channels.COLUMN_DISPLAY_NAME,
            TvContract.Channels.COLUMN_INTERNAL_PROVIDER_ID,
            TvContract.Channels.COLUMN_TYPE,
        )
        resolver.query(TvContract.Channels.CONTENT_URI, projection, null, null, null)?.use { c ->
            while (c.moveToNext()) {
                if (c.str(TvContract.Channels.COLUMN_TYPE) != TvContract.Channels.TYPE_PREVIEW) continue
                val pkg = c.str(TvContract.Channels.COLUMN_PACKAGE_NAME).orEmpty()
                if (pkg.isBlank() || pkg == context.packageName) continue
                val id = c.long(TvContract.Channels._ID)
                val programs = loadPrograms(context, TvContract.buildPreviewProgramsUriForChannel(id))
                if (programs.isEmpty()) continue
                val name = c.str(TvContract.Channels.COLUMN_DISPLAY_NAME).orEmpty()
                val internal = c.str(TvContract.Channels.COLUMN_INTERNAL_PROVIDER_ID).orEmpty()
                out += AppChannel(pkg, name, "$pkg/${internal.ifBlank { name }}", programs)
            }
        }
        // The system's Continue watching, one row per app, ahead of the apps' own rows.
        val watchNext = loadPrograms(context, TvContract.WatchNextPrograms.CONTENT_URI, withPackage = true)
            .filter { it.first != context.packageName }
            .groupBy({ it.first }, { it.second })
            .map { (pkg, programs) -> AppChannel(pkg, "", "$pkg/$WATCH_NEXT", programs) }
        return watchNext + out
    }

    const val WATCH_NEXT = "watch_next"

    private fun loadPrograms(context: Context, uri: Uri): List<AppProgram> =
        loadPrograms(context, uri, withPackage = false).map { it.second }

    private fun loadPrograms(context: Context, uri: Uri, withPackage: Boolean): List<Pair<String, AppProgram>> {
        val list = ArrayList<Pair<String, AppProgram>>()
        context.contentResolver.query(uri, null, null, null, null)?.use { c ->
            while (c.moveToNext()) {
                val title = c.str(TvContract.PreviewPrograms.COLUMN_TITLE).orEmpty()
                val poster = c.str(TvContract.PreviewPrograms.COLUMN_POSTER_ART_URI)
                    ?: c.str(TvContract.PreviewPrograms.COLUMN_THUMBNAIL_URI)
                if (title.isBlank() && poster == null) continue
                val duration = c.long(TvContract.PreviewPrograms.COLUMN_DURATION_MILLIS)
                val position = c.long(TvContract.PreviewPrograms.COLUMN_LAST_PLAYBACK_POSITION_MILLIS)
                list += c.str(TvContract.PreviewPrograms.COLUMN_PACKAGE_NAME).orEmpty() to AppProgram(
                    id = c.long(TvContract.PreviewPrograms._ID),
                    title = title,
                    subtitle = c.str(TvContract.PreviewPrograms.COLUMN_EPISODE_TITLE).orEmpty(),
                    poster = poster,
                    aspect = aspectOf(c.int(TvContract.PreviewPrograms.COLUMN_POSTER_ART_ASPECT_RATIO)),
                    intentUri = c.str(TvContract.PreviewPrograms.COLUMN_INTENT_URI),
                    progress = if (duration > 0 && position > 0) (position.toFloat() / duration).coerceIn(0f, 1f) else null,
                )
            }
        }
        return list
    }

    private fun aspectOf(ratio: Int): Float = when (ratio) {
        TvContract.PreviewPrograms.ASPECT_RATIO_3_2 -> 3f / 2f
        TvContract.PreviewPrograms.ASPECT_RATIO_4_3 -> 4f / 3f
        TvContract.PreviewPrograms.ASPECT_RATIO_1_1 -> 1f
        TvContract.PreviewPrograms.ASPECT_RATIO_2_3 -> 2f / 3f
        5 -> 1f / 1.441f // ASPECT_RATIO_MOVIE_POSTER (API 27)
        else -> 16f / 9f
    }
}

private fun Cursor.idx(name: String) = getColumnIndex(name)
private fun Cursor.str(name: String): String? = idx(name).takeIf { it >= 0 && !isNull(it) }?.let { getString(it) }
private fun Cursor.long(name: String): Long = idx(name).takeIf { it >= 0 && !isNull(it) }?.let { getLong(it) } ?: 0L
private fun Cursor.int(name: String): Int = idx(name).takeIf { it >= 0 && !isNull(it) }?.let { getInt(it) } ?: 0

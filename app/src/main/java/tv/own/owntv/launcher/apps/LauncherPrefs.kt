package tv.own.owntv.launcher.apps

import android.content.Context
import android.content.SharedPreferences
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * Settings › Home screen: everything Mayo TV needs to be the TV's start screen. The Apps row under the
 * hero, the other apps' channels at the end of Home, the Apps section in the menu, and whether Mayo TV
 * takes over the Home button and the power-on.
 */
data class LauncherConfig(
    /** Mayo TV is the start screen: Home button (guard) and power-on lead here. Off = only the rows. */
    val launcher: Boolean = false,
    /** The Home guard (an accessibility service) reopens Mayo TV when the stock start screen comes up. */
    val guard: Boolean = false,
    /** What opens after power-on: [BOOT_HOME], [BOOT_LIVE], [BOOT_OFF] or an app's package. */
    val boot: String = BOOT_HOME,
    /** The Apps row right under the big picture on Home. */
    val appsRow: Boolean = true,
    /** The apps in that row, in order; empty = [DEFAULT_ROW] (those installed). */
    val rowApps: List<String> = emptyList(),
    /** Other apps' channels (Continue watching, Recommended) at the end of Home. */
    val appChannels: Boolean = true,
    /** Channels switched off one by one ([AppChannel.key]). */
    val hiddenChannels: Set<String> = emptySet(),
    /**
     * Where the user put other apps' rows among Home's own, in order: entries of
     * tv.own.owntv.features.home.HomeSlots (the Home row each follows). Rows not listed keep their old spot.
     */
    val channelSlots: List<String> = emptyList(),
    /** The Spotlight's sources in the user's order (SpotlightSource keys); empty = the default order. */
    val spotlightOrder: List<String> = emptyList(),
    /** Spotlight sources switched off. */
    val spotlightOff: Set<String> = emptySet(),
    /** How many highlights the Spotlight shows at most (Jonathan, 6 Nov 2026: Settings › Spotlight). */
    val spotlightMax: Int = SPOTLIGHT_MAX_DEFAULT,
    /** Seconds after which the Spotlight moves on to its next highlight by itself; 0 = only with ◀ ▶. */
    val spotlightAutoSecs: Int = 0,
    /** A film's TMDB rating (★ 7,8) on its Spotlight highlight. */
    val spotlightRatings: Boolean = true,
    /** Series and episodes stay out of the Spotlight's tips, films and "on now". */
    val spotlightHideSeries: Boolean = true,
    /** Premieres, MagentaTV's highlights, top-rated films and matches wear a gold badge over their title. */
    val spotlightBadges: Boolean = true,
    /** Apps in the menu: the grid of every app. */
    val appsInMenu: Boolean = true,
    /** The grid's own order (moved with a held OK); apps not listed follow by name. */
    val appOrder: List<String> = emptyList(),
    /** Apps hidden from the grid. */
    val hiddenApps: Set<String> = emptySet(),
    /** How Home is laid out: [LAYOUT_STANDARD], [LAYOUT_CLASSIC] or [LAYOUT_STAGE] (Jonathan, 4 Nov 2026). */
    val homeLayout: String = LAYOUT_STANDARD,
    /** The titles over Home's rows ("Magenta TV", "Apps" …); off, the cards speak for themselves. */
    val rowTitles: Boolean = true,
) {
    companion object {
        const val BOOT_HOME = "home"
        const val BOOT_LIVE = "live"
        const val BOOT_OFF = "off"

        /** Home as it was: the Apps row under the first row, the other apps' channels at the end. */
        const val LAYOUT_STANDARD = "standard"
        /** The apps as small buttons on top, their channels (YouTube, Nuvio, Spotify) right after the first row. */
        const val LAYOUT_CLASSIC = "classic"
        /** As classic, with the channel row as a stage: the focused channel's programme large over a strip of logos. */
        const val LAYOUT_STAGE = "stage"
        /** As Stage, the picture twice as large and playing by itself, muted, where focus rests (layout F). */
        const val LAYOUT_SPOTLIGHT = "spotlight"

        const val SPOTLIGHT_MAX_DEFAULT = 8
        /** The Spotlight's length, as Settings steps through it. */
        val SPOTLIGHT_MAX_STEPS = listOf(4, 6, 8, 10, 12)
        /** The Spotlight's automatic advance, as Settings steps through it (0 = off). */
        val SPOTLIGHT_AUTO_STEPS = listOf(0, 8, 12, 20, 30)

        /** TizenTube, YouTube, Nuvio, Spotify and Downloader, as far as they are installed. */
        val DEFAULT_ROW = listOf(
            "io.gh.reisxd.tizentube",
            "com.google.android.youtube.tv",
            "com.nuvio.tv",
            "com.nuvio.app",
            "com.spotify.tv.android",
            "com.esaba.downloader",
        )
    }
}

/** Stored in its own small preference file, apart from core's settings. */
object LauncherPrefs {
    private const val FILE = "mayo_launcher"
    private val state = MutableStateFlow(LauncherConfig())
    private var prefs: SharedPreferences? = null

    fun config(context: Context): StateFlow<LauncherConfig> {
        load(context)
        return state.asStateFlow()
    }

    fun current(context: Context): LauncherConfig {
        load(context)
        return state.value
    }

    fun update(context: Context, change: (LauncherConfig) -> LauncherConfig) {
        load(context)
        val next = change(state.value)
        state.value = next
        prefs?.edit()?.apply {
            putBoolean("launcher", next.launcher)
            putBoolean("guard", next.guard)
            putString("boot", next.boot)
            putBoolean("apps_row", next.appsRow)
            putString("row_apps", next.rowApps.joinToString(SEP))
            putBoolean("app_channels", next.appChannels)
            putStringSet("hidden_channels", next.hiddenChannels)
            putString("channel_slots", next.channelSlots.joinToString(LINES))
            putString("spotlight_order", next.spotlightOrder.joinToString(SEP))
            putStringSet("spotlight_off", next.spotlightOff)
            putInt("spotlight_max", next.spotlightMax)
            putInt("spotlight_auto_secs", next.spotlightAutoSecs)
            putBoolean("spotlight_ratings", next.spotlightRatings)
            putBoolean("spotlight_hide_series", next.spotlightHideSeries)
            putBoolean("spotlight_badges", next.spotlightBadges)
            putBoolean("apps_in_menu", next.appsInMenu)
            putString("app_order", next.appOrder.joinToString(SEP))
            putStringSet("hidden_apps", next.hiddenApps)
            putString("home_layout", next.homeLayout)
            putBoolean("row_titles", next.rowTitles)
        }?.apply()
    }

    @Synchronized
    private fun load(context: Context) {
        if (prefs != null) return
        val p = context.applicationContext.getSharedPreferences(FILE, Context.MODE_PRIVATE)
        prefs = p
        val d = LauncherConfig()
        state.value = LauncherConfig(
            launcher = p.getBoolean("launcher", d.launcher),
            guard = p.getBoolean("guard", d.guard),
            boot = p.getString("boot", d.boot) ?: d.boot,
            appsRow = p.getBoolean("apps_row", d.appsRow),
            rowApps = p.getString("row_apps", null).toList(),
            appChannels = p.getBoolean("app_channels", d.appChannels),
            hiddenChannels = p.getStringSet("hidden_channels", null)?.toSet() ?: emptySet(),
            channelSlots = p.getString("channel_slots", null)?.split(LINES)?.filter { it.isNotBlank() }.orEmpty(),
            spotlightOrder = p.getString("spotlight_order", null).toList(),
            spotlightOff = p.getStringSet("spotlight_off", null)?.toSet() ?: emptySet(),
            spotlightMax = p.getInt("spotlight_max", d.spotlightMax),
            spotlightAutoSecs = p.getInt("spotlight_auto_secs", d.spotlightAutoSecs),
            spotlightRatings = p.getBoolean("spotlight_ratings", d.spotlightRatings),
            spotlightHideSeries = p.getBoolean("spotlight_hide_series", d.spotlightHideSeries),
            spotlightBadges = p.getBoolean("spotlight_badges", d.spotlightBadges),
            appsInMenu = p.getBoolean("apps_in_menu", d.appsInMenu),
            appOrder = p.getString("app_order", null).toList(),
            hiddenApps = p.getStringSet("hidden_apps", null)?.toSet() ?: emptySet(),
            homeLayout = p.getString("home_layout", d.homeLayout) ?: d.homeLayout,
            rowTitles = p.getBoolean("row_titles", d.rowTitles),
        )
    }

    private const val SEP = ","
    /** Channel keys may hold commas; one slot per line. */
    private const val LINES = "\n"
    private fun String?.toList(): List<String> = this?.split(SEP)?.filter { it.isNotBlank() }.orEmpty()
}

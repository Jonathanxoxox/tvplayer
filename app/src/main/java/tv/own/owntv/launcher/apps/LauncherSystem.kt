package tv.own.owntv.launcher.apps

import android.accessibilityservice.AccessibilityService
import android.content.BroadcastReceiver
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.SystemClock
import android.provider.Settings
import android.view.accessibility.AccessibilityEvent
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * Mayo TV as the TV's start screen: the Home button, the guard for boxes that keep their own start
 * screen, and the power-on.
 */
object LauncherSystem {
    /** The stock start screens the guard steps in for. */
    val STOCK_LAUNCHERS = setOf(
        "com.google.android.tvlauncher",
        "com.google.android.apps.tv.launcherx",
        "com.google.android.leanbacklauncher",
        "com.android.tv.launcher",
        "com.amazon.tv.launcher",
        "com.mitv.tvhome",
        "com.tcl.home",
    )

    /** Marks an intent of ours that means "the Home button": the shell then goes to Home. */
    const val EXTRA_HOME = "mayo_home_button"
    private const val WRITE_SECURE = "android.permission.WRITE_SECURE_SETTINGS"

    private val homePresses = MutableStateFlow(0L)
    /** Counts up on every Home button press that reached Mayo TV; the shell goes to Home on each. */
    val homePress: StateFlow<Long> = homePresses.asStateFlow()

    private var consumed = 0L

    /** True once per press: a recreated shell does not go Home again for a press it already handled. */
    fun consumeHomePress(at: Long): Boolean {
        if (at == 0L || at == consumed) return false
        consumed = at
        return true
    }

    /** Called by the activity for every intent it is started or brought forward with. */
    fun onIntent(intent: Intent?) {
        if (intent == null) return
        if (intent.hasCategory(Intent.CATEGORY_HOME) || intent.getBooleanExtra(EXTRA_HOME, false)) {
            homePresses.value = SystemClock.elapsedRealtime()
        }
    }

    fun openHome(context: Context, live: Boolean = false) {
        val intent = context.packageManager.getLeanbackLaunchIntentForPackage(context.packageName)
            ?: context.packageManager.getLaunchIntentForPackage(context.packageName) ?: return
        intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_REORDER_TO_FRONT)
        if (!live) intent.putExtra(EXTRA_HOME, true)
        runCatching { context.startActivity(intent) }
    }

    /** Mayo TV is the app the Home button opens. */
    fun isDefaultHome(context: Context): Boolean {
        val intent = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_HOME)
        val info = context.packageManager.resolveActivity(intent, PackageManager.MATCH_DEFAULT_ONLY) ?: return false
        return info.activityInfo?.packageName == context.packageName
    }

    private fun guardComponent(context: Context) = ComponentName(context, HomeGuardService::class.java)

    /** The guard is switched on in the TV's accessibility settings. */
    fun isGuardOn(context: Context): Boolean {
        val me = guardComponent(context)
        return enabledServices(context).orEmpty().any { ComponentName.unflattenFromString(it) == me }
    }

    /** The raw list of switched-on services, or null when it could not be read. */
    private fun enabledServices(context: Context): List<String>? = runCatching {
        Settings.Secure.getString(context.contentResolver, Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES)
    }.getOrNull()?.split(':')?.map { it.trim() }?.filter { it.isNotEmpty() }

    fun canWriteSecure(context: Context): Boolean =
        context.checkSelfPermission(WRITE_SECURE) == PackageManager.PERMISSION_GRANTED

    /**
     * Switches the guard on without touching any other service: the list is read, ours is added to the
     * end, and everything that was there stays, in its order. Nothing is written when the list cannot be
     * read, or when ours is already in it. Needs WRITE_SECURE_SETTINGS (granted once over adb).
     */
    fun switchGuardOn(context: Context): Boolean {
        if (!canWriteSecure(context)) return false
        if (isGuardOn(context)) return true
        return runCatching {
            val resolver = context.contentResolver
            val raw = Settings.Secure.getString(resolver, Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES)
            val others = raw.orEmpty().split(':').map { it.trim() }.filter { it.isNotEmpty() }
            val next = (others + guardComponent(context).flattenToString()).joinToString(":")
            Settings.Secure.putString(resolver, Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES, next)
            if (Settings.Secure.getInt(resolver, Settings.Secure.ACCESSIBILITY_ENABLED, 0) != 1) {
                Settings.Secure.putInt(resolver, Settings.Secure.ACCESSIBILITY_ENABLED, 1)
            }
            isGuardOn(context)
        }.getOrDefault(false)
    }

    /** Takes only our guard out of the list; every other service stays on. */
    fun switchGuardOff(context: Context): Boolean {
        if (!canWriteSecure(context)) return false
        if (!isGuardOn(context)) return true
        return runCatching {
            val resolver = context.contentResolver
            val me = guardComponent(context)
            val raw = Settings.Secure.getString(resolver, Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES) ?: return false
            val next = raw.split(':').map { it.trim() }.filter { it.isNotEmpty() && ComponentName.unflattenFromString(it) != me }
            Settings.Secure.putString(resolver, Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES, next.joinToString(":"))
            true
        }.getOrDefault(false)
    }

    fun openAccessibilitySettings(context: Context) {
        runCatching {
            context.startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        }
    }

    fun grantCommand(context: Context): String =
        "adb shell pm grant ${context.packageName} $WRITE_SECURE"

    /** Without the guard: switch the stock start screen off, so the Home button has only Mayo TV left. */
    const val HOME_COMMAND = "adb shell pm disable-user --user 0 com.google.android.tvlauncher"

    fun channelsCommand(context: Context): String =
        "adb shell pm grant ${context.packageName} ${LauncherApps.READ_EPG} && " +
            "adb shell pm grant ${context.packageName} ${LauncherApps.READ_LISTINGS}"

    /** Once per power-on, whichever comes first: the boot broadcast or the guard connecting. */
    private var startedForBoot = -1L

    fun onBoot(context: Context) {
        val bootAt = System.currentTimeMillis() - SystemClock.elapsedRealtime()
        if (kotlin.math.abs(bootAt - startedForBoot) < 60_000L) return
        val config = LauncherPrefs.current(context)
        if (!config.launcher || config.boot == LauncherConfig.BOOT_OFF) return
        startedForBoot = bootAt
        when (config.boot) {
            // Mayo TV starts as App start says (Home, Live TV, the last channel …), not forced onto Home.
            LauncherConfig.BOOT_HOME, LauncherConfig.BOOT_LIVE -> openHome(context, live = true)
            else -> {
                val pm = context.packageManager
                val app = pm.getLeanbackLaunchIntentForPackage(config.boot) ?: pm.getLaunchIntentForPackage(config.boot)
                if (app != null) runCatching { context.startActivity(app.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) }
                else openHome(context)
            }
        }
    }
}

/** After power-on: Mayo TV, Live TV or the chosen app (Settings › Home screen). */
class LauncherBootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        runCatching { LauncherSystem.onBoot(context.applicationContext) }
    }
}

/**
 * The Home guard: when the stock start screen comes to the front (Home button, Back out of an app,
 * power-on), it opens Mayo TV. It reads which app is in front and nothing else. Every step is guarded:
 * a crashing service is what makes Android switch accessibility services off.
 */
class HomeGuardService : AccessibilityService() {
    private var lastJump = 0L

    override fun onServiceConnected() {
        super.onServiceConnected()
        runCatching { if (SystemClock.elapsedRealtime() < BOOT_WINDOW_MS) LauncherSystem.onBoot(applicationContext) }
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        runCatching {
            if (event?.eventType != AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED) return
            val pkg = event.packageName?.toString() ?: return
            if (pkg !in LauncherSystem.STOCK_LAUNCHERS) return
            val config = LauncherPrefs.current(applicationContext)
            if (!config.launcher || !config.guard) return
            val now = SystemClock.elapsedRealtime()
            if (now - lastJump < 800) return
            lastJump = now
            LauncherSystem.openHome(applicationContext)
        }
    }

    override fun onInterrupt() = Unit

    private companion object {
        const val BOOT_WINDOW_MS = 3 * 60_000L
    }
}

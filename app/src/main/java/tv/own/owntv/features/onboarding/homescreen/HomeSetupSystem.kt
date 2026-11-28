package tv.own.owntv.features.onboarding.homescreen

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.SharedPreferences
import android.content.pm.PackageManager
import android.os.Build
import android.provider.Settings
import tv.own.owntv.core.settings.StartupMode
import tv.own.owntv.launcher.apps.LauncherConfig
import tv.own.owntv.launcher.apps.LauncherPrefs
import tv.own.owntv.launcher.apps.LauncherSystem

/** The three pages of the start-screen chapter, in order. */
internal enum class HomeStep { PITCH, GUARD, BOOT }

/** What opens after power-on, as the chapter offers it. */
internal enum class PowerOn { HOME, LIVE, NOTHING }

/** Where a request to open the accessibility settings ended up. */
internal enum class SettingsOpened { ACCESSIBILITY, GENERAL, NONE }

/**
 * What the box itself decides about Mayo TV as its start screen, read fresh on every check.
 *
 * [guardOn]: the Home guard is in the box's list of switched-on accessibility services.
 * [defaultHome]: the Home button already resolves to Mayo TV (no guard needed for it).
 * [canWrite]: WRITE_SECURE_SETTINGS was granted, so the guard can be switched on from here.
 * [homePicker]: the box has a page where the user can pick the Home app.
 */
internal data class HomeStatus(
    val guardOn: Boolean = false,
    val defaultHome: Boolean = false,
    val canWrite: Boolean = false,
    val homePicker: Boolean = false,
) {
    /** The Home button leads to Mayo TV, one way or the other. */
    val active: Boolean get() = guardOn || defaultHome
}

/**
 * The chapter's own progress, kept in the app-private file `mayo_onboarding_home` so it survives the
 * process being killed while the user is in the box's settings.
 */
internal class HomeSetupProgress(context: Context) {
    private val prefs: SharedPreferences =
        context.applicationContext.getSharedPreferences(FILE, Context.MODE_PRIVATE)

    /** The page to show. */
    var step: HomeStep
        get() = prefs.getString(K_STEP, null)?.let { s -> HomeStep.entries.firstOrNull { it.name == s } } ?: HomeStep.PITCH
        set(value) = prefs.edit().putString(K_STEP, value.name).apply()

    /** How often the user was sent to the box's settings without the guard being on afterwards. */
    var attempts: Int
        get() = prefs.getInt(K_ATTEMPTS, 0)
        set(value) = prefs.edit().putInt(K_ATTEMPTS, value).apply()

    /** The box refused every way into its settings: only the manual route is left. */
    var openFailed: Boolean
        get() = prefs.getBoolean(K_OPEN_FAILED, false)
        set(value) = prefs.edit().putBoolean(K_OPEN_FAILED, value).apply()

    /**
     * The chapter switched "Mayo TV as start screen" on in [LauncherPrefs] on the user's request. A
     * skip without a working guard switches it off again, so nothing half set up is left behind.
     */
    var armed: Boolean
        get() = prefs.getBoolean(K_ARMED, false)
        set(value) = prefs.edit().putBoolean(K_ARMED, value).apply()

    private companion object {
        const val FILE = "mayo_onboarding_home"
        const val K_STEP = "step"
        const val K_ATTEMPTS = "attempts"
        const val K_OPEN_FAILED = "open_failed"
        const val K_ARMED = "armed"
    }
}

/** The system side of the chapter, on top of [LauncherSystem] and [LauncherPrefs]. */
internal object HomeSetupSystem {
    /** Android TV's own settings app; its accessibility page, for boxes without the standard action. */
    private val TV_ACCESSIBILITY = ComponentName(
        "com.android.tv.settings",
        "com.android.tv.settings.accessibility.AccessibilityActivity",
    )

    fun read(context: Context): HomeStatus = HomeStatus(
        guardOn = LauncherSystem.isGuardOn(context),
        defaultHome = runCatching { LauncherSystem.isDefaultHome(context) }.getOrDefault(false),
        canWrite = LauncherSystem.canWriteSecure(context),
        homePicker = hasHomePicker(context),
    )

    /**
     * Opens the accessibility settings, trying the standard action, then Android TV's settings page
     * directly, then the general settings. Each attempt is started rather than resolved first, since
     * package visibility can hide system activities from a lookup that would still start.
     */
    fun openAccessibility(context: Context): SettingsOpened {
        val attempts = listOf(
            SettingsOpened.ACCESSIBILITY to Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS),
            SettingsOpened.ACCESSIBILITY to Intent().setComponent(TV_ACCESSIBILITY),
            SettingsOpened.GENERAL to Intent(Settings.ACTION_SETTINGS),
        )
        for ((where, intent) in attempts) {
            val started = runCatching {
                context.startActivity(intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
            }.isSuccess
            if (started) return where
        }
        return SettingsOpened.NONE
    }

    /** The box offers a Home app picker (phones and some boxes; most Android TV builds do not). */
    private fun hasHomePicker(context: Context): Boolean = runCatching {
        context.packageManager.resolveActivity(Intent(Settings.ACTION_HOME_SETTINGS), PackageManager.MATCH_DEFAULT_ONLY) != null
    }.getOrDefault(false)

    fun openHomePicker(context: Context): Boolean = runCatching {
        context.startActivity(Intent(Settings.ACTION_HOME_SETTINGS).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
    }.isSuccess

    /**
     * The box lets an app start itself from the background, which the power-on start relies on when
     * the guard is not there to open Mayo TV (Android 10 and later need "display over other apps").
     */
    fun canStartFromBackground(context: Context): Boolean =
        Build.VERSION.SDK_INT < Build.VERSION_CODES.Q || runCatching { Settings.canDrawOverlays(context) }.getOrDefault(false)

    /**
     * Mayo TV becomes the start screen with the guard, as Settings › Power-on & Home button does when
     * its guard row is switched on.
     */
    fun arm(context: Context, progress: HomeSetupProgress) {
        progress.armed = true
        LauncherPrefs.update(context) { if (it.launcher && it.guard) it else it.copy(launcher = true, guard = true) }
    }

    /** Records a working Home button in [LauncherPrefs]; writes only when something changes. */
    fun adopt(context: Context, status: HomeStatus) {
        val c = LauncherPrefs.current(context)
        val guard = c.guard || status.guardOn
        if (c.launcher && c.guard == guard) return
        LauncherPrefs.update(context) { it.copy(launcher = true, guard = guard) }
    }

    /** Undoes [arm] when the user leaves without a working Home button. */
    fun disarmUnlessActive(context: Context, progress: HomeSetupProgress, status: HomeStatus) {
        if (!progress.armed || status.active) return
        progress.armed = false
        LauncherPrefs.update(context) { it.copy(launcher = false, guard = false) }
    }

    /** The power-on choice that matches what is stored now. */
    fun currentPowerOn(context: Context, startup: StartupMode?): PowerOn {
        val c = LauncherPrefs.current(context)
        return when {
            // Not the start screen yet: Mayo TV's Home is the suggestion.
            !c.launcher -> PowerOn.HOME
            c.boot == LauncherConfig.BOOT_OFF -> PowerOn.NOTHING
            c.boot == LauncherConfig.BOOT_LIVE -> PowerOn.LIVE
            startup != null && startup != StartupMode.HOME -> PowerOn.LIVE
            else -> PowerOn.HOME
        }
    }

    /**
     * Writes the power-on choice the way Settings does: Mayo TV at power-on is [LauncherConfig.BOOT_HOME],
     * and whether it opens on Home or on live TV is the profile's App start ([StartupMode]). "Nothing"
     * is [LauncherConfig.BOOT_OFF]; Mayo TV then stays the start screen only if the Home button
     * already leads to it. The App start half is [startupFor].
     */
    fun savePowerOn(context: Context, choice: PowerOn, status: HomeStatus) {
        LauncherPrefs.update(context) { c ->
            when (choice) {
                PowerOn.HOME, PowerOn.LIVE -> c.copy(launcher = true, guard = c.guard || status.guardOn, boot = LauncherConfig.BOOT_HOME)
                PowerOn.NOTHING -> c.copy(launcher = status.active, guard = status.guardOn, boot = LauncherConfig.BOOT_OFF)
            }
        }
    }

    /** The App start mode to store for [choice] given the profile's [current] one, or null to keep it. */
    fun startupFor(choice: PowerOn, current: StartupMode): StartupMode? = when (choice) {
        PowerOn.HOME -> StartupMode.HOME.takeIf { current != StartupMode.HOME }
        // A live start the profile already has (last channel, favourites, a set channel) is kept.
        PowerOn.LIVE -> StartupMode.LAST_CHANNEL.takeIf { current == StartupMode.HOME }
        PowerOn.NOTHING -> null
    }
}

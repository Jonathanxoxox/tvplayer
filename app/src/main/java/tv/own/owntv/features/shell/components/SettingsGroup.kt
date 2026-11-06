package tv.own.owntv.features.shell.components

import androidx.compose.runtime.Composable
import androidx.compose.ui.res.stringResource
import tv.own.owntv.R
import tv.own.owntv.features.settings.VideoGroup

/**
 * The Settings groups, in the order of the list on the left (settings cleanup, 6 Nov 2026). The order lives
 * here and only here: the root rows are gathered under their group by [key] (the RootGroup marker in
 * SettingsScreen), wherever that marker sits in the list of rows.
 * [video] adds a group's Video player rows; [rootFirst] puts its own rows above them instead of below.
 * [mine] puts the group under "Your things" (Profiles) instead of among the settings.
 */
internal enum class SettingsGroup(
    val titleRes: Int,
    val key: String,
    val video: VideoGroup? = null,
    val rootFirst: Boolean = false,
    val mine: Boolean = false,
) {
    /** The people who use Mayo TV: a list, not preferences, so it sits with Favourites and History. */
    PROFILE(R.string.profiles_title, "group_profile", mine = true),
    SOURCES(R.string.settings_mayo_group_sources, "group_sources"),
    /** How Mayo TV looks, Home first (Jonathan, 4 Nov 2026: Appearance and Home share one page). */
    APPEARANCE(R.string.settings_group_appearance_home, "group_appearance"),
    LIVE(R.string.settings_mayo_group_live, "group_live", VideoGroup.LIVE),
    PLAYBACK(R.string.settings_mayo_group_playback, "group_playback", VideoGroup.PLAYBACK),
    PICTURE_SOUND(R.string.settings_mayo_group_picture_sound, "group_picture_sound", VideoGroup.PICTURE_SOUND),
    LANGUAGE(R.string.settings_mayo_group_language, "group_language", VideoGroup.LANGUAGES, rootFirst = true),
    /** The menu, the remote and the menus: how Mayo TV is worked, apart from how it looks. */
    CONTROLS(R.string.settings_group_controls, "group_controls"),
    /** Mayo TV as the TV's start screen: the Home button and what opens at power-on. */
    HOMESCREEN(R.string.launcher_settings_group, "group_homescreen"),
    /** Updates, network, remembered values and diagnostics (was "App"). */
    APP(R.string.settings_cleanup_group_system, "group_app", VideoGroup.DIAGNOSTICS, rootFirst = true),
    ;

    companion object {
        /** The group a Video player row lives in; Diagnostics sits at the end of System. */
        fun of(group: VideoGroup): SettingsGroup = entries.firstOrNull { it.video == group } ?: APP
    }
}

/**
 * A path in Settings as search results, breadcrumbs and help texts show it (rule 17): the group's own name
 * from [SettingsGroup.titleRes], then [parts] ("System › Remembered values"). Never written out by hand.
 */
@Composable
internal fun settingsCrumb(group: SettingsGroup, vararg parts: String): String =
    parts.fold(stringResource(group.titleRes)) { path, part -> stringResource(R.string.settings_breadcrumb, path, part) }

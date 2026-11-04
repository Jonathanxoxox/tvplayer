package tv.own.owntv.features.shell.components

import tv.own.owntv.R
import tv.own.owntv.features.settings.VideoGroup

/**
 * The Settings groups, in the order of the category list on the left (Mayo TV, 1 Nov 2026: nine instead of
 * thirteen; Quick is gone, its rows all live in their groups). A group's position is also where its rows start in the root list (a RootGroup marker each).
 * [video] adds a group's Video player rows; [rootFirst] puts its own rows above them instead of below.
 */
internal enum class SettingsGroup(val titleRes: Int, val video: VideoGroup? = null, val rootFirst: Boolean = false) {
    PROFILE(R.string.settings_group_profile),
    SOURCES(R.string.settings_mayo_group_sources),
    LIVE(R.string.settings_mayo_group_live, VideoGroup.LIVE),
    PLAYBACK(R.string.settings_mayo_group_playback, VideoGroup.PLAYBACK),
    PICTURE_SOUND(R.string.settings_mayo_group_picture_sound, VideoGroup.PICTURE_SOUND),
    LANGUAGE(R.string.settings_mayo_group_language, VideoGroup.LANGUAGES, rootFirst = true),
    /** How Mayo TV looks, Home first (Jonathan, 4 Nov 2026: Appearance and Home share one page). */
    APPEARANCE(R.string.settings_group_appearance_home),
    /** The sidebar, the remote and the menus: how Mayo TV is worked, apart from how it looks. */
    CONTROLS(R.string.settings_group_controls),
    /** Mayo TV as the TV's start screen: the Home button and what opens at power-on. */
    HOMESCREEN(R.string.launcher_settings_group),
    APP(R.string.settings_group_app, VideoGroup.DIAGNOSTICS, rootFirst = true),
    ;

    companion object {
        /** The group a Video player row lives in; Diagnostics sits at the end of App. */
        fun of(group: VideoGroup): SettingsGroup = entries.firstOrNull { it.video == group } ?: APP
    }
}

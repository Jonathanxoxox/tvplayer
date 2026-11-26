package tv.own.owntv.features.onboarding

import androidx.annotation.StringRes
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.em
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.tv.material3.Text
import kotlinx.coroutines.flow.flowOf
import org.koin.androidx.compose.koinViewModel
import org.koin.compose.koinInject
import tv.own.owntv.R
import tv.own.owntv.core.database.dao.ChannelDao
import tv.own.owntv.core.database.dao.ProfileDao
import tv.own.owntv.core.database.dao.SourceDao
import tv.own.owntv.core.settings.SettingsRepository
import tv.own.owntv.core.setup.SourceImporter
import tv.own.owntv.core.sports.SportsConfig
import tv.own.owntv.core.sports.model.Sport
import tv.own.owntv.features.keys.ApiKeys
import tv.own.owntv.features.keys.ApiService
import tv.own.owntv.features.setup.SetupViewModel
import tv.own.owntv.launcher.apps.LauncherSystem
import tv.own.owntv.ui.components.OwnTVAvatar
import tv.own.owntv.ui.components.OwnTVIcon
import tv.own.owntv.ui.stage.StageFocus
import tv.own.owntv.ui.stage.StageSurface
import tv.own.owntv.ui.theme.OwnTVTheme
import tv.own.owntv.ui.theme.StageColors
import tv.own.owntv.ui.theme.mpx
import tv.own.owntv.ui.theme.stageAccent
import tv.own.owntv.ui.theme.stageText

/**
 * The last page of the journey: what was set up (profile, channels, start screen, football, extras),
 * each skipped item with a quiet "you can do this later under Settings › …", three tips for the
 * remote, and one big Let's go that opens the app. [onNext] completes the journey; [onBack] returns
 * to the previous chapter (null where Back must not move, see [ChapterFrame]).
 *
 * Everything shown is read from where the earlier pages stored it, so the page is right after the
 * app was closed and reopened here too. A playlist still importing in the background is said to be
 * loading, and its channel count grows while the page is open.
 */
@Composable
fun ReadyChapter(onNext: () -> Unit, onBack: (() -> Unit)?) {
    val summary = rememberReadySummary()
    val go = rememberChapterFocus()
    ChapterFrame(
        chapter = OnboardingChapter.READY,
        title = stringResource(R.string.onboarding_ready_title),
        subtitle = stringResource(R.string.onboarding_ready_sub),
        primary = null,
        secondary = onBack?.let { ChapterAction(stringResource(R.string.common_back), it) },
        width = 1200.mpx,
    ) {
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(48.mpx)) {
            Column(Modifier.weight(1.15f), verticalArrangement = Arrangement.spacedBy(12.mpx)) {
                ReadyHeading(stringResource(R.string.onboarding_ready_summary_heading))
                SummaryLines(summary)
            }
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(14.mpx)) {
                ReadyHeading(stringResource(R.string.onboarding_ready_tips_heading))
                TipLine(stringResource(R.string.onboarding_ready_tip_zap_key), stringResource(R.string.onboarding_ready_tip_zap))
                TipLine(stringResource(R.string.onboarding_ready_tip_list_key), stringResource(R.string.onboarding_ready_tip_list))
                TipLine(stringResource(R.string.common_ok), stringResource(R.string.onboarding_ready_tip_ok))
                Text(
                    stringResource(R.string.onboarding_ready_tips_more),
                    style = stageText(16, 500),
                    color = StageColors.Muted,
                    modifier = Modifier.padding(top = 4.mpx),
                )
            }
        }
        Box(Modifier.fillMaxWidth().padding(top = 24.mpx), contentAlignment = Alignment.Center) {
            GoButton(stringResource(R.string.onboarding_action_go), onNext, go)
        }
    }
}

/** What the summary shows, read from the stores the earlier pages wrote to. */
private class ReadySummary(
    val profileName: String,
    val avatarId: Int,
    val avatarPath: String,
    val kids: Boolean,
    val pin: Boolean,
    val playlists: Int,
    val channels: Int,
    val importing: Boolean,
    val startScreen: Boolean,
    val teams: List<String>,
    val leagues: Int,
    val keys: Int,
)

@Composable
private fun rememberReadySummary(): ReadySummary {
    val settings: SettingsRepository = koinInject()
    val profileDao: ProfileDao = koinInject()
    val sourceDao: SourceDao = koinInject()
    val channelDao: ChannelDao = koinInject()
    val apiKeys: ApiKeys = koinInject()
    // The wizard's own model (activity-scoped): it knows whether the first import is still running.
    val setup: SetupViewModel = koinViewModel()
    val context = LocalContext.current

    val profileId by settings.activeProfileId.collectAsStateWithLifecycle(initialValue = -1L)
    val profile by remember(profileId) { profileDao.observeById(profileId) }.collectAsStateWithLifecycle(initialValue = null)
    val sources by remember(profileId) { sourceDao.observeForProfile(profileId) }.collectAsStateWithLifecycle(initialValue = emptyList())
    val sourceIds = remember(sources) { sources.map { it.id } }
    val channels by remember(sourceIds) {
        if (sourceIds.isEmpty()) flowOf(0) else channelDao.countAll(sourceIds)
    }.collectAsStateWithLifecycle(initialValue = 0)
    val importState by setup.state.collectAsStateWithLifecycle()
    val sports by settings.activeSportsConfig.collectAsStateWithLifecycle<SportsConfig?>(initialValue = null)
    val keys by apiKeys.configured.collectAsStateWithLifecycle()
    // Read once: the start screen was settled on the page before and cannot change while this one shows.
    val startScreen = remember { LauncherSystem.isDefaultHome(context) || LauncherSystem.isGuardOn(context) }

    val football = sports
    return ReadySummary(
        profileName = profile?.name.orEmpty(),
        avatarId = profile?.avatarId ?: -1,
        avatarPath = profile?.avatarPath.orEmpty(),
        kids = profile?.isKids == true,
        pin = profile?.pinHash != null,
        playlists = sources.size,
        channels = channels,
        importing = importState is SourceImporter.ImportState.Running,
        startScreen = startScreen,
        teams = football?.teams.orEmpty()
            .filter { it.sport == Sport.SOCCER }
            .map { team -> team.name.ifBlank { team.abbreviation } }
            .filter { it.isNotBlank() },
        leagues = football?.leagues.orEmpty().count { it.startsWith(Sport.SOCCER.path) },
        keys = keys.size,
    )
}

/** The five lines of the summary; a skipped item says where in Settings it can be done later. */
@Composable
private fun SummaryLines(s: ReadySummary) {
    SummaryLine(
        mark = { OwnTVAvatar(avatarId = s.avatarId, imagePath = s.avatarPath, modifier = Modifier.size(36.mpx)) },
        label = stringResource(R.string.onboarding_ready_row_profile),
        value = s.profileName,
        detail = when {
            s.kids -> stringResource(R.string.profiles_kids)
            s.pin -> stringResource(R.string.onboarding_ready_profile_pin)
            else -> null
        },
        done = true,
    )

    val channelsLine = channelsLine(s.playlists, s.channels, s.importing)
    val loading = stringResource(R.string.onboarding_ready_channels_loading)
    SummaryLine(
        mark = { SummaryIcon(OwnTVIcon.LIVE_TV, channelsLine.done) },
        label = stringResource(R.string.onboarding_ready_row_channels),
        value = when (channelsLine) {
            ChannelsLine.NO_PLAYLIST -> stringResource(R.string.onboarding_ready_no_playlist)
            ChannelsLine.NONE_LOADED -> stringResource(R.string.onboarding_ready_no_channels)
            ChannelsLine.LOADING -> loading
            ChannelsLine.COUNT -> pluralStringResource(R.plurals.onboarding_ready_channels, s.channels, s.channels)
        },
        detail = when {
            !channelsLine.done -> laterIn(R.string.settings_mayo_group_sources)
            s.importing && s.channels > 0 -> loading
            else -> pluralStringResource(R.plurals.onboarding_ready_playlists, s.playlists, s.playlists)
        },
        done = channelsLine.done,
    )

    SummaryLine(
        mark = { SummaryIcon(OwnTVIcon.HOME, s.startScreen) },
        label = stringResource(R.string.onboarding_ready_row_start),
        value = stringResource(if (s.startScreen) R.string.onboarding_ready_start_on else R.string.onboarding_ready_start_off),
        detail = if (s.startScreen) null else laterIn(R.string.launcher_settings_group),
        done = s.startScreen,
    )

    val teams = s.teams
    val teamText = when (teams.size) {
        0 -> null
        1 -> teams[0]
        2 -> stringResource(R.string.onboarding_ready_teams_two, teams[0], teams[1])
        else -> pluralStringResource(R.plurals.onboarding_ready_teams_more, teams.size - 2, teams[0], teams[1], teams.size - 2)
    }
    val leagueText = if (s.leagues > 0) pluralStringResource(R.plurals.onboarding_ready_leagues, s.leagues, s.leagues) else null
    val football = teamText != null || leagueText != null
    SummaryLine(
        mark = { SummaryIcon(OwnTVIcon.SPORTS, football) },
        label = stringResource(R.string.onboarding_ready_row_football),
        value = teamText ?: leagueText ?: stringResource(R.string.onboarding_ready_no_teams),
        detail = when {
            !football -> laterIn(R.string.settings_mayo_group_live, R.string.sports_title)
            teamText != null -> leagueText
            else -> null
        },
        done = football,
    )

    val total = ApiService.entries.size
    SummaryLine(
        mark = { SummaryIcon(OwnTVIcon.SPARKLE, s.keys > 0) },
        label = stringResource(R.string.onboarding_ready_row_extras),
        value = when (s.keys) {
            0 -> stringResource(R.string.onboarding_ready_extras_none)
            total -> stringResource(R.string.onboarding_ready_extras_all)
            else -> pluralStringResource(R.plurals.onboarding_ready_extras_some, total, s.keys, total)
        },
        detail = if (s.keys < total) laterIn(R.string.settings_cleanup_group_system, R.string.keys_settings_title) else null,
        done = s.keys > 0,
    )
}

/** What the channels line of the summary says; [done] marks it as set up. */
internal enum class ChannelsLine(val done: Boolean) {
    NO_PLAYLIST(false),
    /** The first import is still running and no channel has arrived yet. */
    LOADING(true),
    /** A playlist exists, but no channel arrived and nothing is importing: the import failed or found none. */
    NONE_LOADED(false),
    COUNT(true),
}

internal fun channelsLine(playlists: Int, channels: Int, importing: Boolean): ChannelsLine = when {
    playlists == 0 -> ChannelsLine.NO_PLAYLIST
    channels > 0 -> ChannelsLine.COUNT
    importing -> ChannelsLine.LOADING
    else -> ChannelsLine.NONE_LOADED
}

/**
 * "Later under Settings › [group]", or "› [group] › [page]" for a page inside a group: the path as the
 * Settings root shows it, the same one the chapter itself named.
 */
@Composable
private fun laterIn(@StringRes group: Int, @StringRes page: Int? = null): String {
    val path = if (page == null) stringResource(group) else stringResource(R.string.settings_breadcrumb, stringResource(group), stringResource(page))
    return stringResource(R.string.onboarding_ready_later_in, path)
}

/** A small accent heading over each half of the page. */
@Composable
private fun ReadyHeading(text: String) {
    Text(
        text.uppercase(),
        style = stageText(15, 800, 0.12f.em),
        color = stageAccent.accent,
        maxLines = 1,
        overflow = TextOverflow.Ellipsis,
        modifier = Modifier.padding(bottom = 2.mpx),
    )
}

/**
 * One line of the summary: a round mark, the item's name, its value beside it and an optional
 * detail under the value. A skipped item ([done] false) reads quieter. Not focusable: the page has
 * one control.
 */
@Composable
private fun SummaryLine(mark: @Composable () -> Unit, label: String, value: String, detail: String?, done: Boolean) {
    Row(horizontalArrangement = Arrangement.spacedBy(16.mpx), verticalAlignment = Alignment.Top) {
        Box(Modifier.size(36.mpx), contentAlignment = Alignment.Center) { mark() }
        Text(
            label,
            style = stageText(16, 700),
            color = StageColors.Dim,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.width(170.mpx).padding(top = 7.mpx),
        )
        Column(Modifier.weight(1f).padding(top = 4.mpx)) {
            Text(
                value,
                style = stageText(19, 700),
                color = if (done) StageColors.Text else StageColors.Muted,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            if (!detail.isNullOrBlank()) {
                Text(detail, style = stageText(15, 500), color = StageColors.Muted, modifier = Modifier.padding(top = 2.mpx))
            }
        }
    }
}

/** The round mark of a summary line: the item's icon, in the accent once it is set up. */
@Composable
private fun SummaryIcon(icon: OwnTVIcon, done: Boolean) {
    val a = stageAccent
    Box(
        Modifier
            .size(36.mpx)
            .background(if (done) a.accent.copy(alpha = 0.16f) else StageColors.Ink.copy(alpha = 0.06f), CircleShape),
        contentAlignment = Alignment.Center,
    ) {
        OwnTVIcon(icon, if (done) a.accent else StageColors.Dim, Modifier.size(20.mpx))
    }
}

/** One remote tip: the key as a cap, then what it does. */
@Composable
private fun TipLine(key: String, text: String) {
    Row(horizontalArrangement = Arrangement.spacedBy(16.mpx), verticalAlignment = Alignment.CenterVertically) {
        Box(
            Modifier
                .width(76.mpx)
                .height(42.mpx)
                .background(StageColors.Ink.copy(alpha = 0.08f), RoundedCornerShape(10.mpx)),
            contentAlignment = Alignment.Center,
        ) {
            Text(key, style = stageText(18, 800), color = StageColors.Text, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
        Text(text, style = stageText(18, 500), color = StageColors.Body, modifier = Modifier.weight(1f))
    }
}

/** The one big way out: an accent button that opens the app. Focused first. */
@Composable
private fun GoButton(label: String, onClick: () -> Unit, focus: FocusRequester) {
    val a = stageAccent
    val idleInk = OwnTVTheme.colors.onPrimary
    val shape = RoundedCornerShape(38.mpx)
    StageSurface(
        onClick = onClick,
        radius = 38.mpx,
        modifier = Modifier.width(520.mpx).height(76.mpx).focusRequester(focus),
        focusStyle = StageFocus.PRIMARY,
        idle = Modifier.background(a.accent, shape),
        contentAlignment = Alignment.Center,
    ) { focused ->
        val ink = if (focused) a.onAccent else idleInk
        Row(horizontalArrangement = Arrangement.spacedBy(14.mpx), verticalAlignment = Alignment.CenterVertically) {
            Text(label, style = stageText(26, 800), color = ink, maxLines = 1, overflow = TextOverflow.Ellipsis)
            OwnTVIcon(OwnTVIcon.CHEVRON, ink, Modifier.size(26.mpx))
        }
    }
}

package tv.own.owntv.features.onboarding

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import org.koin.compose.koinInject
import tv.own.owntv.R
import tv.own.owntv.core.settings.SettingsRepository
import tv.own.owntv.core.sports.FollowedTeam
import tv.own.owntv.core.sports.SportsCatalog
import tv.own.owntv.core.sports.SportsConfig
import tv.own.owntv.core.sports.SportsSource
import tv.own.owntv.core.sports.model.League
import tv.own.owntv.core.sports.model.Sport
import tv.own.owntv.core.sports.model.Team
import tv.own.owntv.features.onboarding.football.CrestTile
import tv.own.owntv.features.onboarding.football.FootballLabel
import tv.own.owntv.features.onboarding.football.FootballNote
import tv.own.owntv.features.onboarding.football.FootballPicks
import tv.own.owntv.features.onboarding.football.FootballTeamLoader
import tv.own.owntv.features.onboarding.football.LeagueChip
import tv.own.owntv.features.onboarding.football.TeamsPhase
import tv.own.owntv.features.settings.SettingValue
import tv.own.owntv.features.settings.StageFieldRow
import tv.own.owntv.features.settings.StageSettingRow
import tv.own.owntv.features.settings.StageSettingsHeading
import tv.own.owntv.launcher.FootballSetup
import tv.own.owntv.ui.components.OwnTVIcon
import tv.own.owntv.ui.stage.StagePill
import tv.own.owntv.ui.theme.mpx

/** The two pages of the chapter. */
private enum class FootballPage { LEAGUES, TEAMS }

/**
 * Favourite competitions and teams, chosen by the user; nothing is chosen on their behalf.
 *
 * - **Competitions**: large toggle chips, the user's own country first (German leagues first for a
 *   German speaker), then the international competitions and Europe's big leagues. The region's top
 *   league, its main cup and the Champions League start ticked. "No football for me" leaves the
 *   chapter with no football followed and the football channel group switched off.
 * - **Teams**: the teams of the ticked competitions as a crest grid, with a search above it (the same
 *   team lists Settings › Sports › My teams searches). Loading, a failed or slow connection and a
 *   retry are shown plainly; "Later" leaves without teams, which Settings › Sports offers any time.
 *
 * Every choice is written into the active profile's sports settings, the store Settings › Sports
 * edits, when the user moves on: the competitions when leaving the first page, the teams when
 * leaving the second. Other sports in those settings are kept.
 *
 * [onNext] moves on to the next chapter (after this chapter has saved what the user chose);
 * [onBack] returns to the previous chapter, or is null where Back must not move (see [ChapterFrame]).
 */
@Composable
fun FootballChapter(onNext: () -> Unit, onBack: (() -> Unit)?) {
    val settings = koinInject<SettingsRepository>()
    // The profile's sports settings as they are now: empty on a first run, the earlier choice when
    // the user comes back to this chapter from a later one.
    val start by produceState<SportsConfig?>(null, settings) { value = settings.activeSportsConfig.first() }
    val config = start
    if (config == null) {
        ChapterFrame(
            chapter = OnboardingChapter.FOOTBALL,
            title = stringResource(R.string.onboarding_football_title),
            subtitle = stringResource(R.string.onboarding_football_sub),
            primary = null,
            secondary = onBack?.let { ChapterAction(stringResource(R.string.common_back), it) },
        )
        return
    }
    FootballPages(config, settings, onNext, onBack)
}

@Composable
private fun FootballPages(start: SportsConfig, settings: SettingsRepository, onNext: () -> Unit, onBack: (() -> Unit)?) {
    val context = LocalContext.current.applicationContext
    val appScope = koinInject<CoroutineScope>()
    val source = koinInject<SportsSource>()
    val uiScope = rememberCoroutineScope()
    val loader = remember { FootballTeamLoader(source, uiScope) }

    val locale = LocalConfiguration.current.locales[0]
    val region = remember(locale) { FootballPicks.regionOf(locale) }
    val german = FootballPicks.isGerman(locale)
    val offered = remember(region, german) { FootballPicks.offered(region, german, start.leagues) }

    var page by remember { mutableStateOf(FootballPage.LEAGUES) }
    var leagues by remember { mutableStateOf(FootballPicks.preselected(region, start.leagues).toSet()) }
    var teams by remember { mutableStateOf(start.teams.filter { it.sport == Sport.SOCCER }) }

    // Saves run in the app's scope, so leaving the chapter cannot cut them short, one after the other.
    var lastSave by remember { mutableStateOf<Job?>(null) }
    fun save(transform: (SportsConfig) -> SportsConfig) {
        val previous = lastSave
        lastSave = appScope.launch {
            previous?.join()
            runCatching {
                val profileId = settings.activeProfileIdNow()
                if (profileId >= 0L) settings.updateSportsConfig(profileId, transform)
            }
        }
    }

    // As the Settings root shows it: the group, then the page inside it.
    val groupPath = stringResource(R.string.onboarding_football_path, stringResource(R.string.common_nav_settings), stringResource(R.string.settings_mayo_group_live))
    val settingsPath = stringResource(R.string.onboarding_football_path, groupPath, stringResource(R.string.sports_title))
    val teamsPath = stringResource(R.string.onboarding_football_path, settingsPath, stringResource(R.string.sports_my_teams))
    val back = stringResource(R.string.common_back)

    when (page) {
        FootballPage.LEAGUES -> LeaguesPage(
            offered = offered,
            chosen = leagues,
            settingsPath = settingsPath,
            onToggle = { key -> leagues = if (key in leagues) leagues - key else leagues + key },
            onContinue = {
                val chosen = FootballPicks.inOfferedOrder(leagues, offered)
                save { FootballPicks.withLeagues(it, chosen) }
                if (chosen.isEmpty()) {
                    onNext()
                } else {
                    // Coming back after "No football for me": the football channel group returns.
                    if (!FootballSetup.groupOn(context)) FootballSetup.set(context, FootballSetup.GROUP_ON, true)
                    loader.load(FootballPicks.toLoad(chosen))
                    page = FootballPage.TEAMS
                }
            },
            onNoFootball = {
                leagues = emptySet()
                teams = emptyList()
                save(FootballPicks::withoutFootball)
                FootballSetup.set(context, FootballSetup.GROUP_ON, false)
                onNext()
            },
            back = onBack?.let { ChapterAction(back, it) },
        )
        FootballPage.TEAMS -> TeamsPage(
            keys = FootballPicks.toLoad(FootballPicks.inOfferedOrder(leagues, offered)),
            loader = loader,
            chosen = teams,
            teamsPath = teamsPath,
            onToggle = { team ->
                teams = if (teams.any { it.key == team.key }) teams.filterNot { it.key == team.key } else teams + team
            },
            onContinue = {
                val chosen = teams
                save { FootballPicks.withTeams(it, chosen) }
                onNext()
            },
            onLater = onNext,
            back = ChapterAction(back, { page = FootballPage.LEAGUES }),
        )
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun LeaguesPage(
    offered: List<League>,
    chosen: Set<String>,
    settingsPath: String,
    onToggle: (String) -> Unit,
    onContinue: () -> Unit,
    onNoFootball: () -> Unit,
    back: ChapterAction?,
) {
    val first = rememberChapterFocus()
    ChapterFrame(
        chapter = OnboardingChapter.FOOTBALL,
        title = stringResource(R.string.onboarding_football_title),
        subtitle = stringResource(R.string.onboarding_football_sub),
        primary = ChapterAction(stringResource(R.string.onboarding_action_continue), onContinue),
        secondary = back,
        skip = ChapterAction(stringResource(R.string.onboarding_football_action_no_football), onNoFootball),
    ) {
        FootballLabel(stringResource(R.string.onboarding_football_leagues_label).uppercase())
        FlowRow(
            Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(12.mpx),
            verticalArrangement = Arrangement.spacedBy(12.mpx),
        ) {
            offered.forEachIndexed { i, league ->
                LeagueChip(
                    league = league,
                    chosen = league.key in chosen,
                    onClick = { onToggle(league.key) },
                    modifier = if (i == 0) Modifier.focusRequester(first) else Modifier,
                )
            }
        }
        FootballNote(
            if (chosen.isEmpty()) stringResource(R.string.onboarding_football_leagues_none, settingsPath)
            else stringResource(R.string.onboarding_football_leagues_more, settingsPath),
            Modifier.padding(top = 16.mpx),
        )
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun TeamsPage(
    keys: List<String>,
    loader: FootballTeamLoader,
    chosen: List<FollowedTeam>,
    teamsPath: String,
    onToggle: (FollowedTeam) -> Unit,
    onContinue: () -> Unit,
    onLater: () -> Unit,
    back: ChapterAction,
) {
    // A list that finished (or failed) is fetched again only on Try again; one not asked yet is fetched now.
    LaunchedEffect(keys, loader.loading) { loader.load(keys) }
    val phase = loader.phase(keys)
    val loaded = loader.loaded
    val sections = remember(keys, loaded) { FootballPicks.sections(keys, loaded) }
    var query by remember { mutableStateOf(String()) }
    val results = remember(query, keys, loaded) { FootballPicks.search(query, keys, loaded) }
    val missing = keys.filter { it in loader.failed && loaded[it].isNullOrEmpty() }

    val searchFocus = remember { FocusRequester() }
    val firstCrest = remember { FocusRequester() }
    val retryFocus = remember { FocusRequester() }
    var searchHasFocus by remember { mutableStateOf(false) }
    var placed by remember { mutableStateOf(false) }
    // Try again was pressed: the pill goes away when the lists arrive, so focus is placed again then.
    var retried by remember { mutableStateOf(false) }
    // First focus: the first crest once the grid is there, Try again when nothing loaded, the search
    // meanwhile. A later phase change only moves focus that is still parked on an empty search, or that
    // sat on Try again.
    LaunchedEffect(phase, sections.isNotEmpty()) {
        if (placed && !(searchHasFocus && query.isEmpty()) && !retried) return@LaunchedEffect
        if (phase == TeamsPhase.READY) retried = false
        delay(FOCUS_SETTLE_MS)
        val target = when {
            phase == TeamsPhase.READY && sections.isNotEmpty() -> firstCrest
            phase == TeamsPhase.FAILED -> retryFocus
            else -> searchFocus
        }
        // A few frames' grace: the grid or the button may attach a moment after the phase changed.
        for (attempt in 0 until FOCUS_ATTEMPTS) {
            if (runCatching { target.requestFocus() }.getOrDefault(false)) {
                placed = true
                break
            }
            withFrameNanos { }
        }
    }

    val separator = stringResource(R.string.content_epg_bits_separator)
    ChapterFrame(
        chapter = OnboardingChapter.FOOTBALL,
        title = stringResource(R.string.onboarding_football_teams_title),
        subtitle = stringResource(R.string.onboarding_football_teams_sub),
        primary = ChapterAction(stringResource(R.string.onboarding_action_continue), onContinue),
        secondary = back,
        skip = ChapterAction(stringResource(R.string.onboarding_action_later), onLater),
    ) {
        FootballNote(
            if (chosen.isEmpty()) stringResource(R.string.onboarding_football_teams_none_yet)
            else pluralStringResource(R.plurals.onboarding_football_teams_chosen, chosen.size, chosen.size, chosen.joinToString(separator) { it.name }),
            icon = OwnTVIcon.STAR,
            modifier = Modifier.padding(bottom = 10.mpx),
        )
        val searchTitle = stringResource(R.string.onboarding_football_teams_search)
        StageFieldRow(
            icon = OwnTVIcon.SEARCH,
            label = searchTitle,
            value = query,
            onValueChange = { query = it },
            placeholder = stringResource(R.string.onboarding_football_teams_search_hint),
            backLabel = stringResource(R.string.onboarding_football_teams_title),
            modifier = Modifier.focusRequester(searchFocus).onFocusChanged { searchHasFocus = it.hasFocus },
        )
        results.forEach { team ->
            val followed = FootballPicks.follow(team)
            StageSettingRow(
                icon = OwnTVIcon.STAR,
                title = team.name,
                desc = team.location,
                value = SettingValue.Switch(chosen.any { it.key == followed.key }),
                onClick = { onToggle(followed) },
            )
        }
        if (query.trim().length >= 2 && results.isEmpty() && phase == TeamsPhase.READY) {
            FootballNote(stringResource(R.string.onboarding_football_teams_not_found, query.trim()), Modifier.padding(vertical = 8.mpx))
        }

        when (phase) {
            TeamsPhase.LOADING -> FootballNote(
                stringResource(R.string.onboarding_football_teams_loading),
                Modifier.padding(top = 16.mpx),
                icon = OwnTVIcon.REFRESH,
            )
            TeamsPhase.FAILED -> {
                if (loader.loading) {
                    FootballNote(stringResource(R.string.onboarding_football_teams_loading), Modifier.padding(top = 16.mpx), icon = OwnTVIcon.REFRESH)
                } else {
                    FootballNote(stringResource(R.string.onboarding_football_teams_failed, teamsPath), Modifier.padding(top = 16.mpx), icon = OwnTVIcon.WARNING)
                }
                RetryPill(focus = retryFocus) {
                    retried = true
                    loader.load(keys, retry = true)
                }
            }
            TeamsPhase.READY -> {
                sections.forEachIndexed { s, section ->
                    StageSettingsHeading(section.league.name, section.teams.size, first = s == 0 && results.isEmpty())
                    CrestGrid(section.teams, chosen, onToggle, if (s == 0) firstCrest else null)
                }
                if (missing.isNotEmpty()) {
                    val names = missing.mapNotNull { SportsCatalog.league(it)?.name }.joinToString(separator)
                    FootballNote(stringResource(R.string.onboarding_football_teams_partial, names), Modifier.padding(top = 16.mpx), icon = OwnTVIcon.WARNING)
                    RetryPill(focus = retryFocus) {
                        retried = true
                        loader.load(keys, retry = true)
                    }
                }
            }
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun CrestGrid(teams: List<Team>, chosen: List<FollowedTeam>, onToggle: (FollowedTeam) -> Unit, firstFocus: FocusRequester?) {
    FlowRow(
        Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(12.mpx),
        verticalArrangement = Arrangement.spacedBy(12.mpx),
    ) {
        teams.forEachIndexed { i, team ->
            val followed = FootballPicks.follow(team)
            CrestTile(
                team = team,
                chosen = chosen.any { it.key == followed.key },
                onClick = { onToggle(followed) },
                modifier = if (i == 0 && firstFocus != null) Modifier.focusRequester(firstFocus) else Modifier,
            )
        }
    }
}

/** Try again. Always enabled, so it keeps the focus while a retry runs (a second press is ignored). */
@Composable
private fun RetryPill(focus: FocusRequester, onClick: () -> Unit) {
    StagePill(
        stringResource(R.string.onboarding_football_retry),
        onClick = onClick,
        icon = OwnTVIcon.REFRESH,
        modifier = Modifier.padding(top = 12.mpx).focusRequester(focus),
    )
}

/** Lets a page's content attach before focus is moved into it. */
private const val FOCUS_SETTLE_MS = 80L

/** Frames to wait for a focus target to attach. */
private const val FOCUS_ATTEMPTS = 10

package tv.own.owntv.features.settings

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.requiredSize
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.wrapContentSize
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.graphics.TransformOrigin
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.em
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.tv.material3.Text
import org.koin.androidx.compose.koinViewModel
import tv.own.owntv.R
import tv.own.owntv.core.home.TrendingHomeItem
import tv.own.owntv.features.home.HomeViewModel
import tv.own.owntv.ui.components.OwnTVIcon
import tv.own.owntv.ui.stage.drawInnerRing
import tv.own.owntv.ui.stage.drawOuterRing
import tv.own.owntv.ui.stage.stageBackground
import tv.own.owntv.ui.stage.stageGlass
import tv.own.owntv.ui.theme.StageColors
import tv.own.owntv.ui.theme.mpx
import tv.own.owntv.ui.theme.stageAccent
import tv.own.owntv.ui.theme.stageText
import tv.own.owntv.core.epg.displayLogoUrl

/**
 * Settings › Appearance & Home's LIVE PREVIEW: a small Mayo TV Home at a third of the screen's size, in
 * the chosen layout (Standard, Classic, Stage) with or without row titles, drawn from Home's own channel
 * row and Apps row. Not a second Home — nothing focusable — but it draws with the live accent, focus
 * colour, glass and font, so a change shows here before the row is left. (It showed OwnTV's Trending
 * hero until 4 Nov 2026, which Mayo TV's Home no longer has.)
 */
@Composable
internal fun SettingsLivePreview() {
    val home: HomeViewModel = koinViewModel()
    val state by home.uiState.collectAsStateWithLifecycle()
    val launcher = tv.own.owntv.launcher.apps.rememberLauncherConfig()
    val apps by tv.own.owntv.launcher.apps.LauncherApps.apps.collectAsStateWithLifecycle()
    val compact = launcher.homeLayout != tv.own.owntv.launcher.apps.LauncherConfig.LAYOUT_STANDARD
    val spotlight = launcher.homeLayout == tv.own.owntv.launcher.apps.LauncherConfig.LAYOUT_SPOTLIGHT
    val stage = launcher.homeLayout == tv.own.owntv.launcher.apps.LauncherConfig.LAYOUT_STAGE || spotlight
    val titles = launcher.rowTitles
    val channels = (state.catalog?.channels ?: state.recentLive).take(6)
    val onNow = state.recentOnNow
    val catalogTitle = state.catalog?.category?.name ?: stringResource(R.string.home_row_recent_channels)
    val appsTitle = stringResource(R.string.launcher_row_apps)
    val appChannelsTitle = stringResource(R.string.launcher_app_channels)
    val youtube = stringResource(R.string.launcher_youtube)
    val appNames = tv.own.owntv.launcher.apps.rowApps(launcher, apps).map { if (tv.own.owntv.launcher.apps.isTizenTube(it.pkg)) youtube else it.label }
    val a = stageAccent
    val ink = tv.own.owntv.ui.theme.LocalStagePalette.current.ink
    SettingPanelHeading(stringResource(R.string.settings_ca_preview))
    BoxWithConstraints(
        Modifier
            .fillMaxWidth()
            .aspectRatio(16f / 9f)
            .clip(RoundedCornerShape(18.mpx))
            .drawBehind { drawInnerRing(ink.copy(alpha = 0.1f), 1.mpx.toPx(), 18.mpx.toPx()) },
    ) {
        // The 1920 × 1080 frame, scaled down to the box: every size below is the real screen's.
        val scale = maxWidth / 1920.mpx
        Box(
            Modifier
                .wrapContentSize(Alignment.TopStart, unbounded = true)
                .requiredSize(1920.mpx, 1080.mpx)
                .graphicsLayer { scaleX = scale; scaleY = scale; transformOrigin = TransformOrigin(0f, 0f) },
        ) {
            // The user's picture, when Glass & background shows one, as behind the real Home.
            val background = tv.own.owntv.ui.theme.LocalBackground.current
            if (background.showsPicture) {
                tv.own.owntv.ui.components.BackgroundPicture(background, tv.own.owntv.ui.theme.LocalBlurredBackdrop.current, Modifier.fillMaxSize())
            }
            Box(Modifier.fillMaxSize().stageBackground(a.accent))
            // The rail at rest: a glass capsule of icons.
            Column(
                Modifier.padding(start = 26.mpx, top = 230.mpx).width(60.mpx).stageGlass(30.mpx).padding(vertical = 18.mpx),
                verticalArrangement = Arrangement.spacedBy(22.mpx),
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                listOf(OwnTVIcon.SEARCH, OwnTVIcon.HOME, OwnTVIcon.LIVE_TV, OwnTVIcon.SPORTS, OwnTVIcon.MOVIES, OwnTVIcon.SERIES, OwnTVIcon.GRID).forEachIndexed { i, icon ->
                    OwnTVIcon(icon, if (i == 1) a.accent else StageColors.Muted, Modifier.size(26.mpx))
                }
            }
            Text(
                android.text.format.DateFormat.getTimeFormat(androidx.compose.ui.platform.LocalContext.current).format(java.util.Date()),
                style = stageText(44, 800), color = StageColors.Text, maxLines = 1, overflow = TextOverflow.Ellipsis,
                modifier = Modifier.align(Alignment.TopEnd).padding(top = 40.mpx, end = 72.mpx),
            )
            Column(Modifier.padding(start = 150.mpx, top = if (compact) 44.mpx else 130.mpx), verticalArrangement = Arrangement.spacedBy(30.mpx)) {
                if (compact) PreviewPills(appNames, a)
                if (stage) PreviewStage(channels.firstOrNull(), onNow, a, spotlight) else PreviewChannelRow(catalogTitle, channels, onNow, titles, a, ink)
                if (stage) PreviewLogoStrip(channels, a, ink)
                if (!compact) PreviewAppsRow(appsTitle, appNames, titles, ink)
                PreviewBlocksRow(appChannelsTitle, titles && compact, ink)
            }
        }
    }
    Text(
        stringResource(R.string.settings_live_preview_note), style = stageText(15, 500), color = StageColors.Muted,
        modifier = Modifier.padding(top = 12.mpx, start = 2.mpx),
    )
    Box(Modifier.padding(top = 22.mpx, bottom = 18.mpx).fillMaxWidth().height(1.mpx).background(ink.copy(alpha = 0.1f)))
}

internal fun backdropOf(item: TrendingHomeItem): String? =
    tv.own.owntv.core.metadata.MetadataImages.backdrop(item.snapshot.backdropPath, size = "w780")
        ?: when (item) {
            is TrendingHomeItem.Movie -> item.movie.backdropUrl ?: item.movie.posterUrl
            is TrendingHomeItem.Series -> item.series.backdropUrl ?: item.series.posterUrl
        }

/** Classic and Stage: the apps as small buttons on top. */
@Composable
private fun PreviewPills(names: List<String>, a: tv.own.owntv.ui.theme.StageAccent) {
    Row(horizontalArrangement = Arrangement.spacedBy(14.mpx)) {
        (names.take(4) + stringResource(R.string.launcher_all_apps)).forEach { name ->
            Box(Modifier.height(60.mpx).stageGlass(30.mpx).padding(horizontal = 26.mpx), contentAlignment = Alignment.Center) {
                Text(name, style = stageText(20, 800), color = StageColors.Text, maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
        }
    }
}

/** Standard and Classic: the channel row, the first card drawn focused, what is on now under each. */
@Composable
private fun PreviewChannelRow(
    title: String,
    channels: List<tv.own.owntv.core.database.entity.ChannelEntity>,
    onNow: Map<Long, tv.own.owntv.core.database.entity.EpgProgrammeEntity>,
    titles: Boolean,
    a: tv.own.owntv.ui.theme.StageAccent,
    ink: androidx.compose.ui.graphics.Color,
) {
    Column(verticalArrangement = Arrangement.spacedBy(14.mpx)) {
        if (titles) Text(title, style = stageText(27, 800), color = StageColors.Text, maxLines = 1, overflow = TextOverflow.Ellipsis)
        Row(horizontalArrangement = Arrangement.spacedBy(22.mpx)) {
            channels.forEachIndexed { i, ch ->
                Column(Modifier.width(300.mpx), verticalArrangement = Arrangement.spacedBy(10.mpx)) {
                    Box(
                        Modifier.fillMaxWidth().height(169.mpx)
                            .then(if (i == 0) Modifier.drawBehind { drawOuterRing(a.focus, 4.mpx.toPx(), 16.mpx.toPx()) } else Modifier)
                            .clip(RoundedCornerShape(16.mpx)).background(ink.copy(alpha = 0.08f)),
                        contentAlignment = Alignment.Center,
                    ) { PreviewLogo(ch, Modifier.size(150.mpx, 100.mpx)) }
                    Text(onNow[ch.id]?.title ?: ch.name, style = stageText(19, 700), color = StageColors.Text, maxLines = 1, overflow = TextOverflow.Ellipsis)
                }
            }
        }
    }
}

/** Stage: the focused channel's programme large, its picture beside it. */
@Composable
private fun PreviewStage(
    ch: tv.own.owntv.core.database.entity.ChannelEntity?,
    onNow: Map<Long, tv.own.owntv.core.database.entity.EpgProgrammeEntity>,
    a: tv.own.owntv.ui.theme.StageAccent,
    spotlight: Boolean,
) {
    Row(Modifier.height(if (spotlight) 560.mpx else 330.mpx).width(1680.mpx), verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(14.mpx)) {
            Text(stringResource(R.string.home_stage_live), style = stageText(18, 800), color = a.accent, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Text((ch?.let { onNow[it.id]?.title ?: it.name }).orEmpty(), style = stageText(64, 800), color = StageColors.Text, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
        Box(
            Modifier.size(if (spotlight) 960.mpx else 540.mpx, if (spotlight) 540.mpx else 304.mpx)
                .clip(RoundedCornerShape(18.mpx)).background(androidx.compose.ui.graphics.Color.Black),
            contentAlignment = Alignment.Center,
        ) {
            if (ch != null) PreviewLogo(ch, Modifier.size(240.mpx, 160.mpx))
        }
    }
}

/** Stage: the strip of logos under it, the first drawn focused. */
@Composable
private fun PreviewLogoStrip(channels: List<tv.own.owntv.core.database.entity.ChannelEntity>, a: tv.own.owntv.ui.theme.StageAccent, ink: androidx.compose.ui.graphics.Color) {
    Row(horizontalArrangement = Arrangement.spacedBy(18.mpx)) {
        channels.forEachIndexed { i, ch ->
            Box(
                Modifier.size(200.mpx, 112.mpx)
                    .then(if (i == 0) Modifier.drawBehind { drawOuterRing(a.focus, 4.mpx.toPx(), 16.mpx.toPx()) } else Modifier)
                    .clip(RoundedCornerShape(16.mpx)).background(ink.copy(alpha = 0.08f)),
                contentAlignment = Alignment.Center,
            ) { PreviewLogo(ch, Modifier.size(110.mpx, 76.mpx)) }
        }
    }
}

/** Standard: the Apps row of big cards. */
@Composable
private fun PreviewAppsRow(title: String, names: List<String>, titles: Boolean, ink: androidx.compose.ui.graphics.Color) {
    Column(verticalArrangement = Arrangement.spacedBy(14.mpx)) {
        if (titles) Text(title, style = stageText(27, 800), color = StageColors.Text, maxLines = 1, overflow = TextOverflow.Ellipsis)
        Row(horizontalArrangement = Arrangement.spacedBy(22.mpx)) {
            names.take(4).forEach { name ->
                Box(Modifier.size(300.mpx, 169.mpx).clip(RoundedCornerShape(16.mpx)).background(ink.copy(alpha = 0.08f)), contentAlignment = Alignment.Center) {
                    Text(name, style = stageText(28, 800), color = StageColors.Text, maxLines = 1, overflow = TextOverflow.Ellipsis)
                }
            }
        }
    }
}

/** The other apps' channels (YouTube, Nuvio …): blocks only, their pictures are the apps' own. */
@Composable
private fun PreviewBlocksRow(title: String, titles: Boolean, ink: androidx.compose.ui.graphics.Color) {
    Column(verticalArrangement = Arrangement.spacedBy(14.mpx)) {
        if (titles) Text(title, style = stageText(27, 800), color = StageColors.Text, maxLines = 1, overflow = TextOverflow.Ellipsis)
        Row(horizontalArrangement = Arrangement.spacedBy(22.mpx)) {
            repeat(5) { Box(Modifier.size(356.mpx, 200.mpx).clip(RoundedCornerShape(16.mpx)).background(ink.copy(alpha = 0.06f))) }
        }
    }
}

@Composable
private fun PreviewLogo(ch: tv.own.owntv.core.database.entity.ChannelEntity, modifier: Modifier) {
    tv.own.owntv.ui.components.ChannelLogoTile(
        logoUrl = ch.displayLogoUrl,
        modifier = modifier,
        fill = androidx.compose.ui.graphics.Color.Transparent,
    ) { Text(ch.name.take(2).uppercase(), style = stageText(34, 800), color = StageColors.Text, maxLines = 1, overflow = TextOverflow.Ellipsis) }
}

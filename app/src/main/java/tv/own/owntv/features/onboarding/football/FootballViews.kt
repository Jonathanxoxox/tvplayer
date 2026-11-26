package tv.own.owntv.features.onboarding.football

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
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.tv.material3.Text
import tv.own.owntv.core.sports.model.League
import tv.own.owntv.core.sports.model.Team
import tv.own.owntv.features.sports.Crest
import tv.own.owntv.ui.components.OwnTVIcon
import tv.own.owntv.ui.stage.StageFocus
import tv.own.owntv.ui.stage.StageSurface
import tv.own.owntv.ui.stage.stageGlass
import tv.own.owntv.ui.theme.StageColors
import tv.own.owntv.ui.theme.StageRadii
import tv.own.owntv.ui.theme.mpx
import tv.own.owntv.ui.theme.stageAccent
import tv.own.owntv.ui.theme.stageText

/** Width of one crest tile; six fit the chapter panel's inner width with their gaps. */
internal val CREST_TILE_WIDTH = 140.mpx

/**
 * A competition as a large toggle chip: its country's flag (the football mark for the international
 * ones), its name, and a tick while it is chosen. Chosen chips keep an accent wash, so
 * the choice reads from across the room without focus on them.
 */
@Composable
internal fun LeagueChip(league: League, chosen: Boolean, onClick: () -> Unit, modifier: Modifier = Modifier) {
    val a = stageAccent
    val r = StageRadii.Pill
    StageSurface(
        onClick = onClick,
        radius = r,
        modifier = modifier.height(60.mpx),
        idle = if (chosen) Modifier.background(a.accent.copy(alpha = 0.22f), RoundedCornerShape(r)) else Modifier.stageGlass(r),
        contentAlignment = Alignment.Center,
    ) { focused ->
        val ink = if (focused) a.onAccent else StageColors.Text
        Row(
            Modifier.padding(horizontal = 20.mpx),
            horizontalArrangement = Arrangement.spacedBy(12.mpx),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            val flag = flagOf(league.country)
            if (flag != null) {
                Text(flag, style = stageText(22, 500), maxLines = 1, overflow = TextOverflow.Ellipsis)
            } else {
                OwnTVIcon(OwnTVIcon.SPORTS, if (focused) a.onAccent else a.accent, Modifier.size(22.mpx))
            }
            Text(league.name, style = stageText(20, 700), color = ink, maxLines = 1, overflow = TextOverflow.Ellipsis)
            if (chosen) OwnTVIcon(OwnTVIcon.CHECK, if (focused) a.onAccent else a.accent, Modifier.size(22.mpx))
        }
    }
}

/**
 * One team in the grid: its crest (or abbreviation), its name below, and a tick badge while it is
 * chosen. Focus is the rows' FX treatment, so the crest stays visible on the focused tile.
 */
@Composable
internal fun CrestTile(team: Team, chosen: Boolean, onClick: () -> Unit, modifier: Modifier = Modifier) {
    val a = stageAccent
    val r = StageRadii.Poster
    StageSurface(
        onClick = onClick,
        radius = r,
        modifier = modifier.width(CREST_TILE_WIDTH).height(164.mpx),
        focusStyle = StageFocus.FX,
        idle = if (chosen) Modifier.background(a.accent.copy(alpha = 0.22f), RoundedCornerShape(r)) else Modifier.stageGlass(r),
        contentAlignment = Alignment.TopCenter,
    ) { focused ->
        Column(
            Modifier.fillMaxWidth().padding(top = 16.mpx, start = 8.mpx, end = 8.mpx),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(10.mpx),
        ) {
            Crest(team, 76)
            Text(
                team.shortName.ifBlank { team.name },
                style = stageText(17, 700),
                color = if (focused) a.onAccent else StageColors.Text,
                textAlign = TextAlign.Center,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
        }
        if (chosen) {
            Box(
                Modifier.align(Alignment.TopEnd).padding(8.mpx).size(30.mpx).background(a.accent, CircleShape),
                contentAlignment = Alignment.Center,
            ) {
                OwnTVIcon(OwnTVIcon.CHECK, a.onAccent, Modifier.size(18.mpx))
            }
        }
    }
}

/** A quiet line of explanation under a group of controls. */
@Composable
internal fun FootballNote(text: String, modifier: Modifier = Modifier, icon: OwnTVIcon = OwnTVIcon.INFO) {
    Row(modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.mpx), verticalAlignment = Alignment.Top) {
        OwnTVIcon(icon, StageColors.Muted, Modifier.padding(top = 3.mpx).size(20.mpx))
        Text(text, style = stageText(17, 500), color = StageColors.Muted, modifier = Modifier.weight(1f))
    }
}

/** A small caps label above a group (the first page's "Competitions"). */
@Composable
internal fun FootballLabel(text: String, modifier: Modifier = Modifier) {
    Text(text, style = stageText(16, 800), color = StageColors.Muted, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = modifier.padding(top = 6.mpx, bottom = 4.mpx))
}

/**
 * The flag emoji for an ISO 3166 country code ("DE" → 🇩🇪), built from regional indicator letters;
 * null for international competitions and anything that is not two letters.
 */
internal fun flagOf(country: String?): String? {
    if (country == null || country.length != 2 || !country.all { it in 'A'..'Z' }) return null
    val base = REGIONAL_A - 'A'.code
    return String(Character.toChars(base + country[0].code)) + String(Character.toChars(base + country[1].code))
}

/** Regional indicator symbol letter A. */
private const val REGIONAL_A = 0x1F1E6

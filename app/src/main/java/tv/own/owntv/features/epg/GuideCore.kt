package tv.own.owntv.features.epg

import tv.own.owntv.ui.theme.LocalStagePalette
import tv.own.owntv.ui.theme.stageText
import tv.own.owntv.ui.theme.StageColors
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.text.drawText
import androidx.compose.ui.graphics.drawscope.clipRect
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.style.TextDirection
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.focus.FocusRequester
import androidx.tv.material3.Text
import tv.own.owntv.R
import tv.own.owntv.core.database.entity.EpgProgrammeEntity
import tv.own.owntv.ui.components.OwnTVIcon
import tv.own.owntv.ui.format.rememberSystemTimeFormatter
import tv.own.owntv.ui.theme.mpx
import tv.own.owntv.ui.stage.drawBoxShadow
import tv.own.owntv.ui.components.drawStageGlyph

internal object GuideGridDefaults {
    /** The channel column when the guide widths are not customised (48 → 488 of 1920): wide enough for
     *  whole names such as "DAZN 1 HD (Web 1080)", as TiviMate's column is. */
    const val ChannelColShare = 440f / 1920f
    /** `.gcell` rows: 56 high, 62 apart. */
    val RowHeight = 56.mpx
    val RowGap = 6.mpx
    /** 3.5 hours across the 1512 px timeline. */
    val PxPerMin = 7.2.mpx
    const val SlotMin = 30
}

/**
 * One channel's programmes as Stage cells (`.gcell`): white 4.5%, the one on now 8.5%, past ones
 * at half opacity, radius 14, 3 px apart; title 18/700, then the recording ● / reminder bell / catch-up
 * ↺ icons and the time in 14 dim. The cell at [focusTime] is the focused one: accent fill, 2 px focus
 * ring and glow. One Canvas per row rather than a node per programme, so a week of guide scrolls light.
 */
/** TV Player: cells show only the title; the times and icons stay in the details above the grid. */
private const val TitleOnly = true

@Composable
internal fun ProgrammeStripCanvas(
    programmes: List<EpgProgrammeEntity>,
    windowStart: Long,
    windowEnd: Long,
    now: Long,
    focusTime: Long?,
    catchupIds: Set<Long>,
    recordingStarts: Set<Long>,
    reminderStarts: Set<Long>,
    /** How far the timeline is scrolled, shared by every row and the ruler. Read while drawing only. */
    scrollPx: () -> Int,
) {
    val density = androidx.compose.ui.platform.LocalDensity.current
    val measurer = rememberTextMeasurer(cacheSize = 64)
    val a = tv.own.owntv.ui.theme.stageAccent
    val px = with(density) { 1.mpx.toPx() }
    val pxPerMin = with(density) { GuideGridDefaults.PxPerMin.toPx() }
    val titleStyle = tv.own.owntv.ui.theme.stageText(tv.own.owntv.ui.theme.StageListText, 600).copy(textDirection = TextDirection.Content)
    // "No programme" exactly as a programme's title, only in a quieter colour (30 Oct 2026).
    val emptyStyle = titleStyle
    val timeStyle = tv.own.owntv.ui.theme.stageText(14, 400).copy(fontFeatureSettings = "tnum", textDirection = TextDirection.Content)
    val formatTime = rememberSystemTimeFormatter()
    val rangeTemplate = stringResource(R.string.content_live_time_range_plain)
    // Only needed for the small line, which title-only cells never draw: formatting two times per programme
    // on every row that comes into view was pure waste while scrolling.
    val labels = remember(programmes, formatTime, rangeTemplate) {
        if (TitleOnly) emptyList()
        else programmes.map { String.format(java.util.Locale.ROOT, rangeTemplate, formatTime(it.startMs), formatTime(it.stopMs)) }
    }
    val recRed = Color(0xFFFF5B5B)
    val textColor = tv.own.owntv.ui.theme.StageColors.Text
    val dimColor = tv.own.owntv.ui.theme.StageColors.Dim
    val ink = tv.own.owntv.ui.theme.StageColors.Ink
    val palette = tv.own.owntv.ui.theme.LocalStagePalette.current
    // A row with nothing in the window says so, rather than looking broken.
    val emptyText = stringResource(R.string.content_epg_no_programme)
    // Clipped at the timeline's left edge only, so a focused cell's glow still spills up and down.
    Canvas(Modifier.fillMaxSize()) { val scroll = scrollPx(); clipRect(left = 0f, top = -size.height, right = size.width + 40 * px, bottom = size.height * 2) {
        // The focused cell's ring. In light the focus colour is the accent, the cell's own fill, so the
        // ring stands off it with a page-coloured gap first (`.mine.light .gcell.f`: 2.5 page + 2.5 accent).
        fun focusRing(x0: Float, w: Float, h: Float, r: CornerRadius) {
            if (palette.isLight) {
                for ((colour, out) in listOf(palette.page to 1.25f * px, a.accent to 3.75f * px)) {
                    drawRoundRect(colour, Offset(x0 - out, -out), Size(w + 2 * out, h + 2 * out), CornerRadius(r.x + out), style = Stroke(2.5f * px))
                }
            } else {
                drawRoundRect(a.focus, Offset(x0 - px, -px), Size(w + 2 * px, h + 2 * px), CornerRadius(r.x + px), style = Stroke(2 * px))
            }
        }
        val viewW = size.width
        val h = size.height
        // TV Player: flat, faint cells with soft corners and narrow gaps (calmer since 30 Oct 2026).
        val r = CornerRadius(10 * px)
        // Feeds sometimes carry two programmes for the same time. Each one is drawn from where the one
        // before it ends, so they never sit on top of each other; one hidden completely is left out.
        var shownUntil = windowStart
        var drawnAny = false
        // The cursor on a stretch with nothing on (a feed that stops): that gap is the focused cell.
        if (focusTime != null && programmes.none { focusTime in it.startMs until it.stopMs }) {
            val gapStart = (programmes.lastOrNull { it.stopMs <= focusTime }?.stopMs ?: windowStart).coerceAtLeast(windowStart)
            val gapEnd = (programmes.firstOrNull { it.startMs > focusTime }?.startMs ?: windowEnd).coerceAtMost(windowEnd)
            val gx0 = (((gapStart - windowStart) / 60_000f) * pxPerMin - scroll + 3 * px).coerceAtLeast(0f)
            val gx1 = (((gapEnd - windowStart) / 60_000f) * pxPerMin - scroll - 3 * px).coerceAtMost(viewW)
            if (gx1 > gx0) {
                val box = androidx.compose.ui.geometry.Rect(gx0, 0f, gx1, h)
                if (palette.isLight) {
                    drawBoxShadow(a.accent.copy(alpha = 0.4f), 34 * px, r.x, dy = 12 * px, bounds = box)
                    drawRoundRect(a.accent, Offset(gx0, 0f), Size(gx1 - gx0, h), r)
                    focusRing(gx0, gx1 - gx0, h, r)
                } else {
                    drawRoundRect(tv.own.owntv.ui.theme.StageFocusFill, Offset(gx0, 0f), Size(gx1 - gx0, h), r)
                }
                val label = measurer.measure(emptyText, emptyStyle.copy(color = a.onAccent), overflow = TextOverflow.Ellipsis, maxLines = 1, softWrap = false, constraints = Constraints(maxWidth = (gx1 - gx0 - 28 * px).toInt().coerceAtLeast(1)))
                drawText(label, topLeft = Offset(gx0 + 14 * px, (h - label.size.height) / 2f))
                drawnAny = true
            }
        }
        programmes.forEachIndexed { i, p ->
            val s0 = p.startMs.coerceIn(windowStart, windowEnd).coerceAtLeast(shownUntil)
            val e0 = p.stopMs.coerceIn(windowStart, windowEnd)
            if (e0 <= s0) return@forEachIndexed
            shownUntil = e0
            val x = ((s0 - windowStart) / 60_000f) * pxPerMin - scroll.toFloat() + 2 * px
            val w = ((e0 - s0) / 60_000f) * pxPerMin - 4 * px
            if (w <= 0f || x + w <= 0f || x >= viewW) return@forEachIndexed
            drawnAny = true
            val focused = focusTime != null && focusTime in p.startMs until p.stopMs
            val isNow = now in p.startMs until p.stopMs
            val fade = if (!focused && p.stopMs <= now) 0.45f else 1f
            val box = androidx.compose.ui.geometry.Rect(x, 0f, x + w, h)
            if (focused && palette.isLight) {
                drawBoxShadow(a.accent.copy(alpha = 0.4f), 34 * px, r.x, dy = 12 * px, bounds = box)
                drawRoundRect(a.accent, Offset(x, 0f), Size(w, h), r)
                focusRing(x, w, h, r)
            } else if (focused) {
                // Plain white, no shadow or ring: the same focus as everywhere else.
                drawRoundRect(tv.own.owntv.ui.theme.StageFocusFill, Offset(x, 0f), Size(w, h), r)
            } else {
                drawRoundRect(ink.copy(alpha = (if (isNow) 0.11f else 0.06f) * fade), Offset(x, 0f), Size(w, h), r)
            }
            // Text starts at the visible part of a cell that began before the timeline's left edge.
            val tx = maxOf(x, 0f)
            val textW = (x + w - tx - 28 * px).toInt()
            if (textW <= 4) return@forEachIndexed
            val titleColor = if (focused) a.onAccent else textColor.copy(alpha = fade)
            val smallColor = if (focused) Color.Black.copy(alpha = 0.6f) else dimColor.copy(alpha = fade)
            val iconColor = if (focused) a.onAccent else a.accent.copy(alpha = fade)
            // Measured without its colour, which is applied when drawing: focusing a cell or the clock passing
            // then reuses the cached layout instead of measuring the title again.
            val title = measurer.measure(p.title, titleStyle, overflow = TextOverflow.Ellipsis, maxLines = 1, softWrap = false, constraints = Constraints(maxWidth = textW))
            // The title alone, centred in the cell, as the classic player shows it.
            drawText(title, color = titleColor, topLeft = Offset(tx + 14 * px, (h - title.size.height) / 2f))
            if (p.startMs in recordingStarts) {
                drawCircle(if (focused) recRed else recRed.copy(alpha = fade), 4 * px, Offset(x + w - 12 * px, h / 2f))
            }
            if (TitleOnly) return@forEachIndexed
            // The small line: icons first, 8 apart, then the time.
            var cx = tx + 14 * px
            val lineTop = 8 * px + title.size.height + 3 * px
            val icon = 14 * px
            fun glyph(g: OwnTVIcon, c: Color, filled: Boolean = false) {
                if (cx + icon > x + w - 14 * px) return
                drawStageGlyph(g, c, Offset(cx, lineTop + 2 * px), icon, filled)
                cx += icon + 8 * px
            }
            if (p.startMs in recordingStarts) glyph(OwnTVIcon.REC, if (focused) a.onAccent else recRed.copy(alpha = fade), filled = true)
            if (p.startMs in reminderStarts) glyph(OwnTVIcon.BELL, iconColor)
            if (p.id in catchupIds) glyph(OwnTVIcon.REWIND, iconColor)
            val timeW = (x + w - 14 * px - cx).toInt()
            if (timeW > 4) {
                val time = measurer.measure(labels[i], timeStyle.copy(color = smallColor), overflow = TextOverflow.Ellipsis, maxLines = 1, softWrap = false, constraints = Constraints(maxWidth = timeW))
                drawText(time, topLeft = Offset(cx, lineTop))
            }
        }
        // A stretch in view with nothing on, between or after the programmes (the provider's short guide
        // begins with what is on now; a feed can stop or skip), says so as a quiet cell, as a row with nothing
        // at all does, instead of a hole (Jonathan, 31 Oct 2026). The focused one is drawn above already.
        if (drawnAny) {
            val visTo = windowStart + ((scroll + viewW) / pxPerMin * 60_000f).toLong()
            fun gap(from: Long, to: Long) {
                if (focusTime != null && focusTime in from until to) return
                val gx = ((from - windowStart) / 60_000f) * pxPerMin - scroll + 2 * px
                val gw = ((to - from) / 60_000f) * pxPerMin - 4 * px
                if (gw < 48 * px) return
                drawRoundRect(ink.copy(alpha = 0.03f), Offset(gx, 0f), Size(gw, h), r)
                val tx = maxOf(gx, 0f)
                val textW = (gx + gw - tx - 28 * px).toInt()
                if (textW <= 4) return
                val label = measurer.measure(emptyText, emptyStyle.copy(color = dimColor), overflow = TextOverflow.Ellipsis, maxLines = 1, softWrap = false, constraints = Constraints(maxWidth = textW))
                drawText(label, topLeft = Offset(tx + 14 * px, (h - label.size.height) / 2f))
            }
            var t = maxOf(windowStart, windowStart + (scroll / pxPerMin * 60_000f).toLong())
            for (p in programmes) {
                if (t >= visTo) break
                if (p.stopMs <= t) continue
                if (p.startMs > t) gap(t, minOf(p.startMs, visTo))
                t = maxOf(t, p.stopMs)
            }
            if (t < visTo) gap(t, minOf(visTo, windowEnd))
        }
        // Nothing on anywhere in view: say so, rather than a row that looks broken.
        if (!drawnAny) {
            drawRoundRect(ink.copy(alpha = 0.03f), Offset(3 * px, 0f), Size(viewW - 6 * px, h), r)
            val label = measurer.measure(emptyText, emptyStyle.copy(color = dimColor), maxLines = 1, softWrap = false)
            drawText(label, topLeft = Offset(17 * px, (h - label.size.height) / 2f))
        }
    } }
}


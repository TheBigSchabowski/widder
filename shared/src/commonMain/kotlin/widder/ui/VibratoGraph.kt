package widder.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.isShiftPressed
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import widder.audio.PitchTracker
import widder.audio.PracticeEngine
import widder.audio.VibratoLine
import widder.audio.VibratoResult
import widder.core.VibratoSettings
import kotlin.math.abs
import kotlin.math.ceil
import kotlin.math.floor
import kotlin.math.hypot
import kotlin.math.roundToInt

private val accent = Palette.practice
internal val attemptColor = Palette.chords

private val GRAPH_LEFT = 58.dp
private val GRAPH_RIGHT = 10.dp
private val GRAPH_PAD = 12.dp

/**
 * The model and the attempt in cents around the note, over time, with the notes (or their Hz) on the
 * side. While [editing], the line's points are set and dragged here; with [live], the pitch sweeps
 * across pass after pass with a cursor where it is now.
 */
@Composable
internal fun VibratoGraph(
    vm: AppViewModel,
    target: VibratoSettings,
    showTarget: Boolean,
    result: VibratoResult?,
    attemptLive: List<Pair<Float, Float>>?,
    live: VibratoLive?,
    editing: Boolean,
) {
    val seconds = target.seconds.toFloat()
    // The attempt starts where the tone starts, so it lines up with the model.
    val attempt: List<Pair<Float, Float>> = when {
        editing || live != null -> emptyList()
        result != null -> result.curve.indices.map { (it * PitchTracker.HOP_SECONDS).toFloat() to result.curve[it] }
        attemptLive != null -> attemptLive
        else -> emptyList()
    }.let { points ->
        val onset = points.firstOrNull { !it.second.isNaN() }?.first ?: 0f
        points.filter { it.first >= onset }.map { (it.first - onset) to it.second }
    }
    // Live: each pass starts at the left again, the newest drawn over the oldest.
    val sweep = live?.trace?.toList()?.map { (t, c) -> (t % seconds) to c }
    val now = live?.trace?.lastOrNull()?.first?.rem(seconds)
    val span = if (editing || live != null) seconds else maxOf(seconds, attempt.lastOrNull()?.first ?: 0f)
    val steps = target.seconds * 100
    val model = remember(target) { (0..steps).map { (it / 100f) to target.cents(it / 100.0).toFloat() } }
    val line = remember(target) { (0..steps).map { (it / 100f) to target.lineAt(it / 100.0).toFloat() } }
    val range = if (editing) VibratoSettings.LINE_RANGE.toFloat() else {
        val heard = (attempt + sweep.orEmpty()).mapNotNull { it.second.takeIf { c -> !c.isNaN() }?.let(::abs) }.sorted()
        (ceil(
            maxOf(
                100f,
                if (showTarget) model.maxOf { abs(it.second) } + 20f else 0f,
                heard.getOrNull((heard.size * 0.95).toInt())?.plus(20f) ?: 0f,
            ) / 100f,
        ) * 100f).coerceAtMost(600f)
    }
    val division = vm.practice.division
    val measurer = rememberTextMeasurer()
    val grid = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.12f)
    val fine = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.05f)
    val labelColor = MaterialTheme.colorScheme.onSurfaceVariant
    val back = if (editing) accent.copy(alpha = 0.08f) else MaterialTheme.colorScheme.onSurface.copy(alpha = 0.04f)
    val pickedColor = MaterialTheme.colorScheme.onSurface
    val picked = vm.linePoint
    val input = if (!editing) Modifier else Modifier.pointerInput(range, seconds) {
        fun left() = GRAPH_LEFT.toPx()
        fun right() = size.width - GRAPH_RIGHT.toPx()
        fun top() = GRAPH_PAD.toPx()
        fun bottom() = size.height - GRAPH_PAD.toPx()
        fun toU(x: Float) = ((x - left()) / (right() - left())).coerceIn(0f, 1f)
        fun toCents(y: Float): Float {
            val c = ((top() + bottom()) / 2 - y) / ((bottom() - top()) / 2) * range
            return if (vm.lineSnap) VibratoLine.snap(c, division) else c
        }
        fun xOf(u: Float) = left() + (right() - left()) * u
        fun yOf(c: Float) = (top() + bottom()) / 2 - (bottom() - top()) / 2 * c / range
        var lastTap = 0L
        var lastTapIndex = -1
        awaitEachGesture {
            val down = awaitFirstDown()
            down.consume()
            val shift = currentEvent.keyboardModifiers.isShiftPressed
            val points = vm.practice.vibrato.points
            val hit = points.indices.minByOrNull { hypot(xOf(points[it].at) - down.position.x, yOf(points[it].cents) - down.position.y) }
                ?.takeIf { hypot(xOf(points[it].at) - down.position.x, yOf(points[it].cents) - down.position.y) < 18.dp.toPx() }
            // Double click (or tap) on a point removes it.
            if (hit != null && hit == lastTapIndex && down.uptimeMillis - lastTap < 350) {
                vm.removeLinePoint(hit)
                lastTapIndex = -1
                return@awaitEachGesture
            }
            val index = if (hit != null) {
                vm.pickLinePoint(hit)
                hit
            } else {
                vm.addLinePoint(toU(down.position.x), toCents(down.position.y), shift)
                vm.linePoint ?: return@awaitEachGesture
            }
            var moved = false
            val start = down.position
            while (true) {
                val event = awaitPointerEvent()
                val change = event.changes.firstOrNull { it.id == down.id } ?: break
                if (!change.pressed) {
                    // Held without moving: removed, the way to delete on a phone.
                    if (hit != null && !moved && change.uptimeMillis - down.uptimeMillis > 600) {
                        vm.removeLinePoint(hit)
                        lastTapIndex = -1
                    } else if (!moved) {
                        lastTap = down.uptimeMillis
                        lastTapIndex = index
                    } else {
                        lastTapIndex = -1
                    }
                    break
                }
                change.consume()
                if (!moved && hypot(change.position.x - start.x, change.position.y - start.y) < 4.dp.toPx()) continue
                moved = true
                vm.moveLinePoint(index, toU(change.position.x), toCents(change.position.y), event.keyboardModifiers.isShiftPressed || (hit == null && shift))
            }
        }
    }
    Canvas(Modifier.fillMaxWidth().height(if (editing) 260.dp else 190.dp).clip(RoundedCornerShape(14.dp)).background(back).then(input)) {
        val left = GRAPH_LEFT.toPx()
        val right = size.width - GRAPH_RIGHT.toPx()
        val top = GRAPH_PAD.toPx()
        val bottom = size.height - GRAPH_PAD.toPx()
        fun x(t: Float) = left + (right - left) * t / span
        fun y(c: Float) = (top + bottom) / 2 - (bottom - top) / 2 * c.coerceIn(-range, range) / range
        val style = TextStyle(color = labelColor, fontSize = 10.sp)
        // Microtone steps faintly, semitones with their note or frequency.
        val step = 1200f / division
        if (division > 12) {
            var c = ceil(-range / step) * step
            while (c <= range) {
                if (abs(c / 100f - (c / 100f).roundToInt()) > 0.01f) drawLine(fine, Offset(left, y(c)), Offset(right, y(c)), 1.dp.toPx())
                c += step
            }
        }
        val every = if (range > 400) 2 else 1
        for (k in floor(-range / 100f).toInt()..ceil(range / 100f).toInt()) {
            val c = k * 100f
            if (abs(c) > range) continue
            drawLine(
                if (k == 0) labelColor.copy(alpha = 0.5f) else grid,
                Offset(left, y(c)),
                Offset(right, y(c)),
                strokeWidth = if (k == 0) 1.5.dp.toPx() else 1.dp.toPx(),
                pathEffect = if (k == 0) null else PathEffect.dashPathEffect(floatArrayOf(6f, 6f)),
            )
            if (k % every != 0) continue
            val text = if (vm.practice.vibrato.axisHz) {
                "${PracticeEngine.frequency(target.pitch + k, vm.practice.tuning).roundToInt()} Hz"
            } else {
                noteName(target.pitch + k, vm.notation)
            }
            val layout = measurer.measure(text, style)
            drawText(layout, topLeft = Offset(left - layout.size.width - 6.dp.toPx(), y(c) - layout.size.height / 2))
        }
        if (showTarget) {
            if ((editing || live != null) && target.width > 0) curve(line, ::x, ::y, accent.copy(alpha = 0.5f), 2.dp.toPx(), dashed = true)
            curve(model, ::x, ::y, accent.copy(alpha = if (editing) 0.9f else 0.55f), 3.dp.toPx())
        }
        if (editing) {
            val points = target.points
            for ((i, p) in points.withIndex()) {
                val center = Offset(x(p.at * seconds), y(p.cents))
                drawCircle(if (i == picked) pickedColor else accent, if (i == picked) 7.dp.toPx() else 5.dp.toPx(), center)
                drawCircle(Color.White, 2.dp.toPx(), center)
            }
        }
        if (result != null && !editing) {
            val m = y(result.offset.toFloat())
            drawLine(attemptColor.copy(alpha = 0.4f), Offset(left, m), Offset(right, m), 1.dp.toPx(), pathEffect = PathEffect.dashPathEffect(floatArrayOf(3f, 5f)))
        }
        curve(attempt, ::x, ::y, attemptColor, 2.dp.toPx())
        sweep?.let { points ->
            // A wrap to the left starts a new stroke.
            val broken = points.zipWithNext().flatMap { (a, b) -> if (b.first < a.first) listOf(a, b.first to Float.NaN) else listOf(a) } + listOfNotNull(points.lastOrNull())
            curve(broken, ::x, ::y, attemptColor, 2.5.dp.toPx())
        }
        now?.let { drawLine(attemptColor.copy(alpha = 0.6f), Offset(x(it), top), Offset(x(it), bottom), 1.5.dp.toPx()) }
    }
}

/** Draws [points] as a line, broken where the value is NaN. */
internal fun DrawScope.curve(points: List<Pair<Float, Float>>, x: (Float) -> Float, y: (Float) -> Float, color: Color, width: Float, dashed: Boolean = false) {
    val path = Path()
    var open = false
    for ((t, c) in points) {
        if (c.isNaN()) {
            open = false
            continue
        }
        if (open) path.lineTo(x(t), y(c)) else path.moveTo(x(t), y(c))
        open = true
    }
    drawPath(path, color, style = Stroke(width, cap = StrokeCap.Round, join = StrokeJoin.Round, pathEffect = if (dashed) PathEffect.dashPathEffect(floatArrayOf(8f, 8f)) else null))
}

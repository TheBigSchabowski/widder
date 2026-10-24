package widder.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import widder.audio.PracticeEngine
import widder.music.Chord
import kotlin.math.abs
import kotlin.math.ceil
import kotlin.math.floor
import kotlin.math.pow
import kotlin.math.roundToInt

private val accent = Palette.practice

/** Where one sings or plays, over the last seconds, on a piano roll; the drone's notes stand out. */
@Composable
fun PitchMonitorPanel(vm: AppViewModel) {
    val m = vm.monitor
    val p = vm.practice
    val trace = m?.trace?.toList().orEmpty()
    val now = trace.lastOrNull()
    val current = trace.lastOrNull { !it.second.isNaN() }?.takeIf { now != null && now.first - it.first < 0.3f }?.second
    SectionTitle("Visualisieren")
    Panel {
        Row(verticalAlignment = Alignment.CenterVertically) {
            PlayButton(m != null, vm::toggleMonitor, attemptColor, 56.dp)
            Spacer(Modifier.width(14.dp))
            Column(Modifier.weight(1f)) {
                if (m == null) {
                    Text("Wo singe ich?", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.SemiBold)
                    Text(
                        "Zeigt laufend deine Tonhöhe – Stimme oder Instrument – auf einer Klavierrolle, mit dem Grundton der Drone",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                } else if (current == null) {
                    Text("Hört zu …", style = MaterialTheme.typography.titleLarge, color = MaterialTheme.colorScheme.onSurfaceVariant)
                } else {
                    val semis = current.roundToInt()
                    val cents = ((current - semis) * 100).roundToInt()
                    Row(verticalAlignment = Alignment.Bottom) {
                        Text(noteName(semis, vm.notation), fontSize = 32.sp, fontWeight = FontWeight.Bold)
                        Text(
                            (if (cents >= 0) " +" else " −") + "${abs(cents)} ct",
                            Modifier.padding(bottom = 5.dp, start = 4.dp),
                            color = if (abs(cents) <= 10) Palette.good else Palette.hard,
                            fontWeight = FontWeight.SemiBold,
                        )
                        Spacer(Modifier.weight(1f))
                        Text(
                            "${hz(p.tuning * 2.0.pow((current - 69) / 12.0))} Hz",
                            Modifier.padding(bottom = 6.dp),
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
            }
        }
        Spacer(Modifier.height(12.dp))
        PianoRoll(vm, trace, p.monitorSeconds.toFloat())
        Row(Modifier.padding(top = 10.dp), verticalAlignment = Alignment.CenterVertically) {
            Text("Zeitfenster", Modifier.weight(1f), style = MaterialTheme.typography.bodyMedium)
            Choices(listOf(5, 10, 20, 60), p.monitorSeconds, { "$it s" }, accent) { s -> vm.updatePractice { it.copy(monitorSeconds = s) } }
        }
    }
}

@Composable
private fun PianoRoll(vm: AppViewModel, trace: List<Pair<Float, Float>>, seconds: Float) {
    val p = vm.practice
    val end = trace.lastOrNull()?.first ?: 0f
    val visible = trace.filter { it.first >= end - seconds }
    val heard = visible.mapNotNull { it.second.takeIf { v -> !v.isNaN() } }
    // At least an octave, around what was sung; the drone's root when nothing was yet.
    val center = heard.sorted().getOrNull(heard.size / 2) ?: (12 * (p.octave + 2) + p.root).toFloat()
    var lo = floor(minOf(heard.minOrNull() ?: center, center - 6f)) - 1
    var hi = ceil(maxOf(heard.maxOrNull() ?: center, center + 6f)) + 1
    if (hi - lo > 36) {
        lo = floor(center) - 18
        hi = lo + 36
    }
    val measurer = rememberTextMeasurer()
    val labelColor = MaterialTheme.colorScheme.onSurfaceVariant
    val line = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.1f)
    val dark = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.04f)
    val fine = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.05f)
    val droneClasses = PracticeEngine.dronePitches(p).map { it % 12 }.toSet()
    Canvas(Modifier.fillMaxWidth().height(280.dp).clip(RoundedCornerShape(14.dp)).background(MaterialTheme.colorScheme.onSurface.copy(alpha = 0.04f))) {
        val left = 52.dp.toPx()
        val right = size.width - 8.dp.toPx()
        val top = 8.dp.toPx()
        val bottom = size.height - 8.dp.toPx()
        fun x(t: Float) = right - (end - t) / seconds * (right - left)
        fun y(pitch: Float) = bottom - (pitch - lo) / (hi - lo) * (bottom - top)
        val row = (bottom - top) / (hi - lo)
        val style = TextStyle(color = labelColor, fontSize = 10.sp)
        val every = if (hi - lo > 18) 2 else 1
        for (k in lo.toInt()..hi.toInt()) {
            val pc = k.mod(12)
            // Black keys a shade darker, like a piano roll.
            if (pc in setOf(1, 3, 6, 8, 10)) drawRect(dark, Offset(left, y(k + 0.5f)), Size(right - left, row))
            val drone = pc in droneClasses
            drawLine(if (drone) accent.copy(alpha = if (pc == p.root) 0.7f else 0.4f) else line, Offset(left, y(k.toFloat())), Offset(right, y(k.toFloat())), if (drone) 2.dp.toPx() else 1.dp.toPx())
            if (drone || (k - lo.toInt()) % every == 0) {
                val layout = measurer.measure(noteName(k, vm.notation), if (drone) style.copy(color = accent, fontWeight = FontWeight.Bold) else style)
                drawText(layout, topLeft = Offset(left - layout.size.width - 6.dp.toPx(), y(k.toFloat()) - layout.size.height / 2))
            }
        }
        // Microtone steps between the semitones.
        if (p.division > 12) {
            val step = 12f / p.division
            var v = ceil(lo / step) * step
            while (v <= hi) {
                if (abs(v - v.roundToInt()) > 0.01f) drawLine(fine, Offset(left, y(v)), Offset(right, y(v)), 1.dp.toPx())
                v += step
            }
        }
        val path = androidx.compose.ui.graphics.Path()
        var open = false
        var last: Pair<Float, Float>? = null
        for ((t, v) in visible) {
            // A gap, or a leap of more than a fifth: a new stroke.
            if (v.isNaN() || v < lo || v > hi || (last != null && abs(v - last.second) > 7f)) {
                open = false
                last = if (v.isNaN()) null else t to v
                continue
            }
            if (open) path.lineTo(x(t), y(v)) else path.moveTo(x(t), y(v))
            open = true
            last = t to v
        }
        drawPath(path, attemptColor, style = androidx.compose.ui.graphics.drawscope.Stroke(2.5.dp.toPx(), cap = androidx.compose.ui.graphics.StrokeCap.Round, join = androidx.compose.ui.graphics.StrokeJoin.Round))
        visible.lastOrNull { !it.second.isNaN() }?.takeIf { end - it.first < 0.3f && it.second in lo..hi }?.let { (t, v) ->
            drawCircle(attemptColor, 5.dp.toPx(), Offset(x(t), y(v)))
        }
    }
    if (trace.isEmpty()) {
        Text(
            "Grundton der Drone: ${Chord.noteName(p.root, vm.notation)} (lila Linien) – auch wenn die Drone aus ist.",
            Modifier.padding(top = 6.dp),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

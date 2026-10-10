package widder.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import widder.core.Clip
import widder.music.Chord
import widder.music.Interval
import widder.music.MidiAnalysis
import widder.music.Notation
import widder.music.Spelling

private class Loaded(val grips: List<MidiAnalysis.Grip>?)

/**
 * A fold-out keyboard under a MIDI clip: which keys the file presses, chord by chord, to check
 * the chords against. Nothing is asked here; it stays closed until it is opened. A tap on a
 * keyboard strikes that grip.
 */
@Composable
fun MidiKeyboard(vm: AppViewModel, clip: Clip, key: String) {
    Column(Modifier.fillMaxWidth(), horizontalAlignment = Alignment.CenterHorizontally) {
        SmallButton(
            if (vm.keyboardOpen) "Klaviatur ausblenden" else "Auf der Klaviatur zeigen",
            { vm.keyboardOpen = !vm.keyboardOpen },
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        if (!vm.keyboardOpen) return@Column
        val loaded by produceState<Loaded?>(null, clip.file, clip.edit) { value = Loaded(vm.grips(clip)) }
        val grips = loaded?.grips
        when {
            loaded == null -> return@Column
            grips == null -> Hint("Die MIDI-Datei lässt sich nicht lesen")
            grips.isEmpty() -> Hint("Keine Töne in der MIDI-Datei")
            else -> {
                // One range for all, so the grips line up under each other.
                val lowest = grips.minOf { it.pitches.first() }
                val highest = grips.maxOf { it.pitches.last() }
                val low = lowest - lowest % 12
                val high = maxOf(highest + 11 - highest % 12, low + 23)
                val whites = (low..high).count { isWhite(it) }
                grips.forEachIndexed { i, grip ->
                    Grip(
                        grip,
                        number = if (grips.size > 1) i + 1 else null,
                        notation = vm.notation,
                        low = low,
                        high = high,
                        modifier = Modifier.widthIn(max = (whites * 26).dp).fillMaxWidth().padding(top = 10.dp),
                        onClick = { vm.playKeys(grip.pitches, "$key-$i") },
                    )
                }
            }
        }
    }
}

@Composable
private fun Hint(text: String) {
    Text(text, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
}

/** "2  Am   A2 · A3 · C4 · E4" over its keyboard. */
@Composable
private fun Grip(grip: MidiAnalysis.Grip, number: Int?, notation: Notation, low: Int, high: Int, modifier: Modifier, onClick: () -> Unit) {
    val name = grip.chord?.name(notation)
        ?: grip.pitches.takeIf { it.size == 2 }?.let { (a, b) -> (b - a).takeIf { it in 1..24 }?.let(Interval::name) }
    Column(modifier) {
        Row(Modifier.padding(bottom = 4.dp), horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
            if (number != null) Text("$number", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            if (name != null) Text(name, fontWeight = FontWeight.SemiBold, color = Palette.chords)
            val spelled = notation.copy(spelling = spellingFor(grip.chord))
            Text(
                grip.pitches.joinToString(" · ") { noteName(it, spelled) },
                Modifier.weight(1f),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        Piano(grip.pitches.toSet(), low, high, Modifier.fillMaxWidth().height(64.dp).clickable(onClickLabel = "Anhören", onClick = onClick))
    }
}

/**
 * Sharps or flats as the chord's key writes them: C# in A major, Bb in D minor. A root that
 * itself has an accidental decides alone, so the keys match the chord's name.
 */
private fun spellingFor(chord: Chord?): Spelling {
    chord ?: return Spelling.MIXED
    val root = Chord.noteName(chord.root)
    if (root.endsWith("#")) return Spelling.SHARPS
    if (root.endsWith("b")) return Spelling.FLATS
    val minor = 3 in chord.quality.intervals && 4 !in chord.quality.intervals
    val tonic = (chord.root + if (minor) 3 else 0) % 12
    return if (tonic in setOf(5, 10, 3, 8, 1)) Spelling.FLATS else Spelling.SHARPS
}

/** "C#4": middle C is C4. */
fun noteName(pitch: Int, notation: Notation): String = Chord.noteName(pitch % 12, notation) + (pitch / 12 - 1)

private fun isWhite(pitch: Int) = pitch % 12 !in setOf(1, 3, 6, 8, 10)

/** Keys [low]..[high] (whole octaves from C), [pressed] ones coloured; each C is named. */
@Composable
fun Piano(pressed: Set<Int>, low: Int, high: Int, modifier: Modifier = Modifier, color: Color = Palette.chords) {
    val measurer = rememberTextMeasurer()
    val outline = Color(0xFF9A9A9A)
    val pressedBlack = lerp(color, Color.Black, 0.25f)
    Canvas(modifier) {
        val whites = (low..high).filter { isWhite(it) }
        val w = size.width / whites.size
        val h = size.height
        val line = 1.dp.toPx()
        whites.forEachIndexed { i, p ->
            val topLeft = Offset(i * w, 0f)
            drawRect(if (p in pressed) color else Color.White, topLeft, Size(w, h))
            drawRect(outline, topLeft, Size(w, h), style = Stroke(line))
            if (p % 12 == 0) {
                val label = measurer.measure("C${p / 12 - 1}", TextStyle(fontSize = 9.sp, textAlign = TextAlign.Center))
                if (label.size.width < w - 2) {
                    drawText(
                        label,
                        if (p in pressed) Color.White else outline,
                        Offset(i * w + (w - label.size.width) / 2, h - label.size.height - 3.dp.toPx()),
                    )
                }
            }
        }
        val bw = w * 0.6f
        val bh = h * 0.62f
        for (p in low..high) {
            if (isWhite(p)) continue
            val left = whites.indexOf(p - 1).takeIf { it >= 0 } ?: continue
            drawRoundRect(
                if (p in pressed) pressedBlack else Color(0xFF26262A),
                Offset((left + 1) * w - bw / 2, 0f),
                Size(bw, bh),
                CornerRadius(2.dp.toPx()),
            )
        }
    }
}

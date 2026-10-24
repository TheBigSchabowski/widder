package widder.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import widder.music.Chord
import widder.music.Tuner
import widder.music.TunerReading
import kotlin.math.abs
import kotlin.math.roundToInt

private val accent = Palette.practice

/** The divisions offered: semitones, quarter, third and eighth tones, and a few other equal temperaments. */
private val DIVISIONS = listOf(12, 24, 36, 48, 19, 22, 31, 53)

/** A tuner like Choona: chromatic in any division of the octave, or to the strings of a tuning. */
@Composable
fun TunerPanel(vm: AppViewModel) {
    val p = vm.practice
    val t = p.tuner
    val tuning = vm.tuning
    val reading = vm.tunerHz?.let { f -> if (tuning != null) Tuner.string(f, p.tuning, tuning, vm.tunerString) else Tuner.chromatic(f, p.tuning, p.division) }
    SectionTitle("Stimmgerät")
    Panel {
        Row(verticalAlignment = Alignment.CenterVertically) {
            PlayButton(vm.tunerOn, vm::toggleTuner, accent, 56.dp)
            Spacer(Modifier.width(14.dp))
            Column(Modifier.weight(1f)) {
                if (!vm.tunerOn) {
                    Text("Stimmen", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.SemiBold)
                    Text(
                        (tuning?.name ?: "Chromatisch · ${divisionName(p.division)}") + " · a′ = ${hz(p.tuning)} Hz",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                } else if (reading == null) {
                    Text("Spiel einen Ton …", style = MaterialTheme.typography.titleLarge, color = MaterialTheme.colorScheme.onSurfaceVariant)
                } else {
                    NoteReading(vm, reading)
                }
            }
        }
        if (vm.tunerOn) {
            Needle(reading, t.semitones)
        }
        tuning?.let { tn ->
            Text(
                if (vm.tunerString == null) "Saite wird erkannt – antippen spielt sie vor und hält sie fest" else "Saite festgehalten – nochmal antippen: wieder erkennen",
                Modifier.padding(top = 12.dp, bottom = 6.dp),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                for ((i, s) in tn.strings.withIndex()) {
                    val sounding = reading?.string == i
                    val ok = sounding && reading?.let(Tuner::inTune) == true
                    Chip(
                        Tuner.format(s, vm.notation),
                        if (ok) Palette.good else accent,
                        filled = vm.tunerString == i || sounding,
                        modifier = Modifier.clickable { vm.pickString(i) },
                        large = true,
                    )
                }
            }
        }

        Text("Stimmung", Modifier.padding(top = 14.dp, bottom = 6.dp), style = MaterialTheme.typography.bodyMedium)
        TuningMenu(vm)
        if (tuning == null) {
            Text("Teilung der Oktave", Modifier.padding(top = 12.dp, bottom = 6.dp), style = MaterialTheme.typography.bodyMedium)
            Choices(DIVISIONS, p.division, { if (it in listOf(12, 24, 36, 48)) divisionName(it) else "$it-EDO" }, accent) { d ->
                vm.updatePractice { it.copy(division = d, droneCents = 0) }
            }
            Text(
                "Gilt auch fürs Raster der Vibrato-Linie und die Feinstimmung der Drone.",
                Modifier.padding(top = 6.dp),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        Row(Modifier.padding(top = 12.dp), verticalAlignment = Alignment.CenterVertically) {
            Text("Abweichung in", Modifier.weight(1f), style = MaterialTheme.typography.bodyMedium)
            Choices(listOf(false, true), t.semitones, { if (it) "Halbtönen" else "Cent" }, accent) { st -> vm.updateTuner { it.copy(semitones = st) } }
        }
        ConcertPitchLine(vm)
        CustomTuning(vm)
    }
}

/** The note (or string) aimed at, big, with a microtone's cents, and the frequency heard. */
@Composable
private fun NoteReading(vm: AppViewModel, r: TunerReading) {
    Row(verticalAlignment = Alignment.Bottom) {
        Text(Chord.noteName(r.pitch % 12, vm.notation), fontSize = 40.sp, fontWeight = FontWeight.Bold, color = if (Tuner.inTune(r)) Palette.good else MaterialTheme.colorScheme.onSurface)
        Text("${r.pitch / 12 - 1}", Modifier.padding(bottom = 6.dp, start = 2.dp), style = MaterialTheme.typography.titleMedium)
        if (r.stepCents != 0) {
            Text(
                (if (r.stepCents > 0) " +" else " −") + "${abs(r.stepCents)} ct",
                Modifier.padding(bottom = 7.dp, start = 6.dp),
                style = MaterialTheme.typography.titleMedium,
                color = accent,
                fontWeight = FontWeight.SemiBold,
            )
        }
        Spacer(Modifier.weight(1f))
        Text("${vm.tunerHz?.let { ((it * 10).roundToInt() / 10.0).toString().replace('.', ',') }} Hz", Modifier.padding(bottom = 8.dp), color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

/** How far off: a bar from flat to sharp over half a step either way, green in tune. */
@Composable
private fun Needle(r: TunerReading?, semitones: Boolean) {
    val track = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.1f)
    val mid = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.5f)
    val ok = r?.let(Tuner::inTune) == true
    val color = if (ok) Palette.good else Palette.hard
    Column(Modifier.padding(top = 12.dp)) {
        Canvas(Modifier.fillMaxWidth().height(28.dp)) {
            val h = size.height
            drawRoundRect(track, Offset(0f, h * 0.3f), Size(size.width, h * 0.4f), CornerRadius(h * 0.2f))
            drawLine(mid, Offset(size.width / 2, 0f), Offset(size.width / 2, h), 2.dp.toPx())
            if (r != null) {
                val x = size.width / 2 + (r.deviation / r.halfStep).coerceIn(-1.0, 1.0).toFloat() * size.width / 2
                drawRoundRect(color, Offset(x - 5.dp.toPx(), 0f), Size(10.dp.toPx(), h), CornerRadius(5.dp.toPx()))
            }
        }
        Row(Modifier.fillMaxWidth()) {
            Text("tiefer", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Spacer(Modifier.weight(1f))
            Text(
                when {
                    r == null -> "–"
                    ok -> "gestimmt ✓"
                    semitones -> (if (r.deviation > 0) "+" else "−") + ((abs(r.deviation) / 100 * 100).roundToInt() / 100.0).toString().replace('.', ',') + " HT"
                    else -> (if (r.deviation > 0) "+" else "−") + "${abs(r.deviation).roundToInt()} ct · " + if (r.deviation > 0) "runter" else "hoch"
                },
                fontWeight = FontWeight.SemiBold,
                color = if (r == null) MaterialTheme.colorScheme.onSurfaceVariant else color,
            )
            Spacer(Modifier.weight(1f))
            Text("höher", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

/** Chromatic or one of the tunings, from a menu; the user's own can be removed. */
@Composable
private fun TuningMenu(vm: AppViewModel) {
    var open by remember { mutableStateOf(false) }
    val current = vm.practice.tuner.tuning
    Row(verticalAlignment = Alignment.CenterVertically) {
        Box {
            Chip((current ?: "Chromatisch") + "  ▾", accent, filled = true, modifier = Modifier.clickable { open = true }, large = true)
            DropdownMenu(open, { open = false }) {
                DropdownMenuItem({ Text("Chromatisch") }, { vm.chooseTuning(null); open = false })
                for (t in vm.tunings) {
                    DropdownMenuItem(
                        { Text(t.name + "  ·  " + t.strings.joinToString(" ") { Tuner.format(it, vm.notation) }) },
                        { vm.chooseTuning(t.name); open = false },
                    )
                }
            }
        }
        Spacer(Modifier.weight(1f))
        if (current != null && vm.practice.tuner.custom.any { it.name == current }) {
            SmallButton("Löschen", { vm.removeTuning(current) }, color = Palette.again)
        }
    }
}

/** A tuning of one's own: a name and the notes, microtones with cents ("A2+50"). */
@Composable
private fun CustomTuning(vm: AppViewModel) {
    var name by remember { mutableStateOf("") }
    var notes by remember { mutableStateOf("") }
    val valid = Tuner.parseStrings(notes) != null
    Text("Eigene Stimmung", Modifier.padding(top = 14.dp, bottom = 6.dp), style = MaterialTheme.typography.bodyMedium)
    Row(verticalAlignment = Alignment.CenterVertically) {
        OutlinedTextField(name, { name = it }, Modifier.weight(1f), singleLine = true, placeholder = { Text("Name") })
        Spacer(Modifier.width(8.dp))
        OutlinedTextField(notes, { notes = it }, Modifier.weight(2f), singleLine = true, placeholder = { Text("D2 A2 D3 G3 A3 D4") }, isError = notes.isNotBlank() && !valid)
    }
    Row(verticalAlignment = Alignment.CenterVertically) {
        Text(
            "Noten von tief nach hoch; Mikrotöne mit Cent, z. B. A2+50 oder E4-25.",
            Modifier.weight(1f),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        SmallButton("Speichern", {
            if (vm.addTuning(name, notes)) {
                name = ""
                notes = ""
            }
        }, enabled = valid, color = accent)
    }
}

/** The concert pitch, shared with the drone, as a line with a way to change it there. */
@Composable
private fun ConcertPitchLine(vm: AppViewModel) {
    var editing by remember { mutableStateOf(false) }
    Row(Modifier.padding(top = 8.dp), verticalAlignment = Alignment.CenterVertically) {
        Text("Stimmton a′ = ${hz(vm.practice.tuning)} Hz", Modifier.weight(1f), style = MaterialTheme.typography.bodyMedium)
        SmallButton(if (editing) "Fertig" else "Ändern", { editing = !editing }, color = accent)
    }
    if (editing) {
        Box(Modifier.fillMaxWidth().clip(RoundedCornerShape(12.dp)).background(accent.copy(alpha = 0.06f)).padding(horizontal = 12.dp, vertical = 4.dp)) {
            Column { ConcertPitch(vm) }
        }
    }
}

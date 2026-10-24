package widder.ui

import androidx.compose.foundation.background
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.runtime.mutableStateOf
import androidx.compose.material3.OutlinedTextField
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import widder.audio.Grooves
import widder.audio.PracticeEngine
import widder.core.DroneSound
import widder.core.DrumKit
import widder.core.Meter
import widder.core.PracticeSettings
import widder.music.Chord
import kotlin.math.roundToInt

private val accent = Palette.practice

@Composable
fun PracticeScreen(vm: AppViewModel) {
    Column(Modifier.fillMaxSize()) {
        TopBar("Üben", vm::back, subtitle = "Stimmgerät, Visualisieren, Drone, Drums, Looper, Vibrato")
        Centered {
            Column(Modifier.verticalScroll(rememberScrollState()).padding(horizontal = 16.dp, vertical = 8.dp)) {
                AudioDevicesLine(vm)
                TimerPanel(vm)
                TunerPanel(vm)
                PitchMonitorPanel(vm)
                DronePanel(vm)
                DrumPanel(vm)
                LooperPanel(vm)
                VibratoPanel(vm)
                if (vm.platform.keyboard) {
                    Text(
                        "Leertaste: Drums an/aus · D: Drone an/aus · T: Tempo tippen · ↑ ↓: Tempo · V / G: Vibrato / Ton vorspielen · R: nachmachen · Z: Zufall · L: Looper · O: Overdub",
                        Modifier.padding(top = 14.dp, start = 4.dp),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                Spacer(Modifier.height(40.dp))
            }
        }
    }
}

@Composable
private fun DronePanel(vm: AppViewModel) {
    val p = vm.practice
    val tanpura = p.sound == DroneSound.TANPURA
    fun name(pitch: Int) = Chord.noteName(pitch, vm.notation) + (pitch / 12 - 1)
    SectionTitle("Drone")
    Panel {
        Row(verticalAlignment = Alignment.CenterVertically) {
            PlayButton(vm.droneOn, vm::toggleDrone, accent, 56.dp)
            Spacer(Modifier.width(14.dp))
            Column(Modifier.weight(1f)) {
                Text("Drone auf ${Chord.noteName(p.root, vm.notation)}", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.SemiBold)
                Text(
                    (if (tanpura) PracticeEngine.tanpuraStrings(p) else PracticeEngine.dronePitches(p)).joinToString(" · ") { name(it) } + " · ${p.sound.label}",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
        Label("Grundton")
        Choices((0 until 12).toList(), p.root, { Chord.noteName(it, vm.notation) }, accent) { r -> vm.updatePractice { it.copy(root = r) } }
        Label("Oktave")
        Choices((1..4).toList(), p.octave, { name(12 * (it + 1) + p.root) }, accent) { o -> vm.updatePractice { it.copy(octave = o) } }
        Label("Klang")
        Choices(DroneSound.entries, p.sound, { it.label }, accent) { s -> vm.updatePractice { it.copy(sound = s) } }
        Spacer(Modifier.height(6.dp))
        SwitchRow("Quinte dazu", if (tanpura) "Tanpura: die erste Saite spielt die Quinte (Pa) statt der Oktave" else null, p.fifth, accent) { on ->
            vm.updatePractice { it.copy(fifth = on) }
        }
        if (!tanpura) SwitchRow("Oktave dazu", null, p.octaveUp, accent) { on -> vm.updatePractice { it.copy(octaveUp = on) } }
        LabeledSlider("Lautstärke", "${(p.droneVolume * 100).roundToInt()} %", p.droneVolume, 0f..1f, accent) { v ->
            vm.updatePractice { it.copy(droneVolume = (v * 100).roundToInt() / 100f) }
        }
        if (p.division != 12) {
            Label("Feinstimmung · ${divisionName(p.division)}")
            Choices(microSteps(p.division), p.droneCents, ::centsLabel, accent) { c -> vm.updatePractice { it.copy(droneCents = c) } }
        }
        ConcertPitch(vm)
    }
}

@Composable
private fun DrumPanel(vm: AppViewModel) {
    val p = vm.practice
    SectionTitle("Drums")
    Panel {
        Row(verticalAlignment = Alignment.CenterVertically) {
            PlayButton(vm.drumsOn, vm::toggleDrums, accent, 56.dp)
            Spacer(Modifier.width(14.dp))
            Column(Modifier.weight(1f)) {
                Row(verticalAlignment = Alignment.Bottom) {
                    Text("${p.bpm}", style = MaterialTheme.typography.headlineMedium, fontWeight = FontWeight.Bold)
                    Text(" BPM", Modifier.padding(bottom = 4.dp), color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                BeatDots(vm, p.meter.beats)
            }
        }
        Spacer(Modifier.height(8.dp))
        LabeledSlider(
            "Tempo",
            if (p.meter == Meter.SIX_EIGHT) "Achtel pro Minute" else "Viertel pro Minute",
            p.bpm.toFloat(),
            PracticeSettings.MIN_BPM.toFloat()..PracticeSettings.MAX_BPM.toFloat(),
            accent,
        ) { vm.setBpm(it.roundToInt()) }
        Row(verticalAlignment = Alignment.CenterVertically) {
            SmallButton("− 1", { vm.setBpm(p.bpm - 1) }, color = accent)
            SmallButton("+ 1", { vm.setBpm(p.bpm + 1) }, color = accent)
            Spacer(Modifier.weight(1f))
            SmallButton("Tempo tippen", { vm.tapTempo() }, color = accent)
        }
        Label("Taktart")
        Choices(Meter.entries, p.meter, { it.label }, accent) { m -> vm.updatePractice { it.copy(meter = m, groove = Grooves.of(m).first().name) } }
        Label("Groove")
        Choices(Grooves.of(p.meter).map { it.name }, Grooves.find(p.groove, p.meter).name, { it }, accent) { g -> vm.updatePractice { it.copy(groove = g) } }
        Label("Drumset")
        Choices(DrumKit.entries, p.kit, { it.label }, accent) { k -> vm.updatePractice { it.copy(kit = k) } }
        Spacer(Modifier.height(6.dp))
        if (p.kit != DrumKit.CLICK) {
            SwitchRow("Metronom-Klick dazu", "Hoher Klick auf der Eins", p.click, accent) { on -> vm.updatePractice { it.copy(click = on) } }
        }
        LabeledSlider("Lautstärke", "${(p.drumVolume * 100).roundToInt()} %", p.drumVolume, 0f..1f, accent) { v ->
            vm.updatePractice { it.copy(drumVolume = (v * 100).roundToInt() / 100f) }
        }
    }
}

/** One dot per beat; the one being played lights up. */
@Composable
private fun BeatDots(vm: AppViewModel, beats: Int) {
    var beat by remember { mutableIntStateOf(-1) }
    LaunchedEffect(vm.drumsOn) {
        if (!vm.drumsOn) {
            beat = -1
            return@LaunchedEffect
        }
        while (true) withFrameNanos { beat = vm.practiceEngine.beat }
    }
    Row(Modifier.padding(top = 4.dp), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        for (i in 0 until beats) {
            val on = i == beat
            Box(
                Modifier.size(if (i == 0) 12.dp else 10.dp).clip(CircleShape)
                    .background(if (on) accent else MaterialTheme.colorScheme.onSurface.copy(alpha = 0.15f)),
            )
        }
    }
}

@Composable
private fun Label(text: String) {
    Text(text, Modifier.padding(top = 12.dp, bottom = 6.dp), style = MaterialTheme.typography.bodyMedium)
}

/** The concert pitch a′: chips of the user's own standards, a field for any value, and saving or dropping one. */
@Composable
fun ConcertPitch(vm: AppViewModel) {
    val p = vm.practice
    var text by remember(p.tuning) { mutableStateOf(hz(p.tuning)) }
    Label("Stimmton a′")
    Choices(p.tuningPresets.sorted(), p.tuning, { "${hz(it)} Hz" }, accent) { t -> vm.updatePractice { it.copy(tuning = t) } }
    Row(Modifier.padding(top = 8.dp), verticalAlignment = Alignment.CenterVertically) {
        OutlinedTextField(
            text,
            { value ->
                text = value
                value.replace(',', '.').toDoubleOrNull()?.takeIf { it in 300.0..500.0 }?.let { t -> vm.updatePractice { it.copy(tuning = t) } }
            },
            Modifier.width(140.dp),
            singleLine = true,
            suffix = { Text("Hz") },
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
            isError = text.replace(',', '.').toDoubleOrNull()?.let { it !in 300.0..500.0 } ?: true,
        )
        Spacer(Modifier.weight(1f))
        if (p.tuning in p.tuningPresets) {
            if (p.tuningPresets.size > 1) {
                SmallButton("Aus Standards entfernen", { vm.updatePractice { it.copy(tuningPresets = it.tuningPresets - it.tuning) } }, color = accent)
            }
        } else {
            SmallButton("Als Standard speichern", { vm.updatePractice { it.copy(tuningPresets = it.tuningPresets + it.tuning) } }, color = accent)
        }
    }
}

/** 440.0 as "440", 438.5 as "438,5". */
internal fun hz(value: Double): String {
    val tenths = kotlin.math.round(value * 10).toLong()
    return if (tenths % 10 == 0L) "${tenths / 10}" else "${tenths / 10},${kotlin.math.abs(tenths % 10)}"
}

/** The steps of [division] within a semitone either way, in cents. */
internal fun microSteps(division: Int): List<Int> {
    val step = 1200.0 / division
    val n = (100 / step - 1e-6).toInt()
    return (-n..n).map { kotlin.math.round(it * step).toInt() }
}

internal fun centsLabel(c: Int): String = when {
    c == 0 -> "auf dem Ton"
    c > 0 -> "+$c ct"
    else -> "−${-c} ct"
}

internal fun divisionName(division: Int): String = when (division) {
    12 -> "Halbtöne"
    24 -> "Vierteltöne"
    36 -> "Dritteltöne"
    48 -> "Achteltöne"
    else -> "$division-EDO"
}

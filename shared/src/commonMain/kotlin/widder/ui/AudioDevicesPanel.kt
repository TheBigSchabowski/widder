package widder.ui

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameMillis
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import widder.audio.PitchTracker
import kotlin.math.log2
import kotlin.math.roundToInt

/** Which microphone or audio interface records, which output plays, and a meter to try it. */
@Composable
fun AudioDevicesPanel(vm: AppViewModel) {
    val s = vm.settings
    // Asked anew on every visit: an interface may just have been plugged in.
    var refresh by remember { mutableStateOf(0) }
    val inputs = remember(refresh) { vm.audioInputs() }
    val outputs = remember(refresh) { vm.audioOutputs() }
    SectionTitle("Audio-Geräte")
    Panel {
        Text("Eingang", Modifier.padding(bottom = 6.dp), style = MaterialTheme.typography.bodyMedium)
        Choices(listOf<String?>(null) + (inputs + listOfNotNull(s.audioInput)).distinct(), s.audioInput, { it ?: "Standard" }) { name ->
            vm.setAudioDevices(name, s.audioOutput, s.inputChannel)
        }
        Text("Kanal am Eingang", Modifier.padding(top = 12.dp, bottom = 6.dp), style = MaterialTheme.typography.bodyMedium)
        Choices(listOf(0, 1, 2, 3, 4), s.inputChannel, { if (it == 0) "Alle" else "Eingang $it" }) { ch ->
            vm.setAudioDevices(s.audioInput, s.audioOutput, ch)
        }
        Text("Ausgang", Modifier.padding(top = 12.dp, bottom = 6.dp), style = MaterialTheme.typography.bodyMedium)
        Choices(listOf<String?>(null) + (outputs + listOfNotNull(s.audioOutput)).distinct(), s.audioOutput, { it ?: "Standard" }) { name ->
            vm.setAudioDevices(s.audioInput, name, s.inputChannel)
        }
        Spacer(Modifier.height(10.dp))
        Row(verticalAlignment = Alignment.CenterVertically) {
            SmallButton(if (vm.inputTest) "Test beenden" else "Eingang testen", vm::toggleInputTest)
            SmallButton("Geräte neu suchen", { refresh++ })
        }
        if (vm.inputTest) InputMeter(vm)
        Text(
            "„Standard“ folgt der Systemeinstellung. Bei einem Interface mit Gitarre an Eingang 1 hier „Eingang 1“ wählen – " +
                "am Handy per USB-C angeschlossen, dann „Geräte neu suchen“. " +
                "Bleibt der Pegel am Mac bei null: Systemeinstellungen › Datenschutz & Sicherheit › Mikrofon › Widder erlauben.",
            Modifier.padding(top = 8.dp),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

/** Level bar and the pitch heard, while the input test runs. */
@Composable
private fun InputMeter(vm: AppViewModel) {
    var level by remember { mutableStateOf(0f) }
    var heard by remember { mutableStateOf<Float?>(null) }
    LaunchedEffect(Unit) {
        val buffer = FloatArray(PitchTracker.frameSize(vm.audio.recorder.sampleRate))
        while (true) {
            withFrameMillis { }
            level = vm.audio.recorder.level()
            val n = vm.audio.recorder.latest(buffer)
            heard = if (n == buffer.size) PitchTracker.detect(buffer, n, vm.audio.recorder.sampleRate).takeIf { !it.isNaN() } else null
        }
    }
    Column(Modifier.padding(top = 8.dp)) {
        ProgressBar(level.coerceIn(0f, 1f), if (level > 0.9f) Palette.again else Palette.good, Modifier.fillMaxWidth(), height = 10.dp)
        Row(Modifier.padding(top = 6.dp)) {
            Text("Pegel ${(level * 100).roundToInt()} %", Modifier.weight(1f), style = MaterialTheme.typography.bodySmall)
            heard?.let { f ->
                val midi = (69 + 12 * log2(f / 440.0)).roundToInt()
                Text("${noteName(midi, vm.notation)} · ${f.roundToInt()} Hz", fontWeight = FontWeight.SemiBold)
            }
        }
    }
}

/** One line in the practice screen: which devices are in use, with a way to the settings. */
@Composable
fun AudioDevicesLine(vm: AppViewModel) {
    if (vm.audio.devices == null) return
    val s = vm.settings
    Row(Modifier.fillMaxWidth().padding(top = 4.dp), verticalAlignment = Alignment.CenterVertically) {
        Text(
            "Eingang: ${s.audioInput ?: "Standard"}" + (if (s.inputChannel > 0) " (${s.inputChannel})" else "") + " · Ausgang: ${s.audioOutput ?: "Standard"}",
            Modifier.weight(1f),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Spacer(Modifier.width(8.dp))
        SmallButton("Ändern", { vm.open(Screen.Settings) }, color = Palette.practice)
    }
}

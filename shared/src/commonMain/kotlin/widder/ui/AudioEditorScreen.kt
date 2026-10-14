package widder.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
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
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import widder.audio.Effects
import widder.core.AudioEdit
import kotlin.math.abs
import kotlin.math.roundToInt

private val accent = Palette.cards

@Composable
fun AudioEditorScreen(vm: AppViewModel) {
    val state = vm.audioEditor ?: return
    val edit = state.edit
    Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
        Column(Modifier.fillMaxSize()) {
            TopBar("Schneiden & Effekte", vm::closeAudioEditor, subtitle = state.clip.name) {
                SmallButton("Speichern", vm::saveAudioEditor, color = accent)
            }
            Centered(maxWidth = 900.dp) {
                Column(Modifier.verticalScroll(rememberScrollState()).padding(16.dp)) {
                    Waveform(vm, state)
                    Spacer(Modifier.height(8.dp))
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(
                            "Start ${formatSeconds(edit.trimStart)}   Ende ${formatSeconds(state.selectionEnd)}   Länge ${formatSeconds(state.selectionEnd - edit.trimStart)}",
                            Modifier.weight(1f),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                        if (edit.trimStart > 0 || edit.trimEnd != null) {
                            SmallButton("Schnitt aufheben", { vm.updateEdit(edit.copy(trimStart = 0.0, trimEnd = null)) }, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                    }
                    Spacer(Modifier.height(10.dp))
                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                        PlayButton(state.playing && !state.playsOriginal, { vm.previewAudio() }, accent, 52.dp)
                        Column {
                            Text("Anhören", fontWeight = FontWeight.SemiBold)
                            Text("mit allen Effekten", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                        Spacer(Modifier.width(12.dp))
                        OutlinedButton({ vm.previewAudio(original = true) }) {
                            Text(if (state.playing && state.playsOriginal) "Stopp" else "Ohne Effekte")
                        }
                    }

                    SectionTitle("Lautstärke")
                    Panel {
                        LabeledSlider("Pegel", signedDb(edit.gainDb), edit.gainDb.toFloat(), -24f..24f) {
                            vm.updateEdit(edit.copy(gainDb = (it * 2).roundToInt() / 2.0))
                        }
                        SwitchRow("Normalisieren", "Lautesten Punkt auf -1 dB bringen, dann den Pegel anwenden", edit.normalize) {
                            vm.updateEdit(edit.copy(normalize = it))
                        }
                    }

                    SectionTitle("Ein- und Ausblenden")
                    Panel {
                        LabeledSlider("Einblenden", formatSeconds(edit.fadeIn), edit.fadeIn.toFloat(), 0f..3f) {
                            vm.updateEdit(edit.copy(fadeIn = (it * 20).roundToInt() / 20.0))
                        }
                        LabeledSlider("Ausblenden", formatSeconds(edit.fadeOut), edit.fadeOut.toFloat(), 0f..3f) {
                            vm.updateEdit(edit.copy(fadeOut = (it * 20).roundToInt() / 20.0))
                        }
                    }

                    SectionTitle("Hall")
                    Panel {
                        LabeledSlider("Anteil", percent(edit.reverbMix), edit.reverbMix.toFloat(), 0f..1f) {
                            vm.updateEdit(edit.copy(reverbMix = (it * 100).roundToInt() / 100.0))
                        }
                        LabeledSlider("Raumgröße", percent(edit.reverbSize), edit.reverbSize.toFloat(), 0f..1f) {
                            vm.updateEdit(edit.copy(reverbSize = (it * 100).roundToInt() / 100.0))
                        }
                        LabeledSlider("Dämpfung", percent(edit.reverbDamping), edit.reverbDamping.toFloat(), 0f..1f) {
                            vm.updateEdit(edit.copy(reverbDamping = (it * 100).roundToInt() / 100.0))
                        }
                    }

                    SectionTitle("Filter")
                    Panel {
                        LabeledSlider("Tiefen absenken", if (edit.lowCut <= 0) "aus" else "unter ${edit.lowCut.roundToInt()} Hz", edit.lowCut.toFloat(), 0f..600f) {
                            vm.updateEdit(edit.copy(lowCut = if (it < 20) 0.0 else (it / 10).roundToInt() * 10.0))
                        }
                        // The slider runs from "off" on the left down to 1 kHz on the right.
                        val highPos = if (edit.highCut <= 0) 0f else (20000f - edit.highCut.toFloat()) / 19000f
                        LabeledSlider("Höhen absenken", if (edit.highCut <= 0) "aus" else "über ${(edit.highCut / 100).roundToInt() / 10.0} kHz".replace('.', ','), highPos, 0f..1f) {
                            vm.updateEdit(edit.copy(highCut = if (it < 0.02f) 0.0 else ((20000 - it * 19000) / 100).roundToInt() * 100.0))
                        }
                    }

                    Spacer(Modifier.height(20.dp))
                    Row(horizontalArrangement = Arrangement.spacedBy(10.dp), verticalAlignment = Alignment.CenterVertically) {
                        SmallButton("Alles zurücksetzen", { vm.updateEdit(AudioEdit()) }, enabled = !edit.isNeutral, color = MaterialTheme.colorScheme.error)
                        Spacer(Modifier.weight(1f))
                        OutlinedButton(vm::closeAudioEditor) { Text("Abbrechen") }
                        Button(vm::saveAudioEditor, colors = ButtonDefaults.buttonColors(containerColor = accent, contentColor = Color.White)) {
                            Text("Speichern", fontWeight = FontWeight.SemiBold)
                        }
                    }
                    Text(
                        "Die Originaldatei bleibt unverändert – alles lässt sich später wieder ändern, auch mitten beim Lernen.",
                        Modifier.padding(top = 12.dp),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Spacer(Modifier.height(30.dp))
                }
            }
        }
    }
}

/** The source waveform, scaled by the current gain, with draggable start and end markers. */
@Composable
private fun Waveform(vm: AppViewModel, state: AudioEditorState) {
    val edit = state.edit
    var head by remember { mutableStateOf<Double?>(null) }
    LaunchedEffect(state.playing) {
        while (state.playing) {
            withFrameMillis { }
            head = vm.audioEditorPlayhead()
        }
        head = null
    }
    val peaks = state.peaks
    val duration = state.duration.coerceAtLeast(0.001)
    // What the gain does to the picture: normalising is estimated from the drawn peaks.
    val selPeak = run {
        val a = (edit.trimStart / duration * peaks.size).toInt().coerceIn(0, peaks.size - 1)
        val b = (state.selectionEnd / duration * peaks.size).toInt().coerceIn(a + 1, peaks.size)
        (a until b).maxOf { peaks[it] }.coerceAtLeast(1e-4f)
    }
    val gain = Effects.dbToGain(edit.gainDb) * if (edit.normalize) 0.891f / selPeak else 1f
    val wave = MaterialTheme.colorScheme.onSurface
    val dim = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.25f)
    var dragging by remember { mutableStateOf<Int?>(null) } // 0 = start, 1 = end
    Box(
        Modifier.fillMaxWidth().height(170.dp).clip(RoundedCornerShape(16.dp)).background(MaterialTheme.colorScheme.surface)
            .pointerInput(state) {
                detectDragGestures(
                    onDragStart = { at ->
                        val t = at.x / size.width * duration
                        dragging = if (abs(t - state.edit.trimStart) <= abs(t - state.selectionEnd)) 0 else 1
                    },
                    onDrag = { change, _ ->
                        change.consume()
                        val t = (change.position.x / size.width * duration).coerceIn(0.0, duration)
                        val e = state.edit
                        if (dragging == 0) vm.updateEdit(e.copy(trimStart = t)) else vm.updateEdit(e.copy(trimEnd = t))
                    },
                    onDragEnd = { dragging = null },
                    onDragCancel = { dragging = null },
                )
            }
            .pointerInput(state) {
                // A tap moves the closer marker there.
                detectTapGestures { at ->
                    val t = at.x / size.width * duration
                    val e = state.edit
                    if (abs(t - e.trimStart) <= abs(t - state.selectionEnd)) vm.updateEdit(e.copy(trimStart = t)) else vm.updateEdit(e.copy(trimEnd = t))
                }
            },
    ) {
        Canvas(Modifier.fillMaxSize()) {
            val w = size.width
            val h = size.height
            val mid = h / 2
            val bars = (w / 3f).toInt().coerceAtLeast(1)
            val startX = (edit.trimStart / duration * w).toFloat()
            val endX = (state.selectionEnd / duration * w).toFloat()
            for (b in 0 until bars) {
                val p = peaks[(b.toLong() * peaks.size / bars).toInt().coerceIn(0, peaks.size - 1)]
                val x = b * 3f + 1f
                val inside = x in startX..endX
                val amp = (if (inside) p * gain else p).coerceAtMost(1f)
                val clipped = inside && p * gain > 1f
                val barH = (amp * (h * 0.44f)).coerceAtLeast(1f)
                drawRect(
                    if (!inside) dim else if (clipped) Palette.again else wave,
                    Offset(x, mid - barH),
                    Size(2f, barH * 2),
                )
            }
            // Outside the selection is shaded, the markers have grips.
            drawRect(Color.Black.copy(alpha = 0.18f), Offset.Zero, Size(startX, h))
            drawRect(Color.Black.copy(alpha = 0.18f), Offset(endX, 0f), Size(w - endX, h))
            for (x in listOf(startX, endX)) {
                drawRect(accent, Offset(x - 1.5f, 0f), Size(3f, h))
                drawRect(accent, Offset(x - 9f, h / 2 - 22f), Size(18f, 44f))
            }
            // Fades as ramps over the waveform.
            val fadeInX = (edit.fadeIn / duration * w).toFloat()
            val fadeOutX = (edit.fadeOut / duration * w).toFloat()
            if (fadeInX > 0) drawLine(accent.copy(alpha = 0.8f), Offset(startX, h), Offset(startX + fadeInX, 0f), 2f)
            if (fadeOutX > 0) drawLine(accent.copy(alpha = 0.8f), Offset(endX - fadeOutX, 0f), Offset(endX, h), 2f)
            head?.let { t ->
                val x = (t / duration * w).toFloat()
                drawRect(Palette.chords, Offset(x - 1f, 0f), Size(2.5f, h))
            }
        }
    }
    Text(
        "Ziehe die orangen Marken, um Anfang und Ende zu schneiden. Rot = übersteuert.",
        Modifier.padding(top = 6.dp),
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
}

@Composable
fun LabeledSlider(label: String, value: String, position: Float, range: ClosedFloatingPointRange<Float>, color: Color = accent, onChange: (Float) -> Unit) {
    Column {
        Row {
            Text(label, Modifier.weight(1f))
            Text(value, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        Slider(
            position.coerceIn(range.start, range.endInclusive),
            onChange,
            valueRange = range,
            colors = SliderDefaults.colors(thumbColor = color, activeTrackColor = color),
        )
    }
}

@Composable
fun SwitchRow(title: String, text: String?, checked: Boolean, color: Color? = null, onChange: (Boolean) -> Unit) {
    Row(Modifier.fillMaxWidth().clickable { onChange(!checked) }.padding(vertical = 6.dp), verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f)) {
            Text(title)
            if (text != null) Text(text, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        Spacer(Modifier.width(12.dp))
        Switch(checked, onChange, colors = color?.let { SwitchDefaults.colors(checkedTrackColor = it, checkedThumbColor = Color.White) } ?: SwitchDefaults.colors())
    }
}

private fun signedDb(db: Double): String {
    val v = ((db * 10).roundToInt() / 10.0).toString().replace('.', ',')
    return (if (db > 0) "+" else "") + v + " dB"
}

private fun percent(x: Double) = "${(x * 100).roundToInt()} %"

/** Recording in progress: time, level meter, stop. */
@Composable
fun RecordingOverlay(vm: AppViewModel) {
    val state = vm.recording ?: return
    var level by remember { mutableStateOf(0f) }
    var seconds by remember { mutableStateOf(0.0) }
    LaunchedEffect(state) {
        while (true) {
            withFrameMillis { }
            level = vm.audio.recorder.level()
            seconds = (widder.core.currentTimeMillis() - state.started) / 1000.0
        }
    }
    Box(Modifier.fillMaxSize().background(Color.Black.copy(alpha = 0.55f)).clickable(enabled = false) {}, contentAlignment = Alignment.Center) {
        Surface(shape = RoundedCornerShape(24.dp), color = MaterialTheme.colorScheme.surface, modifier = Modifier.padding(24.dp).width(340.dp)) {
            Column(Modifier.padding(24.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    GlyphIcon(Glyph.RECORD, Palette.again, 22.dp)
                    Spacer(Modifier.width(8.dp))
                    Text("Aufnahme läuft", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
                }
                Spacer(Modifier.height(14.dp))
                Text(formatSeconds(seconds).substringBefore(',') + " s", style = MaterialTheme.typography.displaySmall)
                Spacer(Modifier.height(14.dp))
                ProgressBar(level * 1.4f, if (level > 0.9f) Palette.again else Palette.good, Modifier.fillMaxWidth(), height = 10.dp)
                Spacer(Modifier.height(20.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    OutlinedButton({ vm.stopRecording(keep = false) }) { Text("Verwerfen") }
                    Button({ vm.stopRecording() }, colors = ButtonDefaults.buttonColors(containerColor = Palette.again, contentColor = Color.White)) {
                        Text("Stopp & schneiden")
                    }
                }
            }
        }
    }
}

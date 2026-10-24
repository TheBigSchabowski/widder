package widder.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Alignment
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
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import widder.audio.PitchTracker
import widder.audio.VibratoLine
import widder.audio.VibratoResult
import widder.audio.PracticeEngine
import widder.core.Bend
import widder.core.VibratoDirection
import widder.core.VibratoSettings
import widder.core.VibratoSound
import widder.music.Chord
import kotlin.math.abs
import kotlin.math.pow
import kotlin.math.roundToInt

private val accent = Palette.practice

/**
 * A model note to listen to and copy into the microphone. The model is a line (straight, rising,
 * falling, drawn by hand ...) with a vibrato on top; the attempt is measured against both.
 */
@Composable
fun VibratoPanel(vm: AppViewModel) {
    val s = vm.practice.vibrato
    val hidden = vm.vibratoHidden
    val take = vm.vibratoTake
    val live = vm.vibratoLive
    val recording = take?.recording == true
    val listening = recording || live != null
    val drawing = vm.vibratoDrawing && !hidden && !listening
    val note = noteName(s.pitch, vm.notation)
    SectionTitle("Vibrato")
    Panel {
        Row(verticalAlignment = Alignment.CenterVertically) {
            PlayButton(vm.playingKey == VIBRATO_KEY, { vm.playVibrato(swing = true) }, accent, 56.dp, enabled = !recording)
            Spacer(Modifier.width(14.dp))
            Column(Modifier.weight(1f)) {
                Text("Vibrato auf $note", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.SemiBold)
                Text(
                    if (hidden) "Zufall · Linie und Vibrato verdeckt · ${s.sound.label}" else "${describe(s)} · ${s.sound.label}",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
        FlowRow(Modifier.padding(top = 6.dp)) {
            SmallButton(if (vm.playingKey == TONE_KEY) "Ton stoppen" else "Nur den Ton", { vm.playVibrato(swing = false) }, enabled = !recording, color = accent)
            SmallButton("Zufall", vm::randomVibrato, enabled = !recording, color = accent)
            if (hidden) SmallButton("Aufdecken", vm::revealVibrato, color = accent)
        }

        Spacer(Modifier.height(10.dp))
        Row(verticalAlignment = Alignment.CenterVertically) {
            RecordButton(recording, vm::toggleVibratoRecording, enabled = live == null)
            Spacer(Modifier.width(14.dp))
            Column(Modifier.weight(1f)) {
                Text(if (recording) "Hört zu …" else "Nachmachen", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
                Text(
                    when {
                        recording -> "Halte den Ton und folge der Linie, dann auf Stopp tippen"
                        take?.analysing == true -> "Wird ausgewertet …"
                        else -> "Aufnehmen und auswerten: sing oder spiel $note (gern eine Oktave tiefer oder höher) wie die Vorgabe"
                    },
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            if (take?.pcm != null && !recording) {
                SmallButton(if (vm.playingKey == TAKE_KEY) "Stopp" else "Anhören", vm::playVibratoTake, color = attemptColor)
            }
        }
        Spacer(Modifier.height(8.dp))
        Row(verticalAlignment = Alignment.CenterVertically) {
            PlayButton(live != null, vm::toggleVibratoLive, attemptColor, 56.dp, enabled = !recording)
            Spacer(Modifier.width(14.dp))
            Column(Modifier.weight(1f)) {
                Text(if (live != null) "Live · hört zu" else "Live mitspielen", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
                Text(
                    if (live != null) "Spiel einfach drauflos – deine Kurve läuft über die Vorgabe, die Werte gelten für die letzten 3 Sekunden" else "Ohne Aufnahme: sofort sehen, wie dein Vibrato aussieht",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
        Spacer(Modifier.height(12.dp))
        VibratoGraph(
            vm = vm,
            target = take?.target ?: s,
            showTarget = !hidden,
            result = take?.result,
            attemptLive = take?.takeIf { it.result == null }?.live?.toList(),
            live = live,
            editing = drawing,
        )
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Legend(!hidden, take != null || live != null, drawing)
            Spacer(Modifier.weight(1f))
            if (!hidden && !listening) {
                SmallButton(if (drawing) "Fertig" else "Linie bearbeiten", vm::toggleVibratoDrawing, color = accent)
            }
        }
        if (drawing) LineEditorBar(vm)
        live?.result?.let {
            LiveValues(it, s)
            SmallButton("Die letzten 3 Sekunden als Vorgabe übernehmen", { vm.adoptVibrato(it) }, color = attemptColor)
        } ?: live?.let { Hint("Noch kein gehaltener Ton …") }
        take?.takeIf { !it.recording && !it.analysing }?.let { t ->
            val result = t.result
            if (result == null) {
                Hint("Kein gehaltener Ton erkannt. Halte den Ton mindestens eine Sekunde lang, nicht zu leise.")
            } else {
                ResultView(result)
                SmallButton("Als Vorgabe übernehmen – eigenes Vibrato", { vm.adoptVibrato(result) }, color = attemptColor)
            }
        }

        if (!hidden) {
            Row(Modifier.padding(top = 12.dp), verticalAlignment = Alignment.CenterVertically) {
                Text("Achse", Modifier.weight(1f), style = MaterialTheme.typography.bodyMedium)
                Choices(listOf(false, true), s.axisHz, { if (it) "Hz" else "Töne" }, accent) { hz -> vm.updatePractice { it.copy(vibrato = it.vibrato.copy(axisHz = hz)) } }
            }
            Label("Linie")
            val shape = VibratoLine.of(s.points)
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                for (l in VibratoLine.entries) Chip(l.label, accent, filled = l == shape, modifier = Modifier.clickable { vm.setVibratoLine(l) })
                if (shape == null) Chip("Eigene", accent, filled = true, modifier = Modifier.clickable(onClick = vm::toggleVibratoDrawing))
            }
        }
        Label("Ton")
        Choices((0 until 12).toList(), s.root, { Chord.noteName(it, vm.notation) }, accent) { r -> vm.updateVibrato { it.copy(root = r) } }
        Label("Oktave")
        Choices((2..5).toList(), s.octave, { noteName(12 * (it + 1) + s.root, vm.notation) }, accent) { o -> vm.updateVibrato { it.copy(octave = o) } }
        Label("Klang")
        Choices(VibratoSound.entries, s.sound, { it.label }, accent) { k -> vm.updateVibrato { it.copy(sound = k) } }
        if (!hidden) {
            Spacer(Modifier.height(10.dp))
            LabeledSlider("Breite", if (s.width == 0) "kein Vibrato" else "${s.width} Cent · ${widthWord(s.width)}", s.width.toFloat(), 0f..VibratoSettings.MAX_WIDTH.toFloat(), accent) { v ->
                vm.updateVibrato { it.copy(width = (v / 5).roundToInt() * 5) }
            }
            if (s.width > 0) {
                LabeledSlider("Geschwindigkeit", "${decimal(s.rate)} Hz · ${speedWord(s.rate)}", s.rate, VibratoSettings.MIN_RATE..VibratoSettings.MAX_RATE, accent) { v ->
                    vm.updateVibrato { it.copy(rate = (v * 10).roundToInt() / 10f) }
                }
                LabeledSlider(
                    "Vibrato ab",
                    if (s.vibratoFrom == 0f) "sofort" else "${decimal(s.vibratoFrom)} s, dann schwillt es an",
                    s.vibratoFrom,
                    0f..(s.seconds - 1f),
                    accent,
                ) { v -> vm.updateVibrato { it.copy(vibratoFrom = (v * 10).roundToInt() / 10f) } }
                Label("Richtung")
                Choices(VibratoDirection.entries, s.direction, { it.label }, accent) { d -> vm.updateVibrato { it.copy(direction = d) } }
                Text(
                    when (s.direction) {
                        VibratoDirection.UP -> "Die Linie ist der tiefste Punkt; das Vibrato geht darüber, wie bei einem Gitarren-Bending."
                        VibratoDirection.AROUND -> "Das Vibrato schwingt gleich weit über und unter die Linie, wie bei Geige oder Gesang."
                        VibratoDirection.DOWN -> "Die Linie ist der höchste Punkt; das Vibrato geht darunter."
                    },
                    Modifier.padding(top = 6.dp),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
        Label("Länge")
        Choices(listOf(2, 3, 4, 6, 8), s.seconds, { "$it s" }, accent) { n ->
            vm.updateVibrato { it.copy(seconds = n, vibratoFrom = it.vibratoFrom.coerceAtMost(n - 1f)) }
        }
    }
}

/** The picked point: where it sits, how the line gets there, delete; plus the grid and how to use the editor. */
@Composable
private fun LineEditorBar(vm: AppViewModel) {
    val s = vm.practice.vibrato
    val index = vm.linePoint
    val point = index?.let { s.points.getOrNull(it) }
    Column(Modifier.padding(top = 6.dp)) {
        if (point != null && index != null) {
            Text(
                "Punkt ${index + 1}: ${decimal(point.at * s.seconds)} s · ${pitchLabel(vm, s, point.cents)}",
                fontWeight = FontWeight.Medium,
            )
            Row(Modifier.padding(top = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                Text("Übergang", Modifier.padding(end = 8.dp), style = MaterialTheme.typography.bodySmall)
                Choices(Bend.entries, point.bend, { it.label }, accent) { vm.bendLinePoint(index, it) }
                Spacer(Modifier.weight(1f))
                SmallButton("Löschen", { vm.removeLinePoint(index) }, color = Palette.again)
            }
        }
        Row(Modifier.padding(top = 4.dp), verticalAlignment = Alignment.CenterVertically) {
            Text(
                "Einrasten auf " + divisionName(vm.practice.division),
                Modifier.weight(1f),
                style = MaterialTheme.typography.bodySmall,
            )
            androidx.compose.material3.Switch(
                vm.lineSnap,
                { vm.toggleLineSnap() },
                colors = androidx.compose.material3.SwitchDefaults.colors(checkedTrackColor = accent, checkedThumbColor = androidx.compose.ui.graphics.Color.White),
            )
        }
        Text(
            "Klicken: Punkt setzen · Ziehen: verschieben (weicher Übergang) · mit ⇧ Shift ziehen: steiler Übergang · " +
                "Doppelklick oder lange drücken: löschen. Das Vibrato aus den Reglern kommt obendrauf (Breite 0 = nur die Linie).",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

/** What the live listening measured, next to the model's values. */
@Composable
private fun LiveValues(r: VibratoResult, s: VibratoSettings) {
    Column(Modifier.padding(top = 10.dp)) {
        if (r.direction == null) {
            Text("Gerade gehalten · ${r.width.roundToInt()} Cent Schwankung", fontWeight = FontWeight.SemiBold)
            return@Column
        }
        Row(verticalAlignment = Alignment.CenterVertically) {
            LiveValue(if (r.rate.isNaN()) "unregelmäßig" else "${decimal(r.rate.toFloat())} Hz", s.width == 0 || r.rateOk)
            LiveValue("${r.width.roundToInt()} Cent", s.width == 0 || r.widthOk)
            LiveValue(lower(r.direction), s.width == 0 || r.directionOk)
        }
        if (s.width > 0) {
            Text(
                "Vorgabe: ${decimal(s.rate)} Hz · ${s.width} Cent · ${lower(s.direction)}",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

@Composable
private fun LiveValue(text: String, ok: Boolean) {
    Box(Modifier.size(10.dp).clip(CircleShape).background(if (ok) Palette.good else Palette.hard))
    Spacer(Modifier.width(6.dp))
    Text(text, Modifier.padding(end = 16.dp), style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
}

/** [cents] from the model's note as a note name with cents, or in Hz. */
private fun pitchLabel(vm: AppViewModel, s: VibratoSettings, cents: Float): String {
    if (s.axisHz) {
        val hz = PracticeEngine.frequency(s.pitch, vm.practice.tuning) * 2.0.pow(cents / 1200.0)
        return "${decimal(hz.toFloat())} Hz"
    }
    // Right between two notes, after the lower one (A#3 +50, not B3 −50), as the tuner does.
    val semis = kotlin.math.ceil(cents / 100f - 0.5f - 1e-4f).toInt()
    val rest = (cents - semis * 100).roundToInt()
    return noteName(s.pitch + semis, vm.notation) + when {
        rest > 0 -> " +$rest ct"
        rest < 0 -> " −${-rest} ct"
        else -> ""
    }
}

@Composable
private fun RecordButton(recording: Boolean, onClick: () -> Unit, enabled: Boolean = true) {
    Box(
        Modifier.size(56.dp).clip(CircleShape).background(if (enabled) Palette.again else Palette.again.copy(alpha = 0.3f))
            .clickable(enabled = enabled, onClickLabel = if (recording) "Aufnahme stoppen" else "Aufnehmen", onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        GlyphIcon(if (recording) Glyph.STOP else Glyph.RECORD, Color.White, 28.dp)
    }
}

@Composable
private fun Legend(target: Boolean, attempt: Boolean, drawing: Boolean) {
    Row(Modifier.padding(top = 6.dp), verticalAlignment = Alignment.CenterVertically) {
        if (target) LegendItem(accent, "Vorgabe")
        if (target && drawing) {
            Spacer(Modifier.width(16.dp))
            LegendItem(accent.copy(alpha = 0.5f), "Linie")
        }
        if (target && attempt) Spacer(Modifier.width(16.dp))
        if (attempt) LegendItem(attemptColor, "Du")
    }
}

@Composable
private fun LegendItem(color: Color, text: String) {
    Box(Modifier.size(width = 16.dp, height = 4.dp).clip(RoundedCornerShape(2.dp)).background(color))
    Spacer(Modifier.width(6.dp))
    Text(text, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
}

@Composable
private fun ResultView(r: VibratoResult) {
    Column(Modifier.padding(top = 12.dp)) {
        Row(verticalAlignment = Alignment.Bottom) {
            Text("${r.score}", style = MaterialTheme.typography.headlineMedium, fontWeight = FontWeight.Bold, color = scoreColor(r.score))
            Text(" / 100 · ${verdict(r.score)}", Modifier.padding(bottom = 4.dp), color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        if (r.short) Hint("Viel kürzer gehalten als die Vorgabe (${r.target.seconds} s) – die Werte sind dann ungenauer.")
        if (r.targetSwings) {
            if (r.direction == null) Hint("Kein Vibrato erkannt: der Ton schwingt kaum (${r.width.roundToInt()} Cent).")
            ResultRow(
                "Geschwindigkeit",
                if (r.rate.isNaN()) "keine regelmäßige Schwingung" else "${decimal(r.rate.toFloat())} Hz",
                "${decimal(if (r.target.width > 0) r.target.rate else r.targetRate.toFloat())} Hz",
                r.rateOk,
                when {
                    r.rate.isNaN() -> null
                    r.rateOk -> "passt"
                    r.rate > r.targetRate -> "zu schnell"
                    else -> "zu langsam"
                },
            )
            ResultRow(
                "Breite",
                "${r.width.roundToInt()} Cent",
                "${if (r.target.width > 0) r.target.width else r.targetWidth.roundToInt()} Cent",
                r.widthOk,
                when {
                    r.widthOk -> "passt"
                    r.width > r.targetWidth -> "zu breit"
                    else -> "zu schmal"
                },
            )
            ResultRow("Richtung", r.direction?.let(::lower) ?: "–", lower(r.target.direction), r.directionOk, if (r.directionOk) "passt" else null)
        } else {
            ResultRow("Vibrato", if (r.width < VibratoResult.MIN_SWING) "keins" else "${r.width.roundToInt()} Cent", "keins", r.widthOk, if (r.widthOk) "passt" else "zu viel Schwingung")
        }
        ResultRow(
            "Tonhöhe",
            when {
                r.pitchOk -> "im Schnitt auf der Linie"
                r.offset > 0 -> "im Schnitt ${r.offset.roundToInt()} Cent zu hoch"
                else -> "im Schnitt ${(-r.offset).roundToInt()} Cent zu tief"
            },
            null,
            r.pitchOk,
            if (r.pitchOk) "passt" else if (r.offset > 0) "zu hoch" else "zu tief",
        )
        ResultRow(
            "Verlauf",
            "meist höchstens ${r.shape.roundToInt()} Cent neben der Linie",
            null,
            r.shapeOk,
            when {
                r.shapeOk -> "passt"
                r.shape < 50 -> "etwas daneben"
                else -> "deutlich daneben"
            },
        )
    }
}

@Composable
private fun ResultRow(title: String, yours: String, goal: String?, ok: Boolean, word: String?) {
    Row(Modifier.fillMaxWidth().padding(top = 8.dp), verticalAlignment = Alignment.CenterVertically) {
        Box(Modifier.size(10.dp).clip(CircleShape).background(if (ok) Palette.good else Palette.hard))
        Spacer(Modifier.width(10.dp))
        Column(Modifier.weight(1f)) {
            Text(title + (word?.let { " · $it" } ?: ""), fontWeight = FontWeight.Medium)
            Text(if (goal == null) yours else "du: $yours · Vorgabe: $goal", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

@Composable
private fun Hint(text: String) {
    Text(text, Modifier.padding(top = 10.dp), style = MaterialTheme.typography.bodyMedium, color = Palette.hard)
}

@Composable
private fun Label(text: String) {
    Text(text, Modifier.padding(top = 12.dp, bottom = 6.dp), style = MaterialTheme.typography.bodyMedium)
}

private fun describe(s: VibratoSettings): String {
    val line = "Linie: " + (VibratoLine.of(s.points)?.label ?: "eigene")
    if (s.width == 0) return "$line · ohne Vibrato"
    return "$line · ${decimal(s.rate)} Hz · ${s.width} Cent · ${lower(s.direction)}" + if (s.vibratoFrom > 0f) " ab ${decimal(s.vibratoFrom)} s" else ""
}

private fun lower(d: VibratoDirection) = d.label.replaceFirstChar { it.lowercase() }

private fun decimal(x: Float) = if (x.isNaN()) "–" else ((x * 10).roundToInt() / 10.0).toString().replace('.', ',')

private fun speedWord(rate: Float) = when {
    rate < 4f -> "langsam"
    rate < 6.5f -> "mittel"
    else -> "schnell"
}

private fun widthWord(width: Int) = when {
    width < 35 -> "eng"
    width < 90 -> "mittel"
    width <= 110 -> "ein Halbton"
    else -> "weit"
}

private fun verdict(score: Int) = when {
    score >= 90 -> "sehr gut"
    score >= 75 -> "gut"
    score >= 50 -> "geht so"
    else -> "nochmal"
}

private fun scoreColor(score: Int) = when {
    score >= 75 -> Palette.good
    score >= 50 -> Palette.hard
    else -> Palette.again
}

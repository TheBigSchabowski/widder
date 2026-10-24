package widder.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import kotlin.math.floor
import kotlin.math.roundToInt

private val accent = Palette.practice

/** Record once, and it repeats: free, or cut on the drums' bar lines and locked to them; then play more on top. */
@Composable
fun LooperPanel(vm: AppViewModel) {
    val take = vm.loopTake
    val p = vm.practice
    SectionTitle("Looper")
    Panel {
        Row(verticalAlignment = Alignment.CenterVertically) {
            when (take?.phase) {
                null -> RoundButton(Palette.again, Glyph.RECORD, "Aufnehmen", vm::loopButton)
                LoopPhase.RECORDING, LoopPhase.CLOSING -> RoundButton(Palette.again, Glyph.STOP, "Loop setzen", vm::loopButton)
                LoopPhase.READY -> {
                    Captioned("Loop") { PlayButton(vm.loopOn, vm::loopButton, accent, 56.dp) }
                    Spacer(Modifier.width(8.dp))
                    Captioned(if (take.overdubbing) "beenden" else "Overdub") {
                        RoundButton(
                            if (take.overdubbing) Palette.again else Palette.again.copy(alpha = 0.55f),
                            if (take.overdubbing) Glyph.STOP else Glyph.PLUS,
                            if (take.overdubbing) "Overdub beenden" else "Overdub",
                            vm::toggleOverdub,
                        )
                    }
                }
            }
            Spacer(Modifier.width(14.dp))
            Column(Modifier.weight(1f)) {
                Text(
                    when (take?.phase) {
                        null -> "Aufnehmen"
                        LoopPhase.RECORDING -> "Nimmt auf · ${clock(take.seconds)}"
                        LoopPhase.CLOSING -> "Schließt am Taktende …"
                        LoopPhase.READY -> when {
                            take.overdubbing -> "Overdub"
                            take.bars > 0 -> "Loop · ${bars(take.bars)}"
                            else -> "Loop · ${clock(take.seconds)}"
                        }
                    },
                    style = MaterialTheme.typography.titleLarge,
                    fontWeight = FontWeight.SemiBold,
                )
                Text(
                    when (take?.phase) {
                        null -> if (vm.drumsOn) {
                            "Im Takt der Drums: der Loop beginnt auf der nächsten Eins und endet auf einer Taktlinie"
                        } else {
                            "Einmal spielen, dann „Loop setzen“ – es wiederholt sich, so lang du gespielt hast"
                        }
                        LoopPhase.RECORDING -> if (take.sync != null) {
                            if (take.bar < 0) "Gleich geht's los: ab der Eins" else "Takt ${floor(take.bar).toInt() + 1} · tippe auf „Loop setzen“"
                        } else {
                            "Tippe auf „Loop setzen“, wenn du fertig bist"
                        }
                        LoopPhase.CLOSING -> "Spiel weiter bis zur nächsten Eins"
                        LoopPhase.READY -> if (take.overdubbing) {
                            "Schicht ${take.layers + 1} · spiel dazu, Runde für Runde kommt es obendrauf"
                        } else {
                            listOfNotNull(
                                if (take.bars > 0) "${clock(take.seconds)} · im Takt der Drums" else "frei",
                                layers(take.layers).takeIf { take.layers > 1 },
                                if (vm.loopOn) "läuft" else "angehalten",
                            ).joinToString(" · ")
                        }
                    },
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
        if (take != null) {
            FlowRow(Modifier.padding(top = 6.dp)) {
                if (take.phase == LoopPhase.READY) {
                    if (take.layers > 1 && take.undo.isNotEmpty()) SmallButton("Letzte Schicht weg", vm::undoOverdub, color = accent)
                    SmallButton("Neu aufnehmen", vm::startLoop, color = accent)
                    SmallButton("Als WAV speichern", vm::saveLoop, color = accent)
                }
                SmallButton(if (take.phase == LoopPhase.READY) "Löschen" else "Verwerfen", vm::clearLoop, color = Palette.again)
            }
        }
        Spacer(Modifier.height(8.dp))
        LabeledSlider("Lautstärke", "${(p.loopVolume * 100).roundToInt()} %", p.loopVolume, 0f..1f, accent) { v ->
            vm.updatePractice { it.copy(loopVolume = (v * 100).roundToInt() / 100f) }
        }
        LabeledSlider("Latenz-Ausgleich", "${p.loopLatency} ms", p.loopLatency.toFloat(), 0f..500f, accent) { v ->
            vm.updatePractice { it.copy(loopLatency = (v / 5).roundToInt() * 5) }
        }
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                if (vm.measuringLatency) "Hört zu … bitte kurz still sein" else "Ohne Kopfhörer messen: Widder spielt ein paar Klicks und hört, wann sie ankommen",
                Modifier.weight(1f),
                style = MaterialTheme.typography.bodySmall,
                color = if (vm.measuringLatency) accent else MaterialTheme.colorScheme.onSurfaceVariant,
            )
            SmallButton("Latenz messen", vm::measureLatency, enabled = !vm.measuringLatency && take?.let { it.phase != LoopPhase.READY || it.overdubbing } != true, color = accent)
        }
        Text(
            "Klingt der Loop gegenüber den Drums – oder ein Overdub gegenüber dem Loop – zu spät, den Wert erhöhen, klingt er zu früh, " +
                "verringern. Zum Spielen dann Kopfhörer: so nimmt das Mikrofon Drums und Loop nicht mit auf.",
            Modifier.padding(top = 8.dp),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

/** The practice timer: everything off after a while, with the time left. */
@Composable
fun TimerPanel(vm: AppViewModel) {
    val left = vm.practiceSecondsLeft
    SectionTitle("Timer")
    Panel {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text("Alles aus nach", Modifier.weight(1f), fontWeight = FontWeight.Medium)
            Text(
                when {
                    left != null -> "noch ${clock(left.toDouble())}"
                    vm.practice.stopAfter > 0 -> "startet mit dem ersten Ton"
                    else -> "aus"
                },
                color = if (left != null) accent else MaterialTheme.colorScheme.onSurfaceVariant,
                fontWeight = if (left != null) FontWeight.SemiBold else FontWeight.Normal,
            )
        }
        Spacer(Modifier.height(8.dp))
        Choices(listOf(0, 5, 10, 15, 20, 30, 45, 60), vm.practice.stopAfter, { if (it == 0) "Aus" else "$it min" }, accent, vm::setStopAfter)
        Text(
            "Drums, Drone und Loop gehen dann aus – auch wenn die App im Hintergrund ist.",
            Modifier.padding(top = 8.dp),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

/** A round button with a word under it. */
@Composable
private fun Captioned(caption: String, button: @Composable () -> Unit) {
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        button()
        Text(caption, Modifier.padding(top = 2.dp), style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

@Composable
private fun RoundButton(color: Color, glyph: Glyph, label: String, onClick: () -> Unit) {
    Box(
        Modifier.size(56.dp).clip(CircleShape).background(color).clickable(onClickLabel = label, onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        GlyphIcon(glyph, Color.White, 28.dp)
    }
}

private fun clock(seconds: Double): String {
    val s = seconds.toInt()
    return "${s / 60}:${(s % 60).toString().padStart(2, '0')}"
}

private fun bars(n: Int) = if (n == 1) "1 Takt" else "$n Takte"

private fun layers(n: Int) = if (n == 1) "1 Schicht" else "$n Schichten"

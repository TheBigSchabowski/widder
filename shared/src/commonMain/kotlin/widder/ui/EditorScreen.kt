package widder.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import widder.core.AudioEdit
import widder.core.Clip
import widder.core.DeckKind
import kotlin.math.roundToInt

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun EditorScreen(vm: AppViewModel) {
    val e = vm.editor ?: return
    val color = kindColor(e.kind)
    Column(Modifier.fillMaxSize()) {
        TopBar(if (e.original == null) "Neue Karte" else "Karte bearbeiten", vm::cancelEditor, subtitle = vm.deck(e.deckId)?.name) {
            if (e.original == null) SmallButton("Speichern + Neu", { vm.saveEditor(keepOpen = true) }, color = color)
            SmallButton("Speichern", { vm.saveEditor() }, color = color)
        }
        Centered {
            Column(Modifier.verticalScroll(rememberScrollState()).padding(16.dp)) {
                if (e.kind == DeckKind.CARDS) {
                    SectionTitle("Vorderseite")
                    OutlinedTextField(e.front, { e.front = it }, Modifier.fillMaxWidth(), minLines = 3, placeholder = { Text("Frage") })
                    Spacer(Modifier.height(10.dp))
                    AudioSlot(vm, ClipTarget.Editor(back = false), e.frontAudio, color)
                    SectionTitle("Rückseite")
                    OutlinedTextField(e.back, { e.back = it }, Modifier.fillMaxWidth(), minLines = 3, placeholder = { Text("Antwort") })
                    Spacer(Modifier.height(10.dp))
                    AudioSlot(vm, ClipTarget.Editor(back = true), e.backAudio, color)
                    SectionTitle("Schlagwörter")
                    OutlinedTextField(e.tags, { e.tags = it }, Modifier.fillMaxWidth(), singleLine = true, placeholder = { Text("durch Leerzeichen getrennt") })
                } else {
                    SectionTitle("Audio")
                    AudioSlot(vm, ClipTarget.Editor(back = false), e.frontAudio, color)
                    SectionTitle("Lösung – in der Reihenfolge, in der sie klingt")
                    OutlinedTextField(e.answer, { e.answer = it }, Modifier.fillMaxWidth(), singleLine = true, placeholder = { Text("z. B. A D G F oder Fmin Gmin") })
                    Spacer(Modifier.height(8.dp))
                    FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                        vm.editorTokens().forEach { TokenChip(it) }
                    }
                    Text(
                        "Türkis = als Akkord erkannt (Fmin und Fm gelten als gleich). Grau = wird als Text verglichen.",
                        Modifier.padding(top = 6.dp),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    SectionTitle("Titel (nur in der Übersicht)")
                    OutlinedTextField(e.front, { e.front = it }, Modifier.fillMaxWidth(), singleLine = true)
                }
                val original = e.original
                if (original != null) {
                    Spacer(Modifier.height(24.dp))
                    Row {
                        SmallButton(if (vm.isSuspended(original.id)) "Wieder aktivieren" else "Aussetzen", { vm.toggleSuspend(original.id) })
                        SmallButton("Karte löschen", {
                            vm.confirm = Confirm("Karte löschen?", "Die Karte und ihre Audiodateien werden entfernt.", "Löschen") {
                                vm.deleteCard(original.id)
                                vm.back()
                            }
                        }, color = MaterialTheme.colorScheme.error)
                    }
                }
                Spacer(Modifier.height(40.dp))
            }
        }
    }
}

/** An audio attachment: play, cut & effects, replace, remove - or record / choose a file. */
@Composable
fun AudioSlot(vm: AppViewModel, target: ClipTarget, clip: Clip?, color: Color) {
    Panel(padding = 12.dp) {
        if (clip == null) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("Kein Audio", Modifier.weight(1f), color = MaterialTheme.colorScheme.onSurfaceVariant)
                SmallButton("● Aufnehmen", { vm.startRecording(target) }, color = Palette.again)
                SmallButton("Datei wählen", { vm.pickAudio(target) }, color = color)
            }
            return@Panel
        }
        val key = "slot-${target.back}"
        Row(verticalAlignment = Alignment.CenterVertically) {
            PlayButton(vm.playingKey == key, { vm.play(clip, key) }, color, 40.dp)
            Spacer(Modifier.width(12.dp))
            Column(Modifier.weight(1f)) {
                Text(clip.name, maxLines = 1, overflow = TextOverflow.Ellipsis, fontWeight = FontWeight.Medium)
                Text(
                    describe(clip.edit, clip.isMidi),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
        Row(Modifier.padding(top = 4.dp)) {
            SmallButton("Schneiden & Effekte", { vm.openAudioEditor(target) }, color = color)
            SmallButton("Ersetzen", { vm.pickAudio(target) }, color = MaterialTheme.colorScheme.onSurfaceVariant)
            SmallButton("Aufnehmen", { vm.startRecording(target) }, color = MaterialTheme.colorScheme.onSurfaceVariant)
            SmallButton("Entfernen", { vm.removeClip(target) }, color = MaterialTheme.colorScheme.error)
        }
    }
}

/** "MIDI · geschnitten · +3 dB · Hall 30 %" */
fun describe(edit: AudioEdit, midi: Boolean): String {
    val parts = mutableListOf<String>()
    if (midi) parts += "MIDI"
    if (edit.trimStart > 0 || edit.trimEnd != null) parts += "geschnitten"
    if (edit.normalize) parts += "normalisiert"
    if (edit.gainDb != 0.0) parts += (if (edit.gainDb > 0) "+" else "") + ((edit.gainDb * 10).roundToInt() / 10.0).toString().replace('.', ',') + " dB"
    if (edit.fadeIn > 0 || edit.fadeOut > 0) parts += "Blende"
    if (edit.reverbMix > 0) parts += "Hall ${(edit.reverbMix * 100).roundToInt()} %"
    if (edit.lowCut > 0 || edit.highCut > 0) parts += "Filter"
    return if (parts.isEmpty() || parts == listOf("MIDI")) (parts + "unbearbeitet").joinToString(" · ") else parts.joinToString(" · ")
}

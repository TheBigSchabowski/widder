package widder.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.ui.draw.alpha
import androidx.compose.runtime.setValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.getValue
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.DropdownMenu
import androidx.compose.foundation.layout.Box
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
                    SectionTitle("Akkorde (optional)")
                    OutlinedTextField(e.answer, { e.answer = it }, Modifier.fillMaxWidth(), singleLine = true, placeholder = { Text("z. B. Am F C G – erscheinen auf der Rückseite") })
                    ChordPreview(vm, color, canGenerate = true)
                    LabelField(vm)
                    SectionTitle("Schlagwörter")
                    OutlinedTextField(e.tags, { e.tags = it }, Modifier.fillMaxWidth(), singleLine = true, placeholder = { Text("durch Leerzeichen getrennt") })
                } else {
                    SectionTitle("Audio")
                    AudioSlot(vm, ClipTarget.Editor(back = false), e.frontAudio, color)
                    SectionTitle("Lösung – in der Reihenfolge, in der sie klingt")
                    OutlinedTextField(e.answer, { e.answer = it }, Modifier.fillMaxWidth(), singleLine = true, placeholder = { Text("z. B. A D G F oder Fmin Gmin") })
                    ChordPreview(vm, color, canGenerate = e.frontAudio == null)
                    Text(
                        "Türkis = als Akkord oder Intervall erkannt (Fmin und Fm gelten als gleich). Grau = wird als Text verglichen.",
                        Modifier.padding(top = 6.dp),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    LabelField(vm)
                    SectionTitle("Titel (nur in der Übersicht)")
                    OutlinedTextField(e.front, { e.front = it }, Modifier.fillMaxWidth(), singleLine = true)
                }
                val cards = e.kind == DeckKind.CARDS
                SectionTitle(if (cards) "Bemerkungen (auf der Rückseite)" else "Bemerkungen (nur hier sichtbar)")
                OutlinedTextField(
                    e.notes,
                    { e.notes = it },
                    Modifier.fillMaxWidth(),
                    minLines = 2,
                    placeholder = { Text(if (cards) "z. B. welche Umkehrung erklingt" else "z. B. welche Umkehrung erklingt – beim Training versteckt") },
                )
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

/** Chips for the typed chords, the key field and "turn into audio". */
@Composable
private fun ChordPreview(vm: AppViewModel, color: Color, canGenerate: Boolean) {
    val e = vm.editor ?: return
    val tokens = vm.editorTokens()
    if (tokens.isEmpty()) return
    val key = vm.editorKey()
    val labelled = e.label.isNotBlank()
    Spacer(Modifier.height(8.dp))
    // With a text of its own the card shows that instead of the chips; they stay visible here, faded.
    Box(Modifier.alpha(if (labelled) 0.4f else 1f)) { ChordChips(vm, tokens, key, deckId = e.deckId) }
    Row(Modifier.padding(top = 6.dp), verticalAlignment = Alignment.CenterVertically) {
        if (!labelled) {
            OutlinedTextField(
                e.key,
                { e.key = it },
                Modifier.weight(1f),
                singleLine = true,
                label = { Text("Tonart für die Stufen") },
                placeholder = { Text(vm.keyName(key)?.let { "$it (erkannt)" } ?: "z. B. A oder fis-Moll") },
            )
        } else {
            Spacer(Modifier.weight(1f))
        }
        if (canGenerate && tokens.all { it.chord != null }) {
            Spacer(Modifier.width(8.dp))
            SmallButton(if (e.frontAudio == null) "Als Audio erzeugen" else "Audio neu erzeugen", vm::generateChordAudio, color = color)
        }
    }
}

/** Free text that the card shows instead of chords and numerals ("Son Clave 3-2"). */
@Composable
private fun LabelField(vm: AppViewModel) {
    val e = vm.editor ?: return
    OutlinedTextField(
        e.label,
        { e.label = it },
        Modifier.fillMaxWidth().padding(top = 10.dp),
        singleLine = true,
        label = { Text("Eigener Text statt Akkorden und Stufen") },
        placeholder = { Text("z. B. Son Clave 3-2 – leer lassen für die Akkorde") },
    )
    if (e.label.isNotBlank()) {
        Spacer(Modifier.height(8.dp))
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text("Auf der Karte: ", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Chip(e.label.trim(), Palette.chords)
        }
    }
}

/** An audio attachment: play, cut & effects, replace, remove - or record / choose a file. */
@OptIn(ExperimentalLayoutApi::class)
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
        FlowRow(Modifier.padding(top = 4.dp)) {
            SmallButton("Schneiden & Effekte", { vm.openAudioEditor(target) }, color = color)
            SmallButton("Ersetzen", { vm.pickAudio(target) }, color = MaterialTheme.colorScheme.onSurfaceVariant)
            SmallButton("Aufnehmen", { vm.startRecording(target) }, color = MaterialTheme.colorScheme.onSurfaceVariant)
            DownloadButton(vm, clip)
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

/**
 * Saves the clip. A MIDI file or an edited clip can go out as heard (WAV with all effects) or as
 * the original file, so a small menu asks; otherwise the file is saved straight away.
 */
@Composable
fun DownloadButton(vm: AppViewModel, clip: Clip) {
    var open by remember { mutableStateOf(false) }
    val choice = clip.isMidi || !clip.edit.isNeutral
    Box {
        SmallButton("Herunterladen", { if (choice) open = true else vm.downloadClip(clip, asHeard = false) }, color = MaterialTheme.colorScheme.onSurfaceVariant)
        DropdownMenu(open, { open = false }) {
            DropdownMenuItem(
                text = { Text("Als WAV, so wie es klingt") },
                onClick = {
                    open = false
                    vm.downloadClip(clip, asHeard = true)
                },
            )
            DropdownMenuItem(
                text = { Text(if (clip.isMidi) "Original-MIDI (.mid)" else "Originaldatei, unbearbeitet") },
                onClick = {
                    open = false
                    vm.downloadClip(clip, asHeard = false)
                },
            )
        }
    }
}

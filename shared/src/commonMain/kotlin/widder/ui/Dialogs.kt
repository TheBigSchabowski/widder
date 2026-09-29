package widder.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Checkbox
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Slider
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import widder.core.DeckKind
import widder.music.AnswerParser
import kotlin.math.roundToInt

@Composable
fun Dialogs(vm: AppViewModel) {
    when (val d = vm.deckDialog) {
        is DeckDialog.New -> NewDeckDialog(vm, d.kind)
        is DeckDialog.Options -> DeckOptionsDialog(vm, d.deckId)
        null -> Unit
    }
    vm.pendingImport?.let { ImportTargetDialog(vm, it.size) }
    vm.importPreview?.let { ImportPreviewDialog(vm, it) }
    vm.confirm?.let { c ->
        AlertDialog(
            onDismissRequest = { vm.confirm = null },
            title = { Text(c.title) },
            text = { Text(c.text) },
            confirmButton = {
                TextButton({
                    vm.confirm = null
                    c.run()
                }) { Text(c.action, color = MaterialTheme.colorScheme.error) }
            },
            dismissButton = { TextButton({ vm.confirm = null }) { Text("Abbrechen") } },
        )
    }
}

@Composable
private fun NewDeckDialog(vm: AppViewModel, kind: DeckKind) {
    var name by remember { mutableStateOf("") }
    AlertDialog(
        onDismissRequest = { vm.deckDialog = null },
        title = { Text(if (kind == DeckKind.CARDS) "Neuer Kartenstapel" else "Neues Akkord-Training") },
        text = {
            OutlinedTextField(name, { name = it }, singleLine = true, placeholder = { Text("Name") })
        },
        confirmButton = {
            TextButton({
                vm.deckDialog = null
                val deck = vm.createDeck(name, kind)
                vm.open(Screen.Browse(deck.id))
            }) { Text("Anlegen") }
        },
        dismissButton = { TextButton({ vm.deckDialog = null }) { Text("Abbrechen") } },
    )
}

@Composable
private fun DeckOptionsDialog(vm: AppViewModel, deckId: String) {
    val deck = vm.deck(deckId) ?: return
    var name by remember { mutableStateOf(deck.name) }
    var newPerDay by remember { mutableStateOf(deck.newPerDay.toString()) }
    var reviewsPerDay by remember { mutableStateOf(deck.reviewsPerDay.toString()) }
    var distractors by remember { mutableStateOf(deck.distractors) }
    var key by remember { mutableStateOf(deck.key ?: "") }
    AlertDialog(
        onDismissRequest = { vm.deckDialog = null },
        title = { Text("Stapel-Optionen") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                OutlinedTextField(name, { name = it }, label = { Text("Name") }, singleLine = true)
                Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    OutlinedTextField(newPerDay, { newPerDay = it.filter(Char::isDigit).take(4) }, Modifier.weight(1f), label = { Text("Neue / Tag") }, singleLine = true)
                    OutlinedTextField(reviewsPerDay, { reviewsPerDay = it.filter(Char::isDigit).take(5) }, Modifier.weight(1f), label = { Text("Wiederh. / Tag") }, singleLine = true)
                }
                OutlinedTextField(
                    key,
                    { key = it },
                    label = { Text("Tonart für die Stufen") },
                    placeholder = { Text("leer = aus den Akkorden erkennen") },
                    singleLine = true,
                )
                if (deck.kind == DeckKind.CHORDS) {
                    Text("Falsche Zusatz-Akkorde im Vorrat: $distractors", style = MaterialTheme.typography.bodyMedium)
                    Slider(distractors.toFloat(), { distractors = it.roundToInt() }, valueRange = 0f..4f, steps = 3)
                    Text(
                        "Macht es schwerer: Zwischen den richtigen liegen ähnliche Akkorde (z. B. Moll statt Dur).",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                if (vm.folders != null) FolderSection(vm, deck)
                TextButton({
                    vm.deckDialog = null
                    vm.confirm = Confirm("„${deck.name}“ löschen?", "Alle ${vm.cardsIn(deck.id).size} Karten und ihre Audiodateien werden entfernt.", "Löschen") {
                        vm.deleteDeck(deck.id)
                    }
                }) { Text("Stapel löschen", color = MaterialTheme.colorScheme.error) }
            }
        },
        confirmButton = {
            TextButton({
                vm.deckDialog = null
                vm.updateDeck(
                    deck.copy(
                        name = name.trim().ifEmpty { deck.name },
                        newPerDay = newPerDay.toIntOrNull() ?: deck.newPerDay,
                        reviewsPerDay = reviewsPerDay.toIntOrNull() ?: deck.reviewsPerDay,
                        distractors = distractors,
                        key = key.trim().ifEmpty { null },
                    ),
                )
            }) { Text("Speichern") }
        },
        dismissButton = { TextButton({ vm.deckDialog = null }) { Text("Abbrechen") } },
    )
}

/** Which folder the deck mirrors, and what can be done with it. */
@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun FolderSection(vm: AppViewModel, deck: widder.core.Deck) {
    Column {
        Text("Ordner", style = MaterialTheme.typography.titleSmall)
        val label = vm.folderLabel(deck)
        Text(
            label ?: "Nicht verbunden. Mit einem Ordner verbunden wird jede Audio-/MIDI-Datei darin eine Karte; " +
                "ersetzte Dateien ersetzen das Audio, gelöschte entfernen die Karte.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        FlowRow {
            if (label != null) {
                TextButton({ vm.openFolder(deck.id) }) { Text("Öffnen") }
                TextButton({ vm.syncFolder(deck.id, quiet = false) }) { Text("Neu einlesen") }
                TextButton({ vm.chooseFolder(deck.id) }) { Text("Anderer Ordner") }
                TextButton({ vm.unlinkFolder(deck.id) }) { Text("Trennen", color = MaterialTheme.colorScheme.error) }
            } else {
                TextButton({ vm.openFolder(deck.id) }) { Text("Ordner anlegen / verbinden") }
            }
        }
    }
}

@Composable
private fun ImportTargetDialog(vm: AppViewModel, count: Int) {
    AlertDialog(
        onDismissRequest = { vm.back() },
        title = { Text(if (count == 1) "1 Datei importieren" else "$count Dateien importieren") },
        text = {
            LazyColumn(Modifier.heightIn(max = 420.dp)) {
                for (kind in DeckKind.entries) {
                    item {
                        Text(
                            if (kind == DeckKind.CARDS) "Als Karteikarten" else "Als Akkord-Training",
                            Modifier.padding(top = 10.dp, bottom = 4.dp),
                            fontWeight = FontWeight.SemiBold,
                            color = kindColor(kind),
                        )
                    }
                    items(vm.decks(kind), key = { it.id }) { deck ->
                        Text(
                            deck.name,
                            Modifier.fillMaxWidth().clickable { vm.chooseImportTarget(deck.id) }.padding(vertical = 10.dp, horizontal = 6.dp),
                        )
                    }
                    item {
                        Text(
                            "+ Neuer Stapel",
                            Modifier.fillMaxWidth().clickable { vm.chooseImportTarget(null, kind) }.padding(vertical = 10.dp, horizontal = 6.dp),
                            color = kindColor(kind),
                        )
                    }
                }
            }
        },
        confirmButton = {},
        dismissButton = { TextButton({ vm.back() }) { Text("Abbrechen") } },
    )
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun ImportPreviewDialog(vm: AppViewModel, preview: ImportPreview) {
    val german = vm.settings.germanNotation
    AlertDialog(
        onDismissRequest = { vm.back() },
        title = { Text("Übungen prüfen") },
        text = {
            Column {
                Text(
                    "So hat Widder die Dateinamen gelesen. Stimmt etwas nicht, korrigiere es hier.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Spacer(Modifier.height(8.dp))
                LazyColumn(Modifier.heightIn(max = 460.dp)) {
                    items(preview.items) { item ->
                        Column(Modifier.padding(vertical = 8.dp)) {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Checkbox(item.include, { item.include = it })
                                Text(item.file.name, Modifier.weight(1f), maxLines = 1, overflow = TextOverflow.Ellipsis, fontWeight = FontWeight.Medium)
                            }
                            OutlinedTextField(item.answer, { item.answer = it }, Modifier.fillMaxWidth(), singleLine = true, enabled = item.include)
                            Spacer(Modifier.height(4.dp))
                            FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                                val tokens = AnswerParser.parseText(item.answer, german)
                                if (tokens.isEmpty()) Chip("Keine Lösung erkannt", Palette.again)
                                tokens.forEach { TokenChip(it) }
                                if (item.fromMidi) Chip("aus den MIDI-Noten erkannt", Palette.hard)
                            }
                            HorizontalDivider(Modifier.padding(top = 10.dp))
                        }
                    }
                }
            }
        },
        confirmButton = {
            val n = preview.items.count { it.include && it.answer.isNotBlank() }
            TextButton(vm::confirmImportPreview, enabled = n > 0) { Text(if (n == 1) "1 Übung anlegen" else "$n Übungen anlegen") }
        },
        dismissButton = { TextButton({ vm.back() }) { Text("Abbrechen") } },
    )
}

/** Short notices at the bottom, and a veil while something loads. */
@Composable
fun Notices(vm: AppViewModel) {
    vm.busy?.let { text ->
        Box(Modifier.fillMaxSize().background(Color.Black.copy(alpha = 0.3f)).clickable(enabled = false) {}, contentAlignment = Alignment.Center) {
            Surface(shape = RoundedCornerShape(14.dp), color = MaterialTheme.colorScheme.surface) {
                Text(text, Modifier.padding(horizontal = 22.dp, vertical = 16.dp))
            }
        }
    }
    vm.message?.let { text ->
        Box(Modifier.fillMaxSize().padding(bottom = 28.dp, start = 16.dp, end = 16.dp), contentAlignment = Alignment.BottomCenter) {
            Surface(
                Modifier.clickable { vm.message = null },
                shape = RoundedCornerShape(14.dp),
                color = MaterialTheme.colorScheme.inverseSurface,
                shadowElevation = 6.dp,
            ) {
                Text(text, Modifier.padding(horizontal = 18.dp, vertical = 12.dp), color = MaterialTheme.colorScheme.inverseOnSurface)
            }
        }
    }
}

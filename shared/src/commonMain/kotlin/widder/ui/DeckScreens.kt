package widder.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import widder.core.Card
import widder.core.Deck
import widder.core.DeckKind
import widder.core.Fsrs
import widder.core.Phase
import widder.core.currentTimeMillis
import widder.music.Token

fun kindColor(kind: DeckKind): Color = if (kind == DeckKind.CARDS) Palette.cards else Palette.chords

@Composable
fun DecksScreen(vm: AppViewModel, kind: DeckKind) {
    val color = kindColor(kind)
    val decks = vm.decks(kind)
    Column(Modifier.fillMaxSize()) {
        TopBar(if (kind == DeckKind.CARDS) "Karteikarten" else "Akkord-Training", vm::back) {
            SmallButton("Importieren", { vm.pickImport(null) }, color = color)
            SmallButton("+ Stapel", { vm.deckDialog = DeckDialog.New(kind) }, color = color)
        }
        Centered {
            LazyColumn(contentPadding = PaddingValues(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                if (decks.isEmpty()) {
                    item {
                        Panel {
                            Text("Noch keine Stapel", fontWeight = FontWeight.SemiBold)
                            Spacer(Modifier.height(4.dp))
                            Text(
                                if (kind == DeckKind.CARDS) "Lege einen Stapel an oder importiere Audio- und MIDI-Dateien – aus jeder Datei wird eine Karte."
                                else "Lege ein Training an und füge Dateien hinzu. Der Dateiname ist die Lösung: „A D G F.wav“ oder „Fmin Gmin.mid“.",
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                    }
                }
                items(decks, key = { it.id }) { deck -> DeckRow(vm, deck, color) }
                if (kind == DeckKind.CHORDS && !vm.hasStarterKit()) {
                    item {
                        Panel {
                            Text("Startkit: A-Akkorde", fontWeight = FontWeight.SemiBold)
                            Text(
                                StarterKits.A_CHORDS.joinToString(" · "),
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                            SmallButton("Hinzufügen", vm::addStarterKit, color = color)
                        }
                    }
                }
                if (kind == DeckKind.CHORDS) {
                    item {
                        Text(
                            "Der Dateiname ist die Lösung: „A D G F.wav“ wird zu A · D · G · F, „Fmin Gmin.mid“ zu Fmin · Gmin. " +
                                "Steht im Namen einer MIDI-Datei kein Akkord, erkennt Widder die Akkorde aus den Noten.",
                            Modifier.padding(4.dp),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun DeckRow(vm: AppViewModel, deck: Deck, color: Color) {
    val counts = vm.counts(deck)
    val total = vm.cardsIn(deck.id).size
    Panel {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text(deck.name, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold, maxLines = 2, overflow = TextOverflow.Ellipsis)
                Spacer(Modifier.height(4.dp))
                Text(
                    if (deck.kind == DeckKind.CARDS) "$total Karten" else "$total Übungen",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Spacer(Modifier.height(6.dp))
                CountRow(counts.new, counts.learning, counts.review)
            }
            IconButton(Glyph.MORE, "Stapel-Optionen", { vm.deckDialog = DeckDialog.Options(deck.id) })
        }
        Spacer(Modifier.height(12.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            Button(
                onClick = { if (deck.kind == DeckKind.CARDS) vm.startReview(deck.id) else vm.startTraining(deck.id) },
                enabled = counts.total > 0,
                colors = ButtonDefaults.buttonColors(containerColor = color, contentColor = Color.White),
            ) { Text(if (deck.kind == DeckKind.CARDS) "Lernen" else "Trainieren", fontWeight = FontWeight.SemiBold) }
            OutlinedButton(onClick = { vm.open(Screen.Browse(deck.id)) }) {
                Text(if (deck.kind == DeckKind.CARDS) "Karten" else "Übungen")
            }
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun BrowseScreen(vm: AppViewModel, deckId: String) {
    val deck = vm.deck(deckId) ?: return
    val color = kindColor(deck.kind)
    var query by remember { mutableStateOf("") }
    val cards = vm.cardsIn(deckId).filter { card ->
        query.isBlank() || listOf(card.front, card.back, card.answer.joinToString(" "), card.frontAudio?.name ?: "", card.tags.joinToString(" "))
            .any { it.contains(query.trim(), ignoreCase = true) }
    }.sortedBy { it.created }
    val counts = vm.counts(deck)
    Column(Modifier.fillMaxSize()) {
        TopBar(deck.name, vm::back, subtitle = if (deck.kind == DeckKind.CARDS) "${vm.cardsIn(deckId).size} Karten" else "${vm.cardsIn(deckId).size} Übungen") {
            SmallButton("Importieren", { vm.pickImport(deckId) }, color = color)
            SmallButton("+ Neu", { vm.newCard(deckId) }, color = color)
        }
        Centered {
            Column {
                Row(Modifier.padding(horizontal = 16.dp), verticalAlignment = Alignment.CenterVertically) {
                    OutlinedTextField(
                        query,
                        { query = it },
                        Modifier.weight(1f),
                        placeholder = { Text("Suchen") },
                        singleLine = true,
                    )
                    Spacer(Modifier.width(12.dp))
                    Button(
                        onClick = { if (deck.kind == DeckKind.CARDS) vm.startReview(deckId) else vm.startTraining(deckId) },
                        enabled = counts.total > 0,
                        colors = ButtonDefaults.buttonColors(containerColor = color, contentColor = Color.White),
                    ) { Text(if (deck.kind == DeckKind.CARDS) "Lernen" else "Trainieren") }
                }
                LazyColumn(contentPadding = PaddingValues(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    if (cards.isEmpty()) {
                        item {
                            Text(
                                if (query.isBlank()) "Noch leer. Lege eine Karte an oder importiere Dateien." else "Nichts gefunden.",
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                    }
                    items(cards, key = { it.id }) { card -> CardRow(vm, card, deck, color) }
                }
            }
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun CardRow(vm: AppViewModel, card: Card, deck: Deck, color: Color) {
    val state = vm.progress.cards[card.id]
    val suspended = state?.suspended == true
    Surface(
        Modifier.fillMaxWidth().clip(RoundedCornerShape(14.dp)).clickable { vm.editCard(card) },
        shape = RoundedCornerShape(14.dp),
        color = MaterialTheme.colorScheme.surface,
    ) {
        Row(Modifier.padding(12.dp), verticalAlignment = Alignment.CenterVertically) {
            if (card.frontAudio != null) {
                PlayButton(vm.playingKey == "browse-${card.id}", { vm.play(card.frontAudio, "browse-${card.id}") }, color, 40.dp)
                Spacer(Modifier.width(12.dp))
            }
            Column(Modifier.weight(1f)) {
                val title = card.front.ifBlank { card.frontAudio?.name ?: "(leer)" }
                Text(title, maxLines = 2, overflow = TextOverflow.Ellipsis, fontWeight = FontWeight.Medium, color = if (suspended) MaterialTheme.colorScheme.onSurfaceVariant else MaterialTheme.colorScheme.onSurface)
                if (deck.kind == DeckKind.CHORDS) {
                    Spacer(Modifier.height(6.dp))
                    FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                        if (card.answer.isEmpty()) Chip("Lösung fehlt", Palette.again)
                        card.answer.forEach { t -> TokenChip(Token.of(t, vm.settings.germanNotation)) }
                    }
                } else if (card.back.isNotBlank()) {
                    Text(
                        card.back.replace('\n', ' '),
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
            Spacer(Modifier.width(8.dp))
            Text(
                when {
                    suspended -> "ausgesetzt"
                    state == null || state.phase == Phase.NEW -> "neu"
                    state.phase != Phase.REVIEW -> "lernt"
                    else -> "in " + Fsrs.formatInterval((state.due - currentTimeMillis()).coerceAtLeast(0))
                },
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

/** An answer token: teal when it is a chord Widder understands, grey when it is plain text. */
@Composable
fun TokenChip(token: Token) {
    Chip(token.text, if (token.chord != null) Palette.chords else MaterialTheme.colorScheme.onSurfaceVariant)
}

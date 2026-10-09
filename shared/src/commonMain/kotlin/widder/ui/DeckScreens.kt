package widder.ui

import androidx.compose.foundation.border
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
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Checkbox
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
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

/** The two paths by their names. */
fun kindName(kind: DeckKind): String = if (kind == DeckKind.CARDS) "Flash Cards" else "Multiple Choice"

@Composable
fun DecksScreen(vm: AppViewModel, kind: DeckKind) {
    val color = kindColor(kind)
    val decks = vm.decks(kind)
    val list = rememberLazyListState()
    // A deck picked with prefix + number comes into view.
    LaunchedEffect(vm.selectedDeckId, kind) {
        val i = decks.indexOfFirst { it.id == vm.selectedDeckId }
        if (i >= 0) list.animateScrollToItem(i)
    }
    Column(Modifier.fillMaxSize()) {
        TopBar(kindName(kind), vm::back) {
            if (vm.marking) {
                val all = decks.isNotEmpty() && decks.all { it.id in vm.marked }
                SmallButton(if (all) "Keine" else "Alle", { vm.markAll(kind, !all) }, color = color)
                SmallButton(
                    if (vm.marked.isEmpty()) "Löschen" else "Löschen (${vm.marked.size})",
                    vm::deleteMarked,
                    enabled = vm.marked.isNotEmpty(),
                    color = MaterialTheme.colorScheme.error,
                )
                SmallButton("Fertig", vm::stopMarking, color = color)
            } else {
                if (decks.isNotEmpty()) SmallButton("Markieren", vm::startMarking, color = color)
                SmallButton("Importieren", { vm.pickImport(null) }, color = color)
                SmallButton("+ Stapel", { vm.deckDialog = DeckDialog.New(kind) }, color = color)
            }
        }
        Centered {
            LazyColumn(state = list, contentPadding = PaddingValues(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                if (decks.isEmpty()) {
                    item {
                        Panel {
                            Text("Noch keine Stapel", fontWeight = FontWeight.SemiBold)
                            Spacer(Modifier.height(4.dp))
                            Text(
                                if (kind == DeckKind.CARDS) "Lege einen Stapel an oder importiere Audio- und MIDI-Dateien – aus jeder Datei wird eine Karte."
                                else "Lege einen Stapel an und füge Dateien hinzu. Der Dateiname ist die Lösung: „A D G F.wav“ oder „Fmin Gmin.mid“.",
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                    }
                }
                items(decks, key = { it.id }) { deck -> DeckRow(vm, deck, color) }
                if (!vm.marking) items(vm.missingKits(kind), key = { "kit-" + it.id }) { kit ->
                    Panel {
                        Text(kit.title, fontWeight = FontWeight.SemiBold)
                        Text(
                            kit.summary,
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                        SmallButton("Hinzufügen", { vm.addStarterKit(kit.id) }, color = color)
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

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun DeckRow(vm: AppViewModel, deck: Deck, color: Color) {
    val counts = vm.counts(deck)
    val total = vm.cardsIn(deck.id).size
    val tags = vm.tagsIn(deck.id)
    val marked = deck.id in vm.marked
    val picked = deck.id == vm.selectedDeckId
    // With a keyboard: the number for prefix + digit.
    val number = vm.deckNumber(deck.id)?.takeIf { vm.platform.keyboard && vm.settings.prefix != null }
    Panel(
        when {
            marked -> Modifier.border(2.dp, MaterialTheme.colorScheme.error, RoundedCornerShape(18.dp))
            picked -> Modifier.border(2.dp, color, RoundedCornerShape(18.dp))
            else -> Modifier
        }.then(if (vm.marking) Modifier.clip(RoundedCornerShape(18.dp)).clickable { vm.toggleMark(deck.id) } else Modifier),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            if (vm.marking) {
                Checkbox(marked, { vm.toggleMark(deck.id) })
                Spacer(Modifier.width(4.dp))
            }
            if (number != null) {
                KeyHint("$number", Modifier.padding(end = 10.dp))
            }
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
                if (tags.isNotEmpty()) {
                    // Tapping a tag learns only its cards; "Alle" lifts the filter.
                    FlowRow(Modifier.padding(top = 8.dp), horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                        Chip("Alle", color, filled = deck.studyTags.isEmpty(), modifier = Modifier.clickable { vm.setStudyTags(deck.id, emptyList()) })
                        for (tag in tags) {
                            val on = tag in deck.studyTags
                            Chip(tag, color, filled = on, modifier = Modifier.clickable { vm.setStudyTags(deck.id, if (on) deck.studyTags - tag else deck.studyTags + tag) })
                        }
                    }
                }
                vm.folderLabel(deck)?.let {
                    Text(
                        "Ordner: $it",
                        Modifier.padding(top = 4.dp),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
            }
            if (!vm.marking) IconButton(Glyph.MORE, "Stapel-Optionen", { vm.deckDialog = DeckDialog.Options(deck.id) })
        }
        if (vm.marking) return@Panel
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
            if (vm.folders != null) {
                OutlinedButton(onClick = { vm.openFolder(deck.id) }) { Text("Ordner") }
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
    // Files may have changed in the folder since the deck was last looked at.
    androidx.compose.runtime.LaunchedEffect(deckId) { vm.syncFolder(deckId) }
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
                val label = card.label
                if (label != null) {
                    Spacer(Modifier.height(6.dp))
                    Chip(label, Palette.chords)
                } else if (deck.kind == DeckKind.CHORDS && card.answer.isEmpty()) {
                    Spacer(Modifier.height(6.dp))
                    Chip("Lösung fehlt", Palette.again)
                } else if (card.answer.isNotEmpty()) {
                    Spacer(Modifier.height(6.dp))
                    ChordChips(vm, vm.tokensOf(card), vm.keyFor(card), showKey = false, deckId = card.deckId)
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

/** An answer token: teal when it is a chord or interval Widder understands, grey when it is plain text. */
@Composable
fun TokenChip(token: Token, label: String = token.text, large: Boolean = false) {
    Chip(label, if (token.chord != null || token.interval != null) Palette.chords else MaterialTheme.colorScheme.onSurfaceVariant, large = large)
}

/** The chords of a card with their roman numerals, and the key they are counted in. */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun ChordChips(vm: AppViewModel, tokens: List<Token>, key: widder.music.Key?, showKey: Boolean = true, large: Boolean = false, deckId: String? = null) {
    FlowRow(
        horizontalArrangement = Arrangement.spacedBy(if (large) 10.dp else 6.dp, if (large) Alignment.CenterHorizontally else Alignment.Start),
        verticalArrangement = Arrangement.spacedBy(4.dp),
        itemVerticalAlignment = Alignment.CenterVertically,
    ) {
        if (showKey && key != null && vm.numeralMode(deckId) != widder.core.NumeralMode.OFF && tokens.any { it.chord != null }) {
            Text(
                vm.keyName(key) + ":",
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        tokens.forEach { TokenChip(it, vm.chordLabel(it, key, deckId), large) }
    }
}

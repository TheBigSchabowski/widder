package widder.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import widder.core.Clip
import widder.core.Fsrs
import widder.core.Grade
import widder.core.Queue
import widder.core.currentTimeMillis
import widder.core.formatTime

fun gradeColor(grade: Grade): Color = when (grade) {
    Grade.AGAIN -> Palette.again
    Grade.HARD -> Palette.hard
    Grade.GOOD -> Palette.good
    Grade.EASY -> Palette.easy
}

@Composable
fun ReviewScreen(vm: AppViewModel) {
    val session = vm.review ?: return
    val deck = vm.deck(session.deckId) ?: return
    val card = session.card
    val counts = Queue(deck, vm.collection.cards, vm.progress).counts
    Column(Modifier.fillMaxSize()) {
        TopBar(deck.name, vm::back) {
            CountRow(counts.new, counts.learning, counts.review)
            Spacer(Modifier.width(8.dp))
        }
        if (card == null) {
            Finished(vm, session.answered, session.nextDue, Palette.cards)
            return@Column
        }
        Box(Modifier.weight(1f)) {
            Centered {
                Column(Modifier.verticalScroll(rememberScrollState()).padding(16.dp)) {
                    Panel(Modifier.heightIn(min = 220.dp), padding = 24.dp) {
                        Side(vm, card.front, card.frontAudio, ClipTarget.StoredCard(card.id, back = false), "review-front")
                        if (session.showAnswer) {
                            HorizontalDivider(Modifier.padding(vertical = 22.dp))
                            Side(vm, card.back, card.backAudio, ClipTarget.StoredCard(card.id, back = true), "review-back")
                            val label = card.label
                            if (label != null || card.answer.isNotEmpty()) {
                                Spacer(Modifier.height(16.dp))
                                Box(Modifier.fillMaxWidth(), contentAlignment = Alignment.Center) {
                                    if (label != null) {
                                        Chip(label, Palette.chords, large = true)
                                    } else {
                                        ChordChips(vm, vm.tokensOf(card), vm.keyFor(card), large = true, deckId = card.deckId)
                                    }
                                }
                            }
                            val midi = vm.midiClip(card)
                            if (midi != null) {
                                Spacer(Modifier.height(12.dp))
                                MidiKeyboard(vm, midi, "review-keys")
                            }
                            if (card.notes.isNotBlank()) {
                                Spacer(Modifier.height(16.dp))
                                Text(
                                    card.notes,
                                    Modifier.fillMaxWidth(),
                                    style = MaterialTheme.typography.bodyMedium,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    textAlign = TextAlign.Center,
                                )
                            }
                        }
                    }
                    Row(Modifier.padding(top = 8.dp)) {
                        if (session.undo != null) SmallButton("Rückgängig", vm::undoReview, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        SmallButton("Bearbeiten", { vm.editCard(card) }, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        SmallButton("Aussetzen", vm::suspendReviewCard, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        SmallButton("Mischen", vm::shuffle, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }
            }
        }
        Centered(Modifier.height(96.dp)) {
            if (!session.showAnswer) {
                Button(
                    vm::showAnswer,
                    Modifier.fillMaxWidth().padding(16.dp).height(56.dp),
                    shape = RoundedCornerShape(16.dp),
                ) { Text("Antwort zeigen", fontWeight = FontWeight.SemiBold) }
            } else {
                val preview = Fsrs.preview(vm.progress.cards[card.id])
                Row(Modifier.fillMaxWidth().padding(12.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    for (grade in Grade.entries) {
                        GradeButton(grade, Fsrs.formatInterval(preview[grade] ?: 0), Modifier.weight(1f), highlighted = false) { vm.answer(grade) }
                    }
                }
            }
        }
    }
}

@Composable
private fun Side(vm: AppViewModel, text: String, clip: Clip?, target: ClipTarget, key: String) {
    if (text.isNotBlank()) {
        Text(text, Modifier.fillMaxWidth(), style = MaterialTheme.typography.headlineSmall, textAlign = TextAlign.Center)
    }
    if (clip != null) {
        Spacer(Modifier.height(16.dp))
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.Center, verticalAlignment = Alignment.CenterVertically) {
            PlayButton(vm.playingKey == key, { vm.play(clip, key) }, Palette.cards, 56.dp)
            Spacer(Modifier.width(12.dp))
            Column {
                Text(if (clip.isMidi) "MIDI" else "Audio", fontWeight = FontWeight.Medium)
                SmallButton("Schneiden & Effekte", { vm.openAudioEditor(target) }, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
    }
}

@Composable
fun GradeButton(grade: Grade, interval: String?, modifier: Modifier, highlighted: Boolean, onClick: () -> Unit) {
    val color = gradeColor(grade)
    Button(
        onClick,
        modifier.height(64.dp),
        shape = RoundedCornerShape(14.dp),
        colors = ButtonDefaults.buttonColors(
            containerColor = if (highlighted) color else color.copy(alpha = 0.16f),
            contentColor = if (highlighted) Color.White else color,
        ),
        contentPadding = androidx.compose.foundation.layout.PaddingValues(4.dp),
    ) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Text(grade.label, fontWeight = FontWeight.SemiBold, maxLines = 1)
            if (interval != null) Text(interval, style = MaterialTheme.typography.labelSmall, maxLines = 1)
        }
    }
}

@Composable
fun Finished(vm: AppViewModel, answered: Int, nextDue: Long?, color: Color, extra: String? = null) {
    Centered {
        Column(Modifier.padding(24.dp), horizontalAlignment = Alignment.CenterHorizontally) {
            Spacer(Modifier.height(40.dp))
            RamMark(Modifier.height(72.dp).width(72.dp))
            Spacer(Modifier.height(20.dp))
            Text("Für jetzt geschafft!", style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold)
            Spacer(Modifier.height(8.dp))
            Text(
                if (answered == 1) "1 Antwort in dieser Runde" else "$answered Antworten in dieser Runde",
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            if (extra != null) Text(extra, color = MaterialTheme.colorScheme.onSurfaceVariant)
            if (nextDue != null) {
                val minutes = ((nextDue - currentTimeMillis()) / 60_000).coerceAtLeast(1)
                Text(
                    "Die nächste Lernkarte ist um ${formatTime(nextDue, "HH:mm")} dran (in $minutes Min).",
                    Modifier.padding(top = 8.dp),
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    textAlign = TextAlign.Center,
                )
            }
            Spacer(Modifier.height(24.dp))
            Button(vm::back, colors = ButtonDefaults.buttonColors(containerColor = color, contentColor = Color.White)) { Text("Zurück") }
        }
    }
}

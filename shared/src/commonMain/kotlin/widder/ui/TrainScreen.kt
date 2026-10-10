package widder.ui

import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.scale
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.boundsInRoot
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.positionInRoot
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import widder.core.ChordRound
import widder.core.Grade
import widder.core.Tile
import kotlin.math.roundToInt

/** A tile being dragged: where the pointer is, and where the slots are (all in root coordinates). */
private class DragState {
    var tile by mutableStateOf<Tile?>(null)
    var pointer by mutableStateOf(Offset.Zero)
    var grab = Offset.Zero
    var tileSize by mutableStateOf(androidx.compose.ui.geometry.Size.Zero)
    val slots = mutableStateMapOf<Int, Rect>()

    /** The slot under the pointer; a little generous so a near miss still lands. */
    fun slotAt(p: Offset): Int? = slots.entries.firstOrNull { it.value.inflate(10f).contains(p) }?.key
}

@Composable
fun TrainScreen(vm: AppViewModel) {
    val session = vm.training ?: return
    val deck = vm.deck(session.deckId) ?: return
    val card = session.card
    Column(Modifier.fillMaxSize()) {
        TopBar(deck.name, vm::back, subtitle = "Richtig ${session.correct} von ${session.answered}") {
            if (session.combo >= 2) Chip("Serie ×${session.combo}", Palette.hard, filled = true)
            Chip("+${session.gained} XP", Palette.chords)
            Spacer(Modifier.width(8.dp))
        }
        val round = session.round
        if (card == null || round == null) {
            Finished(
                vm,
                session.answered,
                session.nextDue,
                Palette.chords,
                extra = if (session.answered > 0) "${session.correct} richtig · +${session.gained} XP" else null,
            )
            return@Column
        }
        Round(vm, session, round)
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun Round(vm: AppViewModel, session: TrainSession, round: ChordRound) {
    // ChordRound is not observable: reading the move counter here redraws the round after every move.
    // Children get what they show as parameters, so they redraw too.
    check(session.version >= 0)
    val drag = remember(round) { DragState() }
    // A card with a text of its own ("Son Clave") has no key: no numerals, the text comes after checking.
    val cardLabel = session.card?.label
    val key = session.card?.takeIf { cardLabel == null }?.let { vm.keyFor(it) }
    val mode = vm.numeralMode(session.deckId)
    // Intervals are no chords: the wording follows what is asked.
    val intervals = round.answer.all { it.interval != null }
    val label: (Tile) -> Pair<String, String?> = { tile ->
        val numeral = vm.numeral(tile.token, key, session.deckId)
        when {
            numeral == null -> tile.token.text to null
            mode == widder.core.NumeralMode.ONLY -> numeral to null
            else -> tile.token.text to numeral
        }
    }
    var origin by remember { mutableStateOf(Offset.Zero) }
    val playing = vm.playingKey == "train"
    Box(Modifier.fillMaxSize().onGloballyPositioned { origin = it.positionInRoot() }) {
        Centered {
            Column(
                Modifier.verticalScroll(rememberScrollState(), enabled = drag.tile == null).padding(16.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                Spacer(Modifier.height(8.dp))
                ListenButton(playing) { vm.replay() }
                Spacer(Modifier.height(10.dp))
                Row(verticalAlignment = Alignment.CenterVertically) {
                    SmallButton("Nochmal hören", vm::replay, color = Palette.chords)
                    SmallButton("Audio bearbeiten", {
                        session.card?.let { vm.openAudioEditor(ClipTarget.StoredCard(it.id, back = false)) }
                    }, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    SmallButton("Mischen", vm::shuffle, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                Spacer(Modifier.height(14.dp))
                Text(
                    when {
                        round.checked -> resultText(round)
                        intervals && round.answer.size == 1 -> "Welches Intervall ist das? Zieh es ins Feld"
                        intervals -> "Zieh die Intervalle in die Reihenfolge, in der du sie hörst"
                        round.answer.size == 1 -> "Welcher Akkord ist das? Zieh ihn ins Feld"
                        else -> "Zieh die Akkorde in die Reihenfolge, in der du sie hörst"
                    },
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = if (round.checked) FontWeight.Bold else FontWeight.Normal,
                    color = when {
                        !round.checked -> MaterialTheme.colorScheme.onSurfaceVariant
                        round.correct -> Palette.good
                        else -> Palette.again
                    },
                    textAlign = TextAlign.Center,
                )
                if (key != null && !intervals && mode != widder.core.NumeralMode.OFF) {
                    Text(
                        "Stufen in ${vm.keyName(key)}",
                        Modifier.padding(top = 4.dp),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                Spacer(Modifier.height(16.dp))

                // The slots, in order.
                FlowRow(
                    horizontalArrangement = Arrangement.spacedBy(10.dp, Alignment.CenterHorizontally),
                    verticalArrangement = Arrangement.spacedBy(10.dp),
                ) {
                    round.slots.forEachIndexed { i, tile ->
                        Slot(i, tile, round.result?.getOrNull(i), round, drag, vm, label)
                    }
                }
                if (round.checked && cardLabel != null) {
                    Spacer(Modifier.height(10.dp))
                    Chip(cardLabel, Palette.chords, large = true)
                }
                if (round.checked && !round.correct) {
                    Spacer(Modifier.height(14.dp))
                    Text("Richtig wäre:", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    Spacer(Modifier.height(6.dp))
                    FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp, Alignment.CenterHorizontally)) {
                        round.answer.forEach { Chip(vm.chordLabel(it, key, session.deckId), Palette.good) }
                    }
                }
                val midi = session.card?.let(vm::midiClip)
                if (round.checked && midi != null) {
                    Spacer(Modifier.height(10.dp))
                    MidiKeyboard(vm, midi, "train-keys")
                }

                Spacer(Modifier.height(26.dp))
                // The pool.
                val pool = round.pool.filter { round.inPool(it) }
                Box(
                    Modifier.fillMaxWidth().heightIn(min = 90.dp).clip(RoundedCornerShape(18.dp))
                        .background(MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f)).padding(14.dp),
                    contentAlignment = Alignment.Center,
                ) {
                    if (pool.isEmpty()) {
                        Text(
                            if (round.checked) "" else if (intervals) "Alles liegt – prüfen!" else "Alle Akkorde liegen – prüfen!",
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    FlowRow(
                        horizontalArrangement = Arrangement.spacedBy(10.dp, Alignment.CenterHorizontally),
                        verticalArrangement = Arrangement.spacedBy(10.dp),
                    ) {
                        pool.forEach { tile -> DraggableTile(tile, round, drag, vm, inSlot = false, enabled = !round.checked, label = label(tile)) }
                    }
                }
                Spacer(Modifier.height(20.dp))
                Actions(vm, session, round, checked = round.checked, complete = round.complete, anyPlaced = round.slots.any { it != null })
                Spacer(Modifier.height(30.dp))
            }
        }

        // The tile under the finger follows it above everything else.
        val dragged = drag.tile
        if (dragged != null) {
            val density = LocalDensity.current
            val topLeft = drag.pointer - drag.grab - origin
            Box(
                Modifier.offset { IntOffset(topLeft.x.roundToInt(), topLeft.y.roundToInt()) }
                    .size(with(density) { drag.tileSize.width.toDp() }, with(density) { drag.tileSize.height.toDp() })
                    .scale(1.08f),
            ) {
                TileFace(label(dragged), Palette.chords, lifted = true, fill = true)
            }
        }
    }
}

private fun resultText(round: ChordRound): String {
    val wrong = round.result?.count { !it } ?: 0
    return when {
        wrong == 0 -> "Richtig!"
        round.answer.size == 1 -> "Leider falsch"
        wrong == 1 -> "Fast – einer liegt falsch"
        else -> "$wrong liegen falsch"
    }
}

@Composable
private fun ListenButton(playing: Boolean, onClick: () -> Unit) {
    val pulse = rememberInfiniteTransition()
    val scale by pulse.animateFloat(1f, 1.08f, infiniteRepeatable(tween(600), RepeatMode.Reverse))
    Box(
        Modifier.size(96.dp).scale(if (playing) scale else 1f).shadow(8.dp, CircleShape).clip(CircleShape)
            .background(Palette.chords).clickable(onClickLabel = "Anhören", onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        GlyphIcon(if (playing) Glyph.STOP else Glyph.PLAY, Color.White, 44.dp)
    }
}

@Composable
private fun Slot(
    index: Int,
    tile: Tile?,
    result: Boolean?,
    round: ChordRound,
    drag: DragState,
    vm: AppViewModel,
    label: (Tile) -> Pair<String, String?>,
) {
    val hovering = drag.tile != null && drag.slotAt(drag.pointer) == index
    val borderColor = when {
        result == true -> Palette.good
        result == false -> Palette.again
        hovering -> Palette.chords
        else -> MaterialTheme.colorScheme.outline
    }
    Box(
        Modifier.widthIn(min = 84.dp).height(64.dp)
            .onGloballyPositioned { drag.slots[index] = it.boundsInRoot() }
            .clip(RoundedCornerShape(14.dp))
            .background(if (hovering) Palette.chords.copy(alpha = 0.15f) else MaterialTheme.colorScheme.surface)
            .border(2.dp, borderColor, RoundedCornerShape(14.dp)),
        contentAlignment = Alignment.Center,
    ) {
        if (tile == null) {
            Text("${index + 1}", color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.6f), fontSize = 18.sp)
        } else {
            DraggableTile(tile, round, drag, vm, inSlot = true, enabled = !round.checked, label = label(tile), resultColor = result?.let { if (it) Palette.good else Palette.again })
        }
    }
}

@Composable
private fun DraggableTile(
    tile: Tile,
    round: ChordRound,
    drag: DragState,
    vm: AppViewModel,
    inSlot: Boolean,
    enabled: Boolean,
    label: Pair<String, String?>,
    resultColor: Color? = null,
) {
    var bounds by remember { mutableStateOf(Rect.Zero) }
    val beingDragged = drag.tile?.id == tile.id
    Box(
        Modifier.widthIn(min = 84.dp).height(if (inSlot) 64.dp else 58.dp)
            .onGloballyPositioned { bounds = it.boundsInRoot() }
            .alpha(if (beingDragged) 0.25f else 1f)
            .pointerInput(tile.id, enabled, round) {
                if (!enabled) return@pointerInput
                detectDragGestures(
                    onDragStart = { at ->
                        drag.tile = tile
                        drag.grab = at
                        drag.tileSize = bounds.size
                        drag.pointer = bounds.topLeft + at
                    },
                    onDrag = { change, amount ->
                        change.consume()
                        drag.pointer += amount
                    },
                    onDragEnd = {
                        val slot = drag.slotAt(drag.pointer)
                        if (slot != null && slot < round.slots.size) vm.placeTile(tile, slot) else vm.returnTile(tile)
                        drag.tile = null
                    },
                    onDragCancel = { drag.tile = null },
                )
            }
            .clickable(enabled = enabled, onClickLabel = if (inSlot) "Zurücklegen" else "Einsetzen") { vm.tapTile(tile) },
    ) {
        TileFace(label, resultColor ?: Palette.chords, lifted = false, fill = false)
    }
}

/** A tile: the chord name, and above it its roman numeral when those are shown. */
@Composable
private fun TileFace(label: Pair<String, String?>, color: Color, lifted: Boolean, fill: Boolean) {
    val (text, numeral) = label
    Box(
        (if (fill) Modifier.fillMaxSize() else Modifier.fillMaxHeight().widthIn(min = 84.dp)).let { if (lifted) it.shadow(10.dp, RoundedCornerShape(14.dp)) else it }
            .clip(RoundedCornerShape(14.dp)).background(color).padding(horizontal = 16.dp),
        contentAlignment = Alignment.Center,
    ) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            if (numeral != null) {
                Text(numeral, color = Color.White.copy(alpha = 0.85f), fontWeight = FontWeight.SemiBold, fontSize = 13.sp, maxLines = 1)
            }
            Text(text, color = Color.White, fontWeight = FontWeight.Bold, fontSize = if (numeral != null) 20.sp else 22.sp, maxLines = 1)
        }
    }
}

@Composable
private fun Actions(vm: AppViewModel, session: TrainSession, round: ChordRound, checked: Boolean, complete: Boolean, anyPlaced: Boolean) {
    if (!checked) {
        Row(horizontalArrangement = Arrangement.spacedBy(12.dp), verticalAlignment = Alignment.CenterVertically) {
            SmallButton("Leeren", {
                round.slots.indices.forEach { round.remove(it) }
                session.version++
            }, enabled = anyPlaced, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Button(
                vm::checkRound,
                Modifier.height(52.dp).widthIn(min = 180.dp),
                enabled = complete,
                shape = RoundedCornerShape(16.dp),
                colors = ButtonDefaults.buttonColors(containerColor = Palette.chords, contentColor = Color.White),
            ) { Text("Prüfen", fontWeight = FontWeight.SemiBold) }
        }
        return
    }
    val suggested = session.suggested ?: Grade.GOOD
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            for (grade in Grade.entries) {
                GradeButton(grade, if (grade == suggested) "Vorschlag" else null, Modifier.weight(1f), highlighted = grade == suggested) {
                    vm.gradeRound(grade)
                }
            }
        }
        Text(
            "Enter übernimmt den Vorschlag",
            Modifier.padding(top = 8.dp),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

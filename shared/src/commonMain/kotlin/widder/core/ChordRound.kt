package widder.core

import widder.music.Distractors
import widder.music.Notation
import widder.music.Token
import kotlin.random.Random

/** A tile the player moves around; [id] tells apart two tiles with the same text. */
data class Tile(val id: Int, val token: Token)

/**
 * One chord-order exercise: the answer's tiles (plus distractors) lie shuffled in the pool and
 * have to be put into the slots in the order they are heard.
 */
class ChordRound(
    val answer: List<Token>,
    distractors: Int = 0,
    notation: Notation = Notation(),
    random: Random = Random,
    /** Tiles to draw the distractors from; empty = let [Distractors] make up similar chords. */
    candidates: List<Token> = emptyList(),
) {
    val pool: List<Tile>
    val slots: Array<Tile?> = arrayOfNulls(answer.size)
    var result: List<Boolean>? = null
        private set

    init {
        val taken = answer.map { it.key }.toSet()
        val extra = if (candidates.isNotEmpty()) {
            candidates.filter { it.key !in taken }.distinctBy { it.key }.shuffled(random).take(distractors)
        } else {
            Distractors.make(answer, distractors, notation, random)
        }
        val tiles = (answer + extra).mapIndexed { i, t -> Tile(i, t) }
        // Never hand out the tiles already in the right order.
        var shuffled = tiles.shuffled(random)
        var tries = 0
        while (tiles.size > 1 && shuffled.take(answer.size).map { it.token.key } == answer.map { it.key } && tries++ < 10) {
            shuffled = tiles.shuffled(random)
        }
        pool = shuffled
    }

    val checked: Boolean get() = result != null
    val complete: Boolean get() = slots.all { it != null }
    val correct: Boolean get() = result?.all { it } == true

    fun slotOf(tile: Tile): Int? = slots.indexOfFirst { it?.id == tile.id }.takeIf { it >= 0 }
    fun inPool(tile: Tile): Boolean = slotOf(tile) == null

    /**
     * Drops [tile] into [slot]. What was there goes where the tile came from: to the tile's old
     * slot (a swap) or back to the pool.
     */
    fun place(tile: Tile, slot: Int) {
        if (checked || slot !in slots.indices) return
        val from = slotOf(tile)
        val occupant = slots[slot]
        slots[slot] = tile
        if (from != null && from != slot) slots[from] = occupant
    }

    /** Tapping a pool tile puts it into the first free slot. */
    fun placeNext(tile: Tile) {
        val free = slots.indexOfFirst { it == null }
        if (free >= 0 && inPool(tile)) place(tile, free)
    }

    fun remove(slot: Int) {
        if (!checked && slot in slots.indices) slots[slot] = null
    }

    fun removeLast() {
        val last = slots.indexOfLast { it != null }
        if (last >= 0) remove(last)
    }

    fun check(): List<Boolean> {
        val r = answer.indices.map { i -> slots[i]?.token?.key == answer[i].key }
        result = r
        return r
    }

    /** The grade the answer earns: all right is Good (Easy when quick), one slip Hard, more Again. */
    fun suggestedGrade(seconds: Double): Grade {
        val wrong = result?.count { !it } ?: answer.size
        return when {
            wrong == 0 && seconds < 4.0 + 1.5 * answer.size -> Grade.EASY
            wrong == 0 -> Grade.GOOD
            wrong == 1 && answer.size >= 3 -> Grade.HARD
            else -> Grade.AGAIN
        }
    }
}

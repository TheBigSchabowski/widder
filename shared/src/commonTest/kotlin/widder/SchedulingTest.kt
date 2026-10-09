package widder

import widder.core.Card
import widder.core.ChordRound
import widder.core.Deck
import widder.core.Fsrs
import widder.core.Grade
import widder.core.Phase
import widder.core.Progress
import widder.core.Queue
import widder.core.Reward
import widder.core.RewardProgress
import widder.core.RewardSettings
import widder.core.answer
import widder.core.streak
import widder.music.AnswerParser
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class SchedulingTest {
    // Noon, so that adding minutes never crosses the 4 am day boundary.
    private val now = Fsrs.startOfDay(1_780_000_000_000L) + 8 * 60 * Fsrs.MINUTE_MS

    @Test
    fun newCardGoesThroughLearningSteps() {
        val s1 = Fsrs.review(null, Grade.GOOD, now)
        assertEquals(Phase.LEARNING, s1.phase)
        assertEquals(now + 10 * Fsrs.MINUTE_MS, s1.due)
        val s2 = Fsrs.review(s1, Grade.GOOD, now + 10 * Fsrs.MINUTE_MS)
        assertEquals(Phase.REVIEW, s2.phase)
        assertTrue(s2.due >= Fsrs.startOfDay(now, 1))

        val again = Fsrs.review(null, Grade.AGAIN, now)
        assertEquals(now + Fsrs.MINUTE_MS, again.due)
        val hard = Fsrs.review(null, Grade.HARD, now)
        assertEquals(now + 330_000, hard.due)
    }

    @Test
    fun buttonsStayInOrder() {
        var s = Fsrs.review(null, Grade.GOOD, now)
        s = Fsrs.review(s, Grade.GOOD, now + 10 * Fsrs.MINUTE_MS)
        val later = s.due + Fsrs.DAY_MS / 2
        val preview = Fsrs.preview(s, later)
        assertTrue(preview[Grade.AGAIN]!! < preview[Grade.HARD]!!)
        assertTrue(preview[Grade.HARD]!! <= preview[Grade.GOOD]!!)
        assertTrue(preview[Grade.GOOD]!! < preview[Grade.EASY]!!)
        val lapse = Fsrs.review(s, Grade.AGAIN, later)
        assertEquals(Phase.RELEARNING, lapse.phase)
        assertEquals(1, lapse.lapses)
    }

    @Test
    fun queueRespectsNewLimitAndShowsLearningFirst() {
        val deck = Deck("d", "Test", newPerDay = 2)
        val cards = (1..5).map { Card("c$it", "d", front = "$it", created = it.toLong()) }
        var progress = Progress()
        assertEquals(2, Queue(deck, cards, progress, now).counts.new)
        val first = Queue(deck, cards, progress, now).next()!!
        assertEquals("c1", first.id)
        progress = progress.answer(first, Grade.AGAIN, 1, now)
        assertEquals(1, Queue(deck, cards, progress, now).counts.new)
        // c1 waits a minute; meanwhile the second new card comes.
        assertEquals("c2", Queue(deck, cards, progress, now).next(skip = "c1")!!.id)
        progress = progress.answer(cards[1], Grade.EASY, 1, now)
        // New limit reached: only the learning card is left, shown early.
        assertEquals("c1", Queue(deck, cards, progress, now).next()!!.id)
        progress = progress.answer(cards[0], Grade.EASY, 1, now + Fsrs.MINUTE_MS)
        assertNull(Queue(deck, cards, progress, now + Fsrs.MINUTE_MS).next())
        assertEquals(1, progress.streak(now))
    }

    /** The order in which a whole session of new cards comes, all answered Easy. */
    private fun order(deck: Deck, cards: List<Card>, seed: Int?): List<String> {
        var progress = Progress()
        return generateSequence { Queue(deck, cards, progress, now, seed).next()?.also { progress = progress.answer(it, Grade.EASY, 1, now) } }
            .map { it.id }.toList()
    }

    @Test
    fun shuffledQueueMixesTheOrder() {
        val deck = Deck("d", "Intervalle", newPerDay = 50)
        val cards = (1..24).map { Card("startkit-intervalle-$it", "d", created = it.toLong()) }
        val made = cards.map { it.id }
        assertEquals(made, order(deck, cards, seed = null))
        val first = order(deck, cards, seed = 1)
        val second = order(deck, cards, seed = 2)
        // Every card once, but neither in the made order nor the same each session.
        assertEquals(made.toSet(), first.toSet())
        assertEquals(24, first.size)
        assertTrue(first != made && second != made && first != second, "$first")
        assertEquals(first, order(deck, cards, seed = 1))
        // Not just the made order turned a little: neighbours are seldom neighbours.
        val neighbours = first.zipWithNext().count { (a, b) -> made.indexOf(b) - made.indexOf(a) == 1 }
        assertTrue(neighbours <= 4, "$first")
    }

    @Test
    fun chordRoundDragAndDrop() {
        val answer = AnswerParser.parse("A D G F.wav")
        val round = ChordRound(answer, random = Random(1))
        val byText = round.pool.associateBy { it.token.text }
        round.place(byText["D"]!!, 0)
        round.place(byText["A"]!!, 0) // D moves back to the pool
        assertTrue(round.inPool(byText["D"]!!))
        round.placeNext(byText["D"]!!)
        round.place(byText["F"]!!, 2)
        round.place(byText["G"]!!, 3)
        round.place(byText["G"]!!, 2) // swap G and F
        assertEquals(listOf("A", "D", "G", "F"), round.slots.map { it!!.token.text })
        assertTrue(round.check().all { it })
        assertEquals(Grade.EASY, round.suggestedGrade(3.0))
        assertEquals(Grade.GOOD, round.suggestedGrade(30.0))
    }

    @Test
    fun chordRoundWithDistractorsAndMistake() {
        val answer = AnswerParser.parse("Fmin Gmin.wav")
        val round = ChordRound(answer, distractors = 2, random = Random(3))
        assertEquals(4, round.pool.size)
        val wrong = round.pool.first { it.token.key != answer[0].key && it.token.key != answer[1].key }
        round.place(wrong, 0)
        round.place(round.pool.first { it.token.key == answer[1].key }, 1)
        assertEquals(listOf(false, true), round.check())
        assertFalse(round.correct)
        assertEquals(Grade.AGAIN, round.suggestedGrade(5.0))
        // Distractors keep the answer's way of writing minor.
        assertTrue(round.pool.filter { it.token.chord?.quality?.isMinor == true }.all { "min" in it.token.text })
    }

    @Test
    fun distractorsComeFromTheDeckPool() {
        val kit = widder.ui.StarterKits.CHORDS.map { widder.music.Token.of(it) }
        val round = ChordRound(listOf(widder.music.Token.of("A7")), distractors = 3, random = Random(5), candidates = kit)
        assertEquals(4, round.pool.size)
        assertTrue(round.pool.all { t -> kit.any { it.key == t.token.key } })
        assertEquals(1, round.pool.count { it.token.text == "A7" })
    }

    @Test
    fun rewardUnlocksTilesWithTime() {
        val settings = RewardSettings(columns = 3, rows = 2, secondsPerTile = 60)
        var p = Reward.current(RewardProgress(), settings, now)
        assertEquals(6, p.order.size)
        assertEquals(0, Reward.unlocked(p, settings))
        p = Reward.addSeconds(p, settings, 125, now)
        assertEquals(2, Reward.sharpTiles(p, settings).size)
        assertEquals(55, Reward.secondsToNext(p, settings))
        p = Reward.addSeconds(p, settings, 10_000, now)
        assertNull(Reward.secondsToNext(p, settings))
        // Next learning day starts over.
        assertEquals(0, Reward.current(p, settings, now + Fsrs.DAY_MS).seconds)
    }
}

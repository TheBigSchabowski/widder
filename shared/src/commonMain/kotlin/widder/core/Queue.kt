package widder.core

/** What is waiting in a deck today, as shown next to it (Anki's blue / red / green numbers). */
data class DeckCounts(val new: Int, val learning: Int, val review: Int) {
    val total: Int get() = new + learning + review
}

/**
 * Decides which card comes next, the way Anki does: learning cards whose step is over first,
 * then due reviews, then new cards up to the daily limit. Learning cards that are due within
 * [LEARN_AHEAD_MS] are shown early once nothing else is left.
 *
 * With a [seed], new cards and due reviews come in an order mixed by it instead of as they were
 * made: in music the order itself is learned otherwise (the third interval is always the Terz).
 */
class Queue(
    private val deck: Deck,
    private val cards: List<Card>,
    private val progress: Progress,
    private val now: Long = currentTimeMillis(),
    private val seed: Int? = null,
) {
    private val day = Store.learningDay(now)
    private val endOfDay = Fsrs.startOfDay(now, 1)

    private fun state(card: Card) = progress.cards[card.id]

    private val active = cards.filter { it.deckId == deck.id && state(it)?.suspended != true }

    private val learning = active
        .filter { state(it)?.phase.let { p -> p == Phase.LEARNING || p == Phase.RELEARNING } }
        .sortedBy { state(it)!!.due }

    private val reviews = active
        .filter { state(it)?.phase == Phase.REVIEW && state(it)!!.due < endOfDay }
        .let { due -> if (seed == null) due.sortedBy { state(it)!!.due } else due.sortedBy { mix(it.id, seed) } }

    private val newCards = active.filter { (state(it)?.phase ?: Phase.NEW) == Phase.NEW }
        .let { new -> if (seed == null) new.sortedBy { it.created } else new.sortedBy { mix(it.id, seed) } }

    private val newLeft = (deck.newPerDay - (progress.newIntroduced["$day|${deck.id}"] ?: 0)).coerceAtLeast(0)
    private val reviewsLeft = (deck.reviewsPerDay - (progress.reviewsDone["$day|${deck.id}"] ?: 0)).coerceAtLeast(0)

    val counts: DeckCounts
        get() = DeckCounts(
            new = minOf(newLeft, newCards.size),
            learning = learning.count { state(it)!!.due < endOfDay },
            review = minOf(reviewsLeft, reviews.size),
        )

    /** The next card, or null when the deck is done for now. [skip] avoids showing the same card twice in a row. */
    fun next(skip: String? = null): Card? {
        val dueLearning = learning.filter { state(it)!!.due <= now }
        dueLearning.firstOrNull { it.id != skip }?.let { return it }
        if (reviewsLeft > 0) reviews.firstOrNull { it.id != skip }?.let { return it }
        if (newLeft > 0) newCards.firstOrNull { it.id != skip }?.let { return it }
        val ahead = learning.filter { state(it)!!.due <= now + LEARN_AHEAD_MS }
        return ahead.firstOrNull { it.id != skip } ?: dueLearning.firstOrNull() ?: ahead.firstOrNull()
    }

    /** When the next learning card is due, if the deck is only waiting for one. */
    fun nextLearningDue(): Long? = learning.map { state(it)!!.due }.filter { it < endOfDay }.minOrNull()

    companion object {
        const val LEARN_AHEAD_MS = 20 * Fsrs.MINUTE_MS

        /** A place in the mixed order. Ids differing only in their last digit land far apart. */
        fun mix(id: String, seed: Int): Long {
            var z = id.hashCode().toLong() * -0x61c8864680b583ebL + seed
            z = (z xor (z ushr 30)) * -0x40a7b892e31b1a47L
            z = (z xor (z ushr 27)) * -0x6b2fb644ecceee15L
            return z xor (z ushr 31)
        }
    }
}

/** Records an answer in [progress]: new card state, daily limits, statistics and XP. */
fun Progress.answer(card: Card, grade: Grade, xp: Int, now: Long = currentTimeMillis()): Progress {
    val day = Store.learningDay(now)
    val old = cards[card.id]
    val wasNew = (old?.phase ?: Phase.NEW) == Phase.NEW
    val wasReview = old?.phase == Phase.REVIEW
    val key = "$day|${card.deckId}"
    val stats = days[day] ?: DayStats()
    return copy(
        cards = cards + (card.id to Fsrs.review(old, grade, now)),
        newIntroduced = if (wasNew) newIntroduced + (key to (newIntroduced[key] ?: 0) + 1) else newIntroduced,
        reviewsDone = if (wasReview) reviewsDone + (key to (reviewsDone[key] ?: 0) + 1) else reviewsDone,
        days = days + (
            day to stats.copy(
                reviews = stats.reviews + 1,
                correct = stats.correct + if (grade != Grade.AGAIN) 1 else 0,
                xp = stats.xp + xp,
            )
            ),
        totalXp = totalXp + xp,
    )
}

/** Days in a row with at least one answer, counting today only once something was learned. */
fun Progress.streak(now: Long = currentTimeMillis()): Int {
    var day = Fsrs.startOfDay(now)
    if ((days[isoDate(day)]?.reviews ?: 0) == 0) day = Fsrs.startOfDay(day - 1, 0)
    var count = 0
    while ((days[isoDate(day)]?.reviews ?: 0) > 0) {
        count++
        day = Fsrs.startOfDay(day - 1, 0)
    }
    return count
}

/** Level from total XP: each level needs a bit more than the one before. */
data class Level(val level: Int, val intoLevel: Int, val needed: Int)

fun levelFor(xp: Int): Level {
    var level = 1
    var rest = xp
    var needed = 200
    while (rest >= needed) {
        rest -= needed
        level++
        needed += 100
    }
    return Level(level, rest, needed)
}

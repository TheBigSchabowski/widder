package widder.core

import kotlinx.serialization.Serializable
import kotlin.math.exp
import kotlin.math.max
import kotlin.math.pow
import kotlin.math.roundToLong

enum class Grade(val value: Int, val label: String) {
    AGAIN(1, "Nochmal"),
    HARD(2, "Schwer"),
    GOOD(3, "Gut"),
    EASY(4, "Leicht"),
}

@Serializable
enum class Phase { NEW, LEARNING, REVIEW, RELEARNING }

@Serializable
data class ReviewState(
    val phase: Phase = Phase.NEW,
    /** Index into the learning or relearning steps. */
    val step: Int = 0,
    val stability: Double = 0.0,
    val difficulty: Double = 0.0,
    /** Epoch millis when the card is due again. */
    val due: Long = 0,
    val lastReview: Long = 0,
    val reps: Int = 0,
    val lapses: Int = 0,
    val suspended: Boolean = false,
)

/**
 * Anki's scheduler with FSRS memory states: new cards go through the learning steps
 * (1 min, 10 min), graduate into day intervals from the FSRS stability, and a forgotten
 * review card goes through a relearning step (10 min) before it is back in review.
 * Default FSRS-4.5 parameters, 90 % target retention (at which the interval equals the stability).
 */
object Fsrs {
    private val w = doubleArrayOf(
        0.4872, 1.4003, 3.7145, 13.8206, 5.1618, 1.2298, 0.8975, 0.031, 1.6474,
        0.1367, 1.0461, 2.1072, 0.0793, 0.3246, 1.587, 0.2272, 2.8755,
    )
    private const val DECAY = -0.5
    private const val FACTOR = 19.0 / 81.0
    const val MINUTE_MS = 60 * 1000L
    const val DAY_MS = 24 * 60 * MINUTE_MS
    const val DAY_START_HOUR = 4

    val learningSteps = listOf(1 * MINUTE_MS, 10 * MINUTE_MS)
    val relearningSteps = listOf(10 * MINUTE_MS)

    private fun retrievability(elapsedDays: Double, stability: Double) =
        (1 + FACTOR * elapsedDays / stability).pow(DECAY)

    private fun initialDifficulty(g: Int) = (w[4] - (g - 3) * w[5]).coerceIn(1.0, 10.0)

    /** The FSRS memory state after answering [grade]. */
    private fun memory(state: ReviewState, grade: Grade, now: Long): Pair<Double, Double> {
        val g = grade.value
        if (state.reps == 0) return w[g - 1] to initialDifficulty(g)
        val elapsed = (now - state.lastReview).coerceAtLeast(0).toDouble() / DAY_MS
        val r = retrievability(elapsed, state.stability)
        val d = state.difficulty
        val nextD = (w[7] * initialDifficulty(3) + (1 - w[7]) * (d - w[6] * (g - 3))).coerceIn(1.0, 10.0)
        val nextS = if (grade == Grade.AGAIN) {
            (w[11] * d.pow(-w[12]) * ((state.stability + 1).pow(w[13]) - 1) * exp(w[14] * (1 - r)))
                .coerceAtMost(state.stability)
        } else {
            val hardPenalty = if (grade == Grade.HARD) w[15] else 1.0
            val easyBonus = if (grade == Grade.EASY) w[16] else 1.0
            state.stability * (
                exp(w[8]) * (11 - d) * state.stability.pow(-w[9]) * (exp(w[10] * (1 - r)) - 1) *
                    hardPenalty * easyBonus + 1
                )
        }
        return nextS.coerceAtLeast(0.01) to nextD
    }

    private fun days(stability: Double): Long = stability.roundToLong().coerceIn(1, 36500)

    fun review(state: ReviewState?, grade: Grade, now: Long = currentTimeMillis()): ReviewState {
        val old = state ?: ReviewState()
        val (s, d) = memory(old, grade, now)
        val base = old.copy(
            stability = s,
            difficulty = d,
            lastReview = now,
            reps = old.reps + 1,
        )
        return when (old.phase) {
            Phase.NEW, Phase.LEARNING -> learn(base, old.step, learningSteps, grade, now, fromNew = true)
            Phase.RELEARNING -> learn(base, old.step, relearningSteps, grade, now, fromNew = false)
            Phase.REVIEW -> if (grade == Grade.AGAIN) {
                base.copy(phase = Phase.RELEARNING, step = 0, lapses = old.lapses + 1, due = now + relearningSteps[0])
            } else {
                // Keep the buttons in order even where the formula would bunch them together.
                val lastInterval = if (old.lastReview > 0) max(1L, (now - old.lastReview) / DAY_MS) else 1L
                var interval = days(s)
                if (grade == Grade.GOOD || grade == Grade.EASY) interval = max(interval, lastInterval + 1)
                if (grade == Grade.EASY) {
                    val good = days(memory(old, Grade.GOOD, now).first)
                    interval = max(interval, max(good, lastInterval + 1) + 1)
                }
                base.copy(phase = Phase.REVIEW, step = 0, due = startOfDay(now, interval))
            }
        }
    }

    private fun learn(
        base: ReviewState,
        step: Int,
        steps: List<Long>,
        grade: Grade,
        now: Long,
        fromNew: Boolean,
    ): ReviewState {
        val phase = if (fromNew) Phase.LEARNING else Phase.RELEARNING
        return when (grade) {
            Grade.AGAIN -> base.copy(phase = phase, step = 0, due = now + steps[0])
            // Anki: Hard on the first step waits halfway between the first two steps.
            Grade.HARD -> {
                val delay = if (step == 0 && steps.size > 1) (steps[0] + steps[1]) / 2 else steps[step.coerceAtMost(steps.lastIndex)]
                base.copy(phase = phase, step = step, due = now + delay)
            }
            Grade.GOOD -> if (step + 1 < steps.size) {
                base.copy(phase = phase, step = step + 1, due = now + steps[step + 1])
            } else {
                base.copy(phase = Phase.REVIEW, step = 0, due = startOfDay(now, days(base.stability)))
            }
            Grade.EASY -> {
                val good = if (step + 1 < steps.size) 1L else days(base.stability)
                base.copy(phase = Phase.REVIEW, step = 0, due = startOfDay(now, max(days(base.stability), good + 1)))
            }
        }
    }

    /** What each button would do, for the labels under the buttons. */
    fun preview(state: ReviewState?, now: Long = currentTimeMillis()): Map<Grade, Long> =
        Grade.entries.associateWith { review(state, it, now).due - now }

    /** Start of the learning day [daysAhead] days after the one containing [now]. */
    fun startOfDay(now: Long, daysAhead: Long = 0): Long = dayStart(now, daysAhead, DAY_START_HOUR)

    /** "1 Min", "10 Min", "3 T", "2 Mon", "1,5 J" */
    fun formatInterval(ms: Long): String {
        val minutes = ms / MINUTE_MS
        val days = (ms + DAY_MS / 2) / DAY_MS
        return when {
            minutes < 1 -> "<1 Min"
            minutes < 60 -> "$minutes Min"
            minutes < 20 * 60 -> "${minutes / 60} Std"
            days < 31 -> "${max(1, days)} T"
            days < 365 -> "${(days / 30.4).roundToLong()} Mon"
            else -> {
                val years = (days / 36.5).roundToLong() / 10.0
                "${years.toString().replace('.', ',').removeSuffix(",0")} J"
            }
        }
    }
}

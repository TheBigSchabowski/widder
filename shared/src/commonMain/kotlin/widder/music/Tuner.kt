package widder.music

import kotlinx.serialization.Serializable
import kotlin.math.abs
import kotlin.math.log2
import kotlin.math.roundToInt

/** One string to tune to: a MIDI note, with [cents] for a microtonal one. */
@Serializable
data class TuningString(val pitch: Int, val cents: Int = 0) {
    /** Cents from a′ (A4). */
    val fromA4: Double get() = (pitch - 69) * 100.0 + cents
}

/** A tuning: its strings from the lowest (thickest) up. */
@Serializable
data class StringTuning(val name: String, val strings: List<TuningString>)

/** What the tuner makes of a frequency. */
data class TunerReading(
    /** The note or string aimed at, e.g. "A", "A +50 ct" for a microtone, or a string's name. */
    val pitch: Int,
    /** Cents of the aimed-at step from [pitch] (a microtone), 0 for plain semitones. */
    val stepCents: Int,
    /** How far the sound is off the aimed-at step, in cents (+ = too high). */
    val deviation: Double,
    /** Half the distance to the neighbouring step: the needle's full swing. */
    val halfStep: Double,
    /** Index of the string in a tuning; null when chromatic. */
    val string: Int? = null,
)

object Tuner {
    private val LETTERS = mapOf('C' to 0, 'D' to 2, 'E' to 4, 'F' to 5, 'G' to 7, 'A' to 9, 'B' to 11, 'H' to 11)

    private fun s(vararg notes: String) = notes.map { requireNotNull(parse(it)) { it } }

    /** Common tunings, as in Choona: guitar, bass, ukulele and a few more. */
    val presets: List<StringTuning> = listOf(
        StringTuning("Gitarre Standard", s("E2", "A2", "D3", "G3", "B3", "E4")),
        StringTuning("Gitarre Drop D", s("D2", "A2", "D3", "G3", "B3", "E4")),
        StringTuning("Gitarre Halbton tiefer", s("Eb2", "Ab2", "Db3", "Gb3", "Bb3", "Eb4")),
        StringTuning("Gitarre Ganzton tiefer", s("D2", "G2", "C3", "F3", "A3", "D4")),
        StringTuning("Gitarre Drop C", s("C2", "G2", "C3", "F3", "A3", "D4")),
        StringTuning("Gitarre DADGAD", s("D2", "A2", "D3", "G3", "A3", "D4")),
        StringTuning("Gitarre Open D", s("D2", "A2", "D3", "F#3", "A3", "D4")),
        StringTuning("Gitarre Open G", s("D2", "G2", "D3", "G3", "B3", "D4")),
        StringTuning("Gitarre Open E", s("E2", "B2", "E3", "G#3", "B3", "E4")),
        StringTuning("Gitarre 7-Saiter", s("B1", "E2", "A2", "D3", "G3", "B3", "E4")),
        StringTuning("Bass Standard", s("E1", "A1", "D2", "G2")),
        StringTuning("Bass Drop D", s("D1", "A1", "D2", "G2")),
        StringTuning("Bass 5-Saiter", s("B0", "E1", "A1", "D2", "G2")),
        StringTuning("Ukulele", s("G4", "C4", "E4", "A4")),
        StringTuning("Ukulele Bariton", s("D3", "G3", "B3", "E4")),
        StringTuning("Geige / Mandoline", s("G3", "D4", "A4", "E5")),
        StringTuning("Bratsche", s("C3", "G3", "D4", "A4")),
        StringTuning("Cello", s("C2", "G2", "D3", "A3")),
    )

    /**
     * A note such as "E2", "F#3", "Bb1", "Eb4", "H3", "A2+50" or "D3-25" (cents for microtones);
     * null if it is none.
     */
    fun parse(text: String): TuningString? {
        val t = text.trim().replace('♯', '#').replace('♭', 'b').replace('−', '-')
        val m = Regex("""^([A-Ha-h])([#b]?)(-?\d)([+-]\d{1,3})?$""").find(t) ?: return null
        val letter = LETTERS[m.groupValues[1].uppercase()[0]] ?: return null
        val accidental = when (m.groupValues[2]) {
            "#" -> 1
            "b" -> -1
            else -> 0
        }
        val octave = m.groupValues[3].toInt()
        val cents = m.groupValues[4].takeIf { it.isNotEmpty() }?.toInt() ?: 0
        if (abs(cents) >= 100) return null
        val pitch = 12 * (octave + 1) + letter + accidental
        return if (pitch in 0..127) TuningString(pitch, cents) else null
    }

    /** All strings of [text] ("E2 A2 D3 ..."), or null if one is not a note. */
    fun parseStrings(text: String): List<TuningString>? {
        val parts = text.split(Regex("[\\s,]+")).filter { it.isNotBlank() }
        if (parts.isEmpty()) return null
        return parts.map { parse(it) ?: return null }
    }

    /** [s] written so [parse] reads it back, e.g. "A2+50". */
    fun format(s: TuningString, notation: Notation = Notation()): String =
        Chord.noteName(s.pitch % 12, notation) + (s.pitch / 12 - 1) + when {
            s.cents > 0 -> "+${s.cents}"
            s.cents < 0 -> "${s.cents}"
            else -> ""
        }

    /** Cents of [freq] from a′ at [tuning] Hz. */
    fun fromA4(freq: Double, tuning: Double): Double = 1200 * log2(freq / tuning)

    /** The nearest step of [division] steps per octave (counted from a′) and how far off it [freq] is. */
    fun chromatic(freq: Double, tuning: Double, division: Int): TunerReading {
        val c = fromA4(freq, tuning)
        val step = 1200.0 / division
        val stepFromA4 = (c / step).roundToInt() * step
        // Named after the nearest plain note, with the microtone in cents; right between two, after the lower one (A +50, not B♭ −50).
        val semis = kotlin.math.ceil(stepFromA4 / 100 - 0.5 - 1e-9).toInt()
        return TunerReading(69 + semis, (stepFromA4 - semis * 100).roundToInt(), c - stepFromA4, step / 2)
    }

    /** The string of [tuning] nearest to [freq] (or [locked]), and how far off it. */
    fun string(freq: Double, a4: Double, tuning: StringTuning, locked: Int? = null): TunerReading {
        val c = fromA4(freq, a4)
        val index = locked?.takeIf { it in tuning.strings.indices } ?: tuning.strings.indices.minBy { abs(c - tuning.strings[it].fromA4) }
        val s = tuning.strings[index]
        return TunerReading(s.pitch, s.cents, c - s.fromA4, 50.0, index)
    }

    /** Frequency of [s] at [tuning] Hz for a′. */
    fun frequency(s: TuningString, tuning: Double): Double = tuning * Math2.pow2(s.fromA4 / 1200)

    /** Close enough to call it tuned, given how fine the steps are. */
    fun inTune(reading: TunerReading): Boolean = abs(reading.deviation) < minOf(3.0, reading.halfStep / 4)
}

private object Math2 {
    fun pow2(x: Double): Double = kotlin.math.exp(x * kotlin.math.ln(2.0))
}

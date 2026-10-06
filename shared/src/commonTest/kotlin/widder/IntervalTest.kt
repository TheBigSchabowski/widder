package widder

import widder.core.ChordRound
import widder.music.AnswerParser
import widder.music.Chord
import widder.music.Distractors
import widder.music.Harmony
import widder.music.Interval
import widder.music.Key
import widder.music.Midi
import widder.music.MidiAnalysis
import widder.music.Notation
import widder.music.Token
import widder.ui.StarterKits
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class IntervalTest {
    @Test
    fun namesUpToTheQuintdezime() {
        assertEquals(3, Interval.parse("kleine Terz"))
        assertEquals(4, Interval.parse("grosse  terz"))
        assertEquals(7, Interval.parse("reine Quinte"))
        assertEquals(18, Interval.parse("übermässige Undezime"))
        assertEquals(24, Interval.parse("Quintdezime"))
        assertEquals("große Quartdezime", Interval.name(23))
        assertNull(Interval.parse("Am"))
        // None of them reads as a chord.
        Interval.NAMES.forEach { assertNull(Chord.parse(it), it) }
    }

    @Test
    fun fileNamesKeepTwoWordIntervalsTogether() {
        assertEquals(listOf("kleine Terz"), AnswerParser.parse("kleine Terz.mid").map { it.text })
        assertEquals(listOf("große Sexte", "Quinte"), AnswerParser.parse("große Sexte_Quinte (2).wav").map { it.text })
        assertEquals(listOf("A", "D", "G", "F"), AnswerParser.parse("A D G F.wav").map { it.text })
        assertEquals(listOf("kleine None", "Am"), AnswerParser.splitTyped("kleine None, Am"))
        assertEquals(Token.of("grosse Terz").key, Token.of("große Terz").key)
    }

    @Test
    fun wrongIntervalTilesAreNeighbours() {
        val answer = listOf(Token.of("kleine Terz"))
        repeat(20) { seed ->
            val wrong = Distractors.make(answer, 4, Notation(), Random(seed))
            assertEquals(4, wrong.size)
            assertTrue(wrong.all { it.interval != null && it.interval != 3 })
            assertTrue(wrong.all { kotlin.math.abs(it.interval!! - 3) <= 3 || it.interval == 15 }, "$wrong")
        }
        val round = ChordRound(answer, 4, random = Random(1))
        assertEquals(5, round.pool.size)
    }

    @Test
    fun midiWithTwoNotesIsAnInterval() {
        for (together in listOf(true, false)) {
            for (semitones in 1..24) {
                val midi = Midi.parse(Midi.write(MidiAnalysis.interval(50, semitones, together)))
                assertEquals(semitones, MidiAnalysis.interval(midi))
            }
        }
        assertNull(MidiAnalysis.interval(Midi.parse(Midi.write(MidiAnalysis.progression(listOf(Chord.parse("C")!!))))))
    }

    @Test
    fun kitsHaveEveryIntervalChordTypeAndDegree() {
        assertEquals(24, (1..24).map { Interval.name(it) }.toSet().size)
        val types = StarterKits.CHORD_TYPES.map { Chord.parse(it)!!.quality.name }
        assertEquals(listOf("MAJOR", "MAJ7", "MINOR", "MINOR7", "ADD9", "MINOR_ADD9"), types)
        val c = Key.parse("C")!!
        for (german in listOf(false, true)) {
            val numerals = StarterKits.DEGREES.map { Harmony.numeral(Chord.parse(it, german)!!, c) }
            assertEquals(listOf("I", "ii", "iii", "IV", "V", "vi", "vii°"), numerals)
        }
    }
}

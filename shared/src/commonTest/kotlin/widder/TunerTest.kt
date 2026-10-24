package widder

import widder.music.Tuner
import widder.music.TuningString
import kotlin.math.abs
import kotlin.math.pow
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class TunerTest {
    private fun hz(cents: Double, a4: Double = 440.0) = a4 * 2.0.pow(cents / 1200)

    @Test
    fun parsesNotesWithMicrotones() {
        assertEquals(TuningString(40), Tuner.parse("E2"))
        assertEquals(TuningString(42), Tuner.parse("F#2"))
        assertEquals(TuningString(46), Tuner.parse("Bb2"))
        assertEquals(TuningString(47), Tuner.parse("H2"))
        assertEquals(TuningString(45, 50), Tuner.parse("A2+50"))
        assertEquals(TuningString(50, -25), Tuner.parse("d3-25"))
        assertNull(Tuner.parse("X2"))
        assertNull(Tuner.parse("A2+150"))
        assertEquals(6, Tuner.parseStrings("E2 A2 D3 G3 B3 E4")?.size)
        assertNull(Tuner.parseStrings("E2 A2 Q3"))
        assertEquals("A2+50", Tuner.format(TuningString(45, 50)))
        assertTrue(Tuner.presets.all { it.strings.isNotEmpty() })
    }

    @Test
    fun chromaticInSemitonesAndQuarterTones() {
        val a = Tuner.chromatic(hz(0.0), 440.0, 12)
        assertEquals(69, a.pitch)
        assertEquals(0, a.stepCents)
        assertTrue(abs(a.deviation) < 1e-6)
        // 60 cents above a′: B♭ -40 in semitones, but a quarter tone above A (+50) +10 in 24-EDO.
        val semis = Tuner.chromatic(hz(60.0), 440.0, 12)
        assertEquals(70, semis.pitch)
        assertEquals(-40.0, semis.deviation, 1e-6)
        val quarters = Tuner.chromatic(hz(60.0), 440.0, 24)
        assertEquals(69, quarters.pitch)
        assertEquals(50, quarters.stepCents)
        assertEquals(10.0, quarters.deviation, 1e-6)
        assertEquals(25.0, quarters.halfStep, 1e-9)
        // 19-EDO: steps of 63.2 cents; the second step above a′ (126 ct) is named B +26.
        val edo19 = Tuner.chromatic(hz(127.0), 440.0, 19)
        assertEquals(70, edo19.pitch)
        assertEquals(26, edo19.stepCents)
        // At 432 Hz a′ is 432 Hz.
        assertTrue(abs(Tuner.chromatic(432.0, 432.0, 12).deviation) < 1e-6)
    }

    @Test
    fun findsTheNearestString() {
        val guitar = Tuner.presets.first()
        val low = Tuner.string(hz((40 - 69) * 100.0 + 12), 440.0, guitar)
        assertEquals(0, low.string)
        assertEquals(12.0, low.deviation, 1e-6)
        val g = Tuner.string(hz((55 - 69) * 100.0 - 30), 440.0, guitar)
        assertEquals(3, g.string)
        // Locked to the low E, a D is far below it.
        assertEquals(-200.0, Tuner.string(hz((38 - 69) * 100.0), 440.0, guitar, locked = 0).deviation, 1e-6)
        assertTrue(Tuner.inTune(g.copy(deviation = 2.0)))
        assertTrue(!Tuner.inTune(g.copy(deviation = 4.0)))
        assertEquals(440.0, Tuner.frequency(TuningString(69), 440.0), 1e-9)
        assertEquals(hz(-50.0), Tuner.frequency(TuningString(69, -50), 440.0), 1e-9)
    }

    @Test
    fun hearsBassStrings() {
        for (pitch in listOf(23, 28, 33, 40, 64)) {
            val s = widder.core.VibratoSettings(root = pitch % 12, octave = pitch / 12 - 1, sound = widder.core.VibratoSound.STRINGS, width = 0, seconds = 1)
            val tone = widder.audio.VibratoSynth.render(s, 440.0, vibrato = false).channels[0]
            val n = widder.audio.PitchTracker.frameSize(44100, widder.audio.PitchTracker.LOWEST)
            val f = widder.audio.PitchTracker.detect(tone.copyOfRange(20000, 20000 + n), n, 44100, minFreq = widder.audio.PitchTracker.LOWEST)
            val reading = Tuner.chromatic(f.toDouble(), 440.0, 12)
            assertEquals(pitch, reading.pitch, "$pitch: $f Hz")
            assertTrue(abs(reading.deviation) < 3, "$pitch: ${reading.deviation}")
        }
    }
}

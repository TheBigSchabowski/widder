package widder

import widder.audio.PitchTracker
import widder.audio.PracticeEngine
import widder.audio.VibratoAnalysis
import widder.audio.VibratoLine
import widder.audio.VibratoSynth
import widder.core.Bend
import widder.core.LinePoint
import widder.core.VibratoDirection
import widder.core.VibratoSettings
import widder.core.VibratoSound
import kotlin.math.abs
import kotlin.math.log2
import kotlin.math.roundToInt
import kotlin.math.sin
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class VibratoTest {
    @Test
    fun trackerHearsTheStraightNote() {
        for (sound in VibratoSound.entries) for (pitch in listOf(45, 57, 69, 81)) {
            val s = VibratoSettings(root = pitch % 12, octave = pitch / 12 - 1, sound = sound, seconds = 1)
            val pcm = VibratoSynth.render(s, 440.0, vibrato = false)
            val freqs = PitchTracker.track(pcm.channels[0], pcm.sampleRate)
            val f0 = PracticeEngine.frequency(pitch, 440.0)
            val middle = freqs.slice(20 until 80)
            assertTrue(middle.none { it.isNaN() }, "$sound $pitch: gaps")
            assertTrue(middle.all { abs(1200 * log2(it / f0)) < 3 }, "$sound $pitch: ${middle.take(5)}")
        }
    }

    @Test
    fun findsSpeedWidthAndDirectionOfTheModelTone() {
        for (direction in VibratoDirection.entries) for ((rate, width) in listOf(3f to 120, 5.5f to 50, 7.5f to 30)) {
            for (sound in listOf(VibratoSound.STRINGS, VibratoSound.VOICE)) {
                val s = VibratoSettings(root = 7, octave = 3, sound = sound, rate = rate, width = width, direction = direction)
                val result = assertNotNull(VibratoAnalysis.analyse(VibratoSynth.render(s, 440.0, vibrato = true), s, 440.0))
                val label = "$direction $rate Hz $width ct $sound"
                assertEquals(direction, result.direction, "$label: ${result.offset}")
                assertTrue(abs(result.rate - rate) < 0.2, "$label: rate ${result.rate}")
                assertTrue(abs(result.width - width) < width * 0.12 + 3, "$label: width ${result.width}")
                assertTrue(result.score >= 90, "$label: score ${result.score}")
            }
        }
    }

    @Test
    fun aStraightToneHasNoVibrato() {
        val s = VibratoSettings()
        val result = assertNotNull(VibratoAnalysis.analyse(VibratoSynth.render(s, 440.0, vibrato = false), s, 440.0))
        assertNull(result.direction)
        assertTrue(result.width < 5, "${result.width}")
        assertTrue(result.rate.isNaN())
    }

    @Test
    fun anOctaveLowerCountsAndAMistunedSwingIsCaught() {
        val target = VibratoSettings(direction = VibratoDirection.UP, width = 60)
        // Sung an octave lower, around the note instead of above it.
        val sung = target.copy(octave = 2, direction = VibratoDirection.AROUND, rate = 4f)
        val result = assertNotNull(VibratoAnalysis.analyse(VibratoSynth.render(sung, 440.0, vibrato = true), target, 440.0))
        assertEquals(VibratoDirection.AROUND, result.direction)
        assertTrue(abs(result.offset + 30) < 5, "${result.offset}")
        assertTrue(!result.rateOk && result.widthOk && !result.directionOk && !result.pitchOk && result.shapeOk)
    }

    @Test
    fun liveDetectionReadsTheNewestSamples() {
        val s = VibratoSettings(root = 2, octave = 4, seconds = 1)
        val tone = VibratoSynth.render(s, 440.0, vibrato = false).channels[0]
        val buffer = FloatArray(PitchTracker.frameSize(44100))
        tone.copyInto(buffer, 0, 20000, 20000 + buffer.size)
        val freq = PitchTracker.detect(buffer, buffer.size, 44100)
        assertTrue(abs(1200 * log2(freq / PracticeEngine.frequency(s.pitch, 440.0))) < 3, "$freq")
        assertTrue(PitchTracker.detect(FloatArray(buffer.size), buffer.size, 44100).isNaN())
    }

    @Test
    fun linesAreFollowedAndMissed() {
        for (line in VibratoLine.entries) for (width in listOf(0, 50)) {
            val target = VibratoSettings(points = line.points(), width = width, vibratoFrom = if (line == VibratoLine.RISE) 1f else 0f)
            val copy = assertNotNull(VibratoAnalysis.analyse(VibratoSynth.render(target, 440.0, vibrato = true), target, 440.0))
            val label = "$line $width"
            assertTrue(copy.shapeOk && copy.pitchOk && copy.widthOk, "$label: shape ${copy.shape} offset ${copy.offset} width ${copy.width}/${copy.targetWidth}")
            if (width > 0) assertEquals(VibratoDirection.AROUND, copy.direction, label)
            assertTrue(copy.score >= 85, "$label: ${copy.score}")
            if (line == VibratoLine.STRAIGHT) continue
            // The same vibrato on a straight note misses the line.
            val straight = assertNotNull(VibratoAnalysis.analyse(VibratoSynth.render(target.copy(points = emptyList()), 440.0, vibrato = true), target, 440.0))
            assertTrue(!straight.shapeOk || !straight.pitchOk, "$label: shape ${straight.shape} offset ${straight.offset}")
        }
    }

    @Test
    fun editingTheLinePoints() {
        val (one, i) = VibratoLine.add(emptyList(), 0.5f, 200f)
        assertEquals(0, i)
        val (two, j) = VibratoLine.add(one, 0.2f, -100f, Bend.STEEP)
        assertEquals(0, j)
        assertEquals(listOf(0.2f, 0.5f), two.map { it.at })
        // A point stays between its neighbours and inside the range.
        val moved = VibratoLine.move(two, 0, 0.9f, 9000f)
        assertTrue(moved[0].at < moved[1].at)
        assertEquals(VibratoSettings.LINE_RANGE.toFloat(), moved[0].cents)
        assertEquals(Bend.LINEAR, VibratoLine.bend(two, 1, Bend.LINEAR)[1].bend)
        assertEquals(1, VibratoLine.remove(two, 0).size)
        assertEquals(50f, VibratoLine.snap(37f, 24))
        assertEquals(100f, VibratoLine.snap(70f, 12))
        assertEquals(VibratoLine.RISE, VibratoLine.of(VibratoLine.RISE.points()))
        assertNull(VibratoLine.of(two))

        // Flat before the first point and after the last; soft, straight or steep in between.
        val s = VibratoSettings(seconds = 4, points = listOf(LinePoint(0.25f, 0f), LinePoint(0.75f, 100f, Bend.LINEAR)))
        assertEquals(0.0, s.lineAt(0.2), 1e-9)
        assertEquals(50.0, s.lineAt(2.0), 1e-6)
        assertEquals(100.0, s.lineAt(3.9), 1e-9)
        val soft = s.copy(points = listOf(LinePoint(0.25f, 0f), LinePoint(0.75f, 100f, Bend.SMOOTH)))
        assertEquals(50.0, soft.lineAt(2.0), 1e-6)
        assertTrue(soft.lineAt(1.25) < 15)
        val steep = s.copy(points = listOf(LinePoint(0.25f, 0f), LinePoint(0.75f, 100f, Bend.STEEP)))
        assertEquals(0.0, steep.lineAt(2.9), 1e-9)
        assertEquals(100.0, steep.lineAt(3.0), 1e-6)
    }

    @Test
    fun aSungVibratoBecomesALine() {
        // 4 s at 5.5 Hz, 60 cents wide, on a slow rise: as the tracker would hand it over.
        val curve = FloatArray(400) { i -> val t = i / 100.0; (30 * sin(2 * kotlin.math.PI * 5.5 * t) + 10 * t).toFloat() }
        val points = VibratoLine.fromCurve(curve)
        assertTrue(points.size in 30..200, "${points.size} points")
        val model = VibratoSettings(seconds = 4, width = 0, points = points)
        val worst = (5..395).maxOf { i -> abs(model.lineAt(i / 100.0) - curve[i]) }
        assertTrue(worst < 8, "worst $worst")
        // Played back and measured, it is the same vibrato again.
        val result = assertNotNull(VibratoAnalysis.analyse(VibratoSynth.render(model, 440.0, vibrato = true), model.copy(width = 60, points = emptyList()), 440.0))
        assertTrue(abs(result.rate - 5.5) < 0.2, "rate ${result.rate}")
        assertTrue(abs(result.width - 60) < 9, "width ${result.width}")
    }

    @Test
    fun silenceGivesNothing() {
        assertNull(VibratoAnalysis.analyse(widder.audio.Pcm(44100, listOf(FloatArray(44100 * 2))), VibratoSettings(), 440.0))
    }
}

/** A microphone that hands its samples over in big packets, as some devices do. */
private class ChunkyRecorder(private val samples: FloatArray, private val chunk: Int) : widder.audio.AudioRecorder {
    var delivered = 0
    fun deliver() {
        delivered = minOf(samples.size, delivered + chunk)
    }
    override fun start() = true
    override fun level() = 0f
    override fun stop(): widder.audio.Pcm? = null
    override val isRecording = true
    override val recorded: Int get() = delivered
    override fun latest(into: FloatArray): Int {
        val n = minOf(into.size, delivered)
        for (i in 0 until n) into[into.size - n + i] = samples[delivered - n + i]
        return n
    }
}

class PitchFollowerTest {
    @Test
    fun evenCurveFromPacketedInput() {
        val s = VibratoSettings(rate = 5f, width = 80, seconds = 3)
        val tone = VibratoSynth.render(s, 440.0, vibrato = true).channels[0]
        val mic = ChunkyRecorder(tone, (0.15 * 44100).toInt())
        val follower = widder.audio.PitchFollower(mic)
        val readings = ArrayList<Pair<Double, Float>>()
        // Polled more often than packets come, and sometimes not for a while.
        repeat(60) { i ->
            if (i % 3 == 0) mic.deliver()
            readings += follower.poll()
        }
        val times = readings.map { it.first }
        assertTrue(times.size > 150, "${times.size}")
        assertTrue(times.zipWithNext().all { (a, b) -> abs(b - a - 0.01) < 1e-6 }, "uneven")
        // Each reading matches the swing at the moment it is stamped with.
        val f0 = PracticeEngine.frequency(s.pitch, 440.0)
        val errors = readings.filter { it.first in 0.3..2.5 && !it.second.isNaN() }.map { (t, f) ->
            val heard = 1200 * log2(f / f0)
            abs(heard - s.cents(t))
        }
        assertTrue(errors.sorted()[errors.size / 2] < 3, "median ${errors.sorted()[errors.size / 2]}")
    }
}

package widder

import widder.audio.Effects
import widder.audio.Pcm
import widder.audio.Synth
import widder.audio.Wav
import widder.core.AudioEdit
import widder.music.Chord
import widder.music.Midi
import widder.music.MidiAnalysis
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.sin
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class AudioTest {
    private fun sine(seconds: Double, amp: Float = 0.5f, rate: Int = 44100) =
        Pcm(rate, listOf(FloatArray((seconds * rate).toInt()) { (amp * sin(2 * PI * 440 * it / rate)).toFloat() }))

    @Test
    fun wavRoundTrip() {
        val pcm = sine(0.1)
        val back = Wav.read(Wav.write(pcm))
        assertEquals(pcm.sampleRate, back.sampleRate)
        assertEquals(pcm.frames, back.frames)
        for (i in 0 until pcm.frames step 97) assertTrue(abs(pcm.channels[0][i] - back.channels[0][i]) < 1e-3f)
    }

    @Test
    fun trimGainAndNormalize() {
        val pcm = sine(2.0)
        val trimmed = Effects.render(pcm, AudioEdit(trimStart = 0.5, trimEnd = 1.0))
        assertEquals(pcm.sampleRate / 2, trimmed.frames)

        val quieter = Effects.render(pcm, AudioEdit(gainDb = -6.0))
        assertTrue(abs(quieter.peak() - 0.25f) < 0.01f, "peak ${quieter.peak()}")

        val normalized = Effects.render(pcm, AudioEdit(normalize = true))
        assertTrue(normalized.peak() in 0.85f..0.95f, "peak ${normalized.peak()}")
    }

    @Test
    fun reverbAddsATailAndStereo() {
        val pcm = sine(0.5)
        val wet = Effects.render(pcm, AudioEdit(reverbMix = 0.5, reverbSize = 0.8))
        assertEquals(2, wet.channels.size)
        assertTrue(wet.frames > pcm.frames + pcm.sampleRate)
        // The room still rings after the source has stopped.
        assertTrue(wet.peak(pcm.frames + 2000, pcm.frames + 8000) > 0.001f)
    }

    @Test
    fun fadesStartAndEndSilent() {
        val out = Effects.render(sine(1.0), AudioEdit(fadeIn = 0.2, fadeOut = 0.2))
        assertTrue(out.peak(0, 100) < 0.01f)
        assertTrue(out.peak(out.frames - 100, out.frames) < 0.01f)
    }

    @Test
    fun midiRoundTripAndChordAnalysis() {
        val chords = listOf("A", "D", "G", "F").map { Chord.parse(it)!! }
        val bytes = Midi.write(MidiAnalysis.progression(chords))
        val midi = Midi.parse(bytes)
        assertEquals(16, midi.notes.size)
        assertEquals(listOf("A", "D", "G", "F"), MidiAnalysis.chords(midi).map { it.name() })
    }

    @Test
    fun minorProgressionFromMidi() {
        val chords = listOf("Fm", "Gm", "Cm7").map { Chord.parse(it)!! }
        val midi = Midi.parse(Midi.write(MidiAnalysis.progression(chords, 1.0), bpm = 90))
        assertEquals(listOf("Fm", "Gm", "Cm7"), MidiAnalysis.chords(midi).map { it.name() })
        assertTrue(abs(midi.duration - 2.95) < 0.05, "duration ${midi.duration}")
    }

    @Test
    fun synthProducesSound() {
        val midi = Midi.parse(Midi.write(MidiAnalysis.progression(listOf(Chord.parse("C")!!), 0.5)))
        val pcm = Synth.render(midi)
        assertEquals(2, pcm.channels.size)
        assertTrue(pcm.peak() > 0.1f)
        assertTrue(pcm.duration > 0.5)
    }
}

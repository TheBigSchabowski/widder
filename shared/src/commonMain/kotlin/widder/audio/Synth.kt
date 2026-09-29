package widder.audio

import widder.music.MidiFile
import widder.music.Note
import kotlin.math.PI
import kotlin.math.exp
import kotlin.math.min
import kotlin.math.pow
import kotlin.math.sin
import kotlin.random.Random

/**
 * A small additive piano-like synth that turns MIDI notes into audio. It is the same code on
 * Android and the Mac, so a MIDI card sounds identical everywhere and runs through the same
 * effects (volume, reverb ...) as a recording.
 */
object Synth {
    const val SAMPLE_RATE = 44100
    private const val TABLE_SIZE = 4096
    private val sine = FloatArray(TABLE_SIZE) { sin(2 * PI * it / TABLE_SIZE).toFloat() }

    /** Relative strength of the overtones; higher ones also die away faster. */
    private val harmonics = floatArrayOf(1f, 0.45f, 0.22f, 0.12f, 0.07f, 0.04f)
    private const val RELEASE = 0.25

    fun render(midi: MidiFile, sampleRate: Int = SAMPLE_RATE): Pcm = render(midi.notes, sampleRate)

    fun render(notes: List<Note>, sampleRate: Int = SAMPLE_RATE): Pcm {
        val length = (notes.maxOfOrNull { it.end } ?: 0.0) + RELEASE + 0.3
        val frames = (length * sampleRate).toInt().coerceAtLeast(1)
        val left = FloatArray(frames)
        val right = FloatArray(frames)
        for (note in notes) {
            if (note.channel == 9) drum(note, left, right, sampleRate) else tone(note, left, right, sampleRate)
        }
        val peak = Pcm(sampleRate, listOf(left, right)).peak()
        if (peak > 0.95f) {
            val g = 0.95f / peak
            for (i in 0 until frames) {
                left[i] *= g
                right[i] *= g
            }
        }
        return Pcm(sampleRate, listOf(left, right))
    }

    private fun tone(note: Note, left: FloatArray, right: FloatArray, rate: Int) {
        val freq = 440.0 * 2.0.pow((note.pitch - 69) / 12.0)
        val amp = 0.16f * (note.velocity / 127f).pow(1.3f)
        // Low notes ring longer, like on a piano.
        val decay = (2.8 * 2.0.pow(-(note.pitch - 48) / 24.0)).coerceIn(0.4, 5.0)
        val pan = ((note.pitch - 60) / 48f).coerceIn(-0.5f, 0.5f)
        val gl = amp * (1f - pan) * 0.8f
        val gr = amp * (1f + pan) * 0.8f
        val start = (note.start * rate).toInt()
        val held = ((note.end - note.start) * rate).toInt().coerceAtLeast(1)
        val release = (RELEASE * rate).toInt()
        val end = min(left.size, start + held + release)
        val attack = (0.004 * rate).toInt().coerceAtLeast(1)
        val count = harmonics.indices.count { freq * (it + 1) < rate / 2.2 }
        for (h in 0 until count) {
            val inc = freq * (h + 1) * TABLE_SIZE / rate
            val hAmp = harmonics[h]
            val hDecay = exp(-1.0 / (decay / (1 + h * 0.7) * rate)).toFloat()
            var env = 1f
            var phase = (h * 0.37 * TABLE_SIZE)
            var i = start
            while (i < end) {
                val t = i - start
                var e = env
                if (t < attack) e *= t.toFloat() / attack
                if (t > held) e *= 1f - (t - held).toFloat() / release
                val s = sine[phase.toInt() and (TABLE_SIZE - 1)] * hAmp * e
                left[i] += s * gl
                right[i] += s * gr
                phase += inc
                if (phase >= TABLE_SIZE) phase -= TABLE_SIZE
                env *= hDecay
                if (env < 0.0005f) break
                i++
            }
        }
    }

    private fun drum(note: Note, left: FloatArray, right: FloatArray, rate: Int) {
        val random = Random(note.pitch * 31 + (note.start * 1000).toInt())
        val start = (note.start * rate).toInt()
        val length = (if (note.pitch in 35..36) 0.25 else 0.12) * rate
        val amp = 0.2f * note.velocity / 127f
        val kick = note.pitch in 35..36
        var smooth = 0f
        for (t in 0 until length.toInt()) {
            val i = start + t
            if (i >= left.size) break
            val env = exp(-t / (length / 5)).toFloat()
            val s = if (kick) {
                sin(2 * PI * (50 + 80 * exp(-t / (0.03 * rate))) * t / rate).toFloat()
            } else {
                smooth += (random.nextFloat() * 2 - 1 - smooth) * 0.6f
                smooth
            }
            left[i] += s * amp * env
            right[i] += s * amp * env
        }
    }
}

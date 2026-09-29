package widder.audio

import widder.core.AudioEdit
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.max
import kotlin.math.min
import kotlin.math.pow
import kotlin.math.sin
import kotlin.math.sqrt

/** Applies an [AudioEdit] to the source audio. The source is never changed. */
object Effects {
    fun dbToGain(db: Double): Float = 10.0.pow(db / 20.0).toFloat()

    /** Linear gain that [edit] applies to the selection (normalising plus the volume slider). */
    fun gainFor(source: Pcm, edit: AudioEdit): Float {
        var g = dbToGain(edit.gainDb)
        if (edit.normalize) {
            val from = source.frameAt(edit.trimStart)
            val to = edit.trimEnd?.let { source.frameAt(it) } ?: source.frames
            val peak = source.peak(from, to)
            if (peak > 1e-4f) g *= dbToGain(-1.0) / peak
        }
        return g
    }

    fun render(source: Pcm, edit: AudioEdit): Pcm {
        if (edit.isNeutral) return source
        val from = source.frameAt(edit.trimStart)
        val to = edit.trimEnd?.let { source.frameAt(it) } ?: source.frames
        val gain = gainFor(source, edit)
        var pcm = source.slice(from, max(from, to))
        val rate = pcm.sampleRate
        pcm = Pcm(rate, pcm.channels.map { it.copyOf() })

        if (edit.lowCut > 0) pcm.channels.forEach { Biquad.highPass(rate, edit.lowCut).process(it) }
        if (edit.highCut > 0 && edit.highCut < rate / 2.0) pcm.channels.forEach { Biquad.lowPass(rate, edit.highCut).process(it) }

        val frames = pcm.frames
        val fadeIn = min(frames, (edit.fadeIn * rate).toInt())
        val fadeOut = min(frames, (edit.fadeOut * rate).toInt())
        for (ch in pcm.channels) {
            for (i in 0 until frames) {
                var g = gain
                if (i < fadeIn) g *= i.toFloat() / fadeIn
                if (i >= frames - fadeOut) g *= (frames - i).toFloat() / fadeOut
                ch[i] *= g
            }
        }

        if (edit.reverbMix > 0.001) pcm = Freeverb(rate, edit.reverbSize, edit.reverbDamping).process(pcm, edit.reverbMix)
        for (ch in pcm.channels) for (i in ch.indices) ch[i] = softClip(ch[i])
        return pcm
    }

    /** Leaves the normal range alone and rounds off what would otherwise clip hard. */
    private fun softClip(x: Float): Float = when {
        x > 0.9f -> 0.9f + 0.1f * kotlin.math.tanh((x - 0.9f) / 0.1f)
        x < -0.9f -> -0.9f + 0.1f * kotlin.math.tanh((x + 0.9f) / 0.1f)
        else -> x
    }
}

/** RBJ cookbook second-order filter. */
class Biquad private constructor(
    private val b0: Double,
    private val b1: Double,
    private val b2: Double,
    private val a1: Double,
    private val a2: Double,
) {
    fun process(x: FloatArray) {
        var x1 = 0.0
        var x2 = 0.0
        var y1 = 0.0
        var y2 = 0.0
        for (i in x.indices) {
            val x0 = x[i].toDouble()
            val y0 = b0 * x0 + b1 * x1 + b2 * x2 - a1 * y1 - a2 * y2
            x2 = x1; x1 = x0; y2 = y1; y1 = y0
            x[i] = y0.toFloat()
        }
    }

    companion object {
        private const val Q = 0.7071

        fun lowPass(rate: Int, cutoff: Double): Biquad {
            val w = 2 * PI * cutoff / rate
            val alpha = sin(w) / (2 * Q)
            val c = cos(w)
            val a0 = 1 + alpha
            return Biquad((1 - c) / 2 / a0, (1 - c) / a0, (1 - c) / 2 / a0, -2 * c / a0, (1 - alpha) / a0)
        }

        fun highPass(rate: Int, cutoff: Double): Biquad {
            val w = 2 * PI * cutoff / rate
            val alpha = sin(w) / (2 * Q)
            val c = cos(w)
            val a0 = 1 + alpha
            return Biquad((1 + c) / 2 / a0, -(1 + c) / a0, (1 + c) / 2 / a0, -2 * c / a0, (1 - alpha) / a0)
        }
    }
}

/**
 * Jezar's Freeverb: eight parallel comb filters and four allpasses per side, the right side
 * slightly detuned for width. Adds a tail so the room can ring out after the last note.
 */
class Freeverb(private val rate: Int, size: Double, damping: Double) {
    private val scale = rate / 44100.0
    private val combTuning = intArrayOf(1116, 1188, 1277, 1356, 1422, 1491, 1557, 1617)
    private val allpassTuning = intArrayOf(556, 441, 341, 225)
    private val spread = 23
    private val feedback = (size.coerceIn(0.0, 1.0) * 0.28 + 0.7).toFloat()
    private val damp = (damping.coerceIn(0.0, 1.0) * 0.4).toFloat()
    val tailSeconds = 0.5 + size.coerceIn(0.0, 1.0) * 2.5

    private class Comb(size: Int) {
        val buf = FloatArray(size)
        var idx = 0
        var store = 0f
        fun process(input: Float, feedback: Float, damp: Float): Float {
            val out = buf[idx]
            store = out * (1 - damp) + store * damp
            buf[idx] = input + store * feedback
            if (++idx >= buf.size) idx = 0
            return out
        }
    }

    private class Allpass(size: Int) {
        val buf = FloatArray(size)
        var idx = 0
        fun process(input: Float): Float {
            val b = buf[idx]
            buf[idx] = input + b * 0.5f
            if (++idx >= buf.size) idx = 0
            return b - input
        }
    }

    fun process(input: Pcm, mix: Double): Pcm {
        val tail = (tailSeconds * rate).toInt()
        val frames = input.frames + tail
        val inL = input.channels[0]
        val inR = input.channels.getOrElse(1) { inL }
        val outL = FloatArray(frames)
        val outR = FloatArray(frames)
        val combsL = combTuning.map { Comb((it * scale).toInt()) }
        val combsR = combTuning.map { Comb(((it + spread) * scale).toInt()) }
        val apL = allpassTuning.map { Allpass((it * scale).toInt()) }
        val apR = allpassTuning.map { Allpass(((it + spread) * scale).toInt()) }
        val wet = mix.coerceIn(0.0, 1.0).toFloat()
        // Equal-power crossfade keeps the loudness roughly level while turning the reverb up.
        val dry = sqrt(1 - wet * wet * 0.75f)
        val wetGain = wet * 3f
        for (i in 0 until frames) {
            val l = if (i < inL.size) inL[i] else 0f
            val r = if (i < inR.size) inR[i] else 0f
            val x = (l + r) * 0.015f
            var sl = 0f
            var sr = 0f
            for (c in combsL) sl += c.process(x, feedback, damp)
            for (c in combsR) sr += c.process(x, feedback, damp)
            for (a in apL) sl = a.process(sl)
            for (a in apR) sr = a.process(sr)
            outL[i] = l * dry + sl * wetGain
            outR[i] = r * dry + sr * wetGain
        }
        return Pcm(rate, listOf(outL, outR))
    }
}

/** Keeps the rendered audio of the last few clips, so replaying a card does not render again. */
class RenderCache(private val capacity: Int = 12) {
    private val entries = LinkedHashMap<String, Pcm>()

    fun get(key: String): Pcm? = entries.remove(key)?.also { entries[key] = it }

    fun put(key: String, pcm: Pcm) {
        entries.remove(key)
        entries[key] = pcm
        while (entries.size > capacity) entries.remove(entries.keys.first())
    }

    fun clear() = entries.clear()
}

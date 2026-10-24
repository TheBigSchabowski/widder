package widder.audio

import kotlin.concurrent.Volatile
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.ceil
import kotlin.math.exp
import kotlin.math.floor
import kotlin.math.min
import kotlin.math.roundToLong
import kotlin.math.sin

/** Turns one recording into a loop: free, or cut on bar lines of the drums it was played to. */
object Looper {
    /** The seam is smoothed over this long: what came after the end fades into the start. */
    const val FADE_SECONDS = 0.015

    /**
     * Ties microphone samples to the drums' bar clock. [start] is the clock when recording began;
     * [latency] (seconds) is how much later a note played to a click arrives in the recording.
     */
    class Sync(private val start: PracticeEngine.Clock, private val micRate: Int, private val latency: Double) {
        /** The bar position that microphone sample [k] was played at. */
        fun barAt(k: Long): Double = start.bar + (k.toDouble() / micRate - latency) * start.rate / start.framesPerBar

        /** The microphone sample played at bar position [bar]. */
        fun sampleAt(bar: Double): Long = (((bar - start.bar) * start.framesPerBar / start.rate + latency) * micRate).roundToLong()

        /** The loop starts on the coming bar line, or on the one just passed when record came a moment late. */
        val firstBar: Long = barAt(0).let { if (it - floor(it) < 0.2) floor(it) else ceil(it) }.toLong()

        /** Where the loop closes when it is set at bar position [now]: the bar line just passed, if only just, else the next. */
        fun lastBar(now: Double): Long = maxOf(firstBar + 1, (if (now - floor(now) < 0.25) floor(now) else ceil(now)).toLong())
    }

    /**
     * [mic] from sample [from] to [to] (before 0 is silence), with the samples after [to] faded into
     * the start, so the loop runs on without a click.
     */
    fun cut(mic: FloatArray, from: Long, to: Long, rate: Int): FloatArray {
        val n = (to - from).toInt().coerceAtLeast(1)
        fun at(k: Long) = if (k >= 0 && k < mic.size) mic[k.toInt()] else 0f
        val out = FloatArray(n) { at(from + it) }
        val fade = min((FADE_SECONDS * rate).toInt(), n / 4)
        for (i in 0 until fade) {
            val g = i.toFloat() / fade
            out[i] = out[i] * g + at(to + i) * (1 - g)
        }
        return out
    }

    /** All of [mic] as a loop; the last few milliseconds make the seam. */
    fun free(mic: FloatArray, rate: Int): FloatArray {
        val fade = (FADE_SECONDS * rate).toLong()
        return cut(mic, 0, (mic.size - fade).coerceAtLeast(1), rate)
    }

    /** Loop samples played per second: the loop's own rate when free, from the tempo when locked to the bar clock. */
    fun speed(loop: Loop, clock: PracticeEngine.Clock): Double =
        if (loop.bars > 0) loop.samples.size.toDouble() / loop.bars * clock.rate / clock.framesPerBar else loop.rate.toDouble()

    /**
     * Lays a new take over a loop while it plays: what was played at microphone sample k lands
     * where the loop was heard at that moment, pass after pass, until [finish]. [target] (the
     * loop's samples) is changed in place, always behind the play head.
     *
     * [start] is the loop position (in its samples) at the clock read when the microphone opened,
     * [speed] the loop samples played per second, [latency] as in [Sync].
     */
    class Overdub(
        private val target: FloatArray,
        private val start: Double,
        private val speed: Double,
        private val micRate: Int,
        private val latency: Double,
    ) {
        private val fade = (FADE_SECONDS * micRate).toLong().coerceAtLeast(1)

        /** The next loop position to fill, counted on over the passes (taken modulo the loop's length). */
        private var next = ceil(start - latency * speed).toLong()

        /** The microphone sample (fractional) that was played at loop position [q]. */
        private fun micAt(q: Long): Double = ((q - start) / speed + latency) * micRate

        /**
         * Lays in what [mic] holds: its last sample is microphone sample [end] - 1, so it starts at
         * [end] - mic.size. The last few milliseconds wait for the next call, or for [finish].
         */
        fun add(mic: FloatArray, end: Long) = lay(mic, end, end - fade, null)

        /** The last of it, faded out at [end] so it does not click; nothing more comes after. */
        fun finish(mic: FloatArray, end: Long) = lay(mic, end, end - 1, end)

        private fun lay(mic: FloatArray, end: Long, upTo: Long, fadeOutAt: Long?) {
            val n = target.size
            if (n == 0) return
            val first = end - mic.size
            while (true) {
                val k = micAt(next)
                val j = floor(k).toLong()
                if (j + 1 > upTo) break
                if (j >= first) {
                    val f = (k - j).toFloat()
                    val a = mic[(j - first).toInt()]
                    val b = mic[(j + 1 - first).toInt().coerceAtMost(mic.size - 1)]
                    var g = min(1.0, k / fade)
                    if (fadeOutAt != null) g = min(g, (fadeOutAt - k) / fade)
                    val i = (next % n + n) % n
                    target[i.toInt()] += (a + (b - a) * f) * g.coerceAtLeast(0.0).toFloat()
                }
                next++
            }
        }
    }
}

/**
 * A few clicks for measuring the latency from output to microphone: [COUNT] of them, [SPACING]
 * seconds apart, after a moment of silence, then silence again.
 */
class LatencyProbe : LiveSource {
    @Volatile var rate = Synth.SAMPLE_RATE
        private set

    /** Frames rendered since the output opened: the clock the clicks are placed on. */
    @Volatile var frames = 0L
        private set

    private var click = FloatArray(0)

    override fun prepare(sampleRate: Int) {
        rate = sampleRate
        frames = 0
        // 6 ms of 2 kHz, sharp at the front: even a phone's small speaker plays it loud and clear.
        click = FloatArray((0.006 * sampleRate).toInt()) { i -> (0.8 * sin(2 * PI * 2000 * i / sampleRate) * exp(-i / (0.0015 * sampleRate))).toFloat() }
    }

    /** The frame click [i] starts on. */
    fun clickFrame(i: Int): Long = ((START + i * SPACING) * rate).toLong()

    /** The frame after which nothing more can be heard. */
    val endFrame: Long get() = clickFrame(COUNT - 1) + (SPACING * rate).toLong()

    override fun render(left: FloatArray, right: FloatArray, frames: Int) {
        val spacing = (SPACING * rate).toLong()
        val first = clickFrame(0)
        for (i in 0 until frames) {
            val t = this.frames + i - first
            val j = if (t >= 0 && t < COUNT * spacing) t % spacing else -1
            val out = if (j in click.indices) click[j.toInt()] else 0f
            left[i] = out
            right[i] = out
        }
        this.frames += frames
    }

    companion object {
        const val COUNT = 6
        const val SPACING = 0.6
        const val START = 0.4

        /** Longer than this, and a click would be taken for the next. */
        const val MAX_LATENCY = 0.55

        /**
         * The latency in seconds, from a recording that began at output frame [startFrame]: the
         * median of how long each click took to arrive. Null when too few were heard (headphones).
         */
        fun measure(probe: LatencyProbe, mic: FloatArray, micRate: Int, startFrame: Long): Double? {
            val sent = (0 until COUNT).map { (probe.clickFrame(it) - startFrame).toDouble() / probe.rate }.filter { it >= 0 }
            val noise = sent.firstOrNull()?.let { quiet -> peak(mic, 0, (quiet * micRate).toInt()) } ?: return null
            val found = sent.mapNotNull { at ->
                val from = (at * micRate).toInt()
                val to = ((at + MAX_LATENCY) * micRate).toInt().coerceAtMost(mic.size)
                val top = peak(mic, from, to)
                if (top < 0.02f || top < 4 * noise) return@mapNotNull null
                // The front of the click: the first sample that is loud enough, not its loudest echo.
                (from until to).firstOrNull { abs(mic[it]) >= top * 0.3f }?.let { it.toDouble() / micRate - at }
            }
            if (found.size < (sent.size + 1) / 2) return null
            return found.sorted()[found.size / 2]
        }

        private fun peak(mic: FloatArray, from: Int, to: Int): Float {
            var p = 0f
            for (i in from.coerceAtLeast(0) until to.coerceAtMost(mic.size)) p = maxOf(p, abs(mic[i]))
            return p
        }
    }
}

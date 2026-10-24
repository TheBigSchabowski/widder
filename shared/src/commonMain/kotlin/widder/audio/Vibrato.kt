package widder.audio

import widder.core.Bend
import widder.core.LinePoint
import widder.core.VibratoDirection
import widder.core.VibratoSettings
import widder.core.VibratoSound
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.exp
import kotlin.math.ln
import kotlin.math.log2
import kotlin.math.pow
import kotlin.math.roundToInt
import kotlin.math.sin
import kotlin.math.sqrt

/** The vibrato trainer's model tone: one sustained note, straight or swinging as the settings say. */
object VibratoSynth {
    private const val TABLE_SIZE = 4096

    fun render(s: VibratoSettings, tuning: Double, vibrato: Boolean, rate: Int = Synth.SAMPLE_RATE): Pcm {
        val f0 = PracticeEngine.frequency(s.pitch, tuning)
        val table = wavetable(s.sound, f0)
        val frames = ((s.seconds + RELEASE) * rate).toInt()
        val out = FloatArray(frames)
        val attack = (if (s.sound == VibratoSound.STRINGS || s.sound == VibratoSound.VOICE) 0.12 else 0.05) * rate
        val held = s.seconds * rate
        var phase = 0.0
        for (i in 0 until frames) {
            val t = i.toDouble() / rate
            val f = if (vibrato) f0 * 2.0.pow(s.cents(t) / 1200) else f0
            var env = 1.0
            if (i < attack) env = i / attack
            if (i > held) env = (1 - (i - held) / (RELEASE * rate)).coerceAtLeast(0.0)
            out[i] = (lookup(table, phase) * env * AMP).toFloat()
            phase += f * TABLE_SIZE / rate
            if (phase >= TABLE_SIZE) phase -= TABLE_SIZE
        }
        return Pcm(rate, listOf(out, out.copyOf()))
    }

    private fun wavetable(sound: VibratoSound, f0: Double): FloatArray {
        val harmonics: List<Double> = when (sound) {
            VibratoSound.SINE -> listOf(1.0)
            VibratoSound.FLUTE -> listOf(1.0, 0.32, 0.12, 0.05, 0.02)
            VibratoSound.ORGAN -> listOf(1.0, 0.55, 0.3, 0.25, 0.0, 0.1, 0.0, 0.12)
            VibratoSound.STRINGS -> (1..14).map { 1.0 / it.toDouble().pow(1.15) }
            // An "ah": the partials near the formants (750, 1200, 2600 Hz) are the strong ones.
            VibratoSound.VOICE -> (1..24).map { h ->
                val f = h * f0
                fun formant(center: Double, width: Double, gain: Double) = gain * exp(-((f - center) / width).pow(2))
                (0.15 + formant(750.0, 160.0, 1.0) + formant(1200.0, 200.0, 0.6) + formant(2600.0, 300.0, 0.25)) / h.toDouble().pow(0.6)
            }
        }
        // Nothing above about 16 kHz, so a wide swing on a high note does not fold back.
        val usable = harmonics.withIndex().filter { (h, _) -> f0 * (h + 1) * 1.15 < 16_000 }
        val table = FloatArray(TABLE_SIZE) { i ->
            usable.sumOf { (h, a) -> a * sin(2 * PI * (h + 1) * i / TABLE_SIZE + h * 0.9) }.toFloat()
        }
        val peak = table.maxOf { abs(it) }.takeIf { it > 0f } ?: 1f
        for (i in table.indices) table[i] /= peak
        return table
    }

    private fun lookup(table: FloatArray, phase: Double): Double {
        val j = phase.toInt()
        val f = phase - j
        val a = table[j and (TABLE_SIZE - 1)]
        val b = table[(j + 1) and (TABLE_SIZE - 1)]
        return a + (b - a) * f
    }

    private const val RELEASE = 0.2
    private const val AMP = 0.45
}

/**
 * Finds the pitch of a single voice or instrument with the YIN method (de Cheveigné & Kawahara), on
 * audio halved to about 22 kHz, which is plenty for fundamentals and four times less work.
 */
object PitchTracker {
    /** One value of a pitch curve per this many seconds. */
    const val HOP_SECONDS = 0.01
    const val MIN_FREQ = 70.0
    /** Low enough for a five-string bass (B0, 31 Hz). */
    const val LOWEST = 28.0
    private const val MAX_FREQ = 1500.0
    private const val THRESHOLD = 0.15
    private const val WINDOW_SECONDS = 0.023

    /** How many samples before the end of a [detect] frame the moment lies that the reading stands for (its window's middle). */
    fun lag(rate: Int, minFreq: Double = MIN_FREQ): Int = window(rate / 2, minFreq) + 2 * maxLag(rate / 2, minFreq)

    /** Samples [detect] needs at [rate], down to [minFreq] Hz. */
    fun frameSize(rate: Int, minFreq: Double = MIN_FREQ): Int = 2 * (window(rate / 2, minFreq) + maxLag(rate / 2, minFreq)) + 2

    /** The frequency per [HOP_SECONDS] of [samples], NaN where nothing clear sounds. */
    fun track(samples: FloatArray, rate: Int): FloatArray {
        val x = halve(samples, 0, samples.size)
        val r = rate / 2
        val w = window(r)
        val maxLag = maxLag(r)
        val hop = (r * HOP_SECONDS).roundToInt()
        val count = ((x.size - w - maxLag) / hop + 1).coerceAtLeast(0)
        // Quiet frames are not looked at: below a tenth of the loudest stretch, or plain silence.
        val loudest = (0 until count).maxOfOrNull { rms(x, it * hop, w) } ?: 0f
        val gate = maxOf(0.004f, loudest * 0.1f)
        val diff = FloatArray(maxLag + 1)
        return FloatArray(count) { detectAt(x, it * hop, r, w, maxLag, gate, diff) }
    }

    /** The frequency of the newest [count] samples in [samples] (see [frameSize]), NaN if unclear. */
    fun detect(samples: FloatArray, count: Int, rate: Int, gate: Float = 0.01f, minFreq: Double = MIN_FREQ): Float {
        val r = rate / 2
        val w = window(r, minFreq)
        val maxLag = maxLag(r, minFreq)
        val x = halve(samples, 0, count)
        if (x.size < w + maxLag) return Float.NaN
        return detectAt(x, x.size - w - maxLag, r, w, maxLag, gate, FloatArray(maxLag + 1))
    }

    /** At least about one and a half periods of the lowest note, so a bass string is seen whole. */
    private fun window(rate: Int, minFreq: Double = MIN_FREQ) = maxOf(rate * WINDOW_SECONDS, rate * 1.3 / minFreq).toInt()
    private fun maxLag(rate: Int, minFreq: Double = MIN_FREQ) = (rate / minFreq).toInt() + 2

    private fun halve(samples: FloatArray, from: Int, to: Int): FloatArray =
        FloatArray((to - from) / 2) { (samples[from + 2 * it] + samples[from + 2 * it + 1]) * 0.5f }

    private fun rms(x: FloatArray, from: Int, w: Int): Float {
        var sum = 0.0
        for (j in from until from + w) sum += x[j] * x[j]
        return sqrt(sum / w).toFloat()
    }

    private fun detectAt(x: FloatArray, from: Int, rate: Int, w: Int, maxLag: Int, gate: Float, d: FloatArray): Float {
        if (rms(x, from, w) < gate) return Float.NaN
        val minLag = (rate / MAX_FREQ).toInt().coerceAtLeast(2)
        for (tau in 1..maxLag) {
            var sum = 0f
            for (j in from until from + w) {
                val delta = x[j] - x[j + tau]
                sum += delta * delta
            }
            d[tau] = sum
        }
        // Cumulative mean normalised difference: 1 on average, near 0 at the period.
        var running = 0f
        d[0] = 1f
        for (tau in 1..maxLag) {
            running += d[tau]
            d[tau] = if (running > 0f) d[tau] * tau / running else 1f
        }
        var best = -1
        var tau = minLag
        while (tau < maxLag) {
            if (d[tau] < THRESHOLD) {
                while (tau + 1 < maxLag && d[tau + 1] < d[tau]) tau++
                best = tau
                break
            }
            tau++
        }
        if (best < 0) {
            best = (minLag until maxLag).minBy { d[it] }
            if (d[best] > 0.35f) return Float.NaN
        }
        // A parabola through the minimum and its neighbours for the period between two samples.
        val a = d[best - 1]
        val b = d[best]
        val c = d[(best + 1).coerceAtMost(maxLag)]
        val bend = a - 2 * b + c
        val shift = if (bend > 0f) (0.5f * (a - c) / bend).coerceIn(-1f, 1f) else 0f
        return (rate / (best + shift)).toFloat()
    }
}

/**
 * The pitch of what the microphone hears, every [hopSeconds] of audio, timed by the samples
 * themselves: however the device delivers them (in big packets on some), the curve comes out even.
 */
class PitchFollower(private val recorder: AudioRecorder, private val minFreq: Double = PitchTracker.MIN_FREQ, hopSeconds: Double = PitchTracker.HOP_SECONDS) {
    private val rate = recorder.sampleRate
    private val frame = PitchTracker.frameSize(rate, minFreq)
    private val hop = (rate * hopSeconds).roundToInt().coerceAtLeast(1)
    private val lag = PitchTracker.lag(rate, minFreq)
    private val start = recorder.total
    private var next = start + frame
    private val buffer = FloatArray(rate + frame)

    /** New readings since the last call: seconds since the start and Hz (NaN where nothing clear sounds). */
    fun poll(): List<Pair<Double, Float>> {
        val total = recorder.snapshot(buffer)
        if (next > total) return emptyList()
        // Far behind (the app was busy): skip ahead rather than catch up on stale sound.
        if (total - next > rate) next = total - rate / 4
        val out = ArrayList<Pair<Double, Float>>()
        val bufferStart = total - buffer.size
        while (next <= total) {
            val end = (next - bufferStart).toInt()
            val f = PitchTracker.detect(buffer.copyOfRange(end - frame, end), frame, rate, minFreq = minFreq)
            out += (next - lag - start).toDouble() / rate to f
            next += hop
        }
        return out
    }
}

/** Ready-made lines for the model tone; from there, points are added, moved and removed by hand. */
enum class VibratoLine(val label: String) {
    STRAIGHT("Gerade"),
    RISE("Steigend"),
    FALL("Fallend"),
    SCOOP("Von unten"),
    FALL_OFF("Fall-off"),
    BEND("Bending"),
    ARCH("Bogen");

    fun points(): List<LinePoint> = when (this) {
        STRAIGHT -> emptyList()
        RISE -> listOf(LinePoint(0f, -100f), LinePoint(1f, 100f, Bend.LINEAR))
        FALL -> listOf(LinePoint(0f, 100f), LinePoint(1f, -100f, Bend.LINEAR))
        SCOOP -> listOf(LinePoint(0f, -100f), LinePoint(0.15f, 0f))
        FALL_OFF -> listOf(LinePoint(0.7f, 0f), LinePoint(1f, -250f))
        BEND -> listOf(LinePoint(0.15f, 0f), LinePoint(0.3f, 200f), LinePoint(0.7f, 200f, Bend.LINEAR), LinePoint(0.85f, 0f))
        ARCH -> listOf(LinePoint(0f, 0f), LinePoint(0.5f, 100f), LinePoint(1f, 0f))
    }

    companion object {
        /** The ready-made line [points] are, or null for one made by hand. */
        fun of(points: List<LinePoint>): VibratoLine? = entries.firstOrNull { it.points() == points }

        /** Points never sit closer than this (0..1 over the tone). */
        private const val GAP = 0.005f

        private fun clampCents(c: Float) = c.coerceIn(-VibratoSettings.LINE_RANGE.toFloat(), VibratoSettings.LINE_RANGE.toFloat())

        /** [points] with a new one at [at] / [cents]; returns them and the new point's index. */
        fun add(points: List<LinePoint>, at: Float, cents: Float, bend: Bend = Bend.SMOOTH): Pair<List<LinePoint>, Int> {
            val u = at.coerceIn(0f, 1f)
            val i = points.indexOfFirst { it.at > u }.let { if (it < 0) points.size else it }
            val out = points.toMutableList()
            out.add(i, LinePoint(u, clampCents(cents), bend))
            return out to i
        }

        /** Point [index] moved to [at] / [cents], kept between its neighbours so the order stays. */
        fun move(points: List<LinePoint>, index: Int, at: Float, cents: Float, bend: Bend? = null): List<LinePoint> {
            if (index !in points.indices) return points
            val lo = if (index > 0) points[index - 1].at + GAP else 0f
            val hi = if (index < points.size - 1) points[index + 1].at - GAP else 1f
            val p = points[index]
            return points.toMutableList().also {
                it[index] = p.copy(at = at.coerceIn(lo, maxOf(lo, hi)), cents = clampCents(cents), bend = bend ?: p.bend)
            }
        }

        fun remove(points: List<LinePoint>, index: Int): List<LinePoint> = points.filterIndexed { i, _ -> i != index }

        fun bend(points: List<LinePoint>, index: Int, bend: Bend): List<LinePoint> =
            points.mapIndexed { i, p -> if (i == index) p.copy(bend = bend) else p }

        /**
         * A sung pitch curve ([curve] in cents per 10 ms, without gaps) as line points: only as many as it
         * takes to stay within [tolerance] cents, smooth between a peak and a trough, so a vibrato
         * comes back as a vibrato.
         */
        fun fromCurve(curve: FloatArray, tolerance: Float = 3f): List<LinePoint> {
            val n = curve.size
            if (n < 2) return emptyList()
            // A touch of smoothing: the tracker's last-cent jitter is not part of the line.
            val y = FloatArray(n) { i -> (curve[maxOf(0, i - 1)] + curve[i] + curve[minOf(n - 1, i + 1)]) / 3 }
            var tol = tolerance
            var kept: List<Int>
            do {
                kept = simplify(y, tol)
                tol *= 1.5f
            } while (kept.size > 400)
            // A turning point: the line comes up to it and goes down after, or the other way round.
            fun turns(k: Int): Boolean {
                if (k == 0 || k == kept.size - 1) return true
                return (y[kept[k]] - y[kept[k - 1]]) * (y[kept[k + 1]] - y[kept[k]]) < 0
            }
            // Point i stands for the moment i * 10 ms of a tone n * 10 ms long.
            return kept.mapIndexed { k, i ->
                val bend = if (k > 0 && turns(k) && turns(k - 1)) Bend.SMOOTH else Bend.LINEAR
                LinePoint(i / n.toFloat(), clampCents(y[i]), bend)
            }
        }

        /** Douglas–Peucker on the vertical distance: the indices to keep so the rest stays within [tol]. */
        private fun simplify(y: FloatArray, tol: Float): List<Int> {
            val keep = BooleanArray(y.size)
            keep[0] = true
            keep[y.size - 1] = true
            val stack = ArrayDeque<Pair<Int, Int>>()
            stack.addLast(0 to y.size - 1)
            while (stack.isNotEmpty()) {
                val (a, b) = stack.removeLast()
                var worst = -1
                var far = tol
                for (i in a + 1 until b) {
                    val line = y[a] + (y[b] - y[a]) * (i - a) / (b - a)
                    val d = abs(y[i] - line)
                    if (d > far) {
                        far = d
                        worst = i
                    }
                }
                if (worst >= 0) {
                    keep[worst] = true
                    stack.addLast(a to worst)
                    stack.addLast(worst to b)
                }
            }
            return y.indices.filter { keep[it] }
        }

        /** [cents] on the nearest step of [division] steps per octave. */
        fun snap(cents: Float, division: Int): Float {
            val step = 1200f / division
            return (cents / step).roundToInt() * step
        }
    }
}

/** What came out of an attempt, next to the same measures taken of the model. */
class VibratoResult(
    /** Cents from the note per [PitchTracker.HOP_SECONDS], NaN where silent; octave errors folded away. */
    val curve: FloatArray,
    /** The held tone, as indices into [curve]. */
    val from: Int,
    val to: Int,
    /** Swings per second, NaN when no regular swing was found. */
    val rate: Double,
    /** Lowest to highest point of the swing in cents. */
    val width: Double,
    /** Null when the tone hardly swings, or the model has no vibrato to compare with. */
    val direction: VibratoDirection?,
    /** How far the attempt's middle lies from the model's on average, in cents (+ = too high). */
    val offset: Double,
    /** How far the shape strays from the model's line apart from that offset: 95 % of the time within this many cents. */
    val shape: Double,
    val targetRate: Double,
    val targetWidth: Double,
    val target: VibratoSettings,
) {
    /** The model swings: speed and direction are worth comparing. */
    val targetSwings: Boolean get() = !targetRate.isNaN() && targetWidth >= MIN_SWING

    /** Held for much shorter than the model; the measures are then less sure. */
    val short: Boolean get() = (to - from) * PitchTracker.HOP_SECONDS < target.seconds * 0.6

    val rateOk: Boolean get() = !rate.isNaN() && abs(ln(rate / targetRate)) < ln(1.12)
    val widthOk: Boolean get() = if (targetSwings) abs(ln(width.coerceAtLeast(1.0) / targetWidth)) < ln(1.3) else width < maxOf(MIN_SWING, targetWidth * 1.3)
    val directionOk: Boolean get() = direction == target.direction
    val pitchOk: Boolean get() = abs(offset) < 15
    val shapeOk: Boolean get() = shape < 25

    /** 0..100, an equal share each for speed, width, direction, pitch and line (only what applies). */
    val score: Int get() {
        val parts = mutableListOf<Double>()
        if (targetSwings) {
            parts += if (rate.isNaN()) 0.0 else (1 - abs(ln(rate / targetRate)) / ln(1.5)).coerceIn(0.0, 1.0)
            parts += (1 - abs(ln(width.coerceAtLeast(1.0) / targetWidth)) / ln(2.0)).coerceIn(0.0, 1.0)
            parts += if (directionOk) 1.0 else 0.0
        } else {
            parts += (1 - (width - maxOf(MIN_SWING, targetWidth)) / 50).coerceIn(0.0, 1.0)
        }
        parts += (1 - abs(offset) / 50).coerceIn(0.0, 1.0)
        parts += (1 - (shape - 10) / 70).coerceIn(0.0, 1.0)
        return (parts.average() * 100).roundToInt()
    }

    companion object {
        /** Less swing than this (cents) counts as a straight tone. */
        const val MIN_SWING = 12.0
    }
}

object VibratoAnalysis {
    private val perSecond = (1 / PitchTracker.HOP_SECONDS).roundToInt()

    /** Cents of [freq] from [target] Hz, in the nearest octave. */
    fun cents(freq: Double, target: Double): Double {
        val c = 1200 * log2(freq / target)
        return c - 1200 * (c / 1200).roundToInt()
    }

    fun analyse(pcm: Pcm, target: VibratoSettings, tuning: Double): VibratoResult? {
        val f0 = PracticeEngine.frequency(target.pitch, tuning)
        val freqs = PitchTracker.track(pcm.channels[0], pcm.sampleRate)
        return analyse(FloatArray(freqs.size) { if (freqs[it].isNaN()) Float.NaN else cents(freqs[it].toDouble(), f0).toFloat() }, target)
    }

    /**
     * Measures [curve] (cents from the note, see [VibratoResult.curve]) against [target]; null if no tone
     * was held long enough. The held tone is stretched onto the model's length, so a line is compared
     * shape for shape even when the attempt is a little longer or shorter.
     */
    fun analyse(curve: FloatArray, target: VibratoSettings): VibratoResult? {
        val (start, end) = longestTone(curve) ?: return null
        val model = DoubleArray(target.seconds * perSecond) { target.cents(it.toDouble() / perSecond) }
        val x = DoubleArray(end - start) { curve[start + it].toDouble() }
        fillGaps(x)
        unfold(x, model.sorted()[model.size / 2])
        val y = median(x, 2)
        val folded = curve.copyOf()
        for (i in y.indices) folded[start + i] = y[i].toFloat()
        val n = y.size

        // The line: both middles (an average over one swing) side by side over the whole model, the
        // attempt moved by up to 0.15 s to where it fits best, as nobody starts on the very millisecond.
        // Where the attempt has ended (or not yet begun) its nearest value stands in.
        val swingFrom = if (target.width > 0) ((target.vibratoFrom + if (target.vibratoFrom > 0f) VibratoSettings.FADE_IN else 0.0) * perSecond).toInt() else 0
        val roughModel = swing(model.copyOfRange(swingFrom.coerceAtMost(model.size - perSecond / 2).coerceAtLeast(0), model.size))
        val roughSwing = swing(y.copyOfRange(minOf(perSecond / 4, n / 4), n))
        val window = (roughSwing.period.takeIf { !it.isNaN() } ?: roughModel.period.takeIf { !it.isNaN() } ?: (perSecond / 5.0)).roundToInt().coerceAtLeast(3)
        val userMiddle = movingAverage(y, window)
        val modelMiddle = movingAverage(model, window)
        val edge = perSecond / 10
        fun fit(shift: Int): Pair<Double, Double> {
            val diffs = (edge until model.size - edge).map { userMiddle[(it - shift).coerceIn(0, n - 1)] - modelMiddle[it] }
            val mean = diffs.average()
            // 95 % of the time within this much: a missed scoop shows, though it lasts only a moment.
            val spread = diffs.map { abs(it - mean) }.sorted()
            return mean to spread[(spread.size * 0.95).toInt().coerceAtMost(spread.size - 1)]
        }
        val shift = (-perSecond * 15 / 100..perSecond * 15 / 100).minBy { fit(it).second }
        val (offset, shape) = fit(shift)

        // The swing: where the model has its vibrato, leaving out the onset and the very end.
        val userFrom = maxOf(perSecond / 4, swingFrom - shift)
        val userTo = minOf(n, model.size - shift) - perSecond / 10
        val enough = userTo - userFrom >= perSecond * 3 / 5
        val userSwing = swing(if (enough) y.copyOfRange(userFrom, userTo) else y)
        // The model measured over the very same stretch, so a scoop or a fall counts the same on both sides.
        val modelSwing = if (enough) swing(model.copyOfRange(userFrom + shift, userTo + shift)) else roughModel

        // The direction: where the attempt's middle lies from the model's line, in the swinging part.
        val targetSwings = !modelSwing.period.isNaN() && modelSwing.width >= VibratoResult.MIN_SWING && target.width > 0
        val direction = if (!targetSwings || userSwing.width < VibratoResult.MIN_SWING || userTo <= userFrom) null else {
            val lift = (userFrom until userTo).map { userMiddle[it] - target.lineAt((it + shift).toDouble() / perSecond) }.average()
            listOf(VibratoDirection.UP to userSwing.width / 2, VibratoDirection.AROUND to 0.0, VibratoDirection.DOWN to -userSwing.width / 2)
                .minBy { abs(lift - it.second) }.first
        }
        return VibratoResult(
            folded, start, end,
            rate = if (userSwing.width < VibratoResult.MIN_SWING) Double.NaN else userSwing.rate,
            width = userSwing.width,
            direction = direction,
            offset = offset,
            shape = shape,
            targetRate = modelSwing.rate,
            targetWidth = modelSwing.width,
            target = target,
        )
    }

    private class Swing(val period: Double, val width: Double) {
        val rate: Double get() = perSecond / period
    }

    /** Speed and width of the swing in [y], around its own middle line, so the line does not count as swing. */
    private fun swing(y: DoubleArray): Swing {
        val period = period(y)
        val middle = movingAverage(y, (period.takeIf { !it.isNaN() } ?: (perSecond / 4.0)).roundToInt().coerceAtLeast(3))
        var sum = 0.0
        for (i in y.indices) sum += (y[i] - middle[i]).pow(2)
        return Swing(period, 2 * sqrt(2.0) * sqrt(sum / y.size))
    }

    /** The longest run of sound, short dropouts (up to 80 ms) bridged; null if under 0.8 s. */
    private fun longestTone(curve: FloatArray): Pair<Int, Int>? {
        var best: Pair<Int, Int>? = null
        var i = 0
        while (i < curve.size) {
            if (curve[i].isNaN()) {
                i++
                continue
            }
            val start = i
            var last = i
            var j = i + 1
            while (j < curve.size && j - last <= 8) {
                if (!curve[j].isNaN()) last = j
                j++
            }
            if (best == null || last + 1 - start > best.second - best.first) best = start to last + 1
            i = last + 1
        }
        return best?.takeIf { it.second - it.first >= perSecond * 4 / 5 }
    }

    private fun fillGaps(x: DoubleArray) {
        var i = 0
        while (i < x.size) {
            if (!x[i].isNaN()) {
                i++
                continue
            }
            val a = i - 1
            var b = i
            while (x[b].isNaN()) b++
            for (k in i until b) x[k] = x[a] + (x[b] - x[a]) * (k - a) / (b - a)
            i = b
        }
    }

    /** Undoes octave jumps between neighbours, then moves the whole tone to the octave nearest the model's [middle]. */
    private fun unfold(x: DoubleArray, middle: Double) {
        for (i in 1 until x.size) {
            while (x[i] - x[i - 1] > 600) x[i] -= 1200
            while (x[i] - x[i - 1] < -600) x[i] += 1200
        }
        val shift = 1200 * ((x.sorted()[x.size / 2] - middle) / 1200).roundToInt()
        for (i in x.indices) x[i] -= shift
    }

    private fun median(x: DoubleArray, radius: Int) = DoubleArray(x.size) { i ->
        val window = (maxOf(0, i - radius)..minOf(x.size - 1, i + radius)).map { x[it] }.sorted()
        window[window.size / 2]
    }

    private fun movingAverage(x: DoubleArray, length: Int): DoubleArray {
        // Running sums keep this quick for long windows.
        val sums = DoubleArray(x.size + 1)
        for (i in x.indices) sums[i + 1] = sums[i] + x[i]
        return DoubleArray(x.size) { i ->
            val a = maxOf(0, i - length / 2)
            val b = minOf(x.size, a + length)
            (sums[b] - sums[a]) / (b - a)
        }
    }

    /** The swing's period in curve steps from the autocorrelation, NaN if it does not repeat. */
    private fun period(y: DoubleArray): Double {
        val n = y.size
        // Only what moves faster than about 2 Hz: a slow line (a bend, a glide) is not a slow vibrato.
        val slow = movingAverage(y, perSecond * 2 / 5)
        val z = DoubleArray(n) { y[it] - slow[it] }
        val minLag = (perSecond / 12.0).toInt()
        val maxLag = minOf((perSecond / VibratoSettings.MIN_RATE * 1.1).toInt(), n / 2)
        if (maxLag <= minLag + 2) return Double.NaN
        val energy = z.sumOf { it * it }.takeIf { it > 1e-6 } ?: return Double.NaN
        val r = DoubleArray(maxLag + 2) { lag ->
            if (lag >= n) 0.0 else (0 until n - lag).sumOf { z[it] * z[it + lag] } / energy * n / (n - lag)
        }
        val peaks = (minLag..maxLag).filter { r[it] > r[it - 1] && r[it] >= r[it + 1] && r[it] > 0.25 }
        if (peaks.isEmpty()) return Double.NaN
        val top = peaks.maxOf { r[it] }
        // Multiples of the period peak too; the first one nearly as high is the period itself.
        val lag = peaks.first { r[it] >= 0.85 * top }
        val bend = r[lag - 1] - 2 * r[lag] + r[lag + 1]
        return lag + if (bend < 0) 0.5 * (r[lag - 1] - r[lag + 1]) / bend else 0.0
    }
}

package widder.audio

import widder.music.ByteBuilder
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

/** Audio as float samples in -1..1, one array per channel. */
class Pcm(val sampleRate: Int, val channels: List<FloatArray>) {
    init {
        require(channels.isNotEmpty())
    }

    val frames: Int get() = channels[0].size
    val duration: Double get() = frames.toDouble() / sampleRate

    fun frameAt(seconds: Double): Int = (seconds * sampleRate).roundToInt().coerceIn(0, frames)

    fun slice(from: Int, to: Int): Pcm {
        val a = from.coerceIn(0, frames)
        val b = to.coerceIn(a, frames)
        return Pcm(sampleRate, channels.map { it.copyOfRange(a, b) })
    }

    fun peak(from: Int = 0, to: Int = frames): Float {
        var p = 0f
        for (ch in channels) for (i in from.coerceAtLeast(0) until to.coerceAtMost(frames)) p = max(p, abs(ch[i]))
        return p
    }

    /** Loudest sample per bucket, for drawing the waveform. */
    fun peaks(buckets: Int): FloatArray {
        val out = FloatArray(buckets)
        if (frames == 0) return out
        for (b in 0 until buckets) {
            val from = (b.toLong() * frames / buckets).toInt()
            val to = max(from + 1, ((b + 1).toLong() * frames / buckets).toInt())
            // Sampling a few hundred points per bucket is enough for the picture and keeps long files quick.
            val stride = max(1, (to - from) / 256)
            var p = 0f
            for (ch in channels) {
                var i = from
                while (i < min(to, frames)) {
                    p = max(p, abs(ch[i]))
                    i += stride
                }
            }
            out[b] = p
        }
        return out
    }

    fun toStereo(): Pcm = if (channels.size >= 2) Pcm(sampleRate, channels.take(2)) else Pcm(sampleRate, listOf(channels[0], channels[0].copyOf()))

    /** Linear-interpolation resampling; good enough to play a file on an output that only takes 44.1/48 kHz. */
    fun resample(rate: Int): Pcm {
        if (rate == sampleRate || frames == 0) return this
        val outFrames = (frames.toLong() * rate / sampleRate).toInt()
        val step = sampleRate.toDouble() / rate
        return Pcm(
            rate,
            channels.map { src ->
                FloatArray(outFrames) { i ->
                    val pos = i * step
                    val j = pos.toInt()
                    val f = (pos - j).toFloat()
                    val a = src[min(j, src.size - 1)]
                    val b = src[min(j + 1, src.size - 1)]
                    a + (b - a) * f
                }
            },
        )
    }

    /** Interleaved 16-bit samples for the audio output. */
    fun toShorts(from: Int, to: Int, into: ShortArray): Int {
        val n = channels.size
        var k = 0
        for (i in from until to) {
            for (c in 0 until n) {
                into[k++] = (channels[c][i].coerceIn(-1f, 1f) * 32767f).roundToInt().toShort()
            }
        }
        return k
    }

    companion object {
        fun silence(sampleRate: Int, seconds: Double, channelCount: Int = 1) =
            Pcm(sampleRate, List(channelCount) { FloatArray((sampleRate * seconds).toInt()) })

        fun fromShorts(sampleRate: Int, channelCount: Int, data: ShortArray, length: Int = data.size): Pcm {
            val frames = length / channelCount
            val chs = List(channelCount) { FloatArray(frames) }
            for (i in 0 until frames) for (c in 0 until channelCount) chs[c][i] = data[i * channelCount + c] / 32768f
            return Pcm(sampleRate, chs)
        }
    }
}

class AudioFormatException(message: String) : Exception(message)

/** RIFF WAVE: reads 8/16/24/32-bit integer and 32/64-bit float PCM, writes 16-bit PCM. */
object Wav {
    fun isWav(bytes: ByteArray): Boolean =
        bytes.size > 12 && bytes.decodeAscii(0, 4) == "RIFF" && bytes.decodeAscii(8, 4) == "WAVE"

    fun read(bytes: ByteArray): Pcm {
        if (!isWav(bytes)) throw AudioFormatException("Keine WAV-Datei")
        var pos = 12
        var format = 0
        var channelCount = 0
        var rate = 0
        var bits = 0
        var dataStart = -1
        var dataLength = 0
        while (pos + 8 <= bytes.size) {
            val id = bytes.decodeAscii(pos, 4)
            val len = bytes.u32le(pos + 4)
            val body = pos + 8
            when (id) {
                "fmt " -> {
                    format = bytes.u16le(body)
                    channelCount = bytes.u16le(body + 2)
                    rate = bytes.u32le(body + 4).toInt()
                    bits = bytes.u16le(body + 14)
                    // WAVE_FORMAT_EXTENSIBLE: the real format is the first two bytes of the sub-format GUID.
                    if (format == 0xFFFE && len >= 26) format = bytes.u16le(body + 24)
                }
                "data" -> {
                    dataStart = body
                    // Some recorders leave the length at 0 or too long while streaming.
                    dataLength = if (len <= 0 || body + len > bytes.size) bytes.size - body else len.toInt()
                }
            }
            if (dataStart >= 0 && format != 0) break
            pos = body + len.toInt() + (len.toInt() and 1)
            if (len < 0) break
        }
        if (dataStart < 0 || channelCount == 0 || rate == 0) throw AudioFormatException("WAV ohne Audiodaten")
        val bytesPerSample = bits / 8
        if (bytesPerSample == 0) throw AudioFormatException("WAV mit $bits Bit wird nicht unterstützt")
        val frames = dataLength / (bytesPerSample * channelCount)
        val chs = List(channelCount) { FloatArray(frames) }
        var p = dataStart
        for (i in 0 until frames) {
            for (c in 0 until channelCount) {
                chs[c][i] = when {
                    format == 3 && bits == 32 -> Float.fromBits(bytes.u32le(p).toInt())
                    format == 3 && bits == 64 -> Double.fromBits(bytes.u64le(p)).toFloat()
                    bits == 8 -> ((bytes[p].toInt() and 0xFF) - 128) / 128f
                    bits == 16 -> bytes.u16le(p).toShort() / 32768f
                    bits == 24 -> (((bytes[p].toInt() and 0xFF) or ((bytes[p + 1].toInt() and 0xFF) shl 8) or (bytes[p + 2].toInt() shl 16))) / 8388608f
                    bits == 32 -> bytes.u32le(p).toInt() / 2147483648f
                    else -> throw AudioFormatException("WAV mit $bits Bit wird nicht unterstützt")
                }
                p += bytesPerSample
            }
        }
        return Pcm(rate, chs)
    }

    fun write(pcm: Pcm): ByteArray {
        val n = pcm.channels.size
        val dataLength = pcm.frames * n * 2
        val out = ByteBuilder()
        out.ascii("RIFF"); out.u32le(36 + dataLength); out.ascii("WAVE")
        out.ascii("fmt "); out.u32le(16); out.u16le(1); out.u16le(n); out.u32le(pcm.sampleRate)
        out.u32le(pcm.sampleRate * n * 2); out.u16le(n * 2); out.u16le(16)
        out.ascii("data"); out.u32le(dataLength)
        val shorts = ShortArray(pcm.frames * n)
        pcm.toShorts(0, pcm.frames, shorts)
        for (s in shorts) out.u16le(s.toInt())
        return out.toByteArray()
    }

    private fun ByteArray.decodeAscii(at: Int, n: Int) = (at until at + n).map { this[it].toInt().toChar() }.joinToString("")
    private fun ByteArray.u16le(at: Int) = (this[at].toInt() and 0xFF) or ((this[at + 1].toInt() and 0xFF) shl 8)
    private fun ByteArray.u32le(at: Int): Long = (u16le(at).toLong()) or (u16le(at + 2).toLong() shl 16)
    private fun ByteArray.u64le(at: Int): Long = u32le(at) or (u32le(at + 4) shl 32)
}

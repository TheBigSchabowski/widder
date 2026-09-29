package widder.audio

import kotlin.concurrent.thread

/** The platform's audio output line: 16-bit interleaved samples in, sound out. */
interface PcmSink {
    /** Returns the sample rate actually opened, or null if the output cannot be opened. */
    fun open(sampleRate: Int, channels: Int): Int?

    /** Blocks until the samples are queued. */
    fun write(samples: ShortArray, count: Int)

    /** Frames that have left the speaker since [open]. */
    fun framesPlayed(): Long

    /** Waits until everything written has been heard, then closes. */
    fun drainAndClose()

    /** Stops at once and closes. */
    fun abort()
}

/**
 * Streams [Pcm] to a [PcmSink] on its own thread. Shared by Android (AudioTrack) and the Mac
 * (javax.sound SourceDataLine).
 */
class StreamingPlayer(private val newSink: () -> PcmSink) : AudioPlayer {
    private val lock = Any()
    private var current: Playback? = null

    private inner class Playback(val pcm: Pcm, val startFrame: Int, val onEnd: () -> Unit) {
        @Volatile var stopped = false
        @Volatile var sink: PcmSink? = null
        @Volatile var finished = false
        @Volatile var rate = pcm.sampleRate

        fun run() {
            val sink = newSink()
            var source = pcm
            val rate = sink.open(pcm.sampleRate, pcm.channels.size.coerceAtMost(2))
            if (rate == null) {
                finished = true
                return
            }
            this.rate = rate
            if (rate != pcm.sampleRate) source = pcm.resample(rate)
            if (source.channels.size > 2) source = Pcm(source.sampleRate, source.channels.take(2))
            this.sink = sink
            val from = (startFrame.toLong() * rate / pcm.sampleRate).toInt()
            val chunk = 2048
            val buffer = ShortArray(chunk * source.channels.size)
            var i = from
            try {
                while (i < source.frames && !stopped) {
                    val end = minOf(source.frames, i + chunk)
                    val n = source.toShorts(i, end, buffer)
                    sink.write(buffer, n)
                    i = end
                }
                if (stopped) sink.abort() else sink.drainAndClose()
            } catch (e: Exception) {
                runCatching { sink.abort() }
            }
            finished = true
            if (!stopped) onEnd()
        }

        fun position(): Double {
            val s = sink ?: return startFrame.toDouble() / pcm.sampleRate
            return startFrame.toDouble() / pcm.sampleRate + s.framesPlayed().toDouble() / rate
        }
    }

    override fun play(pcm: Pcm, fromSeconds: Double, onEnd: () -> Unit) {
        val playback = Playback(pcm, pcm.frameAt(fromSeconds), onEnd)
        synchronized(lock) {
            current?.let { it.stopped = true; it.sink?.abort() }
            current = playback
        }
        thread(name = "widder-audio", isDaemon = true) { playback.run() }
    }

    override fun stop() {
        synchronized(lock) {
            current?.let { it.stopped = true; runCatching { it.sink?.abort() } }
            current = null
        }
    }

    override val isPlaying: Boolean get() = current?.let { !it.finished && !it.stopped } ?: false

    override fun position(): Double = current?.position() ?: 0.0
}

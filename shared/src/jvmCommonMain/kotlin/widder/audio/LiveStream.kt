package widder.audio

import kotlin.concurrent.thread

/**
 * Streams a [LiveSource] to its own [PcmSink] on its own thread, next to the [StreamingPlayer].
 * The sink's buffer (~100 ms) is the latency between a change in the UI and hearing it.
 */
class LiveStream(private val newSink: () -> PcmSink) : LiveOutput {
    private val lock = Any()
    private var current: Run? = null

    /** The thread of the last run; a new run waits for it, so a source is never rendered twice at once. */
    private var lastThread: Thread? = null

    private inner class Run(val source: LiveSource, val onFail: () -> Unit, val previous: Thread?) {
        @Volatile var stopping = false

        fun run() {
            previous?.join(2000)
            if (stopping) return
            val sink = newSink()
            val rate = sink.open(Synth.SAMPLE_RATE, 2)
            if (rate == null) {
                synchronized(lock) { if (current === this) current = null }
                onFail()
                return
            }
            source.prepare(rate)
            val left = FloatArray(BLOCK)
            val right = FloatArray(BLOCK)
            val shorts = ShortArray(BLOCK * 2)
            // A short fade at the end, so stopping does not click.
            val fadeFrames = rate / 50
            var fade = -1
            try {
                while (fade != 0) {
                    if (stopping && fade < 0) fade = fadeFrames
                    source.render(left, right, BLOCK)
                    for (i in 0 until BLOCK) {
                        var g = 1f
                        if (fade >= 0) {
                            g = fade.toFloat() / fadeFrames
                            if (fade > 0) fade--
                        }
                        shorts[2 * i] = (left[i] * g * 32767).toInt().toShort()
                        shorts[2 * i + 1] = (right[i] * g * 32767).toInt().toShort()
                    }
                    sink.write(shorts, BLOCK * 2)
                }
                sink.drainAndClose()
            } catch (e: Exception) {
                runCatching { sink.abort() }
            }
        }
    }

    override fun start(source: LiveSource, onFail: () -> Unit) {
        synchronized(lock) {
            current?.stopping = true
            val run = Run(source, onFail, lastThread)
            current = run
            lastThread = thread(name = "widder-live", isDaemon = true) { run.run() }
        }
    }

    override fun stop() {
        synchronized(lock) {
            current?.stopping = true
            current = null
        }
    }

    override val isRunning: Boolean get() = synchronized(lock) { current != null }

    private companion object {
        const val BLOCK = 512
    }
}

/** Files under the shared resources folder, e.g. "drums/kick.wav". */
fun bundledResource(name: String): ByteArray? = LiveStream::class.java.getResourceAsStream("/$name")?.use { it.readBytes() }

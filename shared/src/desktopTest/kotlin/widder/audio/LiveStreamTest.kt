package widder.audio

import java.util.concurrent.atomic.AtomicInteger
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class LiveStreamTest {
    /** Takes samples at roughly real time, like an audio line. */
    private class FakeSink(val opened: AtomicInteger, val closed: AtomicInteger, val last: ShortArray) : PcmSink {
        override fun open(sampleRate: Int, channels: Int): Int {
            opened.incrementAndGet()
            return sampleRate
        }

        override fun write(samples: ShortArray, count: Int) {
            samples.copyInto(last, 0, 0, count)
            Thread.sleep(2)
        }

        override fun framesPlayed() = 0L
        override fun drainAndClose() {
            closed.incrementAndGet()
        }

        override fun abort() {
            closed.incrementAndGet()
        }
    }

    /** A constant level; counts how many threads render at the same time. */
    private class Constant : LiveSource {
        val busy = AtomicInteger()
        var overlap = false
        var prepared = 0

        override fun prepare(sampleRate: Int) {
            prepared++
        }

        override fun render(left: FloatArray, right: FloatArray, frames: Int) {
            if (busy.incrementAndGet() > 1) overlap = true
            left.fill(0.5f, 0, frames)
            right.fill(0.5f, 0, frames)
            busy.decrementAndGet()
        }
    }

    @Test
    fun streamsUntilStoppedAndFadesOut() {
        val opened = AtomicInteger()
        val closed = AtomicInteger()
        val last = ShortArray(1024)
        val stream = LiveStream { FakeSink(opened, closed, last) }
        val source = Constant()
        stream.start(source)
        assertTrue(stream.isRunning)
        Thread.sleep(50)
        assertEquals(16383, last[0].toInt())
        // Off and on again at once: the second run waits for the first, never renders alongside it.
        stream.stop()
        stream.start(source)
        Thread.sleep(80)
        stream.stop()
        assertFalse(stream.isRunning)
        Thread.sleep(80)
        assertEquals(2, opened.get())
        assertEquals(2, closed.get())
        assertEquals(2, source.prepared)
        assertFalse(source.overlap)
        // The last block written is the end of the fade: silence.
        assertEquals(0, last[1022].toInt())
    }
}

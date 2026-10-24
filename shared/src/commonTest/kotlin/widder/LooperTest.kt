package widder

import kotlinx.coroutines.Dispatchers
import widder.audio.LatencyProbe
import widder.audio.Loop
import widder.audio.Looper
import widder.audio.PracticeEngine
import widder.audio.SilentAudio
import widder.core.PracticeSettings
import widder.core.Store
import widder.core.currentTimeMillis
import widder.ui.AppViewModel
import kotlin.math.abs
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class LooperTest {
    @Test
    fun cutPadsTheStartAndFoldsTheTailIntoIt() {
        val rate = 1000
        val mic = FloatArray(300) { 1f + it }
        val loop = Looper.cut(mic, -10, 200, rate)
        assertEquals(210, loop.size)
        // A 15 ms seam at 1 kHz: 15 samples, starting from what followed the end.
        assertEquals(mic[200], loop[0])
        assertTrue(abs(loop[14] - (mic[4] * 14 / 15 + mic[214] / 15)) < 1e-4f, "${loop[14]}")
        assertEquals(mic[5], loop[15])
        assertEquals(mic[10], loop[20])
        assertEquals(mic[199], loop[209])
        assertEquals(285, Looper.free(mic, rate).size)
    }

    @Test
    fun syncFindsTheBarLines() {
        // 2 s bars at 44.1 kHz; recording starts half way through bar 2 (counting from 0), 100 ms latency.
        val clock = PracticeEngine.Clock(frame = 0, bar = 2.5, framesPerBar = 88_200.0, rate = 44_100, running = true)
        val sync = Looper.Sync(clock, 44_100, 0.1)
        assertEquals(2.45, sync.barAt(0), 1e-9)
        assertEquals(3, sync.firstBar)
        assertEquals(48_510, sync.sampleAt(3.0))
        assertEquals(3.0, sync.barAt(48_510), 1e-6)
        assertEquals(4, sync.lastBar(3.1))
        assertEquals(4, sync.lastBar(4.2))
        assertEquals(5, sync.lastBar(4.6))
        // Record pressed a moment after the one: the loop still starts on it.
        assertEquals(2, Looper.Sync(PracticeEngine.Clock(0, 2.15, 88_200.0, 44_100, true), 44_100, 0.0).firstBar)
    }

    @Test
    fun aLockedLoopFollowsTheBarClockWithoutTheDrums() {
        val engine = PracticeEngine()
        engine.prepare(44_100)
        engine.settings = PracticeSettings(bpm = 120, loopVolume = 1f)
        engine.loop = Loop(FloatArray(1000) { it / 1000f }, 44_100, bars = 1, origin = 0)
        engine.loopOn = true
        val left = FloatArray(441)
        val right = FloatArray(441)
        repeat(100) { engine.render(left, right, 441) } // 1 s: half a bar at 120 BPM in 4/4
        assertTrue(engine.clock.running)
        assertEquals(0.5, engine.clock.bar, 1e-6)
        assertEquals(500.0, engine.clock.loop, 1e-6)
        engine.render(left, right, 1)
        assertEquals(0.5f, left[0], 0.01f)
    }

    @Test
    fun aFreeLoopJustRepeats() {
        val engine = PracticeEngine()
        engine.prepare(44_100)
        engine.settings = PracticeSettings(loopVolume = 1f)
        engine.loop = Loop(FloatArray(100) { if (it == 0) 0.5f else 0f }, 44_100)
        engine.loopOn = true
        val left = FloatArray(44_100)
        engine.render(left, FloatArray(44_100), 44_100)
        assertFalse(engine.clock.running)
        assertEquals(0.0, engine.clock.loop, 1e-6)
        // The click comes back every 100 samples, at full volume once faded in.
        assertEquals(0.5f, left[44_000], 0.01f)
        assertEquals(0f, left[44_050], 0.001f)
    }

    @Test
    fun anOverdubLandsWhereTheLoopWasHeard() {
        // A 1000-sample loop at 1 kHz, at sample 100 when the microphone opened, 50 ms latency:
        // microphone sample k was played to loop position 50 + k.
        val loop = FloatArray(1000) { 0.25f }
        val dub = Looper.Overdub(loop, start = 100.0, speed = 1000.0, micRate = 1000, latency = 0.05)
        val mic = FloatArray(1300) { k -> if (k == 200 || k == 1000) 1f else 0f }
        // Read in blocks of 100 through a 300-sample window, as from the recorder.
        val window = FloatArray(300)
        for (end in 100L..1200L step 100) {
            for (i in window.indices) window[i] = mic.getOrElse((end - window.size + i).toInt()) { 0f }
            dub.add(window, end)
        }
        for (i in window.indices) window[i] = mic[1000 + i]
        dub.finish(window, 1300)
        assertEquals(1.25f, loop[250], 1e-6f)
        // The second pass: sample 1000 at position 1050, round to 50.
        assertEquals(1.25f, loop[50], 1e-6f)
        assertEquals(0.25f, loop[251], 1e-6f)
        assertEquals(0.25f, loop[49], 1e-6f)
    }

    @Test
    fun anOverdubFadesInAndOutAndFollowsTheTempo() {
        // The tempo went down to half since the loop was recorded: half a loop sample per microphone sample.
        val loop = FloatArray(1000)
        val dub = Looper.Overdub(loop, start = 0.0, speed = 500.0, micRate = 1000, latency = 0.0)
        val mic = FloatArray(1000) { 1f }
        dub.finish(mic, 1000)
        // Loop position q was played at microphone sample 2q.
        assertEquals(0f, loop[0], 1e-6f)
        assertEquals(10f / 15, loop[5], 1e-5f)
        assertEquals(1f, loop[250], 1e-6f)
        assertEquals(8f / 15, loop[496], 1e-5f) // sample 992, 8 before the end
        assertEquals(0f, loop[600], 1e-6f)
    }

    @Test
    fun theProbeHearsItsClicksComeBack() {
        val probe = LatencyProbe()
        probe.prepare(48_000)
        val out = FloatArray(probe.endFrame.toInt() + 1000)
        probe.render(out, FloatArray(out.size), out.size)
        // Speaker to microphone: 44.1 kHz, opened at output frame 3000, 180 ms on the way, quieter and a little noisy.
        val micRate = 44_100
        val start = 3000L
        val mic = FloatArray(micRate * 5) { k ->
            val frame = start + ((k.toDouble() / micRate - 0.18) * 48_000).toLong()
            val noise = ((k * 7919) % 101 - 50) / 50f * 0.003f
            (if (frame in out.indices) out[frame.toInt()] * 0.2f else 0f) + noise
        }
        val latency = assertNotNull(LatencyProbe.measure(probe, mic, micRate, start))
        assertEquals(0.18, latency, 0.002)
        // With headphones the microphone hears nothing of it.
        assertNull(LatencyProbe.measure(probe, FloatArray(micRate * 5) { ((it * 7919) % 101 - 50) / 50f * 0.003f }, micRate, start))
    }

    @Test
    fun theTimerStopsEverything() {
        val vm = AppViewModel(Store(MemoryStore()), SilentAudio.system, live = false, main = Dispatchers.Unconfined, work = Dispatchers.Unconfined)
        vm.setStopAfter(10)
        assertNull(vm.practiceEndsAt)
        vm.toggleDrums()
        vm.toggleDrone()
        val end = assertNotNull(vm.practiceEndsAt)
        assertTrue(abs(end - currentTimeMillis() - 600_000) < 5_000)
        vm.checkPracticeTimer(end - 61_000)
        assertEquals(61, vm.practiceSecondsLeft)
        assertTrue(vm.drumsOn)
        vm.checkPracticeTimer(end)
        assertFalse(vm.drumsOn || vm.droneOn)
        assertNull(vm.practiceEndsAt)
    }
}

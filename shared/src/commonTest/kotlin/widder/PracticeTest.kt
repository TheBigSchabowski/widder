package widder

import kotlinx.coroutines.Dispatchers
import widder.audio.DrumKits
import widder.audio.SilentAudio
import widder.audio.Grooves
import widder.audio.PracticeEngine
import widder.core.DroneSound
import widder.core.DrumKit
import widder.core.Meter
import widder.core.PracticeSettings
import widder.core.Store
import widder.ui.AppViewModel
import widder.ui.Screen
import kotlin.math.abs
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class PracticeTest {
    private val rate = 44100

    private fun PracticeEngine.run(seconds: Double): FloatArray {
        val frames = (seconds * rate).toInt()
        val out = FloatArray(frames)
        val left = FloatArray(512)
        val right = FloatArray(512)
        var done = 0
        while (done < frames) {
            val n = minOf(512, frames - done)
            render(left, right, n)
            left.copyInto(out, done, 0, n)
            done += n
        }
        return out
    }

    private fun FloatArray.peak(from: Int = 0, to: Int = size) = (from until to).maxOf { abs(this[it]) }

    @Test
    fun everyMeterHasGroovesThatFitItsBar() {
        for (meter in Meter.entries) assertTrue(Grooves.of(meter).isNotEmpty(), meter.label)
        assertEquals(17, Grooves.all.size)
        for (g in Grooves.all) {
            assertEquals(g.steps, g.stepsPerBeat * g.meter.beats, g.name)
            val all = g.kick or g.snare or g.hat or g.kickAccent or g.snareAccent or g.hatAccent or g.midAccent
            assertEquals(0, all shr g.steps, "${g.name} has hits beyond its bar")
        }
        assertEquals("Waltz", Grooves.find("Rock", Meter.THREE_FOUR).name)
    }

    @Test
    fun droneIsInTune() {
        val engine = PracticeEngine()
        engine.prepare(rate)
        engine.settings = PracticeSettings(root = 9, octave = 3, sound = DroneSound.SINE, fifth = false)
        engine.droneOn = true
        val out = engine.run(2.0)
        // Count upward zero crossings in the second second, after the fade in: A3 = 220 Hz.
        var crossings = 0
        for (i in rate + 1 until 2 * rate) if (out[i - 1] < 0 && out[i] >= 0) crossings++
        assertTrue(crossings in 219..221, "$crossings")
        assertEquals(listOf(57, 64, 69), PracticeEngine.dronePitches(PracticeSettings(root = 9, octave = 3, octaveUp = true)))
    }

    @Test
    fun drumsStartOnTheOneAndFollowTheTempo() {
        val engine = PracticeEngine()
        engine.prepare(rate)
        engine.settings = PracticeSettings(kit = DrumKit.SYNTH, bpm = 120, groove = "Ballad", drumVolume = 1f)
        engine.drumsOn = true
        val out = engine.run(2.2)
        // Ballad: kick on 1, snare on 3, hats in 8ths. At 120 BPM a bar is 2 s, so the next kick is at 2 s.
        assertTrue(out.peak(0, 2000) > 0.1f, "no hit on the one")
        assertTrue(out.peak(rate * 2, rate * 2 + 2000) > 0.1f, "no kick on the next bar")
        assertEquals(0, engine.beat)
    }

    @Test
    fun silentUntilSwitchedOn() {
        val engine = PracticeEngine()
        engine.prepare(rate)
        assertEquals(0f, engine.run(0.5).peak())
        assertEquals(-1, engine.beat)
    }

    @Test
    fun tanpuraAndEveryKitMakeSound() {
        val engine = PracticeEngine()
        engine.prepare(rate)
        engine.settings = PracticeSettings(sound = DroneSound.TANPURA)
        engine.droneOn = true
        assertTrue(engine.run(1.0).peak() > 0.02f)
        for (kit in DrumKit.entries) {
            val sounds = DrumKits.build(kit, rate, null)
            for (s in listOf(sounds.kick, sounds.snare, sounds.hat)) assertTrue(s.isNotEmpty() && s.peak() in 0.05f..0.5f, kit.label)
        }
    }

    @Test
    fun tapTempoAveragesTheTaps() {
        val vm = AppViewModel(Store(MemoryStore()), SilentAudio.system, live = false, main = Dispatchers.Unconfined, work = Dispatchers.Unconfined)
        listOf(0L, 500L, 1000L, 1500L).forEach { vm.tapTempo(10_000 + it) }
        assertEquals(120, vm.practice.bpm)
        vm.tapTempo(20_000)
        assertEquals(120, vm.practice.bpm)
    }

    @Test
    fun settingsAreKeptWhenLeaving() {
        val store = Store(MemoryStore())
        val vm = AppViewModel(store, SilentAudio.system, live = false, main = Dispatchers.Unconfined, work = Dispatchers.Unconfined)
        vm.open(Screen.Practice)
        vm.updatePractice { it.copy(root = 2, kit = DrumKit.BRUSHES, groove = "Shuffle") }
        vm.toggleDrums()
        vm.back()
        assertEquals(false, vm.drumsOn)
        val again = AppViewModel(store, SilentAudio.system, live = false, main = Dispatchers.Unconfined, work = Dispatchers.Unconfined)
        assertEquals(PracticeSettings(root = 2, kit = DrumKit.BRUSHES, groove = "Shuffle"), again.practice)
    }
}

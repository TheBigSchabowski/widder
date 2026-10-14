package widder.audio

import widder.core.DroneSound
import widder.core.DrumKit
import widder.core.PracticeSettings
import kotlin.concurrent.Volatile
import kotlin.math.PI
import kotlin.math.exp
import kotlin.math.pow
import kotlin.math.sin

/**
 * The practice mode's sound: a drone and a drum machine, rendered block by block on the audio
 * thread. The UI only swaps [settings] and flips [droneOn] / [drumsOn]; every change is faded, so
 * a new root note or tempo never clicks.
 *
 * The drum part is NicheLooper's RhythmSection in Kotlin: the bar clock runs as a phase from 0 to 1,
 * so a tempo change keeps the position within the bar.
 */
class PracticeEngine(private val samples: () -> DrumSamples? = { null }) : LiveSource {
    @Volatile var settings = PracticeSettings()
    @Volatile var droneOn = false
    @Volatile var drumsOn = false

    /** The beat being played (0 = one), -1 while the drums are off; for the UI. */
    @Volatile var beat = -1
        private set

    private var rate = Synth.SAMPLE_RATE
    private var kits = emptyMap<DrumKit, KitSounds>()
    private var clicks = emptyList<FloatArray>()
    private val tables = HashMap<DroneSound, FloatArray>()

    // Bar clock.
    private var phase = 0.0
    private var lastStep = -1
    private var lastSteps = 0
    private var clockRunning = false

    // One-shot voices: drums and clicks.
    private val voiceBuf = arrayOfNulls<FloatArray>(MAX_VOICES)
    private val voicePos = IntArray(MAX_VOICES)
    private val voiceGain = FloatArray(MAX_VOICES)
    private val voiceFade = FloatArray(MAX_VOICES)
    private val voiceKind = IntArray(MAX_VOICES) { -1 }

    // Drone.
    private val tones = ArrayList<Tone>()
    private var droneKey: List<Any>? = null
    private val plucks = ArrayList<Pluck>()
    private var pluckClock = 0
    private var pluckIndex = 0
    private var lfo = 0.0

    private var drumLevel = 0f
    private var droneLevel = 0f

    override fun prepare(sampleRate: Int) {
        rate = sampleRate
        val bundled = samples()
        kits = DrumKit.entries.associateWith { DrumKits.build(it, sampleRate, bundled) }
        clicks = DrumKits.clicks(sampleRate)
        for (sound in DroneSound.entries) tables[sound] = wavetable(sound)
        reset()
    }

    /** Back to silence, the bar clock to one. */
    fun reset() {
        phase = 0.0
        lastStep = -1
        lastSteps = 0
        clockRunning = false
        voiceKind.fill(-1)
        tones.clear()
        plucks.clear()
        droneKey = null
        pluckClock = 0
        pluckIndex = 0
        beat = -1
    }

    override fun render(left: FloatArray, right: FloatArray, frames: Int) {
        val s = settings
        val drums = drumsOn
        val groove = Grooves.find(s.groove, s.meter)
        val kit = kits[s.kit]
        val framesPerBar = rate * 60.0 / s.bpm.coerceIn(PracticeSettings.MIN_BPM, PracticeSettings.MAX_BPM) * s.meter.beats
        if (drums && !clockRunning) {
            phase = 0.0
            lastStep = -1
        }
        clockRunning = drums
        if (!drums) beat = -1
        updateDrone(s)
        val smooth = 1f / (0.03f * rate)
        val drumTarget = s.drumVolume.coerceIn(0f, 1f)
        val droneTarget = if (droneOn) s.droneVolume.coerceIn(0f, 1f) else 0f
        val lfoStep = 2 * PI * 0.13 / rate
        for (i in 0 until frames) {
            if (drums && kit != null) {
                tick(groove, kit, s)
                phase += 1.0 / framesPerBar
                if (phase >= 1.0) phase -= 1.0
            }
            if (s.sound == DroneSound.TANPURA && droneOn) schedulePlucks(s)
            drumLevel += (drumTarget - drumLevel) * smooth
            droneLevel += (droneTarget - droneLevel) * smooth
            lfo += lfoStep
            val out = limit(voices() * drumLevel + drone() * droneLevel)
            left[i] = out
            right[i] = out
        }
        if (lfo > 2 * PI) lfo -= 2 * PI
    }

    // region drums

    private fun tick(groove: Groove, kit: KitSounds, s: PracticeSettings) {
        val step = (phase * groove.steps).toInt().coerceIn(0, groove.steps - 1)
        if (groove.steps != lastSteps) {
            // A groove with another resolution: lastStep counts in the old grid. Adopt the current
            // step without firing it - one missed hit beats a double trigger.
            lastSteps = groove.steps
            if (lastStep >= 0) {
                lastStep = step
                return
            }
        }
        if (step == lastStep) return
        lastStep = step
        val bit = 1 shl step
        if (step % groove.stepsPerBeat == 0) {
            beat = step / groove.stepsPerBeat
            if (s.click || s.kit == DrumKit.CLICK) {
                val accent = when {
                    step == 0 -> 0
                    groove.midAccent and bit != 0 -> 1
                    else -> 2
                }
                trigger(clicks[accent], 1f, CLICK, choke = true)
            }
        }
        if (s.kit == DrumKit.CLICK) return
        if (groove.kick and bit != 0) trigger(kit.kick, if (groove.kickAccent and bit != 0) 1f else 0.6f, KICK)
        if (groove.snare and bit != 0) trigger(kit.snare, if (groove.snareAccent and bit != 0) 1f else 0.32f, SNARE)
        if (groove.hat and bit != 0) trigger(kit.hat, if (groove.hatAccent and bit != 0) 1f else 0.55f, HAT, choke = kit.hatChokes)
    }

    private fun trigger(buf: FloatArray, gain: Float, kind: Int, choke: Boolean = false) {
        if (choke) {
            // Cut the ringing one within a few milliseconds instead of at once, which would click.
            for (v in 0 until MAX_VOICES) if (voiceKind[v] == kind) voiceFade[v] = voiceGain[v] / (0.004f * rate)
        }
        var slot = (0 until MAX_VOICES).firstOrNull { voiceKind[it] == -1 }
        if (slot == null) slot = (0 until MAX_VOICES).maxBy { voicePos[it] }
        voiceBuf[slot] = buf
        voicePos[slot] = 0
        voiceGain[slot] = gain
        voiceFade[slot] = 0f
        voiceKind[slot] = kind
    }

    private fun voices(): Float {
        var sum = 0f
        for (v in 0 until MAX_VOICES) {
            if (voiceKind[v] == -1) continue
            val buf = voiceBuf[v]!!
            sum += buf[voicePos[v]] * voiceGain[v]
            voiceGain[v] -= voiceFade[v]
            if (++voicePos[v] >= buf.size || voiceGain[v] <= 0f) voiceKind[v] = -1
        }
        return sum
    }

    // endregion

    // region drone

    private class Tone(val table: FloatArray, val increment: Double, val amp: Float, val breath: Double) {
        var phase = 0.0
        var gain = 0f
        var target = 1f
    }

    /** A tanpura string: harmonics with their own decay and a "jawari" bloom in the upper ones. */
    private class Pluck(freq: Double, rate: Int, val amp: Float) {
        val increments: DoubleArray
        val decays: FloatArray
        val envelopes: FloatArray
        val phases: DoubleArray
        val blooms: IntArray
        var t = 0

        init {
            val count = (1..HARMONICS).count { freq * it < rate / 2.5 }
            increments = DoubleArray(count) { freq * (it + 1) * TABLE_SIZE / rate }
            decays = FloatArray(count) { exp(-1.0 / (3.2 / (1 + 0.12 * it) * rate)).toFloat() }
            envelopes = FloatArray(count) { 1f / (it + 1f).pow(0.6f) }
            // The partials add up to several times the fundamental; scale the string back to about 1.
            val total = envelopes.sum()
            for (h in envelopes.indices) envelopes[h] /= total * 0.9f
            phases = DoubleArray(count) { it * 0.31 * TABLE_SIZE }
            blooms = IntArray(count) { ((0.25 + 0.07 * it) * rate).toInt() }
        }
    }

    /** Which tones the settings ask for; a change fades the old ones out and the new ones in. */
    private fun updateDrone(s: PracticeSettings) {
        val pitches = dronePitches(s)
        val key: List<Any>? = if (droneOn && s.sound != DroneSound.TANPURA) listOf(s.sound, s.tuning, pitches) else null
        if (key == droneKey) return
        droneKey = key
        for (t in tones) t.target = 0f
        if (key == null) return
        val table = tables.getValue(s.sound)
        for ((k, p) in pitches.withIndex()) {
            val amp = when (p - pitches[0]) {
                0 -> 0.20f
                7 -> 0.15f
                else -> 0.13f
            }
            tones += Tone(table, frequency(p, s.tuning) * TABLE_SIZE / rate, amp, k * 2.1)
        }
    }

    private fun drone(): Float {
        var sum = 0f
        val fade = 1f / (0.15f * rate)
        var i = 0
        while (i < tones.size) {
            val t = tones[i]
            if (t.gain < t.target) t.gain = minOf(t.target, t.gain + fade) else if (t.gain > t.target) t.gain = maxOf(t.target, t.gain - fade)
            if (t.gain <= 0f && t.target == 0f) {
                tones.removeAt(i)
                continue
            }
            val breath = if (t.table === tables[DroneSound.PAD]) 1f + 0.12f * sin(lfo + t.breath).toFloat() else 1f
            sum += lookup(t.table, t.phase) * t.amp * t.gain * breath
            t.phase += t.increment
            if (t.phase >= TABLE_SIZE) t.phase -= TABLE_SIZE
            i++
        }
        return sum + plucks()
    }

    /** The tanpura's cycle: Pa (or the octave), Sa, Sa, low Sa - then a breath. */
    private fun schedulePlucks(s: PracticeSettings) {
        if (pluckClock-- > 0) return
        val pitch = tanpuraStrings(s)[pluckIndex]
        if (plucks.size >= MAX_PLUCKS) plucks.removeAt(0)
        plucks += Pluck(frequency(pitch, s.tuning), rate, if (pluckIndex == 3) 0.20f else 0.15f)
        pluckClock = ((if (pluckIndex == 3) 1.6 else 0.75) * rate).toInt()
        pluckIndex = (pluckIndex + 1) % 4
    }

    private fun plucks(): Float {
        var sum = 0f
        val table = tables.getValue(DroneSound.SINE)
        val attack = 0.004f * rate
        val end = (PLUCK_SECONDS * rate).toInt()
        val fadeFrom = end - rate
        var i = 0
        while (i < plucks.size) {
            val p = plucks[i]
            if (p.t >= end) {
                plucks.removeAt(i)
                continue
            }
            var s = 0f
            for (h in p.increments.indices) {
                val b = p.blooms[h]
                // Upper harmonics swell after the attack and sink back: the buzz of the bridge.
                val bloom = if (h >= 2 && p.t < b) 1f + 0.9f * lookup(table, p.t.toDouble() / b * TABLE_SIZE / 2) else 1f
                s += lookup(table, p.phases[h]) * p.envelopes[h] * bloom
                p.envelopes[h] *= p.decays[h]
                p.phases[h] += p.increments[h]
                if (p.phases[h] >= TABLE_SIZE) p.phases[h] -= TABLE_SIZE
            }
            var env = p.amp
            if (p.t < attack) env *= p.t / attack
            if (p.t > fadeFrom) env *= (end - p.t).toFloat() / rate
            sum += s * env
            p.t++
            i++
        }
        return sum
    }

    // endregion

    private fun wavetable(sound: DroneSound): FloatArray {
        val harmonics: List<Pair<Int, Double>> = when (sound) {
            DroneSound.SINE, DroneSound.TANPURA -> listOf(1 to 1.0)
            DroneSound.ORGAN -> listOf(1 to 1.0, 2 to 0.55, 3 to 0.3, 4 to 0.25, 6 to 0.1, 8 to 0.12)
            DroneSound.PAD -> (1..12).map { it to 1.0 / it.toDouble().pow(1.7) }
        }
        val table = FloatArray(TABLE_SIZE) { i ->
            harmonics.sumOf { (h, a) -> a * sin(2 * PI * h * i / TABLE_SIZE + h * 0.7 * (h - 1)) }.toFloat()
        }
        val peak = table.maxOf { kotlin.math.abs(it) }
        for (i in table.indices) table[i] /= peak
        return table
    }

    /** Untouched up to 0.8, then bent softly towards 1 instead of clipping. */
    private fun limit(x: Float): Float {
        val a = kotlin.math.abs(x)
        if (a <= 0.8f) return x
        val y = 0.8f + 0.2f * kotlin.math.tanh((a - 0.8f) / 0.2f)
        return if (x < 0) -y else y
    }

    private fun lookup(table: FloatArray, phase: Double): Float {
        val j = phase.toInt()
        val f = (phase - j).toFloat()
        val a = table[j and (TABLE_SIZE - 1)]
        val b = table[(j + 1) and (TABLE_SIZE - 1)]
        return a + (b - a) * f
    }

    companion object {
        private const val MAX_VOICES = 12
        private const val MAX_PLUCKS = 10
        private const val PLUCK_SECONDS = 6.0
        private const val HARMONICS = 14
        private const val TABLE_SIZE = 2048
        private const val KICK = 0
        private const val SNARE = 1
        private const val HAT = 2
        private const val CLICK = 3

        /** MIDI pitches of the drone: root, fifth and octave as switched on. */
        fun dronePitches(s: PracticeSettings): List<Int> {
            val root = 12 * (s.octave + 1) + s.root
            return listOfNotNull(root, (root + 7).takeIf { s.fifth }, (root + 12).takeIf { s.octaveUp })
        }

        /** The tanpura's four strings in the order they are plucked: Pa (or the octave), Sa, Sa, low Sa. */
        fun tanpuraStrings(s: PracticeSettings): List<Int> {
            val root = 12 * (s.octave + 1) + s.root
            return listOf(if (s.fifth) root - 5 else root + 12, root + 12, root + 12, root)
        }

        fun frequency(pitch: Int, tuning: Int): Double = tuning * 2.0.pow((pitch - 69) / 12.0)
    }
}

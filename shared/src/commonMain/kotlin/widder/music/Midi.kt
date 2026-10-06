package widder.music

/** One played note, in seconds. Channel 9 is percussion. */
data class Note(val pitch: Int, val start: Double, val end: Double, val velocity: Int, val channel: Int = 0)

class MidiFile(val notes: List<Note>) {
    val duration: Double get() = notes.maxOfOrNull { it.end } ?: 0.0
}

class MidiException(message: String) : Exception(message)

/**
 * Standard MIDI file reader (format 0 and 1). Tempo changes from any track apply to all tracks,
 * and notes held by the sustain pedal last until the pedal is released.
 */
object Midi {
    private class Raw(val tick: Long, val order: Int, val track: Int, val kind: Int, val channel: Int, val a: Int, val b: Int)

    private const val NOTE_OFF = 0
    private const val NOTE_ON = 1
    private const val PEDAL = 2
    private const val TEMPO = 3

    fun parse(bytes: ByteArray): MidiFile {
        val r = Reader(bytes)
        if (r.ascii(4) != "MThd") throw MidiException("Keine MIDI-Datei")
        val headerLength = r.u32()
        r.u16() // format
        val trackCount = r.u16()
        val division = r.u16()
        r.skip(headerLength - 6)
        if (division and 0x8000 != 0) throw MidiException("SMPTE-Zeitbasis wird nicht unterstützt")
        val ticksPerQuarter = division.coerceAtLeast(1)

        val events = mutableListOf<Raw>()
        var order = 0
        for (track in 0 until trackCount) {
            if (r.remaining < 8) break
            val id = r.ascii(4)
            val length = r.u32().toInt()
            if (id != "MTrk") {
                r.skip(length.toLong())
                continue
            }
            val end = r.pos + length
            var tick = 0L
            var status = 0
            while (r.pos < end && r.remaining > 0) {
                tick += r.varInt()
                val b = r.u8()
                if (b == 0xFF) {
                    val type = r.u8()
                    val len = r.varInt()
                    if (type == 0x51 && len == 3L) {
                        val micros = (r.u8() shl 16) or (r.u8() shl 8) or r.u8()
                        events += Raw(tick, order++, track, TEMPO, 0, micros, 0)
                    } else {
                        r.skip(len)
                    }
                    status = 0
                    if (type == 0x2F) break
                    continue
                }
                if (b == 0xF0 || b == 0xF7) {
                    r.skip(r.varInt())
                    status = 0
                    continue
                }
                val data1: Int
                if (b >= 0x80) {
                    status = b
                    data1 = r.u8()
                } else {
                    if (status == 0) throw MidiException("Ungültiges Ereignis")
                    data1 = b
                }
                val channel = status and 0x0F
                when (status and 0xF0) {
                    0x80 -> events += Raw(tick, order++, track, NOTE_OFF, channel, data1, r.u8())
                    0x90 -> {
                        val velocity = r.u8()
                        events += Raw(tick, order++, track, if (velocity == 0) NOTE_OFF else NOTE_ON, channel, data1, velocity)
                    }
                    0xB0 -> {
                        val value = r.u8()
                        if (data1 == 64) events += Raw(tick, order++, track, PEDAL, channel, value, 0)
                    }
                    0xA0, 0xE0 -> r.u8()
                    else -> Unit // program change, channel pressure: one data byte, already read
                }
            }
            r.pos = end
        }

        events.sortWith(compareBy<Raw> { it.tick }.thenBy { if (it.kind == NOTE_OFF) 0 else 1 }.thenBy { it.order })

        // Ticks -> seconds through the tempo map.
        var lastTick = 0L
        var seconds = 0.0
        var microsPerQuarter = 500_000.0
        fun time(tick: Long): Double {
            seconds += (tick - lastTick) * microsPerQuarter / 1_000_000.0 / ticksPerQuarter
            lastTick = tick
            return seconds
        }

        val notes = mutableListOf<Note>()
        val open = HashMap<Int, ArrayDeque<Pair<Double, Int>>>() // channel*128+pitch -> (start, velocity)
        val pedalDown = BooleanArray(16)
        val held = HashMap<Int, MutableList<Int>>() // channel -> keys released while the pedal is down

        fun close(channel: Int, pitch: Int, at: Double) {
            val start = open[channel * 128 + pitch]?.removeFirstOrNull() ?: return
            notes += Note(pitch, start.first, maxOf(at, start.first + 0.02), start.second, channel)
        }

        for (e in events) {
            val t = time(e.tick)
            when (e.kind) {
                TEMPO -> microsPerQuarter = e.a.toDouble()
                NOTE_ON -> {
                    val key = e.channel * 128 + e.a
                    // A repeated key while the pedal holds the old one ends the old one.
                    if (held[e.channel]?.remove(e.a) == true) close(e.channel, e.a, t)
                    open.getOrPut(key) { ArrayDeque() }.addLast(t to e.b)
                }
                NOTE_OFF -> if (pedalDown[e.channel]) {
                    held.getOrPut(e.channel) { mutableListOf() }.add(e.a)
                } else {
                    close(e.channel, e.a, t)
                }
                PEDAL -> {
                    val down = e.a >= 64
                    if (pedalDown[e.channel] && !down) {
                        held.remove(e.channel)?.forEach { close(e.channel, it, t) }
                    }
                    pedalDown[e.channel] = down
                }
            }
        }
        val end = seconds
        for ((key, starts) in open) {
            for ((start, velocity) in starts) notes += Note(key % 128, start, maxOf(end, start + 0.5), velocity, key / 128)
        }
        return MidiFile(notes.sortedWith(compareBy({ it.start }, { it.pitch })))
    }

    /** A format-0 MIDI file with [notes], for tests and generated exercises. */
    fun write(notes: List<Note>, bpm: Int = 120): ByteArray {
        val ticksPerQuarter = 480
        val ticksPerSecond = ticksPerQuarter * bpm / 60.0
        data class Ev(val tick: Long, val on: Boolean, val note: Note)
        val evs = notes.flatMap {
            listOf(Ev((it.start * ticksPerSecond).toLong(), true, it), Ev((it.end * ticksPerSecond).toLong(), false, it))
        }.sortedWith(compareBy({ it.tick }, { it.on }))
        val track = ByteBuilder()
        val micros = 60_000_000 / bpm
        track.varInt(0); track.bytes(0xFF, 0x51, 0x03, micros shr 16 and 0xFF, micros shr 8 and 0xFF, micros and 0xFF)
        var last = 0L
        for (e in evs) {
            track.varInt(e.tick - last)
            last = e.tick
            val ch = e.note.channel and 0x0F
            if (e.on) track.bytes(0x90 or ch, e.note.pitch, e.note.velocity) else track.bytes(0x80 or ch, e.note.pitch, 0)
        }
        track.varInt(0); track.bytes(0xFF, 0x2F, 0x00)
        val out = ByteBuilder()
        out.ascii("MThd"); out.u32(6); out.u16(0); out.u16(1); out.u16(ticksPerQuarter)
        out.ascii("MTrk"); out.u32(track.size); out.bytes(track.toByteArray())
        return out.toByteArray()
    }

    private class Reader(private val data: ByteArray) {
        var pos = 0
        val remaining: Int get() = data.size - pos

        fun u8(): Int {
            if (pos >= data.size) throw MidiException("Datei zu kurz")
            return data[pos++].toInt() and 0xFF
        }

        fun u16(): Int = (u8() shl 8) or u8()
        fun u32(): Long = (u16().toLong() shl 16) or u16().toLong()
        fun ascii(n: Int): String = buildString { repeat(n) { append(u8().toChar()) } }
        fun skip(n: Long) {
            pos = (pos + n.toInt()).coerceAtMost(data.size)
        }

        fun varInt(): Long {
            var value = 0L
            repeat(4) {
                val b = u8()
                value = (value shl 7) or (b and 0x7F).toLong()
                if (b and 0x80 == 0) return value
            }
            return value
        }
    }
}

internal class ByteBuilder {
    private var buf = ByteArray(256)
    var size = 0
        private set

    fun byte(b: Int) {
        if (size == buf.size) buf = buf.copyOf(size * 2)
        buf[size++] = b.toByte()
    }

    fun bytes(vararg b: Int) = b.forEach { byte(it) }
    fun bytes(b: ByteArray) = b.forEach { byte(it.toInt()) }
    fun ascii(s: String) = s.forEach { byte(it.code) }
    fun u16(v: Int) = bytes(v shr 8 and 0xFF, v and 0xFF)
    fun u32(v: Int) = bytes(v shr 24 and 0xFF, v shr 16 and 0xFF, v shr 8 and 0xFF, v and 0xFF)
    fun u16le(v: Int) = bytes(v and 0xFF, v shr 8 and 0xFF)
    fun u32le(v: Int) = bytes(v and 0xFF, v shr 8 and 0xFF, v shr 16 and 0xFF, v shr 24 and 0xFF)

    fun varInt(v: Long) {
        var buffer = v and 0x7F
        var rest = v shr 7
        while (rest > 0) {
            buffer = (buffer shl 8) or ((rest and 0x7F) or 0x80)
            rest = rest shr 7
        }
        while (true) {
            byte((buffer and 0xFF).toInt())
            if (buffer and 0x80 != 0L) buffer = buffer shr 8 else break
        }
    }

    fun toByteArray(): ByteArray = buf.copyOf(size)
}

/** The chords of a piece, as a list of names in the order they are played. */
object MidiAnalysis {
    data class Segment(val start: Double, val end: Double, val chord: Chord?, val pitches: List<Int>)

    /**
     * Cuts the piece where new notes start (notes within 60 ms count as one attack), names what
     * sounds in each piece, and joins neighbours with the same chord. Melody notes that on their own
     * make no chord are counted towards the chord they sound over.
     */
    fun segments(midi: MidiFile): List<Segment> {
        val notes = midi.notes.filter { it.channel != 9 }
        if (notes.isEmpty()) return emptyList()
        val onsets = mutableListOf<Double>()
        for (n in notes.sortedBy { it.start }) {
            if (onsets.isEmpty() || n.start - onsets.last() > 0.06) onsets += n.start
        }
        val raw = onsets.mapIndexed { i, t ->
            val end = onsets.getOrElse(i + 1) { midi.duration }
            val sounding = notes.filter { it.start < end - 0.001 && it.end > t + 0.03 && it.start <= t + 0.06 }
            val pcs = sounding.map { it.pitch % 12 }.toSet()
            val bass = sounding.minByOrNull { it.pitch }?.pitch?.rem(12)
            Segment(t, end, if (pcs.size >= 3 || (pcs.size == 2 && sounding.size >= 2)) Chord.identify(pcs, bass) else null, sounding.map { it.pitch })
        }
        val merged = mutableListOf<Segment>()
        for (s in raw) {
            val last = merged.lastOrNull()
            when {
                last == null -> merged += s
                s.chord == null || s.chord.key == last.chord?.key -> merged[merged.lastIndex] = last.copy(end = s.end)
                last.chord == null -> merged[merged.lastIndex] = s.copy(start = last.start)
                else -> merged += s
            }
        }
        return merged.filter { it.chord != null }
    }

    fun chords(midi: MidiFile): List<Chord> = segments(midi).mapNotNull { it.chord }

    /**
     * The interval of a piece made of exactly two notes, together or one after the other, in
     * semitones (1 up to 24, the Quintdezime); null for anything else.
     */
    fun interval(midi: MidiFile): Int? {
        val notes = midi.notes.filter { it.channel != 9 }
        if (notes.size != 2) return null
        return kotlin.math.abs(notes[0].pitch - notes[1].pitch).takeIf { it in 1..24 }
    }

    /** Two notes [semitones] apart above [low]: at once, or the upper one after the lower. */
    fun interval(low: Int, semitones: Int, together: Boolean, length: Double = 1.8, velocity: Int = 90): List<Note> =
        if (together) {
            listOf(Note(low, 0.0, length, velocity), Note(low + semitones, 0.0, length, velocity))
        } else {
            listOf(Note(low, 0.0, length * 0.55, velocity), Note(low + semitones, length * 0.6, length * 1.3, velocity))
        }

    /**
     * Close voicing around middle C. A slash chord gets its bass note an octave or two below;
     * with [rootBass] a plain chord gets its root there too (fuller, for progressions).
     */
    fun voice(chord: Chord, start: Double, length: Double, velocity: Int = 90, rootBass: Boolean = true): List<Note> {
        val base = 60 + chord.root % 12 - if (chord.root % 12 > 6) 12 else 0
        // A ninth sits above the triad (A C# E B), not squeezed next to the root; sus2 keeps its second.
        val hasThird = 3 in chord.quality.intervals || 4 in chord.quality.intervals
        val upper = chord.quality.intervals.map { base + if (it == 2 && hasThird) 14 else it }.sorted()
        val bassPc = chord.bass ?: chord.root.takeIf { rootBass }
        val bass = bassPc?.let { 36 + it % 12 }
        return (listOfNotNull(bass) + upper).distinct().map { Note(it, start, start + length, velocity) }
    }

    fun progression(chords: List<Chord>, secondsPerChord: Double = 1.4, rootBass: Boolean = true): List<Note> =
        chords.flatMapIndexed { i, c -> voice(c, i * secondsPerChord, secondsPerChord * 0.95, rootBass = rootBass) }
}

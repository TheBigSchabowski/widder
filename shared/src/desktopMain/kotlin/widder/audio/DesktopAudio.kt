package widder.audio

import java.io.ByteArrayInputStream
import java.io.File
import java.util.ServiceLoader
import java.util.concurrent.TimeUnit
import javax.sound.sampled.AudioFormat
import javax.sound.sampled.DataLine
import javax.sound.sampled.Line
import javax.sound.sampled.SourceDataLine
import javax.sound.sampled.TargetDataLine
import javax.sound.sampled.spi.AudioFileReader
import kotlin.concurrent.thread
import kotlin.math.abs
import javax.sound.sampled.AudioSystem as JavaSound

/** javax.sound output. Falls back to 44.1 kHz if the device does not take the file's rate. */
private class LineSink : PcmSink {
    private var line: SourceDataLine? = null
    private var frameBytes = 4
    private val bytes = ByteArray(2048 * 2 * 2)
    @Volatile private var closed = false

    override fun open(sampleRate: Int, channels: Int): Int? {
        for (rate in listOf(sampleRate, 44100, 48000).distinct()) {
            try {
                val format = AudioFormat(rate.toFloat(), 16, channels, true, false)
                val l = DesktopDevices.line(DesktopDevices.output, DataLine.Info(SourceDataLine::class.java, format)) as SourceDataLine
                // ~100 ms of buffer: short enough that stop is immediate and the playhead is on time.
                l.open(format, (rate / 10) * channels * 2)
                l.start()
                line = l
                frameBytes = channels * 2
                return rate
            } catch (e: Exception) {
                continue
            }
        }
        return null
    }

    override fun write(samples: ShortArray, count: Int) {
        val l = line ?: return
        val buf = if (count * 2 <= bytes.size) bytes else ByteArray(count * 2)
        for (i in 0 until count) {
            val s = samples[i].toInt()
            buf[2 * i] = s.toByte()
            buf[2 * i + 1] = (s shr 8).toByte()
        }
        if (!closed) l.write(buf, 0, count * 2)
    }

    override fun framesPlayed(): Long = line?.longFramePosition ?: 0

    override fun drainAndClose() {
        val l = line ?: return
        if (!closed) l.drain()
        abort()
    }

    @Synchronized
    override fun abort() {
        if (closed) return
        closed = true
        line?.let {
            it.stop()
            it.flush()
            it.close()
        }
    }
}

/**
 * The audio devices javax.sound sees (the built-in microphone, an audio interface ...) and which one
 * the app uses; a device that has gone falls back to the system's default.
 */
object DesktopDevices : AudioDevices {
    @Volatile var input: String? = null
        private set
    @Volatile var output: String? = null
        private set
    /** 0 = all channels mixed, else the interface's input 1, 2 ... */
    @Volatile var channel = 0
        private set

    override fun inputs(): List<String> = names(TargetDataLine::class.java)

    override fun outputs(): List<String> = names(SourceDataLine::class.java)

    override fun use(input: String?, output: String?, channel: Int) {
        this.input = input
        this.output = output
        this.channel = channel
    }

    private fun names(type: Class<*>): List<String> = runCatching {
        JavaSound.getMixerInfo()
            .filter { info -> runCatching { JavaSound.getMixer(info).isLineSupported(Line.Info(type)) }.getOrDefault(false) }
            .map { it.name }
            // macOS lists the system default as a device of its own; "Standard" stands for it.
            .filter { it != "Default Audio Device" }
            .distinct()
    }.getOrDefault(emptyList())

    /** A line on the device called [name], or on the default device when there is none by that name (or it refuses). */
    fun line(name: String?, info: DataLine.Info): Line {
        if (name != null) {
            JavaSound.getMixerInfo().firstOrNull { it.name == name }?.let { mixer ->
                runCatching { return JavaSound.getMixer(mixer).getLine(info) }
            }
        }
        return JavaSound.getLine(info)
    }
}

private class LineRecorder : AudioRecorder {
    @Volatile private var rate = 44100
    @Volatile private var line: TargetDataLine? = null
    @Volatile private var level = 0f
    private var data = ShortArray(44100 * 10)
    private var length = 0
    private var received = 0L
    /** Only the last few seconds are kept: listening (tuner, live vibrato) may go on for an hour. */
    @Volatile private var ring = false
    private var worker: Thread? = null

    override val sampleRate: Int get() = rate

    override fun start(): Boolean = open(keepAll = true)

    override fun listen(): Boolean = open(keepAll = false)

    /** Tries 44.1 and 48 kHz, mono or as many channels as the chosen input needs; an interface may take only some. */
    private fun open(keepAll: Boolean): Boolean {
        if (line != null) return true
        val channel = DesktopDevices.channel
        for (r in listOf(44100, 48000)) for (channels in if (channel == 0) listOf(1, 2) else listOf(2, 4, 8, 1).filter { it >= channel }) {
            try {
                val format = AudioFormat(r.toFloat(), 16, channels, true, false)
                val l = DesktopDevices.line(DesktopDevices.input, DataLine.Info(TargetDataLine::class.java, format)) as TargetDataLine
                l.open(format)
                l.start()
                rate = r
                ring = !keepAll
                synchronized(this) {
                    length = 0
                    received = 0
                }
                line = l
                worker = thread(name = "widder-record", isDaemon = true) { read(l, channels, channel) }
                return true
            } catch (e: Exception) {
                continue
            }
        }
        return false
    }

    /** Reads until stopped, keeping one channel ([pick], 1-based) or the mix of all (0). */
    private fun read(l: TargetDataLine, channels: Int, pick: Int) {
        val frameBytes = 2 * channels
        val buf = ByteArray(frameBytes * 1024)
        fun sample(frame: Int, ch: Int): Int {
            val i = frame * frameBytes + 2 * ch
            return ((buf[i].toInt() and 0xFF) or (buf[i + 1].toInt() shl 8)).toShort().toInt()
        }
        while (line === l) {
            val n = l.read(buf, 0, buf.size)
            if (n <= 0) continue
            var peak = 0
            synchronized(this) {
                for (f in 0 until n / frameBytes) {
                    val s = if (pick > 0) sample(f, pick - 1) else (0 until channels).sumOf { sample(f, it) } / channels
                    if (length == data.size) {
                        if (ring) {
                            data.copyInto(data, 0, data.size / 2, data.size)
                            length = data.size / 2
                        } else {
                            data = data.copyOf(data.size * 2)
                        }
                    }
                    data[length++] = s.toShort()
                    received++
                    peak = maxOf(peak, abs(s))
                }
            }
            level = peak / 32768f
        }
    }

    override fun level(): Float = level

    override fun stop(): Pcm? {
        val l = line ?: return null
        line = null
        l.stop()
        l.close()
        worker?.join(500)
        level = 0f
        return synchronized(this) { if (length == 0) null else Pcm.fromShorts(rate, 1, data, length) }
    }

    override val recorded: Int get() = synchronized(this) { length }

    override val total: Long get() = synchronized(this) { received }

    override fun snapshot(into: FloatArray): Long = synchronized(this) {
        latest(into)
        received
    }

    override fun latest(into: FloatArray): Int = synchronized(this) {
        val n = minOf(into.size, length)
        for (i in 0 until n) into[into.size - n + i] = data[length - n + i] / 32768f
        n
    }

    override val isRecording: Boolean get() = line != null
}

/**
 * Tries one reader after the other: the JDK's own (AIFF, AU), macOS' `afconvert` (every Mac has it), the Java
 * decoders for MP3, M4A/AAC, FLAC and OGG, and last `ffmpeg` if it is installed (Opus, CAF, ALAC and the rest).
 */
internal class DesktopDecoder(private val ffmpeg: String? = findTool("ffmpeg")) : AudioDecoder {
    override fun decode(bytes: ByteArray, extension: String): Pcm? =
        javaSound(bytes)
            ?: convert("/usr/bin/afconvert", bytes, extension) { input, output -> listOf("-f", "WAVE", "-d", "LEI16", input, output) }
            ?: JavaDecoders.decode(bytes, extension)
            ?: ffmpeg?.let { tool ->
                convert(tool, bytes, extension) { input, output ->
                    listOf("-v", "error", "-nostdin", "-y", "-i", input, "-vn", "-f", "wav", "-c:a", "pcm_s16le", output)
                }
            }

    // Only the JDK's own readers: one from a jar on the class path would claim files that are not its own.
    private val jdkReaders by lazy {
        ServiceLoader.load(AudioFileReader::class.java, ClassLoader.getPlatformClassLoader()).toList()
    }

    private fun javaSound(bytes: ByteArray): Pcm? {
        for (reader in jdkReaders) {
            try {
                val input = reader.getAudioInputStream(ByteArrayInputStream(bytes))
                val target = AudioFormat(input.format.sampleRate, 16, input.format.channels, true, false)
                val pcm = JavaSound.getAudioInputStream(target, input).readAllBytes()
                val shorts = ShortArray(pcm.size / 2) { ((pcm[2 * it].toInt() and 0xFF) or (pcm[2 * it + 1].toInt() shl 8)).toShort() }
                return Pcm.fromShorts(target.sampleRate.toInt(), target.channels, shorts)
            } catch (e: Exception) {
                continue
            }
        }
        return null
    }

    /** Runs a command line converter on temporary files and reads the WAV it writes. */
    private fun convert(tool: String, bytes: ByteArray, extension: String, arguments: (input: String, output: String) -> List<String>): Pcm? {
        if (!File(tool).canExecute()) return null
        val input = File.createTempFile("widder-in", ".$extension")
        val output = File.createTempFile("widder-out", ".wav")
        return try {
            input.writeBytes(bytes)
            val process = ProcessBuilder(listOf(tool) + arguments(input.path, output.path))
                .redirectErrorStream(true)
                .redirectOutput(ProcessBuilder.Redirect.DISCARD)
                .start()
            if (!process.waitFor(60, TimeUnit.SECONDS)) {
                process.destroyForcibly()
                null
            } else if (process.exitValue() != 0) {
                null
            } else {
                Wav.read(output.readBytes())
            }
        } catch (e: Exception) {
            null
        } finally {
            input.delete()
            output.delete()
        }
    }
}

/** The first executable [name] on the PATH. A Mac app started from the Dock has a short PATH, so Homebrew's folders are tried as well. */
internal fun findTool(name: String): String? =
    (System.getenv("PATH").orEmpty().split(File.pathSeparatorChar) + listOf("/opt/homebrew/bin", "/usr/local/bin"))
        .filter { it.isNotBlank() }
        .map { File(it, name) }
        .firstOrNull { it.isFile && it.canExecute() }
        ?.path

fun desktopAudioSystem(): AudioSystem =
    AudioSystem(StreamingPlayer { LineSink() }, LineRecorder(), DesktopDecoder(), LiveStream { LineSink() }, ::bundledResource, DesktopDevices)

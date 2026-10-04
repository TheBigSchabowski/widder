package widder.audio

import java.io.ByteArrayInputStream
import java.io.File
import java.util.ServiceLoader
import java.util.concurrent.TimeUnit
import javax.sound.sampled.AudioFormat
import javax.sound.sampled.DataLine
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
                val l = JavaSound.getLine(DataLine.Info(SourceDataLine::class.java, format)) as SourceDataLine
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

private class LineRecorder : AudioRecorder {
    private val rate = 44100
    @Volatile private var line: TargetDataLine? = null
    @Volatile private var level = 0f
    private var data = ShortArray(rate * 10)
    private var length = 0
    private var worker: Thread? = null

    override fun start(): Boolean {
        if (line != null) return true
        return try {
            val format = AudioFormat(rate.toFloat(), 16, 1, true, false)
            val l = JavaSound.getLine(DataLine.Info(TargetDataLine::class.java, format)) as TargetDataLine
            l.open(format)
            l.start()
            line = l
            length = 0
            worker = thread(name = "widder-record", isDaemon = true) {
                val buf = ByteArray(2048)
                while (line === l) {
                    val n = l.read(buf, 0, buf.size)
                    if (n <= 0) continue
                    var peak = 0
                    synchronized(this) {
                        for (i in 0 until n / 2) {
                            val s = ((buf[2 * i].toInt() and 0xFF) or (buf[2 * i + 1].toInt() shl 8)).toShort()
                            if (length == data.size) data = data.copyOf(data.size * 2)
                            data[length++] = s
                            peak = maxOf(peak, abs(s.toInt()))
                        }
                    }
                    level = peak / 32768f
                }
            }
            true
        } catch (e: Exception) {
            line = null
            false
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

fun desktopAudioSystem(): AudioSystem = AudioSystem(StreamingPlayer { LineSink() }, LineRecorder(), DesktopDecoder())

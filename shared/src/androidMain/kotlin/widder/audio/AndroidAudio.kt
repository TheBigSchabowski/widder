package widder.audio

import android.annotation.SuppressLint
import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.AudioTrack
import android.media.MediaCodec
import android.media.MediaExtractor
import android.media.MediaFormat
import android.media.MediaRecorder
import java.io.File
import java.nio.ByteOrder
import kotlin.concurrent.thread
import kotlin.math.abs

private class TrackSink : PcmSink {
    private var track: AudioTrack? = null
    @Volatile private var closed = false
    private var written = 0L
    private var channels = 1

    override fun open(sampleRate: Int, channels: Int): Int? = try {
        val mask = if (channels == 2) AudioFormat.CHANNEL_OUT_STEREO else AudioFormat.CHANNEL_OUT_MONO
        val min = AudioTrack.getMinBufferSize(sampleRate, mask, AudioFormat.ENCODING_PCM_16BIT)
        val t = AudioTrack.Builder()
            .setAudioAttributes(
                AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_MEDIA)
                    .setContentType(AudioAttributes.CONTENT_TYPE_MUSIC).build(),
            )
            .setAudioFormat(
                AudioFormat.Builder().setSampleRate(sampleRate).setChannelMask(mask)
                    .setEncoding(AudioFormat.ENCODING_PCM_16BIT).build(),
            )
            .setBufferSizeInBytes(maxOf(min, sampleRate / 10 * channels * 2))
            .setTransferMode(AudioTrack.MODE_STREAM)
            .build()
        t.play()
        track = t
        this.channels = channels
        sampleRate
    } catch (e: Exception) {
        null
    }

    override fun write(samples: ShortArray, count: Int) {
        val t = track ?: return
        var offset = 0
        while (offset < count && !closed) {
            val n = t.write(samples, offset, count - offset)
            if (n <= 0) break
            offset += n
        }
        written += count / channels
    }

    override fun framesPlayed(): Long = (track?.playbackHeadPosition?.toLong() ?: 0L) and 0xFFFFFFFFL

    override fun drainAndClose() {
        val t = track ?: return
        // AudioTrack has no drain: wait until the play head has reached what was written.
        val deadline = System.currentTimeMillis() + 3000
        while (!closed && framesPlayed() < written && System.currentTimeMillis() < deadline) Thread.sleep(10)
        abort()
    }

    @Synchronized
    override fun abort() {
        if (closed) return
        closed = true
        track?.let {
            runCatching { it.pause(); it.flush(); it.stop() }
            it.release()
        }
    }
}

private class MicRecorder : AudioRecorder {
    private val rate = 44100
    @Volatile private var record: AudioRecord? = null
    @Volatile private var level = 0f
    private var data = ShortArray(rate * 10)
    private var length = 0
    private var worker: Thread? = null

    @SuppressLint("MissingPermission") // The activity asks for RECORD_AUDIO before recording starts.
    override fun start(): Boolean {
        if (record != null) return true
        return try {
            val min = AudioRecord.getMinBufferSize(rate, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT)
            val r = AudioRecord(MediaRecorder.AudioSource.MIC, rate, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT, maxOf(min, 8192))
            if (r.state != AudioRecord.STATE_INITIALIZED) {
                r.release()
                return false
            }
            r.startRecording()
            record = r
            length = 0
            worker = thread(name = "widder-record", isDaemon = true) {
                val buf = ShortArray(2048)
                while (record === r) {
                    val n = r.read(buf, 0, buf.size)
                    if (n <= 0) continue
                    var peak = 0
                    synchronized(this) {
                        for (i in 0 until n) {
                            if (length == data.size) data = data.copyOf(data.size * 2)
                            data[length++] = buf[i]
                            peak = maxOf(peak, abs(buf[i].toInt()))
                        }
                    }
                    level = peak / 32768f
                }
            }
            true
        } catch (e: Exception) {
            record = null
            false
        }
    }

    override fun level(): Float = level

    override fun stop(): Pcm? {
        val r = record ?: return null
        record = null
        runCatching { r.stop() }
        worker?.join(500)
        r.release()
        level = 0f
        return synchronized(this) { if (length == 0) null else Pcm.fromShorts(rate, 1, data, length) }
    }

    override val isRecording: Boolean get() = record != null
}

/** MP3, AAC/M4A, FLAC, OGG/Opus ... through Android's MediaCodec. */
private class CodecDecoder(private val cacheDir: File) : AudioDecoder {
    override fun decode(bytes: ByteArray, extension: String): Pcm? {
        val file = File.createTempFile("widder-decode", ".$extension", cacheDir)
        val extractor = MediaExtractor()
        var codec: MediaCodec? = null
        try {
            file.writeBytes(bytes)
            extractor.setDataSource(file.path)
            val track = (0 until extractor.trackCount).firstOrNull {
                extractor.getTrackFormat(it).getString(MediaFormat.KEY_MIME)?.startsWith("audio/") == true
            } ?: return null
            extractor.selectTrack(track)
            val format = extractor.getTrackFormat(track)
            var rate = format.getInteger(MediaFormat.KEY_SAMPLE_RATE)
            var channels = format.getInteger(MediaFormat.KEY_CHANNEL_COUNT)
            val c = MediaCodec.createDecoderByType(format.getString(MediaFormat.KEY_MIME)!!)
            codec = c
            c.configure(format, null, null, 0)
            c.start()
            val info = MediaCodec.BufferInfo()
            var out = ShortArray(rate * channels * 30)
            var length = 0
            var inputDone = false
            var outputDone = false
            while (!outputDone) {
                if (!inputDone) {
                    val index = c.dequeueInputBuffer(10_000)
                    if (index >= 0) {
                        val buffer = c.getInputBuffer(index)!!
                        val size = extractor.readSampleData(buffer, 0)
                        if (size < 0) {
                            c.queueInputBuffer(index, 0, 0, 0, MediaCodec.BUFFER_FLAG_END_OF_STREAM)
                            inputDone = true
                        } else {
                            c.queueInputBuffer(index, 0, size, extractor.sampleTime, 0)
                            extractor.advance()
                        }
                    }
                }
                val index = c.dequeueOutputBuffer(info, 10_000)
                when {
                    index == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED -> {
                        rate = c.outputFormat.getInteger(MediaFormat.KEY_SAMPLE_RATE)
                        channels = c.outputFormat.getInteger(MediaFormat.KEY_CHANNEL_COUNT)
                    }
                    index >= 0 -> {
                        val buffer = c.getOutputBuffer(index)!!.order(ByteOrder.LITTLE_ENDIAN)
                        buffer.position(info.offset)
                        buffer.limit(info.offset + info.size)
                        val shorts = buffer.asShortBuffer()
                        val n = shorts.remaining()
                        if (length + n > out.size) out = out.copyOf(maxOf(out.size * 2, length + n))
                        shorts.get(out, length, n)
                        length += n
                        c.releaseOutputBuffer(index, false)
                        if (info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0) outputDone = true
                    }
                }
            }
            return if (length == 0) null else Pcm.fromShorts(rate, channels, out, length - length % channels)
        } catch (e: Exception) {
            return null
        } finally {
            runCatching { codec?.stop() }
            codec?.release()
            extractor.release()
            file.delete()
        }
    }
}

fun androidAudioSystem(cacheDir: File): AudioSystem =
    AudioSystem(StreamingPlayer { TrackSink() }, MicRecorder(), CodecDecoder(cacheDir))

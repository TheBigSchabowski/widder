package widder.audio

import com.jcraft.jogg.Packet
import com.jcraft.jogg.Page
import com.jcraft.jogg.StreamState
import com.jcraft.jogg.SyncState
import com.jcraft.jorbis.Block
import com.jcraft.jorbis.Comment
import com.jcraft.jorbis.DspState
import com.jcraft.jorbis.Info
import javazoom.jl.decoder.Bitstream
import javazoom.jl.decoder.BitstreamException
import javazoom.jl.decoder.DecoderException
import javazoom.jl.decoder.SampleBuffer
import net.sourceforge.jaad.aac.AACException
import net.sourceforge.jaad.aac.Receiver
import net.sourceforge.jaad.adts.ADTSDemultiplexer
import net.sourceforge.jaad.mp4.MP4Container
import net.sourceforge.jaad.mp4.MP4InputStream
import net.sourceforge.jaad.mp4.api.AudioTrack
import org.jflac.FLACDecoder
import org.jflac.PCMProcessor
import org.jflac.metadata.StreamInfo
import org.jflac.util.ByteData
import java.io.ByteArrayInputStream
import java.io.EOFException
import java.io.IOException
import javazoom.jl.decoder.Decoder as Mp3Decoder
import net.sourceforge.jaad.aac.Decoder as AacDecoder

/**
 * MP3, M4A/AAC, FLAC and OGG Vorbis in plain Java, so a machine without afconvert or ffmpeg still opens them.
 * Each decoder is called directly, chosen by the first bytes of the file: through javax.sound the first
 * reader that is registered gets any file, and the AAC one does not give back what is not its own.
 */
internal object JavaDecoders {
    fun decode(bytes: ByteArray, extension: String): Pcm? = try {
        when {
            bytes.startsWith("fLaC") -> flac(bytes)
            bytes.startsWith("OggS") -> if (bytes.ascii(28, 7) == "vorbis") vorbis(bytes) else null
            bytes.ascii(4, 4) == "ftyp" -> mp4(bytes)
            isAdts(bytes) -> adts(bytes)
            bytes.startsWith("ID3") || isMpegFrame(bytes) || extension == "mp3" -> mp3(bytes)
            else -> null
        }
    } catch (e: Exception) {
        null
    } catch (e: OutOfMemoryError) {
        // A damaged file can announce a block of gigabytes; that is a file we cannot read, not a reason to crash.
        null
    }

    // ADTS and MP3 both start with eleven 1-bits; ADTS has 00 where MP3 names its layer.
    private fun isAdts(b: ByteArray) = b.size > 1 && b[0] == 0xFF.toByte() && (b[1].toInt() and 0xF6) == 0xF0

    private fun isMpegFrame(b: ByteArray) = b.size > 1 && b[0] == 0xFF.toByte() && (b[1].toInt() and 0xE0) == 0xE0

    private fun mp3(bytes: ByteArray): Pcm? {
        // A cover picture in the ID3 tag has bytes that look like frame headers, so the tag is skipped whole.
        val start = id3Length(bytes)
        val stream = Bitstream(ByteArrayInputStream(bytes, start, bytes.size - start))
        val decoder = Mp3Decoder()
        var out: PcmBuilder? = null
        try {
            while (true) {
                val header = stream.readFrame() ?: break
                try {
                    val samples = decoder.decodeFrame(header, stream) as SampleBuffer
                    val channels = decoder.outputChannels
                    val target = out ?: PcmBuilder(decoder.outputFrequency, channels).also { out = it }
                    val data = samples.buffer
                    target.add(samples.bufferLength / channels) { c, f -> data[f * channels + c] / 32768f }
                } catch (e: DecoderException) {
                    // A damaged frame is skipped; the rest of the file still plays.
                } finally {
                    stream.closeFrame()
                }
            }
        } catch (e: BitstreamException) {
            // Garbage or a cut-off file: keep what was decoded up to there.
        }
        return out?.build()
    }

    /** Size of an ID3v2 tag at the start of the file, header and footer included; 0 if there is none. */
    private fun id3Length(b: ByteArray): Int {
        if (!b.startsWith("ID3") || b.size < 10) return 0
        // Four bytes with seven bits each; a byte with the top bit set means this is no tag after all.
        val size = (6..9).fold(0) { sum, i -> if (b[i] < 0) return 0 else (sum shl 7) or b[i].toInt() }
        val footer = if (b[5].toInt() and 0x10 != 0) 10 else 0
        return minOf(10 + size + footer, b.size)
    }

    private fun flac(bytes: ByteArray): Pcm? {
        var out: PcmBuilder? = null
        var channels = 0
        var width = 0
        val decoder = FLACDecoder(ByteArrayInputStream(bytes))
        decoder.addPCMProcessor(
            object : PCMProcessor {
                override fun processStreamInfo(info: StreamInfo) {
                    require(info.bitsPerSample == 16 || info.bitsPerSample == 24) { "unsupported bit depth" }
                    channels = info.channels
                    width = info.bitsPerSample / 8
                    out = PcmBuilder(info.sampleRate, channels)
                }

                // jFLAC hands over WAV-style bytes: signed, little-endian, interleaved.
                override fun processPCM(pcm: ByteData) {
                    val target = out ?: return
                    val data = pcm.data
                    target.add(pcm.len / (width * channels)) { c, f ->
                        val at = (f * channels + c) * width
                        if (width == 2) {
                            ((data[at].toInt() and 0xFF) or (data[at + 1].toInt() shl 8)) / 32768f
                        } else {
                            (
                                (data[at].toInt() and 0xFF) or
                                    ((data[at + 1].toInt() and 0xFF) shl 8) or
                                    (data[at + 2].toInt() shl 16)
                                ) / 8388608f
                        }
                    }
                }
            },
        )
        decoder.decode()
        return out?.build()
    }

    /** The loop from JOrbis' own example: read the three headers, then turn every packet into float samples. */
    private fun vorbis(bytes: ByteArray): Pcm? {
        val sync = SyncState().also { it.init() }
        val stream = StreamState()
        val page = Page()
        val packet = Packet()
        val info = Info().also { it.init() }
        val comment = Comment().also { it.init() }
        var position = 0

        fun feed(): Boolean {
            if (position >= bytes.size) return false
            val n = minOf(4096, bytes.size - position)
            // buffer() may swap in a bigger array, so sync.data is read only after it.
            val at = sync.buffer(n)
            System.arraycopy(bytes, position, sync.data, at, n)
            sync.wrote(n)
            position += n
            return true
        }

        feed()
        if (sync.pageout(page) != 1) return null
        stream.init(page.serialno())
        if (stream.pagein(page) < 0 || stream.packetout(packet) != 1 || info.synthesis_headerin(comment, packet) < 0) return null

        var headers = 0
        while (headers < 2) {
            when (sync.pageout(page)) {
                0 -> if (!feed()) return null
                1 -> {
                    stream.pagein(page)
                    while (headers < 2) {
                        val result = stream.packetout(packet)
                        if (result == 0) break
                        if (result < 0 || info.synthesis_headerin(comment, packet) < 0) return null
                        headers++
                    }
                }
            }
        }

        val dsp = DspState()
        val block = Block(dsp)
        if (dsp.synthesis_init(info) != 0) return null
        block.init(dsp)
        val out = PcmBuilder(info.rate, info.channels)
        val pcm = Array(1) { emptyArray<FloatArray>() }
        val index = IntArray(info.channels)
        var ended = false
        while (!ended) {
            when (sync.pageout(page)) {
                0 -> if (!feed()) ended = true
                1 -> {
                    stream.pagein(page)
                    while (true) {
                        val result = stream.packetout(packet)
                        if (result == 0) break
                        if (result < 0) continue
                        if (block.synthesis(packet) == 0) dsp.synthesis_blockin(block)
                        while (true) {
                            val samples = dsp.synthesis_pcmout(pcm, index)
                            if (samples <= 0) break
                            out.add(samples) { c, f -> pcm[0][c][index[c] + f] }
                            dsp.synthesis_read(samples)
                        }
                    }
                    if (page.eos() != 0) ended = true
                }
            }
        }
        return out.build()
    }

    private fun mp4(bytes: ByteArray): Pcm? {
        val movie = MP4Container(MP4InputStream.open(StrictInput(bytes))).movie
        val track = movie.getTracks(AudioTrack.AudioCodec.AAC).firstOrNull() as? AudioTrack ?: return null
        val decoder = AacDecoder.create(track.decoderSpecificInfo.data)
        val receiver = AacReceiver(track.channelCount)
        try {
            while (track.hasMoreFrames()) receiver.decode(decoder, (track.readNextFrame() ?: break).data)
        } catch (e: IOException) {
            // A cut-off file: keep what was decoded up to there.
        }
        return receiver.build()
    }

    private fun adts(bytes: ByteArray): Pcm? {
        val demultiplexer = ADTSDemultiplexer(ByteArrayInputStream(bytes))
        val decoder = AacDecoder.create(demultiplexer.decoderInfo)
        val receiver = AacReceiver(demultiplexer.channelCount)
        try {
            while (true) receiver.decode(decoder, demultiplexer.readNextFrame() ?: break)
        } catch (e: IOException) {
            // End of the stream.
        }
        return receiver.build()
    }

    /**
     * jaad's MP4 reader skips over a block by calling skip() until enough bytes are gone, which never ends
     * once a cut-off file has no more of them; here skipping past the end is an error.
     */
    private class StrictInput(bytes: ByteArray) : ByteArrayInputStream(bytes) {
        override fun skip(n: Long): Long {
            if (n > available()) throw EOFException("skip past the end of the file")
            return super.skip(n)
        }
    }

    /** jaad hands over each frame as float samples on the 16-bit scale. [channels] is what the file says it has. */
    private class AacReceiver(private val channels: Int) : Receiver {
        private var out: PcmBuilder? = null

        fun decode(decoder: AacDecoder, frame: ByteArray) {
            try {
                decoder.decodeFrame(frame, this)
            } catch (e: AACException) {
                // A damaged frame is skipped.
            }
        }

        override fun accept(data: List<FloatArray>, length: Int, sampleRate: Int) {
            // For a mono file jaad still hands over two arrays, the second a copy of the first.
            val target = out ?: PcmBuilder(sampleRate, minOf(channels.coerceAtLeast(1), data.size)).also { out = it }
            target.add(length) { c, f ->
                val channel = data[minOf(c, data.size - 1)]
                channel[channel.size * f / length] / 32768f
            }
        }

        fun build(): Pcm? = out?.build()
    }

    /** Decoded audio of unknown length: one growing float array per channel. */
    private class PcmBuilder(private val rate: Int, private val channelCount: Int) {
        private var data = Array(channelCount) { FloatArray(rate) }
        private var length = 0

        inline fun add(frames: Int, sample: (channel: Int, frame: Int) -> Float) {
            reserve(frames)
            for (c in 0 until channelCount) {
                val channel = data[c]
                for (f in 0 until frames) channel[length + f] = sample(c, f)
            }
            length += frames
        }

        private fun reserve(frames: Int) {
            if (length + frames <= data[0].size) return
            val size = maxOf(data[0].size * 2, length + frames)
            data = Array(channelCount) { data[it].copyOf(size) }
        }

        fun build(): Pcm? = if (length == 0) null else Pcm(rate, data.map { it.copyOf(length) })
    }

    private fun ByteArray.ascii(at: Int, length: Int): String =
        if (at + length > size) "" else String(this, at, length, Charsets.ISO_8859_1)

    private fun ByteArray.startsWith(text: String) = ascii(0, text.length) == text
}

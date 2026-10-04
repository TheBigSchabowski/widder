package widder.audio

import org.junit.Assume.assumeTrue
import java.io.File
import kotlin.concurrent.thread
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.sin
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.test.fail

/**
 * The files in resources/audio are 0.4 s of a 440 Hz tone, stereo ones with 660 Hz on the right
 * channel; ffmpeg made them. The tests listen for the tones, so a swapped or lost channel fails too.
 */
class DesktopDecoderTest {
    private fun load(name: String): ByteArray =
        checkNotNull(javaClass.getResourceAsStream("/audio/$name")) { "$name fehlt" }.use { it.readBytes() }

    /** Signal strength of [hz] in the frames [from] until [to] (Goertzel); a sine of amplitude a gives a² / 4. */
    private fun strength(samples: FloatArray, from: Int, to: Int, rate: Int, hz: Double): Double {
        var re = 0.0
        var im = 0.0
        for (i in from until to) {
            val phase = 2 * PI * hz * i / rate
            re += samples[i] * cos(phase)
            im += samples[i] * sin(phase)
        }
        val n = (to - from).toDouble()
        return (re * re + im * im) / (n * n)
    }

    private fun assertTone(pcm: Pcm?, channels: Int, rate: Int? = null, name: String = "") {
        assertNotNull(pcm, "$name: nicht gelesen")
        assertEquals(channels, pcm.channels.size, "$name: Kanäle")
        if (rate != null) assertEquals(rate, pcm.sampleRate, "$name: Abtastrate")
        // Encoders add silence at both ends (an MP3 about 0.1 s), and nobody trims it.
        assertTrue(abs(pcm.duration - 0.4) < 0.15, "$name: Dauer ${pcm.duration}")
        // The middle part only: no encoder delay or padding in it.
        val from = pcm.frames * 3 / 10
        val to = pcm.frames * 7 / 10
        val left = pcm.channels[0]
        val left440 = strength(left, from, to, pcm.sampleRate, 440.0)
        val left660 = strength(left, from, to, pcm.sampleRate, 660.0)
        assertTrue(left440 > 0.03 && left440 > 50 * left660, "$name: links 440 Hz $left440, 660 Hz $left660")
        if (channels == 2) {
            val right = pcm.channels[1]
            val right440 = strength(right, from, to, pcm.sampleRate, 440.0)
            val right660 = strength(right, from, to, pcm.sampleRate, 660.0)
            assertTrue(right660 > 0.03 && right660 > 50 * right440, "$name: rechts 440 Hz $right440, 660 Hz $right660")
        }
    }

    private fun java(name: String, extension: String = name.substringAfterLast('.')): Pcm? = JavaDecoders.decode(load(name), extension)

    @Test
    fun mp3InStereoAndMono() {
        assertTone(java("stereo-44k.mp3"), 2, 44100, "mp3 stereo")
        assertTone(java("mono-22k.mp3"), 1, 22050, "mp3 mono")
    }

    @Test
    fun mp3WithACoverPicture() = assertTone(java("stereo-44k-cover.mp3"), 2, 44100, "mp3 mit Cover")

    @Test
    fun m4aInStereoAndMono() {
        assertTone(java("stereo-44k.m4a"), 2, 44100, "m4a stereo")
        assertTone(java("mono-22k.m4a"), 1, 22050, "m4a mono")
    }

    @Test
    fun m4aWithACoverPicture() = assertTone(java("stereo-44k-cover.m4a"), 2, 44100, "m4a mit Cover")

    @Test
    fun aacInAdtsFrames() = assertTone(java("stereo-44k.aac"), 2, 44100, "aac")

    @Test
    fun flacWith16And24Bit() {
        assertTone(java("stereo-16bit.flac"), 2, 22050, "flac 16 Bit")
        assertTone(java("stereo-24bit.flac"), 2, 22050, "flac 24 Bit")
    }

    @Test
    fun flacWithACoverPicture() = assertTone(java("stereo-16bit-cover.flac"), 2, 22050, "flac mit Cover")

    @Test
    fun oggVorbisInStereoAndMono() {
        assertTone(java("stereo-44k.ogg"), 2, 44100, "ogg stereo")
        assertTone(java("mono-22k.ogg"), 1, 22050, "ogg mono")
    }

    @Test
    fun theJavaDecodersGoByTheContentNotTheName() {
        // A wrong or missing extension must not matter, and an MP3 is not mistaken for AAC.
        assertTone(java("stereo-44k.mp3", "m4a"), 2, 44100, "mp3 als .m4a")
        assertTone(java("stereo-44k.m4a", "mp3"), 2, 44100, "m4a als .mp3")
        assertTone(java("stereo-44k.aac", "mp3"), 2, 44100, "aac als .mp3")
        assertTone(java("stereo-16bit.flac", ""), 2, 22050, "flac ohne Endung")
        assertTone(java("stereo-44k.ogg", "flac"), 2, 44100, "ogg als .flac")
    }

    @Test
    fun aiffAndAuComeFromTheJdk() {
        val decoder = DesktopDecoder(ffmpeg = null)
        assertTone(decoder.decode(load("stereo-8k.aiff"), "aiff"), 2, 8000, "aiff")
        // u-law is expanded to 16 bit on the way.
        assertTone(decoder.decode(load("mono-8k-ulaw.au"), "au"), 1, 8000, "au u-law")
    }

    @Test
    fun theWholeChainReadsEveryFormatWithoutFfmpeg() {
        val decoder = DesktopDecoder(ffmpeg = null)
        for (name in listOf("stereo-44k.mp3", "stereo-44k.m4a", "stereo-44k.aac", "stereo-16bit.flac", "stereo-44k.ogg")) {
            assertTone(decoder.decode(load(name), name.substringAfterLast('.')), 2, name = name)
        }
    }

    @Test
    fun opusAndCafAreLeftToFfmpeg() {
        val ffmpeg = findTool("ffmpeg")
        assumeTrue("ffmpeg ist nicht installiert", ffmpeg != null)
        val before = leftovers()
        val decoder = DesktopDecoder(ffmpeg)
        assertTone(decoder.decode(load("stereo.opus"), "opus"), 2, name = "opus")
        assertTone(decoder.decode(load("stereo.caf"), "caf"), 2, name = "caf")
        assertEquals(before, leftovers(), "temporäre Dateien liegen geblieben")
    }

    @Test
    fun withoutFfmpegOpusIsNotRead() {
        // Without afconvert (every machine but a Mac) nothing in the chain knows Opus; on a Mac afconvert may.
        assumeTrue(!File("/usr/bin/afconvert").canExecute())
        assertNull(DesktopDecoder(ffmpeg = null).decode(load("stereo.opus"), "opus"))
    }

    @Test
    fun aBrokenConverterIsNoProblem() {
        val failing = findTool("false")
        val silent = findTool("true")
        assumeTrue("false und true fehlen", failing != null && silent != null)
        // Exits with an error; exits fine but writes nothing.
        assertNull(DesktopDecoder(failing).decode("kein Audio".toByteArray(), "xyz"))
        assertNull(DesktopDecoder(silent).decode("kein Audio".toByteArray(), "xyz"))
        assertNull(DesktopDecoder("/gibt/es/nicht").decode("kein Audio".toByteArray(), "xyz"))
    }

    @Test
    fun findToolLooksOnThePath() {
        assertNotNull(findTool("sh"))
        assertNull(findTool("gibt-es-bestimmt-nicht-1234"))
    }

    private fun leftovers() = File(System.getProperty("java.io.tmpdir")).list { _, name -> name.startsWith("widder-in") || name.startsWith("widder-out") }
        .orEmpty().toSet()

    // Damaged files: whatever comes out, nothing may throw or hang.

    private val formats = listOf("stereo-44k.mp3", "stereo-44k.m4a", "stereo-44k.aac", "stereo-16bit.flac", "stereo-44k.ogg")

    /** Decodes on another thread so that a hang fails this test instead of blocking the whole run. */
    private fun decodeWithin(bytes: ByteArray, extension: String, what: String): Pcm? {
        var result: Result<Pcm?>? = null
        val worker = thread(isDaemon = true) { result = runCatching { DesktopDecoder(ffmpeg = null).decode(bytes, extension) } }
        worker.join(30_000)
        if (worker.isAlive) fail("$what: hängt")
        return result!!.getOrElse { fail("$what: Ausnahme $it") }
    }

    @Test
    fun aCutOffFileGivesWhatThereWas() {
        for (name in formats) {
            val bytes = load(name)
            val extension = name.substringAfterLast('.')
            val whole = checkNotNull(decodeWithin(bytes, extension, name)) { "$name nicht gelesen" }
            for (share in listOf(0.2, 0.5, 0.9)) {
                val cut = bytes.copyOf((bytes.size * share).toInt())
                val part = decodeWithin(cut, extension, "$name auf ${(share * 100).toInt()} % gekürzt")
                assertTrue(part == null || part.frames <= whole.frames, "$name auf $share: länger als das Ganze")
                if (part != null) assertEquals(whole.channels.size, part.channels.size, "$name auf $share: Kanäle")
            }
        }
    }

    @Test
    fun aFileWithDamagedBytesIsReadOrRefused() {
        val random = Random(7)
        for (name in formats) {
            val bytes = load(name)
            val extension = name.substringAfterLast('.')
            for (round in 0 until 6) {
                val damaged = bytes.copyOf()
                repeat(1 + damaged.size / 200) { damaged[random.nextInt(damaged.size)] = random.nextInt().toByte() }
                val pcm = decodeWithin(damaged, extension, "$name, Durchgang $round")
                if (pcm != null) {
                    assertTrue(pcm.frames < 10 * pcm.sampleRate, "$name: unsinnig lang")
                    assertTrue(pcm.channels.all { channel -> channel.all { it.isFinite() } }, "$name: Werte ohne Zahl")
                }
            }
        }
    }

    @Test
    fun noiseAndTextAreNotAudio() {
        val noise = Random(3).nextBytes(20_000)
        val text = "Das hier ist ein Brief und keine Musik. ".repeat(50).toByteArray()
        for (extension in listOf("mp3", "m4a", "aac", "flac", "ogg", "wav", "opus", "")) {
            for ((what, bytes) in listOf("Rauschen" to noise, "Text" to text, "leer" to ByteArray(0), "ein Byte" to ByteArray(1) { 0xFF.toByte() })) {
                val pcm = decodeWithin(bytes, extension, "$what als .$extension")
                if (what != "Rauschen" || extension != "mp3") assertNull(pcm, "$what als .$extension")
            }
        }
    }
}

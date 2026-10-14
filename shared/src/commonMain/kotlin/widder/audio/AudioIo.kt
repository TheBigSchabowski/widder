package widder.audio

/** Plays one piece of audio at a time; starting another stops the current one. */
interface AudioPlayer {
    /** [onEnd] runs on the audio thread when the audio has played out (not when stopped). */
    fun play(pcm: Pcm, fromSeconds: Double = 0.0, onEnd: () -> Unit = {})

    fun stop()

    val isPlaying: Boolean

    /** Seconds into the audio currently playing, as heard. */
    fun position(): Double
}

interface AudioRecorder {
    /** Starts recording from the microphone; false if that is not possible (no device, no permission). */
    fun start(): Boolean

    /** Loudness of the last few milliseconds, 0..1, for the level meter. */
    fun level(): Float

    fun stop(): Pcm?

    val isRecording: Boolean
}

/** Decodes compressed formats the platform knows (MP3, M4A/AAC, FLAC, OGG ...). WAV and MIDI are handled in common code. */
fun interface AudioDecoder {
    fun decode(bytes: ByteArray, extension: String): Pcm?
}

/** Endless audio made on the fly, such as the practice mode's drone and drums. */
interface LiveSource {
    /** On the audio thread before the first [render], with the rate the output opened at. */
    fun prepare(sampleRate: Int)

    /** Fills the first [frames] samples of [left] and [right]; runs on the audio thread. */
    fun render(left: FloatArray, right: FloatArray, frames: Int)
}

/** A second output next to [AudioPlayer] that streams a [LiveSource] until stopped. */
interface LiveOutput {
    /** [onFail] runs on the audio thread when no output can be opened. */
    fun start(source: LiveSource, onFail: () -> Unit = {})

    /** Fades out and closes. */
    fun stop()

    val isRunning: Boolean
}

/** Everything the app needs from the platform besides files. */
class AudioSystem(
    val player: AudioPlayer,
    val recorder: AudioRecorder,
    val decoder: AudioDecoder,
    val live: LiveOutput = NoLiveOutput,
    /** Sound files shipped with the app, e.g. "drums/kick.wav"; null if missing. */
    val bundled: (String) -> ByteArray? = { null },
)

object NoLiveOutput : LiveOutput {
    override fun start(source: LiveSource, onFail: () -> Unit) = Unit
    override fun stop() = Unit
    override val isRunning = false
}

/** For previews and tests: no sound at all. */
object SilentAudio {
    val system = AudioSystem(
        player = object : AudioPlayer {
            override fun play(pcm: Pcm, fromSeconds: Double, onEnd: () -> Unit) = Unit
            override fun stop() = Unit
            override val isPlaying = false
            override fun position() = 0.0
        },
        recorder = object : AudioRecorder {
            override fun start() = false
            override fun level() = 0f
            override fun stop(): Pcm? = null
            override val isRecording = false
        },
        decoder = { _, _ -> null },
    )
}

val AUDIO_EXTENSIONS = setOf("wav", "wave", "mp3", "m4a", "aac", "flac", "ogg", "oga", "opus", "aif", "aiff", "caf")
val MIDI_EXTENSIONS = setOf("mid", "midi")

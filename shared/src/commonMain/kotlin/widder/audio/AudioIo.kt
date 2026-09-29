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

/** Everything the app needs from the platform besides files. */
class AudioSystem(
    val player: AudioPlayer,
    val recorder: AudioRecorder,
    val decoder: AudioDecoder,
)

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

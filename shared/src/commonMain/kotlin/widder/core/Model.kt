package widder.core

import kotlinx.serialization.Serializable

/** The two paths from the start screen. */
@Serializable
enum class DeckKind { CARDS, CHORDS }

@Serializable
data class Deck(
    val id: String,
    val name: String,
    val kind: DeckKind = DeckKind.CARDS,
    val newPerDay: Int = 20,
    val reviewsPerDay: Int = 200,
    /** Chord training only: extra wrong tiles mixed into the pool. */
    val distractors: Int = 0,
    /** Chord training only: where the wrong tiles come from; empty = chords close to the answer. */
    val pool: List<String> = emptyList(),
    /** Key for the roman numerals, e.g. "A" or "fis-Moll"; null = recognised from each card's chords. */
    val key: String? = null,
    /** How this deck labels its chords; null = as in the settings. */
    val numerals: NumeralMode? = null,
    /** New cards and reviews in a new mixed order every session, not in the order they were made. */
    val shuffle: Boolean = true,
    /** Only cards with one of these tags are learned, e.g. "Basis"; empty = all cards. */
    val studyTags: List<String> = emptyList(),
    /** A folder this deck mirrors (see FolderAccess); every audio/MIDI file in it is a card. */
    val folder: String? = null,
    val created: Long = 0,
) {
    /** The cards of this deck from [allCards] that match [studyTags] and can be studied. */
    fun studyCards(allCards: List<Card>): List<Card> = allCards.filter { card ->
        card.deckId == id &&
            (studyTags.isEmpty() || card.tags.any { it in studyTags }) &&
            (kind != DeckKind.CHORDS || (card.answer.isNotEmpty() && card.frontAudio != null))
    }

    /** Computes due counts (new, learning, review) for this deck against [allCards] and [progress]. */
    fun counts(allCards: List<Card>, progress: Progress): DeckCounts =
        Queue(this, studyCards(allCards), progress).counts
}

/**
 * Non-destructive edit of an audio clip. The original file is never touched; every playback
 * renders it through these settings, so they can be changed again at any time - also in the
 * middle of a review when something sounds off.
 */
@Serializable
data class AudioEdit(
    /** Seconds from the start of the source. */
    val trimStart: Double = 0.0,
    /** Seconds from the start of the source; null = up to the end. */
    val trimEnd: Double? = null,
    val gainDb: Double = 0.0,
    /** Brings the loudest peak of the selection to -1 dBFS before [gainDb] is applied. */
    val normalize: Boolean = false,
    val fadeIn: Double = 0.0,
    val fadeOut: Double = 0.0,
    /** High-pass cutoff in Hz; 0 = off. */
    val lowCut: Double = 0.0,
    /** Low-pass cutoff in Hz; 0 = off. */
    val highCut: Double = 0.0,
    /** Share of reverberated signal, 0..1. */
    val reverbMix: Double = 0.0,
    val reverbSize: Double = 0.6,
    val reverbDamping: Double = 0.4,
) {
    val isNeutral: Boolean get() = this == AudioEdit()
}

/** An audio or MIDI file in the collection's media folder, with its edit. */
@Serializable
data class Clip(
    /** File name inside the media folder. */
    val file: String,
    /** The name the file had when it was imported or recorded. */
    val name: String,
    val edit: AudioEdit = AudioEdit(),
) {
    val isMidi: Boolean get() = file.endsWith(".mid", true) || file.endsWith(".midi", true)
}

@Serializable
data class Card(
    val id: String,
    val deckId: String,
    val front: String = "",
    val back: String = "",
    val frontAudio: Clip? = null,
    val backAudio: Clip? = null,
    /**
     * The chords of the card, as written (e.g. "Fmin", "Gmin"). In chord training they are put in
     * order; on a flash card they are shown on the back with their roman numerals.
     */
    val answer: List<String> = emptyList(),
    /** Key for the roman numerals; null = the deck's key, or recognised from the chords. */
    val key: String? = null,
    /**
     * Free text shown instead of the chords and numerals, for what has no key or no chords
     * ("Son Clave 3-2"). The chords still count in the training.
     */
    val label: String? = null,
    /** Notes on the back of a flash card and in the editor. */
    val notes: String = "",
    /** What the audio really plays, e.g. "Amin/C: 1. Umkehrung"; only the editor shows it, never the learning. */
    val voicing: String = "",
    /** The file in the deck's folder this card comes from; the card follows that file. */
    val source: String? = null,
    /** The file's stamp when it was last read in; a different one means it was replaced. */
    val sourceStamp: Long = 0,
    val tags: List<String> = emptyList(),
    val created: Long = 0,
    val modified: Long = 0,
)

@Serializable
data class RewardSettings(
    val enabled: Boolean = true,
    /** File name inside the media folder, or null for no image. */
    val image: String? = null,
    val columns: Int = 3,
    val rows: Int = 2,
    val secondsPerTile: Int = 20 * 60,
    /** 1 (light) .. 4 (very strong). */
    val blur: Int = 3,
    val resetDaily: Boolean = true,
)

@Serializable
data class Settings(
    /** German note names: H is B natural and B is B flat. */
    val germanNotation: Boolean = false,
    /** Minor chords written as "Fmin" instead of "Fm" when the app names chords itself. */
    val minorAsMin: Boolean = false,
    val autoplay: Boolean = true,
    /** How chords are labelled: name only, roman numeral with the name "I (A)", or numeral only. */
    val numerals: NumeralMode = NumeralMode.WITH_NAME,
    /** Which version of the standard kits this collection has received (see StarterKits.STANDARD_VERSION). */
    val standardKits: Int = 0,
    val reward: RewardSettings = RewardSettings(),
    /** The fields of the gesture grid, row by row; null = the standard ones. May hold more than the grid shows. */
    val tiles: List<GestureTile>? = null,
    val tileColumns: Int = 2,
    val tileRows: Int = 2,
    val tileScope: TileScope = TileScope.HOME,
    /** The grid while learning and in the editor opened from there; null = the standard ones. */
    val learnTiles: List<GestureTile>? = null,
    val learnTileRows: Int = 3,
    val learnTilesOn: Boolean = true,
    /** Outlines and names of the fields drawn faintly over the screen. */
    val showTiles: Boolean = false,
    /** Desktop: the prefix before a shortcut letter; null = shortcuts off. */
    val prefix: KeyStroke? = Shortcuts.DEFAULT_PREFIX,
    /** Desktop: prefix + letter; null = the standard ones. */
    val shortcuts: List<Shortcut>? = null,
    /** What the practice mode played last time. */
    val practice: PracticeSettings = PracticeSettings(),
    /** Recording device by name (Mac and Linux); null = the system's default. */
    val audioInput: String? = null,
    val audioOutput: String? = null,
    /** 0 = all inputs of the device mixed, else input 1, 2 ... (an interface's guitar input). */
    val inputChannel: Int = 0,
)

@Serializable
enum class DroneSound(val label: String) { SINE("Sinus"), ORGAN("Orgel"), PAD("Fläche"), TANPURA("Tanpura") }

@Serializable
enum class DrumKit(val label: String) {
    /** The sampled kit from NicheLooper ("The Black Pearl"). */
    ACOUSTIC("Akustik"),
    /** NicheLooper's synthesized fallback kit. */
    SYNTH("Synth"),
    ELECTRO("808"),
    BRUSHES("Besen"),
    CAJON("Cajón"),
    /** No drums, only the metronome. */
    CLICK("Nur Klick"),
}

@Serializable
enum class Meter(val label: String, val beats: Int) { FOUR_FOUR("4/4", 4), THREE_FOUR("3/4", 3), TWO_FOUR("2/4", 2), SIX_EIGHT("6/8", 6) }

/** Practice mode: a drone to play against and a drum machine. */
@Serializable
data class PracticeSettings(
    /** Pitch class of the drone, 0 = C. */
    val root: Int = 9,
    /** Octave of the root in scientific pitch, A2 = 110 Hz. */
    val octave: Int = 2,
    val sound: DroneSound = DroneSound.ORGAN,
    val fifth: Boolean = true,
    val octaveUp: Boolean = false,
    /** Concert pitch of A4 in Hz. */
    val tuning: Double = 440.0,
    /** The concert pitches offered as chips; the user adds and removes their own. */
    val tuningPresets: List<Double> = listOf(432.0, 440.0, 442.0, 443.0),
    /** The drone a microtone off its note, in cents (steps of [division]). */
    val droneCents: Int = 0,
    val droneVolume: Float = 0.6f,
    val kit: DrumKit = DrumKit.ACOUSTIC,
    /** Clicks per minute: quarters, eighths in 6/8. */
    val bpm: Int = 90,
    val meter: Meter = Meter.FOUR_FOUR,
    /** Name of the groove (see Grooves); one of another meter falls back to the meter's first. */
    val groove: String = "Rock",
    val click: Boolean = false,
    val drumVolume: Float = 0.7f,
    val vibrato: VibratoSettings = VibratoSettings(),
    /** Drums, drone and loop stop after this many minutes of playing; 0 = never. */
    val stopAfter: Int = 0,
    val loopVolume: Float = 0.9f,
    /** Output plus input delay in ms: how late a recorded note lands after the click it was played to. */
    val loopLatency: Int = 150,
    /** Steps per octave for microtones: 12 = semitones, 24 = quarter tones ... (tuner, line grid, drone). */
    val division: Int = 12,
    val tuner: TunerSettings = TunerSettings(),
    /** How many seconds the pitch picture shows. */
    val monitorSeconds: Int = 10,
) {
    companion object {
        const val MIN_BPM = 40
        const val MAX_BPM = 240
    }
}

@Serializable
enum class VibratoDirection(val label: String) { UP("Nach oben"), AROUND("Um den Ton"), DOWN("Nach unten") }

/** The tuner: chromatic or to a tuning's strings, and the user's own tunings. */
@Serializable
data class TunerSettings(
    /** Name of the tuning (see widder.music.Tuner.presets and [custom]); null = chromatic. */
    val tuning: String? = null,
    val custom: List<widder.music.StringTuning> = emptyList(),
    /** How far off is shown in semitones instead of cents. */
    val semitones: Boolean = false,
)

/** How the line gets to a point from the one before. */
@Serializable
enum class Bend(val label: String) { SMOOTH("Weich"), LINEAR("Gerade"), STEEP("Steil") }

/** A corner of the vibrato trainer's line: [at] 0..1 over the tone, [cents] from the note. */
@Serializable
data class LinePoint(val at: Float, val cents: Float, val bend: Bend = Bend.SMOOTH)

@Serializable
enum class VibratoSound(val label: String) { STRINGS("Streicher"), VOICE("Stimme"), ORGAN("Orgel"), FLUTE("Flöte"), SINE("Sinus") }

/**
 * The vibrato trainer's model tone: which note, how it sounds, the line it follows (straight, rising,
 * falling, drawn by hand ...) and the vibrato that swings on top of the line.
 */
@Serializable
data class VibratoSettings(
    /** Pitch class, 0 = C. */
    val root: Int = 9,
    /** Octave in scientific pitch, A3 = 220 Hz. */
    val octave: Int = 3,
    val sound: VibratoSound = VibratoSound.STRINGS,
    /** Swings per second. */
    val rate: Float = 5.5f,
    /** From the lowest to the highest point, in cents; 0 = no vibrato, only the line. */
    val width: Int = 50,
    val direction: VibratoDirection = VibratoDirection.AROUND,
    val seconds: Int = 4,
    /** Seconds before the vibrato sets in (it then swells in over [FADE_IN] seconds). */
    val vibratoFrom: Float = 0f,
    /** The line's corner points, in time order; empty = straight on the note. */
    val points: List<LinePoint> = emptyList(),
    /** The graph's side shows frequencies instead of note names. */
    val axisHz: Boolean = false,
) {
    /** MIDI pitch of the note. */
    val pitch: Int get() = 12 * (octave + 1) + root

    /** Where the tone is [t] seconds in, in cents from the note: line plus vibrato. */
    fun cents(t: Double): Double = lineAt(t) + swingAt(t)

    /** The line alone, [t] seconds in: through the points, flat before the first and after the last. */
    fun lineAt(t: Double): Double {
        if (points.isEmpty()) return 0.0
        val u = t / seconds
        val next = points.indexOfFirst { it.at >= u }
        if (next == 0) return points[0].cents.toDouble()
        if (next < 0) return points.last().cents.toDouble()
        val a = points[next - 1]
        val b = points[next]
        val span = (b.at - a.at).toDouble()
        val x = if (span <= 0) 1.0 else (u - a.at) / span
        val f = when (b.bend) {
            Bend.LINEAR -> x
            Bend.SMOOTH -> (1 - kotlin.math.cos(kotlin.math.PI * x)) / 2
            // Holds, then shoots up (or down) in the last moment before the point.
            Bend.STEEP -> {
                val rise = (STEEP_SECONDS / seconds / span).coerceAtMost(1.0)
                ((x - (1 - rise)) / rise).coerceIn(0.0, 1.0)
            }
        }
        return a.cents + (b.cents - a.cents) * f
    }

    /** The vibrato alone, [t] seconds in. */
    fun swingAt(t: Double): Double {
        val v = t - vibratoFrom
        if (width == 0 || v < 0) return 0.0
        val fade = if (vibratoFrom > 0f) (v / FADE_IN).coerceAtMost(1.0) else 1.0
        val swing = 2 * kotlin.math.PI * rate * v
        return fade * when (direction) {
            VibratoDirection.AROUND -> width / 2.0 * kotlin.math.sin(swing)
            VibratoDirection.UP -> width / 2.0 * (1 - kotlin.math.cos(swing))
            VibratoDirection.DOWN -> -width / 2.0 * (1 - kotlin.math.cos(swing))
        }
    }

    /** The middle of the swing, in cents from the line. */
    val center: Double get() = when (direction) {
        VibratoDirection.AROUND -> 0.0
        VibratoDirection.UP -> width / 2.0
        VibratoDirection.DOWN -> -width / 2.0
    }

    companion object {
        const val MIN_RATE = 2f
        const val MAX_RATE = 9f
        const val MAX_WIDTH = 200
        /** How far the line may go from the note, in cents. */
        const val LINE_RANGE = 500
        const val FADE_IN = 0.4
        /** A steep step takes this long. */
        const val STEEP_SECONDS = 0.06
    }
}

@Serializable
enum class NumeralMode { OFF, WITH_NAME, ONLY }

@Serializable
data class Collection(
    val decks: List<Deck> = emptyList(),
    val cards: List<Card> = emptyList(),
    val settings: Settings = Settings(),
)

@Serializable
data class DayStats(
    val reviews: Int = 0,
    val correct: Int = 0,
    val xp: Int = 0,
    val studySeconds: Int = 0,
)

@Serializable
data class RewardProgress(
    /** Learning day (yyyy-mm-dd) the seconds belong to. */
    val day: String = "",
    val seconds: Int = 0,
    /** The order in which tiles are revealed, shuffled once per image and grid. */
    val order: List<Int> = emptyList(),
)

@Serializable
data class Progress(
    val cards: Map<String, ReviewState> = emptyMap(),
    /** "day|deckId" -> number of new cards started / reviews done that day. */
    val newIntroduced: Map<String, Int> = emptyMap(),
    val reviewsDone: Map<String, Int> = emptyMap(),
    /** Learning day -> what happened that day; feeds the streak and XP. */
    val days: Map<String, DayStats> = emptyMap(),
    val totalXp: Int = 0,
    val reward: RewardProgress = RewardProgress(),
)

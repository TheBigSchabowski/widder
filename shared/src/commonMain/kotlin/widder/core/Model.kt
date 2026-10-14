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
)

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
    val tuning: Int = 440,
    val droneVolume: Float = 0.6f,
    val kit: DrumKit = DrumKit.ACOUSTIC,
    /** Clicks per minute: quarters, eighths in 6/8. */
    val bpm: Int = 90,
    val meter: Meter = Meter.FOUR_FOUR,
    /** Name of the groove (see Grooves); one of another meter falls back to the meter's first. */
    val groove: String = "Rock",
    val click: Boolean = false,
    val drumVolume: Float = 0.7f,
) {
    companion object {
        const val MIN_BPM = 40
        const val MAX_BPM = 240
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

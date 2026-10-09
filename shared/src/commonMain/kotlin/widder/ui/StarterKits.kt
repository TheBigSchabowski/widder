package widder.ui

import widder.core.Card
import widder.core.Clip
import widder.core.Deck
import widder.core.DeckKind
import widder.core.NumeralMode
import widder.core.Store
import widder.core.currentTimeMillis
import widder.music.Chord
import widder.music.Harmony
import widder.music.Interval
import widder.music.Key
import widder.music.Midi
import widder.music.MidiAnalysis
import widder.music.Notation
import widder.music.Quality
import kotlin.random.Random

/** Ready-made chord trainings, rendered by the built-in synth. */
object StarterKits {
    const val A_CHORDS_ID = "startkit-a"
    const val A_CARDS_ID = "startkit-a-karten"
    const val INTERVALS_TOGETHER_ID = "startkit-intervalle-gleichzeitig"
    const val INTERVALS_APART_ID = "startkit-intervalle-nacheinander"
    const val CHORD_TYPES_ID = "startkit-akkordtypen"
    const val DEGREES_ID = "startkit-stufen-c"
    const val PROGRESSIONS_ID = "startkit-akkordfolgen"

    /** The levels the standard kits tag their cards with, from easy to hard. */
    const val BASIC = "Basis"
    const val MEDIUM = "Mittel"
    const val ADVANCED = "Fortgeschritten"
    val LEVELS = listOf(BASIC, MEDIUM, ADVANCED)

    /** Where the A kits' files go on the Mac, next to the collection. */
    const val FOLDER = "Startkit A-Akkorde"

    /** A kit as offered on the deck list: [folder] is where its files go on the computer. */
    class Kit(val id: String, val kind: DeckKind, val title: String, val summary: String, val folder: String, val build: (Store) -> Pair<Deck, List<Card>>)

    /**
     * The chords of one key or mode, heard after its tonic and in typical progressions. The first
     * chord is the tonic; [hint] says what makes the mode sound like itself.
     */
    class Scale(val id: String, val key: String, val chords: List<String>, val progressions: List<List<String>>, val hint: String) {
        val title: String get() = "Startkit: " + (if (Key.parse(key)?.mode == null) "Stufen in " else "") + keyName
        val keyName: String get() = Key.parse(key)!!.name(Notation(german = true))
        val numerals: List<String> get() = chords.map { Harmony.numeral(Chord.parse(it)!!, Key.parse(key)!!) }
    }

    // H rather than B, so German note names read it the same.
    val SCALES = listOf(
        Scale(
            DEGREES_ID, "C", listOf("C", "Dm", "Em", "F", "G", "Am", "Hdim"),
            listOf(listOf("C", "F", "G", "C"), listOf("C", "G", "Am", "F"), listOf("C", "Am", "F", "G"), listOf("Dm", "G", "C"), listOf("Am", "F", "C", "G"), listOf("C", "Em", "F", "G")),
            "Dur",
        ),
        // Natural minor plus the major dominant of harmonic minor: E next to Em.
        Scale(
            "startkit-stufen-a-moll", "a-Moll", listOf("Am", "Hdim", "C", "Dm", "Em", "E", "F", "G"),
            listOf(listOf("Am", "Dm", "E", "Am"), listOf("Am", "F", "C", "G"), listOf("Am", "G", "F", "E"), listOf("Am", "Dm", "G", "C"), listOf("Hdim", "E", "Am"), listOf("Am", "F", "G", "Am")),
            "mit E-Dur als Dominante",
        ),
        Scale(
            "startkit-d-dorisch", "D-Dorisch", listOf("Dm", "Em", "F", "G", "Am", "Hdim", "C"),
            listOf(listOf("Dm", "G", "Dm"), listOf("Dm", "C", "G", "Dm"), listOf("Dm", "F", "G", "Dm"), listOf("Dm", "Em", "F", "G"), listOf("Dm", "Am", "G", "Dm")),
            "Moll mit Dur-IV",
        ),
        Scale(
            "startkit-e-phrygisch", "E-Phrygisch", listOf("Em", "F", "G", "Am", "Hdim", "C", "Dm"),
            listOf(listOf("Em", "F", "Em"), listOf("Em", "F", "G", "F"), listOf("Am", "G", "F", "Em"), listOf("Em", "Dm", "F", "Em"), listOf("Em", "F", "Am", "Em")),
            "Moll mit bII",
        ),
        Scale(
            "startkit-f-lydisch", "F-Lydisch", listOf("F", "G", "Am", "Hdim", "C", "Dm", "Em"),
            listOf(listOf("F", "G", "F"), listOf("F", "G", "C", "F"), listOf("F", "Em", "G", "F"), listOf("Dm", "G", "F"), listOf("F", "G", "Am", "F")),
            "Dur mit Dur-II",
        ),
        Scale(
            "startkit-g-mixolydisch", "G-Mixolydisch", listOf("G", "Am", "Hdim", "C", "Dm", "Em", "F"),
            listOf(listOf("G", "F", "G"), listOf("G", "F", "C", "G"), listOf("G", "Dm", "C", "G"), listOf("F", "C", "G"), listOf("G", "Am", "F", "G")),
            "Dur mit bVII",
        ),
    )

    val ALL: List<Kit> by lazy {
        val chordKits = listOf(
            Kit(A_CHORDS_ID, DeckKind.CHORDS, "Startkit: A-Akkorde", A_CHORDS.joinToString(" · "), FOLDER, ::aChords),
            Kit(A_CARDS_ID, DeckKind.CARDS, "Startkit: A-Akkorde als Karteikarten", A_CHORDS.joinToString(" · "), FOLDER, ::aChordCards),
            Kit(
                INTERVALS_TOGETHER_ID, DeckKind.CHORDS, "Startkit: Intervalle gleichzeitig",
                "Beide Töne zusammen, von der kleinen Sekunde bis zur Quintdezime", "Startkit Intervalle gleichzeitig",
            ) { intervals(it, together = true) },
            Kit(
                INTERVALS_APART_ID, DeckKind.CHORDS, "Startkit: Intervalle nacheinander",
                "Erst der tiefe, dann der hohe Ton, von der kleinen Sekunde bis zur Quintdezime", "Startkit Intervalle nacheinander",
            ) { intervals(it, together = false) },
            Kit(
                CHORD_TYPES_ID, DeckKind.CHORDS, "Startkit: Akkordtypen",
                "Alle ${CHORD_TYPES.size} Akkordtypen auf C, von Dur, Moll, Oktave und Powerchord bis dim7 und C13 – " +
                    "in allen Umkehrungen, getaggt mit ${LEVELS.joinToString(" · ")}",
                "Startkit Akkordtypen", ::chordTypes,
            ),
            Kit(
                PROGRESSIONS_ID, DeckKind.CHORDS, "Startkit: Akkordfolgen",
                "Die häufigsten Schritte von einem Akkord zum nächsten in C-Dur und a-Moll – Tonika, Subdominante, " +
                    "Dominante und ihre Verwandten, nach der Tonika als Bezug, getaggt mit ${LEVELS.joinToString(" · ")}",
                "Startkit Akkordfolgen", ::progressions,
            ),
        ) + SCALES.map { scale ->
            Kit(
                scale.id, DeckKind.CHORDS, scale.title,
                scale.numerals.joinToString(" ") + " (${scale.hint}) – einzeln nach der Tonika und in typischen Folgen, auch in Umkehrungen",
                scale.title.replace("Startkit: ", "Startkit "),
            ) { scale(it, scale) }
        }
        // Every training also as flash cards; the A chords have theirs already.
        chordKits + chordKits.filter { it.kind == DeckKind.CHORDS && it.id != A_CHORDS_ID }.map { kit ->
            val question = when (kit.id) {
                INTERVALS_TOGETHER_ID, INTERVALS_APART_ID -> "Welches Intervall ist das?"
                CHORD_TYPES_ID -> "Welcher Akkordtyp ist das?"
                PROGRESSIONS_ID -> "Welche zwei Stufen folgen auf die Tonika?"
                else -> "Welche Stufen hörst du?"
            }
            Kit(kit.id + CARDS_SUFFIX, DeckKind.CARDS, kit.title + " als Karteikarten", kit.summary, kit.folder) { asCards(it, kit, question) }
        }
    }

    /**
     * What every collection gets without asking: all chord types and the common progressions,
     * each as training and as flash cards. Raising [STANDARD_VERSION] hands a new version of them
     * to existing collections once (see AppViewModel.installStandardKits).
     */
    const val STANDARD_VERSION = 1
    val STANDARD = listOf(CHORD_TYPES_ID, CHORD_TYPES_ID + CARDS_SUFFIX, PROGRESSIONS_ID, PROGRESSIONS_ID + CARDS_SUFFIX)

    /** Appended to a training's id for its flash-card twin. */
    const val CARDS_SUFFIX = "-karten"

    /**
     * The flash-card twin of a chord training: the same audio, answer and notes, with [question] on
     * the front instead of the answer. Key and numerals stay as in the training.
     */
    private fun asCards(store: Store, kit: Kit, question: String): Pair<Deck, List<Card>> {
        val (training, exercises) = kit.build(store)
        val id = kit.id + CARDS_SUFFIX
        val deck = Deck(id, training.name + " (Karten)", DeckKind.CARDS, key = training.key, numerals = training.numerals, created = training.created)
        val cards = exercises.map { card ->
            card.copy(id = id + card.id.removePrefix(kit.id), deckId = id, front = question)
        }
        return deck to cards
    }

    fun kit(id: String): Kit? = ALL.firstOrNull { it.id == id }

    /** Everything built on A: colours, the inversions of A and Am, octave and power chord. */
    val A_CHORDS = listOf("Am(add9)", "Amaj(add9)", "A7", "Amaj7", "Am/C", "Am/E", "Amaj", "A/C#", "A/E", "A-Oktave", "A5")

    /** A few orders to put right, from the same chords. */
    private val SEQUENCES = listOf(
        listOf("Amaj", "A7", "Amaj7"),
        listOf("Am/C", "Am/E", "Am(add9)"),
        listOf("A5", "A/C#", "Amaj(add9)"),
        listOf("Amaj", "A/C#", "A/E"),
    )

    /** The same chords as flash cards: hear it, think, turn over, grade yourself. */
    fun aChordCards(store: Store): Pair<Deck, List<Card>> {
        val now = currentTimeMillis()
        val deck = Deck(A_CARDS_ID, "Startkit: A-Akkorde (Karten)", DeckKind.CARDS, created = now)
        val cards = A_CHORDS.mapIndexed { i, name ->
            val chord = requireNotNull(Chord.parse(name)) { name }
            val bytes = Midi.write(MidiAnalysis.progression(listOf(chord), secondsPerChord = 1.8, rootBass = false), bpm = 100)
            Card(
                "$A_CARDS_ID-$i",
                deck.id,
                front = "Welcher Akkord ist das?",
                frontAudio = Clip(store.addMedia("$name.mid", bytes), "$name.mid"),
                answer = listOf(name),
                // Counted in A major, so Am/C reads i/3 and A/E reads I/5.
                key = "A",
                created = now + i,
                modified = now,
            )
        }
        return deck to cards
    }

    fun aChords(store: Store): Pair<Deck, List<Card>> {
        val now = currentTimeMillis()
        // Three wrong tiles from the kit itself: telling A7 from Amaj7 is the point.
        val deck = Deck(A_CHORDS_ID, "Startkit: A-Akkorde", DeckKind.CHORDS, distractors = 3, pool = A_CHORDS, key = "A", created = now)
        val exercises = A_CHORDS.map { listOf(it) } + SEQUENCES
        val cards = exercises.mapIndexed { i, answer ->
            val chords = answer.map { requireNotNull(Chord.parse(it)) { it } }
            val name = answer.joinToString(" ")
            // No extra root in the bass, so Amaj stays in root position and the inversions stand out.
            val bytes = Midi.write(MidiAnalysis.progression(chords, secondsPerChord = 1.8, rootBass = false), bpm = 100)
            Card(
                "$A_CHORDS_ID-$i",
                deck.id,
                front = name,
                frontAudio = Clip(store.addMedia("$name.mid", bytes), "$name.mid"),
                answer = answer,
                created = now + i,
                modified = now,
            )
        }
        return deck to cards
    }

    /** Every interval from the kleine Sekunde (1) to the Quintdezime (24), one card each. */
    fun intervals(store: Store, together: Boolean): Pair<Deck, List<Card>> {
        val now = currentTimeMillis()
        val id = if (together) INTERVALS_TOGETHER_ID else INTERVALS_APART_ID
        // The neighbours that sound alike (kleine/große Terz, Terz/Dezime) are the wrong tiles.
        val deck = Deck(
            id,
            if (together) "Startkit: Intervalle gleichzeitig" else "Startkit: Intervalle nacheinander",
            DeckKind.CHORDS,
            distractors = 4,
            created = now,
        )
        val cards = (1..24).map { semitones ->
            val name = Interval.name(semitones)
            // A different lower note for each, so the pitch itself gives nothing away.
            val low = (if (semitones <= 12) 55 else 48) + semitones * 5 % 12
            val bytes = Midi.write(MidiAnalysis.interval(low, semitones, together, length = 2.0), bpm = 100)
            Card(
                "$id-$semitones",
                deck.id,
                front = name,
                frontAudio = Clip(store.addMedia("$name.mid", bytes), "$name.mid"),
                answer = listOf(name),
                created = now + semitones,
                modified = now,
            )
        }
        return deck to cards
    }

    /** Every chord type on C by level: two-note sounds and the main triads and sevenths first, jazz colours last. */
    val CHORD_TYPE_LEVELS: List<Pair<String, List<String>>> = listOf(
        BASIC to listOf("Cmaj", "Cmin", "C5", "C-Oktave", "C7", "Cmaj7", "Cmin7"),
        MEDIUM to listOf("Csus2", "Csus4", "Cdim", "Caug", "C6", "Cmin6", "Cmaj(add9)", "Cmin(add9)", "Cadd4", "Cmin(add4)", "C7sus4"),
        ADVANCED to listOf(
            "Cdim7", "Cmin7b5", "CminMaj7", "C7#5", "C9", "Cmaj9", "Cmin9", "C6add9", "C7b9", "C7#9", "C11", "Cmin11", "C13", "Cmaj13",
        ),
    )

    val CHORD_TYPES: List<String> = CHORD_TYPE_LEVELS.flatMap { it.second }

    /** "F/A: 1. Umkehrung (Terz im Bass)" for what the audio plays; the answer stays "F". */
    private fun describe(played: Chord, written: String): String {
        val position = played.bass?.let { bass ->
            val n = played.copy(bass = null).inversions().indexOfFirst { it.bass == bass }
            val tone = when (((bass - played.root) % 12 + 12) % 12) {
                2 -> "Sekunde"
                3, 4 -> "Terz"
                5 -> "Quarte"
                6, 7, 8 -> "Quinte"
                9 -> if (played.quality == Quality.DIM7) "Septime" else "Sexte"
                else -> "Septime"
            }
            "$n. Umkehrung ($tone im Bass)"
        } ?: "Grundstellung"
        val name = if (played.bass == null) written else written + "/" + Chord.noteName(played.bass, Notation(german = true))
        return "$name: $position"
    }

    fun chordTypes(store: Store): Pair<Deck, List<Card>> {
        val now = currentTimeMillis()
        // The wrong tiles come from the types themselves; numerals would only repeat the names.
        val deck = Deck(
            CHORD_TYPES_ID, "Startkit: Akkordtypen", DeckKind.CHORDS,
            distractors = 5, pool = CHORD_TYPES, numerals = NumeralMode.OFF, created = now,
        )
        // Every type in root position and each inversion; which one it is stays in the notes.
        val voicings = CHORD_TYPE_LEVELS.flatMap { (level, names) ->
            names.flatMap { name ->
                val chord = requireNotNull(Chord.parse(name)) { name }
                chord.inversions().mapIndexed { n, played -> Voicing(name, chord.quality, n, played, level) }
            }
        }
        val cards = voicings.mapIndexed { i, v ->
            // Root C plays without an extra bass note, so C5 and the octave stay two-note sounds.
            val bytes = Midi.write(MidiAnalysis.progression(listOf(v.played), secondsPerChord = 1.8, rootBass = v.played.quality.intervals.size > 2), bpm = 100)
            Card(
                // Named, not counted, so a later version can add types without moving the others.
                "$CHORD_TYPES_ID-${v.quality.name.lowercase()}-${v.inversion}",
                deck.id,
                front = v.name,
                frontAudio = Clip(store.addMedia("${v.name}.mid", bytes), "${v.name}.mid"),
                answer = listOf(v.name),
                notes = describe(v.played, v.name),
                tags = listOf(v.level),
                created = now + i,
                modified = now,
            )
        }
        return deck to cards
    }

    private class Voicing(val name: String, val quality: Quality, val inversion: Int, val played: Chord, val level: String)

    /** A step from one chord to the next in [key], with the functions it moves between. */
    class Step(val key: String, val chords: List<String>, val functions: String, val level: String)

    // H rather than B, and no B flat at all: "Bb" would read as A in German notation.
    val STEPS = listOf(
        Step("C", listOf("C", "F"), "Tonika → Subdominante", BASIC),
        Step("C", listOf("C", "G"), "Tonika → Dominante", BASIC),
        Step("C", listOf("F", "C"), "Subdominante → Tonika (plagaler Schluss)", BASIC),
        Step("C", listOf("G", "C"), "Dominante → Tonika (authentischer Schluss)", BASIC),
        Step("C", listOf("F", "G"), "Subdominante → Dominante", BASIC),
        Step("C", listOf("G", "F"), "Dominante → Subdominante", MEDIUM),
        Step("C", listOf("G7", "C"), "Dominantseptakkord → Tonika", MEDIUM),
        Step("C", listOf("C", "Am"), "Tonika → Tonikaparallele", MEDIUM),
        Step("C", listOf("Am", "F"), "Tonikaparallele → Subdominante", MEDIUM),
        Step("C", listOf("Dm", "G"), "Subdominantparallele → Dominante (II – V)", MEDIUM),
        Step("C", listOf("G", "Am"), "Dominante → Tonikaparallele (Trugschluss)", MEDIUM),
        Step("C", listOf("C", "Em"), "Tonika → Dominantparallele", ADVANCED),
        Step("C", listOf("F", "Fm"), "Subdominante → Moll-Subdominante", ADVANCED),
        Step("C", listOf("Fm", "C"), "Moll-Subdominante → Tonika", ADVANCED),
        Step("C", listOf("D", "G"), "Doppeldominante → Dominante", ADVANCED),
        Step("C", listOf("E", "Am"), "Zwischendominante → Tonikaparallele", ADVANCED),
        Step("a-Moll", listOf("Am", "Dm"), "Moll-Tonika → Moll-Subdominante", BASIC),
        Step("a-Moll", listOf("Am", "E"), "Moll-Tonika → Dur-Dominante", BASIC),
        Step("a-Moll", listOf("E", "Am"), "Dur-Dominante → Moll-Tonika", BASIC),
        Step("a-Moll", listOf("Dm", "Am"), "Moll-Subdominante → Moll-Tonika", MEDIUM),
        Step("a-Moll", listOf("Am", "C"), "Moll-Tonika → Tonikaparallele (III)", MEDIUM),
        Step("a-Moll", listOf("Am", "F"), "Moll-Tonika → Tonikagegenklang (VI)", MEDIUM),
        Step("a-Moll", listOf("Am", "G"), "Moll-Tonika → VII. Stufe", MEDIUM),
        Step("a-Moll", listOf("Hdim", "E"), "Verminderte II. Stufe → Dominante", ADVANCED),
        Step("a-Moll", listOf("F", "E"), "VI. Stufe → Dominante", ADVANCED),
        Step("a-Moll", listOf("Dm", "E"), "Moll-Subdominante → Dur-Dominante", ADVANCED),
    )

    /**
     * Two chords after the tonic of their key: without it, V – I in C sounds just like I – IV in
     * G. The tonic is only the reference; the answer is the two chords.
     */
    fun progressions(store: Store): Pair<Deck, List<Card>> {
        val now = currentTimeMillis()
        // Written as I, IV ... only; each card brings its own key.
        val deck = Deck(
            PROGRESSIONS_ID, "Startkit: Akkordfolgen", DeckKind.CHORDS,
            distractors = 3, pool = STEPS.flatMap { it.chords }.distinct(), numerals = NumeralMode.ONLY, created = now,
        )
        val cards = STEPS.mapIndexed { i, step ->
            val key = Key.parse(step.key)!!
            val chords = step.chords.map { requireNotNull(Chord.parse(it)) { it } }
            val tonic = Chord(key.tonic, if (key.minor) Quality.MINOR else Quality.MAJOR)
            val reference = MidiAnalysis.voice(tonic, 0.0, 1.2, velocity = 70)
            val step2 = MidiAnalysis.progression(chords, secondsPerChord = 1.5).map { it.copy(start = it.start + 2.0, end = it.end + 2.0) }
            val bytes = Midi.write(reference + step2, bpm = 100)
            val name = step.chords.joinToString(" ")
            val keyName = key.name(Notation(german = true))
            Card(
                "$PROGRESSIONS_ID-$i",
                deck.id,
                front = chords.joinToString(" – ") { Harmony.numeral(it, key) },
                frontAudio = Clip(store.addMedia("$name.mid", bytes), "$name.mid"),
                answer = step.chords,
                key = step.key,
                notes = "${step.functions}\nVorweg klingt die Tonika von $keyName als Bezug.",
                tags = listOf(step.level),
                created = now + i,
                modified = now,
            )
        }
        return deck to cards
    }

    /** The triads of C major, I to vii°. */
    val DEGREES: List<String> get() = SCALES.first().chords

    /**
     * Each chord after the tonic (a chord alone has no degree), then the progressions. After the
     * opening chord each one may sound inverted, the same way every time the kit is made.
     */
    fun scale(store: Store, scale: Scale): Pair<Deck, List<Card>> {
        val now = currentTimeMillis()
        val key = Key.parse(scale.key)!!
        // Written as I, IV ... only, whatever the settings say.
        val deck = Deck(
            scale.id, scale.title, DeckKind.CHORDS,
            distractors = 3, pool = scale.chords, key = scale.key, numerals = NumeralMode.ONLY, created = now,
        )
        val exercises = scale.chords.drop(1).map { listOf(scale.chords[0], it) } + scale.progressions
        val random = Random(scale.id.hashCode())
        val cards = exercises.mapIndexed { i, answer ->
            val chords = answer.map { requireNotNull(Chord.parse(it)) { it } }
            val played = chords.mapIndexed { n, c -> if (n == 0) c else c.inversions().let { it[random.nextInt(minOf(it.size, 3))] } }
            val name = answer.joinToString(" ")
            val bytes = Midi.write(MidiAnalysis.progression(played, secondsPerChord = 1.5), bpm = 100)
            Card(
                "${scale.id}-$i",
                deck.id,
                front = chords.joinToString(" – ") { Harmony.numeral(it, key) },
                frontAudio = Clip(store.addMedia("$name.mid", bytes), "$name.mid"),
                answer = answer,
                notes = played.zip(answer) { p, a -> describe(p, a) }.joinToString("\n"),
                created = now + i,
                modified = now,
            )
        }
        return deck to cards
    }
}

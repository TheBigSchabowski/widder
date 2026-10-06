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
import kotlin.random.Random

/** Ready-made chord trainings, rendered by the built-in synth. */
object StarterKits {
    const val A_CHORDS_ID = "startkit-a"
    const val A_CARDS_ID = "startkit-a-karten"
    const val INTERVALS_TOGETHER_ID = "startkit-intervalle-gleichzeitig"
    const val INTERVALS_APART_ID = "startkit-intervalle-nacheinander"
    const val CHORD_TYPES_ID = "startkit-akkordtypen"
    const val DEGREES_ID = "startkit-stufen-c"

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
                CHORD_TYPES.joinToString(" · ") + " – in allen Umkehrungen", "Startkit Akkordtypen", ::chordTypes,
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
                else -> "Welche Stufen hörst du?"
            }
            Kit(kit.id + CARDS_SUFFIX, DeckKind.CARDS, kit.title + " als Karteikarten", kit.summary, kit.folder) { asCards(it, kit, question) }
        }
    }

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

    /** The colours of a chord on C: major, minor, their sevenths and add9. */
    val CHORD_TYPES = listOf("Cmaj", "Cmaj7", "Cmin", "Cmin7", "Cmaj(add9)", "Cmin(add9)")

    /** "F/A: 1. Umkehrung (Terz im Bass)" for what the audio plays; the answer stays "F". */
    private fun describe(played: Chord, written: String): String {
        val position = played.bass?.let { bass ->
            when (((bass - played.root) % 12 + 12) % 12) {
                3, 4 -> "1. Umkehrung (Terz im Bass)"
                6, 7, 8 -> "2. Umkehrung (Quinte im Bass)"
                else -> "3. Umkehrung (Septime im Bass)"
            }
        } ?: "Grundstellung"
        val name = if (played.bass == null) written else written + "/" + Chord.noteName(played.bass, Notation(german = true))
        return "$name: $position"
    }

    fun chordTypes(store: Store): Pair<Deck, List<Card>> {
        val now = currentTimeMillis()
        // All six types lie in the pool every time; numerals would only repeat the names.
        val deck = Deck(
            CHORD_TYPES_ID, "Startkit: Akkordtypen", DeckKind.CHORDS,
            distractors = 5, pool = CHORD_TYPES, numerals = NumeralMode.OFF, created = now,
        )
        // Every type in root position and each inversion; which one it is stays in the notes.
        val voicings = CHORD_TYPES.flatMap { name -> requireNotNull(Chord.parse(name)) { name }.inversions().map { name to it } }
        val cards = voicings.mapIndexed { i, (name, played) ->
            val bytes = Midi.write(MidiAnalysis.progression(listOf(played), secondsPerChord = 1.8), bpm = 100)
            Card(
                "$CHORD_TYPES_ID-$i",
                deck.id,
                front = name,
                frontAudio = Clip(store.addMedia("$name.mid", bytes), "$name.mid"),
                answer = listOf(name),
                notes = describe(played, name),
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

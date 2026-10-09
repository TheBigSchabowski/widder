package widder.ui

import widder.core.Card
import widder.core.Clip
import widder.core.Deck
import widder.core.DeckKind
import widder.core.NumeralMode
import widder.core.Store
import widder.core.currentTimeMillis
import widder.music.Chord
import widder.music.Interval
import widder.music.Midi
import widder.music.MidiAnalysis
import widder.music.Notation
import widder.music.Quality
import widder.music.Spelling

/** Ready-made decks, rendered by the built-in synth. */
object StarterKits {
    const val INTERVALS_TOGETHER_ID = "startkit-intervalle-gleichzeitig"
    const val INTERVALS_APART_ID = "startkit-intervalle-nacheinander"
    const val CHORDS_ID = "startkit-akkordarten"

    /** A kit as offered on the deck list: [folder] is where its files go on the computer. */
    class Kit(val id: String, val kind: DeckKind, val title: String, val summary: String, val folder: String, val build: (Store) -> Pair<Deck, List<Card>>)

    /** The chord types on A, from the plain triads to the two-note sounds; the answer never names the inversion. */
    val CHORDS = listOf(
        "Amaj", "Amin", "Amaj7", "A7", "Amin7", "Amaj(add9)", "Amin(add9)", "Adim",
        "A5", "Amaj(no5)", "Amin(no5)", "A-Oktave",
    )

    val ALL: List<Kit> by lazy {
        val trainings = listOf(
            Kit(
                INTERVALS_TOGETHER_ID, DeckKind.CHORDS, "Startkit: Intervalle gleichzeitig",
                "Beide Töne zusammen, von der kleinen Sekunde bis zur Quintdezime", "Startkit Intervalle gleichzeitig",
            ) { intervals(it, together = true) },
            Kit(
                INTERVALS_APART_ID, DeckKind.CHORDS, "Startkit: Intervalle nacheinander",
                "Erst der tiefe, dann der hohe Ton, von der kleinen Sekunde bis zur Quintdezime", "Startkit Intervalle nacheinander",
            ) { intervals(it, together = false) },
            Kit(
                CHORDS_ID, DeckKind.CHORDS, "Astra Deck",
                CHORDS.joinToString(" · ") + " – in verschiedenen Umkehrungen; welche erklingt, zeigt nur der Editor",
                "Astra Deck", ::chords,
            ),
        )
        // Every one also as flash cards.
        trainings + trainings.map { kit ->
            val question = if (kit.id == CHORDS_ID) "Welcher Akkord ist das?" else "Welches Intervall ist das?"
            Kit(kit.id + CARDS_SUFFIX, DeckKind.CARDS, kit.title, kit.summary, kit.folder) { asCards(it, kit, question) }
        }
    }

    /**
     * What every collection gets without asking. Raising [STANDARD_VERSION] hands a new version of
     * them to existing collections once (see AppViewModel.installStandardKits).
     */
    const val STANDARD_VERSION = 2
    val STANDARD: List<String> get() = ALL.map { it.id }

    /** Appended to a training's id for its flash-card twin. */
    const val CARDS_SUFFIX = "-karten"

    /**
     * The flash-card twin of a training: the same audio, answer and voicing, with [question] on
     * the front instead of the answer.
     */
    private fun asCards(store: Store, kit: Kit, question: String): Pair<Deck, List<Card>> {
        val (training, exercises) = kit.build(store)
        val id = kit.id + CARDS_SUFFIX
        val deck = Deck(id, training.name, DeckKind.CARDS, key = training.key, numerals = training.numerals, created = training.created)
        val cards = exercises.map { card ->
            card.copy(id = id + card.id.removePrefix(kit.id), deckId = id, front = question)
        }
        return deck to cards
    }

    fun kit(id: String): Kit? = ALL.firstOrNull { it.id == id }

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

    /** "Amin/C: 1. Umkehrung (Terz im Bass)" for what the audio plays; the answer stays "Amin". */
    fun describe(played: Chord, written: String): String {
        val position = played.bass?.let { bass ->
            val n = played.copy(bass = null).inversions().indexOfFirst { it.bass == bass }
            val tone = when (((bass - played.root) % 12 + 12) % 12) {
                3, 4 -> "Terz"
                6, 7 -> "Quinte"
                else -> "Septime"
            }
            "$n. Umkehrung ($tone im Bass)"
        } ?: "Grundstellung"
        // On A: Cis and Gis, but the diminished fifth is Es.
        val spelling = if (played.bass != null && ((played.bass - played.root) % 12 + 12) % 12 == 6) Spelling.FLATS else Spelling.SHARPS
        val name = if (played.bass == null) written else written + "/" + Chord.noteName(played.bass, Notation(german = true, spelling = spelling))
        return "$name: $position"
    }

    /**
     * Each chord type in root position and every inversion, one card each. The answer is the
     * chord alone; which inversion sounds is kept in the card's voicing, seen only in the editor.
     */
    fun chords(store: Store): Pair<Deck, List<Card>> {
        val now = currentTimeMillis()
        // The wrong tiles come from the kit itself; numerals would only repeat the names.
        val deck = Deck(
            CHORDS_ID, "Astra Deck", DeckKind.CHORDS,
            distractors = 5, pool = CHORDS, numerals = NumeralMode.OFF, created = now,
        )
        val voicings = CHORDS.flatMap { name ->
            val chord = requireNotNull(Chord.parse(name)) { name }
            chord.inversions().mapIndexed { n, played -> Voicing(name, chord.quality, n, played) }
        }
        val cards = voicings.mapIndexed { i, v ->
            // Two-note sounds stay two notes: no extra root in the bass.
            val bytes = Midi.write(MidiAnalysis.progression(listOf(v.played), secondsPerChord = 1.8, rootBass = v.played.quality.intervals.size > 2), bpm = 100)
            Card(
                // Named, not counted, so a later version can add types without moving the others.
                "$CHORDS_ID-${v.quality.name.lowercase()}-${v.inversion}",
                deck.id,
                front = v.name,
                frontAudio = Clip(store.addMedia("${v.name}.mid", bytes), "${v.name}.mid"),
                answer = listOf(v.name),
                voicing = describe(v.played, v.name),
                created = now + i,
                modified = now,
            )
        }
        return deck to cards
    }

    private class Voicing(val name: String, val quality: Quality, val inversion: Int, val played: Chord)
}

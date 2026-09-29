package widder.ui

import widder.core.Card
import widder.core.Clip
import widder.core.Deck
import widder.core.DeckKind
import widder.core.Store
import widder.core.currentTimeMillis
import widder.music.Chord
import widder.music.Midi
import widder.music.MidiAnalysis

/** Ready-made chord trainings, rendered by the built-in synth. */
object StarterKits {
    const val A_CHORDS_ID = "startkit-a"

    /** Everything built on A: colours, inversions, octave and power chord. */
    val A_CHORDS = listOf("Am(add9)", "Amaj(add9)", "A7", "Amaj7", "Am/C", "Am/E", "Amaj", "A-Oktave", "A5", "A/C#", "A/A")

    /** A few orders to put right, from the same chords. */
    private val SEQUENCES = listOf(
        listOf("Amaj", "A7", "Amaj7"),
        listOf("Am/C", "Am/E", "Am(add9)"),
        listOf("A5", "A/C#", "Amaj(add9)"),
        listOf("A/A", "A-Oktave", "Amaj"),
    )

    fun aChords(store: Store): Pair<Deck, List<Card>> {
        val now = currentTimeMillis()
        // Three wrong tiles from the kit itself: telling A7 from Amaj7 is the point.
        val deck = Deck(A_CHORDS_ID, "Startkit: A-Akkorde", DeckKind.CHORDS, distractors = 3, pool = A_CHORDS, created = now)
        val exercises = A_CHORDS.map { listOf(it) } + SEQUENCES
        val cards = exercises.mapIndexed { i, answer ->
            val chords = answer.map { requireNotNull(Chord.parse(it)) { it } }
            val name = answer.joinToString(" ")
            // No extra root in the bass: that is what sets A/A apart from Amaj.
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
}

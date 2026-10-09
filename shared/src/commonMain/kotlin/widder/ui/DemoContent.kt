package widder.ui

import widder.core.Card
import widder.core.Clip
import widder.core.Collection
import widder.core.Deck
import widder.core.DeckKind
import widder.core.Store
import widder.core.currentTimeMillis
import widder.music.AnswerParser
import widder.music.Chord
import widder.music.Midi
import widder.music.MidiAnalysis

/** What a fresh collection starts with, so both paths can be tried right away. */
object DemoContent {
    private val theory = listOf(
        "Welche Töne hat C-Dur?" to "C – E – G",
        "Welche Töne hat A-Moll?" to "A – C – E",
        "Wie viele Halbtöne hat eine große Terz?" to "4 Halbtöne (z. B. C → E)",
        "Wie viele Halbtöne hat eine kleine Terz?" to "3 Halbtöne (z. B. A → C)",
        "Quintenzirkel: Welche Tonart folgt auf G-Dur?" to "D-Dur (2 ♯: Fis, Cis)",
        "Welche Akkorde sind I – IV – V in D-Dur?" to "D – G – A",
        "Was ist die Paralleltonart von F-Dur?" to "D-Moll",
        "Aus welchen Intervallen besteht ein Dominantseptakkord?" to "Große Terz, kleine Terz, kleine Terz (z. B. G – H – D – F)",
    )

    /** Named like the user's own files would be: the file name is the answer. */
    private val progressions = listOf("A D G F", "Fmin Gmin", "C F G C", "Am F C G", "Dm G C Am", "C Am Dm G7")

    fun create(store: Store): Collection {
        val now = currentTimeMillis()
        val cardsDeck = Deck("demo-karten", "Musiktheorie Grundlagen", DeckKind.CARDS, created = now)
        val chordDeck = Deck("demo-akkorde", "Erste Akkordfolgen", DeckKind.CHORDS, created = now)
        val cards = mutableListOf<Card>()
        theory.forEachIndexed { i, (front, back) ->
            cards += Card("demo-t$i", cardsDeck.id, front, back, created = now + i, modified = now)
        }
        fun midiClip(name: String, chords: List<Chord>): Clip {
            val bytes = Midi.write(MidiAnalysis.progression(chords), bpm = 100)
            return Clip(store.addMedia("$name.mid", bytes), "$name.mid")
        }
        cards += Card(
            "demo-hoeren",
            cardsDeck.id,
            front = "Dur oder Moll?",
            back = "Moll – A-Moll (Am)",
            frontAudio = midiClip("Am", listOf(Chord.parse("Am")!!)),
            created = now + 100,
            modified = now,
        )
        progressions.forEachIndexed { i, name ->
            val tokens = AnswerParser.parse("$name.mid")
            cards += Card(
                "demo-a$i",
                chordDeck.id,
                front = name,
                frontAudio = midiClip(name, tokens.mapNotNull { it.chord }),
                answer = tokens.map { it.text },
                created = now + i,
                modified = now,
            )
        }
        // The starter kits come on top (AppViewModel.installStandardKits).
        return Collection(decks = listOf(cardsDeck, chordDeck), cards = cards)
    }
}

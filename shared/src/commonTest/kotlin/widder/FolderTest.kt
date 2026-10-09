package widder

import kotlinx.coroutines.Dispatchers
import widder.audio.SilentAudio
import widder.core.Card
import widder.core.Deck
import widder.core.DeckKind
import widder.core.Grade
import widder.core.Progress
import widder.core.answer
import widder.core.FileStore
import widder.core.FolderAccess
import widder.core.FolderFile
import widder.core.IncomingFile
import widder.core.PickKind
import widder.core.Store
import widder.music.Midi
import widder.music.MidiAnalysis
import widder.music.Chord
import widder.ui.AppViewModel
import widder.ui.ClipTarget
import widder.ui.PlatformHooks
import widder.ui.StarterKits
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

private class MemoryStore : FileStore {
    val files = mutableMapOf<String, ByteArray>()
    override val location = "memory"
    override fun read(name: String) = files[name]?.decodeToString()
    override fun write(name: String, text: String) { files[name] = text.encodeToByteArray() }
    override fun readBytes(name: String) = files[name]
    override fun writeBytes(name: String, bytes: ByteArray) { files[name] = bytes }
    override fun delete(name: String) { files.remove(name) }
    override fun exists(name: String) = name in files
}

/** One folder in memory; every write bumps the stamp like a new modification time would. */
private class MemoryFolders : FolderAccess {
    val dirs = mutableMapOf<String, MutableMap<String, Pair<ByteArray, Long>>>()
    private var clock = 1L
    fun put(folder: String, name: String, bytes: ByteArray) { dirs.getOrPut(folder) { mutableMapOf() }[name] = bytes to clock++ }
    override fun list(folder: String) = dirs[folder]?.map { (n, v) -> FolderFile(n, v.second) }
    override fun read(folder: String, name: String) = dirs[folder]?.get(name)?.first
    override fun write(folder: String, name: String, bytes: ByteArray): FolderFile {
        put(folder, name, bytes)
        return FolderFile(name, dirs[folder]!![name]!!.second)
    }
    override fun defaultFolder(name: String) = "/widder/$name".also { dirs.getOrPut(it) { mutableMapOf() } }
    override fun choose(onResult: (String?) -> Unit) = onResult(null)
    override fun open(folder: String) = true
    override fun describe(folder: String) = folder
}

class FolderTest {
    private val folders = MemoryFolders()
    private val hooks = object : PlatformHooks {
        override fun pickFiles(kind: PickKind, onResult: (List<IncomingFile>) -> Unit) = Unit
        override val folders: FolderAccess get() = this@FolderTest.folders
    }
    private val chordsFolder = "/widder/Startkit Akkordarten"
    private val cardsKit = StarterKits.CHORDS_ID + StarterKits.CARDS_SUFFIX

    private fun vm(store: Store = Store(MemoryStore())) = AppViewModel(store, SilentAudio.system, hooks, live = false, main = Dispatchers.Unconfined, work = Dispatchers.Unconfined)
        .also { it.refreshFolders() }

    private fun midiOf(vararg chords: String) = Midi.write(MidiAnalysis.progression(chords.map { Chord.parse(it)!! }))

    @Test
    fun aNewCollectionHasEveryKitAsFlashCardsAndMultipleChoice() {
        val vm = vm()
        StarterKits.STANDARD.forEach { assertNotNull(vm.deck(it), it) }
        assertTrue(vm.missingKits(DeckKind.CARDS).isEmpty() && vm.missingKits(DeckKind.CHORDS).isEmpty())
        // The twin has the same audio, answers and voicings, with the question on the front.
        val chords = vm.cardsIn(StarterKits.CHORDS_ID)
        val twin = vm.cardsIn(cardsKit)
        assertEquals(chords.map { Triple(it.answer, it.notes, it.voicing) }, twin.map { Triple(it.answer, it.notes, it.voicing) })
        assertTrue(twin.all { it.front == "Welcher Akkord ist das?" })
        assertEquals(DeckKind.CARDS, vm.deck(cardsKit)!!.kind)
        assertEquals("Startkit: Akkordarten", vm.deck(cardsKit)!!.name)
        // One file per inversion, even though three cards are called Amin; both decks share it.
        assertEquals(chords.size, folders.list(chordsFolder)!!.size)
        assertEquals(chords.map { it.source }, twin.map { it.source })
        vm.syncFolder(cardsKit)
        assertEquals(twin, vm.cardsIn(cardsKit))
        assertEquals(24, vm.cardsIn(StarterKits.INTERVALS_APART_ID + StarterKits.CARDS_SUFFIX).size)
        assertTrue(vm.cardsIn(StarterKits.INTERVALS_APART_ID + StarterKits.CARDS_SUFFIX).all { it.front == "Welches Intervall ist das?" })
    }

    @Test
    fun intervalKitsHaveTheirOwnFolders() {
        val vm = vm()
        assertEquals(24, vm.cardsIn(StarterKits.INTERVALS_TOGETHER_ID).size)
        assertEquals(24, vm.cardsIn(StarterKits.INTERVALS_APART_ID).size)
        val together = folders.list("/widder/Startkit Intervalle gleichzeitig")!!.map { it.name }.toSet()
        assertTrue("kleine Terz.mid" in together && "Quintdezime.mid" in together, "$together")
        assertEquals(24, folders.list("/widder/Startkit Intervalle nacheinander")!!.size)
        // A sync reads the files back: still one card per interval, nothing turned into chords.
        vm.syncFolder(StarterKits.INTERVALS_TOGETHER_ID, quiet = true)
        val cards = vm.cardsIn(StarterKits.INTERVALS_TOGETHER_ID)
        assertEquals(24, cards.size)
        assertEquals(listOf("kleine Terz"), cards.first { it.source == "kleine Terz.mid" }.answer)
    }

    @Test
    fun chordKitNamesTheChordAndHidesTheInversion() {
        val store = Store(MemoryStore())
        val vm = vm(store)
        val cards = vm.cardsIn(StarterKits.CHORDS_ID)
        // Triads in three positions, sevenths in four, add9 with root, third or fifth in the bass,
        // the two-note sounds in two, the octave once.
        assertEquals(3 * 3 + 3 * 4 + 2 * 3 + 3 * 2 + 1, cards.size)
        assertEquals(StarterKits.CHORDS.toSet(), cards.map { it.answer.single() }.toSet())
        assertTrue(cards.none { "/" in it.answer.single() || it.notes.isNotEmpty() })
        assertEquals(StarterKits.CHORDS, vm.deck(StarterKits.CHORDS_ID)!!.pool)
        val maj7 = cards.filter { it.answer == listOf("Amaj7") }.map { it.voicing }
        assertEquals(
            listOf("Amaj7: Grundstellung", "Amaj7/Cis: 1. Umkehrung (Terz im Bass)", "Amaj7/E: 2. Umkehrung (Quinte im Bass)", "Amaj7/Gis: 3. Umkehrung (Septime im Bass)"),
            maj7,
        )
        assertTrue(cards.any { it.voicing == "Adim/Es: 2. Umkehrung (Quinte im Bass)" }, cards.map { it.voicing }.toString())
        // What the voicing says is what sounds: the lowest note is its bass.
        for (card in cards) {
            val lowest = Midi.parse(store.readMedia(card.frontAudio!!.file)!!).notes.minOf { it.pitch } % 12
            val written = Chord.parse(card.voicing.substringBefore(':'), german = true)!!
            assertEquals(written.bass ?: written.root, lowest, card.voicing)
        }
        fun pitches(voicing: String) = Midi.parse(store.readMedia(cards.single { it.voicing.startsWith("$voicing:") }.frontAudio!!.file)!!).notes.map { it.pitch }.sorted()
        // Two-note sounds stay two notes: the inversions turn the third into a sixth, the fifth into a fourth.
        assertEquals(listOf(57, 69), pitches("A-Oktave"))
        assertEquals(listOf(57, 61), pitches("Amaj(no5)"))
        assertEquals(listOf(49, 57), pitches("Amaj(no5)/Cis"))
        assertEquals(listOf(48, 57), pitches("Amin(no5)/C"))
        assertEquals(listOf(52, 57), pitches("A5/E"))
        // Every card id is different, also in the flash-card twin.
        assertEquals(cards.size, (cards + vm.cardsIn(cardsKit)).map { it.id }.toSet().size / 2)
        // Without numerals the tiles are just the names.
        val type = cards.first { it.answer == listOf("Amin7") }
        assertEquals("Amin7", vm.chordLabel(vm.tokensOf(type).single(), vm.keyFor(type), type.deckId))
    }

    @Test
    fun learningOnlyATagLeavesTheOtherCardsOut() {
        val vm = vm()
        val deck = vm.deck(StarterKits.CHORDS_ID)!!
        assertEquals(20, vm.counts(deck).new)
        vm.updateDeck(deck.copy(newPerDay = 500))
        for (card in vm.cardsIn(deck.id).filter { it.answer.single() in listOf("Amaj", "Amin") }) vm.updateCardForTest(card.copy(tags = listOf("Basis")))
        vm.setStudyTags(deck.id, listOf("Basis"))
        assertEquals(6, vm.counts(vm.deck(deck.id)!!).new)
        vm.setStudyTags(deck.id, listOf("Basis", "gibt es nicht"))
        assertEquals(listOf("Basis"), vm.deck(deck.id)!!.studyTags)
        // The wrong tiles come from the tagged cards only.
        repeat(5) {
            vm.startTraining(deck.id)
            val tiles = vm.training!!.round!!.pool.map { it.token.text }
            assertTrue(tiles.all { it in listOf("Amaj", "Amin") }, "$tiles")
            vm.back()
        }
    }

    @Test
    fun aCollectionFrom18GetsTheNewKitsOnce() {
        val store = Store(MemoryStore())
        // A collection from 1.8: the old chord types and the interval flash cards, one of them learned.
        val old = AppViewModel(store, SilentAudio.system, hooks, live = false, main = Dispatchers.Unconfined, work = Dispatchers.Unconfined)
        val (intervals, intervalCards) = StarterKits.kit(StarterKits.INTERVALS_TOGETHER_ID + StarterKits.CARDS_SUFFIX)!!.build(store)
        val oldTypes = Deck("startkit-akkordtypen", "Startkit: Akkordtypen", DeckKind.CHORDS)
        val c = old.collection.copy(
            decks = old.collection.decks.filter { it.id !in StarterKits.STANDARD } + intervals.copy(name = intervals.name + " (Karten)") + oldTypes,
            cards = old.collection.cards.filter { it.deckId !in StarterKits.STANDARD } + intervalCards +
                Card("startkit-akkordtypen-major-0", oldTypes.id, front = "Cmaj", answer = listOf("Cmaj")),
            settings = old.collection.settings.copy(standardKits = 1),
        )
        store.saveCollection(c)
        store.saveProgress(Progress().answer(intervalCards[0], Grade.GOOD, 0))
        val vm = vm(store)
        // The learned card is still there with its progress, and nothing came twice.
        assertEquals(intervalCards.map { it.id }, vm.cardsIn(intervals.id).map { it.id })
        assertNotNull(vm.progress.cards[intervalCards[0].id])
        assertEquals("Startkit: Intervalle gleichzeitig", vm.deck(intervals.id)!!.name)
        StarterKits.STANDARD.forEach { assertNotNull(vm.deck(it), it) }
        // The old decks stay until they are deleted.
        assertEquals(1, vm.cardsIn(oldTypes.id).size)
        // Only once: deleting a kit afterwards sticks.
        vm.deleteDeck(StarterKits.CHORDS_ID)
        assertNull(vm(store).deck(StarterKits.CHORDS_ID))
    }

    @Test
    fun severalDecksAreMarkedAndDeletedTogether() {
        val store = Store(MemoryStore())
        val vm = vm(store)
        val before = vm.collection.decks.size
        val flashCards = vm.decks(DeckKind.CARDS).size
        vm.toggleMark(StarterKits.CHORDS_ID)
        vm.toggleMark(cardsKit)
        vm.toggleMark(cardsKit)
        assertEquals(setOf(StarterKits.CHORDS_ID), vm.marked)
        vm.markAll(DeckKind.CARDS, on = true)
        assertEquals(flashCards + 1, vm.marked.size)
        vm.deleteMarked()
        assertEquals(before, vm.collection.decks.size, "asks first")
        vm.confirm!!.run()
        assertEquals(before - flashCards - 1, vm.collection.decks.size)
        assertTrue(vm.decks(DeckKind.CARDS).isEmpty())
        assertNull(vm.deck(StarterKits.CHORDS_ID))
        assertTrue(vm.cardsIn(StarterKits.CHORDS_ID).isEmpty())
        assertFalse(vm.marking)
        assertTrue(vm.marked.isEmpty())
        // Gone for good, also after a restart.
        assertTrue(vm(store).decks(DeckKind.CARDS).isEmpty())
    }

    @Test
    fun twoNoteMidiInTheFolderIsAnInterval() {
        val vm = vm()
        val deck = vm.createDeck("Meine Intervalle", DeckKind.CHORDS)
        vm.updateDeck(deck.copy(folder = "/widder/eigene"))
        folders.put("/widder/eigene", "Aufnahme 1.mid", Midi.write(MidiAnalysis.interval(52, 7, together = true)))
        folders.put("/widder/eigene", "große Terz.mid", Midi.write(MidiAnalysis.interval(52, 4, together = false)))
        vm.syncFolder(deck.id, quiet = true)
        assertEquals(setOf(listOf("Quinte"), listOf("große Terz")), vm.cardsIn(deck.id).map { it.answer }.toSet())
    }

    @Test
    fun starterKitIsWrittenToItsFolder() {
        val vm = vm()
        val names = folders.list(chordsFolder)!!.map { it.name }.toSet()
        assertTrue("Amin.mid" in names && "Amin (3).mid" in names && "Amaj(no5) (2).mid" in names, names.toString())
        // Both kit decks follow the same folder; nothing got read in twice.
        for (id in listOf(StarterKits.CHORDS_ID, cardsKit)) {
            assertEquals(chordsFolder, vm.deck(id)!!.folder)
            assertTrue(vm.cardsIn(id).all { it.source != null })
        }
        assertNull(vm.message?.takeIf { "ersetzt" in it })
    }

    @Test
    fun swappedAddedAndDeletedFilesAreFollowed() {
        val vm = vm()
        val deck = StarterKits.CHORDS_ID
        val a7 = vm.cardsIn(deck).first { it.source == "A7.mid" }
        vm.saveProgressForTest(a7)
        val learned = vm.progress.cards[a7.id]
        assertNotNull(learned)

        // A7 as a WAV recording instead of MIDI: same name, the card and its progress stay.
        folders.dirs[chordsFolder]!!.remove("A7.mid")
        folders.put(chordsFolder, "A7.wav", widder.audio.Wav.write(widder.audio.Pcm.silence(44100, 0.5)))
        // Amin replaced with other content under the same name.
        val oldAmin = vm.cardsIn(deck).first { it.source == "Amin.mid" }.frontAudio!!.file
        folders.put(chordsFolder, "Amin.mid", midiOf("Am", "Am"))
        // One A5 deleted, a new progression added.
        folders.dirs[chordsFolder]!!.remove("A5.mid")
        folders.put(chordsFolder, "Dm G C.mid", midiOf("Dm", "G", "C"))

        vm.syncFolder(deck, quiet = false)

        val cards = vm.cardsIn(deck)
        val swapped = cards.first { it.id == a7.id }
        assertEquals("A7.wav", swapped.source)
        assertEquals(listOf("A7"), swapped.answer)
        assertEquals(learned, vm.progress.cards[a7.id])
        assertNotEquals(oldAmin, cards.first { it.source == "Amin.mid" }.frontAudio!!.file)
        assertEquals(1, cards.count { it.answer == listOf("A5") })
        assertEquals(listOf("Dm", "G", "C"), cards.first { it.source == "Dm G C.mid" }.answer)
        assertEquals("Startkit: Akkordarten: 1 neu, 2 ersetzt, 1 entfernt", vm.message)
    }

    @Test
    fun answersMisreadBefore13AreRepaired() {
        val vm = vm()
        val deck = vm.createDeck("Moll", DeckKind.CARDS)
        vm.updateDeck(deck.copy(folder = "/widder/moll"))
        folders.put("/widder/moll", "Am∕C Am∕E Am(add9).mid", midiOf("Am/C", "Am/E", "Am(add9)"))
        folders.put("/widder/moll", "A7.mid", midiOf("A7"))
        vm.syncFolder(deck.id)
        val card = vm.cardsIn(deck.id).first { it.source == "Am∕C Am∕E Am(add9).mid" }
        assertEquals(listOf("Am/C", "Am/E", "Am(add9)"), card.answer)
        // What 1.2 stored, and one the user changed on purpose.
        vm.updateCardForTest(card.copy(answer = listOf("Am/C", "Am/E", "Am")))
        val own = vm.cardsIn(deck.id).first { it.source == "A7.mid" }
        vm.updateCardForTest(own.copy(answer = listOf("A9")))
        vm.syncFolder(deck.id)
        assertEquals(listOf("Am/C", "Am/E", "Am(add9)"), vm.cardsIn(deck.id).first { it.id == card.id }.answer)
        assertEquals(listOf("A9"), vm.cardsIn(deck.id).first { it.id == own.id }.answer)
    }

    @Test
    fun anyDeckCanBeLinked() {
        val vm = vm()
        val deck = vm.createDeck("Meine Akkorde", DeckKind.CHORDS)
        folders.put("/widder/Meine Akkorde", "Fmin Gmin.mid", midiOf("Fm", "Gm"))
        vm.openFolder(deck.id)
        assertEquals(listOf("Fmin", "Gmin"), vm.cardsIn(deck.id).single().answer)
    }

    @Test
    fun endMarkerDraggedToTheLeftEdgeKeepsASelection() {
        val vm = vm()
        vm.editCard(vm.cardsIn(cardsKit).first { it.frontAudio != null })
        vm.openAudioEditor(ClipTarget.Editor(back = false))
        val state = assertNotNull(vm.audioEditor)
        // Crashed on the phone: the start had no room left before the end.
        vm.updateEdit(state.edit.copy(trimEnd = 0.0))
        assertEquals(0.0, state.edit.trimStart)
        assertTrue(state.selectionEnd > state.edit.trimStart)
    }
}

package widder

import kotlinx.coroutines.Dispatchers
import widder.audio.SilentAudio
import widder.core.Card
import widder.core.Deck
import widder.core.DeckKind
import widder.core.Grade
import widder.core.Progress
import widder.core.answer
import widder.core.NumeralMode
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
    private val kitFolder = "/widder/${StarterKits.FOLDER}"

    private fun vm(store: Store = Store(MemoryStore())) = AppViewModel(store, SilentAudio.system, hooks, live = false, main = Dispatchers.Unconfined, work = Dispatchers.Unconfined)
        .also { it.refreshFolders() }

    private fun midiOf(vararg chords: String) = Midi.write(MidiAnalysis.progression(chords.map { Chord.parse(it)!! }))

    @Test
    fun everyTrainingKitAlsoComesAsFlashCards() {
        val vm = vm()
        val twins = vm.missingKits(DeckKind.CARDS).map { it.id }
        // The A chords come with a new collection, their cards included.
        assertEquals(vm.missingKits(DeckKind.CHORDS).map { it.id + StarterKits.CARDS_SUFFIX }, twins)
        // Training first, then its twin: they share the folder and its files, nothing is doubled.
        vm.addStarterKit(StarterKits.CHORD_TYPES_ID)
        twins.forEach { vm.addStarterKit(it) }
        assertTrue(vm.missingKits(DeckKind.CARDS).isEmpty())
        val types = vm.cardsIn(StarterKits.CHORD_TYPES_ID + StarterKits.CARDS_SUFFIX)
        assertEquals(vm.cardsIn(StarterKits.CHORD_TYPES_ID).map { it.answer to it.notes }, types.map { it.answer to it.notes })
        assertTrue(types.all { it.front == "Welcher Akkordtyp ist das?" })
        assertEquals(DeckKind.CARDS, vm.deck(StarterKits.CHORD_TYPES_ID + StarterKits.CARDS_SUFFIX)!!.kind)
        // One file per inversion, even though four cards are called Cmaj7.
        assertEquals(vm.cardsIn(StarterKits.CHORD_TYPES_ID).size, folders.list("/widder/Startkit Akkordtypen")!!.size)
        assertEquals(vm.cardsIn(StarterKits.CHORD_TYPES_ID).map { it.source }, types.map { it.source })
        vm.syncFolder(StarterKits.CHORD_TYPES_ID + StarterKits.CARDS_SUFFIX)
        assertEquals(types, vm.cardsIn(StarterKits.CHORD_TYPES_ID + StarterKits.CARDS_SUFFIX))
        assertEquals(24, vm.cardsIn(StarterKits.INTERVALS_APART_ID + StarterKits.CARDS_SUFFIX).size)
        // The degrees keep their key and numerals, and the answer is not on the front.
        val degrees = vm.deck(StarterKits.DEGREES_ID + StarterKits.CARDS_SUFFIX)!!
        assertEquals("C" to NumeralMode.ONLY, degrees.key to degrees.numerals)
        val progression = vm.cardsIn(degrees.id).first { it.answer == listOf("C", "F", "G", "C") }
        assertEquals("Welche Stufen hörst du?", progression.front)
        assertEquals(4, progression.notes.lines().size)
    }

    @Test
    fun newStarterKitsGetTheirOwnFolders() {
        val store = Store(MemoryStore())
        val vm = vm(store)
        val kits = vm.missingKits(DeckKind.CHORDS).map { it.id }
        assertEquals(
            listOf(StarterKits.INTERVALS_TOGETHER_ID, StarterKits.INTERVALS_APART_ID) + StarterKits.SCALES.map { it.id },
            kits,
        )
        kits.forEach { vm.addStarterKit(it) }
        assertTrue(vm.missingKits(DeckKind.CHORDS).isEmpty())
        assertEquals(24, vm.cardsIn(StarterKits.INTERVALS_TOGETHER_ID).size)
        assertEquals(24, vm.cardsIn(StarterKits.INTERVALS_APART_ID).size)
        // Triads in three positions, sevenths in four, add9 with root, third or fifth in the bass.
        assertEquals(21 + 36 + 55, vm.cardsIn(StarterKits.CHORD_TYPES_ID).size)
        val maj7 = vm.cardsIn(StarterKits.CHORD_TYPES_ID).filter { it.answer == listOf("Cmaj7") }.map { it.notes }
        assertEquals(listOf("Cmaj7: Grundstellung", "Cmaj7/E: 1. Umkehrung (Terz im Bass)", "Cmaj7/G: 2. Umkehrung (Quinte im Bass)", "Cmaj7/H: 3. Umkehrung (Septime im Bass)"), maj7)
        val together = folders.list("/widder/Startkit Intervalle gleichzeitig")!!.map { it.name }.toSet()
        assertTrue("kleine Terz.mid" in together && "Quintdezime.mid" in together, "$together")
        assertEquals(24, folders.list("/widder/Startkit Intervalle nacheinander")!!.size)
        // A sync reads the files back: still one card per interval, nothing turned into chords.
        vm.syncFolder(StarterKits.INTERVALS_TOGETHER_ID, quiet = true)
        val cards = vm.cardsIn(StarterKits.INTERVALS_TOGETHER_ID)
        assertEquals(24, cards.size)
        assertEquals(listOf("kleine Terz"), cards.first { it.source == "kleine Terz.mid" }.answer)

        // The degrees deck writes I, IV ... whatever the settings say.
        val degree = vm.cardsIn(StarterKits.DEGREES_ID).first { it.answer == listOf("C", "F", "G", "C") }
        assertEquals("I – IV – V – I", degree.front)
        assertEquals(listOf("I", "IV", "V", "I"), vm.tokensOf(degree).map { vm.chordLabel(it, vm.keyFor(degree), degree.deckId) })
        // The notes say which inversion sounds; the answer and the numerals do not.
        assertTrue(degree.notes.lines().first() == "C: Grundstellung" && degree.notes.lines().size == 4, degree.notes)
        val played = Midi.parse(store.readMedia(degree.frontAudio!!.file)!!)
        val basses = MidiAnalysis.segments(played).map { s -> s.pitches.min() % 12 }
        val written = degree.notes.lines().map { line -> Chord.parse(line.substringBefore(':'))!!.let { it.bass ?: it.root } }
        assertEquals(written, basses)
        val dorian = vm.cardsIn("startkit-d-dorisch")
        assertEquals("i – IV – i", dorian.first { it.answer == listOf("Dm", "G", "Dm") }.front)
        assertEquals("Startkit: D-Dorisch", vm.deck("startkit-d-dorisch")!!.name)
        val minor = vm.cardsIn("startkit-stufen-a-moll").first { it.answer == listOf("Am", "Dm", "E", "Am") }
        assertEquals("i – iv – V – i", minor.front)
        val type = vm.cardsIn(StarterKits.CHORD_TYPES_ID).first { it.answer == listOf("Cmin7") }
        assertEquals("Cmin7", vm.chordLabel(vm.tokensOf(type).single(), vm.keyFor(type), type.deckId))
    }

    @Test
    fun aNewCollectionHasTheStandardKitsWithLevels() {
        val store = Store(MemoryStore())
        val vm = vm(store)
        StarterKits.STANDARD.forEach { assertNotNull(vm.deck(it), it) }
        val types = vm.cardsIn(StarterKits.CHORD_TYPES_ID)
        assertEquals(StarterKits.LEVELS, vm.tagsIn(StarterKits.CHORD_TYPES_ID))
        assertTrue(types.all { it.tags.size == 1 })
        assertEquals(setOf("Cmaj", "Cmin", "C5", "C-Oktave", "C7", "Cmaj7", "Cmin7"), types.filter { "Basis" in it.tags }.map { it.answer.single() }.toSet())
        assertTrue(types.any { it.answer == listOf("Cdim7") && "Fortgeschritten" in it.tags })
        // Two-note sounds stay two notes: no extra bass under the octave and the power chord.
        val octave = types.single { it.answer == listOf("C-Oktave") }
        assertEquals(listOf(60, 72), Midi.parse(store.readMedia(octave.frontAudio!!.file)!!).notes.map { it.pitch }.sorted())
        assertEquals(2, types.count { it.answer == listOf("C5") })
        assertEquals("Csus4/F: 1. Umkehrung (Quarte im Bass)", types.single { it.answer == listOf("Csus4") && it.notes.contains("/F") }.notes)
        assertEquals("C6/A: 3. Umkehrung (Sexte im Bass)", types.single { it.answer == listOf("C6") && it.notes.contains("/A") }.notes)
        // Every card id is different, also in the flash-card twin.
        val twin = vm.cardsIn(StarterKits.CHORD_TYPES_ID + StarterKits.CARDS_SUFFIX)
        assertEquals(types.size, (types + twin).map { it.id }.toSet().size / 2)

        val steps = vm.cardsIn(StarterKits.PROGRESSIONS_ID)
        assertEquals(StarterKits.STEPS.size, steps.size)
        val plagal = steps.single { it.answer == listOf("F", "C") }
        // Same chords, other key: in C major E – Am is a secondary dominant, III – vi.
        val secondary = steps.single { it.answer == listOf("E", "Am") && it.key == "C" }
        assertEquals("III – vi", secondary.front)
        assertEquals("IV – I", plagal.front)
        assertEquals(listOf("IV", "I"), vm.tokensOf(plagal).map { vm.chordLabel(it, vm.keyFor(plagal), plagal.deckId) })
        val minor = steps.single { it.answer == listOf("E", "Am") && it.key == "a-Moll" }
        assertEquals(listOf("V", "i"), vm.tokensOf(minor).map { vm.chordLabel(it, vm.keyFor(minor), minor.deckId) })
        // The tonic comes first as the reference, then the two chords.
        val heard = MidiAnalysis.segments(Midi.parse(store.readMedia(minor.frontAudio!!.file)!!))
        assertEquals(listOf(9, 4, 9), heard.map { s -> s.pitches.min() % 12 })
        assertTrue(folders.list("/widder/Startkit Akkordfolgen")!!.isNotEmpty())
    }

    @Test
    fun learningOnlyATagLeavesTheOtherCardsOut() {
        val vm = vm()
        val deck = vm.deck(StarterKits.CHORD_TYPES_ID)!!
        assertEquals(20, vm.counts(deck).new)
        vm.updateDeck(deck.copy(newPerDay = 500))
        vm.setStudyTags(deck.id, listOf("Basis"))
        assertEquals(21, vm.counts(vm.deck(deck.id)!!).new)
        vm.setStudyTags(deck.id, listOf("Fortgeschritten", "Basis", "gibt es nicht"))
        assertEquals(listOf("Basis", "Fortgeschritten"), vm.deck(deck.id)!!.studyTags)
        assertEquals(21 + 55, vm.counts(vm.deck(deck.id)!!).new)
        // The wrong tiles come from the basic types only.
        vm.setStudyTags(deck.id, listOf("Basis"))
        repeat(5) {
            vm.startTraining(deck.id)
            val tiles = vm.training!!.round!!.pool.map { it.token.text }
            assertTrue(tiles.all { it in StarterKits.CHORD_TYPE_LEVELS.first().second }, "$tiles")
            vm.back()
        }
    }

    @Test
    fun theOldChordTypesKitGrowsAndKeepsItsProgress() {
        val store = Store(MemoryStore())
        // A collection from 1.7: the six types under their old ids, one of them learned.
        val old = AppViewModel(store, SilentAudio.system, hooks, live = false, main = Dispatchers.Unconfined, work = Dispatchers.Unconfined)
        val oldTypes = listOf("Cmaj", "Cmaj7", "Cmin", "Cmin7", "Cmaj(add9)", "Cmin(add9)")
        val oldCards = oldTypes.flatMap { name -> Chord.parse(name)!!.inversions().map { name to it } }.mapIndexed { i, (name, played) ->
            val position = if (played.bass == null) "Grundstellung" else null
            Card("${StarterKits.CHORD_TYPES_ID}-$i", StarterKits.CHORD_TYPES_ID, front = name, answer = listOf(name), notes = position?.let { "$name: $it" } ?: "x$i")
        }
        val c = old.collection.copy(
            decks = old.collection.decks.filter { it.id !in StarterKits.STANDARD } + Deck(StarterKits.CHORD_TYPES_ID, "Startkit: Akkordtypen", DeckKind.CHORDS, pool = oldTypes),
            cards = old.collection.cards.filter { it.deckId !in StarterKits.STANDARD } + oldCards,
            settings = old.collection.settings.copy(standardKits = 0),
        )
        store.saveCollection(c)
        store.saveProgress(Progress().answer(oldCards[0], Grade.GOOD, 0))
        val vm = vm(store)
        val types = vm.cardsIn(StarterKits.CHORD_TYPES_ID)
        // The root position of Cmaj is still the learned card, now tagged; nothing came twice.
        val cmaj = types.single { it.answer == listOf("Cmaj") && it.notes == "Cmaj: Grundstellung" }
        assertEquals(oldCards[0].id, cmaj.id)
        assertEquals(listOf("Basis"), cmaj.tags)
        assertNotNull(vm.progress.cards[cmaj.id])
        assertEquals(StarterKits.CHORD_TYPES, vm.deck(StarterKits.CHORD_TYPES_ID)!!.pool)
        assertTrue(types.any { it.answer == listOf("C13") })
        assertEquals(1, types.count { it.notes == "Cmaj: Grundstellung" })
        StarterKits.STANDARD.forEach { assertNotNull(vm.deck(it), it) }
        // Only once: deleting the deck afterwards sticks.
        vm.deleteDeck(StarterKits.PROGRESSIONS_ID)
        assertNull(vm(store).deck(StarterKits.PROGRESSIONS_ID))
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
        val names = folders.list(kitFolder)!!.map { it.name }.toSet()
        assertEquals(StarterKits.A_CHORDS.size + 4, names.size)
        assertTrue("Am∕C.mid" in names, names.toString())
        assertTrue("Amaj A∕C# A∕E.mid" in names, names.toString())
        // Both kit decks follow the same folder; nothing got read in twice.
        for (id in listOf(StarterKits.A_CHORDS_ID, StarterKits.A_CARDS_ID)) {
            assertEquals(kitFolder, vm.deck(id)!!.folder)
            assertTrue(vm.cardsIn(id).all { it.source != null })
        }
        assertNull(vm.message?.takeIf { "ersetzt" in it })
        // A slash in the file name is read back as a slash.
        val amc = vm.cardsIn(StarterKits.A_CHORDS_ID).first { it.source == "Am∕C.mid" }
        assertEquals(listOf("Am/C"), amc.answer)
    }

    @Test
    fun swappedAddedAndDeletedFilesAreFollowed() {
        val vm = vm()
        val deck = StarterKits.A_CHORDS_ID
        val a7 = vm.cardsIn(deck).first { it.source == "A7.mid" }
        vm.saveProgressForTest(a7)
        val learned = vm.progress.cards[a7.id]
        assertNotNull(learned)

        // A7 as a WAV recording instead of MIDI: same name, the card and its progress stay.
        folders.dirs[kitFolder]!!.remove("A7.mid")
        folders.put(kitFolder, "A7.wav", widder.audio.Wav.write(widder.audio.Pcm.silence(44100, 0.5)))
        // Am/C replaced with other content under the same name.
        val oldAmC = vm.cardsIn(deck).first { it.source == "Am∕C.mid" }.frontAudio!!.file
        folders.put(kitFolder, "Am∕C.mid", midiOf("Am/C", "Am/C"))
        // A5 deleted, a new progression added.
        folders.dirs[kitFolder]!!.remove("A5.mid")
        folders.put(kitFolder, "Dm G C.mid", midiOf("Dm", "G", "C"))

        vm.syncFolder(deck, quiet = false)

        val cards = vm.cardsIn(deck)
        val swapped = cards.first { it.id == a7.id }
        assertEquals("A7.wav", swapped.source)
        assertEquals(listOf("A7"), swapped.answer)
        assertEquals(learned, vm.progress.cards[a7.id])
        assertNotEquals(oldAmC, cards.first { it.source == "Am∕C.mid" }.frontAudio!!.file)
        assertTrue(cards.none { it.source == "A5.mid" })
        assertEquals(listOf("Dm", "G", "C"), cards.first { it.source == "Dm G C.mid" }.answer)
        assertEquals("Startkit: A-Akkorde: 1 neu, 2 ersetzt, 1 entfernt", vm.message)
    }

    @Test
    fun answersMisreadBefore13AreRepaired() {
        val vm = vm()
        val deck = StarterKits.A_CARDS_ID
        val card = vm.cardsIn(deck).first { it.source == "Am∕C Am∕E Am(add9).mid" }
        assertEquals(listOf("Am/C", "Am/E", "Am(add9)"), card.answer)
        // What 1.2 stored, and one the user changed on purpose.
        vm.updateCardForTest(card.copy(answer = listOf("Am/C", "Am/E", "Am")))
        val own = vm.cardsIn(deck).first { it.source == "A7.mid" }
        vm.updateCardForTest(own.copy(answer = listOf("A9")))
        vm.syncFolder(deck)
        assertEquals(listOf("Am/C", "Am/E", "Am(add9)"), vm.cardsIn(deck).first { it.id == card.id }.answer)
        assertEquals(listOf("A9"), vm.cardsIn(deck).first { it.id == own.id }.answer)
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
        vm.editCard(vm.cardsIn(StarterKits.A_CARDS_ID).first { it.frontAudio != null })
        vm.openAudioEditor(ClipTarget.Editor(back = false))
        val state = assertNotNull(vm.audioEditor)
        // Crashed on the phone: the start had no room left before the end.
        vm.updateEdit(state.edit.copy(trimEnd = 0.0))
        assertEquals(0.0, state.edit.trimStart)
        assertTrue(state.selectionEnd > state.edit.trimStart)
    }
}

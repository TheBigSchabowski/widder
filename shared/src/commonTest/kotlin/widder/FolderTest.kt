package widder

import kotlinx.coroutines.Dispatchers
import widder.audio.SilentAudio
import widder.core.DeckKind
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

    private fun vm() = AppViewModel(Store(MemoryStore()), SilentAudio.system, hooks, live = false, main = Dispatchers.Unconfined, work = Dispatchers.Unconfined)
        .also { it.refreshFolders() }

    private fun midiOf(vararg chords: String) = Midi.write(MidiAnalysis.progression(chords.map { Chord.parse(it)!! }))

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
}

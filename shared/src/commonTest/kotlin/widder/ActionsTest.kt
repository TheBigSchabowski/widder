package widder

import kotlinx.coroutines.Dispatchers
import kotlinx.serialization.json.Json
import widder.audio.AudioPlayer
import widder.audio.AudioRecorder
import widder.audio.AudioSystem
import widder.audio.Pcm
import widder.audio.SilentAudio
import widder.core.Action
import widder.core.ActionKind
import widder.core.DeckKind
import widder.core.FileStore
import widder.core.GestureTile
import widder.core.KeyStroke
import widder.core.Settings
import widder.core.Shortcut
import widder.core.Shortcuts
import widder.core.TileScope
import widder.core.Store
import widder.ui.AppViewModel
import widder.ui.Screen
import widder.ui.KeyCommand
import widder.ui.StarterKits
import widder.ui.tileIndexAt
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.unit.IntSize
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

private class Files : FileStore {
    val files = mutableMapOf<String, ByteArray>()
    override val location = "memory"
    override fun read(name: String) = files[name]?.decodeToString()
    override fun write(name: String, text: String) { files[name] = text.encodeToByteArray() }
    override fun readBytes(name: String) = files[name]
    override fun writeBytes(name: String, bytes: ByteArray) { files[name] = bytes }
    override fun delete(name: String) { files.remove(name) }
    override fun exists(name: String) = name in files
}

/** A microphone that "records" one second of silence. */
private val recordingAudio = AudioSystem(
    player = SilentAudio.system.player,
    recorder = object : AudioRecorder {
        override var isRecording = false
        override fun start() = true.also { isRecording = true }
        override fun level() = 0f
        override fun stop(): Pcm = Pcm(44100, listOf(FloatArray(44100))).also { isRecording = false }
    },
    decoder = { _, _ -> null },
)

class ActionsTest {
    private val ctrlB = KeyStroke("B", ctrl = true)
    private val cardsKit = StarterKits.CHORDS_ID + StarterKits.CARDS_SUFFIX
    private val chordsKit = StarterKits.CHORDS_ID

    private fun vm(audio: AudioSystem = SilentAudio.system, files: Files = Files()) =
        AppViewModel(Store(files), audio, live = false, main = Dispatchers.Unconfined, work = Dispatchers.Unconfined)

    @Test
    fun gridKeepsItsFieldsWhenResized() {
        val vm = vm()
        assertEquals(Shortcuts.defaultTiles, vm.tiles)
        vm.updateSettings { it.copy(tileColumns = 3, tileRows = 2) }
        assertEquals(6, vm.tiles.size)
        assertTrue(vm.tiles[5].isEmpty)
        val record = GestureTile("Jazz", longPress = Action(ActionKind.RECORD, cardsKit))
        vm.saveTile(5, record)
        vm.updateSettings { it.copy(tileColumns = 2, tileRows = 2) }
        assertEquals(4, vm.tiles.size)
        vm.updateSettings { it.copy(tileColumns = 3, tileRows = 2) }
        assertEquals(record, vm.tiles[5], "a smaller grid hides fields, it does not forget them")
        vm.resetTiles()
        assertNull(vm.settings.tiles)
        assertEquals(4, vm.tiles.size)
    }

    @Test
    fun fieldUnderTheFinger() {
        val size = IntSize(400, 800)
        assertEquals(0, tileIndexAt(Offset(10f, 10f), size, 2, 2))
        assertEquals(1, tileIndexAt(Offset(390f, 10f), size, 2, 2))
        assertEquals(2, tileIndexAt(Offset(10f, 790f), size, 2, 2))
        assertEquals(3, tileIndexAt(Offset(400f, 800f), size, 2, 2), "the very edge still belongs to the last field")
        assertEquals(7, tileIndexAt(Offset(200f, 500f), size, 3, 4))
    }

    @Test
    fun gesturesOnlyWhereTheGridListens() {
        val vm = vm()
        assertTrue(vm.tilesActive())
        assertTrue(vm.onTileGesture(2, long = true), "standard: holding bottom left opens the flash cards")
        assertEquals(Screen.Decks(DeckKind.CARDS), vm.screen)
        assertEquals(2, vm.tileFlash?.index)
        assertFalse(vm.tilesActive(), "standard: only on the start screen")
        assertFalse(vm.onTileGesture(2, long = false))

        vm.updateSettings { it.copy(tileScope = TileScope.EVERYWHERE) }
        assertTrue(vm.tilesActive())
        vm.home()
        vm.startTraining(chordsKit)
        assertTrue(vm.learnGrid, "while learning the learning grid listens instead")
        vm.updateSettings { it.copy(learnTilesOn = false) }
        assertFalse(vm.tilesActive(), "dragging chords needs the gestures itself")
        vm.updateSettings { it.copy(learnTilesOn = true) }
        vm.home()
        vm.newCard(cardsKit)
        assertFalse(vm.tilesActive(), "so does typing")
        vm.cancelEditor()

        vm.saveTile(0, GestureTile())
        assertFalse(vm.hasTileAction(0, long = true))
        assertFalse(vm.onTileGesture(0, long = true), "an empty field lets everything through")
        vm.updateSettings { it.copy(tileScope = TileScope.OFF) }
        assertFalse(vm.tilesActive())
    }

    @Test
    fun learningGridEditsTheCardShowsTheKeyboardAndGoesBack() {
        val vm = vm()
        assertEquals(Shortcuts.defaultLearnTiles, vm.tilesOf(learn = true))
        vm.startReview(cardsKit)
        val card = vm.review!!.card!!
        assertTrue(vm.learnGrid)
        assertTrue(vm.tilesActive(), "on by default while learning, whatever the other grid does")
        assertEquals(1 to 3, vm.gridColumns to vm.gridRows)
        assertFalse(vm.hasTileAction(1, long = false), "no keyboard before the answer")
        assertFalse(vm.hasTileAction(2, long = false), "nothing to go back to: the taps go through")

        vm.showAnswer()
        assertTrue(vm.onTileGesture(1, long = false))
        assertTrue(vm.keyboardOpen)
        assertTrue(vm.onTileGesture(1, long = false))
        assertFalse(vm.keyboardOpen)

        assertTrue(vm.onTileGesture(0, long = false))
        assertEquals(Screen.EditCard, vm.screen)
        assertTrue(vm.learnGrid, "the editor opened from learning keeps the learning grid")
        assertFalse(vm.hasTileAction(0, long = false), "already editing: double taps reach the text")
        vm.editor!!.notes = "Daumen auf Cis"
        assertTrue(vm.onTileGesture(2, long = false))
        assertEquals(Screen.Review(cardsKit), vm.screen)
        assertEquals("Daumen auf Cis", vm.review!!.card!!.notes)
        assertEquals("Daumen auf Cis", vm.collection.cards.first { it.id == card.id }.notes)

        vm.home()
        vm.newCard(cardsKit)
        assertFalse(vm.learnGrid, "a new card from the start screen is no learning")
        vm.cancelEditor()
    }

    @Test
    fun shuffleButtonAndDeckOption() {
        val vm = vm()
        val deck = vm.deck(cardsKit)!!
        assertTrue(deck.shuffle, "mixed unless switched off")
        // Switched off: the order the cards were made, until the button mixes them.
        vm.updateDeck(deck.copy(shuffle = false))
        vm.startReview(deck.id)
        val first = vm.cardsIn(deck.id).minBy { it.created }
        assertEquals(first.id, vm.review!!.card!!.id)
        assertTrue(vm.onKey(KeyCommand.SHUFFLE))
        assertTrue(vm.review!!.card!!.id != first.id, "the current card goes back into the pile")
        assertEquals(Screen.Review(deck.id), vm.screen)

        vm.home()
        vm.startTraining(chordsKit)
        val exercise = vm.training!!.card!!.id
        vm.shuffle()
        assertTrue(vm.training!!.card!!.id != exercise)
        assertEquals(0, vm.training!!.answered, "mixing is no answer")
    }

    @Test
    fun deckActionAsksOutsideADeckAndUsesTheOpenOne() {
        val vm = vm()
        vm.run(Action(ActionKind.NEW_CARD))
        assertEquals(ActionKind.NEW_CARD, vm.deckPick?.kind)
        vm.pickDeck(chordsKit)
        assertNull(vm.deckPick)
        assertEquals(Screen.EditCard, vm.screen)
        assertEquals(chordsKit, vm.editor?.deckId)
        vm.cancelEditor()

        vm.open(Screen.Browse(cardsKit))
        vm.run(Action(ActionKind.NEW_CARD))
        assertNull(vm.deckPick)
        assertEquals(cardsKit, vm.editor?.deckId)
        vm.editor!!.front = "Welche Tonart?"
        vm.saveEditor()
        assertEquals(Screen.Browse(cardsKit), vm.screen, "back where the card was started")
    }

    @Test
    fun fixedDeckWinsAndStudyPicksTheRightScreen() {
        val vm = vm()
        vm.open(Screen.Browse(cardsKit))
        vm.run(Action(ActionKind.STUDY, chordsKit))
        assertTrue(vm.screen is Screen.Train)
        assertEquals(listOf(Screen.Home, Screen.Train(chordsKit)), vm.stack, "jumps start from home")
        vm.run(Action(ActionKind.STUDY, cardsKit))
        assertEquals(Screen.Review(cardsKit), vm.screen)
        assertNotNull(vm.review)
        assertNull(vm.training)
    }

    @Test
    fun deletedDeckIsAskedFor() {
        val vm = vm()
        vm.run(Action(ActionKind.BROWSE, "gone"))
        assertEquals(ActionKind.BROWSE, vm.deckPick?.kind)
    }

    @Test
    fun nothingRunsOverAnOpenCard() {
        val vm = vm()
        vm.newCard(cardsKit)
        vm.run(Action(ActionKind.OPEN_CHORDS))
        assertEquals(Screen.EditCard, vm.screen)
    }

    @Test
    fun prefixThenLetter() {
        val vm = vm()
        assertFalse(vm.onShortcutKey(KeyStroke("F"), 'f'), "without the prefix a letter is just a letter")
        assertTrue(vm.onShortcutKey(ctrlB, null))
        assertTrue(vm.prefixPending)
        assertTrue(vm.onShortcutKey(KeyStroke("F"), 'f'))
        assertFalse(vm.prefixPending)
        assertEquals(Screen.Decks(DeckKind.CARDS), vm.screen)

        vm.onShortcutKey(ctrlB, null)
        vm.onShortcutKey(KeyStroke("M"), 'm')
        assertEquals(Screen.Decks(DeckKind.CHORDS), vm.screen)

        vm.onShortcutKey(ctrlB, null)
        vm.onShortcutKey(KeyStroke("Escape"), null)
        assertFalse(vm.prefixPending)
        assertEquals(Screen.Decks(DeckKind.CHORDS), vm.screen)

        vm.onShortcutKey(ctrlB, null)
        vm.runShortcut('?')
        assertEquals(Screen.Help, vm.screen)
    }

    @Test
    fun prefixAndNumberPicksTheDeck() {
        val vm = vm()
        // Numbered across both lists: the flash cards first, then the multiple choice.
        val numbered = vm.numberedDecks()
        assertEquals(vm.decks(DeckKind.CARDS) + vm.decks(DeckKind.CHORDS), numbered)
        val third = numbered[2]
        assertEquals(3, vm.deckNumber(third.id))
        vm.onShortcutKey(ctrlB, null)
        vm.onShortcutKey(KeyStroke("3"), '3')
        assertEquals(third.id, vm.selectedDeckId)
        assertEquals(Screen.Decks(third.kind), vm.screen)

        // The picked deck is the one the deck actions go to: L learns it, Y opens its options.
        vm.onShortcutKey(ctrlB, null)
        vm.onShortcutKey(KeyStroke("Y"), 'y')
        assertEquals(widder.ui.DeckDialog.Options(third.id), vm.deckDialog)
        vm.deckDialog = null
        vm.onShortcutKey(ctrlB, null)
        vm.onShortcutKey(KeyStroke("L"), 'l')
        assertNull(vm.deckPick)
        assertTrue(vm.screen == Screen.Review(third.id) || vm.screen == Screen.Train(third.id), "${vm.screen}")

        // Without a deck Y is the settings.
        vm.home()
        vm.onShortcutKey(ctrlB, null)
        vm.onShortcutKey(KeyStroke("Y"), 'y')
        assertEquals(Screen.Settings, vm.screen)

        // Shift + number ticks decks for deleting, across both lists.
        vm.onShortcutKey(ctrlB, null)
        vm.onShortcutKey(KeyStroke("1", shift = true), '!')
        vm.onShortcutKey(ctrlB, null)
        vm.onShortcutKey(KeyStroke("${numbered.size}", shift = true), '"')
        assertTrue(vm.marking)
        assertEquals(setOf(numbered.first().id, numbered.last().id), vm.marked)
        assertTrue(vm.back(), "Esc ends the marking first")
        assertFalse(vm.marking)
        assertTrue(vm.marked.isEmpty())

        vm.onShortcutKey(ctrlB, null)
        vm.onShortcutKey(KeyStroke("9"), '9')
        assertEquals("Es gibt keinen Stapel 9", vm.message)
    }

    @Test
    fun keysAreWrittenTheSystemsWay() {
        assertEquals("⌃B", ctrlB.label(mac = true))
        assertEquals("Strg+B", ctrlB.label(mac = false))
        val all = KeyStroke("Space", ctrl = true, alt = true, shift = true, meta = true)
        assertEquals("⌃⌥⇧⌘Leertaste", all.label(mac = true))
        assertEquals("Strg+Alt+Umschalt+Super+Leertaste", all.label(mac = false))
        assertTrue(KeyStroke("F5").usableAsPrefix)
        assertFalse(KeyStroke("F").usableAsPrefix)
    }

    @Test
    fun ownShortcutsAndPrefix() {
        val vm = vm()
        vm.saveShortcut(null, Shortcut('J', Action(ActionKind.BROWSE, chordsKit)))
        vm.saveShortcut('F', Shortcut('M', Action(ActionKind.SETTINGS)))
        assertEquals(1, vm.shortcuts.count { it.key == 'M' }, "a taken key is taken over")
        assertNull(vm.shortcuts.firstOrNull { it.key == 'F' })

        vm.capturingPrefix = true
        vm.onShortcutKey(KeyStroke("G"), 'g')
        assertTrue(vm.capturingPrefix, "a plain letter cannot be the prefix")
        val altSpace = KeyStroke("Space", alt = true)
        vm.onShortcutKey(altSpace, ' ')
        assertFalse(vm.capturingPrefix)
        assertEquals(altSpace, vm.settings.prefix)

        assertFalse(vm.onShortcutKey(ctrlB, null), "the old prefix is free again")
        vm.onShortcutKey(altSpace, ' ')
        vm.onShortcutKey(KeyStroke("J"), 'j')
        assertEquals(Screen.Browse(chordsKit), vm.screen)
    }

    @Test
    fun recordTileMakesACardFromTheRecording() {
        val vm = vm(recordingAudio)
        vm.run(Action(ActionKind.RECORD, cardsKit))
        assertEquals(Screen.EditCard, vm.screen)
        assertNotNull(vm.recording)
        vm.stopRecording()
        assertNotNull(vm.editor?.frontAudio, "the recording sits on the new card")
        assertNotNull(vm.audioEditor, "and opens for cutting")

        vm.closeAudioEditor()
        vm.cancelEditor()
        vm.run(Action(ActionKind.RECORD, cardsKit))
        vm.back()
        assertNull(vm.recording)
        assertEquals(Screen.Home, vm.screen, "a discarded quick recording takes its empty card along")
    }

    @Test
    fun settingsSurviveSaving() {
        val json = Json { ignoreUnknownKeys = true; encodeDefaults = false }
        val settings = Settings(
            tiles = listOf(GestureTile("Üben", doubleTap = Action(ActionKind.STUDY, "d1"), longPress = Action(ActionKind.RECORD))),
            tileColumns = 3,
            tileRows = 4,
            tileScope = TileScope.EVERYWHERE,
            showTiles = true,
            prefix = null,
            shortcuts = listOf(Shortcut('X', Action(ActionKind.IMPORT))),
        )
        assertEquals(settings, json.decodeFromString<Settings>(json.encodeToString(settings)))
        assertEquals(Shortcuts.DEFAULT_PREFIX, json.decodeFromString<Settings>("{}").prefix, "old collections get the prefix")
    }
}

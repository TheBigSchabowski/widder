package widder.desktop

import androidx.compose.ui.ImageComposeScene
import androidx.compose.ui.unit.Density
import androidx.compose.ui.use
import kotlinx.coroutines.Dispatchers
import org.jetbrains.skia.EncodedImageFormat
import widder.audio.SilentAudio
import widder.core.DeckKind
import widder.core.DirectoryStore
import widder.core.IncomingFile
import widder.core.Store
import widder.music.AnswerParser
import widder.music.Midi
import widder.music.MidiAnalysis
import widder.ui.App
import widder.ui.AppViewModel
import widder.ui.ClipTarget
import widder.ui.Screen
import java.io.File
import java.nio.file.Files

/**
 * Renders the main screens off-screen at phone and desktop size, without opening a window:
 *
 *   ./gradlew :desktopApp:renderScreens     → desktopApp/build/screens/
 */
fun main() {
    val out = File("build/screens").apply { mkdirs() }
    val sizes = mapOf("phone" to (393 to 852), "desktop" to (1100 to 820))

    fun render(name: String, setup: (AppViewModel) -> Unit) {
        for ((label, size) in sizes) {
            val dir = Files.createTempDirectory("widder").toFile()
            val vm = AppViewModel(Store(DirectoryStore(dir)), SilentAudio.system, live = false, main = Dispatchers.Unconfined, work = Dispatchers.Unconfined, random = kotlin.random.Random(1))
            setup(vm)
            val (w, h) = size
            ImageComposeScene(w * 2, h * 2, Density(2f)) { App(vm) }.use { scene ->
                repeat(5) { scene.render(it * 20_000_000L) }
                val png = scene.render(200_000_000L).encodeToData(EncodedImageFormat.PNG)!!.bytes
                File(out, "$name-$label.png").writeBytes(png)
            }
            vm.dispose()
            dir.deleteRecursively()
        }
    }

    fun AppViewModel.chordDeck() = decks(DeckKind.CHORDS).first().id
    fun AppViewModel.cardDeck() = decks(DeckKind.CARDS).first().id

    render("home") {}
    render("stapel-karten") { it.open(Screen.Decks(DeckKind.CARDS)) }
    render("stapel-akkorde") { it.open(Screen.Decks(DeckKind.CHORDS)) }
    render("uebungen") { it.open(Screen.Browse(it.chordDeck())) }
    render("abfrage") { it.startReview(it.cardDeck()) }
    render("abfrage-antwort") { vm ->
        vm.startReview(vm.cardDeck())
        vm.showAnswer()
    }
    render("training") { it.startTraining(it.chordDeck()) }
    render("training-halb") { vm ->
        vm.startTraining(vm.chordDeck())
        val round = vm.training!!.round!!
        vm.placeTile(round.pool.first { it.token.key == round.answer[0].key }, 0)
        vm.placeTile(round.pool.first { it.token.key == round.answer[1].key }, 1)
    }
    render("training-falsch") { vm ->
        vm.updateDeck(vm.deck(vm.chordDeck())!!.copy(distractors = 2))
        vm.startTraining(vm.chordDeck())
        val round = vm.training!!.round!!
        round.pool.reversed().forEach { if (round.inPool(it)) vm.tapTile(it) }
        vm.checkRound()
    }
    render("training-richtig") { vm ->
        vm.startTraining(vm.chordDeck())
        val round = vm.training!!.round!!
        round.answer.forEachIndexed { i, t -> vm.placeTile(round.pool.first { p -> p.token.key == t.key && round.inPool(p) }, i) }
        vm.checkRound()
    }
    render("editor-akkorde") { vm ->
        vm.editCard(vm.cardsIn(vm.chordDeck()).first())
    }
    render("editor-karte") { vm ->
        vm.editCard(vm.cardsIn(vm.cardDeck()).last())
    }
    render("audio-editor") { vm ->
        val card = vm.cardsIn(vm.chordDeck()).first()
        vm.startTraining(vm.chordDeck())
        vm.openAudioEditor(ClipTarget.StoredCard(card.id, back = false))
        val e = vm.audioEditor!!
        vm.updateEdit(e.edit.copy(trimStart = 0.4, trimEnd = e.duration - 0.8, gainDb = 4.0, reverbMix = 0.35, fadeOut = 0.5))
    }
    render("import-vorschau") { vm ->
        vm.open(Screen.Browse(vm.chordDeck()))
        val midi = Midi.write(MidiAnalysis.progression(AnswerParser.parse("Bb Gm Eb F.mid").mapNotNull { it.chord }))
        vm.receiveFiles(
            listOf(
                IncomingFile("A D G F.wav", ByteArray(10)),
                IncomingFile("Fmin Gmin (2).wav", ByteArray(10)),
                IncomingFile("Mein Stück.mid", midi),
            ),
        )
    }
    render("import-ziel") { vm -> vm.receiveFiles(listOf(IncomingFile("Cmaj7 Am7.wav", ByteArray(10))), deckId = null) }
    render("einstellungen") { it.open(Screen.Settings) }
    render("startkit") { vm ->
        vm.startTraining(widder.ui.StarterKits.A_CHORDS_ID)
    }
    render("startkit-folge") { vm ->
        vm.startTraining(widder.ui.StarterKits.A_CHORDS_ID)
        // Jump to the Amaj -> A/C# -> A/E sequence.
        val session = vm.training!!
        val card = vm.cardsIn(widder.ui.StarterKits.A_CHORDS_ID).first { it.answer == listOf("Amaj", "A/C#", "A/E") }
        session.card = card
        session.round = widder.core.ChordRound(vm.tokensOf(card), 2, candidates = vm.tokensOf(card).take(0) + widder.ui.StarterKits.A_CHORDS.map { widder.music.Token.of(it) })
        session.version++
        vm.placeTile(session.round!!.pool.first { it.token.text == "Amaj" }, 0)
    }
    render("editor-beschriftung") { vm ->
        vm.editCard(vm.cardsIn(widder.ui.StarterKits.A_CARDS_ID).first())
        vm.editor!!.label = "Son Clave 3-2"
    }
    render("karte-beschriftung") { vm ->
        val card = vm.cardsIn(widder.ui.StarterKits.A_CARDS_ID).first()
        vm.editCard(card)
        vm.editor!!.label = "Son Clave 3-2"
        vm.saveEditor()
        vm.home()
        vm.startReview(widder.ui.StarterKits.A_CARDS_ID)
        vm.showAnswer()
    }
    render("karte-stufen") { vm ->
        val id = widder.ui.StarterKits.DEGREES_ID + widder.ui.StarterKits.CARDS_SUFFIX
        vm.addStarterKit(id)
        vm.startReview(id)
        vm.showAnswer()
    }
    render("karte-akkord") { vm ->
        vm.startReview(widder.ui.StarterKits.A_CARDS_ID)
        vm.showAnswer()
    }
    render("editor-karte-akkorde") { vm ->
        vm.newCard(vm.cardDeck())
        vm.editor!!.front = "Typische Pop-Folge – welche Stufen?"
        vm.editor!!.answer = "Am F C G"
        vm.generateChordAudio()
    }
    fun AppViewModel.trainKit(kit: String, answer: List<String>) {
        addStarterKit(kit)
        startTraining(kit)
        val session = training!!
        val card = cardsIn(kit).first { it.answer == answer }
        session.card = card
        session.round = widder.core.ChordRound(
            tokensOf(card), deck(kit)!!.distractors, notation, kotlin.random.Random(3),
            deck(kit)!!.pool.map { widder.music.Token.of(it) },
        )
        session.version++
    }
    render("intervall") { it.trainKit(widder.ui.StarterKits.INTERVALS_TOGETHER_ID, listOf("übermäßige Undezime")) }
    render("intervall-falsch") { vm ->
        vm.trainKit(widder.ui.StarterKits.INTERVALS_APART_ID, listOf("kleine Terz"))
        val round = vm.training!!.round!!
        vm.tapTile(round.pool.first { it.token.key != round.answer[0].key })
        vm.checkRound()
    }
    render("akkordtypen") { it.trainKit(widder.ui.StarterKits.CHORD_TYPES_ID, listOf("Cmin7")) }
    render("stufen") { vm ->
        vm.trainKit(widder.ui.StarterKits.DEGREES_ID, listOf("C", "Am", "F", "G"))
        val round = vm.training!!.round!!
        vm.placeTile(round.pool.first { it.token.key == round.answer[0].key }, 0)
    }
    render("stufen-richtig") { vm ->
        vm.trainKit(widder.ui.StarterKits.DEGREES_ID, listOf("C", "F", "G", "C"))
        val round = vm.training!!.round!!
        round.answer.forEachIndexed { i, t -> vm.placeTile(round.pool.first { p -> p.token.key == t.key && round.inPool(p) }, i) }
        vm.checkRound()
    }
    render("dorisch") { vm ->
        vm.trainKit("startkit-d-dorisch", listOf("Dm", "C", "G", "Dm"))
        val round = vm.training!!.round!!
        vm.placeTile(round.pool.first { it.token.key == round.answer[0].key }, 0)
    }
    render("editor-bemerkungen") { vm ->
        vm.addStarterKit("startkit-stufen-a-moll")
        vm.editCard(vm.cardsIn("startkit-stufen-a-moll").first { it.answer == listOf("Am", "Dm", "E", "Am") })
    }
    render("stufen-liste") { vm ->
        vm.addStarterKit(widder.ui.StarterKits.DEGREES_ID)
        vm.open(Screen.Browse(widder.ui.StarterKits.DEGREES_ID))
    }
    render("stapel-optionen") { vm ->
        vm.addStarterKit(widder.ui.StarterKits.DEGREES_ID)
        vm.open(Screen.Decks(DeckKind.CHORDS))
        vm.deckDialog = widder.ui.DeckDialog.Options(widder.ui.StarterKits.DEGREES_ID)
    }
    render("kacheln") { it.open(Screen.Tiles) }
    render("kacheln-3x4") { vm ->
        vm.updateSettings { it.copy(tileColumns = 3, tileRows = 4) }
        vm.open(Screen.Tiles)
    }
    render("kachel-bearbeiten") { vm -> vm.open(Screen.EditTile(3)) }
    render("home-felder") { vm -> vm.updateSettings { it.copy(showTiles = true) } }
    render("home-geste") { vm ->
        vm.saveTile(1, widder.core.GestureTile("Bild", widder.core.TileColor.PURPLE, longPress = widder.core.Action(widder.core.ActionKind.SETTINGS)))
        vm.onTileGesture(1, long = true)
    }
    render("prefix") { vm -> vm.onShortcutKey(widder.core.KeyStroke("B", ctrl = true), null) }
    render("stapel-waehlen") { vm -> vm.run(widder.core.Action(widder.core.ActionKind.RECORD)) }
    render("kuerzel") { vm -> vm.shortcutEdit = widder.ui.ShortcutEdit('L') }
    println("Screens in ${out.absolutePath}")
    System.exit(0)
}

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
            val vm = AppViewModel(Store(DirectoryStore(dir)), SilentAudio.system, live = false, main = Dispatchers.Unconfined, work = Dispatchers.Unconfined)
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
    println("Screens in ${out.absolutePath}")
    System.exit(0)
}

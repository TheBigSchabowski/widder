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
import widder.core.PickKind
import widder.core.Store
import widder.music.AnswerParser
import widder.music.Midi
import widder.music.MidiAnalysis
import widder.ui.App
import widder.ui.AppViewModel
import widder.ui.ClipTarget
import widder.ui.PlatformHooks
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

    // As on the Mac: with a keyboard, so the deck numbers and shortcuts show.
    val keyboard = object : PlatformHooks {
        override fun pickFiles(kind: PickKind, onResult: (List<IncomingFile>) -> Unit) = onResult(emptyList())
        override val keyboard = true
    }

    // Devices as a Mac with an interface would list them.
    val devices = SilentAudio.system.let {
        widder.audio.AudioSystem(
            it.player, it.recorder, it.decoder,
            devices = object : widder.audio.AudioDevices {
                override fun inputs() = listOf("MacBook Air-Mikrofon", "Rubix44")
                override fun outputs() = listOf("MacBook Air-Lautsprecher", "Rubix44")
                override fun use(input: String?, output: String?, channel: Int) = Unit
            },
        )
    }

    fun render(name: String, keys: Boolean = false, audio: widder.audio.AudioSystem = SilentAudio.system, interact: (ImageComposeScene) -> Unit = {}, setup: (AppViewModel) -> Unit) {
        for ((label, size) in sizes) {
            val dir = Files.createTempDirectory("widder").toFile()
            val vm = AppViewModel(
                Store(DirectoryStore(dir)), audio, if (keys) keyboard else widder.ui.NoPlatform,
                live = false, main = Dispatchers.Unconfined, work = Dispatchers.Unconfined, random = kotlin.random.Random(1),
            )
            setup(vm)
            val (w, h) = size
            ImageComposeScene(w * 2, h * 2, Density(2f)) { App(vm) }.use { scene ->
                repeat(5) { scene.render(it * 20_000_000L) }
                interact(scene)
                repeat(5) { scene.render(100_000_000L + it * 20_000_000L) }
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
    render("ueben", keys = true) { it.open(Screen.Practice) }
    render("ueben-stimmgeraet") { vm ->
        vm.open(Screen.Practice)
        vm.updatePractice { it.copy(division = 24, tuning = 438.5) }
        vm.addTuning("Mein Maqam", "D2 A2 D3 G3 A3+50 D4")
        vm.updateVibrato { it.copy(width = 0) }
    }
    render("einstellungen-audio", audio = devices) { vm ->
        vm.open(Screen.Settings)
        vm.setAudioDevices("Rubix44", null, 1)
    }
    render("ueben-timer") { vm ->
        vm.open(Screen.Practice)
        vm.setStopAfter(10)
        vm.toggleDrums()
    }
    render("ueben-tanpura", interact = { scene ->
        repeat(60) {
            scene.sendPointerEvent(
                androidx.compose.ui.input.pointer.PointerEventType.Scroll,
                androidx.compose.ui.geometry.Offset(300f, 600f),
                scrollDelta = androidx.compose.ui.geometry.Offset(0f, 10f),
            )
        }
    }) { vm ->
        vm.open(Screen.Practice)
        vm.updatePractice { it.copy(sound = widder.core.DroneSound.TANPURA, meter = widder.core.Meter.SIX_EIGHT, groove = "6/8 Blues", kit = widder.core.DrumKit.CLICK) }
    }
    // A microphone that hands back two seconds of sound: enough for a loop.
    val looping = SilentAudio.system.let {
        widder.audio.AudioSystem(
            it.player,
            object : widder.audio.AudioRecorder {
                override var isRecording = false
                override fun start(): Boolean = true.also { isRecording = true }
                override fun level() = 0f
                override fun stop(): widder.audio.Pcm = widder.audio.Pcm(44_100, listOf(FloatArray(88_200) { kotlin.math.sin(it / 20.0).toFloat() * 0.3f }))
                    .also { isRecording = false }
            },
            it.decoder,
        )
    }
    for (dubbing in listOf(false, true)) {
        render(if (dubbing) "ueben-overdub" else "ueben-looper", audio = looping, interact = { scene ->
            repeat(if (dubbing) 24 else 27) {
                scene.sendPointerEvent(
                    androidx.compose.ui.input.pointer.PointerEventType.Scroll,
                    androidx.compose.ui.geometry.Offset(300f, 600f),
                    scrollDelta = androidx.compose.ui.geometry.Offset(0f, 10f),
                )
            }
        }) { vm ->
            vm.open(Screen.Practice)
            vm.startLoop()
            vm.setLoop()
            vm.loopTake!!.layers = 3
            vm.loopTake!!.undo.addLast(FloatArray(1))
            vm.loopTake!!.overdubbing = dubbing
        }
    }
    render("ueben-vibrato", interact = { scene ->
        repeat(60) {
            scene.sendPointerEvent(
                androidx.compose.ui.input.pointer.PointerEventType.Scroll,
                androidx.compose.ui.geometry.Offset(300f, 600f),
                scrollDelta = androidx.compose.ui.geometry.Offset(0f, 10f),
            )
        }
    }) { vm ->
        vm.open(Screen.Practice)
        vm.updateVibrato { it.copy(direction = widder.core.VibratoDirection.UP, width = 60, rate = 5.5f, vibratoFrom = 1f, points = widder.audio.VibratoLine.BEND.points()) }
        // An attempt an octave lower, a bit slow, around the note instead of above it and the bend too late.
        val sung = vm.practice.vibrato.copy(
            octave = 2, direction = widder.core.VibratoDirection.AROUND, rate = 4.6f, width = 75, sound = widder.core.VibratoSound.VOICE,
            points = widder.audio.VibratoLine.move(widder.audio.VibratoLine.BEND.points(), 1, 0.4f, 200f),
        )
        vm.tryVibrato(widder.audio.VibratoSynth.render(sung, 440.0, vibrato = true))
    }
    render("ueben-vibrato-malen") { vm ->
        vm.open(Screen.Practice)
        vm.updateVibrato {
            it.copy(
                width = 40, vibratoFrom = 1.5f,
                points = widder.audio.VibratoLine.SCOOP.points() + listOf(
                    widder.core.LinePoint(0.5f, 0f), widder.core.LinePoint(0.6f, 150f, widder.core.Bend.STEEP), widder.core.LinePoint(1f, -80f),
                ),
            )
        }
        vm.updatePractice { it.copy(division = 24) }
        vm.toggleVibratoDrawing()
        vm.pickLinePoint(3)
    }
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
        round.answer.take(2).forEachIndexed { i, t -> vm.placeTile(round.pool.first { it.token.key == t.key }, i) }
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
    val cardsKit = widder.ui.StarterKits.CHORDS_ID + widder.ui.StarterKits.CARDS_SUFFIX
    render("editor-beschriftung") { vm ->
        vm.editCard(vm.cardsIn(cardsKit).first())
        vm.editor!!.label = "Son Clave 3-2"
    }
    render("karte-beschriftung") { vm ->
        val card = vm.cardsIn(cardsKit).first()
        vm.editCard(card)
        vm.editor!!.label = "Son Clave 3-2"
        vm.saveEditor()
        vm.home()
        vm.startReview(cardsKit)
        vm.review!!.card = vm.cardsIn(cardsKit).first()
        vm.showAnswer()
    }
    render("karte-akkord") { vm ->
        // An inversion: the back names only the chord.
        vm.startReview(cardsKit)
        vm.review!!.card = vm.cardsIn(cardsKit).first { it.voicing.startsWith("Amaj7/Cis") }
        vm.showAnswer()
    }
    render("karte-klaviatur") { vm ->
        vm.startReview(cardsKit)
        vm.review!!.card = vm.cardsIn(cardsKit).first { it.voicing.startsWith("Amaj7/Cis") }
        vm.showAnswer()
        vm.keyboardOpen = true
    }
    render("training-klaviatur") { vm ->
        vm.startTraining(vm.chordDeck())
        val round = vm.training!!.round!!
        round.answer.forEachIndexed { i, t -> vm.placeTile(round.pool.first { p -> p.token.key == t.key && round.inPool(p) }, i) }
        vm.checkRound()
        vm.keyboardOpen = true
    }
    render("editor-umkehrung") { vm ->
        vm.editCard(vm.cardsIn(cardsKit).first { it.voicing.startsWith("Amaj7/Cis") })
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
    render("akkordarten") { it.trainKit(widder.ui.StarterKits.CHORDS_ID, listOf("Amaj7")) }
    render("akkordarten-zweiklang") { vm ->
        vm.trainKit(widder.ui.StarterKits.CHORDS_ID, listOf("Amin(no5)"))
        val round = vm.training!!.round!!
        vm.placeTile(round.pool.first { it.token.key == round.answer[0].key }, 0)
        vm.checkRound()
    }
    render("stapel-optionen") { vm ->
        vm.open(Screen.Decks(DeckKind.CHORDS))
        vm.deckDialog = widder.ui.DeckDialog.Options(widder.ui.StarterKits.CHORDS_ID)
    }
    render("stapel-nummern", keys = true) { vm -> vm.pickDeckNumber(3) }
    render("stapel-markieren", keys = true) { vm ->
        vm.pickDeckNumber(2, mark = true)
        vm.toggleMark(vm.numberedDecks()[3].id)
    }
    render("stapel-loeschen", keys = true) { vm ->
        vm.pickDeckNumber(2, mark = true)
        vm.toggleMark(vm.numberedDecks()[3].id)
        vm.deleteMarked()
    }
    // ⇧ + click on the second deck ticks it without "Markieren", and does not start learning.
    render("stapel-shift-klick", keys = true, interact = { scene ->
        val at = androidx.compose.ui.geometry.Offset(1100f, 560f)
        val shift = androidx.compose.ui.input.pointer.PointerKeyboardModifiers(isShiftPressed = true)
        scene.sendPointerEvent(androidx.compose.ui.input.pointer.PointerEventType.Press, at, keyboardModifiers = shift)
        scene.sendPointerEvent(androidx.compose.ui.input.pointer.PointerEventType.Release, at, keyboardModifiers = shift)
    }) { it.open(Screen.Decks(DeckKind.CARDS)) }
    render("hilfe", keys = true) { it.open(Screen.Help) }
    render("hilfe-handy") { it.open(Screen.Help) }
    render("kacheln") { it.open(Screen.Tiles) }
    render("kacheln-3x4") { vm ->
        vm.updateSettings { it.copy(tileColumns = 3, tileRows = 4) }
        vm.open(Screen.Tiles)
    }
    render("kachel-bearbeiten") { vm -> vm.open(Screen.EditTile(3)) }
    render("kacheln-lernen", interact = { scene ->
        repeat(30) {
            scene.sendPointerEvent(
                androidx.compose.ui.input.pointer.PointerEventType.Scroll,
                androidx.compose.ui.geometry.Offset(300f, 600f),
                scrollDelta = androidx.compose.ui.geometry.Offset(0f, 10f),
            )
        }
    }) { it.open(Screen.Tiles) }
    render("lernen-felder") { vm ->
        vm.updateSettings { it.copy(showTiles = true) }
        vm.startReview(cardsKit)
        vm.review!!.card = vm.cardsIn(cardsKit).first { it.voicing.startsWith("Amaj7/Cis") }
        vm.showAnswer()
        vm.onTileGesture(1, long = false)
    }
    render("home-felder") { vm -> vm.updateSettings { it.copy(showTiles = true) } }
    render("home-geste") { vm ->
        vm.saveTile(1, widder.core.GestureTile("Bild", widder.core.TileColor.PURPLE, longPress = widder.core.Action(widder.core.ActionKind.SETTINGS)))
        vm.onTileGesture(1, long = true)
    }
    render("prefix", keys = true) { vm -> vm.onShortcutKey(widder.core.KeyStroke("B", ctrl = true), null) }
    render("stapel-waehlen") { vm -> vm.run(widder.core.Action(widder.core.ActionKind.RECORD)) }
    render("kuerzel") { vm -> vm.shortcutEdit = widder.ui.ShortcutEdit('L') }
    println("Screens in ${out.absolutePath}")
    System.exit(0)
}

package widder.ui

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlin.coroutines.CoroutineContext
import widder.audio.AUDIO_EXTENSIONS
import widder.audio.AudioSystem
import widder.audio.Effects
import widder.audio.MIDI_EXTENSIONS
import widder.audio.Pcm
import widder.audio.RenderCache
import widder.audio.Synth
import widder.audio.Wav
import widder.core.AudioEdit
import widder.core.Card
import widder.core.ChordRound
import widder.core.Clip
import widder.core.Collection
import widder.core.Deck
import widder.core.DeckCounts
import widder.core.DeckKind
import widder.core.Grade
import widder.core.IncomingFile
import widder.core.PickKind
import widder.core.Progress
import widder.core.Queue
import widder.core.Reward
import widder.core.Settings
import widder.core.Store
import widder.core.answer
import widder.core.currentTimeMillis
import widder.core.formatTime
import widder.core.newId
import widder.music.AnswerParser
import widder.music.Chord
import widder.music.Midi
import widder.music.MidiAnalysis
import widder.music.Notation

class AppViewModel(
    private val store: Store,
    val audio: AudioSystem,
    val platform: PlatformHooks = NoPlatform,
    /** Whether the timer and audio run; off for the screenshot renderer. */
    private val live: Boolean = true,
    main: CoroutineContext = Dispatchers.Main,
    /** Where decoding and rendering run; the screenshot renderer passes Unconfined to stay synchronous. */
    private val work: CoroutineContext = Dispatchers.Default,
) {
    private val scope = CoroutineScope(SupervisorJob() + main)

    var collection by mutableStateOf(store.loadCollection() ?: DemoContent.create(store).also(store::saveCollection))
        private set
    var progress by mutableStateOf(store.loadProgress())
        private set

    var stack by mutableStateOf(listOf<Screen>(Screen.Home))
        private set
    val screen: Screen get() = stack.last()

    var message by mutableStateOf<String?>(null)
    var busy by mutableStateOf<String?>(null)
        private set
    var playingKey by mutableStateOf<String?>(null)
        private set

    var editor by mutableStateOf<CardEditor?>(null)
        private set
    var audioEditor by mutableStateOf<AudioEditorState?>(null)
        private set
    var recording by mutableStateOf<RecordingState?>(null)
        private set
    var review by mutableStateOf<ReviewSession?>(null)
        private set
    var training by mutableStateOf<TrainSession?>(null)
        private set
    var pendingImport by mutableStateOf<List<IncomingFile>?>(null)
        private set
    var importPreview by mutableStateOf<ImportPreview?>(null)
        private set
    var deckDialog by mutableStateOf<DeckDialog?>(null)
    var confirm by mutableStateOf<Confirm?>(null)
    var rewardImages by mutableStateOf<RewardImages?>(null)
        private set
    var showRewardOnly by mutableStateOf(false)

    /** Window focused / app in the foreground; only then does learning time count. */
    var foreground = true

    private val sources = RenderCache(6)
    private val rendered = RenderCache(10)
    private val cacheLock = Mutex()
    private var unsavedSeconds = 0
    private var messageJob: Job? = null

    val settings: Settings get() = collection.settings
    val notation: Notation get() = Notation(german = settings.germanNotation, minorAsMin = settings.minorAsMin)

    init {
        loadRewardImage()
        if (live) {
            scope.launch {
                while (true) {
                    delay(1000)
                    tickSecond()
                }
            }
        }
    }

    fun dispose() {
        flushStudyTime()
        audio.player.stop()
        scope.cancel()
    }

    // region navigation

    fun open(screen: Screen) {
        stack = stack + screen
    }

    /** Back button / Escape: closes the innermost thing that is open. Returns false on the start screen. */
    fun back(): Boolean {
        when {
            recording != null -> stopRecording(keep = false)
            audioEditor != null -> closeAudioEditor()
            importPreview != null -> importPreview = null
            pendingImport != null -> pendingImport = null
            showRewardOnly -> showRewardOnly = false
            screen == Screen.EditCard -> cancelEditor()
            stack.size > 1 -> leave()
            else -> return false
        }
        return true
    }

    private fun leave() {
        val leaving = screen
        audio.player.stop()
        playingKey = null
        if (leaving.isStudy) flushStudyTime()
        if (leaving is Screen.Review) review = null
        if (leaving is Screen.Train) training = null
        if (leaving is Screen.EditCard) editor = null
        stack = stack.dropLast(1)
    }

    fun home() {
        while (stack.size > 1) leave()
    }

    // endregion

    // region decks

    fun decks(kind: DeckKind): List<Deck> = collection.decks.filter { it.kind == kind }.sortedBy { it.name.lowercase() }

    fun deck(id: String): Deck? = collection.decks.firstOrNull { it.id == id }

    fun cardsIn(deckId: String): List<Card> = collection.cards.filter { it.deckId == deckId }

    private fun studyCards(deck: Deck): List<Card> =
        if (deck.kind == DeckKind.CHORDS) collection.cards.filter { it.deckId == deck.id && it.answer.isNotEmpty() && it.frontAudio != null } else collection.cards

    fun counts(deck: Deck): DeckCounts = Queue(deck, studyCards(deck), progress).counts

    fun dueTotal(kind: DeckKind): Int = decks(kind).sumOf { counts(it).total }

    fun createDeck(name: String, kind: DeckKind): Deck {
        val deck = Deck(newId(), name.trim().ifEmpty { if (kind == DeckKind.CARDS) "Neuer Stapel" else "Neue Übungen" }, kind, created = currentTimeMillis())
        saveCollection(collection.copy(decks = collection.decks + deck))
        return deck
    }

    fun updateDeck(deck: Deck) {
        saveCollection(collection.copy(decks = collection.decks.map { if (it.id == deck.id) deck else it }))
    }

    fun hasStarterKit(): Boolean = collection.decks.any { it.id == StarterKits.A_CHORDS_ID }

    fun addStarterKit() {
        if (hasStarterKit()) return
        val (deck, cards) = StarterKits.aChords(store)
        saveCollection(collection.copy(decks = collection.decks + deck, cards = collection.cards + cards))
        toast("Startkit „${deck.name}“ angelegt")
    }

    fun deleteDeck(deckId: String) {
        val cards = cardsIn(deckId)
        cards.forEach { deleteClipFiles(it) }
        saveCollection(collection.copy(decks = collection.decks.filter { it.id != deckId }, cards = collection.cards.filter { it.deckId != deckId }))
        saveProgress(progress.copy(cards = progress.cards - cards.map { it.id }.toSet()))
    }

    // endregion

    // region cards

    fun newCard(deckId: String) {
        val deck = deck(deckId) ?: return
        editor = CardEditor(null, deckId, deck.kind)
        open(Screen.EditCard)
    }

    fun editCard(card: Card) {
        val deck = deck(card.deckId) ?: return
        audio.player.stop()
        editor = CardEditor(card, card.deckId, deck.kind)
        open(Screen.EditCard)
    }

    /** Parsed answer of the editor, for the preview chips. */
    fun editorTokens(): List<widder.music.Token> {
        val e = editor ?: return emptyList()
        return AnswerParser.parseText(e.answer, settings.germanNotation)
    }

    fun saveEditor(keepOpen: Boolean = false) {
        val e = editor ?: return
        val now = currentTimeMillis()
        val card = (e.original ?: Card(newId(), e.deckId, created = now)).copy(
            front = e.front.trim(),
            back = e.back.trim(),
            frontAudio = e.frontAudio,
            backAudio = e.backAudio,
            answer = if (e.kind == DeckKind.CHORDS) e.answer.split(Regex("[\\s,]+")).filter { it.isNotBlank() } else e.original?.answer ?: emptyList(),
            tags = e.tags.split(Regex("\\s+")).filter { it.isNotBlank() },
            modified = now,
        )
        if (card.front.isEmpty() && card.frontAudio == null && card.answer.isEmpty()) {
            toast("Die Vorderseite ist leer")
            return
        }
        // Clips that were replaced or removed in the editor go from the media folder.
        e.original?.let { old ->
            listOfNotNull(old.frontAudio, old.backAudio).filter { it.file != card.frontAudio?.file && it.file != card.backAudio?.file }
                .forEach { store.deleteMedia(it.file) }
        }
        val exists = collection.cards.any { it.id == card.id }
        saveCollection(collection.copy(cards = if (exists) collection.cards.map { if (it.id == card.id) card else it } else collection.cards + card))
        refreshSessionsFor(card)
        if (keepOpen) {
            editor = CardEditor(null, e.deckId, e.kind)
            toast("Karte gespeichert")
        } else {
            editor = null
            leave()
        }
    }

    fun cancelEditor() {
        val e = editor ?: return
        // Recordings made in a card that is thrown away are thrown away too.
        listOfNotNull(e.frontAudio, e.backAudio)
            .filter { clip -> e.original?.let { it.frontAudio?.file == clip.file || it.backAudio?.file == clip.file } != true }
            .forEach { store.deleteMedia(it.file) }
        editor = null
        leave()
    }

    fun deleteCard(cardId: String) {
        val card = collection.cards.firstOrNull { it.id == cardId } ?: return
        deleteClipFiles(card)
        saveCollection(collection.copy(cards = collection.cards.filter { it.id != cardId }))
        saveProgress(progress.copy(cards = progress.cards - cardId))
    }

    fun toggleSuspend(cardId: String) {
        val state = progress.cards[cardId] ?: widder.core.ReviewState()
        saveProgress(progress.copy(cards = progress.cards + (cardId to state.copy(suspended = !state.suspended))))
    }

    fun isSuspended(cardId: String) = progress.cards[cardId]?.suspended == true

    private fun deleteClipFiles(card: Card) {
        listOfNotNull(card.frontAudio, card.backAudio).forEach { store.deleteMedia(it.file) }
    }

    private fun replaceCard(card: Card) {
        saveCollection(collection.copy(cards = collection.cards.map { if (it.id == card.id) card else it }))
        refreshSessionsFor(card)
    }

    private fun refreshSessionsFor(card: Card) {
        review?.let { if (it.card?.id == card.id) it.card = card }
        training?.let { if (it.card?.id == card.id) it.card = card }
    }

    // endregion

    // region audio

    private suspend fun sourcePcm(clip: Clip): Pcm? = withContext(work) {
        cacheLock.withLock { sources.get(clip.file) }?.let { return@withContext it }
        val bytes = store.readMedia(clip.file) ?: return@withContext null
        val ext = clip.file.substringAfterLast('.').lowercase()
        val pcm = try {
            when {
                ext in MIDI_EXTENSIONS -> Synth.render(Midi.parse(bytes))
                Wav.isWav(bytes) -> Wav.read(bytes)
                else -> audio.decoder.decode(bytes, ext)
            }
        } catch (e: Exception) {
            null
        }
        pcm?.also { cacheLock.withLock { sources.put(clip.file, it) } }
    }

    private suspend fun renderedPcm(clip: Clip): Pcm? {
        val key = clip.file + "|" + clip.edit.hashCode()
        cacheLock.withLock { rendered.get(key) }?.let { return it }
        val source = sourcePcm(clip) ?: return null
        val pcm = withContext(work) { Effects.render(source, clip.edit) }
        cacheLock.withLock { rendered.put(key, pcm) }
        return pcm
    }

    fun play(clip: Clip?, key: String) {
        if (clip == null || !live) return
        if (playingKey == key && audio.player.isPlaying) {
            stopAudio()
            return
        }
        playingKey = key
        scope.launch {
            val pcm = renderedPcm(clip)
            if (pcm == null) {
                playingKey = null
                toast("„${clip.name}“ lässt sich nicht abspielen")
                return@launch
            }
            if (playingKey != key) return@launch
            audio.player.play(pcm) { scope.launch { if (playingKey == key) playingKey = null } }
        }
    }

    fun stopAudio() {
        audio.player.stop()
        playingKey = null
        audioEditor?.playing = false
    }

    // endregion

    // region recording and importing audio into a card

    private fun clipOf(target: ClipTarget): Clip? = when (target) {
        is ClipTarget.Editor -> editor?.clip(target.back)
        is ClipTarget.StoredCard -> collection.cards.firstOrNull { it.id == target.cardId }?.let { if (target.back) it.backAudio else it.frontAudio }
    }

    private fun setClip(target: ClipTarget, clip: Clip?) {
        when (target) {
            is ClipTarget.Editor -> editor?.setClip(target.back, clip)
            is ClipTarget.StoredCard -> {
                val card = collection.cards.firstOrNull { it.id == target.cardId } ?: return
                replaceCard(if (target.back) card.copy(backAudio = clip, modified = currentTimeMillis()) else card.copy(frontAudio = clip, modified = currentTimeMillis()))
            }
        }
    }

    fun startRecording(target: ClipTarget) {
        audio.player.stop()
        platform.requestMicrophone { granted ->
            if (!granted) {
                toast("Ohne Mikrofon-Erlaubnis kann Widder nicht aufnehmen")
                return@requestMicrophone
            }
            if (audio.recorder.start()) {
                recording = RecordingState(target).also { it.started = currentTimeMillis() }
            } else {
                toast("Kein Mikrofon gefunden")
            }
        }
    }

    fun stopRecording(keep: Boolean = true) {
        val state = recording ?: return
        recording = null
        val pcm = audio.recorder.stop()
        if (!keep || pcm == null || pcm.frames < pcm.sampleRate / 10) return
        val name = "Aufnahme ${formatTime(currentTimeMillis(), "dd.MM. HH-mm-ss")}.wav"
        val file = store.addMedia(name, Wav.write(pcm))
        val clip = Clip(file, name)
        clipOf(state.target)?.takeIf { state.target is ClipTarget.StoredCard }?.let { store.deleteMedia(it.file) }
        setClip(state.target, clip)
        // Straight into the editor: a fresh recording nearly always needs its ends cut.
        openAudioEditor(state.target)
    }

    fun pickAudio(target: ClipTarget) {
        platform.pickFiles(PickKind.AUDIO) { files ->
            val file = files.firstOrNull { it.extension in AUDIO_EXTENSIONS || it.extension in MIDI_EXTENSIONS } ?: return@pickFiles
            val clip = Clip(store.addMedia(file.name, file.bytes), file.name)
            setClip(target, clip)
            val e = editor
            if (e != null && target is ClipTarget.Editor && e.kind == DeckKind.CHORDS && e.answer.isBlank()) {
                e.answer = answerFor(file).joinToString(" ")
            }
        }
    }

    fun removeClip(target: ClipTarget) = setClip(target, null)

    // endregion

    // region audio editor

    fun openAudioEditor(target: ClipTarget) {
        val clip = clipOf(target) ?: return
        audio.player.stop()
        playingKey = null
        busy = "Audio wird geladen …"
        scope.launch {
            val source = sourcePcm(clip)
            busy = null
            if (source == null) {
                toast("„${clip.name}“ lässt sich nicht öffnen")
            } else {
                audioEditor = AudioEditorState(target, clip, source)
            }
        }
    }

    fun updateEdit(edit: AudioEdit) {
        val state = audioEditor ?: return
        val end = edit.trimEnd?.coerceIn(0.0, state.duration)
        val start = edit.trimStart.coerceIn(0.0, (end ?: state.duration) - 0.02)
        state.edit = edit.copy(trimStart = start.coerceAtLeast(0.0), trimEnd = end?.takeIf { it < state.duration - 0.005 })
    }

    fun previewAudio(original: Boolean = false) {
        val state = audioEditor ?: return
        if (state.playing) {
            stopAudio()
            return
        }
        state.playing = true
        state.playsOriginal = original
        state.playOffset = state.edit.trimStart
        scope.launch {
            val pcm = if (original) {
                state.source.slice(state.source.frameAt(state.edit.trimStart), state.source.frameAt(state.selectionEnd))
            } else {
                withContext(work) { Effects.render(state.source, state.edit) }
            }
            if (!state.playing || !live) {
                state.playing = false
                return@launch
            }
            audio.player.play(pcm) { scope.launch { state.playing = false } }
        }
    }

    /** Seconds into the source that is audible right now, for the play head. */
    fun audioEditorPlayhead(): Double? {
        val state = audioEditor ?: return null
        if (!state.playing) return null
        return (state.playOffset + audio.player.position()).coerceAtMost(state.selectionEnd)
    }

    fun saveAudioEditor() {
        val state = audioEditor ?: return
        audio.player.stop()
        setClip(state.target, state.clip.copy(edit = state.edit))
        audioEditor = null
        toast("Audio gespeichert")
    }

    fun closeAudioEditor() {
        audio.player.stop()
        audioEditor = null
    }

    // endregion

    // region review (flash cards)

    fun startReview(deckId: String) {
        review = ReviewSession(deckId)
        open(Screen.Review(deckId))
        nextReviewCard()
    }

    private fun nextReviewCard(skip: String? = null) {
        val session = review ?: return
        val deck = deck(session.deckId) ?: return
        val queue = Queue(deck, collection.cards, progress)
        val card = queue.next(skip)
        session.card = card
        session.showAnswer = false
        session.shownAt = currentTimeMillis()
        session.nextDue = if (card == null) queue.nextLearningDue() else null
        if (card != null && settings.autoplay) play(card.frontAudio, "review-front")
    }

    fun showAnswer() {
        val session = review ?: return
        val card = session.card ?: return
        if (session.showAnswer) return
        session.showAnswer = true
        if (settings.autoplay && card.backAudio != null) play(card.backAudio, "review-back")
    }

    fun answer(grade: Grade) {
        val session = review ?: return
        val card = session.card ?: return
        if (!session.showAnswer) return
        audio.player.stop()
        playingKey = null
        session.undo = card to progress
        val xp = when (grade) {
            Grade.AGAIN -> 2
            Grade.HARD -> 5
            Grade.GOOD -> 8
            Grade.EASY -> 10
        }
        saveProgress(progress.answer(card, grade, xp))
        session.answered++
        nextReviewCard(skip = card.id)
    }

    fun undoReview() {
        val session = review ?: return
        val (card, before) = session.undo ?: return
        saveProgress(before)
        session.undo = null
        session.answered = (session.answered - 1).coerceAtLeast(0)
        session.card = collection.cards.firstOrNull { it.id == card.id } ?: card
        session.showAnswer = false
        session.nextDue = null
    }

    fun suspendReviewCard() {
        val session = review ?: return
        val card = session.card ?: return
        toggleSuspend(card.id)
        toast("Karte ausgesetzt – im Kartenbrowser wieder aktivieren")
        nextReviewCard(skip = card.id)
    }

    // endregion

    // region chord training

    fun startTraining(deckId: String) {
        training = TrainSession(deckId)
        open(Screen.Train(deckId))
        nextExercise()
    }

    private fun nextExercise(skip: String? = null) {
        val session = training ?: return
        val deck = deck(session.deckId) ?: return
        val queue = Queue(deck, studyCards(deck), progress)
        val card = queue.next(skip)
        session.card = card
        session.suggested = null
        session.nextDue = if (card == null) queue.nextLearningDue() else null
        session.round = card?.let {
            ChordRound(
                it.answer.map { t -> widder.music.Token.of(t, settings.germanNotation) },
                deck.distractors,
                notation,
                candidates = deck.pool.map { t -> widder.music.Token.of(t, settings.germanNotation) },
            )
        }
        session.startedAt = currentTimeMillis()
        session.version++
        if (card != null) play(card.frontAudio, "train")
    }

    private fun moved() {
        training?.let { it.version++ }
    }

    fun placeTile(tile: widder.core.Tile, slot: Int) {
        training?.round?.place(tile, slot)
        moved()
    }

    fun tapTile(tile: widder.core.Tile) {
        val round = training?.round ?: return
        val slot = round.slotOf(tile)
        if (slot != null) round.remove(slot) else round.placeNext(tile)
        moved()
    }

    fun returnTile(tile: widder.core.Tile) {
        val round = training?.round ?: return
        round.slotOf(tile)?.let { round.remove(it) }
        moved()
    }

    fun checkRound() {
        val session = training ?: return
        val round = session.round ?: return
        if (!round.complete || round.checked) return
        round.check()
        session.suggested = round.suggestedGrade((currentTimeMillis() - session.startedAt) / 1000.0)
        moved()
    }

    fun gradeRound(grade: Grade? = null) {
        val session = training ?: return
        val card = session.card ?: return
        val round = session.round ?: return
        if (!round.checked) return
        val g = grade ?: session.suggested ?: Grade.GOOD
        session.combo = if (round.correct) session.combo + 1 else 0
        val xp = if (round.correct) 10 + 2 * round.answer.size + 2 * minOf(session.combo, 5) else 3
        session.gained += xp
        session.answered++
        if (round.correct) session.correct++
        audio.player.stop()
        playingKey = null
        saveProgress(progress.answer(card, g, xp))
        nextExercise(skip = card.id)
    }

    fun replay() {
        training?.card?.let { play(it.frontAudio, "train") }
        val session = review ?: return
        val card = session.card ?: return
        if (session.showAnswer && card.backAudio != null) play(card.backAudio, "review-back") else play(card.frontAudio, "review-front")
    }

    // endregion

    // region import

    /** Files picked, dropped onto the window or shared with the app. Without a deck the user chooses one. */
    fun receiveFiles(files: List<IncomingFile>, deckId: String? = currentDeckId()) {
        val usable = files.filter { it.extension in AUDIO_EXTENSIONS || it.extension in MIDI_EXTENSIONS }
        if (usable.isEmpty()) {
            if (files.isNotEmpty()) toast("Nur Audio- (WAV, MP3, M4A …) und MIDI-Dateien lassen sich importieren")
            return
        }
        if (deckId == null) {
            pendingImport = usable
            return
        }
        importInto(deckId, usable)
    }

    fun pickImport(deckId: String?) {
        platform.pickFiles(PickKind.AUDIO) { receiveFiles(it, deckId) }
    }

    private fun currentDeckId(): String? = when (val s = screen) {
        is Screen.Browse -> s.deckId
        is Screen.Review -> s.deckId
        is Screen.Train -> s.deckId
        else -> null
    }

    fun chooseImportTarget(deckId: String?, newKind: DeckKind? = null) {
        val files = pendingImport ?: return
        pendingImport = null
        val id = deckId ?: createDeck(files.first().name.substringBeforeLast('.').take(30).let { if (files.size > 1) "Import" else it }, newKind ?: DeckKind.CARDS).id
        importInto(id, files)
    }

    private fun answerFor(file: IncomingFile): List<String> {
        val tokens = AnswerParser.parse(file.name, settings.germanNotation)
        if (tokens.isNotEmpty() && tokens.any { it.chord != null }) return tokens.map { it.text }
        if (file.extension in MIDI_EXTENSIONS) {
            val chords = runCatching { MidiAnalysis.chords(Midi.parse(file.bytes)) }.getOrDefault(emptyList())
            if (chords.isNotEmpty()) return chords.map { it.name(notation) }
        }
        return tokens.map { it.text }
    }

    private fun importInto(deckId: String, files: List<IncomingFile>) {
        val deck = deck(deckId) ?: return
        if (deck.kind == DeckKind.CHORDS) {
            importPreview = ImportPreview(
                deckId,
                files.map { f ->
                    val fromName = AnswerParser.parse(f.name, settings.germanNotation)
                    val answer = answerFor(f)
                    ImportPreview.Item(f, answer.joinToString(" "), fromMidi = fromName.none { it.chord != null } && answer.isNotEmpty())
                },
            )
            return
        }
        val now = currentTimeMillis()
        val cards = files.mapIndexed { i, f ->
            val clip = Clip(store.addMedia(f.name, f.bytes), f.name)
            val answer = answerFor(f)
            val chordsFound = answer.isNotEmpty() && answer.all { Chord.parse(it, settings.germanNotation) != null }
            val midiNote = if (f.extension in MIDI_EXTENSIONS) "MIDI" else "Audio"
            Card(
                newId(),
                deckId,
                front = if (chordsFound) "Welche Akkorde hörst du?" else "Was hörst du?",
                back = (if (chordsFound) answer.joinToString(" – ") + "\n\n" else "") + "$midiNote: ${f.name.substringBeforeLast('.')}",
                frontAudio = clip,
                answer = answer,
                created = now + i,
                modified = now,
            )
        }
        saveCollection(collection.copy(cards = collection.cards + cards))
        toast(if (cards.size == 1) "1 Karte angelegt" else "${cards.size} Karten angelegt")
        if (screen !is Screen.Browse) open(Screen.Browse(deckId))
    }

    fun confirmImportPreview() {
        val preview = importPreview ?: return
        val now = currentTimeMillis()
        val cards = preview.items.filter { it.include && it.answer.isNotBlank() }.mapIndexed { i, item ->
            Card(
                newId(),
                preview.deckId,
                front = item.file.name.substringBeforeLast('.'),
                frontAudio = Clip(store.addMedia(item.file.name, item.file.bytes), item.file.name),
                answer = item.answer.split(Regex("[\\s,]+")).filter { it.isNotBlank() },
                created = now + i,
                modified = now,
            )
        }
        importPreview = null
        saveCollection(collection.copy(cards = collection.cards + cards))
        toast(if (cards.size == 1) "1 Übung angelegt" else "${cards.size} Übungen angelegt")
        if (screen !is Screen.Browse) open(Screen.Browse(preview.deckId))
    }

    // endregion

    // region settings and reward

    fun updateSettings(transform: (Settings) -> Settings) {
        val before = settings.reward
        saveCollection(collection.copy(settings = transform(settings)))
        if (before.image != settings.reward.image || before.blur != settings.reward.blur) loadRewardImage()
        if (before.columns != settings.reward.columns || before.rows != settings.reward.rows) {
            saveProgress(progress.copy(reward = Reward.current(progress.reward.copy(order = emptyList()), settings.reward)))
        }
    }

    fun pickRewardImage() {
        platform.pickFiles(PickKind.IMAGE) { files ->
            val file = files.firstOrNull() ?: return@pickFiles
            if (decodeImage(file.bytes) == null) {
                toast("Das Bild lässt sich nicht öffnen")
                return@pickFiles
            }
            settings.reward.image?.let { store.deleteMedia(it) }
            val name = store.addMedia("belohnung.${file.extension.ifEmpty { "jpg" }}", file.bytes)
            updateSettings { it.copy(reward = it.reward.copy(image = name, enabled = true)) }
            saveProgress(progress.copy(reward = Reward.reset(progress.reward)))
        }
    }

    fun removeRewardImage() {
        settings.reward.image?.let { store.deleteMedia(it) }
        updateSettings { it.copy(reward = it.reward.copy(image = null)) }
    }

    fun resetReward() = saveProgress(progress.copy(reward = Reward.reset(progress.reward)))

    fun rewardProgress() = Reward.current(progress.reward, settings.reward)

    private fun loadRewardImage() {
        val name = settings.reward.image
        if (name == null) {
            rewardImages = null
            return
        }
        val blur = settings.reward.blur
        scope.launch {
            rewardImages = withContext(work) {
                val bytes = store.readMedia(name) ?: return@withContext null
                val sharp = decodeImage(bytes) ?: return@withContext null
                RewardImages(sharp, Blur.blurred(sharp, blur))
            }
        }
    }

    private fun tickSecond() {
        if (!foreground || !screen.isStudy) return
        unsavedSeconds++
        if (unsavedSeconds >= 15) flushStudyTime()
    }

    private fun flushStudyTime() {
        if (unsavedSeconds == 0) return
        val seconds = unsavedSeconds
        unsavedSeconds = 0
        val day = Store.learningDay()
        val stats = progress.days[day] ?: widder.core.DayStats()
        saveProgress(
            progress.copy(
                reward = Reward.addSeconds(progress.reward, settings.reward, seconds),
                days = progress.days + (day to stats.copy(studySeconds = stats.studySeconds + seconds)),
            ),
        )
    }

    /** App went to the background / window lost focus. */
    fun onPause() {
        foreground = false
        flushStudyTime()
        if (recording != null) stopRecording(keep = true)
        stopAudio()
    }

    fun onResume() {
        foreground = true
    }

    // endregion

    // region keys (desktop)

    fun onKey(key: KeyCommand): Boolean {
        if (key == KeyCommand.ESCAPE) return back()
        if (audioEditor != null) {
            if (key != KeyCommand.SPACE) return false
            previewAudio()
            return true
        }
        review?.takeIf { screen is Screen.Review }?.let { session ->
            when (key) {
                KeyCommand.SPACE, KeyCommand.ENTER -> if (session.showAnswer) answer(Grade.GOOD) else showAnswer()
                KeyCommand.ONE -> answer(Grade.AGAIN)
                KeyCommand.TWO -> answer(Grade.HARD)
                KeyCommand.THREE -> answer(Grade.GOOD)
                KeyCommand.FOUR -> answer(Grade.EASY)
                KeyCommand.UNDO -> undoReview()
                KeyCommand.REPLAY -> replay()
                KeyCommand.EDIT -> session.card?.let { editCard(it) }
                else -> return false
            }
            return true
        }
        training?.takeIf { screen is Screen.Train }?.let { session ->
            val round = session.round ?: return false
            when (key) {
                KeyCommand.SPACE, KeyCommand.REPLAY -> replay()
                KeyCommand.ENTER -> if (round.checked) gradeRound() else checkRound()
                KeyCommand.BACKSPACE -> round.removeLast().also { moved() }
                KeyCommand.ONE -> if (round.checked) gradeRound(Grade.AGAIN) else return false
                KeyCommand.TWO -> if (round.checked) gradeRound(Grade.HARD) else return false
                KeyCommand.THREE -> if (round.checked) gradeRound(Grade.GOOD) else return false
                KeyCommand.FOUR -> if (round.checked) gradeRound(Grade.EASY) else return false
                KeyCommand.EDIT -> session.card?.let { openAudioEditor(ClipTarget.StoredCard(it.id, back = false)) }
                else -> return false
            }
            return true
        }
        return false
    }

    // endregion

    fun toast(text: String) {
        message = text
        messageJob?.cancel()
        if (live) {
            messageJob = scope.launch {
                delay(3500)
                message = null
            }
        }
    }

    val dataLocation: String get() = store.location

    private fun saveCollection(c: Collection) {
        collection = c
        store.saveCollection(c)
    }

    private fun saveProgress(p: Progress) {
        progress = p
        store.saveProgress(p)
    }
}

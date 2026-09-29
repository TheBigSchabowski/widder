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
import widder.core.NumeralMode
import widder.music.AnswerParser
import widder.music.Chord
import widder.music.Harmony
import widder.music.Key
import widder.music.Token
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
    private val folderLock = Mutex()
    private var unsavedSeconds = 0
    private var messageJob: Job? = null

    val settings: Settings get() = collection.settings
    val notation: Notation get() = Notation(german = settings.germanNotation, minorAsMin = settings.minorAsMin)

    init {
        loadRewardImage()
        if (live) {
            refreshFolders()
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

    fun hasStarterKit(kind: DeckKind): Boolean =
        collection.decks.any { it.id == if (kind == DeckKind.CHORDS) StarterKits.A_CHORDS_ID else StarterKits.A_CARDS_ID }

    fun addStarterKit(kind: DeckKind) {
        if (hasStarterKit(kind)) return
        val (deck, cards) = if (kind == DeckKind.CHORDS) StarterKits.aChords(store) else StarterKits.aChordCards(store)
        saveCollection(collection.copy(decks = collection.decks + deck, cards = collection.cards + cards))
        toast("Startkit „${deck.name}“ angelegt")
        scope.launch { linkKitFolder(deck.id) }
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

    /** Parsed chords of the editor, for the preview chips. */
    fun editorTokens(): List<Token> {
        val e = editor ?: return emptyList()
        return AnswerParser.parseText(e.answer, settings.germanNotation)
    }

    /** The key of the card being edited: typed in, from the deck, or recognised. */
    fun editorKey(): Key? {
        val e = editor ?: return null
        return resolveKey(e.key.ifBlank { null } ?: deck(e.deckId)?.key, editorTokens())
    }

    /** Turns the chords typed into the editor into audio (built-in synth) on the front. */
    fun generateChordAudio() {
        val e = editor ?: return
        val chords = editorTokens().map { it.chord }
        if (chords.isEmpty() || chords.any { it == null }) {
            toast("Nur erkannte Akkorde lassen sich abspielen")
            return
        }
        e.frontAudio?.takeIf { clip -> e.original?.let { it.frontAudio?.file == clip.file || it.backAudio?.file == clip.file } != true }
            ?.let { store.deleteMedia(it.file) }
        val name = editorTokens().joinToString(" ") { it.text }
        // A single chord without its root doubled in the bass, so inversions can be heard.
        val bytes = Midi.write(MidiAnalysis.progression(chords.filterNotNull(), 1.6, rootBass = chords.size > 1), bpm = 100)
        e.frontAudio = Clip(store.addMedia("$name.mid", bytes), "$name.mid")
    }

    // endregion

    // region roman numerals

    private fun resolveKey(explicit: String?, tokens: List<Token>): Key? =
        explicit?.let { Key.parse(it, settings.germanNotation) } ?: Harmony.detectKey(tokens.mapNotNull { it.chord })

    fun tokensOf(card: Card): List<Token> = card.answer.map { Token.of(it, settings.germanNotation) }

    /** Key for a card's numerals: the card's own, the deck's, or recognised from its chords. */
    fun keyFor(card: Card): Key? = resolveKey(card.key ?: deck(card.deckId)?.key, tokensOf(card))

    /** "I (A)", "I" or "A", depending on the setting; plain text for tokens that are no chord. */
    fun chordLabel(token: Token, key: Key?): String {
        val numeral = numeral(token, key) ?: return token.text
        return when (settings.numerals) {
            NumeralMode.OFF -> token.text
            NumeralMode.WITH_NAME -> "$numeral (${token.text})"
            NumeralMode.ONLY -> numeral
        }
    }

    fun numeral(token: Token, key: Key?): String? {
        if (settings.numerals == NumeralMode.OFF) return null
        val chord = token.chord ?: return null
        return Harmony.numeral(chord, key ?: return null)
    }

    fun keyName(key: Key?): String? = key?.name(notation)

    fun saveEditor(keepOpen: Boolean = false) {
        val e = editor ?: return
        val now = currentTimeMillis()
        val card = (e.original ?: Card(newId(), e.deckId, created = now)).copy(
            front = e.front.trim(),
            back = e.back.trim(),
            frontAudio = e.frontAudio,
            backAudio = e.backAudio,
            answer = e.answer.split(Regex("[\\s,]+")).filter { it.isNotBlank() },
            key = e.key.trim().ifEmpty { null },
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

    /** Tests: records one good answer for [card]. */
    internal fun saveProgressForTest(card: Card) = saveProgress(progress.answer(card, Grade.GOOD, 0))

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
                tokensOf(it),
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
        val cards = files.mapIndexed { i, f -> cardFromFile(deck, f, Clip(store.addMedia(f.name, f.bytes), f.name), now + i) }
        saveCollection(collection.copy(cards = collection.cards + cards))
        toast(if (cards.size == 1) "1 Karte angelegt" else "${cards.size} Karten angelegt")
        if (screen !is Screen.Browse) open(Screen.Browse(deckId))
    }

    /** A card for an imported or folder file: its name gives the chords, a MIDI file's notes if not. */
    private fun cardFromFile(deck: Deck, file: IncomingFile, clip: Clip, created: Long): Card {
        val answer = answerFor(file)
        val title = file.name.substringBeforeLast('.').replace(AnswerParser.FILE_SLASH, '/')
        if (deck.kind == DeckKind.CHORDS) {
            return Card(newId(), deck.id, front = title, frontAudio = clip, answer = answer, created = created, modified = created)
        }
        val chordsFound = answer.isNotEmpty() && answer.all { Chord.parse(it, settings.germanNotation) != null }
        return Card(
            newId(),
            deck.id,
            front = when {
                !chordsFound -> "Was hörst du?"
                answer.size == 1 -> "Welcher Akkord ist das?"
                else -> "Welche Akkorde hörst du?"
            },
            // The chords themselves are shown on the back, with their numerals.
            back = if (chordsFound) "" else "Datei: $title",
            frontAudio = clip,
            answer = if (chordsFound) answer else emptyList(),
            created = created,
            modified = created,
        )
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

    // region folders

    val folders: widder.core.FolderAccess? get() = platform.folders

    fun folderLabel(deck: Deck): String? = deck.folder?.let { folders?.describe(it) ?: it }

    /** "Ordner": opens the linked folder; links one first if there is none (the Mac creates it). */
    fun openFolder(deckId: String) {
        val f = folders ?: return
        val deck = deck(deckId) ?: return
        val path = deck.folder
        if (path != null) {
            if (!f.open(path)) toast("Kein Programm zum Öffnen von Ordnern gefunden – ${f.describe(path)}")
            return
        }
        val default = f.defaultFolder(AnswerParser.fileSafe(deck.name))
        if (default != null) {
            scope.launch {
                linkFolderNow(deckId, default)
                f.open(default)
            }
        } else {
            chooseFolder(deckId)
        }
    }

    fun chooseFolder(deckId: String) {
        folders?.choose { folder -> if (folder != null) scope.launch { linkFolderNow(deckId, folder) } }
    }

    fun unlinkFolder(deckId: String) {
        val deck = deck(deckId) ?: return
        saveCollection(
            collection.copy(
                decks = collection.decks.map { if (it.id == deckId) deck.copy(folder = null) else it },
                cards = collection.cards.map { if (it.deckId == deckId) it.copy(source = null, sourceStamp = 0) else it },
            ),
        )
        toast("Ordner getrennt – die Karten bleiben")
    }

    fun syncFolder(deckId: String, quiet: Boolean = true) {
        if (deck(deckId)?.folder == null) return
        scope.launch { syncFolderNow(deckId, quiet) }
    }

    /** Gives the starter kits their folder (once) and brings every linked deck up to date. */
    fun refreshFolders() {
        scope.launch {
            linkKitFolders()
            syncAllFolders()
        }
    }

    private suspend fun syncAllFolders() {
        for (deck in collection.decks.filter { it.folder != null }) syncFolderNow(deck.id, quiet = true)
    }

    /** Starter kits go into a folder next to the collection, once, where the platform has one. */
    private suspend fun linkKitFolders() {
        if (settings.kitFolderLinked) return
        for (id in listOf(StarterKits.A_CHORDS_ID, StarterKits.A_CARDS_ID)) linkKitFolder(id)
        if (collection.decks.any { it.id == StarterKits.A_CHORDS_ID || it.id == StarterKits.A_CARDS_ID }) {
            saveCollection(collection.copy(settings = settings.copy(kitFolderLinked = true)))
        }
    }

    private suspend fun linkKitFolder(deckId: String) {
        val deck = deck(deckId) ?: return
        if (deck.folder != null) return
        val folder = folders?.defaultFolder(StarterKits.FOLDER) ?: return
        linkFolderNow(deckId, folder, quiet = true)
    }

    /** Links the deck: its cards' audio is written to the folder (a file already there wins), then the folder is read. */
    private suspend fun linkFolderNow(deckId: String, folder: String, quiet: Boolean = false) {
        val f = folders ?: return
        val deck = deck(deckId) ?: return
        updateDeck(deck.copy(folder = folder))
        folderLock.withLock {
            val existing = withContext(work) { f.list(folder) }
            if (existing == null) {
                toast("Ordner „${f.describe(folder)}“ ist nicht erreichbar")
                return
            }
            val names = existing.map { it.name }.toMutableSet()
            val linked = mutableMapOf<String, Card>()
            for (card in cardsIn(deckId).filter { it.source == null && it.frontAudio != null }) {
                val clip = card.frontAudio!!
                val ext = clip.file.substringAfterLast('.', "mid")
                val base = AnswerParser.fileSafe(if (card.answer.isNotEmpty()) card.answer.joinToString(" ") else clip.name.substringBeforeLast('.'))
                    .ifEmpty { "Audio" }
                var name = "$base.$ext"
                val bytes = store.readMedia(clip.file) ?: continue
                if (name in names) {
                    // Already there (e.g. the other starter kit wrote it). The same file is simply
                    // taken over; a different one wins over the card's audio on the next read.
                    val same = withContext(work) { f.read(folder, name) }?.contentEquals(bytes) == true
                    linked[card.id] = card.copy(source = name, sourceStamp = if (same) existing.first { it.name == name }.stamp else 0)
                    continue
                }
                var n = 2
                while (name in names || linked.values.any { it.source == name }) name = "$base (${n++}).$ext"
                val written = withContext(work) { f.write(folder, name, bytes) } ?: continue
                names += written.name
                linked[card.id] = card.copy(source = written.name, sourceStamp = written.stamp)
            }
            if (linked.isNotEmpty()) saveCollection(collection.copy(cards = collection.cards.map { linked[it.id] ?: it }))
        }
        syncFolderNow(deckId, quiet)
    }

    /**
     * Makes the deck follow its folder: new files become cards, replaced files replace the card's
     * audio (the learning progress stays), deleted files take their card with them. Cards that
     * never came from the folder are left alone.
     */
    private suspend fun syncFolderNow(deckId: String, quiet: Boolean) = folderLock.withLock {
        val f = folders ?: return@withLock
        val deck = deck(deckId) ?: return@withLock
        val folder = deck.folder ?: return@withLock
        val listing = withContext(work) { f.list(folder) }
        if (listing == null) {
            if (!quiet) toast("Ordner „${f.describe(folder)}“ ist nicht erreichbar – in den Stapel-Optionen neu verbinden")
            return@withLock
        }
        val files = listing.filter { it.name.substringAfterLast('.', "").lowercase().let { e -> e in AUDIO_EXTENSIONS || e in MIDI_EXTENSIONS } }
        val cards = cardsIn(deckId)
        val bySource = cards.filter { it.source != null }.associateBy { it.source!! }
        val present = files.map { it.name }.toSet()
        // "A7.mid" swapped for "A7.wav": same name, other format - the card and its progress stay.
        val orphansByStem = cards.filter { it.source != null && it.source !in present }
            .associateBy { it.source!!.substringBeforeLast('.').lowercase() }.toMutableMap()
        val now = currentTimeMillis()
        val added = mutableListOf<Card>()
        val replaced = mutableMapOf<String, Card>()
        for ((i, file) in files.sortedBy { it.name.lowercase() }.withIndex()) {
            val card = bySource[file.name] ?: orphansByStem.remove(file.name.substringBeforeLast('.').lowercase())
            if (card != null && card.sourceStamp == file.stamp) continue
            val bytes = withContext(work) { f.read(folder, file.name) } ?: continue
            val clip = Clip(store.addMedia(file.name, bytes), file.name)
            if (card == null) {
                added += cardFromFile(deck, IncomingFile(file.name, bytes), clip, now + i).copy(source = file.name, sourceStamp = file.stamp)
            } else {
                card.frontAudio?.let { store.deleteMedia(it.file) }
                replaced[card.id] = card.copy(frontAudio = clip, source = file.name, sourceStamp = file.stamp, modified = now)
            }
        }
        val removed = cards.filter { it.source != null && it.source !in present && it.id !in replaced }
        if (added.isEmpty() && replaced.isEmpty() && removed.isEmpty()) {
            if (!quiet) toast("„${deck.name}“ ist auf dem Stand des Ordners")
            return@withLock
        }
        removed.forEach { deleteClipFiles(it) }
        val removedIds = removed.map { it.id }.toSet()
        saveCollection(collection.copy(cards = collection.cards.filter { it.id !in removedIds }.map { replaced[it.id] ?: it } + added))
        if (removedIds.isNotEmpty()) saveProgress(progress.copy(cards = progress.cards - removedIds))
        replaced.values.forEach { refreshSessionsFor(it) }
        val parts = listOfNotNull(
            added.size.takeIf { it > 0 }?.let { "$it neu" },
            replaced.size.takeIf { it > 0 }?.let { "$it ersetzt" },
            removed.size.takeIf { it > 0 }?.let { "$it entfernt" },
        )
        toast("${deck.name}: ${parts.joinToString(", ")}")
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
        val wasAway = !foreground
        foreground = true
        // Files may have been swapped in Finder or the file manager meanwhile.
        if (wasAway && live) scope.launch { syncAllFolders() }
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

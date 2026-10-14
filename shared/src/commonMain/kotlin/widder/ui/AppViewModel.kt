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
import kotlin.math.roundToInt
import kotlin.random.Random
import widder.audio.AUDIO_EXTENSIONS
import widder.audio.AudioSystem
import widder.audio.Effects
import widder.audio.DrumSamples
import widder.audio.MIDI_EXTENSIONS
import widder.audio.Pcm
import widder.audio.PracticeEngine
import widder.audio.RenderCache
import widder.audio.Synth
import widder.audio.Wav
import widder.core.Action
import widder.core.ActionKind
import widder.core.AudioEdit
import widder.core.Card
import widder.core.ChordRound
import widder.core.Clip
import widder.core.Collection
import widder.core.Deck
import widder.core.DeckCounts
import widder.core.DeckKind
import widder.core.Grade
import widder.core.GestureTile
import widder.core.KeyStroke
import widder.core.IncomingFile
import widder.core.PickKind
import widder.core.Progress
import widder.core.Queue
import widder.core.Reward
import widder.core.Settings
import widder.core.Shortcut
import widder.core.Shortcuts
import widder.core.TileScope
import widder.core.Store
import widder.core.answer
import widder.core.currentTimeMillis
import widder.core.formatTime
import widder.core.newId
import widder.core.NumeralMode
import widder.core.PracticeSettings
import widder.music.AnswerParser
import widder.music.Chord
import widder.music.Harmony
import widder.music.Interval
import widder.music.Key
import widder.music.Token
import widder.music.Midi
import widder.music.MidiAnalysis
import widder.music.Note
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
    /** Mixes the cards; the screenshot renderer passes a fixed one. */
    private val random: Random = Random.Default,
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
    var deckPick by mutableStateOf<DeckPick?>(null)
    var shortcutEdit by mutableStateOf<ShortcutEdit?>(null)

    /** The field whose gesture just fired, briefly lit up. */
    var tileFlash by mutableStateOf<TileFlash?>(null)
        private set

    /** The prefix was pressed; the next key picks the shortcut. */
    var prefixPending by mutableStateOf(false)
        private set

    /** Settings: the next key combination becomes the prefix. */
    var capturingPrefix by mutableStateOf(false)

    /** The deck picked with prefix + number; actions without a deck of their own go to it. */
    var selectedDeckId by mutableStateOf<String?>(null)
        private set

    /** Deck lists show tick boxes, to delete several decks at once. */
    var marking by mutableStateOf(false)
        private set
    var marked by mutableStateOf(setOf<String>())
        private set

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

    /** Standard kits added or grown by [installStandardKits]; their folders are seen to with the others. */
    private var kitsToLink = listOf<String>()

    init {
        installStandardKits()
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
        stopPractice()
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
            prefixPending -> prefixPending = false
            capturingPrefix -> capturingPrefix = false
            deckPick != null -> deckPick = null
            marking -> stopMarking()
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
        if (leaving == Screen.Practice) stopPractice()
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

    /** The cards of [deck] that are learned: with the deck's tags, and for training only what can be trained. */
    private fun studyCards(deck: Deck): List<Card> = collection.cards.filter { card ->
        card.deckId == deck.id &&
            (deck.studyTags.isEmpty() || card.tags.any { it in deck.studyTags }) &&
            (deck.kind != DeckKind.CHORDS || (card.answer.isNotEmpty() && card.frontAudio != null))
    }

    /** Learn only the cards with one of [tags]; empty = all. */
    fun setStudyTags(deckId: String, tags: List<String>) {
        val deck = deck(deckId) ?: return
        val all = tagsIn(deckId)
        updateDeck(deck.copy(studyTags = all.filter { it in tags }))
    }

    fun tagsIn(deckId: String): List<String> = cardsIn(deckId).flatMap { it.tags }.distinct().sorted()

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

    /** The starter kits of [kind] that are not in the collection (any more). */
    fun missingKits(kind: DeckKind): List<StarterKits.Kit> =
        StarterKits.ALL.filter { kit -> kit.kind == kind && collection.decks.none { it.id == kit.id } }

    fun addStarterKit(id: String) {
        val kit = StarterKits.kit(id) ?: return
        if (collection.decks.any { it.id == id }) return
        val (deck, cards) = kit.build(store)
        saveCollection(collection.copy(decks = collection.decks + deck, cards = collection.cards + cards))
        toast("„${deck.name}“ angelegt")
        scope.launch { linkKitFolder(deck.id) }
    }

    /**
     * Gives the collection the standard kits of this version, once: missing decks are added
     * (also ones deleted before, since the kits changed), decks already there get the new cards,
     * and the cards they had (same answer, notes and voicing) keep their progress and take the new tags.
     */
    private fun installStandardKits() {
        if (settings.standardKits >= StarterKits.STANDARD_VERSION) return
        var c = collection
        val touched = mutableListOf<String>()
        for (id in StarterKits.STANDARD) {
            val (deck, cards) = StarterKits.kit(id)?.build(store) ?: continue
            if (c.decks.none { it.id == id }) {
                c = c.copy(decks = c.decks + deck, cards = c.cards + cards)
                touched += id
                continue
            }
            fun same(card: Card) = Triple(card.answer, card.notes, card.voicing)
            val built = cards.associateBy(::same)
            val have = c.cards.filter { it.deckId == id }.map(::same).toSet()
            val added = cards.filter { same(it) !in have }
            cards.filter { it !in added }.forEach { deleteClipFiles(it) }
            c = c.copy(
                // Flash-card twins were named "… (Karten)" up to 1.8; now each list says what it is.
                decks = c.decks.map { if (it.id == id) it.copy(pool = deck.pool, name = if (it.name == deck.name + " (Karten)") deck.name else it.name) else it },
                cards = c.cards.map { card ->
                    val tags = built[same(card)]?.tags
                    if (card.deckId == id && tags != null) card.copy(tags = (card.tags + tags).distinct()) else card
                } + added,
            )
            if (added.isNotEmpty()) touched += id
        }
        kitsToLink = touched
        saveCollection(c.copy(settings = c.settings.copy(standardKits = StarterKits.STANDARD_VERSION)))
    }

    fun deleteDeck(deckId: String) = deleteDecks(setOf(deckId))

    fun deleteDecks(ids: Set<String>) {
        val cards = collection.cards.filter { it.deckId in ids }
        cards.forEach { deleteClipFiles(it) }
        saveCollection(collection.copy(decks = collection.decks.filter { it.id !in ids }, cards = collection.cards.filter { it.deckId !in ids }))
        saveProgress(progress.copy(cards = progress.cards - cards.map { it.id }.toSet()))
        if (selectedDeckId in ids) selectedDeckId = null
        marked = marked - ids
    }

    /** Every deck in the order they are numbered: the flash cards, then the multiple choice. */
    fun numberedDecks(): List<Deck> = decks(DeckKind.CARDS) + decks(DeckKind.CHORDS)

    /** 1 to 9 for prefix + digit; null for the decks after the ninth. */
    fun deckNumber(deckId: String): Int? = (numberedDecks().indexOfFirst { it.id == deckId } + 1).takeIf { it in 1..9 }

    fun toggleMark(deckId: String) {
        marking = true
        marked = if (deckId in marked) marked - deckId else marked + deckId
    }

    fun startMarking() {
        marking = true
    }

    fun stopMarking() {
        marking = false
        marked = emptySet()
    }

    fun markAll(kind: DeckKind, on: Boolean) {
        val ids = decks(kind).map { it.id }.toSet()
        marked = if (on) marked + ids else marked - ids
    }

    /** Asks, then deletes every marked deck with its cards. */
    fun deleteMarked() {
        val decks = collection.decks.filter { it.id in marked }
        if (decks.isEmpty()) return
        val cards = collection.cards.count { it.deckId in marked }
        val title = if (decks.size == 1) "„${decks[0].name}“ löschen?" else "${decks.size} Stapel löschen?"
        val list = if (decks.size == 1) "" else decks.joinToString("\n", postfix = "\n\n") { "• " + it.name }
        confirm = Confirm(title, list + "Alle $cards Karten und ihre Audiodateien werden entfernt.", "Löschen") {
            deleteDecks(decks.map { it.id }.toSet())
            stopMarking()
            toast(if (decks.size == 1) "Stapel gelöscht" else "${decks.size} Stapel gelöscht")
        }
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

    /** How chords are labelled in [deckId]: its own choice, or the setting. */
    fun numeralMode(deckId: String?): NumeralMode = deckId?.let { deck(it)?.numerals } ?: settings.numerals

    /** "I (A)", "I" or "A", depending on the setting; plain text for tokens that are no chord. */
    fun chordLabel(token: Token, key: Key?, deckId: String? = null): String {
        val numeral = numeral(token, key, deckId) ?: return token.text
        return when (numeralMode(deckId)) {
            NumeralMode.OFF -> token.text
            NumeralMode.WITH_NAME -> "$numeral (${token.text})"
            NumeralMode.ONLY -> numeral
        }
    }

    fun numeral(token: Token, key: Key?, deckId: String? = null): String? {
        if (numeralMode(deckId) == NumeralMode.OFF) return null
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
            answer = AnswerParser.splitTyped(e.answer),
            key = e.key.trim().ifEmpty { null },
            label = e.label.trim().ifEmpty { null },
            notes = e.notes.trim(),
            voicing = e.voicing.trim(),
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

    /**
     * Saves a clip where the user wants it: [asHeard] renders it with all edits to a WAV (a MIDI
     * file becomes audio that way), otherwise the original file is saved as it is.
     */
    fun downloadClip(clip: Clip, asHeard: Boolean) {
        scope.launch {
            val stem = clip.name.substringBeforeLast('.')
            val (name, bytes) = if (asHeard) {
                val pcm = renderedPcm(clip) ?: return@launch toast("„${clip.name}“ lässt sich nicht abspielen")
                "$stem.wav" to withContext(work) { Wav.write(pcm) }
            } else {
                clip.name to (store.readMedia(clip.file) ?: return@launch toast("Datei fehlt"))
            }
            platform.saveFile(AnswerParser.fileSafe(name), bytes) { where -> if (where != null) scope.launch { toast("Gespeichert: $where") } }
        }
    }

    /** The keyboard under a MIDI answer is open; it stays open for the next cards. */
    var keyboardOpen by mutableStateOf(false)

    /** The keys of a MIDI clip chord by chord, as far as it is not cut away; null if it cannot be read. */
    suspend fun grips(clip: Clip): List<MidiAnalysis.Grip>? = withContext(work) {
        val bytes = store.readMedia(clip.file) ?: return@withContext null
        val midi = runCatching { Midi.parse(bytes) }.getOrNull() ?: return@withContext null
        MidiAnalysis.grips(midi, clip.edit.trimStart, clip.edit.trimEnd)
    }

    /** Strikes [pitches] together, to hear one grip of the keyboard on its own. */
    fun playKeys(pitches: List<Int>, key: String) {
        if (!live) return
        if (playingKey == key && audio.player.isPlaying) {
            stopAudio()
            return
        }
        playingKey = key
        scope.launch {
            val pcm = withContext(work) { Synth.render(pitches.map { Note(it, 0.0, 1.4, 90) }) }
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

    fun startRecording(target: ClipTarget, quick: Boolean = false) {
        audio.player.stop()
        platform.requestMicrophone { granted ->
            if (!granted) {
                toast("Ohne Mikrofon-Erlaubnis kann Widder nicht aufnehmen")
                if (quick) cancelEditor()
                return@requestMicrophone
            }
            if (audio.recorder.start()) {
                recording = RecordingState(target, quick).also { it.started = currentTimeMillis() }
            } else {
                toast("Kein Mikrofon gefunden")
                if (quick) cancelEditor()
            }
        }
    }

    fun stopRecording(keep: Boolean = true) {
        val state = recording ?: return
        recording = null
        val pcm = audio.recorder.stop()
        if (!keep || pcm == null || pcm.frames < pcm.sampleRate / 10) {
            // A card that was only opened for this recording goes with it.
            if (state.quick && screen == Screen.EditCard && editor?.let { it.original == null && it.frontAudio == null } == true) cancelEditor()
            return
        }
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
        // At least 20 ms stay selected; an end marker dragged to the very left must not leave the start no room.
        val minLength = 0.02.coerceAtMost(state.duration)
        val end = edit.trimEnd?.coerceIn(minLength, state.duration)
        val start = edit.trimStart.coerceIn(0.0, (end ?: state.duration) - minLength)
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

    /** A new mixed order for each session, where the deck wants one. */
    private fun sessionSeed(deckId: String): Int? = if (deck(deckId)?.shuffle != false) random.nextInt() else null

    fun startReview(deckId: String) {
        review = ReviewSession(deckId, sessionSeed(deckId))
        open(Screen.Review(deckId))
        nextReviewCard()
    }

    private fun nextReviewCard(skip: String? = null) {
        val session = review ?: return
        val deck = deck(session.deckId) ?: return
        val queue = Queue(deck, studyCards(deck), progress, seed = session.seed)
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

    /** Tests: stores [card] as it is. */
    internal fun updateCardForTest(card: Card) = replaceCard(card)

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

    /**
     * Mixes the cards anew, also in a deck that keeps its order, and puts the current one back:
     * the next card is a different one where there is one.
     */
    fun shuffle() {
        val seed = random.nextInt()
        review?.takeIf { screen is Screen.Review }?.let { session ->
            session.seed = seed
            nextReviewCard(skip = session.card?.id)
        }
        training?.takeIf { screen is Screen.Train }?.let { session ->
            session.seed = seed
            nextExercise(skip = session.card?.id)
        }
        toast("Gemischt")
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
        training = TrainSession(deckId, sessionSeed(deckId))
        open(Screen.Train(deckId))
        nextExercise()
    }

    private fun nextExercise(skip: String? = null) {
        val session = training ?: return
        val deck = deck(session.deckId) ?: return
        val cards = studyCards(deck)
        val queue = Queue(deck, cards, progress, seed = session.seed)
        val card = queue.next(skip)
        session.card = card
        session.suggested = null
        // Learning only some tags: the wrong tiles come from what is learned, so "Basis" is not
        // mixed with C13.
        val learned = if (deck.studyTags.isEmpty()) null else cards.flatMap { tokensOf(it).map { t -> t.key } }.toSet()
        val pool = deck.pool.map { t -> widder.music.Token.of(t, settings.germanNotation) }
            .let { all -> learned?.let { keys -> all.filter { it.key in keys }.takeIf { it.size > 1 } } ?: all }
        session.nextDue = if (card == null) queue.nextLearningDue() else null
        session.round = card?.let {
            ChordRound(
                tokensOf(it),
                deck.distractors,
                notation,
                candidates = pool,
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
    }?.takeIf { deck(it) != null }

    fun chooseImportTarget(deckId: String?, newKind: DeckKind? = null) {
        val files = pendingImport ?: return
        pendingImport = null
        val id = deckId ?: createDeck(files.first().name.substringBeforeLast('.').take(30).let { if (files.size > 1) "Import" else it }, newKind ?: DeckKind.CARDS).id
        importInto(id, files)
    }

    private fun answerFor(file: IncomingFile): List<String> {
        val tokens = AnswerParser.parse(file.name, settings.germanNotation)
        if (tokens.isNotEmpty() && tokens.any { it.chord != null || it.interval != null }) return tokens.map { it.text }
        if (file.extension in MIDI_EXTENSIONS) {
            val midi = runCatching { Midi.parse(file.bytes) }.getOrNull()
            // Two notes are an interval, not a chord without its fifth.
            midi?.let { MidiAnalysis.interval(it) }?.let { return listOf(Interval.name(it)) }
            val chords = midi?.let { MidiAnalysis.chords(it) }.orEmpty()
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
                    ImportPreview.Item(f, answer.joinToString(" "), fromMidi = fromName.none { it.chord != null || it.interval != null } && answer.isNotEmpty())
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
        val intervals = answer.isNotEmpty() && answer.all { Interval.parse(it) != null }
        val chordsFound = intervals || answer.isNotEmpty() && answer.all { Chord.parse(it, settings.germanNotation) != null }
        return Card(
            newId(),
            deck.id,
            front = when {
                !chordsFound -> "Was hörst du?"
                intervals -> if (answer.size == 1) "Welches Intervall ist das?" else "Welche Intervalle hörst du?"
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
                answer = AnswerParser.splitTyped(item.answer),
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
        // New standard kits get their folder; ones already linked write their new cards into it.
        val standard = kitsToLink
        kitsToLink = emptyList()
        for (id in standard) deck(id)?.folder?.let { linkFolderNow(id, it, quiet = true) } ?: linkKitFolder(id)
    }

    private suspend fun linkKitFolder(deckId: String) {
        val deck = deck(deckId) ?: return
        if (deck.folder != null) return
        val folder = folders?.defaultFolder(StarterKits.kit(deckId)?.folder ?: return) ?: return
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
            val before = existing.associateBy { it.name }
            val names = before.keys.toMutableSet()
            val linked = mutableMapOf<String, Card>()
            for (card in cardsIn(deckId).filter { it.source == null && it.frontAudio != null }) {
                val clip = card.frontAudio!!
                val ext = clip.file.substringAfterLast('.', "mid")
                val base = AnswerParser.fileSafe(if (card.answer.isNotEmpty()) card.answer.joinToString(" ") else clip.name.substringBeforeLast('.'))
                    .ifEmpty { "Audio" }
                var name = "$base.$ext"
                val bytes = store.readMedia(clip.file) ?: continue
                // Already there (e.g. the other starter kit wrote it): the same file is simply taken
                // over, also as "Cmaj7 (2)" for a second card with that answer. A different file
                // under the plain name wins over the card's audio on the next read.
                var n = 2
                var taken: Card? = null
                while (name in names) {
                    val file = before[name]
                    if (file != null && linked.values.none { it.source == name }) {
                        val same = withContext(work) { f.read(folder, name) }?.contentEquals(bytes) == true
                        if (same || n == 2) {
                            taken = card.copy(source = name, sourceStamp = if (same) file.stamp else 0)
                            break
                        }
                    }
                    name = "$base (${n++}).$ext"
                }
                if (taken != null) {
                    linked[card.id] = taken
                    continue
                }
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
        val repaired = mutableMapOf<String, Card>()
        // Up to 1.2 "Am(add9).mid" was read as "Am". Cards still holding exactly that misreading
        // get the right answer; anything edited by hand is left alone.
        for (card in cards) {
            val name = card.source ?: continue
            if (name !in present || card.id in replaced) continue
            val misread = AnswerParser.parse(name.replace(Regex("\\([^)]*\\)"), " "), settings.germanNotation).map { it.text }
            val right = AnswerParser.parse(name, settings.germanNotation).map { it.text }
            if (card.answer == misread && right != misread && right.isNotEmpty()) repaired[card.id] = card.copy(answer = right, modified = now)
        }
        if (added.isEmpty() && replaced.isEmpty() && removed.isEmpty() && repaired.isEmpty()) {
            if (!quiet) toast("„${deck.name}“ ist auf dem Stand des Ordners")
            return@withLock
        }
        removed.forEach { deleteClipFiles(it) }
        val removedIds = removed.map { it.id }.toSet()
        saveCollection(collection.copy(cards = collection.cards.filter { it.id !in removedIds }.map { replaced[it.id] ?: repaired[it.id] ?: it } + added))
        if (removedIds.isNotEmpty()) saveProgress(progress.copy(cards = progress.cards - removedIds))
        (replaced.values + repaired.values).forEach { refreshSessionsFor(it) }
        val parts = listOfNotNull(
            added.size.takeIf { it > 0 }?.let { "$it neu" },
            replaced.size.takeIf { it > 0 }?.let { "$it ersetzt" },
            removed.size.takeIf { it > 0 }?.let { "$it entfernt" },
            repaired.size.takeIf { it > 0 }?.let { if (it == 1) "1 Lösung korrigiert" else "$it Lösungen korrigiert" },
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

    // region tiles and actions

    /** Exactly one field per grid cell, row by row: the learning grid ([learn]) or the other one. */
    fun tilesOf(learn: Boolean): List<GestureTile> {
        val stored = (if (learn) settings.learnTiles ?: Shortcuts.defaultLearnTiles else settings.tiles ?: Shortcuts.defaultTiles)
        return List(columnsOf(learn) * rowsOf(learn)) { stored.getOrNull(it) ?: GestureTile() }
    }

    fun columnsOf(learn: Boolean): Int = if (learn) 1 else settings.tileColumns
    fun rowsOf(learn: Boolean): Int = if (learn) settings.learnTileRows else settings.tileRows

    /** The editor was opened from a card being learned; saving leads back to it. */
    private val editingFromStudy: Boolean get() = screen == Screen.EditCard && stack.getOrNull(stack.size - 2)?.isStudy == true

    /** Learning, or editing from there: the learning grid listens instead of the other one. */
    val learnGrid: Boolean get() = settings.learnTilesOn && (screen.isStudy || editingFromStudy)

    /** The fields that listen now. */
    val tiles: List<GestureTile> get() = tilesOf(learnGrid)
    val gridColumns: Int get() = columnsOf(learnGrid)
    val gridRows: Int get() = rowsOf(learnGrid)

    /** The MIDI clip of a card, which the keyboard shows. */
    fun midiClip(card: Card): Clip? = listOfNotNull(card.frontAudio, card.backAudio).firstOrNull { it.isMidi }

    /** The card being learned on screen. */
    private fun studyCard(): Card? = when (screen) {
        is Screen.Review -> review?.card
        is Screen.Train -> training?.card
        else -> null
    }

    /** The MIDI clip whose keyboard can show right now: the answer is out. */
    private fun keyboardClip(): Clip? = when (screen) {
        is Screen.Review -> review?.takeIf { it.showAnswer }?.card?.let(::midiClip)
        is Screen.Train -> training?.takeIf { it.round?.checked == true }?.card?.let(::midiClip)
        else -> null
    }

    /** Whether [action] can do something here; elsewhere its gesture lets the taps through. */
    fun canRun(action: Action): Boolean = when (action.kind) {
        ActionKind.NONE -> false
        ActionKind.EDIT_CARD -> studyCard() != null
        ActionKind.KEYBOARD -> keyboardClip() != null
        ActionKind.BACK_TO_CARD -> editingFromStudy
        else -> true
    }

    /** Whether the grid takes double taps and long presses right now. */
    fun tilesActive(): Boolean {
        if (recording != null || audioEditor != null || busy != null || prefixPending || capturingPrefix || showRewardOnly) return false
        if (importPreview != null || pendingImport != null || deckPick != null) return false
        if (learnGrid) return true
        return when (settings.tileScope) {
            TileScope.OFF -> false
            TileScope.HOME -> screen == Screen.Home
            // Text fields and drag & drop need these gestures themselves; the grid editor would trip over itself.
            TileScope.EVERYWHERE -> screen != Screen.EditCard && screen !is Screen.Train && screen != Screen.Tiles && screen !is Screen.EditTile
        }
    }

    /** A double tap or long press on field [index]; false when the field has nothing for it. */
    fun onTileGesture(index: Int, long: Boolean): Boolean {
        val tile = tiles.getOrNull(index) ?: return false
        val action = if (long) tile.longPress else tile.doubleTap
        if (!canRun(action) || !tilesActive()) return false
        tileFlash = TileFlash(index, describe(action), (tileFlash?.serial ?: 0) + 1)
        run(action)
        return true
    }

    fun hasTileAction(index: Int, long: Boolean): Boolean =
        tiles.getOrNull(index)?.let { canRun(if (long) it.longPress else it.doubleTap) } == true

    fun clearTileFlash(serial: Int) {
        if (tileFlash?.serial == serial) tileFlash = null
    }

    val shortcuts: List<Shortcut> get() = settings.shortcuts ?: Shortcuts.defaultShortcuts

    /** The tile's deck, if it still exists. */
    fun deckOf(action: Action): Deck? = action.deckId?.let { deck(it) }

    /** "Lernen · Jazz-Standards", or just the action when the deck is chosen each time. */
    fun describe(action: Action): String = deckOf(action)?.let { "${action.kind.label} · ${it.name}" } ?: action.kind.label

    /** Runs what a tile gesture or a shortcut stands for. */
    fun run(action: Action) {
        if (action.isNone) return
        if (recording != null || audioEditor != null || busy != null) return
        when (action.kind) {
            ActionKind.EDIT_CARD -> return studyCard()?.let { editCard(it) } ?: toast("Öffnet beim Lernen die Karte im Editor")
            ActionKind.KEYBOARD -> {
                keyboardOpen = !keyboardOpen
                return
            }
            ActionKind.BACK_TO_CARD -> return if (editingFromStudy) saveEditor() else toast("Nur im Editor, der beim Lernen geöffnet wurde")
            else -> Unit
        }
        if (screen == Screen.EditCard) {
            toast("Erst die Karte speichern oder verwerfen")
            return
        }
        if (action.kind.needsDeck) {
            val deckId = deckOf(action)?.id ?: actionDeckId()
            if (deckId == null) deckPick = DeckPick(action.kind) else runWithDeck(action.kind, deckId)
            return
        }
        when (action.kind) {
            ActionKind.OPEN_CARDS -> jumpTo(Screen.Decks(DeckKind.CARDS))
            ActionKind.OPEN_CHORDS -> jumpTo(Screen.Decks(DeckKind.CHORDS))
            ActionKind.OPEN_PRACTICE -> jumpTo(Screen.Practice)
            ActionKind.SETTINGS -> jumpTo(Screen.Settings)
            ActionKind.CONFIG -> actionDeckId()?.let { deckDialog = DeckDialog.Options(it) } ?: jumpTo(Screen.Settings)
            ActionKind.HELP -> if (screen != Screen.Help) open(Screen.Help)
            ActionKind.HOME -> home()
            ActionKind.REWARD -> {
                home()
                if (settings.reward.enabled && rewardImages != null) showRewardOnly = true else toast("Noch kein Belohnungsbild gewählt")
            }
            else -> Unit
        }
    }

    /** The open deck, or in a deck list the one picked by number. */
    private fun actionDeckId(): String? {
        currentDeckId()?.let { return it }
        val list = screen as? Screen.Decks ?: return null
        return selectedDeckId?.takeIf { deck(it)?.kind == list.kind }
    }

    /** Prefix + digit: shows the deck list with deck [number] picked; with [mark] ticks it instead. */
    fun pickDeckNumber(number: Int, mark: Boolean = false) {
        if (recording != null || audioEditor != null || busy != null) return
        if (screen == Screen.EditCard) {
            toast("Erst die Karte speichern oder verwerfen")
            return
        }
        val deck = numberedDecks().getOrNull(number - 1) ?: return toast("Es gibt keinen Stapel $number")
        if (mark) toggleMark(deck.id)
        selectedDeckId = deck.id
        jumpTo(Screen.Decks(deck.kind))
    }

    /** The deck picked for an action that asked for one. */
    fun pickDeck(deckId: String) {
        val pick = deckPick ?: return
        deckPick = null
        runWithDeck(pick.kind, deckId)
    }

    private fun runWithDeck(kind: ActionKind, deckId: String) {
        val deck = deck(deckId) ?: return
        when (kind) {
            ActionKind.STUDY -> {
                if (counts(deck).total == 0) {
                    toast("In „${deck.name}“ ist gerade nichts fällig")
                    return
                }
                home()
                if (deck.kind == DeckKind.CARDS) startReview(deckId) else startTraining(deckId)
            }
            ActionKind.BROWSE -> jumpTo(Screen.Browse(deckId))
            // These stay on top of whatever is open: after saving it is back there.
            ActionKind.NEW_CARD -> newCard(deckId)
            ActionKind.RECORD -> {
                newCard(deckId)
                startRecording(ClipTarget.Editor(back = false), quick = true)
            }
            ActionKind.IMPORT -> pickImport(deckId)
            else -> Unit
        }
    }

    /** Opens [target] straight from the start screen, so back always leads home. */
    private fun jumpTo(target: Screen) {
        if (screen == target) return
        home()
        if (target != Screen.Home) open(target)
    }

    fun saveTile(index: Int, tile: GestureTile, learn: Boolean = false) {
        val stored = (if (learn) settings.learnTiles ?: Shortcuts.defaultLearnTiles else settings.tiles ?: Shortcuts.defaultTiles).toMutableList()
        while (stored.size <= index) stored += GestureTile()
        stored[index] = tile
        updateSettings { if (learn) it.copy(learnTiles = stored) else it.copy(tiles = stored) }
    }

    fun resetTiles(learn: Boolean = false) = updateSettings {
        if (learn) it.copy(learnTiles = null, learnTileRows = 3, learnTilesOn = true) else it.copy(tiles = null, tileColumns = 2, tileRows = 2)
    }

    /** Replaces the shortcut on [old] (null = adds one); a key already taken is taken over. */
    fun saveShortcut(old: Char?, shortcut: Shortcut) {
        val rest = shortcuts.filter { it.key != old && it.key != shortcut.key }
        val at = shortcuts.indexOfFirst { it.key == old }.takeIf { it >= 0 }?.coerceAtMost(rest.size) ?: rest.size
        updateSettings { it.copy(shortcuts = rest.toMutableList().apply { add(at, shortcut) }) }
    }

    fun deleteShortcut(key: Char) = updateSettings { it.copy(shortcuts = shortcuts.filter { s -> s.key != key }) }

    fun resetShortcuts() = updateSettings { it.copy(shortcuts = null, prefix = Shortcuts.DEFAULT_PREFIX) }

    /**
     * Desktop: every key press comes here first, before the study keys. [stroke] is the key with
     * its modifiers, [typed] the character it types. Returns true when it was used.
     */
    fun onShortcutKey(stroke: KeyStroke, typed: Char?): Boolean {
        if (capturingPrefix) {
            if (stroke.key == "Escape") {
                capturingPrefix = false
            } else if (!stroke.usableAsPrefix) {
                toast(if (platform.macKeys) "Zusammen mit ⌃, ⌥ oder ⌘ drücken – oder eine F-Taste" else "Zusammen mit Strg, Alt oder Super drücken – oder eine F-Taste")
            } else {
                capturingPrefix = false
                updateSettings { it.copy(prefix = stroke) }
                toast("Prefix ist jetzt ${keyLabel(stroke)}")
            }
            return true
        }
        if (prefixPending) {
            prefixPending = false
            if (stroke.key == "Escape" || stroke == settings.prefix) return true
            // Shift + digit types "!" or "\"", so the digit comes from the key itself.
            stroke.key.singleOrNull()?.digitToIntOrNull()?.let {
                pickDeckNumber(it, mark = stroke.shift)
                return true
            }
            val key = typed?.takeIf { it.isLetter() || it == '?' }?.uppercaseChar() ?: stroke.key.singleOrNull() ?: return true
            runShortcut(key)
            return true
        }
        if (settings.prefix != null && stroke == settings.prefix) {
            prefixPending = true
            return true
        }
        return false
    }

    fun keyLabel(stroke: KeyStroke): String = stroke.label(platform.macKeys)

    /** What comes after the prefix. */
    fun runShortcut(key: Char) {
        prefixPending = false
        val shortcut = shortcuts.firstOrNull { it.key == key } ?: return toast("${settings.prefix?.let(::keyLabel) ?: ""} $key ist nicht belegt")
        run(shortcut.action)
    }

    fun closePrefix() {
        prefixPending = false
    }

    // endregion

    // region practice

    /** The practice mode's settings while it is open; saved with the collection when it is left. */
    var practice by mutableStateOf(settings.practice)
        private set
    var droneOn by mutableStateOf(false)
        private set
    var drumsOn by mutableStateOf(false)
        private set

    val practiceEngine = PracticeEngine { DrumSamples.load(audio.bundled) }
    private val taps = ArrayDeque<Long>()

    fun updatePractice(transform: (PracticeSettings) -> PracticeSettings) {
        practice = transform(practice)
        practiceEngine.settings = practice
    }

    fun toggleDrone() {
        droneOn = !droneOn
        playPractice()
    }

    fun toggleDrums() {
        drumsOn = !drumsOn
        playPractice()
    }

    /** Starts the stream when the first part is switched on and stops it with the last. */
    private fun playPractice() {
        practiceEngine.settings = practice
        practiceEngine.droneOn = droneOn
        practiceEngine.drumsOn = drumsOn
        val wanted = droneOn || drumsOn
        if (wanted && !audio.live.isRunning) {
            audio.player.stop()
            playingKey = null
            audio.live.start(practiceEngine) {
                scope.launch {
                    droneOn = false
                    drumsOn = false
                    toast("Kein Audio-Ausgang verfügbar")
                }
            }
        } else if (!wanted && audio.live.isRunning) {
            audio.live.stop()
        }
    }

    fun stopPractice() {
        droneOn = false
        drumsOn = false
        playPractice()
        if (practice != settings.practice) updateSettings { it.copy(practice = practice) }
    }

    fun setBpm(bpm: Int) = updatePractice { it.copy(bpm = bpm.coerceIn(PracticeSettings.MIN_BPM, PracticeSettings.MAX_BPM)) }

    /** Tap tempo: the average of the last few taps; a pause of two seconds starts over. */
    fun tapTempo(now: Long = currentTimeMillis()) {
        if (taps.isNotEmpty() && now - taps.last() > 2000) taps.clear()
        taps.addLast(now)
        while (taps.size > 5) taps.removeFirst()
        if (taps.size >= 2) setBpm(((taps.size - 1) * 60_000.0 / (taps.last() - taps.first())).roundToInt())
    }

    // endregion

    // region keys (desktop)

    fun onKey(key: KeyCommand): Boolean {
        if (key == KeyCommand.ESCAPE) return back()
        if (screen == Screen.Practice) {
            when (key) {
                KeyCommand.SPACE -> toggleDrums()
                KeyCommand.DRONE -> toggleDrone()
                KeyCommand.TAP -> tapTempo()
                KeyCommand.FASTER -> setBpm(practice.bpm + 1)
                KeyCommand.SLOWER -> setBpm(practice.bpm - 1)
                else -> return false
            }
            return true
        }
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
                KeyCommand.KEYBOARD -> if (keyboardClip() != null) keyboardOpen = !keyboardOpen else return false
                KeyCommand.SHUFFLE -> shuffle()
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
                KeyCommand.KEYBOARD -> if (keyboardClip() != null) keyboardOpen = !keyboardOpen else return false
                KeyCommand.SHUFFLE -> shuffle()
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

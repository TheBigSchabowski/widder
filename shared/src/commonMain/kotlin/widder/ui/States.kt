package widder.ui

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.graphics.ImageBitmap
import widder.audio.Pcm
import widder.core.Card
import widder.core.ChordRound
import widder.core.Clip
import widder.core.DeckKind
import widder.core.Grade
import widder.core.IncomingFile
import widder.core.PickKind
import widder.core.Progress

sealed interface Screen {
    data object Home : Screen
    data class Decks(val kind: DeckKind) : Screen
    data class Browse(val deckId: String) : Screen
    data object EditCard : Screen
    data class Review(val deckId: String) : Screen
    data class Train(val deckId: String) : Screen
    data object Settings : Screen
    data object Tiles : Screen
    data object Help : Screen

    /** Drone and drums to practise with. */
    data object Practice : Screen

    /** One field of the gesture grid, counted row by row. */
    data class EditTile(val index: Int, val learn: Boolean = false) : Screen

    /** Screens where the time counts towards the reward picture. */
    val isStudy: Boolean get() = this is Review || this is Train
}

/** What the platform does for the app: file dialogs and the microphone permission. */
interface PlatformHooks {
    fun pickFiles(kind: PickKind, onResult: (List<IncomingFile>) -> Unit)

    fun requestMicrophone(onResult: (Boolean) -> Unit) = onResult(true)

    /** The Mac takes files dropped onto the window; the hint is only shown there. */
    val canDropFiles: Boolean get() = false

    /** Asks where to save [bytes] (named [suggestedName]); [onDone] gets where it went, or null. */
    fun saveFile(suggestedName: String, bytes: ByteArray, onDone: (String?) -> Unit) = onDone(null)

    /** A real keyboard: the prefix shortcuts are offered and shown. */
    val keyboard: Boolean get() = false

    /** Key combinations written the Mac way (⌃B) rather than spelled out (Strg+B). */
    val macKeys: Boolean get() = true

    /** Folders the user can see; null where decks cannot be linked to one. */
    val folders: widder.core.FolderAccess? get() = null
}

object NoPlatform : PlatformHooks {
    override fun pickFiles(kind: PickKind, onResult: (List<IncomingFile>) -> Unit) = onResult(emptyList())
}

/** Where a clip lives: on the card being edited, or directly on a stored card (from a review). */
sealed interface ClipTarget {
    val back: Boolean

    data class Editor(override val back: Boolean) : ClipTarget
    data class StoredCard(val cardId: String, override val back: Boolean) : ClipTarget
}

class CardEditor(val original: Card?, val deckId: String, val kind: DeckKind) {
    var front by mutableStateOf(original?.front ?: "")
    var back by mutableStateOf(original?.back ?: "")
    var frontAudio by mutableStateOf(original?.frontAudio)
    var backAudio by mutableStateOf(original?.backAudio)
    var answer by mutableStateOf(original?.answer?.joinToString(" ") ?: "")
    var tags by mutableStateOf(original?.tags?.joinToString(" ") ?: "")
    var key by mutableStateOf(original?.key ?: "")
    var label by mutableStateOf(original?.label ?: "")
    var notes by mutableStateOf(original?.notes ?: "")
    var voicing by mutableStateOf(original?.voicing ?: "")

    fun clip(back: Boolean): Clip? = if (back) backAudio else frontAudio
    fun setClip(back: Boolean, clip: Clip?) {
        if (back) backAudio = clip else frontAudio = clip
    }
}

/** [seed] mixes the order of the cards (see Queue); null = the order they were made. */
class ReviewSession(val deckId: String, var seed: Int? = null) {
    var card by mutableStateOf<Card?>(null)
    var showAnswer by mutableStateOf(false)
    var shownAt = 0L

    /** Before the last answer, for undo. */
    var undo by mutableStateOf<Pair<Card, Progress>?>(null)
    var answered by mutableIntStateOf(0)
    var nextDue by mutableStateOf<Long?>(null)
}

class TrainSession(val deckId: String, var seed: Int? = null) {
    var card by mutableStateOf<Card?>(null)
    var round by mutableStateOf<ChordRound?>(null)

    /** Bumped after every move, because [ChordRound] itself is not observable. */
    var version by mutableIntStateOf(0)
    var suggested by mutableStateOf<Grade?>(null)
    var startedAt = 0L
    var combo by mutableIntStateOf(0)
    var gained by mutableIntStateOf(0)
    var answered by mutableIntStateOf(0)
    var correct by mutableIntStateOf(0)
    var nextDue by mutableStateOf<Long?>(null)
}

class AudioEditorState(val target: ClipTarget, val clip: Clip, val source: Pcm) {
    var edit by mutableStateOf(clip.edit)
    val peaks: FloatArray = source.peaks(800)
    val sourcePeak: Float = source.peak().coerceAtLeast(1e-4f)
    var playing by mutableStateOf(false)

    /** Where in the source the current playback started, to place the play head. */
    var playOffset = 0.0
    var playsOriginal = false
    val duration: Double get() = source.duration
    val selectionEnd: Double get() = edit.trimEnd ?: duration
}

/** [quick]: started from a tile or shortcut; throwing the recording away throws the new card away too. */
class RecordingState(val target: ClipTarget, val quick: Boolean = false) {
    var started = 0L
}

/** A few files waiting to be imported into a chord set, with their answers still editable. */
class ImportPreview(val deckId: String, items: List<Item>) {
    class Item(val file: IncomingFile, answer: String, val fromMidi: Boolean) {
        var answer by mutableStateOf(answer)
        var include by mutableStateOf(true)
    }

    val items: List<Item> = items
}

sealed interface DeckDialog {
    data class New(val kind: DeckKind) : DeckDialog
    data class Options(val deckId: String) : DeckDialog
}

/** An action that needs a deck and has none yet: the user picks one. */
/** Lights up field [index] for a moment; [serial] tells two flashes on the same field apart. */
class TileFlash(val index: Int, val label: String, val serial: Int)

class DeckPick(val kind: widder.core.ActionKind)

/** The shortcut being edited in the settings; null = a new one. */
class ShortcutEdit(val key: Char?)

class Confirm(val title: String, val text: String, val action: String, val run: () -> Unit)

class RewardImages(val sharp: ImageBitmap, val blurred: ImageBitmap)

/** Keys the desktop app forwards while learning. */
enum class KeyCommand { SPACE, ENTER, ONE, TWO, THREE, FOUR, UNDO, REPLAY, EDIT, KEYBOARD, SHUFFLE, BACKSPACE, ESCAPE, DRONE, TAP, FASTER, SLOWER }

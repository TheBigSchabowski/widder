package widder.core

import kotlinx.serialization.Serializable

/** What a tile gesture, a keyboard shortcut, or a home-screen widget does. */
@Serializable
enum class ActionKind(val label: String, val needsDeck: Boolean = false) {
    NONE("Nichts"),
    OPEN_CARDS("Flash Cards öffnen"),
    OPEN_CHORDS("Multiple Choice öffnen"),
    OPEN_PRACTICE("Üben: Drone & Drums"),
    /** Starts the Practice screen with an A drone without the fifth playing right away. */
    DRONE_A_NO_FIFTH("Drone auf A (ohne Quinte)"),
    STUDY("Lernen", needsDeck = true),
    BROWSE("Stapel öffnen", needsDeck = true),
    NEW_CARD("Neue Karte", needsDeck = true),
    RECORD("Aufnehmen → Karte", needsDeck = true),
    IMPORT("Dateien importieren", needsDeck = true),
    REWARD("Bild ansehen"),
    SETTINGS("Einstellungen"),
    HOME("Startbildschirm"),

    /** The options of the chosen or open deck; without one the settings. */
    CONFIG("Konfiguration"),
    HELP("Modi & Tasten"),

    /** While learning: the card on screen in the editor. */
    EDIT_CARD("Karte bearbeiten"),

    /** The keyboard under a MIDI answer, once the answer shows. */
    KEYBOARD("Klaviatur"),

    /** In the editor opened while learning: save and go back to the card. */
    BACK_TO_CARD("Speichern → zurück zur Karte"),
}

/**
 * An action with its deck. Without a deck the action takes the deck that is open (card list,
 * review, training) and asks otherwise.
 */
@Serializable
data class Action(val kind: ActionKind = ActionKind.NONE, val deckId: String? = null) {
    val isNone: Boolean get() = kind == ActionKind.NONE
}

@Serializable
enum class TileColor { AMBER, TEAL, RED, BLUE, GREEN, PURPLE, GREY }

/**
 * One field of the invisible grid over the screen. A normal tap goes to whatever lies
 * underneath; only a double tap or a long press on the field runs its actions.
 */
@Serializable
data class GestureTile(
    val title: String = "",
    val color: TileColor = TileColor.AMBER,
    val doubleTap: Action = Action(),
    val longPress: Action = Action(),
) {
    val isEmpty: Boolean get() = doubleTap.isNone && longPress.isNone

    val displayTitle: String get() = title.ifBlank { listOf(longPress, doubleTap).firstOrNull { !it.isNone }?.kind?.label ?: "leer" }
}

/** Where the grid listens. */
@Serializable
enum class TileScope(val label: String) {
    OFF("aus"),
    HOME("nur Startbildschirm"),
    EVERYWHERE("überall außer Editor und Multiple Choice"),
}

/** A key with its modifiers, e.g. the prefix ⌃B. [key] is "A".."Z", "0".."9" or "Space". */
@Serializable
data class KeyStroke(
    val key: String,
    val ctrl: Boolean = false,
    val alt: Boolean = false,
    val shift: Boolean = false,
    val meta: Boolean = false,
) {
    val hasModifier: Boolean get() = ctrl || alt || meta

    /** A prefix must not swallow normal typing: a modifier, or a function key on its own. */
    val usableAsPrefix: Boolean get() = hasModifier || (key.length > 1 && key[0] == 'F' && key.drop(1).all(Char::isDigit))

    /** On the Mac with its symbols in its order (⌃⌥⇧⌘B), elsewhere spelled out (Strg+Alt+B). */
    fun label(mac: Boolean): String {
        val name = if (key == "Space") "Leertaste" else key
        if (mac) {
            return buildString {
                if (ctrl) append('⌃')
                if (alt) append('⌥')
                if (shift) append('⇧')
                if (meta) append('⌘')
                append(name)
            }
        }
        return (listOfNotNull("Strg".takeIf { ctrl }, "Alt".takeIf { alt }, "Umschalt".takeIf { shift }, "Super".takeIf { meta }) + name).joinToString("+")
    }
}

/** Prefix + [key] runs [action] – like tmux: ⌃B, then a letter (or "?"). */
@Serializable
data class Shortcut(val key: Char, val action: Action)

/** Tiles and keys as they are until the user changes them. */
object Shortcuts {
    val DEFAULT_PREFIX = KeyStroke("B", ctrl = true)

    /** Offered in the settings; anything else can be pressed in. */
    val PREFIXES = listOf(
        DEFAULT_PREFIX,
        KeyStroke("A", ctrl = true),
        KeyStroke("Space", ctrl = true),
        KeyStroke("K", meta = true),
    )

    val defaultShortcuts = listOf(
        Shortcut('F', Action(ActionKind.OPEN_CARDS)),
        Shortcut('M', Action(ActionKind.OPEN_CHORDS)),
        Shortcut('Y', Action(ActionKind.CONFIG)),
        Shortcut('L', Action(ActionKind.STUDY)),
        Shortcut('N', Action(ActionKind.NEW_CARD)),
        Shortcut('R', Action(ActionKind.RECORD)),
        Shortcut('I', Action(ActionKind.IMPORT)),
        Shortcut('O', Action(ActionKind.BROWSE)),
        Shortcut('B', Action(ActionKind.REWARD)),
        Shortcut('S', Action(ActionKind.SETTINGS)),
        Shortcut('H', Action(ActionKind.HOME)),
        Shortcut('?', Action(ActionKind.HELP)),
    )

    /** Prefix + digit picks the deck with that number, so digits are not free for shortcuts. */
    fun bindable(key: Char): Boolean = key.isLetter() || key == '?'

    /** 2 × 2, row by row: the most used actions on the long press, which never also hits a button. */
    val defaultTiles = listOf(
        GestureTile("Lernen", TileColor.TEAL, doubleTap = Action(ActionKind.BROWSE), longPress = Action(ActionKind.STUDY)),
        GestureTile("Neue Karte", TileColor.BLUE, doubleTap = Action(ActionKind.IMPORT), longPress = Action(ActionKind.NEW_CARD)),
        GestureTile("Stapel", TileColor.AMBER, doubleTap = Action(ActionKind.OPEN_CHORDS), longPress = Action(ActionKind.OPEN_CARDS)),
        GestureTile("Aufnehmen", TileColor.RED, doubleTap = Action(ActionKind.HOME), longPress = Action(ActionKind.RECORD)),
    )

    /**
     * While learning, and in the editor opened from there: one column, top to bottom. Each
     * gesture only counts where it can do something, so elsewhere the taps reach the screen.
     */
    val defaultLearnTiles = listOf(
        GestureTile("Bearbeiten", TileColor.BLUE, doubleTap = Action(ActionKind.EDIT_CARD)),
        GestureTile("Klaviatur", TileColor.TEAL, doubleTap = Action(ActionKind.KEYBOARD)),
        GestureTile("Zurück zur Karte", TileColor.GREEN, doubleTap = Action(ActionKind.BACK_TO_CARD)),
    )
}

package widder.desktop

import androidx.compose.foundation.border
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.Modifier
import androidx.compose.ui.draganddrop.DragAndDropEvent
import androidx.compose.ui.draganddrop.DragAndDropTarget
import androidx.compose.ui.draganddrop.awtTransferable
import androidx.compose.foundation.draganddrop.dragAndDropTarget
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.painter.BitmapPainter
import androidx.compose.ui.graphics.painter.Painter
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEvent
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.isAltPressed
import androidx.compose.ui.input.key.isCtrlPressed
import androidx.compose.ui.input.key.isShiftPressed
import androidx.compose.ui.input.key.isMetaPressed
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.nativeKeyCode
import androidx.compose.ui.input.key.utf16CodePoint
import androidx.compose.ui.input.key.type
import androidx.compose.ui.res.loadImageBitmap
import androidx.compose.ui.res.useResource
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Window
import androidx.compose.ui.window.application
import androidx.compose.ui.window.rememberWindowState
import widder.audio.AUDIO_EXTENSIONS
import widder.audio.MIDI_EXTENSIONS
import widder.audio.desktopAudioSystem
import widder.core.DirectoryStore
import widder.core.IncomingFile
import widder.core.KeyStroke
import widder.core.PickKind
import widder.core.Store
import widder.core.UserDirs
import widder.ui.App
import widder.ui.AppViewModel
import widder.ui.KeyCommand
import widder.ui.PlatformHooks
import widder.ui.Screen
import java.awt.FileDialog
import java.awt.Frame
import java.awt.datatransfer.DataFlavor
import java.io.File

fun dataDir(): File = System.getProperty("widder.dataDir")?.let(::File)
    ?: File(UserDirs.documents(), "Widder")

/** The Mac takes the icon from the app bundle; on other systems the window needs it. */
private fun windowIcon(): Painter? =
    if (System.getProperty("os.name").startsWith("Mac")) null else useResource("icon.png") { BitmapPainter(loadImageBitmap(it)) }

@OptIn(ExperimentalComposeUiApi::class)
fun main() = application {
    val viewModel = remember { AppViewModel(Store(DirectoryStore(dataDir())), desktopAudioSystem(), DesktopHooks(dataDir())) }
    DisposableEffect(Unit) { onDispose { viewModel.dispose() } }
    Window(
        onCloseRequest = {
            viewModel.dispose()
            exitApplication()
        },
        title = "Widder",
        icon = remember(::windowIcon),
        // -Dwindow.size=393x852 opens at phone size, to check the narrow layout that Android uses.
        state = System.getProperty("window.size")?.split("x")?.map { it.toInt().dp }
            ?.let { (w, h) -> rememberWindowState(width = w, height = h) }
            ?: rememberWindowState(width = 1100.dp, height = 820.dp),
        onPreviewKeyEvent = { event -> handleKey(viewModel, event) },
    ) {
        var hovering by remember { mutableStateOf(false) }
        val dropTarget = remember {
            object : DragAndDropTarget {
                override fun onEntered(event: DragAndDropEvent) {
                    hovering = true
                }

                override fun onExited(event: DragAndDropEvent) {
                    hovering = false
                }

                override fun onEnded(event: DragAndDropEvent) {
                    hovering = false
                }

                override fun onDrop(event: DragAndDropEvent): Boolean {
                    hovering = false
                    val transferable = event.awtTransferable
                    if (!transferable.isDataFlavorSupported(DataFlavor.javaFileListFlavor)) return false
                    val files = (transferable.getTransferData(DataFlavor.javaFileListFlavor) as List<*>).filterIsInstance<File>()
                    viewModel.receiveFiles(expand(files).map { IncomingFile(it.name, it.readBytes()) })
                    return true
                }
            }
        }
        App(
            viewModel,
            Modifier.dragAndDropTarget(shouldStartDragAndDrop = { true }, target = dropTarget)
                .then(if (hovering) Modifier.border(4.dp, Color(0xFF3FB8A6)) else Modifier),
        )
    }
}

/** Dropped folders count with the audio and MIDI files inside them. */
private fun expand(files: List<File>): List<File> = files.flatMap { f ->
    if (f.isDirectory) {
        f.walkTopDown().filter { it.isFile && (it.extension.lowercase() in AUDIO_EXTENSIONS || it.extension.lowercase() in MIDI_EXTENSIONS) }
            .sortedBy { it.name.lowercase() }.toList()
    } else {
        listOf(f)
    }
}

private val modifierKeys = setOf(
    Key.ShiftLeft, Key.ShiftRight, Key.CtrlLeft, Key.CtrlRight, Key.AltLeft, Key.AltRight, Key.MetaLeft, Key.MetaRight,
)

/** The key as a [KeyStroke]: letters, digits, space, Escape and F1–F12; null for everything else. */
private fun strokeOf(event: KeyEvent): KeyStroke? {
    val code = event.key.nativeKeyCode
    val name = when (code) {
        in java.awt.event.KeyEvent.VK_A..java.awt.event.KeyEvent.VK_Z -> code.toChar().toString()
        in java.awt.event.KeyEvent.VK_0..java.awt.event.KeyEvent.VK_9 -> code.toChar().toString()
        in java.awt.event.KeyEvent.VK_F1..java.awt.event.KeyEvent.VK_F12 -> "F${code - java.awt.event.KeyEvent.VK_F1 + 1}"
        java.awt.event.KeyEvent.VK_SPACE -> "Space"
        java.awt.event.KeyEvent.VK_ESCAPE -> "Escape"
        else -> null
    } ?: return null
    return KeyStroke(name, ctrl = event.isCtrlPressed, alt = event.isAltPressed, shift = event.isShiftPressed, meta = event.isMetaPressed)
}

private fun handleKey(vm: AppViewModel, event: KeyEvent): Boolean {
    if (event.type != KeyEventType.KeyDown || event.key in modifierKeys) return false
    val stroke = strokeOf(event)
    val typed = event.utf16CodePoint.takeIf { it in 1..0xFFFE }?.toChar()
    if (stroke != null) {
        if (vm.onShortcutKey(stroke, typed)) return true
    } else if (vm.prefixPending && typed == '?') {
        // "?" lies on ⇧ß or ⇧/ depending on the layout: only the character says what it is.
        vm.runShortcut('?')
        return true
    } else if (vm.prefixPending || vm.capturingPrefix) {
        // Arrows, Tab and the like neither pick a shortcut nor become the prefix.
        vm.closePrefix()
        vm.capturingPrefix = false
        return true
    }
    val command = event.isMetaPressed || event.isCtrlPressed
    // Typing in text fields (card editor, search) must not trigger shortcuts.
    val study = vm.screen is Screen.Review || vm.screen is Screen.Train || vm.screen == Screen.Practice || vm.audioEditor != null
    val key = when {
        event.key == Key.Escape -> KeyCommand.ESCAPE
        !study -> null
        command && event.key == Key.Z -> KeyCommand.UNDO
        command -> null
        event.key == Key.Spacebar -> KeyCommand.SPACE
        event.key == Key.Enter || event.key == Key.NumPadEnter -> KeyCommand.ENTER
        event.key == Key.One || event.key == Key.NumPad1 -> KeyCommand.ONE
        event.key == Key.Two || event.key == Key.NumPad2 -> KeyCommand.TWO
        event.key == Key.Three || event.key == Key.NumPad3 -> KeyCommand.THREE
        event.key == Key.Four || event.key == Key.NumPad4 -> KeyCommand.FOUR
        event.key == Key.R -> KeyCommand.REPLAY
        event.key == Key.E -> KeyCommand.EDIT
        event.key == Key.K -> KeyCommand.KEYBOARD
        event.key == Key.M -> KeyCommand.SHUFFLE
        event.key == Key.Backspace -> KeyCommand.BACKSPACE
        event.key == Key.D -> KeyCommand.DRONE
        event.key == Key.T -> KeyCommand.TAP
        event.key == Key.V -> KeyCommand.VIBRATO
        event.key == Key.G -> KeyCommand.TONE
        event.key == Key.Z -> KeyCommand.RANDOM
        event.key == Key.L -> KeyCommand.LOOP
        event.key == Key.O -> KeyCommand.OVERDUB
        event.key == Key.DirectionUp -> KeyCommand.FASTER
        event.key == Key.DirectionDown -> KeyCommand.SLOWER
        else -> null
    } ?: return false
    return vm.onKey(key)
}

/** The system's own file dialogs, and folders in Finder or the file manager. */
private class DesktopHooks(dataDir: File) : PlatformHooks {
    override val folders = DesktopFolders(dataDir)

    private var lastDir: String = UserDirs.music().takeIf { it.isDirectory }?.path ?: UserDirs.home().path

    override val canDropFiles: Boolean = true

    override val keyboard: Boolean = true

    override val macKeys: Boolean = System.getProperty("os.name").startsWith("Mac")

    override fun saveFile(suggestedName: String, bytes: ByteArray, onDone: (String?) -> Unit) {
        val dialog = FileDialog(null as Frame?, "Audio speichern", FileDialog.SAVE)
        dialog.directory = UserDirs.downloads().takeIf { it.isDirectory }?.path ?: lastDir
        dialog.file = suggestedName
        dialog.isVisible = true
        val name = dialog.file ?: return onDone(null)
        val file = File(dialog.directory, name)
        file.writeBytes(bytes)
        onDone(file.path.replaceFirst(UserDirs.home().path, "~"))
    }

    override fun pickFiles(kind: PickKind, onResult: (List<IncomingFile>) -> Unit) {
        val dialog = FileDialog(null as Frame?, if (kind == PickKind.IMAGE) "Bild wählen" else "Audio- oder MIDI-Dateien wählen", FileDialog.LOAD)
        dialog.directory = lastDir
        dialog.isMultipleMode = kind == PickKind.AUDIO
        val allowed = if (kind == PickKind.IMAGE) setOf("png", "jpg", "jpeg", "webp", "heic", "gif", "bmp") else AUDIO_EXTENSIONS + MIDI_EXTENSIONS
        dialog.setFilenameFilter { _, name -> name.substringAfterLast('.', "").lowercase() in allowed }
        dialog.isVisible = true
        val files = dialog.files.toList()
        if (files.isNotEmpty()) lastDir = files.first().parent
        onResult(files.map { IncomingFile(it.name, it.readBytes()) })
    }
}

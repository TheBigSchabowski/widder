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
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEvent
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.isCtrlPressed
import androidx.compose.ui.input.key.isMetaPressed
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.type
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Window
import androidx.compose.ui.window.application
import androidx.compose.ui.window.rememberWindowState
import widder.audio.AUDIO_EXTENSIONS
import widder.audio.MIDI_EXTENSIONS
import widder.audio.desktopAudioSystem
import widder.core.DirectoryStore
import widder.core.IncomingFile
import widder.core.PickKind
import widder.core.Store
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
    ?: File(System.getProperty("user.home"), "Documents/Widder")

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

private fun handleKey(vm: AppViewModel, event: KeyEvent): Boolean {
    if (event.type != KeyEventType.KeyDown) return false
    val command = event.isMetaPressed || event.isCtrlPressed
    // Typing in text fields (card editor, search) must not trigger shortcuts.
    val study = vm.screen is Screen.Review || vm.screen is Screen.Train || vm.audioEditor != null
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
        event.key == Key.Backspace -> KeyCommand.BACKSPACE
        else -> null
    } ?: return false
    return vm.onKey(key)
}

/** The native macOS open dialog, and folders in Finder. */
private class DesktopHooks(dataDir: File) : PlatformHooks {
    override val folders = DesktopFolders(dataDir)

    private var lastDir: String = File(System.getProperty("user.home"), "Music").takeIf { it.isDirectory }?.path
        ?: System.getProperty("user.home")

    override val canDropFiles: Boolean = true

    override fun saveFile(suggestedName: String, bytes: ByteArray, onDone: (String?) -> Unit) {
        val dialog = FileDialog(null as Frame?, "Audio speichern", FileDialog.SAVE)
        dialog.directory = File(System.getProperty("user.home"), "Downloads").takeIf { it.isDirectory }?.path ?: lastDir
        dialog.file = suggestedName
        dialog.isVisible = true
        val name = dialog.file ?: return onDone(null)
        val file = File(dialog.directory, name)
        file.writeBytes(bytes)
        onDone(file.path.replaceFirst(System.getProperty("user.home"), "~"))
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

package widder.desktop

import widder.core.FolderAccess
import widder.core.FolderFile
import widder.core.FolderTools
import widder.core.FolderTools.Answer
import widder.core.UserDirs
import java.awt.Desktop
import java.awt.FileDialog
import java.awt.Frame
import java.io.File
import javax.swing.JFileChooser
import javax.swing.SwingUtilities
import kotlin.concurrent.thread

/** Plain folders on the computer; new ones go next to the collection in [dataDir]. */
class DesktopFolders(private val dataDir: File) : FolderAccess {
    private fun stamp(f: File) = f.lastModified() * 31 + f.length()

    override fun list(folder: String): List<FolderFile>? {
        val dir = File(folder)
        if (!dir.isDirectory) return null
        return dir.listFiles()?.filter { it.isFile && !it.isHidden }?.map { FolderFile(it.name, stamp(it)) }
    }

    override fun read(folder: String, name: String): ByteArray? = File(folder, name).takeIf { it.isFile }?.readBytes()

    override fun write(folder: String, name: String, bytes: ByteArray): FolderFile? {
        val file = File(folder, name)
        file.parentFile.mkdirs()
        file.writeBytes(bytes)
        return FolderFile(file.name, stamp(file))
    }

    override fun defaultFolder(name: String): String = File(dataDir, name).apply { mkdirs() }.absolutePath

    override fun choose(onResult: (String?) -> Unit) {
        if (System.getProperty("os.name").startsWith("Mac")) return chooseOnMac(onResult)
        // The dialog tools wait until the user is done; on the event thread the window would hang meanwhile.
        thread(isDaemon = true, name = "folder-dialog") {
            val answer = FolderTools.ask(FolderTools.dialogCommands(dataDir, TITLE))
            SwingUtilities.invokeLater {
                onResult(
                    when (answer) {
                        is Answer.Folder -> answer.path
                        Answer.Cancelled -> null
                        null -> chooseWithSwing()
                    },
                )
            }
        }
    }

    private fun chooseOnMac(onResult: (String?) -> Unit) {
        // The native macOS dialog picks folders instead of files with this switch.
        System.setProperty("apple.awt.fileDialogForDirectories", "true")
        try {
            val dialog = FileDialog(null as Frame?, TITLE, FileDialog.LOAD)
            dialog.directory = dataDir.absolutePath
            dialog.isVisible = true
            onResult(dialog.file?.let { File(dialog.directory, it).absolutePath })
        } finally {
            System.setProperty("apple.awt.fileDialogForDirectories", "false")
        }
    }

    /** All that is left when neither zenity nor kdialog is installed: plain, but it works everywhere. */
    private fun chooseWithSwing(): String? {
        val chooser = JFileChooser(dataDir).apply {
            fileSelectionMode = JFileChooser.DIRECTORIES_ONLY
            dialogTitle = TITLE
        }
        return if (chooser.showOpenDialog(null) == JFileChooser.APPROVE_OPTION) chooser.selectedFile.absolutePath else null
    }

    override fun open(folder: String): Boolean {
        try {
            if (Desktop.isDesktopSupported() && Desktop.getDesktop().isSupported(Desktop.Action.OPEN)) {
                Desktop.getDesktop().open(File(folder))
                return true
            }
        } catch (e: Exception) {
            // Linux without the GTK libraries AWT wants, or a folder that is gone: xdg-open has the last word.
        }
        return FolderTools.show(File(folder))
    }

    override fun describe(folder: String): String = folder.replaceFirst(UserDirs.home().path, "~")

    private companion object {
        const val TITLE = "Ordner wählen"
    }
}

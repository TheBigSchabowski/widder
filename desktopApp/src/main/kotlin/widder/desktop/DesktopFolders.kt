package widder.desktop

import widder.core.FolderAccess
import widder.core.FolderFile
import java.awt.Desktop
import java.awt.FileDialog
import java.awt.Frame
import java.io.File

/** Plain folders on the Mac; new ones go next to the collection in [dataDir]. */
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
        // The native macOS dialog picks folders instead of files with this switch.
        System.setProperty("apple.awt.fileDialogForDirectories", "true")
        try {
            val dialog = FileDialog(null as Frame?, "Ordner wählen", FileDialog.LOAD)
            dialog.directory = dataDir.absolutePath
            dialog.isVisible = true
            onResult(dialog.file?.let { File(dialog.directory, it).absolutePath })
        } finally {
            System.setProperty("apple.awt.fileDialogForDirectories", "false")
        }
    }

    override fun open(folder: String): Boolean = try {
        Desktop.getDesktop().open(File(folder))
        true
    } catch (e: Exception) {
        false
    }

    override fun describe(folder: String): String = folder.replaceFirst(System.getProperty("user.home"), "~")
}

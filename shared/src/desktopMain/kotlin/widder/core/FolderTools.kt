package widder.core

import java.io.File
import java.io.IOException
import java.util.concurrent.TimeUnit

/**
 * What a desktop without macOS needs a program for. AWT's file dialog picks folders on macOS only; GNOME
 * ships zenity and KDE kdialog, and both show the dialog the user knows from other programs. Showing a
 * folder in the file manager is `xdg-open` where AWT lacks the GTK libraries for Desktop.open.
 */
object FolderTools {
    sealed interface Answer {
        class Folder(val path: String) : Answer

        data object Cancelled : Answer
    }

    /** The dialog tools to try, the one that fits [desktop] first. */
    fun dialogCommands(start: File, title: String, desktop: String = System.getenv("XDG_CURRENT_DESKTOP").orEmpty()): List<List<String>> {
        val zenity = listOf("zenity", "--file-selection", "--directory", "--title=$title", "--filename=${start.absolutePath.trimEnd('/')}/")
        val kdialog = listOf("kdialog", "--title", title, "--getexistingdirectory", start.absolutePath)
        return if (desktop.contains("KDE", ignoreCase = true)) listOf(kdialog, zenity) else listOf(zenity, kdialog)
    }

    /**
     * Runs the tools one after the other until one answers with a folder or a cancelled dialog. Null if none
     * is installed or none could open a dialog (no display, say). Blocks until the dialog is closed.
     */
    fun ask(commands: List<List<String>>): Answer? = commands.firstNotNullOfOrNull(::askOne)

    private fun askOne(command: List<String>): Answer? {
        val process = try {
            ProcessBuilder(command).redirectError(ProcessBuilder.Redirect.DISCARD).start()
        } catch (e: IOException) {
            return null
        }
        // Read first, wait afterwards: a tool that fills the pipe would never finish otherwise.
        val output = process.inputStream.bufferedReader().use { it.readText() }.trim()
        return when (process.waitFor()) {
            0 -> if (output.isEmpty()) Answer.Cancelled else Answer.Folder(output)
            1 -> Answer.Cancelled
            else -> null
        }
    }

    /** Opens [folder] in the file manager; false if there is no way to or the command says it failed. */
    fun show(folder: File, command: List<String> = listOf("xdg-open")): Boolean = try {
        val process = ProcessBuilder(command + folder.path)
            .redirectOutput(ProcessBuilder.Redirect.DISCARD)
            .redirectError(ProcessBuilder.Redirect.DISCARD)
            .start()
        // Some file managers keep xdg-open waiting for as long as they run: no answer within a second means it worked.
        !process.waitFor(1, TimeUnit.SECONDS) || process.exitValue() == 0
    } catch (e: IOException) {
        false
    }
}

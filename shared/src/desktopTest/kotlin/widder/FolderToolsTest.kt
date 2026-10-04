package widder

import org.junit.Assume.assumeTrue
import widder.core.FolderTools
import widder.core.FolderTools.Answer
import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue

class FolderToolsTest {
    /** A stand-in for a tool: runs [script] with sh, so a temp folder mounted noexec does no harm. */
    private fun tool(script: String): List<String> {
        assumeTrue(File("/bin/sh").canExecute())
        val file = File.createTempFile("folder-tool", ".sh").apply {
            deleteOnExit()
            writeText(script)
        }
        return listOf("/bin/sh", file.path)
    }

    /** A dialog tool that prints [output] and exits with [exitCode]. */
    private fun dialog(output: String, exitCode: Int) = tool("printf '%s\\n' '$output'\nexit $exitCode\n")

    private fun folder(answer: Answer?): String = assertIs<Answer.Folder>(answer).path

    private val missing = listOf("/gibt/es/nicht/zenity")

    @Test
    fun theFolderTheToolPrintsIsTheAnswer() {
        assertEquals("/home/linus/Musik/Akkorde mit Leerzeichen", folder(FolderTools.ask(listOf(dialog("/home/linus/Musik/Akkorde mit Leerzeichen", 0)))))
    }

    @Test
    fun exitCodeOneMeansCancelledAndNoSecondDialogFollows() {
        assertEquals(Answer.Cancelled, FolderTools.ask(listOf(dialog("", 1), dialog("/zweiter/Versuch", 0))))
    }

    @Test
    fun aToolThatCannotOpenADialogIsSkipped() {
        assertEquals("/zweiter/Versuch", folder(FolderTools.ask(listOf(dialog("", 255), dialog("/zweiter/Versuch", 0)))))
    }

    @Test
    fun aToolThatIsNotInstalledIsSkipped() {
        assertEquals("/zweiter/Versuch", folder(FolderTools.ask(listOf(missing, dialog("/zweiter/Versuch", 0)))))
        assertNull(FolderTools.ask(listOf(missing)))
        assertNull(FolderTools.ask(emptyList()))
    }

    @Test
    fun noOutputAtAllCountsAsCancelled() {
        assertEquals(Answer.Cancelled, FolderTools.ask(listOf(dialog("", 0))))
    }

    @Test
    fun theDialogCommandsStartWithTheToolOfTheDesktop() {
        val start = File("/home/linus/Dokumente/Widder")
        assertEquals("zenity", FolderTools.dialogCommands(start, "Ordner wählen", "GNOME").first().first())
        assertEquals("zenity", FolderTools.dialogCommands(start, "Ordner wählen", "").first().first())
        assertEquals(listOf("kdialog", "zenity"), FolderTools.dialogCommands(start, "Ordner wählen", "KDE").map { it.first() })
    }

    @Test
    fun theDialogsOpenInTheGivenFolder() {
        val (zenity, kdialog) = FolderTools.dialogCommands(File("/home/linus/Dokumente/Widder"), "Ordner wählen", "GNOME")
        // zenity needs the slash to show the inside of the folder instead of selecting it.
        assertEquals("--filename=/home/linus/Dokumente/Widder/", zenity.last())
        assertEquals("/home/linus/Dokumente/Widder", kdialog.last())
    }

    @Test
    fun showHandsTheFolderToTheCommand() {
        val folder = File.createTempFile("shown", ".txt").apply { deleteOnExit() }
        // $1 is the folder: the "command" writes it into the file, which proves it got there.
        val command = tool("printf '%s' \"\$1\" > '${folder.path}'\n")
        assertTrue(FolderTools.show(File("/ein/Ordner mit Leerzeichen"), command))
        assertEquals("/ein/Ordner mit Leerzeichen", folder.readText())
    }

    @Test
    fun showReportsAFailingOrMissingCommand() {
        assertFalse(FolderTools.show(File("/egal"), tool("exit 2\n")))
        assertFalse(FolderTools.show(File("/egal"), missing))
    }

    @Test
    fun aCommandThatKeepsRunningCountsAsOpened() {
        assertTrue(FolderTools.show(File("/egal"), tool("sleep 3\n")))
    }
}

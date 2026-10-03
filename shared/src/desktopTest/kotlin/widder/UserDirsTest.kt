package widder

import widder.core.UserDirs
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class UserDirsTest {
    private val home = "/home/linus"

    private val german = """
        # This file is written by xdg-user-dirs-update
        XDG_DESKTOP_DIR="${'$'}HOME/Schreibtisch"
        XDG_DOWNLOAD_DIR="${'$'}HOME/Downloads"
        XDG_DOCUMENTS_DIR="${'$'}HOME/Dokumente"
        XDG_MUSIC_DIR="/mnt/musik"
        XDG_VIDEOS_DIR="${'$'}HOME/"
    """.trimIndent()

    @Test
    fun aFolderBelowHomeIsReadWithItsTranslatedName() {
        assertEquals("/home/linus/Dokumente", UserDirs.resolve(german, "XDG_DOCUMENTS_DIR", home))
    }

    @Test
    fun anAbsolutePathIsTakenAsItIs() {
        assertEquals("/mnt/musik", UserDirs.resolve(german, "XDG_MUSIC_DIR", home))
    }

    @Test
    fun theHomeFolderItselfMeansTheFolderIsSwitchedOff() {
        assertNull(UserDirs.resolve(german, "XDG_VIDEOS_DIR", home))
    }

    @Test
    fun aMissingKeyOrAMissingFileIsNull() {
        assertNull(UserDirs.resolve(german, "XDG_PICTURES_DIR", home))
        assertNull(UserDirs.resolve(null, "XDG_DOCUMENTS_DIR", home))
    }

    @Test
    fun bracesAroundHomeAndCommentsAreFine() {
        val text = "# XDG_DOCUMENTS_DIR=\"/nope\"\nXDG_DOCUMENTS_DIR=\"${'$'}{HOME}/Docs\"\n"
        assertEquals("/home/linus/Docs", UserDirs.resolve(text, "XDG_DOCUMENTS_DIR", home))
    }

    @Test
    fun theHomeVariableWinsOverJavasOwnGuess() {
        assertEquals("/home/linus", UserDirs.homeFolder("/home/linus", "/root"))
        assertEquals("/root", UserDirs.homeFolder(null, "/root"))
        assertEquals("/root", UserDirs.homeFolder("", "/root"))
        assertEquals("/root", UserDirs.homeFolder("relative/dir", "/root"))
    }
}

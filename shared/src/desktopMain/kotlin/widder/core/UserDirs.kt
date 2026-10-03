package widder.core

import java.io.File

/**
 * The folders a desktop user knows by name. On Linux they are set in `~/.config/user-dirs.dirs` and
 * follow the language ("Dokumente", "Herunterladen"); the English names are the fallback, and what macOS has.
 */
object UserDirs {
    fun home(): File = File(homeFolder(System.getenv("HOME"), System.getProperty("user.home")))

    fun documents(): File = lookup("XDG_DOCUMENTS_DIR", "Documents")

    fun downloads(): File = lookup("XDG_DOWNLOAD_DIR", "Downloads")

    fun music(): File = lookup("XDG_MUSIC_DIR", "Music")

    private fun lookup(key: String, fallback: String): File {
        val home = home()
        val config = System.getenv("XDG_CONFIG_HOME")?.takeIf { it.isNotBlank() }?.let(::File) ?: File(home, ".config")
        val text = File(config, "user-dirs.dirs").takeIf { it.isFile }?.readText()
        return resolve(text, key, home.path)?.let(::File) ?: File(home, fallback)
    }

    // Java reads user.home from the password file, while the XDG rules and the shell's environment go by $HOME.
    internal fun homeFolder(env: String?, property: String): String = env?.takeIf { it.startsWith("/") } ?: property

    /**
     * The folder [key] is set to in the text of `user-dirs.dirs`, or null if it is not set. The home
     * folder itself means "no such folder" there, which is null as well.
     */
    internal fun resolve(text: String?, key: String, home: String): String? {
        val value = text?.lineSequence()
            ?.map { it.trim() }
            ?.firstNotNullOfOrNull { line ->
                line.takeIf { it.startsWith("$key=") }?.substringAfter('=')?.trim()?.removeSurrounding("\"")
            }
            ?: return null
        val path = when {
            value.startsWith("\$HOME") -> home + value.removePrefix("\$HOME")
            value.startsWith("\${HOME}") -> home + value.removePrefix("\${HOME}")
            else -> value
        }
        return path.takeIf { it.startsWith("/") && it.trimEnd('/') != home.trimEnd('/') }
    }
}

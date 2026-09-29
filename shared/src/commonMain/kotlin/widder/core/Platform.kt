package widder.core

/** Where the collection is kept: a folder on the Mac, app storage on Android. */
interface FileStore {
    /** Shown to the user, e.g. the folder path. */
    val location: String

    fun read(name: String): String?

    /** Must replace the file atomically so a crash never leaves half-written progress behind. */
    fun write(name: String, text: String)

    fun readBytes(name: String): ByteArray?

    fun writeBytes(name: String, bytes: ByteArray)

    fun delete(name: String)

    fun exists(name: String): Boolean
}

expect fun currentTimeMillis(): Long

/** Epoch millis of [hour] o'clock, [daysAhead] local days after the day containing `now - hour`. */
expect fun dayStart(now: Long, daysAhead: Long, hour: Int): Long

/** Local calendar date of [epochMillis] as "yyyy-mm-dd". */
expect fun isoDate(epochMillis: Long): String

/** Local time of [epochMillis] in a java.time-style [pattern] such as "dd.MM. HH:mm". */
expect fun formatTime(epochMillis: Long, pattern: String): String

expect fun newId(): String

/** A file the user picked, dropped onto the window or shared with the app. */
class IncomingFile(val name: String, val bytes: ByteArray) {
    val extension: String get() = name.substringAfterLast('.', "").lowercase()
}

enum class PickKind { AUDIO, IMAGE }

/** A file in a deck's folder; [stamp] changes whenever the file does (modified time and size). */
class FolderFile(val name: String, val stamp: Long)

/**
 * Folders the user can reach with Finder or a file manager, that a deck mirrors. A folder is
 * identified by a platform string: a path on the Mac, a document-tree URI on Android.
 */
interface FolderAccess {
    /** The files in [folder], or null when it cannot be read (gone, no permission). */
    fun list(folder: String): List<FolderFile>?

    fun read(folder: String, name: String): ByteArray?

    /** Writes (or replaces) a file; returns it as listed afterwards. */
    fun write(folder: String, name: String, bytes: ByteArray): FolderFile?

    /** A folder the app may create on its own for [name] (the Mac: next to the collection), or null. */
    fun defaultFolder(name: String): String?

    /** Lets the user pick a folder; [onResult] gets it, or null if cancelled. */
    fun choose(onResult: (String?) -> Unit)

    /** Shows the folder in Finder / the file manager; false if that is not possible. */
    fun open(folder: String): Boolean

    /** For display, e.g. "~/Documents/Widder/Startkit A-Akkorde". */
    fun describe(folder: String): String
}

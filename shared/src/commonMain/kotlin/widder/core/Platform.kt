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

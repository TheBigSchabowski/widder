package widder.core

import kotlinx.serialization.json.Json

/**
 * The collection (decks, cards, settings) and the learning progress, as two JSON files, plus the
 * media folder with the audio and MIDI files the cards point to.
 */
class Store(private val files: FileStore) {
    private val json = Json {
        prettyPrint = true
        ignoreUnknownKeys = true
        encodeDefaults = false
    }

    val location: String get() = files.location

    fun loadCollection(): Collection? = read("collection.json")

    fun saveCollection(collection: Collection) = files.write("collection.json", json.encodeToString(collection))

    fun loadProgress(): Progress = read("progress.json") ?: Progress()

    fun saveProgress(progress: Progress) = files.write("progress.json", json.encodeToString(progress))

    fun readMedia(name: String): ByteArray? = files.readBytes("media/$name")

    /** Stores [bytes] under a new name that keeps the extension; returns that name. */
    fun addMedia(originalName: String, bytes: ByteArray): String {
        val ext = originalName.substringAfterLast('.', "bin").lowercase()
        val base = originalName.substringBeforeLast('.').filter { it.isLetterOrDigit() || it == '-' || it == '_' }.take(40)
        val name = "${base.ifEmpty { "audio" }}-${newId()}.$ext"
        files.writeBytes("media/$name", bytes)
        return name
    }

    fun deleteMedia(name: String) = files.delete("media/$name")

    private inline fun <reified T> read(name: String): T? {
        val text = files.read(name) ?: return null
        return try {
            json.decodeFromString<T>(text)
        } catch (e: Exception) {
            // Keep the unreadable file instead of silently overwriting the user's collection.
            files.write("$name.broken-${currentTimeMillis()}", text)
            null
        }
    }

    companion object {
        fun learningDay(now: Long = currentTimeMillis()): String = isoDate(Fsrs.startOfDay(now))
    }
}

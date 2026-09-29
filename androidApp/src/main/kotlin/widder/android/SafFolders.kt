package widder.android

import android.content.ContentResolver
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.provider.DocumentsContract
import android.provider.DocumentsContract.Document
import widder.core.FolderAccess
import widder.core.FolderFile

/**
 * Folders through Android's document picker: the user chooses one once (e.g. "Documents/Widder"),
 * the app keeps the permission and reads and writes the files there. Folders the app picks on its
 * own would not be visible in the Files app, so there is no default folder.
 */
class SafFolders(
    private val context: Context,
    private val pickTree: (onResult: (Uri?) -> Unit) -> Unit,
) : FolderAccess {
    private val resolver: ContentResolver get() = context.contentResolver

    private class Entry(val id: String, val name: String, val stamp: Long)

    private fun tree(folder: String) = Uri.parse(folder)

    private fun entries(folder: String): List<Entry>? = try {
        val tree = tree(folder)
        val children = DocumentsContract.buildChildDocumentsUriUsingTree(tree, DocumentsContract.getTreeDocumentId(tree))
        val columns = arrayOf(
            Document.COLUMN_DOCUMENT_ID,
            Document.COLUMN_DISPLAY_NAME,
            Document.COLUMN_LAST_MODIFIED,
            Document.COLUMN_SIZE,
            Document.COLUMN_MIME_TYPE,
        )
        resolver.query(children, columns, null, null, null)?.use { c ->
            buildList {
                while (c.moveToNext()) {
                    if (c.getString(4) == Document.MIME_TYPE_DIR) continue
                    add(Entry(c.getString(0), c.getString(1), c.getLong(2) * 31 + c.getLong(3)))
                }
            }
        }
    } catch (e: Exception) {
        null
    }

    override fun list(folder: String): List<FolderFile>? = entries(folder)?.map { FolderFile(it.name, it.stamp) }

    override fun read(folder: String, name: String): ByteArray? = try {
        val entry = entries(folder)?.firstOrNull { it.name == name }
        entry?.let { e -> resolver.openInputStream(DocumentsContract.buildDocumentUriUsingTree(tree(folder), e.id))?.use { it.readBytes() } }
    } catch (e: Exception) {
        null
    }

    override fun write(folder: String, name: String, bytes: ByteArray): FolderFile? = try {
        val tree = tree(folder)
        val uri = entries(folder)?.firstOrNull { it.name == name }?.let { DocumentsContract.buildDocumentUriUsingTree(tree, it.id) }
            ?: DocumentsContract.createDocument(
                resolver,
                DocumentsContract.buildDocumentUriUsingTree(tree, DocumentsContract.getTreeDocumentId(tree)),
                mimeType(name),
                name,
            )
        uri?.let { target ->
            resolver.openOutputStream(target, "wt")?.use { it.write(bytes) }
            // The provider may adjust the name; take what it lists.
            val id = DocumentsContract.getDocumentId(target)
            entries(folder)?.firstOrNull { it.id == id }?.let { FolderFile(it.name, it.stamp) }
        }
    } catch (e: Exception) {
        null
    }

    private fun mimeType(name: String) = when (name.substringAfterLast('.').lowercase()) {
        "mid", "midi" -> "audio/midi"
        "wav" -> "audio/x-wav"
        "mp3" -> "audio/mpeg"
        else -> "application/octet-stream"
    }

    override fun defaultFolder(name: String): String? = null

    override fun choose(onResult: (String?) -> Unit) = pickTree { uri ->
        if (uri == null) return@pickTree onResult(null)
        // Keep access across restarts.
        resolver.takePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION)
        onResult(uri.toString())
    }

    override fun open(folder: String): Boolean = try {
        val tree = tree(folder)
        val uri = DocumentsContract.buildDocumentUriUsingTree(tree, DocumentsContract.getTreeDocumentId(tree))
        val intent = Intent(Intent.ACTION_VIEW).setDataAndType(uri, Document.MIME_TYPE_DIR)
            .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_ACTIVITY_NEW_TASK)
        context.startActivity(intent)
        true
    } catch (e: Exception) {
        false
    }

    /** "primary:Documents/Widder" -> "Documents/Widder". */
    override fun describe(folder: String): String = try {
        DocumentsContract.getTreeDocumentId(tree(folder)).substringAfter(':').ifEmpty { "Speicher" }
    } catch (e: Exception) {
        folder
    }
}

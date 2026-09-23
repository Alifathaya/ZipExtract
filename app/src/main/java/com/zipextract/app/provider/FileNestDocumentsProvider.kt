package com.zipextract.app.provider

import android.content.Context
import android.content.pm.ProviderInfo
import android.database.Cursor
import android.database.MatrixCursor
import android.os.CancellationSignal
import android.os.Environment
import android.os.ParcelFileDescriptor
import android.provider.DocumentsContract.Document
import android.provider.DocumentsContract.Root
import android.provider.DocumentsProvider
import android.webkit.MimeTypeMap
import com.zipextract.app.R
import java.io.File
import java.io.FileNotFoundException

/**
 * Exposes device storage through the system file picker (SAF),
 * so FileNest appears when other apps / Chrome show "Choose file".
 */
class FileNestDocumentsProvider : DocumentsProvider() {

    override fun onCreate(): Boolean = true

    override fun attachInfo(context: Context, info: ProviderInfo) {
        super.attachInfo(context, info)
        // Exported + MANAGE_DOCUMENTS is required for DocumentsUI; refuse wrong authority.
        if (info.authority != AUTHORITY(context)) {
            throw SecurityException("Invalid authority for FileNestDocumentsProvider")
        }
    }

    override fun queryRoots(projection: Array<out String>?): Cursor {
        val result = MatrixCursor(projection ?: DEFAULT_ROOT_PROJECTION)
        val row = result.newRow()
        row.add(Root.COLUMN_ROOT_ID, ROOT_ID)
        row.add(Root.COLUMN_DOCUMENT_ID, docIdFor(storageRoot()))
        row.add(Root.COLUMN_TITLE, context?.getString(R.string.app_name) ?: "FileNest")
        row.add(Root.COLUMN_SUMMARY, context?.getString(R.string.documents_root_summary))
        row.add(Root.COLUMN_ICON, R.mipmap.ic_launcher)
        row.add(Root.COLUMN_FLAGS, Root.FLAG_LOCAL_ONLY or Root.FLAG_SUPPORTS_IS_CHILD)
        row.add(Root.COLUMN_MIME_TYPES, "*/*")
        row.add(Root.COLUMN_AVAILABLE_BYTES, storageRoot().usableSpace)
        return result
    }

    override fun queryDocument(documentId: String, projection: Array<out String>?): Cursor {
        val result = MatrixCursor(projection ?: DEFAULT_DOCUMENT_PROJECTION)
        includeFile(result, fileForDocId(documentId))
        return result
    }

    override fun queryChildDocuments(
        parentDocumentId: String,
        projection: Array<out String>?,
        sortOrder: String?,
    ): Cursor {
        val result = MatrixCursor(projection ?: DEFAULT_DOCUMENT_PROJECTION)
        val parent = fileForDocId(parentDocumentId)
        if (!parent.isDirectory || !parent.canRead()) return result
        val children = parent.listFiles()?.sortedWith(
            compareBy<File> { !it.isDirectory }.thenBy { it.name.lowercase() },
        ) ?: return result
        for (child in children) {
            if (child.name.startsWith(".")) continue
            includeFile(result, child)
        }
        return result
    }

    override fun isChildDocument(parentDocumentId: String, documentId: String): Boolean {
        return documentId == parentDocumentId || documentId.startsWith("$parentDocumentId/")
    }

    override fun openDocument(
        documentId: String,
        mode: String,
        signal: CancellationSignal?,
    ): ParcelFileDescriptor {
        val file = fileForDocId(documentId)
        if (!file.exists() || !file.isFile) {
            throw FileNotFoundException("Missing $documentId")
        }
        val access = when {
            mode.contains("w") && mode.contains("r") -> ParcelFileDescriptor.MODE_READ_WRITE
            mode.contains("w") -> ParcelFileDescriptor.MODE_WRITE_ONLY
            else -> ParcelFileDescriptor.MODE_READ_ONLY
        }
        return ParcelFileDescriptor.open(file, access)
    }

    private fun includeFile(result: MatrixCursor, file: File) {
        if (!file.exists()) return
        val mime = if (file.isDirectory) {
            Document.MIME_TYPE_DIR
        } else {
            mimeTypeFor(file.name)
        }
        val flags = 0
        val row = result.newRow()
        row.add(Document.COLUMN_DOCUMENT_ID, docIdFor(file))
        row.add(Document.COLUMN_DISPLAY_NAME, file.name)
        row.add(Document.COLUMN_SIZE, if (file.isFile) file.length() else null)
        row.add(Document.COLUMN_MIME_TYPE, mime)
        row.add(Document.COLUMN_LAST_MODIFIED, file.lastModified())
        row.add(Document.COLUMN_FLAGS, flags)
        row.add(Document.COLUMN_ICON, null)
    }

    private fun storageRoot(): File {
        val ext = Environment.getExternalStorageDirectory()
        if (ext != null && ext.exists()) return ext
        return context?.filesDir ?: File("/storage/emulated/0")
    }

    private fun docIdFor(file: File): String {
        val root = storageRoot().canonicalFile
        val target = file.canonicalFile
        if (target == root) return "$ROOT_ID:"
        val relative = target.absolutePath.removePrefix(root.absolutePath)
            .trimStart('/', '\\')
        return "$ROOT_ID:$relative"
    }

    private fun fileForDocId(documentId: String): File {
        val root = storageRoot().canonicalFile
        if (documentId == ROOT_ID || documentId == "$ROOT_ID:") return root
        require(documentId.startsWith("$ROOT_ID:")) { "Invalid document $documentId" }
        val relative = documentId.removePrefix("$ROOT_ID:")
        if (relative.isBlank()) return root
        // Prevent path traversal outside the storage root.
        val target = File(root, relative).canonicalFile
        require(target.path == root.path || target.path.startsWith(root.path + File.separator)) {
            "Invalid path"
        }
        return target
    }

    companion object {
        fun AUTHORITY(context: Context): String = "${context.packageName}.documents"

        private const val ROOT_ID = "filenest"

        private val DEFAULT_ROOT_PROJECTION = arrayOf(
            Root.COLUMN_ROOT_ID,
            Root.COLUMN_DOCUMENT_ID,
            Root.COLUMN_TITLE,
            Root.COLUMN_SUMMARY,
            Root.COLUMN_FLAGS,
            Root.COLUMN_ICON,
            Root.COLUMN_MIME_TYPES,
            Root.COLUMN_AVAILABLE_BYTES,
        )

        private val DEFAULT_DOCUMENT_PROJECTION = arrayOf(
            Document.COLUMN_DOCUMENT_ID,
            Document.COLUMN_DISPLAY_NAME,
            Document.COLUMN_MIME_TYPE,
            Document.COLUMN_SIZE,
            Document.COLUMN_LAST_MODIFIED,
            Document.COLUMN_FLAGS,
            Document.COLUMN_ICON,
        )

        private fun mimeTypeFor(name: String): String {
            val ext = name.substringAfterLast('.', "").lowercase()
            if (ext == "apk") return "application/vnd.android.package-archive"
            val fromMap = MimeTypeMap.getSingleton().getMimeTypeFromExtension(ext)
            return fromMap ?: "application/octet-stream"
        }
    }
}

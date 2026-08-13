package com.tcrrry.desktop.install

import android.content.Context
import android.database.Cursor
import android.net.Uri
import android.os.Environment
import android.provider.DocumentsContract
import androidx.core.content.FileProvider
import com.tcrrry.desktop.model.ApkEntry
import com.tcrrry.desktop.model.ApkSource
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import java.io.File
import java.util.ArrayDeque
import kotlin.coroutines.coroutineContext

internal data class ScannedApkRecord(
    val uri: String,
    val displayName: String,
    val sizeBytes: Long,
    val lastModified: Long,
    val source: ApkSource,
)

internal object ApkScanRules {
    fun <Node> breadthFirst(
        roots: List<Node>,
        isDirectory: (Node) -> Boolean,
        children: (Node) -> List<Node>,
        toRecord: (Node) -> ScannedApkRecord?,
        checkpoint: () -> Unit,
    ): List<ScannedApkRecord> {
        val pending = ArrayDeque<Node>()
        pending.addAll(roots)
        val results = ArrayList<ScannedApkRecord>()
        while (pending.isNotEmpty()) {
            checkpoint()
            val node = pending.removeFirst()
            if (isDirectory(node)) {
                val next = try {
                    children(node)
                } catch (_: SecurityException) {
                    emptyList()
                }
                next.forEach {
                    checkpoint()
                    pending += it
                }
            } else {
                toRecord(node)?.takeIf { it.displayName.endsWith(".apk", ignoreCase = true) }?.let(results::add)
            }
        }
        return results
    }

    fun stable(records: List<ScannedApkRecord>): List<ScannedApkRecord> = records
        .asSequence()
        .filter { it.displayName.endsWith(".apk", ignoreCase = true) }
        .sortedWith(compareByDescending<ScannedApkRecord> { it.lastModified }.thenBy(ScannedApkRecord::displayName))
        .distinctBy(ScannedApkRecord::uri)
        .toList()
}

class ApkScanner(
    private val context: Context,
) {
    suspend fun scan(
        downloadDirectory: File? = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS),
        externalTrees: List<Uri> = emptyList(),
    ): List<ApkEntry> = withContext(Dispatchers.IO) {
        val records = ArrayList<ScannedApkRecord>()
        if (downloadDirectory?.canRead() == true) {
            records += scanFileTree(downloadDirectory)
        }
        externalTrees.forEach { treeUri ->
            coroutineContext.ensureActive()
            records += scanDocumentTree(treeUri)
        }
        ApkScanRules.stable(records).map { record ->
            ApkEntry(
                contentUri = Uri.parse(record.uri),
                displayName = record.displayName,
                sizeBytes = record.sizeBytes,
                lastModified = record.lastModified,
                source = record.source,
            )
        }
    }

    private suspend fun scanFileTree(root: File): List<ScannedApkRecord> {
        val activeContext = coroutineContext
        return ApkScanRules.breadthFirst(
            roots = listOf(root),
            isDirectory = { it.isDirectory && it.canRead() },
            children = { it.listFiles()?.toList().orEmpty() },
            toRecord = { file ->
                if (!file.isFile || !file.canRead()) return@breadthFirst null
                val uri = try {
                    FileProvider.getUriForFile(context, "${context.packageName}.fileprovider", file)
                } catch (_: IllegalArgumentException) {
                    return@breadthFirst null
                }
                ScannedApkRecord(
                    uri = uri.toString(),
                    displayName = file.name,
                    sizeBytes = file.length(),
                    lastModified = file.lastModified(),
                    source = ApkSource.DOWNLOADS,
                )
            },
            checkpoint = activeContext::ensureActive,
        )
    }

    private suspend fun scanDocumentTree(
        rootTreeUri: Uri,
    ): List<ScannedApkRecord> {
        val rootDocumentUri = DocumentsContract.buildDocumentUriUsingTree(
            rootTreeUri,
            DocumentsContract.getTreeDocumentId(rootTreeUri),
        )
        val activeContext = coroutineContext
        return ApkScanRules.breadthFirst(
            roots = listOf(DocumentChild(rootDocumentUri, "", 0L, 0L, isDirectory = true)),
            isDirectory = DocumentChild::isDirectory,
            children = { directory ->
                val childrenUri = DocumentsContract.buildChildDocumentsUriUsingTree(
                    directory.uri,
                    DocumentsContract.getDocumentId(directory.uri),
                )
                queryDocumentChildren(childrenUri)
            },
            toRecord = { child ->
                ScannedApkRecord(
                    uri = child.uri.toString(),
                    displayName = child.displayName,
                    sizeBytes = child.sizeBytes,
                    lastModified = child.lastModified,
                    source = ApkSource.EXTERNAL_TREE,
                )
            },
            checkpoint = activeContext::ensureActive,
        )
    }

    private fun queryDocumentChildren(childrenUri: Uri): List<DocumentChild> {
        val resolver = context.contentResolver
        val projection = arrayOf(
            DocumentsContract.Document.COLUMN_DOCUMENT_ID,
            DocumentsContract.Document.COLUMN_DISPLAY_NAME,
            DocumentsContract.Document.COLUMN_MIME_TYPE,
            DocumentsContract.Document.COLUMN_SIZE,
            DocumentsContract.Document.COLUMN_LAST_MODIFIED,
        )
        val result = ArrayList<DocumentChild>()
        try {
            resolver.query(childrenUri, projection, null, null, null)?.use { cursor ->
                val idIndex = cursor.getColumnIndex(DocumentsContract.Document.COLUMN_DOCUMENT_ID)
                val nameIndex = cursor.getColumnIndex(DocumentsContract.Document.COLUMN_DISPLAY_NAME)
                val mimeIndex = cursor.getColumnIndex(DocumentsContract.Document.COLUMN_MIME_TYPE)
                val sizeIndex = cursor.getColumnIndex(DocumentsContract.Document.COLUMN_SIZE)
                val modifiedIndex = cursor.getColumnIndex(DocumentsContract.Document.COLUMN_LAST_MODIFIED)
                while (cursor.moveToNext()) {
                    val documentId = cursor.getStringOrNull(idIndex) ?: continue
                    val displayName = cursor.getStringOrNull(nameIndex).orEmpty()
                    val mimeType = cursor.getStringOrNull(mimeIndex).orEmpty()
                    val sizeBytes = cursor.getLongOrZero(sizeIndex)
                    val lastModified = cursor.getLongOrZero(modifiedIndex)
                    result += DocumentChild(
                        uri = DocumentsContract.buildDocumentUriUsingTree(childrenUri, documentId),
                        displayName = displayName,
                        sizeBytes = sizeBytes,
                        lastModified = lastModified,
                        isDirectory = mimeType == DocumentsContract.Document.MIME_TYPE_DIR,
                    )
                }
            }
        } catch (_: SecurityException) {
            return emptyList()
        } catch (_: RuntimeException) {
            return emptyList()
        }
        return result
    }

    private fun Cursor.getStringOrNull(index: Int): String? =
        if (index >= 0 && !isNull(index)) getString(index) else null

    private fun Cursor.getLongOrZero(index: Int): Long =
        if (index >= 0 && !isNull(index)) getLong(index) else 0L

    private data class DocumentChild(
        val uri: Uri,
        val displayName: String,
        val sizeBytes: Long,
        val lastModified: Long,
        val isDirectory: Boolean,
    )
}

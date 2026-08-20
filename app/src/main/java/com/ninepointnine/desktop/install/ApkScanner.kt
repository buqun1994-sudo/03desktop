package com.ninepointnine.desktop.install

import android.content.Context
import android.net.Uri
import android.os.Environment
import androidx.core.content.FileProvider
import com.ninepointnine.desktop.model.ApkEntry
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
                toRecord(node)
                    ?.takeIf { it.displayName.endsWith(".apk", ignoreCase = true) }
                    ?.let(results::add)
            }
        }
        return results
    }

    fun stable(records: List<ScannedApkRecord>): List<ScannedApkRecord> = records
        .asSequence()
        .filter { it.displayName.endsWith(".apk", ignoreCase = true) }
        .sortedWith(
            compareByDescending<ScannedApkRecord> { it.lastModified }
                .thenBy(ScannedApkRecord::displayName),
        )
        .distinctBy(ScannedApkRecord::uri)
        .toList()
}

class ApkScanner(
    private val context: Context,
) {
    suspend fun scan(
        downloadDirectory: File =
            Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS),
    ): List<ApkEntry> = withContext(Dispatchers.IO) {
        if (!downloadDirectory.canRead()) return@withContext emptyList()
        ApkScanRules.stable(scanFileTree(downloadDirectory)).map { record ->
            ApkEntry(
                contentUri = Uri.parse(record.uri),
                displayName = record.displayName,
                sizeBytes = record.sizeBytes,
                lastModified = record.lastModified,
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
                    FileProvider.getUriForFile(
                        context,
                        "${context.packageName}.fileprovider",
                        file,
                    )
                } catch (_: IllegalArgumentException) {
                    return@breadthFirst null
                }
                ScannedApkRecord(
                    uri = uri.toString(),
                    displayName = file.name,
                    sizeBytes = file.length(),
                    lastModified = file.lastModified(),
                )
            },
            checkpoint = activeContext::ensureActive,
        )
    }
}

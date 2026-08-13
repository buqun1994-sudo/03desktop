package com.tcrrry.desktop.model

import android.net.Uri

enum class ApkSource {
    DOWNLOADS,
    EXTERNAL_TREE,
}

data class ApkEntry(
    val contentUri: Uri,
    val displayName: String,
    val sizeBytes: Long,
    val lastModified: Long,
    val source: ApkSource,
)

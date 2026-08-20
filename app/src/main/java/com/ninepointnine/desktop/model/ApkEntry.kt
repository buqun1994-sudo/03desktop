package com.ninepointnine.desktop.model

import android.net.Uri

data class ApkEntry(
    val contentUri: Uri,
    val displayName: String,
    val sizeBytes: Long,
    val lastModified: Long,
)

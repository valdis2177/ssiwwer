package com.example.systemaudiorecorder

import android.net.Uri

data class Recording(
    val id: Long,
    val uri: Uri,
    val name: String,
    val durationMs: Long,
    val sizeBytes: Long,
    val dateAddedSeconds: Long
)

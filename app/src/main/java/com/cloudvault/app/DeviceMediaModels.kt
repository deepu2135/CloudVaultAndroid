package com.cloudvault.app

import android.net.Uri

data class DeviceFolderInfo(
    val bucketId: String,
    val bucketName: String,
    val totalCount: Int,
    val isSelected: Boolean
)

data class LocalMediaFile(
    val id: Long,
    val uri: Uri,
    val filePath: String,
    val displayName: String,
    val sizeBytes: Long,
    val dateModified: Long,
    val mediaType: MediaType,
    val bucketId: String,
    val bucketName: String
) {
    val signature: String get() = "${id}_${sizeBytes}_${dateModified}_${displayName}"
}

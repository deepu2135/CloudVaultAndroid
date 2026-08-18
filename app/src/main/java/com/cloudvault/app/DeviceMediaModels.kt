package com.cloudvault.app

import android.net.Uri

data class DeviceFolderInfo(
    val bucketId: String,
    val bucketName: String,
    val totalCount: Int,
    var isSelected: Boolean
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
    val displayNameWithoutExt: String
        get() = displayName.substringBeforeLast(".", displayName)

    val signature: String
        get() = "${displayName.lowercase().trim()}_${sizeBytes}"

    val legacySignature: String
        get() = "${filePath.ifBlank { displayName }}_${sizeBytes}_${displayName}"
}


package com.cloudvault.app

data class DuplicateGroup(
    val key: String,
    val title: String,
    val previewItem: VaultMediaItem,
    val items: List<VaultMediaItem>,
    val totalSizeBytes: Long,
    val wastedSizeBytes: Long
)

object DuplicateFinderHelper {

    fun findDuplicates(
        photos: List<VaultMediaItem>,
        videos: List<VaultMediaItem>,
        audios: List<VaultMediaItem>,
        files: List<VaultMediaItem>
    ): List<DuplicateGroup> {
        val allItems = mutableListOf<VaultMediaItem>()
        allItems.addAll(photos)
        allItems.addAll(videos)
        allItems.addAll(audios)
        allItems.addAll(files)

        val groupsMap = mutableMapOf<String, MutableList<VaultMediaItem>>()

        for (item in allItems) {
            val key = when (item.type) {
                MediaType.PHOTO -> "photo_${item.sizeBytes}_${normalizeTitle(item.title)}"
                MediaType.VIDEO -> "video_${item.sizeBytes}_${item.durationSeconds}_${normalizeTitle(item.title)}"
                MediaType.AUDIO -> "audio_${item.sizeBytes}_${item.durationSeconds}_${normalizeTitle(item.title)}"
                MediaType.DOCUMENT -> "doc_${item.sizeBytes}_${normalizeTitle(item.title)}"
            }

            groupsMap.getOrPut(key) { mutableListOf() }.add(item)
        }

        val result = mutableListOf<DuplicateGroup>()

        for ((key, items) in groupsMap) {
            if (items.size > 1) {
                // Sort items by dateAdded descending (newest first, oldest last)
                val sorted = items.sortedByDescending { it.dateAdded }
                val singleSize = sorted.first().sizeBytes
                val totalSize = singleSize * sorted.size
                val wastedSize = singleSize * (sorted.size - 1)

                result.add(
                    DuplicateGroup(
                        key = key,
                        title = sorted.first().title,
                        previewItem = sorted.first(),
                        items = sorted,
                        totalSizeBytes = totalSize,
                        wastedSizeBytes = wastedSize
                    )
                )
            }
        }

        // Sort by wasted storage descending (biggest wasters at the top)
        return result.sortedByDescending { it.wastedSizeBytes }
    }

    private fun normalizeTitle(title: String): String {
        return title.lowercase().trim().replace(Regex("[^a-z0-9._-]"), "")
    }
}

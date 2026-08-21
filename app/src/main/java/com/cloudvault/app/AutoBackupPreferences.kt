package com.cloudvault.app

import android.content.Context
import android.content.SharedPreferences

object AutoBackupPreferences {

    private const val PREFS_NAME = "cloudvault_auto_backup_prefs"
    private const val KEY_ENABLED = "auto_backup_enabled"
    private const val KEY_WIFI_ONLY = "auto_backup_wifi_only"
    private const val KEY_BACKUP_VIDEOS = "auto_backup_videos_enabled"
    private const val KEY_SELECTED_BUCKETS = "selected_bucket_ids"
    private const val KEY_BACKED_UP_SIGNATURES = "backed_up_signatures"
    private const val KEY_LAST_BACKUP_TIME = "last_backup_timestamp"

    private val inMemorySignatures = java.util.Collections.synchronizedSet(mutableSetOf<String>())
    @Volatile private var isSignaturesLoaded = false

    private fun getPrefs(context: Context): SharedPreferences {
        return context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
    }

    private fun ensureSignaturesLoaded(context: Context) {
        if (!isSignaturesLoaded) {
            synchronized(inMemorySignatures) {
                if (!isSignaturesLoaded) {
                    val persisted = getPrefs(context).getStringSet(KEY_BACKED_UP_SIGNATURES, null)
                    if (persisted != null) {
                        inMemorySignatures.addAll(persisted)
                    }
                    isSignaturesLoaded = true
                }
            }
        }
    }

    fun isEnabled(context: Context): Boolean {
        return getPrefs(context).getBoolean(KEY_ENABLED, false)
    }

    fun setEnabled(context: Context, enabled: Boolean) {
        getPrefs(context).edit().putBoolean(KEY_ENABLED, enabled).apply()
    }

    fun isWifiOnly(context: Context): Boolean {
        return getPrefs(context).getBoolean(KEY_WIFI_ONLY, false)
    }

    fun setWifiOnly(context: Context, wifiOnly: Boolean) {
        getPrefs(context).edit().putBoolean(KEY_WIFI_ONLY, wifiOnly).apply()
    }

    fun isBackupVideosEnabled(context: Context): Boolean {
        return getPrefs(context).getBoolean(KEY_BACKUP_VIDEOS, true)
    }

    fun setBackupVideosEnabled(context: Context, enabled: Boolean) {
        getPrefs(context).edit().putBoolean(KEY_BACKUP_VIDEOS, enabled).apply()
    }

    fun getSelectedBucketIds(context: Context): Set<String>? {
        val set = getPrefs(context).getStringSet(KEY_SELECTED_BUCKETS, null)
        return if (set != null) HashSet(set) else null
    }

    fun setSelectedBucketIds(context: Context, bucketIds: Set<String>) {
        getPrefs(context).edit().putStringSet(KEY_SELECTED_BUCKETS, HashSet(bucketIds)).apply()
    }

    fun isBackedUp(context: Context, file: LocalMediaFile): Boolean {
        ensureSignaturesLoaded(context)
        val name = file.displayName.lowercase().trim()
        val nameNoExt = file.displayNameWithoutExt.lowercase().trim()
        val sig = file.signature
        val legSig = file.legacySignature
        val path = file.filePath.lowercase().trim()
        val idStr = "id_${file.id}"

        return inMemorySignatures.contains(sig) ||
                inMemorySignatures.contains(legSig) ||
                (name.isNotBlank() && inMemorySignatures.contains("name:$name")) ||
                (nameNoExt.isNotBlank() && inMemorySignatures.contains("base:$nameNoExt")) ||
                (path.isNotBlank() && inMemorySignatures.contains("path:$path")) ||
                inMemorySignatures.contains(idStr)
    }

    fun hasSignature(context: Context, signature: String): Boolean {
        ensureSignaturesLoaded(context)
        return inMemorySignatures.contains(signature)
    }

    @Synchronized
    fun markSignatureBackedUp(context: Context, signature: String) {
        ensureSignaturesLoaded(context)
        inMemorySignatures.add(signature)
        val prefs = getPrefs(context)
        val currentSet = HashSet(prefs.getStringSet(KEY_BACKED_UP_SIGNATURES, emptySet()) ?: emptySet())
        currentSet.add(signature)
        prefs.edit().putStringSet(KEY_BACKED_UP_SIGNATURES, currentSet).apply()
    }

    @Synchronized
    fun markFileBackedUp(context: Context, file: LocalMediaFile) {
        ensureSignaturesLoaded(context)
        val name = file.displayName.lowercase().trim()
        val nameNoExt = file.displayNameWithoutExt.lowercase().trim()
        val sig = file.signature
        val legSig = file.legacySignature
        val path = file.filePath.lowercase().trim()
        val idStr = "id_${file.id}"

        val keysToAdd = mutableListOf(sig, legSig, idStr)
        if (name.isNotBlank()) keysToAdd.add("name:$name")
        if (nameNoExt.isNotBlank()) keysToAdd.add("base:$nameNoExt")
        if (path.isNotBlank()) keysToAdd.add("path:$path")

        inMemorySignatures.addAll(keysToAdd)

        val prefs = getPrefs(context)
        val currentSet = HashSet(prefs.getStringSet(KEY_BACKED_UP_SIGNATURES, emptySet()) ?: emptySet())
        currentSet.addAll(keysToAdd)
        prefs.edit().putStringSet(KEY_BACKED_UP_SIGNATURES, currentSet).apply()
    }

    @Synchronized
    fun markMultipleFilesBackedUp(context: Context, files: Collection<LocalMediaFile>) {
        if (files.isEmpty()) return
        ensureSignaturesLoaded(context)

        val keysToAdd = HashSet<String>()
        for (file in files) {
            val name = file.displayName.lowercase().trim()
            val nameNoExt = file.displayNameWithoutExt.lowercase().trim()
            keysToAdd.add(file.signature)
            keysToAdd.add(file.legacySignature)
            keysToAdd.add("id_${file.id}")
            if (name.isNotBlank()) keysToAdd.add("name:$name")
            if (nameNoExt.isNotBlank()) keysToAdd.add("base:$nameNoExt")
            if (file.filePath.isNotBlank()) keysToAdd.add("path:${file.filePath.lowercase().trim()}")
        }

        inMemorySignatures.addAll(keysToAdd)

        val prefs = getPrefs(context)
        val currentSet = HashSet(prefs.getStringSet(KEY_BACKED_UP_SIGNATURES, emptySet()) ?: emptySet())
        currentSet.addAll(keysToAdd)
        prefs.edit().putStringSet(KEY_BACKED_UP_SIGNATURES, currentSet).apply()
    }

    @Synchronized
    fun markMultipleSignaturesBackedUp(context: Context, signatures: Collection<String>) {
        if (signatures.isEmpty()) return
        ensureSignaturesLoaded(context)
        inMemorySignatures.addAll(signatures)

        val prefs = getPrefs(context)
        val currentSet = HashSet(prefs.getStringSet(KEY_BACKED_UP_SIGNATURES, emptySet()) ?: emptySet())
        currentSet.addAll(signatures)
        prefs.edit().putStringSet(KEY_BACKED_UP_SIGNATURES, currentSet).apply()
    }

    @Synchronized
    fun clearAllSignatures(context: Context) {
        inMemorySignatures.clear()
        getPrefs(context).edit().remove(KEY_BACKED_UP_SIGNATURES).apply()
    }

    fun getBackedUpCount(context: Context): Int {
        ensureSignaturesLoaded(context)
        return inMemorySignatures.count { it.startsWith("name:") || it.contains("_") }
    }

    fun getLastBackupTime(context: Context): Long {
        return getPrefs(context).getLong(KEY_LAST_BACKUP_TIME, 0L)
    }

    fun setLastBackupTime(context: Context, time: Long) {
        getPrefs(context).edit().putLong(KEY_LAST_BACKUP_TIME, time).apply()
    }
}


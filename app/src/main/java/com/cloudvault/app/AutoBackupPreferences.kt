package com.cloudvault.app

import android.content.Context
import android.content.SharedPreferences

object AutoBackupPreferences {

    private const val PREFS_NAME = "cloudvault_auto_backup_prefs"
    private const val KEY_ENABLED = "auto_backup_enabled"
    private const val KEY_WIFI_ONLY = "auto_backup_wifi_only"
    private const val KEY_SELECTED_BUCKETS = "selected_bucket_ids"
    private const val KEY_BACKED_UP_SIGNATURES = "backed_up_signatures"
    private const val KEY_LAST_BACKUP_TIME = "last_backup_timestamp"

    private fun getPrefs(context: Context): SharedPreferences {
        return context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
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

    fun getSelectedBucketIds(context: Context): Set<String>? {
        val set = getPrefs(context).getStringSet(KEY_SELECTED_BUCKETS, null)
        return if (set != null) HashSet(set) else null
    }

    fun setSelectedBucketIds(context: Context, bucketIds: Set<String>) {
        getPrefs(context).edit().putStringSet(KEY_SELECTED_BUCKETS, HashSet(bucketIds)).apply()
    }

    fun hasSignature(context: Context, signature: String): Boolean {
        val set = getPrefs(context).getStringSet(KEY_BACKED_UP_SIGNATURES, null)
        return set?.contains(signature) == true
    }

    fun markSignatureBackedUp(context: Context, signature: String) {
        val prefs = getPrefs(context)
        val currentSet = HashSet(prefs.getStringSet(KEY_BACKED_UP_SIGNATURES, emptySet()) ?: emptySet())
        currentSet.add(signature)
        prefs.edit().putStringSet(KEY_BACKED_UP_SIGNATURES, currentSet).apply()
    }

    fun markMultipleSignaturesBackedUp(context: Context, signatures: Collection<String>) {
        val prefs = getPrefs(context)
        val currentSet = HashSet(prefs.getStringSet(KEY_BACKED_UP_SIGNATURES, emptySet()) ?: emptySet())
        currentSet.addAll(signatures)
        prefs.edit().putStringSet(KEY_BACKED_UP_SIGNATURES, currentSet).apply()
    }

    fun getLastBackupTime(context: Context): Long {
        return getPrefs(context).getLong(KEY_LAST_BACKUP_TIME, 0L)
    }

    fun setLastBackupTime(context: Context, time: Long) {
        getPrefs(context).edit().putLong(KEY_LAST_BACKUP_TIME, time).apply()
    }
}

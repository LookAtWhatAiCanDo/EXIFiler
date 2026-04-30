package com.exifiler.android

import android.content.Context
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map

private val Context.dataStore by preferencesDataStore(name = "exifiler_prefs")

class AppPreferencesManager(private val context: Context) {

    companion object {
        private val TARGET_FOLDER_KEY = stringPreferencesKey("target_folder")
        private val SERVICE_ENABLED_KEY = booleanPreferencesKey("service_enabled")
        private val ACTIVITY_LOG_KEY = stringPreferencesKey("activity_log")
        const val DEFAULT_TARGET_FOLDER = "DCIM/EXIFiler"
        const val MAX_LOG_ENTRIES = 10
    }

    val targetFolderFlow: Flow<String> = context.dataStore.data
        .map { it[TARGET_FOLDER_KEY] ?: DEFAULT_TARGET_FOLDER }

    val serviceEnabledFlow: Flow<Boolean> = context.dataStore.data
        .map { it[SERVICE_ENABLED_KEY] ?: false }

    val activityLogFlow: Flow<List<String>> = context.dataStore.data
        .map { prefs ->
            val raw = prefs[ACTIVITY_LOG_KEY] ?: ""
            if (raw.isBlank()) emptyList()
            else raw.split("\n").filter { it.isNotBlank() }
        }

    suspend fun getTargetFolder(): String =
        context.dataStore.data.first()[TARGET_FOLDER_KEY] ?: DEFAULT_TARGET_FOLDER

    suspend fun setTargetFolder(path: String) {
        context.dataStore.edit { it[TARGET_FOLDER_KEY] = path }
    }

    suspend fun isServiceEnabled(): Boolean =
        context.dataStore.data.first()[SERVICE_ENABLED_KEY] ?: false

    suspend fun setServiceEnabled(enabled: Boolean) {
        context.dataStore.edit { it[SERVICE_ENABLED_KEY] = enabled }
    }

    suspend fun addActivityLogEntry(entry: String) {
        context.dataStore.edit { prefs ->
            val existing = prefs[ACTIVITY_LOG_KEY] ?: ""
            val entries = if (existing.isBlank()) mutableListOf()
            else existing.split("\n").filter { it.isNotBlank() }.toMutableList()
            entries.add(0, entry)
            // Keep only the last MAX_LOG_ENTRIES entries
            val trimmed = entries.take(MAX_LOG_ENTRIES)
            prefs[ACTIVITY_LOG_KEY] = trimmed.joinToString("\n")
        }
    }

    suspend fun removeActivityLogEntries(entriesToRemove: Set<String>) {
        context.dataStore.edit { prefs ->
            val existing = prefs[ACTIVITY_LOG_KEY] ?: ""
            val entries = if (existing.isBlank()) emptyList()
            else existing.split("\n").filter { it.isNotBlank() }
            val remaining = entries.filter { it !in entriesToRemove }
            prefs[ACTIVITY_LOG_KEY] = remaining.joinToString("\n")
        }
    }
}

package com.exifiler.android

import android.content.Context
import android.util.Log
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import com.exifiler.MonitoringProfile
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import org.json.JSONArray
import org.json.JSONException
import org.json.JSONObject

private val Context.dataStore by preferencesDataStore(name = "exifiler_prefs")

class AppPreferencesManager(private val context: Context) {

    companion object {
        private const val TAG = "AppPreferencesManager"
        private val TARGET_FOLDER_KEY = stringPreferencesKey("target_folder")
        private val SERVICE_ENABLED_KEY = booleanPreferencesKey("service_enabled")
        private val ACTIVITY_LOG_KEY = stringPreferencesKey("activity_log")
        private val PROFILES_KEY = stringPreferencesKey("monitoring_profiles")
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

    // ── Monitoring Profiles ────────────────────────────────────────────────

    /** Emits the current list of [MonitoringProfile]s whenever it changes. */
    val profilesFlow: Flow<List<MonitoringProfile>> = context.dataStore.data
        .map { prefs -> deserializeProfiles(prefs[PROFILES_KEY] ?: "[]") }

    /** Returns the current list of [MonitoringProfile]s (one-shot suspend read). */
    suspend fun getProfiles(): List<MonitoringProfile> =
        deserializeProfiles(context.dataStore.data.first()[PROFILES_KEY] ?: "[]")

    /**
     * Inserts a new profile or replaces an existing one with the same [MonitoringProfile.id].
     */
    suspend fun saveProfile(profile: MonitoringProfile) {
        context.dataStore.edit { prefs ->
            val current = deserializeProfiles(prefs[PROFILES_KEY] ?: "[]").toMutableList()
            val idx = current.indexOfFirst { it.id == profile.id }
            if (idx >= 0) current[idx] = profile else current.add(profile)
            prefs[PROFILES_KEY] = serializeProfiles(current)
        }
    }

    /** Removes the profile with the given [id]; no-op if it does not exist. */
    suspend fun deleteProfile(id: String) {
        context.dataStore.edit { prefs ->
            val current = deserializeProfiles(prefs[PROFILES_KEY] ?: "[]").toMutableList()
            current.removeAll { it.id == id }
            prefs[PROFILES_KEY] = serializeProfiles(current)
        }
    }

    // ── JSON serialisation helpers ─────────────────────────────────────────

    private fun serializeProfiles(profiles: List<MonitoringProfile>): String {
        val arr = JSONArray()
        for (p in profiles) {
            val patternsArr = JSONArray()
            p.filePatterns.forEach { patternsArr.put(it) }
            val filtersObj = JSONObject()
            p.exifFilters.forEach { (k, v) -> filtersObj.put(k, v) }
            arr.put(
                JSONObject()
                    .put("id", p.id)
                    .put("name", p.name)
                    .put("inputFolder", p.inputFolder)
                    .put("filePatterns", patternsArr)
                    .put("exifFilters", filtersObj)
                    .put("outputFolder", p.outputFolder)
                    .put("isEnabled", p.isEnabled)
            )
        }
        return arr.toString()
    }

    private fun deserializeProfiles(json: String): List<MonitoringProfile> {
        if (json.isBlank() || json == "[]") return emptyList()
        return try {
            val arr = JSONArray(json)
            (0 until arr.length()).mapNotNull { i ->
                try {
                    val obj = arr.getJSONObject(i)
                    val patternsArr = obj.optJSONArray("filePatterns") ?: JSONArray()
                    val patterns = (0 until patternsArr.length()).map { patternsArr.getString(it) }
                    val filtersObj = obj.optJSONObject("exifFilters") ?: JSONObject()
                    val filters = filtersObj.keys().asSequence()
                        .associateWith { filtersObj.getString(it) }
                    MonitoringProfile(
                        id = obj.getString("id"),
                        name = obj.getString("name"),
                        inputFolder = obj.getString("inputFolder"),
                        filePatterns = patterns,
                        exifFilters = filters,
                        outputFolder = obj.getString("outputFolder"),
                        isEnabled = obj.optBoolean("isEnabled", true),
                    )
                } catch (e: JSONException) {
                    Log.w(TAG, "deserializeProfiles: skipping malformed profile at index $i", e)
                    null
                }
            }
        } catch (e: JSONException) {
            Log.e(TAG, "deserializeProfiles: failed to parse profiles JSON", e)
            emptyList()
        }
    }
}

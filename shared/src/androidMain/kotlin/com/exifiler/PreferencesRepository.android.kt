package com.exifiler

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import kotlinx.coroutines.flow.first

actual class PreferencesRepository(private val dataStore: DataStore<Preferences>) {

    private val targetFolderKey = stringPreferencesKey("target_folder")
    private val serviceEnabledKey = booleanPreferencesKey("service_enabled")

    actual suspend fun getTargetFolder(): String =
        dataStore.data.first()[targetFolderKey] ?: "DCIM/EXIFiler"

    actual suspend fun setTargetFolder(path: String) {
        dataStore.edit { it[targetFolderKey] = path }
    }

    actual suspend fun isServiceEnabled(): Boolean =
        dataStore.data.first()[serviceEnabledKey] ?: false

    actual suspend fun setServiceEnabled(enabled: Boolean) {
        dataStore.edit { it[serviceEnabledKey] = enabled }
    }
}

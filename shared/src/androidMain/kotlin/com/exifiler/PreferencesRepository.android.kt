package com.exifiler

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.flow.first

private val Context.prefsDataStore: DataStore<Preferences>
    by preferencesDataStore(name = "exifiler_shared_prefs")

actual class PreferencesRepository() {

    private val targetFolderKey = stringPreferencesKey("target_folder")
    private val serviceEnabledKey = booleanPreferencesKey("service_enabled")

    private fun dataStore(): DataStore<Preferences> =
        AppContextHolder.appContext.prefsDataStore

    actual suspend fun getTargetFolder(): String =
        dataStore().data.first()[targetFolderKey] ?: "DCIM/EXIFiler"

    actual suspend fun setTargetFolder(path: String) {
        dataStore().edit { it[targetFolderKey] = path }
    }

    actual suspend fun isServiceEnabled(): Boolean =
        dataStore().data.first()[serviceEnabledKey] ?: false

    actual suspend fun setServiceEnabled(enabled: Boolean) {
        dataStore().edit { it[serviceEnabledKey] = enabled }
    }
}

/**
 * Holds the application [Context] for use by [PreferencesRepository].
 * Must be initialised once in [EXIFilerApp.onCreate] before any repository is accessed.
 */
object AppContextHolder {
    private var _appContext: android.content.Context? = null

    var appContext: android.content.Context
        get() = _appContext
            ?: error("AppContextHolder.appContext has not been initialised. " +
                "Ensure EXIFilerApp is declared in AndroidManifest.xml and Application.onCreate() runs first.")
        set(value) { _appContext = value }
}

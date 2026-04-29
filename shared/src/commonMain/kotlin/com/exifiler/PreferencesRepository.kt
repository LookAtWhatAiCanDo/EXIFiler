package com.exifiler

expect class PreferencesRepository {
    suspend fun getTargetFolder(): String
    suspend fun setTargetFolder(path: String)
    suspend fun isServiceEnabled(): Boolean
    suspend fun setServiceEnabled(enabled: Boolean)
}

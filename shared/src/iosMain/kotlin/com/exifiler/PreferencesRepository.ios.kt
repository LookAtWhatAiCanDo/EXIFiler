package com.exifiler

actual class PreferencesRepository {
    actual suspend fun getTargetFolder(): String = TODO("Not implemented for iOS yet")
    actual suspend fun setTargetFolder(path: String): Unit = TODO("Not implemented for iOS yet")
    actual suspend fun isServiceEnabled(): Boolean = TODO("Not implemented for iOS yet")
    actual suspend fun setServiceEnabled(enabled: Boolean): Unit = TODO("Not implemented for iOS yet")
}

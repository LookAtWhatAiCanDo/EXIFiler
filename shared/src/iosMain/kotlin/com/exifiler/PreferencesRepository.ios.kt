package com.exifiler

actual class PreferencesRepository {
    actual suspend fun getTargetFolder(): String = TODO("iOS implementation requires NSUserDefaults integration")
    actual suspend fun setTargetFolder(path: String): Unit = TODO("iOS implementation requires NSUserDefaults integration")
    actual suspend fun isServiceEnabled(): Boolean = TODO("iOS implementation requires NSUserDefaults integration")
    actual suspend fun setServiceEnabled(enabled: Boolean): Unit = TODO("iOS implementation requires NSUserDefaults integration")
}

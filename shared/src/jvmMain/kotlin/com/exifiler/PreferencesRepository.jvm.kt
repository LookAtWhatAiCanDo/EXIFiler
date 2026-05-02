package com.exifiler

actual class PreferencesRepository actual constructor() {
    actual suspend fun getTargetFolder(): String = TODO("JVM stub")
    actual suspend fun setTargetFolder(path: String): Unit = TODO("JVM stub")
    actual suspend fun isServiceEnabled(): Boolean = TODO("JVM stub")
    actual suspend fun setServiceEnabled(enabled: Boolean): Unit = TODO("JVM stub")
}

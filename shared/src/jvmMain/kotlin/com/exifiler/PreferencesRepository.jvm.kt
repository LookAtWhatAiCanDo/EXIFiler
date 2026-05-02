package com.exifiler

actual class PreferencesRepository actual constructor() {
    @Volatile private var targetFolder: String = "DCIM/EXIFiler"
    @Volatile private var serviceEnabled: Boolean = false

    actual suspend fun getTargetFolder(): String = targetFolder
    actual suspend fun setTargetFolder(path: String) { targetFolder = path }
    actual suspend fun isServiceEnabled(): Boolean = serviceEnabled
    actual suspend fun setServiceEnabled(enabled: Boolean) { serviceEnabled = enabled }
}

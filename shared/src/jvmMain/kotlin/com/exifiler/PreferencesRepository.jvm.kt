package com.exifiler

actual class PreferencesRepository actual constructor() {
    private var targetFolder: String = "DCIM/EXIFiler"
    private var serviceEnabled: Boolean = false

    actual suspend fun getTargetFolder(): String = targetFolder
    actual suspend fun setTargetFolder(path: String) { targetFolder = path }
    actual suspend fun isServiceEnabled(): Boolean = serviceEnabled
    actual suspend fun setServiceEnabled(enabled: Boolean) { serviceEnabled = enabled }
}

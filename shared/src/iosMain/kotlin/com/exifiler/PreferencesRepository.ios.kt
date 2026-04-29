package com.exifiler

actual class PreferencesRepository {
    actual suspend fun getTargetFolder(): String =
        TODO("iOS: read 'target_folder' key from NSUserDefaults.standard, return 'DCIM/EXIFiler' as default")
    actual suspend fun setTargetFolder(path: String): Unit =
        TODO("iOS: write path to 'target_folder' key in NSUserDefaults.standard")
    actual suspend fun isServiceEnabled(): Boolean =
        TODO("iOS: read 'service_enabled' bool from NSUserDefaults.standard, return false as default")
    actual suspend fun setServiceEnabled(enabled: Boolean): Unit =
        TODO("iOS: write enabled to 'service_enabled' key in NSUserDefaults.standard")
}

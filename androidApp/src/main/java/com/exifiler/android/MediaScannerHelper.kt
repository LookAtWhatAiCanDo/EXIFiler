package com.exifiler.android

import android.content.Context
import android.media.MediaScannerConnection
import android.net.Uri
import android.util.Log

object MediaScannerHelper {

    private const val TAG = "MediaScannerHelper"

    fun scan(context: Context, uri: Uri) {
        try {
            // For content URIs from MediaStore, the file is already indexed.
            // We broadcast ACTION_MEDIA_SCANNER_SCAN_FILE for file path URIs if needed.
            val path = uri.path
            if (path != null && uri.scheme == "file") {
                MediaScannerConnection.scanFile(
                    context,
                    arrayOf(path),
                    null
                ) { scannedPath, _ ->
                    Log.d(TAG, "Media scanner completed for: $scannedPath")
                }
            } else {
                Log.d(TAG, "Content URI already indexed by MediaStore: $uri")
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error triggering media scan for $uri", e)
        }
    }
}

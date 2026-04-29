package com.exifiler.android

import android.content.ContentValues
import android.content.Context
import android.net.Uri
import android.os.Environment
import android.provider.MediaStore
import android.util.Log

object MediaMover {

    private const val TAG = "MediaMover"

    /**
     * Moves a file from its current URI to the target folder using MediaStore scoped storage APIs.
     * Returns true on success.
     */
    suspend fun moveFile(
        context: Context,
        sourceUri: Uri,
        filename: String,
        targetFolder: String
    ): Boolean {
        return try {
            val contentResolver = context.contentResolver

            // Determine MIME type
            val mimeType = contentResolver.getType(sourceUri) ?: guessMimeType(filename)

            // Build destination ContentValues
            val relativeFolder = if (targetFolder.startsWith("DCIM")) {
                "${Environment.DIRECTORY_DCIM}/${targetFolder.removePrefix("DCIM/")}"
            } else {
                targetFolder
            }

            val destValues = ContentValues().apply {
                put(MediaStore.MediaColumns.DISPLAY_NAME, filename)
                put(MediaStore.MediaColumns.MIME_TYPE, mimeType)
                put(MediaStore.MediaColumns.RELATIVE_PATH, relativeFolder)
            }

            val destCollection = if (mimeType.startsWith("video/")) {
                MediaStore.Video.Media.EXTERNAL_CONTENT_URI
            } else {
                MediaStore.Images.Media.EXTERNAL_CONTENT_URI
            }

            val destUri = contentResolver.insert(destCollection, destValues) ?: run {
                Log.e(TAG, "Failed to create destination MediaStore entry for $filename")
                return false
            }

            // Copy bytes
            contentResolver.openInputStream(sourceUri)?.use { input ->
                contentResolver.openOutputStream(destUri)?.use { output ->
                    input.copyTo(output)
                }
            } ?: run {
                Log.e(TAG, "Failed to open streams for $filename")
                contentResolver.delete(destUri, null, null)
                return false
            }

            // Delete source
            val deleted = contentResolver.delete(sourceUri, null, null)
            if (deleted == 0) {
                Log.w(TAG, "Source file $filename could not be deleted after copy — " +
                    "the file may appear duplicated in Downloads. Manual cleanup may be required.")
            }

            // Notify media scanner of new file
            MediaScannerHelper.scan(context, destUri)

            true
        } catch (e: Exception) {
            Log.e(TAG, "Error moving file $filename", e)
            false
        }
    }

    private fun guessMimeType(filename: String): String {
        val lower = filename.lowercase()
        return when {
            lower.endsWith(".jpg") || lower.endsWith(".jpeg") -> "image/jpeg"
            lower.endsWith(".mp4") -> "video/mp4"
            lower.endsWith(".mov") -> "video/quicktime"
            else -> "application/octet-stream"
        }
    }
}

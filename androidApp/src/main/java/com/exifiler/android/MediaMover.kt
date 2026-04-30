package com.exifiler.android

import android.app.RecoverableSecurityException
import android.content.ContentValues
import android.content.Context
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import android.util.Log

object MediaMover {

    private const val TAG = "MediaMover"

    /**
     * Moves a file from its current URI to the target folder using MediaStore scoped storage APIs.
     *
     * Deletion of the source file requires one of:
     *  - API 29: `requestLegacyExternalStorage` flag in the manifest (declared).
     *  - API 31+: `MANAGE_MEDIA` permission granted by the user in Settings (one-time).
     *
     * On API 30 (the only version where neither mechanism applies automatically), deletion of
     * files not owned by this app will fail with [RecoverableSecurityException]. The caller
     * ([EXIFilerService]) collects such URIs and issues a single [MediaStore.createDeleteRequest]
     * batch at the end of each scan — one system dialog covers all files, not one per file.
     *
     * @return A [MoveResult] indicating success/failure and whether the source delete succeeded.
     */
    suspend fun moveFile(
        context: Context,
        sourceUri: Uri,
        filename: String,
        targetFolder: String
    ): MoveResult {
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
                return MoveResult.Failure
            }

            // Copy bytes — handle null streams explicitly to avoid data loss
            val inputStream = contentResolver.openInputStream(sourceUri)
            if (inputStream == null) {
                Log.e(TAG, "Failed to open input stream for $filename")
                contentResolver.delete(destUri, null, null)
                return MoveResult.Failure
            }
            val outputStream = contentResolver.openOutputStream(destUri)
            if (outputStream == null) {
                Log.e(TAG, "Failed to open destination output stream for $filename")
                inputStream.close()
                contentResolver.delete(destUri, null, null)
                return MoveResult.Failure
            }
            inputStream.use { input ->
                outputStream.use { output ->
                    input.copyTo(output)
                }
            }

            // Notify media scanner of new file
            MediaScannerHelper.scan(context, destUri)

            // Delete source — requires MANAGE_MEDIA (API 31+) or requestLegacyExternalStorage
            // (API 29).  On API 30 the system throws RecoverableSecurityException for files not
            // owned by this app; the caller must handle those with a batch createDeleteRequest.
            try {
                val deleted = contentResolver.delete(sourceUri, null, null)
                if (deleted == 0) {
                    Log.w(TAG, "delete() returned 0 for $filename — original may remain in Downloads")
                    MoveResult.CopiedDeletePending(sourceUri)
                } else {
                    Log.d(TAG, "Moved $filename → $relativeFolder")
                    MoveResult.Success
                }
            } catch (rse: RecoverableSecurityException) {
                // Copy is done; we just can't delete the original without user consent.
                // Return the source URI so the caller can batch-request deletion.
                Log.w(TAG, "RecoverableSecurityException deleting $filename — will batch-request delete", rse)
                MoveResult.CopiedDeletePending(sourceUri)
            }

        } catch (e: Exception) {
            Log.e(TAG, "Error moving file $filename", e)
            MoveResult.Failure
        }
    }

    /** Result of a [moveFile] call. */
    sealed class MoveResult {
        /** Copy AND delete both succeeded — source is gone. */
        data object Success : MoveResult()

        /** Copy succeeded but delete was denied; [sourceUri] should be batch-deleted. */
        data class CopiedDeletePending(val sourceUri: Uri) : MoveResult()

        /** Copy itself failed — file was not moved. */
        data object Failure : MoveResult()
    }

    /**
     * On API 30 (Android 11), `MANAGE_MEDIA` doesn't exist yet, so we batch all pending source
     * URIs into a single [MediaStore.createDeleteRequest] and launch it from a notification that
     * the user taps **once** — not once per file.
     *
     * On API 31+ this method should never be needed because MANAGE_MEDIA allows silent deletion.
     * It is provided as a safety net only.
     */
    @Suppress("unused")
    fun canManageMediaSilently(context: Context): Boolean {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            MediaStore.canManageMedia(context)
        } else {
            // API 29 relies on requestLegacyExternalStorage; API 30 cannot do it silently.
            Build.VERSION.SDK_INT == Build.VERSION_CODES.Q
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

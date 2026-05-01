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
     * Deletion of the source file after copying requires one of:
     *  - API 29: `requestLegacyExternalStorage` flag in the manifest (declared).
     *  - API 31+: `MANAGE_MEDIA` permission granted by the user in Settings, combined with
     *    [MediaStore.createDeleteRequest] (the permission suppresses the confirmation dialog).
     *
     * On API 30 and on API 31+ before `MANAGE_MEDIA` is granted, deletion of files not owned
     * by this app will fail with [RecoverableSecurityException]. The caller ([EXIFilerService])
     * collects such URIs and issues a [MediaStore.createDeleteRequest] batch via notification —
     * one system dialog covers all files when `MANAGE_MEDIA` is not yet granted, and no dialog
     * at all when `MANAGE_MEDIA` is granted.
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

            // Check if destination already has a file with the same name.
            // If so, the file was already copied in a previous scan; skip the copy and
            // just try to delete the source so it stops re-appearing in Downloads.
            // MediaStore stores RELATIVE_PATH with a trailing slash; append one if absent.
            val destPathForQuery = if (relativeFolder.endsWith("/")) relativeFolder else "$relativeFolder/"
            val alreadyExists = contentResolver.query(
                destCollection,
                arrayOf(MediaStore.MediaColumns._ID),
                "${MediaStore.MediaColumns.DISPLAY_NAME} = ? AND ${MediaStore.MediaColumns.RELATIVE_PATH} = ?",
                arrayOf(filename, destPathForQuery),
                null,
            )?.use { it.moveToFirst() } == true

            if (alreadyExists) {
                Log.i(TAG, "Destination already contains $filename — skipping copy, attempting source delete")
                return try {
                    val deleted = contentResolver.delete(sourceUri, null, null)
                    if (deleted > 0) MoveResult.Success else MoveResult.CopiedDeletePending(sourceUri)
                } catch (rse: RecoverableSecurityException) {
                    MoveResult.CopiedDeletePending(sourceUri)
                }
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

            // Delete source — this works for files the app owns (any API) and on API 29 via
            // requestLegacyExternalStorage. For non-owned files on API 30+ it throws
            // RecoverableSecurityException; the caller must handle those with a batch
            // createDeleteRequest() — see EXIFilerService.scanDownloads() retry logic.
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
     * Returns true when the app can request media file deletions without a confirmation dialog.
     *
     * On API 31+ this requires the user to have granted [MediaStore.canManageMedia] via the
     * Settings → Special app access → Media management screen. With this granted, a
     * [MediaStore.createDeleteRequest] completes silently (no dialog). Without it, the request
     * shows a system confirmation dialog.
     *
     * On API 29, [android.R.attr.requestLegacyExternalStorage] fulfils the same role for
     * files accessed under the legacy storage model.
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

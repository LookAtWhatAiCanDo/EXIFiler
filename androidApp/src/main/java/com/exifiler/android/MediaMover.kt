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
     * Deletion of the source file after copying:
     *  - **API 29**: direct `ContentResolver.delete()` works via `requestLegacyExternalStorage`.
     *  - **API 30+**: direct delete **always fails** for files not owned by this app, regardless
     *    of whether `MANAGE_MEDIA` is granted. `MANAGE_MEDIA` only suppresses the confirmation
     *    dialog shown by `createDeleteRequest()` — it does not enable direct deletion.
     *    This method returns [MoveResult.CopiedDeletePending] so the caller can batch the URI
     *    into a [MediaStore.createDeleteRequest] PendingIntent.
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
                Log.i(TAG, "Destination already contains $filename — skipping copy, queuing source delete")
                // On API 30+, direct delete always fails for non-owned files regardless of
                // MANAGE_MEDIA status. Return CopiedDeletePending so EXIFilerService batches
                // the URI into a createDeleteRequest().
                if (!canDirectDelete()) {
                    return MoveResult.CopiedDeletePending(sourceUri)
                }
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

            // Delete source — on API 29 requestLegacyExternalStorage grants access.
            // On API 30+, direct delete always fails for non-owned files (even with MANAGE_MEDIA);
            // return CopiedDeletePending so the caller uses createDeleteRequest().
            if (!canDirectDelete()) {
                Log.d(TAG, "Queuing $filename for createDeleteRequest (API 30+: direct delete not permitted for non-owned files)")
                return MoveResult.CopiedDeletePending(sourceUri)
            }
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
     * Returns true when direct [android.content.ContentResolver.delete] can succeed for
     * files not owned by this app, without throwing [android.app.RecoverableSecurityException].
     *
     * Only API 29 (`requestLegacyExternalStorage`) qualifies. On API 30+, even with
     * `MANAGE_MEDIA` granted, direct `ContentResolver.delete()` always fails for non-owned
     * files — `MANAGE_MEDIA` only suppresses the confirmation dialog in `createDeleteRequest()`,
     * it does not enable direct deletion. The caller must use [MediaStore.createDeleteRequest]
     * on API 30+.
     */
    private fun canDirectDelete(): Boolean = Build.VERSION.SDK_INT <= Build.VERSION_CODES.Q

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

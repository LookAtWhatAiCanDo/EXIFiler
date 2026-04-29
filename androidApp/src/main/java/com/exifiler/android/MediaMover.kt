package com.exifiler.android

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.RecoverableSecurityException
import android.content.ContentValues
import android.content.Context
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import android.util.Log
import androidx.core.app.NotificationCompat

object MediaMover {

    private const val TAG = "MediaMover"
    private const val DELETE_CHANNEL_ID = "exifiler_delete_approval"

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

            // Copy bytes — handle null streams explicitly to avoid data loss
            val inputStream = contentResolver.openInputStream(sourceUri)
            if (inputStream == null) {
                Log.e(TAG, "Failed to open input stream for $filename")
                contentResolver.delete(destUri, null, null)
                return false
            }
            val outputStream = contentResolver.openOutputStream(destUri)
            if (outputStream == null) {
                Log.e(TAG, "Failed to open destination output stream for $filename")
                inputStream.close()
                contentResolver.delete(destUri, null, null)
                return false
            }
            inputStream.use { input ->
                outputStream.use { output ->
                    input.copyTo(output)
                }
            }

            // Delete source — the file may be owned by another app, in which case Android
            // throws RecoverableSecurityException. We catch it specifically here (before the
            // outer Exception handler) so a successful copy is never silently rolled back and
            // the file is not re-processed on every subsequent scan.
            try {
                val deleted = contentResolver.delete(sourceUri, null, null)
                if (deleted == 0) {
                    Log.w(TAG, "Source file $filename could not be deleted after copy — " +
                        "the file may appear duplicated in Downloads. Manual cleanup may be required.")
                }
            } catch (rse: RecoverableSecurityException) {
                Log.w(TAG, "RecoverableSecurityException deleting $filename — requesting user approval via notification", rse)
                requestDeleteApproval(context, sourceUri, filename, rse)
                // Copy succeeded; the original will be deleted when the user taps the notification.
            }

            // Notify media scanner of new file
            MediaScannerHelper.scan(context, destUri)

            true
        } catch (e: Exception) {
            Log.e(TAG, "Error moving file $filename", e)
            false
        }
    }

    /**
     * Shows a high-priority notification whose tap action is the system-provided delete-approval
     * dialog. On API 30+ we use [MediaStore.createDeleteRequest]; on API 29 we use the
     * [RecoverableSecurityException.userAction] pending intent directly.
     */
    private fun requestDeleteApproval(
        context: Context,
        uri: Uri,
        filename: String,
        rse: RecoverableSecurityException
    ) {
        val deletePi: PendingIntent = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            MediaStore.createDeleteRequest(context.contentResolver, listOf(uri))
        } else {
            // userAction or its actionIntent can be null in rare cases; fall back gracefully.
            rse.userAction?.actionIntent ?: run {
                Log.w(TAG, "RecoverableSecurityException.userAction is null for $filename — cannot request approval")
                return
            }
        }

        val nm = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        if (nm.getNotificationChannel(DELETE_CHANNEL_ID) == null) {
            nm.createNotificationChannel(
                NotificationChannel(
                    DELETE_CHANNEL_ID,
                    "Delete originals from Downloads",
                    NotificationManager.IMPORTANCE_HIGH
                )
            )
        }

        val notification = NotificationCompat.Builder(context, DELETE_CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_notification)
            .setContentTitle("EXIFiler: tap to remove original from Downloads")
            .setContentText(filename)
            .setContentIntent(deletePi)
            .setAutoCancel(true)
            .build()

        nm.notify(uri.toString().hashCode(), notification)
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

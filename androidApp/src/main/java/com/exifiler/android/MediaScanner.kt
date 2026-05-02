package com.exifiler.android

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.RecoverableSecurityException
import android.content.ContentUris
import android.content.Context
import android.net.Uri
import android.os.Build
import android.provider.MediaStore
import android.util.Log
import androidx.core.app.NotificationCompat
import com.exifiler.DetectionResult
import com.exifiler.MetadataDetector
import com.exifiler.MonitoringProfile
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import okio.buffer
import okio.source
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * One-shot scan engine shared by [EXIFilerService] (observer-driven) and the UI's Scan Now
 * button. Holds the cross-scan state (LRU of processed URIs, retry-delete queue) as singleton
 * fields so a Scan Now run and an observer-triggered run see the same dedup/retry state.
 *
 * [scan] never starts/stops the foreground service. Scan Now therefore works regardless of
 * whether the service is running.
 */
object MediaScanner {

    private const val TAG = "MediaScanner"
    const val NOTIFICATION_ID_DELETE_PENDING = 1002
    const val CHANNEL_ID = "exifiler_service_channel"
    val SUPPORTED_EXTENSIONS = listOf("jpg", "jpeg", "mp4", "mov")

    private val scanMutex = Mutex()

    // Keyed by content URI string (not raw ID) to avoid ID collisions across MediaStore collections.
    private val processedUris = object : LinkedHashMap<String, Unit>(64, 0.75f, true) {
        override fun removeEldestEntry(eldest: Map.Entry<String, Unit>): Boolean = size > 500
    }

    // Source URIs where copy succeeded but delete failed. Retried at the start of every scan
    // so that once MANAGE_MEDIA is granted the originals are cleaned up. All access happens
    // inside scan() which holds scanMutex, so no separate synchronization is needed.
    private val retryDeleteUris = LinkedHashSet<Uri>()

    private data class FileEntry(val uri: Uri, val name: String)

    private data class PendingMoveLog(
        val timestamp: String,
        val filename: String,
        val sourceFolder: String,
        val targetFolder: String,
        // null = source already gone (clean success). non-null = source URI awaiting delete.
        val pendingSourceUri: Uri?,
    )

    suspend fun scan(context: Context, preferencesManager: AppPreferencesManager) = scanMutex.withLock {
        Log.d(TAG, "+scan()")
        val appContext = context.applicationContext
        val contentResolver = appContext.contentResolver

        // Pre-scan: on API 29 only, attempt direct deletes for URIs queued from previous scans.
        // (requestLegacyExternalStorage makes direct delete work for app-accessible files on API 29.)
        // On API 30+, createDeleteRequest() is required — that flush happens AFTER scanning below,
        // so that URIs added during this scan are included in the same notification.
        if (retryDeleteUris.isNotEmpty() && Build.VERSION.SDK_INT < Build.VERSION_CODES.R) {
            val retryIter = retryDeleteUris.iterator()
            while (retryIter.hasNext()) {
                val uri = retryIter.next()
                try {
                    if (contentResolver.delete(uri, null, null) > 0) {
                        Log.i(TAG, "scan: retroactively deleted source $uri")
                        retryIter.remove()
                    }
                } catch (_: RecoverableSecurityException) {
                    // Still no permission — keep in set for next scan.
                } catch (e: Exception) {
                    Log.w(TAG, "scan: giving up on pending delete for $uri", e)
                    retryIter.remove()
                }
            }
        }

        val profiles = preferencesManager.getProfiles()
            .filter { it.isEnabled }
            .ifEmpty { listOf(MonitoringProfile.DEFAULT) }

        Log.d(TAG, "scan: ${profiles.size} active profile(s)")

        val pendingLogs = mutableListOf<PendingMoveLog>()
        var totalNew = 0
        var totalMatched = 0

        for (profile in profiles) {
            val (newCount, matchCount) = scanForProfile(appContext, profile, pendingLogs)
            totalNew += newCount
            totalMatched += matchCount
        }

        Log.i(TAG, "scan: $totalNew new file(s) across all profiles, $totalMatched matched")

        // Post-scan: on API 30+, flush any pending source-delete URIs (including ones just added
        // during this scan) via createDeleteRequest(). Doing this AFTER profile scanning ensures
        // that URIs from the current scan are included — no second scan needed.
        var autoDeletedNow = false
        if (retryDeleteUris.isNotEmpty() && Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            val uriList = retryDeleteUris.toList()
            val deleteIntent = MediaStore.createDeleteRequest(contentResolver, uriList)

            val canManageMedia = Build.VERSION.SDK_INT >= Build.VERSION_CODES.S &&
                MediaStore.canManageMedia(appContext)
            if (canManageMedia) {
                // MANAGE_MEDIA is granted: the PendingIntent fires silently — no dialog, no
                // user tap required.
                try {
                    deleteIntent.send()
                    Log.i(TAG, "scan: auto-deleted ${uriList.size} source(s) (MANAGE_MEDIA granted)")
                    retryDeleteUris.clear()
                    autoDeletedNow = true
                } catch (e: PendingIntent.CanceledException) {
                    Log.w(TAG, "scan: createDeleteRequest send() cancelled, falling back to notification", e)
                    showDeletePendingNotification(appContext, deleteIntent, uriList.size)
                    retryDeleteUris.clear()
                }
            } else {
                showDeletePendingNotification(appContext, deleteIntent, uriList.size)
                Log.i(TAG, "scan: issued createDeleteRequest notification for ${uriList.size} source(s) — grant Media Management permission for auto-delete")
                retryDeleteUris.clear()
            }
        }

        // Now write per-file log entries with accurate phrasing.
        for (entry in pendingLogs) {
            val suffix = when {
                entry.pendingSourceUri == null -> ""
                autoDeletedNow -> ""
                Build.VERSION.SDK_INT >= Build.VERSION_CODES.R -> " (tap notification to delete source)"
                else -> " (source delete will retry)"
            }
            preferencesManager.addActivityLogEntry(
                "${entry.timestamp} | ${entry.filename} | ${entry.sourceFolder} → ${entry.targetFolder}$suffix"
            )
        }

        // Surface a scan-summary entry when files were checked but none matched any profile,
        // so the user can confirm scanning is actually happening.
        if (totalNew > 0 && totalMatched == 0) {
            val timestamp = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.getDefault()).format(Date())
            preferencesManager.addActivityLogEntry(
                "$timestamp | Scan: $totalNew file(s) checked — 0 matched any profile criteria"
            )
        }

        Log.d(TAG, "-scan()")
    }

    private suspend fun scanForProfile(
        context: Context,
        profile: MonitoringProfile,
        pendingLogs: MutableList<PendingMoveLog>,
    ): Pair<Int, Int> {
        val contentResolver = context.contentResolver
        val inputPath = profile.inputFolder.trimEnd('/') + "/"
        val isDownloadFolder = inputPath.equals("Download/", ignoreCase = true)

        val candidates = mutableListOf<FileEntry>()

        fun addFromCursor(collectionUri: Uri, idColumn: String, nameColumn: String,
                          selection: String?, selectionArgs: Array<String>?) {
            val projection = arrayOf(idColumn, nameColumn)
            contentResolver.query(collectionUri, projection, selection, selectionArgs, null)
                ?.use { cursor ->
                    val idCol = cursor.getColumnIndexOrThrow(idColumn)
                    val nameCol = cursor.getColumnIndexOrThrow(nameColumn)
                    while (cursor.moveToNext()) {
                        val id = cursor.getLong(idCol)
                        val name = cursor.getString(nameCol) ?: continue
                        candidates.add(FileEntry(ContentUris.withAppendedId(collectionUri, id), name))
                    }
                    Log.d(TAG, "scanForProfile[${profile.name}]: $collectionUri → ${cursor.count} row(s)")
                } ?: Log.e(TAG, "scanForProfile[${profile.name}]: query returned null for $collectionUri")
        }

        if (isDownloadFolder) {
            addFromCursor(
                MediaStore.Downloads.EXTERNAL_CONTENT_URI,
                MediaStore.Downloads._ID, MediaStore.Downloads.DISPLAY_NAME,
                selection = "(${MediaStore.Downloads.MIME_TYPE} IN (?,?,?,?,?)) " +
                    "OR (${MediaStore.Downloads.DISPLAY_NAME} LIKE ? COLLATE NOCASE) " +
                    "OR (${MediaStore.Downloads.DISPLAY_NAME} LIKE ? COLLATE NOCASE) " +
                    "OR (${MediaStore.Downloads.DISPLAY_NAME} LIKE ? COLLATE NOCASE)",
                selectionArgs = arrayOf(
                    "image/jpeg", "image/jpg", "image/pjpeg", "video/mp4", "video/quicktime",
                    "%.jpg", "%.jpeg", "%.mp4"
                )
            )
        }

        addFromCursor(
            MediaStore.Images.Media.EXTERNAL_CONTENT_URI,
            MediaStore.Images.Media._ID, MediaStore.Images.Media.DISPLAY_NAME,
            selection = "${MediaStore.Images.Media.RELATIVE_PATH} LIKE ?",
            selectionArgs = arrayOf("$inputPath%")
        )

        addFromCursor(
            MediaStore.Video.Media.EXTERNAL_CONTENT_URI,
            MediaStore.Video.Media._ID, MediaStore.Video.Media.DISPLAY_NAME,
            selection = "${MediaStore.Video.Media.RELATIVE_PATH} LIKE ? AND " +
                "((${MediaStore.Video.Media.MIME_TYPE} IN (?,?)) " +
                "OR (${MediaStore.Video.Media.DISPLAY_NAME} LIKE ? COLLATE NOCASE))",
            selectionArgs = arrayOf("$inputPath%", "video/mp4", "video/quicktime", "%.mp4")
        )

        Log.d(TAG, "scanForProfile[${profile.name}]: ${candidates.size} candidate(s)")

        var newCount = 0
        var matchCount = 0
        val effectivePatterns = profile.filePatterns.ifEmpty { SUPPORTED_EXTENSIONS }
        for ((fileUri, name) in candidates) {
            if (!matchesFilePatterns(name, effectivePatterns)) {
                Log.v(TAG, "scanForProfile[${profile.name}]: skipping $name — not in filePatterns")
                continue
            }
            val uriKey = fileUri.toString()
            if (uriKey in processedUris) {
                Log.v(TAG, "scanForProfile[${profile.name}]: skipping already-processed $name")
                continue
            }
            newCount++
            Log.d(TAG, "scanForProfile[${profile.name}]: processing $name ($fileUri)")
            if (processFile(
                    context = context,
                    uri = fileUri,
                    filename = name,
                    uriKey = uriKey,
                    sourceFolder = profile.inputFolder,
                    targetFolder = profile.outputFolder,
                    exifFilters = profile.exifFilters,
                    pendingLogs = pendingLogs,
                )
            ) matchCount++
        }
        return newCount to matchCount
    }

    private fun matchesFilePatterns(filename: String, patterns: List<String>): Boolean {
        val lower = filename.lowercase()
        return patterns.any { ext ->
            val normalised = ext.lowercase().trimStart('*', '.')
            lower.endsWith(".$normalised")
        }
    }

    private suspend fun processFile(
        context: Context,
        uri: Uri,
        filename: String,
        uriKey: String,
        sourceFolder: String,
        targetFolder: String,
        exifFilters: Map<String, String>,
        pendingLogs: MutableList<PendingMoveLog>,
    ): Boolean {
        Log.d(TAG, "processFile: $filename ($uri)")
        val contentResolver = context.contentResolver
        return try {
            val inputStream = contentResolver.openInputStream(uri) ?: run {
                Log.w(TAG, "processFile: openInputStream returned null for $filename")
                return false
            }
            val result = inputStream.use { stream ->
                MetadataDetector.detect(stream.source().buffer(), filename, exifFilters)
            }

            Log.d(TAG, "processFile: $filename -> $result")
            if (result is DetectionResult.Match) {
                Log.i(TAG, "processFile: matched profile filter — moving $filename (device=${result.deviceName})")
                val moveResult = MediaMover.moveFile(
                    context = context.applicationContext,
                    sourceUri = uri,
                    filename = filename,
                    targetFolder = targetFolder
                )
                processedUris[uriKey] = Unit
                val timestamp = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.getDefault()).format(Date())
                when (moveResult) {
                    is MediaMover.MoveResult.Success -> {
                        pendingLogs.add(PendingMoveLog(timestamp, filename, sourceFolder, targetFolder, pendingSourceUri = null))
                        Log.i(TAG, "processFile: moved $filename to $targetFolder")
                        true
                    }
                    is MediaMover.MoveResult.CopiedDeletePending -> {
                        retryDeleteUris.add(moveResult.sourceUri)
                        pendingLogs.add(PendingMoveLog(timestamp, filename, sourceFolder, targetFolder, pendingSourceUri = moveResult.sourceUri))
                        Log.i(TAG, "processFile: $filename copied; source delete queued")
                        true
                    }
                    is MediaMover.MoveResult.Failure -> {
                        Log.e(TAG, "processFile: move failed for $filename")
                        false
                    }
                }
            } else {
                processedUris[uriKey] = Unit
                false
            }
        } catch (e: Exception) {
            Log.e(TAG, "processFile: error processing $filename", e)
            false
        }
    }

    /**
     * Posts a notification whose content intent fires a [MediaStore.createDeleteRequest] batch.
     * Used when MANAGE_MEDIA is not granted — the user sees a system confirmation dialog after
     * tapping the notification.
     */
    private fun showDeletePendingNotification(context: Context, deleteIntent: PendingIntent, count: Int) {
        ensureNotificationChannel(context)
        val nm = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        val text = context.resources.getQuantityString(
            R.plurals.notification_delete_pending_text, count, count
        )
        val notification = NotificationCompat.Builder(context, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_notification)
            .setContentTitle(context.getString(R.string.notification_delete_pending_title))
            .setContentText(text)
            .setContentIntent(deleteIntent)
            .setAutoCancel(true)
            .build()
        nm.notify(NOTIFICATION_ID_DELETE_PENDING, notification)
    }

    /** Idempotent: createNotificationChannel ignores duplicates. */
    fun ensureNotificationChannel(context: Context) {
        val manager = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        manager.createNotificationChannel(
            NotificationChannel(
                CHANNEL_ID,
                context.getString(R.string.notification_channel_name),
                NotificationManager.IMPORTANCE_LOW
            ).apply { description = "EXIFiler background service notifications" }
        )
    }
}

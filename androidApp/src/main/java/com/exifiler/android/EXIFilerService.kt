package com.exifiler.android

import android.app.RecoverableSecurityException
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.ContentUris
import android.content.Context
import android.content.Intent
import android.database.ContentObserver
import android.net.Uri
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.provider.MediaStore
import android.util.Log
import androidx.core.app.NotificationCompat
import com.exifiler.DetectionResult
import com.exifiler.MetadataDetector
import com.exifiler.MonitoringProfile
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import okio.buffer
import okio.source
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class EXIFilerService : Service() {

    private val serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val contentObservers = mutableListOf<ContentObserver>()
    private lateinit var preferencesManager: AppPreferencesManager
    private val scanMutex = Mutex()
    // Keyed by content URI string (not raw ID) to avoid ID collisions across MediaStore collections.
    private val processedUris = object : LinkedHashMap<String, Unit>(64, 0.75f, true) {
        override fun removeEldestEntry(eldest: Map.Entry<String, Unit>): Boolean = size > 500
    }
    // Source URIs where copy succeeded but delete failed. Retried at the start of every scan
    // so that once MANAGE_MEDIA is granted (or service is restarted) the originals are cleaned up.
    // All access happens inside scanDownloads() which holds scanMutex, so no separate
    // synchronization is needed.
    private val retryDeleteUris = LinkedHashSet<Uri>()

    // URI + filename pair accumulated across MediaStore collections during a scan.
    private data class FileEntry(val uri: Uri, val name: String)

    companion object {
        private const val TAG = "EXIFilerService"
        private const val NOTIFICATION_ID = 1001
        private const val NOTIFICATION_ID_DELETE_PENDING = 1002
        private const val CHANNEL_ID = "exifiler_service_channel"
        /** Intent action that triggers an immediate scan (e.g. after MANAGE_MEDIA is granted). */
        const val ACTION_SCAN_NOW = "com.exifiler.android.action.SCAN_NOW"
        /** Intent action sent from the notification Quit button to stop the service. */
        const val ACTION_QUIT = "com.exifiler.android.action.QUIT"
        /** Default file extensions used when a profile's filePatterns list is empty. */
        val SUPPORTED_EXTENSIONS = listOf("jpg", "jpeg", "mp4", "mov")
    }

    override fun onCreate() {
        super.onCreate()
        preferencesManager = AppPreferencesManager(applicationContext)
        createNotificationChannel()
        startForeground(NOTIFICATION_ID, buildNotification())
        registerDownloadsObserver()
        Log.i(TAG, "EXIFilerService started")
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_SCAN_NOW -> serviceScope.launch { scanDownloads() }
            ACTION_QUIT -> serviceScope.launch {
                withContext(NonCancellable) {
                    preferencesManager.setServiceEnabled(false)
                    AppEvents.notifyQuit()
                    stopForeground(STOP_FOREGROUND_REMOVE)
                    stopSelf()
                }
            }
        }
        return START_STICKY
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onDestroy() {
        contentObservers.forEach { contentResolver.unregisterContentObserver(it) }
        serviceScope.cancel()
        Log.i(TAG, "EXIFilerService stopped")
        super.onDestroy()
    }

    private fun createNotificationChannel() {
        val manager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        manager.createNotificationChannel(
            NotificationChannel(CHANNEL_ID, getString(R.string.notification_channel_name), NotificationManager.IMPORTANCE_LOW).apply {
                description = "EXIFiler background service notifications"
            }
        )
    }

    private fun buildNotification(): Notification {
        val pendingIntent = PendingIntent.getActivity(
            this, 0,
            Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_IMMUTABLE
        )
        val quitPendingIntent = PendingIntent.getService(
            this, 1,
            Intent(this, EXIFilerService::class.java).apply { action = ACTION_QUIT },
            PendingIntent.FLAG_IMMUTABLE
        )
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle(getString(R.string.notification_title))
            .setContentText(getString(R.string.notification_text))
            .setSmallIcon(R.drawable.ic_notification)
            .setContentIntent(pendingIntent)
            .setOngoing(true)
            .addAction(0, getString(R.string.notification_quit_action), quitPendingIntent)
            .build()
    }

    /**
     * Posts a notification whose content intent fires a [MediaStore.createDeleteRequest] batch.
     * With [MediaStore.canManageMedia] true the system silently approves (no dialog on tap).
     * Without it, the user sees a system confirmation dialog after tapping.
     */
    private fun showDeletePendingNotification(deleteIntent: PendingIntent, count: Int) {
        val nm = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        val text = resources.getQuantityString(R.plurals.notification_delete_pending_text, count, count)
        val notification = NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_notification)
            .setContentTitle(getString(R.string.notification_delete_pending_title))
            .setContentText(text)
            .setContentIntent(deleteIntent)
            .setAutoCancel(true)
            .build()
        nm.notify(NOTIFICATION_ID_DELETE_PENDING, notification)
    }

    private fun registerDownloadsObserver() {
        // Watch all three collections: some devices index Download-folder media files under
        // Images/Video rather than Downloads, so we need observers on all three.
        val handler = Handler(Looper.getMainLooper())
        val observedUris = listOf(
            MediaStore.Downloads.EXTERNAL_CONTENT_URI,
            MediaStore.Images.Media.EXTERNAL_CONTENT_URI,
            MediaStore.Video.Media.EXTERNAL_CONTENT_URI
        )
        observedUris.forEach { collectionUri ->
            val observer = object : ContentObserver(handler) {
                override fun onChange(selfChange: Boolean, uri: Uri?) {
                    // Skip if a scan is already in progress (coalesce rapid notifications)
                    if (scanMutex.isLocked) return
                    serviceScope.launch { scanDownloads() }
                }
            }
            contentResolver.registerContentObserver(collectionUri, true, observer)
            contentObservers.add(observer)
        }
        // Perform initial scan
        serviceScope.launch { scanDownloads() }
    }

    private suspend fun scanDownloads() = scanMutex.withLock {
        Log.d(TAG, "+scanDownloads()")

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
                        Log.i(TAG, "scanDownloads: retroactively deleted source $uri")
                        retryIter.remove()
                    }
                } catch (_: RecoverableSecurityException) {
                    // Still no permission — keep in set for next scan.
                } catch (e: Exception) {
                    Log.w(TAG, "scanDownloads: giving up on pending delete for $uri", e)
                    retryIter.remove()
                }
            }
        }

        // Load active profiles (fall back to the built-in default if none are configured).
        val profiles = preferencesManager.getProfiles()
            .filter { it.isEnabled }
            .ifEmpty { listOf(MonitoringProfile.DEFAULT) }

        Log.d(TAG, "scanDownloads: ${profiles.size} active profile(s)")

        var totalNew = 0
        var totalMatched = 0

        for (profile in profiles) {
            val (newCount, matchCount) = scanForProfile(profile)
            totalNew += newCount
            totalMatched += matchCount
        }

        Log.i(TAG, "scanDownloads: $totalNew new file(s) across all profiles, $totalMatched matched")

        // Surface a scan-summary entry when files were checked but none matched any profile,
        // so the user can confirm the service is actively scanning.
        if (totalNew > 0 && totalMatched == 0) {
            val timestamp = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.getDefault()).format(Date())
            preferencesManager.addActivityLogEntry(
                "$timestamp | Scan: $totalNew file(s) checked — 0 matched any profile criteria"
            )
        }

        // Post-scan: on API 30+, flush any pending source-delete URIs (including ones just added
        // during this scan) via createDeleteRequest(). Doing this AFTER profile scanning ensures
        // that URIs from the current scan are included — no second scan needed.
        // Per the Android docs, even with MANAGE_MEDIA granted, non-owned files must be deleted
        // via createDeleteRequest(); the permission only suppresses the confirmation dialog.
        if (retryDeleteUris.isNotEmpty() && Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            val uriList = retryDeleteUris.toList()
            val deleteIntent = MediaStore.createDeleteRequest(contentResolver, uriList)
            showDeletePendingNotification(deleteIntent, uriList.size)
            // Clear the set — the notification's PendingIntent carries out the deletion.
            // With MANAGE_MEDIA granted the system silently approves (no dialog on tap);
            // without it the user sees a one-time confirmation dialog.
            retryDeleteUris.clear()
            Log.i(TAG, "scanDownloads: issued createDeleteRequest for ${uriList.size} source(s)")
        }

        Log.d(TAG, "-scanDownloads()")
    }

    /**
     * Scans [profile]'s input folder for matching files and processes them.
     * Returns a pair of (newFilesChecked, filesMatched).
     */
    private suspend fun scanForProfile(profile: MonitoringProfile): Pair<Int, Int> {
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
            // Downloads collection — filter by MIME type / extension so we don't open every file.
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

        // Images collection — some devices index Download-folder media here instead of Downloads.
        addFromCursor(
            MediaStore.Images.Media.EXTERNAL_CONTENT_URI,
            MediaStore.Images.Media._ID, MediaStore.Images.Media.DISPLAY_NAME,
            selection = "${MediaStore.Images.Media.RELATIVE_PATH} LIKE ?",
            selectionArgs = arrayOf("$inputPath%")
        )

        // Video collection — same reasoning.
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
        // Empty filePatterns means "all supported types" — fall back to the known-good extension list
        // so we never open/process every file in the folder indiscriminately.
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
            if (processFile(fileUri, name, uriKey, profile.inputFolder, profile.outputFolder, profile.exifFilters)) matchCount++
        }
        return newCount to matchCount
    }

    /** Returns `true` if [filename]'s extension matches any entry in [patterns]. */
    private fun matchesFilePatterns(filename: String, patterns: List<String>): Boolean {
        val lower = filename.lowercase()
        return patterns.any { ext ->
            val normalised = ext.lowercase().trimStart('*', '.')
            lower.endsWith(".$normalised")
        }
    }

    private suspend fun processFile(
        uri: Uri,
        filename: String,
        uriKey: String,
        sourceFolder: String,
        targetFolder: String,
        exifFilters: Map<String, String>
    ): Boolean {
        Log.d(TAG, "processFile: $filename ($uri)")
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
                    context = applicationContext,
                    sourceUri = uri,
                    filename = filename,
                    targetFolder = targetFolder
                )
                // Mark processed regardless of delete outcome so we don't re-copy on next scan
                processedUris[uriKey] = Unit
                when (moveResult) {
                    is MediaMover.MoveResult.Success -> {
                        val timestamp = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.getDefault()).format(Date())
                        preferencesManager.addActivityLogEntry(
                            "$timestamp | $filename | $sourceFolder → $targetFolder"
                        )
                        Log.i(TAG, "processFile: moved $filename to $targetFolder")
                        true
                    }
                    is MediaMover.MoveResult.CopiedDeletePending -> {
                        // Copy succeeded but delete was denied. Enqueue the source URI so the
                        // retry loop at the top of the next scan can clean it up:
                        //   - API 31+ with MANAGE_MEDIA: via createDeleteRequest() notification
                        //     (no dialog shown because MANAGE_MEDIA suppresses it).
                        //   - Otherwise: via direct delete (owned files / API 29 legacy mode).
                        retryDeleteUris.add(moveResult.sourceUri)
                        val timestamp = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.getDefault()).format(Date())
                        preferencesManager.addActivityLogEntry(
                            "$timestamp | $filename | $sourceFolder → $targetFolder (source delete pending — grant Media Management permission)"
                        )
                        Log.w(TAG, "processFile: $filename copied; source delete pending (will retry on next scan)")
                        true
                    }
                    is MediaMover.MoveResult.Failure -> {
                        Log.e(TAG, "processFile: move failed for $filename")
                        false
                    }
                }
            } else {
                // Mark as processed so we don't re-scan it on every observer callback
                processedUris[uriKey] = Unit
                false
            }
        } catch (e: Exception) {
            Log.e(TAG, "processFile: error processing $filename", e)
            false
        }
    }
}

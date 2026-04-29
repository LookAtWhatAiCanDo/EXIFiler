package com.exifiler.android

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
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.provider.MediaStore
import android.util.Log
import androidx.core.app.NotificationCompat
import com.exifiler.DetectionResult
import com.exifiler.MetadataDetector
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
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

    companion object {
        private const val TAG = "EXIFilerService"
        private const val NOTIFICATION_ID = 1001
        private const val CHANNEL_ID = "exifiler_service_channel"
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
        val channel = NotificationChannel(
            CHANNEL_ID,
            getString(R.string.notification_channel_name),
            NotificationManager.IMPORTANCE_LOW
        ).apply {
            description = "EXIFiler background service notifications"
        }
        val manager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        manager.createNotificationChannel(channel)
    }

    private fun buildNotification(): Notification {
        val pendingIntent = PendingIntent.getActivity(
            this, 0,
            Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_IMMUTABLE
        )
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle(getString(R.string.notification_title))
            .setContentText(getString(R.string.notification_text))
            .setSmallIcon(R.drawable.ic_notification)
            .setContentIntent(pendingIntent)
            .setOngoing(true)
            .build()
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

        data class FileEntry(val uri: Uri, val name: String)
        val candidates = mutableListOf<FileEntry>()

        fun queryCollection(
            collectionUri: Uri,
            idColumn: String,
            nameColumn: String,
            selection: String?,
            selectionArgs: Array<String>?
        ) {
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
                    Log.d(TAG, "scanDownloads: $collectionUri → ${cursor.count} row(s)")
                } ?: Log.e(TAG, "scanDownloads: query returned null for $collectionUri")
        }

        // 1. MediaStore.Downloads — files placed in Downloads by download manager / apps.
        //    Filter by MIME type or extension since Downloads can contain any file type.
        queryCollection(
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

        // 2. MediaStore.Images — JPEG files in the Download folder.
        //    On many devices, media files transferred via USB/cable or saved by other apps are
        //    indexed here (not in Downloads) even when physically located in Download/.
        queryCollection(
            MediaStore.Images.Media.EXTERNAL_CONTENT_URI,
            MediaStore.Images.Media._ID, MediaStore.Images.Media.DISPLAY_NAME,
            selection = "${MediaStore.Images.Media.RELATIVE_PATH} LIKE ?",
            selectionArgs = arrayOf("Download%")
        )

        // 3. MediaStore.Video — MP4/MOV files in the Download folder (same reasoning as Images).
        queryCollection(
            MediaStore.Video.Media.EXTERNAL_CONTENT_URI,
            MediaStore.Video.Media._ID, MediaStore.Video.Media.DISPLAY_NAME,
            selection = "${MediaStore.Video.Media.RELATIVE_PATH} LIKE ? AND " +
                "((${MediaStore.Video.Media.MIME_TYPE} IN (?,?)) " +
                "OR (${MediaStore.Video.Media.DISPLAY_NAME} LIKE ? COLLATE NOCASE))",
            selectionArgs = arrayOf("Download%", "video/mp4", "video/quicktime", "%.mp4")
        )

        Log.d(TAG, "scanDownloads: ${candidates.size} total candidate(s) across all collections")

        var newCount = 0
        for ((fileUri, name) in candidates) {
            val uriKey = fileUri.toString()
            if (uriKey in processedUris) {
                Log.v(TAG, "scanDownloads: skipping already-processed $name")
                continue
            }
            newCount++
            Log.d(TAG, "scanDownloads: processing $name ($fileUri)")
            processFile(fileUri, name, uriKey)
        }

        Log.i(TAG, "scanDownloads: ${candidates.size} candidate(s) found, $newCount new file(s) processed")
        Log.d(TAG, "-scanDownloads()")
    }

    private suspend fun processFile(uri: Uri, filename: String, uriKey: String) {
        Log.d(TAG, "processFile: $filename ($uri)")
        try {
            val inputStream = contentResolver.openInputStream(uri) ?: run {
                Log.w(TAG, "processFile: openInputStream returned null for $filename")
                return
            }
            val result = inputStream.use { stream ->
                MetadataDetector.detect(stream.source().buffer(), filename)
            }

            Log.d(TAG, "processFile: $filename -> $result")
            if (result is DetectionResult.Match) {
                Log.i(TAG, "processFile: Meta device file detected: $filename (device=${result.deviceName})")
                val targetFolder = preferencesManager.getTargetFolder()
                val moveResult = MediaMover.moveFile(
                    context = applicationContext,
                    sourceUri = uri,
                    filename = filename,
                    targetFolder = targetFolder
                )
                if (moveResult) {
                    processedUris[uriKey] = Unit
                    val timestamp = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.getDefault())
                        .format(Date())
                    preferencesManager.addActivityLogEntry(
                        "$timestamp | $filename | Downloads → $targetFolder"
                    )
                    Log.i(TAG, "processFile: moved $filename to $targetFolder")
                } else {
                    Log.e(TAG, "processFile: move failed for $filename")
                }
            } else {
                // Mark as processed so we don't re-scan it on every observer callback
                processedUris[uriKey] = Unit
            }
        } catch (e: Exception) {
            Log.e(TAG, "processFile: error processing $filename", e)
        }
    }
}

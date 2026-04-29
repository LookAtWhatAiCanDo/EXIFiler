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
    private lateinit var downloadsObserver: ContentObserver
    private lateinit var preferencesManager: AppPreferencesManager
    private val scanMutex = Mutex()
    private val processedUris = object : LinkedHashMap<Long, Unit>(64, 0.75f, true) {
        override fun removeEldestEntry(eldest: Map.Entry<Long, Unit>): Boolean = size > 500
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
        contentResolver.unregisterContentObserver(downloadsObserver)
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
        val downloadsUri = MediaStore.Downloads.EXTERNAL_CONTENT_URI
        downloadsObserver = object : ContentObserver(Handler(Looper.getMainLooper())) {
            override fun onChange(selfChange: Boolean, uri: Uri?) {
                // Skip if a scan is already in progress (coalesce rapid notifications)
                if (scanMutex.isLocked) return
                serviceScope.launch {
                    scanDownloads()
                }
            }
        }
        contentResolver.registerContentObserver(downloadsUri, true, downloadsObserver)
        // Perform initial scan
        serviceScope.launch { scanDownloads() }
    }

    private suspend fun scanDownloads() = scanMutex.withLock {
        Log.d(TAG, "+scanDownloads()")
        val projection = arrayOf(
            MediaStore.Downloads._ID,
            MediaStore.Downloads.DISPLAY_NAME,
            MediaStore.Downloads.MIME_TYPE,
            MediaStore.Downloads.DATE_ADDED
        )
        // Match by MIME type OR by filename extension so that files whose MIME_TYPE column is
        // null or set incorrectly (e.g. transferred via USB/ADB) are still picked up.
        // Note: LIKE with leading wildcards cannot use an index, but this is unavoidable for
        // suffix extension matching and the Downloads table is typically small.
        val selection = "(${MediaStore.Downloads.MIME_TYPE} IN (?, ?, ?, ?, ?)) " +
            "OR (${MediaStore.Downloads.DISPLAY_NAME} LIKE ? COLLATE NOCASE) " +
            "OR (${MediaStore.Downloads.DISPLAY_NAME} LIKE ? COLLATE NOCASE) " +
            "OR (${MediaStore.Downloads.DISPLAY_NAME} LIKE ? COLLATE NOCASE)"
        val selectionArgs = arrayOf(
            "image/jpeg", "image/jpg", "image/pjpeg", "video/mp4", "video/quicktime",
            "%.jpg", "%.jpeg", "%.mp4"
        )
        val sortOrder = "${MediaStore.Downloads.DATE_ADDED} DESC"

        val cursor = contentResolver.query(
            MediaStore.Downloads.EXTERNAL_CONTENT_URI,
            projection, selection, selectionArgs, sortOrder
        )
        if (cursor == null) {
            Log.e(TAG, "scanDownloads: contentResolver.query returned null")
            return@withLock
        }
        cursor.use {
            val total = cursor.count
            Log.d(TAG, "scanDownloads: query returned $total candidate file(s)")

            val idCol = cursor.getColumnIndexOrThrow(MediaStore.Downloads._ID)
            val nameCol = cursor.getColumnIndexOrThrow(MediaStore.Downloads.DISPLAY_NAME)
            val mimeCol = cursor.getColumnIndexOrThrow(MediaStore.Downloads.MIME_TYPE)

            var newCount = 0
            while (cursor.moveToNext()) {
                val id = cursor.getLong(idCol)
                val name = cursor.getString(nameCol) ?: continue
                val mime = cursor.getString(mimeCol) ?: "<null>"

                if (id in processedUris) {
                    Log.v(TAG, "scanDownloads: skipping already-processed $name")
                    continue
                }
                newCount++
                Log.d(TAG, "scanDownloads: processing $name (mime=$mime, id=$id)")
                val fileUri = ContentUris.withAppendedId(
                    MediaStore.Downloads.EXTERNAL_CONTENT_URI, id
                )
                processFile(fileUri, name, id)
            }

            Log.i(TAG, "scanDownloads: $total candidate(s) found, $newCount new file(s) processed")
        }

        Log.d(TAG, "-scanDownloads()")
    }

    private suspend fun processFile(uri: Uri, filename: String, id: Long) {
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
                    processedUris[id] = Unit
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
                processedUris[id] = Unit
            }
        } catch (e: Exception) {
            Log.e(TAG, "processFile: error processing $filename", e)
        }
    }
}

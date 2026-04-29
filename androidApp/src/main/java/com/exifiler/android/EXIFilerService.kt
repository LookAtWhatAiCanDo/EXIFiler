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
        val projection = arrayOf(
            MediaStore.Downloads._ID,
            MediaStore.Downloads.DISPLAY_NAME,
            MediaStore.Downloads.MIME_TYPE,
            MediaStore.Downloads.DATE_ADDED
        )
        val selection = "${MediaStore.Downloads.MIME_TYPE} IN (?, ?, ?, ?)"
        val selectionArgs = arrayOf("image/jpeg", "image/jpg", "video/mp4", "video/quicktime")
        val sortOrder = "${MediaStore.Downloads.DATE_ADDED} DESC"

        contentResolver.query(
            MediaStore.Downloads.EXTERNAL_CONTENT_URI,
            projection, selection, selectionArgs, sortOrder
        )?.use { cursor ->
            val idCol = cursor.getColumnIndexOrThrow(MediaStore.Downloads._ID)
            val nameCol = cursor.getColumnIndexOrThrow(MediaStore.Downloads.DISPLAY_NAME)

            while (cursor.moveToNext()) {
                val id = cursor.getLong(idCol)
                val name = cursor.getString(nameCol) ?: continue

                if (id in processedUris) continue
                val fileUri = ContentUris.withAppendedId(
                    MediaStore.Downloads.EXTERNAL_CONTENT_URI, id
                )
                processFile(fileUri, name, id)
            }
        }
    }

    private suspend fun processFile(uri: Uri, filename: String, id: Long) {
        try {
            val inputStream = contentResolver.openInputStream(uri) ?: return
            val result = inputStream.use { stream ->
                MetadataDetector.detect(stream.source().buffer(), filename)
            }

            if (result is DetectionResult.Match) {
                Log.i(TAG, "Matched Meta device file: $filename")
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
                    Log.i(TAG, "Moved $filename to $targetFolder")
                }
            } else {
                // Mark as processed so we don't re-scan it
                processedUris[id] = Unit
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error processing file $filename", e)
        }
    }
}

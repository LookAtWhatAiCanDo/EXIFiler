package com.exifiler.android

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
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
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Foreground service that watches MediaStore for new media files and delegates scanning to
 * [MediaScanner]. The service exists purely to host the [ContentObserver]s and the foreground
 * notification — all scan logic lives in [MediaScanner], which is also invoked directly from
 * the UI's Scan Now button without going through this service.
 */
class EXIFilerService : Service() {

    private val serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val contentObservers = mutableListOf<ContentObserver>()
    private lateinit var preferencesManager: AppPreferencesManager

    companion object {
        private const val TAG = "EXIFilerService"
        private const val NOTIFICATION_ID = 1001
        private const val CHANNEL_ID = MediaScanner.CHANNEL_ID
        const val ACTION_QUIT = "com.exifiler.android.action.QUIT"
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
        if (intent?.action == ACTION_QUIT) {
            serviceScope.launch {
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
                    serviceScope.launch { MediaScanner.scan(applicationContext) }
                }
            }
            contentResolver.registerContentObserver(collectionUri, true, observer)
            contentObservers.add(observer)
        }
        // Initial scan when the service starts.
        serviceScope.launch { MediaScanner.scan(applicationContext) }
    }
}

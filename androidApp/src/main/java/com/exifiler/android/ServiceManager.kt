package com.exifiler.android

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch

object ServiceManager {

    private const val TAG = "ServiceManager"

    fun startService(context: Context) {
        val intent = Intent(context, EXIFilerService::class.java)
        context.startForegroundService(intent)
        Log.i(TAG, "EXIFilerService start requested")
    }

    fun stopService(context: Context) {
        val intent = Intent(context, EXIFilerService::class.java)
        context.stopService(intent)
        Log.i(TAG, "EXIFilerService stop requested")
    }

    /**
     * Runs an immediate scan via [MediaScanner], independent of [EXIFilerService]. Never
     * starts or stops the service — Scan Now must work regardless of whether monitoring
     * is enabled, and tapping it must not turn monitoring back on.
     */
    fun requestScan(context: Context, scope: CoroutineScope) {
        scope.launch { MediaScanner.scan(context.applicationContext) }
        Log.i(TAG, "MediaScanner.scan dispatched")
    }

    class BootReceiver : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            val action = intent.action ?: return
            if (action == Intent.ACTION_BOOT_COMPLETED ||
                action == "android.intent.action.LOCKED_BOOT_COMPLETED"
            ) {
                // goAsync() keeps the receiver alive past onReceive() so the
                // coroutine can safely read DataStore before calling startService.
                val pendingResult = goAsync()
                val prefsManager = AppPreferencesManager(context)
                CoroutineScope(Dispatchers.IO).launch {
                    try {
                        val enabled = prefsManager.isServiceEnabled()
                        if (enabled) {
                            Log.i(TAG, "Boot completed — restarting EXIFilerService")
                            startService(context)
                        }
                    } finally {
                        pendingResult.finish()
                    }
                }
            }
        }
    }
}

package com.exifiler.android

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.os.Build
import android.util.Log
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch

object ServiceManager {

    private const val TAG = "ServiceManager"

    fun startService(context: Context) {
        val intent = Intent(context, EXIFilerService::class.java)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            context.startForegroundService(intent)
        } else {
            context.startService(intent)
        }
        Log.i(TAG, "EXIFilerService start requested")
    }

    fun stopService(context: Context) {
        val intent = Intent(context, EXIFilerService::class.java)
        context.stopService(intent)
        Log.i(TAG, "EXIFilerService stop requested")
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

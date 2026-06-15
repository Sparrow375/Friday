package com.friday.assistant.core

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log
import com.friday.assistant.ui.FridayForegroundService

class BootReceiver : BroadcastReceiver() {
    companion object {
        private const val TAG = "BootReceiver"
    }

    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action == Intent.ACTION_BOOT_COMPLETED) {
            Log.i(TAG, "System boot completed. Automatically starting FridayForegroundService...")
            try {
                FridayForegroundService.start(context)
            } catch (e: Exception) {
                Log.e(TAG, "Failed to start FridayForegroundService on boot", e)
            }
        }
    }
}

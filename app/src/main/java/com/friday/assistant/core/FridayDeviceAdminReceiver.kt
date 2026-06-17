package com.friday.assistant.core

import android.app.admin.DeviceAdminReceiver
import android.content.Context
import android.content.Intent
import android.util.Log

class FridayDeviceAdminReceiver : DeviceAdminReceiver() {
    companion object {
        private const val TAG = "FridayDeviceAdminReceiver"
    }

    override fun onEnabled(context: Context, intent: Intent) {
        Log.i(TAG, "Device Admin privileges enabled for Friday")
    }

    override fun onDisabled(context: Context, intent: Intent) {
        Log.i(TAG, "Device Admin privileges disabled for Friday")
    }
}

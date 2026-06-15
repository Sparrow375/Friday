package com.friday.assistant.ui

import android.accessibilityservice.AccessibilityService
import android.util.Log
import android.view.accessibility.AccessibilityEvent

class FridayService : AccessibilityService() {

    companion object {
        private const val TAG = "FridayService"

        @Volatile
        var instance: FridayService? = null
            private set
    }

    override fun onServiceConnected() {
        super.onServiceConnected()
        Log.i(TAG, "Accessibility Service Connected successfully")
        instance = this

        // Proactively ensure the foreground process is active when service binds
        FridayForegroundService.start(this)
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        // Accessibility events will be processed here for active window readers
    }

    override fun onInterrupt() {
        Log.w(TAG, "Accessibility Service Interrupted")
    }

    override fun onDestroy() {
        super.onDestroy()
        Log.i(TAG, "Accessibility Service Destroyed")
        instance = null
    }
}

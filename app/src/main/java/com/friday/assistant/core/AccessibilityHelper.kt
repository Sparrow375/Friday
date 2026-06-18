package com.friday.assistant.core

import android.content.Context
import android.provider.Settings
import android.text.TextUtils

object AccessibilityHelper {
    private const val SERVICE_ID = "com.friday.assistant/com.friday.assistant.automation.FridayAccessibilityService"

    fun isFridayAccessibilityEnabled(context: Context): Boolean {
        val enabled = Settings.Secure.getString(
            context.contentResolver,
            Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES
        ) ?: return false
        val splitter = TextUtils.SimpleStringSplitter(':')
        splitter.setString(enabled)
        while (splitter.hasNext()) {
            if (splitter.next().equals(SERVICE_ID, ignoreCase = true)) return true
        }
        return false
    }

    fun openAccessibilitySettings(context: Context) {
        context.startActivity(
            android.content.Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS).apply {
                flags = android.content.Intent.FLAG_ACTIVITY_NEW_TASK
            }
        )
    }
}

package com.friday.assistant.ui

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.Bundle
import android.os.PowerManager
import android.os.VibrationEffect
import android.os.Vibrator
import android.os.VibratorManager
import android.view.WindowManager
import android.util.Log
import com.friday.assistant.core.FridayLogger

/**
 * Ultra-fast, transparent activity that serves as an entry point for:
 * 1. Physical Side Button mappings (e.g. Samsung Side Key shortcut).
 * 2. System Voice Assistant intents (ACTION_ASSIST, ACTION_VOICE_COMMAND, SEARCH_LONG_PRESS).
 * 3. App Shortcuts & Quick Settings / Widgets.
 *
 * It immediately triggers the assistant overlay and voice recording, then closes itself
 * instantly so the user remains on their current screen without any visual disruption.
 */
class TriggerActivity : Activity() {

    companion object {
        private const val TAG = "TriggerActivity"
        const val ACTION_TRIGGER = "com.friday.assistant.ACTION_TRIGGER_ASSISTANT"
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        FridayLogger.i(TAG, "TriggerActivity launched via intent: ${intent?.action}")

        // 1. Configure lockscreen visibility & screen turn-on
        setupLockscreenFlags()

        // 2. Wake screen if dark
        wakeScreenIfNecessary()

        // 3. Perform immediate haptic click
        performHapticClick()

        // 4. Trigger Assistant
        triggerAssistant()

        // 5. Close immediately without transition animation
        finish()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            overrideActivityTransition(OVERRIDE_TRANSITION_CLOSE, 0, 0)
        } else {
            @Suppress("DEPRECATION")
            overridePendingTransition(0, 0)
        }
    }

    private fun setupLockscreenFlags() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O_MR1) {
            setShowWhenLocked(true)
            setTurnScreenOn(true)
        }
        @Suppress("DEPRECATION")
        window.addFlags(
            WindowManager.LayoutParams.FLAG_SHOW_WHEN_LOCKED or
            WindowManager.LayoutParams.FLAG_DISMISS_KEYGUARD or
            WindowManager.LayoutParams.FLAG_TURN_SCREEN_ON or
            WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON
        )
    }

    private fun wakeScreenIfNecessary() {
        try {
            val pm = getSystemService(Context.POWER_SERVICE) as? PowerManager
            val isScreenOn = pm?.isInteractive == true
            if (!isScreenOn) {
                @Suppress("DEPRECATION")
                val wakeLock = pm?.newWakeLock(
                    PowerManager.SCREEN_BRIGHT_WAKE_LOCK or PowerManager.ACQUIRE_CAUSES_WAKEUP or PowerManager.ON_AFTER_RELEASE,
                    "Friday:TriggerWakeLock"
                )
                wakeLock?.acquire(3000L)
            }
        } catch (e: Exception) {
            FridayLogger.e(TAG, "Failed to acquire wake lock in TriggerActivity", e)
        }
    }

    private fun performHapticClick() {
        try {
            val prefs = getSharedPreferences("friday_assistant_prefs", Context.MODE_PRIVATE)
            if (prefs.getBoolean("haptic_feedback_enabled", true)) {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                    val vm = getSystemService(Context.VIBRATOR_MANAGER_SERVICE) as? VibratorManager
                    vm?.defaultVibrator?.vibrate(VibrationEffect.createPredefined(VibrationEffect.EFFECT_CLICK))
                } else if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                    @Suppress("DEPRECATION")
                    val v = getSystemService(Context.VIBRATOR_SERVICE) as? Vibrator
                    v?.vibrate(VibrationEffect.createPredefined(VibrationEffect.EFFECT_CLICK))
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "Haptic vibration error", e)
        }
    }

    private fun triggerAssistant() {
        val service = FridayService.instance
        if (service != null) {
            FridayService.triggerGestureActivation()
        } else {
            FridayLogger.w(TAG, "FridayService instance is null; attempting to trigger via intent")
            try {
                val svcIntent = Intent(this, FridayService::class.java).apply {
                    action = FridayService.ACTION_TRIGGER_GESTURE
                }
                startService(svcIntent)
            } catch (e: Exception) {
                FridayLogger.e(TAG, "Failed to start FridayService directly", e)
            }
        }
    }
}

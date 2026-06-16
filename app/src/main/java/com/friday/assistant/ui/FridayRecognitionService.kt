package com.friday.assistant.ui

import android.speech.RecognitionService
import android.content.Intent

class FridayRecognitionService : RecognitionService() {
    override fun onStartListening(recognizerIntent: Intent?, listener: Callback?) {
        // Minimal stub to satisfy Android's system assistant API
    }

    override fun onCancel(listener: Callback?) {
        // Minimal stub
    }

    override fun onStopListening(listener: Callback?) {
        // Minimal stub
    }
}

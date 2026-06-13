package com.friday.assistant.core.native

import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

class WhisperEngine {
    companion object {
        private const val TAG = "WhisperEngine"
        init {
            try {
                System.loadLibrary("friday_native")
                Log.i(TAG, "Native library 'friday_native' loaded successfully for WhisperEngine")
            } catch (e: UnsatisfiedLinkError) {
                Log.e(TAG, "Failed to load native library 'friday_native' for WhisperEngine", e)
            }
        }
    }

    private var contextPtr: Long = 0

    // JNI Declarations
    private external fun initWhisper(modelPath: String): Long
    private external fun freeWhisper(ctxPtr: Long)
    private external fun transcribeWhisper(ctxPtr: Long, audioSamples: FloatArray): String

    @Synchronized
    suspend fun loadModel(modelPath: String): Boolean = withContext(Dispatchers.IO) {
        if (contextPtr != 0L) {
            freeModel()
        }
        try {
            contextPtr = initWhisper(modelPath)
            contextPtr != 0L
        } catch (e: Exception) {
            Log.e(TAG, "Exception initializing Whisper model", e)
            false
        }
    }

    @Synchronized
    suspend fun freeModel() = withContext(Dispatchers.IO) {
        if (contextPtr != 0L) {
            try {
                freeWhisper(contextPtr)
            } catch (e: Exception) {
                Log.e(TAG, "Exception freeing Whisper model", e)
            } finally {
                contextPtr = 0L
            }
        }
    }

    @Synchronized
    suspend fun transcribe(audioSamples: FloatArray): String = withContext(Dispatchers.Default) {
        if (contextPtr == 0L) {
            Log.e(TAG, "Whisper model not loaded")
            return@withContext ""
        }
        try {
            transcribeWhisper(contextPtr, audioSamples)
        } catch (e: Exception) {
            Log.e(TAG, "Exception transcribing audio", e)
            ""
        }
    }

    fun isModelLoaded(): Boolean = contextPtr != 0L
}

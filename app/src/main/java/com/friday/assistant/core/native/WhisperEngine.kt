package com.friday.assistant.core.native

import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

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
    private val mutex = Mutex()

    // JNI Declarations
    private external fun initWhisper(modelPath: String): Long
    private external fun freeWhisper(ctxPtr: Long)
    private external fun abortWhisper()
    private external fun transcribeWhisper(ctxPtr: Long, audioSamples: FloatArray, initialPrompt: String?): String

    /**
     * Signals any in-flight Whisper transcription to abort immediately so the caller
     * does not stall waiting on the WhisperEngine mutex.
     */
    fun abortTranscription() {
        try {
            abortWhisper()
        } catch (e: Throwable) {
            Log.w(TAG, "Error signaling Whisper abort", e)
        }
    }

    suspend fun loadModel(modelPath: String): Boolean = mutex.withLock {
        withContext(Dispatchers.IO) {
            if (contextPtr != 0L) {
                freeModelInternal()
            }
            try {
                contextPtr = initWhisper(modelPath)
                contextPtr != 0L
            } catch (e: Throwable) {
                Log.e(TAG, "Exception initializing Whisper model", e)
                false
            }
        }
    }

    suspend fun freeModel() = mutex.withLock {
        withContext(Dispatchers.IO) {
            freeModelInternal()
        }
    }

    private fun freeModelInternal() {
        if (contextPtr != 0L) {
            try {
                freeWhisper(contextPtr)
            } catch (e: Throwable) {
                Log.e(TAG, "Exception freeing Whisper model", e)
            } finally {
                contextPtr = 0L
            }
        }
    }

    suspend fun transcribe(audioSamples: FloatArray, initialPrompt: String? = null): String = mutex.withLock {
        withContext(Dispatchers.IO) {
            if (contextPtr == 0L) {
                Log.e(TAG, "Whisper model not loaded")
                return@withContext ""
            }
            try {
                transcribeWhisper(contextPtr, audioSamples, initialPrompt)
            } catch (e: Throwable) {
                Log.e(TAG, "Exception transcribing audio", e)
                ""
            }
        }
    }

    fun isModelLoaded(): Boolean = contextPtr != 0L
}

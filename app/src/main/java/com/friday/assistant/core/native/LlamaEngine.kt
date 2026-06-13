package com.friday.assistant.core.native

import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

class LlamaEngine {
    companion object {
        private const val TAG = "LlamaEngine"
        init {
            try {
                System.loadLibrary("friday_native")
                Log.i(TAG, "Native library 'friday_native' loaded successfully for LlamaEngine")
            } catch (e: UnsatisfiedLinkError) {
                Log.e(TAG, "Failed to load native library 'friday_native'", e)
            }
        }
    }

    private var statePtr: Long = 0

    // JNI Declarations
    private external fun initLlama(modelPath: String): Long
    private external fun freeLlama(statePtr: Long)
    private external fun clearLlamaHistory(statePtr: Long)
    private external fun generateLlama(statePtr: Long, promptStr: String, maxTokens: Int, temp: Float): String

    @Synchronized
    suspend fun loadModel(modelPath: String): Boolean = withContext(Dispatchers.IO) {
        if (statePtr != 0L) {
            freeModel()
        }
        try {
            statePtr = initLlama(modelPath)
            statePtr != 0L
        } catch (e: Exception) {
            Log.e(TAG, "Exception initializing Llama model", e)
            false
        }
    }

    @Synchronized
    suspend fun freeModel() = withContext(Dispatchers.IO) {
        if (statePtr != 0L) {
            try {
                freeLlama(statePtr)
            } catch (e: Exception) {
                Log.e(TAG, "Exception freeing Llama model", e)
            } finally {
                statePtr = 0L
            }
        }
    }

    @Synchronized
    fun clearHistory() {
        if (statePtr != 0L) {
            try {
                clearLlamaHistory(statePtr)
            } catch (e: Exception) {
                Log.e(TAG, "Exception clearing Llama history", e)
            }
        }
    }

    @Synchronized
    suspend fun generate(prompt: String, maxTokens: Int = 256, temp: Float = 0.7f): String = withContext(Dispatchers.Default) {
        if (statePtr == 0L) {
            return@withContext "Error: Model not loaded"
        }
        try {
            generateLlama(statePtr, prompt, maxTokens, temp)
        } catch (e: Exception) {
            Log.e(TAG, "Exception in Llama text generation", e)
            "Error: Generation failed"
        }
    }

    fun isModelLoaded(): Boolean = statePtr != 0L
}

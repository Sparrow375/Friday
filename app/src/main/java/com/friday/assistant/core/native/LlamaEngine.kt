package com.friday.assistant.core.native

import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

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
    private val mutex = Mutex()

    interface TokenCallback {
        fun onToken(token: String)
    }

    // JNI Declarations
    private external fun initLlama(modelPath: String): Long
    private external fun freeLlama(statePtr: Long)
    private external fun clearLlamaHistory(statePtr: Long)
    private external fun generateLlama(statePtr: Long, promptStr: String, maxTokens: Int, temp: Float): String
    private external fun generateLlamaStream(statePtr: Long, promptStr: String, maxTokens: Int, temp: Float, callback: TokenCallback): String

    suspend fun loadModel(modelPath: String): Boolean = mutex.withLock {
        withContext(Dispatchers.IO) {
            if (statePtr != 0L) {
                freeModelInternal()
            }
            try {
                statePtr = initLlama(modelPath)
                statePtr != 0L
            } catch (e: Throwable) {
                Log.e(TAG, "Exception initializing Llama model", e)
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
        if (statePtr != 0L) {
            try {
                freeLlama(statePtr)
            } catch (e: Throwable) {
                Log.e(TAG, "Exception freeing Llama model", e)
            } finally {
                statePtr = 0L
            }
        }
    }

    suspend fun clearHistory() = mutex.withLock {
        if (statePtr != 0L) {
            try {
                clearLlamaHistory(statePtr)
            } catch (e: Throwable) {
                Log.e(TAG, "Exception clearing Llama history", e)
            }
        }
    }

    suspend fun generate(prompt: String, maxTokens: Int = 256, temp: Float = 0.7f): String = mutex.withLock {
        withContext(Dispatchers.IO) {
            if (statePtr == 0L) {
                return@withContext "Error: Model not loaded"
            }
            try {
                generateLlama(statePtr, prompt, maxTokens, temp)
            } catch (e: Throwable) {
                Log.e(TAG, "Exception in Llama text generation", e)
                "Error: Generation failed"
            }
        }
    }

    suspend fun generateStream(prompt: String, maxTokens: Int = 256, temp: Float = 0.7f, callback: TokenCallback): String = mutex.withLock {
        withContext(Dispatchers.IO) {
            if (statePtr == 0L) {
                return@withContext "Error: Model not loaded"
            }
            try {
                generateLlamaStream(statePtr, prompt, maxTokens, temp, callback)
            } catch (e: Throwable) {
                Log.e(TAG, "Exception in Llama text generation stream", e)
                "Error: Generation failed"
            }
        }
    }

    fun isModelLoaded(): Boolean = statePtr != 0L
}

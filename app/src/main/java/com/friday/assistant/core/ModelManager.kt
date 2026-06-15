package com.friday.assistant.core

import android.content.Context
import android.util.Log
import java.io.File
import java.io.FileOutputStream

class ModelManager(private val context: Context) {
    companion object {
        private const val TAG = "ModelManager"
        const val SPEAKER_MODEL_NAME = "speaker_verification.onnx"
        const val PREFS_NAME = "friday_model_prefs"
        const val KEY_LLM_PATH = "llm_model_path"
        const val KEY_WHISPER_PATH = "whisper_model_path"
    }

    private val sharedPrefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    fun getLlmModelPath(): String {
        val savedPath = sharedPrefs.getString(KEY_LLM_PATH, null)
        if (!savedPath.isNullOrEmpty() && File(savedPath).exists()) {
            return savedPath
        }
        // Fallback default path
        val defaultDir = context.getExternalFilesDir("models")
        val defaultFile = File(defaultDir, "qwen2.5-3b-instruct-q4_k_m.gguf")
        return defaultFile.absolutePath
    }

    fun setLlmModelPath(path: String) {
        sharedPrefs.edit().putString(KEY_LLM_PATH, path).apply()
    }

    fun getWhisperModelPath(): String {
        val file = File(context.filesDir, "ggml-tiny-q5_1.bin")
        if (!file.exists()) {
            copyModelFromAssets("ggml-tiny-q5_1.bin", file)
        }
        return file.absolutePath
    }

    fun getSpeakerModelPath(): String {
        val file = File(context.filesDir, SPEAKER_MODEL_NAME)
        if (!file.exists()) {
            copyModelFromAssets(SPEAKER_MODEL_NAME, file)
        }
        return file.absolutePath
    }

    fun isLlmLoaded(): Boolean = File(getLlmModelPath()).exists()

    fun isWhisperLoaded(): Boolean = File(getWhisperModelPath()).exists()

    fun isSpeakerLoaded(): Boolean = File(getSpeakerModelPath()).exists()

    private fun copyModelFromAssets(assetName: String, destFile: File) {
        try {
            Log.i(TAG, "Copying model $assetName from assets to: ${destFile.absolutePath}")
            context.assets.open(assetName).use { inputStream ->
                FileOutputStream(destFile).use { outputStream ->
                    inputStream.copyTo(outputStream)
                }
            }
            Log.d(TAG, "$assetName copied successfully")
        } catch (e: Exception) {
            Log.e(TAG, "Error copying $assetName from assets", e)
        }
    }
}

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
        if (!file.exists() || file.length() < 1024 * 1024 * 5) { // Whisper model should be >5MB
            file.delete()
            copyModelFromAssets("ggml-tiny-q5_1.bin", file)
        }
        return file.absolutePath
    }

    fun getSpeakerModelPath(): String {
        val file = File(context.filesDir, SPEAKER_MODEL_NAME)
        if (!file.exists() || file.length() < 1024 * 1024 * 2) { // Speaker model should be >2MB
            file.delete()
            copyModelFromAssets(SPEAKER_MODEL_NAME, file)
        }
        return file.absolutePath
    }

    fun isLlmLoaded(): Boolean {
        val path = getLlmModelPath()
        val file = File(path)
        return file.exists() && file.length() > 1024 * 1024 * 100 // GGUF should be >100MB
    }

    fun isWhisperLoaded(): Boolean {
        val path = getWhisperModelPath()
        val file = File(path)
        return file.exists() && file.length() > 1024 * 1024 * 5
    }

    fun isSpeakerLoaded(): Boolean {
        val path = getSpeakerModelPath()
        val file = File(path)
        return file.exists() && file.length() > 1024 * 1024 * 2
    }

    private fun copyModelFromAssets(assetName: String, destFile: File) {
        val tempFile = File(destFile.parent, "${destFile.name}.tmp")
        try {
            Log.i(TAG, "Copying model $assetName from assets to temp file: ${tempFile.absolutePath}")
            context.assets.open(assetName).use { inputStream ->
                FileOutputStream(tempFile).use { outputStream ->
                    inputStream.copyTo(outputStream)
                }
            }
            if (tempFile.exists()) {
                if (destFile.exists()) {
                    destFile.delete()
                }
                if (tempFile.renameTo(destFile)) {
                    Log.d(TAG, "$assetName copied successfully and renamed to ${destFile.name}")
                } else {
                    Log.e(TAG, "Failed to rename temp file to ${destFile.name}")
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error copying $assetName from assets", e)
            if (tempFile.exists()) {
                tempFile.delete()
            }
        }
    }
}

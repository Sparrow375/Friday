package com.friday.assistant.core

import android.content.Context
import android.util.Log
import java.io.File
import java.io.FileOutputStream

class ModelManager(private val context: Context) {
    companion object {
        private const val TAG = "ModelManager"
        const val SPEAKER_MODEL_NAME = "speaker_verification.onnx"
        const val WAKEWORD_MODEL_NAME = "wakeword.onnx"
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
        val defaultFile = File(defaultDir, "qwen2.5-1.5b-instruct-q4_k_m.gguf")
        return defaultFile.absolutePath
    }

    fun setLlmModelPath(path: String) {
        sharedPrefs.edit().putString(KEY_LLM_PATH, path).apply()
    }

    fun downloadLlmModel(
        onProgress: (Float) -> Unit,
        onFinished: (Boolean, String?) -> Unit
    ) {
        val defaultDir = context.getExternalFilesDir("models") ?: context.filesDir
        if (!defaultDir.exists()) {
            defaultDir.mkdirs()
        }
        val targetFile = File(defaultDir, "qwen2.5-1.5b-instruct-q4_k_m.gguf")
        val tempFile = File(defaultDir, "qwen2.5-1.5b-instruct-q4_k_m.gguf.tmp")
        
        try {
            val url = java.net.URL("https://huggingface.co/Qwen/Qwen2.5-1.5B-Instruct-GGUF/resolve/main/qwen2.5-1.5b-instruct-q4_k_m.gguf")
            val connection = url.openConnection() as java.net.HttpURLConnection
            connection.connectTimeout = 15000
            connection.readTimeout = 15000
            connection.connect()
            
            if (connection.responseCode != java.net.HttpURLConnection.HTTP_OK) {
                onFinished(false, "HTTP error: ${connection.responseCode} ${connection.responseMessage}")
                return
            }
            
            val totalLength = if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.N) {
                connection.contentLengthLong
            } else {
                connection.contentLength.toLong()
            }
            
            connection.inputStream.use { input ->
                tempFile.outputStream().use { output ->
                    val buffer = ByteArray(64 * 1024)
                    var bytesRead: Int
                    var bytesCopied = 0L
                    
                    while (input.read(buffer).also { bytesRead = it } != -1) {
                        output.write(buffer, 0, bytesRead)
                        bytesCopied += bytesRead
                        if (totalLength > 0) {
                            onProgress(bytesCopied.toFloat() / totalLength)
                        }
                    }
                }
            }
            
            if (tempFile.exists()) {
                if (targetFile.exists()) {
                    targetFile.delete()
                }
                if (tempFile.renameTo(targetFile)) {
                    setLlmModelPath(targetFile.absolutePath)
                    onFinished(true, targetFile.absolutePath)
                } else {
                    onFinished(false, "Failed to rename temporary download file.")
                }
            } else {
                onFinished(false, "Temp file was not created.")
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error downloading LLM model", e)
            if (tempFile.exists()) {
                tempFile.delete()
            }
            onFinished(false, e.localizedMessage)
        }
    }

    private fun getAssetSize(assetName: String): Long {
        return try {
            context.assets.openFd(assetName).use { it.length }
        } catch (e: Exception) {
            try {
                context.assets.open(assetName).use { it.available().toLong() }
            } catch (ex: Exception) {
                Log.e(TAG, "Error checking asset size for $assetName", ex)
                0L
            }
        }
    }

    fun getWhisperModelPath(): String {
        val file = File(context.filesDir, "ggml-tiny-q5_1.bin")
        val assetSize = getAssetSize("ggml-tiny-q5_1.bin")
        if (!file.exists() || (assetSize > 0 && file.length() != assetSize)) {
            Log.i(TAG, "Whisper model mismatch or missing (local size: ${file.length()}, asset size: $assetSize). Copying.")
            file.delete()
            copyModelFromAssets("ggml-tiny-q5_1.bin", file)
        }
        return file.absolutePath
    }

    fun getSpeakerModelPath(): String {
        val file = File(context.filesDir, SPEAKER_MODEL_NAME)
        val assetSize = getAssetSize(SPEAKER_MODEL_NAME)
        if (!file.exists() || (assetSize > 0 && file.length() != assetSize)) {
            Log.i(TAG, "Speaker model mismatch or missing (local size: ${file.length()}, asset size: $assetSize). Copying.")
            file.delete()
            copyModelFromAssets(SPEAKER_MODEL_NAME, file)
        }
        return file.absolutePath
    }

    fun getWakeWordModelPath(): String {
        val file = File(context.filesDir, WAKEWORD_MODEL_NAME)
        val assetSize = getAssetSize(WAKEWORD_MODEL_NAME)
        if (!file.exists() || (assetSize > 0 && file.length() != assetSize)) {
            Log.i(TAG, "Wake-word model mismatch or missing (local size: ${file.length()}, asset size: $assetSize). Copying.")
            file.delete()
            copyModelFromAssets(WAKEWORD_MODEL_NAME, file)
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
        val assetSize = getAssetSize("ggml-tiny-q5_1.bin")
        return file.exists() && (assetSize <= 0 || file.length() == assetSize)
    }

    fun isSpeakerLoaded(): Boolean {
        val path = getSpeakerModelPath()
        val file = File(path)
        val assetSize = getAssetSize(SPEAKER_MODEL_NAME)
        return file.exists() && (assetSize <= 0 || file.length() == assetSize)
    }

    fun isWakeWordLoaded(): Boolean {
        val path = getWakeWordModelPath()
        val file = File(path)
        val assetSize = getAssetSize(WAKEWORD_MODEL_NAME)
        return file.exists() && (assetSize <= 0 || file.length() == assetSize)
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

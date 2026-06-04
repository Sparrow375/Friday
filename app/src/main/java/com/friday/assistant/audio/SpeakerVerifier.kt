package com.friday.assistant.audio

import android.content.Context
import android.util.Log
import ai.onnxruntime.OnnxTensor
import ai.onnxruntime.OrtEnvironment
import ai.onnxruntime.OrtSession
import java.io.File
import java.nio.FloatBuffer
import kotlin.math.sqrt

class SpeakerVerifier private constructor(private val context: Context) {

    private var ortEnv: OrtEnvironment? = null
    private var ortSession: OrtSession? = null

    init {
        initModel()
    }

    private fun initModel() {
        val prefs = context.getSharedPreferences("friday_prefs", Context.MODE_PRIVATE)
        val selectedPath = prefs.getString("selected_speaker_path", null)
        var existingModel: File? = null
        
        if (selectedPath != null) {
            val file = File(selectedPath)
            if (file.exists() && file.isFile) {
                existingModel = file
            }
        }
        
        if (existingModel == null) {
            val targetFile = File(context.filesDir, "speaker_verification.onnx")
            if (!targetFile.exists() || targetFile.length() == 0L) {
                try {
                    context.assets.open("speaker_verification.onnx").use { input ->
                        targetFile.outputStream().use { output ->
                            input.copyTo(output)
                        }
                    }
                    Log.i(TAG, "Copied speaker_verification.onnx from assets to filesDir")
                } catch (e: Exception) {
                    Log.e(TAG, "Failed to copy speaker_verification.onnx from assets", e)
                }
            }
            if (targetFile.exists() && targetFile.length() > 0L) {
                existingModel = targetFile
            }
        }

        if (existingModel == null) {
            val possiblePaths = listOf(
                File(context.filesDir, "speaker_verification.onnx"),
                File(context.getExternalFilesDir(null), "speaker_verification.onnx"),
                File("/sdcard/Android/data/com.friday.assistant/files/speaker_verification.onnx")
            )
            existingModel = possiblePaths.firstOrNull { it.exists() && it.isFile }
        }
        
        if (existingModel == null) {
            Log.w(TAG, "Speaker verification ONNX model file does not exist.")
            return
        }
        
        try {
            ortEnv = OrtEnvironment.getEnvironment()
            ortSession = ortEnv?.createSession(existingModel.absolutePath, OrtSession.SessionOptions())
            Log.i(TAG, "ONNX Speaker Verification model loaded successfully from: ${existingModel.absolutePath}")
        } catch (t: Throwable) {
            Log.e(TAG, "Error loading ONNX model or JNI libraries from: ${existingModel.absolutePath}", t)
        }
    }

    fun isModelLoaded(): Boolean = ortSession != null

    /**
     * Extracts a speaker embedding vector from raw PCM audio samples.
     * Audio must be 16kHz mono.
     */
    fun extractEmbedding(pcmData: ShortArray): FloatArray? {
        val session = ortSession ?: return null
        val env = ortEnv ?: return null
        
        try {
            // Convert ShortArray PCM to FloatArray normalized between -1.0f and 1.0f
            val floatAudio = FloatArray(pcmData.size)
            for (i in pcmData.indices) {
                floatAudio[i] = pcmData[i].toFloat() / 32768.0f
            }

            // Create input tensor: shape [1, num_samples]
            val shape = longArrayOf(1, floatAudio.size.toLong())
            val buffer = FloatBuffer.wrap(floatAudio)
            val inputTensor = OnnxTensor.createTensor(env, buffer, shape)

            // Run inference
            val inputName = session.inputNames.iterator().next()
            val results = session.run(mapOf(inputName to inputTensor))
            
            // Get output embedding
            val outputValue = results[0].value
            val embedding = if (outputValue is Array<*>) {
                // If output shape is [1, embedding_size]
                if (outputValue[0] is FloatArray) {
                    outputValue[0] as FloatArray
                } else {
                    null
                }
            } else if (outputValue is FloatArray) {
                outputValue
            } else {
                null
            }

            results.close()
            inputTensor.close()
            return embedding
        } catch (t: Throwable) {
            Log.e(TAG, "Error during speaker embedding extraction", t)
            return null
        }
    }

    /**
     * Verifies if the speaker of the current audio matches the enrolled speaker.
     */
    fun verifySpeaker(pcmData: ShortArray, enrolledEmbedding: FloatArray): Float {
        val currentEmbedding = extractEmbedding(pcmData) ?: return -1f
        return computeCosineSimilarity(currentEmbedding, enrolledEmbedding)
    }

    /**
     * Computes the Cosine Similarity between two embedding vectors.
     * Similarity ranges from -1.0 to 1.0. Typically, 0.75+ is a strong match.
     */
    fun computeCosineSimilarity(vectorA: FloatArray, vectorB: FloatArray): Float {
        if (vectorA.size != vectorB.size || vectorA.isEmpty()) return 0.0f
        
        var dotProduct = 0.0f
        var normA = 0.0f
        var normB = 0.0f
        
        for (i in vectorA.indices) {
            dotProduct += vectorA[i] * vectorB[i]
            normA += vectorA[i] * vectorA[i]
            normB += vectorB[i] * vectorB[i]
        }
        
        return if (normA > 0 && normB > 0) {
            dotProduct / (sqrt(normA) * sqrt(normB))
        } else {
            0.0f
        }
    }

    companion object {
        private const val TAG = "SpeakerVerifier"
        
        @Volatile
        private var INSTANCE: SpeakerVerifier? = null

        fun getInstance(context: Context): SpeakerVerifier? {
            return try {
                INSTANCE ?: synchronized(this) {
                    val instance = SpeakerVerifier(context)
                    INSTANCE = instance
                    instance
                }
            } catch (t: Throwable) {
                Log.e(TAG, "Failed to initialize SpeakerVerifier (JNI loading / model error)", t)
                null
            }
        }
    }
}

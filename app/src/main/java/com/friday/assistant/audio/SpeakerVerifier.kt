package com.friday.assistant.audio

import android.content.Context
import android.util.Log
import ai.onnxruntime.OnnxTensor
import ai.onnxruntime.OrtEnvironment
import ai.onnxruntime.OrtSession
import java.io.File
import java.nio.FloatBuffer
import kotlin.math.sqrt

class SpeakerVerifier private constructor(context: Context) {

    private var ortEnv: OrtEnvironment? = null
    private var ortSession: OrtSession? = null
    private val modelFile = File(context.filesDir, "speaker_verification.onnx")

    init {
        initModel()
    }

    private fun initModel() {
        if (!modelFile.exists()) {
            Log.w(TAG, "Speaker verification ONNX model file does not exist at ${modelFile.absolutePath}")
            return
        }
        try {
            ortEnv = OrtEnvironment.getEnvironment()
            ortSession = ortEnv?.createSession(modelFile.absolutePath, OrtSession.SessionOptions())
            Log.i(TAG, "ONNX Speaker Verification model loaded successfully.")
        } catch (t: Throwable) {
            Log.e(TAG, "Error loading ONNX model or JNI libraries", t)
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

        fun getInstance(context: Context): SpeakerVerifier {
            return INSTANCE ?: synchronized(this) {
                val instance = SpeakerVerifier(context)
                INSTANCE = instance
                instance
            }
        }
    }
}

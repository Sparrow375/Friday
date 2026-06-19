package com.friday.assistant.audio

import android.content.Context
import android.util.Log
import ai.onnxruntime.OnnxTensor
import ai.onnxruntime.OrtEnvironment
import ai.onnxruntime.OrtSession
import com.friday.assistant.core.ModelManager
import com.google.gson.Gson
import com.google.gson.reflect.TypeToken
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.nio.FloatBuffer
import kotlin.math.sqrt

class SpeakerVerifier(private val context: Context, private val modelManager: ModelManager) {

    companion object {
        private const val TAG = "SpeakerVerifier"
        private const val PREFS_NAME = "friday_speaker_prefs"
        private const val KEY_ENROLLED_EMBEDDING = "enrolled_speaker_embedding"
        private const val DEFAULT_MATCH_THRESHOLD = 0.72f
    }

    private var ortEnv: OrtEnvironment? = null
    private var ortSession: OrtSession? = null
    private val sharedPrefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
    private val gson = Gson()

    // Temporary list for recording enrollment samples before averaging
    private val enrollmentSessionEmbeddings = mutableListOf<FloatArray>()

    init {
        try {
            ortEnv = OrtEnvironment.getEnvironment()
            val modelPath = modelManager.getSpeakerModelPath()
            if (File(modelPath).exists()) {
                val options = OrtSession.SessionOptions().apply {
                    setIntraOpNumThreads(2)
                    setOptimizationLevel(OrtSession.SessionOptions.OptLevel.ALL_OPT)
                    try {
                        addNnapi()
                    } catch (e: Exception) {
                        Log.w(TAG, "NNAPI failed to initialize for SpeakerVerifier, using CPU fallback", e)
                    }
                }
                ortSession = ortEnv?.createSession(modelPath, options)
                Log.i(TAG, "ONNX Speaker Verification model loaded successfully")
            } else {
                Log.e(TAG, "Speaker model does not exist at: $modelPath")
            }
        } catch (e: Throwable) {
            Log.e(TAG, "Failed to initialize ONNX Runtime or load speaker model", e)
        }
    }

    fun isModelLoaded(): Boolean = ortSession != null

    /**
     * Extracts speaker embedding vector from raw PCM 16kHz mono audio.
     */
    fun extractEmbedding(pcmData: ShortArray): FloatArray? {
        val session = ortSession ?: return null
        val env = ortEnv ?: return null
        
        try {
            // Convert Short PCM to Float (-1.0 to 1.0)
            val floatAudio = FloatArray(pcmData.size)
            for (i in pcmData.indices) {
                floatAudio[i] = pcmData[i].toFloat() / 32768.0f
            }

            // Shape [1, num_samples]
            val shape = longArrayOf(1, floatAudio.size.toLong())
            val buffer = FloatBuffer.wrap(floatAudio)
            val inputTensor = OnnxTensor.createTensor(env, buffer, shape)

            val inputName = session.inputNames.iterator().next()
            val results = session.run(mapOf(inputName to inputTensor))
            
            val outputValue = results[0].value
            val embedding = when {
                outputValue is Array<*> && outputValue[0] is FloatArray -> outputValue[0] as FloatArray
                outputValue is FloatArray -> outputValue
                else -> null
            }

            results.close()
            inputTensor.close()
            return embedding
        } catch (e: Exception) {
            Log.e(TAG, "Error extracting speaker embedding", e)
            return null
        }
    }

    // ==========================================
    // Speaker Enrollment Flow (Multi-sample)
    // ==========================================

    fun startEnrollmentSession() {
        enrollmentSessionEmbeddings.clear()
        Log.d(TAG, "Enrollment session started. Cache cleared.")
    }

    fun addEnrollmentSample(pcmData: ShortArray): Boolean {
        val embedding = extractEmbedding(pcmData)
        if (embedding != null) {
            enrollmentSessionEmbeddings.add(embedding)
            Log.d(TAG, "Enrollment sample added. Current count: ${enrollmentSessionEmbeddings.size}")
            return true
        }
        Log.e(TAG, "Failed to extract embedding from enrollment sample")
        return false
    }

    fun finalizeEnrollment(): Boolean {
        if (enrollmentSessionEmbeddings.isEmpty()) {
            Log.e(TAG, "No samples collected for enrollment")
            return false
        }

        // Average all embeddings in session
        val embeddingSize = enrollmentSessionEmbeddings[0].size
        val averageEmbedding = FloatArray(embeddingSize)

        for (embedding in enrollmentSessionEmbeddings) {
            if (embedding.size != embeddingSize) continue
            for (i in 0 until embeddingSize) {
                averageEmbedding[i] += embedding[i]
            }
        }

        // Divide by count to get the mean
        val count = enrollmentSessionEmbeddings.size
        for (i in 0 until embeddingSize) {
            averageEmbedding[i] /= count
        }

        // Normalize the average embedding vector
        var normSum = 0.0f
        for (v in averageEmbedding) {
            normSum += v * v
        }
        val norm = sqrt(normSum)
        if (norm > 0) {
            for (i in 0 until embeddingSize) {
                averageEmbedding[i] /= norm
            }
        }

        // Save to Shared Preferences
        val json = gson.toJson(averageEmbedding)
        sharedPrefs.edit().putString(KEY_ENROLLED_EMBEDDING, json).apply()
        Log.i(TAG, "Speaker profile finalized and saved to preferences. Vector size: $embeddingSize")
        return true
    }

    fun isEnrolled(): Boolean {
        return sharedPrefs.contains(KEY_ENROLLED_EMBEDDING)
    }

    fun clearEnrollment() {
        sharedPrefs.edit().remove(KEY_ENROLLED_EMBEDDING).apply()
        Log.i(TAG, "Enrolled speaker profile deleted")
    }

    private fun getEnrolledEmbedding(): FloatArray? {
        val json = sharedPrefs.getString(KEY_ENROLLED_EMBEDDING, null) ?: return null
        return try {
            val type = object : TypeToken<FloatArray>() {}.type
            gson.fromJson<FloatArray>(json, type)
        } catch (e: Exception) {
            Log.e(TAG, "Error parsing enrolled embedding", e)
            null
        }
    }

    // ==========================================
    // Speaker Verification
    // ==========================================

    suspend fun verify(pcmData: ShortArray, threshold: Float = DEFAULT_MATCH_THRESHOLD): Boolean = withContext(Dispatchers.Default) {
        val enrolled = getEnrolledEmbedding()
        if (enrolled == null) {
            Log.w(TAG, "Speaker verification requested but no profile is enrolled. Accepting by default.")
            return@withContext true
        }

        val testEmbedding = extractEmbedding(pcmData) ?: return@withContext false
        val similarity = computeCosineSimilarity(testEmbedding, enrolled)
        Log.i(TAG, "Speaker verification score: $similarity (threshold: $threshold)")
        
        val verified = similarity >= threshold
        if (verified && similarity >= 0.85f) {
            // Adaptively merge high-confidence verify samples into the enrolled embedding
            adaptEnrolledProfile(testEmbedding, learningRate = 0.05f)
        }
        
        return@withContext verified
    }

    private fun adaptEnrolledProfile(newEmbedding: FloatArray, learningRate: Float = 0.05f) {
        val enrolled = getEnrolledEmbedding() ?: return
        if (newEmbedding.size != enrolled.size) return
        
        val updated = FloatArray(enrolled.size)
        for (i in enrolled.indices) {
            updated[i] = enrolled[i] * (1f - learningRate) + newEmbedding[i] * learningRate
        }
        
        // Normalize the updated embedding vector
        var normSum = 0.0f
        for (v in updated) {
            normSum += v * v
        }
        val norm = sqrt(normSum)
        if (norm > 0) {
            for (i in updated.indices) {
                updated[i] /= norm
            }
        }
        
        // Save to Shared Preferences
        val json = gson.toJson(updated)
        sharedPrefs.edit().putString(KEY_ENROLLED_EMBEDDING, json).apply()
        Log.d(TAG, "Speaker profile adaptively updated with learning rate $learningRate")
    }

    private fun computeCosineSimilarity(vectorA: FloatArray, vectorB: FloatArray): Float {
        if (vectorA.size != vectorB.size || vectorA.isEmpty()) return 0.0f
        var dotProduct = 0.0f
        var normA = 0.0f
        var normB = 0.0f
        for (i in vectorA.indices) {
            dotProduct += vectorA[i] * vectorB[i]
            normA += vectorA[i] * vectorA[i]
            normB += vectorB[i] * vectorB[i]
        }
        return if (normA > 0f && normB > 0f) {
            dotProduct / (sqrt(normA) * sqrt(normB))
        } else {
            0.0f
        }
    }
}

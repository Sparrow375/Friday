package com.friday.assistant.intelligence.nlu

import android.content.Context
import android.util.Log
import ai.onnxruntime.OnnxTensor
import ai.onnxruntime.OrtEnvironment
import ai.onnxruntime.OrtSession
import java.io.File
import java.nio.LongBuffer

class NluIntentClassifier(private val context: Context) {
    companion object {
        private const val TAG = "NluIntentClassifier"
        private const val MODEL_NAME = "nlu_model.onnx"
        private const val VOCAB_NAME = "vocab.txt"
    }

    private var ortEnv: OrtEnvironment? = null
    private var ortSession: OrtSession? = null
    private var tokenizer: WordpieceTokenizer? = null
    private var isLoaded = false

    // Mapped labels expected from the fine-tuned ONNX classifier
    private val intentLabels = listOf("volume_up", "volume_down", "lock_phone", "search_reddit", "open_app", "unknown")

    init {
        loadModel()
    }

    fun isModelLoaded(): Boolean = isLoaded

    @Synchronized
    private fun loadModel() {
        try {
            val destDir = context.getExternalFilesDir("models") ?: context.filesDir
            val modelFile = File(destDir, MODEL_NAME)
            val vocabFile = File(destDir, VOCAB_NAME)

            var modelBytes: ByteArray? = null
            var tokenizerLoaded = false

            if (modelFile.exists() && vocabFile.exists()) {
                Log.i(TAG, "Loading custom NLU model from: ${modelFile.absolutePath}")
                modelBytes = modelFile.readBytes()
                val vocabMap = mutableMapOf<String, Int>()
                vocabFile.forEachLine { line ->
                    val word = line.trim()
                    if (word.isNotEmpty()) {
                        vocabMap[word] = vocabMap.size
                    }
                }
                tokenizer = WordpieceTokenizer(vocabMap)
                tokenizerLoaded = true
            } else {
                // Try loading from assets
                val assetsList = context.assets.list("") ?: emptyArray()
                if (assetsList.contains(MODEL_NAME) && assetsList.contains(VOCAB_NAME)) {
                    Log.i(TAG, "Loading NLU model from assets")
                    context.assets.open(MODEL_NAME).use { input ->
                        modelBytes = input.readBytes()
                    }
                    tokenizer = WordpieceTokenizer.loadFromAssets(context, VOCAB_NAME)
                    tokenizerLoaded = true
                }
            }

            if (modelBytes != null && tokenizerLoaded) {
                ortEnv = OrtEnvironment.getEnvironment()
                ortSession = ortEnv?.createSession(modelBytes)
                isLoaded = true
                Log.i(TAG, "NLU ONNX session successfully initialized")
            } else {
                Log.w(TAG, "NLU model files ($MODEL_NAME, $VOCAB_NAME) not found. Running in rule-based command matching fallback.")
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error loading NLU ONNX model", e)
            isLoaded = false
        }
    }

    fun classifyIntent(text: String): Pair<String, Float> {
        if (!isLoaded || ortSession == null || tokenizer == null || ortEnv == null) {
            return Pair("unknown", 0f)
        }

        try {
            val tokenIds = tokenizer!!.tokenize(text)
            if (tokenIds.isEmpty()) return Pair("unknown", 0f)

            // Convert tokens to BERT input format: [CLS] + tokenIds + [SEP]
            val clsId = 101 // Default BERT/MobileBERT CLS
            val sepId = 102 // Default BERT/MobileBERT SEP
            
            val inputIdsList = mutableListOf<Long>()
            inputIdsList.add(clsId.toLong())
            for (id in tokenIds) {
                inputIdsList.add(id.toLong())
            }
            inputIdsList.add(sepId.toLong())

            val seqLen = inputIdsList.size
            val shape = longArrayOf(1, seqLen.toLong())

            val inputIdsBuffer = LongBuffer.wrap(inputIdsList.toLongArray())
            val attentionMaskBuffer = LongBuffer.wrap(LongArray(seqLen) { 1L })
            
            val env = ortEnv!!
            val inputIdsTensor = OnnxTensor.createTensor(env, inputIdsBuffer, shape)
            val attentionMaskTensor = OnnxTensor.createTensor(env, attentionMaskBuffer, shape)

            val inputs = mapOf(
                "input_ids" to inputIdsTensor,
                "attention_mask" to attentionMaskTensor
            )

            ortSession!!.run(inputs).use { results ->
                val outputValue = results[0].value
                val logits = when {
                    outputValue is Array<*> && outputValue[0] is FloatArray -> outputValue[0] as FloatArray
                    outputValue is FloatArray -> outputValue
                    else -> return Pair("unknown", 0f)
                }

                val numClasses = logits.size

                // Softmax selection
                var maxIdx = 0
                var maxVal = logits[0]
                for (i in 1 until numClasses) {
                    if (logits[i] > maxVal) {
                        maxVal = logits[i]
                        maxIdx = i
                    }
                }

                var sumExp = 0.0
                for (v in logits) {
                    sumExp += Math.exp(v.toDouble())
                }
                val confidence = (Math.exp(maxVal.toDouble()) / sumExp).toFloat()

                val intent = if (maxIdx < intentLabels.size) intentLabels[maxIdx] else "unknown"
                Log.d(TAG, "NLU classification result: intent=$intent, confidence=$confidence")
                return Pair(intent, confidence)
            }
        } catch (e: Exception) {
            Log.e(TAG, "Failed running NLU classification", e)
            return Pair("unknown", 0f)
        }
    }
}

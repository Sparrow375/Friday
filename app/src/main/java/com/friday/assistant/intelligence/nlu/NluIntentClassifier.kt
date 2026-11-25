package com.friday.assistant.intelligence.nlu

import android.content.Context
import android.util.Log
import ai.onnxruntime.OnnxTensor
import ai.onnxruntime.OrtEnvironment
import ai.onnxruntime.OrtSession
import java.io.File
import java.nio.LongBuffer

data class JointNluResult(
    val intent: String,
    val confidence: Float,
    val slots: Map<String, String> = emptyMap()
)

class NluIntentClassifier(private val context: Context) {
    companion object {
        private const val TAG = "NluIntentClassifier"
        private const val MODEL_NAME = "joint_nlu_model.onnx"
        private const val VOCAB_NAME = "vocab.txt"
        private const val LABELS_NAME = "labels.txt"
        private const val SLOT_LABELS_NAME = "slot_labels.txt"

        private val PUNCT_ONLY_REGEX = Regex("^[.,!?:;\"'\\-_/\\\\]+$")

        /**
         * Reconstructs BIO-tagged slot entities cleanly.
         * Promotes orphan `I-X` tags (e.g. when model skips `B-X` on first token) to a new entity span
         * instead of silently discarding them.
         */
        internal fun decodeBioSlots(
            tokenStrings: List<String>,
            predictedSlotIds: List<Int>,
            slotLabels: List<String>,
            detokenizer: (List<String>) -> String
        ): Map<String, String> {
            val slotsMap = mutableMapOf<String, String>()
            if (slotLabels.isEmpty() || tokenStrings.size < 2) return slotsMap

            fun commitSlot(tag: String, tokens: List<String>) {
                val raw = detokenizer(tokens).trim()
                val cleanVal = raw.replace(Regex("[\\[\\]]"), "").trim()
                if (cleanVal.isEmpty() || cleanVal.matches(PUNCT_ONLY_REGEX)) return
                if (cleanVal.equals("quote", ignoreCase = true) || cleanVal.equals("contact", ignoreCase = true)) return

                val existing = slotsMap[tag]
                if (existing == null || cleanVal.length > existing.length) {
                    slotsMap[tag] = cleanVal
                }
            }

            var currentTag: String? = null
            val currentTokens = mutableListOf<String>()

            // Skip [CLS] at index 0 and trailing [SEP]
            val limit = minOf(tokenStrings.size - 1, predictedSlotIds.size)
            for (i in 1 until limit) {
                val slotIdx = predictedSlotIds[i]
                val slotTag = if (slotIdx in slotLabels.indices) slotLabels[slotIdx] else "O"
                val tokStr = tokenStrings[i]

                when {
                    slotTag.startsWith("B-") -> {
                        if (currentTag != null && currentTokens.isNotEmpty()) {
                            commitSlot(currentTag, currentTokens)
                        }
                        currentTag = slotTag.substring(2)
                        currentTokens.clear()
                        currentTokens.add(tokStr)
                    }
                    slotTag.startsWith("I-") -> {
                        val entityType = slotTag.substring(2)
                        if (currentTag == entityType) {
                            currentTokens.add(tokStr)
                        } else {
                            // Promote orphan I-X or entity transition into a clean span
                            if (currentTag != null && currentTokens.isNotEmpty()) {
                                commitSlot(currentTag, currentTokens)
                            }
                            currentTag = entityType
                            currentTokens.clear()
                            currentTokens.add(tokStr)
                        }
                    }
                    else -> {
                        if (currentTag != null && currentTokens.isNotEmpty()) {
                            commitSlot(currentTag, currentTokens)
                            currentTag = null
                            currentTokens.clear()
                        }
                    }
                }
            }
            if (currentTag != null && currentTokens.isNotEmpty()) {
                commitSlot(currentTag, currentTokens)
            }
            return slotsMap
        }
    }

    private var ortEnv: OrtEnvironment? = null
    private var ortSession: OrtSession? = null
    private var tokenizer: WordpieceTokenizer? = null
    private var isLoaded = false

    // 56 Intent Labels (v2 default fallback)
    private var intentLabels = listOf(
        "volume_up", "volume_down", "brightness_up", "brightness_down",
        "torch_toggle", "torch_strength", "lock_phone", "open_app",
        "navigate_to", "set_alarm", "set_timer", "set_reminder", "send_whatsapp",
        "play_media", "play_spotify", "play_youtube",
        "pause_media", "next_track", "previous_track",
        "power_saver_toggle", "screencast_toggle",
        "wifi_toggle", "bluetooth_toggle", "hotspot_toggle", "dnd_toggle",
        "call_contact", "read_call_log",
        "take_screenshot", "web_search",
        "clipboard_read", "clipboard_write",
        "read_notifications", "get_battery", "get_time",
        "airplane_mode_toggle", "mobile_data_toggle",
        "open_camera", "open_files",
        "notes_create", "notes_list", "notes_search", "notes_delete", "notes_update",
        "search_google", "search_reddit", "remember_preference", "recall_preference",
        "unknown",
        "sports_score", "sports_follow",
        "calendar_list", "calendar_add",
        "reminder_list", "routine_create", "routine_run", "digest_summary"
    )

    // 23 BIO Slot Labels
    private var slotLabels = listOf(
        "O",
        "B-CONTACT", "I-CONTACT",
        "B-MESSAGE", "I-MESSAGE",
        "B-DESTINATION", "I-DESTINATION",
        "B-APP", "I-APP",
        "B-QUERY", "I-QUERY",
        "B-TIME", "I-TIME",
        "B-VALUE", "I-VALUE",
        "B-NOTE_CONTENT", "I-NOTE_CONTENT",
        "B-FACT", "I-FACT",
        "B-NOTE_ID", "I-NOTE_ID",
        "B-TEXT", "I-TEXT"
    )

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
            val labelsFile = File(destDir, LABELS_NAME)
            val slotLabelsFile = File(destDir, SLOT_LABELS_NAME)

            var modelBytes: ByteArray? = null
            var tokenizerLoaded = false

            val loadedLabels = mutableListOf<String>()
            val assetsList = context.assets.list("") ?: emptyArray()
            if (assetsList.contains(LABELS_NAME)) {
                context.assets.open(LABELS_NAME).bufferedReader().useLines { lines ->
                    lines.forEach { line ->
                        val lbl = line.trim()
                        if (lbl.isNotEmpty()) loadedLabels.add(lbl)
                    }
                }
            }
            if (loadedLabels.size >= 40) {
                intentLabels = loadedLabels
                Log.i(TAG, "Loaded ${loadedLabels.size} intent labels from assets")
            } else if (labelsFile.exists()) {
                labelsFile.forEachLine { line ->
                    val lbl = line.trim()
                    if (lbl.isNotEmpty()) loadedLabels.add(lbl)
                }
                if (loadedLabels.size >= 40) {
                    intentLabels = loadedLabels
                    Log.i(TAG, "Loaded ${loadedLabels.size} intent labels from file")
                }
            }

            val loadedSlotLabels = mutableListOf<String>()
            if (assetsList.contains(SLOT_LABELS_NAME)) {
                context.assets.open(SLOT_LABELS_NAME).bufferedReader().useLines { lines ->
                    lines.forEach { line ->
                        val lbl = line.trim()
                        if (lbl.isNotEmpty()) loadedSlotLabels.add(lbl)
                    }
                }
            }
            if (loadedSlotLabels.size >= 23) {
                slotLabels = loadedSlotLabels
                Log.i(TAG, "Loaded ${loadedSlotLabels.size} slot labels from assets")
            } else if (slotLabelsFile.exists()) {
                slotLabelsFile.forEachLine { line ->
                    val lbl = line.trim()
                    if (lbl.isNotEmpty()) loadedSlotLabels.add(lbl)
                }
                if (loadedSlotLabels.size >= 23) {
                    slotLabels = loadedSlotLabels
                    Log.i(TAG, "Loaded ${loadedSlotLabels.size} slot labels from file")
                }
            }

            if (assetsList.contains(MODEL_NAME) && assetsList.contains(VOCAB_NAME)) {
                Log.i(TAG, "Loading Joint NLU ONNX from assets: $MODEL_NAME")
                context.assets.open(MODEL_NAME).use { input ->
                    modelBytes = input.readBytes()
                }
                tokenizer = WordpieceTokenizer.loadFromAssets(context, VOCAB_NAME)
                tokenizerLoaded = true
            } else if (modelFile.exists() && vocabFile.exists()) {
                Log.i(TAG, "Loading Joint NLU ONNX from files: ${modelFile.absolutePath}")
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
            }

            if (modelBytes != null && tokenizerLoaded) {
                ortEnv = OrtEnvironment.getEnvironment()
                OrtSession.SessionOptions().use { opts ->
                    opts.setIntraOpNumThreads(2)
                    opts.setOptimizationLevel(OrtSession.SessionOptions.OptLevel.ALL_OPT)
                    ortSession = ortEnv?.createSession(modelBytes, opts)
                }
                isLoaded = true
                Log.i(TAG, "Joint NLU ONNX ready (${intentLabels.size} intents, ${slotLabels.size} slots)")
            } else {
                Log.w(TAG, "Joint NLU model files not found.")
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error loading Joint NLU ONNX model", e)
            isLoaded = false
        }
    }

    fun classifyJoint(text: String): JointNluResult {
        if (!isLoaded || ortSession == null || tokenizer == null || ortEnv == null) {
            return JointNluResult("unknown", 0f, emptyMap())
        }

        try {
            val (tokenIds, tokenStrings) = tokenizer!!.tokenizeWithTokens(text)
            if (tokenIds.isEmpty()) return JointNluResult("unknown", 0f, emptyMap())

            val clsId = 101
            val sepId = 102

            val inputIdsList = mutableListOf<Long>(clsId.toLong())
            val allTokenStrings = mutableListOf("[CLS]")

            for (i in tokenIds.indices) {
                inputIdsList.add(tokenIds[i].toLong())
                allTokenStrings.add(tokenStrings[i])
            }

            inputIdsList.add(sepId.toLong())
            allTokenStrings.add("[SEP]")

            val seqLen = inputIdsList.size
            val shape = longArrayOf(1, seqLen.toLong())

            val inputIdsBuffer = LongBuffer.wrap(inputIdsList.toLongArray())
            val attentionMaskBuffer = LongBuffer.wrap(LongArray(seqLen) { 1L })

            val env = ortEnv!!
            OnnxTensor.createTensor(env, inputIdsBuffer, shape).use { inputIdsTensor ->
                OnnxTensor.createTensor(env, attentionMaskBuffer, shape).use { attentionMaskTensor ->
                    val inputs = mapOf(
                        "input_ids" to inputIdsTensor,
                        "attention_mask" to attentionMaskTensor
                    )

                    ortSession!!.run(inputs).use { results ->
                        val intentOutputValue = results[0].value
                        val intentLogits = when {
                            intentOutputValue is Array<*> && intentOutputValue[0] is FloatArray -> intentOutputValue[0] as FloatArray
                            intentOutputValue is FloatArray -> intentOutputValue
                            else -> return JointNluResult("unknown", 0f, emptyMap())
                        }

                        var maxIdx = 0
                        var maxVal = intentLogits[0]
                        for (i in 1 until intentLogits.size) {
                            if (intentLogits[i] > maxVal) {
                                maxVal = intentLogits[i]
                                maxIdx = i
                            }
                        }

                        var sumExp = 0.0
                        for (v in intentLogits) {
                            sumExp += kotlin.math.exp((v - maxVal).toDouble())
                        }
                        val confidence = (1.0 / sumExp).toFloat()
                        val intent = if (maxIdx < intentLabels.size) intentLabels[maxIdx] else "unknown"

                        var cleanedSlotsMap: Map<String, String> = emptyMap()
                        if (results.size() > 1) {
                            val slotOutputValue = results[1].value
                            val slotLogits: Array<FloatArray>? = when {
                                slotOutputValue is Array<*> && slotOutputValue.isNotEmpty() && slotOutputValue[0] is Array<*> -> {
                                    @Suppress("UNCHECKED_CAST")
                                    slotOutputValue[0] as Array<FloatArray>
                                }
                                slotOutputValue is Array<*> && slotOutputValue.isNotEmpty() && slotOutputValue[0] is FloatArray -> {
                                    @Suppress("UNCHECKED_CAST")
                                    slotOutputValue as Array<FloatArray>
                                }
                                else -> null
                            }

                            if (slotLogits != null && slotLabels.isNotEmpty()) {
                                val predictedSlotIds = slotLogits.map { tokenLogits ->
                                    var bestIdx = 0
                                    var bestVal = tokenLogits[0]
                                    for (s in 1 until tokenLogits.size) {
                                        if (tokenLogits[s] > bestVal) {
                                            bestVal = tokenLogits[s]
                                            bestIdx = s
                                        }
                                    }
                                    bestIdx
                                }
                                cleanedSlotsMap = decodeBioSlots(
                                    tokenStrings = allTokenStrings,
                                    predictedSlotIds = predictedSlotIds,
                                    slotLabels = slotLabels,
                                    detokenizer = { toks -> tokenizer!!.convertTokensToString(toks) }
                                )
                            }
                        }

                        Log.d(TAG, "Joint NLU: intent=$intent ($confidence), slots=$cleanedSlotsMap")
                        return JointNluResult(intent, confidence, cleanedSlotsMap)
                    }
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "Failed running Joint NLU classification", e)
            return JointNluResult("unknown", 0f, emptyMap())
        }
    }

    fun classifyIntent(text: String): Pair<String, Float> {
        val res = classifyJoint(text)
        return Pair(res.intent, res.confidence)
    }
}

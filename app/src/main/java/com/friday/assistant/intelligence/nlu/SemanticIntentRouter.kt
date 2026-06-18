package com.friday.assistant.intelligence.nlu

import android.content.Context
import android.util.Log
import ai.onnxruntime.OnnxTensor
import ai.onnxruntime.OrtEnvironment
import ai.onnxruntime.OrtSession
import com.friday.assistant.core.FridayLogger
import com.friday.assistant.core.ModelManager
import java.io.File
import java.nio.LongBuffer

class SemanticIntentRouter(private val context: Context) {

    companion object {
        private const val TAG = "SemanticIntentRouter"
        private const val EMBEDDING_DIM = 384
        private const val SIMILARITY_THRESHOLD = 0.68f // Cosine similarity threshold for routing
    }

    private var ortEnv: OrtEnvironment? = null
    private var ortSession: OrtSession? = null
    private var tokenizer: WordpieceTokenizer? = null
    private var isInitialized = false

    // Data class representing a reference command and its mapped intent
    data class ReferenceCommand(val text: String, val intent: String, var embedding: FloatArray? = null)

    // A comprehensive set of reference commands representing all assistant intents
    private val referenceCommands = listOf(
        // Volume
        ReferenceCommand("increase the volume", "volume_up"),
        ReferenceCommand("turn up the music", "volume_up"),
        ReferenceCommand("make it louder please", "volume_up"),
        ReferenceCommand("raise speaker volume", "volume_up"),
        ReferenceCommand("boost the sound", "volume_up"),
        ReferenceCommand("crank up the audio", "volume_up"),
        ReferenceCommand("it is too quiet in here", "volume_up"),
        ReferenceCommand("i cannot hear anything", "volume_up"),
        ReferenceCommand("lower the volume", "volume_down"),
        ReferenceCommand("turn down the sound", "volume_down"),
        ReferenceCommand("make it quieter please", "volume_down"),
        ReferenceCommand("reduce the speaker volume", "volume_down"),
        ReferenceCommand("make it softer", "volume_down"),
        ReferenceCommand("pipe down the audio", "volume_down"),
        ReferenceCommand("it is extremely loud", "volume_down"),
        ReferenceCommand("quiet down", "volume_down"),

        // Brightness
        ReferenceCommand("make the screen brighter", "brightness_up"),
        ReferenceCommand("increase screen brightness", "brightness_up"),
        ReferenceCommand("turn up brightness", "brightness_up"),
        ReferenceCommand("brighten the display", "brightness_up"),
        ReferenceCommand("boost display illumination", "brightness_up"),
        ReferenceCommand("the screen is too dim", "brightness_up"),
        ReferenceCommand("i cannot see the screen", "brightness_up"),
        ReferenceCommand("dim the screen", "brightness_down"),
        ReferenceCommand("decrease screen brightness", "brightness_down"),
        ReferenceCommand("turn down display brightness", "brightness_down"),
        ReferenceCommand("make the screen darker", "brightness_down"),
        ReferenceCommand("reduce display light", "brightness_down"),
        ReferenceCommand("it is too bright", "brightness_down"),
        ReferenceCommand("the display is blinding me", "brightness_down"),

        // Torch
        ReferenceCommand("turn on the flashlight", "torch_toggle"),
        ReferenceCommand("switch off the torch", "torch_toggle"),
        ReferenceCommand("activate flash light", "torch_toggle"),
        ReferenceCommand("kill the flashlight", "torch_toggle"),
        ReferenceCommand("enable the torch", "torch_toggle"),
        ReferenceCommand("extinguish the light", "torch_toggle"),
        ReferenceCommand("it is pitch dark in here", "torch_toggle"),
        ReferenceCommand("we need some light", "torch_toggle"),
        ReferenceCommand("set flashlight level to maximum", "torch_strength"),
        ReferenceCommand("change torch strength to level 3", "torch_strength"),
        ReferenceCommand("make flashlight intensity full", "torch_strength"),
        ReferenceCommand("torch level five", "torch_strength"),

        // Device Lock
        ReferenceCommand("lock the screen", "lock_phone"),
        ReferenceCommand("put the phone to sleep", "lock_phone"),
        ReferenceCommand("lock my device", "lock_phone"),
        ReferenceCommand("turn off screen display", "lock_phone"),

        // Open App
        ReferenceCommand("open whatsapp", "open_app"),
        ReferenceCommand("launch spotify", "open_app"),
        ReferenceCommand("start instagram app", "open_app"),
        ReferenceCommand("go to my settings", "open_app"),
        ReferenceCommand("run youtube", "open_app"),
        ReferenceCommand("show me the browser", "open_app"),
        ReferenceCommand("open camera", "open_app"),

        // Navigation
        ReferenceCommand("navigate to the nearest hospital", "navigate_to"),
        ReferenceCommand("directions to home", "navigate_to"),
        ReferenceCommand("find routes to starbucks", "navigate_to"),
        ReferenceCommand("how do i get to London", "navigate_to"),
        ReferenceCommand("show routes to work", "navigate_to"),

        // Alarm & Timer
        ReferenceCommand("set an alarm for 7 am", "set_alarm"),
        ReferenceCommand("wake me up at 8:30 in the morning", "set_alarm"),
        ReferenceCommand("create alarm for noon", "set_alarm"),
        ReferenceCommand("alarm at 6 am", "set_alarm"),
        ReferenceCommand("set a timer for 5 minutes", "set_timer"),
        ReferenceCommand("start a countdown for ten minutes", "set_timer"),
        ReferenceCommand("timer for one hour", "set_timer"),

        // WhatsApp
        ReferenceCommand("send a whatsapp to mom saying hello", "send_whatsapp"),
        ReferenceCommand("whatsapp text dad", "send_whatsapp"),
        ReferenceCommand("ping brother on whatsapp", "send_whatsapp"),

        // Media Controls
        ReferenceCommand("play lofi hip hop", "play_media"),
        ReferenceCommand("listen to rock music", "play_media"),
        ReferenceCommand("start playing taylor swift", "play_media"),
        ReferenceCommand("resume media playback", "play_media"),

        // Power Saver
        ReferenceCommand("turn on power saver", "power_saver_toggle"),
        ReferenceCommand("disable low power mode", "power_saver_toggle"),
        ReferenceCommand("enable battery saver", "power_saver_toggle"),
        ReferenceCommand("activate eco mode", "power_saver_toggle"),

        // Screen Mirroring (Screencast)
        ReferenceCommand("turn on screen cast", "screencast_toggle"),
        ReferenceCommand("start smart view screen mirroring", "screencast_toggle"),
        ReferenceCommand("mirror screen to TV", "screencast_toggle"),
        ReferenceCommand("stop casting screen", "screencast_toggle"),

        // WiFi
        ReferenceCommand("turn on wifi", "wifi_toggle"),
        ReferenceCommand("enable wireless internet", "wifi_toggle"),
        ReferenceCommand("disable connection to wifi", "wifi_toggle"),
        ReferenceCommand("switch off wifi connection", "wifi_toggle"),

        // Bluetooth
        ReferenceCommand("turn on bluetooth", "bluetooth_toggle"),
        ReferenceCommand("enable bluetooth connection", "bluetooth_toggle"),
        ReferenceCommand("disable bluetooth receiver", "bluetooth_toggle"),
        ReferenceCommand("switch off bluetooth", "bluetooth_toggle"),

        // Hotspot
        ReferenceCommand("turn on mobile hotspot", "hotspot_toggle"),
        ReferenceCommand("enable personal hotspot", "hotspot_toggle"),
        ReferenceCommand("disable tethering sharing", "hotspot_toggle"),
        ReferenceCommand("turn off wireless hotspot", "hotspot_toggle"),

        // Phone & SMS
        ReferenceCommand("call mom", "call_contact"),
        ReferenceCommand("phone dad please", "call_contact"),
        ReferenceCommand("can you call john", "call_contact"),
        ReferenceCommand("dial the office", "call_contact"),
        ReferenceCommand("give sarah a call", "call_contact"),
        ReferenceCommand("text mom saying i am running late", "send_sms"),
        ReferenceCommand("send sms to dad", "send_sms"),
        ReferenceCommand("read my text messages", "read_sms"),
        ReferenceCommand("check my recent calls", "read_call_log"),

        // Screenshot & utilities
        ReferenceCommand("take a screenshot", "take_screenshot"),
        ReferenceCommand("capture the screen", "take_screenshot"),
        ReferenceCommand("screenshot this screen", "take_screenshot"),
        ReferenceCommand("what is my battery level", "get_battery"),
        ReferenceCommand("how much battery do i have", "get_battery"),
        ReferenceCommand("what time is it", "get_time"),
        ReferenceCommand("tell me the current time", "get_time"),
        ReferenceCommand("read my clipboard", "clipboard_read"),
        ReferenceCommand("copy hello world to clipboard", "clipboard_write"),
        ReferenceCommand("check my notifications", "read_notifications"),

        // Media controls
        ReferenceCommand("pause the music", "pause_media"),
        ReferenceCommand("pause playback", "pause_media"),
        ReferenceCommand("next track", "next_track"),
        ReferenceCommand("skip this song", "next_track"),
        ReferenceCommand("previous track", "previous_track"),
        ReferenceCommand("play sunflower on spotify", "play_spotify"),
        ReferenceCommand("listen to jazz on spotify", "play_spotify"),
        ReferenceCommand("search cats on youtube", "play_youtube"),
        ReferenceCommand("play video on youtube", "play_youtube"),

        // DND & connectivity
        ReferenceCommand("turn on do not disturb", "dnd_toggle"),
        ReferenceCommand("disable do not disturb mode", "dnd_toggle"),
        ReferenceCommand("enable airplane mode", "airplane_mode_toggle"),
        ReferenceCommand("turn off airplane mode", "airplane_mode_toggle"),
        ReferenceCommand("turn on mobile data", "mobile_data_toggle"),
        ReferenceCommand("disable mobile data", "mobile_data_toggle"),

        // Camera
        ReferenceCommand("open the camera", "open_camera"),
        ReferenceCommand("launch camera app", "open_camera"),
        ReferenceCommand("take a photo", "open_camera"),
        ReferenceCommand("take a picture please", "open_camera"),
        ReferenceCommand("capture a photo for me", "open_camera"),
        ReferenceCommand("snap a selfie", "open_camera"),
        ReferenceCommand("open front camera", "open_camera"),
        ReferenceCommand("start camera viewfinder", "open_camera"),

        // Notes
        ReferenceCommand("note that I need to buy groceries", "notes_create"),
        ReferenceCommand("jot down call dentist tomorrow", "notes_create"),
        ReferenceCommand("write down buy milk and bread", "notes_create"),
        ReferenceCommand("save a note saying meeting at 3pm", "notes_create"),
        ReferenceCommand("remind me to check the oven", "notes_create"),
        ReferenceCommand("make a note about project deadline", "notes_create"),
        ReferenceCommand("show me my notes", "notes_list"),
        ReferenceCommand("list all my saved notes", "notes_list"),
        ReferenceCommand("what notes do I have", "notes_list"),
        ReferenceCommand("find notes about dentist", "notes_search"),
        ReferenceCommand("search my notes for groceries", "notes_search"),
        ReferenceCommand("delete note number three", "notes_delete"),
        ReferenceCommand("remove note with id five", "notes_delete"),

        // Airplane mode expanded
        ReferenceCommand("activate flight mode", "airplane_mode_toggle"),
        ReferenceCommand("switch on aeroplane mode", "airplane_mode_toggle"),
        ReferenceCommand("I am about to board a flight", "airplane_mode_toggle"),
        ReferenceCommand("disable flight mode", "airplane_mode_toggle"),
        ReferenceCommand("switch off airplane mode", "airplane_mode_toggle"),

        // Mobile data expanded
        ReferenceCommand("turn on cellular data", "mobile_data_toggle"),
        ReferenceCommand("disable my internet connection", "mobile_data_toggle"),
        ReferenceCommand("enable data connection", "mobile_data_toggle"),
        ReferenceCommand("switch off mobile internet", "mobile_data_toggle"),
        ReferenceCommand("I want to save my data", "mobile_data_toggle"),

        // In-app media play (open app then play)
        ReferenceCommand("play despacito on spotify app", "play_spotify"),
        ReferenceCommand("open spotify and play jazz", "play_spotify"),
        ReferenceCommand("put on some music in spotify", "play_spotify"),
        ReferenceCommand("open youtube and search cats", "play_youtube"),
        ReferenceCommand("find funny videos on youtube", "play_youtube"),
        ReferenceCommand("search youtube for workout music", "play_youtube"),
        ReferenceCommand("play lofi beats on youtube music", "play_media"),
        ReferenceCommand("start playing podcast on spotify", "play_media"),
        ReferenceCommand("search google for latest news", "search_google"),
        ReferenceCommand("look this up on google", "search_google"),
        ReferenceCommand("google the weather today", "search_google"),

        // WhatsApp in-app send
        ReferenceCommand("open whatsapp and message mom", "send_whatsapp"),
        ReferenceCommand("go to whatsapp and text dad", "send_whatsapp"),

        // Recall / Remember preferences
        ReferenceCommand("remember that my favorite color is blue", "remember_preference"),
        ReferenceCommand("recall what you know about me", "recall_preference"),
        ReferenceCommand("what do you remember about me", "recall_preference"),
        ReferenceCommand("store the fact that I prefer dark mode", "remember_preference"),

        // File manager
        ReferenceCommand("open my files", "open_files"),
        ReferenceCommand("show downloads folder", "open_files"),
        ReferenceCommand("browse my documents", "open_files"),

        // Follow-up phrasing (maps via dialogue state at runtime; still train for semantic similarity)
        ReferenceCommand("turn it off", "torch_toggle"),
        ReferenceCommand("turn it on", "torch_toggle"),
        ReferenceCommand("switch it off", "wifi_toggle"),
        ReferenceCommand("turn it down", "volume_down"),
        ReferenceCommand("make it louder", "volume_up")
    )

    init {
        // Initialize in background/lazily
        Thread {
            try {
                loadModel()
            } catch (e: Exception) {
                Log.e(TAG, "Error in init loading model", e)
            }
        }.start()
    }

    @Synchronized
    private fun loadModel() {
        if (isInitialized) return

        try {
            val modelManager = ModelManager(context)
            val modelPath = modelManager.getSemanticModelPath()
            val vocabName = ModelManager.NLU_VOCAB_NAME

            var modelBytes: ByteArray? = null
            var tokenizerLoaded = false

            if (modelPath != null && File(modelPath).exists()) {
                Log.i(TAG, "Loading Semantic model from internal storage: $modelPath")
                modelBytes = File(modelPath).readBytes()
                val vocabFile = File(context.getExternalFilesDir("models") ?: context.filesDir, vocabName)
                if (vocabFile.exists()) {
                    tokenizer = WordpieceTokenizer.loadFromAssets(context, vocabName)
                    tokenizerLoaded = true
                }
            }

            if (modelBytes == null) {
                // Try assets
                val assetsList = context.assets.list("") ?: emptyArray()
                if (assetsList.contains(ModelManager.SEMANTIC_MODEL_NAME) && assetsList.contains(vocabName)) {
                    Log.i(TAG, "Loading Semantic model from assets")
                    context.assets.open(ModelManager.SEMANTIC_MODEL_NAME).use { input ->
                        modelBytes = input.readBytes()
                    }
                    tokenizer = WordpieceTokenizer.loadFromAssets(context, vocabName)
                    tokenizerLoaded = true
                }
            }

            if (modelBytes != null && tokenizerLoaded) {
                ortEnv = OrtEnvironment.getEnvironment()
                ortSession = ortEnv?.createSession(modelBytes)
                
                // Precompute reference command embeddings
                precomputeReferenceEmbeddings()
                
                isInitialized = true
                FridayLogger.i(TAG, "Semantic Router initialized successfully with ${referenceCommands.size} reference vectors.")
            } else {
                Log.w(TAG, "Semantic model file (${ModelManager.SEMANTIC_MODEL_NAME}) not found. Skipping Semantic Router.")
            }
        } catch (e: Exception) {
            Log.e(TAG, "Failed to load Semantic ONNX model", e)
        }
    }

    private fun precomputeReferenceEmbeddings() {
        for (ref in referenceCommands) {
            ref.embedding = computeEmbeddingDirect(ref.text)
        }
    }

    fun isModelLoaded(): Boolean = isInitialized

    // Generate semantic embedding vector using mean pooling over ONNX output
    private fun computeEmbeddingDirect(text: String): FloatArray? {
        val session = ortSession ?: return null
        val env = ortEnv ?: return null
        val tok = tokenizer ?: return null

        try {
            val tokenIds = tok.tokenize(text)
            if (tokenIds.isEmpty()) return null

            // BERT format: [CLS] + tokens + [SEP]
            val clsId = 101L
            val sepId = 102L

            val inputIdsList = mutableListOf<Long>()
            inputIdsList.add(clsId)
            for (id in tokenIds) {
                inputIdsList.add(id.toLong())
            }
            inputIdsList.add(sepId)

            val seqLen = inputIdsList.size
            val shape = longArrayOf(1, seqLen.toLong())

            val inputIdsBuffer = LongBuffer.wrap(inputIdsList.toLongArray())
            val attentionMaskBuffer = LongBuffer.wrap(LongArray(seqLen) { 1L })
            val tokenTypeIdsBuffer = LongBuffer.wrap(LongArray(seqLen) { 0L })

            val inputIdsTensor = OnnxTensor.createTensor(env, inputIdsBuffer, shape)
            val attentionMaskTensor = OnnxTensor.createTensor(env, attentionMaskBuffer, shape)
            val tokenTypeIdsTensor = OnnxTensor.createTensor(env, tokenTypeIdsBuffer, shape)

            val inputs = mapOf(
                "input_ids" to inputIdsTensor,
                "attention_mask" to attentionMaskTensor,
                "token_type_ids" to tokenTypeIdsTensor
            )

            session.run(inputs).use { results ->
                val outputValue = results[0].value
                
                // Expecting output float shape [1, seqLen, 384]
                val tokenEmbeddings = outputValue as? Array<Array<FloatArray>> ?: return null
                
                // Mean pooling calculation
                val sentenceEmbedding = FloatArray(EMBEDDING_DIM)
                var validCount = 0f

                for (t in 0 until seqLen) {
                    validCount += 1.0f
                    val embedding = tokenEmbeddings[0][t]
                    for (i in 0 until EMBEDDING_DIM) {
                        sentenceEmbedding[i] += embedding[i]
                    }
                }

                if (validCount > 0f) {
                    for (i in 0 until EMBEDDING_DIM) {
                        sentenceEmbedding[i] /= validCount
                    }
                }

                // Normalize vector to unit length
                var magSq = 0f
                for (v in sentenceEmbedding) {
                    magSq += v * v
                }
                val magnitude = Math.sqrt(magSq.toDouble()).toFloat()
                if (magnitude > 0f) {
                    for (i in 0 until EMBEDDING_DIM) {
                        sentenceEmbedding[i] /= magnitude
                    }
                }

                return sentenceEmbedding
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error computing embedding for text: $text", e)
            return null
        }
    }

    // Calculates cosine similarity (dot product of pre-normalized vectors)
    private fun cosineSimilarity(vecA: FloatArray, vecB: FloatArray): Float {
        var dotProduct = 0f
        for (i in 0 until EMBEDDING_DIM) {
            dotProduct += vecA[i] * vecB[i]
        }
        return dotProduct
    }

    // Routes user input to the closest matching intent
    fun routeIntent(userInput: String): Pair<String, Float> {
        if (!isInitialized) return Pair("unknown", 0f)

        val queryEmbedding = computeEmbeddingDirect(userInput) ?: return Pair("unknown", 0f)

        var bestIntent = "unknown"
        var maxSimilarity = 0f
        var bestMatchText = ""

        for (ref in referenceCommands) {
            val refEmbedding = ref.embedding ?: continue
            val similarity = cosineSimilarity(queryEmbedding, refEmbedding)
            if (similarity > maxSimilarity) {
                maxSimilarity = similarity
                bestIntent = ref.intent
                bestMatchText = ref.text
            }
        }

        Log.d(TAG, "Semantic Route: '$userInput' -> closest: '$bestMatchText' (intent: $bestIntent, similarity: $maxSimilarity)")
        
        return if (maxSimilarity >= SIMILARITY_THRESHOLD) {
            Pair(bestIntent, maxSimilarity)
        } else {
            Pair("unknown", maxSimilarity)
        }
    }
}

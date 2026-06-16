# Project Friday: Performance Optimization Plan (V3)

This revised plan addresses the updated requirements:
1.  **Speaker-Independent Wake Word**: Reverting to a robust, speaker-independent wake-word model trained on a wide variety of voices, while maintaining high noise resilience.
2.  **Explicit LLM Optimizations**: Detailing prompt caching, Vulkan GPU acceleration, and CPU thread affinity.
3.  **Advanced NLU & Linguistic Diversity**: Training the model to handle diverse natural language phrasing (from *"increase volume"* to *"it's too quiet, make it louder"*) using semantic sentence embeddings and LLM-driven data augmentation.
4.  **Expanded Function Registry**: Adding more general domains (e.g. torch brightness levels, navigation, power-saving).
5.  **Cloud Training (Google Colab)**: Offloading model training to Colab to save local resources.

---

## Phase 1: Robust Speaker-Independent Wake Word Detection

Instead of restricting the wake-word model to a single voice, we will train a **speaker-independent 2D-CNN** model. It will detect the word "Friday" spoken by *anyone*, but will remain highly robust to false triggers from music, TV, and background conversations.

### 1. Data Collection & Preprocessing
To train a model that generalizes across all speakers:
*   **Positive Samples**: 
    - 5,000+ synthesized samples of "Friday", "Hey Friday", "Ok Friday" using advanced TTS engines (Edge-TTS, Google TTS, Coqui-TTS) covering 50+ different voices, pitches, and speeds.
    - 500+ real-world recordings of various speakers (men, women, children) saying "Friday" in different acoustic environments.
*   **Negative Samples (Anti-Trigger Mining)**:
    - **Phonetic Confusers**: 10,000+ samples of words like *"Freeday", "Freddie", "Friday night", "Fly day", "Dry", "Free", "Monday", "Today"*.
    - **Speech Negatives**: 50,000+ random 1.5-second speech frames extracted from *LibriSpeech* and *Google Speech Commands*.
    - **Environmental Noise**: 10,000+ noise samples (street, babble, fan, music) mixed with the positive and negative samples at random SNRs (from 0dB to 20dB) to prevent false triggers in noisy environments.
*   **Audio Features**: Convert the 1.5s PCM buffers to **Log-Mel Spectrograms** (40 Mel bins, 30ms window, 10ms step), creating a $148 \times 40$ input feature map.

---

## Phase 2: Ultra-Low Latency LLM Optimization (<2s Response)

To reduce local LLM response times on-device to under 2 seconds, we optimize the execution pipeline:

```
[User Speech] -> [STT] -> [Text] -> [Agent Core (NLU)] 
                                         |
                                         +--> [Is Tool?] -> YES -> [Direct Action (5ms)]
                                         |
                                         +--> [Is Chat?] -> YES -> [Llama Engine (Vulkan / KV Cached)]
```

### 1. Prompt Caching & KV Cache Recycling
- **The Bottleneck**: Currently, the entire system prompt and chat history are re-processed on every turn. Processing 300+ tokens on a mobile CPU takes several seconds before generation starts.
- **The Solution**: Keep the system prompt and conversation prefix cached in llama.cpp's KV cache. In `friday_jni.cpp`, we match incoming token sequences against the cache. We only process *new* user input tokens (typically 10-30 tokens). This reduces prompt prefill time from 3-5 seconds to **under 50ms**.

### 2. Vulkan GPU Acceleration
- We will compile `llama.cpp` JNI with Vulkan enabled (`GGML_VULKAN=ON`).
- Vulkan offloads heavy matrix calculations to the Samsung S24's GPU (Adreno 750 or Xclipse 940), accelerating token generation (decoding phase) to 25+ tokens/sec.

### 3. CPU Core Affinity Binding
- Android's scheduler often places heavy JNI threads on low-frequency efficiency cores.
- **The Solution**: Bind `llama.cpp` threads to high-performance cores using `sched_setaffinity` in JNI. On the S24, we target cores 4-7 (performance/prime cores) and elevate thread priority to `THREAD_PRIORITY_FOREGROUND`.

### 4. Lightweight Quantization Formats
- We will use **IQ4_NL** or **Q3_K_M** quantizations of Qwen-2.5-1.5B.
- This shrinks the model footprint from 1.1GB to ~800MB. Smaller models require less memory bandwidth to load weights during generation, resulting in faster token decoding.

---

## Phase 3: Advanced NLU & Conversational Intent Parsing

To handle extreme variations in phrasing (e.g. from *"increase volume"* to *"it's too quiet, make it louder"* or *"I can't hear, turn it up"*), we replace basic string matching with semantic understanding.

### 1. Semantic Sentence Embeddings (ONNX Sentence Transformer)
Instead of matching exact keywords, we will use a lightweight **Sentence Transformer** model (e.g. `all-MiniLM-L6-v2` or `bge-micro-v2` quantized to ONNX, <80MB):
- **How it works**: The model converts any text query into a dense vector (e.g., 384 dimensions) representing its semantic meaning.
- **Vector Search**: We compute the cosine similarity between the user's query vector and a database of pre-calculated vectors representing core assistant intents:
  - *"I can't hear anything"* $\rightarrow$ Maps closely to the vector of *"increase volume"* due to semantic context, yielding a high similarity score.
- **Benefits**: Generalizes to slang, indirect phrasing, and polite forms without requiring thousands of exact matches.

### 2. LLM-Driven Data Augmentation
To train the first-stage NLU classifier (MobileBERT) on linguistic variations, we will use an LLM (Gemini or Qwen) to generate an expanded synthetic dataset:
- **Seed Intent**: `volume_up`
- **LLM Prompt**: *"Generate 100 diverse ways a user might ask an assistant to turn up the volume, including polite, indirect, slang, and passive sentences (e.g., 'crank it up', 'can you make it louder', 'it is too quiet')."*
- This yields a dataset of 5,000+ highly varied text training examples covering all target domains.

### 3. Dialogue State Tracking (DST)
To resolve pronouns (like "it" in *"turn it down"*), the NLU engine caches active domains:
- If the current query lacks a direct entity but matches a command (e.g. *"lower it"*), the engine checks the active domain cache. If the last command was `media_control`, it lowers the playback volume.

---

## Phase 4: Expanded System and In-App Function Registry

Here is the updated registry of controllable domains, including advanced system commands and deep automation:

| Function Domain | Specific Action | Implementation Strategy | Required Permissions / API |
| :--- | :--- | :--- | :--- |
| **Torch Strength** | Set brightness level (1-5) | `CameraManager.turnOnTorchWithStrengthLevel(cameraId, level)` | `android.permission.CAMERA` (Android 13+) |
| **Screen Cast** | Launch Smart View / Cast | Broadcast intent for Cast or open `Settings.ACTION_CAST_SETTINGS` | None |
| **Power Saver** | Toggle Battery Saver | Redirect to battery saver settings or automate toggle via Accessibility | `android.permission.WRITE_SECURE_SETTINGS` (or Accessibility) |
| **Navigation** | Navigate to address | Launch `Intent(Intent.ACTION_VIEW, Uri.parse("google.navigation:q=Address"))` | None |
| **Alarms & Timers**| Create alarms with labels | `Intent(AlarmClock.ACTION_SET_ALARM).putExtra(...)` | `com.android.alarm.permission.SET_ALARM` |
| **WhatsApp Tasks**| Send message, Start call | Launch deep link, scan UI node tree for input fields, inject text, click send | Accessibility Service (`com.whatsapp:id/entry`) |
| **Instagram Search**| Search profile/hashtag | Launch `instagram://user?username=...` or `instagram://tag?name=...` | None |
| **System Settings**| Toggle Screen Timeout | `Settings.System.putInt(..., Settings.System.SCREEN_OFF_TIMEOUT, ms)`| `android.permission.WRITE_SETTINGS` |

---

## Phase 5: Google Colab Training Workflow

To avoid consuming local GPU/CPU resources during training, we will provide a **Google Colab Notebook** (`scripts/friday_training.ipynb`) to orchestrate the heavy training pipelines:

1.  **Environment Setup**: Connects to a free T4 GPU instance, installs PyTorch, ONNX, and ONNX Runtime.
2.  **Dataset Downloader**: Pulls negative speech corpora (Google Speech Commands) and noise datasets (ESC-50) from public repositories.
3.  **Wake-Word Training**: Trains the 2D DS-CNN on Log-Mel Spectrograms, tests validation accuracy, and exports to `wakeword.onnx`.
4.  **NLU Training**: Fine-tunes a MobileBERT sequence classification model on the LLM-augmented intent dataset, performs INT8 quantization, and exports `nlu_model.onnx` and `vocab.txt`.
5.  **Output Export**: Packages the compiled ONNX models into a zip file for download and direct copying into the Android project's `assets/` folder.

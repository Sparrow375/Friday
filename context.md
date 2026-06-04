# Project Friday: Personal Android Voice Assistant

## Overview
Friday is a highly personalized, 100% offline-capable Android voice assistant designed specifically for a Samsung Galaxy S24 running Android 16 (One UI 8). It features local speaker verification, offline Speech-to-Text, a hybrid offline command classifier, automated routines, app-specific integrations, and a sleek floating overlay UI with voice feedback and animated visualizer.

The project is configured for cloud-compilation via GitHub Actions, bypassing the need for local compiles and automatically publishing the compiled APK as a GitHub Release.

## Repository Structure & Components

The application is structured under the package namespace `com.friday.assistant`:

- **`app/src/main/assets/`**: Contains the pre-bundled `speaker_verification.onnx` model, which is copied to internal storage on first run.
- **`friday_helper.py`**: A PC-side Python utility using `llama-cpp-python` and `Flask` to serve GGUF language models directly to the Android app over the local network.
- **`.github/workflows/android-build.yml`**: GitHub Actions build script. Automatically builds the debug APK (`app-debug.apk`) on push and uploads it as a build artifact, creating a GitHub Release.
- **`app/build.gradle.kts`**: Configurations and dependencies including MediaPipe (tasks-genai for local LLM), Microsoft ONNX Runtime (for speaker embedding verification), SQLite Room database (Room 2.7.2), and Gson. Configured using AGP 9.0 built-in Kotlin support and KSP (Kotlin Symbol Processing) to compile Room stubs.
- **`app/src/main/AndroidManifest.xml`**: Defines system-wide permissions and maps background components. Cleartext traffic is enabled to allow local connection. Contains the `QUERY_ALL_PACKAGES` permission to resolve Android 11+ app query/launch visibility limitations.

### Source Code Package Structure (`app/src/main/java/com/friday/assistant/`):

1. **`core/`**:
   - `FridayApplication.kt`: Manages notification channel setup and database singleton.
   - `Database.kt`: SQLite Room database definition containing tables for Conversations (chat history), Notes, Routines, and Geofences.
   - `BootReceiver.kt`: Automatically launches the overlay assistant service on device boot.

2. **`audio/`**:
   - `VoiceRecorder.kt`: Captures raw PCM audio (16kHz mono 16-bit) from the microphone.
   - `SpeakerVerifier.kt`: Interfaces with the ONNX runtime model (`speaker_verification.onnx`) to extract 192-dimensional speaker embeddings and compare similarity via cosine similarity. Copies the model from assets on first run. **Inference and model loading are performed asynchronously on a background thread pool (`Dispatchers.IO` / `Dispatchers.Default`) to prevent UI freezes.**
   - `SpeechRecognizerManager.kt`: Manages Android's SpeechRecognizer. **Reuses a single SpeechRecognizer instance to prevent binder leaks. Aborts current sessions via `cancel()` before starting new ones, and delays wake-word restarts by 300ms to allow system resources to clean up, resolving the ERROR_RECOGNIZER_BUSY (11) error.**

3. **`classifier/`**:
   - `CommandClassifier.kt`: Implements the Layer 1 (Regex/pattern) and Layer 2 (Keyword and synonyms matching with parameter extraction) offline classification. **Features a robust extraction helper (`extractSearchQuery`) that isolates search terms from platform intents (Reddit, Spotify, YouTube, Brave, X/Twitter, Google) irrespective of sentence structures (e.g. "search X on Y" or "search Y for X").**
   - `LocalLlmRunner.kt`: Interfaces with MediaPipe GenAI Tasks to run a local quantized model, or queries the PC-side GGUF server if configured. Includes explicit prompt constraints to prevent emoji generation. **Model loading and reloading run asynchronously on a background thread.**

4. **`executor/`**:
   - `SystemExecutor.kt`: Direct bindings for system controls (volume, brightness, wifi, bluetooth, DND, flashlight, alarms, calling contacts) and launcher/deep-linking configurations. **Database queries (like contacts retrieval) are executed off the Main thread on `Dispatchers.IO`. Deep-linking support includes native YouTube search (`vnd.youtube://`) and Google Search integration.**
   - `RoutineExecutor.kt`: Parses a list of routine actions (JSON) and triggers them sequentially.

5. **`ui/`**:
   - `MainActivity.kt`: Jetpack Compose dashboard UI for configuration, training, routines, and models. **Employs a background polling coroutine in `onCreate()` that checks model load statuses every second and reactively updates state variables once loading completes in the background.**
   - `OverlayService.kt`: Foreground service drawing the floating overlay. Features a visualizer, click-outside-to-collapse, and text input row. Intercepts TextToSpeech UtteranceProgressListener to pause/resume STT listening, preventing feedback loops and audio self-triggering, and sanitizes speech text to strip all emojis before TTS. **Model initialization is started on `Dispatchers.IO` during `onCreate()`, and verification runs on `Dispatchers.Default` to prevent Application Not Responding (ANR) crashes.**
   - `AudioVisualizerView.kt`: Custom view drawing multiple fluid, animated sine waves responsive to the real-time RMS audio input.

## Verification & Deployment Flow
1. Code changes are pushed to GitHub.
2. The Action compiles the project using `./gradlew assembleDebug` in a clean Ubuntu VM.
3. Download the built APK from the run's **Artifacts** tab.
4. Install and launch the app, grant permissions.
5. Voice verification is active out-of-the-box (copied from assets).
6. To use GGUF LLMs: start the PC-side helper (`python friday_helper.py <GGUF_FILE>`), input the printed IP address in the app settings, and enjoy fully functional local network LLM interactions alongside offline regex/routine commands.

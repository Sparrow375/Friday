# Project Friday: Personal Android Voice Assistant

## Overview
Friday is a highly personalized, 100% offline-capable Android voice assistant designed specifically for a Samsung Galaxy S24 running Android 16 (One UI 8). It features local speaker verification, offline Speech-to-Text, a hybrid offline command classifier, automated routines, app-specific integrations, and a sleek floating overlay UI with voice feedback and animated visualizer.

The project is configured for cloud-compilation via GitHub Actions, bypassing the need for local compiles and automatically publishing the compiled APK as a GitHub Release.

## Repository Structure & Components

The application is structured under the package namespace `com.friday.assistant`:

- **`.github/workflows/android-build.yml`**: GitHub Actions build script. Automatically builds the debug APK (`app-debug.apk`) on push and uploads it as a build artifact.
- **`app/build.gradle.kts`**: Configurations and dependencies including MediaPipe (tasks-genai for local LLM), Microsoft ONNX Runtime (for speaker embedding verification), SQLite Room database, and Gson.
- **`app/src/main/AndroidManifest.xml`**: Defines system-wide permissions (overlay, record audio, write settings, location, foreground microphone service) and maps background components.

### Source Code Package Structure (`app/src/main/java/com/friday/assistant/`):

1. **`core/`**:
   - `FridayApplication.kt`: Manages notification channel setup and database singleton.
   - `Database.kt`: SQLite Room database definition containing tables for Conversations (chat history), Notes, Routines, and Geofences.
   - `BootReceiver.kt`: Automatically launches the overlay assistant service on device boot.

2. **`audio/`**:
   - `VoiceRecorder.kt`: Captures raw PCM audio (16kHz mono 16-bit) from the microphone.
   - `SpeakerVerifier.kt`: Interfaces with the ONNX runtime model (`speaker_verification.onnx`) to extract 192-dimensional speaker embeddings and compare similarity via cosine similarity.
   - `SpeechRecognizerManager.kt`: Wraps Android's native SpeechRecognizer and coordinates with `VoiceRecorder` to capture transcripts and raw audio simultaneously.

3. **`classifier/`**:
   - `CommandClassifier.kt`: Implements the Layer 1 (Regex/pattern) and Layer 2 (Keyword and synonyms matching with parameter extraction) offline classification.
   - `LocalLlmRunner.kt`: Interfaces with MediaPipe GenAI Tasks to run a local quantized model (e.g. Gemma 2B or Llama 3.2) in a coroutine block with custom prompt templates.

4. **`executor/`**:
   - `SystemExecutor.kt`: Direct bindings for system controls (volume, brightness, wifi, bluetooth, DND, flashlight, alarms) and launcher/deep-linking configurations for Reddit, Discord, Instagram, Chrome, Spotify, Brave, and WhatsApp.
   - `RoutineExecutor.kt`: Parses a list of routine actions (JSON) and triggers them sequentially.

5. **`ui/`**:
   - `MainActivity.kt`: Jetpack Compose dashboard UI for permissions checking, voice profile training (enrolling voice sample), and routines management.
   - `OverlayService.kt`: Persistent foreground service drawing the floating overlay bubble and translucent bottom sheet.
   - `AudioVisualizerView.kt`: Custom view drawing multiple fluid, animated sine waves responsive to the real-time RMS audio input.

## Verification & Deployment Flow
1. Code changes are pushed to GitHub.
2. The Action compiles the project using `./gradlew assembleDebug` in a clean Ubuntu VM.
3. Download the built APK from the run's **Artifacts** tab.
4. Copy `gemma.bin` and `speaker_verification.onnx` into `/sdcard/Android/data/com.friday.assistant/files/` on the device.
5. Install and launch the app, grant permissions, train voice profile, and toggle Friday service.

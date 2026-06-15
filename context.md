# Project Friday: Modern Agentic Android Voice Assistant (V2)

## Overview
Project Friday is a highly optimized, 100% offline-capable, agentic personal AI assistant for Android (specifically optimized for Samsung Galaxy S24, targetSdk 36). It runs entirely on-device, offering secure, low-latency voice interactions, in-app automation, and a personalized experience via a multi-layer memory system.

### Key Capabilities
1. **Always-On Voice Pipeline**: Uses energy-efficient Voice Activity Detection (VAD) coupled with `whisper.cpp` to match a customizable local wake word (default: "Friday") with zero API keys or external server calls.
2. **Offline Speech-to-Text**: Converts voice queries to text locally using `whisper.cpp` (quantized tiny/base model).
3. **Agentic Reasoning (Local LLM)**: Powered by `llama.cpp` running a quantized `Qwen2.5-3B-Instruct` GGUF model directly on-device. The agent acts on user requests by executing tool-calling loops.
4. **Speaker Verification**: Authenticates the owner's voice via a local ONNX-based ECAPA-TDNN speaker embedding model, rejecting unauthorized triggers.
5. **Deep UI Automation**: Integrates with Android's Accessibility Service to scan screen content and execute UI actions (tap, type, scroll) on any app.
6. **Multi-Layer Memory System**: Room DB database managing Working, Episodic (conversations), Semantic (user facts/preferences), and Procedural (learned routines) memories.
7. **Premium Glassmorphism Overlay UI**: Jetpack Compose-based floating bubble and glass panels with glowing, fluid waveforms representing recording/speaking states.

---

## Repository Structure & Packages

The project uses a clean package namespace `com.friday.assistant`:

### Package Modules
1. **`core/`**:
   - `FridayApplication.kt`: Application entry point, setups database and notifications.
   - `BootReceiver.kt`: Listens for system boot to launch the assistant service automatically.
   - `ModelManager.kt`: Manages local model files (Whisper, Qwen GGUF, Speaker ONNX); handles automated copying of Whisper and speaker ONNX models from assets to internal storage.
   - **`db/`**: SQLite Room database schema, entities, and DAOs for notes, routines, usage tracking, and multi-layer memory tables.
   - **`native/`**: Kotlin wrappers (`LlamaEngine.kt`, `WhisperEngine.kt`) interfacing with the C++ JNI bridge.

2. **`audio/`**:
   - `AudioCaptureManager.kt`: Continuous high-priority microphone capture (16kHz mono 16-bit PCM).
   - `WakeWordDetector.kt`: Implements energy/frequency-based VAD to spot voice activity and runs short Whisper checks to match the customizable wake word.
   - `SpeakerVerifier.kt`: Computes 192-dimensional speaker embeddings using Microsoft ONNX Runtime (`speaker_verification.onnx`) to verify user voice profile.
   - `VoicePipeline.kt`: Orchestrates transitions between IDLE, WAKE, RECORDING, VERIFY, and TTS states.

3. **`intelligence/`**:
   - `AgentCore.kt`: Runs the tool-calling agent loop, parsing JSON commands from LLM.
   - `PromptBuilder.kt`: Formulates dynamic system prompts with tool schemas and retrieved memory context.
   - `ToolDispatcher.kt`: Directs LLM tool calls to the appropriate executor.
   - `MemoryManager.kt`: Coordinates semantic memory extraction and episodic history storage.
   - `PreferenceExtractor.kt`: Asynchronously extracts and learns user facts/preferences from conversation turns using background LLM inference.

4. **`tools/`**:
   - `Tool.kt`: Common interface for tools.
   - **`system/`**: Controls volume, brightness, torch, WiFi, Bluetooth, DND, battery, etc.
   - **`phone/`**: Calls contacts, reads SMS, drafts messages.
   - **`apps/`**: Launches package names, deep-linking into common applications.
   - **`media/`**: Exposes media playback control.
   - **`search/`**: Simple search tool.
   - **`clipboard/`**, **`notes/`**, **`calendar/`**, **`notifications/`**, **`location/`**, **`camera/`**, **`files/`**: Device integration tools.
   - **`accessibility/`**: Screen interaction tools (`ScreenReaderTool`, `ClickElementTool`, `TypeTextTool`, `ScrollScreenTool`, `GlobalActionTool`).
   - **`whatsapp/`**: WhatsApp deep automation (`WhatsAppTool`).
   - **`email/`**: Gmail automation (`EmailTool`).

5. **`automation/`**:
   - `ScreenReader.kt`: Converts `AccessibilityNodeInfo` tree to structured text snapshot and caches interactive elements mapped to short integer IDs.
   - `UIAutomator.kt`: Simulates UI actions (taps, text input, scroll, global system controls) on accessibility nodes.

6. **`ui/`**:
   - `FridayService.kt`: Background foreground service hosting window overlays and Accessibility Service; runs as a microphone foreground type on targetSdk 36.
   - `NotificationService.kt`: Background notification listener service that intercepts status bar events.
   - **`overlay/`**: WindowManager overlay layouts, custom waveform canvases (`AudioWaveformComposable.kt`), and overlay controls (`OverlayManager.kt` implementing `ViewModelStoreOwner` for Compose safety).
   - **`screens/`**: Clean minimalist settings/permission dashboard (`MainActivity.kt`) requesting Microphone, Notification, location/contacts permissions and including GGUF picker with background copy progress bar.
   - **`theme/`**: Sleek modern Indigo & Slate-dark Material 3 theme (`FridayTheme.kt`).

---

## Native Build & JNI Architecture

`llama.cpp` and `whisper.cpp` source code is fetched at build-time using CMake `FetchContent` and cross-compiled via the Android NDK in GitHub Actions.
- **CMake Configuration**: `app/src/main/cpp/CMakeLists.txt`
- **JNI Glue Layer**: `app/src/main/cpp/friday_jni.cpp` exports JNI methods for model loading, token generation, audio feed, and transcription.
  * *Updated (June 2026)*: Upgraded JNI calls to utilize the new `llama_vocab` struct APIs (`llama_model_get_vocab`, `llama_vocab_is_eog`, and `llama_token_to_piece` with vocab pointer) to remain compatible with the refactored upstream `llama.cpp` master.
- **GitHub Actions**: Generates and signs `app-debug.apk` on every push to main/master, deploying it directly to GitHub Releases.
  * *Updated (June 2026)*: Configured Gradle to automatically download `ggml-tiny-q5_1.bin` from Hugging Face during the pre-build stage to keep the repository lightweight and self-contained.

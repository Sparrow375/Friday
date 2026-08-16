# Task List: Battery Optimization Overhaul (Two-Stage VAD-Gated Neural Wake-Word)

- [x] 1. Copy 1D-CNN raw PCM `wakeword.onnx` (17.5 KB, input `[1, 1, 24000]`) to `app/src/main/assets/wakeword.onnx`. <!-- id: 0 -->
- [x] 2. Implement Two-Stage VAD-Gated Neural `WakeWordDetector.kt` using integer RMS/ZCR VAD gating, pre-allocated zero-GC rolling buffer, and 0.4ms ONNX inference. <!-- id: 1 -->
- [x] 3. Integrate `WakeWordDetector` with `AudioCaptureManager` in `FridayService.kt`, removing the heavy background `SpeechRecognizer` loop. <!-- id: 2 -->
- [x] 4. Audit buffer allocations in `AudioCaptureManager.kt` and `VoicePipeline.kt` ensuring zero-allocation audio streaming in 16kHz mono. <!-- id: 3 -->
- [x] 5. Compile and verify with `gradlew compileDebugKotlin`. <!-- id: 4 -->
- [x] 6. Update `context.md` with the low-power audio architecture. <!-- id: 5 -->

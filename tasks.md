# Task List: Battery Optimization Implementation

- [x] 1. Refactor Audio Pipeline: Update `AudioCaptureManager.kt` audio source and implement ONNX 1D-CNN + VAD wake-word detector in `WakeWordDetector.kt`. <!-- id: 0 -->
- [x] 2. Update `FridayService.kt`: Connect ONNX wake-word detector with `AudioCaptureManager`, remove redundant Whisper boot pre-loading, ensure on-demand `SpeechRecognizer` lifecycle. <!-- id: 1 -->
- [x] 3. Optimize Intelligence Layer: Add heuristic pre-filtering in `PreferenceExtractor.kt` and charging constraints in `BriefScheduler.kt` / `BriefWorker.kt`. <!-- id: 2 -->
- [x] 4. Fix UI Overlay Lifecycle & Animations: Pause Compose lifecycle on `hide()` in `OverlayManager.kt` and gate idle animations in `FridayOverlayContent.kt`. <!-- id: 3 -->
- [x] 5. Tune Native JNI: Update `mparams` (`use_mlock = false`, `use_mmap = true`) and scoped thread affinity in `friday_jni.cpp`. <!-- id: 4 -->
- [x] 6. Compile & verify build correctness with Gradle. <!-- id: 5 -->
- [x] 7. Update `context.md` and create `walkthrough.md`. <!-- id: 6 -->

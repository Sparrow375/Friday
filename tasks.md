# Task List: Seeed Studio XIAO nRF52840 Pendant Integration & Wake-Word Preparation

- [x] 1. Create `architechture.md` documenting the complete Friday Assistant architecture, including the new Seeed XIAO nRF52840 Sense BLE Pendant subsystem <!-- id: 0 -->
- [x] 2. Build Arduino PDM Microphone Test sketch (`firmware/xiao_mic_test/xiao_mic_test.ino`) to verify on-board microphone & audio levels <!-- id: 1 -->
- [x] 3. Build Arduino Audio Streaming sketch (`firmware/xiao_audio_recorder/xiao_audio_recorder.ino`) for PC-synchronized sample collection <!-- id: 2 -->
- [x] 4. Create Python PC dataset recorder script (`scripts/record_pendant_samples.py`) for recording "Friday" wake-word and noise samples directly from the pendant <!-- id: 3 -->
- [x] 5. Build full BLE Pendant Firmware (`firmware/xiao_friday_pendant/xiao_friday_pendant.ino`) with BLE GATT Server, PDM continuous audio capture, IMA-ADPCM compression, and wake-word trigger <!-- id: 4 -->
- [x] 6. Update Android BLE integration (`FridayBleWearableManager.kt`, `FridayService.kt`, `MainActivity.kt`) to support Seeed XIAO pendant discovery, wake-word trigger, and audio reception <!-- id: 5 -->
- [x] 7. Verify Android build compilation via `./gradlew compileDebugKotlin` <!-- id: 6 -->
- [x] 8. Record user voice samples (52 positive 'Friday' samples, 51 negative/noise samples) via Seeed XIAO Sense PDM mic <!-- id: 7 -->
- [x] 9. Build training pipeline (`scripts/train_pendant_wakeword.py`) with data augmentation, Log-Mel spectrogram extraction, and CNN training <!-- id: 8 -->
- [x] 10. Generate zero-dependency C++ inference engine (`firmware/xiao_friday_pendant/wakeword_model.h`) with weights and feature extractor <!-- id: 9 -->
- [x] 11. Wire `predictWakeWord()` into `firmware/xiao_friday_pendant/xiao_friday_pendant.ino` for real-time on-chip wake-word detection <!-- id: 10 -->
- [x] 12. Evaluate model metrics via `scripts/test_pendant_model.py` and test on recorded dataset <!-- id: 11 -->
- [x] 13. Migrate pendant firmware from `ArduinoBLE` to native `Bluefruit52Lib` (zero library downloads required) <!-- id: 12 -->
- [x] 14. Compile and flash production wake-word pendant firmware to XIAO nRF52840 Sense <!-- id: 13 -->
- [x] 15. Diagnose live stream wake-word suppression (Root cause: 91.2% audio frame drop due to ISR buffer overwrite while loop() was executing, decimating contiguous speech into fragmented stutters) <!-- id: 14 -->
- [x] 16. Eliminate frame drops & accelerate TinyML inference:
  - [x] Direct DMA ring-buffer ingestion in `onPDMdata()` (guarantees contiguous 16kHz audio with 0 dropped frames)
  - [x] Precomputed Radix-2 FFT twiddle steps (eliminates 558 runtime `cosf`/`sinf` calls per pass)
  - [x] Bounded Mel filterbank projection (bins 7..127, cutting Mel matrix ops by 95%)
  - [x] Syllable hysteresis expansion to 16 frames (~250ms) to bridge natural pauses in "Friday"
  - [x] Added live FPS meter in Heartbeat telemetry (`~62.5 fps`) <!-- id: 15 -->
- [ ] 17. Upload updated firmware to Seeed XIAO Sense, verify 62.5 fps audio stream and live wake-word triggers (> 0.85) on "Friday" <!-- id: 16 -->
- [x] 18. Upgrade Pendant Firmware for VAD-triggered speech audio streaming (no wake-word required in debug mode) over BLE with flow control <!-- id: 17 -->
- [x] 19. Update Android BLE Manager & FridayService to stream pendant audio into local STT (Whisper) and display real-time transcribed text on phone overlay/screen <!-- id: 18 -->
- [x] 20. Add live wearable debug card / transcript display on MainActivity dashboard for real-time monitoring <!-- id: 19 -->
- [x] 21. Verify firmware compilation & Android assembleDebug build <!-- id: 20 -->


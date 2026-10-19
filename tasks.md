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
- [x] 13. Update `context.md` and commit/push to git <!-- id: 12 -->

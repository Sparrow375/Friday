/*
 * Project Friday - Seeed Studio XIAO nRF52840 Sense
 * Always-On Wake-Word Pendant Firmware
 *
 * Board: "Seeed Studio XIAO nRF52840 Sense" (Seeed nRF52 Boards)
 * Core:  Seeeduino:nrf52 (includes Bluefruit52Lib and PDM pre-installed)
 * Zero external library downloads required!
 */

#include <bluefruit.h>
#include <PDM.h>
#include "wakeword_model.h"

// ==================== BLE UUIDs ====================
#define SERVICE_UUID        "1F81DA00-B5A3-F393-E0A9-E50E24DCCA9E"
#define STATE_CHAR_UUID     "1F81DA01-B5A3-F393-E0A9-E50E24DCCA9E"
#define COMMAND_CHAR_UUID   "1F81DA02-B5A3-F393-E0A9-E50E24DCCA9E"

BLEService fridayService(SERVICE_UUID);
BLECharacteristic stateCharacteristic(STATE_CHAR_UUID);
BLECharacteristic commandCharacteristic(COMMAND_CHAR_UUID);

// ==================== Audio Buffers ====================
#define SAMPLE_RATE 16000
#define FRAME_SAMPLES 256      // 16ms per frame
#define ADPCM_PAYLOAD_SIZE 128 // 256 samples compressed 4:1 = 128 bytes

// Circular buffer for rolling wake-word detection (1.5 seconds = 24000 samples)
#define ROLLING_BUFFER_SIZE 24000
short rollingBuffer[ROLLING_BUFFER_SIZE];
volatile int rollingWriteIdx = 0;
volatile unsigned long totalSamplesCollected = 0;
volatile unsigned long totalFramesReceived = 0;
volatile float lastCalculatedRms = 0.0f;
volatile bool newAudioReady = false;

int streamingReadIdx = 0;
int speechActiveFrames = 0;
unsigned long lastInferenceTime = 0;
unsigned long lastHeartbeatTime = 0;
unsigned long lastHeartbeatFrames = 0;

// State management
enum PendantState {
  STATE_IDLE = 0x00,
  STATE_WAKE_DETECTED = 0x01,
  STATE_RECORDING_VOICE = 0x02
};
PendantState currentState = STATE_IDLE;

// VAD & Wake-word calibrated parameters
float backgroundRms = 50.0f;
const float VAD_MULTIPLIER = 1.40f;
const float MIN_SPEECH_RMS = 60.0f;
const float WAKE_CONFIDENCE_STRICT = 0.86f;   // Instant single-hit trigger
const float WAKE_CONFIDENCE_MODERATE = 0.72f; // Requires 2 consecutive hits (~160ms)
unsigned long wakeTriggerTimestamp = 0;
unsigned long wakeCooldownUntil = 0;          // Cooldown to prevent recursive re-triggering
static int consecutiveWakeHits = 0;
bool streamAudioOverBle = true; // Stream compressed voice command to phone
bool debugVadStreamingMode = true; // Debug mode: Automatic VAD voice streaming without wake-word

// Manual recording state (commanded by phone)
volatile bool manualRecordingActive = false;
volatile uint8_t manualDurationSec = 5;
volatile unsigned long manualRecordingEndTime = 0;
volatile bool stopRecordingRequested = false;
volatile uint16_t globalChunkIndex = 0;

// ==================== IMA-ADPCM Encoder ====================
static const int STEP_TABLE[89] = {
    7, 8, 9, 10, 11, 12, 13, 14, 16, 17,
    19, 21, 23, 25, 28, 31, 34, 37, 41, 45,
    50, 55, 60, 66, 73, 80, 88, 97, 107, 118,
    130, 143, 157, 173, 190, 209, 230, 253, 279, 307,
    337, 371, 408, 449, 494, 544, 598, 658, 724, 796,
    876, 963, 1060, 1166, 1282, 1411, 1552, 1707, 1878, 2066,
    2272, 2499, 2749, 3024, 3327, 3660, 4026, 4428, 4871, 5358,
    5894, 6484, 7132, 7845, 8630, 9493, 10442, 11487, 12635, 13899,
    15289, 16818, 18500, 20350, 22385, 24623, 27086, 29794, 32767
};

static const int INDEX_TABLE[16] = {
    -1, -1, -1, -1, 2, 4, 6, 8,
    -1, -1, -1, -1, 2, 4, 6, 8
};

int adpcmValprev = 0;
int adpcmIndex = 0;

void resetAdpcm() {
  adpcmValprev = 0;
  adpcmIndex = 0;
}

uint8_t encodeSample(short sample) {
  int step = STEP_TABLE[adpcmIndex];
  int diff = sample - adpcmValprev;
  uint8_t delta = 0;

  if (diff < 0) {
    delta = 8;
    diff = -diff;
  }

  int vpdiff = step >> 3;
  if (diff >= step) {
    delta |= 4;
    diff -= step;
    vpdiff += step;
  }
  step >>= 1;
  if (diff >= step) {
    delta |= 2;
    diff -= step;
    vpdiff += step;
  }
  step >>= 1;
  if (diff >= step) {
    delta |= 1;
    vpdiff += step;
  }

  if (delta & 8) {
    adpcmValprev -= vpdiff;
  } else {
    adpcmValprev += vpdiff;
  }

  if (adpcmValprev > 32767) adpcmValprev = 32767;
  else if (adpcmValprev < -32768) adpcmValprev = -32768;

  adpcmIndex += INDEX_TABLE[delta & 0x07];
  if (adpcmIndex < 0) adpcmIndex = 0;
  else if (adpcmIndex > 88) adpcmIndex = 88;

  return delta;
}

// Encodes 256 16-bit samples into 128 bytes
void encodeFrame(const short* inPcm, uint8_t* outAdpcm, int samples) {
  int outIdx = 0;
  for (int i = 0; i < samples; i += 2) {
    uint8_t nibble1 = encodeSample(inPcm[i]);
    uint8_t nibble2 = encodeSample(inPcm[i + 1]);
    outAdpcm[outIdx++] = (nibble2 << 4) | (nibble1 & 0x0F);
  }
}

// ==================== PDM Audio Callback (DMA Interrupt) ====================
void onPDMdata() {
  int bytesAvailable = PDM.available();
  short tempBuffer[256];

  // Drain all available audio frames from PDM hardware buffer
  while (bytesAvailable >= 512) {
    int bytesRead = PDM.read(tempBuffer, 512);
    if (bytesRead <= 0) break;
    int samples = bytesRead / sizeof(short);

    long sumSquares = 0;
    int writeIdx = rollingWriteIdx;
    for (int i = 0; i < samples; i++) {
      short sample = tempBuffer[i];
      sumSquares += ((long)sample * sample);
      rollingBuffer[writeIdx] = sample;
      writeIdx = (writeIdx + 1);
      if (writeIdx >= ROLLING_BUFFER_SIZE) writeIdx = 0;
    }
    rollingWriteIdx = writeIdx;
    totalSamplesCollected += samples;
    totalFramesReceived++;

    lastCalculatedRms = sqrtf((float)sumSquares / samples);
    newAudioReady = true;

    bytesAvailable = PDM.available();
  }
}

// ==================== Wake-Word Detection ====================
// Returns true when "Friday" is detected by the on-chip CNN model
bool checkWakeWord(float currentRms) {
  // Cooldown guard: prevent self-triggering after wake detection or audio recording
  if (millis() < wakeCooldownUntil) {
    return false;
  }

  // Wait until circular buffer has at least 1.5 seconds of live audio
  if (totalSamplesCollected < ROLLING_BUFFER_SIZE) {
    return false;
  }

  // Adaptive speech threshold
  float speechThreshold = backgroundRms * VAD_MULTIPLIER;
  if (speechThreshold < MIN_SPEECH_RMS) speechThreshold = MIN_SPEECH_RMS;

  // Hysteresis Voice Activity Detection:
  // Once voice starts, maintain speech window for at least 14 frames (~220ms) across syllables
  if (currentRms >= speechThreshold) {
    speechActiveFrames = 14;
  } else {
    if (speechActiveFrames > 0) {
      speechActiveFrames--;
    } else {
      // Ambient silence: slowly adapt background noise baseline
      backgroundRms = (backgroundRms * 0.99f) + (currentRms * 0.01f);
      consecutiveWakeHits = 0;
    }
  }

  // Run TinyML CNN forward pass when speech is active, throttled to every 80ms
  if (speechActiveFrames > 0 && (millis() - lastInferenceTime >= 80)) {
    lastInferenceTime = millis();

    // Zero-copy circular inference (direct evaluation on rollingBuffer, 0 extra RAM)
    int head = rollingWriteIdx;
    float confidence = predictWakeWordScoreCircular(rollingBuffer, head);

    // Live feedback in Serial Monitor
    Serial.print(F("[Speech Active] RMS: "));
    Serial.print((int)currentRms);
    Serial.print(F(" | Thresh: "));
    Serial.print((int)speechThreshold);
    Serial.print(F(" | 'Friday' Score: "));
    Serial.println(confidence, 3);

    bool isTrigger = false;
    if (confidence >= WAKE_CONFIDENCE_STRICT) {
      isTrigger = true;
    } else if (confidence >= WAKE_CONFIDENCE_MODERATE) {
      consecutiveWakeHits++;
      if (consecutiveWakeHits >= 2) {
        isTrigger = true;
      }
    } else {
      consecutiveWakeHits = 0;
    }

    if (isTrigger) {
      Serial.println(F("**************************************************"));
      Serial.print(F(">>> WAKE-WORD DETECTED ('Friday')! Confidence: "));
      Serial.println(confidence, 3);
      Serial.println(F("**************************************************"));
      speechActiveFrames = 0;
      consecutiveWakeHits = 0;
      wakeCooldownUntil = millis() + 4000; // 4s cooldown during speech recording and command turnaround
      return true;
    }
  }

  return false;
}

// Manual recording commanded by phone over BLE
void startManualRecording(uint8_t durationSec) {
  manualRecordingActive = true;
  manualDurationSec = (durationSec > 0 && durationSec <= 30) ? durationSec : 5;
  manualRecordingEndTime = millis() + (manualDurationSec * 1000UL);
  stopRecordingRequested = false;
  globalChunkIndex = 0;

  currentState = STATE_RECORDING_VOICE;
  stateCharacteristic.write8((uint8_t)STATE_RECORDING_VOICE);
  stateCharacteristic.notify8((uint8_t)STATE_RECORDING_VOICE);

  resetAdpcm();

  // Send Audio Header: 0x01 | totalChunks(2B) | totalBytes(4B) | sampleRate(2B)
  uint16_t estimatedChunks = (manualDurationSec * 1000) / 16;
  uint32_t estimatedBytes = estimatedChunks * ADPCM_PAYLOAD_SIZE;
  uint16_t rate = SAMPLE_RATE;

  uint8_t header[9];
  header[0] = 0x01;
  header[1] = (estimatedChunks >> 8) & 0xFF;
  header[2] = estimatedChunks & 0xFF;
  header[3] = (estimatedBytes >> 24) & 0xFF;
  header[4] = (estimatedBytes >> 16) & 0xFF;
  header[5] = (estimatedBytes >> 8) & 0xFF;
  header[6] = estimatedBytes & 0xFF;
  header[7] = (rate >> 8) & 0xFF;
  header[8] = rate & 0xFF;

  for (int r = 0; r < 5; r++) {
    if (commandCharacteristic.notify(header, sizeof(header))) break;
    delay(4);
  }

  // Visual feedback: Red LED active during recording (Active LOW)
  digitalWrite(LED_GREEN, HIGH);
  digitalWrite(LED_BLUE, HIGH);
  digitalWrite(LED_RED, LOW);

  streamingReadIdx = rollingWriteIdx;
  Serial.print(F("[BLE Command] Manual Recording STARTED for "));
  Serial.print(manualDurationSec);
  Serial.println(F("s"));
}

void stopManualRecording() {
  if (currentState == STATE_RECORDING_VOICE) {
    stopRecordingRequested = true;
    Serial.println(F("[BLE Command] Manual Recording STOP requested by phone"));
  }
}

// Command characteristic write callback (phone can send control codes or echo back transcripts)
void command_write_callback(uint16_t conn_hdl, BLECharacteristic* chr, uint8_t* data, uint16_t len) {
  (void) conn_hdl;
  (void) chr;
  if (len == 0 || data == NULL) return;

  if (data[0] == 0x05) {
    // START MANUAL RECORDING: 0x05 | durationSec (optional)
    uint8_t dur = (len > 1 && data[1] > 0 && data[1] <= 30) ? data[1] : 5;
    startManualRecording(dur);
  } else if (data[0] == 0x06) {
    // STOP MANUAL RECORDING: 0x06
    stopManualRecording();
  } else if (data[0] == 0x0A) {
    debugVadStreamingMode = true;
    Serial.println(F("[BLE Command] VAD Audio Streaming Mode ENABLED (No Wake-Word needed)"));
  } else if (data[0] == 0x0B) {
    debugVadStreamingMode = false;
    Serial.println(F("[BLE Command] Wake-Word Only Mode ENABLED"));
  } else {
    // Phone echoes back transcribed text for serial debugging
    Serial.print(F("[Phone STT Echo]: "));
    for (uint16_t i = 0; i < len; i++) {
      Serial.print((char)data[i]);
    }
    Serial.println();
  }
}

// ==================== BLE GATT Configuration ====================
void setupGatt() {
  fridayService.begin();

  // State Characteristic: Read | Notify
  stateCharacteristic.setProperties(CHR_PROPS_READ | CHR_PROPS_NOTIFY);
  stateCharacteristic.setPermission(SECMODE_OPEN, SECMODE_NO_ACCESS);
  stateCharacteristic.setFixedLen(1);
  stateCharacteristic.begin();
  stateCharacteristic.write8((uint8_t)STATE_IDLE);

  // Command Characteristic: Read | Notify | Write
  commandCharacteristic.setProperties(CHR_PROPS_READ | CHR_PROPS_NOTIFY | CHR_PROPS_WRITE);
  commandCharacteristic.setPermission(SECMODE_OPEN, SECMODE_OPEN);
  commandCharacteristic.setMaxLen(244);
  commandCharacteristic.setWriteCallback(command_write_callback);
  commandCharacteristic.begin();
}

void startAdv(void) {
  Bluefruit.Advertising.addFlags(BLE_GAP_ADV_FLAGS_LE_ONLY_GENERAL_DISC_MODE);
  Bluefruit.Advertising.addTxPower();

  // Primary advertisement packet includes the Service UUID
  Bluefruit.Advertising.addService(fridayService);

  // Secondary Scan Response packet includes the Device Name
  Bluefruit.ScanResponse.addName();

  Bluefruit.Advertising.restartOnDisconnect(true);
  Bluefruit.Advertising.setInterval(32, 244); // 20ms fast, 152.5ms slow
  Bluefruit.Advertising.setFastTimeout(30);   // 30 seconds
  Bluefruit.Advertising.start(0);             // 0 = advertise forever
}

void connect_callback(uint16_t conn_handle) {
  BLEConnection* conn = Bluefruit.Connection(conn_handle);
  char central_name[32] = { 0 };
  if (conn) {
    conn->getPeerName(central_name, sizeof(central_name));
    conn->requestDataLengthUpdate();
    conn->requestMtuExchange(247);
    Serial.print(F("BLE Connected: "));
    Serial.print(central_name);
    Serial.print(F(" (Negotiating MTU, current: "));
    Serial.print(conn->getMtu());
    Serial.println(F(")"));
  }
  digitalWrite(LED_BLUE, LOW); // Steady blue
}

void disconnect_callback(uint16_t conn_handle, uint8_t reason) {
  (void) conn_handle;
  Serial.print(F("BLE Disconnected, reason = 0x"));
  Serial.println(reason, HEX);
  digitalWrite(LED_BLUE, HIGH);
}

// ==================== Setup ====================
void setup() {
  Serial.begin(115200);

  // User LEDs (Active LOW)
  pinMode(LED_RED, OUTPUT);
  pinMode(LED_GREEN, OUTPUT);
  pinMode(LED_BLUE, OUTPUT);
  digitalWrite(LED_RED, HIGH);
  digitalWrite(LED_GREEN, HIGH);
  digitalWrite(LED_BLUE, LOW); // Blue = Initializing

  // Allow USB CDC up to 2 seconds if user has Serial Monitor open
  unsigned long startWait = millis();
  while (!Serial && (millis() - startWait < 2000)) {
    delay(10);
  }

  Serial.println(F("\n=================================================="));
  Serial.println(F("  Project Friday - Seeed XIAO nRF52840 Sense Boot "));
  Serial.println(F("=================================================="));

  // 1. Initialize PDM Microphone @ 16kHz mono with gain = 45
  Serial.print(F("[1/3] Initializing PDM Mic @ 16kHz... "));
  PDM.onReceive(onPDMdata);
  if (!PDM.begin(1, 16000)) {
    Serial.println(F("FAILED!"));
    while (1) {
      digitalWrite(LED_RED, LOW);
      delay(150);
      digitalWrite(LED_RED, HIGH);
      delay(150);
    }
  }
  PDM.setGain(45); // Set gain AFTER begin()
  Serial.println(F("OK!"));

  // 2. Initialize Bluetooth LE with high bandwidth (MTU 247, connection interval 7.5-15ms)
  Serial.print(F("[2/3] Initializing Bluefruit LE... "));
  Bluefruit.configPrphBandwidth(BANDWIDTH_MAX);
  if (!Bluefruit.begin()) {
    Serial.println(F("FAILED!"));
  } else {
    Serial.println(F("OK!"));
  }

  Bluefruit.setTxPower(4); // +4 dBm
  Bluefruit.setName("Friday-Pendant");
  Bluefruit.Periph.setConnectCallback(connect_callback);
  Bluefruit.Periph.setDisconnectCallback(disconnect_callback);

  // 3. Configure GATT Services & Start Advertising
  Serial.print(F("[3/3] Setting up GATT & BLE Advertising... "));
  setupGatt();
  startAdv();
  Serial.println(F("OK!"));

  Serial.println(F("=================================================="));
  Serial.println(F("  Friday Pendant READY & LISTENING               "));
  Serial.println(F("  Device Name: Friday-Pendant                     "));
  Serial.println(F("  Say 'Friday' to trigger wake-word!              "));
  Serial.println(F("==================================================\n"));

  digitalWrite(LED_BLUE, HIGH); // Off after setup
}

// ==================== Main Loop ====================
void loop() {
  // 1. Top-Level Heartbeat every 1.5 seconds with exact FPS calculation
  if (millis() - lastHeartbeatTime >= 1500) {
    unsigned long now = millis();
    unsigned long elapsedMs = now - lastHeartbeatTime;
    lastHeartbeatTime = now;

    unsigned long framesDelta = totalFramesReceived - lastHeartbeatFrames;
    lastHeartbeatFrames = totalFramesReceived;
    float fps = (float)framesDelta * 1000.0f / elapsedMs;

    Serial.print(F("[Heartbeat] Up: "));
    Serial.print(now / 1000);
    Serial.print(F("s | Frames: "));
    Serial.print(totalFramesReceived);
    Serial.print(F(" ("));
    Serial.print(fps, 1);
    Serial.print(F(" fps) | RMS: "));
    Serial.print((int)lastCalculatedRms);
    Serial.print(F(" | BG: "));
    Serial.print((int)backgroundRms);
    Serial.print(F(" | BLE: "));
    Serial.println(Bluefruit.connected() ? F("Connected") : F("Advertising"));
  }

  // 2. LED indication based on connection status
  if (Bluefruit.connected()) {
    // Steady Blue = BLE Connected
    digitalWrite(LED_BLUE, LOW);
  } else {
    // Pulse Blue = Advertising / Searching for phone
    static unsigned long lastBlink = 0;
    if (millis() - lastBlink > 1000) {
      lastBlink = millis();
      digitalWrite(LED_BLUE, !digitalRead(LED_BLUE));
    }
  }

  // 3. Handle incoming audio frames
  if (newAudioReady) {
    newAudioReady = false;

    // ================= State Machine =================
    if (currentState == STATE_IDLE) {
      if (debugVadStreamingMode && Bluefruit.connected() && (millis() >= wakeCooldownUntil)) {
        // Adaptive speech threshold
        float speechThreshold = backgroundRms * VAD_MULTIPLIER;
        if (speechThreshold < MIN_SPEECH_RMS) speechThreshold = MIN_SPEECH_RMS;

        static int vadSpeechHits = 0;
        if (lastCalculatedRms >= speechThreshold) {
          vadSpeechHits++;
          if (vadSpeechHits >= 2) {
            vadSpeechHits = 0;
            currentState = STATE_RECORDING_VOICE;

            Serial.print(F("\n>>> [VAD Active] Speech detected (RMS: "));
            Serial.print((int)lastCalculatedRms);
            Serial.print(F(" >= Thresh: "));
            Serial.print((int)speechThreshold);
            Serial.println(F("). Streaming voice to phone over BLE..."));

            // 1. Notify phone of recording state immediately (< 15ms over BLE)
            stateCharacteristic.write8((uint8_t)STATE_RECORDING_VOICE);
            stateCharacteristic.notify8((uint8_t)STATE_RECORDING_VOICE);

            // 2. Reset ADPCM encoder for clean audio transmission
            resetAdpcm();

            // 3. Send Audio Header: 0x01 | totalChunks(2B) | totalBytes(4B) | sampleRate(2B)
            uint16_t totalChunks = 0; // Dynamic VAD stream length
            uint32_t totalBytes = 0;
            uint16_t rate = SAMPLE_RATE;

            uint8_t header[9];
            header[0] = 0x01; // Audio Header packet type
            header[1] = (totalChunks >> 8) & 0xFF;
            header[2] = totalChunks & 0xFF;
            header[3] = (totalBytes >> 24) & 0xFF;
            header[4] = (totalBytes >> 16) & 0xFF;
            header[5] = (totalBytes >> 8) & 0xFF;
            header[6] = totalBytes & 0xFF;
            header[7] = (rate >> 8) & 0xFF;
            header[8] = rate & 0xFF;

            for (int r = 0; r < 5; r++) {
              if (commandCharacteristic.notify(header, sizeof(header))) break;
              delay(4);
            }

            // Visual feedback: Red LED active during recording
            digitalWrite(LED_GREEN, HIGH);
            digitalWrite(LED_RED, LOW);

            // 200ms pre-roll: rewind 3,200 samples so initial syllable is preserved
            int preRoll = 3200;
            streamingReadIdx = (rollingWriteIdx - preRoll + ROLLING_BUFFER_SIZE) % ROLLING_BUFFER_SIZE;
          }
        } else {
          vadSpeechHits = 0;
          // Ambient silence: slowly adapt background baseline
          backgroundRms = (backgroundRms * 0.99f) + (lastCalculatedRms * 0.01f);
        }
      } else if (!debugVadStreamingMode) {
        // Normal Wake-Word Trigger Check
        if (checkWakeWord(lastCalculatedRms)) {
          currentState = STATE_WAKE_DETECTED;
          wakeTriggerTimestamp = millis();

          stateCharacteristic.write8((uint8_t)STATE_WAKE_DETECTED);
          stateCharacteristic.notify8((uint8_t)STATE_WAKE_DETECTED);

          digitalWrite(LED_GREEN, LOW);
          digitalWrite(LED_BLUE, HIGH);

          resetAdpcm();

          if (streamAudioOverBle && Bluefruit.connected()) {
            uint16_t totalChunks = 156;
            uint32_t totalBytes = totalChunks * ADPCM_PAYLOAD_SIZE;
            uint16_t rate = SAMPLE_RATE;

            uint8_t header[9];
            header[0] = 0x01;
            header[1] = (totalChunks >> 8) & 0xFF;
            header[2] = totalChunks & 0xFF;
            header[3] = (totalBytes >> 24) & 0xFF;
            header[4] = (totalBytes >> 16) & 0xFF;
            header[5] = (totalBytes >> 8) & 0xFF;
            header[6] = totalBytes & 0xFF;
            header[7] = (rate >> 8) & 0xFF;
            header[8] = rate & 0xFF;

            for (int r = 0; r < 5; r++) {
              if (commandCharacteristic.notify(header, sizeof(header))) break;
              delay(5);
            }

            currentState = STATE_RECORDING_VOICE;
            stateCharacteristic.write8((uint8_t)STATE_RECORDING_VOICE);
            stateCharacteristic.notify8((uint8_t)STATE_RECORDING_VOICE);
            digitalWrite(LED_GREEN, HIGH);
            digitalWrite(LED_RED, LOW);

            streamingReadIdx = rollingWriteIdx;
          } else {
            delay(800);
            currentState = STATE_IDLE;
            wakeCooldownUntil = millis() + 3000;
            stateCharacteristic.write8((uint8_t)STATE_IDLE);
            stateCharacteristic.notify8((uint8_t)STATE_IDLE);
            digitalWrite(LED_GREEN, HIGH);
          }
        }
      }
    } else if (currentState == STATE_RECORDING_VOICE) {
      static unsigned long recordingStartTime = 0;
      static int silenceFrames = 0;
      static int speechFrames = 0;

      if (globalChunkIndex == 0) {
        recordingStartTime = millis();
        silenceFrames = 0;
        speechFrames = 0;
      }

      float speechThreshold = backgroundRms * VAD_MULTIPLIER;
      if (speechThreshold < MIN_SPEECH_RMS) speechThreshold = MIN_SPEECH_RMS;

      if (lastCalculatedRms >= speechThreshold) {
        speechFrames++;
        silenceFrames = 0;
      } else {
        silenceFrames++;
      }

      int currentHead = rollingWriteIdx;
      int availableSamples = (currentHead - streamingReadIdx + ROLLING_BUFFER_SIZE) % ROLLING_BUFFER_SIZE;

      BLEConnection* activeConn = Bluefruit.Connection(Bluefruit.connHandle());
      uint16_t connMtu = activeConn ? activeConn->getMtu() : 23;
      int frameSamples = (connMtu >= 134) ? 256 : 32;
      int payloadBytes = frameSamples / 2;

      while (availableSamples >= frameSamples && globalChunkIndex < 2000) {
        short chunk[256];
        for (int i = 0; i < frameSamples; i++) {
          chunk[i] = rollingBuffer[(streamingReadIdx + i) % ROLLING_BUFFER_SIZE];
        }

        // Encode samples to ADPCM bytes (4-bit per sample)
        uint8_t adpcmPayload[128];
        encodeFrame(chunk, adpcmPayload, frameSamples);

        // Construct packet: 0x02 | chunkIdx(2B) | payload
        uint8_t packet[3 + 128];
        packet[0] = 0x02; // Audio Chunk
        packet[1] = (globalChunkIndex >> 8) & 0xFF;
        packet[2] = globalChunkIndex & 0xFF;
        memcpy(&packet[3], adpcmPayload, payloadBytes);

        bool sent = commandCharacteristic.notify(packet, 3 + payloadBytes);
        if (sent) {
          globalChunkIndex++;
          streamingReadIdx = (streamingReadIdx + frameSamples) % ROLLING_BUFFER_SIZE;
          availableSamples -= frameSamples;
        } else {
          // BLE TX buffer full - wait briefly for SoftDevice stack to transmit
          delay(4);
          break;
        }
      }

      // Check for completion of spoken phrase
      bool finishStreaming = false;
      if (manualRecordingActive) {
        // App-controlled manual recording: stops on phone stop command (0x06) or duration timer
        if (stopRecordingRequested || (millis() >= manualRecordingEndTime)) {
          finishStreaming = true;
        }
      } else if (debugVadStreamingMode) {
        // Finish when user stops speaking for ~350ms (22 frames) after at least 4 frames of speech (~65ms),
        // or if silence continues for ~500ms (32 frames) with minimal speech (avoids streaming 4.5s of empty silence),
        // or when max utterance limit (3.5s / 220 chunks) is reached
        if (speechFrames >= 4 && silenceFrames >= 22) {
          finishStreaming = true;
        } else if (speechFrames == 0 && silenceFrames >= 32) {
          finishStreaming = true;
        } else if (globalChunkIndex >= 300 || (millis() - recordingStartTime > 3500)) {
          finishStreaming = true;
        }
      } else {
        if (globalChunkIndex >= 200 || (millis() - recordingStartTime > 2800)) {
          finishStreaming = true;
        }
      }

      if (finishStreaming) {
        // Drain any remaining buffered samples
        currentHead = rollingWriteIdx;
        availableSamples = (currentHead - streamingReadIdx + ROLLING_BUFFER_SIZE) % ROLLING_BUFFER_SIZE;
        while (availableSamples >= frameSamples && globalChunkIndex < 2000) {
          short chunk[256];
          for (int i = 0; i < frameSamples; i++) {
            chunk[i] = rollingBuffer[(streamingReadIdx + i) % ROLLING_BUFFER_SIZE];
          }
          uint8_t adpcmPayload[128];
          encodeFrame(chunk, adpcmPayload, frameSamples);
          uint8_t packet[3 + 128];
          packet[0] = 0x02;
          packet[1] = (globalChunkIndex >> 8) & 0xFF;
          packet[2] = globalChunkIndex & 0xFF;
          memcpy(&packet[3], adpcmPayload, payloadBytes);
          if (commandCharacteristic.notify(packet, 3 + payloadBytes)) {
            globalChunkIndex++;
            streamingReadIdx = (streamingReadIdx + frameSamples) % ROLLING_BUFFER_SIZE;
            availableSamples -= frameSamples;
          } else {
            delay(10);
            break;
          }
        }

        // Send Audio End sentinel packet (0x03) with robust retry and SoftDevice queue yield
        uint8_t endPacket[1] = { 0x03 };
        for (int r = 0; r < 20; r++) {
          if (commandCharacteristic.notify(endPacket, 1)) break;
          delay(15);
        }

        Serial.print(F("[Pendant BLE] Voice streaming complete. Chunks sent: "));
        Serial.print(globalChunkIndex);
        Serial.print(F(" (~"));
        Serial.print(globalChunkIndex * 16);
        Serial.println(F(" ms). Returning to IDLE."));

        globalChunkIndex = 0;
        silenceFrames = 0;
        speechFrames = 0;
        currentState = STATE_IDLE;
        manualRecordingActive = false;
        stopRecordingRequested = false;
        wakeCooldownUntil = millis() + 500; // 500ms cooldown after speech

        stateCharacteristic.write8((uint8_t)STATE_IDLE);
        for (int r = 0; r < 10; r++) {
          if (stateCharacteristic.notify8((uint8_t)STATE_IDLE)) break;
          delay(10);
        }

        digitalWrite(LED_RED, HIGH);
        digitalWrite(LED_BLUE, LOW);
      }
    }
  }
}

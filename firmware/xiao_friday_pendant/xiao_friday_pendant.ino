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

short pcmFrame[FRAME_SAMPLES];
volatile bool frameReady = false;

// Circular buffer for rolling wake-word detection (1.5 seconds = 24000 samples)
#define ROLLING_BUFFER_SIZE 24000
short rollingBuffer[ROLLING_BUFFER_SIZE];
int rollingWriteIdx = 0;
unsigned long totalSamplesCollected = 0;
unsigned long totalFramesReceived = 0;

int speechActiveFrames = 0;
unsigned long lastInferenceTime = 0;
unsigned long lastHeartbeatTime = 0;
float lastCalculatedRms = 0.0f;

// State management
enum PendantState {
  STATE_IDLE = 0x00,
  STATE_WAKE_DETECTED = 0x01,
  STATE_RECORDING_VOICE = 0x02
};
PendantState currentState = STATE_IDLE;

// VAD & Wake-word calibrated parameters
float backgroundRms = 50.0f;
const float VAD_MULTIPLIER = 1.30f;
const float MIN_SPEECH_RMS = 45.0f;
const float WAKE_CONFIDENCE_THRESHOLD = 0.55f; // Positive avg is 0.89, noise avg is 0.04
unsigned long wakeTriggerTimestamp = 0;
bool streamAudioOverBle = true; // Set true to stream compressed voice command to phone

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

// ==================== PDM Audio Callback ====================
void onPDMdata() {
  int bytesAvailable = PDM.available();
  if (bytesAvailable > 0) {
    int bytesToRead = bytesAvailable > (int)sizeof(pcmFrame) ? sizeof(pcmFrame) : bytesAvailable;
    PDM.read(pcmFrame, bytesToRead);
    frameReady = true;
  }
}

// ==================== Wake-Word Detection ====================
// Returns true when "Friday" is detected by the on-chip CNN model
bool checkWakeWord(const short* frame, int samples, float currentRms) {
  // Wait until circular buffer has at least 1.5 seconds of live audio
  if (totalSamplesCollected < ROLLING_BUFFER_SIZE) {
    return false;
  }

  // Adaptive speech threshold
  float speechThreshold = backgroundRms * VAD_MULTIPLIER;
  if (speechThreshold < MIN_SPEECH_RMS) speechThreshold = MIN_SPEECH_RMS;

  // Hysteresis Voice Activity Detection:
  // Once voice starts, maintain speech window for at least 8 frames (~130ms) across short pauses
  if (currentRms >= speechThreshold) {
    speechActiveFrames = 8;
  } else {
    if (speechActiveFrames > 0) {
      speechActiveFrames--;
    } else {
      // Ambient silence: slowly adapt background noise baseline
      backgroundRms = (backgroundRms * 0.99f) + (currentRms * 0.01f);
    }
  }

  // Run TinyML CNN forward pass when speech is active, throttled to every 90ms
  if (speechActiveFrames > 0 && (millis() - lastInferenceTime >= 90)) {
    lastInferenceTime = millis();

    // Zero-copy circular inference (direct evaluation on rollingBuffer, 0 extra RAM)
    float confidence = predictWakeWordScoreCircular(rollingBuffer, rollingWriteIdx);

    // Live feedback in Serial Monitor
    Serial.print(F("[Speech Active] RMS: "));
    Serial.print((int)currentRms);
    Serial.print(F(" | Thresh: "));
    Serial.print((int)speechThreshold);
    Serial.print(F(" | 'Friday' Score: "));
    Serial.println(confidence, 3);

    if (confidence >= WAKE_CONFIDENCE_THRESHOLD) {
      Serial.println(F("**************************************************"));
      Serial.print(F(">>> WAKE-WORD DETECTED ('Friday')! Confidence: "));
      Serial.println(confidence, 3);
      Serial.println(F("**************************************************"));
      speechActiveFrames = 0;
      return true;
    }
  }

  return false;
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
  }
  Serial.print(F("BLE Connected: "));
  Serial.println(central_name);
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

  // 2. Initialize Bluetooth LE with standard safe memory footprint
  Serial.print(F("[2/3] Initializing Bluefruit LE... "));
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
  // 1. Top-Level Heartbeat every 1.5 seconds (runs even if mic is silent)
  if (millis() - lastHeartbeatTime >= 1500) {
    lastHeartbeatTime = millis();
    Serial.print(F("[Heartbeat] Up: "));
    Serial.print(millis() / 1000);
    Serial.print(F("s | Frames: "));
    Serial.print(totalFramesReceived);
    Serial.print(F(" | Last RMS: "));
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
  if (frameReady) {
    frameReady = false;
    totalFramesReceived++;

    // Calculate RMS volume of this 16ms frame
    long sumSquares = 0;
    for (int i = 0; i < FRAME_SAMPLES; i++) {
      short sample = pcmFrame[i];
      sumSquares += ((long)sample * sample);

      // Append to circular rolling buffer
      rollingBuffer[rollingWriteIdx] = sample;
      rollingWriteIdx = (rollingWriteIdx + 1) % ROLLING_BUFFER_SIZE;
      totalSamplesCollected++;
    }
    float currentRms = sqrt((float)sumSquares / FRAME_SAMPLES);
    lastCalculatedRms = currentRms;

    // ================= State Machine =================
    if (currentState == STATE_IDLE) {
      // Check for Wake-Word Trigger
      if (checkWakeWord(pcmFrame, FRAME_SAMPLES, currentRms)) {
        currentState = STATE_WAKE_DETECTED;
        wakeTriggerTimestamp = millis();

        // 1. Notify Phone immediately (< 15ms over BLE)
        stateCharacteristic.write8((uint8_t)STATE_WAKE_DETECTED);
        stateCharacteristic.notify8((uint8_t)STATE_WAKE_DETECTED);

        // 2. Visual feedback: GREEN LED
        digitalWrite(LED_GREEN, LOW);
        digitalWrite(LED_BLUE, HIGH);

        // Reset ADPCM encoder for clean audio transmission
        resetAdpcm();

        if (streamAudioOverBle && Bluefruit.connected()) {
          // Send Audio Header: 0x01 | totalChunks(2B) | totalBytes(4B) | sampleRate(2B)
          // Stream 2.5 seconds = 40,000 samples @ 16kHz = 156 chunks of 256 samples
          uint16_t totalChunks = 156;
          uint32_t totalBytes = totalChunks * ADPCM_PAYLOAD_SIZE;
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

          commandCharacteristic.notify(header, sizeof(header));
          currentState = STATE_RECORDING_VOICE;
          stateCharacteristic.write8((uint8_t)STATE_RECORDING_VOICE);
          stateCharacteristic.notify8((uint8_t)STATE_RECORDING_VOICE);
          digitalWrite(LED_GREEN, HIGH);
          digitalWrite(LED_RED, LOW); // Red = Recording
        } else {
          // If not streaming audio, let phone listen via its own mic
          delay(800);
          currentState = STATE_IDLE;
          stateCharacteristic.write8((uint8_t)STATE_IDLE);
          stateCharacteristic.notify8((uint8_t)STATE_IDLE);
          digitalWrite(LED_GREEN, HIGH);
        }
      }
    } else if (currentState == STATE_RECORDING_VOICE) {
      static uint16_t chunkIndex = 0;
      static unsigned long recordingStartTime = 0;

      if (chunkIndex == 0) {
        recordingStartTime = millis();
      }

      // Encode 256 samples to 128 ADPCM bytes
      uint8_t adpcmPayload[ADPCM_PAYLOAD_SIZE];
      encodeFrame(pcmFrame, adpcmPayload, FRAME_SAMPLES);

      // Construct packet: 0x02 | chunkIdx(2B) | payload(128B) = 131 bytes
      uint8_t packet[3 + ADPCM_PAYLOAD_SIZE];
      packet[0] = 0x02; // Audio Chunk
      packet[1] = (chunkIndex >> 8) & 0xFF;
      packet[2] = chunkIndex & 0xFF;
      memcpy(&packet[3], adpcmPayload, ADPCM_PAYLOAD_SIZE);

      commandCharacteristic.notify(packet, sizeof(packet));
      chunkIndex++;

      // Stop recording after 2.5 seconds or 156 chunks
      if (chunkIndex >= 156 || (millis() - recordingStartTime > 2800)) {
        // Send Audio End packet (0x03)
        uint8_t endPacket[1] = { 0x03 };
        commandCharacteristic.notify(endPacket, 1);

        Serial.println(F("Audio streaming complete. Returning to IDLE."));
        chunkIndex = 0;
        currentState = STATE_IDLE;
        stateCharacteristic.write8((uint8_t)STATE_IDLE);
        stateCharacteristic.notify8((uint8_t)STATE_IDLE);
        digitalWrite(LED_RED, HIGH);
        digitalWrite(LED_BLUE, LOW);
      }
    }
  }
}

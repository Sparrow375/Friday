/*
 * Project Friday - Seeed Studio XIAO nRF52840 Sense
 * Always-On Wake-Word Pendant Firmware
 *
 * Board: "Seeed Studio XIAO nRF52840 Sense" (Seeed nRF52 mbed-enabled Boards)
 * Required Libraries (Install via Arduino Library Manager):
 * - ArduinoBLE (by Arduino)
 * - PDM (included with Seeed nRF52 mbed board package)
 *
 * Features:
 * 1. Low-power PDM digital microphone acquisition @ 16kHz mono.
 * 2. Energy-efficient VAD (Voice Activity Detection).
 * 3. Wake-Word Detection trigger & TinyML inference slot.
 * 4. BLE GATT Server advertising "Friday-Pendant":
 *    - Service: 1F81DA00-B5A3-F393-E0A9-E50E24DCCA9E
 *    - State Char: 1F81DA01-... (0x01 = Wake Detected, 0x02 = Recording, 0x00 = Idle)
 *    - Command Char: 1F81DA02-... (IMA-ADPCM compressed audio packet streaming)
 * 5. On-chip IMA-ADPCM 4-bit encoder (4:1 real-time audio compression).
 */

#include <ArduinoBLE.h>
#include <PDM.h>

// ==================== BLE UUIDs ====================
#define SERVICE_UUID        "1F81DA00-B5A3-F393-E0A9-E50E24DCCA9E"
#define STATE_CHAR_UUID     "1F81DA01-B5A3-F393-E0A9-E50E24DCCA9E"
#define COMMAND_CHAR_UUID   "1F81DA02-B5A3-F393-E0A9-E50E24DCCA9E"

BLEService fridayService(SERVICE_UUID);
BLEByteCharacteristic stateCharacteristic(STATE_CHAR_UUID, BLERead | BLENotify);
BLECharacteristic commandCharacteristic(COMMAND_CHAR_UUID, BLERead | BLENotify | BLEWrite, 244);

// ==================== Audio Buffers ====================
#define SAMPLE_RATE 16000
#define FRAME_SAMPLES 256      // 16ms per frame
#define ADPCM_PAYLOAD_SIZE 128 // 256 samples compressed 4:1 = 128 bytes

short pcmFrame[FRAME_SAMPLES];
volatile bool frameReady = false;

// Ring buffer for rolling wake-word detection (1.0 second = 16000 samples)
#define ROLLING_BUFFER_SIZE 16000
short rollingBuffer[ROLLING_BUFFER_SIZE];
int rollingWriteIdx = 0;

// State management
enum PendantState {
  STATE_IDLE = 0x00,
  STATE_WAKE_DETECTED = 0x01,
  STATE_RECORDING_VOICE = 0x02
};
PendantState currentState = STATE_IDLE;

// VAD & Wake-word parameters
float backgroundRms = 100.0f;
const float VAD_MULTIPLIER = 2.0f;
const float MIN_SPEECH_RMS = 250.0f;
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
// Returns true when "Friday" is detected
bool checkWakeWord(const short* frame, int samples, float currentRms) {
  // 1. Check Voice Activity Detection (VAD) threshold
  float speechThreshold = backgroundRms * VAD_MULTIPLIER;
  if (speechThreshold < MIN_SPEECH_RMS) speechThreshold = MIN_SPEECH_RMS;

  if (currentRms < speechThreshold) {
    // Ambient silence: slowly adapt background noise floor
    backgroundRms = (backgroundRms * 0.98f) + (currentRms * 0.02f);
    return false;
  }

  // 2. TinyML Model Inference Hook:
  // Once the user's custom wake-word model is trained (via Edge Impulse or TFLite Micro),
  // place model inference here on `rollingBuffer`.
  //
  // Currently, we provide an acoustic energy-pulse pattern detector for test & calibration:
  // (Detects sustained clear speech burst > 500 RMS for wake trigger)
  static int consecutiveSpeechFrames = 0;
  if (currentRms > 600.0f) {
    consecutiveSpeechFrames++;
    // ~150-300ms of sustained speech energy
    if (consecutiveSpeechFrames >= 8 && consecutiveSpeechFrames <= 25) {
      consecutiveSpeechFrames = 0;
      return true;
    }
  } else {
    consecutiveSpeechFrames = 0;
  }

  return false;
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

  // 1. Initialize PDM Microphone @ 16kHz mono
  PDM.onReceive(onPDMdata);
  PDM.setGain(25);
  if (!PDM.begin(1, 16000)) {
    while (1) {
      digitalWrite(LED_RED, LOW);
      delay(150);
      digitalWrite(LED_RED, HIGH);
      delay(150);
    }
  }

  // 2. Initialize Bluetooth LE
  if (!BLE.begin()) {
    while (1) {
      digitalWrite(LED_RED, LOW);
      delay(300);
      digitalWrite(LED_RED, HIGH);
      delay(300);
    }
  }

  BLE.setLocalName("Friday-Pendant");
  BLE.setDeviceName("Friday-Pendant");
  BLE.setAdvertisedService(fridayService);

  fridayService.addCharacteristic(stateCharacteristic);
  fridayService.addCharacteristic(commandCharacteristic);
  BLE.addService(fridayService);

  stateCharacteristic.writeValue((uint8_t)STATE_IDLE);

  // Start advertising
  BLE.advertise();

  Serial.println(F("=================================================="));
  Serial.println(F("  Friday Pendant (Seeed XIAO nRF52840 Sense) READY"));
  Serial.println(F("  BLE Device Name: Friday-Pendant                 "));
  Serial.println(F("  Service UUID: 1F81DA00-B5A3-F393-E0A9-E50E24DCCA9E"));
  Serial.println(F("=================================================="));

  digitalWrite(LED_BLUE, HIGH); // Off after setup
}

// ==================== Main Loop ====================
void loop() {
  BLEDevice central = BLE.central();

  // LED indication based on connection status
  if (central && central.connected()) {
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

  // Handle incoming audio frames
  if (frameReady) {
    frameReady = false;

    // Calculate RMS volume of this 16ms frame
    long sumSquares = 0;
    for (int i = 0; i < FRAME_SAMPLES; i++) {
      short sample = pcmFrame[i];
      sumSquares += ((long)sample * sample);

      // Append to circular rolling buffer
      rollingBuffer[rollingWriteIdx] = sample;
      rollingWriteIdx = (rollingWriteIdx + 1) % ROLLING_BUFFER_SIZE;
    }
    float currentRms = sqrt((float)sumSquares / FRAME_SAMPLES);

    // ================= State Machine =================
    if (currentState == STATE_IDLE) {
      // Check for Wake-Word Trigger
      if (checkWakeWord(pcmFrame, FRAME_SAMPLES, currentRms)) {
        Serial.println(F(">>> WAKE-WORD DETECTED ('Friday')! <<<"));
        currentState = STATE_WAKE_DETECTED;
        wakeTriggerTimestamp = millis();

        // 1. Notify Phone immediately (< 15ms over BLE)
        stateCharacteristic.writeValue((uint8_t)STATE_WAKE_DETECTED);

        // 2. Visual feedback: GREEN LED
        digitalWrite(LED_GREEN, LOW);
        digitalWrite(LED_BLUE, HIGH);

        // Reset ADPCM encoder for clean audio transmission
        resetAdpcm();

        if (streamAudioOverBle && central && central.connected()) {
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

          commandCharacteristic.writeValue(header, sizeof(header));
          currentState = STATE_RECORDING_VOICE;
          stateCharacteristic.writeValue((uint8_t)STATE_RECORDING_VOICE);
          digitalWrite(LED_GREEN, HIGH);
          digitalWrite(LED_RED, LOW); // Red = Recording
        } else {
          // If not streaming audio, let phone listen via its own mic
          delay(800);
          currentState = STATE_IDLE;
          stateCharacteristic.writeValue((uint8_t)STATE_IDLE);
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

      commandCharacteristic.writeValue(packet, sizeof(packet));
      chunkIndex++;

      // Stop recording after 2.5 seconds or 156 chunks
      if (chunkIndex >= 156 || (millis() - recordingStartTime > 2800)) {
        // Send Audio End packet (0x03)
        uint8_t endPacket[1] = { 0x03 };
        commandCharacteristic.writeValue(endPacket, 1);

        Serial.println(F("Audio streaming complete. Returning to IDLE."));
        chunkIndex = 0;
        currentState = STATE_IDLE;
        stateCharacteristic.writeValue((uint8_t)STATE_IDLE);
        digitalWrite(LED_RED, HIGH);
        digitalWrite(LED_BLUE, LOW);
      }
    }
  }
}

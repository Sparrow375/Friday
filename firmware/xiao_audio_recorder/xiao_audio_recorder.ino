/*
 * Project Friday - Seeed Studio XIAO nRF52840 Sense
 * Audio Sample Streaming & Dataset Recorder Firmware
 *
 * Board: "Seeed Studio XIAO nRF52840 Sense" (Seeed nRF52 mbed-enabled Boards)
 * Baud Rate: 115200 (or higher)
 *
 * Protocol:
 * - PC sends "START 1500\n" -> Record 1500ms of audio (16kHz 16-bit mono PCM).
 * - XIAO turns RED LED on, streams audio frames preceded by header.
 * - Format:
 *     "AUDIO_START <sample_count>\n"
 *     <raw binary 16-bit PCM little-endian samples>
 *     "AUDIO_END\n"
 * - XIAO turns GREEN LED on when recording completes.
 */

#include <PDM.h>

static const int CHUNK_SAMPLES = 256; // 256 samples * 2 bytes = 512 bytes per chunk
short pcmBuffer[CHUNK_SAMPLES];
volatile int samplesAvailable = 0;

bool isRecording = false;
unsigned long totalSamplesToRecord = 0;
unsigned long samplesRecorded = 0;

void onPDMdata() {
  int bytesAvailable = PDM.available();
  if (bytesAvailable > 0) {
    int bytesToRead = bytesAvailable > (int)sizeof(pcmBuffer) ? sizeof(pcmBuffer) : bytesAvailable;
    PDM.read(pcmBuffer, bytesToRead);
    samplesAvailable = bytesToRead / 2;
  }
}

void setup() {
  Serial.begin(115200);

  // Setup RGB User LEDs (Active LOW)
  pinMode(LED_RED, OUTPUT);
  pinMode(LED_GREEN, OUTPUT);
  pinMode(LED_BLUE, OUTPUT);
  digitalWrite(LED_RED, HIGH);    // OFF
  digitalWrite(LED_GREEN, HIGH);  // OFF
  digitalWrite(LED_BLUE, LOW);    // Blue ON = Ready

  // Initialize PDM @ 16kHz mono
  PDM.onReceive(onPDMdata);
  PDM.setGain(25); // Good sensitivity without clipping

  if (!PDM.begin(1, 16000)) {
    while (1) {
      digitalWrite(LED_RED, LOW);
      delay(100);
      digitalWrite(LED_RED, HIGH);
      delay(100);
    }
  }

  // Ready message
  delay(500);
  Serial.println(F("FRIDAY_RECORDER_READY"));
}

void loop() {
  // Check for commands from PC
  if (Serial.available()) {
    String cmd = Serial.readStringUntil('\n');
    cmd.trim();

    if (cmd.startsWith("START") || cmd.startsWith("RECORD")) {
      // Parse duration in ms (default 1500ms = 1.5 sec)
      int durationMs = 1500;
      int spaceIdx = cmd.indexOf(' ');
      if (spaceIdx > 0) {
        durationMs = cmd.substring(spaceIdx + 1).toInt();
        if (durationMs < 500) durationMs = 500;
        if (durationMs > 5000) durationMs = 5000;
      }

      totalSamplesToRecord = (unsigned long)(16000L * durationMs / 1000L);
      samplesRecorded = 0;
      samplesAvailable = 0;
      isRecording = true;

      // Visual feedback: RED LED = Recording active!
      digitalWrite(LED_BLUE, HIGH);
      digitalWrite(LED_GREEN, HIGH);
      digitalWrite(LED_RED, LOW);

      // Send start header with sample count
      Serial.print("AUDIO_START ");
      Serial.println(totalSamplesToRecord);
      Serial.flush();
    } else if (cmd == "PING") {
      Serial.println(F("PONG"));
    }
  }

  // If currently recording and new PDM samples arrived
  if (isRecording && samplesAvailable > 0) {
    int countToSend = samplesAvailable;
    if (samplesRecorded + countToSend > totalSamplesToRecord) {
      countToSend = totalSamplesToRecord - samplesRecorded;
    }

    // Send binary PCM bytes (countToSend * 2 bytes)
    Serial.write((const uint8_t*)pcmBuffer, countToSend * sizeof(short));
    samplesRecorded += countToSend;
    samplesAvailable = 0;

    // Check if recording finished
    if (samplesRecorded >= totalSamplesToRecord) {
      isRecording = false;
      Serial.flush();
      delay(10);
      Serial.println("\nAUDIO_END");
      Serial.flush();

      // Visual feedback: GREEN LED = Finished
      digitalWrite(LED_RED, HIGH);
      digitalWrite(LED_BLUE, HIGH);
      digitalWrite(LED_GREEN, LOW);
      delay(400);
      digitalWrite(LED_GREEN, HIGH);
      digitalWrite(LED_BLUE, LOW); // Return to Blue Ready
    }
  } else {
    // Drop samples when not actively recording to keep buffer fresh
    samplesAvailable = 0;
  }
}

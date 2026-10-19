/*
 * Project Friday - Seeed Studio XIAO nRF52840 Sense
 * Microphone Test & Sound Level Visualizer
 *
 * Board: "Seeed Studio XIAO nRF52840 Sense" (Seeed nRF52 mbed-enabled Boards)
 * Baud Rate: 115200
 *
 * This sketch verifies that the on-board PDM digital microphone is working
 * properly. It displays real-time audio volume levels, RMS energy, and peak
 * amplitude on the Serial Monitor (or waveform on Serial Plotter).
 *
 * Visual Indicator:
 * - BLUE LED: Active & Listening
 * - GREEN LED: Voice Activity Detected
 */

#include <PDM.h>

// Audio capture buffer (16-bit PCM @ 16kHz mono)
// 256 samples = 16ms of audio
static const int BUFFER_SIZE = 256;
short sampleBuffer[BUFFER_SIZE];
volatile int samplesRead = 0;

// Running statistics for VAD & volume measurement
float currentRms = 0.0f;
short maxPeak = 0;
unsigned long lastPrintTime = 0;
bool plotterMode = false;

// Voice detection threshold (adjust based on room ambient noise)
const float SPEECH_THRESHOLD = 250.0f;

void onPDMdata() {
  // Query bytes available from PDM hardware peripheral
  int bytesAvailable = PDM.available();
  if (bytesAvailable > 0) {
    // Read directly into sample buffer (2 bytes per 16-bit sample)
    int bytesToRead = bytesAvailable > (int)sizeof(sampleBuffer) ? sizeof(sampleBuffer) : bytesAvailable;
    PDM.read(sampleBuffer, bytesToRead);
    samplesRead = bytesToRead / 2;
  }
}

void setup() {
  // Initialize Serial
  Serial.begin(115200);
  
  // Setup RGB User LEDs (Active LOW on XIAO nRF52840)
  pinMode(LED_RED, OUTPUT);
  pinMode(LED_GREEN, OUTPUT);
  pinMode(LED_BLUE, OUTPUT);
  digitalWrite(LED_RED, HIGH);    // OFF
  digitalWrite(LED_GREEN, HIGH);  // OFF
  digitalWrite(LED_BLUE, LOW);    // Blue ON = Ready & Listening

  // Give user a moment to open Serial Monitor
  delay(1500);

  Serial.println(F("=================================================="));
  Serial.println(F("  Project Friday - XIAO nRF52840 Sense Mic Test   "));
  Serial.println(F("=================================================="));
  Serial.println(F("Initializing PDM Digital Microphone @ 16kHz..."));

  // Configure PDM callback
  PDM.onReceive(onPDMdata);

  // Set microphone gain (default is 20, range ~0-80)
  PDM.setGain(25);

  // Initialize PDM: 1 channel (mono), 16 kHz sample rate
  if (!PDM.begin(1, 16000)) {
    Serial.println(F("ERROR: Failed to initialize PDM microphone!"));
    Serial.println(F("Please verify you selected 'Seeed Studio XIAO nRF52840 Sense'"));
    // Flash RED LED rapidly on error
    while (1) {
      digitalWrite(LED_RED, LOW);
      delay(150);
      digitalWrite(LED_RED, HIGH);
      delay(150);
    }
  }

  Serial.println(F("PDM Microphone initialized successfully!"));
  Serial.println(F("Commands:"));
  Serial.println(F("  Send 'p' to toggle between Meter Mode & Serial Plotter Mode"));
  Serial.println(F("  Send '+' / '-' to adjust microphone gain"));
  Serial.println(F("--------------------------------------------------\n"));
}

void loop() {
  // Check for incoming user commands via Serial
  if (Serial.available()) {
    char c = Serial.read();
    if (c == 'p' || c == 'P') {
      plotterMode = !plotterMode;
      if (!plotterMode) {
        Serial.println(F("\n[Switched to Meter Mode]"));
      }
    } else if (c == '+') {
      PDM.setGain(35);
      Serial.println(F("[Gain increased to 35]"));
    } else if (c == '-') {
      PDM.setGain(15);
      Serial.println(F("[Gain decreased to 15]"));
    }
  }

  // Process audio samples when buffer is filled
  if (samplesRead > 0) {
    long sumSquares = 0;
    short peak = 0;

    for (int i = 0; i < samplesRead; i++) {
      short sample = sampleBuffer[i];
      sumSquares += ((long)sample * sample);
      short absSample = abs(sample);
      if (absSample > peak) {
        peak = absSample;
      }

      // If in Serial Plotter mode, output raw waveform
      if (plotterMode) {
        Serial.println(sample);
      }
    }

    // Compute RMS amplitude for this batch
    currentRms = sqrt((float)sumSquares / samplesRead);
    maxPeak = peak;
    samplesRead = 0;

    // Visual LED feedback
    if (currentRms > SPEECH_THRESHOLD) {
      digitalWrite(LED_GREEN, LOW);  // Green ON = Speech detected!
      digitalWrite(LED_BLUE, HIGH);
    } else {
      digitalWrite(LED_GREEN, HIGH); // Green OFF
      digitalWrite(LED_BLUE, LOW);   // Blue ON = Idle listening
    }

    // In Meter Mode, print formatted stats every 100ms
    if (!plotterMode && (millis() - lastPrintTime >= 100)) {
      lastPrintTime = millis();

      // Generate visual ASCII volume bar (up to 25 chars)
      int barLength = (int)(currentRms / 40.0f);
      if (barLength > 25) barLength = 25;

      char bar[26];
      for (int b = 0; b < 25; b++) {
        bar[b] = (b < barLength) ? '=' : ' ';
      }
      bar[25] = '\0';

      Serial.print(F("["));
      Serial.print(bar);
      Serial.print(F("]  RMS: "));
      Serial.print((int)currentRms);
      Serial.print(F("  Peak: "));
      Serial.print(maxPeak);

      if (currentRms > SPEECH_THRESHOLD) {
        Serial.print(F("  *** VOICE DETECTED ***"));
      }
      Serial.println();
    }
  }
}

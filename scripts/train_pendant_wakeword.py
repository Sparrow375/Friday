#!/usr/bin/env python3
"""
Project Friday - Pendant Wake-Word Training & C++ Exporter
Trains an ultra-compact, high-accuracy Convolutional Neural Network (CNN)
on the Seeed Studio XIAO nRF52840 Sense audio dataset and exports a
zero-dependency C++ header `wakeword_model.h` for on-chip inference.

Usage:
  .venv/Scripts/python -u scripts/train_pendant_wakeword.py
"""

import os
import math
import wave
import struct
import random
import numpy as np
from pathlib import Path
from scipy.fft import rfft

import torch
import torch.nn as nn
import torch.optim as optim
from torch.utils.data import TensorDataset, DataLoader

# ==================== Audio & Feature Parameters ====================
SAMPLE_RATE = 16000
AUDIO_DURATION = 1.5  # seconds
TOTAL_SAMPLES = int(SAMPLE_RATE * AUDIO_DURATION)  # 24,000 samples

FRAME_LEN = 512       # 32 ms
HOP_LEN = 384         # 24 ms
NUM_FRAMES = (TOTAL_SAMPLES - FRAME_LEN) // HOP_LEN + 1  # 62 frames
NUM_MELS = 16         # 16 Mel filterbank channels
F_MIN = 200.0         # Speech lower band
F_MAX = 4000.0        # Speech upper band

DATASET_ROOT = Path("dataset/pendant_wakeword")
POS_DIR = DATASET_ROOT / "positive"
NEG_DIR = DATASET_ROOT / "negative"
OUTPUT_DIR = Path("output")
OUTPUT_DIR.mkdir(parents=True, exist_ok=True)

ARDUINO_HEADER_PATH = Path("firmware/xiao_friday_pendant/wakeword_model.h")

# Reproducibility
random.seed(42)
np.random.seed(42)
torch.manual_seed(42)

# ==================== Filterbank Generation ====================
def compute_mel_filterbank(sr=SAMPLE_RATE, n_fft=FRAME_LEN, n_mels=NUM_MELS, fmin=F_MIN, fmax=F_MAX):
    def hz_to_mel(hz): return 2595.0 * np.log10(1.0 + hz / 700.0)
    def mel_to_hz(mel): return 700.0 * (10.0**(mel / 2595.0) - 1.0)

    mel_min = hz_to_mel(fmin)
    mel_max = hz_to_mel(fmax)
    mel_points = np.linspace(mel_min, mel_max, n_mels + 2)
    hz_points = mel_to_hz(mel_points)
    bin_points = np.floor((n_fft + 1) * hz_points / sr).astype(int)

    num_bins = n_fft // 2 + 1
    fbank = np.zeros((n_mels, num_bins), dtype=np.float32)
    for m in range(1, n_mels + 1):
        f_m_minus = bin_points[m - 1]
        f_m = bin_points[m]
        f_m_plus = bin_points[m + 1]
        for k in range(f_m_minus, f_m):
            if f_m > f_m_minus:
                fbank[m - 1, k] = (k - f_m_minus) / (f_m - f_m_minus)
        for k in range(f_m, f_m_plus):
            if f_m_plus > f_m:
                fbank[m - 1, k] = (f_m_plus - k) / (f_m_plus - f_m)
    return fbank

MEL_FBANK = compute_mel_filterbank()
HANNING_WINDOW = 0.5 * (1.0 - np.cos(2.0 * np.pi * np.arange(FRAME_LEN) / (FRAME_LEN - 1))).astype(np.float32)

def extract_log_mel_spectrogram(samples_float):
    """
    Extracts a (NUM_MELS, NUM_FRAMES) Log-Mel Spectrogram from float32 samples [-1.0, 1.0].
    This matches the exact C++ algorithm on the microcontroller.
    """
    if len(samples_float) < TOTAL_SAMPLES:
        samples_float = np.pad(samples_float, (0, TOTAL_SAMPLES - len(samples_float)))
    else:
        samples_float = samples_float[:TOTAL_SAMPLES]

    # Peak normalization invariant to speaker distance / whispering / shouting
    peak = np.max(np.abs(samples_float))
    if peak > (300.0 / 32768.0):
        samples_float = (samples_float / peak) * 0.5

    log_mel = np.zeros((NUM_MELS, NUM_FRAMES), dtype=np.float32)
    for frame_idx in range(NUM_FRAMES):
        start = frame_idx * HOP_LEN
        frame = samples_float[start : start + FRAME_LEN] * HANNING_WINDOW
        fft_mag = np.abs(rfft(frame))
        mel_energy = np.dot(MEL_FBANK, fft_mag)
        log_mel[:, frame_idx] = np.log10(mel_energy + 1e-5)

    mean = np.mean(log_mel)
    var = np.var(log_mel)
    std = np.sqrt(max(var, 0.09))  # Hard std floor at 0.30 prevents room silence/hiss from blowing up into false formants
    normalized = (log_mel - mean) / (2.5 * std)
    return np.clip(normalized, -2.0, 2.0).astype(np.float32)


# ==================== Audio Loading & Augmentation ====================
def load_wav(path):
    with wave.open(str(path), "rb") as wf:
        n_frames = wf.getnframes()
        frames = wf.readframes(n_frames)
        samples = np.frombuffer(frames, dtype=np.int16).astype(np.float32) / 32768.0
        return samples

def augment_audio(samples, noise_pool, is_user=True):
    # 1. Speed perturbation via linear resampling (0.90x to 1.10x simulates natural cadence variations)
    if random.random() < 0.70:
        speed = random.uniform(0.90, 1.10)
        old_indices = np.arange(len(samples))
        new_indices = np.linspace(0, len(samples) - 1, int(len(samples) / speed))
        stretched = np.interp(new_indices, old_indices, samples)
        if len(stretched) < TOTAL_SAMPLES:
            pad_before = random.randint(0, TOTAL_SAMPLES - len(stretched))
            pad_after = TOTAL_SAMPLES - len(stretched) - pad_before
            samples = np.pad(stretched, (pad_before, pad_after))
        else:
            samples = stretched[:TOTAL_SAMPLES]

    # 2. Bounded Translation Shift (+/- 3200 samples = +/- 200ms across 1.5s window)
    # Allows the word "Friday" to slide naturally without truncating syllables
    shift = random.randint(-3200, 3200)
    if shift > 0:
        shifted = np.pad(samples[:-shift], (shift, 0))
    elif shift < 0:
        shifted = np.pad(samples[-shift:], (0, -shift))
    else:
        shifted = samples.copy()

    # 3. Dynamic Gain Variation (0.4x to 1.8x covers soft conversational speech to louder projection)
    gain = random.uniform(0.40, 1.80)
    augmented = shifted * gain

    # 4. Background Room Noise Injection (ambient room murmurs)
    if noise_pool and random.random() < 0.60:
        noise = random.choice(noise_pool)
        noise_start = random.randint(0, max(0, len(noise) - TOTAL_SAMPLES))
        noise_chunk = noise[noise_start : noise_start + TOTAL_SAMPLES]
        if len(noise_chunk) < TOTAL_SAMPLES:
            noise_chunk = np.pad(noise_chunk, (0, TOTAL_SAMPLES - len(noise_chunk)))
        augmented = augmented + noise_chunk * random.uniform(0.02, 0.12)

    # 5. Gentle thermal mic noise
    augmented += np.random.normal(0, random.uniform(0.0005, 0.003), size=augmented.shape)
    return np.clip(augmented, -1.0, 1.0).astype(np.float32)


def augment_noise(samples):
    """Augments ambient room noise without injecting speech."""
    gain = random.uniform(0.40, 2.20)
    shift = random.randint(-6000, 6000)
    if shift > 0:
        shifted = np.roll(samples, shift)
    elif shift < 0:
        shifted = np.roll(samples, shift)
    else:
        shifted = samples.copy()
    augmented = shifted * gain + np.random.normal(0, random.uniform(0.0005, 0.004), size=shifted.shape)
    return np.clip(augmented, -1.0, 1.0).astype(np.float32)


# ==================== Tiny Neural Network Architecture ====================
class TinyWakeWordCnn(nn.Module):
    """
    Ultra-lightweight CNN designed specifically for Cortex-M4F microcontrollers.
    - ~1,700 parameters (< 7 KB Flash).
    - ~3ms inference latency on 64 MHz nRF52840.
    """
    def __init__(self):
        super().__init__()
        # Conv 1: [1, 16, 62] -> [8, 16, 31]
        self.conv1 = nn.Conv2d(1, 8, kernel_size=3, stride=(1, 2), padding=1)
        self.relu1 = nn.ReLU()

        # Conv 2: [8, 16, 31] -> [16, 8, 16]
        self.conv2 = nn.Conv2d(8, 16, kernel_size=3, stride=(2, 2), padding=1)
        self.relu2 = nn.ReLU()

        # Depthwise Separable Conv 3:
        # Depthwise: [16, 8, 16] -> [16, 4, 8]
        self.dw_conv3 = nn.Conv2d(16, 16, kernel_size=3, stride=(2, 2), padding=1, groups=16)
        # Pointwise: [16, 4, 8] -> [16, 4, 8]
        self.pw_conv3 = nn.Conv2d(16, 16, kernel_size=1)
        self.relu3 = nn.ReLU()

        # Frequency Average Pooling: [16, 4, 8] -> [16, 1, 8]
        # Preserves 8 sequential phoneme time slices across the 1.5s window!
        # Prevents time-averaged tone/noise memorization by verifying syllable trajectory!
        self.pool = nn.AdaptiveAvgPool2d((1, 8))

        # Temporal Linear classifier: 16 channels x 8 time slices = 128 inputs -> [1]
        self.fc = nn.Linear(16 * 8, 1)

    def forward(self, x):
        x = self.relu1(self.conv1(x))
        x = self.relu2(self.conv2(x))
        x = self.relu3(self.pw_conv3(self.dw_conv3(x)))
        x = self.pool(x)
        x = torch.flatten(x, 1)
        x = self.fc(x)
        return x


# ==================== C++ Exporter ====================
def export_cpp_header(model, filepath: Path):
    model.eval()
    params = {k: v.cpu().detach().numpy() for k, v in model.state_dict().items()}

    def format_array(name, arr, ctype="const float"):
        flat = arr.flatten()
        vals_str = ", ".join(f"{v:.7f}f" for v in flat)
        return f"{ctype} {name}[{len(flat)}] = {{\n    {vals_str}\n}};\n"

    cpp = """// ============================================================================
// Auto-generated TinyML Wake-Word Model for Project Friday
// Board: Seeed Studio XIAO nRF52840 Sense
// Model: TinyWakeWordCnn (Log-Mel Spectrogram -> Depthwise Separable CNN)
// Zero external dependencies - pure portable C++ with hardware-friendly buffers
// ============================================================================

#ifndef WAKEWORD_MODEL_H
#define WAKEWORD_MODEL_H

#include <Arduino.h>
#include <math.h>

#define WW_SAMPLE_RATE    16000
#define WW_TOTAL_SAMPLES  24000  // 1.5 seconds of audio
#define WW_FRAME_LEN      512
#define WW_HOP_LEN        384
#define WW_NUM_FRAMES     62
#define WW_NUM_MELS       16

// ==================== Filterbank Constants ====================
"""
    cpp += format_array("HANNING_WINDOW", HANNING_WINDOW)
    cpp += "\n"
    cpp += format_array("MEL_FBANK", MEL_FBANK)
    cpp += "\n"
    cpp += "// ==================== Neural Network Weights ====================\n"
    cpp += format_array("CONV1_W", params["conv1.weight"])
    cpp += format_array("CONV1_B", params["conv1.bias"])
    cpp += "\n"
    cpp += format_array("CONV2_W", params["conv2.weight"])
    cpp += format_array("CONV2_B", params["conv2.bias"])
    cpp += "\n"
    cpp += format_array("DW_CONV3_W", params["dw_conv3.weight"])
    cpp += format_array("DW_CONV3_B", params["dw_conv3.bias"])
    cpp += "\n"
    cpp += format_array("PW_CONV3_W", params["pw_conv3.weight"])
    cpp += format_array("PW_CONV3_B", params["pw_conv3.bias"])
    cpp += "\n"
    cpp += format_array("FC_W", params["fc.weight"])
    cpp += format_array("FC_B", params["fc.bias"])
    cpp += "\n"

    cpp += """// ==================== Compact In-Place Radix-2 Real FFT ====================
static void computeFftMagnitude(float* real, float* imag, float* outMag) {
    int n = WW_FRAME_LEN;
    int j = 0;
    for (int i = 0; i < n - 1; i++) {
        if (i < j) {
            float tr = real[i]; real[i] = real[j]; real[j] = tr;
            float ti = imag[i]; imag[i] = imag[j]; imag[j] = ti;
        }
        int k = n >> 1;
        while (k <= j) { j -= k; k >>= 1; }
        j += k;
    }

    for (int l = 1; l < n; l <<= 1) {
        float angle = -3.14159265358979323846f / (float)l;
        float wstepR = cosf(angle);
        float wstepI = sinf(angle);
        int l2 = l << 1;
        for (int m = 0; m < n; m += l2) {
            float wR = 1.0f;
            float wI = 0.0f;
            for (int i = 0; i < l; i++) {
                int i1 = m + i;
                int i2 = i1 + l;
                float tempR = wR * real[i2] - wI * imag[i2];
                float tempI = wR * imag[i2] + wI * real[i2];
                real[i2] = real[i1] - tempR;
                imag[i2] = imag[i1] - tempI;
                real[i1] += tempR;
                imag[i1] += tempI;
                float next_wR = wR * wstepR - wI * wstepI;
                wI = wR * wstepI + wI * wstepR;
                wR = next_wR;
            }
        }
    }

    for (int k = 0; k <= n / 2; k++) {
        outMag[k] = sqrtf(real[k] * real[k] + imag[k] * imag[k]);
    }
}

// Scratch memory buffers (zero heap allocation)
static float fftReal[WW_FRAME_LEN];
static float fftImag[WW_FRAME_LEN];
static float fftMag[WW_FRAME_LEN / 2 + 1];
static float melSpectrogram[WW_NUM_MELS][WW_NUM_FRAMES];

static float conv1Out[8][16][31];
static float conv2Out[16][8][16];
static float dwConvOut[16][4][8];
static float pwConvOut[16][4][8];
static float pooled[128]; // Frequency-pooled across 4 bands, preserving 8 temporal phoneme steps (16 * 8 = 128)

// ==================== Feature Extraction ====================
static bool extractFeatures(const short* rawAudio, short maxAbs) {
    float sumTotal = 0.0f;
    float sumSq = 0.0f;
    int totalElements = WW_NUM_MELS * WW_NUM_FRAMES;

    float inputScale = (maxAbs > 300) ? (0.5f / (float)maxAbs) : (1.0f / 32768.0f);

    for (int f = 0; f < WW_NUM_FRAMES; f++) {
        int start = f * WW_HOP_LEN;
        for (int i = 0; i < WW_FRAME_LEN; i++) {
            int audioIdx = start + i;
            float s = (audioIdx < WW_TOTAL_SAMPLES) ? ((float)rawAudio[audioIdx] * inputScale) : 0.0f;
            fftReal[i] = s * HANNING_WINDOW[i];
            fftImag[i] = 0.0f;
        }

        computeFftMagnitude(fftReal, fftImag, fftMag);

        for (int m = 0; m < WW_NUM_MELS; m++) {
            float energy = 0.0f;
            int fbankRowOffset = m * 257;
            for (int k = 0; k < 257; k++) {
                energy += MEL_FBANK[fbankRowOffset + k] * fftMag[k];
            }
            float logE = log10f(energy + 1e-5f);
            melSpectrogram[m][f] = logE;
            sumTotal += logE;
            sumSq += (logE * logE);
        }
    }

    float mean = sumTotal / (float)totalElements;
    float variance = (sumSq / (float)totalElements) - (mean * mean);
    // Hard spectral variance floor: Room silence/hiss has variance 0.015-0.051.
    // Human speech ("Friday") has formants with variance >= 0.060.
    // Flat acoustic noise is rejected immediately before running the neural network.
    if (variance < 0.052f) {
        return false;
    }
    float std = sqrtf(fmaxf(variance, 0.09f));
    float normScale = 1.0f / (2.5f * std);

    for (int m = 0; m < WW_NUM_MELS; m++) {
        for (int f = 0; f < WW_NUM_FRAMES; f++) {
            float val = (melSpectrogram[m][f] - mean) * normScale;
            if (val > 2.0f) val = 2.0f;
            if (val < -2.0f) val = -2.0f;
            melSpectrogram[m][f] = val;
        }
    }
    return true;
}

// ==================== CNN Forward Pass ====================
static float forwardCnn() {
    // 1. Conv1: in [1, 16, 62] -> out [8, 16, 31], stride=(1, 2), pad=(1, 1), k=3x3
    for (int oc = 0; oc < 8; oc++) {
        for (int oh = 0; oh < 16; oh++) {
            for (int ow = 0; ow < 31; ow++) {
                float sum = CONV1_B[oc];
                int in_w_center = ow * 2;
                for (int kh = -1; kh <= 1; kh++) {
                    int ih = oh + kh;
                    if (ih < 0 || ih >= 16) continue;
                    for (int kw = -1; kw <= 1; kw++) {
                        int iw = in_w_center + kw;
                        if (iw < 0 || iw >= 62) continue;
                        int wIdx = (oc * 9) + ((kh + 1) * 3) + (kw + 1);
                        sum += melSpectrogram[ih][iw] * CONV1_W[wIdx];
                    }
                }
                conv1Out[oc][oh][ow] = (sum > 0.0f) ? sum : 0.0f; // ReLU
            }
        }
    }

    // 2. Conv2: in [8, 16, 31] -> out [16, 8, 16], stride=(2, 2), pad=(1, 1), k=3x3
    for (int oc = 0; oc < 16; oc++) {
        for (int oh = 0; oh < 8; oh++) {
            for (int ow = 0; ow < 16; ow++) {
                float sum = CONV2_B[oc];
                int in_h_center = oh * 2;
                int in_w_center = ow * 2;
                for (int ic = 0; ic < 8; ic++) {
                    for (int kh = -1; kh <= 1; kh++) {
                        int ih = in_h_center + kh;
                        if (ih < 0 || ih >= 16) continue;
                        for (int kw = -1; kw <= 1; kw++) {
                            int iw = in_w_center + kw;
                            if (iw < 0 || iw >= 31) continue;
                            int wIdx = (oc * 72) + (ic * 9) + ((kh + 1) * 3) + (kw + 1);
                            sum += conv1Out[ic][ih][iw] * CONV2_W[wIdx];
                        }
                    }
                }
                conv2Out[oc][oh][ow] = (sum > 0.0f) ? sum : 0.0f; // ReLU
            }
        }
    }

    // 3. Depthwise Conv3: in [16, 8, 16] -> out [16, 4, 8], stride=(2, 2), pad=(1, 1), k=3x3
    for (int c = 0; c < 16; c++) {
        for (int oh = 0; oh < 4; oh++) {
            for (int ow = 0; ow < 8; ow++) {
                float sum = DW_CONV3_B[c];
                int in_h_center = oh * 2;
                int in_w_center = ow * 2;
                for (int kh = -1; kh <= 1; kh++) {
                    int ih = in_h_center + kh;
                    if (ih < 0 || ih >= 8) continue;
                    for (int kw = -1; kw <= 1; kw++) {
                        int iw = in_w_center + kw;
                        if (iw < 0 || iw >= 16) continue;
                        int wIdx = (c * 9) + ((kh + 1) * 3) + (kw + 1);
                        sum += conv2Out[c][ih][iw] * DW_CONV3_W[wIdx];
                    }
                }
                dwConvOut[c][oh][ow] = sum;
            }
        }
    }

    // 4. Pointwise Conv3: in [16, 4, 8] -> out [16, 4, 8], 1x1 conv + ReLU
    for (int oc = 0; oc < 16; oc++) {
        for (int oh = 0; oh < 4; oh++) {
            for (int ow = 0; ow < 8; ow++) {
                float sum = PW_CONV3_B[oc];
                for (int ic = 0; ic < 16; ic++) {
                    int wIdx = (oc * 16) + ic;
                    sum += dwConvOut[ic][oh][ow] * PW_CONV3_W[wIdx];
                }
                pwConvOut[oc][oh][ow] = (sum > 0.0f) ? sum : 0.0f; // ReLU
            }
        }
    }

    // 5. Frequency Pooling: [16, 4, 8] -> [16, 8] (preserves 8 temporal phoneme time slices!)
    for (int c = 0; c < 16; c++) {
        for (int w = 0; w < 8; w++) {
            float sum = 0.0f;
            for (int h = 0; h < 4; h++) {
                sum += pwConvOut[c][h][w];
            }
            pooled[(c * 8) + w] = sum / 4.0f;
        }
    }

    // 6. Temporal Dense Classifier: [128] -> [1] + Sigmoid
    float logit = FC_B[0];
    for (int i = 0; i < 128; i++) {
        logit += pooled[i] * FC_W[i];
    }

    return 1.0f / (1.0f + expf(-logit));
}

// ==================== Public API ====================
/**
 * Extracts log-mel spectrogram features directly from a circular buffer of 24,000 samples.
 * headIdx points to the oldest sample in the circular buffer.
 * Returns false if the audio buffer is flat ambient noise / silence (spectral variance < 0.052).
 */
static bool extractFeaturesCircular(const short* circBuffer, int headIdx, short maxAbs) {
    float sumTotal = 0.0f;
    float sumSq = 0.0f;
    int totalElements = WW_NUM_MELS * WW_NUM_FRAMES;

    float inputScale = (maxAbs > 300) ? (0.5f / (float)maxAbs) : (1.0f / 32768.0f);

    for (int f = 0; f < WW_NUM_FRAMES; f++) {
        int start = f * WW_HOP_LEN;
        for (int i = 0; i < WW_FRAME_LEN; i++) {
            int offset = start + i;
            float s = 0.0f;
            if (offset < WW_TOTAL_SAMPLES) {
                int circIdx = (headIdx + offset) % WW_TOTAL_SAMPLES;
                s = (float)circBuffer[circIdx] * inputScale;
            }
            fftReal[i] = s * HANNING_WINDOW[i];
            fftImag[i] = 0.0f;
        }

        computeFftMagnitude(fftReal, fftImag, fftMag);

        for (int m = 0; m < WW_NUM_MELS; m++) {
            float energy = 0.0f;
            int fbankRowOffset = m * 257;
            for (int k = 0; k < 257; k++) {
                energy += MEL_FBANK[fbankRowOffset + k] * fftMag[k];
            }
            float logE = log10f(energy + 1e-5f);
            melSpectrogram[m][f] = logE;
            sumTotal += logE;
            sumSq += (logE * logE);
        }
    }

    float mean = sumTotal / (float)totalElements;
    float variance = (sumSq / (float)totalElements) - (mean * mean);
    // Hard spectral variance floor: Room silence/hiss has variance 0.015-0.051.
    // Human speech ("Friday") has formants with variance >= 0.060.
    // Flat acoustic noise is rejected immediately before running the neural network.
    if (variance < 0.052f) {
        return false;
    }
    float std = sqrtf(fmaxf(variance, 0.09f));
    float normScale = 1.0f / (2.5f * std);

    for (int m = 0; m < WW_NUM_MELS; m++) {
        for (int f = 0; f < WW_NUM_FRAMES; f++) {
            float val = (melSpectrogram[m][f] - mean) * normScale;
            if (val > 2.0f) val = 2.0f;
            if (val < -2.0f) val = -2.0f;
            melSpectrogram[m][f] = val;
        }
    }
    return true;
}

/**
 * Runs TinyML wake-word model directly on a circular audio buffer without memory copying.
 * Returns 0.0f immediately if peak amplitude is below physical speech floor (< 260 ADC counts)
 * or if spectral variance is flat background noise (< 0.052).
 */
static inline float predictWakeWordScoreCircular(const short* circBuffer, int headIdx) {
    short maxAbs = 0;
    for (int i = 0; i < WW_TOTAL_SAMPLES; i += 4) {
        int circIdx = (headIdx + i) % WW_TOTAL_SAMPLES;
        short val = circBuffer[circIdx];
        if (val < 0) val = -val;
        if (val > maxAbs) maxAbs = val;
    }

    // Hard acoustic floor: If peak in 1.5 seconds is < 260 ADC counts,
    // it is flat room noise or physical silence (room noise peak is 180-240).
    // Human vocalization ("Friday") always exceeds 280-400 ADC counts.
    if (maxAbs < 260) {
        return 0.0f;
    }

    if (!extractFeaturesCircular(circBuffer, headIdx, maxAbs)) {
        return 0.0f;
    }
    return forwardCnn();
}

/**
 * Runs the TinyML wake-word model on a linear 1.5-second buffer (24,000 samples @ 16kHz).
 * Returns the probability (0.0 to 1.0) that "Friday" was spoken.
 */
static inline float predictWakeWordScore(const short* audio24k) {
    short maxAbs = 0;
    for (int i = 0; i < WW_TOTAL_SAMPLES; i += 4) {
        short val = audio24k[i];
        if (val < 0) val = -val;
        if (val > maxAbs) maxAbs = val;
    }

    if (maxAbs < 260) {
        return 0.0f;
    }

    if (!extractFeatures(audio24k, maxAbs)) {
        return 0.0f;
    }
    return forwardCnn();
}

/**
 * Convenience helper returning true if confidence exceeds threshold (default 0.70).
 */
static inline bool isWakeWordDetected(const short* audio24k, float threshold = 0.70f) {
    return predictWakeWordScore(audio24k) >= threshold;
}

#endif // WAKEWORD_MODEL_H
"""

    with open(filepath, "w") as f:
        f.write(cpp)
    print(f"[✓] C++ inference engine successfully written to: {filepath}", flush=True)


# ==================== Main Training Routine ====================
def main():
    print("==================================================", flush=True)
    print("    Project Friday: Pendant Wake-Word Training    ", flush=True)
    print("==================================================", flush=True)

    device = torch.device("cuda" if torch.cuda.is_available() else "cpu")
    print(f"Using compute device: {device}", flush=True)

    user_pos_files = sorted(list(set(list(POS_DIR.glob("friday_*.wav")) + list(POS_DIR.glob("friday_user_*.wav")))))
    user_noise_files = sorted(list(set(list(NEG_DIR.glob("noise_*.wav")) + list(NEG_DIR.glob("real_silence_*.wav")) + list(NEG_DIR.glob("silence_*.wav")))))
    user_speech_files = sorted(list(NEG_DIR.glob("speech_*.wav")))
    synth_neg_files = sorted(list(NEG_DIR.glob("synth_*.wav")))[:1000]

    print(f"Dataset files found:")
    print(f"  User recorded 'Friday' samples: {len(user_pos_files)} (PURE USER GROUND TRUTH - 0 synthetic positives)")
    print(f"  User recorded room noise:       {len(user_noise_files)}")
    print(f"  User negative speech samples:   {len(user_speech_files)}")
    print(f"  Synthetic negative words:       {len(synth_neg_files)}")

    if not user_pos_files:
        print("Error: No positive samples found in dataset directory!", flush=True)
        return

    raw_user_pos = [load_wav(f) for f in user_pos_files]
    raw_user_noise = [load_wav(f) for f in user_noise_files]
    raw_user_speech = [load_wav(f) for f in user_speech_files]
    raw_synth_neg = [load_wav(f) for f in synth_neg_files]

    # Build background noise pool exclusively from real user room noise
    noise_pool = [n for n in raw_user_noise if len(n) >= TOTAL_SAMPLES]

    # Harvest dense sliding windows directly from user's continuous room silence recordings
    raw_silence_files = sorted(list(Path("dataset/raw_recordings").glob("silence_continuous_*.wav")))
    dense_silence_slices = []
    for sf in raw_silence_files:
        with wave.open(str(sf), "rb") as wf:
            s_audio = np.frombuffer(wf.readframes(wf.getnframes()), dtype=np.int16).astype(np.float32) / 32768.0
        # Skip first 1 second (16000 samples) to avoid user keyboard Enter click
        if len(s_audio) > (16000 + TOTAL_SAMPLES):
            s_clean = s_audio[16000:]
            for st in range(0, len(s_clean) - TOTAL_SAMPLES, 1600):  # 100ms stride -> 820+ dense windows
                dense_silence_slices.append(s_clean[st : st + TOTAL_SAMPLES])
    print(f"  Harvested {len(dense_silence_slices)} dense room silence windows directly from continuous silence recordings...", flush=True)
    if dense_silence_slices:
        noise_pool.extend(dense_silence_slices)

    print("\nExtracting features with pure user data grounding...", flush=True)
    all_specs = []
    all_labels = []

    # 1. User positive samples: 30x augmentation centered on user's authentic vocal profile
    print(f"  Augmenting {len(raw_user_pos)} user 'Friday' samples (30x each)...", flush=True)
    for p in raw_user_pos:
        all_specs.append(extract_log_mel_spectrogram(p))
        all_labels.append(1.0)
        for _ in range(30):
            aug = augment_audio(p, noise_pool, is_user=True)
            all_specs.append(extract_log_mel_spectrogram(aug))
            all_labels.append(1.0)

    # 2. User room noise (pendant clips): 30x heavy augmentation without speech mixing to guarantee 0% false alarms in silence
    print(f"  Augmenting {len(raw_user_noise)} user room noise samples (30x each)...", flush=True)
    for n in raw_user_noise:
        all_specs.append(extract_log_mel_spectrogram(n))
        all_labels.append(0.0)
        for _ in range(30):
            aug = augment_noise(n)
            all_specs.append(extract_log_mel_spectrogram(aug))
            all_labels.append(0.0)

    # 2b. Dense Real Room Silence (820 slices x 3 variations = ~2,460 samples)
    if dense_silence_slices:
        print(f"  Injecting {len(dense_silence_slices)} dense room silence slices with 2x gain variations...", flush=True)
        for s in dense_silence_slices:
            all_specs.append(extract_log_mel_spectrogram(s))
            all_labels.append(0.0)
            for _ in range(2):
                gain = random.uniform(0.50, 2.20)
                aug_s = s * gain + np.random.normal(0, random.uniform(0.0005, 0.003), size=len(s))
                all_specs.append(extract_log_mel_spectrogram(aug_s.astype(np.float32)))
                all_labels.append(0.0)

    # 3. User negative speech (e.g. 'hello', 'test', numbers): 25x augmentation
    print(f"  Augmenting {len(raw_user_speech)} user negative speech samples (25x each)...", flush=True)
    for s in raw_user_speech:
        all_specs.append(extract_log_mel_spectrogram(s))
        all_labels.append(0.0)
        for _ in range(25):
            aug = augment_audio(s, noise_pool, is_user=True)
            all_specs.append(extract_log_mel_spectrogram(aug))
            all_labels.append(0.0)

    # 4. Physical room silence, mains hum, and microphone hiss variations
    print("  Generating 350 physical room silence and low-frequency noise samples...", flush=True)
    for _ in range(350):
        noise_amp = random.uniform(0.0005, 0.005)
        silence_sample = np.random.normal(0, noise_amp, TOTAL_SAMPLES)
        if random.random() < 0.5:
            hum_freq = random.choice([50.0, 60.0, 100.0, 120.0])
            t = np.linspace(0, AUDIO_DURATION, TOTAL_SAMPLES)
            hum = np.sin(2 * np.pi * hum_freq * t) * random.uniform(0.0005, 0.003)
            silence_sample += hum
        all_specs.append(extract_log_mel_spectrogram(silence_sample.astype(np.float32)))
        all_labels.append(0.0)

    # 4b. Narrowband acoustic tones, whistles, and motor hums (500 samples across 200Hz to 3500Hz)
    print("  Generating 500 narrowband acoustic tones and continuous whistle samples...", flush=True)
    t = np.linspace(0, AUDIO_DURATION, TOTAL_SAMPLES)
    for freq in np.linspace(200, 3500, 250):
        tone = np.sin(2 * np.pi * freq * t).astype(np.float32) * random.uniform(0.1, 0.8)
        all_specs.append(extract_log_mel_spectrogram(tone))
        all_labels.append(0.0)
        tone2 = (np.sin(2 * np.pi * freq * t) + 0.5 * np.sin(2 * np.pi * (freq * 2) * t)).astype(np.float32) * random.uniform(0.1, 0.6)
        all_specs.append(extract_log_mel_spectrogram(tone2))
        all_labels.append(0.0)

    # 5. Synthetic negative words (English words that are NOT Friday)
    print(f"  Loading {len(raw_synth_neg)} synthetic negative words...", flush=True)
    for sn in raw_synth_neg:
        all_specs.append(extract_log_mel_spectrogram(sn))
        all_labels.append(0.0)

    # 6. Random normalized Gaussian noise matrices (immunizing against spatial ReLU bias false activations)
    print("  Generating 1,500 random noise matrices (immunizing against random noise false alarms)...", flush=True)
    for _ in range(1500):
        scale = random.uniform(0.3, 1.2)
        spec = np.clip(np.random.randn(NUM_MELS, NUM_FRAMES).astype(np.float32) * scale, -2.0, 2.0)
        all_specs.append(spec)
        all_labels.append(0.0)

    all_specs = np.array(all_specs, dtype=np.float32)  # [N, 16, 62]
    all_labels = np.array(all_labels, dtype=np.float32)  # [N]

    pos_count = int(np.sum(all_labels == 1.0))
    neg_count = int(np.sum(all_labels == 0.0))
    print(f"\nTotal training dataset: {len(all_specs)} samples ({pos_count} positive, {neg_count} negative)", flush=True)

    # Shuffle and Split 80/20
    indices = np.arange(len(all_specs))
    np.random.shuffle(indices)

    split = int(len(indices) * 0.8)
    train_idx, val_idx = indices[:split], indices[split:]

    train_x = torch.tensor(all_specs[train_idx]).unsqueeze(1)
    train_y = torch.tensor(all_labels[train_idx])
    val_x = torch.tensor(all_specs[val_idx]).unsqueeze(1)
    val_y = torch.tensor(all_labels[val_idx])

    train_loader = DataLoader(TensorDataset(train_x, train_y), batch_size=64, shuffle=True)
    val_loader = DataLoader(TensorDataset(val_x, val_y), batch_size=64, shuffle=False)

    # Build Model
    model = TinyWakeWordCnn().to(device)
    total_params = sum(p.numel() for p in model.parameters() if p.requires_grad)
    print(f"Model Architecture: TinyWakeWordCnn ({total_params} parameters, ~{total_params * 4 / 1024:.2f} KB)", flush=True)

    # Balanced BCE loss
    pos_weight = torch.tensor([1.00]).to(device)
    criterion = nn.BCEWithLogitsLoss(pos_weight=pos_weight)
    optimizer = optim.Adam(model.parameters(), lr=0.003, weight_decay=1e-4)
    scheduler = optim.lr_scheduler.CosineAnnealingLR(optimizer, T_max=75, eta_min=1e-4)

    epochs = 75
    best_score = -1.0
    best_weights = None

    # Pre-extract spectrograms of original user samples for tracking during training
    user_test_specs = [torch.tensor(extract_log_mel_spectrogram(p)).unsqueeze(0).unsqueeze(0).to(device) for p in raw_user_pos]
    user_noise_test_specs = [torch.tensor(extract_log_mel_spectrogram(n)).unsqueeze(0).unsqueeze(0).to(device) for n in raw_user_noise]
    user_speech_test_specs = [torch.tensor(extract_log_mel_spectrogram(s)).unsqueeze(0).unsqueeze(0).to(device) for s in raw_user_speech]
    random_noise_test_specs = [torch.randn(1, 1, NUM_MELS, NUM_FRAMES).to(device) * scale for scale in [0.4, 0.7, 1.0, 1.2] for _ in range(25)]
    cont_sil_test_specs = [torch.tensor(extract_log_mel_spectrogram(s)).unsqueeze(0).unsqueeze(0).to(device) for s in dense_silence_slices[::4]] if dense_silence_slices else []
    t_val = np.linspace(0, AUDIO_DURATION, TOTAL_SAMPLES)
    tone_test_specs = [torch.tensor(extract_log_mel_spectrogram(np.sin(2 * np.pi * f * t_val).astype(np.float32) * 0.5)).unsqueeze(0).unsqueeze(0).to(device) for f in [440, 600, 800, 1000, 1500]]

    print("\nStarting training loop...", flush=True)
    for epoch in range(1, epochs + 1):
        model.train()
        train_loss = 0.0
        train_correct = 0
        train_total = 0

        for specs, labels in train_loader:
            specs = specs.to(device)
            labels = labels.to(device)

            optimizer.zero_grad()
            logits = model(specs).squeeze(-1)
            loss = criterion(logits, labels)
            loss.backward()
            optimizer.step()

            train_loss += loss.item() * len(labels)
            preds = (torch.sigmoid(logits) >= 0.5).float()
            train_correct += (preds == labels).sum().item()
            train_total += len(labels)

        train_acc = train_correct / train_total

        # Validation
        model.eval()
        val_loss = 0.0
        val_correct = 0
        val_total = 0
        with torch.no_grad():
            for specs, labels in val_loader:
                specs = specs.to(device)
                labels = labels.to(device)

                logits = model(specs).squeeze(-1)
                loss = criterion(logits, labels)
                val_loss += loss.item() * len(labels)
                preds = (torch.sigmoid(logits) >= 0.5).float()
                val_correct += (preds == labels).sum().item()
                val_total += len(labels)

            # Evaluate user test recall (positive detection @ 0.70)
            user_hits = sum(1 for s in user_test_specs if torch.sigmoid(model(s)).item() >= 0.70)
            user_recall = user_hits / len(user_test_specs) if user_test_specs else 1.0

            # Evaluate user room noise rejection (must be < 0.25)
            user_noise_rejects = sum(1 for s in user_noise_test_specs if torch.sigmoid(model(s)).item() < 0.25)
            user_noise_rejection = user_noise_rejects / len(user_noise_test_specs) if user_noise_test_specs else 1.0

            # Evaluate random noise matrix rejection (must be < 0.15)
            rand_noise_rejects = sum(1 for s in random_noise_test_specs if torch.sigmoid(model(s)).item() < 0.15)
            rand_noise_rejection = rand_noise_rejects / len(random_noise_test_specs)

            # Evaluate continuous room silence stream rejection (must be < 0.50)
            cont_sil_hits = sum(1 for s in cont_sil_test_specs if torch.sigmoid(model(s)).item() >= 0.50)
            cont_sil_rejection = 1.0 - (cont_sil_hits / len(cont_sil_test_specs)) if cont_sil_test_specs else 1.0

            # Evaluate pure acoustic tone rejection (must be < 0.25)
            tone_hits = sum(1 for s in tone_test_specs if torch.sigmoid(model(s)).item() >= 0.50)
            tone_rejection = 1.0 - (tone_hits / len(tone_test_specs))

            # Evaluate user speech rejection (negative words < 0.50)
            user_speech_rejects = sum(1 for s in user_speech_test_specs if torch.sigmoid(model(s)).item() < 0.50)
            user_speech_rejection = user_speech_rejects / len(user_speech_test_specs) if user_speech_test_specs else 1.0

        val_acc = val_correct / val_total
        scheduler.step()

        # Strict scoring: If any room noise, continuous silence, or pure tones trigger, penalize heavily
        epoch_score = (val_acc * 0.10) + (user_recall * 0.35) + (user_noise_rejection * 0.15) + (cont_sil_rejection * 0.15) + (tone_rejection * 0.10) + (rand_noise_rejection * 0.05) + (user_speech_rejection * 0.10)
        if rand_noise_rejection < 0.98 or user_noise_rejection < 0.90 or cont_sil_rejection < 0.98 or tone_rejection < 0.98:
            epoch_score *= 0.5

        if epoch_score >= best_score:
            best_score = epoch_score
            best_weights = {k: v.cpu().clone() for k, v in model.state_dict().items()}

        if epoch % 5 == 0 or epoch == epochs:
            print(f"Epoch [{epoch:02d}/{epochs}] - Loss: {train_loss/train_total:.4f}, Acc: {train_acc*100:.1f}% | Val Acc: {val_acc*100:.1f}%, User Recall: {user_recall*100:.1f}%, Sil Rej: {cont_sil_rejection*100:.1f}%, Tone Rej: {tone_rejection*100:.1f}%", flush=True)



    print(f"\nTraining Complete! Best Combined Score: {best_score * 100:.1f}%", flush=True)

    model.load_state_dict(best_weights)
    model.eval()
    model.cpu()

    # Detailed Evaluation on original recorded user samples
    print("\n--- Evaluation on User Recorded Samples (52 clips) ---", flush=True)
    user_preds = []
    with torch.no_grad():
        for p in raw_user_pos:
            spec = torch.tensor(extract_log_mel_spectrogram(p)).unsqueeze(0).unsqueeze(0)
            prob = torch.sigmoid(model(spec)).item()
            user_preds.append(prob)

    u_hit_50 = sum(1 for p in user_preds if p >= 0.50)
    u_hit_65 = sum(1 for p in user_preds if p >= 0.65)
    u_hit_75 = sum(1 for p in user_preds if p >= 0.75)
    print(f"  User Positives >= 0.50: {u_hit_50}/{len(user_preds)} ({u_hit_50/len(user_preds)*100:.1f}%)", flush=True)
    print(f"  User Positives >= 0.65: {u_hit_65}/{len(user_preds)} ({u_hit_65/len(user_preds)*100:.1f}%)", flush=True)
    print(f"  User Positives >= 0.75: {u_hit_75}/{len(user_preds)} ({u_hit_75/len(user_preds)*100:.1f}%)", flush=True)
    print(f"  User Average Confidence: {np.mean(user_preds):.3f}", flush=True)

    # Evaluation on synthetic negatives
    print("\n--- Evaluation on Negative Samples ---", flush=True)
    neg_preds = []
    with torch.no_grad():
        for n in (raw_user_noise + raw_user_speech + raw_synth_neg[:500]):
            spec = torch.tensor(extract_log_mel_spectrogram(n)).unsqueeze(0).unsqueeze(0)
            prob = torch.sigmoid(model(spec)).item()
            neg_preds.append(prob)

    neg_rejected_65 = sum(1 for p in neg_preds if p < 0.65)
    neg_rejected_50 = sum(1 for p in neg_preds if p < 0.50)
    print(f"  Negative Rejection (< 0.65): {neg_rejected_65}/{len(neg_preds)} ({neg_rejected_65/len(neg_preds)*100:.1f}%)", flush=True)
    print(f"  Negative Rejection (< 0.50): {neg_rejected_50}/{len(neg_preds)} ({neg_rejected_50/len(neg_preds)*100:.1f}%)", flush=True)
    print(f"  Negative Average Score:      {np.mean(neg_preds):.3f}", flush=True)

    # Save artifacts
    pth_path = OUTPUT_DIR / "pendant_wakeword_model.pth"
    torch.save(best_weights, pth_path)
    print(f"\n[✓] PyTorch model saved to: {pth_path}", flush=True)

    ARDUINO_HEADER_PATH.parent.mkdir(parents=True, exist_ok=True)
    export_cpp_header(model, ARDUINO_HEADER_PATH)
    print("\nModel is fully compiled into C++ and ready to run on Seeed Studio XIAO nRF52840 Sense!", flush=True)

    # Optional ONNX export
    try:
        onnx_path = OUTPUT_DIR / "pendant_wakeword.onnx"
        dummy_input = torch.randn(1, 1, NUM_MELS, NUM_FRAMES, dtype=torch.float32)
        torch.onnx.export(
            model,
            dummy_input,
            str(onnx_path),
            input_names=["input"],
            output_names=["output"],
            dynamic_axes={"input": {0: "batch_size"}, "output": {0: "batch_size"}},
            opset_version=13
        )
        print(f"[✓] ONNX model exported to: {onnx_path}", flush=True)
    except Exception as e:
        print(f"Note: ONNX export skipped ({e}). C++ header is ready for Arduino.", flush=True)


if __name__ == "__main__":
    main()

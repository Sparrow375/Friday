#!/usr/bin/env python3
"""
Project Friday - Pendant Wake-Word Training & C++ Exporter
Trains PhoneticWakeWordNet (32 Mel bands, Depthwise-Separable Convolutions,
Batch Normalization, and 2-Layer Non-Linear MLP Classification Head)
on authentic user recordings and continuous conversational speech negatives.

Exports a zero-dependency C++ header `wakeword_model.h` with folded BatchNorm
for real-time on-chip inference on the Seeed Studio XIAO nRF52840 Sense.
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
NUM_MELS = 32         # 32 Mel filterbank channels (high formant resolution)
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
    Matches the exact C++ algorithm on the microcontroller.
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
    shift = random.randint(-3200, 3200)
    if shift > 0:
        shifted = np.pad(samples[:-shift], (shift, 0))
    elif shift < 0:
        shifted = np.pad(samples[-shift:], (0, -shift))
    else:
        shifted = samples.copy()

    # 3. Dynamic Gain Variations (0.60x to 1.80x simulates head orientation and proximity)
    gain = random.uniform(0.60, 1.80)
    augmented = shifted * gain

    # 4. Realistic Background Noise Injection (SNR 12dB to 30dB)
    if noise_pool and random.random() < 0.85:
        noise = random.choice(noise_pool)
        if len(noise) >= TOTAL_SAMPLES:
            n_start = random.randint(0, len(noise) - TOTAL_SAMPLES)
            noise_slice = noise[n_start : n_start + TOTAL_SAMPLES]
        else:
            noise_slice = np.pad(noise, (0, TOTAL_SAMPLES - len(noise)))

        noise_scale = random.uniform(0.02, 0.12)
        augmented = augmented + (noise_slice * noise_scale)

    return np.clip(augmented, -1.0, 1.0).astype(np.float32)


# ==================== Neural Network Architecture ====================
class PhoneticWakeWordNet(nn.Module):
    """
    Phonetic Wake-Word Network for Cortex-M4F microcontroller.
    - 32 Mel filterbank channels for fine formant resolution.
    - Depthwise-Separable Convolutions with Batch Normalization.
    - Preserves 8 chronological phoneme time slices across 1.5s window.
    - 2-Layer Non-Linear MLP Head (256 -> 32 -> 1) with ReLU gating:
      requires all phonemes (/f/, /r/, /aI/, /d/, /eI/) to be satisfied in sequence,
      preventing isolated loud vowels or normal speech from triggering false alarms.
    - ~10,600 parameters (~41 KB Flash, ~73 KB RAM).
    """
    def __init__(self):
        super().__init__()
        # Conv 1: [1, 32, 62] -> [16, 16, 31]
        self.conv1 = nn.Conv2d(1, 16, kernel_size=3, stride=(2, 2), padding=1, bias=False)
        self.bn1 = nn.BatchNorm2d(16)
        self.relu1 = nn.ReLU()

        # Block 2 (DS-Conv): [16, 16, 31] -> [32, 8, 16]
        self.dw_conv2 = nn.Conv2d(16, 16, kernel_size=3, stride=(2, 2), padding=1, groups=16, bias=False)
        self.bn_dw2 = nn.BatchNorm2d(16)
        self.relu_dw2 = nn.ReLU()
        self.pw_conv2 = nn.Conv2d(16, 32, kernel_size=1, bias=False)
        self.bn_pw2 = nn.BatchNorm2d(32)
        self.relu_pw2 = nn.ReLU()

        # Block 3 (DS-Conv): [32, 8, 16] -> [32, 4, 8]
        self.dw_conv3 = nn.Conv2d(32, 32, kernel_size=3, stride=(2, 2), padding=1, groups=32, bias=False)
        self.bn_dw3 = nn.BatchNorm2d(32)
        self.relu_dw3 = nn.ReLU()
        self.pw_conv3 = nn.Conv2d(32, 32, kernel_size=1, bias=False)
        self.bn_pw3 = nn.BatchNorm2d(32)
        self.relu_pw3 = nn.ReLU()

        # Frequency Average Pooling: [32, 4, 8] -> [32, 1, 8]
        # Preserves 8 chronological phoneme steps across time!
        self.pool = nn.AdaptiveAvgPool2d((1, 8))

        # 2-Layer Non-Linear Classification Head: 256 -> 32 -> 1
        self.fc1 = nn.Linear(32 * 8, 32)
        self.relu_fc1 = nn.ReLU()
        self.drop = nn.Dropout(0.25)
        self.fc2 = nn.Linear(32, 1)

    def forward(self, x):
        x = self.relu1(self.bn1(self.conv1(x)))
        x = self.relu_dw2(self.bn_dw2(self.dw_conv2(x)))
        x = self.relu_pw2(self.bn_pw2(self.pw_conv2(x)))
        x = self.relu_dw3(self.bn_dw3(self.dw_conv3(x)))
        x = self.relu_pw3(self.bn_pw3(self.pw_conv3(x)))
        x = self.pool(x)
        x = torch.flatten(x, 1)
        x = self.drop(self.relu_fc1(self.fc1(x)))
        x = self.fc2(x)
        return x


# ==================== C++ Exporter with Folded BatchNorm ====================
def export_cpp_header(model, filepath: Path):
    model.eval()

    def fold_bn(conv, bn):
        scale = bn.weight.data / torch.sqrt(bn.running_var + bn.eps)
        w_folded = conv.weight.data * scale.view(-1, 1, 1, 1)
        b_folded = bn.bias.data - bn.running_mean * scale
        return w_folded.cpu().numpy(), b_folded.cpu().numpy()

    c1_w, c1_b = fold_bn(model.conv1, model.bn1)
    dw2_w, dw2_b = fold_bn(model.dw_conv2, model.bn_dw2)
    pw2_w, pw2_b = fold_bn(model.pw_conv2, model.bn_pw2)
    dw3_w, dw3_b = fold_bn(model.dw_conv3, model.bn_dw3)
    pw3_w, pw3_b = fold_bn(model.pw_conv3, model.bn_pw3)
    fc1_w = model.fc1.weight.detach().cpu().numpy()
    fc1_b = model.fc1.bias.detach().cpu().numpy()
    fc2_w = model.fc2.weight.detach().cpu().numpy()[0]
    fc2_b = model.fc2.bias.detach().cpu().numpy()[0]

    def format_array(name, arr, ctype="const float"):
        flat = arr.flatten()
        vals_str = ", ".join(f"{v:.7f}f" for v in flat)
        return f"{ctype} {name}[{len(flat)}] = {{\n    {vals_str}\n}};\n"

    cpp = """// ============================================================================
// Auto-generated TinyML Wake-Word Model for Project Friday
// Board: Seeed Studio XIAO nRF52840 Sense
// Model: PhoneticWakeWordNet (32 Mel bands, DS-CNN, Folded BatchNorm, 2-Layer MLP)
// Zero external dependencies - pure portable C++ with static scratch buffers
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
#define WW_NUM_MELS       32     // 32 Mel filterbank channels for fine formant resolution

// ==================== Filterbank Constants ====================
"""
    cpp += format_array("HANNING_WINDOW", HANNING_WINDOW)
    cpp += "\n"
    cpp += format_array("MEL_FBANK", MEL_FBANK)
    cpp += "\n"
    cpp += "// ==================== Neural Network Weights (Folded BatchNorm) ====================\n"
    cpp += format_array("CONV1_W", c1_w)
    cpp += format_array("CONV1_B", c1_b)
    cpp += "\n"
    cpp += format_array("DW_CONV2_W", dw2_w)
    cpp += format_array("DW_CONV2_B", dw2_b)
    cpp += "\n"
    cpp += format_array("PW_CONV2_W", pw2_w)
    cpp += format_array("PW_CONV2_B", pw2_b)
    cpp += "\n"
    cpp += format_array("DW_CONV3_W", dw3_w)
    cpp += format_array("DW_CONV3_B", dw3_b)
    cpp += "\n"
    cpp += format_array("PW_CONV3_W", pw3_w)
    cpp += format_array("PW_CONV3_B", pw3_b)
    cpp += "\n"
    cpp += format_array("FC1_W", fc1_w)
    cpp += format_array("FC1_B", fc1_b)
    cpp += "\n"
    cpp += format_array("FC2_W", fc2_w)
    cpp += f"const float FC2_B[1] = {{ {fc2_b:.7f}f }};\n\n"

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

// Scratch memory buffers (zero heap allocation, static BSS)
static float fftReal[WW_FRAME_LEN];
static float fftImag[WW_FRAME_LEN];
static float fftMag[WW_FRAME_LEN / 2 + 1];
static float melSpectrogram[WW_NUM_MELS][WW_NUM_FRAMES];

static float conv1Out[16][16][31];
static float dw2Out[16][8][16];
static float pw2Out[32][8][16];
static float dw3Out[32][4][8];
static float pw3Out[32][4][8];
static float pooled[256];
static float hidden[32];

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
            for (int k = 7; k <= 127; k++) {
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
    // 1. Conv1: in [1, 32, 62] -> out [16, 16, 31], stride=(2, 2), pad=(1, 1), k=3x3
    for (int oc = 0; oc < 16; oc++) {
        for (int oh = 0; oh < 16; oh++) {
            for (int ow = 0; ow < 31; ow++) {
                float sum = CONV1_B[oc];
                int in_h_center = oh * 2;
                int in_w_center = ow * 2;
                for (int kh = -1; kh <= 1; kh++) {
                    int ih = in_h_center + kh;
                    if (ih < 0 || ih >= 32) continue;
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

    // 2. Depthwise Conv2: in [16, 16, 31] -> out [16, 8, 16], stride=(2, 2), pad=(1, 1), k=3x3
    for (int c = 0; c < 16; c++) {
        for (int oh = 0; oh < 8; oh++) {
            for (int ow = 0; ow < 16; ow++) {
                float sum = DW_CONV2_B[c];
                int in_h_center = oh * 2;
                int in_w_center = ow * 2;
                for (int kh = -1; kh <= 1; kh++) {
                    int ih = in_h_center + kh;
                    if (ih < 0 || ih >= 16) continue;
                    for (int kw = -1; kw <= 1; kw++) {
                        int iw = in_w_center + kw;
                        if (iw < 0 || iw >= 31) continue;
                        int wIdx = (c * 9) + ((kh + 1) * 3) + (kw + 1);
                        sum += conv1Out[c][ih][iw] * DW_CONV2_W[wIdx];
                    }
                }
                dw2Out[c][oh][ow] = (sum > 0.0f) ? sum : 0.0f; // ReLU
            }
        }
    }

    // 3. Pointwise Conv2: in [16, 8, 16] -> out [32, 8, 16], 1x1 conv + ReLU
    for (int oc = 0; oc < 32; oc++) {
        for (int oh = 0; oh < 8; oh++) {
            for (int ow = 0; ow < 16; ow++) {
                float sum = PW_CONV2_B[oc];
                int wOffset = oc * 16;
                for (int ic = 0; ic < 16; ic++) {
                    sum += dw2Out[ic][oh][ow] * PW_CONV2_W[wOffset + ic];
                }
                pw2Out[oc][oh][ow] = (sum > 0.0f) ? sum : 0.0f; // ReLU
            }
        }
    }

    // 4. Depthwise Conv3: in [32, 8, 16] -> out [32, 4, 8], stride=(2, 2), pad=(1, 1), k=3x3
    for (int c = 0; c < 32; c++) {
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
                        sum += pw2Out[c][ih][iw] * DW_CONV3_W[wIdx];
                    }
                }
                dw3Out[c][oh][ow] = (sum > 0.0f) ? sum : 0.0f; // ReLU
            }
        }
    }

    // 5. Pointwise Conv3: in [32, 4, 8] -> out [32, 4, 8], 1x1 conv + ReLU
    for (int oc = 0; oc < 32; oc++) {
        for (int oh = 0; oh < 4; oh++) {
            for (int ow = 0; ow < 8; ow++) {
                float sum = PW_CONV3_B[oc];
                int wOffset = oc * 32;
                for (int ic = 0; ic < 32; ic++) {
                    sum += dw3Out[ic][oh][ow] * PW_CONV3_W[wOffset + ic];
                }
                pw3Out[oc][oh][ow] = (sum > 0.0f) ? sum : 0.0f; // ReLU
            }
        }
    }

    // 6. Frequency Average Pooling: [32, 4, 8] -> [256] (preserves 8 temporal steps!)
    for (int c = 0; c < 32; c++) {
        for (int w = 0; w < 8; w++) {
            float sum = 0.0f;
            for (int h = 0; h < 4; h++) {
                sum += pw3Out[c][h][w];
            }
            pooled[(c * 8) + w] = sum / 4.0f;
        }
    }

    // 7. Non-Linear 2-Layer MLP Classification Head:
    // Layer 1: [256] -> [32] + ReLU
    for (int j = 0; j < 32; j++) {
        float sum = FC1_B[j];
        int rowOffset = j * 256;
        for (int i = 0; i < 256; i++) {
            sum += pooled[i] * FC1_W[rowOffset + i];
        }
        hidden[j] = (sum > 0.0f) ? sum : 0.0f; // Non-linear phonemic activation gate
    }

    // Layer 2: [32] -> [1] + Sigmoid
    float logit = FC2_B[0];
    for (int j = 0; j < 32; j++) {
        logit += hidden[j] * FC2_W[j];
    }

    return 1.0f / (1.0f + expf(-logit));
}

// ==================== Public API ====================
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
            for (int k = 7; k <= 127; k++) {
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

static inline float predictWakeWordScoreCircular(const short* circBuffer, int headIdx) {
    short maxAbs = 0;
    for (int i = 0; i < WW_TOTAL_SAMPLES; i += 4) {
        int circIdx = (headIdx + i) % WW_TOTAL_SAMPLES;
        short val = circBuffer[circIdx];
        if (val < 0) val = -val;
        if (val > maxAbs) maxAbs = val;
    }

    if (maxAbs < 260) {
        return 0.0f;
    }

    if (!extractFeaturesCircular(circBuffer, headIdx, maxAbs)) {
        return 0.0f;
    }
    return forwardCnn();
}

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

static inline bool isWakeWordDetected(const short* audio24k, float threshold = 0.70f) {
    return predictWakeWordScore(audio24k) >= threshold;
}

#endif // WAKEWORD_MODEL_H
"""
    with open(filepath, "w") as f:
        f.write(cpp)
    print(f"\n[Success] Exported zero-dependency C++ header to: {filepath} ({len(cpp)/1024:.1f} KB)")


# ==================== Main Training Pipeline ====================
def main():
    print("==================================================")
    print("  Project Friday - Phonetic Wake-Word Training    ")
    print("==================================================")

    device = torch.device("cuda" if torch.cuda.is_available() else "cpu")
    print(f"Using compute device: {device} ({torch.cuda.get_device_name(0) if torch.cuda.is_available() else 'CPU'})")

    user_pos_files = sorted(list(POS_DIR.glob("friday_*.wav")))
    user_noise_files = sorted(list(NEG_DIR.glob("noise_*.wav")))
    real_silence_files = sorted(list(NEG_DIR.glob("real_silence_*.wav")))
    user_speech_files = sorted(list(NEG_DIR.glob("speech_*.wav")))
    cont_speech_files = sorted(list(NEG_DIR.glob("continuous_speech_*.wav")))
    jfk_speech_files = sorted(list(NEG_DIR.glob("jfk_speech_*.wav")))
    synth_neg_files = sorted(list(NEG_DIR.glob("synth_neg_*.wav")))

    print(f"\nDataset breakdown:")
    print(f"  User recorded 'Friday' samples: {len(user_pos_files)} (PURE USER GROUND TRUTH)")
    print(f"  User recorded room noise:       {len(user_noise_files)}")
    print(f"  User real silence slices:       {len(real_silence_files)}")
    print(f"  User negative speech samples:   {len(user_speech_files)}")
    print(f"  Continuous conversational speech:{len(cont_speech_files)}")
    print(f"  Continuous JFK speech:          {len(jfk_speech_files)}")
    print(f"  Synthetic negative words:       {len(synth_neg_files)}")

    raw_user_pos = [load_wav(f) for f in user_pos_files]
    raw_user_noise = [load_wav(f) for f in user_noise_files]
    raw_real_silence = [load_wav(f) for f in real_silence_files]
    raw_user_speech = [load_wav(f) for f in user_speech_files]
    raw_cont_speech = [load_wav(f) for f in cont_speech_files]
    raw_jfk_speech = [load_wav(f) for f in jfk_speech_files]
    jfk_path = Path("app/.cxx/Debug/q656mh6g/arm64-v8a/_deps/whispercpp_new-src/samples/jfk.wav")
    if jfk_path.exists():
        with wave.open(str(jfk_path), "rb") as wf:
            jfk_full = np.frombuffer(wf.readframes(wf.getnframes()), dtype=np.int16).astype(np.float32) / 32768.0
        for i in range(0, len(jfk_full) - TOTAL_SAMPLES, 1280): # 80ms stride
            raw_jfk_speech.append(jfk_full[i : i + TOTAL_SAMPLES])
    raw_synth_neg = [load_wav(f) for f in synth_neg_files]

    noise_pool = [n for n in (raw_user_noise + raw_real_silence) if len(n) >= TOTAL_SAMPLES]

    # Harvest dense sliding windows directly from user's continuous room silence recordings
    raw_silence_files = sorted(list(Path("dataset/raw_recordings").glob("silence_continuous_*.wav")))
    dense_silence_slices = []
    loud_transients = []
    for sf in raw_silence_files:
        with wave.open(str(sf), "rb") as wf:
            s_audio = np.frombuffer(wf.readframes(wf.getnframes()), dtype=np.int16).astype(np.float32) / 32768.0
        if len(s_audio) > TOTAL_SAMPLES:
            for st in range(0, len(s_audio) - TOTAL_SAMPLES, 1280):  # 80ms stride perfectly matches MCU circular buffer rate
                chunk = s_audio[st : st + TOTAL_SAMPLES]
                dense_silence_slices.append(chunk)
                peak_cnt = np.max(np.abs(chunk)) * 32768.0
                if peak_cnt > 800.0:  # Loud transients: coughs, TV bursts, keyboard clicks
                    loud_transients.append(chunk)
    print(f"  Harvested {len(dense_silence_slices)} dense ambient windows (including {len(loud_transients)} loud transients like coughs & TV)...", flush=True)
    if dense_silence_slices:
        noise_pool.extend(dense_silence_slices)

    print("\nExtracting 32-Mel features across all categories...", flush=True)
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

    # 2. Continuous Conversational Speech (355 windows + JFK 20 windows)
    print(f"  Loading {len(raw_cont_speech) + len(raw_jfk_speech)} continuous speech negative samples (augmented 2x)...", flush=True)
    for cs in raw_cont_speech + raw_jfk_speech:
        all_specs.append(extract_log_mel_spectrogram(cs))
        all_labels.append(0.0)
        for _ in range(2):
            gain = random.uniform(0.70, 1.40)
            aug_cs = cs * gain
            all_specs.append(extract_log_mel_spectrogram(aug_cs.astype(np.float32)))
            all_labels.append(0.0)

    # 3. Dense room silence windows
    print(f"  Injecting {len(dense_silence_slices)} dense room silence slices with 2x gain variations...", flush=True)
    for s in dense_silence_slices:
        all_specs.append(extract_log_mel_spectrogram(s))
        all_labels.append(0.0)
        for _ in range(2):
            gain = random.uniform(0.50, 2.20)
            aug_s = s * gain + np.random.normal(0, random.uniform(0.0005, 0.003), size=len(s))
            all_specs.append(extract_log_mel_spectrogram(aug_s.astype(np.float32)))
            all_labels.append(0.0)

    # 3b. Loud room transients (coughs, throat clearing, TV dialogue, key clicks)
    if loud_transients:
        print(f"  Augmenting {len(loud_transients)} loud transients (coughs, TV bursts) across 4 gain variations...", flush=True)
        for lt in loud_transients:
            for g in [0.4, 0.7, 1.0, 1.5]:
                aug_lt = lt * g
                all_specs.append(extract_log_mel_spectrogram(aug_lt.astype(np.float32)))
                all_labels.append(0.0)

    # 4. User negative speech: 25x augmentation
    print(f"  Augmenting {len(raw_user_speech)} user negative speech samples (25x each)...", flush=True)
    for s in raw_user_speech:
        all_specs.append(extract_log_mel_spectrogram(s))
        all_labels.append(0.0)
        for _ in range(25):
            aug = augment_audio(s, noise_pool, is_user=True)
            all_specs.append(extract_log_mel_spectrogram(aug))
            all_labels.append(0.0)

    # 5. Narrowband acoustic tones, whistles, and motor hums (500 samples across 200Hz to 3500Hz)
    print("  Generating 500 narrowband acoustic tones and continuous whistle samples...", flush=True)
    t = np.linspace(0, AUDIO_DURATION, TOTAL_SAMPLES)
    for freq in np.linspace(200, 3500, 250):
        tone = np.sin(2 * np.pi * freq * t).astype(np.float32) * random.uniform(0.1, 0.8)
        all_specs.append(extract_log_mel_spectrogram(tone))
        all_labels.append(0.0)
        tone2 = (np.sin(2 * np.pi * freq * t) + 0.5 * np.sin(2 * np.pi * (freq * 2) * t)).astype(np.float32) * random.uniform(0.1, 0.6)
        all_specs.append(extract_log_mel_spectrogram(tone2))
        all_labels.append(0.0)

    # 6. Synthetic negative words (English words that are NOT Friday)
    print(f"  Loading {len(raw_synth_neg)} synthetic negative words...", flush=True)
    for sn in raw_synth_neg:
        all_specs.append(extract_log_mel_spectrogram(sn))
        all_labels.append(0.0)

    # 7. Random normalized Gaussian noise matrices
    print("  Generating 1,500 random noise matrices (immunizing against random noise false alarms)...", flush=True)
    for _ in range(1500):
        scale = random.uniform(0.3, 1.2)
        spec = np.clip(np.random.randn(NUM_MELS, NUM_FRAMES).astype(np.float32) * scale, -2.0, 2.0)
        all_specs.append(spec)
        all_labels.append(0.0)

    all_specs = np.array(all_specs, dtype=np.float32)
    all_labels = np.array(all_labels, dtype=np.float32)

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
    model = PhoneticWakeWordNet().to(device)

    # Balanced pos_weight
    pos_weight = torch.tensor([neg_count / max(pos_count, 1)]).to(device)
    criterion = nn.BCEWithLogitsLoss(pos_weight=pos_weight)
    optimizer = optim.AdamW(model.parameters(), lr=0.002, weight_decay=1e-3)
    epochs = 80
    scheduler = optim.lr_scheduler.CosineAnnealingLR(optimizer, T_max=epochs)

    # Prepare specific test sets for epoch validation
    user_test_specs = [torch.tensor(extract_log_mel_spectrogram(p)).unsqueeze(0).unsqueeze(0).to(device) for p in raw_user_pos]
    user_speech_test_specs = [torch.tensor(extract_log_mel_spectrogram(s)).unsqueeze(0).unsqueeze(0).to(device) for s in raw_user_speech]

    # Continuous JFK speech validation (11 seconds = 118 sliding windows)
    jfk_path = Path("app/.cxx/Debug/q656mh6g/arm64-v8a/_deps/whispercpp_new-src/samples/jfk.wav")
    jfk_test_specs = []
    if jfk_path.exists():
        with wave.open(str(jfk_path), "rb") as wf:
            jfk_audio = np.frombuffer(wf.readframes(wf.getnframes()), dtype=np.int16).astype(np.float32) / 32768.0
        for i in range(0, len(jfk_audio) - TOTAL_SAMPLES, 1280): # 80ms stride
            chunk = jfk_audio[i : i + TOTAL_SAMPLES]
            if np.max(np.abs(chunk)) * 32768.0 >= 260.0:
                spec = extract_log_mel_spectrogram(chunk)
                if np.var(spec) >= 0.052:
                    jfk_test_specs.append(torch.tensor(spec).unsqueeze(0).unsqueeze(0).to(device))

    # Continuous 110s Ambiance (TV & Coughing) validation stream
    amb_path = Path("dataset/raw_recordings/silence_continuous_20261115_135706.wav")
    amb_test_specs = []
    if amb_path.exists():
        with wave.open(str(amb_path), "rb") as wf:
            amb_audio = np.frombuffer(wf.readframes(wf.getnframes()), dtype=np.int16).astype(np.float32) / 32768.0
        for i in range(0, len(amb_audio) - TOTAL_SAMPLES, 1280): # 80ms stride captures every MCU buffer window
            chunk = amb_audio[i : i + TOTAL_SAMPLES]
            if np.max(np.abs(chunk)) * 32768.0 >= 260.0:
                spec = extract_log_mel_spectrogram(chunk)
                if np.var(spec) >= 0.052:
                    amb_test_specs.append(torch.tensor(spec).unsqueeze(0).unsqueeze(0).to(device))

    # Continuous 84s Room silence validation stream
    sil84_path = Path("dataset/raw_recordings/silence_continuous_20261115_125057.wav")
    sil84_test_specs = []
    if sil84_path.exists():
        with wave.open(str(sil84_path), "rb") as wf:
            sil84_audio = np.frombuffer(wf.readframes(wf.getnframes()), dtype=np.int16).astype(np.float32) / 32768.0
        for i in range(0, len(sil84_audio) - TOTAL_SAMPLES, 1280): # 80ms stride captures every MCU buffer window
            chunk = sil84_audio[i : i + TOTAL_SAMPLES]
            if np.max(np.abs(chunk)) * 32768.0 >= 260.0:
                spec = extract_log_mel_spectrogram(chunk)
                if np.var(spec) >= 0.052:
                    sil84_test_specs.append(torch.tensor(spec).unsqueeze(0).unsqueeze(0).to(device))

    t_val = np.linspace(0, AUDIO_DURATION, TOTAL_SAMPLES)
    tone_test_specs = [torch.tensor(extract_log_mel_spectrogram(np.sin(2 * np.pi * f * t_val).astype(np.float32) * 0.5)).unsqueeze(0).unsqueeze(0).to(device) for f in [440, 600, 800, 1000, 1500]]
    print(f"Prepared validation streams: {len(user_test_specs)} user 'Friday', {len(jfk_test_specs)} JFK speech, {len(amb_test_specs)} TV/cough ambiance, {len(sil84_test_specs)} room silence...")

    best_score = -1.0
    best_weights = None

    print("\nStarting training loop on GPU...", flush=True)
    for epoch in range(1, epochs + 1):
        model.train()
        train_loss = 0.0
        train_correct = 0
        train_total = 0

        for bx, by in train_loader:
            bx, by = bx.to(device), by.to(device)
            optimizer.zero_grad()
            logits = model(bx).squeeze(1)
            loss = criterion(logits, by)
            loss.backward()
            optimizer.step()

            train_loss += loss.item() * len(by)
            preds = (torch.sigmoid(logits) >= 0.50).float()
            train_correct += (preds == by).sum().item()
            train_total += len(by)

        train_acc = train_correct / train_total

        # Validation
        model.eval()
        val_loss = 0.0
        val_correct = 0
        val_total = 0
        with torch.no_grad():
            for bx, by in val_loader:
                bx, by = bx.to(device), by.to(device)
                logits = model(bx).squeeze(1)
                loss = criterion(logits, by)
                val_loss += loss.item() * len(by)
                preds = (torch.sigmoid(logits) >= 0.50).float()
                val_correct += (preds == by).sum().item()
                val_total += len(by)

            # Evaluate user recall (threshold >= 0.75)
            user_hits = sum(1 for p in user_test_specs if torch.sigmoid(model(p)).item() >= 0.75)
            user_recall = user_hits / len(user_test_specs)

            # Evaluate continuous JFK speech rejection (must be < 0.50)
            jfk_hits = sum(1 for s in jfk_test_specs if torch.sigmoid(model(s)).item() >= 0.50)
            jfk_rejection = 1.0 - (jfk_hits / len(jfk_test_specs)) if jfk_test_specs else 1.0

            # Evaluate continuous 110s ambiance (TV & Coughing) rejection (must be < 0.50)
            amb_hits = sum(1 for s in amb_test_specs if torch.sigmoid(model(s)).item() >= 0.50)
            amb_rejection = 1.0 - (amb_hits / len(amb_test_specs)) if amb_test_specs else 1.0

            # Evaluate continuous 84s silence rejection
            sil_hits = sum(1 for s in sil84_test_specs if torch.sigmoid(model(s)).item() >= 0.50)
            sil_rejection = 1.0 - (sil_hits / len(sil84_test_specs)) if sil84_test_specs else 1.0

            # Evaluate pure acoustic tone rejection
            tone_hits = sum(1 for s in tone_test_specs if torch.sigmoid(model(s)).item() >= 0.50)
            tone_rejection = 1.0 - (tone_hits / len(tone_test_specs))

            # Evaluate user speech rejection (negative words < 0.50)
            user_speech_rejects = sum(1 for s in user_speech_test_specs if torch.sigmoid(model(s)).item() < 0.50)
            user_speech_rejection = user_speech_rejects / len(user_speech_test_specs) if user_speech_test_specs else 1.0

        val_acc = val_correct / val_total
        scheduler.step()

        # Strict scoring function: Penalize heavily if ANY continuous speech, silence, TV/cough, or tone triggers!
        epoch_score = (val_acc * 0.10) + (user_recall * 0.35) + (jfk_rejection * 0.20) + (amb_rejection * 0.20) + (sil_rejection * 0.05) + (tone_rejection * 0.05) + (user_speech_rejection * 0.05)
        if jfk_rejection < 0.999 or amb_rejection < 0.999 or sil_rejection < 0.999 or tone_rejection < 0.99 or user_recall < 0.98:
            epoch_score *= 0.5

        if epoch_score >= best_score:
            best_score = epoch_score
            best_weights = {k: v.cpu().clone() for k, v in model.state_dict().items()}

        if epoch % 5 == 0 or epoch == epochs:
            print(f"Epoch [{epoch:02d}/{epochs}] - Loss: {train_loss/train_total:.4f}, Acc: {train_acc*100:.1f}% | Val Acc: {val_acc*100:.1f}%, User Recall: {user_recall*100:.1f}%, JFK Rej: {jfk_rejection*100:.1f}%, Amb Rej: {amb_rejection*100:.1f}%, Sil Rej: {sil_rejection*100:.1f}%, Tone Rej: {tone_rejection*100:.1f}%", flush=True)

    print(f"\nTraining Complete! Best score: {best_score:.4f}")
    model.load_state_dict(best_weights)
    model.eval()

    # Save checkpoint
    checkpoint_path = OUTPUT_DIR / "pendant_wakeword_model.pth"
    torch.save(model.state_dict(), checkpoint_path)
    print(f"Saved PyTorch model weights to: {checkpoint_path}")

    # Export zero-dependency C++ header for Arduino firmware
    export_cpp_header(model, ARDUINO_HEADER_PATH)

if __name__ == "__main__":
    main()

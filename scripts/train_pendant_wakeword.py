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

    log_mel = np.zeros((NUM_MELS, NUM_FRAMES), dtype=np.float32)
    for frame_idx in range(NUM_FRAMES):
        start = frame_idx * HOP_LEN
        frame = samples_float[start : start + FRAME_LEN] * HANNING_WINDOW
        fft_mag = np.abs(rfft(frame))
        mel_energy = np.dot(MEL_FBANK, fft_mag)
        log_mel[:, frame_idx] = np.log10(mel_energy + 1e-5)

    mean = np.mean(log_mel)
    std = np.std(log_mel) + 1e-6
    normalized = (log_mel - mean) / (2.5 * std)
    return np.clip(normalized, -2.0, 2.0).astype(np.float32)


# ==================== Audio Loading & Augmentation ====================
def load_wav(path):
    with wave.open(str(path), "rb") as wf:
        n_frames = wf.getnframes()
        frames = wf.readframes(n_frames)
        samples = np.frombuffer(frames, dtype=np.int16).astype(np.float32) / 32768.0
        return samples

def augment_audio(samples, noise_pool):
    shift = random.randint(-2400, 2400)
    if shift > 0:
        shifted = np.pad(samples, (shift, 0))[:TOTAL_SAMPLES]
    elif shift < 0:
        shifted = np.pad(samples[abs(shift):], (0, abs(shift)))[:TOTAL_SAMPLES]
    else:
        shifted = samples.copy()

    gain = random.uniform(0.65, 1.35)
    augmented = shifted * gain

    if noise_pool and random.random() < 0.6:
        noise = random.choice(noise_pool)
        noise_start = random.randint(0, max(0, len(noise) - TOTAL_SAMPLES))
        noise_chunk = noise[noise_start : noise_start + TOTAL_SAMPLES]
        if len(noise_chunk) < TOTAL_SAMPLES:
            noise_chunk = np.pad(noise_chunk, (0, TOTAL_SAMPLES - len(noise_chunk)))
        augmented = augmented + noise_chunk * random.uniform(0.05, 0.20)

    augmented += np.random.normal(0, random.uniform(0.001, 0.006), size=augmented.shape)
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

        # Global Average Pooling: [16, 4, 8] -> [16]
        self.pool = nn.AdaptiveAvgPool2d((1, 1))

        # Linear classifier: [16] -> [1]
        self.fc = nn.Linear(16, 1)

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
static float pooled[16];

// ==================== Feature Extraction ====================
static void extractFeatures(const short* rawAudio) {
    float sumTotal = 0.0f;
    float sumSq = 0.0f;
    int totalElements = WW_NUM_MELS * WW_NUM_FRAMES;

    for (int f = 0; f < WW_NUM_FRAMES; f++) {
        int start = f * WW_HOP_LEN;
        for (int i = 0; i < WW_FRAME_LEN; i++) {
            int audioIdx = start + i;
            float s = (audioIdx < WW_TOTAL_SAMPLES) ? (rawAudio[audioIdx] / 32768.0f) : 0.0f;
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
    float std = sqrtf(fmaxf(variance, 1e-6f));
    float normScale = 1.0f / (2.5f * std);

    for (int m = 0; m < WW_NUM_MELS; m++) {
        for (int f = 0; f < WW_NUM_FRAMES; f++) {
            float val = (melSpectrogram[m][f] - mean) * normScale;
            if (val > 2.0f) val = 2.0f;
            if (val < -2.0f) val = -2.0f;
            melSpectrogram[m][f] = val;
        }
    }
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
                        if (ih < 0 || ih >= 8) continue;
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

    // 5. Global Average Pooling: [16, 4, 8] -> [16]
    for (int c = 0; c < 16; c++) {
        float sum = 0.0f;
        for (int h = 0; h < 4; h++) {
            for (int w = 0; w < 8; w++) {
                sum += pwConvOut[c][h][w];
            }
        }
        pooled[c] = sum / 32.0f;
    }

    // 6. Dense Linear Classifier: [16] -> [1] + Sigmoid
    float logit = FC_B[0];
    for (int c = 0; c < 16; c++) {
        logit += pooled[c] * FC_W[c];
    }

    return 1.0f / (1.0f + expf(-logit));
}

// ==================== Public API ====================
/**
 * Runs the TinyML wake-word model on a 1.5-second buffer (24,000 samples @ 16kHz).
 * Returns the probability (0.0 to 1.0) that "Friday" was spoken.
 */
static inline float predictWakeWordScore(const short* audio24k) {
    extractFeatures(audio24k);
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

    pos_files = sorted(list(POS_DIR.glob("*.wav")))
    neg_files = sorted(list(NEG_DIR.glob("*.wav")))

    if not pos_files or not neg_files:
        print("Error: No samples found in dataset directory!", flush=True)
        return

    print(f"Loading {len(pos_files)} positive samples ('Friday')...", flush=True)
    print(f"Loading {len(neg_files)} negative samples (noise & speech)...", flush=True)

    raw_pos = [load_wav(f) for f in pos_files]
    raw_neg = [load_wav(f) for f in neg_files]
    noise_pool = [load_wav(f) for f in neg_files if "noise" in f.name]

    # Pre-generate augmented spectrogram dataset in memory
    print("Extracting features with data augmentation...", flush=True)
    all_specs = []
    all_labels = []

    # Positives + 14x augmentations
    for p in raw_pos:
        all_specs.append(extract_log_mel_spectrogram(p))
        all_labels.append(1.0)
        for _ in range(14):
            aug = augment_audio(p, noise_pool)
            all_specs.append(extract_log_mel_spectrogram(aug))
            all_labels.append(1.0)

    # Negatives + 2x augmentations
    for n in raw_neg:
        all_specs.append(extract_log_mel_spectrogram(n))
        all_labels.append(0.0)
        for _ in range(2):
            aug = augment_audio(n, noise_pool)
            all_specs.append(extract_log_mel_spectrogram(aug))
            all_labels.append(0.0)

    all_specs = np.array(all_specs, dtype=np.float32)  # [N, 16, 62]
    all_labels = np.array(all_labels, dtype=np.float32)  # [N]

    print(f"Total dataset: {len(all_specs)} samples ({np.sum(all_labels == 1.0):.0f} positive, {np.sum(all_labels == 0.0):.0f} negative)", flush=True)

    # Shuffle and Split 80/20
    indices = np.arange(len(all_specs))
    np.random.shuffle(indices)

    split = int(len(indices) * 0.8)
    train_idx, val_idx = indices[:split], indices[split:]

    train_x = torch.tensor(all_specs[train_idx]).unsqueeze(1)
    train_y = torch.tensor(all_labels[train_idx])
    val_x = torch.tensor(all_specs[val_idx]).unsqueeze(1)
    val_y = torch.tensor(all_labels[val_idx])

    train_loader = DataLoader(TensorDataset(train_x, train_y), batch_size=32, shuffle=True)
    val_loader = DataLoader(TensorDataset(val_x, val_y), batch_size=32, shuffle=False)

    # Build Model
    model = TinyWakeWordCnn()
    total_params = sum(p.numel() for p in model.parameters() if p.requires_grad)
    print(f"Model Architecture: TinyWakeWordCnn ({total_params} parameters, ~{total_params * 4 / 1024:.2f} KB)", flush=True)

    criterion = nn.BCEWithLogitsLoss()
    optimizer = optim.Adam(model.parameters(), lr=0.002, weight_decay=2e-4)

    epochs = 35
    best_val_acc = 0.0
    best_weights = None

    print("\nStarting training loop...", flush=True)
    for epoch in range(1, epochs + 1):
        model.train()
        train_loss = 0.0
        train_correct = 0
        train_total = 0

        for specs, labels in train_loader:
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
                logits = model(specs).squeeze(-1)
                loss = criterion(logits, labels)
                val_loss += loss.item() * len(labels)
                preds = (torch.sigmoid(logits) >= 0.5).float()
                val_correct += (preds == labels).sum().item()
                val_total += len(labels)

        val_acc = val_correct / val_total

        if val_acc >= best_val_acc:
            best_val_acc = val_acc
            best_weights = {k: v.cpu().clone() for k, v in model.state_dict().items()}

        if epoch % 5 == 0 or epoch == epochs or val_acc == 1.0:
            print(f"Epoch [{epoch:02d}/{epochs}] - Train Loss: {train_loss/train_total:.4f}, Train Acc: {train_acc*100:.1f}% | Val Loss: {val_loss/val_total:.4f}, Val Acc: {val_acc*100:.1f}%", flush=True)

    print(f"\nTraining Complete! Best Validation Accuracy: {best_val_acc * 100:.1f}%", flush=True)

    model.load_state_dict(best_weights)
    model.eval()

    # Full Evaluation on original un-augmented recorded samples
    print("\n--- Final Evaluation on Original Recorded Samples ---", flush=True)
    pos_preds = []
    neg_preds = []
    with torch.no_grad():
        for p in raw_pos:
            spec = torch.tensor(extract_log_mel_spectrogram(p)).unsqueeze(0).unsqueeze(0)
            prob = torch.sigmoid(model(spec)).item()
            pos_preds.append(prob)
        for n in raw_neg:
            spec = torch.tensor(extract_log_mel_spectrogram(n)).unsqueeze(0).unsqueeze(0)
            prob = torch.sigmoid(model(spec)).item()
            neg_preds.append(prob)

    pos_correct = sum(1 for p in pos_preds if p >= 0.6)
    neg_correct = sum(1 for p in neg_preds if p < 0.4)

    print(f"Positive ('Friday') Accuracy: {pos_correct}/{len(pos_preds)} ({pos_correct/len(pos_preds)*100:.1f}%) | Avg Confidence: {np.mean(pos_preds):.3f}", flush=True)
    print(f"Negative (Noise/Speech) Accuracy: {neg_correct}/{len(neg_preds)} ({neg_correct/len(neg_preds)*100:.1f}%) | Avg Confidence: {np.mean(neg_preds):.3f}", flush=True)

    # Save artifacts
    pth_path = OUTPUT_DIR / "pendant_wakeword_model.pth"
    torch.save(best_weights, pth_path)
    print(f"[✓] PyTorch model saved to: {pth_path}", flush=True)

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

    ARDUINO_HEADER_PATH.parent.mkdir(parents=True, exist_ok=True)
    export_cpp_header(model, ARDUINO_HEADER_PATH)

    print("\nModel is fully compiled into C++ and ready to run on Seeed Studio XIAO nRF52840 Sense!", flush=True)


if __name__ == "__main__":
    main()

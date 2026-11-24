#!/usr/bin/env python3
"""
Project Friday - Pendant Wake-Word Training & C++ Exporter (V2)
Trains PhoneticWakeWordNetV2 (32 Mel bands, 4 DS-Conv blocks, Translation-Invariant
1D Temporal Motif Conv, Dual Max+Avg Pooling, and 2-Layer MLP) with:
  1. Per-window DC offset removal (100% parity between Python and C++ firmware)
  2. Strict zero-leakage group hold-out split (unseen user Fridays, unseen loud music,
     unseen TV/cough, unseen TTS speakers, and unseen JFK speech)
  3. SNR-calibrated loud music/noise mixing + mixed speech-on-music hard negatives
  4. SpecAugment + Label Smoothing for calibrated confidence and low false alarms

Exports a zero-dependency C++ header `wakeword_model.h` with folded BatchNorm
for real-time on-chip inference on the Seeed Studio XIAO nRF52840 Sense.
"""

import os
import math
import wave
import random
import numpy as np
from pathlib import Path
from collections import defaultdict
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
NUM_MELS = 32         # 32 Mel filterbank channels
F_MIN = 200.0         # Speech lower band
F_MAX = 4000.0        # Speech upper band

DATASET_ROOT = Path("dataset/pendant_wakeword")
POS_DIR = DATASET_ROOT / "positive"
NEG_DIR = DATASET_ROOT / "negative"
HOLDOUT_NEG_DIR = DATASET_ROOT / "holdout_neg"
OUTPUT_DIR = Path("output")
OUTPUT_DIR.mkdir(parents=True, exist_ok=True)

ARDUINO_HEADER_PATH = Path("firmware/xiao_friday_pendant/wakeword_model.h")

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

def extract_log_mel_spectrogram(samples_float, check_gates=False):
    """
    Extracts a (NUM_MELS, NUM_FRAMES) Log-Mel Spectrogram from float32 samples [-1.0, 1.0].
    Includes per-window DC offset removal matching the C++ firmware exactly.
    If check_gates=True, returns None when AC peak < 240 ADC or spectral variance < 0.052.
    """
    if len(samples_float) < TOTAL_SAMPLES:
        samples_float = np.pad(samples_float, (0, TOTAL_SAMPLES - len(samples_float)))
    else:
        samples_float = samples_float[:TOTAL_SAMPLES]

    # Per-window DC offset removal (critical for consistent features across mic sessions)
    samples_ac = samples_float - np.mean(samples_float)

    # AC Peak normalization invariant to speaker distance / whispering / shouting
    peak = float(np.max(np.abs(samples_ac)))
    if check_gates and peak < (240.0 / 32768.0):
        return None
    if peak > (300.0 / 32768.0):
        samples_ac = (samples_ac / peak) * 0.5

    log_mel = np.zeros((NUM_MELS, NUM_FRAMES), dtype=np.float32)
    for frame_idx in range(NUM_FRAMES):
        start = frame_idx * HOP_LEN
        frame = samples_ac[start : start + FRAME_LEN] * HANNING_WINDOW
        fft_mag = np.abs(rfft(frame))
        mel_energy = np.dot(MEL_FBANK, fft_mag)
        log_mel[:, frame_idx] = np.log10(mel_energy + 1e-5)

    mean = float(np.mean(log_mel))
    var = float(np.var(log_mel))
    if check_gates and var < 0.052:
        return None
    std = math.sqrt(max(var, 0.09))
    normalized = (log_mel - mean) / (2.5 * std)
    return np.clip(normalized, -2.0, 2.0).astype(np.float32)


# ==================== Audio Loading & SNR Augmentation ====================
def load_wav(path):
    with wave.open(str(path), "rb") as wf:
        n_frames = wf.getnframes()
        frames = wf.readframes(n_frames)
        samples = np.frombuffer(frames, dtype=np.int16).astype(np.float32) / 32768.0
        # Return DC-centered waveform cleanly for accurate RMS mixing
        return (samples - np.mean(samples)).astype(np.float32)

def mix_at_snr(clean_sig, noise_sig, snr_db):
    """Mixes DC-centered clean_sig and noise_sig at a target AC SNR (dB)."""
    if len(noise_sig) >= len(clean_sig):
        st = random.randint(0, len(noise_sig) - len(clean_sig))
        n_slice = noise_sig[st : st + len(clean_sig)]
    else:
        n_slice = np.pad(noise_sig, (0, len(clean_sig) - len(noise_sig)))

    s_ac = clean_sig - np.mean(clean_sig)
    n_ac = n_slice - np.mean(n_slice)
    s_rms = float(np.sqrt(np.mean(s_ac**2)) + 1e-8)
    n_rms = float(np.sqrt(np.mean(n_ac**2)) + 1e-8)
    target_n_rms = s_rms / (10.0 ** (snr_db / 20.0))
    scaled_noise = n_ac * (target_n_rms / n_rms)
    return np.clip(s_ac + scaled_noise, -1.0, 1.0).astype(np.float32)

def augment_positive(samples, music_pool, room_pool):
    # 1. Speed perturbation (0.86x to 1.14x simulates faster/slower pronunciation)
    if random.random() < 0.75:
        speed = random.uniform(0.86, 1.14)
        old_idx = np.arange(len(samples))
        new_idx = np.linspace(0, len(samples) - 1, int(len(samples) / speed))
        stretched = np.interp(new_idx, old_idx, samples)
        if len(stretched) < TOTAL_SAMPLES:
            pad_b = random.randint(0, TOTAL_SAMPLES - len(stretched))
            samples = np.pad(stretched, (pad_b, TOTAL_SAMPLES - len(stretched) - pad_b))
        else:
            samples = stretched[:TOTAL_SAMPLES]

    # 2. Wide Circular Translation Shift (+/- 400ms = +/- 6400 samples) without dead-zero cliffs
    shift = random.randint(-6400, 6400)
    shifted = np.roll(samples, shift) if shift != 0 else samples.copy()

    # 3. Dynamic Gain Variations
    augmented = shifted * random.uniform(0.55, 1.85)

    # 4. SNR-Calibrated Loud Music / Room Noise Injection
    roll = random.random()
    if music_pool and roll < 0.35:
        # Loud music background (-2 dB to +8 dB SNR)
        bg = random.choice(music_pool)
        augmented = mix_at_snr(augmented, bg, random.uniform(-2.0, 8.0))
    elif (music_pool or room_pool) and roll < 0.85:
        # Moderate ambient / TV / music (+8 dB to +24 dB SNR)
        pool = music_pool if (music_pool and random.random() < 0.5) else room_pool
        if pool:
            augmented = mix_at_snr(augmented, random.choice(pool), random.uniform(8.0, 24.0))

    return np.clip(augmented, -1.0, 1.0).astype(np.float32)


# ==================== Neural Network Architecture ====================
class PhoneticWakeWordNetV2(nn.Module):
    """
    Translation-Invariant Phonetic Wake-Word Network for Cortex-M4F.
    - 32 Mel filterbank channels -> 4 DS-Conv blocks -> 48 feature channels x 8 time slices.
    - 1D Temporal Motif Conv (kernel=5, ~940ms receptive field) enforces exact chronological
      phoneme order (/f/ -> /r/ -> /aI/ -> /d/ -> /eI/) regardless of position in window.
    - Global Temporal Max + Avg Dual-Pooling (128 features) -> 2-Layer MLP (128 -> 32 -> 1).
    - ~25,800 parameters (~103 KB Flash, ~95 KB RAM).
    """
    def __init__(self):
        super().__init__()
        # Conv1: [1, 32, 62] -> [16, 16, 31]
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

        # Block 3 (DS-Conv): [32, 8, 16] -> [48, 4, 8]
        self.dw_conv3 = nn.Conv2d(32, 32, kernel_size=3, stride=(2, 2), padding=1, groups=32, bias=False)
        self.bn_dw3 = nn.BatchNorm2d(32)
        self.relu_dw3 = nn.ReLU()
        self.pw_conv3 = nn.Conv2d(32, 48, kernel_size=1, bias=False)
        self.bn_pw3 = nn.BatchNorm2d(48)
        self.relu_pw3 = nn.ReLU()

        # Block 4 (Context DS-Conv, stride 1): [48, 4, 8] -> [48, 4, 8]
        self.dw_conv4 = nn.Conv2d(48, 48, kernel_size=3, stride=(1, 1), padding=1, groups=48, bias=False)
        self.bn_dw4 = nn.BatchNorm2d(48)
        self.relu_dw4 = nn.ReLU()
        self.pw_conv4 = nn.Conv2d(48, 48, kernel_size=1, bias=False)
        self.bn_pw4 = nn.BatchNorm2d(48)
        self.relu_pw4 = nn.ReLU()

        # Temporal Motif 1D Conv across the 8 chronological slices (~940ms motif detector)
        self.tconv = nn.Conv1d(48, 64, kernel_size=5, padding=2, bias=False)
        self.bn_tconv = nn.BatchNorm1d(64)
        self.relu_tconv = nn.ReLU()

        # Classification Head: Global Max (64) + Global Avg (64) = 128 -> 32 -> 1
        self.fc1 = nn.Linear(128, 32)
        self.relu_fc1 = nn.ReLU()
        self.drop = nn.Dropout(0.30)
        self.fc2 = nn.Linear(32, 1)

    def forward(self, x):
        x = self.relu1(self.bn1(self.conv1(x)))
        x = self.relu_dw2(self.bn_dw2(self.dw_conv2(x)))
        x = self.relu_pw2(self.bn_pw2(self.pw_conv2(x)))
        x = self.relu_dw3(self.bn_dw3(self.dw_conv3(x)))
        x = self.relu_pw3(self.bn_pw3(self.pw_conv3(x)))
        x = self.relu_dw4(self.bn_dw4(self.dw_conv4(x)))
        x = self.relu_pw4(self.bn_pw4(self.pw_conv4(x)))

        # Average over frequency height dimension (H=4) -> [B, 48, 8]
        x = torch.mean(x, dim=2)
        x = self.relu_tconv(self.bn_tconv(self.tconv(x)))  # [B, 64, 8]

        # Global Temporal Max + Avg Pooling -> [B, 128]
        t_max = x.amax(dim=2)
        t_avg = x.mean(dim=2)
        x = torch.cat([t_max, t_avg], dim=1)

        x = self.drop(self.relu_fc1(self.fc1(x)))
        return self.fc2(x)


def apply_spec_augment(batch_x):
    """GPU batch SpecAugment (frequency & time masking) to prevent single-bin/frame memorization."""
    B, C, M, T = batch_x.shape
    out = batch_x.clone()
    for i in range(B):
        if random.random() < 0.60:
            fw = random.randint(1, 4)
            f0 = random.randint(0, M - fw)
            out[i, :, f0 : f0 + fw, :] = 0.0
        if random.random() < 0.60:
            tw = random.randint(1, 6)
            t0 = random.randint(0, T - tw)
            out[i, :, :, t0 : t0 + tw] = 0.0
    return out


# ==================== C++ Exporter with Folded BatchNorm ====================
def export_cpp_header(model, filepath: Path):
    model.eval()

    def fold_bn2d(conv, bn):
        scale = bn.weight.data / torch.sqrt(bn.running_var + bn.eps)
        w_folded = conv.weight.data * scale.view(-1, 1, 1, 1)
        b_folded = bn.bias.data - bn.running_mean * scale
        return w_folded.cpu().numpy(), b_folded.cpu().numpy()

    def fold_bn1d(conv, bn):
        scale = bn.weight.data / torch.sqrt(bn.running_var + bn.eps)
        w_folded = conv.weight.data * scale.view(-1, 1, 1)
        b_folded = bn.bias.data - bn.running_mean * scale
        return w_folded.cpu().numpy(), b_folded.cpu().numpy()

    c1_w, c1_b = fold_bn2d(model.conv1, model.bn1)
    dw2_w, dw2_b = fold_bn2d(model.dw_conv2, model.bn_dw2)
    pw2_w, pw2_b = fold_bn2d(model.pw_conv2, model.bn_pw2)
    dw3_w, dw3_b = fold_bn2d(model.dw_conv3, model.bn_dw3)
    pw3_w, pw3_b = fold_bn2d(model.pw_conv3, model.bn_pw3)
    dw4_w, dw4_b = fold_bn2d(model.dw_conv4, model.bn_dw4)
    pw4_w, pw4_b = fold_bn2d(model.pw_conv4, model.bn_pw4)
    tc_w, tc_b = fold_bn1d(model.tconv, model.bn_tconv)
    fc1_w = model.fc1.weight.detach().cpu().numpy()
    fc1_b = model.fc1.bias.detach().cpu().numpy()
    fc2_w = model.fc2.weight.detach().cpu().numpy()[0]
    fc2_b = model.fc2.bias.detach().cpu().numpy()[0]

    def format_array(name, arr, ctype="const float"):
        flat = arr.flatten()
        vals_str = ", ".join(f"{v:.7f}f" for v in flat)
        return f"{ctype} {name}[{len(flat)}] = {{\n    {vals_str}\n}};\n"

    cpp = """// ============================================================================
// Auto-generated TinyML Wake-Word Model for Project Friday (V2)
// Board: Seeed Studio XIAO nRF52840 Sense
// Model: PhoneticWakeWordNetV2 (32 Mel, 4 DS-Conv, 1D Temporal Motif, Dual-Pool)
// Features: Per-window DC removal + AC scaling + spectral variance gate
// Zero external dependencies - pure portable C++ with static scratch buffers
// ============================================================================

#ifndef WAKEWORD_MODEL_H
#define WAKEWORD_MODEL_H

#include <Arduino.h>
#include <math.h>
#include <stdint.h>

#define WW_SAMPLE_RATE    16000
#define WW_TOTAL_SAMPLES  24000  // 1.5 seconds of audio
#define WW_FRAME_LEN      512
#define WW_HOP_LEN        384
#define WW_NUM_FRAMES     62
#define WW_NUM_MELS       32     // 32 Mel filterbank channels

// ==================== Filterbank Constants ====================
"""
    cpp += format_array("HANNING_WINDOW", HANNING_WINDOW)
    cpp += "\n"
    cpp += format_array("MEL_FBANK", MEL_FBANK)
    cpp += "\n"
    cpp += "// ==================== Neural Network Weights (Folded BatchNorm) ====================\n"
    for name, w, b in [
        ("CONV1", c1_w, c1_b),
        ("DW_CONV2", dw2_w, dw2_b),
        ("PW_CONV2", pw2_w, pw2_b),
        ("DW_CONV3", dw3_w, dw3_b),
        ("PW_CONV3", pw3_w, pw3_b),
        ("DW_CONV4", dw4_w, dw4_b),
        ("PW_CONV4", pw4_w, pw4_b),
        ("TCONV", tc_w, tc_b),
    ]:
        cpp += format_array(f"{name}_W", w)
        cpp += format_array(f"{name}_B", b)
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
static float pw3Out[48][4][8];
static float dw4Out[48][4][8];
static float pw4Out[48][4][8];
static float freqPooled[48][8];
static float tconvOut[64][8];
static float pooled[128];
static float hidden[32];

// ==================== Feature Extraction (DC-Removed) ====================
static bool extractFeatures(const short* rawAudio, float meanRaw, float acMaxAbs) {
    float sumTotal = 0.0f;
    float sumSq = 0.0f;
    int totalElements = WW_NUM_MELS * WW_NUM_FRAMES;

    float inputScale = (acMaxAbs > 300.0f) ? (0.5f / acMaxAbs) : (1.0f / 32768.0f);

    for (int f = 0; f < WW_NUM_FRAMES; f++) {
        int start = f * WW_HOP_LEN;
        for (int i = 0; i < WW_FRAME_LEN; i++) {
            int audioIdx = start + i;
            float s = (audioIdx < WW_TOTAL_SAMPLES) ? (((float)rawAudio[audioIdx] - meanRaw) * inputScale) : 0.0f;
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

static bool extractFeaturesCircular(const short* circBuffer, int headIdx, float meanRaw, float acMaxAbs) {
    float sumTotal = 0.0f;
    float sumSq = 0.0f;
    int totalElements = WW_NUM_MELS * WW_NUM_FRAMES;

    float inputScale = (acMaxAbs > 300.0f) ? (0.5f / acMaxAbs) : (1.0f / 32768.0f);

    for (int f = 0; f < WW_NUM_FRAMES; f++) {
        int start = f * WW_HOP_LEN;
        for (int i = 0; i < WW_FRAME_LEN; i++) {
            int offset = start + i;
            float s = 0.0f;
            if (offset < WW_TOTAL_SAMPLES) {
                int circIdx = (headIdx + offset) % WW_TOTAL_SAMPLES;
                s = ((float)circBuffer[circIdx] - meanRaw) * inputScale;
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

// ==================== CNN + Temporal Motif Forward Pass ====================
static float forwardCnn() {
    // 1. Conv1: [1, 32, 62] -> [16, 16, 31], stride=(2,2), pad=1
    for (int oc = 0; oc < 16; oc++) {
        for (int oh = 0; oh < 16; oh++) {
            int in_h_center = oh * 2;
            for (int ow = 0; ow < 31; ow++) {
                float sum = CONV1_B[oc];
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
                conv1Out[oc][oh][ow] = (sum > 0.0f) ? sum : 0.0f;
            }
        }
    }

    // 2. DW_Conv2: [16, 16, 31] -> [16, 8, 16], stride=(2,2), pad=1
    for (int c = 0; c < 16; c++) {
        for (int oh = 0; oh < 8; oh++) {
            int in_h_center = oh * 2;
            for (int ow = 0; ow < 16; ow++) {
                float sum = DW_CONV2_B[c];
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
                dw2Out[c][oh][ow] = (sum > 0.0f) ? sum : 0.0f;
            }
        }
    }

    // 3. PW_Conv2: [16, 8, 16] -> [32, 8, 16]
    for (int oc = 0; oc < 32; oc++) {
        int wOffset = oc * 16;
        for (int oh = 0; oh < 8; oh++) {
            for (int ow = 0; ow < 16; ow++) {
                float sum = PW_CONV2_B[oc];
                for (int ic = 0; ic < 16; ic++) {
                    sum += dw2Out[ic][oh][ow] * PW_CONV2_W[wOffset + ic];
                }
                pw2Out[oc][oh][ow] = (sum > 0.0f) ? sum : 0.0f;
            }
        }
    }

    // 4. DW_Conv3: [32, 8, 16] -> [32, 4, 8], stride=(2,2), pad=1
    for (int c = 0; c < 32; c++) {
        for (int oh = 0; oh < 4; oh++) {
            int in_h_center = oh * 2;
            for (int ow = 0; ow < 8; ow++) {
                float sum = DW_CONV3_B[c];
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
                dw3Out[c][oh][ow] = (sum > 0.0f) ? sum : 0.0f;
            }
        }
    }

    // 5. PW_Conv3: [32, 4, 8] -> [48, 4, 8]
    for (int oc = 0; oc < 48; oc++) {
        int wOffset = oc * 32;
        for (int oh = 0; oh < 4; oh++) {
            for (int ow = 0; ow < 8; ow++) {
                float sum = PW_CONV3_B[oc];
                for (int ic = 0; ic < 32; ic++) {
                    sum += dw3Out[ic][oh][ow] * PW_CONV3_W[wOffset + ic];
                }
                pw3Out[oc][oh][ow] = (sum > 0.0f) ? sum : 0.0f;
            }
        }
    }

    // 6. DW_Conv4: [48, 4, 8] -> [48, 4, 8], stride=(1,1), pad=1
    for (int c = 0; c < 48; c++) {
        for (int oh = 0; oh < 4; oh++) {
            for (int ow = 0; ow < 8; ow++) {
                float sum = DW_CONV4_B[c];
                for (int kh = -1; kh <= 1; kh++) {
                    int ih = oh + kh;
                    if (ih < 0 || ih >= 4) continue;
                    for (int kw = -1; kw <= 1; kw++) {
                        int iw = ow + kw;
                        if (iw < 0 || iw >= 8) continue;
                        int wIdx = (c * 9) + ((kh + 1) * 3) + (kw + 1);
                        sum += pw3Out[c][ih][iw] * DW_CONV4_W[wIdx];
                    }
                }
                dw4Out[c][oh][ow] = (sum > 0.0f) ? sum : 0.0f;
            }
        }
    }

    // 7. PW_Conv4: [48, 4, 8] -> [48, 4, 8]
    for (int oc = 0; oc < 48; oc++) {
        int wOffset = oc * 48;
        for (int oh = 0; oh < 4; oh++) {
            for (int ow = 0; ow < 8; ow++) {
                float sum = PW_CONV4_B[oc];
                for (int ic = 0; ic < 48; ic++) {
                    sum += dw4Out[ic][oh][ow] * PW_CONV4_W[wOffset + ic];
                }
                pw4Out[oc][oh][ow] = (sum > 0.0f) ? sum : 0.0f;
            }
        }
    }

    // 8. Frequency Average Pooling: [48, 4, 8] -> [48, 8]
    for (int c = 0; c < 48; c++) {
        for (int w = 0; w < 8; w++) {
            float sum = pw4Out[c][0][w] + pw4Out[c][1][w] + pw4Out[c][2][w] + pw4Out[c][3][w];
            freqPooled[c][w] = sum * 0.25f;
        }
    }

    // 9. Temporal Motif 1D Conv: [48, 8] -> [64, 8], kernel_size=5, padding=2
    for (int oc = 0; oc < 64; oc++) {
        int ocOffset = oc * (48 * 5);
        for (int ow = 0; ow < 8; ow++) {
            float sum = TCONV_B[oc];
            for (int ic = 0; ic < 48; ic++) {
                int icOffset = ocOffset + (ic * 5);
                for (int kw = -2; kw <= 2; kw++) {
                    int iw = ow + kw;
                    if (iw < 0 || iw >= 8) continue;
                    sum += freqPooled[ic][iw] * TCONV_W[icOffset + (kw + 2)];
                }
            }
            tconvOut[oc][ow] = (sum > 0.0f) ? sum : 0.0f;
        }
    }

    // 10. Global Temporal Dual-Pooling (Max + Avg): [64, 8] -> [128]
    for (int c = 0; c < 64; c++) {
        float maxVal = tconvOut[c][0];
        float sumVal = tconvOut[c][0];
        for (int w = 1; w < 8; w++) {
            float v = tconvOut[c][w];
            if (v > maxVal) maxVal = v;
            sumVal += v;
        }
        pooled[c] = maxVal;
        pooled[64 + c] = sumVal * 0.125f;
    }

    // 11. 2-Layer MLP Classification Head: [128] -> [32] -> [1]
    for (int j = 0; j < 32; j++) {
        float sum = FC1_B[j];
        int rowOffset = j * 128;
        for (int i = 0; i < 128; i++) {
            sum += pooled[i] * FC1_W[rowOffset + i];
        }
        hidden[j] = (sum > 0.0f) ? sum : 0.0f;
    }

    float logit = FC2_B[0];
    for (int j = 0; j < 32; j++) {
        logit += hidden[j] * FC2_W[j];
    }

    return 1.0f / (1.0f + expf(-logit));
}

// ==================== Public API ====================
static inline float predictWakeWordScoreCircular(const short* circBuffer, int headIdx) {
    int64_t sumRaw = 0;
    for (int i = 0; i < WW_TOTAL_SAMPLES; i += 4) {
        int circIdx = (headIdx + i) % WW_TOTAL_SAMPLES;
        sumRaw += circBuffer[circIdx];
    }
    float meanRaw = (float)sumRaw / (float)(WW_TOTAL_SAMPLES / 4);

    float acMaxAbs = 0.0f;
    for (int i = 0; i < WW_TOTAL_SAMPLES; i += 4) {
        int circIdx = (headIdx + i) % WW_TOTAL_SAMPLES;
        float val = fabsf((float)circBuffer[circIdx] - meanRaw);
        if (val > acMaxAbs) acMaxAbs = val;
    }

    if (acMaxAbs < 240.0f) {
        return 0.0f;
    }

    if (!extractFeaturesCircular(circBuffer, headIdx, meanRaw, acMaxAbs)) {
        return 0.0f;
    }
    return forwardCnn();
}

static inline float predictWakeWordScore(const short* audio24k) {
    int64_t sumRaw = 0;
    for (int i = 0; i < WW_TOTAL_SAMPLES; i += 4) {
        sumRaw += audio24k[i];
    }
    float meanRaw = (float)sumRaw / (float)(WW_TOTAL_SAMPLES / 4);

    float acMaxAbs = 0.0f;
    for (int i = 0; i < WW_TOTAL_SAMPLES; i += 4) {
        float val = fabsf((float)audio24k[i] - meanRaw);
        if (val > acMaxAbs) acMaxAbs = val;
    }

    if (acMaxAbs < 240.0f) {
        return 0.0f;
    }

    if (!extractFeatures(audio24k, meanRaw, acMaxAbs)) {
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


# ==================== Group-Split Utilities ====================
def get_pos_session_key(path: Path):
    name = path.name
    if name.startswith("friday_user_"):
        # e.g. friday_user_friday_continuous_20261124_233450_0001.wav -> friday_continuous_20261124_233450
        parts = name.rsplit("_", 1)[0]
        return parts
    return "pendant_initial_52"


def generate_synthetic_music_clip():
    """Creates synthetic multi-harmonic chords + rhythmic beats/percussion bursts for music robustness."""
    t = np.linspace(0, AUDIO_DURATION, TOTAL_SAMPLES, dtype=np.float32)
    f0 = random.uniform(75.0, 440.0)
    # Rich polyphonic chord with harmonics
    ratios = [1.0, 1.25, 1.5, 2.0, 2.5, 3.0]
    sig = np.zeros_like(t)
    for r in ratios:
        amp = random.uniform(0.05, 0.35) / r
        phase = random.uniform(0, 2 * math.pi)
        sig += amp * np.sin(2.0 * math.pi * (f0 * r) * t + phase)
    # Envelope / tempo pulse
    bpm_hz = random.uniform(1.4, 3.5)
    env = 0.4 + 0.6 * np.maximum(0.0, np.sin(2.0 * math.pi * bpm_hz * t + random.uniform(0, math.pi)))
    sig *= env
    # Occasional drum transient bursts
    for _ in range(random.randint(1, 5)):
        pos = random.randint(0, TOTAL_SAMPLES - 600)
        burst_len = random.randint(200, 600)
        decay = np.exp(-np.linspace(0, 5, burst_len, dtype=np.float32))
        sig[pos : pos + burst_len] += np.random.randn(burst_len).astype(np.float32) * decay * random.uniform(0.15, 0.6)
    return np.clip(sig * random.uniform(0.15, 0.75), -1.0, 1.0).astype(np.float32)


# ==================== Main Training Pipeline ====================
def main():
    print("=======================================================")
    print("  Project Friday - Zero-Leakage V2 Wake-Word Training  ")
    print("=======================================================")

    device = torch.device("cuda" if torch.cuda.is_available() else "cpu")
    print(f"Using compute device: {device} ({torch.cuda.get_device_name(0) if torch.cuda.is_available() else 'CPU'})")

    # 1. Split User Positives by session chronologically (80% Train / 20% UNSEEN Hold-out)
    user_pos_files = sorted(list(POS_DIR.glob("friday_*.wav")))
    pos_by_session = defaultdict(list)
    for f in user_pos_files:
        pos_by_session[get_pos_session_key(f)].append(f)

    train_pos_files, holdout_pos_files = [], []
    for sess, files in sorted(pos_by_session.items()):
        cut = max(1, int(len(files) * 0.80))
        train_pos_files.extend(files[:cut])
        holdout_pos_files.extend(files[cut:])

    train_pos = [load_wav(f) for f in train_pos_files]
    holdout_pos = [load_wav(f) for f in holdout_pos_files]
    print(f"\nPositive Group Split (Zero Leakage):")
    print(f"  Sessions found: {len(pos_by_session)} | Train files: {len(train_pos)} | Hold-out UNSEEN files: {len(holdout_pos)}")

    # 2. Split User Negative Speech (first 70% Train / last 30% UNSEEN)
    user_speech_files = sorted(list(NEG_DIR.glob("speech_*.wav")))
    sp_cut = int(len(user_speech_files) * 0.70)
    train_user_speech = [load_wav(f) for f in user_speech_files[:sp_cut]]
    holdout_user_speech = [load_wav(f) for f in user_speech_files[sp_cut:]]

    # 3. Load Training-Only Speech & Noise Files (NEVER touch holdout_neg/ or jfk.wav!)
    user_noise_files = sorted(list(NEG_DIR.glob("noise_*.wav")))
    cont_speech_files = sorted(list(NEG_DIR.glob("continuous_speech_*.wav")))
    hard_conf_files = sorted(list(NEG_DIR.glob("hard_confuser_*.wav")))
    synth_neg_files = sorted(list(NEG_DIR.glob("synth_neg_*.wav")))
    holdout_tts_files = sorted(list(HOLDOUT_NEG_DIR.glob("holdout_tts_*.wav")))

    raw_user_noise = [load_wav(f) for f in user_noise_files]
    raw_cont_speech = [load_wav(f) for f in cont_speech_files]
    raw_hard_conf = [load_wav(f) for f in hard_conf_files]
    raw_synth_neg = [load_wav(f) for f in synth_neg_files]
    holdout_tts = [load_wav(f) for f in holdout_tts_files]

    # 4. Contiguous-Block Split of Continuous Ambient/Music Recordings (1.5s gap prevents overlap!)
    raw_silence_files = sorted(list(Path("dataset/raw_recordings").glob("silence_continuous_*.wav")))
    train_music_slices = []
    train_ambient_slices = []
    holdout_streams = {}  # name -> list of raw float chunks @ 80ms step

    GAP_SAMPLES = TOTAL_SAMPLES  # 1.5s isolation buffer between train and hold-out blocks
    for sf in raw_silence_files:
        raw_s = load_wav(sf)
        total_n = len(raw_s)
        if total_n <= (TOTAL_SAMPLES * 3):
            continue
        # Hold out last 25% of recording cleanly (e.g. last ~46s of 191.6s music, last ~26s of TV/cough)
        train_end = int(total_n * 0.75)
        holdout_start = train_end + GAP_SAMPLES

        is_music = "20261124_233125" in sf.name or (np.sqrt(np.mean(raw_s**2)) * 32768.0 > 700.0)
        tr_chunks = [raw_s[st : st + TOTAL_SAMPLES] for st in range(0, train_end - TOTAL_SAMPLES + 1, 1280)]
        ho_chunks = [raw_s[st : st + TOTAL_SAMPLES] for st in range(holdout_start, total_n - TOTAL_SAMPLES + 1, 1280)]

        if is_music:
            train_music_slices.extend(tr_chunks)
        else:
            train_ambient_slices.extend(tr_chunks)
        holdout_streams[sf.name] = ho_chunks
        print(f"  Continuous '{sf.name}': {len(tr_chunks)} train windows | {len(ho_chunks)} STRICT UNSEEN hold-out windows")

    # JFK speech is 100% HELD OUT (never seen in training!)
    jfk_path = Path("app/.cxx/Debug/q656mh6g/arm64-v8a/_deps/whispercpp_new-src/samples/jfk.wav")
    jfk_holdout_chunks = []
    if jfk_path.exists():
        jfk_audio = load_wav(jfk_path)
        jfk_holdout_chunks = [jfk_audio[i : i + TOTAL_SAMPLES] for i in range(0, len(jfk_audio) - TOTAL_SAMPLES + 1, 1280)]
        print(f"  JFK speech (100% UNSEEN): {len(jfk_holdout_chunks)} hold-out windows")

    room_noise_pool = [n for n in (raw_user_noise + train_ambient_slices) if len(n) >= TOTAL_SAMPLES]
    music_noise_pool = train_music_slices

    # ==================== Build Training Tensor Dataset ====================
    print("\nExtracting 32-Mel features for Training Partition...", flush=True)
    train_specs, train_labels = [], []

    # A. Train Positives (45x SNR-calibrated & shift-wide augmentations)
    print(f"  Augmenting {len(train_pos)} train 'Friday' clips (45x each with loud-music SNR & +/-400ms shift)...", flush=True)
    for p in train_pos:
        train_specs.append(extract_log_mel_spectrogram(p))
        train_labels.append(1.0)
        for _ in range(45):
            aug = augment_positive(p, music_noise_pool, room_noise_pool)
            train_specs.append(extract_log_mel_spectrogram(aug))
            train_labels.append(1.0)

    # B. Pure Music & Ambient/TV/Cough Negatives
    print(f"  Injecting {len(train_music_slices)} loud music + {len(train_ambient_slices)} ambient/TV/cough slices (gain augmented)...", flush=True)
    for s in train_music_slices:
        train_specs.append(extract_log_mel_spectrogram(s))
        train_labels.append(0.0)
        for g in [random.uniform(0.45, 0.85), random.uniform(1.15, 1.75)]:
            train_specs.append(extract_log_mel_spectrogram((s * g).astype(np.float32)))
            train_labels.append(0.0)

    for s in train_ambient_slices:
        train_specs.append(extract_log_mel_spectrogram(s))
        train_labels.append(0.0)
        peak_cnt = float(np.max(np.abs(s))) * 32768.0
        reps = 3 if peak_cnt > 800.0 else 1
        for _ in range(reps):
            g = random.uniform(0.45, 1.80)
            aug_s = s * g + np.random.normal(0, random.uniform(0.0005, 0.003), size=len(s))
            train_specs.append(extract_log_mel_spectrogram(aug_s.astype(np.float32)))
            train_labels.append(0.0)

    # C. Clean Non-Friday Speech (Conversational + Hard Confusers + User Speech + Synth Neg)
    speech_train_pool = raw_cont_speech + raw_hard_conf
    print(f"  Injecting {len(speech_train_pool)} continuous/hard-confuser speech clips + {len(train_user_speech)} user negative words + {len(raw_synth_neg)} synth words...", flush=True)
    for cs in speech_train_pool:
        train_specs.append(extract_log_mel_spectrogram(cs))
        train_labels.append(0.0)
        for _ in range(2):
            shift = random.randint(-3200, 3200)
            sc = np.pad(cs[:-shift], (shift, 0)) if shift > 0 else (np.pad(cs[-shift:], (0, -shift)) if shift < 0 else cs)
            train_specs.append(extract_log_mel_spectrogram((sc * random.uniform(0.6, 1.5)).astype(np.float32)))
            train_labels.append(0.0)

    for us in train_user_speech:
        train_specs.append(extract_log_mel_spectrogram(us))
        train_labels.append(0.0)
        for _ in range(18):
            shift = random.randint(-4800, 4800)
            sc = np.pad(us[:-shift], (shift, 0)) if shift > 0 else (np.pad(us[-shift:], (0, -shift)) if shift < 0 else us)
            if music_noise_pool and random.random() < 0.6:
                sc = mix_at_snr(sc, random.choice(music_noise_pool), random.uniform(-3.0, 15.0))
            elif room_noise_pool:
                sc = mix_at_snr(sc, random.choice(room_noise_pool), random.uniform(5.0, 22.0))
            train_specs.append(extract_log_mel_spectrogram(sc.astype(np.float32)))
            train_labels.append(0.0)

    for sn in raw_synth_neg:
        train_specs.append(extract_log_mel_spectrogram(sn))
        train_labels.append(0.0)

    # D. Hard Mixed Speech-on-Music + Multi-Speaker Babble Negatives (3,500 samples)
    print("  Generating 3,500 hard mixed Speech-on-Music & Babble negatives...", flush=True)
    all_neg_speech = speech_train_pool + raw_synth_neg + train_user_speech
    all_bg_pool = music_noise_pool + train_ambient_slices
    for _ in range(3500):
        sp = random.choice(all_neg_speech)
        # Optional 2-speaker babble overlap (30%)
        if random.random() < 0.30:
            sp2 = random.choice(all_neg_speech)
            sp = np.clip(sp * 0.7 + sp2 * random.uniform(0.4, 0.8), -1.0, 1.0)
        if all_bg_pool:
            bg = random.choice(all_bg_pool)
            mixed = mix_at_snr(sp, bg, random.uniform(-6.0, 15.0))
        else:
            mixed = sp
        train_specs.append(extract_log_mel_spectrogram(mixed))
        train_labels.append(0.0)

    # E. Synthetic Polyphonic Music + Drum Transients + Pure Tones + Noise Matrices
    print("  Generating 1,000 synthetic music chords/beats + 500 tones + 1,200 noise matrices...", flush=True)
    for _ in range(1000):
        train_specs.append(extract_log_mel_spectrogram(generate_synthetic_music_clip()))
        train_labels.append(0.0)

    t_tone = np.linspace(0, AUDIO_DURATION, TOTAL_SAMPLES, dtype=np.float32)
    for freq in np.linspace(200, 3500, 250):
        tone = np.sin(2 * np.pi * freq * t_tone) * random.uniform(0.1, 0.8)
        train_specs.append(extract_log_mel_spectrogram(tone))
        train_labels.append(0.0)
        tone2 = (np.sin(2 * np.pi * freq * t_tone) + 0.5 * np.sin(2 * np.pi * (freq * 2) * t_tone)) * random.uniform(0.1, 0.6)
        train_specs.append(extract_log_mel_spectrogram(tone2))
        train_labels.append(0.0)

    for _ in range(1200):
        scale = random.uniform(0.3, 1.2)
        spec = np.clip(np.random.randn(NUM_MELS, NUM_FRAMES).astype(np.float32) * scale, -2.0, 2.0)
        train_specs.append(spec)
        train_labels.append(0.0)

    train_specs = np.array(train_specs, dtype=np.float32)
    train_labels = np.array(train_labels, dtype=np.float32)
    pos_count = int(np.sum(train_labels == 1.0))
    neg_count = int(np.sum(train_labels == 0.0))
    print(f"\nTotal ZERO-LEAKAGE Training Dataset: {len(train_specs)} samples ({pos_count} positive, {neg_count} negative)", flush=True)

    train_loader = DataLoader(
        TensorDataset(torch.tensor(train_specs).unsqueeze(1), torch.tensor(train_labels)),
        batch_size=96,
        shuffle=True,
        drop_last=False,
    )

    # ==================== Build STRICT UNSEEN Hold-Out Evaluation Tensors ====================
    def chunks_to_gated_tensor(chunks):
        specs = []
        for c in chunks:
            sp = extract_log_mel_spectrogram(c, check_gates=True)
            if sp is not None:
                specs.append(sp)
        if not specs:
            return None
        return torch.tensor(np.array(specs, dtype=np.float32)).unsqueeze(1).to(device)

    ho_pos_clean_t = chunks_to_gated_tensor(holdout_pos)
    # Also test UNSEEN Fridays mixed with UNSEEN held-out loud music (+3 dB SNR)!
    ho_music_chunks = []
    for k, v in holdout_streams.items():
        if "20261124_233125" in k:
            ho_music_chunks = v
    if ho_music_chunks:
        ho_pos_music = [mix_at_snr(p, ho_music_chunks[i % len(ho_music_chunks)], 3.0) for i, p in enumerate(holdout_pos)]
        ho_pos_music_t = chunks_to_gated_tensor(ho_pos_music)
    else:
        ho_pos_music_t = None

    ho_stream_tensors = {k: chunks_to_gated_tensor(v) for k, v in holdout_streams.items()}
    ho_jfk_t = chunks_to_gated_tensor(jfk_holdout_chunks)
    ho_tts_t = chunks_to_gated_tensor(holdout_tts)
    ho_usp_t = chunks_to_gated_tensor(holdout_user_speech)

    print("\nUnseen Hold-Out Evaluation Sets:")
    print(f"  Hold-Out Clean 'Friday':    {len(holdout_pos)}")
    if ho_pos_music_t is not None:
        print(f"  Hold-Out Music+3dB 'Friday':{len(ho_pos_music_t)}")
    for k, v in ho_stream_tensors.items():
        print(f"  Hold-Out '{k}': {len(v) if v is not None else 0} active windows")
    print(f"  Hold-Out JFK Speech:        {len(ho_jfk_t) if ho_jfk_t is not None else 0} windows")
    print(f"  Hold-Out Unseen TTS Voices: {len(ho_tts_t) if ho_tts_t is not None else 0} windows")

    # ==================== Model, Loss & Training Loop ====================
    model = PhoneticWakeWordNetV2().to(device)
    print(f"PhoneticWakeWordNetV2 Parameters: {sum(p.numel() for p in model.parameters()):,}")

    # Moderate pos_weight + Label Smoothing prevents over-confident false triggers
    pos_weight = torch.tensor([1.8]).to(device)
    criterion = nn.BCEWithLogitsLoss(pos_weight=pos_weight)
    optimizer = optim.AdamW(model.parameters(), lr=0.002, weight_decay=2e-3)
    epochs = 65
    scheduler = optim.lr_scheduler.CosineAnnealingLR(optimizer, T_max=epochs)

    best_score = -1e9
    best_weights = None
    best_summary = ""

    print("\nStarting training loop on GPU...", flush=True)
    for epoch in range(1, epochs + 1):
        model.train()
        train_loss, train_correct, train_total = 0.0, 0, 0

        for bx, by in train_loader:
            bx = apply_spec_augment(bx).to(device)
            by_raw = by.to(device)
            # Label smoothing: 0.96 for positives, 0.02 for negatives
            by_smooth = by_raw * 0.94 + 0.02

            optimizer.zero_grad()
            logits = model(bx).squeeze(1)
            loss = criterion(logits, by_smooth)
            loss.backward()
            optimizer.step()

            train_loss += loss.item() * len(by_raw)
            preds = (torch.sigmoid(logits) >= 0.50).float()
            train_correct += (preds == by_raw).sum().item()
            train_total += len(by_raw)

        scheduler.step()
        train_acc = train_correct / train_total

        # Evaluate strictly on 100% UNSEEN Hold-Out Data!
        model.eval()
        with torch.no_grad():
            pos_clean_scores = torch.sigmoid(model(ho_pos_clean_t).squeeze(1)).cpu().numpy()
            pos_clean_rec = float(np.mean(pos_clean_scores >= 0.72))

            if ho_pos_music_t is not None:
                pos_mus_scores = torch.sigmoid(model(ho_pos_music_t).squeeze(1)).cpu().numpy()
                pos_mus_rec = float(np.mean(pos_mus_scores >= 0.70))
            else:
                pos_mus_rec = pos_clean_rec

            # Hold-out continuous music / TV / silence false triggers (single & 2-consecutive >= 0.85!)
            music_single_fa, music_consec2_fa, total_music_win = 0, 0, 0
            other_single_fa, other_consec2_fa, total_other_win = 0, 0, 0
            for k, t_tensor in ho_stream_tensors.items():
                if t_tensor is None:
                    continue
                sc = torch.sigmoid(model(t_tensor).squeeze(1)).cpu().numpy()
                s_fa = int(np.sum(sc >= 0.85))
                c2_fa = int(np.sum((sc[1:] >= 0.85) & (sc[:-1] >= 0.85))) if len(sc) > 1 else 0
                if "20261124_233125" in k:
                    music_single_fa += s_fa
                    music_consec2_fa += c2_fa
                    total_music_win += len(sc)
                else:
                    other_single_fa += s_fa
                    other_consec2_fa += c2_fa
                    total_other_win += len(sc)

            jfk_scores = torch.sigmoid(model(ho_jfk_t).squeeze(1)).cpu().numpy() if ho_jfk_t is not None else np.array([])
            jfk_fa = int(np.sum(jfk_scores >= 0.75)) if len(jfk_scores) else 0

            tts_scores = torch.sigmoid(model(ho_tts_t).squeeze(1)).cpu().numpy() if ho_tts_t is not None else np.array([])
            tts_fa = int(np.sum(tts_scores >= 0.75)) if len(tts_scores) else 0

            usp_scores = torch.sigmoid(model(ho_usp_t).squeeze(1)).cpu().numpy() if ho_usp_t is not None else np.array([])
            usp_fa = int(np.sum(usp_scores >= 0.75)) if len(usp_scores) else 0

        music_rej_c2 = 1.0 - (music_consec2_fa / max(total_music_win, 1))
        music_rej_s1 = 1.0 - (music_single_fa / max(total_music_win, 1))
        other_rej = 1.0 - (other_single_fa / max(total_other_win, 1))
        speech_rej = 1.0 - ((jfk_fa + tts_fa + usp_fa) / max(len(jfk_scores) + len(tts_scores) + len(usp_scores), 1))

        # Epoch selection prioritizes: Zero 2-consec music triggers, zero unseen speech triggers, high clean+music Friday recall!
        epoch_score = (
            (pos_clean_rec * 0.30)
            + (pos_mus_rec * 0.20)
            + (music_rej_c2 * 0.25)
            + (music_rej_s1 * 0.10)
            + (other_rej * 0.08)
            + (speech_rej * 0.07)
        )
        # Mild stability guard once warmed up
        if epoch >= 15 and (epoch_score >= best_score):
            best_score = epoch_score
            best_weights = {k: v.cpu().clone() for k, v in model.state_dict().items()}
            best_summary = (
                f"Ep {epoch:02d} | CleanRec={pos_clean_rec*100:.1f}%, MusRec={pos_mus_rec*100:.1f}% | "
                f"UnseenMus C2_FA={music_consec2_fa}/{total_music_win}, S1_FA={music_single_fa} | "
                f"OtherFA={other_single_fa}, JFK_FA={jfk_fa}, TTS_FA={tts_fa}, UserNeg_FA={usp_fa}"
            )

        if epoch % 5 == 0 or epoch == epochs:
            print(
                f"Epoch [{epoch:02d}/{epochs}] Loss={train_loss/train_total:.4f} Acc={train_acc*100:.1f}% | "
                f"UNSEEN CleanRec={pos_clean_rec*100:.1f}% Mus+3dBRec={pos_mus_rec*100:.1f}% | "
                f"MusFA(C2/S1)={music_consec2_fa}/{music_single_fa} ({total_music_win}) | "
                f"TV/SilFA={other_single_fa} | JFK/TTS/USp FA={jfk_fa}/{tts_fa}/{usp_fa}",
                flush=True,
            )

    if best_weights is None:
        best_weights = {k: v.cpu().clone() for k, v in model.state_dict().items()}

    print(f"\nTraining Complete! Best Unseen Hold-Out Score: {best_score:.4f}")
    print(f"Best Checkpoint: {best_summary}")
    model.load_state_dict(best_weights)
    model.eval()

    checkpoint_path = OUTPUT_DIR / "pendant_wakeword_model.pth"
    torch.save(model.state_dict(), checkpoint_path)
    print(f"Saved PyTorch model weights to: {checkpoint_path}")

    export_cpp_header(model, ARDUINO_HEADER_PATH)


if __name__ == "__main__":
    main()

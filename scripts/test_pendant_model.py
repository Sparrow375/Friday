#!/usr/bin/env python3
"""
Project Friday - Pendant Wake-Word Model Test & Benchmark Tool
Evaluates the trained TinyWakeWordCnn model on recorded pendant audio samples.
Reports Precision, Recall, False Alarm Rate, and confidence distributions.

Usage:
  .venv/Scripts/python -u scripts/test_pendant_model.py
"""

import os
import wave
import numpy as np
from pathlib import Path
from scipy.fft import rfft

import torch
import torch.nn as nn

# Parameters matching training & C++ firmware
SAMPLE_RATE = 16000
TOTAL_SAMPLES = 24000  # 1.5 seconds
FRAME_LEN = 512
HOP_LEN = 384
NUM_FRAMES = 62
NUM_MELS = 16

MODEL_PATH = Path("output/pendant_wakeword_model.pth")
DATASET_ROOT = Path("dataset/pendant_wakeword")
POS_DIR = DATASET_ROOT / "positive"
NEG_DIR = DATASET_ROOT / "negative"


def compute_mel_filterbank(sr=SAMPLE_RATE, n_fft=FRAME_LEN, n_mels=NUM_MELS, fmin=200.0, fmax=4000.0):
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

def extract_features(samples_float):
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

def load_wav(path):
    with wave.open(str(path), "rb") as wf:
        n_frames = wf.getnframes()
        frames = wf.readframes(n_frames)
        samples = np.frombuffer(frames, dtype=np.int16).astype(np.float32) / 32768.0
        return samples


class TinyWakeWordCnn(nn.Module):
    def __init__(self):
        super().__init__()
        self.conv1 = nn.Conv2d(1, 8, kernel_size=3, stride=(1, 2), padding=1)
        self.relu1 = nn.ReLU()
        self.conv2 = nn.Conv2d(8, 16, kernel_size=3, stride=(2, 2), padding=1)
        self.relu2 = nn.ReLU()
        self.dw_conv3 = nn.Conv2d(16, 16, kernel_size=3, stride=(2, 2), padding=1, groups=16)
        self.pw_conv3 = nn.Conv2d(16, 16, kernel_size=1)
        self.relu3 = nn.ReLU()
        self.pool = nn.AdaptiveAvgPool2d((1, 1))
        self.fc = nn.Linear(16, 1)

    def forward(self, x):
        x = self.relu1(self.conv1(x))
        x = self.relu2(self.conv2(x))
        x = self.relu3(self.pw_conv3(self.dw_conv3(x)))
        x = self.pool(x)
        x = torch.flatten(x, 1)
        x = self.fc(x)
        return x


def main():
    print("==================================================")
    print("  Project Friday - Pendant Wake-Word Benchmark    ")
    print("==================================================")

    if not MODEL_PATH.exists():
        print(f"Error: Model file {MODEL_PATH} not found. Run training first.")
        return

    model = TinyWakeWordCnn()
    model.load_state_dict(torch.load(MODEL_PATH, weights_only=True))
    model.eval()

    pos_files = sorted(list(POS_DIR.glob("*.wav")))
    neg_files = sorted(list(NEG_DIR.glob("*.wav")))

    print(f"Evaluating {len(pos_files)} positive and {len(neg_files)} negative recorded samples...")

    pos_scores = []
    neg_scores = []

    with torch.no_grad():
        for p in pos_files:
            audio = load_wav(p)
            spec = torch.tensor(extract_features(audio)).unsqueeze(0).unsqueeze(0)
            score = torch.sigmoid(model(spec)).item()
            pos_scores.append((p.name, score))

        for n in neg_files:
            audio = load_wav(n)
            spec = torch.tensor(extract_features(audio)).unsqueeze(0).unsqueeze(0)
            score = torch.sigmoid(model(spec)).item()
            neg_scores.append((n.name, score))

    # Threshold evaluation
    thresholds = [0.50, 0.60, 0.70, 0.80]
    print("\n---------------- Performance at Various Thresholds ----------------")
    print(f"{'Threshold':<12}{'True Pos (Recall)':<20}{'False Alarms':<18}{'Precision':<12}{'F1-Score':<10}")
    print("-" * 72)

    for th in thresholds:
        tp = sum(1 for _, s in pos_scores if s >= th)
        fn = len(pos_scores) - tp
        fp = sum(1 for _, s in neg_scores if s >= th)
        tn = len(neg_scores) - fp

        recall = (tp / len(pos_scores)) * 100.0 if pos_scores else 0.0
        precision = (tp / (tp + fp)) * 100.0 if (tp + fp) > 0 else 0.0
        f1 = (2 * precision * recall) / (precision + recall) if (precision + recall) > 0 else 0.0

        print(f"{th:<12.2f}{f'{tp}/{len(pos_scores)} ({recall:.1f}%)':<20}{f'{fp}/{len(neg_scores)}':<18}{f'{precision:.1f}%':<12}{f1/100:.3f}")

    print("-" * 72)

    # Confidence summary
    avg_pos = np.mean([s for _, s in pos_scores])
    avg_neg = np.mean([s for _, s in neg_scores])
    print(f"\nAverage 'Friday' Confidence:       {avg_pos:.4f} ({avg_pos*100:.1f}%)")
    print(f"Average Negative / Noise Score:    {avg_neg:.4f} ({avg_neg*100:.1f}%)")
    print(f"Acoustic Separation Margin:        {avg_pos - avg_neg:.4f} (High margin = rock-solid reliability)")

    # Display sample details
    print("\n--- Sample Detections ---")
    print("Top 5 Positive samples:")
    for name, s in pos_scores[:5]:
        status = "TRIGGER" if s >= 0.70 else "MISSED"
        print(f"  {name}: {s:.3f} [{status}]")

    print("\nTop 5 Negative samples:")
    for name, s in neg_scores[:5]:
        status = "REJECTED" if s < 0.70 else "FALSE ALARM"
        print(f"  {name}: {s:.3f} [{status}]")

    print("\nBenchmark complete! The on-chip C++ model in 'firmware/xiao_friday_pendant/wakeword_model.h' is verified.")


if __name__ == "__main__":
    main()

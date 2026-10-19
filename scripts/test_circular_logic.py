import numpy as np
import wave
from scipy.fft import rfft
import torch
import torch.nn as nn
from pathlib import Path

SAMPLE_RATE = 16000
TOTAL_SAMPLES = 24000
FRAME_LEN = 512
HOP_LEN = 384
NUM_FRAMES = 62
NUM_MELS = 16

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
            if f_m > f_m_minus: fbank[m - 1, k] = (k - f_m_minus) / (f_m - f_m_minus)
        for k in range(f_m, f_m_plus):
            if f_m_plus > f_m: fbank[m - 1, k] = (f_m_plus - k) / (f_m_plus - f_m)
    return fbank

MEL_FBANK = compute_mel_filterbank()
HANNING_WINDOW = 0.5 * (1.0 - np.cos(2.0 * np.pi * np.arange(FRAME_LEN) / (FRAME_LEN - 1))).astype(np.float32)

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
        logits = self.fc(x)
        return torch.sigmoid(logits)

model = TinyWakeWordCnn()
model.load_state_dict(torch.load("output/pendant_wakeword_model.pth", map_location="cpu"))
model.eval()

# Load friday_0001.wav
with wave.open("dataset/pendant_wakeword/positive/friday_0001.wav", "rb") as wf:
    raw_bytes = wf.readframes(wf.getnframes())
    samples = np.frombuffer(raw_bytes, dtype=np.int16).astype(np.float32) / 32768.0

# 1. Linear extract features
log_mel_linear = np.zeros((NUM_MELS, NUM_FRAMES), dtype=np.float32)
for f in range(NUM_FRAMES):
    start = f * HOP_LEN
    frame = samples[start : start + FRAME_LEN] * HANNING_WINDOW
    fft_mag = np.abs(rfft(frame))
    mel_energy = np.dot(MEL_FBANK, fft_mag)
    log_mel_linear[:, f] = np.log10(mel_energy + 1e-5)
mean = np.mean(log_mel_linear)
std = np.std(log_mel_linear) + 1e-6
norm_linear = np.clip((log_mel_linear - mean) / (2.5 * std), -2.0, 2.0).astype(np.float32)
tensor_linear = torch.tensor(norm_linear).unsqueeze(0).unsqueeze(0)
with torch.no_grad():
    score_linear = model(tensor_linear).item()
print("Linear Score:", score_linear)

# 2. Circular buffer simulation with offset
# Suppose the circular buffer was filled and rollingWriteIdx is at 5000:
circBuffer = np.zeros(TOTAL_SAMPLES, dtype=np.float32)
writeIdx = 5000
for i in range(TOTAL_SAMPLES):
    circBuffer[(writeIdx + i) % TOTAL_SAMPLES] = samples[i]

# In C++ extractFeaturesCircular(circBuffer, headIdx):
# headIdx = rollingWriteIdx
headIdx = writeIdx
log_mel_circ = np.zeros((NUM_MELS, NUM_FRAMES), dtype=np.float32)
for f in range(NUM_FRAMES):
    start = f * HOP_LEN
    for i in range(FRAME_LEN):
        offset = start + i
        circIdx = (headIdx + offset) % TOTAL_SAMPLES
        # in C++: s = circBuffer[circIdx]
    frame = np.zeros(FRAME_LEN, dtype=np.float32)
    for i in range(FRAME_LEN):
        frame[i] = circBuffer[(headIdx + start + i) % TOTAL_SAMPLES] * HANNING_WINDOW[i]
    fft_mag = np.abs(rfft(frame))
    mel_energy = np.dot(MEL_FBANK, fft_mag)
    log_mel_circ[:, f] = np.log10(mel_energy + 1e-5)
mean_c = np.mean(log_mel_circ)
std_c = np.std(log_mel_circ) + 1e-6
norm_circ = np.clip((log_mel_circ - mean_c) / (2.5 * std_c), -2.0, 2.0).astype(np.float32)
tensor_circ = torch.tensor(norm_circ).unsqueeze(0).unsqueeze(0)
with torch.no_grad():
    score_circ = model(tensor_circ).item()
print("Circular Score:", score_circ)
print("Max diff:", np.max(np.abs(norm_linear - norm_circ)))

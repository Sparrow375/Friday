#!/usr/bin/env python3
import os
import wave
import math
import numpy as np
from pathlib import Path
from scipy.signal import resample_poly

SRC_NEG = Path("temp_dataset/negative")
SRC_POS = Path("temp_dataset/positive")
DEST_ROOT = Path("dataset/pendant_wakeword")
DEST_NEG = DEST_ROOT / "negative"
DEST_POS = DEST_ROOT / "positive"

DEST_NEG.mkdir(parents=True, exist_ok=True)
DEST_POS.mkdir(parents=True, exist_ok=True)

def convert_and_save(src_path, dest_path, target_sr=16000, target_duration=1.5):
    with wave.open(str(src_path), "rb") as wf:
        sr = wf.getframerate()
        n_channels = wf.getnchannels()
        n_frames = wf.getnframes()
        frames = wf.readframes(n_frames)
        samples = np.frombuffer(frames, dtype=np.int16).astype(np.float32) / 32768.0

    if n_channels > 1:
        samples = samples[::n_channels]

    if sr != target_sr:
        gcd = math.gcd(sr, target_sr)
        up = target_sr // gcd
        down = sr // gcd
        samples = resample_poly(samples, up, down).astype(np.float32)

    target_len = int(target_sr * target_duration)
    if len(samples) < target_len:
        # Center in 1.5s window
        pad_total = target_len - len(samples)
        pad_left = pad_total // 2
        pad_right = pad_total - pad_left
        samples = np.pad(samples, (pad_left, pad_right))
    else:
        samples = samples[:target_len]

    int16_samples = np.clip(samples * 32767.0, -32768.0, 32767.0).astype(np.int16)
    with wave.open(str(dest_path), "wb") as wf:
        wf.setnchannels(1)
        wf.setsampwidth(2)
        wf.setframerate(target_sr)
        wf.writeframes(int16_samples.tobytes())

# 1. Process Negatives
neg_files = sorted(list(SRC_NEG.glob("*.wav")))
print(f"Processing {len(neg_files)} synthetic negative files...")
count = 0
for idx, f in enumerate(neg_files):
    out_file = DEST_NEG / f"synth_neg_{idx:04d}.wav"
    convert_and_save(f, out_file)
    count += 1
print(f"Added {count} synthetic negative samples to {DEST_NEG}")

# 2. Process synthetic positives (only files that correspond to pure 'friday')
# In generate_dataset.ps1: posPhrases = @("friday", "hey friday", "ok friday", "hello friday", "friday assistant")
pos_files = sorted(list(SRC_POS.glob("*.wav")))
print(f"Processing {len(pos_files)} synthetic positive files...")
pos_added = 0
for idx, f in enumerate(pos_files):
    # Only keep every 5th file (index % 5 == 0 is "friday")
    if idx % 5 == 0:
        out_file = DEST_POS / f"synth_friday_{pos_added:04d}.wav"
        convert_and_save(f, out_file)
        pos_added += 1
print(f"Added {pos_added} synthetic positive ('friday') samples to {DEST_POS}")

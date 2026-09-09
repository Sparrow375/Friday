#!/usr/bin/env python3
"""
Raspberry Pi INMP441 Microphone Calibration & Audio Diagnostics Runner (v3 - High-Fidelity Voice)
Runs on Raspberry Pi Zero 2 W.
"""

import os
import sys
import time
import wave
import subprocess
import argparse
import numpy as np

OUTPUT_DIR = "/home/avaneesh/calibration_results"
HARDWARE_DEVICE = "inmp441_raw" # hw:adau7002,0
SAMPLE_RATE = 48000
TARGET_RATE = 16000
DECIMATION = 3

def design_fir_bandpass(num_taps=85, low_hz=100, high_hz=7200, fs=48000):
    """Design a Hamming-windowed bandpass filter (rumble cutoff + anti-aliasing)."""
    M = (num_taps - 1) / 2.0
    n = np.arange(num_taps)
    f_high = high_hz / fs
    f_low = low_hz / fs
    with np.errstate(divide='ignore', invalid='ignore'):
        h_high = np.sin(2.0 * np.pi * f_high * (n - M)) / (np.pi * (n - M))
        h_high[int(M)] = 2.0 * f_high
        h_low = np.sin(2.0 * np.pi * f_low * (n - M)) / (np.pi * (n - M))
        h_low[int(M)] = 2.0 * f_low
    h_bp = (h_high - h_low) * (0.54 - 0.46 * np.cos(2.0 * np.pi * n / (num_taps - 1)))
    freq_test = 1000.0 / fs
    gain_1k = np.abs(np.sum(h_bp * np.exp(-2j * np.pi * freq_test * n)))
    h_bp = h_bp / gain_1k
    return h_bp.astype(np.float32)

def soft_limit(x, threshold=0.7):
    """
    Studio-grade soft knee limiter:
    Linear below threshold (transparent), smooth hyperbolic tangent compression above threshold.
    Guarantees output never exceeds 1.0 and never hard-clips.
    """
    out = np.copy(x)
    abs_x = np.abs(out)
    over_idx = abs_x > threshold
    if np.any(over_idx):
        scale = 1.0 - threshold
        over_val = abs_x[over_idx] - threshold
        compressed = threshold + scale * np.tanh(over_val / scale)
        out[over_idx] = np.sign(out[over_idx]) * compressed
    return out

def save_wav(filename, float_samples, rate=16000, nchannels=1):
    os.makedirs(OUTPUT_DIR, exist_ok=True)
    full_path = os.path.join(OUTPUT_DIR, filename)
    clipped = np.clip(float_samples, -1.0, 1.0)
    int16_samples = (clipped * 32767.0).astype(np.int16)
    with wave.open(full_path, "wb") as w:
        w.setnchannels(nchannels)
        w.setsampwidth(2)
        w.setframerate(rate)
        w.writeframes(int16_samples.tobytes())
    return full_path

def record_raw_stream(duration_sec=5.0, buffer_time_us=1000000):
    print(f"\n[Record] Capturing {duration_sec:.1f}s of raw 48kHz stereo from {HARDWARE_DEVICE}...")
    cmd = [
        "arecord",
        "-D", HARDWARE_DEVICE,
        "-r", str(SAMPLE_RATE),
        "-f", "S32_LE",
        "-c", "2",
        "-t", "raw",
        "--buffer-time", str(buffer_time_us),
        "--period-time", "100000",
        "-d", str(int(np.ceil(duration_sec)))
    ]
    t0 = time.time()
    proc = subprocess.Popen(cmd, stdout=subprocess.PIPE, stderr=subprocess.PIPE)
    raw_data, err_data = proc.communicate()
    elapsed = time.time() - t0

    if proc.returncode != 0:
        err_str = err_data.decode('utf-8', errors='replace')
        raise RuntimeError(f"arecord failed (exit code {proc.returncode}): {err_str}")

    print(f"[Record] Captured {len(raw_data)} bytes in {elapsed:.2f}s")
    return raw_data

def analyze_and_process(raw_data, label="test"):
    stereo_i32 = np.frombuffer(raw_data, dtype=np.int32).reshape(-1, 2)
    n_frames = len(stereo_i32)
    duration = n_frames / SAMPLE_RATE

    raw_ch0 = stereo_i32[:, 0]
    raw_ch1 = stereo_i32[:, 1]

    norm_ch0 = raw_ch0.astype(np.float32) / 2147483648.0
    norm_ch1 = raw_ch1.astype(np.float32) / 2147483648.0

    mean_ch0 = float(np.mean(norm_ch0))
    mean_ch1 = float(np.mean(norm_ch1))
    rms_ch0 = float(np.sqrt(np.mean((norm_ch0 - mean_ch0) ** 2)))
    rms_ch1 = float(np.sqrt(np.mean((norm_ch1 - mean_ch1) ** 2)))
    peak_ch0 = float(np.max(np.abs(norm_ch0 - mean_ch0)))
    peak_ch1 = float(np.max(np.abs(norm_ch1 - mean_ch1)))

    print(f"\n=== Channel Diagnostics ({duration:.2f}s) ===")
    print(f"Channel 0: RMS = {rms_ch0:.5f} ({20*np.log10(max(rms_ch0, 1e-6)):.1f} dBFS), Peak = {peak_ch0:.4f}")
    print(f"Channel 1: RMS = {rms_ch1:.5f} ({20*np.log10(max(rms_ch1, 1e-6)):.1f} dBFS), Peak = {peak_ch1:.4f}")

    active_idx = 1 if rms_ch1 >= rms_ch0 else 0
    active_raw = norm_ch1 if active_idx == 1 else norm_ch0
    active_clean = active_raw - np.mean(active_raw)

    print(f"Using Active Microphone Channel: Channel {active_idx}")

    # 1. Pure raw 48k active channel
    save_wav(f"{label}_01_raw_48k.wav", active_clean, rate=48000, nchannels=1)

    # 2. Naive decimation (old daemon behavior)
    naive_16k = active_clean[::DECIMATION]
    save_wav(f"{label}_02_naive_16k.wav", naive_16k, rate=16000, nchannels=1)

    # 3. Clean Speech Bandpass FIR (100Hz rumble cut + 7.2kHz anti-aliasing)
    fir_bp = design_fir_bandpass(num_taps=85, low_hz=100, high_hz=7200, fs=48000)
    bp_filtered_48k = np.convolve(active_clean, fir_bp, mode='same')
    bp_16k = bp_filtered_48k[::DECIMATION]
    bp_rms = float(np.sqrt(np.mean(bp_16k ** 2)))
    bp_peak = float(np.max(np.abs(bp_16k)))
    save_wav(f"{label}_03_clean_bandpass_1x.wav", bp_16k, rate=16000, nchannels=1)
    print(f"\n[Bandpassed 16k Baseline]: RMS = {bp_rms:.5f} ({20*np.log10(max(bp_rms, 1e-6)):.1f} dBFS), Peak = {bp_peak:.4f}")

    # 4. Gain Variations
    gains = [
        ("gain_3_0x", 3.0, "+9.5 dB", False),
        ("gain_4_0x", 4.0, "+12.0 dB", False),
        ("gain_5_0x", 5.0, "+14.0 dB", False),
        ("gain_6_0x", 6.0, "+15.5 dB", False),
        ("gain_6_0x_softlimit", 6.0, "+15.5 dB (Soft-Limited)", True),
        ("gain_8_0x_softlimit", 8.0, "+18.0 dB (Soft-Limited)", True),
    ]
    print("\n--- Digital Gain & Dynamics Variations ---")
    for g_id, g_factor, db_label, use_limiter in gains:
        boosted = bp_16k * g_factor
        if use_limiter:
            processed = soft_limit(boosted)
            clip_pct = 0.0
            clip_count = 0
        else:
            processed = boosted
            clip_count = int(np.sum(np.abs(boosted) >= 1.0))
            clip_pct = (clip_count / len(boosted)) * 100.0
            
        g_rms = float(np.sqrt(np.mean(np.clip(processed, -1.0, 1.0) ** 2)))
        g_peak = float(np.max(np.abs(np.clip(processed, -1.0, 1.0))))
        f_name = f"{label}_04_{g_id}.wav"
        save_wav(f_name, processed, rate=16000, nchannels=1)
        print(f" {db_label:28s} -> RMS: {g_rms:.4f} ({20*np.log10(max(g_rms, 1e-6)):.1f} dBFS), Peak: {g_peak:.4f}, Hard Clipping: {clip_pct:.2f}% ({clip_count} samples)")

def main():
    parser = argparse.ArgumentParser(description="Calibrate Raspberry Pi INMP441 Microphone")
    parser.add_argument("--duration", type=float, default=5.0, help="Recording duration in seconds")
    parser.add_argument("--label", type=str, default="speech_test", help="Test label")
    args = parser.parse_args()

    chk = subprocess.run(["systemctl", "is-active", "friday-wearable.service"], capture_output=True, text=True)
    was_running = (chk.stdout.strip() == "active")
    if was_running:
        subprocess.run(["sudo", "systemctl", "stop", "friday-wearable.service"], check=True)
        time.sleep(0.3)

    raw = record_raw_stream(duration_sec=args.duration)
    analyze_and_process(raw, label=args.label)

if __name__ == "__main__":
    main()

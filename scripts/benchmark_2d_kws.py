#!/usr/bin/env python3
"""
Benchmark 2D Log-Mel Spectrogram Extraction & DS-CNN Inference on Pi Zero 2 W
"""

import time
import numpy as np

SAMPLE_RATE = 16000
DURATION_SEC = 1.5
INPUT_SAMPLES = int(SAMPLE_RATE * DURATION_SEC) # 24,000
N_MELS = 40
N_FFT = 512
HOP_LENGTH = 160
WIN_LENGTH = 480

def create_mel_filterbank(sr=SAMPLE_RATE, n_fft=N_FFT, n_mels=N_MELS, fmin=60.0, fmax=7600.0):
    """Precompute constant Mel triangular filterbank matrix (n_mels, n_fft // 2 + 1)."""
    def hz_to_mel(hz):
        return 2595.0 * np.log10(1.0 + hz / 700.0)

    def mel_to_hz(mel):
        return 700.0 * (10.0 ** (mel / 2595.0) - 1.0)

    mel_min = hz_to_mel(fmin)
    mel_max = hz_to_mel(fmax)
    mel_points = np.linspace(mel_min, mel_max, n_mels + 2)
    hz_points = mel_to_hz(mel_points)
    bins = np.floor((n_fft + 1) * hz_points / sr).astype(int)

    num_bins = n_fft // 2 + 1
    fb = np.zeros((n_mels, num_bins), dtype=np.float32)

    for m in range(1, n_mels + 1):
        f_m_minus = bins[m - 1]
        f_m = bins[m]
        f_m_plus = bins[m + 1]

        if f_m > f_m_minus:
            fb[m - 1, f_m_minus:f_m] = (np.arange(f_m_minus, f_m) - f_m_minus) / (f_m - f_m_minus)
        if f_m_plus > f_m:
            fb[m - 1, f_m:f_m_plus] = (f_m_plus - np.arange(f_m, f_m_plus)) / (f_m_plus - f_m)

    return fb

class FastLogMelExtractor:
    def __init__(self):
        self.fb = create_mel_filterbank()
        self.window = np.hanning(WIN_LENGTH).astype(np.float32)

    def __call__(self, audio_24k):
        # Frame extraction via stride tricks (zero copy)
        num_frames = (len(audio_24k) - WIN_LENGTH) // HOP_LENGTH + 1
        shape = (num_frames, WIN_LENGTH)
        strides = (audio_24k.strides[0] * HOP_LENGTH, audio_24k.strides[0])
        frames = np.lib.stride_tricks.as_strided(audio_24k, shape=shape, strides=strides)

        # Windowing & Real FFT
        windowed = frames * self.window
        fft_complex = np.fft.rfft(windowed, n=N_FFT, axis=1)
        power_spec = np.abs(fft_complex) ** 2  # shape: (num_frames, 257)

        # Mel filterbank dot product -> (n_mels, num_frames)
        mel_spec = np.dot(self.fb, power_spec.T)

        # Log power
        log_mel = np.log10(np.maximum(mel_spec, 1e-6)) * 10.0
        # Normalize roughly [-1.0, 1.0]
        log_mel = (log_mel + 40.0) / 40.0
        return log_mel.astype(np.float32)

def main():
    print(f"=== Benchmarking 2D Log-Mel Extraction on Pi ===")
    extractor = FastLogMelExtractor()
    dummy_audio = np.random.randn(INPUT_SAMPLES).astype(np.float32) * 0.1

    # Warmup
    for _ in range(5):
        _ = extractor(dummy_audio)

    # Benchmark 50 runs
    times = []
    for _ in range(50):
        t0 = time.perf_counter()
        spec = extractor(dummy_audio)
        times.append((time.perf_counter() - t0) * 1000)

    avg_ms = np.mean(times)
    min_ms = np.min(times)
    max_ms = np.max(times)

    print(f"Log-Mel Spectrogram Shape: {spec.shape} (mels x frames)")
    print(f"Average Execution Time   : {avg_ms:.2f} ms")
    print(f"Min / Max Execution Time : {min_ms:.2f} ms / {max_ms:.2f} ms")
    print(f"CPU Load (if run 5x/sec) : {(avg_ms * 5 / 1000) * 100:.1f}% of ONE core")

if __name__ == "__main__":
    main()

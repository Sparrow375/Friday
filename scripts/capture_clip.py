#!/usr/bin/env python3
"""
Single-clip audio capture runner on Raspberry Pi.
Records from INMP441 I2S mic, extracts Channel 1, subtracts DC,
decimates to 16kHz mono, and saves as 16-bit PCM WAV.
"""

import sys
import time
import wave
import subprocess
import numpy as np

def main():
    if len(sys.argv) < 2:
        print("Usage: python3 capture_clip.py <output_wav> [duration_sec]")
        sys.exit(1)

    out_file = sys.argv[1]
    duration = float(sys.argv[2]) if len(sys.argv) > 2 else 1.8
    rate = 48000
    target_rate = 16000
    decimation = 3

    total_hw_samples = int(duration * rate)
    total_bytes = total_hw_samples * 2 * 4  # 2 channels * 4 bytes (S32_LE)

    cmd = [
        "arecord", "-D", "inmp441_raw", "-r", "48000", "-f", "S32_LE",
        "-c", "2", "-t", "raw", "-q",
        "--buffer-time", "1000000", "--period-time", "100000"
    ]

    proc = subprocess.Popen(cmd, stdout=subprocess.PIPE, stderr=subprocess.DEVNULL)
    try:
        raw_bytes = proc.stdout.read(total_bytes)
    finally:
        proc.terminate()
        try:
            proc.wait(timeout=1.0)
        except Exception:
            proc.kill()

    if len(raw_bytes) < total_bytes:
        print(f"ERROR:ShortRead:{len(raw_bytes)}/{total_bytes}", file=sys.stderr)
        sys.exit(1)

    stereo_i32 = np.frombuffer(raw_bytes, dtype=np.int32).reshape(-1, 2)
    ch0 = stereo_i32[:, 0].astype(np.float32) / 2147483648.0
    ch1 = stereo_i32[:, 1].astype(np.float32) / 2147483648.0

    rms0 = float(np.sqrt(np.mean((ch0 - np.mean(ch0)) ** 2)))
    rms1 = float(np.sqrt(np.mean((ch1 - np.mean(ch1)) ** 2)))

    active_raw = ch1 if rms1 >= rms0 else ch0
    active_clean = active_raw - np.mean(active_raw)
    dec_16k = active_clean[::decimation]

    rms = float(np.sqrt(np.mean(dec_16k ** 2)))
    peak = float(np.max(np.abs(dec_16k)))

    clipped = np.clip(dec_16k, -1.0, 1.0)
    int16_samples = (clipped * 32767.0).astype(np.int16)

    with wave.open(out_file, "wb") as w:
        w.setnchannels(1)
        w.setsampwidth(2)
        w.setframerate(target_rate)
        w.writeframes(int16_samples.tobytes())

    print(f"DONE:{rms:.5f}:{peak:.4f}")

if __name__ == "__main__":
    main()

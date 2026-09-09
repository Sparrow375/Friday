#!/usr/bin/env python3
"""
Persistent Voice Sample Recording Server on Pi.
Keeps arecord running continuously so there is ZERO startup delay.
Listens on stdin for commands:
  RECORD <filename> <duration>
  PING
  QUIT
"""

import sys
import time
import wave
import subprocess
import numpy as np

SAMPLE_RATE = 48000
TARGET_RATE = 16000
DECIMATION = 3

def main():
    cmd = [
        "arecord", "-D", "inmp441_raw", "-r", "48000", "-f", "S32_LE",
        "-c", "2", "-t", "raw", "-q",
        "--buffer-time", "1000000", "--period-time", "100000"
    ]
    proc = subprocess.Popen(cmd, stdout=subprocess.PIPE, stderr=subprocess.DEVNULL)
    
    chunk_samples_16k = 1600  # 100ms
    hw_bytes = chunk_samples_16k * DECIMATION * 2 * 4  # 38,400 bytes

    # Read a few chunks to clear initial hardware startup transients
    for _ in range(5):
        _ = proc.stdout.read(hw_bytes)

    # Signal to PC that server is ready
    print("SERVER_READY", flush=True)

    while True:
        line = sys.stdin.readline()
        if not line:
            break
        parts = line.strip().split()
        if not parts:
            continue

        cmd_name = parts[0]
        if cmd_name == "QUIT":
            break
        elif cmd_name == "PING":
            print("PONG", flush=True)
        elif cmd_name == "RECORD":
            out_file = parts[1]
            duration = float(parts[2]) if len(parts) > 2 else 2.0

            needed_samples_16k = int(duration * TARGET_RATE)
            recorded = []
            samples_collected = 0

            # Drain any accumulated buffer in pipe so recording starts strictly NOW
            while samples_collected < needed_samples_16k:
                raw_bytes = proc.stdout.read(hw_bytes)
                if not raw_bytes or len(raw_bytes) < hw_bytes:
                    break

                stereo_i32 = np.frombuffer(raw_bytes, dtype=np.int32).reshape(-1, 2)
                ch0 = stereo_i32[:, 0].astype(np.float32) / 2147483648.0
                ch1 = stereo_i32[:, 1].astype(np.float32) / 2147483648.0

                rms0 = float(np.sqrt(np.mean((ch0 - np.mean(ch0)) ** 2)))
                rms1 = float(np.sqrt(np.mean((ch1 - np.mean(ch1)) ** 2)))

                active = ch1 if rms1 >= rms0 else ch0
                dec = active[::DECIMATION]
                recorded.append(dec)
                samples_collected += len(dec)

            full = np.concatenate(recorded)[:needed_samples_16k]
            # Smooth DC removal
            full = full - np.mean(full)
            rms = float(np.sqrt(np.mean(full ** 2)))
            peak = float(np.max(np.abs(full)))

            clipped = np.clip(full, -1.0, 1.0)
            int16_samples = (clipped * 32767.0).astype(np.int16)

            with wave.open(out_file, "wb") as w:
                w.setnchannels(1)
                w.setsampwidth(2)
                w.setframerate(TARGET_RATE)
                w.writeframes(int16_samples.tobytes())

            print(f"SAVED:{out_file}:{rms:.5f}:{peak:.4f}", flush=True)

    proc.terminate()
    try:
        proc.wait(timeout=1.0)
    except Exception:
        proc.kill()

if __name__ == "__main__":
    main()

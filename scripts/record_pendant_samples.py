#!/usr/bin/env python3
"""
Project Friday - Pendant Wake-Word Audio Dataset Collector
Records high-fidelity 16kHz 16-bit PCM WAV audio samples directly from the
Seeed Studio XIAO nRF52840 Sense onboard PDM microphone over USB Serial.

Prerequisites:
- Flash `firmware/xiao_audio_recorder/xiao_audio_recorder.ino` to the XIAO nRF52840 Sense.
- Close the Arduino IDE Serial Monitor / Serial Plotter so the COM port is free.
- Run:
    python scripts/record_pendant_samples.py
"""

import os
import sys
import time
import math
import wave
import struct
import argparse
from pathlib import Path

try:
    import serial
    import serial.tools.list_ports
except ImportError:
    print("Error: 'pyserial' is required. Install with: pip install pyserial")
    sys.exit(1)

SAMPLE_RATE = 16000
SAMPLE_WIDTH = 2  # 16-bit = 2 bytes
CHANNELS = 1      # Mono
DEFAULT_DURATION_MS = 1500  # 1.5 seconds per clip

DATASET_ROOT = Path("dataset/pendant_wakeword")
POS_DIR = DATASET_ROOT / "positive"
NEG_DIR = DATASET_ROOT / "negative"


def find_default_port():
    ports = serial.tools.list_ports.comports()
    # Prioritize USB Serial devices
    for p in ports:
        desc = p.description.lower()
        if "usb serial" in desc or "xiao" in desc or "seeed" in desc or "nrf" in desc:
            return p.device
    for p in ports:
        if "com" in p.device.lower() and "bluetooth" not in p.description.lower():
            return p.device
    return ports[0].device if ports else None


def list_ports():
    ports = serial.tools.list_ports.comports()
    print("\nAvailable Serial Ports:")
    for i, p in enumerate(ports):
        print(f"  [{i + 1}] {p.device}: {p.description}")
    return ports


def calculate_rms(raw_pcm_bytes):
    num_samples = len(raw_pcm_bytes) // 2
    if num_samples == 0:
        return 0.0
    samples = struct.unpack(f"<{num_samples}h", raw_pcm_bytes)
    sum_squares = sum(s * s for s in samples)
    return math.sqrt(sum_squares / num_samples)


def get_next_filename(folder: Path, prefix: str) -> Path:
    folder.mkdir(parents=True, exist_ok=True)
    existing = list(folder.glob(f"{prefix}_*.wav"))
    max_idx = 0
    for f in existing:
        try:
            num = int(f.stem.split("_")[-1])
            if num > max_idx:
                max_idx = num
        except ValueError:
            pass
    return folder / f"{prefix}_{max_idx + 1:04d}.wav"


def save_wav(filepath: Path, raw_pcm_bytes: bytes, sample_rate=SAMPLE_RATE):
    with wave.open(str(filepath), "wb") as wf:
        wf.setnchannels(CHANNELS)
        wf.setsampwidth(SAMPLE_WIDTH)
        wf.setframerate(sample_rate)
        wf.writeframes(raw_pcm_bytes)


def capture_clip(ser: serial.Serial, duration_ms=DEFAULT_DURATION_MS) -> bytes:
    # Clear input buffer
    ser.reset_input_buffer()

    # Send trigger
    cmd = f"START {duration_ms}\n"
    ser.write(cmd.encode("utf-8"))
    ser.flush()

    # Wait for AUDIO_START header
    expected_samples = int(SAMPLE_RATE * duration_ms / 1000)
    expected_bytes = expected_samples * SAMPLE_WIDTH

    line = ser.readline().decode("utf-8", errors="ignore").strip()
    while not line.startswith("AUDIO_START"):
        if not line:
            # Timeout
            raise TimeoutError("No response from XIAO recorder. Is the correct firmware flashed?")
        line = ser.readline().decode("utf-8", errors="ignore").strip()

    # Read binary PCM bytes
    pcm_data = bytearray()
    start_time = time.time()
    timeout = (duration_ms / 1000.0) + 3.0

    while len(pcm_data) < expected_bytes:
        if time.time() - start_time > timeout:
            raise TimeoutError(f"Timed out waiting for audio bytes. Received {len(pcm_data)}/{expected_bytes}")
        remaining = expected_bytes - len(pcm_data)
        chunk = ser.read(min(remaining, 512))
        if chunk:
            pcm_data.extend(chunk)

    # Wait for AUDIO_END
    ser.readline()

    return bytes(pcm_data)


def main():
    parser = argparse.ArgumentParser(description="Friday XIAO nRF52840 Sense Dataset Collector")
    parser.add_argument("--port", type=str, default=None, help="Serial port (e.g. COM5)")
    parser.add_argument("--baud", type=int, default=115200, help="Baud rate (default: 115200)")
    parser.add_argument("--duration", type=int, default=DEFAULT_DURATION_MS, help="Clip duration in ms (default: 1500)")
    args = parser.parse_args()

    port = args.port
    if not port:
        ports = list_ports()
        default_port = find_default_port()
        if not default_port:
            print("No serial ports found. Please connect your Seeed XIAO nRF52840 Sense.")
            sys.exit(1)
        print(f"\nDefault detected port: {default_port}")
        user_choice = input(f"Press Enter to use '{default_port}', or type port name: ").strip()
        port = user_choice if user_choice else default_port

    print(f"\nConnecting to {port} @ {args.baud} baud...")
    try:
        ser = serial.Serial(port, args.baud, timeout=2.0)
    except Exception as e:
        print(f"Failed to open port {port}: {e}")
        print("Note: If the Arduino Serial Monitor or Plotter is currently open, please close it first!")
        sys.exit(1)

    time.sleep(1.0)
    ser.reset_input_buffer()

    POS_DIR.mkdir(parents=True, exist_ok=True)
    NEG_DIR.mkdir(parents=True, exist_ok=True)

    pos_count = len(list(POS_DIR.glob("*.wav")))
    neg_count = len(list(NEG_DIR.glob("*.wav")))

    print("\n=======================================================")
    print("      Project Friday - Pendant Wake-Word Recorder     ")
    print("=======================================================")
    print(f"Dataset directory: {DATASET_ROOT.resolve()}")
    print(f"Current Samples: [Positive: {pos_count}]  [Negative: {neg_count}]")
    print("-------------------------------------------------------")
    print("Options:")
    print("  [1] Record Positive Wake-Word ('Friday')")
    print("  [2] Record Negative (Room silence / Background noise)")
    print("  [3] Record Negative (Other words / Speech / TV / Cough)")
    print("  [4] Quick Mic Test (RMS volume meter)")
    print("  [q] Quit")
    print("=======================================================\n")

    try:
        while True:
            choice = input("Select mode (1/2/3/4/q) [default: 1]: ").strip().lower()
            if not choice:
                choice = "1"

            if choice == "q":
                break

            elif choice == "1":
                # Record Positive Wake-Word
                print("\n-> PREPARE TO SAY 'FRIDAY'")
                input("Press ENTER to start countdown...")
                for sec in range(3, 0, -1):
                    print(f"  {sec}...", flush=True)
                    time.sleep(0.6)
                print("  >>> SPEAK NOW: 'FRIDAY' <<<", flush=True)

                pcm = capture_clip(ser, args.duration)
                rms = calculate_rms(pcm)

                if rms < 80:
                    print(f"  [!] Warning: Very low volume (RMS: {rms:.1f}). Did you speak close to the mic?")
                    retry = input("  Keep this sample anyway? (y/N): ").strip().lower()
                    if retry != "y":
                        print("  Discarded.")
                        continue

                out_file = get_next_filename(POS_DIR, "friday")
                save_wav(out_file, pcm)
                pos_count += 1
                print(f"  [✓] Saved: {out_file.name} (RMS: {rms:.1f}) | Total Positives: {pos_count}\n")

            elif choice == "2":
                # Record Background Noise / Silence
                print("\n-> RECORDING ROOM SILENCE / BACKGROUND NOISE")
                print("Please remain quiet or let normal ambient noise occur.")
                input("Press ENTER to record 1.5s...")

                pcm = capture_clip(ser, args.duration)
                rms = calculate_rms(pcm)
                out_file = get_next_filename(NEG_DIR, "noise")
                save_wav(out_file, pcm)
                neg_count += 1
                print(f"  [✓] Saved: {out_file.name} (RMS: {rms:.1f}) | Total Negatives: {neg_count}\n")

            elif choice == "3":
                # Record Other Words
                sample_words = ["hello", "today", "computer", "phone", "friend", "weather", "assistant", "alright"]
                suggested = sample_words[neg_count % len(sample_words)]
                print(f"\n-> RECORDING OTHER SPEECH (e.g. say: '{suggested}')")
                input("Press ENTER to start countdown...")
                for sec in range(3, 0, -1):
                    print(f"  {sec}...", flush=True)
                    time.sleep(0.6)
                print(f"  >>> SPEAK: '{suggested}' (or anything other than 'Friday') <<<", flush=True)

                pcm = capture_clip(ser, args.duration)
                rms = calculate_rms(pcm)
                out_file = get_next_filename(NEG_DIR, "speech")
                save_wav(out_file, pcm)
                neg_count += 1
                print(f"  [✓] Saved: {out_file.name} (RMS: {rms:.1f}) | Total Negatives: {neg_count}\n")

            elif choice == "4":
                # Quick Mic Test
                print("\n-> Running 5-second Live Volume Test (Speak into the pendant)...")
                for _ in range(5):
                    pcm = capture_clip(ser, 800)
                    rms = calculate_rms(pcm)
                    bar = "=" * min(int(rms / 40.0), 30)
                    print(f"  [{bar:<30}] RMS: {rms:.1f}")
                print()

    except KeyboardInterrupt:
        print("\nExiting.")
    finally:
        ser.close()
        print(f"\nDataset Session Complete. Positive: {pos_count}, Negative: {neg_count}")


if __name__ == "__main__":
    main()

#!/usr/bin/env python3
"""
Project Friday - Continuous Wake-Word Audio Recorder
Records a continuous audio stream of the user speaking "Friday" repeatedly
with natural pauses in between. Automatically saves the raw recording to WAV
and feeds it directly to `slice_wakeword_recording.py` to extract positive
utterances and authentic room silence/noise clips.

Usage:
  python scripts/record_continuous_wakeword.py
  python scripts/record_continuous_wakeword.py --device 1
  python scripts/record_continuous_wakeword.py --silence-only
"""

import os
import sys
import time
import wave
import argparse
import numpy as np
from pathlib import Path
from datetime import datetime

try:
    import sounddevice as sd
except ImportError:
    print("Error: 'sounddevice' is required. Run: pip install sounddevice")
    sys.exit(1)

SAMPLE_RATE = 16000
CHANNELS = 1
RAW_DIR = Path("dataset/raw_recordings")


def list_audio_devices():
    print("\nAvailable Audio Input Devices:")
    devices = sd.query_devices()
    default_in = sd.default.device[0]
    for idx, d in enumerate(devices):
        if d['max_input_channels'] > 0:
            is_def = " (Default)" if idx == default_in else ""
            print(f"  [{idx}] {d['name']}{is_def}")
    print()


def record_continuous_session(device=None, silence_only=False):
    RAW_DIR.mkdir(parents=True, exist_ok=True)
    timestamp = datetime.now().strftime("%Y%m%d_%H%M%S")
    mode_tag = "silence" if silence_only else "friday"
    output_wav = RAW_DIR / f"{mode_tag}_continuous_{timestamp}.wav"

    print("==================================================")
    print("  Project Friday - Continuous Audio Recorder      ")
    print("==================================================")
    if silence_only:
        print("Mode: SILENCE HARVESTING")
        print("Stay quiet in your room for ~15 to 30 seconds.")
        print("Ambient room noise, fan, keyboard typing, or gentle movement are welcome.")
    else:
        print("Mode: 'FRIDAY' WAKE-WORD RECORDING")
        print("Instructions:")
        print("  1. Speak 'Friday' repeatedly at a natural, comfortable pace.")
        print("  2. Leave a ~1 to 2 second pause between each repetition.")
        print("  3. Vary your tone slightly: normal speech, softer, faster, slower,")
        print("     or from different head angles/distances.")
        print("  4. Press [ENTER] or [Ctrl+C] when you have spoken 20 to 50 times.")
    print("==================================================\n")

    input("Press [ENTER] to start recording...")
    print("\nRECORDING IN PROGRESS... (Press [ENTER] or [Ctrl+C] to STOP)\n")

    audio_frames = []
    is_running = [True]

    def audio_callback(indata, frames, time_info, status):
        if status:
            print(f"[Warning] {status}", file=sys.stderr)
        audio_frames.append(indata.copy())

    start_time = time.time()

    # Determine input device
    stream = sd.InputStream(
        samplerate=SAMPLE_RATE,
        channels=CHANNELS,
        dtype='float32',
        device=device,
        callback=audio_callback,
        blocksize=1024
    )

    import threading
    def wait_for_enter():
        try:
            input()
            is_running[0] = False
        except (KeyboardInterrupt, EOFError):
            is_running[0] = False

    input_thread = threading.Thread(target=wait_for_enter, daemon=True)
    input_thread.start()

    with stream:
        try:
            while is_running[0] and input_thread.is_alive():
                time.sleep(0.1)
                elapsed = time.time() - start_time
                if audio_frames:
                    latest = audio_frames[-1]
                    peak = np.max(np.abs(latest)) * 32768.0
                    rms = np.sqrt(np.mean(latest**2)) * 32768.0
                    # Visual VU meter bar (20 chars)
                    bar_len = min(20, int((rms / 500.0) * 20))
                    bar = "=" * bar_len + " " * (20 - bar_len)
                    sys.stdout.write(f"\r  [{int(elapsed//60):02d}:{int(elapsed%60):02d}] Meter: [{bar}] Peak: {int(peak):4d} | RMS: {int(rms):3d}  ")
                    sys.stdout.flush()
        except KeyboardInterrupt:
            is_running[0] = False

    print("\n\nRecording stopped! Processing captured audio...")

    if not audio_frames:
        print("Error: No audio frames captured.")
        return None

    full_audio = np.concatenate(audio_frames, axis=0).flatten()
    total_dur = len(full_audio) / SAMPLE_RATE

    # Save to 16kHz 16-bit PCM WAV
    samples_clamped = np.clip(full_audio, -1.0, 1.0)
    int16_samples = (samples_clamped * 32767.0).astype(np.int16)

    with wave.open(str(output_wav), "wb") as wf:
        wf.setnchannels(1)
        wf.setsampwidth(2)
        wf.setframerate(SAMPLE_RATE)
        wf.writeframes(int16_samples.tobytes())

    print(f"Saved recording: {output_wav}")
    print(f"Total Duration: {total_dur:.2f} seconds ({len(full_audio)} samples)\n")

    # Automatically invoke slice_wakeword_recording.py
    sys.path.insert(0, str(Path(__file__).resolve().parent.parent))
    sys.path.insert(0, str(Path(__file__).resolve().parent))
    try:
        from scripts.slice_wakeword_recording import process_continuous_recording
    except ImportError:
        from slice_wakeword_recording import process_continuous_recording
    process_continuous_recording(str(output_wav), silence_only=silence_only)

    return output_wav


def main():
    parser = argparse.ArgumentParser(description="Record continuous wake-word session.")
    parser.add_argument("--device", type=int, default=None, help="Audio input device index")
    parser.add_argument("--list-devices", action="store_true", help="List audio input devices and exit")
    parser.add_argument("--silence-only", action="store_true", help="Record ambient silence session")
    args = parser.parse_args()

    if args.list_devices:
        list_audio_devices()
        return

    record_continuous_session(device=args.device, silence_only=args.silence_only)


if __name__ == "__main__":
    main()

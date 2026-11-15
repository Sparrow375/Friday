#!/usr/bin/env python3
"""
Project Friday - Automated Continuous Wake-Word Audio Slicer
Splits long continuous recordings of repeated wake-word ("Friday") utterances
into exact 1.5-second centered positive samples, and automatically harvests
the intervening silent gaps as authentic room noise/silence negative samples.

Usage:
  python scripts/slice_wakeword_recording.py <audio_file>
  python scripts/slice_wakeword_recording.py dataset/raw_recordings/my_recording.wav
  python scripts/slice_wakeword_recording.py dataset/raw_recordings/room_silence.wav --silence-only
"""

import os
import sys
import wave
import argparse
import numpy as np
from pathlib import Path
from scipy.signal import find_peaks

SAMPLE_RATE = 16000
WINDOW_SAMPLES = 24000  # 1.5 seconds @ 16kHz
FRAME_SAMPLES = 256     # 16ms
HOP_SAMPLES = 128       # 8ms

POS_DIR = Path("dataset/pendant_wakeword/positive")
NEG_DIR = Path("dataset/pendant_wakeword/negative")


def load_audio_file(file_path):
    """Loads any audio file (WAV, MP3, M4A) and returns 16kHz mono float32 samples [-1.0, 1.0]."""
    path = Path(file_path)
    if not path.exists():
        raise FileNotFoundError(f"Audio file not found: {file_path}")

    # Try standard wave module first if it's a WAV file
    if path.suffix.lower() == ".wav":
        try:
            with wave.open(str(path), "rb") as wf:
                channels = wf.getnchannels()
                sampwidth = wf.getsampwidth()
                framerate = wf.getframerate()
                nframes = wf.getnframes()
                raw_bytes = wf.readframes(nframes)

                if sampwidth == 2:
                    samples = np.frombuffer(raw_bytes, dtype=np.int16).astype(np.float32) / 32768.0
                elif sampwidth == 1:
                    samples = (np.frombuffer(raw_bytes, dtype=np.uint8).astype(np.float32) - 128.0) / 128.0
                elif sampwidth == 4:
                    samples = np.frombuffer(raw_bytes, dtype=np.int32).astype(np.float32) / 2147483648.0
                else:
                    raise ValueError(f"Unsupported sample width: {sampwidth}")

                if channels > 1:
                    samples = samples.reshape(-1, channels).mean(axis=1)

                if framerate != SAMPLE_RATE:
                    from scipy.signal import resample_poly
                    from math import gcd
                    g = gcd(SAMPLE_RATE, framerate)
                    samples = resample_poly(samples, SAMPLE_RATE // g, framerate // g).astype(np.float32)

                return samples
        except Exception as e:
            pass

    # Fallback to torchaudio or av
    try:
        import torchaudio
        waveform, sr = torchaudio.load(str(path))
        if waveform.shape[0] > 1:
            waveform = waveform.mean(dim=0, keepdim=True)
        if sr != SAMPLE_RATE:
            resampler = torchaudio.transforms.Resample(sr, SAMPLE_RATE)
            waveform = resampler(waveform)
        return waveform.squeeze(0).numpy().astype(np.float32)
    except Exception as e:
        raise RuntimeError(f"Could not load audio file {path}: {e}")


def save_wav(file_path, samples_float):
    """Saves float32 samples [-1.0, 1.0] as 16kHz 16-bit mono PCM WAV."""
    file_path = Path(file_path)
    file_path.parent.mkdir(parents=True, exist_ok=True)
    samples_clamped = np.clip(samples_float, -1.0, 1.0)
    int16_samples = (samples_clamped * 32767.0).astype(np.int16)

    with wave.open(str(file_path), "wb") as wf:
        wf.setnchannels(1)
        wf.setsampwidth(2)
        wf.setframerate(SAMPLE_RATE)
        wf.writeframes(int16_samples.tobytes())


def compute_energy_envelope(samples):
    """Computes RMS and Peak envelope over 16ms frames with 8ms hop."""
    num_frames = (len(samples) - FRAME_SAMPLES) // HOP_SAMPLES + 1
    rms_env = np.zeros(num_frames, dtype=np.float32)
    peak_env = np.zeros(num_frames, dtype=np.float32)

    for i in range(num_frames):
        start = i * HOP_SAMPLES
        frame = samples[start : start + FRAME_SAMPLES]
        rms_env[i] = np.sqrt(np.mean(frame**2))
        peak_env[i] = np.max(np.abs(frame))

    return rms_env, peak_env


def detect_speech_utterances(samples, min_interval_sec=0.75):
    """
    Detects individual 'Friday' utterances in continuous audio using peak prominence
    and local vocal peak amplitude.
    Returns list of dicts: [{'mid_sample': int, 'peak_adc': float, 'rms_adc': float, 'sec': float}]
    """
    rms_env, peak_env = compute_energy_envelope(samples)
    if len(rms_env) == 0:
        return []

    rms_counts = rms_env * 32768.0
    peak_counts = peak_env * 32768.0

    # Noise floor estimation using 5th percentile (robust even in dense speech recordings)
    bg_rms = float(np.percentile(rms_counts, 5))
    min_peak_height = max(bg_rms * 1.5, 350.0)
    min_prominence = max(bg_rms * 0.25, 50.0)
    min_distance_frames = int(min_interval_sec * SAMPLE_RATE / HOP_SAMPLES)

    print(f"[Acoustic Baseline] Ambient Noise Floor: RMS={bg_rms:.1f} ADC counts")
    print(f"[Acoustic Baseline] Search Thresholds: Height={min_peak_height:.1f}, Prominence={min_prominence:.1f}")

    # Find vocal amplitude peaks in peak envelope
    candidate_peaks, _ = find_peaks(
        peak_counts,
        height=min_peak_height,
        distance=min_distance_frames,
        prominence=min_prominence
    )

    # Neighborhood search window (+/- 300ms) to verify vocal peak amplitude
    nhood_frames = int(0.30 * SAMPLE_RATE / HOP_SAMPLES)
    utterances = []

    for p in candidate_peaks:
        sec = (p * HOP_SAMPLES) / SAMPLE_RATE
        if sec < 0.35:  # Skip start transients or initial button clicks
            continue

        n_start = max(0, p - nhood_frames)
        n_end = min(len(peak_counts), p + nhood_frames + 1)
        local_max_peak = float(np.max(peak_counts[n_start:n_end]))
        local_rms = float(rms_counts[p])

        # A human utterance on this mic produces sharp vocal peak > 350 ADC counts
        if local_max_peak >= 350.0:
            mid_sample = p * HOP_SAMPLES + (FRAME_SAMPLES // 2)
            utterances.append({
                "mid_sample": mid_sample,
                "peak_adc": local_max_peak,
                "rms_adc": local_rms,
                "sec": sec
            })

    return utterances


def process_continuous_recording(file_path, output_pos_dir=POS_DIR, output_neg_dir=NEG_DIR, silence_only=False):
    """
    Extracts centered 1.5s 'Friday' positive clips and harvested silence negative clips.
    """
    print(f"\n==================================================")
    print(f"  Project Friday - Audio Recording Slicer         ")
    print(f"==================================================")
    print(f"Input file: {file_path}")

    samples = load_audio_file(file_path)
    total_seconds = len(samples) / SAMPLE_RATE
    print(f"Total Duration: {total_seconds:.2f} seconds ({len(samples)} samples @ 16kHz)")

    output_pos_dir = Path(output_pos_dir)
    output_neg_dir = Path(output_neg_dir)
    output_pos_dir.mkdir(parents=True, exist_ok=True)
    output_neg_dir.mkdir(parents=True, exist_ok=True)

    stem = Path(file_path).stem

    def harvest_silence_windows(samples_arr):
        step_samples = WINDOW_SAMPLES // 2  # 50% overlap (0.75s hop across 1.5s windows)
        saved_silence = 0
        if len(samples_arr) < WINDOW_SAMPLES:
            repeats = int(np.ceil(WINDOW_SAMPLES / len(samples_arr)))
            clip = np.tile(samples_arr, repeats)[:WINDOW_SAMPLES]
            out_name = f"real_silence_{stem}_{saved_silence+1:04d}.wav"
            save_wav(output_neg_dir / out_name, clip)
            saved_silence += 1
            pk = float(np.max(np.abs(clip)) * 32768.0)
            rms = float(np.sqrt(np.mean(clip**2)) * 32768.0)
            print(f"  [Silence {saved_silence:02d}] {out_name} | Peak={pk:.0f} | RMS={rms:.1f}")
        else:
            for start_s in range(0, len(samples_arr) - WINDOW_SAMPLES + 1, step_samples):
                clip = samples_arr[start_s : start_s + WINDOW_SAMPLES]
                out_name = f"real_silence_{stem}_{saved_silence+1:04d}.wav"
                save_wav(output_neg_dir / out_name, clip)
                saved_silence += 1
                pk = float(np.max(np.abs(clip)) * 32768.0)
                rms = float(np.sqrt(np.mean(clip**2)) * 32768.0)
                print(f"  [Silence {saved_silence:02d}] {out_name} | Peak={pk:.0f} | RMS={rms:.1f}")
        print(f"\nSuccessfully harvested {saved_silence} ambient room silence clips into: {output_neg_dir}")
        return saved_silence

    if silence_only:
        print("\nProcessing mode: SILENCE ONLY (Harvesting pure ambient room noise clips with 50% overlap)")
        harvest_silence_windows(samples)
        return

    # Detect speech utterances
    utterances = detect_speech_utterances(samples)
    print(f"\nDetected {len(utterances)} 'Friday' vocalization utterances!")

    if len(utterances) == 0:
        print("\n[Auto-Silence Detected] No speech utterances matching 'Friday' energy envelope were detected.")
        print("Automatically harvesting this entire recording as ambient room silence negative clips...")
        harvest_silence_windows(samples)
        return

    # 1. Extract Positive Utterances (Centered 1.5-second windows)
    saved_pos = 0
    positive_intervals = []

    print("\n--- Slicing Positive 'Friday' Utterances (1.5s centered) ---")
    for idx, u in enumerate(utterances):
        mid_s = u["mid_sample"]
        win_start = mid_s - (WINDOW_SAMPLES // 2)
        win_end = win_start + WINDOW_SAMPLES

        # Clamp & pad if near edges
        if win_start < 0:
            pad_left = -win_start
            clip = np.pad(samples[:win_end], (pad_left, 0))
            clip_start_s = 0
            clip_end_s = win_end
        elif win_end > len(samples):
            pad_right = win_end - len(samples)
            clip = np.pad(samples[win_start:], (0, pad_right))
            clip_start_s = win_start
            clip_end_s = len(samples)
        else:
            clip = samples[win_start:win_end]
            clip_start_s = win_start
            clip_end_s = win_end

        positive_intervals.append((clip_start_s, clip_end_s))

        out_name = f"friday_user_{stem}_{idx+1:04d}.wav"
        save_wav(output_pos_dir / out_name, clip)
        saved_pos += 1

        print(f"  [{idx+1:02d}] {out_name}: Utterance @ {u['sec']:.2f}s | Peak={u['peak_adc']:.0f} | RMS={u['rms_adc']:.1f}")

    # 2. Harvest Silence Gaps between Utterances
    print("\n--- Harvesting Inter-Word Ambient Silence Gaps ---")
    buffer_margin = int(0.20 * SAMPLE_RATE)  # 200ms buffer away from positive speech windows
    masked_intervals = []
    for s_start, s_end in positive_intervals:
        masked_intervals.append((max(0, s_start - buffer_margin), min(len(samples), s_end + buffer_margin)))

    # Compute silent regions outside speech windows
    silence_regions = []
    prev_end = 0
    for s_start, s_end in sorted(masked_intervals, key=lambda x: x[0]):
        if s_start > prev_end:
            silence_regions.append((prev_end, s_start))
        prev_end = max(prev_end, s_end)
    if prev_end < len(samples):
        silence_regions.append((prev_end, len(samples)))

    saved_neg = 0
    for r_start, r_end in silence_regions:
        r_dur = (r_end - r_start) / SAMPLE_RATE
        if r_dur >= 0.8:
            # If duration is at least 0.8s, harvest silence (tile if < 1.5s, chunk if > 1.5s)
            if r_dur < 1.5:
                raw_clip = samples[r_start:r_end]
                peak_adc = float(np.max(np.abs(raw_clip)) * 32768.0)
                if peak_adc < 450.0:
                    repeats = int(np.ceil(WINDOW_SAMPLES / len(raw_clip)))
                    clip = np.tile(raw_clip, repeats)[:WINDOW_SAMPLES]
                    out_name = f"real_silence_{stem}_{saved_neg+1:04d}.wav"
                    save_wav(output_neg_dir / out_name, clip)
                    saved_neg += 1
                    rms_adc = float(np.sqrt(np.mean(clip**2)) * 32768.0)
                    print(f"  [Silence {saved_neg:02d}] {out_name}: Dur={r_dur:.2f}s (tiled to 1.5s) | Peak={peak_adc:.0f} | RMS={rms_adc:.1f}")
            else:
                num_chunks = int(r_dur // 1.5)
                for c_idx in range(num_chunks):
                    c_start = r_start + (c_idx * WINDOW_SAMPLES)
                    c_end = c_start + WINDOW_SAMPLES
                    clip = samples[c_start:c_end]
                    peak_adc = float(np.max(np.abs(clip)) * 32768.0)
                    if peak_adc < 450.0:
                        out_name = f"real_silence_{stem}_{saved_neg+1:04d}.wav"
                        save_wav(output_neg_dir / out_name, clip)
                        saved_neg += 1
                        rms_adc = float(np.sqrt(np.mean(clip**2)) * 32768.0)
                        print(f"  [Silence {saved_neg:02d}] {out_name}: Dur=1.50s | Peak={peak_adc:.0f} | RMS={rms_adc:.1f}")

    print("\n==================================================")
    print(f"  Processing Complete!                           ")
    print(f"  -> Positives Extracted: {saved_pos} clips")
    print(f"  -> Silences Harvested:  {saved_neg} clips")
    print(f"==================================================\n")


def main():
    parser = argparse.ArgumentParser(description="Slice continuous wake-word recordings into positive and silence clips.")
    parser.add_argument("audio_file", help="Path to raw continuous recording (WAV, MP3, M4A)")
    parser.add_argument("--pos-dir", default=str(POS_DIR), help="Output directory for positive 'Friday' clips")
    parser.add_argument("--neg-dir", default=str(NEG_DIR), help="Output directory for negative silence clips")
    parser.add_argument("--silence-only", action="store_true", help="Process entire audio file as ambient silence chunks")
    args = parser.parse_args()

    process_continuous_recording(args.audio_file, args.pos_dir, args.neg_dir, args.silence_only)


if __name__ == "__main__":
    main()

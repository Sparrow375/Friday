#!/usr/bin/env python3
"""
Project Friday - Zero-Leakage Pendant Wake-Word Benchmark (V2)
Evaluates PhoneticWakeWordNetV2 across BOTH full recordings AND strictly unseen
hold-out partitions (unseen user 'Friday', unseen 45s loud music, unseen TV/cough,
unseen JFK speech, unseen TTS speakers, and sliding-window shift invariance).
"""

import sys
import wave
import math
import numpy as np
from pathlib import Path
from collections import defaultdict
import torch

sys.path.insert(0, str(Path(__file__).resolve().parent.parent))

from scripts.train_pendant_wakeword import (
    PhoneticWakeWordNetV2,
    extract_log_mel_spectrogram,
    load_wav,
    mix_at_snr,
    get_pos_session_key,
    SAMPLE_RATE,
    TOTAL_SAMPLES,
    POS_DIR,
    NEG_DIR,
    HOLDOUT_NEG_DIR,
)

MODEL_PATH = Path("output/pendant_wakeword_model.pth")


def eval_stream(model, chunks):
    scores = []
    with torch.no_grad():
        for chunk in chunks:
            feat = extract_log_mel_spectrogram(chunk, check_gates=True)
            if feat is None:
                scores.append(0.0)
            else:
                s = torch.sigmoid(model(torch.tensor(feat).unsqueeze(0).unsqueeze(0))).item()
                scores.append(s)
    arr = np.array(scores, dtype=np.float32)
    single_75 = int(np.sum(arr >= 0.75))
    single_85 = int(np.sum(arr >= 0.85))
    consec2_85 = int(np.sum((arr[1:] >= 0.85) & (arr[:-1] >= 0.85))) if len(arr) > 1 else 0
    # Firmware rule: 2 consec >= 0.85 with avg >= 0.88 OR 3-of-4 >= 0.70 with cur >= 0.74
    fw_triggers = 0
    for i in range(len(arr)):
        trig = False
        if i >= 1 and arr[i] >= 0.85 and arr[i - 1] >= 0.85 and 0.5 * (arr[i] + arr[i - 1]) >= 0.88:
            trig = True
        elif i >= 3:
            hits = sum(1 for k in range(i - 3, i + 1) if arr[k] >= 0.70)
            if hits >= 3 and arr[i] >= 0.74:
                trig = True
        if trig:
            fw_triggers += 1
    return {
        "n": len(arr),
        "single_75": single_75,
        "single_85": single_85,
        "consec2_85": consec2_85,
        "fw_triggers": fw_triggers,
        "max": float(np.max(arr)) if len(arr) else 0.0,
        "mean": float(np.mean(arr)) if len(arr) else 0.0,
    }


def main():
    print("===========================================================")
    print("  Project Friday - Zero-Leakage Wake-Word Benchmark (V2)   ")
    print("===========================================================")

    if not MODEL_PATH.exists():
        print(f"Error: Model file {MODEL_PATH} not found. Run training first.")
        return

    model = PhoneticWakeWordNetV2()
    model.load_state_dict(torch.load(MODEL_PATH, weights_only=True))
    model.eval()

    # 1. Positives: All vs Strictly Unseen Hold-Out
    user_pos_files = sorted(list(POS_DIR.glob("friday_*.wav")))
    pos_by_session = defaultdict(list)
    for f in user_pos_files:
        pos_by_session[get_pos_session_key(f)].append(f)

    holdout_pos_files = []
    for sess, files in sorted(pos_by_session.items()):
        cut = max(1, int(len(files) * 0.80))
        holdout_pos_files.extend(files[cut:])

    def eval_pos_list(files):
        sc = []
        with torch.no_grad():
            for f in files:
                feat = extract_log_mel_spectrogram(load_wav(f), check_gates=True)
                s = 0.0 if feat is None else torch.sigmoid(model(torch.tensor(feat).unsqueeze(0).unsqueeze(0))).item()
                sc.append(s)
        return np.array(sc)

    all_pos_sc = eval_pos_list(user_pos_files)
    ho_pos_sc = eval_pos_list(holdout_pos_files)

    print("\n--- Positive 'Friday' Recall ---")
    print(f"  ALL User Clips ({len(all_pos_sc)}):      >=0.74: {np.sum(all_pos_sc>=0.74)}/{len(all_pos_sc)} ({np.mean(all_pos_sc>=0.74)*100:.1f}%) | Mean={np.mean(all_pos_sc):.4f}")
    print(f"  UNSEEN Hold-Out ({len(ho_pos_sc)}):     >=0.74: {np.sum(ho_pos_sc>=0.74)}/{len(ho_pos_sc)} ({np.mean(ho_pos_sc>=0.74)*100:.1f}%) | Mean={np.mean(ho_pos_sc):.4f}")

    # Test Unseen Hold-Out Fridays across +/- 400ms circular translation shifts (realistic sliding window!)
    print("\n--- Translation Invariance on UNSEEN Hold-Out 'Fridays' ---")
    for ms in [-400, -250, 0, +250, +400]:
        s_samp = int(ms * 16)
        hits = 0
        with torch.no_grad():
            for f in holdout_pos_files:
                a = load_wav(f)
                w = np.roll(a, s_samp) if s_samp != 0 else a
                feat = extract_log_mel_spectrogram(w, check_gates=True)
                if feat is not None and torch.sigmoid(model(torch.tensor(feat).unsqueeze(0).unsqueeze(0))).item() >= 0.74:
                    hits += 1
        print(f"  Shift {ms:+4d}ms: {hits}/{len(holdout_pos_files)} ({hits/len(holdout_pos_files)*100:5.1f}%)")

    # 2. Strictly Unseen Continuous Negative Streams (last 25% after 1.5s gap)
    print("\n--- STRICT UNSEEN Hold-Out Continuous Negative Streams ---")
    for name, fname in [
        ("UNSEEN Loud Music (last 46s)", "silence_continuous_20261124_233125.wav"),
        ("UNSEEN TV/Cough (last 26s)", "silence_continuous_20261115_135706.wav"),
        ("UNSEEN Room Silence (last 19s)", "silence_continuous_20261115_125057.wav"),
    ]:
        p = Path("dataset/raw_recordings") / fname
        if p.exists():
            raw_s = load_wav(p)
            ho_start = int(len(raw_s) * 0.75) + TOTAL_SAMPLES
            chunks = [raw_s[i : i + TOTAL_SAMPLES] for i in range(ho_start, len(raw_s) - TOTAL_SAMPLES + 1, 1280)]
            r = eval_stream(model, chunks)
            print(f"  {name} ({r['n']} win): FW_Triggers={r['fw_triggers']}, 2Consec>=0.85={r['consec2_85']}, Single>=0.85={r['single_85']}, Max={r['max']:.4f}, Mean={r['mean']:.4f}")

    # Test Unseen Fridays mixed with Unseen Loud Music at +3dB SNR
    mus_p = Path("dataset/raw_recordings/silence_continuous_20261124_233125.wav")
    if mus_p.exists():
        raw_m = load_wav(mus_p)
        ho_start = int(len(raw_m) * 0.75) + TOTAL_SAMPLES
        ho_m_chunks = [raw_m[i : i + TOTAL_SAMPLES] for i in range(ho_start, len(raw_m) - TOTAL_SAMPLES + 1, 1280)]
        hits_m = 0
        with torch.no_grad():
            for idx, f in enumerate(holdout_pos_files):
                mixed = mix_at_snr(load_wav(f), ho_m_chunks[idx % len(ho_m_chunks)], 3.0)
                feat = extract_log_mel_spectrogram(mixed, check_gates=True)
                if feat is not None and torch.sigmoid(model(torch.tensor(feat).unsqueeze(0).unsqueeze(0))).item() >= 0.74:
                    hits_m += 1
        print(f"\n  UNSEEN 'Friday' + UNSEEN Loud Music (+3dB SNR): {hits_m}/{len(holdout_pos_files)} ({hits_m/len(holdout_pos_files)*100:.1f}%)")

    # 3. 100% Unseen JFK Speech, Hold-Out TTS Speakers, and Hold-Out User Negatives
    print("\n--- STRICT UNSEEN Speech Generalization ---")
    jfk_path = Path("app/.cxx/Debug/q656mh6g/arm64-v8a/_deps/whispercpp_new-src/samples/jfk.wav")
    if jfk_path.exists():
        jfk = load_wav(jfk_path)
        jfk_chunks = [jfk[i : i + TOTAL_SAMPLES] for i in range(0, len(jfk) - TOTAL_SAMPLES + 1, 1280)]
        r = eval_stream(model, jfk_chunks)
        print(f"  UNSEEN JFK Speech ({r['n']} win):       FW_Triggers={r['fw_triggers']}, Single>=0.75={r['single_75']}, Max={r['max']:.4f}")

    tts_files = sorted(list(HOLDOUT_NEG_DIR.glob("holdout_tts_*.wav")))
    if tts_files:
        r = eval_stream(model, [load_wav(f) for f in tts_files])
        print(f"  UNSEEN TTS Voices ({r['n']} clips):     Single>=0.75={r['single_75']}, Single>=0.85={r['single_85']}, Max={r['max']:.4f}")

    usp_files = sorted(list(NEG_DIR.glob("speech_*.wav")))
    usp_ho = usp_files[int(len(usp_files) * 0.70):]
    if usp_ho:
        r = eval_stream(model, [load_wav(f) for f in usp_ho])
        print(f"  UNSEEN User Neg Speech ({r['n']} clips):Single>=0.75={r['single_75']}, Max={r['max']:.4f}")

    # Pure Tones
    t = np.linspace(0, 1.5, 24000, dtype=np.float32)
    tone_chunks = [(np.sin(2 * np.pi * f * t) * 0.7).astype(np.float32) for f in [200, 300, 440, 600, 800, 1000, 1200, 1500, 2000, 3000]]
    r_t = eval_stream(model, tone_chunks)
    print(f"  Narrowband Tones ({r_t['n']} freqs):     Single>=0.75={r_t['single_75']}, Max={r_t['max']:.4f}")
    print("===========================================================")


if __name__ == "__main__":
    main()

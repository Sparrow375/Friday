#!/usr/bin/env python3
"""
Generate hard phonetic confusers for training and strictly held-out unseen
TTS speaker negatives for zero-leakage generalization testing.
"""

import asyncio
import wave
import random
import miniaudio
import numpy as np
from pathlib import Path
import edge_tts

NEG_DIR = Path("dataset/pendant_wakeword/negative")
HOLDOUT_NEG_DIR = Path("dataset/pendant_wakeword/holdout_neg")
NEG_DIR.mkdir(parents=True, exist_ok=True)
HOLDOUT_NEG_DIR.mkdir(parents=True, exist_ok=True)

TRAIN_VOICES = [
    "en-US-JennyNeural",
    "en-US-GuyNeural",
    "en-US-AriaNeural",
    "en-US-ChristopherNeural",
    "en-GB-SoniaNeural",
    "en-IN-NeerjaNeural",
    "en-IN-PrabhatNeural",
    "en-AU-NatashaNeural",
]

# Strictly held-out speakers (never used in training!)
HOLDOUT_VOICES = [
    "en-GB-RyanNeural",
    "en-CA-LiamNeural",
]

HARD_CONFUSERS = [
    "Fry day", "Free day", "Fly day", "Dry day", "Cry day", "Try day",
    "Bright day", "Fine day", "Five days", "Fresh day", "Right day",
    "Ride today", "Try today", "Cry today", "Fly high today", "Fried egg today",
    "Provide", "Divide", "Decide", "Inside", "Outside", "Collide", "Beside",
    "Alright", "Alright then", "Alright hey", "Wait a day", "Slide away",
    "Right away", "High tide", "Wide awake", "Night time", "Bye bye",
    "My day", "Pay day", "May day", "Say hey", "Play today", "Stay today",
    "Fridge", "Freeze", "Fresh", "Friend", "Friendly", "Freedom", "Friction",
    "Frightened", "Fragile", "Fraction", "Frame", "Phrase", "Praise", "Pride",
    "Bride", "Fried rice", "French fries", "Coffee grinder", "First aid",
    "Fire alarm", "Private", "Privacy", "Priority", "Primary", "Driver",
    "Monday wait", "Not really today", "Hey Siri", "Okay Google", "Alexa",
]

HOLDOUT_PHRASES = [
    "Good morning everyone, let's get started with the meeting",
    "Can you turn up the music a little bit louder please",
    "This song has a really nice beat and loud bass",
    "I think I heard somebody knocking at the front door",
    "What time does the movie start tonight at the theater",
    "Yesterday was sunny, and Saturday looks even warmer",
    "Try today to finish the report before the evening deadline",
    "Free day tomorrow means we can sleep in until noon",
    "Fried rice and spicy noodles sound amazing for dinner",
    "Drive safely on the highway because it might rain later",
    "Please divide the total bill evenly between all four of us",
    "Look outside the window, the sky is bright orange right now",
    "Alright then, I will call you back in about twenty minutes",
    "She decided to provide all the ingredients for the recipe",
    "Play something upbeat while I clean up the kitchen",
    "Do you want coffee or green tea before we head out",
]

TARGET_LEN = 24000
STEP = 12000


async def synth_list(phrases, voices, out_dir: Path, prefix: str, per_phrase=2):
    sem = asyncio.Semaphore(12)
    saved = [0]

    async def task(idx, phrase, voice):
        async with sem:
            try:
                rate = random.choice(["-12%", "+0%", "+12%"])
                pitch = random.choice(["-8Hz", "+0Hz", "+8Hz"])
                comm = edge_tts.Communicate(phrase, voice, rate=rate, pitch=pitch)
                mp3 = bytearray()
                async for ch in comm.stream():
                    if ch["type"] == "audio":
                        mp3.extend(ch["data"])
                if not mp3:
                    return
                decoded = miniaudio.decode(bytes(mp3), nchannels=1, sample_rate=16000)
                samples = np.frombuffer(decoded.samples, dtype=np.int16)

                if len(samples) <= TARGET_LEN:
                    pad_before = (TARGET_LEN - len(samples)) // 2
                    pad_after = TARGET_LEN - len(samples) - pad_before
                    win = np.pad(samples, (pad_before, pad_after))
                    out = out_dir / f"{prefix}_{idx:04d}_0.wav"
                    with wave.open(str(out), "wb") as wf:
                        wf.setnchannels(1); wf.setsampwidth(2); wf.setframerate(16000)
                        wf.writeframes(win.tobytes())
                    saved[0] += 1
                else:
                    w_i = 0
                    for st in range(0, len(samples) - TARGET_LEN + 1, STEP):
                        win = samples[st : st + TARGET_LEN]
                        out = out_dir / f"{prefix}_{idx:04d}_{w_i}.wav"
                        with wave.open(str(out), "wb") as wf:
                            wf.setnchannels(1); wf.setsampwidth(2); wf.setframerate(16000)
                            wf.writeframes(win.tobytes())
                        w_i += 1
                        saved[0] += 1
            except Exception:
                pass

    jobs = []
    idx = 0
    for phrase in phrases:
        chosen = random.sample(voices, min(per_phrase, len(voices)))
        for v in chosen:
            jobs.append(task(idx, phrase, v))
            idx += 1
    await asyncio.gather(*jobs)
    return saved[0]


async def main():
    print("Synthesizing hard phonetic confusers for training...")
    n_train = await synth_list(HARD_CONFUSERS, TRAIN_VOICES, NEG_DIR, "hard_confuser", per_phrase=3)
    print(f"Saved {n_train} hard confuser clips into {NEG_DIR}")

    print("Synthesizing strictly held-out unseen speaker negatives...")
    n_hold = await synth_list(HOLDOUT_PHRASES, HOLDOUT_VOICES, HOLDOUT_NEG_DIR, "holdout_tts", per_phrase=2)
    print(f"Saved {n_hold} hold-out unseen voice clips into {HOLDOUT_NEG_DIR}")


if __name__ == "__main__":
    asyncio.run(main())

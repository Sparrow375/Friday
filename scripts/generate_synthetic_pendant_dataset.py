#!/usr/bin/env python3
"""
Project Friday - Synthetic Wake-Word Dataset Generator
Generates high-diversity synthetic speech samples for 'Friday' (positive)
and phonetic confusers / conversational commands (negative) across 20+ neural
voices, varied speeds, and pitches using edge-tts and miniaudio.

Zero human recording required!
"""

import os
import sys
import asyncio
import wave
import random
import miniaudio
import numpy as np
from pathlib import Path
import edge_tts

OUTPUT_ROOT = Path("dataset/pendant_wakeword")
POS_DIR = OUTPUT_ROOT / "positive"
NEG_DIR = OUTPUT_ROOT / "negative"

POS_DIR.mkdir(parents=True, exist_ok=True)
NEG_DIR.mkdir(parents=True, exist_ok=True)

# Select a rich, diverse subset of neural voices across accents & genders
VOICES = [
    # US English
    "en-US-AriaNeural",
    "en-US-GuyNeural",
    "en-US-JennyNeural",
    "en-US-ChristopherNeural",
    "en-US-EricNeural",
    "en-US-MichelleNeural",
    "en-US-RogerNeural",
    # UK English
    "en-GB-SoniaNeural",
    "en-GB-RyanNeural",
    "en-GB-LibbyNeural",
    "en-GB-ThomasNeural",
    # Indian English (very common user environment)
    "en-IN-NeerjaNeural",
    "en-IN-PrabhatNeural",
    "en-IN-NeerjaExpressiveNeural",
    # Australian & Canadian English
    "en-AU-NatashaNeural",
    "en-AU-WilliamMultilingualNeural",
    "en-CA-ClaraNeural",
    "en-CA-LiamNeural",
    # Irish
    "en-IE-EmilyNeural",
    "en-IE-ConnorNeural",
]

POS_PHRASES = [
    "Friday",
    "friday",
    "Hey Friday",
    "hey friday",
    "Ok Friday",
    "ok friday",
    "Hello Friday",
    "hello friday",
    "Friday assistant",
    "friday assistant",
    "Yo Friday",
    "Hi Friday",
    "Friday please",
    "Wake up Friday",
]

NEG_CONFUSERS = [
    # Phonetic confusers that sound close to 'Friday'
    "free day",
    "freeday",
    "fried day",
    "fry day",
    "fly day",
    "play day",
    "gray day",
    "friend",
    "friendly",
    "friends",
    "freedom",
    "freezer",
    "fire day",
    "find day",
    "fine day",
    "fight",
    "fighter",
    "frida",
    "freddy",
    "bright day",
    "pride day",
    "try day",
    "cry day",
    "dry day",
    "shy day",
    "pay day",
    "may day",
    "they say",
    "midday",
    "frightened",
    "frequent",
    "friction",
    "frail",
    "frost",
    "frozen",
    # Common assistant / command words
    "hello",
    "hey there",
    "ok",
    "assistant",
    "phone",
    "google",
    "alexa",
    "siri",
    "bixby",
    "what time is it",
    "what is the weather",
    "turn on the lights",
    "turn off lights",
    "set a timer for five minutes",
    "send a message to mom",
    "play some music",
    "volume up",
    "volume down",
    "pause the music",
    "stop",
    "cancel",
    "good morning",
    "good night",
    "how are you doing",
    "open browser",
    "open settings",
    "call dad",
    "take a screenshot",
    # Days of the week
    "monday",
    "tuesday",
    "wednesday",
    "thursday",
    "saturday",
    "sunday",
]

RATES = ["-20%", "-10%", "+0%", "+10%", "+20%"]
PITCHES = ["-15Hz", "+0Hz", "+15Hz"]

async def synthesize_one(text: str, voice: str, rate: str, pitch: str, out_path: Path, sem: asyncio.Semaphore):
    async with sem:
        if out_path.exists() and out_path.stat().st_size > 1000:
            return True
        try:
            communicate = edge_tts.Communicate(text, voice, rate=rate, pitch=pitch)
            mp3_bytes = bytearray()
            async for chunk in communicate.stream():
                if chunk["type"] == "audio":
                    mp3_bytes.extend(chunk["data"])

            if not mp3_bytes:
                return False

            decoded = miniaudio.decode(bytes(mp3_bytes), nchannels=1, sample_rate=16000)
            samples = np.frombuffer(decoded.samples, dtype=np.int16)

            # Center or pad in a 1.5s window (24,000 samples)
            target_len = 24000
            if len(samples) < target_len:
                pad_total = target_len - len(samples)
                pad_front = random.randint(0, pad_total)
                pad_back = pad_total - pad_front
                samples = np.pad(samples, (pad_front, pad_back), mode="constant")
            else:
                samples = samples[:target_len]

            with wave.open(str(out_path), "wb") as wf:
                wf.setnchannels(1)
                wf.setsampwidth(2)
                wf.setframerate(16000)
                wf.writeframes(samples.tobytes())

            return True
        except Exception as e:
            # Silently handle transient network retry
            return False

async def main():
    print("=== Friday Synthetic Wake-Word Dataset Generator ===", flush=True)
    sem = asyncio.Semaphore(12) # 12 concurrent requests for speed

    # 1. Build list of positive tasks
    pos_tasks = []
    pos_idx = 0
    # Existing files offset
    existing_pos = list(POS_DIR.glob("synth_pos_*.wav"))
    pos_idx = len(existing_pos)

    print(f"Generating positive 'Friday' speech variants across {len(VOICES)} voices...", flush=True)
    for voice in VOICES:
        for phrase in POS_PHRASES:
            # Pick 2-3 random rate/pitch combos per voice & phrase
            combos = random.sample([(r, p) for r in RATES for p in PITCHES], 3)
            for rate, pitch in combos:
                out_file = POS_DIR / f"synth_pos_{pos_idx:05d}.wav"
                pos_tasks.append(synthesize_one(phrase, voice, rate, pitch, out_file, sem))
                pos_idx += 1

    # 2. Build list of negative tasks
    neg_tasks = []
    existing_neg = list(NEG_DIR.glob("synth_neg_*.wav"))
    neg_idx = len(existing_neg)

    print(f"Generating negative confuser & command speech variants across {len(VOICES)} voices...", flush=True)
    for voice in VOICES:
        for phrase in NEG_CONFUSERS:
            combos = random.sample([(r, p) for r in RATES for p in PITCHES], 2)
            for rate, pitch in combos:
                out_file = NEG_DIR / f"synth_neg_{neg_idx:05d}.wav"
                neg_tasks.append(synthesize_one(phrase, voice, rate, pitch, out_file, sem))
                neg_idx += 1

    print(f"Total tasks scheduled: {len(pos_tasks)} positive, {len(neg_tasks)} negative.", flush=True)
    print("Starting asynchronous synthesis...", flush=True)

    all_tasks = pos_tasks + neg_tasks
    random.shuffle(all_tasks)

    chunk_size = 50
    completed = 0
    total = len(all_tasks)

    for i in range(0, total, chunk_size):
        batch = all_tasks[i : i + chunk_size]
        results = await asyncio.gather(*batch)
        completed += len(batch)
        print(f"[{completed}/{total}] Synthesized samples... ({sum(results)} successful in batch)", flush=True)

    final_pos = len(list(POS_DIR.glob("*.wav")))
    final_neg = len(list(NEG_DIR.glob("*.wav")))
    print(f"\nSynthesis complete! Total in dataset: {final_pos} Positive, {final_neg} Negative WAV files.", flush=True)

if __name__ == "__main__":
    asyncio.run(main())

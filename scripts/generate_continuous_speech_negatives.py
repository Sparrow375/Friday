#!/usr/bin/env python3
"""
Generate diverse continuous conversational English speech samples
for negative training of the wake-word model.
Uses edge-tts with diverse voices and extracts 1.5s sliding windows.
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

OUTPUT_DIR = Path("dataset/pendant_wakeword/negative")
OUTPUT_DIR.mkdir(parents=True, exist_ok=True)

VOICES = [
    "en-US-JennyNeural",
    "en-US-GuyNeural",
    "en-US-AriaNeural",
    "en-US-ChristopherNeural",
    "en-GB-SoniaNeural",
    "en-GB-RyanNeural",
    "en-IN-NeerjaNeural",
    "en-IN-PrabhatNeural",
    "en-AU-NatashaNeural",
    "en-CA-LiamNeural",
]

PHRASES = [
    # Days of the week & day compounds
    "Monday", "Tuesday", "Wednesday", "Thursday", "Saturday", "Sunday",
    "Today is Monday morning", "Tomorrow is Tuesday afternoon", "See you on Wednesday evening",
    "Thursday is almost the weekend", "Saturday night party", "Sunday morning breakfast",
    "Someday we will travel", "Everyday is a new opportunity", "Holiday season is coming",
    "Happy birthday to you", "Yesterday was quite busy", "May day celebration", "Pay day is tomorrow",
    # Phonetic confusers & near-misses
    "Free day to do whatever we want", "It was a wonderful freeday", "Fly day with clear blue skies",
    "Fried day with fried chicken and fries", "I made a sunny side up fried egg", "Fry day at the beach",
    "Don't be frightened by the dark", "There was some friction between the gears",
    "She has many friendly coworkers", "Freedom of speech is fundamental",
    "Put the ice cream in the freezer", "Fire day drill in the building",
    "We need to find day tickets for the train", "It was a bright sunny day outside",
    "Take pride day by day", "Let us try day after day", "Dry day with no rain in the forecast",
    # Conversational dialogues & questions
    "Hey, what are you doing right now?", "I was thinking we could go outside for a walk",
    "Let's check the weather forecast for tomorrow", "Can you help me find my car keys?",
    "The quick brown fox jumps over the lazy dog", "I have an appointment at three o'clock sharp",
    "Please remember to turn off the kitchen lights", "What is the capital city of France?",
    "We need to fix this software issue immediately", "I'll see you tomorrow morning at the office",
    "That sounds like a really great idea", "Could you please pass me the salt?",
    "I am reading an interesting book about astronomy", "Let's listen to some relaxing instrumental music",
    "Don't forget to call your family tonight", "He is working on a brand new project",
    "The coffee machine is located in the break room", "Do you want to grab lunch together right now?",
    "I'm going for an evening run in the central park", "Everything is working smoothly and according to plan",
    "How much does this package cost to ship?", "Where did you put the documentation folder?",
    "Could you explain how this algorithm works?", "The system is running at maximum efficiency",
    # Common voice commands
    "Turn up the audio volume please", "Mute the microphone during the conference call",
    "Set an alarm for seven thirty tomorrow morning", "How long will it take to get to the downtown airport?",
    "Play my favorite playlist on Spotify", "Read my latest unread text messages",
    "Take a clear photo with the high resolution camera", "Turn on do not disturb mode for two hours",
    "What is twenty-five percent of one hundred and sixty?", "Open the web browser and search for news",
    "Show me turn by turn directions to the nearest hospital", "Remind me to buy groceries at six PM",
    "Stop playing media on all connected devices", "Check the battery percentage of my smartphone",
]

TARGET_LEN = 24000  # 1.5 seconds @ 16kHz
STEP = 12000        # 0.75s step for 50% overlap

async def synthesize_all():
    print(f"Synthesizing {len(PHRASES)} conversational phrases across {len(VOICES)} voices...")
    sem = asyncio.Semaphore(10)
    saved_count = 0

    async def synth_task(idx, phrase, voice):
        nonlocal saved_count
        async with sem:
            try:
                rate = random.choice(["-10%", "+0%", "+10%"])
                pitch = random.choice(["-10Hz", "+0Hz", "+10Hz"])
                comm = edge_tts.Communicate(phrase, voice, rate=rate, pitch=pitch)
                mp3_bytes = bytearray()
                async for chunk in comm.stream():
                    if chunk["type"] == "audio":
                        mp3_bytes.extend(chunk["data"])

                if not mp3_bytes:
                    return

                decoded = miniaudio.decode(bytes(mp3_bytes), nchannels=1, sample_rate=16000)
                samples = np.frombuffer(decoded.samples, dtype=np.int16)

                # Slide windows
                if len(samples) <= TARGET_LEN:
                    pad_before = (TARGET_LEN - len(samples)) // 2
                    pad_after = TARGET_LEN - len(samples) - pad_before
                    window = np.pad(samples, (pad_before, pad_after))
                    out_path = OUTPUT_DIR / f"continuous_speech_{idx:04d}_0.wav"
                    with wave.open(str(out_path), "wb") as wf:
                        wf.setnchannels(1)
                        wf.setsampwidth(2)
                        wf.setframerate(16000)
                        wf.writeframes(window.tobytes())
                    saved_count += 1
                else:
                    win_idx = 0
                    for st in range(0, len(samples) - TARGET_LEN + 1, STEP):
                        window = samples[st : st + TARGET_LEN]
                        out_path = OUTPUT_DIR / f"continuous_speech_{idx:04d}_{win_idx}.wav"
                        with wave.open(str(out_path), "wb") as wf:
                            wf.setnchannels(1)
                            wf.setsampwidth(2)
                            wf.setframerate(16000)
                            wf.writeframes(window.tobytes())
                        win_idx += 1
                        saved_count += 1
            except Exception as e:
                pass

    tasks = []
    task_idx = 0
    for phrase in PHRASES:
        voice = random.choice(VOICES)
        tasks.append(synth_task(task_idx, phrase, voice))
        task_idx += 1
        # Add a second variant with a different accent
        voice2 = random.choice(VOICES)
        if voice2 != voice:
            tasks.append(synth_task(task_idx, phrase, voice2))
            task_idx += 1

    await asyncio.gather(*tasks)
    print(f"Successfully generated {saved_count} continuous conversational speech negative windows!")

if __name__ == "__main__":
    asyncio.run(synthesize_all())

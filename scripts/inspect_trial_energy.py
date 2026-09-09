import wave
import numpy as np

with wave.open("scripts/calibration_audio/voice_trial1_02_naive_16k.wav", "rb") as w:
    s = np.frombuffer(w.readframes(w.getnframes()), dtype=np.int16).astype(np.float32) / 32768.0

# Print RMS in 200ms windows
step = 3200
print("Time (s) | RMS      | Peak")
print("--------------------------")
for i in range(0, len(s), step):
    w = s[i:i+step]
    rms = np.sqrt(np.mean(w**2))
    peak = np.max(np.abs(w))
    bar = "#" * int(rms * 100)
    print(f"{i/16000:7.2f}  | {rms:.5f} | {peak:.4f} | {bar}")

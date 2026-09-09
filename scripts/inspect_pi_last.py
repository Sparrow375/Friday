import wave, numpy as np
with wave.open("/home/avaneesh/last_command.wav", "rb") as w:
    data = np.frombuffer(w.readframes(w.getnframes()), dtype=np.int16)
    print("last_command:", len(data), "nonzeros:", np.count_nonzero(data), "max:", np.max(np.abs(data)), "rms:", np.sqrt(np.mean((data.astype(float)/32768.0)**2)))

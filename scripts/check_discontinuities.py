import wave
import numpy as np

def check_chunk_discontinuities(wav_path, chunk_size=1600):
    with wave.open(wav_path, "rb") as w:
        raw = w.readframes(w.getnframes())
    s = np.frombuffer(raw, dtype=np.int16).astype(np.float32) / 32768.0

    print(f"\nChecking chunk boundaries ({chunk_size} samples) in {wav_path}:")
    num_chunks = len(s) // chunk_size
    boundary_jumps = []
    normal_jumps = []

    for i in range(1, num_chunks):
        boundary_idx = i * chunk_size
        jump = abs(s[boundary_idx] - s[boundary_idx - 1])
        boundary_jumps.append(jump)

    # Average adjacent sample jump inside chunks
    for i in range(len(s) - 1):
        if (i + 1) % chunk_size != 0:
            normal_jumps.append(abs(s[i + 1] - s[i]))

    mean_boundary = np.mean(boundary_jumps)
    mean_normal = np.mean(normal_jumps)
    max_boundary = np.max(boundary_jumps)
    print(f"Normal sample-to-sample delta (avg):   {mean_normal:.5f}")
    print(f"Chunk-boundary delta (avg):            {mean_boundary:.5f} ({mean_boundary/mean_normal:.1f}x higher!)")
    print(f"Max chunk-boundary cliff:              {max_boundary:.5f}")

check_chunk_discontinuities("scripts/calibration_audio/pi_last_command.wav")
check_chunk_discontinuities("scripts/calibration_audio/voice_trial1_02_naive_16k.wav")

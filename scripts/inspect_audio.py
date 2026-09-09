import wave
import sys
import numpy as np

def inspect_wav(wav_path):
    print(f"=== Inspecting {wav_path} ===")
    with wave.open(wav_path, "rb") as w:
        nchannels = w.getnchannels()
        sampwidth = w.getsampwidth()
        framerate = w.getframerate()
        nframes = w.getnframes()
        raw = w.readframes(nframes)

    duration = nframes / framerate
    print(f"Channels: {nchannels}, Sample Width: {sampwidth * 8}-bit, Sample Rate: {framerate} Hz")
    print(f"Frames: {nframes} ({duration:.2f} seconds), Total Raw Bytes: {len(raw)}")

    if sampwidth == 2:
        samples = np.frombuffer(raw, dtype=np.int16).astype(np.float32) / 32768.0
    elif sampwidth == 4:
        samples = np.frombuffer(raw, dtype=np.int32).astype(np.float32) / 2147483648.0
    else:
        print(f"Unhandled sample width: {sampwidth}")
        return

    if nchannels == 2:
        samples = samples.reshape(-1, 2)
        print("Stereo file detected. Analyzing channel 0 and channel 1:")
        for ch in range(2):
            s = samples[:, ch]
            analyze_channel(s, f"Channel {ch}")
    else:
        analyze_channel(samples, "Mono")

def analyze_channel(s, label):
    min_val = float(np.min(s))
    max_val = float(np.max(s))
    mean_val = float(np.mean(s))
    rms = float(np.sqrt(np.mean(s ** 2)))
    abs_s = np.abs(s)
    clipped_ratio = float(np.sum(abs_s >= 0.999)) / len(s)

    # Discontinuity check (large sample-to-sample jump indicative of dropped packets/chunks)
    diffs = np.abs(np.diff(s))
    large_jumps = float(np.sum(diffs > 0.4))
    jump_ratio = large_jumps / len(diffs)

    print(f"\n--- {label} Statistics ---")
    print(f"Min: {min_val:.5f}, Max: {max_val:.5f}, DC Offset (Mean): {mean_val:.5f}")
    print(f"RMS Energy: {rms:.5f} ({20 * np.log10(max(rms, 1e-6)):.2f} dBFS)")
    print(f"Clipped samples (>= 0.999): {clipped_ratio * 100:.2f}% ({int(clipped_ratio * len(s))} samples)")
    print(f"Severe jumps (> 0.4 delta): {large_jumps} ({jump_ratio*100:.3f}%)")

    # High frequency ratio check (spectral centroid or energy above 4kHz vs below 4kHz)
    fft_vals = np.abs(np.fft.rfft(s))
    freqs = np.fft.rfftfreq(len(s), d=1.0/16000.0)
    low_band = np.sum(fft_vals[freqs < 4000])
    high_band = np.sum(fft_vals[freqs >= 4000])
    print(f"Spectral Energy Ratio (High / Low Band): {high_band / max(low_band, 1e-6):.3f}")

if __name__ == "__main__":
    path = sys.argv[1] if len(sys.argv) > 1 else "scripts/calibration_audio/pi_last_command.wav"
    inspect_wav(path)

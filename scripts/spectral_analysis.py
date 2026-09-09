import wave
import sys
import numpy as np

def analyze_spectrum(wav_path):
    with wave.open(wav_path, "rb") as w:
        rate = w.getframerate()
        nframes = w.getnframes()
        raw = w.readframes(nframes)
    s = np.frombuffer(raw, dtype=np.int16).astype(np.float32) / 32768.0

    # FFT
    fft_vals = np.abs(np.fft.rfft(s))
    freqs = np.fft.rfftfreq(len(s), d=1.0/rate)

    # Frequency band energy
    bands = [
        ("Sub-bass / Rumble (0 - 80 Hz)", 0, 80),
        ("Bass / Fan Hum (80 - 250 Hz)", 80, 250),
        ("Speech Fundamental (250 - 500 Hz)", 250, 500),
        ("Speech Formants / Core (500 - 2000 Hz)", 500, 2000),
        ("Upper Speech / Presence (2000 - 4000 Hz)", 2000, 4000),
        ("Sibilance / Highs (4000 - 7500 Hz)", 4000, 7500),
        ("Ultra-High / Aliasing Zone (> 7500 Hz)", 7500, rate / 2)
    ]

    total_energy = np.sum(fft_vals ** 2)
    print(f"\n=== Spectral Breakdown for {wav_path} (Sample Rate: {rate} Hz) ===")
    print(f"Overall RMS: {np.sqrt(np.mean(s**2)):.4f} ({20*np.log10(max(np.sqrt(np.mean(s**2)), 1e-6)):.1f} dBFS)")
    for name, low, high in bands:
        if low >= rate / 2:
            continue
        high = min(high, rate / 2)
        idx = (freqs >= low) & (freqs < high)
        band_energy = np.sum(fft_vals[idx] ** 2)
        pct = (band_energy / max(total_energy, 1e-12)) * 100.0
        print(f"  {name:42s}: {pct:5.2f}%")

if __name__ == "__main__":
    for p in sys.argv[1:]:
        analyze_spectrum(p)

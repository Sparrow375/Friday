import wave
import numpy as np

def test_hpf(wav_path):
    with wave.open(wav_path, "rb") as w:
        rate = w.getframerate()
        nframes = w.getnframes()
        raw = w.readframes(nframes)
    s = np.frombuffer(raw, dtype=np.int16).astype(np.float32) / 32768.0

    raw_rms = np.sqrt(np.mean(s**2))
    raw_peak = np.max(np.abs(s))
    print(f"Original: RMS = {raw_rms:.5f} ({20*np.log10(raw_rms):.1f} dBFS), Peak = {raw_peak:.4f}")

    # Design a simple 2nd order IIR Butterworth High-Pass Filter (or frequency-domain brickwall / FIR)
    # Cutoffs: 80Hz, 120Hz, 150Hz
    for cutoff in [80, 100, 120, 150]:
        # Frequency domain filter for clean test
        fft_s = np.fft.rfft(s)
        freqs = np.fft.rfftfreq(len(s), d=1.0/rate)
        # Smooth roll-off below cutoff
        gain = np.ones_like(freqs)
        sub_idx = freqs < cutoff
        gain[sub_idx] = 0.5 * (1.0 - np.cos(np.pi * freqs[sub_idx] / cutoff)) # smooth Hann taper to 0 at DC
        filtered_s = np.fft.irfft(fft_s * gain, n=len(s))
        
        f_rms = np.sqrt(np.mean(filtered_s**2))
        f_peak = np.max(np.abs(filtered_s))
        print(f"HPF @ {cutoff} Hz: RMS = {f_rms:.5f} ({20*np.log10(max(f_rms, 1e-6)):.1f} dBFS) [Drop: {20*np.log10(raw_rms) - 20*np.log10(f_rms):.1f} dB], Peak = {f_peak:.4f}")

        # Save to file
        out_name = wav_path.replace(".wav", f"_hpf{cutoff}.wav")
        int16_out = (np.clip(filtered_s, -1.0, 1.0) * 32767.0).astype(np.int16)
        with wave.open(out_name, "wb") as out_w:
            out_w.setnchannels(1)
            out_w.setsampwidth(2)
            out_w.setframerate(rate)
            out_w.writeframes(int16_out.tobytes())

if __name__ == "__main__":
    test_hpf("scripts/calibration_audio/ambient_test1_04_anti_aliased_fir_16k.wav")

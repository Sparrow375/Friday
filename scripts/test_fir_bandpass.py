import numpy as np

def design_fir_bandpass(num_taps=65, low_hz=100, high_hz=7200, fs=48000):
    M = (num_taps - 1) / 2.0
    n = np.arange(num_taps)
    f_high = high_hz / fs
    f_low = low_hz / fs
    
    with np.errstate(divide='ignore', invalid='ignore'):
        # Lowpass high_hz
        h_high = np.sin(2.0 * np.pi * f_high * (n - M)) / (np.pi * (n - M))
        h_high[int(M)] = 2.0 * f_high
        # Lowpass low_hz
        h_low = np.sin(2.0 * np.pi * f_low * (n - M)) / (np.pi * (n - M))
        h_low[int(M)] = 2.0 * f_low
    
    # Bandpass = Lowpass(high) - Lowpass(low)
    h_bp = h_high - h_low
    # Hamming window
    window = 0.54 - 0.46 * np.cos(2.0 * np.pi * n / (num_taps - 1))
    h_bp = h_bp * window
    # Normalize gain at 1kHz
    freq_test = 1000.0 / fs
    gain_at_1k = np.abs(np.sum(h_bp * np.exp(-2j * np.pi * freq_test * n)))
    h_bp = h_bp / gain_at_1k
    return h_bp.astype(np.float32)

fir = design_fir_bandpass()
print(f"FIR taps: {len(fir)}, max coeff: {np.max(fir):.4f}")

# Test on 1-second white noise + 50Hz hum
fs = 48000
t = np.linspace(0, 1, fs)
sig = np.sin(2*np.pi*50*t) + np.sin(2*np.pi*1000*t) + np.sin(2*np.pi*15000*t)
filtered = np.convolve(sig, fir, mode='same')

# Check power at 50Hz, 1kHz, 15kHz
for freq in [50, 1000, 15000]:
    in_power = np.abs(np.mean(sig * np.exp(-2j*np.pi*freq*t)))
    out_power = np.abs(np.mean(filtered * np.exp(-2j*np.pi*freq*t)))
    atten_db = 20 * np.log10(max(out_power, 1e-6) / max(in_power, 1e-6))
    print(f"Freq {freq:5d} Hz: Gain = {atten_db:6.1f} dB")

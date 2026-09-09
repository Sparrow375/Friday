import numpy as np

def butterworth_highpass_biquad(cutoff_hz=100, fs=16000):
    w0 = 2.0 * np.pi * cutoff_hz / fs
    cos_w0 = np.cos(w0)
    sin_w0 = np.sin(w0)
    alpha = sin_w0 / (2.0 * 0.70710678) # Q = 1/sqrt(2)
    
    b0 = (1.0 + cos_w0) / 2.0
    b1 = -(1.0 + cos_w0)
    b2 = (1.0 + cos_w0) / 2.0
    a0 = 1.0 + alpha
    a1 = -2.0 * cos_w0
    a2 = 1.0 - alpha
    
    b = np.array([b0/a0, b1/a0, b2/a0], dtype=np.float32)
    a = np.array([1.0, a1/a0, a2/a0], dtype=np.float32)
    return b, a

def apply_biquad(samples, b, a):
    # Vectorized / lfilter implementation
    out = np.zeros_like(samples)
    x1, x2, y1, y2 = 0.0, 0.0, 0.0, 0.0
    b0, b1, b2 = b[0], b[1], b[2]
    a1, a2 = a[1], a[2]
    for i in range(len(samples)):
        x0 = samples[i]
        y0 = b0 * x0 + b1 * x1 + b2 * x2 - a1 * y1 - a2 * y2
        out[i] = y0
        x2, x1 = x1, x0
        y2, y1 = y1, y0
    return out

b, a = butterworth_highpass_biquad(100, 16000)
fs = 16000
t = np.linspace(0, 1, fs)
sig = np.sin(2*np.pi*50*t) + np.sin(2*np.pi*1000*t)
filtered = apply_biquad(sig, b, a)

for freq in [30, 50, 80, 100, 200, 1000]:
    s = np.sin(2*np.pi*freq*t)
    f = apply_biquad(s, b, a)
    # measure steady state gain
    gain_db = 20 * np.log10(np.std(f[fs//2:]) / np.std(s[fs//2:]))
    print(f"Freq {freq:4d} Hz: Gain = {gain_db:5.1f} dB")

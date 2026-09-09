import time
import numpy as np

def benchmark():
    num_samples = 1600 # 100ms at 16kHz
    samples = np.random.randn(num_samples).astype(np.float32)

    # 1. Anti-aliasing FIR convolution (45 taps on 4800 samples at 48kHz)
    fir = np.ones(45, dtype=np.float32) / 45.0
    hw_samples = np.random.randn(4800).astype(np.float32)

    t0 = time.perf_counter()
    for _ in range(100):
        # Convolve and decimate
        filt = np.convolve(hw_samples, fir, mode='same')[::3]
    t_fir = (time.perf_counter() - t0) / 100 * 1000

    # 2. Biquad HPF
    b = np.array([0.97, -1.94, 0.97], dtype=np.float32)
    a = np.array([1.0, -1.94, 0.95], dtype=np.float32)
    b0, b1, b2 = b[0], b[1], b[2]
    a1, a2 = a[1], a[2]

    t0 = time.perf_counter()
    for _ in range(100):
        out = np.empty_like(samples)
        x1, x2, y1, y2 = 0.0, 0.0, 0.0, 0.0
        for i in range(num_samples):
            x0 = samples[i]
            y0 = b0 * x0 + b1 * x1 + b2 * x2 - a1 * y1 - a2 * y2
            out[i] = y0
            x2, x1 = x1, x0
            y2, y1 = y1, y0
    t_iir = (time.perf_counter() - t0) / 100 * 1000

    print(f"FIR 45-tap Anti-Aliasing (4800->1600): {t_fir:.3f} ms per 100ms frame")
    print(f"IIR Biquad HPF (1600 samples):        {t_iir:.3f} ms per 100ms frame")
    print(f"Total DSP time:                       {t_fir + t_iir:.3f} ms (Budget: 100 ms)")

if __name__ == "__main__":
    benchmark()

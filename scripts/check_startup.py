import subprocess, numpy as np

cmd = ["arecord", "-D", "inmp441_raw", "-r", "48000", "-f", "S32_LE", "-c", "2", "-t", "raw", "-q", "--buffer-time", "1000000", "--period-time", "100000"]
proc = subprocess.Popen(cmd, stdout=subprocess.PIPE)
hw_bytes = 1600 * 3 * 2 * 4
for i in range(15):
    raw = proc.stdout.read(hw_bytes)
    arr = np.frombuffer(raw, dtype=np.int32).reshape(-1, 2)
    ch0 = arr[:, 0].astype(np.float32) / 2147483648.0
    diff = ch0 - np.mean(ch0)
    rms = float(np.sqrt(np.mean(diff * diff)))
    print(f"Chunk {i:02d}: mean={np.mean(ch0):.5f}, ac_rms={rms:.5f}")
proc.terminate()

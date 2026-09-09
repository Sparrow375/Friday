import subprocess, numpy as np

cmd = ["arecord", "-D", "inmp441_raw", "-r", "48000", "-f", "S32_LE", "-c", "2", "-t", "raw", "--buffer-time", "1000000", "--period-time", "100000", "-d", "2", "-q"]
proc = subprocess.Popen(cmd, stdout=subprocess.PIPE)
raw, _ = proc.communicate()
arr = np.frombuffer(raw, dtype=np.int32).reshape(-1, 2)
ch0 = arr[:, 0].astype(np.float32) / 2147483648.0
ch1 = arr[:, 1].astype(np.float32) / 2147483648.0

print("Ch0 raw min/max:", np.min(ch0), np.max(ch0), "mean:", np.mean(ch0))
print("Ch1 raw min/max:", np.min(ch1), np.max(ch1), "mean:", np.mean(ch1))

# Discard initial 0.3s transient and check AC RMS
c0 = ch0[14400:] - np.mean(ch0[14400:])
c1 = ch1[14400:] - np.mean(ch1[14400:])

print("Ch0 steady RMS:", np.sqrt(np.mean(c0**2)), "peak:", np.max(np.abs(c0)))
print("Ch1 steady RMS:", np.sqrt(np.mean(c1**2)), "peak:", np.max(np.abs(c1)))

# Check high-frequency variance (differences between consecutive samples)
diff0 = np.diff(c0)
diff1 = np.diff(c1)
print("Ch0 diff RMS (AC audio activity):", np.sqrt(np.mean(diff0**2)))
print("Ch1 diff RMS (AC audio activity):", np.sqrt(np.mean(diff1**2)))

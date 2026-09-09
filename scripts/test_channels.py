import subprocess, numpy as np

cmd = ["arecord", "-D", "inmp441_raw", "-r", "48000", "-f", "S32_LE", "-c", "2", "-t", "raw", "--buffer-time", "1000000", "--period-time", "100000", "-d", "2", "-q"]
proc = subprocess.Popen(cmd, stdout=subprocess.PIPE)
raw, _ = proc.communicate()
arr = np.frombuffer(raw, dtype=np.int32).reshape(-1, 2)
ch0 = arr[:, 0].astype(np.float32) / 2147483648.0
ch1 = arr[:, 1].astype(np.float32) / 2147483648.0
rms0 = np.sqrt(np.mean((ch0 - np.mean(ch0))**2))
rms1 = np.sqrt(np.mean((ch1 - np.mean(ch1))**2))
print("Ch0 RMS:", rms0, "max:", np.max(np.abs(ch0 - np.mean(ch0))))
print("Ch1 RMS:", rms1, "max:", np.max(np.abs(ch1 - np.mean(ch1))))

import time
import numpy as np
import onnxruntime as ort

opts = ort.SessionOptions()
opts.intra_op_num_threads = 2
session = ort.InferenceSession("/home/avaneesh/wakeword.onnx", opts)
input_name = session.get_inputs()[0].name
x = np.zeros((1, 1, 24000), dtype=np.float32)

# Warmup
for _ in range(5):
    session.run(None, {input_name: x})

times = []
for _ in range(30):
    t0 = time.perf_counter()
    session.run(None, {input_name: x})
    times.append((time.perf_counter() - t0) * 1000)

print(f"ONNX Model Inference Latency on Pi Zero 2 W:")
print(f"Mean: {np.mean(times):.2f} ms, Min: {np.min(times):.2f} ms, Max: {np.max(times):.2f} ms, P95: {np.percentile(times, 95):.2f} ms")

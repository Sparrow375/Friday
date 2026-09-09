import wave
import numpy as np
import onnxruntime as ort

def test_recording_on_wakeword(wav_path):
    print(f"Testing {wav_path} against wakeword.onnx...")
    session = ort.InferenceSession("/home/avaneesh/wakeword.onnx")
    input_name = session.get_inputs()[0].name

    with wave.open(wav_path, "rb") as w:
        raw = w.readframes(w.getnframes())
        rate = w.getframerate()
    s = np.frombuffer(raw, dtype=np.int16).astype(np.float32) / 32768.0

    print(f"Loaded {len(s)} samples at {rate} Hz ({len(s)/rate:.2f}s)")
    if rate != 16000:
        print(f"Warning: wakeword.onnx requires 16000 Hz, got {rate} Hz")
        return

    # Evaluate sliding 1.5s window (24000 samples) every 100ms (1600 samples)
    window_size = 24000
    step = 1600
    max_conf = 0.0
    best_time = 0.0

    for start in range(0, len(s) - window_size, step):
        chunk = s[start:start + window_size]
        inp = np.expand_dims(np.expand_dims(chunk, axis=0), axis=0).astype(np.float32)
        logits = session.run(None, {input_name: inp})[0][0]
        exps = np.exp(logits - np.max(logits))
        conf = float(exps[1] / np.sum(exps))
        t = (start + window_size) / rate
        if conf > max_conf:
            max_conf = conf
            best_time = t
        if conf >= 0.50:
            print(f"  At t={t:.2f}s: Confidence = {conf*100:.1f}%, Logits = {logits}")

    print(f"\nPeak Confidence across recording: {max_conf*100:.2f}% at t={best_time:.2f}s")

if __name__ == "__main__":
    import sys
    path = sys.argv[1] if len(sys.argv) > 1 else "/home/avaneesh/calibration_results/voice_trial1_02_naive_16k.wav"
    test_recording_on_wakeword(path)

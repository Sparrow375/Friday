#!/usr/bin/env python3
import os
import sys
import numpy as np

# Feature extraction settings matching the Android client
SAMPLE_RATE = 16000
DURATION_SECONDS = 1.5
INPUT_SIZE = int(SAMPLE_RATE * DURATION_SECONDS)  # 24000 samples

def print_help():
    print("Usage:")
    print("  py -3 scripts/test_wakeword_pc.py <path_to_wav_file>")
    print("  py -3 scripts/test_wakeword_pc.py --record")
    print("\nNote: For recording, make sure you have 'sounddevice' and 'scipy' installed:")
    print("  pip install sounddevice scipy onnxruntime numpy")

def main():
    print("=== Friday Assistant Wake-Word PC Tester ===")
    
    # 1. Check imports
    try:
        import onnxruntime as ort
    except ImportError:
        print("[!] onnxruntime is required. Install it using: pip install onnxruntime")
        return
        
    model_path = os.path.join("app", "src", "main", "assets", "wakeword.onnx")
    if not os.path.exists(model_path):
        # Check output directory
        model_path = os.path.join("output", "wakeword.onnx")
        if not os.path.exists(model_path):
            print(f"[!] wakeword.onnx not found in assets or output folder.")
            return

    print(f"Loading ONNX model from: {model_path}...")
    try:
        session = ort.InferenceSession(model_path)
        print("Model loaded successfully!")
    except Exception as e:
        print(f"[!] Failed to load ONNX model: {e}")
        return

    # 2. Parse arguments
    if len(sys.argv) < 2:
        print_help()
        return

    mode = sys.argv[1]
    audio_data = None

    if mode == "--record":
        try:
            import sounddevice as sd
            import scipy.io.wavfile as wavfile
        except ImportError:
            print("[!] sounddevice and scipy are required for recording. Install them using: pip install sounddevice scipy")
            return
        
        print("\nPress Enter and then speak 'Friday' clearly...")
        input()
        print("Recording for 1.5 seconds...")
        
        # Record mono 16kHz audio
        recording = sd.rec(int(DURATION_SECONDS * SAMPLE_RATE), samplerate=SAMPLE_RATE, channels=1, dtype='float32')
        sd.wait()
        print("Recording finished!")
        
        audio_data = recording.flatten()
        
    elif os.path.exists(mode):
        try:
            import scipy.io.wavfile as wavfile
            sr, data = wavfile.read(mode)
            print(f"Loaded WAV file: {mode} (Sample Rate: {sr}, Samples: {len(data)})")
            
            # Normalize to float32 between -1.0 and 1.0
            y = data.astype(np.float32)
            if y.dtype == np.int16:
                y /= 32768.0
            elif y.dtype == np.int32:
                y /= 2147483648.0
                
            if len(y.shape) > 1:
                y = np.mean(y, axis=1) # Mono
                
            # Resample to 16kHz if needed
            if sr != SAMPLE_RATE:
                print(f"Resampling from {sr}Hz to {SAMPLE_RATE}Hz...")
                duration = len(y) / sr
                num_target_samples = int(duration * SAMPLE_RATE)
                y = np.interp(
                    np.linspace(0, len(y) - 1, num_target_samples),
                    np.arange(len(y)),
                    y
                )
            
            # Pad or Crop to INPUT_SIZE
            if len(y) > INPUT_SIZE:
                start = (len(y) - INPUT_SIZE) // 2
                y = y[start:start+INPUT_SIZE]
            else:
                pad_len = INPUT_SIZE - len(y)
                left = pad_len // 2
                right = pad_len - left
                y = np.pad(y, (left, right), 'constant')
                
            audio_data = y
            
        except Exception as e:
            print(f"[!] Failed to read or process WAV file: {e}")
            return
    else:
        print(f"[!] Mode or file path '{mode}' not found.")
        print_help()
        return

    if audio_data is None:
        print("[!] No audio data collected.")
        return

    # 3. Run ONNX Inference
    # Input shape: [1, 1, 24000]
    input_data = np.expand_dims(np.expand_dims(audio_data, axis=0), axis=0).astype(np.float32)
    
    input_name = session.get_inputs()[0].name
    outputs = session.run(None, {input_name: input_data})
    probabilities = outputs[0][0]
    
    # Softmax
    exp_neg = np.exp(probabilities[0])
    exp_pos = np.exp(probabilities[1])
    confidence = exp_pos / (exp_neg + exp_pos)
    
    print("\n=== Inference Result ===")
    print(f"Logits (Neg/Pos): [{probabilities[0]:.4f}, {probabilities[1]:.4f}]")
    print(f"Wake-Word Confidence: {confidence:.2%}")
    if confidence >= 0.85:
        print("[+] Wake-Word DETECTED! (Friday)")
    else:
        print("[-] Wake-Word NOT detected.")

if __name__ == "__main__":
    main()

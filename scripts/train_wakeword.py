#!/usr/bin/env python3
"""
Friday Assistant Wake-Word Model Trainer (1D CNN Raw Audio - High Performance)
Synthesizes training data using local SAPI5 voices, extracts raw PCM waveforms
using fast scipy/numpy interpolation, trains a 1D CNN classifier, and exports
it to quantized ONNX for Android.
"""

import os
import sys
import shutil
import numpy as np
import scipy.io.wavfile as wavfile
import scipy.signal as signal

# Feature extraction settings matching the Android client
SAMPLE_RATE = 16000
DURATION_SECONDS = 1.5
INPUT_SIZE = int(SAMPLE_RATE * DURATION_SECONDS)  # 24000 samples

# 2nd-order Butterworth High-Pass Filter at 80Hz (eliminates sub-audible MEMS DC drift & HVAC rumble)
HPF_B, HPF_A = signal.butter(2, 80, btype='high', fs=SAMPLE_RATE)
HPF_ZI = signal.lfilter_zi(HPF_B, HPF_A) * 0.0

def resample_wave(y, orig_sr, target_sr):
    if orig_sr == target_sr:
        return y
    duration = len(y) / orig_sr
    num_target_samples = int(duration * target_sr)
    return np.interp(
        np.linspace(0, len(y) - 1, num_target_samples),
        np.arange(len(y)),
        y
    )

def train_wakeword():
    print("=== Friday Custom Wake-Word Training Pipeline ===", flush=True)
    
    try:
        import torch
        import torch.nn as nn
        import torch.optim as optim
        from torch.utils.data import TensorDataset, DataLoader
        import onnx
        from onnxruntime.quantization import quantize_dynamic, QuantType
    except ImportError as e:
        print(f"\n[!] Prerequisite libraries are missing: {e}", flush=True)
        print("Please run: pip install torch onnx onnxruntime numpy scipy", flush=True)
        return

    data_dir = "temp_dataset"
    pos_dir = os.path.join(data_dir, "positive")
    neg_dir = os.path.join(data_dir, "negative")
    
    if not os.path.exists(pos_dir) or not os.path.exists(neg_dir):
        print("[!] Dataset directory does not exist! Please run scripts/generate_dataset.ps1 first.", flush=True)
        return

    # 2. Load and preprocess audio files into raw waveforms of fixed size
    print("Loading and preprocessing audio waveforms (applying 80Hz Butterworth HPF)...", flush=True)
    
    def load_waveforms(directory, target_len=INPUT_SIZE):
        real_waves = []
        synth_waves = []
        for file in sorted(os.listdir(directory)):
            if file.endswith(".wav"):
                path = os.path.join(directory, file)
                try:
                    sr, data = wavfile.read(path)
                    y = data.astype(np.float32) / 32768.0
                    if len(y.shape) > 1:
                        y = np.mean(y, axis=1)
                    # Resample to 16kHz
                    if sr != SAMPLE_RATE:
                        y = resample_wave(y, sr, SAMPLE_RATE)
                        
                    # Apply 80Hz Butterworth High-Pass Filter (strips sub-audible DC drift < 80Hz)
                    y, _ = signal.lfilter(HPF_B, HPF_A, y, zi=HPF_ZI)
                    y = y - np.mean(y)

                    is_real = file.startswith("pi_real_")

                    # Handle padding or multi-crop to target_len
                    if len(y) > target_len:
                        if is_real:
                            # Extract sliding window crops every 1600 samples (100ms)
                            # Exactly matches the 100ms chunk stepping in the daemon!
                            step = 1600
                            for start in range(0, len(y) - target_len + 1, step):
                                real_waves.append(y[start : start + target_len])
                        else:
                            if len(y) >= target_len + 3200:
                                crops = [
                                    y[:target_len],
                                    y[(len(y) - target_len) // 2 : (len(y) - target_len) // 2 + target_len],
                                    y[-target_len:]
                                ]
                                synth_waves.extend(crops)
                            else:
                                start = (len(y) - target_len) // 2
                                synth_waves.append(y[start:start+target_len])
                    else:
                        pad_len = target_len - len(y)
                        left = pad_len // 2
                        right = pad_len - left
                        padded = np.pad(y, (left, right), 'constant')
                        if is_real:
                            real_waves.append(padded)
                        else:
                            synth_waves.append(padded)
                except Exception as ex:
                    print(f"Failed to load {file}: {ex}", flush=True)
        return real_waves, synth_waves

    pos_real, pos_synth = load_waveforms(pos_dir)
    neg_real, neg_synth = load_waveforms(neg_dir)
    print(f"Loaded Positives: {len(pos_real)} real 100ms-sliding crops, {len(pos_synth)} synthetic.", flush=True)
    print(f"Loaded Negatives: {len(neg_real)} real 100ms-sliding crops, {len(neg_synth)} synthetic.", flush=True)

    # Data Augmentation & Dataset preparation
    X = []
    y = []

    # Augmentation functions
    def augment(wave, is_positive=True):
        # 1. Random subtle time jitter
        shift = np.random.randint(-1200, 1200) # up to 75ms jitter
        if shift > 0:
            aug_wave = np.pad(wave, (shift, 0), 'constant')[:-shift]
        elif shift < 0:
            aug_wave = np.pad(wave, (0, -shift), 'constant')[-shift:]
        else:
            aug_wave = wave.copy()
        
        # 2. Wide dynamic gain (0.2x to 2.0x) to train distance invariance (from 10cm up to 1.5m)
        gain = np.random.uniform(0.2, 2.0)
        aug_wave = aug_wave * gain
        
        # 3. Add ambient room sensor noise
        noise_level = np.random.uniform(0.0005, 0.005)
        aug_wave = aug_wave + np.random.randn(len(aug_wave)) * noise_level
        
        return np.clip(aug_wave, -1.0, 1.0)

    # 1. Add Real Positive samples (augmented 8x per 100ms alignment crop)
    for wave in pos_real:
        X.append(wave)
        y.append(1)
        for _ in range(8):
            X.append(augment(wave, is_positive=True))
            y.append(1)

    # 2. Add Synthetic Positive samples (2x)
    for wave in pos_synth:
        X.append(wave)
        y.append(1)
        for _ in range(2):
            X.append(augment(wave, is_positive=True))
            y.append(1)

    # 3. Add Real Negative samples (phonetic distractors from user: WhatsApp, Alexa, Siri, etc.) heavily augmented (16x per crop)
    for wave in neg_real:
        X.append(wave)
        y.append(0)
        for _ in range(16):
            X.append(augment(wave, is_positive=False))
            y.append(0)

    # 4. Add Synthetic Negative samples (1x)
    for wave in neg_synth:
        X.append(wave)
        y.append(0)
        if np.random.rand() < 0.5:
            X.append(augment(wave, is_positive=False))
            y.append(0)

    # 5. Add slices of real ambient room noise / background chatter if available
    ambient_files = [
        "scripts/calibration_audio/ambient_test1_03_naive_decimated_16k.wav",
        "scripts/calibration_audio/ambient_check_02_naive_16k.wav"
    ]
    for amb_file in ambient_files:
        if os.path.exists(amb_file):
            try:
                sr, amb_data = wavfile.read(amb_file)
                amb_y = amb_data.astype(np.float32) / 32768.0
                if len(amb_y.shape) > 1:
                    amb_y = np.mean(amb_y, axis=1)
                amb_y, _ = signal.lfilter(HPF_B, HPF_A, amb_y, zi=HPF_ZI)
                amb_y = amb_y - np.mean(amb_y)
                if len(amb_y) > INPUT_SIZE:
                    for _ in range(50):
                        idx = np.random.randint(0, len(amb_y) - INPUT_SIZE)
                        slice_y = amb_y[idx:idx + INPUT_SIZE] * np.random.uniform(0.3, 1.2)
                        X.append(np.clip(slice_y, -1.0, 1.0))
                        y.append(0)
            except Exception as e_amb:
                print(f"Note: Could not load ambient file {amb_file}: {e_amb}")

    # 6. Add pure silence and low-amplitude white noise examples
    for _ in range(100):
        X.append(np.zeros(INPUT_SIZE, dtype=np.float32))
        y.append(0)
        X.append((np.random.randn(INPUT_SIZE).astype(np.float32) * np.random.uniform(0.001, 0.005)))
        y.append(0)

    X = np.array(X, dtype=np.float32)
    y = np.array(y, dtype=np.int64)

    # Shuffle dataset
    indices = np.arange(len(X))
    np.random.shuffle(indices)
    X = X[indices]
    y = y[indices]

    # Add channel dimension: (Batch, 1, 24000)
    X = np.expand_dims(X, axis=1)

    print(f"Final training set size: {len(X)} (Positives: {np.sum(y == 1)}, Negatives: {np.sum(y == 0)})", flush=True)

    # Split to train and validation sets (90% / 10%)
    split_idx = int(len(X) * 0.9)
    X_train, X_val = X[:split_idx], X[split_idx:]
    y_train, y_val = y[:split_idx], y[split_idx:]

    # PyTorch DataLoaders
    train_dataset = TensorDataset(torch.tensor(X_train), torch.tensor(y_train))
    val_dataset = TensorDataset(torch.tensor(X_val), torch.tensor(y_val))
    train_loader = DataLoader(train_dataset, batch_size=32, shuffle=True)
    val_loader = DataLoader(val_dataset, batch_size=32, shuffle=False)

    # 3. 1D CNN Architecture
    class WakeWord1DCNN(nn.Module):
        def __init__(self, num_classes=2):
            super(WakeWord1DCNN, self).__init__()
            # Input: (Batch, 1, 24000)
            self.conv1 = nn.Conv1d(1, 16, kernel_size=81, stride=4, padding=40)
            self.bn1 = nn.BatchNorm1d(16)
            self.relu1 = nn.ReLU()
            self.pool1 = nn.MaxPool1d(4) # Output: (16, 1500)

            self.conv2 = nn.Conv1d(16, 32, kernel_size=25, stride=2, padding=12)
            self.bn2 = nn.BatchNorm1d(32)
            self.relu2 = nn.ReLU()
            self.pool2 = nn.MaxPool1d(4) # Output: (32, 187)

            self.conv3 = nn.Conv1d(32, 64, kernel_size=9, stride=2, padding=4)
            self.bn3 = nn.BatchNorm1d(64)
            self.relu3 = nn.ReLU()
            self.pool3 = nn.MaxPool1d(4) # Output: (64, 23)

            self.fc1 = nn.Linear(64 * 23, 64)
            self.relu_fc = nn.ReLU()
            self.dropout = nn.Dropout(0.3)
            self.fc2 = nn.Linear(64, num_classes)

        def forward(self, x):
            x = self.pool1(self.relu1(self.bn1(self.conv1(x))))
            x = self.pool2(self.relu2(self.bn2(self.conv2(x))))
            x = self.pool3(self.relu3(self.bn3(self.conv3(x))))
            x = x.view(x.size(0), -1)
            x = self.dropout(self.relu_fc(self.fc1(x)))
            x = self.fc2(x)
            return x

    model = WakeWord1DCNN()
    criterion = nn.CrossEntropyLoss()
    optimizer = optim.Adam(model.parameters(), lr=0.001)

    # 4. Training Loop
    epochs = 20
    print(f"Training 1D CNN Wake-Word Model for {epochs} epochs...", flush=True)
    for epoch in range(1, epochs + 1):
        model.train()
        train_loss = 0.0
        train_correct = 0
        for batch_x, batch_y in train_loader:
            optimizer.zero_grad()
            outputs = model(batch_x)
            loss = criterion(outputs, batch_y)
            loss.backward()
            optimizer.step()
            
            train_loss += loss.item() * batch_x.size(0)
            _, preds = torch.max(outputs, 1)
            train_correct += torch.sum(preds == batch_y).item()
            
        train_loss = train_loss / len(X_train)
        train_acc = train_correct / len(X_train)
        
        # Validation
        model.eval()
        val_loss = 0.0
        val_correct = 0
        with torch.no_grad():
            for batch_x, batch_y in val_loader:
                outputs = model(batch_x)
                loss = criterion(outputs, batch_y)
                val_loss += loss.item() * batch_x.size(0)
                _, preds = torch.max(outputs, 1)
                val_correct += torch.sum(preds == batch_y).item()
                
        val_loss = val_loss / len(X_val)
        val_acc = val_correct / len(X_val)
        
        print(f"Epoch {epoch}/{epochs} - Train Loss: {train_loss:.4f}, Train Acc: {train_acc:.4f} | Val Loss: {val_loss:.4f}, Val Acc: {val_acc:.4f}", flush=True)

    print("Model training completed successfully.", flush=True)

    # 5. Export to ONNX
    os.makedirs("output", exist_ok=True)
    onnx_path = os.path.join("output", "wakeword.onnx")
    print(f"Exporting wake-word model to ONNX at: {onnx_path}...", flush=True)
    
    model.eval()
    dummy_input = torch.randn(1, 1, INPUT_SIZE, dtype=torch.float32)
    
    try:
        torch.onnx.export(
            model,
            dummy_input,
            onnx_path,
            input_names=["input_audio"],
            output_names=["probabilities"],
            opset_version=15,
            dynamo=False
        )
    except Exception as ex_exp:
        print(f"Export fallback (dynamo): {ex_exp}", flush=True)
        torch.onnx.export(
            model,
            dummy_input,
            onnx_path,
            input_names=["input_audio"],
            output_names=["probabilities"],
            opset_version=15
        )
    print("ONNX model exported.", flush=True)

    final_model_path = onnx_path

    # Convert ONNX model to embed weights (convert from external data format to embedded)
    print("Converting ONNX model to embed weights...", flush=True)
    try:
        import onnx
        from onnx.external_data_helper import convert_model_from_external_data
        model_proto = onnx.load(onnx_path)
        # Downgrade IR version to 8 (supported by max supported IR version 9 on phone)
        model_proto.ir_version = 8
        convert_model_from_external_data(model_proto)
        
        # Save self-contained model directly to assets destination and scripts
        assets_dest = os.path.abspath("app/src/main/assets/wakeword.onnx")
        scripts_dest = os.path.abspath("scripts/wakeword.onnx")
        if os.path.exists(assets_dest):
            os.remove(assets_dest)
        onnx.save_model(model_proto, assets_dest)
        onnx.save_model(model_proto, scripts_dest)
        print(f"Saved self-contained wake-word model with IR=8 to: {assets_dest} and {scripts_dest}", flush=True)
    except Exception as ex:
        print(f"Failed to embed weights in ONNX: {ex}", flush=True)
        # Fallback to loading, overriding IR version to 8, and saving
        try:
            import onnx
            model_proto = onnx.load(final_model_path)
            model_proto.ir_version = 8
            assets_dest = os.path.abspath("app/src/main/assets/wakeword.onnx")
            scripts_dest = os.path.abspath("scripts/wakeword.onnx")
            if os.path.exists(assets_dest):
                os.remove(assets_dest)
            onnx.save_model(model_proto, assets_dest)
            onnx.save_model(model_proto, scripts_dest)
            print(f"Saved downgraded fallback wake-word model to: {assets_dest} and {scripts_dest}", flush=True)
        except Exception as ex2:
            print(f"Failed to write downgraded fallback: {ex2}", flush=True)

    print("Retaining dataset folder for future iterations.", flush=True)

if __name__ == "__main__":
    train_wakeword()

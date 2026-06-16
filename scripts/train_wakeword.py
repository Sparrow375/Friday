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

# Feature extraction settings matching the Android client
SAMPLE_RATE = 16000
DURATION_SECONDS = 1.5
INPUT_SIZE = int(SAMPLE_RATE * DURATION_SECONDS)  # 24000 samples

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
    print("Loading and preprocessing audio waveforms...", flush=True)
    
    def load_waveforms(directory, target_len=INPUT_SIZE):
        waveforms = []
        for file in os.listdir(directory):
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
                        
                    # Handle padding or cropping to target_len
                    if len(y) > target_len:
                        start = (len(y) - target_len) // 2
                        y = y[start:start+target_len]
                    else:
                        pad_len = target_len - len(y)
                        left = pad_len // 2
                        right = pad_len - left
                        y = np.pad(y, (left, right), 'constant')
                    waveforms.append(y)
                except Exception as ex:
                    print(f"Failed to load {file}: {ex}", flush=True)
        return waveforms

    pos_waves = load_waveforms(pos_dir)
    neg_waves = load_waveforms(neg_dir)
    print(f"Loaded {len(pos_waves)} positive and {len(neg_waves)} negative waveforms.", flush=True)

    # Data Augmentation & Dataset preparation
    X = []
    y = []

    # Augmentation functions
    def augment(wave):
        # 1. Random shift
        shift = np.random.randint(-2400, 2400) # up to 150ms
        if shift > 0:
            aug_wave = np.pad(wave, (shift, 0), 'constant')[:-shift]
        elif shift < 0:
            aug_wave = np.pad(wave, (0, -shift), 'constant')[-shift:]
        else:
            aug_wave = wave.copy()
        
        # 2. Random gain
        gain = np.random.uniform(0.7, 1.3)
        aug_wave = aug_wave * gain
        
        # 3. Add noise
        noise = np.random.randn(len(aug_wave)) * np.random.uniform(0.001, 0.01)
        aug_wave = aug_wave + noise
        
        return np.clip(aug_wave, -1.0, 1.0)

    # Balance the dataset by repeating positive examples with augmentation
    multiplier = max(1, len(neg_waves) // len(pos_waves))
    
    for wave in pos_waves:
        X.append(wave)
        y.append(1)
        for _ in range(multiplier + 1):
            X.append(augment(wave))
            y.append(1)

    for wave in neg_waves:
        X.append(wave)
        y.append(0)
        X.append(augment(wave))
        y.append(0)

    # Add pure silence and white noise examples
    for _ in range(100):
        X.append(np.zeros(INPUT_SIZE, dtype=np.float32))
        y.append(0)
        X.append(np.random.randn(INPUT_SIZE).astype(np.float32) * 0.01)
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
    
    torch.onnx.export(
        model,
        dummy_input,
        onnx_path,
        input_names=["input_audio"],
        output_names=["probabilities"],
        opset_version=18
    )
    print("ONNX model exported.", flush=True)

    final_model_path = onnx_path

    # Copy to assets folder
    assets_dest = os.path.abspath("app/src/main/assets/wakeword.onnx")
    shutil.copy2(final_model_path, assets_dest)
    print(f"Copied finalized wake-word model to assets: {assets_dest}", flush=True)

    # Clean up temporary dataset folder
    print("Cleaning up temporary dataset files...", flush=True)
    shutil.rmtree(data_dir, ignore_errors=True)
    print("Data synthesis dataset cleaned up.", flush=True)

if __name__ == "__main__":
    train_wakeword()

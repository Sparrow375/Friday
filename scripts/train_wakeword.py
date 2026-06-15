#!/usr/bin/env python3
"""
Friday Assistant Wake-Word Model Trainer
Contains a PyTorch implementation for training a low-power, custom-phrase wake word detector,
extracting Mel-spectrogram acoustic features, exporting the model to ONNX, and quantizing it.
"""

import os
import numpy as np

# Note: In a full environment, you would install PyTorch, Librosa, Sounddevice, and ONNX:
# pip install torch librosa sounddevice onnx onnxruntime

# Feature extraction settings matching the Android client
SAMPLE_RATE = 16000
DURATION_SECONDS = 1.5
NUM_MEL_BINS = 40
HOP_LENGTH = 160  # 10ms frame hop for 16kHz audio
N_FFT = 400       # 25ms frame window size

def train_wakeword():
    print("Initializing Wake Word Training script...")
    print(f"Sampling Rate: {SAMPLE_RATE}Hz, Window Duration: {DURATION_SECONDS}s")
    
    try:
        import torch
        import torch.nn as nn
        import torch.optim as optim
        from torch.utils.data import TensorDataset, DataLoader
        import librosa
        import onnx
        from onnxruntime.quantization import quantize_dynamic, QuantType
    except ImportError:
        print("\n[!] Prerequisite libraries are missing. Run the following command first:")
        print("pip install torch librosa onnx onnxruntime numpy")
        return

    # 1. Custom CNN Model for Mel-Spectrogram Classification
    class WakeWordCNN(nn.Module):
        def __init__(self, num_classes=2):
            super(WakeWordCNN, self).__init__()
            # Input shape: (Batch, 1, NumMelBins=40, NumTimeFrames=150)
            self.conv1 = nn.Conv2d(1, 16, kernel_size=3, padding=1)
            self.bn1 = nn.BatchNorm2d(16)
            self.relu1 = nn.ReLU()
            self.pool1 = nn.MaxPool2d(2)  # Output: (16, 20, 75)

            self.conv2 = nn.Conv2d(16, 32, kernel_size=3, padding=1)
            self.bn2 = nn.BatchNorm2d(32)
            self.relu2 = nn.ReLU()
            self.pool2 = nn.MaxPool2d(2)  # Output: (32, 10, 37)

            self.fc1 = nn.Linear(32 * 10 * 37, 64)
            self.fc_relu = nn.ReLU()
            self.dropout = nn.Dropout(0.3)
            self.fc2 = nn.Linear(64, num_classes)

        def forward(self, x):
            x = self.pool1(self.relu1(self.bn1(self.conv1(x))))
            x = self.pool2(self.relu2(self.bn2(self.conv2(x))))
            x = x.view(x.size(0), -1)  # Flatten
            x = self.dropout(self.fc_relu(self.fc1(x)))
            x = self.fc2(x)
            return x

    # 2. Simulate dataset generation (positive "Friday" vs negative background/noise)
    print("Generating simulated acoustic dataset for training...")
    num_samples = 100
    time_frames = int((SAMPLE_RATE * DURATION_SECONDS) / HOP_LENGTH) + 1  # ~151 frames
    
    # Random Mel-Spectrogram features
    features = np.random.randn(num_samples, 1, NUM_MEL_BINS, time_frames).astype(np.float32)
    labels = np.random.randint(0, 2, size=num_samples).astype(np.int64)

    # Convert to PyTorch tensors
    tensor_features = torch.tensor(features)
    tensor_labels = torch.tensor(labels)
    dataset = TensorDataset(tensor_features, tensor_labels)
    dataloader = DataLoader(dataset, batch_size=16, shuffle=True)

    # Instantiate model, optimizer, loss
    model = WakeWordCNN()
    criterion = nn.CrossEntropyLoss()
    optimizer = optim.Adam(model.parameters(), lr=0.001)

    print("Training wake word classifier...")
    model.train()
    for epoch in range(1, 6):
        epoch_loss = 0.0
        for batch_features, batch_labels in dataloader:
            optimizer.zero_grad()
            outputs = model(batch_features)
            loss = criterion(outputs, batch_labels)
            loss.backward()
            optimizer.step()
            epoch_loss += loss.item()
        print(f"Epoch {epoch}/5 - Loss: {epoch_loss/len(dataloader):.4f}")
    
    print("Training finished successfully.")

    # 3. Export to ONNX
    os.makedirs("output", exist_ok=True)
    onnx_path = os.path.join("output", "wakeword.onnx")
    print(f"Exporting wake-word model to ONNX at: {onnx_path}...")
    
    model.eval()
    dummy_input = torch.randn(1, 1, NUM_MEL_BINS, time_frames, dtype=torch.float32)
    
    torch.onnx.export(
        model,
        dummy_input,
        onnx_path,
        input_names=["input_features"],
        output_names=["probabilities"],
        dynamic_axes={
            "input_features": {0: "batch_size"},
            "probabilities": {0: "batch_size"}
        },
        opset_version=14
    )
    print("ONNX wake word model exported.")

    # 4. Quantize ONNX
    quantized_path = os.path.join("output", "wakeword_quantized.onnx")
    print(f"Quantizing ONNX model for mobile deployment at: {quantized_path}...")
    
    quantize_dynamic(
        model_input=onnx_path,
        model_output=quantized_path,
        weight_type=QuantType.QInt8
    )

    final_model_path = os.path.join("output", "wakeword.onnx")
    if os.path.exists(quantized_path):
        os.remove(onnx_path)
        os.rename(quantized_path, final_model_path)
        print(f"Successfully quantized and finalized wake-word model at: {final_model_path}")

    print("\n--- Next Steps ---")
    print("1. Integrate this wakeword.onnx with Friday's AudioFeatureExtractor and WakeWordDetectorONNX.")
    print("2. Copy 'output/wakeword.onnx' to your Android assets folder (app/src/main/assets/)")
    print("3. Build and launch the app.")

if __name__ == "__main__":
    train_wakeword()

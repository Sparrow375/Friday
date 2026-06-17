#!/usr/bin/env python3
"""
Friday Assistant NLU Training Script
Fine-tunes a lightweight transformer (e.g. DistilBERT) for custom intent classification,
exports the model to ONNX, and performs INT8 quantization for Android deployment.
"""

import os
import json
import torch
import torch.nn as nn
from torch.utils.data import Dataset, DataLoader

# Note: In a full environment, you would install HuggingFace transformers, optimum, and onnxruntime:
# pip install transformers onnx onnxruntime optuna

# 1. Custom Intent Schema definition
INTENT_LABELS = [
    "volume_up", "volume_down", "brightness_up", "brightness_down", 
    "torch_toggle", "torch_strength", "lock_phone", "open_app", 
    "navigate_to", "set_alarm", "set_timer", "send_whatsapp", 
    "play_media", "power_saver_toggle", "screencast_toggle",
    "wifi_toggle", "bluetooth_toggle", "hotspot_toggle", "unknown"
]
INTENT_TO_ID = {intent: i for i, intent in enumerate(INTENT_LABELS)}

# Pre-packaged training samples
TRAINING_DATA = [
    # volume_up
    ("increase volume", "volume_up"),
    ("volume up", "volume_up"),
    ("make it louder", "volume_up"),
    ("turn up the music", "volume_up"),
    ("increase the sound level", "volume_up"),
    ("raise the volume", "volume_up"),
    # volume_down
    ("decrease volume", "volume_down"),
    ("volume down", "volume_down"),
    ("lower the music", "volume_down"),
    ("make it quieter", "volume_down"),
    ("turn down the sound", "volume_down"),
    ("mute volume", "volume_down"),
    # brightness_up
    ("increase brightness", "brightness_up"),
    ("make screen brighter", "brightness_up"),
    ("raise brightness", "brightness_up"),
    ("screen is too dim", "brightness_up"),
    ("set brightness to maximum", "brightness_up"),
    # brightness_down
    ("decrease brightness", "brightness_down"),
    ("make screen dimmer", "brightness_down"),
    ("lower screen brightness", "brightness_down"),
    ("dim the screen", "brightness_down"),
    ("brightness to low", "brightness_down"),
    # torch_toggle
    ("turn on flashlight", "torch_toggle"),
    ("flashlight off", "torch_toggle"),
    ("torch on", "torch_toggle"),
    ("disable flashlight", "torch_toggle"),
    ("toggle torch", "torch_toggle"),
    # torch_strength
    ("set flashlight strength to maximum", "torch_strength"),
    ("dim torch to fifty percent", "torch_strength"),
    ("torch brightness low", "torch_strength"),
    ("maximum flashlight brightness", "torch_strength"),
    ("change torch strength", "torch_strength"),
    # lock_phone
    ("lock the phone", "lock_phone"),
    ("lock my screen", "lock_phone"),
    ("lock phone", "lock_phone"),
    ("lock screen", "lock_phone"),
    ("please lock my phone", "lock_phone"),
    ("turn off screen", "lock_phone"),
    # open_app
    ("open brave", "open_app"),
    ("open spotify", "open_app"),
    ("launch whatsapp", "open_app"),
    ("start gmail app", "open_app"),
    ("open the settings dashboard", "open_app"),
    ("launch settings", "open_app"),
    # navigate_to
    ("navigate to new york", "navigate_to"),
    ("directions to san francisco", "navigate_to"),
    ("drive to starbucks", "navigate_to"),
    ("show route to the airport", "navigate_to"),
    ("go to central park", "navigate_to"),
    # set_alarm
    ("set alarm for seven am", "set_alarm"),
    ("wake me up at six thirty", "set_alarm"),
    ("create alarm for noon", "set_alarm"),
    ("alarm for tomorrow at eight", "set_alarm"),
    # set_timer
    ("set timer for five minutes", "set_timer"),
    ("countdown for ten seconds", "set_timer"),
    ("timer for two hours", "set_timer"),
    ("start a timer", "set_timer"),
    # send_whatsapp
    ("send message to dad on whatsapp", "send_whatsapp"),
    ("whatsapp mom saying i am running late", "send_whatsapp"),
    ("text brother via whatsapp", "send_whatsapp"),
    ("send whatsapp message to friend", "send_whatsapp"),
    # play_media
    ("play some music on spotify", "play_media"),
    ("listen to beatles", "play_media"),
    ("start playing pop songs", "play_media"),
    ("play song", "play_media"),
    # power_saver_toggle
    ("enable battery saver", "power_saver_toggle"),
    ("turn off power saver", "power_saver_toggle"),
    ("battery saver on", "power_saver_toggle"),
    ("power saving mode", "power_saver_toggle"),
    # screencast_toggle
    ("start screen cast", "screencast_toggle"),
    ("stop screen mirroring", "screencast_toggle"),
    ("smart view on", "screencast_toggle"),
    ("enable screen mirroring", "screencast_toggle"),
    # wifi_toggle
    ("turn on wifi", "wifi_toggle"),
    ("disable wifi", "wifi_toggle"),
    ("enable wireless internet", "wifi_toggle"),
    ("switch off the wifi", "wifi_toggle"),
    ("wifi on", "wifi_toggle"),
    # bluetooth_toggle
    ("turn on bluetooth", "bluetooth_toggle"),
    ("disable bluetooth", "bluetooth_toggle"),
    ("enable bluetooth connection", "bluetooth_toggle"),
    ("switch off bluetooth receiver", "bluetooth_toggle"),
    ("bluetooth on", "bluetooth_toggle"),
    # hotspot_toggle
    ("turn on mobile hotspot", "hotspot_toggle"),
    ("disable internet sharing", "hotspot_toggle"),
    ("enable personal hotspot", "hotspot_toggle"),
    ("switch off portable hotspot", "hotspot_toggle"),
    ("hotspot on", "hotspot_toggle"),
    # unknown
    ("what is the weather today", "unknown"),
    ("who is the president of france", "unknown"),
    ("how do i bake a cake", "unknown"),
    ("tell me a joke", "unknown"),
    ("hello friday how are you", "unknown")
]

class IntentDataset(Dataset):
    def __init__(self, data, tokenizer, max_len=32):
        self.data = data
        self.tokenizer = tokenizer
        self.max_len = max_len

    def __len__(self):
        return len(self.data)

    def __getitem__(self, index):
        text, intent = self.data[index]
        label = INTENT_TO_ID[intent]
        
        # Simulating standard HuggingFace tokenizer encoding
        encoding = self.tokenizer(
            text,
            padding="max_length",
            truncation=True,
            max_length=self.max_len,
            return_tensors="pt"
        )
        
        return {
            "input_ids": encoding["input_ids"].squeeze(0),
            "attention_mask": encoding["attention_mask"].squeeze(0),
            "label": torch.tensor(label, dtype=torch.long)
        }

def train_model():
    print("Initializing training script...")
    print(f"Intents recognized: {INTENT_LABELS}")
    
    try:
        from transformers import AutoTokenizer, AutoModelForSequenceClassification, Trainer, TrainingArguments
        import onnxruntime
        from onnxruntime.quantization import quantize_dynamic, QuantType
    except ImportError:
        print("\n[!] Prerequisite libraries are missing. Run the following command first:")
        print("pip install transformers torch onnx onnxruntime")
        return

    model_name = "distilbert-base-uncased"
    print(f"Downloading pre-trained tokenizer: {model_name}...")
    tokenizer = AutoTokenizer.from_pretrained(model_name)
    
    # Save vocab.txt directly for Android WordPiece Tokenizer integration
    vocab = tokenizer.get_vocab()
    sorted_vocab = sorted(vocab.items(), key=lambda x: x[1])
    
    os.makedirs("output", exist_ok=True)
    vocab_path = os.path.join("output", "vocab.txt")
    with open(vocab_path, "w", encoding="utf-8") as f:
        for word, _ in sorted_vocab:
            f.write(word + "\n")
    print(f"Saved vocabulary to: {vocab_path}")

    # Save labels.txt directly for dynamic label mapping in Android
    labels_path = os.path.join("output", "labels.txt")
    with open(labels_path, "w", encoding="utf-8") as f:
        for label in INTENT_LABELS:
            f.write(label + "\n")
    print(f"Saved labels mapping to: {labels_path}")

    # Build datasets
    dataset = IntentDataset(TRAINING_DATA, tokenizer)
    
    print("Loading pre-trained classification model...")
    model = AutoModelForSequenceClassification.from_pretrained(
        model_name,
        num_labels=len(INTENT_LABELS)
    )

    # Simple training loop parameters
    training_args = TrainingArguments(
        output_dir="./results",
        num_train_epochs=10,
        per_device_train_batch_size=8,
        logging_steps=10,
        learning_rate=5e-5,
        weight_decay=0.01,
        save_strategy="no"
    )

    trainer = Trainer(
        model=model,
        args=training_args,
        train_dataset=dataset,
    )

    print("Starting training...")
    trainer.train()
    print("Training complete!")

    # 2. Export Model to ONNX format
    onnx_path = os.path.join("output", "nlu_model.onnx")
    print(f"Exporting model to ONNX format at: {onnx_path}...")
    
    model.eval()
    dummy_input_ids = torch.ones(1, 16, dtype=torch.long)
    dummy_attention_mask = torch.ones(1, 16, dtype=torch.long)
    
    torch.onnx.export(
        model,
        (dummy_input_ids, dummy_attention_mask),
        onnx_path,
        input_names=["input_ids", "attention_mask"],
        output_names=["logits"],
        dynamic_axes={
            "input_ids": {0: "batch_size", 1: "sequence_length"},
            "attention_mask": {0: "batch_size", 1: "sequence_length"},
            "logits": {0: "batch_size"}
        },
        opset_version=14
    )
    print("ONNX model successfully exported.")

    # 3. Dynamic INT8 Quantization for Mobile/Android deployment
    quantized_path = os.path.join("output", "nlu_model_quantized.onnx")
    print(f"Quantizing ONNX model for mobile deployment at: {quantized_path}...")
    
    quantize_dynamic(
        model_input=onnx_path,
        model_output=quantized_path,
        weight_type=QuantType.QInt8
    )
    
    # Rename quantized model to final required asset filename
    final_model_path = os.path.join("output", "nlu_model.onnx")
    if os.path.exists(quantized_path):
        os.remove(onnx_path)
        os.rename(quantized_path, final_model_path)
        print(f"Successfully quantized and finalized model at: {final_model_path}")
    
    print("\n--- Next Steps ---")
    print(f"1. Copy 'output/vocab.txt' to your Android project assets folder (app/src/main/assets/)")
    print(f"2. Copy 'output/nlu_model.onnx' to your Android project assets folder (app/src/main/assets/)")
    print("3. Build and launch your app. Friday will run the custom trained NLU model locally!")

if __name__ == "__main__":
    train_model()

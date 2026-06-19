#!/usr/bin/env python3
"""
Friday Assistant NLU Training Script (local fallback).
For full dataset augmentation use scripts/friday_training.ipynb on Google Colab.
"""

import os
import torch
from torch.utils.data import Dataset

INTENT_LABELS = [
    "volume_up", "volume_down", "brightness_up", "brightness_down",
    "torch_toggle", "torch_strength", "lock_phone", "open_app",
    "navigate_to", "set_alarm", "set_timer", "send_whatsapp",
    "play_media", "play_spotify", "play_youtube",
    "pause_media", "next_track", "previous_track",
    "power_saver_toggle", "screencast_toggle",
    "wifi_toggle", "bluetooth_toggle", "hotspot_toggle", "dnd_toggle",
    "call_contact", "send_sms", "read_sms", "read_call_log",
    "take_screenshot", "web_search",
    "clipboard_read", "clipboard_write",
    "read_notifications", "get_battery", "get_time",
    "airplane_mode_toggle", "mobile_data_toggle", "unknown",
]
INTENT_TO_ID = {intent: i for i, intent in enumerate(INTENT_LABELS)}

TRAINING_DATA = [
    ("increase volume", "volume_up"), ("make it louder", "volume_up"), ("turn it down", "volume_down"),
    ("turn on flashlight", "torch_toggle"), ("turn off flashlight", "torch_toggle"), ("turn it off", "torch_toggle"),
    ("turn on wifi", "wifi_toggle"), ("disable bluetooth", "bluetooth_toggle"), ("lock my screen", "lock_phone"),
    ("open spotify", "open_app"), ("navigate to home", "navigate_to"), ("set alarm for seven am", "set_alarm"),
    ("set timer for five minutes", "set_timer"), ("send message to dad on whatsapp", "send_whatsapp"),
    ("play sunflower on spotify", "play_spotify"), ("search cats on youtube", "play_youtube"),
    ("pause the music", "pause_media"), ("next track", "next_track"), ("previous song", "previous_track"),
    ("call mom", "call_contact"), ("can you call john", "call_contact"), ("dial dad", "call_contact"),
    ("text mom saying i am late", "send_sms"), ("read my messages", "read_sms"), ("recent calls", "read_call_log"),
    ("take a screenshot", "take_screenshot"), ("capture the screen", "take_screenshot"),
    ("read clipboard", "clipboard_read"), ("copy hello to clipboard", "clipboard_write"),
    ("check notifications", "read_notifications"), ("battery level", "get_battery"), ("what time is it", "get_time"),
    ("turn on do not disturb", "dnd_toggle"), ("enable airplane mode", "airplane_mode_toggle"),
    ("turn off mobile data", "mobile_data_toggle"), ("search for weather today", "web_search"),
    ("what is the capital of france", "unknown"), ("tell me a joke", "unknown"),
    ("set alarm for [TIME_1]", "set_alarm"), ("wake me up at [TIME_1]", "set_alarm"),
    ("send message to [CONTACT] saying [QUOTE]", "send_whatsapp"), ("whatsapp [CONTACT] saying [QUOTE]", "send_whatsapp"),
    ("text [CONTACT] saying [QUOTE]", "send_sms"), ("sms [CONTACT] saying [QUOTE]", "send_sms"),
    ("call [PHONE_1]", "call_contact"), ("dial [PHONE_1]", "call_contact"),
    ("copy [QUOTE_1] to clipboard", "clipboard_write"),
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
        encoding = self.tokenizer(text, padding="max_length", truncation=True, max_length=self.max_len, return_tensors="pt")
        return {
            "input_ids": encoding["input_ids"].squeeze(0),
            "attention_mask": encoding["attention_mask"].squeeze(0),
            "label": torch.tensor(label, dtype=torch.long),
        }

def train_model():
    print("Initializing NLU training...")
    print(f"Intent classes: {len(INTENT_LABELS)}")
    try:
        from transformers import AutoTokenizer, AutoModelForSequenceClassification, Trainer, TrainingArguments
        from onnxruntime.quantization import quantize_dynamic, QuantType
    except ImportError:
        print("pip install transformers torch onnx onnxruntime")
        return

    model_name = "google/mobilebert-uncased"
    tokenizer = AutoTokenizer.from_pretrained(model_name)
    os.makedirs("output", exist_ok=True)

    with open(os.path.join("output", "vocab.txt"), "w", encoding="utf-8") as f:
        for word, _ in sorted(tokenizer.get_vocab().items(), key=lambda x: x[1]):
            f.write(word + "\n")
    with open(os.path.join("output", "labels.txt"), "w", encoding="utf-8") as f:
        for label in INTENT_LABELS:
            f.write(label + "\n")

    dataset = IntentDataset(TRAINING_DATA, tokenizer)
    model = AutoModelForSequenceClassification.from_pretrained(model_name, num_labels=len(INTENT_LABELS))
    training_args = TrainingArguments(
        output_dir="./results", num_train_epochs=10, per_device_train_batch_size=8,
        logging_steps=10, learning_rate=5e-5, weight_decay=0.01, save_strategy="no",
    )
    Trainer(model=model, args=training_args, train_dataset=dataset).train()

    onnx_path = os.path.join("output", "nlu_model.onnx")
    model.eval()
    dummy_ids = torch.ones(1, 16, dtype=torch.long)
    dummy_mask = torch.ones(1, 16, dtype=torch.long)
    torch.onnx.export(model, (dummy_ids, dummy_mask), onnx_path,
        input_names=["input_ids", "attention_mask"], output_names=["logits"],
        dynamic_axes={"input_ids": {0: "batch_size", 1: "sequence_length"},
                      "attention_mask": {0: "batch_size", 1: "sequence_length"},
                      "logits": {0: "batch_size"}}, opset_version=14)

    quantized_path = os.path.join("output", "nlu_model_quantized.onnx")
    quantize_dynamic(model_input=onnx_path, model_output=quantized_path, weight_type=QuantType.QInt8)
    if os.path.exists(quantized_path):
        os.remove(onnx_path)
        os.rename(quantized_path, onnx_path)
    print("Done. Copy output/nlu_model.onnx, vocab.txt, labels.txt to app/src/main/assets/")

if __name__ == "__main__":
    train_model()

#!/usr/bin/env python3
"""Copy trained Joint NLU assets to Android project assets"""

import os
import shutil
import json
from transformers import AutoTokenizer

src_dir = os.path.join(os.path.dirname(__file__), "..", "output", "friday_joint_nlu", "output")
dst_dir = os.path.join(os.path.dirname(__file__), "..", "app", "src", "main", "assets")
os.makedirs(dst_dir, exist_ok=True)

# 1. Copy ONNX model
model_src = os.path.join(src_dir, "joint_nlu_model.onnx")
model_dst = os.path.join(dst_dir, "joint_nlu_model.onnx")
shutil.copy2(model_src, model_dst)

# 2. Copy JSON labels
shutil.copy2(os.path.join(src_dir, "joint_intent_labels.json"), os.path.join(dst_dir, "joint_intent_labels.json"))
shutil.copy2(os.path.join(src_dir, "joint_slot_labels.json"), os.path.join(dst_dir, "joint_slot_labels.json"))

# 3. Create line-delimited labels for easy Android reading
with open(os.path.join(src_dir, "joint_intent_labels.json"), "r") as f:
    intents = json.load(f)
with open(os.path.join(dst_dir, "labels.txt"), "w", encoding="utf-8") as f:
    f.write("\n".join(intents) + "\n")

with open(os.path.join(src_dir, "joint_slot_labels.json"), "r") as f:
    slots = json.load(f)
with open(os.path.join(dst_dir, "slot_labels.txt"), "w", encoding="utf-8") as f:
    f.write("\n".join(slots) + "\n")

# 4. Export exact MiniLM vocab
tokenizer = AutoTokenizer.from_pretrained("sentence-transformers/all-MiniLM-L6-v2")
vocab = tokenizer.get_vocab()
sorted_vocab = sorted(vocab.items(), key=lambda x: x[1])
with open(os.path.join(dst_dir, "vocab.txt"), "w", encoding="utf-8") as f:
    for word, _ in sorted_vocab:
        f.write(word + "\n")

print(f"Successfully copied assets!")
print(f"- Model: {model_dst} ({os.path.getsize(model_dst)/(1024*1024):.1f} MB)")
print(f"- Intents: {len(intents)} labels")
print(f"- Slots: {len(slots)} tags")
print(f"- Vocab: {len(sorted_vocab)} tokens")

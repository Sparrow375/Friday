import os
import json
import time
import re
import onnxruntime as ort
import numpy as np
from transformers import AutoTokenizer

joint_model_path = r'f:\Avaneesh\projects\Friday\app\src\main\assets\joint_nlu_model.onnx'
legacy_model_path = r'f:\Avaneesh\projects\Friday\app\src\main\assets\nlu_model.onnx'
labels_path = r'f:\Avaneesh\projects\Friday\app\src\main\assets\labels.txt'
slot_labels_path = r'f:\Avaneesh\projects\Friday\app\src\main\assets\slot_labels.txt'
vocab_path = r'f:\Avaneesh\projects\Friday\app\src\main\assets\vocab.txt'

with open(labels_path, 'r') as f:
    intent_labels = [l.strip() for l in f if l.strip()]

with open(slot_labels_path, 'r') as f:
    slot_labels = [l.strip() for l in f if l.strip()]

# Load custom vocab for Kotlin-style WordpieceTokenizer
vocab_map = {}
with open(vocab_path, 'r', encoding='utf-8') as f:
    for idx, line in enumerate(f):
        w = line.strip()
        if w:
            vocab_map[w] = idx

def kotlin_tokenize(text):
    clean = re.sub(r'([^a-z0-9#])', r' \1 ', text.lower()).strip()
    words = [w for w in clean.split() if w]
    token_ids = []
    token_strings = []
    unk_id = vocab_map.get('[UNK]', 100)
    for word in words:
        start = 0
        l = len(word)
        while start < l:
            end = l
            matched_id = -1
            matched_str = ''
            while start < end:
                sub = word[start:end]
                if start > 0:
                    sub = '##' + sub
                if sub in vocab_map:
                    matched_id = vocab_map[sub]
                    matched_str = sub
                    break
                end -= 1
            if matched_id == -1:
                token_ids.append(unk_id)
                token_strings.append('[UNK]')
                break
            token_ids.append(matched_id)
            token_strings.append(matched_str)
            start = end
    return token_ids, token_strings

session_joint = ort.InferenceSession(joint_model_path)
session_legacy = ort.InferenceSession(legacy_model_path) if os.path.exists(legacy_model_path) else None
hf_tok = AutoTokenizer.from_pretrained('sentence-transformers/all-MiniLM-L6-v2')

test_queries = [
    'search how to make cake on youtube',
    'search icc on google',
    'turn it off',
    'turn it on',
    'its dark',
    'open reddit',
    'play honepie'
]

print('=== 1. HF Tokenizer Padded (max_length=48) ===')
for q in test_queries:
    enc = hf_tok(q, return_tensors='np', padding='max_length', truncation=True, max_length=48)
    out = session_joint.run(None, {'input_ids': enc['input_ids'].astype(np.int64), 'attention_mask': enc['attention_mask'].astype(np.int64)})
    intent_logits = out[0][0]
    exp = np.exp(intent_logits - np.max(intent_logits))
    probs = exp / np.sum(exp)
    best_idx = np.argmax(probs)
    print(f'Query: "{q}" -> {intent_labels[best_idx]} ({probs[best_idx]:.3f})')

print('\n=== 2. HF Tokenizer Unpadded (seqLen = exact) ===')
for q in test_queries:
    enc = hf_tok(q, return_tensors='np')
    out = session_joint.run(None, {'input_ids': enc['input_ids'].astype(np.int64), 'attention_mask': enc['attention_mask'].astype(np.int64)})
    intent_logits = out[0][0]
    exp = np.exp(intent_logits - np.max(intent_logits))
    probs = exp / np.sum(exp)
    best_idx = np.argmax(probs)
    print(f'Query: "{q}" -> {intent_labels[best_idx]} ({probs[best_idx]:.3f})')

print('\n=== 3. Kotlin WordpieceTokenizer Unpadded (What Android NluIntentClassifier.kt runs!) ===')
for q in test_queries:
    t_ids, t_strs = kotlin_tokenize(q)
    input_ids = np.array([[101] + t_ids + [102]], dtype=np.int64)
    att_mask = np.ones_like(input_ids, dtype=np.int64)
    out = session_joint.run(None, {'input_ids': input_ids, 'attention_mask': att_mask})
    intent_logits = out[0][0]
    exp = np.exp(intent_logits - np.max(intent_logits))
    probs = exp / np.sum(exp)
    best_idx = np.argmax(probs)
    
    slot_logits = out[1][0]
    token_slot_indices = np.argmax(slot_logits, axis=-1)
    all_toks = ['[CLS]'] + t_strs + ['[SEP]']
    print(f'Query: "{q}" -> {intent_labels[best_idx]} ({probs[best_idx]:.3f})')
    print('   Tokens:', all_toks)
    print('   Slots:', [slot_labels[si] for si in token_slot_indices[:len(all_toks)]])

if session_legacy:
    print('\n=== 4. Legacy MobileBERT Model (nlu_model.onnx) ===')
    for q in test_queries:
        t_ids, t_strs = kotlin_tokenize(q)
        input_ids = np.array([[101] + t_ids + [102]], dtype=np.int64)
        att_mask = np.ones_like(input_ids, dtype=np.int64)
        try:
            out = session_legacy.run(None, {'input_ids': input_ids, 'attention_mask': att_mask})
            logits = out[0][0]
            exp = np.exp(logits - np.max(logits))
            probs = exp / np.sum(exp)
            best_idx = np.argmax(probs)
            print(f'Legacy Model - Query: "{q}" -> {intent_labels[best_idx] if best_idx < len(intent_labels) else best_idx} ({probs[best_idx]:.3f})')
        except Exception as e:
            print(f'Legacy model error for "{q}": {e}')

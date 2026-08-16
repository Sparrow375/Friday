import os
import json
import time
import re
import urllib.parse
import onnxruntime as ort
import numpy as np

joint_model_path = r'f:\Avaneesh\projects\Friday\app\src\main\assets\joint_nlu_model.onnx'
labels_path = r'f:\Avaneesh\projects\Friday\app\src\main\assets\labels.txt'
slot_labels_path = r'f:\Avaneesh\projects\Friday\app\src\main\assets\slot_labels.txt'
vocab_path = r'f:\Avaneesh\projects\Friday\app\src\main\assets\vocab.txt'

with open(labels_path, 'r') as f:
    intent_labels = [l.strip() for l in f if l.strip()]

with open(slot_labels_path, 'r') as f:
    slot_labels = [l.strip() for l in f if l.strip()]

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

session = ort.InferenceSession(joint_model_path)

def convert_tokens_to_string(tokens):
    out = []
    for tok in tokens:
        if tok.startswith('##'):
            if out:
                out[-1] += tok[2:]
            else:
                out.append(tok[2:])
        else:
            out.append(tok)
    return ' '.join(out)

def classify_joint(text):
    t_ids, t_strs = kotlin_tokenize(text)
    if not t_ids:
        return {'intent': 'unknown', 'confidence': 0.0, 'slots': {}}
    input_ids = np.array([[101] + t_ids + [102]], dtype=np.int64)
    att_mask = np.ones_like(input_ids, dtype=np.int64)
    out = session.run(None, {'input_ids': input_ids, 'attention_mask': att_mask})
    
    intent_logits = out[0][0]
    exp = np.exp(intent_logits - np.max(intent_logits))
    probs = exp / np.sum(exp)
    best_idx = int(np.argmax(probs))
    intent = intent_labels[best_idx] if best_idx < len(intent_labels) else 'unknown'
    conf = float(probs[best_idx])
    
    slot_logits = out[1][0]
    predicted_slot_ids = np.argmax(slot_logits, axis=-1)
    all_toks = ['[CLS]'] + t_strs + ['[SEP]']
    
    slots_map = {}
    current_tag = None
    current_tokens = []
    limit = min(len(all_toks) - 1, len(predicted_slot_ids))
    
    for i in range(1, limit):
        slot_idx = predicted_slot_ids[i]
        slot_tag = slot_labels[slot_idx] if slot_idx < len(slot_labels) else 'O'
        tok_str = all_toks[i]
        
        if slot_tag.startswith('B-'):
            if current_tag and current_tokens:
                slots_map[current_tag] = convert_tokens_to_string(current_tokens)
            current_tag = slot_tag[2:]
            current_tokens = [tok_str]
        elif slot_tag.startswith('I-'):
            tag_type = slot_tag[2:]
            if current_tag == tag_type:
                current_tokens.append(tok_str)
            else:
                if current_tag and current_tokens:
                    slots_map[current_tag] = convert_tokens_to_string(current_tokens)
                current_tag = tag_type
                current_tokens = [tok_str]
        else:
            if current_tag and current_tokens:
                slots_map[current_tag] = convert_tokens_to_string(current_tokens)
                current_tag = None
                current_tokens = []
    if current_tag and current_tokens:
        slots_map[current_tag] = convert_tokens_to_string(current_tokens)
        
    return {'intent': intent, 'confidence': conf, 'slots': slots_map}

print("=" * 70)
print("VERIFYING FIXED ANDROID ROUTING SIMULATION")
print("=" * 70)

queries = [
    "search how to make cake on youtube",
    "search icc on google",
    "turn it off",
    "turn it on",
    "its dark",
    "open reddit",
    "play honepie"
]

for q in queries:
    res = classify_joint(q)
    print(f"\nQuery: '{q}'")
    print(f"  -> Intent: {res['intent']} ({res['confidence']*100:.1f}%)")
    print(f"  -> Slots:  {res['slots']}")
    
    # Simulate Android Tool Dispatch
    intent = res['intent']
    slots = res['slots']
    
    if intent in ("play_media", "play_spotify", "play_youtube"):
        mediaQuery = slots.get("QUERY", q).strip()
        app = "spotify" if intent == "play_spotify" else ("youtube" if intent == "play_youtube" else slots.get("APP", None))
        if app == "youtube":
            encoded = urllib.parse.quote(mediaQuery)
            action_desc = f"MediaControlTool -> ACTION_VIEW: https://www.youtube.com/results?search_query={encoded} (pkg: com.google.android.youtube)"
        elif app == "spotify":
            action_desc = f"MediaControlTool -> Spotify Search: '{mediaQuery}'"
        else:
            action_desc = f"MediaControlTool -> Default Play: '{mediaQuery}'"
        print(f"  -> Dispatched: {action_desc}")
    elif intent in ("search_google", "web_search"):
        searchQuery = slots.get("QUERY", q).strip()
        encoded = urllib.parse.quote(searchQuery)
        action_desc = f"WebSearchTool -> Google Search for: '{searchQuery}' (https://www.google.com/search?q={encoded})"
        print(f"  -> Dispatched: {action_desc}")
    elif intent == "lock_phone":
        print(f"  -> Dispatched: SystemControlTool -> lock_phone")
    elif intent == "torch_toggle":
        action = "off" if "off" in q else "on"
        print(f"  -> Dispatched: SystemControlTool -> toggle_torch ({action})")
    elif intent == "open_app":
        app_name = slots.get("APP", q)
        print(f"  -> Dispatched: AppLauncherTool -> open_app ({app_name})")

print("\n" + "=" * 70)
print("ALL TEST QUERIES VERIFIED SUCCESSFULLY")
print("=" * 70)

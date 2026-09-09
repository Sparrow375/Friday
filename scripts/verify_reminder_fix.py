import os, json, re, time
import numpy as np
import onnxruntime as ort

PROJECT_ROOT = 'f:/Avaneesh/projects/Friday'
model_path = os.path.join(PROJECT_ROOT, 'app', 'src', 'main', 'assets', 'joint_nlu_model.onnx')
intent_labels_path = os.path.join(PROJECT_ROOT, 'app', 'src', 'main', 'assets', 'joint_intent_labels.json')
slot_labels_path = os.path.join(PROJECT_ROOT, 'app', 'src', 'main', 'assets', 'joint_slot_labels.json')
vocab_path = os.path.join(PROJECT_ROOT, 'app', 'src', 'main', 'assets', 'vocab.txt')

with open(intent_labels_path) as f: intent_labels = json.load(f)
with open(slot_labels_path) as f: slot_labels = json.load(f)
with open(vocab_path, encoding='utf-8') as f:
    vocab = {line.strip(): i for i, line in enumerate(f) if line.strip()}

session = ort.InferenceSession(model_path)

def preprocess_kotlin_sim(query):
    working = query
    # Step 4 normalization:
    dottedTimeRegex = re.compile(r'(?i)\b(\d{1,2}(?::\d{2})?)\s*([ap])\s*\.\s*m\s*\.?\b')
    working = dottedTimeRegex.sub(lambda m: f"{m.group(1)} {m.group(2).lower()}m", working)
    return working

def tokenize_android(text):
    clean = text.lower()
    clean = ''.join([' ' + c + ' ' if not (c.isalnum() or c == '#') else c for c in clean]).strip()
    words = [w for w in clean.split() if w]
    token_ids = []
    token_strings = []
    unk_id = vocab.get('[UNK]', 100)
    for word in words:
        start = 0
        l = len(word)
        while start < l:
            end = l
            matched_id = -1
            matched_str = ''
            while start < end:
                sub = word[start:end]
                if start > 0: sub = '##' + sub
                if sub in vocab:
                    matched_id = vocab[sub]
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

def convert_tokens_to_string(tokens):
    out = []
    for t in tokens:
        if t.startswith('##'):
            if out: out[-1] += t[2:]
            else: out.append(t[2:])
        else:
            out.append(t)
    return ' '.join(out).strip()

def decode_slots_kotlin_new(token_strings, slot_ids):
    slots = {}
    def commit_slot(tag, toks):
        raw = convert_tokens_to_string(toks).strip()
        clean = re.sub(r'[\[\]]', '', raw).strip()
        if not clean or re.match(r'^[.,!?:;"\'\-_/\\]+$', clean): return
        if clean.lower() in ('quote', 'contact'): return
        existing = slots.get(tag)
        if not existing or len(clean) > len(existing):
            slots[tag] = clean

    current_tag = None
    current_tokens = []
    limit = min(len(token_strings) - 1, len(slot_ids))
    for i in range(1, limit):
        slot_tag = slot_labels[slot_ids[i]] if slot_ids[i] < len(slot_labels) else 'O'
        tok_str = token_strings[i]
        if slot_tag.startswith('B-'):
            if current_tag and current_tokens:
                commit_slot(current_tag, current_tokens)
            current_tag = slot_tag[2:]
            current_tokens = [tok_str]
        elif slot_tag.startswith('I-') and current_tag == slot_tag[2:]:
            current_tokens.append(tok_str)
        else:
            if current_tag and current_tokens:
                commit_slot(current_tag, current_tokens)
                current_tag = None
                current_tokens = []
    if current_tag and current_tokens:
        commit_slot(current_tag, current_tokens)
    return slots

queries = [
    'remind me to test today at 8:00 p.m.',
    'remind me to test today at 8:00 pm',
    'remind me to buy milk today at 5pm',
    'remind me tomorrow at 9am to call doctor',
    'set a reminder for today at 8pm to test the code'
]

for q in queries:
    preprocessed = preprocess_kotlin_sim(q)
    tids, tstrs = tokenize_android(preprocessed)
    input_ids = [101] + tids + [102]
    all_tokens = ['[CLS]'] + tstrs + ['[SEP]']
    inputs = {
        'input_ids': np.array([input_ids], dtype=np.int64),
        'attention_mask': np.ones((1, len(input_ids)), dtype=np.int64)
    }
    outs = session.run(None, inputs)
    intent_idx = int(np.argmax(outs[0][0]))
    intent = intent_labels[intent_idx]
    slot_ids = np.argmax(outs[1][0], axis=-1)
    slots = decode_slots_kotlin_new(all_tokens, slot_ids)
    print(f"Query: '{q}'")
    print(f"  Preprocessed: '{preprocessed}'")
    print(f"  Intent: {intent}")
    print(f"  Slots: {slots}")
    print()

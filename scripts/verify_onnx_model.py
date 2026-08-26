import onnxruntime as ort
import json
import numpy as np

# Load assets
model_path = "f:/Avaneesh/projects/Friday/app/src/main/assets/joint_nlu_model.onnx"
intents_path = "f:/Avaneesh/projects/Friday/app/src/main/assets/joint_intent_labels.json"
vocab_path = "f:/Avaneesh/projects/Friday/app/src/main/assets/vocab.txt"

with open(intents_path, "r", encoding="utf-8") as f:
    intents = json.load(f)

vocab = {}
with open(vocab_path, "r", encoding="utf-8") as f:
    for idx, line in enumerate(f):
        vocab[line.strip()] = idx

session = ort.InferenceSession(model_path)

def tokenize(text):
    tokens = ["[CLS]"]
    for word in text.lower().split():
        if word in vocab:
            tokens.append(word)
        else:
            tokens.append("[UNK]")
    tokens.append("[SEP]")
    ids = [vocab.get(t, vocab.get("[UNK]", 100)) for t in tokens]
    return ids

test_queries = [
    "owe friend",
    "owe mom money",
    "call rohit",
    "dial mom",
    "update note 2 to buy milk",
    "delete note 5",
    "search on google for quantum computing",
    "remind me in 2 days to call rohit",
    "play sunflower on youtube"
]

print(f"\nVerifying Newly Deployed Joint NLU Model ({len(intents)} intents):")
print("-" * 75)
for q in test_queries:
    ids = tokenize(q)
    input_ids = np.array([ids], dtype=np.int64)
    attention_mask = np.ones((1, len(ids)), dtype=np.int64)
    
    outputs = session.run(None, {"input_ids": input_ids, "attention_mask": attention_mask})
    intent_logits = outputs[0][0]
    
    # Softmax
    exp_logits = np.exp(intent_logits - np.max(intent_logits))
    probs = exp_logits / np.sum(exp_logits)
    pred_idx = int(np.argmax(probs))
    pred_intent = intents[pred_idx]
    conf = probs[pred_idx]
    
    print(f"Query: '{q:<40}' -> Intent: {pred_intent:<20} (Conf: {conf:.2%})")

print("-" * 75)

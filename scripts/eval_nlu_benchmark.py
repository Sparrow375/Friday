"""
Evaluates app/src/main/assets/joint_nlu_model.onnx on scripts/stress_test_dataset.json
plus an extended v2 stress suite covering the 8 new intents & contrastive edge cases.
"""

import json
import os
import time
import numpy as np
import onnxruntime as ort


def softmax(x: np.ndarray) -> np.ndarray:
    e = np.exp(x - np.max(x))
    return e / np.sum(e)


class AndroidWordpieceTokenizer:
    """Exact Python mirror of WordpieceTokenizer.kt for true parity benchmarking."""
    def __init__(self, vocab_path: str):
        self.vocab = {}
        with open(vocab_path, "r", encoding="utf-8") as f:
            for idx, line in enumerate(f):
                w = line.strip()
                if w:
                    self.vocab[w] = idx
        self.unk_id = self.vocab.get("[UNK]", 100)
        self.cls_id = self.vocab.get("[CLS]", 101)
        self.sep_id = self.vocab.get("[SEP]", 102)

    def encode(self, text: str):
        import re
        clean = re.sub(r"([^a-z0-9#])", r" \1 ", text.lower()).strip()
        words = [w for w in clean.split() if w]
        ids = [self.cls_id]
        for word in words:
            start = 0
            n = len(word)
            while start < n:
                end = n
                matched = -1
                while start < end:
                    sub = word[start:end]
                    if start > 0:
                        sub = "##" + sub
                    if sub in self.vocab:
                        matched = self.vocab[sub]
                        break
                    end -= 1
                if matched == -1:
                    ids.append(self.unk_id)
                    break
                ids.append(matched)
                start = end
        ids.append(self.sep_id)
        arr = np.array([ids], dtype=np.int64)
        mask = np.ones_like(arr, dtype=np.int64)
        return {"input_ids": arr, "attention_mask": mask}


V2_NEW_TESTS = [
    ("whats the cricket score", "sports_score"),
    ("real madrid match score", "sports_score"),
    ("follow barcelona", "sports_follow"),
    ("track arsenal scores", "sports_follow"),
    ("whats on my calendar", "calendar_list"),
    ("show todays events", "calendar_list"),
    ("add team sync to my calendar tomorrow at 3 pm", "calendar_add"),
    ("list my reminders", "reminder_list"),
    ("what reminders do i have", "reminder_list"),
    ("create a routine called goodnight", "routine_create"),
    ("run routine goodnight", "routine_run"),
    ("whats my daily briefing", "digest_summary"),
    ("set flashlight brightness to maximum", "torch_strength"),
    ("next song", "next_track"),
    ("show downloads folder", "open_files"),
    ("turn off dnd", "dnd_toggle"),
    ("turn on mobile data", "mobile_data_toggle"),
    ("enable airplane mode", "airplane_mode_toggle"),
    ("list notes", "notes_list"),
]


def run_benchmark():
    root = os.path.abspath(os.path.join(os.path.dirname(__file__), ".."))
    assets = os.path.join(root, "app", "src", "main", "assets")
    model_path = os.path.join(assets, "joint_nlu_model.onnx")
    labels_path = os.path.join(assets, "labels.txt")
    stress_path = os.path.join(root, "scripts", "stress_test_dataset.json")

    with open(labels_path, "r", encoding="utf-8") as f:
        intent_labels = [l.strip() for l in f if l.strip()]

    tokenizer = AndroidWordpieceTokenizer(os.path.join(assets, "vocab.txt"))
    def tokenize_fn(q: str):
        return tokenizer.encode(q)
    sess_opts = ort.SessionOptions()
    sess_opts.intra_op_num_threads = 2
    sess = ort.InferenceSession(model_path, sess_opts, providers=["CPUExecutionProvider"])

    with open(stress_path, "r", encoding="utf-8") as f:
        stress = json.load(f)

    warm = tokenize_fn("turn on wifi")
    sess.run(None, warm)

    latencies = []
    correct = 0
    failures = []
    for row in stress:
        q = row["query"]
        expected = row["expected_intent"]
        enc = tokenize_fn(q)
        t0 = time.perf_counter()
        out = sess.run(None, enc)
        latencies.append((time.perf_counter() - t0) * 1000.0)
        probs = softmax(out[0][0])
        pred_idx = int(np.argmax(probs))
        pred = intent_labels[pred_idx]
        conf = float(probs[pred_idx])
        if pred == expected:
            correct += 1
        else:
            failures.append((q, expected, pred, conf))

    print(f"\n[Original Stress Suite] {correct}/{len(stress)} ({100.0*correct/len(stress):.1f}%)")
    print(f"CPU latency -> mean={np.mean(latencies):.2f} ms, p95={np.percentile(latencies, 95):.2f} ms")
    if failures:
        print("Mismatches on original suite:")
        for q, exp, pr, cf in failures:
            print(f"  '{q}' -> expected={exp}, got={pr} ({cf:.2f})")

    new_ok = 0
    new_fails = []
    for q, expected in V2_NEW_TESTS:
        enc = tokenize_fn(q)
        out = sess.run(None, enc)
        probs = softmax(out[0][0])
        pred_idx = int(np.argmax(probs))
        pred = intent_labels[pred_idx]
        conf = float(probs[pred_idx])
        if pred == expected:
            new_ok += 1
        else:
            new_fails.append((q, expected, pred, conf))

    print(f"\n[V2 Targeted Suite] {new_ok}/{len(V2_NEW_TESTS)} ({100.0*new_ok/len(V2_NEW_TESTS):.1f}%)")
    for q, exp, pr, cf in new_fails:
        print(f"  MISS '{q}' -> expected={exp}, got={pr} ({cf:.2f})")


if __name__ == "__main__":
    run_benchmark()

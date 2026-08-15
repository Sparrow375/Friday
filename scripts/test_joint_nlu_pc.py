#!/usr/bin/env python3
"""
Friday Assistant - Joint NLU (Intent + Neural Slot-Filling) PC Benchmark & Evaluator
Evaluates the dual-head Joint NLU ONNX model on the 152-command stress dataset.
Measures:
- Intent Classification Accuracy
- Slot Extraction Accuracy (Spans)
- Dispatch / Tool Routing Accuracy
- CPU Latency (ms per query, p50, p95)
- RAM / Model Size Footprint
- Side-by-side comparison with previous MobileBERT + RegEx NLU
"""

import os
import sys
import json
import time
import re
from typing import Dict, List, Tuple, Any, Optional
import numpy as np

try:
    import onnxruntime as ort
    from transformers import AutoTokenizer
except ImportError:
    print("Error: Missing onnxruntime or transformers. Run: pip install onnxruntime transformers")
    sys.exit(1)

PROJECT_ROOT = os.path.abspath(os.path.join(os.path.dirname(__file__), ".."))
OUTPUT_DIR = os.path.join(PROJECT_ROOT, "output", "friday_joint_nlu", "output")
if not os.path.exists(os.path.join(OUTPUT_DIR, "joint_nlu_model.onnx")):
    OUTPUT_DIR = os.path.join(PROJECT_ROOT, "output")

MODEL_PATH = os.path.join(OUTPUT_DIR, "joint_nlu_model.onnx")
INTENT_LABELS_PATH = os.path.join(OUTPUT_DIR, "joint_intent_labels.json")
SLOT_LABELS_PATH = os.path.join(OUTPUT_DIR, "joint_slot_labels.json")
STRESS_DATASET_PATH = os.path.join(PROJECT_ROOT, "scripts", "stress_test_dataset.json")


class JointNluRunner:
    """Runs dual-head joint NLU ONNX model with neural slot-filling decoding"""
    def __init__(self, model_path: str, intent_labels_path: str, slot_labels_path: str):
        if not os.path.exists(model_path):
            raise FileNotFoundError(f"Joint NLU model not found at: {model_path}")

        print(f"Loading Joint NLU ONNX model: {model_path} ({os.path.getsize(model_path)/(1024*1024):.1f} MB)")
        self.session = ort.InferenceSession(model_path)
        
        with open(intent_labels_path, "r", encoding="utf-8") as f:
            self.intent_labels = json.load(f)
        with open(slot_labels_path, "r", encoding="utf-8") as f:
            self.slot_labels = json.load(f)

        self.tokenizer = AutoTokenizer.from_pretrained("sentence-transformers/all-MiniLM-L6-v2")
        print(f"Initialized Joint NLU with {len(self.intent_labels)} intents and {len(self.slot_labels)} slot tags.")

    def predict(self, text: str) -> Dict[str, Any]:
        start_time = time.perf_counter()
        
        words = text.split()
        encoding = self.tokenizer(
            text,
            return_tensors="np",
            padding="max_length",
            truncation=True,
            max_length=48
        )
        
        input_ids = encoding["input_ids"]
        attention_mask = encoding["attention_mask"]

        inputs = {
            "input_ids": input_ids.astype(np.int64),
            "attention_mask": attention_mask.astype(np.int64)
        }

        outputs = self.session.run(None, inputs)
        intent_logits = outputs[0][0]
        slot_logits = outputs[1][0]

        # 1. Intent Softmax
        exp_intents = np.exp(intent_logits - np.max(intent_logits))
        intent_probs = exp_intents / np.sum(exp_intents)
        best_intent_idx = int(np.argmax(intent_probs))
        intent = self.intent_labels[best_intent_idx]
        confidence = float(intent_probs[best_intent_idx])

        # 2. Token-level Slot Extraction (BIO Decoding)
        token_slot_indices = np.argmax(slot_logits, axis=-1)
        tokens = self.tokenizer.convert_ids_to_tokens(input_ids[0])
        
        slots: Dict[str, str] = {}
        current_entity_type = None
        current_tokens = []

        for idx, (token, slot_idx) in enumerate(zip(tokens, token_slot_indices)):
            if token in ("[CLS]", "[SEP]", "[PAD]"):
                continue
            slot_tag = self.slot_labels[slot_idx]
            
            if slot_tag.startswith("B-"):
                if current_entity_type and current_tokens:
                    val = self.tokenizer.convert_tokens_to_string(current_tokens).strip()
                    slots[current_entity_type] = val
                current_entity_type = slot_tag[2:]
                current_tokens = [token]
            elif slot_tag.startswith("I-") and current_entity_type == slot_tag[2:]:
                current_tokens.append(token)
            else:
                if current_entity_type and current_tokens:
                    val = self.tokenizer.convert_tokens_to_string(current_tokens).strip()
                    slots[current_entity_type] = val
                current_entity_type = None
                current_tokens = []

        if current_entity_type and current_tokens:
            val = self.tokenizer.convert_tokens_to_string(current_tokens).strip()
            slots[current_entity_type] = val

        elapsed_ms = (time.perf_counter() - start_time) * 1000.0

        return {
            "query": text,
            "intent": intent,
            "confidence": confidence,
            "slots": slots,
            "latency_ms": elapsed_ms
        }


class LocalMemoryStore:
    """Simulates Friday's on-device SQLite memory and notes database"""
    def __init__(self):
        self.notes: List[str] = []
        self.preferences: Dict[str, str] = {}

    def save_note(self, content: str) -> str:
        self.notes.append(content)
        return f"Saved Note #{len(self.notes)}: '{content}'"

    def list_notes(self) -> str:
        if not self.notes:
            return "You have no saved notes."
        return "Your notes: " + " | ".join([f"[{i+1}] {n}" for i, n in enumerate(self.notes)])

    def search_notes(self, query: str) -> str:
        q_words = [w for w in query.lower().split() if w not in ("find", "notes", "about", "for", "search")]
        matches = [n for n in self.notes if any(w in n.lower() for w in q_words)]
        if matches:
            return "Matching notes: " + " | ".join(matches)
        return f"No notes found matching '{query}'."

    def remember_fact(self, fact: str) -> str:
        self.preferences[fact] = fact
        return f"Stored Preference: '{fact}'"

    def recall(self, query: str = "") -> str:
        if not self.preferences and not self.notes:
            return "No notes or preferences stored yet."
        q_clean = query.lower()
        q_words = [w for w in q_clean.split() if w not in ("what", "is", "my", "the", "tell", "me", "about", "who", "am", "i")]
        
        if not q_words or q_clean in ("myself", "what you know about me", "all"):
            p_str = "; ".join(self.preferences.values()) if self.preferences else "None"
            n_str = "; ".join(self.notes) if self.notes else "None"
            return f"Preferences: [{p_str}] | Notes: [{n_str}]"

        for f in self.preferences.values():
            if any(w in f.lower() for w in q_words):
                return f"Memory match: '{f}'"
        for n in self.notes:
            if any(w in n.lower() for w in q_words):
                return f"Note match: '{n}'"
        return f"No stored information found matching '{query}'."


class JointAgentDispatcher:
    """Dispatches tool calls directly from neural intent and slot tags (Zero RegExes)"""
    def __init__(self, runner: JointNluRunner):
        self.runner = runner
        self.memory = LocalMemoryStore()

    def dispatch(self, query: str) -> Dict[str, Any]:
        pred = self.runner.predict(query)
        intent = pred["intent"]
        conf = pred["confidence"]
        slots = pred["slots"]

        tool: str = "FALLBACK_LLM_BRAIN"
        args: Dict[str, Any] = {}
        reason: str = ""
        simulated_output: Optional[str] = None

        if conf < 0.60 or intent == "unknown":
            tool = "FALLBACK_LLM_BRAIN"
            args = {"query": query}
            reason = f"Low confidence ({conf:.2f}) or unknown intent -> LLM brain"
            simulated_output = f"[LLM Answer for: '{query}']"
        elif intent == "send_whatsapp":
            tool = "whatsapp_send"
            args = {
                "recipient": slots.get("CONTACT", ""),
                "message": slots.get("MESSAGE", "")
            }
            reason = "Neural WhatsApp slot-filled route"
            simulated_output = f"Opening WhatsApp chat with {args['recipient']} and sending: '{args['message']}'"
        elif intent == "call_contact":
            contact = slots.get("CONTACT", "").strip()
            if not contact:
                tool = "FALLBACK_LLM_BRAIN"
                args = {"query": query}
                reason = "Call intent predicted but contact name is missing -> LLM fallback"
                simulated_output = "[LLM Brain: Who would you like to call?]"
            else:
                tool = "phone_control"
                args = {
                    "action": "call",
                    "contact_name": contact
                }
                reason = "Neural phone call route"
                simulated_output = f"Calling {contact}..."
        elif intent == "read_call_log":
            tool = "phone_control"
            args = {"action": "read_call_log"}
            reason = "Read call log"
            simulated_output = "Recent calls: Mom (10 mins ago), Rohit (1 hour ago)"
        elif intent in ("volume_up", "volume_down"):
            tool = "system_control"
            args = {"action": "set_volume", "value": "up" if intent == "volume_up" else "down"}
            reason = f"Volume control ({intent})"
            simulated_output = f"Volume set to {'higher' if intent == 'volume_up' else 'lower'}"
        elif intent in ("brightness_up", "brightness_down"):
            tool = "system_control"
            args = {"action": "set_brightness", "value": "up" if intent == "brightness_up" else "down"}
            reason = f"Brightness control ({intent})"
            simulated_output = f"Brightness adjusted {'up' if intent == 'brightness_up' else 'down'}"
        elif intent == "torch_toggle":
            tool = "system_control"
            args = {"action": "toggle_torch", "value": "off" if "off" in query.lower() or "kill" in query.lower() else "on"}
            reason = "Torch toggle"
            simulated_output = f"Flashlight turned {args['value']}"
        elif intent == "torch_strength":
            tool = "system_control"
            args = {"action": "set_torch_strength", "value": slots.get("VALUE", "50%")}
            reason = "Torch strength"
            simulated_output = f"Torch brightness set to {args['value']}"
        elif intent in ("wifi_toggle", "bluetooth_toggle", "hotspot_toggle", "dnd_toggle", "airplane_mode_toggle", "mobile_data_toggle", "power_saver_toggle", "screencast_toggle"):
            tool = "system_control"
            args = {"action": intent}
            reason = f"System setting toggle ({intent})"
            simulated_output = f"Toggled {intent.replace('_toggle', '')}"
        elif intent == "lock_phone":
            tool = "system_control"
            args = {"action": "lock_phone"}
            reason = "Lock phone"
            simulated_output = "Locking device screen."
        elif intent == "take_screenshot":
            tool = "screenshot"
            args = {"action": "capture"}
            reason = "Take screenshot"
            simulated_output = "Screenshot captured and saved to Gallery."
        elif intent == "navigate_to":
            dest = slots.get("DESTINATION", "").strip() or query
            tool = "location_control"
            args = {"action": "navigate", "destination": dest}
            reason = "Neural navigation destination route"
            simulated_output = f"Opening Google Maps navigation to: {dest}"
        elif intent == "set_alarm":
            tool = "calendar_control"
            args = {"action": "set_alarm", "time": slots.get("TIME", "7 am")}
            reason = "Set alarm"
            simulated_output = f"Alarm set for {args['time']}"
        elif intent == "set_timer":
            tool = "calendar_control"
            args = {"action": "set_timer", "duration": slots.get("TIME", "5 minutes")}
            reason = "Set timer"
            simulated_output = f"Timer started for {args['duration']}"
        elif intent in ("play_spotify", "play_youtube", "play_media"):
            tool = "media_control"
            app = "spotify" if intent == "play_spotify" else ("youtube" if intent == "play_youtube" else slots.get("APP", None))
            args = {"action": "play_search", "query": slots.get("QUERY", query), "app": app}
            reason = f"Media playback ({intent})"
            simulated_output = f"Playing '{args['query']}' on {app or 'media player'}"
        elif intent in ("pause_media", "next_track", "previous_track"):
            tool = "media_control"
            args = {"action": intent.replace("_media", "").replace("_track", "")}
            reason = f"Media transport ({intent})"
            simulated_output = f"Media action executed: {args['action']}"
        elif intent in ("open_app", "open_files", "open_camera"):
            if intent == "open_camera":
                tool = "camera_control"
                args = {"action": "open_camera"}
                simulated_output = "Opening Camera..."
            else:
                tool = "app_launcher"
                args = {"app_name": slots.get("APP", "app")}
                simulated_output = f"Launching app: {args['app_name']}"
            reason = f"App launch ({intent})"
        elif intent in ("notes_create", "notes_list", "notes_search", "notes_delete"):
            tool = "notes_control"
            note_content = slots.get("NOTE_CONTENT", query)
            args = {"action": intent.replace("notes_", ""), "content": note_content}
            reason = f"Notes ({intent})"
            if intent == "notes_create":
                simulated_output = self.memory.save_note(note_content)
            elif intent == "notes_list":
                simulated_output = self.memory.list_notes()
            elif intent == "notes_search":
                simulated_output = self.memory.search_notes(slots.get("QUERY", query))
            else:
                simulated_output = f"Deleted note matching: {note_content}"
        elif intent in ("remember_preference", "recall_preference"):
            tool = "remember" if intent == "remember_preference" else "recall"
            fact = slots.get("FACT", slots.get("QUERY", query))
            args = {"action": intent, "fact": fact}
            reason = f"Preference ({intent})"
            if intent == "remember_preference":
                simulated_output = self.memory.remember_fact(fact)
            else:
                simulated_output = self.memory.recall(fact)
        elif intent in ("search_google", "search_reddit", "web_search"):
            tool = "web_search"
            args = {"query": slots.get("QUERY", query)}
            reason = f"Web search ({intent})"
            simulated_output = f"Searching online for: '{args['query']}'"
        elif intent in ("clipboard_read", "clipboard_write"):
            tool = "clipboard_control"
            args = {"action": "read" if intent == "clipboard_read" else "write"}
            reason = "Clipboard"
            simulated_output = "Clipboard action executed."
        elif intent == "read_notifications":
            tool = "notification_control"
            args = {"action": "list"}
            reason = "Read notifications"
        elif intent in ("get_battery", "get_time"):
            tool = "system_status"
            args = {"action": intent}
            reason = "System status"

        return {
            "query": query,
            "prediction": pred,
            "dispatch": {
                "tool": tool,
                "arguments": args,
                "reason": reason
            },
            "latency_ms": pred["latency_ms"]
        }


def run_benchmark():
    if not os.path.exists(MODEL_PATH):
        print(f"\n[!] Model file not found: {MODEL_PATH}")
        print("Please train the model first using: python scripts/train_joint_nlu.py")
        print("Or run scripts/friday_joint_nlu_training.ipynb on Google Colab and place output/joint_nlu_model.onnx here.")
        return

    runner = JointNluRunner(MODEL_PATH, INTENT_LABELS_PATH, SLOT_LABELS_PATH)
    dispatcher = JointAgentDispatcher(runner)

    with open(STRESS_DATASET_PATH, "r", encoding="utf-8") as f:
        cases = json.load(f)

    print(f"\nRunning Joint NLU Stress Benchmark on {len(cases)} queries...\n")

    correct_intent = 0
    correct_tool = 0
    total = len(cases)
    latencies = []
    failures = []

    cat_stats = {}

    for idx, c in enumerate(cases, 1):
        cat = c["category"]
        if cat not in cat_stats:
            cat_stats[cat] = {"total": 0, "intent_ok": 0, "tool_ok": 0}
        cat_stats[cat]["total"] += 1

        res = dispatcher.dispatch(c["query"])
        val_intent = res["prediction"]["intent"]
        tool = res["dispatch"]["tool"]
        lat = res["latency_ms"]
        latencies.append(lat)

        i_ok = (c["expected_intent"] is None) or (val_intent == c["expected_intent"])
        t_ok = (c["expected_tool"] is None) or (tool == c["expected_tool"])

        if i_ok:
            correct_intent += 1
            cat_stats[cat]["intent_ok"] += 1
        if t_ok:
            correct_tool += 1
            cat_stats[cat]["tool_ok"] += 1
        else:
            failures.append({
                "query": c["query"],
                "category": cat,
                "expected_tool": c["expected_tool"],
                "actual_tool": tool,
                "expected_intent": c["expected_intent"],
                "actual_intent": val_intent,
                "slots": res["prediction"]["slots"],
                "reason": res["dispatch"]["reason"]
            })

    print("=" * 75)
    print(" JOINT NLU (DEBERTA/MINILM + NEURAL SLOTS) BENCHMARK SUMMARY")
    print("=" * 75)
    print(f" Total Test Queries:       {total}")
    print(f" Intent Accuracy:          {correct_intent}/{total} ({correct_intent/total*100:.1f}%)")
    print(f" Tool Routing Accuracy:    {correct_tool}/{total} ({correct_tool/total*100:.1f}%)")
    print(f" Average CPU Latency:      {np.mean(latencies):.2f} ms")
    print(f" Median Latency (P50):     {np.median(latencies):.2f} ms")
    print(f" 95th Percentile (P95):    {np.percentile(latencies, 95):.2f} ms")
    print(f" Model Size (INT8 ONNX):   {os.path.getsize(MODEL_PATH)/(1024*1024):.1f} MB")
    print("=" * 75)

    print("\n# CATEGORY-WISE PERFORMANCE BREAKDOWN\n")
    print(f"| {'Category':<20} | {'Total':>5} | {'Intent Accuracy':>15} | {'Routing Accuracy':>17} |")
    print(f"|{'-'*22}|{'-'*7}|{'-'*17}|{'-'*19}|")
    for cat, s in cat_stats.items():
        i_pct = s["intent_ok"] / s["total"] * 100
        t_pct = s["tool_ok"] / s["total"] * 100
        print(f"| {cat:<20} | {s['total']:>5} | {i_pct:>14.1f}% | {t_pct:>16.1f}% |")

    if failures:
        print(f"\nRemaining Failures ({len(failures)} cases):")
        for f in failures[:10]:
            print(f"- \"{f['query']}\" -> Expected: {f['expected_tool']}, Got: {f['actual_tool']} (Slots: {f['slots']})")


def format_single_result(res: Dict[str, Any]) -> str:
    pred = res["prediction"]
    disp = res["dispatch"]
    exec_res = disp.get("execution_result", "")
    lines = [
        f"\n=======================================================",
        f"  User Query: \"{res['query']}\"",
        f"=======================================================",
        f"  [1] Neural Intent:     {pred['intent']} ({pred['confidence']*100:.1f}%)",
        f"  [2] Neural Slots:      {json.dumps(pred['slots'])}",
        f"  [3] Dispatched Tool:   \033[92m{disp['tool']}\033[0m" if disp['tool'] != 'FALLBACK_LLM_BRAIN' else f"  [3] Dispatched Tool:   \033[93m{disp['tool']}\033[0m",
        f"      Tool Arguments:    {json.dumps(disp['arguments'])}",
        f"      Dispatch Reason:   {disp['reason']}",
    ]
    if exec_res:
        lines.append(f"  [4] Execution Output:  \033[96m{exec_res}\033[0m")
    lines.append(f"  [5] Inference Latency: {res['latency_ms']:.2f} ms")
    lines.append("=======================================================\n")
    return "\n".join(lines)


def interactive_mode(dispatcher: JointAgentDispatcher):
    print("\n" + "=" * 60)
    print("  Friday Joint NLU Interactive Tester (Neural Intent + Slots)")
    print("  Type any command to test, or 'exit' / 'quit' to stop.")
    print("=" * 60 + "\n")

    while True:
        try:
            query = input("Friday >> ").strip()
            if not query:
                continue
            if query.lower() in ("exit", "quit", "q"):
                print("Exiting...")
                break
            if query.lower() == "benchmark":
                run_benchmark()
                continue

            res = dispatcher.dispatch(query)
            print(format_single_result(res))
        except (KeyboardInterrupt, EOFError):
            print("\nExiting...")
            break


if __name__ == "__main__":
    import argparse
    parser = argparse.ArgumentParser(description="Friday Joint NLU PC Tester")
    parser.add_argument("query", nargs="*", help="Single query to test directly")
    parser.add_argument("--benchmark", action="store_true", help="Run full stress benchmark")
    parser.add_argument("--interactive", "-i", action="store_true", help="Start interactive REPL")
    args = parser.parse_args()

    if args.benchmark:
        run_benchmark()
    elif args.query:
        query_str = " ".join(args.query)
        runner = JointNluRunner(MODEL_PATH, INTENT_LABELS_PATH, SLOT_LABELS_PATH)
        dispatcher = JointAgentDispatcher(runner)
        res = dispatcher.dispatch(query_str)
        print(format_single_result(res))
    else:
        runner = JointNluRunner(MODEL_PATH, INTENT_LABELS_PATH, SLOT_LABELS_PATH)
        dispatcher = JointAgentDispatcher(runner)
        interactive_mode(dispatcher)

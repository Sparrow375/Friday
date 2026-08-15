#!/usr/bin/env python3
"""
Friday Assistant - Local Model & Intent Routing Test Harness (PC Simulator)

Mirrors the exact Android Kotlin intelligence pipeline:
- WordpieceTokenizer (vocab.txt)
- NluIntentClassifier (nlu_model.onnx, labels.txt)
- InputPreprocessor (entity extraction)
- PostClassificationValidator
- EntityExtractor
- AgentCore command routing

Usage:
  python scripts/test_nlu_pc.py                  # Interactive REPL mode
  python scripts/test_nlu_pc.py --benchmark      # Run full benchmark suite
  python scripts/test_nlu_pc.py --query "text"   # Test a single command
"""

import os
import sys
import json
import re
import argparse
from typing import Dict, List, Tuple, Any, Optional
import numpy as np

# Try importing onnxruntime
try:
    import onnxruntime as ort
except ImportError:
    print("Error: onnxruntime not found. Please install with: pip install onnxruntime")
    sys.exit(1)

PROJECT_ROOT = os.path.abspath(os.path.join(os.path.dirname(__file__), ".."))
ASSETS_DIR = os.path.join(PROJECT_ROOT, "app", "src", "main", "assets")

MODEL_PATH = os.path.join(ASSETS_DIR, "nlu_model.onnx")
VOCAB_PATH = os.path.join(ASSETS_DIR, "vocab.txt")
LABELS_PATH = os.path.join(ASSETS_DIR, "labels.txt")


class WordpieceTokenizer:
    """Exact replica of Kotlin com.friday.assistant.intelligence.nlu.WordpieceTokenizer"""
    def __init__(self, vocab_path: str):
        self.vocab: Dict[str, int] = {}
        self.id_to_token: Dict[int, str] = {}
        if os.path.exists(vocab_path):
            with open(vocab_path, "r", encoding="utf-8") as f:
                for idx, line in enumerate(f):
                    token = line.strip()
                    if token:
                        self.vocab[token] = idx
                        self.id_to_token[idx] = token
        self.unk_id = self.vocab.get("[UNK]", 100)
        self.cls_id = self.vocab.get("[CLS]", 101)
        self.sep_id = self.vocab.get("[SEP]", 102)

    def tokenize(self, text: str) -> List[int]:
        tokens: List[int] = []
        clean_text = re.sub(r"([^a-z0-9#])", r" \1 ", text.lower()).strip()
        words = [w for w in re.split(r"\s+", clean_text) if w]

        for word in words:
            start = 0
            w_len = len(word)
            while start < w_len:
                end = w_len
                matched_id = -1
                while start < end:
                    sub_word = word[start:end]
                    if start > 0:
                        sub_word = f"##{sub_word}"
                    if sub_word in self.vocab:
                        matched_id = self.vocab[sub_word]
                        break
                    end -= 1
                if matched_id == -1:
                    tokens.append(self.unk_id)
                    break
                tokens.append(matched_id)
                start = end
        return tokens

    def decode_ids(self, token_ids: List[int]) -> List[str]:
        return [self.id_to_token.get(t, f"[ID:{t}]") for t in token_ids]


class InputPreprocessor:
    """Exact replica of Kotlin com.friday.assistant.intelligence.InputPreprocessor"""
    @staticmethod
    def preprocess(query: str) -> Tuple[str, str, Dict[str, str]]:
        original_text = query
        entities: Dict[str, str] = {}
        working_text = query

        # 1. Extract quoted strings: "..." or '...' -> [QUOTE]
        quotes_regex = re.compile(r'"([^"]+)"|\'([^\']+)\'')
        quote_index = 1
        match_result = quotes_regex.search(working_text)
        while match_result:
            value = match_result.group(1) if match_result.group(1) else match_result.group(2)
            key = f"[QUOTE_{quote_index}]"
            entities[key] = value
            working_text = working_text.replace(match_result.group(0), key, 1)
            quote_index += 1
            match_result = quotes_regex.search(working_text)

        if "[QUOTE_1]" in entities:
            entities["[QUOTE]"] = entities["[QUOTE_1]"]

        # 2. Message payloads: text after saying/with/that
        message_patterns = [
            re.compile(r"(?i)(?:text|sms|message|whatsapp|send message to|send whatsapp to)\s+(.+?)\s+(?:saying|with|that|message|write)\s*(.+)")
        ]
        for pattern in message_patterns:
            match = pattern.search(working_text)
            if match:
                recipient = match.group(1).strip()
                body = match.group(2).strip()

                if not body.startswith("[QUOTE"):
                    entities["[QUOTE]"] = body
                    working_text = working_text.replace(body, "[QUOTE]", 1)

                if not recipient.startswith("[CONTACT") and not recipient.startswith("[PHONE"):
                    entities["[CONTACT]"] = recipient
                    working_text = working_text.replace(recipient, "[CONTACT]", 1)
                break

        # 3. Phone numbers
        phone_regex = re.compile(r"(?:\+\d{1,3}\s?)?\d{10,}")
        phone_index = 1
        phone_match = phone_regex.search(working_text)
        while phone_match:
            key = f"[PHONE_{phone_index}]"
            entities[key] = phone_match.group(0)
            working_text = working_text.replace(phone_match.group(0), key, 1)
            phone_index += 1
            phone_match = phone_regex.search(working_text)

        if "[PHONE_1]" in entities:
            entities["[PHONE]"] = entities["[PHONE_1]"]

        # 4. Time expressions
        time_regex = re.compile(r"\b\d{1,2}:\d{2}\s*(?:am|pm|AM|PM)?\b|\b\d{1,2}\s*(?:am|pm|AM|PM)\b")
        time_index = 1
        time_match = time_regex.search(working_text)
        while time_match:
            key = f"[TIME_{time_index}]"
            entities[key] = time_match.group(0)
            working_text = working_text.replace(time_match.group(0), key, 1)
            time_index += 1
            time_match = time_regex.search(working_text)

        if "[TIME_1]" in entities:
            entities["[TIME]"] = entities["[TIME_1]"]

        return working_text, original_text, entities


class EntityExtractor:
    """Exact replica of Kotlin com.friday.assistant.intelligence.EntityExtractor"""
    APP_NAMES = {
        "spotify": "spotify", "youtube": "youtube", "yt": "youtube",
        "youtube music": "youtube music", "yt music": "youtube music",
        "whatsapp": "whatsapp", "instagram": "instagram", "gmail": "gmail",
        "maps": "maps", "google maps": "maps", "chrome": "chrome",
        "browser": "browser", "camera": "camera", "settings": "settings",
        "telegram": "telegram", "netflix": "netflix"
    }

    @staticmethod
    def clean_entity(text: str) -> str:
        text = re.sub(r"\b(please|thanks|thank you|now|right now)\b", "", text, flags=re.IGNORECASE)
        return text.replace("?", "").replace(".", "").replace("!", "").strip()

    @staticmethod
    def is_generic_word(text: str) -> bool:
        return text.lower().strip() in {"music", "song", "something", "it", "that", "this", "media"}

    @classmethod
    def detect_app_name(cls, query: str) -> Optional[str]:
        lower = query.lower()
        for k in sorted(cls.APP_NAMES.keys(), key=lambda x: len(x), reverse=True):
            if k in lower:
                return cls.APP_NAMES[k]
        return None

    @classmethod
    def extract_call_contact(cls, query: str) -> Optional[str]:
        patterns = [
            r"(?i)(?:call|phone|dial|ring)\s+(?:up\s+)?(.+?)(?:\s+please)?$",
            r"(?i)(?:can you|could you|please)\s+call\s+(.+?)(?:\s+please)?$",
            r"(?i)make a call to\s+(.+?)(?:\s+please)?$",
            r"(?i)give\s+(.+?)\s+a call$"
        ]
        for p in patterns:
            match = re.search(p, query.strip())
            if match:
                name = cls.clean_entity(match.group(1))
                if name and not cls.is_generic_word(name):
                    return name
        return None

    @classmethod
    def extract_sms_details(cls, query: str) -> Optional[Tuple[str, str]]:
        patterns = [
            r"(?i)(?:text|sms|message)\s+(.+?)\s+(?:saying|with|that|message)?\s*(.+)",
            r"(?i)send (?:an? )?(?:sms|text message) to\s+(.+?)\s+(?:saying|with|message)?\s*(.+)",
            r"(?i)send\s+(.+?)\s+(?:an? )?(?:sms|text)\s+(?:saying|with)?\s*(.+)"
        ]
        for p in patterns:
            match = re.search(p, query.strip())
            if match:
                recipient = cls.clean_entity(match.group(1))
                message = match.group(2).strip().strip('"').strip("'")
                if recipient and message:
                    return recipient, message
        return None

    @classmethod
    def extract_media_query(cls, query: str) -> Tuple[str, Optional[str]]:
        lower = query.lower()
        app = cls.detect_app_name(query)
        patterns = [
            r"(?i)play\s+(.+?)\s+on\s+(spotify|youtube|youtube music)",
            r"(?i)play\s+(.+?)\s+from\s+(spotify|youtube)",
            r"(?i)listen to\s+(.+?)\s+on\s+(spotify|youtube)",
            r"(?i)play\s+(.+)",
            r"(?i)listen to\s+(.+)",
            r"(?i)start playing\s+(.+)",
            r"(?i)search\s+(.+?)\s+on\s+(spotify|youtube|google)",
            r"(?i)search\s+(?:for\s+)?(.+?)\s+on\s+(spotify|youtube|google)"
        ]
        for p in patterns:
            match = re.search(p, query.strip())
            if match:
                media_query = cls.clean_entity(match.group(1))
                detected_app = match.group(2).lower() if len(match.groups()) > 1 and match.group(2) else app
                media_query = re.sub(r"\bon (spotify|youtube|youtube music|google)\b", "", media_query, flags=re.IGNORECASE)
                media_query = re.sub(r"\bfrom (spotify|youtube)\b", "", media_query, flags=re.IGNORECASE).strip()
                if media_query and not cls.is_generic_word(media_query):
                    return media_query, detected_app
        return "", app

    @classmethod
    def extract_launch_app_name(cls, query: str) -> str:
        keywords = ["open up ", "go to ", "open ", "launch ", "start ", "show ", "run "]
        lower = query.lower()
        for kw in keywords:
            idx = lower.find(kw)
            if idx != -1:
                return cls.clean_entity(lower[idx + len(kw):])
        return cls.clean_entity(query)

    @classmethod
    def is_screenshot_query(cls, query: str) -> bool:
        lower = query.lower()
        return ("screenshot" in lower or "screen shot" in lower or
                "capture screen" in lower or "take a snap" in lower or
                ("capture" in lower and "screen" in lower))


class NluModelRunner:
    """Runs nlu_model.onnx with labels.txt and vocab.txt"""
    def __init__(self, model_path: str, vocab_path: str, labels_path: str):
        print(f"Loading NLU ONNX model: {model_path}")
        self.session = ort.InferenceSession(model_path)
        self.tokenizer = WordpieceTokenizer(vocab_path)
        self.labels: List[str] = []
        with open(labels_path, "r", encoding="utf-8") as f:
            self.labels = [l.strip() for l in f if l.strip()]
        print(f"Loaded {len(self.labels)} labels, vocab size: {len(self.tokenizer.vocab)}")

    def classify(self, text: str) -> Tuple[str, float, List[Tuple[str, float]], List[int]]:
        token_ids = self.tokenizer.tokenize(text)
        if not token_ids:
            return "unknown", 0.0, [], []

        # [CLS] + token_ids + [SEP]
        input_ids = [self.tokenizer.cls_id] + token_ids + [self.tokenizer.sep_id]
        attention_mask = [1] * len(input_ids)

        input_ids_np = np.array([input_ids], dtype=np.int64)
        attention_mask_np = np.array([attention_mask], dtype=np.int64)

        # Dynamic input names
        input_names = [inp.name for inp in self.session.get_inputs()]
        inputs = {input_names[0]: input_ids_np}
        if len(input_names) > 1:
            inputs[input_names[1]] = attention_mask_np

        outputs = self.session.run(None, inputs)
        logits = outputs[0][0]

        # Softmax
        exp_logits = np.exp(logits - np.max(logits))
        probs = exp_logits / np.sum(exp_logits)

        top_indices = np.argsort(probs)[::-1]
        top_k = [(self.labels[idx] if idx < len(self.labels) else "unknown", float(probs[idx]))
                 for idx in top_indices[:5]]

        best_intent = top_k[0][0]
        best_conf = top_k[0][1]

        return best_intent, best_conf, top_k, input_ids


class AgentRouter:
    """Simulates AgentCore.kt and PostClassificationValidator.kt command dispatching"""
    WHATSAPP_PATTERNS = [
        re.compile(r"(?i)(?:message|text|send message to)\s+(.+?)\s+on\s+whatsapp\s+(?:saying|text|message)?\s*(.+)"),
        re.compile(r"(?i)(?:message|text|send message to)\s+(.+?)\s+(?:saying|text|message)?\s*(.+)\s+on\s+whatsapp"),
        re.compile(r"(?i)whatsapp\s+(.+?)\s+(?:saying|text|message)?\s*(.+)")
    ]

    def __init__(self, nlu_runner: NluModelRunner):
        self.nlu = nlu_runner

    def route(self, user_query: str) -> Dict[str, Any]:
        # Step 1: Preprocess
        cleaned_text, original_text, entities = InputPreprocessor.preprocess(user_query)
        clean_query = cleaned_text.strip().lower()

        # Step 2: NLU Classification
        nlu_intent, nlu_conf, top_k, token_ids = self.nlu.classify(cleaned_text)

        # Step 3: Post-Classification Validation
        final_intent = nlu_intent
        final_conf = nlu_conf
        route_to_llm = False

        # App installed check simulation
        known_apps = {"spotify", "youtube", "whatsapp", "instagram", "gmail", "chrome", "maps", "settings"}

        # Check search google trap suppression
        if final_intent in ("search_google", "web_search"):
            orig_lower = original_text.lower()
            if "message" in orig_lower or "whatsapp" in orig_lower or "text" in orig_lower:
                if "whatsapp" in orig_lower or "message" in orig_lower:
                    final_intent = "send_whatsapp"
                    final_conf = 0.90
                elif "sms" in orig_lower or "text" in orig_lower:
                    final_intent = "send_sms"
                    final_conf = 0.90

        if final_intent == "unknown" or final_conf < 0.60:
            route_to_llm = True

        # Step 4: Dispatch in AgentCore order
        dispatched_tool: Optional[str] = None
        tool_args: Dict[str, Any] = {}
        reason: str = ""

        if not route_to_llm:
            # 1. Screenshot
            if EntityExtractor.is_screenshot_query(clean_query) or final_intent == "take_screenshot":
                dispatched_tool = "screenshot"
                tool_args = {"action": "capture"}
                reason = "Screenshot trigger matched"

            # 2. Phone Call (Exact Kotlin logic: checks callContact from entity OR regex, gated before WhatsApp)
            elif not dispatched_tool:
                call_contact = entities.get("[CONTACT]") or EntityExtractor.extract_call_contact(original_text)
                is_call_query = call_contact is not None or final_intent == "call_contact"
                if is_call_query and call_contact:
                    dispatched_tool = "phone_control"
                    tool_args = {"action": "call", "contact_name": call_contact}
                    reason = f"Phone call triggered (callContact='{call_contact}', intent='{final_intent}')"

            # 3. SMS
            if not dispatched_tool:
                sms_details = EntityExtractor.extract_sms_details(original_text)
                if not sms_details and ("[CONTACT]" in entities or "[PHONE]" in entities) and "[QUOTE]" in entities:
                    contact = entities.get("[CONTACT]") or entities.get("[PHONE]")
                    msg = entities.get("[QUOTE]")
                    if contact and msg:
                        sms_details = (contact, msg)

                if sms_details or final_intent == "send_sms":
                    if sms_details:
                        dispatched_tool = "phone_control"
                        tool_args = {"action": "send_sms", "recipient": sms_details[0], "message": sms_details[1]}
                        reason = "SMS trigger matched"

            # 3b. Read SMS / Call Log
            if not dispatched_tool:
                if "read sms" in clean_query or "read my messages" in clean_query or "check messages" in clean_query or "read recent sms" in clean_query or "check my new text messages" in clean_query or final_intent == "read_sms":
                    dispatched_tool = "phone_control"
                    tool_args = {"action": "read_sms"}
                    reason = "Read SMS trigger matched"
                elif "call log" in clean_query or "recent calls" in clean_query or "call history" in clean_query or "who called me" in clean_query or final_intent == "read_call_log":
                    dispatched_tool = "phone_control"
                    tool_args = {"action": "read_call_log"}
                    reason = "Read Call Log trigger matched"

            # 3c. Clipboard
            if not dispatched_tool:
                if "clipboard" in clean_query or final_intent in ("clipboard_read", "clipboard_write"):
                    dispatched_tool = "clipboard_control"
                    if "copy" in clean_query or "write" in clean_query or final_intent == "clipboard_write":
                        tool_args = {"action": "write", "text": entities.get("[QUOTE]", original_text)}
                        reason = "Clipboard write trigger"
                    else:
                        tool_args = {"action": "read"}
                        reason = "Clipboard read trigger"

            # 3d. Battery / Time
            if not dispatched_tool:
                if "battery" in clean_query or final_intent == "get_battery":
                    dispatched_tool = "system_status"
                    tool_args = {"action": "get_battery"}
                    reason = "Battery level query"
                elif "what time" in clean_query or "current time" in clean_query or final_intent == "get_time":
                    dispatched_tool = "system_status"
                    tool_args = {"action": "get_time"}
                    reason = "Current time query"

            # 3e. Media Transport Controls (Pause / Next / Previous / Resume)
            if not dispatched_tool:
                if ("pause" in clean_query and ("music" in clean_query or "media" in clean_query or "playback" in clean_query)) or final_intent == "pause_media" or clean_query in ("pause", "pause it"):
                    dispatched_tool = "media_control"
                    tool_args = {"action": "pause"}
                    reason = "Media pause trigger"
                elif "next track" in clean_query or "skip" in clean_query or "next song" in clean_query or final_intent == "next_track":
                    dispatched_tool = "media_control"
                    tool_args = {"action": "next"}
                    reason = "Media next track trigger"
                elif "previous track" in clean_query or ("go back" in clean_query and "song" in clean_query) or "previous song" in clean_query or final_intent == "previous_track":
                    dispatched_tool = "media_control"
                    tool_args = {"action": "previous"}
                    reason = "Media previous track trigger"

            # 4. WhatsApp Direct Pattern Check
            if not dispatched_tool:
                if "whatsapp" in clean_query and any(w in clean_query for w in ["message", "send", "text"]):
                    for p in self.WHATSAPP_PATTERNS:
                        m = p.search(original_text)
                        if m:
                            dispatched_tool = "whatsapp_send"
                            tool_args = {"recipient": m.group(1).strip(), "message": m.group(2).strip().strip('"')}
                            reason = "WhatsApp regex pattern matched"
                            break

            # 5. Volume
            if not dispatched_tool:
                if any(w in clean_query for w in ["volume", "sound", "audio", "mute", "unmute", "louder", "quieter"]) or final_intent in ("volume_up", "volume_down"):
                    dispatched_tool = "system_control"
                    action_val = "up" if ("up" in clean_query or "louder" in clean_query or "boost" in clean_query or final_intent == "volume_up") else "down"
                    tool_args = {"action": "set_volume", "value": action_val}
                    reason = f"Volume control (intent='{final_intent}')"

            # 6. Brightness
            if not dispatched_tool:
                if any(w in clean_query for w in ["brightness", "dim", "brighter", "dimmer"]) or final_intent in ("brightness_up", "brightness_down"):
                    dispatched_tool = "system_control"
                    action_val = "up" if ("up" in clean_query or "brighter" in clean_query or final_intent == "brightness_up") else "down"
                    tool_args = {"action": "set_brightness", "value": action_val}
                    reason = f"Brightness control (intent='{final_intent}')"

            # 7. Torch / Flashlight
            if not dispatched_tool:
                if any(w in clean_query for w in ["flashlight", "torch", "light"]) or final_intent in ("torch_toggle", "torch_strength"):
                    if final_intent == "torch_strength" or any(w in clean_query for w in ["strength", "level", "intensity"]):
                        dispatched_tool = "system_control"
                        tool_args = {"action": "set_torch_strength", "raw": original_text}
                        reason = f"Torch strength control"
                    else:
                        state = "off" if any(w in clean_query for w in ["off", "disable", "stop", "kill"]) else "on"
                        dispatched_tool = "system_control"
                        tool_args = {"action": "toggle_torch", "value": state}
                        reason = f"Torch control (state='{state}')"

            # 8. WiFi / Bluetooth / Hotspot / DND
            if not dispatched_tool:
                if "wifi" in clean_query or "wi-fi" in clean_query or final_intent == "wifi_toggle":
                    state = "off" if "off" in clean_query else "on"
                    dispatched_tool = "system_control"
                    tool_args = {"action": "toggle_wifi", "value": state}
                    reason = f"WiFi toggle (state='{state}')"
                elif "bluetooth" in clean_query or final_intent == "bluetooth_toggle":
                    state = "off" if "off" in clean_query or "disable" in clean_query else "on"
                    dispatched_tool = "system_control"
                    tool_args = {"action": "toggle_bluetooth", "value": state}
                    reason = f"Bluetooth toggle (state='{state}')"
                elif "hotspot" in clean_query or final_intent == "hotspot_toggle":
                    state = "off" if "off" in clean_query or "disable" in clean_query else "on"
                    dispatched_tool = "system_control"
                    tool_args = {"action": "toggle_hotspot", "value": state}
                    reason = "Hotspot toggle"
                elif "do not disturb" in clean_query or "dnd" in clean_query or final_intent == "dnd_toggle":
                    state = "off" if "off" in clean_query or "disable" in clean_query else "on"
                    dispatched_tool = "system_control"
                    tool_args = {"action": "toggle_dnd", "value": state}
                    reason = f"DND toggle (state='{state}')"

            # 8b. Notifications
            if not dispatched_tool:
                if "notification" in clean_query or "notifications" in clean_query or "alerts" in clean_query or final_intent == "read_notifications":
                    dispatched_tool = "notification_control"
                    tool_args = {"action": "list"}
                    reason = "Read notifications trigger"

            # 9. Lock Screen
            if not dispatched_tool:
                if final_intent == "lock_phone" or "lock screen" in clean_query or "lock phone" in clean_query or "put phone to sleep" in clean_query:
                    dispatched_tool = "system_control"
                    tool_args = {"action": "lock_phone"}
                    reason = "Lock phone trigger"

            # 9b. Reddit search
            if not dispatched_tool:
                if (final_intent == "search_reddit" and final_conf > 0.7) or "on reddit" in clean_query or "reddit search" in clean_query:
                    dispatched_tool = "web_search"
                    tool_args = {"action": "search_reddit", "query": original_text}
                    reason = "Reddit search trigger"

            # 9c. Google search / Web search
            if not dispatched_tool:
                if (final_intent in ("search_google", "web_search") and final_conf > 0.7) or clean_query.startswith("google ") or "search google" in clean_query or "search the web" in clean_query or "on google" in clean_query:
                    search_phrase = re.sub(r"(?i)^(google|search google for|search for|search|look up)\s+", "", original_text).strip()
                    dispatched_tool = "web_search"
                    tool_args = {"query": search_phrase}
                    reason = f"Google/Web search trigger (intent='{final_intent}')"

            # 9d. Preferences (Remember / Recall)
            if not dispatched_tool:
                if (final_intent == "remember_preference" and final_conf > 0.7) or clean_query.startswith("remember that") or clean_query.startswith("store the fact"):
                    dispatched_tool = "remember"
                    tool_args = {"action": "remember", "text": original_text}
                    reason = "Remember preference"
                elif (final_intent == "recall_preference" and final_conf > 0.7) or "what do you remember about me" in clean_query or "recall what you know" in clean_query:
                    dispatched_tool = "recall"
                    tool_args = {"action": "recall"}
                    reason = "Recall preference"

            # 9e. Files
            if not dispatched_tool:
                if (final_intent == "open_files" and final_conf > 0.7) or "open my files" in clean_query or "open file manager" in clean_query or "show downloads" in clean_query or "browse my files" in clean_query:
                    dispatched_tool = "app_launcher"
                    tool_args = {"app_name": "files"}
                    reason = "Open files"

            # 10. Media Playback / Search
            if not dispatched_tool:
                if final_intent in ("play_media", "play_spotify", "play_youtube") or clean_query.startswith("play ") or clean_query.startswith("listen to "):
                    media_q, target_app = EntityExtractor.extract_media_query(original_text)
                    dispatched_tool = "media_control"
                    tool_args = {"action": "play_search", "query": media_q or clean_query, "app": target_app}
                    reason = f"Media playback (intent='{final_intent}')"

            # 11. Open App
            if not dispatched_tool:
                if (final_intent == "open_app" and final_conf > 0.7) or clean_query.startswith("open ") or clean_query.startswith("launch ") or clean_query.startswith("start "):
                    app_name = EntityExtractor.extract_launch_app_name(original_text)
                    dispatched_tool = "app_launcher"
                    tool_args = {"app_name": app_name}
                    reason = f"App launcher (app='{app_name}')"

            # 12. Navigation
            if not dispatched_tool:
                if (final_intent == "navigate_to" and final_conf > 0.7) or "navigate to" in clean_query or "directions to" in clean_query or "how do i get to" in clean_query or "take me to" in clean_query:
                    nav_patterns = [
                        r"(?i)navigate\s+to\s+(.+)",
                        r"(?i)directions\s+to\s+(.+)",
                        r"(?i)take\s+me\s+to\s+(.+)",
                        r"(?i)how\s+do\s+i\s+get\s+to\s+(.+)",
                        r"(?i)go\s+to\s+(.+)",
                        r"(?i)routes\s+to\s+(.+)"
                    ]
                    dest = ""
                    for p in nav_patterns:
                        m = re.search(p, original_text)
                        if m:
                            dest = m.group(1).strip()
                            break
                    if not dest:
                        dest = clean_query.replace("navigate", "").replace("to", "").replace("directions", "").replace("show", "").strip()
                    dispatched_tool = "location_control"
                    tool_args = {"action": "navigate", "destination": dest}
                    reason = f"Navigation trigger (destination='{dest}')"

            # 13. Alarm / Timer
            if not dispatched_tool:
                if final_intent == "set_alarm" or "alarm for" in clean_query or "wake me up" in clean_query:
                    dispatched_tool = "calendar_control"
                    tool_args = {"action": "set_alarm", "raw": original_text}
                    reason = f"Set Alarm (intent='{final_intent}')"
                elif final_intent == "set_timer" or "timer for" in clean_query or "countdown for" in clean_query:
                    dispatched_tool = "calendar_control"
                    tool_args = {"action": "set_timer", "raw": original_text}
                    reason = f"Set Timer (intent='{final_intent}')"

            # 14. Airplane Mode / Mobile Data / Battery Saver / Screencast / Camera
            if not dispatched_tool:
                if final_intent == "airplane_mode_toggle" or "airplane mode" in clean_query or "flight mode" in clean_query:
                    dispatched_tool = "system_control"
                    tool_args = {"action": "toggle_airplane_mode"}
                    reason = "Airplane mode toggle"
                elif final_intent == "mobile_data_toggle" or "mobile data" in clean_query or "cellular data" in clean_query:
                    dispatched_tool = "system_control"
                    tool_args = {"action": "toggle_mobile_data"}
                    reason = "Mobile data toggle"
                elif final_intent == "power_saver_toggle" or "battery saver" in clean_query or "power saver" in clean_query:
                    dispatched_tool = "system_control"
                    tool_args = {"action": "toggle_power_saver"}
                    reason = "Battery saver toggle"
                elif final_intent == "screencast_toggle" or "screen mirroring" in clean_query or "screencast" in clean_query:
                    dispatched_tool = "system_control"
                    tool_args = {"action": "toggle_screencast"}
                    reason = "Screencast toggle"
                elif final_intent == "open_camera" or "camera" in clean_query or "photo" in clean_query or "picture" in clean_query:
                    dispatched_tool = "camera_control"
                    tool_args = {"action": "open_camera"}
                    reason = "Camera launch"

            # 15. Notes & Reminders
            if not dispatched_tool:
                if final_intent in ("notes_create", "notes_list", "notes_search", "notes_delete") or any(w in clean_query for w in ["note", "jot down", "write down", "save a note", "remind me"]):
                    if final_intent == "notes_search" or "find notes" in clean_query or "search notes" in clean_query:
                        dispatched_tool = "notes_control"
                        tool_args = {"action": "search", "query": original_text}
                        reason = "Notes search"
                    elif final_intent == "notes_list" or "list notes" in clean_query or "show all my notes" in clean_query:
                        dispatched_tool = "notes_control"
                        tool_args = {"action": "list"}
                        reason = "Notes list"
                    elif final_intent == "notes_delete" or "delete note" in clean_query:
                        dispatched_tool = "notes_control"
                        tool_args = {"action": "delete", "query": original_text}
                        reason = "Notes delete"
                    else:
                        dispatched_tool = "notes_control"
                        tool_args = {"action": "create", "content": original_text}
                        reason = f"Notes create (intent='{final_intent}')"

            # 16. WhatsApp NLU Route fallback (bottom of AgentCore)
            if not dispatched_tool and final_intent == "send_whatsapp" and final_conf > 0.7:
                recipient = entities.get("[CONTACT]", "")
                msg_text = entities.get("[QUOTE]", "")
                dispatched_tool = "whatsapp_send"
                tool_args = {"recipient": recipient, "message": msg_text}
                reason = "WhatsApp NLU Intent route"

        if not dispatched_tool:
            dispatched_tool = "FALLBACK_LLM_BRAIN"
            tool_args = {"query": original_text}
            reason = f"Routed to LLM reasoning (routeToLlm={route_to_llm}, intent='{final_intent}', conf={final_conf:.2f})"

        return {
            "query": user_query,
            "preprocessed": {
                "cleaned": cleaned_text,
                "entities": entities,
                "token_ids": token_ids,
                "tokens": self.nlu.tokenizer.decode_ids(token_ids)
            },
            "nlu": {
                "raw_intent": nlu_intent,
                "raw_confidence": nlu_conf,
                "top_5": top_k
            },
            "validation": {
                "validated_intent": final_intent,
                "validated_confidence": final_conf,
                "route_to_llm": route_to_llm
            },
            "dispatch": {
                "tool": dispatched_tool,
                "arguments": tool_args,
                "reason": reason
            }
        }


def format_report(result: Dict[str, Any]) -> str:
    lines = []
    lines.append(f"\n=======================================================")
    lines.append(f"  User Query: \"{result['query']}\"")
    lines.append(f"=======================================================")
    
    prep = result["preprocessed"]
    lines.append(f"  [1] Preprocessor:")
    lines.append(f"      Cleaned Text: \"{prep['cleaned']}\"")
    lines.append(f"      Entities:     {json.dumps(prep['entities'])}")
    lines.append(f"      Subword Toks: {' '.join(prep['tokens'])}")
    
    nlu = result["nlu"]
    lines.append(f"\n  [2] NLU ONNX Model Top-3:")
    for intent, conf in nlu["top_5"][:3]:
        bar = "#" * int(conf * 20)
        lines.append(f"      - {intent:<22} {conf*100:>5.1f}% | {bar}")
    
    val = result["validation"]
    lines.append(f"\n  [3] Post-Classification Validation:")
    lines.append(f"      Final Intent: {val['validated_intent']} (Conf: {val['validated_confidence']*100:.1f}%)")
    lines.append(f"      Route to LLM: {val['route_to_llm']}")
    
    disp = result["dispatch"]
    tool_name = disp["tool"]
    color_tool = f"\033[92m{tool_name}\033[0m" if tool_name != "FALLBACK_LLM_BRAIN" else f"\033[93m{tool_name}\033[0m"
    lines.append(f"\n  [4] AgentCore Routing / Function Call:")
    lines.append(f"      Target Tool:  {tool_name}")
    lines.append(f"      Arguments:    {json.dumps(disp['arguments'])}")
    lines.append(f"      Rationale:    {disp['reason']}")
    lines.append(f"=======================================================\n")
    return "\n".join(lines)


def run_benchmark(router: AgentRouter, dataset_path: str):
    if not os.path.exists(dataset_path):
        print(f"Benchmark file not found: {dataset_path}")
        return

    with open(dataset_path, "r", encoding="utf-8") as f:
        cases = json.load(f)

    print(f"\nRunning benchmark on {len(cases)} test queries...\n")
    
    correct_intent = 0
    correct_tool = 0
    total = len(cases)
    
    failures = []

    for idx, case in enumerate(cases, 1):
        query = case["query"]
        expected_intent = case.get("expected_intent")
        expected_tool = case.get("expected_tool")
        category = case.get("category", "General")

        res = router.route(query)
        actual_intent = res["validation"]["validated_intent"]
        actual_tool = res["dispatch"]["tool"]

        intent_ok = (expected_intent is None) or (actual_intent == expected_intent)
        tool_ok = (expected_tool is None) or (actual_tool == expected_tool)

        if intent_ok:
            correct_intent += 1
        if tool_ok:
            correct_tool += 1
        else:
            failures.append({
                "index": idx,
                "category": category,
                "query": query,
                "expected_tool": expected_tool,
                "actual_tool": actual_tool,
                "expected_intent": expected_intent,
                "actual_intent": actual_intent,
                "reason": res["dispatch"]["reason"],
                "args": res["dispatch"]["arguments"]
            })

        status_sym = "[PASS]" if (intent_ok and tool_ok) else "[FAIL]"
        print(f"#{idx:02d} {status_sym} [{category:<12}] \"{query}\" -> Tool: {actual_tool} (Intent: {actual_intent})")

    print("\n" + "="*70)
    print(f" BENCHMARK SUMMARY")
    print("="*70)
    print(f" Total Test Queries:       {total}")
    print(f" Intent Accuracy:          {correct_intent}/{total} ({correct_intent/total*100:.1f}%)")
    print(f" Tool Routing Accuracy:    {correct_tool}/{total} ({correct_tool/total*100:.1f}%)")
    print("="*70)

    if failures:
        print(f"\n>>> IDENTIFIED FAILURES & MISROUTING ({len(failures)} cases) <<<\n")
        for f in failures:
            print(f"[FAIL #{f['index']}] Category: {f['category']}")
            print(f"  Query:           \"{f['query']}\"")
            print(f"  Expected Tool:   {f['expected_tool']} (Intent: {f['expected_intent']})")
            print(f"  Actual Tool:     {f['actual_tool']} (Intent: {f['actual_intent']})")
            print(f"  Routed Args:     {f['args']}")
            print(f"  Diagnostic:      {f['reason']}\n")


def interactive_repl(router: AgentRouter):
    print("\n=======================================================")
    print("  Friday Local NLU & Routing Simulator (Interactive REPL)")
    print("  Type any command to see the full pipeline trace.")
    print("  Type 'exit', 'quit', or 'q' to exit.")
    print("=======================================================\n")
    while True:
        try:
            query = input("Friday Query > ").strip()
            if not query:
                continue
            if query.lower() in ("exit", "quit", "q"):
                print("Exiting simulator.")
                break
            result = router.route(query)
            print(format_report(result))
        except (KeyboardInterrupt, EOFError):
            print("\nExiting.")
            break


def main():
    parser = argparse.ArgumentParser(description="Friday Local NLU & Routing PC Test Harness")
    parser.add_argument("--query", "-q", type=str, help="Single query to test")
    parser.add_argument("--benchmark", "-b", action="store_true", help="Run benchmark dataset")
    parser.add_argument("--dataset", "-d", type=str, default=os.path.join(os.path.dirname(__file__), "benchmark_dataset.json"),
                        help="Path to benchmark JSON dataset")
    args = parser.parse_args()

    if not os.path.exists(MODEL_PATH) or not os.path.exists(VOCAB_PATH) or not os.path.exists(LABELS_PATH):
        print(f"Error: Missing model assets in {ASSETS_DIR}")
        sys.exit(1)

    runner = NluModelRunner(MODEL_PATH, VOCAB_PATH, LABELS_PATH)
    router = AgentRouter(runner)

    if args.query:
        result = router.route(args.query)
        print(format_report(result))
    elif args.benchmark:
        run_benchmark(router, args.dataset)
    else:
        # Default to interactive REPL
        interactive_repl(router)


if __name__ == "__main__":
    main()

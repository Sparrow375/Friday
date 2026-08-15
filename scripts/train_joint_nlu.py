#!/usr/bin/env python3
"""
Friday Assistant - Joint Intent Classification & Neural Slot-Filling Trainer
(Fine-tuned with implicit messaging, postposition navigation, user profile recall, and expanded QA)
"""

import os
import sys
import json
import random
from typing import List, Dict, Tuple, Any

import torch
import torch.nn as nn
from torch.utils.data import Dataset, DataLoader

# ==============================================================================
# 1. SCHEMAS
# ==============================================================================

INTENT_LABELS = [
    "volume_up", "volume_down", "brightness_up", "brightness_down",
    "torch_toggle", "torch_strength", "lock_phone", "open_app",
    "navigate_to", "set_alarm", "set_timer", "send_whatsapp",
    "play_media", "play_spotify", "play_youtube",
    "pause_media", "next_track", "previous_track",
    "power_saver_toggle", "screencast_toggle",
    "wifi_toggle", "bluetooth_toggle", "hotspot_toggle", "dnd_toggle",
    "call_contact", "read_call_log",
    "take_screenshot", "web_search",
    "clipboard_read", "clipboard_write",
    "read_notifications", "get_battery", "get_time",
    "airplane_mode_toggle", "mobile_data_toggle",
    "open_camera", "open_files",
    "notes_create", "notes_list", "notes_search", "notes_delete",
    "search_google", "search_reddit", "remember_preference", "recall_preference",
    "unknown"
]

SLOT_LABELS = [
    "O",
    "B-CONTACT", "I-CONTACT",
    "B-MESSAGE", "I-MESSAGE",
    "B-DESTINATION", "I-DESTINATION",
    "B-APP", "I-APP",
    "B-QUERY", "I-QUERY",
    "B-TIME", "I-TIME",
    "B-VALUE", "I-VALUE",
    "B-NOTE_CONTENT", "I-NOTE_CONTENT",
    "B-FACT", "I-FACT",
    "B-NOTE_ID", "I-NOTE_ID",
    "B-TEXT", "I-TEXT"
]

INTENT_TO_ID = {intent: i for i, intent in enumerate(INTENT_LABELS)}
ID_TO_INTENT = {i: intent for i, intent in enumerate(INTENT_LABELS)}

SLOT_TO_ID = {slot: i for i, slot in enumerate(SLOT_LABELS)}
ID_TO_SLOT = {i: slot for i, slot in enumerate(SLOT_LABELS)}

# ==============================================================================
# 2. DATASET GENERATION
# ==============================================================================

NAMES = [
    "mom", "dad", "mother", "father", "sarah", "john", "alex", "david", "priya",
    "rohit", "alice", "bob", "rahul", "sister", "brother", "bro", "emma", "michael",
    "jessica", "daniel", "ananya", "vikram", "boss", "wife", "husband", "sam", "ryan",
    "kavya", "arjun", "sneha", "neha", "tanmay", "amit", "grandma", "grandpa", "kanak",
    "pooja", "varun", "manish", "divya"
]

MESSAGES = [
    "i am coming home", "please pick me up", "i will be there in 10 minutes",
    "the meeting is rescheduled", "hello how are you", "bring the keys",
    "see you at lunch", "congrats on the promotion", "i am late for dinner",
    "see you soon", "reach home safe", "on my way", "call me back when free",
    "don't forget to buy milk", "running 5 minutes late", "are we still on for tonight",
    "dinner is ready", "im coming home in 30 minutes", "can you send the document",
    "let me know when you reach", "happy birthday"
]

DESTINATIONS = [
    "home", "work", "central airport", "starbucks", "hyderabad", "uppal",
    "nearest gas station", "nearest hospital", "central mall", "times square",
    "downtown", "london", "san francisco", "the gym", "office", "cinema hall",
    "railway station", "city center", "brooklyn bridge", "pacific beach",
    "maharashtra", "mumbai", "delhi", "bangalore", "market", "metro station"
]

APPS = [
    "spotify", "youtube", "whatsapp", "instagram", "reddit", "chrome",
    "settings", "camera", "files", "maps", "netflix", "telegram", "twitter",
    "gmail", "discord", "calculator", "calendar", "gallery", "clock", "notes",
    "stotify", "insta", "yt", "fb", "snapchat"
]

MEDIA_QUERIES = [
    "sunflower", "taylor swift", "coldplay", "linkin park", "lo-fi beats",
    "rock music", "how to bake a cake", "cats funny videos", "workout playlist",
    "jazz music", "latest podcast episode", "space documentary", "relaxing piano",
    "ed sheeran", "eminem", "hans zimmer", "top hits 2026", "coding music",
    "previous song", "next track", "latest news"
]

TIMES = [
    "7 am", "6:30 am", "8:00 pm", "tomorrow morning at 8", "5 am", "6 pm",
    "10 minutes", "5 minutes", "30 seconds", "15 minutes", "1 hour", "45 mins",
    "20 minutes", "9:45 pm", "noon", "midnight", "tomorrow at 7:30 am"
]

FACTS = [
    "my favorite color is blue", "i prefer dark mode", "i am allergic to peanuts",
    "my car number is 4021", "my office starts at 9 am", "i live in new york",
    "my dog's name is max", "i drink coffee without sugar", "my birthday is in july",
    "i am a software engineer", "i like spicy food"
]

UNKNOWN_QUERIES = [
    "what is the capital of france", "tell me a funny joke", "tell a joke",
    "explain quantum computing simply", "how do i cook spaghetti carbonara",
    "write a python function to check prime numbers", "who wrote hamlet",
    "how far is the moon from earth", "what is the difference between AI and machine learning",
    "who is the president of the united states", "what is the speed of light",
    "can you write a poem about the ocean", "solve 25 multiplied by 4",
    "why is the sky blue", "how does an airplane fly", "what is the meaning of life",
    "give me 5 tips for better sleep", "how to learn rust programming language",
    "what causes earthquakes", "how does photosynthesis work", "who discovered gravity",
    "can you explain relativity", "what is the tallest mountain in the world",
    "capital of mumbai", "capital of india", "capital of maharashtra", "capital of texas",
    "whats the capital of india", "whats the capital of maharashtra", "capital of japan",
    "who won the world cup", "how to tie a tie", "why do we dream", "good", "hello", "hi", "wd", "wdw"
]


def annotate_span(words: List[str], tag_prefix: str) -> List[Tuple[str, str]]:
    return [(w, f"B-{tag_prefix}" if i == 0 else f"I-{tag_prefix}") for i, w in enumerate(words)]


def create_annotated_sample(words_with_tags: List[Tuple[str, str]], intent: str) -> Dict[str, Any]:
    text = " ".join([w for w, _ in words_with_tags])
    slots = [t for _, t in words_with_tags]
    return {"text": text, "intent": intent, "slots": slots}


def generate_synthetic_dataset(samples_per_intent: int = 120) -> List[Dict[str, Any]]:
    dataset = []

    for _ in range(samples_per_intent):
        # 1. Volume
        v_pct = f"{random.randint(10, 100)}%"
        dataset.append(create_annotated_sample([("increase", "O"), ("volume", "O")], "volume_up"))
        dataset.append(create_annotated_sample([("turn", "O"), ("up", "O"), ("the", "O"), ("sound", "O")], "volume_up"))
        dataset.append(create_annotated_sample([("boost", "O"), ("the", "O"), ("volume", "O")], "volume_up"))
        dataset.append(create_annotated_sample([("boost", "O"), ("volume", "O")], "volume_up"))
        dataset.append(create_annotated_sample([("make", "O"), ("it", "O"), ("louder", "O"), ("please", "O")], "volume_up"))
        dataset.append(create_annotated_sample([("crank", "O"), ("the", "O"), ("volume", "O")], "volume_up"))
        dataset.append(create_annotated_sample([("pump", "O"), ("up", "O"), ("the", "O"), ("volume", "O")], "volume_up"))
        dataset.append(create_annotated_sample([("set", "O"), ("volume", "O"), ("to", "O"), (v_pct, "B-VALUE")], "volume_up"))

        dataset.append(create_annotated_sample([("lower", "O"), ("volume", "O")], "volume_down"))
        dataset.append(create_annotated_sample([("turn", "O"), ("down", "O"), ("the", "O"), ("sound", "O")], "volume_down"))
        dataset.append(create_annotated_sample([("make", "O"), ("it", "O"), ("quieter", "O")], "volume_down"))
        dataset.append(create_annotated_sample([("turn", "O"), ("down", "O"), ("the", "O"), ("music", "O")], "volume_down"))
        dataset.append(create_annotated_sample([("mute", "O"), ("audio", "O")], "volume_down"))
        dataset.append(create_annotated_sample([("mute", "O"), ("the", "O"), ("sound", "O")], "volume_down"))
        dataset.append(create_annotated_sample([("silence", "O"), ("media", "O")], "volume_down"))

        # 2. Brightness
        b_pct = f"{random.randint(10, 100)}%"
        dataset.append(create_annotated_sample([("make", "O"), ("the", "O"), ("screen", "O"), ("brighter", "O")], "brightness_up"))
        dataset.append(create_annotated_sample([("increase", "O"), ("screen", "O"), ("brightness", "O")], "brightness_up"))
        dataset.append(create_annotated_sample([("turn", "O"), ("up", "O"), ("brightness", "O")], "brightness_up"))
        dataset.append(create_annotated_sample([("make", "O"), ("display", "O"), ("brighter", "O")], "brightness_up"))
        dataset.append(create_annotated_sample([("set", "O"), ("brightness", "O"), ("to", "O"), ("maximum", "B-VALUE")], "brightness_up"))
        dataset.append(create_annotated_sample([("set", "O"), ("brightness", "O"), ("to", "O"), (b_pct, "B-VALUE")], "brightness_up"))

        dataset.append(create_annotated_sample([("dim", "O"), ("the", "O"), ("screen", "O")], "brightness_down"))
        dataset.append(create_annotated_sample([("decrease", "O"), ("screen", "O"), ("brightness", "O")], "brightness_down"))
        dataset.append(create_annotated_sample([("lower", "O"), ("brightness", "O")], "brightness_down"))
        dataset.append(create_annotated_sample([("the", "O"), ("screen", "O"), ("is", "O"), ("too", "O"), ("bright", "O")], "brightness_down"))
        dataset.append(create_annotated_sample([("the", "O"), ("display", "O"), ("is", "O"), ("blinding", "O"), ("me", "O")], "brightness_down"))
        dataset.append(create_annotated_sample([("make", "O"), ("screen", "O"), ("darker", "O")], "brightness_down"))

        # 3. Torch
        dataset.append(create_annotated_sample([("turn", "O"), ("on", "O"), ("flashlight", "O")], "torch_toggle"))
        dataset.append(create_annotated_sample([("turn", "O"), ("on", "O"), ("torch", "O")], "torch_toggle"))
        dataset.append(create_annotated_sample([("enable", "O"), ("flashlight", "O")], "torch_toggle"))
        dataset.append(create_annotated_sample([("turn", "O"), ("off", "O"), ("flashlight", "O")], "torch_toggle"))
        dataset.append(create_annotated_sample([("turn", "O"), ("off", "O"), ("the", "O"), ("torch", "O")], "torch_toggle"))
        dataset.append(create_annotated_sample([("kill", "O"), ("the", "O"), ("torch", "O")], "torch_toggle"))
        dataset.append(create_annotated_sample([("disable", "O"), ("flashlight", "O")], "torch_toggle"))
        dataset.append(create_annotated_sample([("it", "O"), ("is", "O"), ("pitch", "O"), ("dark", "O"), ("in", "O"), ("here", "O")], "torch_toggle"))
        dataset.append(create_annotated_sample([("set", "O"), ("torch", "O"), ("strength", "O"), ("to", "O"), (f"level {random.randint(1,5)}", "B-VALUE")], "torch_strength"))
        dataset.append(create_annotated_sample([("flashlight", "O"), ("brightness", "O"), ("to", "O"), ("maximum", "B-VALUE")], "torch_strength"))

        # 4. Lock Phone
        dataset.append(create_annotated_sample([("lock", "O"), ("my", "O"), ("phone", "O")], "lock_phone"))
        dataset.append(create_annotated_sample([("lock", "O"), ("the", "O"), ("phone", "O")], "lock_phone"))
        dataset.append(create_annotated_sample([("lock", "O"), ("screen", "O")], "lock_phone"))
        dataset.append(create_annotated_sample([("put", "O"), ("phone", "O"), ("to", "O"), ("sleep", "O")], "lock_phone"))
        dataset.append(create_annotated_sample([("turn", "O"), ("off", "O"), ("screen", "O"), ("display", "O")], "lock_phone"))
        dataset.append(create_annotated_sample([("turn", "O"), ("off", "O"), ("the", "O"), ("screen", "O")], "lock_phone"))
        dataset.append(create_annotated_sample([("sleep", "O"), ("the", "O"), ("screen", "O")], "lock_phone"))

        # 5. App Launcher
        app = random.choice(APPS)
        dataset.append(create_annotated_sample([("open", "O"), (app, "B-APP")], "open_app"))
        dataset.append(create_annotated_sample([("launch", "O"), (app, "B-APP")], "open_app"))
        dataset.append(create_annotated_sample([("start", "O"), (app, "B-APP"), ("app", "O")], "open_app"))
        dataset.append(create_annotated_sample([("open", "O"), ("up", "O"), (app, "B-APP")], "open_app"))
        dataset.append(create_annotated_sample([("go", "O"), ("to", "O"), (app, "B-APP")], "open_app"))
        dataset.append(create_annotated_sample([("open", "O"), ("camera", "O")], "open_camera"))
        dataset.append(create_annotated_sample([("take", "O"), ("a", "O"), ("photo", "O")], "open_camera"))
        dataset.append(create_annotated_sample([("launch", "O"), ("camera", "O")], "open_camera"))
        dataset.append(create_annotated_sample([("open", "O"), ("my", "O"), ("files", "O")], "open_files"))
        dataset.append(create_annotated_sample([("show", "O"), ("downloads", "O"), ("folder", "O")], "open_files"))

        # 6. Navigation (Including Postpositions)
        dest = random.choice(DESTINATIONS)
        dest_annotated = annotate_span(dest.split(), "DESTINATION")
        dataset.append(create_annotated_sample([("navigate", "O"), ("to", "O")] + dest_annotated, "navigate_to"))
        dataset.append(create_annotated_sample([("find", "O"), ("me", "O"), ("directions", "O"), ("to", "O")] + dest_annotated, "navigate_to"))
        dataset.append(create_annotated_sample([("directions", "O"), ("to", "O")] + dest_annotated, "navigate_to"))
        dataset.append(create_annotated_sample([("take", "O"), ("me", "O"), ("to", "O")] + dest_annotated, "navigate_to"))
        dataset.append(create_annotated_sample([("how", "O"), ("do", "O"), ("i", "O"), ("get", "O"), ("to", "O")] + dest_annotated, "navigate_to"))
        dataset.append(create_annotated_sample([("show", "O"), ("routes", "O"), ("to", "O")] + dest_annotated, "navigate_to"))
        dataset.append(create_annotated_sample([("take", "O"), ("me", "O"), ("home", "B-DESTINATION")], "navigate_to"))
        # Postposition patterns: "<dest> directions"
        dataset.append(create_annotated_sample(dest_annotated + [("directions", "O")], "navigate_to"))
        dataset.append(create_annotated_sample(dest_annotated + [("routes", "O")], "navigate_to"))
        dataset.append(create_annotated_sample(dest_annotated + [("traffic", "O")], "navigate_to"))

        # 7. Alarms & Timers
        t_val = random.choice(TIMES)
        t_annotated = annotate_span(t_val.split(), "TIME")
        dataset.append(create_annotated_sample([("set", "O"), ("alarm", "O"), ("for", "O")] + t_annotated, "set_alarm"))
        dataset.append(create_annotated_sample([("wake", "O"), ("me", "O"), ("up", "O"), ("at", "O")] + t_annotated, "set_alarm"))
        dataset.append(create_annotated_sample([("alarm", "O"), ("for", "O")] + t_annotated, "set_alarm"))
        dataset.append(create_annotated_sample([("set", "O"), ("timer", "O"), ("for", "O")] + t_annotated, "set_timer"))
        dataset.append(create_annotated_sample([("countdown", "O"), ("for", "O")] + t_annotated, "set_timer"))
        dataset.append(create_annotated_sample([("start", "O"), ("a", "O")] + t_annotated + [("timer", "O")], "set_timer"))
        dataset.append(create_annotated_sample([("timer", "O"), ("for", "O")] + t_annotated, "set_timer"))

        # 8. WhatsApp Messaging (With & Without explicit 'whatsapp')
        name = random.choice(NAMES)
        name_annotated = annotate_span(name.split(), "CONTACT")
        msg = random.choice(MESSAGES)
        msg_annotated = annotate_span(msg.split(), "MESSAGE")

        # Explicit WhatsApp
        dataset.append(create_annotated_sample(
            [("send", "O"), ("message", "O"), ("to", "O")] + name_annotated + [("on", "O"), ("whatsapp", "O"), ("saying", "O")] + msg_annotated, "send_whatsapp"
        ))
        dataset.append(create_annotated_sample(
            [("text", "O")] + name_annotated + [("on", "O"), ("whatsapp", "O"), ("saying", "O")] + msg_annotated, "send_whatsapp"
        ))
        dataset.append(create_annotated_sample(
            [("whatsapp", "O")] + name_annotated + [("saying", "O")] + msg_annotated, "send_whatsapp"
        ))
        dataset.append(create_annotated_sample(
            [("message", "O")] + name_annotated + [("on", "O"), ("whatsapp", "O"), ("that", "O")] + msg_annotated, "send_whatsapp"
        ))
        # Implicit messaging ("text <contact> <msg>", "can you text <contact> <msg>")
        dataset.append(create_annotated_sample(
            [("can", "O"), ("you", "O"), ("text", "O")] + name_annotated + msg_annotated, "send_whatsapp"
        ))
        dataset.append(create_annotated_sample(
            [("text", "O")] + name_annotated + msg_annotated, "send_whatsapp"
        ))
        dataset.append(create_annotated_sample(
            [("text", "O")] + name_annotated + [("saying", "O")] + msg_annotated, "send_whatsapp"
        ))
        dataset.append(create_annotated_sample(
            [("message", "O")] + name_annotated + msg_annotated, "send_whatsapp"
        ))
        dataset.append(create_annotated_sample(
            [("send", "O"), ("a", "O"), ("message", "O"), ("to", "O")] + name_annotated + [("saying", "O")] + msg_annotated, "send_whatsapp"
        ))
        dataset.append(create_annotated_sample(
            [("ping", "O")] + name_annotated + [("that", "O")] + msg_annotated, "send_whatsapp"
        ))

        # 9. Phone Calls & Call Log
        dataset.append(create_annotated_sample([("call", "O")] + name_annotated, "call_contact"))
        dataset.append(create_annotated_sample([("can", "O"), ("you", "O"), ("call", "O")] + name_annotated, "call_contact"))
        dataset.append(create_annotated_sample([("dial", "O")] + name_annotated + [("please", "O")], "call_contact"))
        dataset.append(create_annotated_sample([("give", "O")] + name_annotated + [("a", "O"), ("call", "O")], "call_contact"))
        dataset.append(create_annotated_sample([("make", "O"), ("a", "O"), ("phone", "O"), ("call", "O"), ("to", "O")] + name_annotated, "call_contact"))
        dataset.append(create_annotated_sample([("check", "O"), ("recent", "O"), ("calls", "O")], "read_call_log"))
        dataset.append(create_annotated_sample([("who", "O"), ("called", "O"), ("me", "O"), ("recently", "O")], "read_call_log"))
        dataset.append(create_annotated_sample([("show", "O"), ("call", "O"), ("history", "O")], "read_call_log"))
        dataset.append(create_annotated_sample([("show", "O"), ("missed", "O"), ("calls", "O")], "read_call_log"))

        # 10. Media Controls
        mq = random.choice(MEDIA_QUERIES)
        mq_annotated = annotate_span(mq.split(), "QUERY")
        dataset.append(create_annotated_sample([("play", "O")] + mq_annotated + [("on", "O"), ("spotify", "B-APP")], "play_spotify"))
        dataset.append(create_annotated_sample([("listen", "O"), ("to", "O")] + mq_annotated + [("on", "O"), ("spotify", "B-APP")], "play_spotify"))
        dataset.append(create_annotated_sample([("search", "O")] + mq_annotated + [("on", "O"), ("youtube", "B-APP")], "play_youtube"))
        dataset.append(create_annotated_sample([("play", "O")] + mq_annotated + [("on", "O"), ("youtube", "B-APP")], "play_youtube"))
        dataset.append(create_annotated_sample([("play", "O")] + mq_annotated, "play_media"))
        dataset.append(create_annotated_sample([("listen", "O"), ("to", "O")] + mq_annotated, "play_media"))
        dataset.append(create_annotated_sample([("pause", "O"), ("playback", "O")], "pause_media"))
        dataset.append(create_annotated_sample([("pause", "O"), ("the", "O"), ("music", "O")], "pause_media"))
        dataset.append(create_annotated_sample([("stop", "O"), ("music", "O")], "pause_media"))
        dataset.append(create_annotated_sample([("next", "O"), ("track", "O")], "next_track"))
        dataset.append(create_annotated_sample([("next", "O"), ("song", "O")], "next_track"))
        dataset.append(create_annotated_sample([("previous", "O"), ("song", "O")], "previous_track"))
        dataset.append(create_annotated_sample([("previous", "O"), ("track", "O")], "previous_track"))
        dataset.append(create_annotated_sample([("go", "O"), ("back", "O"), ("to", "O"), ("previous", "O"), ("song", "O")], "previous_track"))

        # 11. System Controls
        dataset.append(create_annotated_sample([("turn", "O"), ("on", "O"), ("wifi", "O")], "wifi_toggle"))
        dataset.append(create_annotated_sample([("turn", "O"), ("off", "O"), ("wifi", "O")], "wifi_toggle"))
        dataset.append(create_annotated_sample([("enable", "O"), ("wifi", "O")], "wifi_toggle"))
        dataset.append(create_annotated_sample([("disable", "O"), ("wifi", "O")], "wifi_toggle"))
        dataset.append(create_annotated_sample([("turn", "O"), ("on", "O"), ("bluetooth", "O")], "bluetooth_toggle"))
        dataset.append(create_annotated_sample([("turn", "O"), ("off", "O"), ("bluetooth", "O")], "bluetooth_toggle"))
        dataset.append(create_annotated_sample([("enable", "O"), ("bluetooth", "O")], "bluetooth_toggle"))
        dataset.append(create_annotated_sample([("disable", "O"), ("bluetooth", "O")], "bluetooth_toggle"))
        dataset.append(create_annotated_sample([("turn", "O"), ("on", "O"), ("hotspot", "O")], "hotspot_toggle"))
        dataset.append(create_annotated_sample([("turn", "O"), ("off", "O"), ("hotspot", "O")], "hotspot_toggle"))
        dataset.append(create_annotated_sample([("turn", "O"), ("on", "O"), ("do", "O"), ("not", "O"), ("disturb", "O")], "dnd_toggle"))
        dataset.append(create_annotated_sample([("turn", "O"), ("off", "O"), ("do", "O"), ("not", "O"), ("disturb", "O")], "dnd_toggle"))
        dataset.append(create_annotated_sample([("turn", "O"), ("on", "O"), ("battery", "O"), ("saver", "O")], "power_saver_toggle"))
        dataset.append(create_annotated_sample([("turn", "O"), ("off", "O"), ("battery", "O"), ("saver", "O")], "power_saver_toggle"))
        dataset.append(create_annotated_sample([("start", "O"), ("screen", "O"), ("mirroring", "O")], "screencast_toggle"))
        dataset.append(create_annotated_sample([("stop", "O"), ("screen", "O"), ("mirroring", "O")], "screencast_toggle"))
        dataset.append(create_annotated_sample([("screencast", "O")], "screencast_toggle"))
        dataset.append(create_annotated_sample([("enable", "O"), ("airplane", "O"), ("mode", "O")], "airplane_mode_toggle"))
        dataset.append(create_annotated_sample([("turn", "O"), ("on", "O"), ("mobile", "O"), ("data", "O")], "mobile_data_toggle"))
        dataset.append(create_annotated_sample([("turn", "O"), ("off", "O"), ("mobile", "O"), ("data", "O")], "mobile_data_toggle"))
        dataset.append(create_annotated_sample([("take", "O"), ("a", "O"), ("screenshot", "O")], "take_screenshot"))
        dataset.append(create_annotated_sample([("take", "O"), ("a", "O"), ("snap", "O"), ("of", "O"), ("the", "O"), ("screen", "O")], "take_screenshot"))
        dataset.append(create_annotated_sample([("capture", "O"), ("the", "O"), ("screen", "O")], "take_screenshot"))
        dataset.append(create_annotated_sample([("screenshot", "O"), ("this", "O")], "take_screenshot"))

        # 12. Clipboard, Notifications, Battery, Time
        dataset.append(create_annotated_sample([("read", "O"), ("my", "O"), ("clipboard", "O")], "clipboard_read"))
        dataset.append(create_annotated_sample([("what", "O"), ("is", "O"), ("on", "O"), ("my", "O"), ("clipboard", "O")], "clipboard_read"))
        dataset.append(create_annotated_sample([("what", "O"), ("did", "O"), ("i", "O"), ("copy", "O")], "clipboard_read"))
        dataset.append(create_annotated_sample([("copy", "O"), ("to", "O"), ("clipboard", "O"), ("hello", "B-TEXT"), ("world", "I-TEXT")], "clipboard_write"))
        dataset.append(create_annotated_sample([("copy", "O"), ("this", "O"), ("text", "O"), ("to", "O"), ("clipboard", "O"), ("admin123", "B-TEXT")], "clipboard_write"))
        dataset.append(create_annotated_sample([("read", "O"), ("notifications", "O")], "read_notifications"))
        dataset.append(create_annotated_sample([("what", "O"), ("notifications", "O"), ("do", "O"), ("i", "O"), ("have", "O")], "read_notifications"))
        dataset.append(create_annotated_sample([("check", "O"), ("my", "O"), ("new", "O"), ("notifications", "O")], "read_notifications"))
        dataset.append(create_annotated_sample([("what", "O"), ("is", "O"), ("my", "O"), ("battery", "O"), ("level", "O")], "get_battery"))
        dataset.append(create_annotated_sample([("how", "O"), ("much", "O"), ("battery", "O"), ("is", "O"), ("left", "O")], "get_battery"))
        dataset.append(create_annotated_sample([("check", "O"), ("battery", "O"), ("percentage", "O")], "get_battery"))
        dataset.append(create_annotated_sample([("what", "O"), ("time", "O"), ("is", "O"), ("it", "O")], "get_time"))
        dataset.append(create_annotated_sample([("tell", "O"), ("me", "O"), ("the", "O"), ("time", "O")], "get_time"))

        # 13. Notes & Reminders (Including "note my <content>", "remind me to <content> in <time>")
        note_text = random.choice(["buy milk", "meeting with client at 3pm", "call rohit", "drink water", "my metro card balance is 100 rupees", "pay electricity bill", "bring passport"])
        note_annotated = annotate_span(note_text.split(), "NOTE_CONTENT")
        dataset.append(create_annotated_sample([("remind", "O"), ("me", "O"), ("to", "O")] + note_annotated + [("in", "O"), ("10", "B-TIME"), ("minutes", "I-TIME")], "notes_create"))
        dataset.append(create_annotated_sample([("jot", "O"), ("down", "O")] + note_annotated, "notes_create"))
        dataset.append(create_annotated_sample([("save", "O"), ("a", "O"), ("note", "O"), ("that", "O")] + note_annotated, "notes_create"))
        dataset.append(create_annotated_sample([("note", "O"), ("down", "O")] + note_annotated, "notes_create"))
        dataset.append(create_annotated_sample([("note", "O"), ("my", "O")] + note_annotated, "notes_create"))
        dataset.append(create_annotated_sample([("create", "O"), ("a", "O"), ("note", "O")] + note_annotated, "notes_create"))
        dataset.append(create_annotated_sample([("find", "O"), ("notes", "O"), ("about", "O"), ("rent", "B-QUERY")], "notes_search"))
        dataset.append(create_annotated_sample([("show", "O"), ("all", "O"), ("my", "O"), ("notes", "O")], "notes_list"))
        dataset.append(create_annotated_sample([("list", "O"), ("all", "O"), ("notes", "O")], "notes_list"))
        dataset.append(create_annotated_sample([("tell", "O"), ("my", "O"), ("notes", "O")], "notes_list"))
        dataset.append(create_annotated_sample([("delete", "O"), ("note", "O"), (f"{random.randint(1,20)}", "B-NOTE_ID")], "notes_delete"))

        # 14. Web & Reddit Search
        sq = random.choice(["who won the match", "quantum physics", "weather in tokyo", "how to make pasta", "best laptops 2026"])
        sq_annotated = annotate_span(sq.split(), "QUERY")
        dataset.append(create_annotated_sample([("google", "O")] + sq_annotated, "search_google"))
        dataset.append(create_annotated_sample([("search", "O")] + sq_annotated + [("on", "O"), ("google", "O")], "search_google"))
        dataset.append(create_annotated_sample([("search", "O"), ("reddit", "O"), ("for", "O")] + sq_annotated, "search_reddit"))
        dataset.append(create_annotated_sample([("look", "O"), ("up", "O")] + sq_annotated + [("on", "O"), ("reddit", "O")], "search_reddit"))
        dataset.append(create_annotated_sample([("search", "O"), ("the", "O"), ("web", "O"), ("for", "O")] + sq_annotated, "web_search"))
        dataset.append(create_annotated_sample([("look", "O"), ("up", "O")] + sq_annotated + [("online", "O")], "web_search"))

        # 15. User Preferences & Profile Recall
        fact = random.choice(FACTS)
        fact_annotated = annotate_span(fact.split(), "FACT")
        dataset.append(create_annotated_sample([("remember", "O"), ("that", "O")] + fact_annotated, "remember_preference"))
        dataset.append(create_annotated_sample([("store", "O"), ("the", "O"), ("fact", "O"), ("that", "O")] + fact_annotated, "remember_preference"))
        dataset.append(create_annotated_sample([("recall", "O"), ("what", "O"), ("you", "O"), ("know", "O"), ("about", "O"), ("me", "O")], "recall_preference"))
        dataset.append(create_annotated_sample([("what", "O"), ("do", "O"), ("you", "O"), ("remember", "O"), ("about", "O"), ("me", "O")], "recall_preference"))
        dataset.append(create_annotated_sample([("tell", "O"), ("me", "O"), ("about", "O"), ("myself", "O")], "recall_preference"))
        dataset.append(create_annotated_sample([("who", "O"), ("am", "O"), ("i", "O")], "recall_preference"))
        dataset.append(create_annotated_sample([("what", "O"), ("are", "O"), ("my", "O"), ("saved", "O"), ("preferences", "O")], "recall_preference"))

        # Specific user memory & attribute queries ("what is my <attribute>")
        attr = random.choice([
            "metro card balance", "metro balance", "car number", "wifi password",
            "blood group", "passport number", "favorite color", "home address",
            "office address", "dog's name", "birthday", "flight number"
        ])
        attr_annotated = annotate_span(attr.split(), "QUERY")
        dataset.append(create_annotated_sample([("what", "O"), ("is", "O"), ("my", "O")] + attr_annotated, "recall_preference"))
        dataset.append(create_annotated_sample([("whats", "O"), ("my", "O")] + attr_annotated, "recall_preference"))
        dataset.append(create_annotated_sample([("tell", "O"), ("me", "O"), ("my", "O")] + attr_annotated, "recall_preference"))
        dataset.append(create_annotated_sample([("what", "O"), ("did", "O"), ("i", "O"), ("note", "O"), ("down", "O"), ("about", "O")] + attr_annotated, "notes_search"))
        dataset.append(create_annotated_sample([("search", "O"), ("notes", "O"), ("for", "O")] + attr_annotated, "notes_search"))


        # 16. Conversational / Unknown
        unk = random.choice(UNKNOWN_QUERIES)
        dataset.append(create_annotated_sample([(w, "O") for w in unk.split()], "unknown"))

    random.shuffle(dataset)
    return dataset


# ==============================================================================
# 3. PYTORCH DATASET WITH SUBWORD ALIGNMENT
# ==============================================================================

class JointDataset(Dataset):
    def __init__(self, data: List[Dict[str, Any]], tokenizer, max_len: int = 48):
        self.data = data
        self.tokenizer = tokenizer
        self.max_len = max_len

    def __len__(self):
        return len(self.data)

    def __getitem__(self, idx):
        item = self.data[idx]
        text_words = item["text"].split()
        word_slots = item["slots"]
        intent_id = INTENT_TO_ID.get(item["intent"], INTENT_TO_ID["unknown"])

        input_ids = [self.tokenizer.cls_token_id]
        slot_ids = [-100]

        for word, slot in zip(text_words, word_slots):
            sub_tokens = self.tokenizer.tokenize(word)
            sub_ids = self.tokenizer.convert_tokens_to_ids(sub_tokens)
            if not sub_ids:
                continue
            input_ids.extend(sub_ids)

            slot_id = SLOT_TO_ID.get(slot, 0)
            slot_ids.append(slot_id)

            i_slot = slot
            if slot.startswith("B-"):
                i_slot = "I-" + slot[2:]
            i_slot_id = SLOT_TO_ID.get(i_slot, slot_id)

            for _ in range(len(sub_ids) - 1):
                slot_ids.append(i_slot_id)

        input_ids.append(self.tokenizer.sep_token_id)
        slot_ids.append(-100)

        if len(input_ids) > self.max_len:
            input_ids = input_ids[:self.max_len]
            slot_ids = slot_ids[:self.max_len]

        attention_mask = [1] * len(input_ids)
        pad_len = self.max_len - len(input_ids)

        input_ids += [self.tokenizer.pad_token_id] * pad_len
        attention_mask += [0] * pad_len
        slot_ids += [-100] * pad_len

        return {
            "input_ids": torch.tensor(input_ids, dtype=torch.long),
            "attention_mask": torch.tensor(attention_mask, dtype=torch.long),
            "intent_label": torch.tensor(intent_id, dtype=torch.long),
            "slot_labels": torch.tensor(slot_ids, dtype=torch.long)
        }


class JointTransformerNLU(nn.Module):
    def __init__(self, encoder, num_intents: int, num_slots: int):
        super().__init__()
        self.encoder = encoder
        hidden_size = encoder.config.hidden_size
        self.dropout = nn.Dropout(0.15)
        self.intent_head = nn.Linear(hidden_size, num_intents)
        self.slot_head = nn.Linear(hidden_size, num_slots)

    def forward(self, input_ids, attention_mask):
        outputs = self.encoder(input_ids=input_ids, attention_mask=attention_mask)
        sequence_output = outputs.last_hidden_state
        cls_output = sequence_output[:, 0, :]
        intent_logits = self.intent_head(self.dropout(cls_output))
        slot_logits = self.slot_head(self.dropout(sequence_output))
        return intent_logits, slot_logits


# ==============================================================================
# 4. TRAINING & ONNX EXPORT
# ==============================================================================

def train_and_export():
    print("=" * 70)
    print(" Friday Joint NLU Fine-Tuning (Edge Cases & Implicit Messaging)")
    print("=" * 70)

    from transformers import AutoTokenizer, AutoModel
    import onnxruntime as ort
    from onnxruntime.quantization import quantize_dynamic, QuantType

    device = torch.device("cuda" if torch.cuda.is_available() else "cpu")
    print(f"Using compute device: {device}")

    model_name = "sentence-transformers/all-MiniLM-L6-v2"
    tokenizer = AutoTokenizer.from_pretrained(model_name)
    base_encoder = AutoModel.from_pretrained(model_name)

    print("Generating enriched synthetic dataset...")
    raw_data = generate_synthetic_dataset(samples_per_intent=140)
    print(f"Total training samples: {len(raw_data)} across {len(INTENT_LABELS)} intents and {len(SLOT_LABELS)} slot tags.")

    dataset = JointDataset(raw_data, tokenizer, max_len=48)
    dataloader = DataLoader(dataset, batch_size=32, shuffle=True)

    model = JointTransformerNLU(base_encoder, len(INTENT_LABELS), len(SLOT_LABELS)).to(device)

    intent_loss_fn = nn.CrossEntropyLoss()
    slot_loss_fn = nn.CrossEntropyLoss(ignore_index=-100)
    optimizer = torch.optim.AdamW(model.parameters(), lr=5e-5, weight_decay=0.01)

    print("\nTraining for 5 epochs...")
    model.train()
    for epoch in range(1, 6):
        total_loss = 0.0
        for batch in dataloader:
            input_ids = batch["input_ids"].to(device)
            attention_mask = batch["attention_mask"].to(device)
            intent_labels = batch["intent_label"].to(device)
            slot_labels = batch["slot_labels"].to(device)

            optimizer.zero_grad()
            intent_logits, slot_logits = model(input_ids, attention_mask)
            i_loss = intent_loss_fn(intent_logits, intent_labels)
            s_loss = slot_loss_fn(slot_logits.view(-1, len(SLOT_LABELS)), slot_labels.view(-1))
            loss = i_loss + 1.5 * s_loss
            loss.backward()
            optimizer.step()
            total_loss += loss.item()

        print(f"Epoch [{epoch}/5] - Loss: {total_loss/len(dataloader):.4f}")

    output_dir = os.path.join(os.path.dirname(__file__), "..", "output")
    os.makedirs(output_dir, exist_ok=True)

    with open(os.path.join(output_dir, "joint_intent_labels.json"), "w") as f:
        json.dump(INTENT_LABELS, f, indent=2)
    with open(os.path.join(output_dir, "joint_slot_labels.json"), "w") as f:
        json.dump(SLOT_LABELS, f, indent=2)

    model.eval()
    fp32_path = os.path.join(output_dir, "joint_nlu_fp32.onnx")
    int8_path = os.path.join(output_dir, "joint_nlu_model.onnx")
    dummy_input_ids = torch.ones(1, 48, dtype=torch.long).to(device)
    dummy_attention_mask = torch.ones(1, 48, dtype=torch.long).to(device)

    print("\nExporting ONNX model with TorchScript (dynamo=False)...")
    torch.onnx.export(
        model,
        (dummy_input_ids, dummy_attention_mask),
        fp32_path,
        export_params=True,
        input_names=["input_ids", "attention_mask"],
        output_names=["intent_logits", "slot_logits"],
        dynamic_axes={
            "input_ids": {0: "batch_size", 1: "sequence_length"},
            "attention_mask": {0: "batch_size", 1: "sequence_length"},
            "intent_logits": {0: "batch_size"},
            "slot_logits": {0: "batch_size", 1: "sequence_length"}
        },
        opset_version=14,
        do_constant_folding=True,
        dynamo=False
    )

    print("Quantizing to dynamic INT8 ONNX...")
    quantize_dynamic(
        model_input=fp32_path,
        model_output=int8_path,
        weight_type=QuantType.QInt8,
        extra_options={"EnableShapeInference": False}
    )

    if os.path.exists(fp32_path):
        os.remove(fp32_path)

    size_mb = os.path.getsize(int8_path) / (1024 * 1024)
    print(f"\n✅ Created Joint NLU Model: {int8_path} ({size_mb:.1f} MB)")


if __name__ == "__main__":
    train_and_export()

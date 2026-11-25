"""
Friday Joint NLU v2 Trainer & INT8 ONNX Exporter
=================================================
Upgrades from 6-layer MiniLM (48 intents) to 12-layer BAAI/bge-small-en-v1.5 (56 intents + 23 BIO slots)
trained locally on CUDA GPU with stratified validation, balanced per-intent sampling, ASR noise augmentation,
label smoothing, and dynamic INT8 ONNX export directly to app/src/main/assets/.
"""

import os
import json
import random
import math
import time
from collections import defaultdict
from typing import List, Tuple, Dict, Any

import numpy as np
import torch
import torch.nn as nn
from torch.utils.data import Dataset, DataLoader
from sklearn.metrics import f1_score, classification_report
from transformers import AutoTokenizer, AutoModel, get_cosine_schedule_with_warmup
from onnxruntime.quantization import quantize_dynamic, QuantType

SEED = 42
random.seed(SEED)
np.random.seed(SEED)
torch.manual_seed(SEED)
if torch.cuda.is_available():
    torch.cuda.manual_seed_all(SEED)

# ==============================================================================
# 1. INTENT & SLOT SCHEMAS (56 Intents, 23 BIO Slot Tags)
# ==============================================================================

INTENT_LABELS = [
    # Original 48 intents (indices preserved cleanly)
    "volume_up",
    "volume_down",
    "brightness_up",
    "brightness_down",
    "torch_toggle",
    "torch_strength",
    "lock_phone",
    "open_app",
    "navigate_to",
    "set_alarm",
    "set_timer",
    "set_reminder",
    "send_whatsapp",
    "play_media",
    "play_spotify",
    "play_youtube",
    "pause_media",
    "next_track",
    "previous_track",
    "power_saver_toggle",
    "screencast_toggle",
    "wifi_toggle",
    "bluetooth_toggle",
    "hotspot_toggle",
    "dnd_toggle",
    "call_contact",
    "read_call_log",
    "take_screenshot",
    "web_search",
    "clipboard_read",
    "clipboard_write",
    "read_notifications",
    "get_battery",
    "get_time",
    "airplane_mode_toggle",
    "mobile_data_toggle",
    "open_camera",
    "open_files",
    "notes_create",
    "notes_list",
    "notes_search",
    "notes_delete",
    "notes_update",
    "search_google",
    "search_reddit",
    "remember_preference",
    "recall_preference",
    "unknown",
    # 8 New NLU v2 intents
    "sports_score",
    "sports_follow",
    "calendar_list",
    "calendar_add",
    "reminder_list",
    "routine_create",
    "routine_run",
    "digest_summary",
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
    "B-TEXT", "I-TEXT",
]

INTENT_TO_ID = {lbl: idx for idx, lbl in enumerate(INTENT_LABELS)}
SLOT_TO_ID = {lbl: idx for idx, lbl in enumerate(SLOT_LABELS)}

# ==============================================================================
# 2. SYNTHETIC + CURATED CORPUS GENERATOR WITH BALANCED SAMPLING
# ==============================================================================

NAMES = [
    "mom", "dad", "john", "sarah", "alex", "emma", "david", "priya", "rahul",
    "rohit", "ankit", "mike", "lisa", "bro", "sister", "manager", "boss",
    "arjun", "neha", "kiran", "alice", "tom", "9876543210", "9123456780"
]

MESSAGES = [
    "i am coming home", "please pick me up", "i will be there in 10 minutes",
    "running late today", "call me back when free", "meet me at the cafe",
    "the meeting is rescheduled", "happy birthday", "dont wait for me",
    "bring the keys", "see you at lunch", "congrats", "hello how are you",
    "got it thanks", "are you home yet", "check your email"
]

DESTINATIONS = [
    "home", "work", "office", "central airport", "nearest gas station", "starbucks",
    "downtown", "city mall", "gym", "london", "uppal", "bangalore", "library",
    "railway station", "nearest hospital", "grocery store", "cinema hall", "indiranagar"
]

APPS = [
    "whatsapp", "spotify", "instagram", "youtube", "gmail", "chrome", "chrome browser",
    "maps", "google maps", "settings", "my settings", "camera", "clock", "reddit",
    "slack", "telegram", "twitter", "netflix", "amazon", "uber", "discord", "calculator"
]

MEDIA_QUERIES = [
    "sunflower", "taylor swift", "coldplay", "linkin park", "lo-fi beats", "lofi beats",
    "rock music", "how to bake a cake", "cats", "workout playlist", "jazz music",
    "space documentary", "relaxing piano", "ed sheeran", "eminem", "hans zimmer",
    "top hits", "coding music", "bohemian rhapsody", "arijit singh", "interstellar theme"
]

ALARM_TIMES = [
    "7 am", "6:30 am", "8:00 pm", "tomorrow morning at 8", "5 am", "6 pm",
    "9:45 pm", "noon", "midnight", "tomorrow at 7:30 am", "5.30 am"
]

TIMER_DURATIONS = [
    "10 minutes", "5 minutes", "30 seconds", "15 minutes", "1 hour", "45 mins",
    "20 minutes", "2 minutes", "half an hour", "90 seconds"
]

REMINDER_TIMES = [
    "tomorrow", "tomorrow morning", "tomorrow afternoon", "tomorrow night",
    "tomorrow at 7pm", "tomorrow at 8am", "tomorrow at 5 pm",
    "today at 12pm", "today at 6pm", "tonight at 8", "tonight at 9pm",
    "on friday at 9am", "on monday at 10am", "this saturday at 4pm",
    "in 10 minutes", "in 15 minutes", "in 30 minutes", "in 1 hour", "in 2 hours",
    "at 7pm", "at noon", "at 5:30 pm", "next monday morning"
]

REMINDER_TASKS = [
    "pay the bill", "buy groceries", "drink water", "call mom", "pick up dry cleaning",
    "submit assignment", "attend meeting", "take medicine", "go for a run",
    "water the plants", "recharge metro card", "turn off the stove", "feed the dog",
    "check the oven", "call dad", "pay electricity bill", "buy milk", "stretch"
]

SPORTS_TEAMS = [
    "india", "real madrid", "barcelona", "liverpool", "manchester city", "arsenal",
    "mumbai indians", "csk", "rcb", "australia", "england", "psg", "bayern munich",
    "chelsea", "indian cricket team"
]

CALENDAR_TITLES = [
    "team sync", "dentist appointment", "lunch with alex", "project review",
    "doctor visit", "gym session", "standup meeting", "flight check in"
]

ROUTINE_NAMES = [
    "goodnight", "morning", "workout", "work mode", "focus mode", "heading out",
    "sleep mode", "study session"
]

FACTS = [
    "my favorite color is amber", "i prefer dark mode", "i am allergic to peanuts",
    "my car number is 4021", "my office starts at 9 am", "i live in hyderabad",
    "my dog's name is max", "i drink coffee without sugar", "my birthday is in july",
    "i am a software engineer", "i like spicy food", "my wifi password is tiger99"
]

SEARCH_TOPICS = [
    "quantum computing", "latest tech news", "vegan pasta recipes", "weather in tokyo",
    "python documentation", "electric cars", "how to tie a tie", "flights to mumbai",
    "artificial intelligence trends", "best laptops", "history of rome", "mars rover",
    "stock market today", "how does photosynthesis work", "olympic schedule"
]

UNKNOWN_QUERIES = [
    "what is the capital of france", "tell me a funny joke", "tell a joke",
    "explain quantum computing simply", "how do i cook spaghetti carbonara",
    "write a python function to check prime numbers", "who wrote hamlet",
    "how far is the moon from earth", "what is the difference between ai and ml",
    "who is the president of the united states", "what is the speed of light",
    "can you write a poem about the ocean", "solve 25 multiplied by 4",
    "why is the sky blue", "how does an airplane fly", "what is the meaning of life",
    "give me 5 tips for better sleep", "how to learn rust programming",
    "what causes earthquakes", "who discovered gravity", "explain relativity",
    "what is the tallest mountain in the world", "whats the capital of india",
    "capital of japan", "why do we dream", "hello", "good morning", "thanks friday",
    "how are you doing today", "who created you", "what can you do for me",
    "convert 50 usd to inr", "what is 15 percent of 240", "define entropy"
]


def annotate(words: List[str], tag_prefix: str) -> List[Tuple[str, str]]:
    if not words:
        return []
    return [(w, f"B-{tag_prefix}" if i == 0 else f"I-{tag_prefix}") for i, w in enumerate(words)]


def make_sample(tagged: List[Tuple[str, str]], intent: str) -> Dict[str, Any]:
    return {
        "text": " ".join(w for w, _ in tagged),
        "intent": intent,
        "slots": [t for _, t in tagged],
    }


def lit(phrase: str) -> List[Tuple[str, str]]:
    return [(w, "O") for w in phrase.split()]


def build_intent_buckets(samples_per_round: int = 100) -> Dict[str, List[Dict[str, Any]]]:
    buckets: Dict[str, List[Dict[str, Any]]] = defaultdict(list)

    # Load curated manual dataset first
    manual_path = os.path.join(os.path.dirname(__file__), "manual_commands_dataset.json")
    if os.path.exists(manual_path):
        with open(manual_path, "r", encoding="utf-8") as f:
            for item in json.load(f):
                intent = item.get("intent", "unknown")
                if intent in INTENT_TO_ID and len(item["text"].split()) == len(item["slots"]):
                    for _ in range(4):
                        buckets[intent].append(item)

    for _ in range(samples_per_round):
        # volume_up / volume_down
        vp = f"{random.randint(10, 100)}%"
        vnum = f"{random.randint(20, 95)} percent"
        for s in [
            lit("increase the volume"), lit("increase volume"), lit("turn up the sound"),
            lit("turn up volume"), lit("boost the volume"), lit("make it louder please"),
            lit("crank up the sound"), lit("pump up volume"),
            lit("set volume to") + annotate([vp], "VALUE"),
            lit("volume up to") + annotate(vnum.split(), "VALUE"),
        ]:
            buckets["volume_up"].append(make_sample(s, "volume_up"))

        vp_low = f"{random.randint(5, 45)}%"
        for s in [
            lit("lower the volume"), lit("lower volume"), lit("turn down the sound"),
            lit("make it quieter"), lit("turn down the music"), lit("mute the audio"),
            lit("mute sound"), lit("silence media"), lit("reduce volume"),
            lit("volume down to") + annotate([vp_low], "VALUE"),
        ]:
            buckets["volume_down"].append(make_sample(s, "volume_down"))

        # brightness_up / brightness_down (contrasted explicitly against flashlight/torch)
        bp = f"{random.randint(55, 100)}%"
        for s in [
            lit("make the screen brighter"), lit("increase screen brightness"),
            lit("turn up the brightness"), lit("make display brighter"),
            lit("the screen is too dark"), lit("raise display brightness"),
            lit("set brightness to") + annotate(["maximum"], "VALUE"),
            lit("set screen brightness to") + annotate([bp], "VALUE"),
        ]:
            buckets["brightness_up"].append(make_sample(s, "brightness_up"))

        bp_low = f"{random.randint(10, 45)}%"
        for s in [
            lit("dim the screen"), lit("decrease screen brightness"), lit("lower brightness"),
            lit("the screen is too bright"), lit("the display is blinding me"),
            lit("make it dimmer"), lit("reduce display brightness"),
            lit("brightness") + annotate([bp_low], "VALUE"),
        ]:
            buckets["brightness_down"].append(make_sample(s, "brightness_down"))

        # torch_toggle & torch_strength (strong contrastive distinction vs screen brightness!)
        for s in [
            lit("turn on flashlight"), lit("turn on the torch"), lit("enable flashlight"),
            lit("turn off flashlight"), lit("turn off the torch"), lit("kill the torch"),
            lit("disable flashlight"), lit("it is dark in here"), lit("we need some light"),
            lit("flashlight on"), lit("flashlight off"), lit("toggle torch"),
        ]:
            buckets["torch_toggle"].append(make_sample(s, "torch_toggle"))

        lvl = f"level {random.randint(1, 5)}"
        pct = f"{random.randint(20, 100)}%"
        for s in [
            lit("set flashlight brightness to") + annotate(["maximum"], "VALUE"),
            lit("set flashlight brightness to") + annotate([pct], "VALUE"),
            lit("change torch strength to") + annotate(lvl.split(), "VALUE"),
            lit("set torch intensity to") + annotate([pct], "VALUE"),
            lit("torch") + annotate(lvl.split(), "VALUE"),
            lit("flashlight intensity") + annotate(["high"], "VALUE"),
            lit("max flashlight brightness"), lit("increase torch brightness"),
        ]:
            buckets["torch_strength"].append(make_sample(s, "torch_strength"))

        # lock_phone
        for s in [
            lit("lock my phone"), lit("lock the phone"), lit("lock the screen"),
            lit("put phone to sleep"), lit("turn off screen display"), lit("turn off the screen"),
            lit("sleep the screen"), lit("lock device"),
        ]:
            buckets["lock_phone"].append(make_sample(s, "lock_phone"))

        # open_app / open_camera / open_files
        app = random.choice(APPS)
        app_tag = annotate(app.split(), "APP")
        for s in [
            lit("open") + app_tag, lit("launch") + app_tag,
            lit("start") + app_tag + lit("app"), lit("go to") + app_tag,
            lit("open up") + app_tag, lit("switch to") + app_tag,
        ]:
            buckets["open_app"].append(make_sample(s, "open_app"))

        for s in [
            lit("open camera"), lit("launch camera"), lit("take a photo"),
            lit("snap a picture"), lit("open my camera"), lit("start camera app"),
        ]:
            buckets["open_camera"].append(make_sample(s, "open_camera"))

        for s in [
            lit("open file manager"), lit("open my files"), lit("show downloads folder"),
            lit("open downloads"), lit("browse my files"), lit("show my downloads"),
            lit("open documents folder"), lit("launch files app"),
        ]:
            buckets["open_files"].append(make_sample(s, "open_files"))

        # navigate_to
        dest = random.choice(DESTINATIONS)
        dest_tag = annotate(dest.split(), "DESTINATION")
        for s in [
            lit("navigate to") + dest_tag, lit("directions to") + dest_tag,
            lit("find me directions to") + dest_tag, lit("take me to") + dest_tag,
            lit("how do i get to") + dest_tag, lit("show routes to") + dest_tag,
        ]:
            buckets["navigate_to"].append(make_sample(s, "navigate_to"))

        # set_alarm / set_timer / set_reminder / reminder_list
        al = annotate(random.choice(ALARM_TIMES).split(), "TIME")
        for s in [
            lit("set alarm for") + al, lit("wake me up at") + al,
            lit("alarm for") + al, lit("set an alarm at") + al,
        ]:
            buckets["set_alarm"].append(make_sample(s, "set_alarm"))

        tm = annotate(random.choice(TIMER_DURATIONS).split(), "TIME")
        for s in [
            lit("set timer for") + tm, lit("countdown for") + tm,
            lit("start a") + tm + lit("timer"), lit("timer for") + tm,
        ]:
            buckets["set_timer"].append(make_sample(s, "set_timer"))

        rtask = annotate(random.choice(REMINDER_TASKS).split(), "NOTE_CONTENT")
        rtime = annotate(random.choice(REMINDER_TIMES).split(), "TIME")
        for s in [
            lit("remind me to") + rtask + rtime,
            lit("remind me") + rtime + lit("to") + rtask,
            lit("set a reminder to") + rtask + rtime,
            lit("set a reminder for") + rtime + lit("to") + rtask,
            lit("can you remind me to") + rtask + rtime,
        ]:
            buckets["set_reminder"].append(make_sample(s, "set_reminder"))

        for s in [
            lit("list my reminders"), lit("show my reminders"), lit("what reminders do i have"),
            lit("check upcoming reminders"), lit("any reminders today"),
            lit("what did i set reminders for"), lit("show pending reminders"),
        ]:
            buckets["reminder_list"].append(make_sample(s, "reminder_list"))

        # WhatsApp & Phone
        name = annotate(random.choice(NAMES).split(), "CONTACT")
        msg = annotate(random.choice(MESSAGES).split(), "MESSAGE")
        for s in [
            lit("send message to") + name + lit("on whatsapp saying") + msg,
            lit("text") + name + lit("on whatsapp saying") + msg,
            lit("whatsapp") + name + lit("saying") + msg,
            lit("send a whatsapp to") + name + lit("saying") + msg,
            lit("message") + name + msg,
        ]:
            buckets["send_whatsapp"].append(make_sample(s, "send_whatsapp"))

        for s in [
            lit("call") + name, lit("can you call") + name, lit("dial") + name,
            lit("give") + name + lit("a call"), lit("make a phone call to") + name,
        ]:
            buckets["call_contact"].append(make_sample(s, "call_contact"))

        for s in [
            lit("check recent calls"), lit("who called me recently"),
            lit("show call history"), lit("read call log"), lit("missed calls"),
        ]:
            buckets["read_call_log"].append(make_sample(s, "read_call_log"))

        # Media controls (rich examples for pause / next_track / previous_track!)
        mq = annotate(random.choice(MEDIA_QUERIES).split(), "QUERY")
        for s in [
            lit("play") + mq + lit("on youtube music"),
            lit("listen to") + mq + lit("on youtube music"),
            lit("play") + mq, lit("listen to") + mq, lit("put on") + mq,
        ]:
            buckets["play_media"].append(make_sample(s, "play_media"))

        for s in [
            lit("play") + mq + lit("on spotify"), lit("spotify play") + mq,
        ]:
            buckets["play_spotify"].append(make_sample(s, "play_spotify"))

        for s in [
            lit("search") + mq + lit("on youtube"), lit("play") + mq + lit("on youtube"),
            lit("watch") + mq + lit("on youtube"),
        ]:
            buckets["play_youtube"].append(make_sample(s, "play_youtube"))

        for s in [
            lit("pause music"), lit("pause playback"), lit("pause the music"),
            lit("stop the music"), lit("stop playback"), lit("pause song"),
        ]:
            buckets["pause_media"].append(make_sample(s, "pause_media"))

        for s in [
            lit("next song"), lit("next track"), lit("skip song"), lit("skip this track"),
            lit("skip to next track"), lit("play next song"), lit("skip forward"),
        ]:
            buckets["next_track"].append(make_sample(s, "next_track"))

        for s in [
            lit("previous song"), lit("previous track"), lit("go back one song"),
            lit("last song"), lit("play previous track"), lit("replay last song"),
        ]:
            buckets["previous_track"].append(make_sample(s, "previous_track"))

        # System Toggles (explicit short acronyms + full phrases!)
        for s in [lit("turn on wifi"), lit("turn off wifi"), lit("enable wi-fi"), lit("disable wifi"), lit("toggle wifi")]:
            buckets["wifi_toggle"].append(make_sample(s, "wifi_toggle"))

        for s in [lit("turn on bluetooth"), lit("turn off bluetooth"), lit("enable bluetooth"), lit("disable bluetooth")]:
            buckets["bluetooth_toggle"].append(make_sample(s, "bluetooth_toggle"))

        for s in [lit("turn on hotspot"), lit("turn off hotspot"), lit("enable wifi hotspot"), lit("disable mobile hotspot")]:
            buckets["hotspot_toggle"].append(make_sample(s, "hotspot_toggle"))

        for s in [
            lit("turn on dnd"), lit("turn off dnd"), lit("enable dnd"), lit("disable dnd"),
            lit("turn on do not disturb"), lit("turn off do not disturb"),
            lit("enable do not disturb mode"), lit("silence notifications mode"),
        ]:
            buckets["dnd_toggle"].append(make_sample(s, "dnd_toggle"))

        for s in [
            lit("turn on battery saver"), lit("turn off battery saver"),
            lit("enable power saver"), lit("disable low power mode"),
        ]:
            buckets["power_saver_toggle"].append(make_sample(s, "power_saver_toggle"))

        for s in [
            lit("start screen mirroring"), lit("stop screen mirroring"),
            lit("cast my screen"), lit("enable wireless display"),
        ]:
            buckets["screencast_toggle"].append(make_sample(s, "screencast_toggle"))

        for s in [
            lit("enable airplane mode"), lit("disable airplane mode"), lit("turn on airplane mode"),
            lit("turn off airplane mode"), lit("switch on flight mode"), lit("turn off flight mode"),
        ]:
            buckets["airplane_mode_toggle"].append(make_sample(s, "airplane_mode_toggle"))

        for s in [
            lit("turn on mobile data"), lit("turn off mobile data"), lit("enable mobile data"),
            lit("disable cellular data"), lit("turn on cellular data"), lit("switch off mobile internet"),
        ]:
            buckets["mobile_data_toggle"].append(make_sample(s, "mobile_data_toggle"))

        for s in [lit("take a screenshot"), lit("capture the screen"), lit("screenshot this"), lit("save screenshot")]:
            buckets["take_screenshot"].append(make_sample(s, "take_screenshot"))

        # Utilities
        for s in [lit("read my clipboard"), lit("what is on my clipboard"), lit("paste clipboard content")]:
            buckets["clipboard_read"].append(make_sample(s, "clipboard_read"))

        clip_txt = annotate(random.choice(["hello world", "call me at 5", "meeting link", "room 302"]).split(), "TEXT")
        for s in [lit("copy to clipboard") + clip_txt, lit("copy") + clip_txt + lit("to clipboard")]:
            buckets["clipboard_write"].append(make_sample(s, "clipboard_write"))

        for s in [lit("read notifications"), lit("check my notifications"), lit("any new notifications"), lit("read new alerts")]:
            buckets["read_notifications"].append(make_sample(s, "read_notifications"))

        for s in [lit("what is my battery level"), lit("check battery percentage"), lit("how much battery left"), lit("battery status")]:
            buckets["get_battery"].append(make_sample(s, "get_battery"))

        for s in [lit("what time is it"), lit("tell me the time"), lit("current time please"), lit("whats the time")]:
            buckets["get_time"].append(make_sample(s, "get_time"))

        # Notes
        ntext = annotate(random.choice([
            "buy milk and eggs", "meeting with client at 3pm", "call rohit",
            "metro balance is 100 rupees", "pay electricity bill", "bring passport",
            "gym locker code is 4021", "idea for project dashboard"
        ]).split(), "NOTE_CONTENT")
        for s in [
            lit("jot down") + ntext, lit("save a note that") + ntext,
            lit("note down") + ntext, lit("take a note") + ntext, lit("create a note") + ntext,
        ]:
            buckets["notes_create"].append(make_sample(s, "notes_create"))

        for s in [
            lit("list notes"), lit("show notes"), lit("list all notes"),
            lit("show all my notes"), lit("read my notes"), lit("what notes do i have"),
        ]:
            buckets["notes_list"].append(make_sample(s, "notes_list"))

        nq = annotate(random.choice(["rent", "passport", "groceries", "meeting", "locker code"]).split(), "QUERY")
        for s in [
            lit("find notes about") + nq, lit("search notes for") + nq,
            lit("what did i note down about") + nq,
        ]:
            buckets["notes_search"].append(make_sample(s, "notes_search"))

        nid = str(random.randint(1, 20))
        for s in [lit("delete note") + annotate([nid], "NOTE_ID"), lit("remove note number") + annotate([nid], "NOTE_ID")]:
            buckets["notes_delete"].append(make_sample(s, "notes_delete"))

        nup = annotate(random.choice(["buy milk", "meeting at 5 pm", "pick up groceries"]).split(), "NOTE_CONTENT")
        for s in [
            lit("update note") + annotate([nid], "NOTE_ID") + lit("to") + nup,
            lit("edit note") + annotate([nid], "NOTE_ID") + lit("to say") + nup,
        ]:
            buckets["notes_update"].append(make_sample(s, "notes_update"))

        # Web / Google / Reddit Search
        sq = annotate(random.choice(SEARCH_TOPICS).split(), "QUERY")
        for s in [
            lit("search the web for") + sq, lit("browse the web for") + sq, lit("find online") + sq,
        ]:
            buckets["web_search"].append(make_sample(s, "web_search"))

        for s in [
            lit("search") + sq, lit("google") + sq, lit("look up") + sq,
            lit("search on google for") + sq, lit("google search") + sq,
        ]:
            buckets["search_google"].append(make_sample(s, "search_google"))

        for s in [
            lit("search reddit for") + sq, lit("look up") + sq + lit("on reddit"), lit("find") + sq + lit("on reddit"),
        ]:
            buckets["search_reddit"].append(make_sample(s, "search_reddit"))

        # Preferences
        fact = annotate(random.choice(FACTS).split(), "FACT")
        for s in [
            lit("remember that") + fact, lit("store the fact that") + fact,
            lit("save my preference") + fact,
        ]:
            buckets["remember_preference"].append(make_sample(s, "remember_preference"))

        attr = annotate(random.choice([
            "car number", "wifi password", "favorite color", "home address",
            "office address", "dog's name", "birthday", "metro balance"
        ]).split(), "QUERY")
        for s in [
            lit("recall what you know about me"), lit("what do you remember about me"),
            lit("who am i"), lit("tell me about myself"), lit("what are my saved preferences"),
            lit("what is my") + attr, lit("tell me my") + attr,
        ]:
            buckets["recall_preference"].append(make_sample(s, "recall_preference"))

        # New Intent 48: sports_score
        team = annotate(random.choice(SPORTS_TEAMS).split(), "QUERY")
        for s in [
            lit("whats the cricket score"), lit("what is the india match score"),
            lit("cricket live score"), lit("india vs pakistan score"),
            lit("whats the") + team + lit("score"),
            lit("did") + team + lit("win today"),
            lit("check") + team + lit("match result"),
            lit("real madrid match score"), lit("any live football scores"),
        ]:
            buckets["sports_score"].append(make_sample(s, "sports_score"))

        # New Intent 49: sports_follow
        for s in [
            lit("follow") + team, lit("remember i follow") + team,
            lit("track") + team + lit("scores"), lit("add") + team + lit("to my followed teams"),
            lit("unfollow") + team, lit("stop following") + team,
        ]:
            buckets["sports_follow"].append(make_sample(s, "sports_follow"))

        # New Intent 50: calendar_list
        for s in [
            lit("whats on my calendar"), lit("show todays events"), lit("list calendar events"),
            lit("do i have any meetings today"), lit("whats my schedule today"),
            lit("upcoming events on calendar"), lit("am i free this afternoon"),
        ]:
            buckets["calendar_list"].append(make_sample(s, "calendar_list"))

        # New Intent 51: calendar_add
        ctitle = annotate(random.choice(CALENDAR_TITLES).split(), "NOTE_CONTENT")
        ctime = annotate(random.choice(REMINDER_TIMES).split(), "TIME")
        for s in [
            lit("add") + ctitle + lit("to my calendar") + ctime,
            lit("schedule calendar event") + ctitle + ctime,
            lit("create meeting") + ctitle + ctime,
            lit("put") + ctitle + lit("on calendar for") + ctime,
        ]:
            buckets["calendar_add"].append(make_sample(s, "calendar_add"))

        # New Intent 52: routine_create
        rname = annotate(random.choice(ROUTINE_NAMES).split(), "QUERY")
        for s in [
            lit("create a routine called") + rname,
            lit("make a voice routine") + rname,
            lit("when i say") + rname + lit("turn off wifi and lock phone"),
            lit("save routine") + rname + lit("to turn on dnd"),
        ]:
            buckets["routine_create"].append(make_sample(s, "routine_create"))

        # New Intent 53: routine_run
        for s in [
            lit("run routine") + rname, lit("activate") + rname + lit("routine"),
            lit("trigger my") + rname + lit("routine"), lit("start") + rname + lit("macro"),
        ]:
            buckets["routine_run"].append(make_sample(s, "routine_run"))

        # New Intent 54: digest_summary
        for s in [
            lit("whats my daily briefing"), lit("give me my morning digest"),
            lit("catch me up for today"), lit("summarize today reminders and news"),
            lit("brief me on today"), lit("evening summary please"),
        ]:
            buckets["digest_summary"].append(make_sample(s, "digest_summary"))

        # Unknown / Conversational
        unk = random.choice(UNKNOWN_QUERIES)
        buckets["unknown"].append(make_sample(lit(unk), "unknown"))

    return buckets


def augment_asr_noise(item: Dict[str, Any]) -> Dict[str, Any]:
    """Simulate real Whisper ASR formatting (dropped apostrophes/punctuation, fillers)."""
    words = item["text"].split()
    slots = list(item["slots"])
    cleaned_words = []
    for w in words:
        w2 = w.lower().replace("'", "")
        if random.random() < 0.08:
            w2 = w2.replace("-", " ")
        cleaned_words.append(w2)
    # Optional polite prefix with 'O' tag
    if random.random() < 0.14:
        prefix = random.choice(["please", "hey", "can you", "friday"])
        p_words = prefix.split()
        cleaned_words = p_words + cleaned_words
        slots = ["O"] * len(p_words) + slots
    # Re-normalize whitespace tokens cleanly
    final_words, final_slots = [], []
    for w, s in zip(cleaned_words, slots):
        parts = w.split()
        for idx, pw in enumerate(parts):
            final_words.append(pw)
            if idx == 0:
                final_slots.append(s)
            elif s.startswith("B-"):
                final_slots.append("I-" + s[2:])
            else:
                final_slots.append(s)
    return {"text": " ".join(final_words), "intent": item["intent"], "slots": final_slots}


def prepare_stratified_splits(target_per_intent: int = 260, val_ratio: float = 0.12):
    buckets = build_intent_buckets(samples_per_round=100)
    train_data, val_data = [], []

    for intent in INTENT_LABELS:
        pool = buckets[intent]
        assert len(pool) > 0, f"Empty dataset bucket for intent {intent}"
        # Deduplicate raw strings first before balancing
        seen = {}
        for ex in pool:
            seen[ex["text"]] = ex
        unique_pool = list(seen.values())
        random.shuffle(unique_pool)

        n_val = max(6, int(round(len(unique_pool) * val_ratio)))
        n_val = min(n_val, max(2, len(unique_pool) // 3))
        val_samples = unique_pool[:n_val]
        train_pool = unique_pool[n_val:] if len(unique_pool) > n_val else list(unique_pool)

        balanced_train = []
        while len(balanced_train) < target_per_intent:
            ex = random.choice(train_pool)
            if random.random() < 0.45:
                ex = augment_asr_noise(ex)
            balanced_train.append(ex)

        val_data.extend(val_samples)
        train_data.extend(balanced_train[:target_per_intent])

    random.shuffle(train_data)
    random.shuffle(val_data)
    return train_data, val_data


# ==============================================================================
# 3. DATASET & MODEL ARCHITECTURE (12-Layer BAAI/bge-small-en-v1.5)
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
            i_slot = ("I-" + slot[2:]) if slot.startswith("B-") else slot
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
            "slot_labels": torch.tensor(slot_ids, dtype=torch.long),
        }


class JointTransformerNluV2(nn.Module):
    def __init__(self, encoder, num_intents: int, num_slots: int):
        super().__init__()
        self.encoder = encoder
        hidden = encoder.config.hidden_size
        self.dropout = nn.Dropout(0.15)
        # Stronger normalized projection head for 56-intent separation
        self.intent_head = nn.Sequential(
            nn.Linear(hidden, hidden),
            nn.GELU(),
            nn.Dropout(0.10),
            nn.Linear(hidden, num_intents),
        )
        self.slot_head = nn.Linear(hidden, num_slots)

    def forward(self, input_ids, attention_mask):
        outputs = self.encoder(input_ids=input_ids, attention_mask=attention_mask)
        seq_out = outputs.last_hidden_state
        cls_out = seq_out[:, 0, :]
        intent_logits = self.intent_head(self.dropout(cls_out))
        slot_logits = self.slot_head(self.dropout(seq_out))
        return intent_logits, slot_logits


def evaluate(model, loader, device):
    model.eval()
    all_intent_preds, all_intent_true = [], []
    all_slot_preds, all_slot_true = [], []
    with torch.no_grad():
        for batch in loader:
            input_ids = batch["input_ids"].to(device)
            attention_mask = batch["attention_mask"].to(device)
            i_logits, s_logits = model(input_ids, attention_mask)

            preds = i_logits.argmax(dim=-1).cpu().tolist()
            trues = batch["intent_label"].tolist()
            all_intent_preds.extend(preds)
            all_intent_true.extend(trues)

            s_pred = s_logits.argmax(dim=-1).cpu().numpy()
            s_true = batch["slot_labels"].numpy()
            mask = s_true != -100
            all_slot_preds.extend(s_pred[mask].tolist())
            all_slot_true.extend(s_true[mask].tolist())

    intent_acc = sum(p == t for p, t in zip(all_intent_preds, all_intent_true)) / max(1, len(all_intent_true))
    intent_f1 = f1_score(all_intent_true, all_intent_preds, average="macro", zero_division=0)
    slot_f1 = f1_score(all_slot_true, all_slot_preds, average="micro", zero_division=0)
    return intent_acc, intent_f1, slot_f1


def train_and_export():
    t0 = time.time()
    print("=" * 72)
    print(" Friday Joint NLU v2 (12-Layer BAAI/bge-small-en-v1.5, 56 Intents)")
    print("=" * 72)

    device = torch.device("cuda" if torch.cuda.is_available() else "cpu")
    print(f"Compute device: {device}" + (f" ({torch.cuda.get_device_name(0)})" if torch.cuda.is_available() else ""))

    model_name = "BAAI/bge-small-en-v1.5"
    tokenizer = AutoTokenizer.from_pretrained(model_name)
    encoder = AutoModel.from_pretrained(model_name, attn_implementation="eager", torch_dtype=torch.float32)

    train_data, val_data = prepare_stratified_splits(target_per_intent=260, val_ratio=0.14)
    print(f"Dataset split -> train: {len(train_data)} | held-out val: {len(val_data)} across {len(INTENT_LABELS)} intents.")

    train_ds = JointDataset(train_data, tokenizer, max_len=48)
    val_ds = JointDataset(val_data, tokenizer, max_len=48)
    train_loader = DataLoader(train_ds, batch_size=64, shuffle=True, pin_memory=torch.cuda.is_available())
    val_loader = DataLoader(val_ds, batch_size=64, shuffle=False)

    model = JointTransformerNluV2(encoder, len(INTENT_LABELS), len(SLOT_LABELS)).to(device)
    intent_loss_fn = nn.CrossEntropyLoss(label_smoothing=0.04)
    slot_loss_fn = nn.CrossEntropyLoss(ignore_index=-100)

    epochs = 6
    optimizer = torch.optim.AdamW(model.parameters(), lr=4e-5, weight_decay=0.015)
    total_steps = len(train_loader) * epochs
    scheduler = get_cosine_schedule_with_warmup(
        optimizer, num_warmup_steps=int(total_steps * 0.08), num_training_steps=total_steps
    )

    use_bf16 = torch.cuda.is_available() and torch.cuda.is_bf16_supported()
    best_score = -1.0
    best_state = None

    for epoch in range(1, epochs + 1):
        model.train()
        total_loss = 0.0
        for batch in train_loader:
            input_ids = batch["input_ids"].to(device, non_blocking=True)
            attention_mask = batch["attention_mask"].to(device, non_blocking=True)
            intent_labels = batch["intent_label"].to(device, non_blocking=True)
            slot_labels = batch["slot_labels"].to(device, non_blocking=True)

            optimizer.zero_grad(set_to_none=True)
            with torch.amp.autocast("cuda", dtype=torch.bfloat16, enabled=use_bf16):
                intent_logits, slot_logits = model(input_ids, attention_mask)
                i_loss = intent_loss_fn(intent_logits, intent_labels)
                s_loss = slot_loss_fn(slot_logits.view(-1, len(SLOT_LABELS)), slot_labels.view(-1))
                loss = i_loss + 1.4 * s_loss

            loss.backward()
            torch.nn.utils.clip_grad_norm_(model.parameters(), 1.0)
            optimizer.step()
            scheduler.step()
            total_loss += loss.item()

        val_acc, val_macro_f1, val_slot_f1 = evaluate(model, val_loader, device)
        score = 0.7 * val_acc + 0.3 * val_slot_f1
        print(
            f"Epoch [{epoch}/{epochs}] loss={total_loss/len(train_loader):.4f} "
            f"val_acc={val_acc*100:.2f}% val_macro_f1={val_macro_f1:.4f} val_slot_f1={val_slot_f1:.4f}"
        )
        if score > best_score:
            best_score = score
            best_state = {k: v.detach().cpu().clone() for k, v in model.state_dict().items()}

    assert best_state is not None
    model.load_state_dict(best_state)
    model.to("cpu")
    model.eval()

    root_dir = os.path.abspath(os.path.join(os.path.dirname(__file__), ".."))
    output_dir = os.path.join(root_dir, "output")
    assets_dir = os.path.join(root_dir, "app", "src", "main", "assets")
    os.makedirs(output_dir, exist_ok=True)
    os.makedirs(assets_dir, exist_ok=True)

    # Save label & vocab artifacts to BOTH output/ and assets/
    for target_dir in (output_dir, assets_dir):
        with open(os.path.join(target_dir, "joint_intent_labels.json"), "w", encoding="utf-8") as f:
            json.dump(INTENT_LABELS, f, indent=2)
        with open(os.path.join(target_dir, "joint_slot_labels.json"), "w", encoding="utf-8") as f:
            json.dump(SLOT_LABELS, f, indent=2)
        with open(os.path.join(target_dir, "labels.txt"), "w", encoding="utf-8") as f:
            f.write("\n".join(INTENT_LABELS) + "\n")
        with open(os.path.join(target_dir, "slot_labels.txt"), "w", encoding="utf-8") as f:
            f.write("\n".join(SLOT_LABELS) + "\n")
        ordered_vocab = [tok for tok, _ in sorted(tokenizer.get_vocab().items(), key=lambda kv: kv[1])]
        with open(os.path.join(target_dir, "vocab.txt"), "w", encoding="utf-8") as f:
            f.write("\n".join(ordered_vocab) + "\n")

    fp32_path = os.path.join(output_dir, "joint_nlu_fp32.onnx")
    int8_out_path = os.path.join(output_dir, "joint_nlu_model.onnx")
    int8_assets_path = os.path.join(assets_dir, "joint_nlu_model.onnx")

    dummy_ids = torch.ones(1, 24, dtype=torch.long)
    dummy_mask = torch.ones(1, 24, dtype=torch.long)
    print("\nExporting ONNX (TorchScript)...")
    torch.onnx.export(
        model,
        (dummy_ids, dummy_mask),
        fp32_path,
        export_params=True,
        input_names=["input_ids", "attention_mask"],
        output_names=["intent_logits", "slot_logits"],
        dynamic_axes={
            "input_ids": {0: "batch_size", 1: "sequence_length"},
            "attention_mask": {0: "batch_size", 1: "sequence_length"},
            "intent_logits": {0: "batch_size"},
            "slot_logits": {0: "batch_size", 1: "sequence_length"},
        },
        opset_version=14,
        do_constant_folding=True,
        dynamo=False,
    )

    print("Validating FP32 ONNX output parity before quantization...")
    import onnxruntime as ort
    fp32_sess = ort.InferenceSession(fp32_path, providers=["CPUExecutionProvider"])
    probe_pairs = [
        ("increase the volume", "volume_up"),
        ("turn off dnd", "dnd_toggle"),
        ("whats the cricket score", "sports_score"),
        ("lock my phone", "lock_phone"),
    ]
    for pt, exp in probe_pairs:
        enc = tokenizer(pt, return_tensors="np")
        with torch.no_grad():
            ti, _ = model(torch.tensor(enc["input_ids"]), torch.tensor(enc["attention_mask"]))
        oi, _ = fp32_sess.run(None, {"input_ids": enc["input_ids"].astype(np.int64), "attention_mask": enc["attention_mask"].astype(np.int64)})
        p_pt = INTENT_LABELS[int(ti[0].argmax())]
        p_ox = INTENT_LABELS[int(oi[0].argmax())]
        max_diff = float(np.max(np.abs(ti.numpy() - oi)))
        print(f"  Parity '{pt}' (exp={exp}): torch={p_pt}, fp32_onnx={p_ox}, max_logit_diff={max_diff:.5f}")

    print("Quantizing encoder linear layers to INT8 ONNX (MatMul/Gemm)...")
    head_nodes = [
        "/intent_head/intent_head.0/MatMul",
        "/intent_head/intent_head.3/MatMul",
        "/slot_head/MatMul",
    ]
    quantize_dynamic(
        model_input=fp32_path,
        model_output=int8_out_path,
        op_types_to_quantize=["MatMul", "Gemm"],
        nodes_to_exclude=head_nodes,
        weight_type=QuantType.QInt8,
        extra_options={"EnableShapeInference": False},
    )
    int8_sess = ort.InferenceSession(int8_out_path, providers=["CPUExecutionProvider"])
    ok_probe = 0
    for pt, exp in probe_pairs:
        enc = tokenizer(pt, return_tensors="np")
        oi, _ = int8_sess.run(None, {"input_ids": enc["input_ids"].astype(np.int64), "attention_mask": enc["attention_mask"].astype(np.int64)})
        p_int8 = INTENT_LABELS[int(oi[0].argmax())]
        print(f"  INT8(QInt8) '{pt}' -> pred={p_int8} (exp={exp})")
        if p_int8 == exp:
            ok_probe += 1

    if ok_probe < 3:
        print("QInt8 degraded accuracy; trying QUInt8 on MatMul...")
        quantize_dynamic(
            model_input=fp32_path,
            model_output=int8_out_path,
            op_types_to_quantize=["MatMul"],
            nodes_to_exclude=head_nodes,
            weight_type=QuantType.QUInt8,
            extra_options={"EnableShapeInference": False},
        )
        uint8_sess = ort.InferenceSession(int8_out_path, providers=["CPUExecutionProvider"])
        ok_u8 = 0
        for pt, exp in probe_pairs:
            enc = tokenizer(pt, return_tensors="np")
            oi, _ = uint8_sess.run(None, {"input_ids": enc["input_ids"].astype(np.int64), "attention_mask": enc["attention_mask"].astype(np.int64)})
            p_u8 = INTENT_LABELS[int(oi[0].argmax())]
            print(f"  INT8(QUInt8) '{pt}' -> pred={p_u8} (exp={exp})")
            if p_u8 == exp:
                ok_u8 += 1
        if ok_u8 < 3:
            print("Warning: fallback to ONNX FP16/FP32 weight quantization failed probe, inspect graph.")

    if os.path.exists(fp32_path):
        os.remove(fp32_path)

    with open(int8_out_path, "rb") as src, open(int8_assets_path, "wb") as dst:
        dst.write(src.read())

    size_mb = os.path.getsize(int8_assets_path) / (1024 * 1024)
    print(f"\nExported INT8 NLU v2 -> {int8_assets_path} ({size_mb:.2f} MB) in {time.time() - t0:.1f}s")


if __name__ == "__main__":
    train_and_export()

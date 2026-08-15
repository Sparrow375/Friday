#!/usr/bin/env python3
"""
Targeted Verification Suite for User Interactive Edge Cases
"""

import os
import sys
import json
sys.path.insert(0, os.path.dirname(__file__))
from test_joint_nlu_pc import JointNluRunner, JointAgentDispatcher, MODEL_PATH, INTENT_LABELS_PATH, SLOT_LABELS_PATH

TEST_QUERIES = [
    ("can you text kanak im coming home in 30 minutes", "send_whatsapp", "whatsapp_send"),
    ("text sarah on whatsapp that i am coming home", "send_whatsapp", "whatsapp_send"),
    ("capital of mumbai", "unknown", "FALLBACK_LLM_BRAIN"),
    ("whats the capital of india", "unknown", "FALLBACK_LLM_BRAIN"),
    ("whats the capital of maharashtra", "unknown", "FALLBACK_LLM_BRAIN"),
    ("maharashtra directions", "navigate_to", "location_control"),
    ("tell me about myself", "recall_preference", "recall"),
    ("note my metro card balance is 100 rupees", "notes_create", "notes_control"),
    ("what is my metro card balance", "recall_preference", "recall"),
    ("tell my notes", "notes_list", "notes_control"),
    ("open stotify", "open_app", "app_launcher"),
    ("remind me to drink water in 10 minutes", "notes_create", "notes_control"),
    ("tell a joke", "unknown", "FALLBACK_LLM_BRAIN"),
    ("wd", "unknown", "FALLBACK_LLM_BRAIN"),
]

def run_user_suite():
    print("\n" + "=" * 70)
    print("  RUNNING TARGETED USER INTERACTIVE EDGE CASE SUITE")
    print("=" * 70)

    runner = JointNluRunner(MODEL_PATH, INTENT_LABELS_PATH, SLOT_LABELS_PATH)
    dispatcher = JointAgentDispatcher(runner)

    passed = 0
    total = len(TEST_QUERIES)

    # First, preload some notes/facts into dispatcher's memory
    dispatcher.memory.save_note("my metro card balance is 100 rupees")
    dispatcher.memory.remember_fact("my car number is 4021")

    for query, exp_intent, exp_tool in TEST_QUERIES:
        res = dispatcher.dispatch(query)
        pred = res["prediction"]
        disp = res["dispatch"]
        intent = pred["intent"]
        conf = pred["confidence"]
        tool = disp["tool"]
        slots = pred["slots"]
        exec_out = disp.get("execution_result", "")

        intent_ok = (exp_intent is None) or (intent == exp_intent)
        tool_ok = (tool == exp_tool)

        status = "✅ PASS" if tool_ok else "❌ FAIL"
        if tool_ok:
            passed += 1

        print(f"\nQuery: \"{query}\"")
        print(f"  -> Intent: {intent} ({conf*100:.1f}%) | Expected: {exp_intent}")
        print(f"  -> Tool:   {tool} | Expected: {exp_tool} | {status}")
        print(f"  -> Slots:  {json.dumps(slots)}")
        if exec_out:
            print(f"  -> Result: {exec_out}")

    print("\n" + "=" * 70)
    print(f"  Targeted Suite Score: {passed}/{total} ({passed/total*100:.1f}%)")
    print("=" * 70 + "\n")

if __name__ == "__main__":
    run_user_suite()

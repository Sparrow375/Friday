import json
import os
import sys

sys.path.insert(0, os.path.abspath(os.path.join(os.path.dirname(__file__), "..")))
from scripts.test_nlu_pc import NluModelRunner, AgentRouter, MODEL_PATH, VOCAB_PATH, LABELS_PATH

def generate():
    runner = NluModelRunner(MODEL_PATH, VOCAB_PATH, LABELS_PATH)
    router = AgentRouter(runner)

    dataset_path = os.path.join(os.path.dirname(__file__), "stress_test_dataset.json")
    with open(dataset_path, "r", encoding="utf-8") as f:
        cases = json.load(f)

    cat_stats = {}
    failures = []

    for idx, c in enumerate(cases, 1):
        cat = c["category"]
        if cat not in cat_stats:
            cat_stats[cat] = {"total": 0, "intent_ok": 0, "tool_ok": 0}
        cat_stats[cat]["total"] += 1

        res = router.route(c["query"])
        val_intent = res["validation"]["validated_intent"]
        conf = res["validation"]["validated_confidence"]
        tool = res["dispatch"]["tool"]

        i_ok = (c["expected_intent"] is None) or (val_intent == c["expected_intent"])
        t_ok = (c["expected_tool"] is None) or (tool == c["expected_tool"])

        if i_ok:
            cat_stats[cat]["intent_ok"] += 1
        if t_ok:
            cat_stats[cat]["tool_ok"] += 1
        else:
            failures.append({
                "idx": idx,
                "category": cat,
                "query": c["query"],
                "expected_tool": c["expected_tool"],
                "actual_tool": tool,
                "expected_intent": c["expected_intent"],
                "actual_intent": val_intent,
                "conf": conf,
                "reason": res["dispatch"]["reason"],
                "args": res["dispatch"]["arguments"]
            })

    print("\n# CATEGORY-WISE PERFORMANCE BREAKDOWN\n")
    print(f"| {'Category':<20} | {'Total':>5} | {'Intent Accuracy':>15} | {'Routing Accuracy':>17} |")
    print(f"|{'-'*22}|{'-'*7}|{'-'*17}|{'-'*19}|")
    tot_cases = len(cases)
    tot_i = 0
    tot_t = 0
    for cat, s in cat_stats.items():
        tot_i += s["intent_ok"]
        tot_t += s["tool_ok"]
        i_pct = s["intent_ok"] / s["total"] * 100
        t_pct = s["tool_ok"] / s["total"] * 100
        print(f"| {cat:<20} | {s['total']:>5} | {i_pct:>14.1f}% | {t_pct:>16.1f}% |")

    overall_i = tot_i / tot_cases * 100
    overall_t = tot_t / tot_cases * 100
    print(f"| {'**OVERALL TOTAL**':<20} | {tot_cases:>5} | {overall_i:>14.1f}% | {overall_t:>16.1f}% |")

    print(f"\nTotal Failures: {len(failures)} / {tot_cases}")
    
    # Categorize failures by Root Cause Type
    root_causes = {
        "Phone Call Hijack (re-call / contact regex)": [],
        "Entity Over-Matching / Keyword Stripping": [],
        "Order-of-Operations / Priority Collision": [],
        "Low NLU Confidence (<60%) on Messaging": [],
        "Out-of-Domain / Hallucination": [],
        "Missing Tool Handler": []
    }

    for f in failures:
        r = f["reason"]
        q = f["query"]
        if "Phone call triggered" in r:
            root_causes["Phone Call Hijack (re-call / contact regex)"].append(f)
        elif f["actual_tool"] == "FALLBACK_LLM_BRAIN" and f["conf"] < 0.60:
            if f["category"] == "LLM Conversational":
                root_causes["Out-of-Domain / Hallucination"].append(f)
            else:
                root_causes["Low NLU Confidence (<60%) on Messaging"].append(f)
        elif f["actual_tool"] == "app_launcher" and ("start " in q or "timer" in q or "mirroring" in q):
            root_causes["Order-of-Operations / Priority Collision"].append(f)
        elif f["actual_tool"] == "system_status" and "battery" in q:
            root_causes["Order-of-Operations / Priority Collision"].append(f)
        elif f["actual_tool"] == "system_control" and "wifi" in q:
            root_causes["Order-of-Operations / Priority Collision"].append(f)
        else:
            root_causes["Out-of-Domain / Hallucination"].append(f)

    print("\n# ROOT CAUSE ANALYSIS OF FAILURES\n")
    for rtype, flist in root_causes.items():
        if flist:
            print(f"\n### {rtype} ({len(flist)} cases)")
            for item in flist:
                print(f"- **\"{item['query']}\"** (Expected: `{item['expected_tool']}`, Got: `{item['actual_tool']}`) -> {item['reason']}")

if __name__ == "__main__":
    generate()

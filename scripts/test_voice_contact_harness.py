#!/usr/bin/env python3
"""
Interactive & Automated Local PC Test Harness for Friday Voice Recognition & Contact Matching.

Simulates:
1. Android SpeechRecognizer multi-hypothesis candidate generation.
2. ContactHelper phonetic correction and candidate scoring.
3. Strict Levenshtein, consonant skeleton, and ambiguity detection algorithms.
4. Call safety guards against emergency numbers and accidental calls (e.g. 'owe friend' -> 101).
"""

import sys
import re
from typing import List, Dict, Tuple, Optional

# Replicates KNOWN_ASR_NAME_MAP from ContactHelper.kt
KNOWN_ASR_NAME_MAP: Dict[str, str] = {
    "connecting": "kanak",
    "connect": "kanak",
    "conic": "kanak",
    "raw hit": "rohit",
    "row hit": "rohit",
    "prayer": "priya",
    "poo ja": "pooja",
    "shub ham": "shubham",
    "are ya": "arya",
    "deep pack": "deepak",
    "so raj": "suraj",
    "are on": "aaron",
    "are man": "armaan",
    "shrujani": "srujani",
    "surjani": "srujani",
    "srujan": "srujani",
    "srijani": "srujani",
    "sreejani": "srujani",
    "shreejani": "srujani",
    "roojani": "srujani",
    "rojani": "srujani",
}

# Default simulated contact book on device
DEFAULT_CONTACTS = [
    {"name": "Srujani", "normalized": "srujani", "number": "+919876543210"},
    {"name": "Ragini", "normalized": "ragini", "number": "+919812345678"},
    {"name": "Kanak", "normalized": "kanak", "number": "+919922334455"},
    {"name": "Rohit", "normalized": "rohit", "number": "+919833445566"},
    {"name": "Priya", "normalized": "priya", "number": "+919844556677"},
    {"name": "Fire", "normalized": "fire", "number": "101"},
    {"name": "Police", "normalized": "police", "number": "100"},
    {"name": "Ambulance", "normalized": "ambulance", "number": "102"},
    {"name": "Emergency", "normalized": "emergency", "number": "112"},
    {"name": "Mom", "normalized": "mom", "number": "+919800112233"},
    {"name": "Dad", "normalized": "dad", "number": "+919800223344"},
]

EMERGENCY_NUMBERS = {"100", "101", "102", "108", "112", "911", "999", "119", "000"}


def get_consonants(s: str) -> str:
    """Treats 'c' as 'k' for Indian phonetic alignment and strips vowels/spaces."""
    s = s.lower().replace('c', 'k')
    return "".join(ch for ch in s if ch not in "aeiou ")


def levenshtein_distance(s1: str, s2: str) -> int:
    dp = [[0] * (len(s2) + 1) for _ in range(len(s1) + 1)]
    for i in range(len(s1) + 1):
        dp[i][0] = i
    for j in range(len(s2) + 1):
        dp[0][j] = j
    for i in range(1, len(s1) + 1):
        for j in range(1, len(s2) + 1):
            cost = 0 if s1[i - 1] == s2[j - 1] else 1
            dp[i][j] = min(dp[i - 1][j] + 1, dp[i][j - 1] + 1, dp[i - 1][j - 1] + cost)
    return dp[len(s1)][len(s2)]


def find_best_matching_contact(candidate: str, contacts: List[Dict[str, str]]) -> Tuple[Optional[str], str]:
    """
    Simulates ContactHelper.findBestMatchingContact() in Kotlin.
    Returns (matched_name, reasoning).
    """
    trimmed = candidate.strip().lower()
    if not trimmed:
        return None, "Empty candidate"

    # 1. Exact match
    for c in contacts:
        if c["normalized"] == trimmed:
            return c["name"], f"Exact match on '{c['name']}'"

    # 2. Known phonetic map
    if trimmed in KNOWN_ASR_NAME_MAP:
        mapped = KNOWN_ASR_NAME_MAP[trimmed]
        for c in contacts:
            if mapped in c["normalized"]:
                return c["name"], f"Phonetic map '{trimmed}' -> '{mapped}' -> '{c['name']}'"

    # 3. First name exact match or starts-with (>= 3 chars)
    for c in contacts:
        first_name = c["normalized"].split()[0]
        if first_name == trimmed:
            return c["name"], f"First name exact match on '{c['name']}'"
    if len(trimmed) >= 3:
        for c in contacts:
            if c["normalized"].startswith(trimmed):
                return c["name"], f"Starts-with match '{trimmed}' on '{c['name']}'"

    # 4. Word-level contains (>= 4 chars)
    if len(trimmed) >= 4:
        for c in contacts:
            if trimmed in c["normalized"]:
                return c["name"], f"Contains match '{trimmed}' in '{c['name']}'"

    # 5. Strict Fuzzy Levenshtein match
    best_match = None
    min_dist = float("inf")
    second_min_dist = float("inf")
    max_allowed = 1 if len(trimmed) <= 6 else 2

    for c in contacts:
        first_name = c["normalized"].split()[0]
        dist = min(
            levenshtein_distance(trimmed, first_name),
            levenshtein_distance(trimmed, c["normalized"])
        )
        if dist < min_dist:
            second_min_dist = min_dist
            min_dist = dist
            best_match = c["name"]
        elif dist < second_min_dist:
            second_min_dist = dist

    # Ambiguity check
    if 1 <= min_dist <= max_allowed:
        if min_dist == second_min_dist:
            return None, f"Ambiguity rejection: multiple contacts at distance {min_dist}"
        return best_match, f"Fuzzy Levenshtein match (distance {min_dist} <= {max_allowed})"

    # 6. Strict Consonant skeleton match (>= 4 consonants, exact match only, NO startsWith)
    candidate_consonants = get_consonants(trimmed)
    if len(candidate_consonants) >= 4:
        for c in contacts:
            c_consonants = get_consonants(c["normalized"].split()[0])
            if len(c_consonants) >= 4 and c_consonants == candidate_consonants:
                return c["name"], f"Consonant skeleton exact match: '{candidate_consonants}'"

    return None, f"No match found (min distance was {min_dist}, consonants '{candidate_consonants}')"


def correct_transcript(raw_text: str, contacts: List[Dict[str, str]]) -> str:
    """Simulates ContactHelper.correctTranscript()."""
    text = raw_text.strip()
    general_replacements = [
        (r"\bsoch\b", "search"),
        (r"\bsurch\b", "search"),
        (r"\bsharch\b", "search"),
        (r"\bbalanc\b", "balance"),
        (r"\bbanalcne\b", "balance"),
        (r"\bwhatapp\b", "whatsapp"),
        (r"\bwatsapp\b", "whatsapp"),
    ]
    for pattern, rep in general_replacements:
        text = re.sub(pattern, rep, text, flags=re.IGNORECASE)

    contact_patterns = [
        r"(?i)(?:send a message to|send message to|message|text|whatsapp)\s+([a-zA-Z0-9]+)\s+(?:saying|that|with|message)\b",
        r"(?i)(?:send a message to|send message to|message|text|whatsapp)\s+([a-zA-Z0-9]+)$",
        r"(?i)(?:call|dial|ring|phone)\s+([a-zA-Z0-9]+)\b"
    ]
    for pat in contact_patterns:
        m = re.search(pat, text)
        if m:
            candidate_name = m.group(1)
            matched, _ = find_best_matching_contact(candidate_name, contacts)
            if matched and matched.lower() != candidate_name.lower():
                start, end = m.span(1)
                text = text[:start] + matched + text[end:]
                break
    return text


def score_hypotheses(candidates: List[str], contacts: List[Dict[str, str]]) -> Tuple[str, List[Dict]]:
    """
    Simulates SpeechToTextHelper.onResults() multi-hypothesis scoring.
    """
    best_candidate = candidates[0] if candidates else ""
    best_score = -1
    diagnostics = []

    for index, candidate in enumerate(candidates):
        corrected = correct_transcript(candidate, contacts)
        cand_lower = candidate.lower()
        corr_lower = corrected.lower()

        score = 100 - (index * 5)
        reasons = [f"Base ASR rank score: {score}"]

        # Check exact contact match
        has_exact = any(
            c["normalized"] in cand_lower or f"to {c['normalized']}" in cand_lower or c["normalized"] in cand_lower.split()
            for c in contacts
        )

        if has_exact:
            score += 150
            reasons.append("+150 exact contact name in transcript")
        elif corrected != candidate:
            has_corr_contact = any(c["normalized"] in corr_lower for c in contacts)
            if has_corr_contact:
                score += 80
                reasons.append("+80 phonetic/dictionary correction aligned with contacts")

        diagnostics.append({
            "candidate": candidate,
            "corrected": corrected,
            "score": score,
            "reasons": reasons
        })

        if score > best_score:
            best_score = score
            best_candidate = corrected

    return best_candidate, diagnostics


def test_call_safety(query: str, contacts: List[Dict[str, str]]) -> Dict:
    """
    Evaluates whether a query will trigger an actual phone call and whether it violates safety.
    """
    clean_query = query.lower().strip()
    has_call_verb = bool(re.search(r"\b(call|dial)\b", clean_query)) and \
                    "call log" not in clean_query and \
                    "recent calls" not in clean_query
    has_msg_verb = bool(re.search(r"\b(message|text|whatsapp|sms|saying|send)\b", clean_query))

    is_call_query = not has_msg_verb and has_call_verb

    action = "NONE"
    dialed_number = None
    safety_alert = None

    if is_call_query:
        # Extract contact
        raw_name = re.sub(r"(?i)(call|phone|dial|ring)\s+", "", clean_query).strip()
        matched_contact, reason = find_best_matching_contact(raw_name, contacts)
        target = matched_contact or raw_name

        # Find phone number
        for c in contacts:
            if c["name"].lower() == target.lower() or c["normalized"] == target.lower():
                dialed_number = c["number"]
                break

        clean_num = re.sub(r"[^0-9]", "", dialed_number or "")
        if clean_num in EMERGENCY_NUMBERS:
            safety_alert = f"EMERGENCY NUMBER BLOCKED: {clean_num}. Opened dialer only."
            action = "OPEN_DIALER_SAFE"
        else:
            action = f"CALL: {target} ({dialed_number})"
    else:
        action = "IGNORED (No explicit call verb, or messaging verb present)"

    return {
        "query": query,
        "has_call_verb": has_call_verb,
        "is_call_query": is_call_query,
        "action": action,
        "dialed_number": dialed_number,
        "safety_alert": safety_alert
    }


def run_benchmark_suite():
    print("=" * 70)
    print("RUNNING AUTOMATED VOICE RECOGNITION & SAFETY VERIFICATION SUITE")
    print("=" * 70)

    # 1. Test "owe friend" bug
    print("\n--- TEST 1: The 'owe friend' / Emergency Fire Call Test ---")
    res = test_call_safety("owe friend", DEFAULT_CONTACTS)
    print(f"Query: 'owe friend'")
    print(f"Has call verb: {res['has_call_verb']}")
    print(f"Is Call Query: {res['is_call_query']}")
    print(f"Action: {res['action']}")
    assert not res["is_call_query"], "CRITICAL FAIL: 'owe friend' triggered a call query!"
    print(">> PASS: 'owe friend' does NOT trigger phone calling!")

    # 2. Test explicit call "call rohit"
    print("\n--- TEST 2: Explicit Call 'call rohit' ---")
    res = test_call_safety("call rohit", DEFAULT_CONTACTS)
    print(f"Query: 'call rohit'")
    print(f"Action: {res['action']}")
    assert "CALL: Rohit" in res["action"], "FAIL: 'call rohit' did not call Rohit"
    print(">> PASS: 'call rohit' successfully initiates call to Rohit!")

    # 3. Test multi-hypothesis resolution: Srujani vs Ragini
    print("\n--- TEST 3: Srujani vs Ragini Multi-Hypothesis Selection ---")
    # ASR produced candidates where first candidate was 'text rajani hello' and second was 'text srujani hello'
    hypotheses = [
        "text rajani hello",
        "text srujani hello",
        "text ragini hello"
    ]
    winner, diags = score_hypotheses(hypotheses, DEFAULT_CONTACTS)
    for d in diags:
        print(f"Candidate: '{d['candidate']}' -> Corrected: '{d['corrected']}' -> Score: {d['score']} ({', '.join(d['reasons'])})")
    print(f"Selected Winner: '{winner}'")
    assert "srujani" in winner.lower(), f"CRITICAL FAIL: Expected 'Srujani' to win, but got '{winner}'"
    print(">> PASS: Srujani correctly won over Ragini!")

    # 4. Test phonetic variant 'shrujani'
    print("\n--- TEST 4: Phonetic Variant 'shrujani' Correction ---")
    hypotheses = ["send message to shrujani saying how are you"]
    winner, diags = score_hypotheses(hypotheses, DEFAULT_CONTACTS)
    print(f"Input: '{hypotheses[0]}'")
    print(f"Output: '{winner}'")
    assert "srujani" in winner.lower(), f"FAIL: Expected 'Srujani' in corrected text, got '{winner}'"
    print(">> PASS: 'shrujani' correctly mapped to Srujani!")

    # 5. Test emergency number block
    print("\n--- TEST 5: Accidental/Direct Emergency Number Block ---")
    res = test_call_safety("call fire", DEFAULT_CONTACTS)
    print(f"Query: 'call fire'")
    print(f"Action: {res['action']}")
    print(f"Safety Alert: {res['safety_alert']}")
    assert res["action"] == "OPEN_DIALER_SAFE", "FAIL: Emergency number was directly called!"
    print(">> PASS: Direct ACTION_CALL to 101/Emergency blocked; safe dialer opened.")

    # 6. Test 'friend' matching against 'Fire' (Consonant skeleton check)
    print("\n--- TEST 6: Consonant Skeleton Guard ('friend' must NEVER match 'Fire') ---")
    matched, reason = find_best_matching_contact("friend", DEFAULT_CONTACTS)
    print(f"Candidate 'friend' matched to: {matched} ({reason})")
    assert matched != "Fire", "CRITICAL FAIL: 'friend' matched to 'Fire'!"
    print(">> PASS: 'friend' does not match 'Fire'!")

    print("\n" + "=" * 70)
    print("ALL 6 TESTS PASSED FLAWLESSLY! Voice recognition & safety system verified.")
    print("=" * 70)


def interactive_mode():
    print("\n--- Interactive Local PC Voice Recognition Testing Field ---")
    print("Type a phrase (e.g. 'text srujani hello' or 'call friend') or 'quit' to exit.")
    print("To simulate multiple ASR hypotheses, separate them with ' | '.")
    print("-" * 60)

    while True:
        try:
            user_input = input("\nEnter query > ").strip()
            if not user_input or user_input.lower() in ("quit", "exit"):
                break

            candidates = [c.strip() for c in user_input.split("|") if c.strip()]
            winner, diags = score_hypotheses(candidates, DEFAULT_CONTACTS)
            safety = test_call_safety(winner, DEFAULT_CONTACTS)

            print("\n[ASR Hypotheses Evaluation]")
            for i, d in enumerate(diags):
                print(f"  #{i+1}: '{d['candidate']}' -> '{d['corrected']}' [Score: {d['score']}]")
                for r in d["reasons"]:
                    print(f"      - {r}")

            print(f"\n[Final Selected Transcript]: \"{winner}\"")
            print(f"[Call Safety Check]: Action: {safety['action']}")
            if safety["safety_alert"]:
                print(f"  ALERT: {safety['safety_alert']}")

        except (KeyboardInterrupt, EOFError):
            break


if __name__ == "__main__":
    if len(sys.argv) > 1 and sys.argv[1] == "--interactive":
        interactive_mode()
    else:
        run_benchmark_suite()
        if sys.stdin.isatty():
            interactive_mode()

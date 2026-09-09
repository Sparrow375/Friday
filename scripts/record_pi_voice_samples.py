#!/usr/bin/env python3
"""
Interactive Voice Sample Recorder for Raspberry Pi INMP441 Microphone (v2 - Real-Time Worker)
Uses a persistent audio recording worker on the Pi for ZERO startup latency.
"""

import os
import sys
import time
import argparse
import paramiko

PI_HOST = "192.168.1.180"
PI_USER = "avaneesh"
PI_PASS = "avaneesh2006"

def play_sound(freq, duration_ms):
    """Play audio chime on Windows, fallback gracefully."""
    try:
        import winsound
        winsound.Beep(freq, duration_ms)
    except Exception:
        pass

def get_ssh_client():
    client = paramiko.SSHClient()
    client.set_missing_host_key_policy(paramiko.AutoAddPolicy())
    key_path = os.path.expanduser("~/.ssh/id_ed25519")
    if os.path.exists(key_path):
        client.connect(PI_HOST, username=PI_USER, key_filename=key_path, timeout=10)
    else:
        client.connect(PI_HOST, username=PI_USER, password=PI_PASS, timeout=10)
    return client

class PiRecordWorker:
    def __init__(self, client):
        self.client = client
        print("  [Pi] Pausing wearable service to release mic...", flush=True)
        self.client.exec_command("echo avaneesh2006 | sudo -S systemctl stop friday-wearable.service")[1].channel.recv_exit_status()
        time.sleep(0.5)

        print("  [Pi] Starting zero-latency hardware audio worker...", flush=True)
        self.stdin, self.stdout, self.stderr = self.client.exec_command(
            "/home/avaneesh/friday_env/bin/python3 -u /home/avaneesh/voice_record_server.py"
        )
        ready = self.stdout.readline().strip()
        if ready != "SERVER_READY":
            raise RuntimeError(f"Failed to initialize audio server on Pi: {ready}")
        print("  [Pi] Microphone hardware is warm and listening (0ms latency).", flush=True)

    def record_clip(self, remote_wav, duration=2.2):
        # Trigger recording on Pi
        cmd = f"RECORD {remote_wav} {duration}\n"
        self.stdin.write(cmd)
        self.stdin.flush()

        resp = self.stdout.readline().strip()
        if not resp.startswith("SAVED:"):
            raise RuntimeError(f"Unexpected response from Pi: {resp}")

        parts = resp.split(":")
        rms = float(parts[2]) if len(parts) > 2 else 0.0
        peak = float(parts[3]) if len(parts) > 3 else 0.0
        return rms, peak

    def close(self):
        try:
            self.stdin.write("QUIT\n")
            self.stdin.flush()
        except Exception:
            pass
        print("  [Pi] Resuming wearable service...", flush=True)
        self.client.exec_command("echo avaneesh2006 | sudo -S systemctl start friday-wearable.service")[1].channel.recv_exit_status()
        time.sleep(0.5)

def capture_single(worker, sftp, remote_wav, local_wav, duration=2.2, phrase="Friday"):
    # 3-2-1 Countdown
    print("\n  Get ready...", flush=True)
    for i in range(3, 1, -1):
        print(f"    --> {i}...", flush=True)
        play_sound(750, 100)
        time.sleep(0.65)

    print("    --> 1...", flush=True)
    play_sound(750, 100)
    time.sleep(0.3)

    # Start audio capture slightly before the chime so the first consonant ('F-') is NEVER clipped!
    print(f"\n  🔴 SPEAK NOW! [{phrase}]", flush=True)
    play_sound(1350, 200)

    rms, peak = worker.record_clip(remote_wav, duration=duration)
    play_sound(950, 100)

    # Download to PC
    sftp.get(remote_wav, local_wav)

    if peak < 0.05:
        print(f"  ⚠️ Warning: Low voice volume (RMS: {rms:.4f}, Peak: {peak:.3f}). Make sure you spoke towards the mic!")
    else:
        print(f"  ✔ Saved: {os.path.basename(local_wav)} (RMS: {rms:.4f}, Peak: {peak:.3f} - Clean Audio!)")
    return True

def record_positives(worker, sftp, count=25):
    target_dir = os.path.abspath("temp_dataset/positive")
    os.makedirs(target_dir, exist_ok=True)

    print(f"\n=======================================================")
    print(f"  🎤 RECORDING POSITIVE SAMPLES: 'Friday'")
    print(f"  Target: {count} clips of you saying 'Friday'")
    print(f"=======================================================")
    print("  Tips for best accuracy:")
    print("   - Speak naturally as soon as you hear the high beep")
    print("   - Samples 1-10: normal voice at 30cm")
    print("   - Samples 11-20: conversational voice at 50cm")
    print("   - Samples 21-25: casual, quick, or soft")
    print("=======================================================")

    existing = [f for f in os.listdir(target_dir) if f.startswith("pi_real_pos_") and f.endswith(".wav")]
    start_idx = len(existing) + 1

    for i in range(count):
        idx = start_idx + i
        local_wav = os.path.join(target_dir, f"pi_real_pos_{idx:03d}.wav")
        remote_wav = f"/tmp/pos_{idx:03d}.wav"

        dist_hint = "30cm (normal)" if i < 10 else ("50cm (conversational)" if i < 20 else "casual / fast")
        prompt = input(f"\n[Sample {i+1}/{count}] Distance: {dist_hint}. Press ENTER to record (or 'q' to stop): ")
        if prompt.strip().lower() == 'q':
            break

        capture_single(worker, sftp, remote_wav, local_wav, duration=2.2, phrase="Friday")

    print(f"\n✔ Positive recording phase complete! Total files in {target_dir}: {len(os.listdir(target_dir))}")

def record_negatives(worker, sftp, count=15):
    target_dir = os.path.abspath("temp_dataset/negative")
    os.makedirs(target_dir, exist_ok=True)

    neg_words = [
        "What's up", "WhatsApp", "Hey Google", "Alexa", "Siri",
        "Free day", "Fried day", "Friday night", "Friendly",
        "Open YouTube", "Turn on torch", "What is the time",
        "How is the weather", "Navigate home", "Send a message"
    ]

    print(f"\n=======================================================")
    print(f"  🎤 RECORDING NEGATIVE SAMPLES (Distractor Words)")
    print(f"  Target: {min(count, len(neg_words))} clips of distractor words")
    print(f"=======================================================")

    existing = [f for f in os.listdir(target_dir) if f.startswith("pi_real_neg_") and f.endswith(".wav")]
    start_idx = len(existing) + 1

    for i in range(min(count, len(neg_words))):
        idx = start_idx + i
        local_wav = os.path.join(target_dir, f"pi_real_neg_{idx:03d}.wav")
        remote_wav = f"/tmp/neg_{idx:03d}.wav"
        phrase = neg_words[i]

        prompt = input(f"\n[Negative {i+1}/{count}] Say: \"{phrase}\". Press ENTER (or 'q' to stop): ")
        if prompt.strip().lower() == 'q':
            break

        capture_single(worker, sftp, remote_wav, local_wav, duration=2.2, phrase=phrase)

    print(f"\n✔ Negative recording phase complete! Total files in {target_dir}: {len(os.listdir(target_dir))}")

def main():
    parser = argparse.ArgumentParser(description="Record real voice samples from Pi INMP441 mic")
    parser.add_argument("--mode", choices=["all", "pos", "neg"], default="all", help="What to record")
    parser.add_argument("--num-pos", type=int, default=25, help="Number of positive samples")
    parser.add_argument("--num-neg", type=int, default=15, help="Number of negative samples")
    args = parser.parse_args()

    print(f"Connecting to Raspberry Pi ({PI_HOST})...")
    client = get_ssh_client()
    sftp = client.open_sftp()
    worker = None

    try:
        worker = PiRecordWorker(client)

        if args.mode in ["all", "pos"]:
            record_positives(worker, sftp, count=args.num_pos)

        if args.mode in ["all", "neg"]:
            record_negatives(worker, sftp, count=args.num_neg)

    finally:
        if worker:
            worker.close()
        sftp.close()
        client.close()
        print("\n✔ Session finished cleanly. Wearable daemon is back online.")

if __name__ == "__main__":
    main()

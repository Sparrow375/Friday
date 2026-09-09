#!/usr/bin/env python3
"""
Interactive Microphone Calibration Runner (with Windows Audio Countdown Chimes)
"""

import os
import sys
import time
import argparse
import paramiko
import subprocess

PI_HOST = "192.168.1.180"
PI_USER = "avaneesh"
REMOTE_RUNNER = "/home/avaneesh/mic_calibration_runner.py"
REMOTE_OUTPUT_DIR = "/home/avaneesh/calibration_results"
LOCAL_AUDIO_DIR = os.path.abspath("scripts/calibration_audio")

def play_beep(freq, duration_ms):
    try:
        cmd = f"powershell -Command \"[console]::beep({freq}, {duration_ms})\""
        subprocess.run(cmd, shell=True, capture_output=True, timeout=1.0)
    except Exception:
        pass

def get_ssh_client():
    client = paramiko.SSHClient()
    client.set_missing_host_key_policy(paramiko.AutoAddPolicy())
    key_path = os.path.expanduser("~/.ssh/id_ed25519")
    if os.path.exists(key_path):
        client.connect(PI_HOST, username=PI_USER, key_filename=key_path, timeout=10)
    else:
        client.connect(PI_HOST, username=PI_USER, password="avaneesh2006", timeout=10)
    return client

def record_and_sync(label="voice_trial1", duration=5.0, countdown=4):
    os.makedirs(LOCAL_AUDIO_DIR, exist_ok=True)
    client = get_ssh_client()

    print(f"\n=======================================================")
    print(f"  VOICE CALIBRATION TRIAL: [{label}] ({duration:.1f}s)")
    print(f"  Position yourself ~30cm from the microphone")
    print(f"=======================================================")
    
    for i in range(countdown, 0, -1):
        print(f"  --> Starting in {i}...", flush=True)
        play_beep(600, 150)
        time.sleep(0.85)

    print("\n  >>> BEEP! RECORDING NOW! SPEAK YOUR PHRASE! <<<", flush=True)
    play_beep(1200, 300)

    # Trigger remote runner
    cmd = f"/home/avaneesh/friday_env/bin/python3 {REMOTE_RUNNER} --duration {duration} --label {label}"
    stdin, stdout, stderr = client.exec_command(cmd)
    
    out = stdout.read().decode('utf-8', errors='replace')
    err = stderr.read().decode('utf-8', errors='replace')
    code = stdout.channel.recv_exit_status()

    print("\n  >>> RECORDING FINISHED! Processing audio... <<<", flush=True)
    play_beep(800, 150)
    time.sleep(0.1)
    play_beep(800, 150)

    if code != 0:
        print(f"[Error] Remote runner failed with code {code}:")
        print(err)
        client.close()
        return []

    print(out)

    # Download newly generated files
    print("\n[Sync] Downloading audio variations to PC...")
    sftp = client.open_sftp()
    remote_files = sftp.listdir(REMOTE_OUTPUT_DIR)
    
    matching_files = [f for f in remote_files if f.startswith(label) and f.endswith(".wav")]
    matching_files.sort()

    downloaded = []
    for f in matching_files:
        r_path = f"{REMOTE_OUTPUT_DIR}/{f}"
        l_path = os.path.join(LOCAL_AUDIO_DIR, f)
        sftp.get(r_path, l_path)
        size_kb = os.path.getsize(l_path) / 1024.0
        downloaded.append((f, l_path, size_kb))

    sftp.close()
    client.close()

    print(f"\n[Done] Downloaded {len(downloaded)} audio variations:")
    for f, l_path, size_kb in downloaded:
        print(f" • {f} ({size_kb:.1f} KB)")
        print(f"   file:///{l_path.replace(os.sep, '/')}")

    return downloaded

if __name__ == "__main__":
    parser = argparse.ArgumentParser(description="Run Pi Mic Calibration Turn")
    parser.add_argument("--label", type=str, default="voice_trial1", help="Trial label")
    parser.add_argument("--duration", type=float, default=5.0, help="Recording duration")
    parser.add_argument("--countdown", type=int, default=4, help="Countdown seconds")
    args = parser.parse_args()

    record_and_sync(label=args.label, duration=args.duration, countdown=args.countdown)

import os
import paramiko

PI_HOST = "192.168.1.180"
PI_USER = "avaneesh"
PI_PASS = "avaneesh2006"

PUB_KEY_PATH = os.path.expanduser("~/.ssh/id_ed25519.pub")

def main():
    print(f"Connecting to {PI_USER}@{PI_HOST}...")
    client = paramiko.SSHClient()
    client.set_missing_host_key_policy(paramiko.AutoAddPolicy())
    client.connect(PI_HOST, username=PI_USER, password=PI_PASS, timeout=10)
    print("SSH connection successful!")

    # Setup authorized_keys if public key exists
    if os.path.exists(PUB_KEY_PATH):
        with open(PUB_KEY_PATH, "r") as f:
            pub_key = f.read().strip()
        print(f"Installing public key to ~/.ssh/authorized_keys on Pi...")
        cmd_key = f"mkdir -p ~/.ssh && chmod 700 ~/.ssh && touch ~/.ssh/authorized_keys && chmod 600 ~/.ssh/authorized_keys && grep -qF '{pub_key}' ~/.ssh/authorized_keys || echo '{pub_key}' >> ~/.ssh/authorized_keys"
        stdin, stdout, stderr = client.exec_command(cmd_key)
        stdout.channel.recv_exit_status()
        print("Public key installed! Passwordless SSH enabled.")

    commands = [
        ("System Info", "uname -a"),
        ("ALSA Cards", "cat /proc/asound/cards"),
        ("arecord Devices", "arecord -l"),
        ("ALSA Conf (/etc/asound.conf)", "cat /etc/asound.conf 2>/dev/null || echo 'No /etc/asound.conf'"),
        ("User ALSA Conf (~/.asoundrc)", "cat ~/.asoundrc 2>/dev/null || echo 'No ~/.asoundrc'"),
        ("Amixer Controls", "amixer -c 0 2>/dev/null || amixer 2>/dev/null || echo 'No amixer output'"),
        ("Friday Service Status", "systemctl --user status friday-wearable 2>/dev/null || systemctl status friday-wearable 2>/dev/null || echo 'Not found'"),
        ("Friday Wearable Files", "find ~ -name 'wearable_daemon.py' -o -name 'last_command.wav' -o -name 'wakeword.onnx' 2>/dev/null")
    ]

    for title, cmd in commands:
        print(f"\n=== {title} ({cmd}) ===")
        stdin, stdout, stderr = client.exec_command(cmd)
        out = stdout.read().decode('utf-8', errors='replace').strip()
        err = stderr.read().decode('utf-8', errors='replace').strip()
        if out:
            print(out)
        if err:
            print(f"[stderr] {err}")

    client.close()

if __name__ == "__main__":
    main()

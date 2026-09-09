import os
import sys
import paramiko

PI_HOST = "192.168.1.180"
PI_USER = "avaneesh"
REMOTE_DIR = "/home/avaneesh/calibration_results"
LOCAL_DIR = os.path.abspath("scripts/calibration_audio")

def download_all():
    os.makedirs(LOCAL_DIR, exist_ok=True)
    client = paramiko.SSHClient()
    client.set_missing_host_key_policy(paramiko.AutoAddPolicy())
    key_path = os.path.expanduser("~/.ssh/id_ed25519")
    client.connect(PI_HOST, username=PI_USER, key_filename=key_path, timeout=10)
    sftp = client.open_sftp()
    
    files = sftp.listdir(REMOTE_DIR)
    print(f"Found {len(files)} files in {REMOTE_DIR}:")
    downloaded = []
    for f in sorted(files):
        if f.endswith(".wav"):
            r_path = f"{REMOTE_DIR}/{f}"
            l_path = os.path.join(LOCAL_DIR, f)
            sftp.get(r_path, l_path)
            size = os.path.getsize(l_path)
            print(f" [✓] {f} ({size} bytes) -> {l_path}")
            downloaded.append(l_path)
    
    sftp.close()
    client.close()
    return downloaded

if __name__ == "__main__":
    download_all()

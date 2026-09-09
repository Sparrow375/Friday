import sys
import os
import paramiko

PI_HOST = "192.168.1.180"
PI_USER = "avaneesh"
PI_PASS = "avaneesh2006"

def get_client():
    client = paramiko.SSHClient()
    client.set_missing_host_key_policy(paramiko.AutoAddPolicy())
    key_path = os.path.expanduser("~/.ssh/id_ed25519")
    if os.path.exists(key_path):
        client.connect(PI_HOST, username=PI_USER, key_filename=key_path, timeout=10)
    else:
        client.connect(PI_HOST, username=PI_USER, password=PI_PASS, timeout=10)
    return client

def run_cmd(cmd):
    client = get_client()
    try:
        stdin, stdout, stderr = client.exec_command(cmd)
        out = stdout.read().decode('utf-8', errors='replace')
        err = stderr.read().decode('utf-8', errors='replace')
        code = stdout.channel.recv_exit_status()
        return code, out, err
    finally:
        client.close()

def download_file(remote_path, local_path):
    client = get_client()
    try:
        sftp = client.open_sftp()
        sftp.get(remote_path, local_path)
        sftp.close()
    finally:
        client.close()

def upload_file(local_path, remote_path):
    client = get_client()
    try:
        sftp = client.open_sftp()
        sftp.put(local_path, remote_path)
        sftp.close()
    finally:
        client.close()

if __name__ == "__main__":
    if len(sys.argv) < 2:
        print("Usage: python pi_ssh.py <exec|get|put> [args...]")
        sys.exit(1)
    action = sys.argv[1]
    if action == "exec":
        code, out, err = run_cmd(" ".join(sys.argv[2:]))
        if out:
            print(out, end="")
        if err:
            print(err, file=sys.stderr, end="")
        sys.exit(code)
    elif action == "get":
        download_file(sys.argv[2], sys.argv[3])
        print(f"Downloaded {sys.argv[2]} -> {sys.argv[3]}")
    elif action == "put":
        upload_file(sys.argv[2], sys.argv[3])
        print(f"Uploaded {sys.argv[2]} -> {sys.argv[3]}")

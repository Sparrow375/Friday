import sys
import os
import socket

def get_ip():
    s = socket.socket(socket.AF_INET, socket.SOCK_DGRAM)
    try:
        s.connect(('10.255.255.255', 1))
        IP = s.getsockname()[0]
    except Exception:
        IP = '127.0.0.1'
    finally:
        s.close()
    return IP

def start_server(model_path, port=5000):
    try:
        from flask import Flask, request, jsonify
        from llama_cpp import Llama
    except ImportError:
        print("Required libraries missing. Installing flask and llama-cpp-python...")
        import subprocess
        try:
            subprocess.check_call([sys.executable, "-m", "pip", "install", "flask", "llama-cpp-python"])
        except Exception as e:
            print(f"Automatic installation failed: {e}")
            print("Please run: pip install flask llama-cpp-python")
            sys.exit(1)
        from flask import Flask, request, jsonify
        from llama_cpp import Llama

    if not os.path.exists(model_path):
        print(f"Error: Model file not found at '{model_path}'")
        sys.exit(1)

    print(f"Loading GGUF model from {model_path}...")
    try:
        llm = Llama(model_path=model_path, n_ctx=2048)
    except Exception as e:
        print(f"Error loading model: {e}")
        sys.exit(1)
    print("Model loaded successfully!")

    app = Flask(__name__)

    @app.route('/generate', methods=['POST'])
    def generate():
        data = request.json
        prompt = data.get("prompt", "")
        history = data.get("history", [])

        # Build prompt format
        full_prompt = "System: You are Friday, Avaneesh's voice assistant. Keep replies very short (1-2 sentences) and friendly.\n"
        for turn in history:
            speaker = turn.get("speaker", "USER")
            message = turn.get("message", "")
            full_prompt += f"{speaker}: {message}\n"
        full_prompt += f"USER: {prompt}\nFRIDAY:"

        print(f"\nReceived prompt: {prompt}")
        output = llm(full_prompt, max_tokens=128, stop=["USER:", "System:", "\n\n"])
        response_text = output["choices"][0]["text"].strip()
        print(f"Response: {response_text}")

        return jsonify({"response": response_text})

    local_ip = get_ip()
    print("\n" + "="*60)
    print(f" Friday GGUF PC Server is active!")
    print(f" PC Local IP Address: {local_ip}")
    print(f" In the Friday Android app, configure the PC IP under settings as:")
    print(f" {local_ip}:{port}")
    print("="*60 + "\n")

    app.run(host='0.0.0.0', port=port)

if __name__ == "__main__":
    if len(sys.argv) < 2:
        print("Usage: python friday_helper.py <path_to_gguf_file> [port]")
        print("Example: python friday_helper.py Llama-3.2-1B-Instruct-Q4_K_M.gguf")
        sys.exit(1)
    
    model_path = sys.argv[1]
    port = int(sys.argv[2]) if len(sys.argv) > 2 else 5000
    start_server(model_path, port)

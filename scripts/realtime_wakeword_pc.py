#!/usr/bin/env python3
import os
import sys
import time
import queue
import threading
import tkinter as tk
from tkinter import messagebox
import numpy as np

# Audio parameters matching the trained model and Android code
SAMPLE_RATE = 16000
DURATION_SECONDS = 1.5
INPUT_SIZE = int(SAMPLE_RATE * DURATION_SECONDS)  # 24000
BLOCK_SIZE = 1600  # 100ms chunks for sliding window evaluation

class WakeWordApp:
    def __init__(self, root):
        self.root = root
        self.root.title("Friday Wake-Word Tester")
        self.root.geometry("450x420")
        self.root.configure(bg="#0B0F19") # Slate black / dark obsidian background
        self.root.resizable(False, False)

        # State variables
        self.detection_count = 0
        self.is_listening = False
        self.cooldown_until = 0.0
        self.audio_buffer = np.zeros(INPUT_SIZE, dtype=np.float32)
        
        # Communication queue between background audio stream thread and main GUI thread
        self.msg_queue = queue.Queue()

        self.setup_ui()
        self.load_model()
        self.start_listening()
        self.process_queue()

    def setup_ui(self):
        # Header title
        title_label = tk.Label(
            self.root, 
            text="Friday Real-Time Wake-Word Detector", 
            font=("Helvetica", 15, "bold"), 
            bg="#0B0F19", 
            fg="#60A5FA" # Neon blue
        )
        title_label.pack(pady=15)

        # Status indicator canvas orb
        self.orb_canvas = tk.Canvas(self.root, width=80, height=80, bg="#0B0F19", highlightthickness=0)
        self.orb_canvas.pack(pady=10)
        self.status_orb = self.orb_canvas.create_oval(10, 10, 70, 70, fill="#1E293B", outline="")

        # Real-time Status Text
        self.status_label = tk.Label(
            self.root, 
            text="Initializing...", 
            font=("Helvetica", 11, "bold"), 
            bg="#0B0F19", 
            fg="#94A3B8" # Slate silver
        )
        self.status_label.pack(pady=5)

        # Confidence percentage label
        self.confidence_label = tk.Label(
            self.root,
            text="Conf: 0.0%",
            font=("Helvetica", 10),
            bg="#0B0F19",
            fg="#475569"
        )
        self.confidence_label.pack(pady=2)

        # Counter Label
        self.count_label = tk.Label(
            self.root, 
            text="Detections: 0", 
            font=("Helvetica", 28, "bold"), 
            bg="#0B0F19", 
            fg="#FFFFFF"
        )
        self.count_label.pack(pady=15)

        # Volume level meter
        meter_frame = tk.Frame(self.root, bg="#0B0F19")
        meter_frame.pack(pady=10)
        
        tk.Label(meter_frame, text="Mic Level: ", font=("Helvetica", 9), bg="#0B0F19", fg="#64748B").pack(side=tk.LEFT)
        self.meter_canvas = tk.Canvas(meter_frame, width=200, height=12, bg="#1E293B", highlightthickness=0)
        self.meter_canvas.pack(side=tk.LEFT, padx=5)
        self.meter_bar = self.meter_canvas.create_rectangle(0, 0, 0, 12, fill="#3B82F6", outline="")

        # Reset button
        reset_btn = tk.Button(
            self.root, 
            text="Reset Counter", 
            font=("Helvetica", 10, "bold"),
            bg="#1E293B", 
            fg="#FFFFFF", 
            activebackground="#334155", 
            activeforeground="#FFFFFF",
            relief=tk.FLAT, 
            bd=0, 
            padx=15, 
            pady=6,
            command=self.reset_counter
        )
        reset_btn.pack(pady=15)

        # Hover states for button
        reset_btn.bind("<Enter>", lambda e: reset_btn.config(bg="#334155"))
        reset_btn.bind("<Leave>", lambda e: reset_btn.config(bg="#1E293B"))

    def load_model(self):
        try:
            import onnxruntime as ort
        except ImportError:
            messagebox.showerror("Dependency Error", "onnxruntime is required. Please install it using: pip install onnxruntime")
            self.root.destroy()
            return

        model_path = os.path.join("app", "src", "main", "assets", "wakeword.onnx")
        if not os.path.exists(model_path):
            model_path = os.path.join("output", "wakeword.onnx")
            if not os.path.exists(model_path):
                messagebox.showerror("Model Error", "wakeword.onnx not found in assets or output directories.")
                self.root.destroy()
                return

        try:
            self.session = ort.InferenceSession(model_path)
            self.input_name = self.session.get_inputs()[0].name
            self.status_label.config(text="Listening for 'Friday'...")
            self.orb_canvas.itemconfig(self.status_orb, fill="#3B82F6") # Blue orb
        except Exception as e:
            messagebox.showerror("Initialization Error", f"Failed to load ONNX model:\n{e}")
            self.root.destroy()

    def start_listening(self):
        try:
            import sounddevice as sd
        except ImportError:
            messagebox.showerror("Dependency Error", "sounddevice is required. Please install it using: pip install sounddevice")
            self.root.destroy()
            return

        self.is_listening = True
        self.audio_thread = threading.Thread(target=self.audio_capture_loop, daemon=True)
        self.audio_thread.start()

    def audio_capture_loop(self):
        import sounddevice as sd
        
        def audio_callback(indata, frames, time_info, status):
            if status:
                print(f"[Audio Status Warning] {status}", file=sys.stderr)
            self.msg_queue.put(('audio', indata.copy()))

        try:
            with sd.InputStream(
                samplerate=SAMPLE_RATE,
                channels=1,
                blocksize=BLOCK_SIZE,
                dtype='float32',
                callback=audio_callback
            ):
                while self.is_listening:
                    time.sleep(0.05)
        except Exception as e:
            self.msg_queue.put(('error', str(e)))

    def process_queue(self):
        try:
            while True:
                msg_type, data = self.msg_queue.get_nowait()
                if msg_type == 'audio':
                    self.handle_audio_chunk(data)
                elif msg_type == 'error':
                    messagebox.showerror("Audio Stream Error", f"Failed to capture audio:\n{data}")
                    self.root.destroy()
        except queue.Empty:
            pass
        
        # Check queue again in 10ms for minimal latency
        self.root.after(10, self.process_queue)

    def handle_audio_chunk(self, chunk):
        # Flatten chunk
        samples = chunk.flatten()
        
        # Shift circular buffer and copy new samples to the end
        self.audio_buffer = np.roll(self.audio_buffer, -len(samples))
        self.audio_buffer[-len(samples):] = samples

        # Calculate volume level (RMS) for the current 100ms chunk
        rms = np.sqrt(np.mean(samples ** 2)) if len(samples) > 0 else 0
        meter_width = min(200, int(rms * 1200)) # Scale RMS to meter bar size
        self.meter_canvas.coords(self.meter_bar, 0, 0, meter_width, 12)

        # Run ONNX inference
        now = time.time()
        if now < self.cooldown_until:
            # We are in cooldown phase (wake word was just triggered)
            # Update orb countdown colors
            time_left = self.cooldown_until - now
            if time_left > 0.8:
                self.orb_canvas.itemconfig(self.status_orb, fill="#10B981") # Flash Green
                self.status_label.config(text="[+] Detected 'Friday'!", fg="#10B981")
            else:
                self.orb_canvas.itemconfig(self.status_orb, fill="#475569") # Muted slate orb
                self.status_label.config(text="Cooling down...", fg="#64748B")
            return

        # Regular listening state visual updates
        self.orb_canvas.itemconfig(self.status_orb, fill="#3B82F6") # Listening Blue
        self.status_label.config(text="Listening for 'Friday'...", fg="#94A3B8")

        # Run ONNX inference on the full 1.5-second buffer [1, 1, 24000]
        input_data = np.expand_dims(np.expand_dims(self.audio_buffer, axis=0), axis=0).astype(np.float32)
        outputs = self.session.run(None, {self.input_name: input_data})
        logits = outputs[0][0]

        # Apply softmax to logits to get the wake-word probability (confidence)
        exp_neg = np.exp(logits[0])
        exp_pos = np.exp(logits[1])
        confidence = exp_pos / (exp_neg + exp_pos)

        # Update confidence text label
        self.confidence_label.config(text=f"Conf: {confidence:.1%}", fg="#475569" if confidence < 0.5 else "#F59E0B" if confidence < 0.85 else "#10B981")

        if confidence >= 0.85:
            # Trigger detection!
            self.detection_count += 1
            self.count_label.config(text=f"Detections: {self.detection_count}")
            self.cooldown_until = now + 1.2 # Disable detections for 1.2 seconds to avoid double-triggers
            
            # Flash orb to Green
            self.orb_canvas.itemconfig(self.status_orb, fill="#10B981")
            self.status_label.config(text="[+] Detected 'Friday'!", fg="#10B981")

    def reset_counter(self):
        self.detection_count = 0
        self.count_label.config(text="Detections: 0")

    def cleanup(self):
        self.is_listening = False
        self.root.destroy()

def main():
    root = tk.Tk()
    app = WakeWordApp(root)
    root.protocol("WM_DELETE_WINDOW", app.cleanup)
    root.mainloop()

if __name__ == "__main__":
    main()

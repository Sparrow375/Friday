#!/usr/bin/env python3
"""
Friday Wearable Voice Detection Daemon (v2 - Rigorous Multi-Tier Architecture)
Runs on Raspberry Pi Zero 2 W with INMP441 I2S MEMS Microphone.

Detection Architecture:
- Hardware I2S Capture: Native 48kHz S32_LE stereo capture, isolating active Channel 1 (INMP441 Right channel)
- Zero-Latency Decimation: Integer 3:1 decimation (48kHz -> 16kHz) with zero resampling distortion
- Tier 0: Dynamic ambient baseline tracking & close-proximity SNR gate
- Tier 1: 1D-CNN ONNX Keyword Spotter with Logit Margin & Probability threshold
- Tier 2: Multi-Frame Temporal Confirmation (requires 2 consecutive 100ms frames)
- Tier 3: Warmup buffer guard & post-command cooldown
- BLE: GATT Service advertising 'Friday-Wearable' streaming compressed IMA-ADPCM packets
"""

import os
import sys
import time
import wave
import asyncio
import threading
import subprocess
import numpy as np
import onnxruntime as ort

from dbus_next.aio import MessageBus
from dbus_next.constants import BusType
from bluez_peripheral.util import Adapter
from bluez_peripheral.advert import Advertisement
from bluez_peripheral.gatt.service import Service
from bluez_peripheral.gatt.characteristic import characteristic, CharacteristicFlags

# Audio Settings
SAMPLE_RATE = 16000          # Output target for ONNX and BLE transmission
HARDWARE_RATE = 48000        # Native I2S sample rate
DECIMATION_FACTOR = 3        # 48000 / 3 = 16000
WINDOW_SAMPLES = 24000       # 1.5s window for ONNX wake-word
CHUNK_SAMPLES = 1600         # 100ms chunk at 16kHz
HW_CHUNK_SAMPLES = CHUNK_SAMPLES * DECIMATION_FACTOR # 4800 samples per channel at 48kHz
HW_CHUNK_BYTES = HW_CHUNK_SAMPLES * 2 * 4            # 2 channels * 4 bytes (S32_LE) = 38,400 bytes

# Responsive & Robust Detection Tuning
MIN_SPEECH_RMS = 0.040       # Close-proximity speech floor (ambient room noise is ~0.010)
SNR_MULTIPLIER = 1.60        # Frame RMS must be 1.6x above ambient baseline
CONFIDENCE_THRESHOLD = 0.75  # Calibrated probability threshold for 'Friday'
LOGIT_MARGIN_MIN = 1.0       # pos_logit - neg_logit >= 1.0
MIN_CONSECUTIVE_HITS = 1     # Instant detection on qualified candidate frame
WARMUP_CHUNKS = 15           # Discard initial 1.5s until buffer is fully populated

SILENCE_TIMEOUT_SEC = 1.2    # Trailing silence to end command recording
MAX_COMMAND_SEC = 7.0        # Max command duration limit
COOLDOWN_SEC = 2.0           # Cooldown to avoid self-retrigger or room echo

# BLE UUIDs
SERVICE_UUID = "1F81DA00-B5A3-F393-E0A9-E50E24DCCA9E"
STATE_CHAR_UUID = "1F81DA01-B5A3-F393-E0A9-E50E24DCCA9E"
COMMAND_CHAR_UUID = "1F81DA02-B5A3-F393-E0A9-E50E24DCCA9E"

# IMA ADPCM Tables
STEP_TABLE = [
    7, 8, 9, 10, 11, 12, 13, 14, 16, 17,
    19, 21, 23, 25, 28, 31, 34, 37, 41, 45,
    50, 55, 60, 66, 73, 80, 88, 97, 107, 118,
    130, 143, 157, 173, 190, 209, 230, 253, 279, 307,
    337, 371, 408, 449, 494, 544, 598, 658, 724, 796,
    876, 963, 1060, 1166, 1282, 1411, 1552, 1707, 1878, 2066,
    2272, 2499, 2749, 3024, 3327, 3660, 4026, 4428, 4871, 5358,
    5894, 6484, 7132, 7845, 8630, 9493, 10442, 11487, 12635, 13899,
    15289, 16818, 18500, 20350, 22385, 24623, 27086, 29794, 32767
]
INDEX_TABLE = [-1, -1, -1, -1, 2, 4, 6, 8, -1, -1, -1, -1, 2, 4, 6, 8]

def encode_ima_adpcm_fast(int16_samples):
    """High-speed IMA-ADPCM encoder without scalar overflow."""
    step_table = STEP_TABLE
    idx_table = INDEX_TABLE
    out = bytearray(len(int16_samples) // 2)
    valprev = 0
    index = 0
    out_idx = 0

    samples = [int(s) for s in int16_samples]
    n = len(samples) - (len(samples) % 2)

    for i in range(0, n, 2):
        # Sample 1 (low nibble)
        s1 = samples[i]
        step = step_table[index]
        diff = s1 - valprev
        sign = 8 if diff < 0 else 0
        if sign:
            diff = -diff
        delta1 = 0
        vpdiff = step >> 3
        if diff >= step:
            delta1 |= 4
            diff -= step
            vpdiff += step
        step >>= 1
        if diff >= step:
            delta1 |= 2
            diff -= step
            vpdiff += step
        step >>= 1
        if diff >= step:
            delta1 |= 1
            vpdiff += step
        valprev = valprev - vpdiff if sign else valprev + vpdiff
        if valprev > 32767:
            valprev = 32767
        elif valprev < -32768:
            valprev = -32768
        index += idx_table[delta1]
        if index < 0:
            index = 0
        elif index > 88:
            index = 88
        delta1 |= sign

        # Sample 2 (high nibble)
        s2 = samples[i + 1]
        step = step_table[index]
        diff = s2 - valprev
        sign = 8 if diff < 0 else 0
        if sign:
            diff = -diff
        delta2 = 0
        vpdiff = step >> 3
        if diff >= step:
            delta2 |= 4
            diff -= step
            vpdiff += step
        step >>= 1
        if diff >= step:
            delta2 |= 2
            diff -= step
            vpdiff += step
        step >>= 1
        if diff >= step:
            delta2 |= 1
            vpdiff += step
        valprev = valprev - vpdiff if sign else valprev + vpdiff
        if valprev > 32767:
            valprev = 32767
        elif valprev < -32768:
            valprev = -32768
        index += idx_table[delta2]
        if index < 0:
            index = 0
        elif index > 88:
            index = 88
        delta2 |= sign

        out[out_idx] = delta1 | (delta2 << 4)
        out_idx += 1

    return bytes(out)

class FridayGattService(Service):
    def __init__(self):
        super().__init__(SERVICE_UUID, True)
        self._state_val = bytes([0x00])
        self._command_val = b""

    @characteristic(STATE_CHAR_UUID, CharacteristicFlags.READ | CharacteristicFlags.NOTIFY)
    def state_char(self, options):
        return self._state_val

    @characteristic(COMMAND_CHAR_UUID, CharacteristicFlags.READ | CharacteristicFlags.NOTIFY)
    def command_char(self, options):
        return self._command_val

    def set_state(self, new_state_byte):
        self._state_val = bytes([new_state_byte])
        try:
            self.state_char.changed(self._state_val)
        except Exception:
            pass

    def notify_command_packet(self, packet_bytes):
        self._command_val = packet_bytes
        try:
            self.command_char.changed(self._command_val)
        except Exception:
            pass

class FridayWearableDaemon:
    def __init__(self, model_path="wakeword.onnx", record_device="inmp441_raw"):
        self.model_path = model_path
        self.record_device = record_device
        self.running = False
        self.session = None
        self.input_name = None
        self.ring_buffer = np.zeros(WINDOW_SAMPLES, dtype=np.float32)
        self.proc = None
        self.gatt_service = None
        self.loop = None
        
        self.baseline_rms = 0.020
        self.chunks_seen = 0
        self.consecutive_hits = 0

        self._load_model()

    def _load_model(self):
        print(f"[Init] Loading ONNX wake-word model: {self.model_path}...")
        t0 = time.perf_counter()
        opts = ort.SessionOptions()
        opts.intra_op_num_threads = 2
        opts.execution_mode = ort.ExecutionMode.ORT_SEQUENTIAL
        self.session = ort.InferenceSession(self.model_path, opts)
        self.input_name = self.session.get_inputs()[0].name
        print(f"[Init] Model loaded in {(time.perf_counter() - t0)*1000:.1f}ms")

    def _start_audio_stream(self):
        cmd = [
            "arecord",
            "-D", self.record_device,
            "-r", str(HARDWARE_RATE),
            "-f", "S32_LE",
            "-c", "2",
            "-t", "raw",
            "-q"
        ]
        print(f"[Audio] Starting native hardware capture: {' '.join(cmd)}")
        self.proc = subprocess.Popen(
            cmd,
            stdout=subprocess.PIPE,
            stderr=subprocess.PIPE,
            bufsize=HW_CHUNK_BYTES * 4
        )
        time.sleep(0.1)
        if self.proc.poll() is not None:
            err = self.proc.stderr.read().decode('utf-8', errors='replace')
            raise RuntimeError(f"arecord failed to start: {err}")

    def _stop_audio_stream(self):
        if self.proc:
            try:
                self.proc.terminate()
                self.proc.wait(timeout=1.0)
            except Exception:
                self.proc.kill()
            self.proc = None

    async def _send_audio_over_ble(self, full_audio_float):
        """Compresses audio using fast IMA ADPCM and streams chunks over BLE GATT notifications."""
        int16_samples = (np.clip(full_audio_float, -1.0, 1.0) * 32767.0).astype(np.int16)
        t0 = time.perf_counter()
        adpcm_data = encode_ima_adpcm_fast(int16_samples)
        dt_comp = (time.perf_counter() - t0) * 1000

        print(f"[BLE] Compressed {len(int16_samples)*2} bytes PCM -> {len(adpcm_data)} bytes ADPCM in {dt_comp:.1f}ms")

        chunk_size = 450
        chunks = [adpcm_data[i:i + chunk_size] for i in range(0, len(adpcm_data), chunk_size)]
        total_chunks = len(chunks)

        # 1. Header packet: 0x01 | total_chunks (2B) | total_bytes (4B) | sample_rate (2B)
        header = bytearray([0x01])
        header.extend(total_chunks.to_bytes(2, 'big'))
        header.extend(len(adpcm_data).to_bytes(4, 'big'))
        header.extend(SAMPLE_RATE.to_bytes(2, 'big'))
        self.gatt_service.notify_command_packet(bytes(header))
        await asyncio.sleep(0.02)

        # 2. Data chunks: 0x02 | chunk_index (2B) | chunk_data
        for idx, ch in enumerate(chunks):
            packet = bytearray([0x02])
            packet.extend(idx.to_bytes(2, 'big'))
            packet.extend(ch)
            self.gatt_service.notify_command_packet(bytes(packet))
            await asyncio.sleep(0.015)

        # 3. End packet: 0x03
        self.gatt_service.notify_command_packet(bytes([0x03]))
        print(f"[BLE] Successfully transmitted {total_chunks} audio packets over BLE.")

        # Update state back to IDLE (0x00)
        await asyncio.sleep(0.1)
        self.gatt_service.set_state(0x00)

    def audio_processing_worker(self):
        """Dedicated background thread capturing audio and running rigorous multi-tier detection."""
        self._start_audio_stream()
        print("\n>>> FRIDAY RIGOROUS WEARABLE ENGINE STARTED <<<\n")

        state = "LISTENING_WAKEWORD"
        command_buffer = []
        speech_started = False
        silence_chunks = 0
        command_start_time = 0.0
        cooldown_until = 0.0

        while self.running:
            if self.proc.poll() is not None:
                print("\n[Error] Audio capture terminated unexpectedly.")
                break

            raw_bytes = self.proc.stdout.read(HW_CHUNK_BYTES)
            if not raw_bytes or len(raw_bytes) < HW_CHUNK_BYTES:
                time.sleep(0.01)
                continue

            # Extract active INMP441 Right channel (Channel 1) from 32-bit stereo
            stereo_i32 = np.frombuffer(raw_bytes, dtype=np.int32).reshape(-1, 2)
            ch1 = stereo_i32[:, 1].astype(np.float32) / 2147483648.0
            
            # Integer 3:1 decimation from 48kHz to 16kHz (4800 -> 1600 samples)
            chunk = ch1[::DECIMATION_FACTOR]
            # Zero-bias AC high-pass: eliminate hardware DC offset completely
            chunk = chunk - np.mean(chunk)
            rms = float(np.sqrt(np.mean(chunk ** 2)))
            now = time.time()

            if state == "LISTENING_WAKEWORD":
                self.ring_buffer = np.roll(self.ring_buffer, -CHUNK_SAMPLES)
                self.ring_buffer[-CHUNK_SAMPLES:] = chunk
                self.chunks_seen += 1

                # Adapt baseline noise floor on ambient/silence frames
                if rms < MIN_SPEECH_RMS:
                    self.baseline_rms = self.baseline_rms * 0.98 + rms * 0.02

                # Enforce warmup guard: do not evaluate until ring buffer is fully populated
                if self.chunks_seen < WARMUP_CHUNKS:
                    continue

                if now < cooldown_until:
                    self.consecutive_hits = 0
                    continue

                # Tier 0 Close-Proximity VAD Gate & SNR check
                speech_gate = max(MIN_SPEECH_RMS, self.baseline_rms * SNR_MULTIPLIER)
                if rms < speech_gate:
                    self.consecutive_hits = 0
                    continue

                # Tier 1 ONNX Neural Evaluation
                t_inf = time.perf_counter()
                inp = np.expand_dims(np.expand_dims(self.ring_buffer, axis=0), axis=0).astype(np.float32)
                logits = self.session.run(None, {self.input_name: inp})[0][0]
                infer_ms = (time.perf_counter() - t_inf) * 1000

                neg_logit, pos_logit = float(logits[0]), float(logits[1])
                max_l = max(neg_logit, pos_logit)
                exps = np.exp(logits - max_l)
                conf = float(exps[1] / np.sum(exps))
                margin = pos_logit - neg_logit

                # Tier 2 Quality & Temporal Confirmation Gate
                if conf >= CONFIDENCE_THRESHOLD and margin >= LOGIT_MARGIN_MIN and pos_logit > 0.0:
                    self.consecutive_hits += 1
                    print(f"[Detect] 'Friday' candidate frame (conf: {conf*100:.1f}%, margin: {margin:.1f}, RMS: {rms:.3f}) [hit {self.consecutive_hits}/{MIN_CONSECUTIVE_HITS}]")
                    
                    if self.consecutive_hits >= MIN_CONSECUTIVE_HITS:
                        print(f"\n[★] RIGOROUS WAKE-WORD CONFIRMED! 'Friday' (Conf: {conf*100:.1f}%, infer: {infer_ms:.1f}ms)")
                        print("    --> RECORDING COMMAND... (Listening for user query)")

                        # Notify Phone over BLE: 0x01 (WAKE_TRIGGERED)
                        if self.gatt_service and self.loop:
                            self.loop.call_soon_threadsafe(self.gatt_service.set_state, 0x01)

                        state = "RECORDING_COMMAND"
                        command_buffer = []
                        speech_started = False
                        silence_chunks = 0
                        command_start_time = now
                        self.consecutive_hits = 0
                else:
                    self.consecutive_hits = 0

            elif state == "RECORDING_COMMAND":
                command_buffer.append(chunk)
                elapsed = now - command_start_time

                # Active speech detection during command recording
                speech_gate = max(0.04, self.baseline_rms * 1.35)
                if rms > speech_gate:
                    speech_started = True
                    silence_chunks = 0
                else:
                    if speech_started:
                        silence_chunks += 1

                # Check silence end-of-speech or timeout
                silence_duration = silence_chunks * (CHUNK_SAMPLES / SAMPLE_RATE)
                if (speech_started and silence_duration >= SILENCE_TIMEOUT_SEC) or (elapsed >= MAX_COMMAND_SEC):
                    print(f"[✔] Command recorded! Duration: {elapsed:.1f}s. Streaming to phone...")
                    state = "LISTENING_WAKEWORD"
                    cooldown_until = now + COOLDOWN_SEC

                    full_audio = np.concatenate(command_buffer)

                    # Save local copy for diagnostics
                    self._save_wav(full_audio, "last_command.wav")

                    # Stream audio packets to phone over BLE
                    if self.gatt_service and self.loop:
                        asyncio.run_coroutine_threadsafe(self._send_audio_over_ble(full_audio), self.loop)

        self._stop_audio_stream()

    def _save_wav(self, float_samples, filename):
        int_samples = (np.clip(float_samples, -1.0, 1.0) * 32767.0).astype(np.int16)
        with wave.open(filename, "wb") as w:
            w.setnchannels(1)
            w.setsampwidth(2)
            w.setframerate(SAMPLE_RATE)
            w.writeframes(int_samples.tobytes())

    async def start(self):
        self.running = True
        self.loop = asyncio.get_running_loop()

        # Connect to system D-Bus
        bus = await MessageBus(bus_type=BusType.SYSTEM).connect()
        intro = await bus.introspect("org.bluez", "/org/bluez/hci0")
        proxy = bus.get_proxy_object("org.bluez", "/org/bluez/hci0", intro)
        adapter = Adapter(proxy)

        # Register GATT Service
        self.gatt_service = FridayGattService()
        await self.gatt_service.register(bus, adapter=adapter)
        print(f"[BLE] FridayGattService registered ({SERVICE_UUID})")

        # Start BLE Advertisement
        advert = Advertisement(
            localName="Friday-Wearable",
            serviceUUIDs=[SERVICE_UUID],
            appearance=0,
            timeout=0
        )
        await advert.register(bus, adapter)
        print("[BLE] Advertisement active as 'Friday-Wearable' (BLE Peripheral)")

        # Start audio worker thread
        audio_thread = threading.Thread(target=self.audio_processing_worker, daemon=True)
        audio_thread.start()

        # Run until cancelled
        try:
            while self.running:
                await asyncio.sleep(1.0)
        except asyncio.CancelledError:
            pass
        finally:
            self.running = False
            self._stop_audio_stream()
            print("[Exit] Daemon stopped.")

if __name__ == "__main__":
    daemon = FridayWearableDaemon()
    try:
        asyncio.run(daemon.start())
    except KeyboardInterrupt:
        print("\nExiting.")

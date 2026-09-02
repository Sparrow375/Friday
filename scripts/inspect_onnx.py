import struct
import numpy as np
import os

with open("app/src/main/assets/wakeword.onnx", "rb") as f:
    raw_data = f.read()

print(f"Read wakeword.onnx: {len(raw_data)} bytes")
# Check if it has standard protobuf markers
print(f"Header bytes: {raw_data[:30]}")

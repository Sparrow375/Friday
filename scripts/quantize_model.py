import os
import onnx
from onnxruntime.quantization import quantize_dynamic, QuantType

input_model = "app/src/main/assets/wakeword.onnx"
output_model = "scripts/wakeword_int8.onnx"

print("Quantizing wakeword.onnx to INT8...")
quantize_dynamic(
    input_model,
    output_model,
    weight_type=QuantType.QInt8
)

orig_size = os.path.getsize(input_model)
quant_size = os.path.getsize(output_model)
print(f"Original FP32 Size: {orig_size / 1024:.1f} KB")
print(f"Quantized INT8 Size: {quant_size / 1024:.1f} KB (Shrunk by {(1 - quant_size/orig_size)*100:.1f}%)")

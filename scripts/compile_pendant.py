import subprocess
import shutil
import os

sketch_ino = r"F:\Avaneesh\projects\Friday\firmware\xiao_friday_pendant\xiao_friday_pendant.ino"
sketch_cpp = r"C:\Users\Avaneesh\AppData\Local\arduino\sketches\183D72687E44651266881E761012BE03\sketch\xiao_friday_pendant.ino.cpp"

# Copy ino with arduino pre-include to sketch_cpp
with open(sketch_ino, "r") as f:
    ino_code = f.read()

header = '#include <Arduino.h>\n#line 1 "F:\\\\Avaneesh\\\\projects\\\\Friday\\\\firmware\\\\xiao_friday_pendant\\\\xiao_friday_pendant.ino"\n'
with open(sketch_cpp, "w") as f:
    f.write(header + ino_code)

cmd = [
    r"C:\Users\Avaneesh\AppData\Local\Arduino15\packages\Seeeduino\tools\arm-none-eabi-gcc\9-2019q4/bin/arm-none-eabi-g++",
    "-mcpu=cortex-m4", "-mthumb", "-c", "-g", "-Werror=return-type", "-mfloat-abi=hard", "-mfpu=fpv4-sp-d16",
    "-u", "_printf_float", "-std=gnu++11", "-ffunction-sections", "-fdata-sections", "-fno-threadsafe-statics",
    "-nostdlib", "--param", "max-inline-insns-single=500", "-fno-rtti", "-fno-exceptions", "-MMD",
    r"-IF:\Avaneesh\projects\Friday\firmware\xiao_friday_pendant",
    "-DF_CPU=64000000", "-DARDUINO=10607", "-DARDUINO_Seeed_XIAO_nRF52840_Sense", "-DARDUINO_ARCH_NRF52",
    '-DARDUINO_BSP_VERSION="1.1.13"', "-DNRF52840_XXAA", "-DUSBCON", "-DUSE_TINYUSB",
    "-DUSB_VID=0x2886", "-DUSB_PID=0x8045", '-DUSB_MANUFACTURER="Seeed"', '-DUSB_PRODUCT="XIAO nRF52840 Sense"',
    "-DSOFTDEVICE_PRESENT", "-DARDUINO_NRF52_ADAFRUIT", "-DNRF52_SERIES", "-DDX_CC_TEE", "-DLFS_NAME_MAX=64",
    "-Ofast", "-DCFG_DEBUG=0", "-DCFG_LOGGER=0", "-DCFG_SYSVIEW=0",
    r"-IC:\Users\Avaneesh\AppData\Local\Arduino15\packages\Seeeduino\tools\CMSIS\5.7.0/CMSIS/Core/Include/",
    r"-IC:\Users\Avaneesh\AppData\Local\Arduino15\packages\Seeeduino\tools\CMSIS\5.7.0/CMSIS/DSP/Include/",
    r"-IC:\Users\Avaneesh\AppData\Local\Arduino15\packages\Seeeduino\hardware\nrf52\1.1.13\cores\nRF5/nordic",
    r"-IC:\Users\Avaneesh\AppData\Local\Arduino15\packages\Seeeduino\hardware\nrf52\1.1.13\cores\nRF5/nordic/nrfx",
    r"-IC:\Users\Avaneesh\AppData\Local\Arduino15\packages\Seeeduino\hardware\nrf52\1.1.13\cores\nRF5/nordic/nrfx/hal",
    r"-IC:\Users\Avaneesh\AppData\Local\Arduino15\packages\Seeeduino\hardware\nrf52\1.1.13\cores\nRF5/nordic/nrfx/mdk",
    r"-IC:\Users\Avaneesh\AppData\Local\Arduino15\packages\Seeeduino\hardware\nrf52\1.1.13\cores\nRF5/nordic/nrfx/soc",
    r"-IC:\Users\Avaneesh\AppData\Local\Arduino15\packages\Seeeduino\hardware\nrf52\1.1.13\cores\nRF5/nordic/nrfx/drivers/include",
    r"-IC:\Users\Avaneesh\AppData\Local\Arduino15\packages\Seeeduino\hardware\nrf52\1.1.13\cores\nRF5/nordic/nrfx/drivers/src",
    r"-IC:\Users\Avaneesh\AppData\Local\Arduino15\packages\Seeeduino\hardware\nrf52\1.1.13\cores\nRF5/nordic/softdevice/s140_nrf52_7.3.0_API/include",
    r"-IC:\Users\Avaneesh\AppData\Local\Arduino15\packages\Seeeduino\hardware\nrf52\1.1.13\cores\nRF5/nordic/softdevice/s140_nrf52_7.3.0_API/include/nrf52",
    r"-IC:\Users\Avaneesh\AppData\Local\Arduino15\packages\Seeeduino\hardware\nrf52\1.1.13\cores\nRF5/freertos/Source/include",
    r"-IC:\Users\Avaneesh\AppData\Local\Arduino15\packages\Seeeduino\hardware\nrf52\1.1.13\cores\nRF5/freertos/config",
    r"-IC:\Users\Avaneesh\AppData\Local\Arduino15\packages\Seeeduino\hardware\nrf52\1.1.13\cores\nRF5/freertos/portable/GCC/nrf52",
    r"-IC:\Users\Avaneesh\AppData\Local\Arduino15\packages\Seeeduino\hardware\nrf52\1.1.13\cores\nRF5/freertos/portable/CMSIS/nrf52",
    r"-IC:\Users\Avaneesh\AppData\Local\Arduino15\packages\Seeeduino\hardware\nrf52\1.1.13\cores\nRF5/sysview/SEGGER",
    r"-IC:\Users\Avaneesh\AppData\Local\Arduino15\packages\Seeeduino\hardware\nrf52\1.1.13\cores\nRF5/sysview/Config",
    r"-IC:\Users\Avaneesh\AppData\Local\Arduino15\packages\Seeeduino\hardware\nrf52\1.1.13/libraries/Adafruit_TinyUSB_Arduino/src/arduino",
    r"-IC:\Users\Avaneesh\AppData\Local\Arduino15\packages\Seeeduino\hardware\nrf52\1.1.13\cores\nRF5",
    r"-IC:\Users\Avaneesh\AppData\Local\Arduino15\packages\Seeeduino\hardware\nrf52\1.1.13\variants\Seeed_XIAO_nRF52840_Sense",
    r"-IC:\Users\Avaneesh\AppData\Local\Arduino15\packages\Seeeduino\hardware\nrf52\1.1.13\libraries\Bluefruit52Lib\src",
    r"-IC:\Users\Avaneesh\AppData\Local\Arduino15\packages\Seeeduino\hardware\nrf52\1.1.13\libraries\Adafruit_nRFCrypto\src",
    r"-IC:\Users\Avaneesh\AppData\Local\Arduino15\packages\Seeeduino\hardware\nrf52\1.1.13\libraries\Adafruit_TinyUSB_Arduino\src",
    r"-IC:\Users\Avaneesh\AppData\Local\Arduino15\packages\Seeeduino\hardware\nrf52\1.1.13\libraries\PDM\src",
    r"-IC:\Users\Avaneesh\AppData\Local\Arduino15\packages\Seeeduino\hardware\nrf52\1.1.13\libraries\Adafruit_LittleFS\src",
    r"-IC:\Users\Avaneesh\AppData\Local\Arduino15\packages\Seeeduino\hardware\nrf52\1.1.13\libraries\InternalFileSytem\src",
    sketch_cpp,
    "-o",
    sketch_cpp + ".o"
]

res = subprocess.run(cmd, capture_output=True, text=True)
print("Return code:", res.returncode)
if res.stdout:
    print("STDOUT:", res.stdout)
if res.stderr:
    print("STDERR:", res.stderr)
if res.returncode == 0:
    print("SUCCESS: Sketch compiled cleanly with 0 errors!")

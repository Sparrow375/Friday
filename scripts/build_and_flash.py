#!/usr/bin/env python3
"""
Project Friday - Complete Pendant Firmware Builder & Flasher
Compiles the Arduino sketch, links with Nordic SoftDevice S140 & FreeRTOS/Bluefruit libraries,
generates the DFU package, and flashes to Seeed Studio XIAO nRF52840 Sense on COM11.
"""

import subprocess
import shutil
import sys
import os
import time
from pathlib import Path

# Paths
SKETCH_INO = Path(r"F:\Avaneesh\projects\Friday\firmware\xiao_friday_pendant\xiao_friday_pendant.ino")
HEADER_H = Path(r"F:\Avaneesh\projects\Friday\firmware\xiao_friday_pendant\wakeword_model.h")
BUILD_DIR = Path(r"C:\Users\Avaneesh\AppData\Local\arduino\sketches\183D72687E44651266881E761012BE03")

SKETCH_CPP = BUILD_DIR / "sketch" / "xiao_friday_pendant.ino.cpp"
CACHED_H = BUILD_DIR / "sketch" / "wakeword_model.h"
SKETCH_OBJ = BUILD_DIR / "sketch" / "xiao_friday_pendant.ino.cpp.o"

GCC_BIN = Path(r"C:\Users\Avaneesh\AppData\Local\Arduino15\packages\Seeeduino\tools\arm-none-eabi-gcc\9-2019q4\bin")
GPP = str(GCC_BIN / "arm-none-eabi-g++")
OBJCOPY = str(GCC_BIN / "arm-none-eabi-objcopy")
SIZE_TOOL = str(GCC_BIN / "arm-none-eabi-size")

NRFUTIL = Path(r"C:\Users\Avaneesh\AppData\Local\Arduino15\packages\Seeeduino\hardware\nrf52\1.1.13\tools\adafruit-nrfutil\win32\adafruit-nrfutil.exe")
CORE_DIR = Path(r"C:\Users\Avaneesh\AppData\Local\Arduino15\packages\Seeeduino\hardware\nrf52\1.1.13\cores\nRF5")
CMSIS_DSP = Path(r"C:\Users\Avaneesh\AppData\Local\Arduino15\packages\Seeeduino\tools\CMSIS\5.7.0\CMSIS\DSP\Lib\GCC")
CRYPTO_LIB = Path(r"C:\Users\Avaneesh\AppData\Local\Arduino15\packages\Seeeduino\hardware\nrf52\1.1.13\libraries\Adafruit_nRFCrypto\src\cortex-m4\fpv4-sp-d16-hard")

PORT = sys.argv[1] if len(sys.argv) > 1 else "COM11"

def compile_sketch():
    print("[1/4] Compiling xiao_friday_pendant.ino...", flush=True)
    # Sync header and ino
    with open(SKETCH_INO, "r", encoding="utf-8") as f:
        ino_code = f.read()

    header_line = '#include <Arduino.h>\n#line 1 "F:\\\\Avaneesh\\\\projects\\\\Friday\\\\firmware\\\\xiao_friday_pendant\\\\xiao_friday_pendant.ino"\n'
    with open(SKETCH_CPP, "w", encoding="utf-8") as f:
        f.write(header_line + ino_code)

    shutil.copy2(HEADER_H, CACHED_H)

    cmd = [
        GPP,
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
        str(SKETCH_CPP),
        "-o",
        str(SKETCH_OBJ)
    ]
    res = subprocess.run(cmd, capture_output=True, text=True)
    if res.returncode != 0:
        print("[!] Compilation Failed:\n", res.stderr)
        sys.exit(1)
    print("    [✓] Object compiled successfully.", flush=True)

def link_elf():
    print("[2/4] Linking firmware ELF binary...", flush=True)
    objs = [str(p) for p in BUILD_DIR.rglob("*.o")]
    elf = str(BUILD_DIR / "xiao_friday_pendant.ino.elf")
    map_f = str(BUILD_DIR / "xiao_friday_pendant.ino.map")
    linker_dir = str(CORE_DIR / "linker")

    cmd = [
        GPP,
        f"-L{BUILD_DIR}",
        "-Ofast", "-Wl,--gc-sections",
        f"-L{linker_dir}",
        "-Tnrf52840_s140_v7.ld",
        f"-Wl,-Map,{map_f}",
        "-mcpu=cortex-m4", "-mthumb", "-mfloat-abi=hard", "-mfpu=fpv4-sp-d16",
        "-Wl,--cref", "-Wl,--check-sections", "-Wl,--gc-sections",
        "-Wl,--unresolved-symbols=report-all", "-Wl,--warn-common", "-Wl,--warn-section-align",
        "-Wl,--wrap=malloc", "-Wl,--wrap=free",
        "--specs=nano.specs", "--specs=nosys.specs",
        "-o", elf,
        *objs,
        "-Wl,--start-group",
        f"-L{CMSIS_DSP}", "-larm_cortexM4lf_math",
        "-lm",
        f"-L{CRYPTO_LIB}", "-lnrf_cc310_0.9.13-no-interrupts",
        "-Wl,--end-group"
    ]
    res = subprocess.run(cmd, capture_output=True, text=True)
    if res.returncode != 0:
        print("[!] Linking Failed:\n", res.stderr)
        sys.exit(1)

    # Print memory usage
    size_res = subprocess.run([SIZE_TOOL, "-A", elf], capture_output=True, text=True)
    print("    [✓] Link Successful! Memory footprint:\n" + size_res.stdout.strip())

def generate_packages():
    print("[3/4] Generating HEX and DFU ZIP packages...", flush=True)
    elf = str(BUILD_DIR / "xiao_friday_pendant.ino.elf")
    hex_f = str(BUILD_DIR / "xiao_friday_pendant.ino.hex")
    zip_f = str(BUILD_DIR / "xiao_friday_pendant.ino.zip")

    # objcopy to hex
    cmd_hex = [OBJCOPY, "-O", "ihex", elf, hex_f]
    res_hex = subprocess.run(cmd_hex, capture_output=True, text=True)
    if res_hex.returncode != 0:
        print("[!] objcopy to hex failed:\n", res_hex.stderr)
        sys.exit(1)

    # adafruit-nrfutil to DFU zip
    cmd_zip = [str(NRFUTIL), "dfu", "genpkg", "--dev-type", "0x0052", "--sd-req", "0x0123", "--application", hex_f, zip_f]
    res_zip = subprocess.run(cmd_zip, capture_output=True, text=True)
    if res_zip.returncode != 0:
        print("[!] adafruit-nrfutil genpkg failed:\n", res_zip.stderr)
        sys.exit(1)
    print("    [✓] Generated:", zip_f, flush=True)

def flash_pendant(port):
    print(f"[4/4] Flashing to Seeed Studio XIAO nRF52840 Sense on {port}...", flush=True)
    zip_f = str(BUILD_DIR / "xiao_friday_pendant.ino.zip")

    # Touch port at 1200 baud to reset into DFU bootloader if required
    try:
        import serial
        print(f"    Resetting board into bootloader via 1200-baud touch on {port}...")
        ser = serial.Serial(port, 1200)
        ser.close()
        time.sleep(1.5)
    except Exception as e:
        print(f"    (Port touch note: {e})")

    # Upload via adafruit-nrfutil
    cmd_upload = [str(NRFUTIL), "--verbose", "dfu", "serial", "-pkg", zip_f, "-p", port, "-b", "115200", "--singlebank"]
    res_upload = subprocess.run(cmd_upload, capture_output=True, text=True)
    if res_upload.returncode != 0:
        print("[!] Upload output:\n", res_upload.stdout, res_upload.stderr)
        # Retry once after 2 seconds
        print("    Retrying upload...")
        time.sleep(2)
        res_upload = subprocess.run(cmd_upload, capture_output=True, text=True)
        if res_upload.returncode != 0:
            print("[!] Upload Failed:\n", res_upload.stdout, res_upload.stderr)
            sys.exit(1)

    print("    [✓] FLASH SUCCESSFUL! Board rebooted with new wake-word firmware.")

if __name__ == "__main__":
    compile_sketch()
    link_elf()
    generate_packages()
    flash_pendant(PORT)

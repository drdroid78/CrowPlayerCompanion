"""Verify both LVGL draw buffers reside in internal DMA-capable ESP32-S3 RAM."""
import re
from pathlib import Path
text = Path("build/panel/CrowPlayer.ino.map").read_text()
for name in ("drawBufferA", "drawBufferB"):
    match = re.search(r"\.bss\._ZL11" + name + r"\s+(0x[0-9a-fA-F]+)\s+(0x[0-9a-fA-F]+)", text)
    if not match:
        match = re.search(r"\.(?:bss|dram1)\._ZL11" + name + r"\s+(0x[0-9a-fA-F]+)\s+(0x[0-9a-fA-F]+)", text)
    assert match, f"{name} not found in linker map"
    address, size = (int(v, 16) for v in match.groups())
    assert address % 4 == 0, f"{name} misaligned: {address:#x}"
    assert 0x3FC80000 <= address < 0x3FD00000, f"{name} is not in internal RAM: {address:#x}"
    assert size == 240 * 40 * 2, f"Unexpected size: {size}"
    print(f"{name}: {address:#x}, {size} bytes")

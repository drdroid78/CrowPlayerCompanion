"""Check the actual ESP32 link address, not a host compiler's incidental alignment."""
import re
from pathlib import Path
text = Path('build/panel/CrowPlayer.ino.map').read_text()
match = re.search(r'\.bss\._ZL10drawBuffer\s+(0x[0-9a-fA-F]+)\s+(0x[0-9a-fA-F]+)', text)
assert match, 'Display buffer not found in ESP32 linker map'
address, size = (int(v, 16) for v in match.groups())
assert address % 4 == 0, f'LVGL RGB565 buffer is misaligned: {address:#x}'
assert size == 240 * 40 * 2, f'Unexpected buffer size: {size}'
print(f'ESP32 display buffer alignment verified: {address:#x}, {size} bytes')


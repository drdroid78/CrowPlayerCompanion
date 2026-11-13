#!/usr/bin/env bash
set -euo pipefail
mkdir -p firmware/CrowPlayer/src/fonts
for size in 14 16 44; do
  npx --yes lv_font_conv@1.5.3 --font /usr/share/fonts/truetype/dejavu/DejaVuSans.ttf --size "$size" --bpp 4 --format lvgl --no-compress --lv-include lvgl.h --range 0x20-0x7e,0x160-0x161,0x400-0x4ff,0x2661,0x2665,0x266b --symbols '·…—' -o "firmware/CrowPlayer/src/fonts/cp_font_${size}.c"
done


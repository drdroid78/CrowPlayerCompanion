#!/usr/bin/env bash
set -euo pipefail
mkdir -p firmware/CrowPlayer/src/fonts
for size in 10 14 16 18 24 28 44; do
  npx --yes lv_font_conv@1.5.3 --font /usr/share/fonts/truetype/dejavu/DejaVuSans.ttf --size "$size" --bpp 4 --format lvgl --no-compress --lv-include lvgl.h --range 0x20-0x7e,0x160-0x161,0x400-0x4ff,0x2661,0x2665,0x266b --symbols '·…—' -o "firmware/CrowPlayer/src/fonts/cp_font_${size}.c"
done
npx --yes lv_font_conv@1.5.3 --font /usr/share/fonts/truetype/dejavu/DejaVuSans-Bold.ttf --size 18 --bpp 4 --format lvgl --no-compress --lv-include lvgl.h --range 0x20-0x7e,0x160-0x161,0x400-0x4ff -o firmware/CrowPlayer/src/fonts/cp_font_bold_18.c


# Vendored Inter (SIL OFL), with DejaVu fallback for music symbols.
for size in 10 14 16 18 24 28 44 64; do
  npx --yes lv_font_conv@1.5.3 --font firmware/assets/fonts/Inter-Regular.ttf --range 0x20-0x7e,0x160-0x161,0x400-0x4ff --symbols '·…—' --font /usr/share/fonts/truetype/dejavu/DejaVuSans.ttf --symbols '♡♥♫' --size "$size" --bpp 4 --format lvgl --no-compress --lv-include lvgl.h -o "firmware/CrowPlayer/src/fonts/cp_inter_$size.c"
done
npx --yes lv_font_conv@1.5.3 --font firmware/assets/fonts/Inter-SemiBold.ttf --range 0x20-0x7e,0x160-0x161,0x400-0x4ff --symbols '·…—' --size 18 --bpp 4 --format lvgl --no-compress --lv-include lvgl.h -o firmware/CrowPlayer/src/fonts/cp_inter_semibold_18.c

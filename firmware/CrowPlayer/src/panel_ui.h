#pragma once
#include <lvgl.h>
#include "panel_core.h"
LV_FONT_DECLARE(cp_font_12);
LV_FONT_DECLARE(cp_font_16);
LV_FONT_DECLARE(cp_font_44);
namespace cp {
struct Theme {const char*name;uint32_t rgb;};
extern const Theme themes[8];
void render(const Model&m,uint32_t now);
}

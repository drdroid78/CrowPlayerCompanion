#pragma once
#include <lvgl.h>
#include "panel_core.h"
LV_FONT_DECLARE(cp_font_14);
LV_FONT_DECLARE(cp_font_10);
LV_FONT_DECLARE(cp_font_16);
LV_FONT_DECLARE(cp_font_18);
LV_FONT_DECLARE(cp_font_24);
LV_FONT_DECLARE(cp_font_28);
LV_FONT_DECLARE(cp_font_44);
LV_FONT_DECLARE(cp_font_bold_18);
LV_FONT_DECLARE(cp_inter_10);
LV_FONT_DECLARE(cp_inter_14);
LV_FONT_DECLARE(cp_inter_16);
LV_FONT_DECLARE(cp_inter_18);
LV_FONT_DECLARE(cp_inter_24);
LV_FONT_DECLARE(cp_inter_28);
LV_FONT_DECLARE(cp_inter_44);
LV_FONT_DECLARE(cp_inter_64);
LV_FONT_DECLARE(cp_inter_semibold_18);
namespace cp {
struct Theme {const char*name;uint32_t rgb;};
extern const Theme themes[THEME_COUNT];
uint32_t uiAllocations();
void resetUI();
void render(const Model&m,uint32_t now);
}


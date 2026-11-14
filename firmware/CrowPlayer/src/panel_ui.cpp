// Copyright (c) 2026 DrDroid78. Author: DrDroid78.
#include "panel_ui.h"
#include <math.h>
namespace cp {
const Theme themes[8]={{"Tornado Red",0xC81A26},{"Lapiz Blue",0x1F5FA8},{"Reflex Silver",0xC8C8C6},{"Indium Grey",0x8A8D90},{"Urano Grey",0x5B5D60},{"Atlantic Blue",0x0F3D68},{"Pure White",0xF5F5F5},{"Škoda Green",0x4BA82E}};
static lv_obj_t*root;static uint32_t accent;
static lv_color_t col(uint32_t x){return lv_color_hex(x);}
static lv_image_dsc_t coverImage;
static void cover(const Model&m){if(!m.artworkPresent||!m.artwork)return;coverImage={};coverImage.header.magic=LV_IMAGE_HEADER_MAGIC;coverImage.header.cf=LV_COLOR_FORMAT_RGB565;coverImage.header.w=240;coverImage.header.h=240;coverImage.header.stride=240*2;coverImage.data_size=240*240*2;coverImage.data=m.artwork;auto*img=lv_image_create(root);lv_image_set_src(img,&coverImage);lv_image_set_scale(img,256);lv_obj_set_pos(img,0,0);}
static lv_obj_t* label(const char*s,int x,int y,int w,const lv_font_t*f=&cp_font_14,uint32_t c=0xF5F5F5){
  auto*l=lv_label_create(root);lv_obj_set_size(l,w,f->line_height);lv_obj_set_pos(l,x,y);lv_obj_set_style_text_font(l,f,0);lv_obj_set_style_text_color(l,col(c),0);lv_obj_set_style_text_align(l,LV_TEXT_ALIGN_CENTER,0);lv_label_set_long_mode(l,LV_LABEL_LONG_DOT);lv_label_set_text(l,s);return l;
}
static lv_obj_t* leftLabel(const char*s,int x,int y,int w,const lv_font_t*f,uint32_t c){
  auto*l=lv_label_create(root);lv_obj_set_size(l,w,f->line_height);lv_obj_set_pos(l,x,y);lv_obj_set_style_text_font(l,f,0);lv_obj_set_style_text_color(l,col(c),0);lv_obj_set_style_text_align(l,LV_TEXT_ALIGN_LEFT,0);lv_label_set_long_mode(l,LV_LABEL_LONG_DOT);lv_label_set_text(l,s);return l;
}
static void disc(int x,int y,int r,uint32_t c){auto*o=lv_obj_create(root);lv_obj_remove_style_all(o);lv_obj_set_pos(o,x-r,y-r);lv_obj_set_size(o,2*r,2*r);lv_obj_set_style_radius(o,LV_RADIUS_CIRCLE,0);lv_obj_set_style_bg_color(o,col(c),0);lv_obj_set_style_bg_opa(o,LV_OPA_COVER,0);}
static void ring(int x,int y,int r,int start,int end,uint32_t c,int width){
  auto*a=lv_arc_create(root);lv_obj_remove_style_all(a);lv_obj_set_pos(a,x-r,y-r);lv_obj_set_size(a,r*2,r*2);lv_arc_set_bg_angles(a,start,end);lv_obj_set_style_arc_width(a,width,LV_PART_MAIN);lv_obj_set_style_arc_color(a,col(c),LV_PART_MAIN);lv_obj_set_style_arc_rounded(a,true,LV_PART_MAIN);lv_obj_set_style_arc_opa(a,LV_OPA_TRANSP,LV_PART_INDICATOR);lv_obj_remove_flag(a,LV_OBJ_FLAG_CLICKABLE);
}
static lv_point_precise_t iconLines[192][2];static uint8_t iconLineCount=0;
static void line(int x1,int y1,int x2,int y2,uint32_t color=0xF5F5F5,int width=2){if(iconLineCount>=192)return;auto&pts=iconLines[iconLineCount++];pts[0]={lv_value_precise_t(x1),lv_value_precise_t(y1)};pts[1]={lv_value_precise_t(x2),lv_value_precise_t(y2)};auto*l=lv_line_create(root);lv_line_set_points(l,pts,2);lv_obj_set_style_line_color(l,col(color),0);lv_obj_set_style_line_width(l,width,0);lv_obj_set_style_line_rounded(l,true,0);lv_obj_remove_flag(l,LV_OBJ_FLAG_CLICKABLE);}
static void volumeRing(int percent,uint32_t color){
  percent=clamp(percent,0,100);
  constexpr int start=135,sweep=270,radius=104,width=7;
  ring(120,120,radius,start,start+sweep,0x252C35,width);
  if(percent>0)ring(120,120,radius,start,start+std::max(2,percent*sweep/100),color,width);
}
static lv_obj_t* outlineCircle(int x,int y,int d,uint32_t color){auto*o=lv_obj_create(root);lv_obj_remove_style_all(o);lv_obj_set_pos(o,x-d/2,y-d/2);lv_obj_set_size(o,d,d);lv_obj_set_style_radius(o,LV_RADIUS_CIRCLE,0);lv_obj_set_style_bg_opa(o,LV_OPA_TRANSP,0);lv_obj_set_style_border_width(o,1,0);lv_obj_set_style_border_color(o,col(color),0);return o;}
static void selectedRow(int y){auto*o=lv_obj_create(root);lv_obj_remove_style_all(o);lv_obj_set_pos(o,14,y-18);lv_obj_set_size(o,212,36);lv_obj_set_style_radius(o,7,0);lv_obj_set_style_bg_opa(o,LV_OPA_TRANSP,0);lv_obj_set_style_border_width(o,1,0);lv_obj_set_style_border_color(o,col(accent),0);lv_obj_remove_flag(o,LV_OBJ_FLAG_CLICKABLE|LV_OBJ_FLAG_SCROLLABLE);}
static void drawSourceIcon(int source,int x,int y,uint32_t c=0xF5F5F5,float scale=1.0f){
  auto px=[&](int v){return int(lroundf(v*scale));};
  auto L=[&](int x1,int y1,int x2,int y2){line(x+px(x1),y+px(y1),x+px(x2),y+px(y2),c,2);};
  switch(source){
    case 0: // Optical source: circle and three rays, as in the approved design.
      outlineCircle(x+px(-10),y,px(10),c);L(-2,0,16,0);L(-1,-3,11,-10);L(-1,3,11,10);break;
    case 4: // AUX IN: 3.5 mm plug
      L(-20,0,-12,0);L(-12,-6,6,-6);L(6,-6,6,6);L(6,6,-12,6);L(-12,6,-12,-6);L(6,-4,13,-4);L(13,-4,18,0);L(18,0,13,4);L(13,4,6,4);L(10,-4,10,4);break;
    case 5: { // HIGH: two stacked wires, circled minus left and plus right
      L(-13,-5,14,-5);L(-14,5,13,5);outlineCircle(x+px(-18),y+px(-5),px(10),c);outlineCircle(x+px(18),y+px(5),px(10),c);L(-20,-5,-16,-5);L(16,5,20,5);L(18,3,18,7);break;}
    case 2: // Bluetooth rune
      L(0,-14,0,14);L(0,-14,7,-7);L(7,-7,-7,7);L(-7,-7,7,7);L(7,7,0,14);break;
    case 6: // Horizontal USB symbol: arrow, square and circular terminals.
      L(-15,0,14,0);disc(x+px(-16),y,std::max(2,px(3)),c);L(14,0,10,-3);L(14,0,10,3);
      L(-8,0,-4,-6);L(-4,-6,1,-6);disc(x+px(1),y+px(-6),std::max(1,px(2)),c);
      L(-5,0,-1,6);L(-1,6,5,6);disc(x+px(5),y+px(6),std::max(1,px(2)),c);break;
    default: break;
  }
}
static void nav(int screen){for(int i=0;i<6;i++)disc(95+i*10,224,i==screen?3:2,i==screen?accent:0x464950);}
static void timeText(char*out,uint32_t ms){snprintf(out,12,"%u:%02u",unsigned(ms/60000),unsigned(ms/1000%60));}
static void heart(bool liked){label(liked?"♥":"♡",169,27,30,&cp_font_18,liked?accent:0xF5F5F5);}
static void settingsText(const Model&m,int key,char*out,size_t n){auto&s=m.settings;switch(key){case 0:snprintf(out,n,"%02u:00",s.dayHour);break;case 1:snprintf(out,n,"%02u:00",s.nightHour);break;case 2:snprintf(out,n,"%u%%",s.dayLight);break;case 3:snprintf(out,n,"%u%%",s.nightLight);break;case 4:snprintf(out,n,"%u%%",s.dayLed);break;case 5:snprintf(out,n,"%u%%",s.nightLed);break;case 6:if(s.idleSeconds)snprintf(out,n,"%u с",s.idleSeconds);else snprintf(out,n,"Выкл");break;case 7:snprintf(out,n,"%s",s.tabletVolume?"Планшет":"Усилитель");break;}}
static int wheelY(int offset){const float angle=float(offset)*25.0f*3.14159265f/180.0f;return int(lroundf(120.0f+100.0f*sinf(angle)));}
static int wheelIconX(int offset){const float angle=float(offset)*25.0f*3.14159265f/180.0f;return int(lroundf(125.0f-100.0f*cosf(angle)));}
static void settingIcon(int key,int x,int y,bool selected){uint32_t bg=0x050609;uint32_t ink=selected?0xF5F5F5:0xB8C0CA;
  if(key==6){outlineCircle(x,y,16,ink);line(x,y,x,y-5,ink,2);line(x,y,x+4,y+2,ink,2);}
  else if(key==0){ring(x,y+4,6,180,360,ink,2);line(x-10,y+5,x+10,y+5,ink,2);line(x,y-10,x,y-8,ink,2);line(x-8,y-6,x-6,y-4,ink,2);line(x+8,y-6,x+6,y-4,ink,2);}
  else if(key==1){disc(x-2,y,8,ink);disc(x+3,y-4,8,bg);}
  else if(key==2||key==4){disc(x,y,3,ink);line(x,y-9,x,y-6,ink,2);line(x,y+6,x,y+9,ink,2);line(x-9,y,x-6,y,ink,2);line(x+6,y,x+9,y,ink,2);line(x-7,y-7,x-5,y-5,ink,2);line(x+5,y+5,x+7,y+7,ink,2);line(x+5,y-5,x+7,y-7,ink,2);line(x-7,y+7,x-5,y+5,ink,2);}
  else if(key==3||key==5){disc(x-1,y,7,ink);disc(x+4,y-3,7,bg);}
  else if(key==7){line(x-7,y-3,x-4,y-3,ink,2);line(x-4,y-3,x,y-6,ink,2);line(x,y-6,x,y+6,ink,2);line(x,y+6,x-4,y+3,ink,2);line(x-4,y+3,x-7,y+3,ink,2);line(x-7,y+3,x-7,y-3,ink,2);line(x+3,y-4,x+5,y-2,ink,2);line(x+5,y-2,x+5,y+2,ink,2);line(x+5,y+2,x+3,y+4,ink,2);}
  else{disc(x,y,4,ink);}
}
void render(const Model&m,uint32_t now){
  if(!root){root=lv_obj_create(nullptr);lv_obj_remove_style_all(root);lv_obj_set_size(root,240,240);lv_obj_set_style_bg_color(root,col(0x050609),0);lv_obj_set_style_bg_opa(root,LV_OPA_COVER,0);lv_obj_remove_flag(root,LV_OBJ_FLAG_SCROLLABLE);lv_screen_load(root);}lv_obj_clean(root);iconLineCount=0;accent=themes[m.settings.theme].rgb;
  char b[160];bool ham=m.ham.online(now);
  if(m.screen==0){
    ring(120,120,103,135,405,0x252830,3);int minute=m.clock.minute();if(m.clock.epoch)ring(120,120,103,135,135+std::max(1,minute*270/1440),accent,4);
    m.clock.text(b);label(b,20,91,200,&cp_font_44);
    if(m.usb)label("USB",40,170,160,&cp_font_14,accent);
  }else if(m.screen==1){
    cover(m);
    auto*shade=lv_obj_create(root);lv_obj_remove_style_all(shade);lv_obj_set_pos(shade,0,128);lv_obj_set_size(shade,240,112);lv_obj_set_style_bg_color(shade,col(0x050609),0);lv_obj_set_style_bg_opa(shade,210,0);lv_obj_remove_flag(shade,LV_OBJ_FLAG_SCROLLABLE);
    if(!m.artworkPresent){ring(120,89,46,0,359,accent,2);label("♫",83,56,74,&cp_font_44,accent);}
    if(m.ham.source>=0)drawSourceIcon(m.ham.source,48,36,0xF5F5F5,0.65f);
    heart(m.liked&&m.likeKnown);if(m.likePending)label("…",188,27,30,&cp_font_14,accent);
    uint32_t pos=m.position;if(m.playing&&m.usb)pos+=now-m.positionAt;pos=std::min(pos,m.duration);
    leftLabel(m.title,35,139,150,&cp_font_16,0xF5F5F5);
    leftLabel(m.artist,35,159,150,&cp_font_14,0xA5A9AF);
    char p[12],d[12];timeText(p,pos);timeText(d,m.duration);label(p,22,182,40,&cp_font_14,0xF5F5F5);label(d,178,182,40,&cp_font_14,0xF5F5F5);
    line(65,191,175,191,0x303743,3);if(m.duration)line(65,191,65+int(uint64_t(pos)*110/m.duration),191,accent,3);
    if(m.playing){line(192,146,192,158,0xF5F5F5,3);line(198,146,198,158,0xF5F5F5,3);}else{line(192,146,200,152,0xF5F5F5,3);line(200,152,192,158,0xF5F5F5,3);line(192,158,192,146,0xF5F5F5,3);}
  }else if(m.screen==2){
    label("Усилитель",40,18,160,&cp_font_bold_18);ring(76,102,45,135,405,0x35404A,3);if(ham&&m.ham.volume>=0)ring(76,102,45,135,135+std::max(1,m.ham.volume*270/66),accent,4);
    if(ham&&m.ham.volume>=0)snprintf(b,sizeof b,"%d",m.ham.volume);else strcpy(b,"--");label(b,34,77,84,&cp_font_44);label("Громкость",27,149,99,&cp_font_14,m.subSelected?0xA5A9AF:0xF5F5F5);
    ring(171,102,37,135,405,0x35404A,3);if(ham&&m.ham.sub!=-128){ring(171,102,37,135,135+std::max(1,clamp(int(m.ham.sub)+60,0,60)*270/60),accent,4);snprintf(b,sizeof b,"%d",m.ham.sub);}else strcpy(b,"--");label(b,136,86,70,&cp_font_28);label("Саб",143,149,56,&cp_font_14,m.subSelected?accent:0xF5F5F5);
    if(ham&&m.ham.mute>=0){uint32_t mc=m.ham.mute==1?accent:0xD0D2D5;line(48,184,53,184,mc);line(53,184,59,179,mc);line(59,179,59,193,mc);line(59,193,53,188,mc);line(53,188,48,188,mc);line(48,188,48,184,mc);line(62,181,65,184,mc);line(65,184,65,188,mc);line(65,188,62,191,mc);label(m.ham.mute==1?"Mute · вкл":"Mute · выкл",70,177,120,&cp_font_14,mc);}
  }else if(m.screen==3){
    // Original radial selector: the amplifier's advertised order determines
    // clockwise positions; the center always shows the active input.
    ring(120,118,35,0,359,ham&&m.ham.source>=0?accent:0x41454D,2);
    if(ham&&m.ham.source>=0)drawSourceIcon(m.ham.source,120,101,0xF5F5F5,0.48f);
    label(ham&&m.ham.source>=0?sourceName(m.ham.source):"--",91,115,58,&cp_font_14);
    if(ham&&m.ham.source>=0)label("Активен",92,131,56,&cp_font_10,accent);
    const int count=sourceCount(m.ham);
    for(int i=0;i<count;i++){
      const int source=sourceAt(m.ham,i);int x,y;sourcePositionForId(m.ham,i,x,y);
      const bool active=ham&&source==m.ham.source,selected=source==m.sourceSelection;
      if(selected&&!active)ring(x,y,23,0,359,accent,2);
      drawSourceIcon(source,x,y-5,active?accent:0xF5F5F5,0.78f);
      const char*name=sourceName(source);int w=!strcmp(name,"AUX IN")?58:42;
      label(name,x-w/2,y+12,w,&cp_font_14,active?accent:0xF5F5F5);
    }
  }else if(m.screen==4){
    static const char* names[]={"День с","Ночь с","Ярк. дня","Ярк. ночи","LED днём","LED ночью","Тайм-аут","Громкость"};
    label("Настройки",40,14,160,&cp_font_bold_18);
    for(int offset=-1;offset<=1;offset++){
      int key=(m.setting+offset+8)%8,y=120+offset*51,iconX=offset?42:30;
      bool selected=offset==0;uint32_t textColor=selected?0xF5F5F5:0x9399A2;
      if(selected)selectedRow(y);
      settingIcon(key,iconX,y,selected);
      int valueWidth=key==7?(selected?80:58):50,valueX=222-valueWidth,labelX=iconX+19;
      const lv_font_t* rowFont=key==7?&cp_font_14:selected?&cp_font_18:&cp_font_16;
      leftLabel(names[key],labelX,y-(selected&&key!=7?8:7),valueX-labelX-3,rowFont,textColor);
      settingsText(m,key,b,sizeof b);
      label(b,valueX,y-7,valueWidth,key==7&&!selected?&cp_font_10:&cp_font_14,selected?0xF5F5F5:0xAEB4BD);
    }
  }else{
    label("Тема",40,15,160,&cp_font_bold_18);
    for(int offset=-2;offset<=2;offset++){
      int index=(int(m.settings.theme)+offset+8)%8,y=wheelY(offset),iconX=wheelIconX(offset);
      bool selected=offset==0;uint32_t textColor=selected?0xF5F5F5:offset==0?0xC1C5CC:0xA0A5AD;
      if(selected)selectedRow(y);
      disc(iconX,y,selected?8:7,themes[index].rgb);
      leftLabel(themes[index].name,iconX+19,y-9,240-iconX-38,selected?&cp_font_18:&cp_font_16,textColor);
    }
  }
  if(m.screen==0)nav(m.screen);
  if(m.volumeOverlay){
    auto*shade=lv_obj_create(root);lv_obj_remove_style_all(shade);lv_obj_set_pos(shade,0,0);lv_obj_set_size(shade,240,240);lv_obj_set_style_bg_color(shade,col(0x050609),0);lv_obj_set_style_bg_opa(shade,LV_OPA_COVER,0);lv_obj_remove_flag(shade,LV_OBJ_FLAG_SCROLLABLE);
    char value[16];int current=m.volumeOverlayValue;
    volumeRing(m.volumeOverlayTick*100/51,accent);
    if(m.settings.tabletVolume){
      line(86,104,94,104,0xF5F5F5,3);line(94,104,107,94,0xF5F5F5,3);line(107,94,107,130,0xF5F5F5,3);line(107,130,94,120,0xF5F5F5,3);line(94,120,86,120,0xF5F5F5,3);line(86,120,86,104,0xF5F5F5,3);
      int tip=m.volumeOverlayDirection>0?96:128,base=m.volumeOverlayDirection>0?128:96;line(139,base,139,tip,0xF5F5F5,3);line(139,tip,131,tip+(m.volumeOverlayDirection>0?8:-8),0xF5F5F5,3);line(139,tip,147,tip+(m.volumeOverlayDirection>0?8:-8),0xF5F5F5,3);
      label("Громкость",55,151,130,&cp_font_18);
    }
    else if(current>=(m.volumeOverlaySub?-60:0)){
      snprintf(value,sizeof value,"%d",current);
      label(m.volumeOverlaySub?"Саб":"Громкость",35,77,170,&cp_font_14,0xB8CBD3);
      label(value,30,94,180,&cp_font_44,0xC4F4FF);
      if(!m.volumeOverlaySub)label("%",80,150,80,&cp_font_16,0xB8CBD3);
    }else{label(m.volumeOverlaySub?"SUBWOOFER":"VOLUME",35,77,170,&cp_font_14,0xB8CBD3);label("--",30,94,180,&cp_font_44,0xC4F4FF);}
  }
}
}


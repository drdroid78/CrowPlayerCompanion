#include "panel_ui.h"
#include <math.h>
namespace cp {
const Theme themes[8]={{"Tornado Red",0xC81A26},{"Lapiz Blue",0x1F5FA8},{"Reflex Silver",0xC8C8C6},{"Indium Grey",0x8A8D90},{"Urano Grey",0x5B5D60},{"Atlantic Blue",0x0F3D68},{"Pure White",0xF5F5F5},{"Škoda Green",0x4BA82E}};
static lv_obj_t*root;static uint32_t accent;
static lv_color_t col(uint32_t x){return lv_color_hex(x);}
static lv_image_dsc_t coverImage;
static void cover(const Model&m){if(!m.artworkPresent)return;coverImage={};coverImage.header.magic=LV_IMAGE_HEADER_MAGIC;coverImage.header.cf=LV_COLOR_FORMAT_RGB565;coverImage.header.w=96;coverImage.header.h=96;coverImage.header.stride=96*2;coverImage.data_size=96*96*2;coverImage.data=m.artwork;auto*img=lv_image_create(root);lv_image_set_src(img,&coverImage);lv_image_set_scale(img,640);lv_obj_set_pos(img,72,72);}
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
static void drawSourceIcon(int source,int x,int y,uint32_t c=0xF5F5F5){
  switch(source){
    case 0: // OPT: horizontal optical plug and light rays
      line(x-18,y,x-10,y,c);line(x-10,y-7,x+6,y-7,c);line(x+6,y-7,x+6,y+7,c);line(x+6,y+7,x-10,y+7,c);line(x-10,y+7,x-10,y-7,c);line(x+6,y-3,x+11,y-3,c);line(x+11,y-3,x+11,y+3,c);line(x+15,y-7,x+20,y-11,c);line(x+16,y,x+22,y,c);line(x+15,y+7,x+20,y+11,c);break;
    case 4: // AUX IN: small horizontal 3.5 mm plug
      line(x-19,y,x-12,y,c);line(x-12,y-6,x+7,y-6,c);line(x+7,y-6,x+7,y+6,c);line(x+7,y+6,x-12,y+6,c);line(x-12,y+6,x-12,y-6,c);line(x+7,y-4,x+12,y-4,c);line(x+7,y,x+15,y,c);line(x+7,y+4,x+12,y+4,c);line(x+15,y-3,x+19,y,c);line(x+19,y,x+15,y+3,c);break;
    case 5: { // HIGH: two stacked wires, circled minus left and plus right
      line(x-13,y-5,x+14,y-5,c);line(x-14,y+5,x+13,y+5,c);outlineCircle(x-18,y-5,9,c);outlineCircle(x+18,y+5,9,c);label("-",x-23,y-11,10,&cp_font_14,c);label("+",x+13,y-1,10,&cp_font_14,c);break;}
    case 2: // Bluetooth rune
      line(x,y-14,x,y+14,c);line(x,y-14,x+7,y-7,c);line(x+7,y-7,x,y,c);line(x,y,x+7,y+7,c);line(x+7,y+7,x,y+14,c);line(x,y-8,x-7,y-1,c);line(x-7,y+1,x,y+8,c);break;
    case 6: // USB trident, horizontal orientation from the attached reference
      line(x-18,y,x+17,y,c);line(x-2,y,x-2,y-10,c);line(x-2,y-10,x-6,y-6,c);line(x-2,y-10,x+2,y-6,c);line(x+7,y,x+7,y+9,c);line(x+7,y+9,x+3,y+5,c);line(x+7,y+9,x+11,y+5,c);outlineCircle(x-2,y-13,5,c);line(x+17,y,x+12,y-4,c);line(x+17,y,x+12,y+4,c);break;
    default: break;
  }
}
static void nav(int screen){for(int i=0;i<6;i++)disc(95+i*10,224,i==screen?3:2,i==screen?accent:0x464950);}
static void timeText(char*out,uint32_t ms){snprintf(out,12,"%u:%02u",unsigned(ms/60000),unsigned(ms/1000%60));}
static void heart(bool liked){label(liked?"♥":"♡",186,12,38,&cp_font_16,liked?accent:0xF5F5F5);}
static void settingsText(const Model&m,int key,char*out,size_t n){auto&s=m.settings;switch(key){case 0:snprintf(out,n,"%02u:00",s.dayHour);break;case 1:snprintf(out,n,"%02u:00",s.nightHour);break;case 2:snprintf(out,n,"%u%%",s.dayLight);break;case 3:snprintf(out,n,"%u%%",s.nightLight);break;case 4:snprintf(out,n,"%u%%",s.dayLed);break;case 5:snprintf(out,n,"%u%%",s.nightLed);break;case 6:if(s.idleSeconds)snprintf(out,n,"%u с",s.idleSeconds);else snprintf(out,n,"Выкл");break;case 7:if(m.setting==7)snprintf(out,n,"%s",s.tabletVolume?"Планшет":"Усилитель");else snprintf(out,n,"%s",s.tabletVolume?"ПЛ":"УС");break;}}
static int wheelY(int offset){return 120+offset*41;}
static int wheelIconX(int offset){const int y=offset*41;return int(lroundf(125.0f-sqrtf(100.0f*100.0f-float(y*y))));}
static void settingIcon(int key,int x,int y,bool selected){uint32_t bg=selected?0x102A38:0x11151B;uint32_t ink=selected?accent:0x858D97;disc(x,y,selected?12:10,bg);outlineCircle(x,y,selected?25:21,selected?accent:0x3C434D);
  if(key==0||key==1||key==6){outlineCircle(x,y,12,ink);line(x,y,x,y-4,ink,1);line(x,y,x+4,y+2,ink,1);}
  else if(key==2||key==4){disc(x,y,3,ink);line(x,y-8,x,y-5,ink,1);line(x,y+5,x,y+8,ink,1);line(x-8,y,x-5,y,ink,1);line(x+5,y,x+8,y,ink,1);line(x-6,y-6,x-4,y-4,ink,1);line(x+4,y+4,x+6,y+6,ink,1);}
  else if(key==3){disc(x-2,y-1,6,ink);disc(x+2,y-4,6,bg);}
  else if(key==7){line(x-5,y-1,x+5,y-1,ink,1);line(x-5,y+3,x+5,y+3,ink,1);line(x-3,y-5,x+3,y-5,ink,1);line(x-3,y-5,x-3,y-2,ink,1);line(x+3,y-5,x+3,y-2,ink,1);}
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
    if(!m.artworkPresent)label("♫",83,56,74,&cp_font_44,accent);
    if(m.ham.source>=0)drawSourceIcon(m.ham.source,26,22);
    heart(m.liked&&m.likeKnown);if(m.likePending)label("…",188,27,30,&cp_font_14,accent);
    uint32_t pos=m.position;if(m.playing&&m.usb)pos+=now-m.positionAt;pos=std::min(pos,m.duration);
    label(m.title,18,139,204,&cp_font_16);
    label(m.artist,24,159,192,&cp_font_14,0xA5A9AF);
    char p[12],d[12];timeText(p,pos);timeText(d,m.duration);label(p,22,182,40,&cp_font_14,0xF5F5F5);label(d,178,182,40,&cp_font_14,0xF5F5F5);
    line(65,191,175,191,0x303743,3);if(m.duration)line(65,191,65+int(uint64_t(pos)*110/m.duration),191,accent,3);
    if(m.playing){line(116,210,116,224,0xF5F5F5,3);line(124,210,124,224,0xF5F5F5,3);}else{line(116,210,126,217,0xF5F5F5,3);line(126,217,116,224,0xF5F5F5,3);line(116,224,116,210,0xF5F5F5,3);}
  }else if(m.screen==2){
    label("ЗВУК",60,28,120,&cp_font_14,0xA5A9AF);ring(82,104,53,135,405,0x292C33,3);if(ham&&m.ham.volume>=0)ring(82,104,53,135,135+std::max(1,m.ham.volume*270/66),accent,4);
    if(ham&&m.ham.volume>=0)snprintf(b,sizeof b,"%d",m.ham.volume);else strcpy(b,"--");label(b,37,78,90,&cp_font_44);label("ГРОМКОСТЬ",30,148,105,&cp_font_14,m.subSelected?0xA5A9AF:accent);
    ring(177,104,33,135,405,0x292C33,2);if(ham&&m.ham.sub!=-128)snprintf(b,sizeof b,"%d",m.ham.sub);else strcpy(b,"--");label(b,147,91,60,&cp_font_16);label("САБ",148,148,58,&cp_font_14,m.subSelected?accent:0xA5A9AF);
    if(ham&&m.ham.mute>=0)label(m.ham.mute==1?"MUTE · ВКЛ":"MUTE · ВЫКЛ",40,182,160,&cp_font_16,m.ham.mute==1?accent:0xD0D2D5);
  }else if(m.screen==3){
    // Original radial selector: the amplifier's advertised order determines
    // clockwise positions; the center always shows the active input.
    disc(120,118,32,0x101218);ring(120,118,35,0,359,ham&&m.ham.source>=0?accent:0x41454D,2);
    label(ham&&m.ham.source>=0?sourceName(m.ham.source):"--",90,109,60,&cp_font_16);
    const int count=sourceCount(m.ham);
    for(int i=0;i<count;i++){
      const int source=sourceAt(m.ham,i);int x,y;sourcePosition(i,count,x,y);
      const bool active=ham&&source==m.ham.source,selected=source==m.sourceSelection;
      disc(x,y,21,active?accent:0x22252C);
      if(selected&&!active)ring(x,y,24,0,359,accent,2);
      const char*name=sourceName(source);int w=!strcmp(name,"AUX IN")?58:42;
      label(name,x-w/2,y-7,w,&cp_font_14,active?0x050609:0xF5F5F5);
    }
  }else if(m.screen==4){
    static const char* names[]={"День с","Ночь с","Ярк. дня","Ярк. ночи","LED днём","LED ночью","Тайм-аут","Громкость"};
    label("НАСТРОЙКИ",40,15,160,&cp_font_14,0xA5A9AF);
    for(int offset=-2;offset<=2;offset++){
      int key=(m.setting+offset+8)%8,y=wheelY(offset),iconX=wheelIconX(offset);
      bool selected=offset==0;uint32_t textColor=selected?0xF5F5F5:0x9399A2;
      if(selected)selectedRow(y);
      settingIcon(key,iconX,y,selected);
      int valueWidth=key==7?(selected?72:34):50,valueX=224-valueWidth,labelX=iconX+19;
      const lv_font_t* rowFont=selected?&cp_font_18:&cp_font_16;
      leftLabel(names[key],labelX,y-(selected&&key!=7?8:7),valueX-labelX-3,rowFont,textColor);
      settingsText(m,key,b,sizeof b);
      label(b,valueX,y-7,valueWidth,&cp_font_14,selected?0xF5F5F5:0xAEB4BD);
    }
  }else{
    label("ТЕМА",40,15,160,&cp_font_14,0xA5A9AF);
    for(int offset=-2;offset<=2;offset++){
      int index=(int(m.settings.theme)+offset+8)%8,y=wheelY(offset),iconX=wheelIconX(offset);
      bool selected=offset==0;uint32_t textColor=selected?0xF5F5F5:offset==0?0xC1C5CC:0xA0A5AD;
      if(selected)selectedRow(y);
      disc(iconX,y,selected?14:11,themes[index].rgb);
      leftLabel(themes[index].name,iconX+19,y-9,240-iconX-38,selected?&cp_font_18:&cp_font_16,textColor);
    }
  }
  if(m.screen!=1&&m.screen!=4&&m.screen!=5)nav(m.screen);
  if(m.volumeOverlay){
    auto*shade=lv_obj_create(root);lv_obj_remove_style_all(shade);lv_obj_set_pos(shade,0,0);lv_obj_set_size(shade,240,240);lv_obj_set_style_bg_color(shade,col(0x050609),0);lv_obj_set_style_bg_opa(shade,LV_OPA_COVER,0);lv_obj_remove_flag(shade,LV_OBJ_FLAG_SCROLLABLE);
    char value[16];int current=m.volumeOverlayValue;
    volumeRing(m.volumeOverlayTick*100/51,accent);
    if(m.settings.tabletVolume){
      label("VOLUME",60,77,120,&cp_font_14,0xB8CBD3);
      label(m.volumeOverlayDirection>0?"UP":"DOWN",30,96,180,&cp_font_44,0xC4F4FF);
    }
    else if(current>=(m.volumeOverlaySub?-60:0)){
      snprintf(value,sizeof value,"%d",current);
      label(m.volumeOverlaySub?"SUBWOOFER":"VOLUME",35,77,170,&cp_font_14,0xB8CBD3);
      label(value,30,94,180,&cp_font_44,0xC4F4FF);
      if(!m.volumeOverlaySub)label("%",80,150,80,&cp_font_16,0xB8CBD3);
    }else{label(m.volumeOverlaySub?"SUBWOOFER":"VOLUME",35,77,170,&cp_font_14,0xB8CBD3);label("--",30,94,180,&cp_font_44,0xC4F4FF);}
  }
}
}


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
static void disc(int x,int y,int r,uint32_t c){auto*o=lv_obj_create(root);lv_obj_remove_style_all(o);lv_obj_set_pos(o,x-r,y-r);lv_obj_set_size(o,2*r,2*r);lv_obj_set_style_radius(o,LV_RADIUS_CIRCLE,0);lv_obj_set_style_bg_color(o,col(c),0);lv_obj_set_style_bg_opa(o,LV_OPA_COVER,0);}
static void ring(int x,int y,int r,int start,int end,uint32_t c,int width){
  auto*a=lv_arc_create(root);lv_obj_remove_style_all(a);lv_obj_set_pos(a,x-r,y-r);lv_obj_set_size(a,r*2,r*2);lv_arc_set_bg_angles(a,start,end);lv_obj_set_style_arc_width(a,width,LV_PART_MAIN);lv_obj_set_style_arc_color(a,col(c),LV_PART_MAIN);lv_obj_set_style_arc_rounded(a,true,LV_PART_MAIN);lv_obj_set_style_arc_opa(a,LV_OPA_TRANSP,LV_PART_INDICATOR);lv_obj_remove_flag(a,LV_OBJ_FLAG_CLICKABLE);
}
static lv_point_precise_t iconLines[96][2];static uint8_t iconLineCount=0;
static void line(int x1,int y1,int x2,int y2,uint32_t color=0xF5F5F5,int width=2){if(iconLineCount>=96)return;auto&pts=iconLines[iconLineCount++];pts[0]={lv_value_precise_t(x1),lv_value_precise_t(y1)};pts[1]={lv_value_precise_t(x2),lv_value_precise_t(y2)};auto*l=lv_line_create(root);lv_line_set_points(l,pts,2);lv_obj_set_style_line_color(l,col(color),0);lv_obj_set_style_line_width(l,width,0);lv_obj_set_style_line_rounded(l,true,0);lv_obj_remove_flag(l,LV_OBJ_FLAG_CLICKABLE);}
static lv_obj_t* outlineCircle(int x,int y,int d,uint32_t color){auto*o=lv_obj_create(root);lv_obj_remove_style_all(o);lv_obj_set_pos(o,x-d/2,y-d/2);lv_obj_set_size(o,d,d);lv_obj_set_style_radius(o,LV_RADIUS_CIRCLE,0);lv_obj_set_style_bg_opa(o,LV_OPA_TRANSP,0);lv_obj_set_style_border_width(o,1,0);lv_obj_set_style_border_color(o,col(color),0);return o;}
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
static void settingsText(const Model&m,int key,char*out,size_t n){auto&s=m.settings;switch(key){case 0:snprintf(out,n,"%02u:00",s.dayHour);break;case 1:snprintf(out,n,"%02u:00",s.nightHour);break;case 2:snprintf(out,n,"%u%%",s.dayLight);break;case 3:snprintf(out,n,"%u%%",s.nightLight);break;case 4:snprintf(out,n,"%u%%",s.dayLed);break;case 5:snprintf(out,n,"%u%%",s.nightLed);break;case 6:if(s.idleSeconds)snprintf(out,n,"%u с",s.idleSeconds);else snprintf(out,n,"Выкл");break;}}
void render(const Model&m,uint32_t now){
  if(!root){root=lv_obj_create(nullptr);lv_obj_remove_style_all(root);lv_obj_set_size(root,240,240);lv_obj_set_style_bg_color(root,col(0x050609),0);lv_obj_set_style_bg_opa(root,LV_OPA_COVER,0);lv_obj_remove_flag(root,LV_OBJ_FLAG_SCROLLABLE);lv_screen_load(root);}lv_obj_clean(root);iconLineCount=0;accent=themes[m.settings.theme].rgb;
  char b[160];bool ham=m.ham.online(now);
  if(m.screen==0){
    ring(120,120,103,135,405,0x252830,3);int minute=m.clock.minute();if(m.clock.epoch)ring(120,120,103,135,135+std::max(1,minute*270/1440),accent,4);
    label("ŠKODA EDITION",40,53,160,&cp_font_14,0xA5A9AF);m.clock.text(b);label(b,20,91,200,&cp_font_44);
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
    label("ИСТОЧНИК",55,27,130,&cp_font_14,0xA5A9AF);
    disc(120,118,32,0x101218);ring(120,118,35,0,359,ham&&m.ham.source>=0?accent:0x41454D,2);
    label(ham&&m.ham.source>=0?sourceName(m.ham.source):"--",90,109,60,&cp_font_16);
    const int count=sourceCount(m.ham);
    for(int i=0;i<count;i++){
      const int source=sourceAt(m.ham,i);int x,y;sourcePosition(i,count,x,y);
      const bool active=ham&&source==m.ham.source,selected=source==m.sourceSelection;
      disc(x,y,21,active?accent:0x22252C);
      if(selected&&!active)ring(x,y,24,0,359,accent,2);
      const char*name=sourceName(source);int w=!strcmp(name,"AUX IN")?46:42;
      label(name,x-w/2,y-7,w,&cp_font_14,active?0x050609:0xF5F5F5);
    }
  }else if(m.screen==4){
    static const char* names[]={"Начало дня","Начало ночи","Экран · день","Экран · ночь","LED · день","LED · ночь","Дежурный экран"};
    label("НАСТРОЙКИ",40,27,160,&cp_font_14,0xA5A9AF);
    for(int row=-1;row<=1;row++){int key=(m.setting+row+7)%7;int y=63+(row+1)*43;bool selected=!row;if(selected){auto*o=lv_obj_create(root);lv_obj_remove_style_all(o);lv_obj_set_pos(o,30,y-3);lv_obj_set_size(o,180,42);lv_obj_set_style_radius(o,10,0);lv_obj_set_style_bg_color(o,col(0x1B1F26),0);lv_obj_set_style_bg_opa(o,255,0);}label(names[key],35,y,170,&cp_font_14,selected?accent:0xA5A9AF);settingsText(m,key,b,sizeof b);label(b,33,y+17,174,&cp_font_14,selected?0xF5F5F5:0xA5A9AF);}
    label(m.edit?"Поворот · изменить":"Касание · изменить",30,198,180,&cp_font_14,m.edit?accent:0xA5A9AF);
  }else{
    static const char* shortNames[]={"Tornado","Lapiz","Reflex","Indium","Urano","Atlantic","Pure","Škoda"};
    label("ТЕМА ОФОРМЛЕНИЯ",24,22,192,&cp_font_14,0xA5A9AF);
    for(int i=0;i<8;i++){int x=25+(i%2)*98,y=51+(i/2)*31;bool selected=m.settings.theme==i;auto*tile=lv_obj_create(root);lv_obj_remove_style_all(tile);lv_obj_set_pos(tile,x,y);lv_obj_set_size(tile,90,27);lv_obj_set_style_radius(tile,8,0);lv_obj_set_style_bg_color(tile,col(selected?0x20242C:0x101218),0);lv_obj_set_style_bg_opa(tile,LV_OPA_COVER,0);lv_obj_set_style_border_width(tile,selected?1:0,0);lv_obj_set_style_border_color(tile,col(themes[i].rgb),0);lv_obj_set_style_border_opa(tile,selected?LV_OPA_COVER:LV_OPA_TRANSP,0);disc(x+12,y+13,7,themes[i].rgb);label(shortNames[i],x+22,y+5,65,&cp_font_14,selected?0xF5F5F5:0xA5A9AF);}
    label(themes[m.settings.theme].name,25,181,190,&cp_font_16,accent);snprintf(b,sizeof b,"#%06lX",(unsigned long)themes[m.settings.theme].rgb);label(b,25,198,190,&cp_font_14,0xA5A9AF);
  }
  if(m.screen!=1)nav(m.screen);
  if(m.volumeOverlay){
    auto*shade=lv_obj_create(root);lv_obj_remove_style_all(shade);lv_obj_set_pos(shade,0,0);lv_obj_set_size(shade,240,240);lv_obj_set_style_bg_color(shade,col(0x050609),0);lv_obj_set_style_bg_opa(shade,LV_OPA_COVER,0);lv_obj_remove_flag(shade,LV_OBJ_FLAG_SCROLLABLE);
    ring(120,110,68,135,405,0x252830,4);
    char value[16];int current=m.volumeOverlaySub?m.ham.sub:m.ham.volume;
    if(ham&&current!=(m.volumeOverlaySub?-128:-1)){
      snprintf(value,sizeof value,"%d",current);
      int progress=m.volumeOverlaySub?clamp(current+60,0,60)*270/60:clamp(current,0,66)*270/66;
      ring(120,110,68,135,135+std::max(1,progress),accent,5);
    }else strcpy(value,"--");
    label(value,38,85,164,&cp_font_44,0xF5F5F5);
    label(m.volumeOverlaySub?"САБВУФЕР":"ГРОМКОСТЬ",26,177,188,&cp_font_14,accent);
  }
}
}


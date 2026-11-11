#include "panel_ui.h"
#include <math.h>
namespace cp {
const Theme themes[8]={{"Tornado Red",0xC81A26},{"Lapiz Blue",0x1F5FA8},{"Reflex Silver",0xC8C8C6},{"Indium Grey",0x8A8D90},{"Urano Grey",0x5B5D60},{"Atlantic Blue",0x0F3D68},{"Pure White",0xF5F5F5},{"Škoda Green",0x4BA82E}};
static lv_obj_t*root;static uint32_t accent;
static lv_color_t col(uint32_t x){return lv_color_hex(x);}
static lv_obj_t* label(const char*s,int x,int y,int w,const lv_font_t*f=&cp_font_12,uint32_t c=0xF5F5F5){
  auto*l=lv_label_create(root);lv_obj_set_size(l,w,f->line_height);lv_obj_set_pos(l,x,y);lv_obj_set_style_text_font(l,f,0);lv_obj_set_style_text_color(l,col(c),0);lv_obj_set_style_text_align(l,LV_TEXT_ALIGN_CENTER,0);lv_label_set_long_mode(l,LV_LABEL_LONG_DOT);lv_label_set_text(l,s);return l;
}
static void disc(int x,int y,int r,uint32_t c){auto*o=lv_obj_create(root);lv_obj_remove_style_all(o);lv_obj_set_pos(o,x-r,y-r);lv_obj_set_size(o,2*r,2*r);lv_obj_set_style_radius(o,LV_RADIUS_CIRCLE,0);lv_obj_set_style_bg_color(o,col(c),0);lv_obj_set_style_bg_opa(o,LV_OPA_COVER,0);}
static void ring(int x,int y,int r,int start,int end,uint32_t c,int width){
  auto*a=lv_arc_create(root);lv_obj_remove_style_all(a);lv_obj_set_pos(a,x-r,y-r);lv_obj_set_size(a,r*2,r*2);lv_arc_set_bg_angles(a,start,end);lv_obj_set_style_arc_width(a,width,LV_PART_MAIN);lv_obj_set_style_arc_color(a,col(c),LV_PART_MAIN);lv_obj_set_style_arc_rounded(a,true,LV_PART_MAIN);lv_obj_set_style_arc_opa(a,LV_OPA_TRANSP,LV_PART_INDICATOR);lv_obj_remove_flag(a,LV_OBJ_FLAG_CLICKABLE);
}
static void ticks(){for(int i=0;i<60;i++){float a=i*6*3.14159265f/180;int r=i%5?111:108;static lv_point_precise_t pts[60][2];pts[i][0]={lv_value_precise_t(120+sin(a)*r),lv_value_precise_t(120-cos(a)*r)};pts[i][1]={lv_value_precise_t(120+sin(a)*115),lv_value_precise_t(120-cos(a)*115)};auto*l=lv_line_create(root);lv_line_set_points(l,pts[i],2);lv_obj_set_style_line_color(l,col(0x34363C),0);lv_obj_set_style_line_width(l,1,0);}}
static void nav(int screen){for(int i=0;i<5;i++)disc(100+i*10,224,i==screen?3:2,i==screen?accent:0x464950);}
static void timeText(char*out,uint32_t ms){snprintf(out,12,"%u:%02u",unsigned(ms/60000),unsigned(ms/1000%60));}
static void heart(bool liked){label(liked?"♥":"♡",178,34,30,&cp_font_16,liked?accent:0xD0D2D5);}
static void settingsText(const Model&m,int key,char*out,size_t n){auto&s=m.settings;switch(key){case 0:snprintf(out,n,"%s",themes[s.theme].name);break;case 1:snprintf(out,n,"%02u:00",s.dayHour);break;case 2:snprintf(out,n,"%02u:00",s.nightHour);break;case 3:snprintf(out,n,"%u%%",s.dayLight);break;case 4:snprintf(out,n,"%u%%",s.nightLight);break;case 5:snprintf(out,n,"%u%%",s.dayLed);break;case 6:snprintf(out,n,"%u%%",s.nightLed);break;case 7:if(s.idleSeconds)snprintf(out,n,"%u с",s.idleSeconds);else snprintf(out,n,"Выкл");break;}}
void render(const Model&m,uint32_t now){
  if(!root){root=lv_obj_create(nullptr);lv_obj_remove_style_all(root);lv_obj_set_size(root,240,240);lv_obj_set_style_bg_color(root,col(0x050609),0);lv_obj_set_style_bg_opa(root,LV_OPA_COVER,0);lv_obj_remove_flag(root,LV_OBJ_FLAG_SCROLLABLE);lv_screen_load(root);}lv_obj_clean(root);accent=themes[m.settings.theme].rgb;
  char b[160];bool ham=m.ham.online(now);ticks();
  if(m.screen==0){
    ring(120,120,103,135,405,0x252830,3);int minute=m.clock.minute();if(m.clock.epoch)ring(120,120,103,135,135+std::max(1,minute*270/1440),accent,4);
    label("ŠKODA EDITION",40,53,160,&cp_font_12,0xA5A9AF);m.clock.text(b);label(b,20,91,200,&cp_font_44);label(m.clock.epoch?(daytime(m.settings,minute)?"ДНЕВНОЙ РЕЖИМ":"НОЧНОЙ РЕЖИМ"):"ОЖИДАНИЕ ВРЕМЕНИ",25,155,190,&cp_font_12,0xA5A9AF);
    label(m.usb?"USB · подключено":"USB · ожидание",40,182,160,&cp_font_12,m.usb?accent:0xA5A9AF);
  }else if(m.screen==1){
    ring(120,103,70,135,405,0x292C33,3);uint32_t pos=m.position;if(m.playing&&m.usb)pos+=now-m.positionAt;pos=std::min(pos,m.duration);
    if(m.duration)ring(120,103,70,135,135+std::max(1,int(uint64_t(pos)*270/m.duration)),accent,4);
    disc(120,102,54,0x101218);label("♫",85,71,70,&cp_font_44,0x8C929B);heart(m.liked&&m.likeKnown);
    if(m.likePending)label("…",177,54,30,&cp_font_12,accent);
    char p[12],d[12];timeText(p,pos);timeText(d,m.duration);snprintf(b,sizeof b,"%s / %s",p,d);label(b,65,149,110,&cp_font_12,0xA5A9AF);
    label(m.title,28,172,184,&cp_font_16);label(m.artist,35,193,170,&cp_font_12,0xA5A9AF);
    label(LV_SYMBOL_PREV,45,117,32,&lv_font_montserrat_14);label(m.playing?LV_SYMBOL_PAUSE:LV_SYMBOL_PLAY,104,117,32,&lv_font_montserrat_14);label(LV_SYMBOL_NEXT,163,117,32,&lv_font_montserrat_14);
  }else if(m.screen==2){
    label("ЗВУК",60,28,120,&cp_font_12,0xA5A9AF);ring(82,104,53,135,405,0x292C33,3);if(ham&&m.ham.volume>=0)ring(82,104,53,135,135+std::max(1,m.ham.volume*270/66),accent,4);
    if(ham&&m.ham.volume>=0)snprintf(b,sizeof b,"%d",m.ham.volume);else strcpy(b,"--");label(b,37,78,90,&cp_font_44);label("ГРОМКОСТЬ",30,148,105,&cp_font_12,m.subSelected?0xA5A9AF:accent);
    ring(177,104,33,135,405,0x292C33,2);if(ham&&m.ham.sub!=-128)snprintf(b,sizeof b,"%d",m.ham.sub);else strcpy(b,"--");label(b,147,91,60,&cp_font_16);label("САБ",148,148,58,&cp_font_12,m.subSelected?accent:0xA5A9AF);
    label(ham?(m.ham.mute==1?"MUTE · ВКЛ":"MUTE · ВЫКЛ"):"HAM · нет связи",40,182,160,&cp_font_16,ham&&m.ham.mute==1?accent:0xD0D2D5);
  }else if(m.screen==3){
    disc(120,118,32,0x101218);ring(120,118,34,0,359,accent,2);label(ham?sourceName(m.ham.source):"--",90,109,60,&cp_font_16);
    if(!ham||!m.ham.count)label("Ожидание HAM",45,174,150,&cp_font_12,0xA5A9AF);
    for(int i=0;ham&&i<m.ham.count;i++){float a=(-90+i*360.0/m.ham.count)*3.14159265/180;int x=120+cos(a)*76,y=118+sin(a)*76;bool active=m.ham.sources[i]==m.ham.source;disc(x,y,21,active?accent:0x22252C);label(sourceName(m.ham.sources[i]),x-23,y-7,46,&cp_font_12,active?0x050609:0xF5F5F5);}
  }else{
    static const char* names[]={"Тема","Начало дня","Начало ночи","Экран · день","Экран · ночь","LED · день","LED · ночь","Дежурный экран"};
    label("НАСТРОЙКИ",40,27,160,&cp_font_12,0xA5A9AF);
    for(int row=-1;row<=1;row++){int key=(m.setting+row+8)%8;int y=63+(row+1)*43;bool selected=!row;if(selected){auto*o=lv_obj_create(root);lv_obj_remove_style_all(o);lv_obj_set_pos(o,30,y-3);lv_obj_set_size(o,180,42);lv_obj_set_style_radius(o,10,0);lv_obj_set_style_bg_color(o,col(0x1B1F26),0);lv_obj_set_style_bg_opa(o,255,0);}label(names[key],35,y,170,&cp_font_12,selected?accent:0xA5A9AF);settingsText(m,key,b,sizeof b);label(b,33,y+17,174,&cp_font_12,selected?0xF5F5F5:0xA5A9AF);}
    label(m.edit?"Поворот · изменить":"Касание · изменить",30,198,180,&cp_font_12,m.edit?accent:0xA5A9AF);
  }
  nav(m.screen);
  if(m.notice[0]&&int32_t(m.noticeUntil-now)>0){disc(120,184,48,0x1B1F26);label(m.notice,35,173,170,&cp_font_12);}
}
}

#include "../CrowPlayer/src/panel_ui.h"
#include <fstream>
#include <filesystem>
#include <vector>
#include <string>
static uint16_t pixels[240*240];
static uint16_t demoArtwork[240*240];
alignas(LV_DRAW_BUF_ALIGN) static uint16_t buffer[240*24];
static void flush(lv_display_t*d,const lv_area_t*a,uint8_t*data){auto*p=reinterpret_cast<uint16_t*>(data);for(int y=a->y1;y<=a->y2;y++)for(int x=a->x1;x<=a->x2;x++)pixels[y*240+x]=*p++;lv_display_flush_ready(d);}
static void save(const std::string&name){lv_refr_now(nullptr);std::ofstream out(name,std::ios::binary);out<<"P6\n240 240\n255\n";for(auto c:pixels){char rgb[]={char(((c>>11)&31)*255/31),char(((c>>5)&63)*255/63),char((c&31)*255/31)};out.write(rgb,3);}}
int main(int argc,char**argv){
  std::string dest=argc>1?argv[1]:"build/preview";std::filesystem::create_directories(dest);lv_init();auto*d=lv_display_create(240,240);lv_display_set_color_format(d,LV_COLOR_FORMAT_RGB565);lv_display_set_buffers(d,buffer,nullptr,sizeof buffer,LV_DISPLAY_RENDER_MODE_PARTIAL);lv_display_set_flush_cb(d,flush);
  cp::Model m;const uint32_t now=5000;m.clock.sync(1794515760000ULL,now,180);m.usb=true;m.usbHostConnected=true;m.likeKnown=m.likeAvailable=m.liked=true;m.playing=true;strcpy(m.title,"ГРУППА КРОВИ");strcpy(m.artist,"КИНО");m.position=87000;m.duration=214000;m.positionAt=now;m.ham.volume=45;m.ham.sub=-8;m.ham.mute=0;m.ham.source=0;m.ham.count=5;uint8_t sources[]={2,6,4,0,5};memcpy(m.ham.sources,sources,5);m.ham.seen=true;m.ham.lastRx=now;
  for(int theme=0;theme<8;theme++)for(int screen=0;screen<6;screen++){m.screen=screen;m.settings.theme=theme;cp::render(m,now);save(dest+"/theme-"+std::to_string(theme)+"-screen-"+std::to_string(screen)+".ppm");}
  m.screen=0;m.usb=false;m.usbHostConnected=true;cp::render(m,now);save(dest+"/standby-usb-host-only.ppm");m.usbHostConnected=false;cp::render(m,now);save(dest+"/standby-usb-disconnected.ppm");m.usb=true;m.usbHostConnected=true;
  m.settings.theme=1;m.screen=4;for(int setting=0;setting<8;setting++){m.setting=setting;cp::render(m,now);save(dest+"/settings-cursor-"+std::to_string(setting)+".ppm");}
  m.screen=3;for(int i=0;i<5;i++){m.ham.source=cp::sourceCycleAt(m.ham,i);m.sourceSelection=m.ham.source;cp::render(m,now);save(dest+"/source-active-"+std::to_string(m.ham.source)+".ppm");}m.ham.source=0;
  m.sourceSelection=6;cp::render(m,now);save(dest+"/source-selected-usb-active-opt.ppm");
  m.screen=1;m.volumeOverlay=true;m.volumeOverlayTick=26;m.volumeOverlayDirection=1;m.settings.tabletVolume=true;cp::render(m,now);save(dest+"/volume-tablet-up.ppm");m.volumeOverlayDirection=-1;cp::render(m,now);save(dest+"/volume-tablet-down.ppm");m.settings.tabletVolume=false;m.volumeOverlayValue=45;cp::render(m,now);save(dest+"/volume-amplifier.ppm");m.volumeOverlay=false;
  for(int y=0;y<240;y++)for(int x=0;x<240;x++)demoArtwork[y*240+x]=uint16_t(((x*31/240)<<11)|((y*63/240)<<5)|((x+y)*31/480));m.artwork=(uint8_t*)demoArtwork;m.artworkPresent=true;m.liked=false;cp::render(m,now);save(dest+"/player-artwork.ppm");m.artworkPresent=false;m.artwork=nullptr;m.liked=true;
  m.screen=1;strcpy(m.title,"Очень длинное название трека, которое не должно закрывать исполнителя");strcpy(m.artist,"Длинное имя исполнителя и приглашённые музыканты");cp::render(m,now);save(dest+"/long-title.ppm");
  m=cp::Model();for(int screen=0;screen<4;screen++){m.screen=screen;cp::render(m,now);save(dest+"/offline-"+std::to_string(screen)+".ppm");}
}


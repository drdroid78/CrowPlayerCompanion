// Native USB companion + inverted 1.8 V Hellion UART (external level shifter).
#include <Arduino.h>
#include <LovyanGFX.hpp>
#include <ArduinoJson.h>
#include <Adafruit_NeoPixel.h>
#include <Preferences.h>
#include <Wire.h>
#include <USB.h>
#include <USBCDC.h>
#include <WiFi.h>
#include <ArduinoOTA.h>
#include "crowplayer_secrets.h"
#include "src/panel_ui.h"
class CrowDisplay:public lgfx::LGFX_Device {
  lgfx::Panel_GC9A01 panel;lgfx::Bus_SPI bus;lgfx::Light_PWM light;
 public:CrowDisplay(){
    auto b=bus.config();b.spi_host=SPI2_HOST;b.spi_mode=0;b.freq_write=40000000;b.spi_3wire=true;b.use_lock=true;b.dma_channel=SPI_DMA_CH_AUTO;b.pin_sclk=10;b.pin_mosi=11;b.pin_miso=-1;b.pin_dc=3;bus.config(b);panel.setBus(&bus);
    auto p=panel.config();p.pin_cs=9;p.pin_rst=14;p.pin_busy=-1;p.memory_width=240;p.memory_height=240;p.panel_width=240;p.panel_height=240;p.invert=true;p.rgb_order=false;panel.config(p);
    auto l=light.config();l.pin_bl=46;l.invert=false;l.freq=12000;l.pwm_channel=7;light.config(l);panel.setLight(&light);setPanel(&panel);
  }
};
CrowDisplay lcd;USBCDC tablet;HardwareSerial amp(1);Preferences prefs;
Adafruit_NeoPixel leds(5,48,NEO_GRB+NEO_KHZ800);
cp::Model model;cp::HamParser parser;cp::LineBuffer usbLine;cp::Button button;
static portMUX_TYPE encoderMux=portMUX_INITIALIZER_UNLOCKED;
static volatile int32_t encoderSteps=0;static volatile uint8_t encoderPrevious=3;static volatile int8_t encoderPartial=0;
static DRAM_ATTR const int8_t encoderTable[16]={0,-1,1,0,1,0,0,-1,-1,0,0,1,0,1,-1,0};
static bool dirty=true,settingsDirty=false,otaReady=false;static uint32_t settingsAt=0,lastRender=0,lastTick=0,lastPoll=0,lastTouch=0,lastLed=0,effectAt=0;
static int effect=0;static uint8_t pollIndex=0;static uint32_t nextId=1,usbPendingId=0,usbPendingAt=0;
static bool ampPending=false,ampAck=false;static uint8_t ampCommand=0;static int ampTarget=0;static uint32_t ampPendingAt=0;static float brightness=18;
static int queuedVolume=0;
static uint16_t drawBuffer[240*24];
void IRAM_ATTR onEncoder(){
  uint8_t next=(digitalRead(45)<<1)|digitalRead(42);portENTER_CRITICAL_ISR(&encoderMux);
  if((encoderPrevious^next)==3)encoderPartial=0;else encoderPartial+=encoderTable[(encoderPrevious<<2)|next];encoderPrevious=next;
  if(encoderPartial>=4){encoderSteps++;encoderPartial=0;}else if(encoderPartial<=-4){encoderSteps--;encoderPartial=0;}portEXIT_CRITICAL_ISR(&encoderMux);
}
static void notify(const char*s,bool error=false){strlcpy(model.notice,s,sizeof model.notice);model.noticeUntil=millis()+1500;effect=error?3:2;effectAt=millis();dirty=true;}
static void saveLater(){settingsDirty=true;settingsAt=millis();dirty=true;}
static void activity(){model.activeAt=millis();if(model.screen==0){model.screen=1;dirty=true;}}
static void sendHam(uint8_t cmd,uint8_t op,const uint8_t*data=nullptr,size_t n=0){auto p=cp::packet(1,cmd,op,data,n);amp.write(p.bytes,p.size);}
static void setHam(uint8_t cmd,int value){
  if(!model.ham.online(millis())){notify("Нет связи с HAM",true);return;}if(ampPending)return;
  uint8_t raw=0;if(cmd==1){if(!model.ham.advertised(value))return;raw=0x51+value;}else if(cmd==2){value=cp::clamp(value,0,60);raw=0x42-value;}else if(cmd==3){value=cp::clamp(value,-60,0);raw=uint8_t(-value);}else if(cmd==4)raw=value?1:0;else return;
  ampPending=true;ampAck=false;ampCommand=cmd;ampTarget=value;ampPendingAt=millis();model.requestPending=true;sendHam(cmd,0xBB,&raw,1);dirty=true;
}
static void volume(int delta){
  uint8_t cmd=model.subSelected&&model.screen==2?3:2;
  if(ampPending){if(ampCommand==cmd)queuedVolume=cp::clamp(queuedVolume+delta,-30,30);return;}
  if(cmd==3){if(model.ham.sub!=-128)setHam(3,model.ham.sub+delta);}else if(model.ham.volume>=0)setHam(2,model.ham.volume+delta);else notify("Ожидание громкости",true);
}
static void media(const char*action){
  if(!model.usb){notify("Подключите USB",true);return;}if(usbPendingId)return;
  if(!strcmp(action,"like")&&(!model.likeAvailable||!model.likeKnown)){notify("Лайк недоступен",true);return;}
  StaticJsonDocument<768>d;usbPendingId=nextId++;usbPendingAt=millis();d["v"]=1;d["type"]="command";d["id"]=usbPendingId;d["action"]=action;d["title"]=model.title;d["artist"]=model.artist;
  if(!strcmp(action,"like")){d["liked"]=!model.liked;model.likePending=true;}serializeJson(d,tablet);tablet.println();dirty=true;
}
static void editSetting(int delta){auto&s=model.settings;switch(model.setting){case 0:s.theme=(s.theme+delta%8+8)%8;break;case 1:s.dayHour=(s.dayHour+delta%24+24)%24;break;case 2:s.nightHour=(s.nightHour+delta%24+24)%24;break;case 3:s.dayLight=cp::clamp(int(s.dayLight)+delta*5,5,100);break;case 4:s.nightLight=cp::clamp(int(s.nightLight)+delta*5,1,100);break;case 5:s.dayLed=cp::clamp(int(s.dayLed)+delta*5,0,100);break;case 6:s.nightLed=cp::clamp(int(s.nightLed)+delta,0,100);break;case 7:s.idleSeconds=cp::clamp(int(s.idleSeconds)+delta*15,0,600);break;}saveLater();}
static void rotate(int delta){activity();if(model.screen==4){if(model.edit)editSetting(delta);else{model.setting=(model.setting+delta%8+8)%8;dirty=true;}}else volume(delta);}
static void tap(int x,int y){
  if(model.screen==0){activity();return;}activity();
  if(model.screen==1){if(x>=170&&y<80)media("like");else if(y>=96&&y<148){if(x<90)media("previous");else if(x>150)media("next");else media("play_pause");}}
  else if(model.screen==2){if(y>=170){if(model.ham.mute>=0)setHam(4,!model.ham.mute);}else if(y>=65&&y<164){model.subSelected=x>135;dirty=true;}}
  else if(model.screen==3&&model.ham.online(millis())){for(int i=0;i<model.ham.count;i++){float a=(-90+i*360.0/model.ham.count)*3.14159265/180;int sx=120+cos(a)*76,sy=118+sin(a)*76;if((x-sx)*(x-sx)+(y-sy)*(y-sy)<25*25)setHam(1,model.ham.sources[i]);}}
  else if(model.screen==4){if(y>=95&&y<145){model.edit=!model.edit;saveLater();}else{model.setting=(model.setting+(y<95?7:1))%8;model.edit=false;dirty=true;}}
}
static bool readTouch(uint8_t reg,uint8_t*out,size_t n){Wire1.beginTransmission(0x15);Wire1.write(reg);if(Wire1.endTransmission(false)!=0)return false;if(Wire1.requestFrom(uint8_t(0x15),uint8_t(n))!=n)return false;for(size_t i=0;i<n;i++)out[i]=Wire1.read();return true;}
static void touch(uint32_t now){
  if(now-lastTouch<12)return;lastTouch=now;static bool held=false;static int startX,startY,endX,endY;static uint32_t downAt=0;uint8_t b[5];
  if(!readTouch(2,b,5))return;bool pressed=(b[0]&15)>0;int x=((b[1]&15)<<8)|b[2],y=((b[3]&15)<<8)|b[4];if(pressed&&(x>=240||y>=240))return;
  if(pressed&&!held){held=true;startX=endX=x;startY=endY=y;downAt=now;}else if(pressed){endX=x;endY=y;}
  else if(held){held=false;int dx=endX-startX,dy=endY-startY;bool asleep=model.screen==0;activity();if(asleep)return;
    if(abs(dx)>35&&abs(dx)>abs(dy)*1.3){model.screen=(model.screen+(dx<0?1:4))%5;model.edit=false;model.subSelected=false;effect=1;effectAt=now;dirty=true;}
    else if(abs(dy)>25&&abs(dy)>abs(dx)*1.3){if(model.screen==4){if(model.edit)editSetting(dy<0?1:-1);else model.setting=(model.setting+(dy<0?1:7))%8;dirty=true;}else volume(cp::clamp(-dy/12,-10,10));}
    else if(abs(dx)<18&&abs(dy)<18&&now-downAt<600)tap(endX,endY);
  }
}
static void usbReceive(uint32_t now){
  int budget=512;while(budget--&&tablet.available()){if(!usbLine.feed(tablet.read()))continue;StaticJsonDocument<3072>d;if(deserializeJson(d,usbLine.data)||d["v"]!=1)continue;const char*type=d["type"]|"";
    if(!strcmp(type,"state")){
      const char*title=d["title"]|"";const char*artist=d["artist"]|"";bool changed=strcmp(title,model.title)||strcmp(artist,model.artist);
      strlcpy(model.title,title,sizeof model.title);strlcpy(model.artist,artist,sizeof model.artist);model.playing=d["state"]=="playing";model.position=d["positionMs"]|0u;model.duration=d["durationMs"]|0u;model.positionAt=now;
      model.likeKnown=d["liked"].is<bool>();model.liked=d["liked"]|false;model.likeAvailable=d["likeAvailable"]|false;if(changed){model.likePending=false;usbPendingId=0;}
      model.clock.sync(d["epochMs"]|uint64_t(0),now,d["utcOffsetMinutes"]|180);model.lastUsb=now;model.usb=true;dirty=true;
    }else if(!strcmp(type,"heartbeat")){model.lastUsb=now;model.usb=true;}
    else if(!strcmp(type,"result")&&usbPendingId&&d["id"].as<uint32_t>()==usbPendingId){if(d["ok"]==true){if(model.likePending&&d["liked"].is<bool>()){model.liked=d["liked"].as<bool>();model.likeKnown=true;}notify("Подтверждено");}else notify("Команда не выполнена",true);usbPendingId=0;model.likePending=false;}
  }
  if(model.usb&&(!tablet||uint32_t(now-model.lastUsb)>5000)){model.usb=false;model.playing=false;usbPendingId=0;model.likePending=false;notify("USB отключён",true);}
  if(usbPendingId&&now-usbPendingAt>3000){usbPendingId=0;model.likePending=false;notify("Нет подтверждения",true);}
}
static void hamReceive(uint32_t now){
  int budget=256;while(budget--&&amp.available())if(parser.feed(amp.read(),now)){auto&p=parser.p;uint8_t*b=p.bytes;
    if(ampPending&&b[3]==1&&b[4]==ampCommand&&b[5]==0xEE&&b[2]>=4&&b[6]==0x0A){ampAck=true;sendHam(ampCommand,0x66);}
    if(cp::report(model.ham,p,now)){dirty=true;if(ampPending&&b[4]==ampCommand){int actual=ampCommand==1?model.ham.source:ampCommand==2?model.ham.volume:ampCommand==3?model.ham.sub:model.ham.mute;
      if(actual==ampTarget){ampPending=false;model.requestPending=false;effect=ampCommand==2?4:2;effectAt=now;}else if(ampAck){ampPending=false;model.requestPending=false;notify("HAM изменил значение",true);}}}
  }
  if(ampPending&&now-ampPendingAt>1000){ampPending=false;queuedVolume=0;model.requestPending=false;notify("HAM: нет подтверждения",true);}
  if(!ampPending&&queuedVolume){int delta=queuedVolume;queuedVolume=0;uint8_t cmd=ampCommand;if(model.ham.online(now)){if(cmd==2)setHam(2,model.ham.volume+delta);else if(cmd==3)setHam(3,model.ham.sub+delta);}}
  if(!ampPending&&now-lastPoll>200){static const uint8_t commands[]={0,4,2,3,1};sendHam(commands[pollIndex++%5],0x66);lastPoll=now;}
}
static void lighting(uint32_t now){
  if(now-lastLed<20)return;float elapsed=now-lastLed;lastLed=now;bool day=model.clock.epoch&&cp::daytime(model.settings,model.clock.minute());float target=model.clock.epoch?(day?model.settings.dayLight:model.settings.nightLight):18;
  brightness+=cp::clamp(target-brightness,-elapsed*0.05f,elapsed*0.05f);lcd.setBrightness(uint8_t(brightness*2.55f));float base=(model.clock.epoch?(day?model.settings.dayLed:model.settings.nightLed):6)/100.0f;if(model.ham.mute==1)base*=0.25f;
  uint32_t color=cp::themes[model.settings.theme].rgb,t=now-effectAt;if(t>900)effect=0;
  for(int i=0;i<5;i++){float f=base;if(effect==1&&int(t/80)%5==i)f=std::min(1.0f,base*2);else if(effect==2)f=base*(1+0.7f*sin(t*3.14159f/900));else if(effect==3)f=base*((t<150||(t>300&&t<450))?2:0.25f);else if(effect==4&&model.ham.online(now))f=base*(i*60/5<model.ham.volume?1.8f:0.12f);
    f=cp::clamp(f,0.0f,1.0f);leds.setPixelColor(i,leds.Color(((color>>16)&255)*f,((color>>8)&255)*f,(color&255)*f));}leds.show();
}
void setup(){
  pinMode(1,OUTPUT);digitalWrite(1,HIGH);pinMode(2,OUTPUT);digitalWrite(2,HIGH);pinMode(40,OUTPUT);digitalWrite(40,LOW);
  pinMode(45,INPUT_PULLUP);pinMode(42,INPUT_PULLUP);pinMode(41,INPUT_PULLUP);encoderPrevious=(digitalRead(45)<<1)|digitalRead(42);attachInterrupt(45,onEncoder,CHANGE);attachInterrupt(42,onEncoder,CHANGE);
  prefs.begin("crowpanel",false);if(prefs.getUChar("version",0)==1&&prefs.getBytesLength("settings")==sizeof(cp::Settings)){cp::Settings s;prefs.getBytes("settings",&s,sizeof s);if(s.valid())model.settings=s;}
  lcd.init();lcd.setBrightness(46);leds.begin();leds.clear();leds.show();pinMode(13,OUTPUT);digitalWrite(13,LOW);delay(10);digitalWrite(13,HIGH);delay(50);Wire1.begin(6,7,400000);Wire1.setTimeOut(10);Wire1.beginTransmission(0x15);Wire1.write(0xFE);Wire1.write(0xFF);Wire1.endTransmission();
  amp.begin(115200,SERIAL_8N1,44,43,true);tablet.begin(115200);USB.productName("CrowPanel Skoda Edition");USB.begin();
  lv_init();auto*d=lv_display_create(240,240);lv_display_set_color_format(d,LV_COLOR_FORMAT_RGB565);lv_display_set_buffers(d,drawBuffer,nullptr,sizeof drawBuffer,LV_DISPLAY_RENDER_MODE_PARTIAL);
  lv_display_set_flush_cb(d,[](lv_display_t*display,const lv_area_t*a,uint8_t*p){lcd.pushImage(a->x1,a->y1,a->x2-a->x1+1,a->y2-a->y1+1,reinterpret_cast<lgfx::rgb565_t*>(p));lv_display_flush_ready(display);});
  if(strlen(CROWPLAYER_WIFI_SSID)){WiFi.mode(WIFI_STA);WiFi.begin(CROWPLAYER_WIFI_SSID,CROWPLAYER_WIFI_PASSWORD);}lastTick=millis();model.activeAt=lastTick;
}
void loop(){
  uint32_t now=millis();lv_tick_inc(now-lastTick);lastTick=now;model.clock.advance(now);usbReceive(now);hamReceive(now);touch(now);
  int32_t steps;portENTER_CRITICAL(&encoderMux);steps=encoderSteps;encoderSteps=0;portEXIT_CRITICAL(&encoderMux);if(steps)rotate(cp::clamp(int(steps),-10,10));
  auto event=button.update(digitalRead(41)==LOW,now);if(event!=cp::ButtonEvent::None){bool asleep=model.screen==0;activity();if(!asleep){if(model.screen==4){if(event==cp::ButtonEvent::Hold){model.edit=false;model.screen=1;}else model.edit=!model.edit;saveLater();}else if(event==cp::ButtonEvent::Single)media("play_pause");else if(event==cp::ButtonEvent::Double)media("next");else if(model.ham.mute>=0)setHam(4,!model.ham.mute);}}
  if(model.settings.idleSeconds&&model.screen!=0&&now-model.activeAt>uint32_t(model.settings.idleSeconds)*1000){model.screen=0;model.edit=false;dirty=true;}
  if(settingsDirty&&now-settingsAt>2000){prefs.putBytes("settings",&model.settings,sizeof model.settings);prefs.putUChar("version",1);settingsDirty=false;}lighting(now);
  if((dirty&&now-lastRender>60)||now-lastRender>=1000){cp::render(model,now);lastRender=now;dirty=false;}lv_timer_handler();
  if(strlen(CROWPLAYER_WIFI_SSID)&&WiFi.status()==WL_CONNECTED){if(!otaReady){ArduinoOTA.setHostname("crowpanel-skoda");ArduinoOTA.begin();otaReady=true;}ArduinoOTA.handle();}delay(2);
}

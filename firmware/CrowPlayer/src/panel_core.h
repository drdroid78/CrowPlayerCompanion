#pragma once
#include <stdint.h>
#include <stddef.h>
#include <string.h>
#include <stdio.h>
#include <math.h>
#include <algorithm>
namespace cp {
template<class T> T clamp(T v,T lo,T hi){return std::min(hi,std::max(lo,v));}
struct Settings {
  uint8_t theme=7, dayHour=7, nightHour=22, dayLight=80, nightLight=18, dayLed=35, nightLed=6;
  uint16_t idleSeconds=30;
  bool tabletVolume=false;
  bool valid() const {return theme<8 && dayHour<24 && nightHour<24 && dayLight<=100 && nightLight<=100 && dayLed<=100 && nightLed<=100 && idleSeconds<=600;}
};
struct Clock {
  uint64_t epoch=0; uint32_t at=0; int offset=180;
  void sync(uint64_t ms,uint32_t now,int zone){if(ms>=1577836800000ULL && ms<4102444800000ULL && zone>=-720 && zone<=840){epoch=ms;at=now;offset=zone;}}
  void advance(uint32_t now){if(epoch){epoch+=uint32_t(now-at);at=now;}}
  int minute() const {return int(((epoch/60000)+offset)%1440);}
  void text(char* out) const {
    if(!epoch){strcpy(out,"--:--");return;}
    unsigned m=unsigned(minute())%1440;
    out[0]='0'+m/600;out[1]='0'+m/60%10;out[2]=':';out[3]='0'+m%60/10;out[4]='0'+m%10;out[5]=0;
  }
};
inline bool daytime(const Settings&s,int minute){int d=s.dayHour*60,n=s.nightHour*60;return d==n || (d<n ? minute>=d&&minute<n : minute>=d||minute<n);}
constexpr uint32_t splashDurationMs=3000,splashFadeMs=650;
inline float splashBacklightFactor(uint32_t age){
  if(age>=splashDurationMs)return 0.0f;
  if(age<splashFadeMs)return float(age)/splashFadeMs;
  if(age>=splashDurationMs-splashFadeMs)return float(splashDurationMs-age)/splashFadeMs;
  return 1.0f;
}
// A full four-transition cycle emits one step; a diagonal jump invalidates partial motion.
struct Quadrature {
  uint8_t previous=3; int8_t partial=0;
  int feed(uint8_t next){
    static constexpr int8_t table[16]={0,-1,1,0,1,0,0,-1,-1,0,0,1,0,1,-1,0};
    if((previous^next)==3){partial=0;previous=next;return 0;}
    partial+=table[(previous<<2)|next]; previous=next;
    if(partial>=4){partial=0;return 1;} if(partial<=-4){partial=0;return -1;} return 0;
  }
};
enum class ButtonEvent {None,Single,Double,Hold};
struct Button {
  bool raw=false,stable=false,held=false,pending=false; uint32_t changed=0,down=0,released=0;
  ButtonEvent update(bool pressed,uint32_t now){
    if(pressed!=raw){raw=pressed;changed=now;}
    if(raw!=stable && uint32_t(now-changed)>=25){
      stable=raw;
      if(stable){down=now;held=false;}
      else if(!held){if(pending && uint32_t(now-released)<=300){pending=false;return ButtonEvent::Double;} pending=true;released=now;}
    }
    if(stable&&!held&&uint32_t(now-down)>=700){held=true;pending=false;return ButtonEvent::Hold;}
    if(pending&&!stable&&uint32_t(now-released)>300){pending=false;return ButtonEvent::Single;}
    return ButtonEvent::None;
  }
};
// Keep the first tap pending until it is clear whether it is part of a pair.
struct TapPair {
  bool pending=false;int x=0,y=0,screen=0;uint32_t released=0;
  static constexpr uint32_t intervalMs=350;
  bool expired(uint32_t now)const{return pending&&uint32_t(now-released)>intervalMs;}
  bool matches(int px,int py,int currentScreen,uint32_t now)const{
    int dx=px-x,dy=py-y;return pending&&!expired(now)&&screen==currentScreen&&dx*dx+dy*dy<=40*40;
  }
  void start(int px,int py,int currentScreen,uint32_t now){pending=true;x=px;y=py;screen=currentScreen;released=now;}
  void clear(){pending=false;}
};
struct HamState {
  int volume=-1,sub=-128,source=-1,mute=-1; uint8_t sources[9]={},count=0;
  uint32_t lastRx=0; bool seen=false;
  bool online(uint32_t now)const{return seen&&uint32_t(now-lastRx)<3500;}
  bool advertised(uint8_t id)const{for(int i=0;i<count;i++)if(sources[i]==id)return true;return false;}
};
inline const char* sourceName(int id){static const char* names[]={"OPT","COAX","BT","AUX","AUX IN","HIGH","USB","UDP","SCP"};return id>=0&&id<9?names[id]:"--";}
inline int sourceCount(const HamState&s){return s.count?std::min<int>(s.count,5):5;}
inline uint8_t sourceAt(const HamState&s,int i){static const uint8_t fallback[5]={0,4,5,2,6};int count=sourceCount(s);i=clamp(i,0,count-1);return s.count?s.sources[i]:fallback[i];}
inline int sourceIndex(const HamState&s,int id){for(int i=0;i<sourceCount(s);i++)if(sourceAt(s,i)==id)return i;return -1;}
inline void sourcePosition(int i,int count,int&x,int&y){count=clamp(count,1,5);i=clamp(i,0,count-1);float a=(-90.0f+i*360.0f/count)*0.01745329252f;x=120+int(cosf(a)*76);y=118+int(sinf(a)*76);}
inline void sourcePositionForId(const HamState&s,int i,int&x,int&y){
  // Fixed positions match the source screen; unknown sources keep the
  // amplifier's advertised order rather than being assigned a wrong icon.
  const int count=sourceCount(s),id=sourceAt(s,i);
  const bool knownSet=!s.count||(s.count==5&&s.advertised(0)&&s.advertised(2)&&s.advertised(4)&&s.advertised(5)&&s.advertised(6));
  if(count==5&&knownSet){switch(id){case 2:x=120;y=43;return;case 0:x=43;y=91;return;case 6:x=197;y=91;return;case 5:x=64;y=177;return;case 4:x=176;y=177;return;}}
  sourcePosition(i,count,x,y);
}
inline uint8_t sourceCycleAt(const HamState&s,int i){
  const bool knownSet=!s.count||(s.count==5&&s.advertised(0)&&s.advertised(2)&&s.advertised(4)&&s.advertised(5)&&s.advertised(6));
  static const uint8_t clockwise[]={2,6,4,5,0};
  return sourceCount(s)==5&&knownSet?clockwise[clamp(i,0,4)]:sourceAt(s,i);
}
inline int sourceCycleIndex(const HamState&s,int id){for(int i=0;i<sourceCount(s);i++)if(sourceCycleAt(s,i)==id)return i;return -1;}
// Logical LED positions are top, then clockwise. Physical wiring orientation
// is applied separately by the board driver.
inline int sourceLedSlot(const HamState&s,int id){
  int index=sourceCycleIndex(s,id);if(index<0)return -1;
  int count=sourceCount(s);return ((index*5+count/2)/count)%5;
}
inline int stepLedSlot(int slot,int delta){return ((slot%5+delta%5)%5+5)%5;}
// Map the hardware LED index onto a logical position around the screen.
inline int physicalLedSlot(int index,int top,bool clockwise){return clockwise?stepLedSlot(index,-top):stepLedSlot(top,-index);}
struct Packet {uint8_t bytes[68]={};size_t size=0;};
inline Packet packet(uint8_t group,uint8_t command,uint8_t type,const uint8_t*data=nullptr,size_t n=0){
  Packet p;if(n>61)return p;p.size=n+7;p.bytes[0]=0xAA;p.bytes[1]=0x77;p.bytes[2]=n+3;p.bytes[3]=group;p.bytes[4]=command;p.bytes[5]=type;
  if(n)memcpy(p.bytes+6,data,n);
  uint8_t sum=0;for(size_t i=2;i<p.size-1;i++)sum+=p.bytes[i];p.bytes[p.size-1]=uint8_t(-sum);return p;
}
struct HamParser {
  Packet p; uint32_t lastByte=0;
  bool feed(uint8_t b,uint32_t now){
    if(p.size&&uint32_t(now-lastByte)>30)p.size=0;
    lastByte=now;
    if(!p.size){if(b==0xAA)p.bytes[p.size++]=b;return false;}
    if(p.size==1){if(b==0x77)p.bytes[p.size++]=b;else if(b!=0xAA)p.size=0;return false;}
    if(p.size==2&&(b<3||b>64)){p.size=0;return false;}
    p.bytes[p.size++]=b;
    if(p.size>=3&&p.size==size_t(p.bytes[2])+4){uint8_t sum=0;for(size_t i=2;i<p.size;i++)sum+=p.bytes[i];p.size=0;return sum==0;}
    return false;
  }
};
inline bool report(HamState&s,const Packet&p,uint32_t now){
  const uint8_t*b=p.bytes;if(b[3]!=1||b[5]!=0xDD)return false;size_t n=b[2]-3;const uint8_t*d=b+6;
  if(!n)return false;
  switch(b[4]){
    case 0: if(d[0]>9||n<size_t(1+d[0]))return false;for(int i=0;i<d[0];i++)if(d[1+i]>8)return false;s.count=d[0];memcpy(s.sources,d+1,s.count);break;
    case 1: if(d[0]<0x51||d[0]>0x59)return false;s.source=d[0]-0x51;break;
    case 2: {int v=int(int8_t(d[0]))+60;if(v<0||v>66)return false;s.volume=v;break;}
    case 3:s.sub=int8_t(d[0]);break;
    case 4:if(d[0]>1)return false;s.mute=d[0];break;
    default:return false;
  }s.seen=true;s.lastRx=now;return true;
}
struct LineBuffer {
  char data[2048]={};size_t size=0;bool overflow=false;
  bool feed(char c){if(c=='\n'){data[size]=0;bool ok=size&&!overflow;size=0;overflow=false;return ok;}if(c=='\r')return false;if(size<sizeof(data)-1&&!overflow)data[size++]=c;else overflow=true;return false;}
};
struct Model {
  Settings settings; Clock clock; HamState ham;
  int screen=1,setting=0,sourceSelection=-1;bool edit=false,subSelected=false,usb=false,usbHostConnected=false,playing=false,liked=false,likeKnown=false,likeAvailable=false,likePending=false,requestPending=false;
  bool volumeOverlay=false,volumeOverlaySub=false;uint32_t volumeOverlayUntil=0;
  int8_t volumeOverlayDirection=0;uint8_t volumeOverlayAmount=0,volumeOverlayTick=26;int16_t volumeOverlayValue=-1;
  bool artworkPresent=false;uint8_t* artwork=nullptr;
  char title[192]="Ожидание планшета",artist[128]="Подключите USB";
  uint32_t position=0,duration=0,positionAt=0,lastUsb=0,activeAt=0;
};
}


#include "../CrowPlayer/src/panel_core.h"
#include <assert.h>
#include <iostream>
using namespace cp;
static Packet parse(const uint8_t*b,size_t n){HamParser p;bool ready=false;for(size_t i=0;i<n;i++)ready=p.feed(b[i],100+i);assert(ready);return p.p;}
int main(){
  {Quadrature q;int steps=0;for(int n:{2,0,1,3})steps+=q.feed(n);assert(steps==-1);for(int n:{1,0,2,3})steps+=q.feed(n);assert(steps==0);
   for(int n:{2,3,2,3,2,0,1,3})steps+=q.feed(n);assert(steps==-1);q.feed(0);assert(q.partial==0);}
  {Button b;assert(b.update(true,0)==ButtonEvent::None);b.update(false,5);b.update(true,10);b.update(true,40);b.update(false,80);b.update(false,110);assert(b.update(false,411)==ButtonEvent::Single);
   Button h;h.update(true,0);h.update(true,25);assert(h.update(true,725)==ButtonEvent::Hold);h.update(false,800);assert(h.update(false,1200)==ButtonEvent::None);
   Button d;d.update(true,0);d.update(true,25);d.update(false,50);d.update(false,75);d.update(true,100);d.update(true,125);d.update(false,150);assert(d.update(false,175)==ButtonEvent::Double);}
  {TapPair t;t.start(100,100,0,0xfffffff0);assert(t.matches(110,105,0,0x40));assert(!t.matches(150,100,0,0x40));assert(!t.matches(100,100,1,0x40));assert(t.expired(0x151));t.clear();assert(!t.matches(100,100,0,0x40));}
  {Settings s;assert(daytime(s,420));assert(!daytime(s,419));assert(!daytime(s,1320));s.dayHour=22;s.nightHour=7;assert(daytime(s,1380));assert(daytime(s,60));assert(!daytime(s,600));}
  {Clock c;c.sync(1800000000000ULL,0xfffffff0,180);c.advance(16);assert(c.epoch==1800000000032ULL);assert(c.offset==180);char b[6];c.text(b);assert(strlen(b)==5);c.sync(5,0,0);assert(c.epoch==1800000000032ULL);}
  {uint8_t data=0x51;auto p=packet(1,1,0xBB,&data,1);uint8_t expected[]={0xAA,0x77,4,1,1,0xBB,0x51,0xEE};assert(p.size==8&&!memcmp(p.bytes,expected,8));
   uint8_t sub=8;p=packet(1,3,0xBB,&sub,1);assert(p.bytes[7]==0x35);uint8_t ack[]={0xAA,0x77,4,1,2,0xEE,0x0A,1};parse(ack,8);}
  {HamState s;uint8_t v[]={0xAA,0x77,4,1,3,0xDD,0xE7,0x34};auto p=parse(v,8);assert(report(s,p,100));assert(s.sub==-25&&s.online(100));assert(!s.online(3600));
   uint8_t raw=0xDD;p=packet(1,2,0xDD,&raw,1);assert(report(s,p,200)&&s.volume==25);
   uint8_t cfg[]={5,0,4,5,2,6};p=packet(1,0,0xDD,cfg,6);assert(report(s,p,300));assert(s.count==5&&s.advertised(6)&&!s.advertised(3));cfg[0]=8;p=packet(1,0,0xDD,cfg,6);assert(!report(s,p,300));
   uint8_t src[]={0x53};p=packet(1,1,0xDD,src,1);assert(report(s,p,400)&&s.source==2);}
  {HamState s;s.count=5;uint8_t advertised[]={0,4,5,2,6};memcpy(s.sources,advertised,5);
   const int ids[]={2,6,4,5,0};for(int i=0;i<5;i++){assert(sourceCycleAt(s,i)==ids[i]);assert(sourceCycleIndex(s,ids[i])==i);}
   int x,y;sourcePositionForId(s,sourceIndex(s,2),x,y);assert(x==120&&y==43);sourcePositionForId(s,sourceIndex(s,5),x,y);assert(x==64&&y==177);
   s.sources[4]=3;sourcePositionForId(s,0,x,y);int expectedX,expectedY;sourcePosition(0,5,expectedX,expectedY);assert(x==expectedX&&y==expectedY);assert(sourceCycleAt(s,0)==0);}
  {HamParser p;auto valid=packet(1,4,0x66);p.feed(0,0);p.feed(0xAA,1);p.feed(0xAA,2);p.feed(0x77,3);p.feed(0xFF,4);bool ready=false;for(size_t i=0;i<valid.size;i++)ready=p.feed(valid.bytes[i],10+i);assert(ready);
   valid.bytes[6]^=1;for(size_t i=0;i<valid.size;i++)assert(!p.feed(valid.bytes[i],30+i));p.feed(0xAA,50);assert(!p.feed(0x77,100));}
  {LineBuffer b;for(int i=0;i<3000;i++)assert(!b.feed('x'));assert(!b.feed('\n'));b.feed('{');b.feed('}');assert(b.feed('\n')&&!strcmp(b.data,"{}"));}
  std::cout<<"Panel core: encoder, button, clock, schedule, UART, framing passed\n";
}


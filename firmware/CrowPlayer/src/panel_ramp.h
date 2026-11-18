// Copyright (c) 2026 DrDroid78.
// Continuous 270-degree variable-width ring, rasterized once per changed value.
// The geometry follows Godwit Light; no arc/triangle seams or integer width steps.
#pragma once
#include <cstdint>
#include <cstdlib>
#include <cstring>
#include <cmath>
#include <algorithm>
#ifdef ARDUINO
#include <esp_heap_caps.h>
#endif
namespace cp { namespace smooth {
class Ramp {
  struct Pixel {uint16_t offset, phase;uint8_t coverage,radius;};
  static constexpr int Side=240,Capacity=9000;
  static constexpr size_t FrameBytes=Side*Side*sizeof(uint16_t);
  static constexpr float Pi=3.14159265358979323846f;
  void* memory_=nullptr;
  uint16_t* image_=nullptr;Pixel* mask_=nullptr;size_t count_=0;
  bool attempted_=false;int percent_=-1;uint32_t accent_=0;
  static float clamp01(float x){return std::max(0.0f,std::min(1.0f,x));}
  bool init(){
    if(attempted_)return memory_!=nullptr;
    attempted_=true;
    const size_t bytes=FrameBytes+Capacity*sizeof(Pixel);
#ifdef ARDUINO
    // Source images may reside in PSRAM; only the two SPI draw buffers must be internal.
    memory_=heap_caps_malloc(bytes,MALLOC_CAP_SPIRAM|MALLOC_CAP_8BIT);
#else
    memory_=std::malloc(bytes);
#endif
    if(!memory_)return false;
    image_=static_cast<uint16_t*>(memory_);
    mask_=reinterpret_cast<Pixel*>(static_cast<uint8_t*>(memory_)+FrameBytes);
    std::memset(image_,0xFF,FrameBytes);
    for(int y=0;y<Side;y++)for(int x=0;x<Side;x++){
      const float dx=x+0.5f-120.0f,dy=y+0.5f-120.0f;
      const float rr=dx*dx+dy*dy;
      if(rr<99.0f*99.0f||rr>115.0f*115.0f)continue;
      const float radius=std::sqrt(rr);
      const float angle=std::atan2(dx,-dy)*180.0f/Pi;
      const float t=(angle+135.0f)/270.0f;
      const float width=2.0f+12.0f*t;
      // Signed distances to outer circle, smooth inner spiral, and flat end caps.
      const float edge=std::min(std::min(114.0f-radius,radius-(114.0f-width)),
                      std::min((angle+135.0f)*radius*Pi/180.0f,
                               (135.0f-angle)*radius*Pi/180.0f));
      const float coverage=clamp01(edge+0.5f);
      if(coverage<=0.0f)continue;
      if(count_==Capacity){std::free(memory_);memory_=nullptr;image_=nullptr;return false;}
      mask_[count_++]={uint16_t(y*Side+x),uint16_t(std::lround(clamp01(t)*65535.0f)),
                      uint8_t(std::lround(coverage*255.0f)),uint8_t(std::lround(radius))};
    }
    return true;
  }
  static uint16_t rgb565(float r,float g,float b){
    const unsigned red=unsigned(std::lround(r*31.0f/255.0f));
    const unsigned green=unsigned(std::lround(g*63.0f/255.0f));
    const unsigned blue=unsigned(std::lround(b*31.0f/255.0f));
    return uint16_t((red<<11)|(green<<5)|blue);
  }
public:
  Ramp()=default;Ramp(const Ramp&)=delete;Ramp& operator=(const Ramp&)=delete;
  ~Ramp(){std::free(memory_);}
  bool valid()const{return memory_!=nullptr;}
  const uint16_t* data()const{return image_;}
  size_t pixels()const{return count_;}
  bool render(int percent,uint32_t accent){
    if(!init())return false;
    percent=std::max(0,std::min(100,percent));
    if(percent==percent_&&accent==accent_)return false;
    percent_=percent;accent_=accent;
    const float fill=percent*65535.0f/100.0f;
    const float red=(accent>>16)&255,green=(accent>>8)&255,blue=accent&255;
    for(size_t i=0;i<count_;i++){
      const Pixel&p=mask_[i];const float coverage=p.coverage/255.0f;
      const float filled=percent==0?0.0f:percent==100?1.0f:
        clamp01(0.5f+(fill-p.phase)*(270.0f*Pi/180.0f/65535.0f)*p.radius);
      const float r=255.0f+coverage*((202.0f+filled*(red-202.0f))-255.0f);
      const float g=255.0f+coverage*((202.0f+filled*(green-202.0f))-255.0f);
      const float b=255.0f+coverage*((202.0f+filled*(blue-202.0f))-255.0f);
      image_[p.offset]=rgb565(r,g,b);
    }
    return true;
  }
};
}} // namespace cp::smooth

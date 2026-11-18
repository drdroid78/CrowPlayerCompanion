// Copyright (c) 2026 DrDroid78.
#include "../CrowPlayer/src/panel_ramp.h"
#include <cassert>
#include <vector>
#include <iostream>
int main(){
  cp::smooth::Ramp r;
  assert(r.render(0,0x3D7B31)&&r.valid());
  assert(r.pixels()>3000&&r.pixels()<9000);
  std::vector<uint16_t> image(r.data(),r.data()+240*240);
  assert(!r.render(0,0x3D7B31));
  assert(std::equal(image.begin(),image.end(),r.data()));
  // Every interior cross-section of the wedge is solid: no slice boundaries.
  for(int value : {0,1,25,50,75,99,100}){
    r.render(value,0x3D7B31);
    for(int angle=-130;angle<=130;angle++){
      float t=(angle+135)/270.0f,rad=114-(2+12*t)*0.5f;
      int x=int(std::floor(120+rad*std::sin(angle*3.14159265358979323846f/180)));
      int y=int(std::floor(120-rad*std::cos(angle*3.14159265358979323846f/180)));
      assert(r.data()[y*240+x]!=0xFFFF);
    }
    assert(r.data()[120*240+120]==0xFFFF);
  }
  r.render(0,0x3D7B31);
  int blended=0;
  for(int i=0;i<240*240;i++){
    uint16_t v=r.data()[i];
    if(v!=0xFFFF&&v!=0xCE59&&v!=0xCE79)blended++;
  }
  assert(blended>100); // edge pixels contain intermediate coverage colours
  assert(!r.render(0,0x3D7B31));
  assert(r.render(50,0x3D7B31));
  std::cout<<"Continuous ramp: solid geometry, antialias coverage and cached updates passed\n";
}

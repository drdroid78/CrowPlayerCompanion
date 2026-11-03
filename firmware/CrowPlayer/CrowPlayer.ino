/*
 * CrowPlayer for Elecrow CrowPanel 1.28-inch HMI ESP32-S3 rotary display.
 * BLE HID media remote + BLE GATT metadata receiver for CrowPlayer Companion.
 *
 * Controls:
 *   encoder clockwise / counter-clockwise : volume up / down
 *   single click                           : play / pause
 *   double click                           : previous track
 *   hold for 700 ms                        : next track
 */
#include <Arduino.h>
#include <LovyanGFX.hpp>
#include <NimBLEDevice.h>
#include <HijelHID_BLEKeyboard.h>
#include <ArduinoJson.h>
#include <Adafruit_NeoPixel.h>

static constexpr int PIN_LCD_SCLK=10, PIN_LCD_MOSI=11, PIN_LCD_DC=3;
static constexpr int PIN_LCD_CS=9, PIN_LCD_RST=14, PIN_LCD_BL=46;
static constexpr int PIN_POWER_GATE=1;
static constexpr int PIN_NEOPIXEL=48;
static constexpr int NEOPIXEL_COUNT=5;
static constexpr uint8_t SKODA_GREEN_R=0, SKODA_GREEN_G=150, SKODA_GREEN_B=65;
static constexpr uint8_t NEOPIXEL_BRIGHTNESS=42;
static constexpr int PIN_ENC_A=45, PIN_ENC_B=42, PIN_ENC_SW=41;

class CrowDisplay : public lgfx::LGFX_Device {
  lgfx::Panel_GC9A01 _panel;
  lgfx::Bus_SPI _bus;
public:
  CrowDisplay() {
    auto b=_bus.config();
    b.spi_host=SPI2_HOST; b.spi_mode=0; b.freq_write=40000000;
    b.freq_read=16000000; b.spi_3wire=true; b.use_lock=true;
    b.dma_channel=SPI_DMA_CH_AUTO; b.pin_sclk=PIN_LCD_SCLK;
    b.pin_mosi=PIN_LCD_MOSI; b.pin_miso=-1; b.pin_dc=PIN_LCD_DC;
    _bus.config(b); _panel.setBus(&_bus);
    auto p=_panel.config(); p.pin_cs=PIN_LCD_CS; p.pin_rst=PIN_LCD_RST;
    p.pin_busy=-1; p.memory_width=240; p.memory_height=240;
    p.panel_width=240; p.panel_height=240; p.offset_x=0; p.offset_y=0;
    p.invert=true; p.rgb_order=false; p.dlen_16bit=false;
    _panel.config(p); setPanel(&_panel);
  }
};

CrowDisplay lcd;
Adafruit_NeoPixel accentLeds(NEOPIXEL_COUNT, PIN_NEOPIXEL, NEO_GRB + NEO_KHZ800);
HijelHID_BLEKeyboard keyboard("CrowPlayer", "CrowPlayer", 100);
static const char* SERVICE_UUID="7d2ea28a-f7bd-485a-bd9d-92ad6ecfe93e";
static const char* META_UUID   ="7d2ea28b-f7bd-485a-bd9d-92ad6ecfe93e";
static NimBLECharacteristic* metaChar=nullptr;

String trackTitle="Waiting for music";
String trackArtist="Open Yandex Music";
String playback="paused";
int volumeEstimate=50; // Estimate only; system volume is not readable over HID.
int lastA=HIGH;
bool lastButton=HIGH;
uint32_t buttonDownAt=0, lastClickAt=0;
bool longHandled=false, waitingSecondClick=false;
bool screenDirty=true;
bool lastPaired=false;

class MetaCallbacks : public NimBLECharacteristicCallbacks {
  void onWrite(NimBLECharacteristic* c, NimBLEConnInfo& info) override {
    std::string raw=c->getValue();
    if(raw.empty()) return;
    JsonDocument doc;
    if(deserializeJson(doc, raw.c_str())) return;
    const char* t=doc["title"] | "Unknown track";
    const char* a=doc["artist"] | "Unknown artist";
    const char* s=doc["state"] | "paused";
    trackTitle=String(t).substring(0,64);
    trackArtist=String(a).substring(0,64);
    playback=String(s);
    screenDirty=true;
  }
};

void drawCentered(const String& value, int y, int size, uint16_t color) {
  lcd.setTextSize(size); lcd.setTextColor(color, TFT_BLACK);
  lcd.setTextDatum(textdatum_t::middle_center);
  lcd.drawString(value,120,y);
}

// Fixed emerald-green accent lighting inspired by the requested Škoda theme.
void setAccentLighting() {
  accentLeds.begin();
  accentLeds.setBrightness(NEOPIXEL_BRIGHTNESS);
  const uint32_t green=accentLeds.Color(SKODA_GREEN_R,SKODA_GREEN_G,SKODA_GREEN_B);
  for(int i=0;i<NEOPIXEL_COUNT;i++) accentLeds.setPixelColor(i,green);
  accentLeds.show();
}

// Startup splash: custom stylized winged-arrow mark, not an official logo asset.
void drawStartupSplash() {
  lcd.fillScreen(TFT_BLACK);
  const uint16_t green=0x05A8; // deep emerald on RGB565 display
  lcd.drawCircle(120,103,66,green);
  lcd.drawCircle(120,103,61,0x2E66);
  // Stylized winged arrow inside the circular badge.
  lcd.fillTriangle(73,104,112,86,108,101,green);
  lcd.fillTriangle(73,104,108,105,112,119,green);
  lcd.fillTriangle(108,86,157,94,143,103,green);
  lcd.fillTriangle(108,105,143,105,157,114,green);
  lcd.fillTriangle(143,94,166,82,158,105,green);
  lcd.fillTriangle(143,105,158,105,166,126,green);
  lcd.fillTriangle(112,96,151,100,130,108,0xDFFF);
  lcd.fillTriangle(112,110,151,106,130,100,0xDFFF);
  lcd.fillCircle(130,104,5,green);
  lcd.setTextDatum(textdatum_t::middle_center);
  lcd.setTextColor(green,TFT_BLACK); lcd.setTextSize(2);
  lcd.drawString("SKODA",120,187);
  lcd.setTextColor(0xBDF7,TFT_BLACK); lcd.setTextSize(1);
  lcd.drawString("CROWPLAYER",120,210);
  delay(2200);
}

void drawPairing() {
  lcd.fillScreen(TFT_BLACK);
  lcd.drawCircle(120,120,113,0x5AEB); lcd.drawCircle(120,120,107,0x3186);
  drawCentered("CROWPLAYER",54,2,TFT_WHITE);
  lcd.drawCircle(120,105,29,0x5AEB); lcd.drawCircle(120,105,20,0x5AEB);
  lcd.drawLine(120,82,120,105,0x5AEB);
  drawCentered("BLUETOOTH",153,2,0x5AEB);
  drawCentered("Pair with CrowPlayer",180,1,TFT_WHITE);
  drawCentered("in tablet Bluetooth settings",198,1,0xBDF7);
  drawCentered("Then open CrowPlayer Companion",220,1,0x7BEF);
}

void drawPlayer() {
  lcd.fillScreen(TFT_BLACK);
  lcd.drawCircle(120,120,114,0x2925); lcd.drawCircle(120,120,108,0x4208);
  // Album-art placeholder. Current BLE metadata protocol sends title/artist/state.
  lcd.fillRoundRect(76,24,88,88,12,0x4018);
  lcd.fillCircle(120,68,23,0x801F); lcd.fillCircle(120,68,9,0x4018);
  String title=trackTitle;
  if(title.length()>22) title=title.substring(0,21)+"…";
  String artist=trackArtist;
  if(artist.length()>24) artist=artist.substring(0,23)+"…";
  drawCentered(title,132,1,TFT_WHITE);
  drawCentered(artist,151,1,0xBDF7);
  lcd.drawRoundRect(48,168,144,4,2,0x4208);
  lcd.fillRoundRect(48,168,72,4,2,0xF02D); // Decorative until position data is added.
  lcd.fillTriangle(70,199,82,191,82,207,0xF02D); lcd.fillRect(86,191,3,16,0xF02D);
  if(playback=="playing") {
    lcd.fillRect(111,190,5,18,TFT_WHITE); lcd.fillRect(122,190,5,18,TFT_WHITE);
  } else {
    lcd.fillTriangle(111,189,111,209,130,199,TFT_WHITE);
  }
  lcd.fillTriangle(170,199,158,191,158,207,0xF02D); lcd.fillRect(154,191,3,16,0xF02D);
  lcd.drawArc(120,120,116,111,220,320,0xF02D,0xF02D);
  drawCentered(String("VOL ")+volumeEstimate+"%",226,1,0xF02D);
  lcd.setTextDatum(textdatum_t::top_center); lcd.setTextSize(1);
  lcd.setTextColor(0x7BEF,TFT_BLACK); lcd.drawString("CROWPLAYER",120,8);
}

void sendMedia(uint16_t key) {
  if(keyboard.isPaired()) keyboard.tap(key);
}

void setup() {
  Serial.begin(115200);
  pinMode(PIN_POWER_GATE,OUTPUT); digitalWrite(PIN_POWER_GATE,HIGH);
  pinMode(PIN_LCD_BL,OUTPUT); digitalWrite(PIN_LCD_BL,HIGH);
  setAccentLighting();
  pinMode(PIN_ENC_A,INPUT_PULLUP); pinMode(PIN_ENC_B,INPUT_PULLUP);
  pinMode(PIN_ENC_SW,INPUT_PULLUP); lastA=digitalRead(PIN_ENC_A);

  lcd.init(); lcd.setRotation(0); lcd.fillScreen(TFT_BLACK);
  drawStartupSplash();
  drawPairing();

  keyboard.setLogLevel(HIDLogLevel::Normal);
  keyboard.begin();

  // Attach our metadata service to the NimBLE server created by the HID library.
  NimBLEServer* server=NimBLEDevice::getServer();
  if(server) {
    NimBLEService* service=server->createService(SERVICE_UUID);
    metaChar=service->createCharacteristic(META_UUID,
      NIMBLE_PROPERTY::WRITE | NIMBLE_PROPERTY::WRITE_NR);
    metaChar->setCallbacks(new MetaCallbacks());
    service->start();
    // Do not force the 128-bit UUID into the legacy advertising packet; HID name
    // remains discoverable and the Android companion scans for the name.
    NimBLEAdvertising* adv=NimBLEDevice::getAdvertising();
    if(adv) { adv->stop(); adv->start(); }
    Serial.println("CrowPlayer metadata GATT service ready");
  } else {
    Serial.println("ERROR: HID BLE server was not created");
  }
}

void loop() {
  // Quadrature encoder: clockwise raises volume, counter-clockwise lowers it.
  int a=digitalRead(PIN_ENC_A);
  if(a!=lastA && a==LOW) {
    if(digitalRead(PIN_ENC_B)!=a) {
      volumeEstimate=min(100,volumeEstimate+2);
      sendMedia(MEDIA_VOLUME_UP);
    } else {
      volumeEstimate=max(0,volumeEstimate-2);
      sendMedia(MEDIA_VOLUME_DOWN);
    }
    screenDirty=true;
  }
  lastA=a;

  // Button gestures: click=play/pause, double-click=previous, hold=next.
  bool sw=digitalRead(PIN_ENC_SW);
  if(lastButton==HIGH && sw==LOW) {
    buttonDownAt=millis(); longHandled=false;
  }
  if(sw==LOW && !longHandled && millis()-buttonDownAt>=700) {
    sendMedia(MEDIA_NEXT_TRACK); longHandled=true; waitingSecondClick=false;
  }
  if(lastButton==LOW && sw==HIGH && !longHandled) {
    uint32_t now=millis();
    if(waitingSecondClick && now-lastClickAt<350) {
      sendMedia(MEDIA_PREV_TRACK); waitingSecondClick=false;
    } else {
      waitingSecondClick=true; lastClickAt=now;
    }
  }
  if(waitingSecondClick && millis()-lastClickAt>=350) {
    sendMedia(MEDIA_PLAY_PAUSE); waitingSecondClick=false;
  }
  lastButton=sw;

  bool paired=keyboard.isPaired();
  if(paired!=lastPaired) { lastPaired=paired; screenDirty=true; }
  if(screenDirty) {
    if(paired) drawPlayer(); else drawPairing();
    screenDirty=false;
  }
  delay(3);
}

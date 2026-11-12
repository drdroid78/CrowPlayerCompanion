# CrowPlayer ŠKODA Edition

Сенсорный медиапульт для Elecrow CrowPanel 1.28-inch HMI ESP32-S3: связь с Android по USB, управление Hellion HAM DSP по инвертированному UART. Android Companion для Huawei MatePad SE / HarmonyOS.

Автор: **DrDroid78**.

См. `RELEASE_050_RU.md` для списка функций, настройки OTA и ограничений проверки.

Текущая разработка Android Companion: **0.6.3**, ветка `feature/crowplayer-0.6.0`. USB-соединение с панелью и управление избранным описаны в `RELEASE_063_RU.md`; вход в аккаунт Яндекса и предыдущий этап — в `RELEASE_062_RU.md`.

Новая прошивка панели: экраны LVGL, восемь тем, сенсорные жесты/энкодер, часы, день/ночь, пять LED, USB CDC и UART HAM. Компаньон 0.6.3 соединяется с ней как USB host по OTG и обменивается JSON по CDC. Сборка, управление, подключение 1,8 В и ограничения — [PANEL_FIRMWARE_RU.md](docs/PANEL_FIRMWARE_RU.md).

Android-проект находится в `android/`. Прошивка Arduino IDE — `firmware/CrowPlayer/CrowPlayer.ino`.

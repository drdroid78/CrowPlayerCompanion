# CrowPlayer ŠKODA Edition

Сенсорный медиапульт для Elecrow CrowPanel 1.28-inch HMI ESP32-S3: связь с Android по USB, управление Hellion HAM DSP по инвертированному UART. Android Companion для Huawei MatePad SE / HarmonyOS.

Автор: **DrDroid78**.

См. `RELEASE_050_RU.md` для списка функций, настройки OTA и ограничений проверки.

Текущая разработка Android Companion: **0.6.2**, ветка `feature/crowplayer-0.6.0`. Вход в аккаунт Яндекса, прямое управление избранным и порядок проверки на планшете описаны в `RELEASE_062_RU.md`. Предыдущие изменения медиакоманд — в `RELEASE_061_RU.md`.

Новая прошивка панели: пять экранов LVGL, восемь тем, сенсорные жесты/энкодер, часы, день/ночь, пять LED, USB CDC и UART HAM. Сборка, управление, подключение 1,8 В и ограничения — [PANEL_FIRMWARE_RU.md](docs/PANEL_FIRMWARE_RU.md). **Компаньон 0.6.2 ещё использует BLE: поддержка USB в Android — следующий отдельный этап.** Аппаратная проверка новой прошивки пока не проведена.

Android-проект находится в `android/`. Прошивка Arduino IDE — `firmware/CrowPlayer/CrowPlayer.ino`.

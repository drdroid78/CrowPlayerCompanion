# Android companion

Сборка выполняется из корня архива командой:

```bash
gradle -p android :app:assembleDebug
```

APK: `android/app/build/outputs/apk/debug/app-debug.apk`.

Приложение использует NotificationListenerService + MediaSessionManager для чтения активной медиасессии и USB host/CDC ACM для JSON-обмена с CrowPanel. Подключи панель кабелем OTG/USB-C к планшету, открой CrowPlayer и разреши доступ к устройству в системном запросе Android. Для чтения медиасессий отдельно включи доступ к уведомлениям.


## Версия 0.6.3

Компаньон ищет CrowPanel как CDC ACM устройство по USB OTG, запрашивает системное разрешение, отправляет состояние медиасессии и получает команды управления. Яркость и темы меняются на самой панели. USB CDC пока передаёт метаданные без обложки.


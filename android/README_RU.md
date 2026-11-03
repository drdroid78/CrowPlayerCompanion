# Android companion

Сборка выполняется из корня архива командой:

```bash
gradle -p android :app:assembleDebug
```

APK: `android/app/build/outputs/apk/debug/app-debug.apk`.

Приложение использует NotificationListenerService + MediaSessionManager для чтения активной медиасессии и BLE GATT client для отправки JSON на CrowPanel. Для чтения медиасессий нужно вручную разрешить доступ к уведомлениям в системных настройках. Для BLE нужно выдать приложению разрешения Bluetooth.

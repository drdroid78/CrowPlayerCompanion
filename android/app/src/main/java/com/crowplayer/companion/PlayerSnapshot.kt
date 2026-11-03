package com.crowplayer.companion

import android.graphics.Bitmap

/**
 * In-process snapshot populated by MusicNotificationListener.
 * This first build is a diagnostic prototype; BLE transport is added after
 * confirming that HarmonyOS exposes the Yandex Music media session.
 */
object PlayerSnapshot {
    @Volatile var title: String = "Нет данных о треке"
    @Volatile var artist: String = "Запусти Яндекс Музыку"
    @Volatile var album: String = ""
    @Volatile var packageName: String = ""
    @Volatile var playback: String = "Неизвестно"
    @Volatile var artwork: Bitmap? = null
    @Volatile var positionMs: Long = 0L
    @Volatile var durationMs: Long = 0L
    @Volatile var lastUpdated: Long = 0L

    fun clear() {
        title = "Нет данных о треке"
        artist = "Запусти Яндекс Музыку"
        album = ""
        packageName = ""
        playback = "Неизвестно"
        artwork = null
        positionMs = 0L
        durationMs = 0L
        lastUpdated = System.currentTimeMillis()
    }
}

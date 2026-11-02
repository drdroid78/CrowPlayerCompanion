package com.crowplayer.companion

import android.content.ComponentName
import android.media.MediaMetadata
import android.media.session.MediaController
import android.media.session.PlaybackState
import android.service.notification.NotificationListenerService
import android.service.notification.StatusBarNotification
import android.util.Log

class MusicNotificationListener : NotificationListenerService() {
    private val callbacks = mutableMapOf<String, MediaController.Callback>()

    override fun onListenerConnected() {
        super.onListenerConnected()
        refreshSessions()
    }

    override fun onNotificationPosted(sbn: StatusBarNotification?) {
        refreshSessions()
    }

    override fun onNotificationRemoved(sbn: StatusBarNotification?) {
        refreshSessions()
    }

    private fun refreshSessions() {
        try {
            val component = ComponentName(this, MusicNotificationListener::class.java)
            val controllers = getSystemService(android.media.session.MediaSessionManager::class.java)
                .getActiveSessions(component)

            val chosen = controllers
                .sortedByDescending { it.playbackState?.state == PlaybackState.STATE_PLAYING }
                .firstOrNull { it.metadata != null || it.playbackState != null }

            if (chosen == null) {
                PlayerSnapshot.clear()
                return
            }

            // Keep a callback attached so the screen refreshes on play/pause and track changes.
            val key = chosen.sessionToken.hashCode().toString()
            if (!callbacks.containsKey(key)) {
                val callback = object : MediaController.Callback() {
                    override fun onMetadataChanged(metadata: MediaMetadata?) {
                        updateSnapshot(chosen)
                    }

                    override fun onPlaybackStateChanged(state: PlaybackState?) {
                        updateSnapshot(chosen)
                    }

                    override fun onSessionDestroyed() {
                        refreshSessions()
                    }
                }
                chosen.registerCallback(callback)
                callbacks[key] = callback
            }
            updateSnapshot(chosen)
        } catch (e: SecurityException) {
            Log.e("CrowPlayer", "Нет доступа к активным медиасессиям", e)
            PlayerSnapshot.clear()
            PlayerSnapshot.playback = "Нет доступа"
        } catch (e: Exception) {
            Log.e("CrowPlayer", "Ошибка чтения медиасессии", e)
            PlayerSnapshot.clear()
            PlayerSnapshot.playback = "Ошибка чтения"
        }
    }

    private fun updateSnapshot(controller: MediaController) {
        val metadata = controller.metadata
        val state = controller.playbackState?.state

        PlayerSnapshot.title =
            metadata?.getString(MediaMetadata.METADATA_KEY_TITLE)
                ?: metadata?.getString(MediaMetadata.METADATA_KEY_DISPLAY_TITLE)
                ?: "Название недоступно"

        PlayerSnapshot.artist =
            metadata?.getString(MediaMetadata.METADATA_KEY_ARTIST)
                ?: metadata?.getString(MediaMetadata.METADATA_KEY_ALBUM_ARTIST)
                ?: "Исполнитель неизвестен"

        PlayerSnapshot.album =
            metadata?.getString(MediaMetadata.METADATA_KEY_ALBUM) ?: ""

        PlayerSnapshot.packageName = controller.packageName ?: ""

        PlayerSnapshot.playback = when (state) {
            PlaybackState.STATE_PLAYING, PlaybackState.STATE_FAST_FORWARDING,
            PlaybackState.STATE_REWINDING -> "Воспроизводится"
            PlaybackState.STATE_PAUSED -> "Пауза"
            PlaybackState.STATE_STOPPED -> "Остановлено"
            PlaybackState.STATE_BUFFERING -> "Загрузка"
            else -> "Состояние неизвестно"
        }

        PlayerSnapshot.artwork =
            metadata?.getBitmap(MediaMetadata.METADATA_KEY_ART)
                ?: metadata?.getBitmap(MediaMetadata.METADATA_KEY_ALBUM_ART)
                ?: metadata?.getBitmap(MediaMetadata.METADATA_KEY_DISPLAY_ICON)

        PlayerSnapshot.lastUpdated = System.currentTimeMillis()
    }
}

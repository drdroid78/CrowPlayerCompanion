package com.crowplayer.companion

import android.content.ComponentName
import android.media.MediaMetadata
import android.media.session.MediaController
import android.media.session.MediaSessionManager
import android.media.session.PlaybackState
import android.service.notification.NotificationListenerService
import android.util.Log
import android.os.Handler
import android.os.Looper

/** Reads the active Android media session exposed by Yandex Music / HarmonyOS. */
class MusicNotificationListener : NotificationListenerService() {
    private val handler = Handler(Looper.getMainLooper())
    private val poll = object : Runnable {
        override fun run() {
            updateSnapshot()
            handler.postDelayed(this, 1000L)
        }
    }

    override fun onListenerConnected() {
        super.onListenerConnected()
        handler.removeCallbacks(poll)
        handler.post(poll)
    }

    override fun onListenerDisconnected() {
        handler.removeCallbacks(poll)
        super.onListenerDisconnected()
    }

    override fun onDestroy() {
        handler.removeCallbacks(poll)
        super.onDestroy()
    }

    private fun updateSnapshot() {
        try {
            val manager = getSystemService(MEDIA_SESSION_SERVICE) as MediaSessionManager
            val sessions = manager.getActiveSessions(ComponentName(this, MusicNotificationListener::class.java))
            val controller = sessions.firstOrNull { it.playbackState?.state == PlaybackState.STATE_PLAYING }
                ?: sessions.firstOrNull()
            if (controller == null) {
                PlayerSnapshot.clear()
                return
            }
            applyController(controller)
        } catch (e: SecurityException) {
            PlayerSnapshot.playback = "Нет доступа к медиасессиям"
            PlayerSnapshot.lastUpdated = System.currentTimeMillis()
            Log.w("CrowPlayer", "Media session access unavailable", e)
        } catch (e: Exception) {
            Log.w("CrowPlayer", "Could not read media session", e)
        }
    }

    private fun applyController(controller: MediaController) {
        val metadata = controller.metadata
        val state = controller.playbackState?.state
        val title = metadata?.getString(MediaMetadata.METADATA_KEY_TITLE)
            ?: metadata?.getString(MediaMetadata.METADATA_KEY_DISPLAY_TITLE)
            ?: "Нет названия трека"
        val artist = metadata?.getString(MediaMetadata.METADATA_KEY_ARTIST)
            ?: metadata?.getString(MediaMetadata.METADATA_KEY_ALBUM_ARTIST)
            ?: "Исполнитель неизвестен"
        val artwork = metadata?.getBitmap(MediaMetadata.METADATA_KEY_ALBUM_ART)
            ?: metadata?.getBitmap(MediaMetadata.METADATA_KEY_ART)
        PlayerSnapshot.title = title.take(80)
        PlayerSnapshot.artist = artist.take(80)
        PlayerSnapshot.album = metadata?.getString(MediaMetadata.METADATA_KEY_ALBUM) ?: ""
        PlayerSnapshot.packageName = controller.packageName ?: ""
        PlayerSnapshot.playback = when (state) {
            PlaybackState.STATE_PLAYING, PlaybackState.STATE_BUFFERING -> "playing"
            PlaybackState.STATE_PAUSED, PlaybackState.STATE_STOPPED -> "paused"
            else -> "paused"
        }
        PlayerSnapshot.artwork = artwork
        PlayerSnapshot.lastUpdated = System.currentTimeMillis()
    }
}

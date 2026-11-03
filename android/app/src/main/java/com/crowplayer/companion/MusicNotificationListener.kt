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
    companion object {
        @Volatile var activeController: MediaController? = null
            private set
        fun playPause() {
            val controller = activeController ?: return
            if (controller.playbackState?.state == PlaybackState.STATE_PLAYING)
                controller.transportControls.pause()
            else controller.transportControls.play()
        }
        fun seekTo(positionMs: Long): Boolean {
            val controller = activeController ?: return false
            if ((controller.playbackState?.actions ?: 0L) and PlaybackState.ACTION_SEEK_TO == 0L) return false
            controller.transportControls.seekTo(positionMs.coerceAtLeast(0L))
            return true
        }
        private val optimisticLikes = mutableMapOf<String, Boolean>()

        @Synchronized
        fun toggleLike(): Boolean {
            val controller = activeController ?: return false
            if ((controller.playbackState?.actions ?: 0L) and PlaybackState.ACTION_SET_RATING == 0L) return false

            val metadata = controller.metadata
            val trackKey = metadata?.getString(MediaMetadata.METADATA_KEY_MEDIA_ID)
                ?.takeIf { it.isNotBlank() }
                ?: "${metadata?.getString(MediaMetadata.METADATA_KEY_TITLE).orEmpty()}|" +
                   metadata?.getString(MediaMetadata.METADATA_KEY_ARTIST).orEmpty()

            // isRated only says that a rating exists; it does NOT mean the heart is on.
            val rating = metadata?.getRating(MediaMetadata.METADATA_KEY_USER_RATING)
            val reportedLiked = rating?.hasHeart() == true
            val currentLiked = optimisticLikes[trackKey] ?: reportedLiked
            val nextLiked = !currentLiked

            // Keep the requested state locally because some media sessions update metadata
            // asynchronously (or do not echo the new rating back at all).
            optimisticLikes[trackKey] = nextLiked
            if (optimisticLikes.size > 100) optimisticLikes.clear()
            // Send an explicit heart=false rating to remove the like. An unrated
            // value is often ignored by media-session providers such as Yandex Music.
            val requestedRating = android.media.Rating.newHeartRating(nextLiked)
            controller.transportControls.setRating(requestedRating)
            return true
        }
    }
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
        activeController = null
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
                activeController = null
                PlayerSnapshot.clear()
                return
            }
            activeController = controller
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
        PlayerSnapshot.positionMs = (controller.playbackState?.position ?: 0L).coerceAtLeast(0L)
        PlayerSnapshot.durationMs = (metadata?.getLong(MediaMetadata.METADATA_KEY_DURATION) ?: 0L).coerceAtLeast(0L)
        PlayerSnapshot.lastUpdated = System.currentTimeMillis()
    }
}

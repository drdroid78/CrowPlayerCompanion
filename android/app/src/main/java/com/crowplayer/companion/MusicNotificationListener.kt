package com.crowplayer.companion

import android.content.ComponentName
import android.media.MediaMetadata
import android.media.Rating
import android.media.session.MediaController
import android.media.session.MediaSessionManager
import android.media.session.PlaybackState
import android.os.Handler
import android.os.Looper
import android.service.notification.NotificationListenerService
import android.util.Log
import java.util.concurrent.Executors

/** Reads the active Android media session exposed by Yandex Music / HarmonyOS. */
class MusicNotificationListener : NotificationListenerService() {
    companion object {
        @Volatile var activeController: MediaController? = null
            private set
        private val commandExecutor = Executors.newFixedThreadPool(2)
        private val optimisticLikes = mutableMapOf<String, Boolean>()

        fun playPause() {
            val controller = activeController ?: return
            commandExecutor.execute {
                try {
                    if (controller.playbackState?.state == PlaybackState.STATE_PLAYING)
                        controller.transportControls.pause()
                    else controller.transportControls.play()
                } catch (e: Exception) { Log.w("CrowPlayer", "Playback command failed", e) }
            }
        }

        fun seekTo(positionMs: Long): Boolean {
            val controller = activeController ?: return false
            if ((controller.playbackState?.actions ?: 0L) and PlaybackState.ACTION_SEEK_TO == 0L) return false
            commandExecutor.execute {
                try { controller.transportControls.seekTo(positionMs.coerceAtLeast(0L)) }
                catch (e: Exception) { Log.w("CrowPlayer", "Seek command failed", e) }
            }
            return true
        }

        /** Never performs a MediaSession Binder call on the UI thread. */
        fun toggleLike(onResult: (Boolean) -> Unit) {
            val controller = activeController
            if (controller == null) { onResult(false); return }
            val metadata = controller.metadata
            val trackKey = "${controller.packageName.orEmpty()}|" +
                (metadata?.getString(MediaMetadata.METADATA_KEY_MEDIA_ID)?.takeIf { it.isNotBlank() }
                    ?: "${metadata?.getString(MediaMetadata.METADATA_KEY_TITLE).orEmpty()}|" +
                       metadata?.getString(MediaMetadata.METADATA_KEY_ARTIST).orEmpty())

            val nextLiked: Boolean
            synchronized(optimisticLikes) {
                val reportedLiked = metadata?.getRating(MediaMetadata.METADATA_KEY_USER_RATING)?.hasHeart() == true
                val currentLiked = optimisticLikes[trackKey] ?: reportedLiked
                nextLiked = !currentLiked
                optimisticLikes[trackKey] = nextLiked
            }

            commandExecutor.execute {
                var success = false
                try {
                    // A fresh controller reference avoids sending to a session that was replaced.
                    val target = activeController
                    if (target != null && target.packageName == controller.packageName) {
                        target.transportControls.setRating(Rating.newHeartRating(nextLiked))
                        success = true
                    }
                } catch (e: Exception) {
                    Log.w("CrowPlayer", "Could not send heart rating", e)
                    synchronized(optimisticLikes) { optimisticLikes[trackKey] = !nextLiked }
                }
                Handler(Looper.getMainLooper()).post { onResult(success) }
            }
        }
    }

    private val handler = Handler(Looper.getMainLooper())
    private val poll = object : Runnable {
        override fun run() { updateSnapshot(); handler.postDelayed(this, 1000L) }
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
        } catch (e: Exception) { Log.w("CrowPlayer", "Could not read media session", e) }
    }

    private fun applyController(controller: MediaController) {
        val metadata = controller.metadata
        val state = controller.playbackState?.state
        val title = metadata?.getString(MediaMetadata.METADATA_KEY_TITLE)
            ?: metadata?.getString(MediaMetadata.METADATA_KEY_DISPLAY_TITLE) ?: "Нет названия трека"
        val artist = metadata?.getString(MediaMetadata.METADATA_KEY_ARTIST)
            ?: metadata?.getString(MediaMetadata.METADATA_KEY_ALBUM_ARTIST) ?: "Исполнитель неизвестен"
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

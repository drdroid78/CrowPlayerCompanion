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
        // MediaSession Binder calls run off the UI thread. A cached pool prevents
        // one stalled vendor/Yandex session call from blocking every later command.
        private val commandExecutor = Executors.newCachedThreadPool()
        private val mainHandler = Handler(Looper.getMainLooper())

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

        /**
         * Sends a heart rating only when the active session advertises SET_RATING.
         * The UI is updated only after the session reports the requested user rating
         * back through MediaMetadata; a successful Binder call alone is not confirmation.
         */
        fun toggleLike(onResult: (Boolean) -> Unit) {
            val controller = activeController
            if (controller == null) { mainHandler.post { onResult(false) }; return }
            val metadata = controller.metadata
            val currentRating = metadata?.getRating(MediaMetadata.METADATA_KEY_USER_RATING)
            val actions = controller.playbackState?.actions ?: 0L
            if (currentRating == null ||
                currentRating.ratingStyle != Rating.RATING_HEART ||
                actions and PlaybackState.ACTION_SET_RATING == 0L) {
                mainHandler.post { onResult(false) }
                return
            }

            val desired = !currentRating.hasHeart()
            val packageName = controller.packageName
            commandExecutor.execute {
                try {
                    val target = activeController
                    if (target == null || target.packageName != packageName) {
                        mainHandler.post { onResult(false) }
                        return@execute
                    }
                    target.transportControls.setRating(Rating.newHeartRating(desired))
                    // Wait for metadata confirmation; do not fabricate a local liked state.
                    var attempt = 0
                    val verify = object : Runnable {
                        override fun run() {
                            val fresh = activeController
                            val rating = if (fresh?.packageName == packageName)
                                fresh.metadata?.getRating(MediaMetadata.METADATA_KEY_USER_RATING)
                            else null
                            if (rating?.ratingStyle == Rating.RATING_HEART && rating.hasHeart() == desired) {
                                PlayerSnapshot.isLiked = desired
                                onResult(true)
                            } else if (++attempt < 6) {
                                mainHandler.postDelayed(this, 350L)
                            } else {
                                PlayerSnapshot.isLiked = currentRating.hasHeart()
                                onResult(false)
                            }
                        }
                    }
                    mainHandler.postDelayed(verify, 350L)
                } catch (e: Exception) {
                    Log.w("CrowPlayer", "Could not send heart rating", e)
                    mainHandler.post { PlayerSnapshot.isLiked = currentRating.hasHeart(); onResult(false) }
                }
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
        val reportedLiked = metadata?.getRating(MediaMetadata.METADATA_KEY_USER_RATING)?.hasHeart() == true
        PlayerSnapshot.isLiked = reportedLiked
        PlayerSnapshot.positionMs = (controller.playbackState?.position ?: 0L).coerceAtLeast(0L)
        PlayerSnapshot.durationMs = (metadata?.getLong(MediaMetadata.METADATA_KEY_DURATION) ?: 0L).coerceAtLeast(0L)
        PlayerSnapshot.lastUpdated = System.currentTimeMillis()
    }
}

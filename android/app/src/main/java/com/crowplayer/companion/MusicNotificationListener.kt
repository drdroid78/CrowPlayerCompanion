package com.crowplayer.companion

import android.app.Notification
import android.content.ComponentName
import android.media.MediaMetadata
import android.media.Rating
import android.media.session.MediaController
import android.media.session.MediaSession
import android.media.session.MediaSessionManager
import android.media.session.PlaybackState
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.service.notification.NotificationListenerService
import android.service.notification.StatusBarNotification
import android.util.Log
import java.util.concurrent.Executors

/** Reads the player and uses only commands actually exposed by its current session. */
class MusicNotificationListener : NotificationListenerService() {
    enum class LikeResult(val message: String) {
        CONFIRMED("Избранное обновлено"),
        UNAVAILABLE("Плеер не предоставил команду избранного. Подключи аккаунт Яндекса в настройках ниже."),
        UNCONFIRMED("Команда отправлена, но плеер не подтвердил изменение избранного"),
        TRACK_CHANGED("Трек сменился. Повтори действие для нового трека."),
        DISCONNECTED("Нет доступа к плееру. Проверь доступ к уведомлениям."),
        FAILED("Не удалось отправить команду избранного"),
        BUSY("Дождись подтверждения предыдущей команды"),
        API_AUTH(YandexProblem.Kind.AUTH.message),
        API_NETWORK(YandexProblem.Kind.NETWORK.message),
        API_TRACK(YandexProblem.Kind.TRACK.message),
        API_AMBIGUOUS(YandexProblem.Kind.AMBIGUOUS.message),
        API_RESPONSE(YandexProblem.Kind.RESPONSE.message),
        API_CANCELLED(YandexProblem.Kind.CANCELLED.message),
        API_UNCONFIRMED(YandexProblem.Kind.UNCONFIRMED.message)
    }

    companion object {
        @Volatile var activeController: MediaController? = null
            private set
        @Volatile private var listener: MusicNotificationListener? = null
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

        fun transport(action: String): Boolean {
            val controller = activeController ?: return false
            val actions = controller.playbackState?.actions ?: 0L
            val flag = when (action) {
                "play_pause" -> if (controller.playbackState?.state == PlaybackState.STATE_PLAYING)
                    PlaybackState.ACTION_PAUSE else PlaybackState.ACTION_PLAY
                "previous" -> PlaybackState.ACTION_SKIP_TO_PREVIOUS
                "next" -> PlaybackState.ACTION_SKIP_TO_NEXT
                else -> return false
            }
            if (actions and flag == 0L) return false
            commandExecutor.execute {
                try {
                    when (action) {
                        "play_pause" -> if (flag == PlaybackState.ACTION_PAUSE) controller.transportControls.pause()
                            else controller.transportControls.play()
                        "previous" -> controller.transportControls.skipToPrevious()
                        "next" -> controller.transportControls.skipToNext()
                    }
                } catch (e: Exception) { Log.w("CrowPlayer", "Panel transport command failed", e) }
            }
            return true
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

        fun toggleLike(onResult: (LikeResult) -> Unit) {
            mainHandler.post {
                val service = listener
                if (service == null) onResult(LikeResult.DISCONNECTED)
                else service.requestLike(onResult)
            }
        }

        internal fun yandexChoices() = listener?.yandexLikes?.choices().orEmpty()
        internal fun chooseYandexTrack(id: String) = listener?.yandexLikes?.choose(id) == true
    }

    private data class LikeCommand(val direction: LikeActionPolicy.Direction, val send: () -> Unit)
    private data class LikeSupport(val state: Boolean?, val commands: List<LikeCommand>, val report: String) {
        fun command(): LikeCommand? = LikeActionPolicy.choose(commands.map { it.direction }, state)?.let { commands[it] }
    }
    private class LikeRequest(
        val token: MediaSession.Token,
        val track: LikeActionPolicy.Track,
        val desired: Boolean,
        val callback: (LikeResult) -> Unit,
        val startedAt: Long = SystemClock.elapsedRealtime(),
        var sent: Boolean = false
    )

    private val handler = Handler(Looper.getMainLooper())
    private val notifications = mutableMapOf<String, StatusBarNotification>()
    @Volatile private var pendingLike: LikeRequest? = null
    private var yandexLikes: YandexLikes? = null
    private var apiRequestPending = false
    private val poll = object : Runnable {
        override fun run() { updateSnapshot(); handler.postDelayed(this, 1000L) }
    }
    private val verifyLike = object : Runnable {
        override fun run() {
            val request = pendingLike ?: return
            try {
                val controller = activeController
                when {
                    controller == null -> finishLike(request, LikeResult.DISCONNECTED)
                    !matches(request, controller) -> finishLike(request, LikeResult.TRACK_CHANGED)
                    request.sent && likeSupport(controller).state == request.desired -> {
                        applyController(controller)
                        finishLike(request, LikeResult.CONFIRMED)
                    }
                    SystemClock.elapsedRealtime() - request.startedAt >= 5000L ->
                        finishLike(request, LikeResult.UNCONFIRMED)
                    else -> handler.postDelayed(this, 250L)
                }
            } catch (e: Exception) {
                Log.w("CrowPlayer", "Could not confirm favourite", e)
                finishLike(request, LikeResult.FAILED)
            }
        }
    }

    override fun onListenerConnected() {
        super.onListenerConnected()
        listener = this
        notifications.clear()
        activeNotifications?.forEach { remember(it) }
        handler.removeCallbacks(poll)
        handler.post(poll)
    }

    override fun onNotificationPosted(sbn: StatusBarNotification) {
        if (remember(sbn)) updateSnapshot()
    }

    override fun onNotificationRemoved(sbn: StatusBarNotification) {
        if (notifications.remove(sbn.key) != null) updateSnapshot()
    }

    private fun remember(sbn: StatusBarNotification): Boolean {
        if (!sbn.notification.extras.containsKey(Notification.EXTRA_MEDIA_SESSION))
            return notifications.remove(sbn.key) != null
        notifications[sbn.key] = sbn
        return true
    }

    override fun onListenerDisconnected() {
        disconnect()
        super.onListenerDisconnected()
    }

    override fun onDestroy() {
        disconnect()
        super.onDestroy()
    }

    private fun disconnect() {
        handler.removeCallbacksAndMessages(null)
        val wasActive = listener === this
        if (wasActive) { listener = null; activeController = null }
        yandexLikes?.close()
        yandexLikes = null
        pendingLike?.let { finishLike(it, LikeResult.DISCONNECTED) }
        notifications.clear()
        if (wasActive) PlayerSnapshot.clear()
    }

    private fun apiTarget(controller: MediaController? = activeController): YandexLikes.Target? = controller?.let {
        YandexLikes.Target(it.sessionToken, it.packageName, track(it), it.metadata?.getString(MediaMetadata.METADATA_KEY_ALBUM).orEmpty())
    }

    private fun directLikes(): YandexLikes = yandexLikes ?: YandexLikes.create(this) { apiTarget() }.also { yandexLikes = it }

    private fun track(controller: MediaController): LikeActionPolicy.Track {
        val metadata = controller.metadata
        return LikeActionPolicy.Track(
            metadata?.getString(MediaMetadata.METADATA_KEY_MEDIA_ID),
            metadata?.getString(MediaMetadata.METADATA_KEY_TITLE)
                ?: metadata?.getString(MediaMetadata.METADATA_KEY_DISPLAY_TITLE),
            metadata?.getString(MediaMetadata.METADATA_KEY_ARTIST)
                ?: metadata?.getString(MediaMetadata.METADATA_KEY_ALBUM_ARTIST),
            metadata?.getLong(MediaMetadata.METADATA_KEY_DURATION) ?: 0L
        )
    }

    private fun matches(request: LikeRequest, controller: MediaController): Boolean =
        controller.sessionToken == request.token && track(controller) == request.track

    @Suppress("DEPRECATION")
    private fun likeSupport(controller: MediaController): LikeSupport {
        val metadata = controller.metadata
        val rating = metadata?.getRating(MediaMetadata.METADATA_KEY_USER_RATING)
        val ratedHeart = rating?.takeIf { it.ratingStyle == Rating.RATING_HEART && it.isRated }?.hasHeart()
        val playback = controller.playbackState
        val canRate = (playback?.actions ?: 0L) and PlaybackState.ACTION_SET_RATING != 0L &&
            (rating?.ratingStyle == Rating.RATING_HEART || controller.ratingType == Rating.RATING_HEART)
        val notificationCommands = mutableListOf<LikeCommand>()
        val customCommands = mutableListOf<LikeCommand>()
        val report = StringBuilder("CrowPlayer ${BuildConfig.VERSION_NAME}\nИсточник: ${controller.packageName}\n")
            .append("Рейтинг: style=${rating?.ratingStyle}, rated=${rating?.isRated}, heart=$ratedHeart\n")
            .append("SET_RATING: $canRate\nКоманды медиасессии:\n")
        playback?.customActions.orEmpty().forEach { action ->
            report.append("- ${action.action}: ${action.name}\n")
            LikeActionPolicy.direction(action.name, action.action)?.let { direction ->
                customCommands += LikeCommand(direction) {
                    controller.transportControls.sendCustomAction(action, action.extras)
                }
            }
        }
        report.append("Кнопки уведомления текущего трека:\n")
        val currentTrack = track(controller)
        notifications.values.filter { sbn ->
            val notification = sbn.notification
            sbn.packageName == controller.packageName &&
                notification.extras.getParcelable<MediaSession.Token>(Notification.EXTRA_MEDIA_SESSION) == controller.sessionToken &&
                !currentTrack.title.isNullOrBlank() &&
                notification.extras.getCharSequence(Notification.EXTRA_TITLE)?.toString() == currentTrack.title
        }.sortedByDescending { it.postTime }.take(1).forEach { sbn ->
            sbn.notification.actions.orEmpty().forEach { action ->
                report.append("- ${action.title}\n")
                val intent = action.actionIntent
                // A PendingIntent must belong to the same player as the session.
                if (intent != null && intent.creatorPackage == controller.packageName) {
                    LikeActionPolicy.direction(action.title)?.let { direction ->
                        notificationCommands += LikeCommand(direction) { intent.send() }
                    }
                }
            }
        }
        val state = ratedHeart ?: LikeActionPolicy.reportedState(notificationCommands.map { it.direction })
            ?: LikeActionPolicy.reportedState(customCommands.map { it.direction })
        val commands = notificationCommands + customCommands + if (canRate) listOf(
            LikeCommand(if (state == true) LikeActionPolicy.Direction.REMOVE else LikeActionPolicy.Direction.ADD) {
                controller.transportControls.setRating(Rating.newHeartRating(state != true))
            }
        ) else emptyList()
        return LikeSupport(state, commands, report.toString())
    }

    private fun requestLike(onResult: (LikeResult) -> Unit) {
        if (pendingLike != null || apiRequestPending) { onResult(LikeResult.BUSY); return }
        val controller = activeController
        if (controller == null) { onResult(LikeResult.DISCONNECTED); return }
        try {
            if (YandexLikes.isYandex(controller.packageName) && YandexAccountStore.get(this).read() != null) {
                apiRequestPending = true
                val started = directLikes().toggle { result ->
                    apiRequestPending = false
                    if (result.liked != null && listener === this) runCatching { activeController?.let { applyController(it) } }
                    val status = when (result.problem) {
                        null -> LikeResult.CONFIRMED
                        YandexProblem.Kind.AUTH -> LikeResult.API_AUTH
                        YandexProblem.Kind.NETWORK -> LikeResult.API_NETWORK
                        YandexProblem.Kind.TRACK -> LikeResult.API_TRACK
                        YandexProblem.Kind.AMBIGUOUS -> LikeResult.API_AMBIGUOUS
                        YandexProblem.Kind.CANCELLED -> LikeResult.API_CANCELLED
                        YandexProblem.Kind.UNCONFIRMED -> LikeResult.API_UNCONFIRMED
                        else -> LikeResult.API_RESPONSE
                    }
                    onResult(status)
                }
                if (!started) { apiRequestPending = false; onResult(LikeResult.DISCONNECTED) }
                return
            }
            val requestedTrack = track(controller)
            val support = likeSupport(controller)
            val command = support.command()
            if (track(controller) != requestedTrack) { onResult(LikeResult.TRACK_CHANGED); return }
            if (command == null || requestedTrack.title.isNullOrBlank()) {
                onResult(LikeResult.UNAVAILABLE)
                return
            }
            val request = LikeRequest(controller.sessionToken, requestedTrack, command.direction.liked, onResult)
            pendingLike = request
            // Start the watchdog before sending: a stuck vendor Binder must not disable the heart forever.
            handler.postDelayed(verifyLike, 250L)
            commandExecutor.execute {
                try {
                    val current = activeController
                    if (pendingLike !== request || listener !== this || current == null || !matches(request, current) ||
                        SystemClock.elapsedRealtime() - request.startedAt >= 5000L) {
                        handler.post { finishLike(request, LikeResult.TRACK_CHANGED) }
                        return@execute
                    }
                    command.send()
                    handler.post { if (pendingLike === request) request.sent = true }
                } catch (e: Exception) {
                    Log.w("CrowPlayer", "Favourite command failed", e)
                    handler.post { finishLike(request, LikeResult.FAILED) }
                }
            }
        } catch (e: Exception) {
            Log.w("CrowPlayer", "Favourite command unavailable", e)
            onResult(LikeResult.FAILED)
        }
    }

    private fun finishLike(request: LikeRequest, result: LikeResult) {
        if (pendingLike !== request) return
        pendingLike = null
        handler.removeCallbacks(verifyLike)
        request.callback(result)
    }

    private fun updateSnapshot() {
        try {
            val manager = getSystemService(MEDIA_SESSION_SERVICE) as MediaSessionManager
            val sessions = manager.getActiveSessions(ComponentName(this, MusicNotificationListener::class.java))
            val controller = sessions.firstOrNull { it.playbackState?.state == PlaybackState.STATE_PLAYING }
                ?: sessions.firstOrNull { it.sessionToken == activeController?.sessionToken }
                ?: sessions.firstOrNull()
            activeController = controller
            yandexLikes?.cancelIfTargetChanged()
            if (controller == null) {
                pendingLike?.let { finishLike(it, LikeResult.DISCONNECTED) }
                PlayerSnapshot.clear()
                return
            }
            pendingLike?.takeUnless { matches(it, controller) }?.let { finishLike(it, LikeResult.TRACK_CHANGED) }
            applyController(controller)
        } catch (e: SecurityException) {
            activeController = null
            yandexLikes?.cancelIfTargetChanged()
            pendingLike?.let { finishLike(it, LikeResult.DISCONNECTED) }
            PlayerSnapshot.clear()
            PlayerSnapshot.playback = "Нет доступа к медиасессиям"
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
        PlayerSnapshot.packageName = controller.packageName
        PlayerSnapshot.playback = when (state) {
            PlaybackState.STATE_PLAYING, PlaybackState.STATE_BUFFERING -> "playing"
            PlaybackState.STATE_PAUSED, PlaybackState.STATE_STOPPED -> "paused"
            else -> "paused"
        }
        PlayerSnapshot.artwork = artwork
        val support = likeSupport(controller)
        val direct = apiTarget(controller)?.let { directLikes().view(it) }
        val liked = if (direct != null) direct.liked else support.state
        PlayerSnapshot.isLiked = liked == true
        PlayerSnapshot.likeStateKnown = liked != null
        PlayerSnapshot.likeAvailable = direct != null || support.command() != null
        PlayerSnapshot.likeDiagnostics = support.report +
            "\nMedia ID: ${track(controller).mediaId.orEmpty().take(160)}\n" +
            "Аккаунт Яндекса: ${if (YandexAccountStore.get(this).read() != null) "подключён" else "не подключён"}\n" +
            (direct?.let { "Прямое избранное: ${it.message}\nID записи: ${it.id.orEmpty()}" } ?: "")
        PlayerSnapshot.positionMs = (controller.playbackState?.position ?: 0L).coerceAtLeast(0L)
        PlayerSnapshot.durationMs = (metadata?.getLong(MediaMetadata.METADATA_KEY_DURATION) ?: 0L).coerceAtLeast(0L)
        PlayerSnapshot.lastUpdated = System.currentTimeMillis()
    }
}

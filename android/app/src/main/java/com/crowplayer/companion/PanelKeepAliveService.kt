package com.crowplayer.companion

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Intent
import android.os.Handler
import android.os.Build
import android.os.IBinder
import android.os.Looper
import android.os.PowerManager
import org.json.JSONObject

/** Keeps USB-host polling and panel command delivery alive while the tablet display is off. */
class PanelKeepAliveService : Service() {
    companion object {
        const val ACTION_STATUS = "com.crowplayer.companion.PANEL_STATUS"
        const val EXTRA_STATUS = "status"
        private const val CHANNEL_ID = "crowplayer_panel_connection"
        private const val NOTIFICATION_ID = 1066
    }

    private var wakeLock: PowerManager.WakeLock? = null
    private var usb: UsbCdcLink? = null
    private val handler = Handler(Looper.getMainLooper())
    private val statePump = object : Runnable {
        override fun run() {
            usb?.sendState()
            handler.postDelayed(this, 250L)
        }
    }

    override fun onCreate() {
        super.onCreate()
        createNotificationChannel()
        val notification = Notification.Builder(this, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_launcher)
            .setContentTitle("CrowPlayer подключён")
            .setContentText("Связь с CrowPanel активна")
            .setCategory(Notification.CATEGORY_SERVICE)
            .setOngoing(true)
            .setShowWhen(false)
            .build()
        startForeground(NOTIFICATION_ID, notification)
        val power = getSystemService(POWER_SERVICE) as PowerManager
        wakeLock = power.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "CrowPlayer:PanelUsb").apply {
            setReferenceCounted(false)
            acquire()
        }
        usb = UsbCdcLink(applicationContext,
            onStatus = ::publishStatus,
            onCommand = ::handlePanelCommand)
        handler.post(statePump)
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        usb?.start()
        return START_STICKY
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onDestroy() {
        handler.removeCallbacksAndMessages(null)
        usb?.close()
        usb = null
        wakeLock?.let { if (it.isHeld) it.release() }
        wakeLock = null
        stopForeground(STOP_FOREGROUND_REMOVE)
        super.onDestroy()
    }

    private fun publishStatus(message: String) {
        sendBroadcast(Intent(ACTION_STATUS).setPackage(packageName).putExtra(EXTRA_STATUS, message))
    }

    private fun handlePanelCommand(command: JSONObject) {
        val id = command.optLong("id", -1L)
        if (id < 0L) return
        when (val action = command.optString("action")) {
            "play_pause", "previous", "next" -> {
                val ok = MusicNotificationListener.transport(action)
                usb?.sendResult(id, ok, if (PlayerSnapshot.likeStateKnown) PlayerSnapshot.isLiked else null)
            }
            "like" -> handlePanelLike(command, id)
            else -> usb?.sendResult(id, false, if (PlayerSnapshot.likeStateKnown) PlayerSnapshot.isLiked else null)
        }
    }

    private fun handlePanelLike(command: JSONObject, id: Long) {
        val actual = if (PlayerSnapshot.likeStateKnown) PlayerSnapshot.isLiked else null
        val sameTrack = command.optString("title").trim().equals(PlayerSnapshot.title.trim(), ignoreCase = true) &&
            command.optString("artist").trim().equals(PlayerSnapshot.artist.trim(), ignoreCase = true)
        val desired = command.opt("liked") as? Boolean
        if (!sameTrack || !PlayerSnapshot.likeAvailable || desired == null || (actual != null && desired == actual)) {
            val message = when {
                !sameTrack -> "Трек уже сменился"
                !PlayerSnapshot.likeAvailable -> "Войди в аккаунт Яндекса в приложении-компаньоне"
                else -> "Состояние избранного обновляется"
            }
            usb?.sendResult(id, false, actual, message)
            return
        }
        MusicNotificationListener.toggleLike { result ->
            val confirmed = result == MusicNotificationListener.LikeResult.CONFIRMED &&
                PlayerSnapshot.likeStateKnown && PlayerSnapshot.isLiked == desired
            usb?.sendResult(id, confirmed, if (PlayerSnapshot.likeStateKnown) PlayerSnapshot.isLiked else null, result.message)
        }
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT < 26) return
        val channel = NotificationChannel(CHANNEL_ID, "Подключение CrowPanel", NotificationManager.IMPORTANCE_LOW).apply {
            description = "Работа USB и медиакоманд при выключенном экране планшета"
            setShowBadge(false)
            enableVibration(false)
            setSound(null, null)
        }
        (getSystemService(NOTIFICATION_SERVICE) as NotificationManager).createNotificationChannel(channel)
    }
}

package com.crowplayer.companion

import android.app.Activity
import android.app.AlertDialog
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Intent
import android.content.ComponentName
import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.provider.Settings
import android.view.Gravity
import android.view.View
import android.widget.Button
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.SeekBar
import android.widget.TextView
import android.widget.Toast

/** Škoda-inspired dark/emerald companion UI. Media metadata remains supplied by MediaSession. */
class MainActivity : Activity() {
    private lateinit var status: TextView
    private lateinit var trackTitle: TextView
    private lateinit var likeButton: ImageView
    private var likeRequestPending = false
    private lateinit var likeFeedback: TextView
    private var likeFeedbackUntil = 0L
    private lateinit var artist: TextView
    private lateinit var playback: TextView
    private lateinit var source: TextView
    private lateinit var art: ImageView
    private lateinit var usbStatus: TextView
    private lateinit var notificationAccessStatus: TextView
    private lateinit var yandexAccountStatus: TextView
    private lateinit var yandexAccountButton: Button
    private var yandexLogin: YandexLoginDialog? = null
    private lateinit var usb: UsbCdcLink
    private lateinit var trackSeekBar: SeekBar
    private var userSeeking = false
    private val prefs by lazy { getSharedPreferences("crowplayer_settings", MODE_PRIVATE) }
    private val handler = Handler(Looper.getMainLooper())
    private val emerald = Color.rgb(0, 190, 125)
    private val muted = Color.rgb(151, 164, 160)
    private val panel = Color.rgb(21, 29, 29)
    private val refreshTask = object : Runnable {
        override fun run() { renderSnapshot(); handler.postDelayed(this, 250L) }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.statusBarColor = Color.rgb(7, 13, 13)
        window.navigationBarColor = Color.rgb(7, 13, 13)
        window.decorView.systemUiVisibility = 0
        usb = UsbCdcLink(this,
            onStatus = { message -> runOnUiThread { if (::usbStatus.isInitialized) usbStatus.text = message } },
            onCommand = { command -> runOnUiThread { handlePanelCommand(command) } })

        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(20), dp(12), dp(20), dp(18))
            background = GradientDrawable(GradientDrawable.Orientation.TL_BR,
                intArrayOf(Color.rgb(7, 13, 13), Color.rgb(12, 25, 22), Color.rgb(7, 13, 13)))
        }
        val scroll = ScrollView(this).apply { isFillViewport = true; clipToPadding = false }
        val content = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER_HORIZONTAL
            setPadding(0, dp(6), 0, dp(12))
        }
        scroll.addView(content)

        val header = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(2), dp(8), dp(2), dp(18))
        }
        val mark = TextView(this).apply {
            text = "Š"; textSize = 19f; gravity = Gravity.CENTER; setTextColor(Color.BLACK)
            typeface = Typeface.create("sans-serif-black", Typeface.BOLD)
            background = rounded(emerald, 14f)
        }
        header.addView(mark, LinearLayout.LayoutParams(dp(44), dp(44)))
        val brand = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(dp(12), 0, 0, 0) }
        brand.addView(label("ŠKODA  /  CROWPLAYER", 15f, Color.WHITE, true, Gravity.START))
        brand.addView(label("MUSIC COMPANION", 10f, emerald, true, Gravity.START))
        header.addView(brand, LinearLayout.LayoutParams(0, -2, 1f))
        header.addView(label(BuildConfig.VERSION_NAME, 11f, muted, true))
        content.addView(header)

        val statusRow = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL }
        status = label("MEDIA SESSION NOT FOUND", 10f, muted, true, Gravity.START)
        statusRow.addView(dot(emerald), LinearLayout.LayoutParams(dp(7), dp(7)))
        statusRow.addView(status, LinearLayout.LayoutParams(0, -2, 1f).apply { leftMargin = dp(9) })
        content.addView(statusRow, LinearLayout.LayoutParams(-1, -2).apply { bottomMargin = dp(12) })

        val playerCard = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL; gravity = Gravity.CENTER_HORIZONTAL
            setPadding(dp(18), dp(20), dp(18), dp(18)); background = rounded(panel, 24f)
        }
        val artFrame = FrameLayout(this).apply { background = rounded(Color.rgb(29, 51, 44), 22f); clipToOutline = true }
        art = ImageView(this).apply { scaleType = ImageView.ScaleType.CENTER_CROP; setBackgroundColor(Color.rgb(29, 51, 44)) }
        artFrame.addView(art, FrameLayout.LayoutParams(-1, -1))
        val placeholder = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; gravity = Gravity.CENTER }
        placeholder.addView(label("Š", 52f, emerald, true))
        placeholder.addView(label("CROWPLAYER", 10f, Color.WHITE, true))
        artFrame.addView(placeholder, FrameLayout.LayoutParams(-1, -1))
        art.tag = placeholder
        playerCard.addView(artFrame, LinearLayout.LayoutParams(dp(196), dp(196)).apply { bottomMargin = dp(14) })

        playerCard.addView(label("NOW PLAYING", 10f, emerald, true))
        val titleRow = FrameLayout(this)
        trackTitle = label("Нет данных о треке", 22f, Color.WHITE, true, Gravity.CENTER).apply {
            maxLines = 2
            setPadding(dp(42), dp(3), dp(42), dp(3))
            ellipsize = android.text.TextUtils.TruncateAt.END
        }
        titleRow.addView(trackTitle, FrameLayout.LayoutParams(-1, -2, Gravity.CENTER))
        likeButton = ImageView(this).apply {
            setImageResource(R.drawable.ic_heart_outline)
            scaleType = ImageView.ScaleType.CENTER_INSIDE
            contentDescription = "Добавить трек в избранное"
            isClickable = true
            isFocusable = true
            background = rounded(Color.TRANSPARENT, 24f)
            setPadding(dp(9), dp(9), dp(9), dp(9))
            setOnClickListener {
                requestTrackLike()
            }
        }
        titleRow.addView(likeButton, FrameLayout.LayoutParams(dp(44), dp(44), Gravity.END or Gravity.CENTER_VERTICAL))
        playerCard.addView(titleRow, LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(8) })
        artist = label("Запусти Яндекс Музыку", 15f, muted)
        playerCard.addView(artist, LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(5) })
        likeFeedback = label("", 12f, muted).apply { visibility = View.GONE }
        playerCard.addView(likeFeedback, LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(5) })

        val playbackRow = LinearLayout(this).apply { gravity = Gravity.CENTER; orientation = LinearLayout.HORIZONTAL }
        playback = label("ОЖИДАНИЕ", 10f, emerald, true).apply {
            isSingleLine = true
            includeFontPadding = true
            minHeight = dp(30)
            gravity = Gravity.CENTER
        }
        playbackRow.addView(playback, LinearLayout.LayoutParams(-1, dp(34)).apply {
            topMargin = dp(10)
        })
        playerCard.addView(playbackRow, LinearLayout.LayoutParams(-1, dp(44)))
        trackSeekBar = SeekBar(this).apply {
            max = 1000
            setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
                override fun onProgressChanged(seekBar: SeekBar?, progress: Int, fromUser: Boolean) {}
                override fun onStartTrackingTouch(seekBar: SeekBar?) { userSeeking = true }
                override fun onStopTrackingTouch(seekBar: SeekBar?) {
                    val duration = PlayerSnapshot.durationMs
                    if (duration > 0L) {
                        val target = duration * (seekBar?.progress ?: 0) / 1000L
                        if (!MusicNotificationListener.seekTo(target)) status.text = "Плеер не поддерживает перемотку"
                    }
                    userSeeking = false
                    renderSnapshot()
                }
            })
        }
        playerCard.addView(trackSeekBar, LinearLayout.LayoutParams(-1, dp(38)).apply { topMargin = dp(4) })
        content.addView(playerCard, LinearLayout.LayoutParams(-1, -2))

        val accountCard = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL; setPadding(dp(16), dp(15), dp(16), dp(15))
            background = rounded(Color.rgb(17, 25, 24), 18f)
        }
        accountCard.addView(label("ИЗБРАННОЕ ЯНДЕКС МУЗЫКИ", 10f, muted, true, Gravity.START))
        yandexAccountStatus = label("Аккаунт не подключён", 12f, muted, false, Gravity.START)
        accountCard.addView(yandexAccountStatus, LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(8) })
        accountCard.addView(label("Для прямого управления сердечком войди в тот же аккаунт, что и в Яндекс Музыке.", 12f, muted, false, Gravity.START))
        yandexAccountButton = actionButton("ВОЙТИ В ЯНДЕКС", false) {
            (yandexLogin ?: YandexLoginDialog(this).also { yandexLogin = it }).show()
        }
        accountCard.addView(yandexAccountButton, LinearLayout.LayoutParams(-1, dp(44)).apply { topMargin = dp(8) })
        content.addView(accountCard, LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(14) })

        val linkCard = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL; setPadding(dp(16), dp(15), dp(16), dp(15))
            background = rounded(Color.rgb(17, 25, 24), 18f)
        }
        linkCard.addView(label("ПОДКЛЮЧЕНИЕ УСТРОЙСТВА", 10f, muted, true, Gravity.START))
        usbStatus = label("Подключи панель кабелем USB OTG", 14f, Color.WHITE, true, Gravity.START)
        linkCard.addView(usbStatus, LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(8) })
        val connect = actionButton("ПОДКЛЮЧИТЬ ПО USB", true) { usb.start() }
        linkCard.addView(connect, LinearLayout.LayoutParams(-1, dp(48)).apply { topMargin = dp(13) })
        content.addView(linkCard, LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(14) })

        val settingsCard = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL; setPadding(dp(16), dp(15), dp(16), dp(15))
            background = rounded(Color.rgb(17, 25, 24), 18f)
        }
        settingsCard.addView(label("НАСТРОЙКА МЕДИАДАННЫХ", 10f, muted, true, Gravity.START))
        notificationAccessStatus = label("Проверка доступа…", 12f, muted, false, Gravity.START)
        settingsCard.addView(notificationAccessStatus, LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(8) })
        val settingsActions = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        settingsActions.addView(actionButton("ДОСТУП", false) {
            try { startActivity(Intent(Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS)) }
            catch (_: Exception) { startActivity(Intent(Settings.ACTION_SETTINGS)) }
        }, LinearLayout.LayoutParams(0, dp(40), 1f).apply { rightMargin = dp(5) })
        settingsActions.addView(actionButton("ОБНОВИТЬ", false) { renderSnapshot() },
            LinearLayout.LayoutParams(0, dp(40), 1f).apply { leftMargin = dp(5) })
        settingsCard.addView(settingsActions, LinearLayout.LayoutParams(-1, dp(40)).apply { topMargin = dp(10) })
        settingsCard.addView(actionButton("ДИАГНОСТИКА ПЛЕЕРА", false) {
            val report = PlayerSnapshot.likeDiagnostics
            AlertDialog.Builder(this)
                .setTitle("Управление избранным")
                .setMessage(report)
                .setPositiveButton("Скопировать") { _, _ ->
                    (getSystemService(CLIPBOARD_SERVICE) as ClipboardManager)
                        .setPrimaryClip(ClipData.newPlainText("CrowPlayer", report))
                    Toast.makeText(this, "Отчёт скопирован", Toast.LENGTH_SHORT).show()
                }
                .setNegativeButton("Закрыть", null)
                .show()
        }, LinearLayout.LayoutParams(-1, dp(40)).apply { topMargin = dp(8) })
        settingsCard.addView(label("Яркость, темы, LED и поведение энкодера настраиваются на самой панели.", 12f, muted, false, Gravity.START),
            LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(10) })
        content.addView(settingsCard, LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(12) })

        source = label("Источник: ожидаем Яндекс Музыку", 10f, muted)
        content.addView(source, LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(16) })
        content.addView(label("Оставь приложение открытым во время первого теста связи с CrowPanel.", 11f, muted),
            LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(5) })
        content.addView(label("CrowPlayer Škoda Edition  •  Автор: DrDroid78", 10f, muted),
            LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(14) })
        root.addView(scroll, LinearLayout.LayoutParams(-1, 0, 1f))
        setContentView(root)
    }

    private fun label(value: String, size: Float, color: Int, bold: Boolean = false, gravity: Int = Gravity.CENTER): TextView =
        TextView(this).apply {
            text = value; textSize = size; setTextColor(color); this.gravity = gravity
            if (bold) typeface = Typeface.create("sans-serif", Typeface.BOLD)
            setPadding(dp(2), dp(3), dp(2), dp(3))
        }

    private fun dot(color: Int): View = View(this).apply { background = GradientDrawable().apply { shape = GradientDrawable.OVAL; setColor(color) } }

    private fun rounded(color: Int, radius: Float): GradientDrawable = GradientDrawable().apply {
        setColor(color); cornerRadius = dp(radius).toFloat()
    }

    private fun actionButton(caption: String, primary: Boolean, action: () -> Unit): Button = Button(this).apply {
        text = caption; textSize = 11f; isAllCaps = false; typeface = Typeface.create("sans-serif", Typeface.BOLD)
        setTextColor(if (primary) Color.rgb(4, 18, 14) else Color.WHITE)
        background = rounded(if (primary) emerald else Color.rgb(37, 50, 47), 14f)
        stateListAnimator = null
        setOnClickListener { action() }
    }

    private fun hasNotificationAccess(): Boolean {
        val enabled = Settings.Secure.getString(contentResolver, "enabled_notification_listeners") ?: return false
        return enabled.split(":").any { ComponentName.unflattenFromString(it) == ComponentName(this, MusicNotificationListener::class.java) }
    }

    private fun refreshNotificationAccessStatus() {
        val enabled = hasNotificationAccess()
        notificationAccessStatus.text = if (enabled) "● Доступ к уведомлениям включён" else "○ Доступ выключен — включи его для работы плеера"
        notificationAccessStatus.setTextColor(if (enabled) emerald else Color.rgb(255, 185, 90))
    }

    private fun dp(value: Int): Int = (value * resources.displayMetrics.density + 0.5f).toInt()
    private fun dp(value: Float): Int = (value * resources.displayMetrics.density + 0.5f).toInt()

    override fun onResume() {
        super.onResume()
        refreshNotificationAccessStatus()
        val keepAlive = Intent(this, PanelKeepAliveService::class.java)
        if (android.os.Build.VERSION.SDK_INT >= 26) startForegroundService(keepAlive) else startService(keepAlive)
        usb.start()
        handler.removeCallbacks(refreshTask)
        handler.post(refreshTask)
        if (!prefs.getBoolean("notification_access_prompted", false)) {
            prefs.edit().putBoolean("notification_access_prompted", true).apply()
            if (!hasNotificationAccess()) handler.postDelayed({
                try { startActivity(Intent(Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS)) }
                catch (_: Exception) { startActivity(Intent(Settings.ACTION_SETTINGS)) }
            }, 350L)
        }
    }
    override fun onPause() { super.onPause() }
    override fun onDestroy() { handler.removeCallbacks(refreshTask); yandexLogin?.close(); usb.close(); super.onDestroy() }

    private fun requestTrackLike() {
        if (likeRequestPending) return
        likeRequestPending = true
        likeButton.isEnabled = false
        likeFeedback.text = "Обновляем избранное…"
        likeFeedbackUntil = Long.MAX_VALUE
        likeFeedback.visibility = View.VISIBLE
        MusicNotificationListener.toggleLike { result ->
            likeRequestPending = false
            if (isDestroyed || isFinishing) return@toggleLike
            likeFeedback.text = result.message
            likeFeedbackUntil = SystemClock.elapsedRealtime() + 10000L
            Toast.makeText(this, result.message, Toast.LENGTH_LONG).show()
            renderSnapshot()
            if (result == MusicNotificationListener.LikeResult.API_AMBIGUOUS) {
                val choices = MusicNotificationListener.yandexChoices()
                if (choices.isNotEmpty()) AlertDialog.Builder(this).setTitle("Выбери версию трека")
                    .setItems(choices.map { "${it.title} — ${it.artist}\n${it.album} · ${it.duration / 60000}:${(it.duration / 1000 % 60).toString().padStart(2, '0')}" }.toTypedArray()) { _, index ->
                        if (MusicNotificationListener.chooseYandexTrack(choices[index].id)) requestTrackLike()
                        else Toast.makeText(this, "Трек сменился. Нажми сердце для нового трека.", Toast.LENGTH_LONG).show()
                    }.setNegativeButton("Отмена", null).show()
            }
        }
    }

    private fun renderSnapshot() {
        val hasSession = PlayerSnapshot.lastUpdated > 0L && (PlayerSnapshot.packageName.isNotBlank() || PlayerSnapshot.playback == "Нет доступа к медиасессиям")
        val account = YandexAccountStore.get(this).read()
        yandexAccountStatus.text = account?.let { "Подключён: ${it.name}" } ?: "Аккаунт не подключён"
        yandexAccountButton.text = if (account == null) "ВОЙТИ В ЯНДЕКС" else "АККАУНТ ЯНДЕКСА"
        status.text = if (hasSession) "МЕДИАСЕССИЯ ОБНАРУЖЕНА" else "МЕДИАСЕССИЯ НЕ НАЙДЕНА"
        trackTitle.text = PlayerSnapshot.title
        likeButton.setImageResource(if (PlayerSnapshot.isLiked) R.drawable.ic_heart_filled else R.drawable.ic_heart_outline)
        likeButton.isEnabled = !likeRequestPending
        likeButton.alpha = if (PlayerSnapshot.likeAvailable && !likeRequestPending) 1f else 0.45f
        likeButton.contentDescription = when {
            likeRequestPending -> "Ожидание подтверждения избранного"
            !PlayerSnapshot.likeAvailable -> "Избранное недоступно. Нажми для пояснения."
            !PlayerSnapshot.likeStateKnown -> "Изменить избранное. Текущее состояние неизвестно."
            PlayerSnapshot.isLiked -> "Убрать из избранного"
            else -> "Добавить в избранное"
        }
        likeFeedback.visibility = if (SystemClock.elapsedRealtime() < likeFeedbackUntil) View.VISIBLE else View.GONE
        artist.text = if (PlayerSnapshot.album.isNotBlank()) "${PlayerSnapshot.artist}  ·  ${PlayerSnapshot.album}" else PlayerSnapshot.artist
        val playing = PlayerSnapshot.playback.equals("playing", true)
        playback.text = if (playing) "●  ВОСПРОИЗВЕДЕНИЕ" else "Ⅱ  ПАУЗА"
        playback.setTextColor(if (playing) emerald else muted)
        source.text = if (PlayerSnapshot.packageName.isNotBlank()) "Источник: ${PlayerSnapshot.packageName}" else "Источник не определён"
        val bitmap: Bitmap? = PlayerSnapshot.artwork
        val placeholder = art.tag as? View
        if (bitmap != null) { art.setImageBitmap(bitmap); placeholder?.visibility = View.GONE }
        else { art.setImageDrawable(null); placeholder?.visibility = View.VISIBLE }
        val duration = PlayerSnapshot.durationMs
        if (!userSeeking) {
            if (duration > 0L) {
                val basePosition = PlayerSnapshot.positionMs
                val elapsed = if (playing) (System.currentTimeMillis() - PlayerSnapshot.lastUpdated).coerceAtLeast(0L) else 0L
                val displayedPosition = (basePosition + elapsed).coerceIn(0L, duration)
                trackSeekBar.progress = ((displayedPosition * 1000L) / duration).toInt()
                trackSeekBar.isEnabled = true
            } else {
                trackSeekBar.progress = 0
                trackSeekBar.isEnabled = false
            }
        }
        usb.sendState()
    }

    private fun handlePanelCommand(command: org.json.JSONObject) {
        val id = command.optLong("id", -1L)
        if (id < 0L) return
        when (command.optString("action")) {
            "play_pause", "previous", "next" -> {
                val ok = MusicNotificationListener.transport(command.optString("action"))
                usb.sendResult(id, ok, if (PlayerSnapshot.likeStateKnown) PlayerSnapshot.isLiked else null)
            }
            "like" -> handlePanelLike(command, id)
            else -> usb.sendResult(id, false, if (PlayerSnapshot.likeStateKnown) PlayerSnapshot.isLiked else null)
        }
    }

    private fun handlePanelLike(command: org.json.JSONObject, id: Long) {
        val actual = if (PlayerSnapshot.likeStateKnown) PlayerSnapshot.isLiked else null
        val sameTrack = command.optString("title").trim().equals(PlayerSnapshot.title.trim(), ignoreCase = true) &&
            command.optString("artist").trim().equals(PlayerSnapshot.artist.trim(), ignoreCase = true)
        val desiredValue = command.opt("liked")
        val desired = desiredValue as? Boolean
        if (!sameTrack || !PlayerSnapshot.likeAvailable || desired == null || (actual != null && desired == actual)) {
            val message = when {
                !sameTrack -> "Трек уже сменился"
                !PlayerSnapshot.likeAvailable -> "Войди в аккаунт Яндекса в приложении-компаньоне"
                else -> "Состояние избранного обновляется"
            }
            usb.sendResult(id, false, actual, message)
            return
        }
        MusicNotificationListener.toggleLike { result ->
            val confirmed = result == MusicNotificationListener.LikeResult.CONFIRMED &&
                PlayerSnapshot.likeStateKnown && PlayerSnapshot.isLiked == desired
            usb.sendResult(id, confirmed, if (PlayerSnapshot.likeStateKnown) PlayerSnapshot.isLiked else null, result.message)
        }
    }
}

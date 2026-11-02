package com.crowplayer.companion

import android.app.Activity
import android.content.Intent
import android.graphics.Color
import android.graphics.Typeface
import android.net.Uri
import android.os.Bundle
import android.provider.Settings
import android.view.Gravity
import android.view.View
import android.widget.Button
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView

class MainActivity : Activity() {
    private lateinit var status: TextView
    private lateinit var title: TextView
    private lateinit var artist: TextView
    private lateinit var source: TextView
    private lateinit var playback: TextView
    private lateinit var art: ImageView
    private lateinit var refreshButton: Button
    private val handler = android.os.Handler(android.os.Looper.getMainLooper())

    private val refreshTask = object : Runnable {
        override fun run() {
            renderSnapshot()
            handler.postDelayed(this, 1000)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        val page = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER_HORIZONTAL
            setPadding(24, 24, 24, 24)
            setBackgroundColor(Color.rgb(17, 17, 22))
        }

        val scroll = ScrollView(this)
        val content = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER_HORIZONTAL
        }
        scroll.addView(content)

        fun text(value: String, size: Float, color: Int, bold: Boolean = false): TextView {
            return TextView(this).apply {
                text = value
                textSize = size
                setTextColor(color)
                gravity = Gravity.CENTER
                if (bold) setTypeface(typeface, Typeface.BOLD)
                setPadding(4, 8, 4, 8)
            }
        }

        content.addView(text("CROWPLAYER COMPANION", 20f, Color.WHITE, true))
        status = text("Проверяем доступ…", 14f, Color.rgb(255, 105, 135))
        content.addView(status)

        art = ImageView(this).apply {
            scaleType = ImageView.ScaleType.CENTER_CROP
            setBackgroundColor(Color.rgb(52, 37, 80))
        }
        content.addView(art, LinearLayout.LayoutParams(220, 220).apply {
            topMargin = 18
            bottomMargin = 12
        })

        title = text("Нет данных о треке", 20f, Color.WHITE, true)
        artist = text("Запусти Яндекс Музыку", 16f, Color.LTGRAY)
        playback = text("Состояние: неизвестно", 15f, Color.rgb(255, 105, 135))
        source = text("", 12f, Color.GRAY)

        content.addView(title)
        content.addView(artist)
        content.addView(playback)
        content.addView(source)

        val permissionButton = Button(this).apply {
            text = "Открыть доступ к уведомлениям"
            setOnClickListener {
                try {
                    startActivity(Intent(Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS))
                } catch (_: Exception) {
                    startActivity(Intent(Settings.ACTION_SETTINGS))
                }
            }
        }
        content.addView(permissionButton)

        refreshButton = Button(this).apply {
            text = "Обновить данные"
            setOnClickListener { renderSnapshot() }
        }
        content.addView(refreshButton)

        content.addView(text(
            "Как проверить: разреши доступ CrowPlayer к уведомлениям, " +
                "запусти Яндекс Музыку и включи любой трек. Вернись сюда.",
            13f, Color.LTGRAY
        ))

        page.addView(scroll, LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT, 0, 1f
        ))
        setContentView(page)
    }

    override fun onResume() {
        super.onResume()
        handler.removeCallbacks(refreshTask)
        handler.post(refreshTask)
    }

    override fun onPause() {
        handler.removeCallbacks(refreshTask)
        super.onPause()
    }

    private fun renderSnapshot() {
        val hasSession = PlayerSnapshot.lastUpdated > 0L &&
            (PlayerSnapshot.packageName.isNotBlank() ||
             PlayerSnapshot.playback == "Нет доступа")

        status.text = if (hasSession) {
            "Медиасессия обнаружена"
        } else {
            "Медиасессия пока не найдена"
        }

        title.text = PlayerSnapshot.title
        artist.text = PlayerSnapshot.artist
        playback.text = "Состояние: ${PlayerSnapshot.playback}"
        source.text = if (PlayerSnapshot.packageName.isNotBlank()) {
            "Источник: ${PlayerSnapshot.packageName}"
        } else {
            "Источник не определён"
        }

        val bitmap = PlayerSnapshot.artwork
        if (bitmap != null) {
            art.setImageBitmap(bitmap)
        } else {
            art.setImageDrawable(null)
            art.setBackgroundColor(Color.rgb(52, 37, 80))
        }
    }
}

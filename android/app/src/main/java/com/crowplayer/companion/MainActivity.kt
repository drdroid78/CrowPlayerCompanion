package com.crowplayer.companion

import android.Manifest
import android.app.Activity
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Color
import android.graphics.Typeface
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.view.Gravity
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
    private lateinit var bleStatus: TextView
    private lateinit var ble: BleLink
    private val handler = android.os.Handler(android.os.Looper.getMainLooper())
    private val refreshTask = object : Runnable {
        override fun run() { renderSnapshot(); handler.postDelayed(this, 1000) }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        ble = BleLink(this) { message -> runOnUiThread { bleStatus.text = message } }
        val page = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL; gravity = Gravity.CENTER_HORIZONTAL
            setPadding(24, 24, 24, 24); setBackgroundColor(Color.rgb(17, 17, 22))
        }
        val scroll = ScrollView(this)
        val content = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; gravity = Gravity.CENTER_HORIZONTAL }
        scroll.addView(content)
        fun text(value: String, size: Float, color: Int, bold: Boolean = false) = TextView(this).apply {
            text = value; textSize = size; setTextColor(color); gravity = Gravity.CENTER
            if (bold) setTypeface(typeface, Typeface.BOLD); setPadding(4, 8, 4, 8)
        }
        content.addView(text("CROWPLAYER COMPANION", 20f, Color.WHITE, true))
        status = text("Проверяем доступ…", 14f, Color.rgb(255, 105, 135)); content.addView(status)
        bleStatus = text("CrowPanel пока не подключён", 14f, Color.rgb(80, 220, 170)); content.addView(bleStatus)
        art = ImageView(this).apply { scaleType = ImageView.ScaleType.CENTER_CROP; setBackgroundColor(Color.rgb(52, 37, 80)) }
        content.addView(art, LinearLayout.LayoutParams(220, 220).apply { topMargin = 18; bottomMargin = 12 })
        title = text("Нет данных о треке", 20f, Color.WHITE, true)
        artist = text("Запусти Яндекс Музыку", 16f, Color.LTGRAY)
        playback = text("Состояние: неизвестно", 15f, Color.rgb(255, 105, 135))
        source = text("", 12f, Color.GRAY)
        content.addView(title); content.addView(artist); content.addView(playback); content.addView(source)
        content.addView(Button(this).apply {
            text = "Открыть доступ к уведомлениям"
            setOnClickListener { try { startActivity(Intent(Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS)) } catch (_: Exception) { startActivity(Intent(Settings.ACTION_SETTINGS)) } }
        })
        content.addView(Button(this).apply { text = "Подключить CrowPanel"; setOnClickListener { requestBlePermissionsAndStart() } })
        content.addView(Button(this).apply { text = "Обновить данные"; setOnClickListener { renderSnapshot() } })
        content.addView(text("Оставь приложение открытым во время проверки. Для управления медиакнопками сначала выполни сопряжение CrowPlayer в системных настройках Bluetooth.", 13f, Color.LTGRAY))
        page.addView(scroll, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, 0, 1f))
        setContentView(page)
    }

    private fun requestBlePermissionsAndStart() {
        if (Build.VERSION.SDK_INT >= 31) {
            val needed = arrayOf(Manifest.permission.BLUETOOTH_SCAN, Manifest.permission.BLUETOOTH_CONNECT)
                .filter { checkSelfPermission(it) != PackageManager.PERMISSION_GRANTED }
            if (needed.isNotEmpty()) { requestPermissions(needed.toTypedArray(), 71); return }
        } else if (Build.VERSION.SDK_INT >= 23 && checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION) != PackageManager.PERMISSION_GRANTED) {
            requestPermissions(arrayOf(Manifest.permission.ACCESS_FINE_LOCATION), 71); return
        }
        ble.start()
    }

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode == 71 && grantResults.isNotEmpty() && grantResults.all { it == PackageManager.PERMISSION_GRANTED }) ble.start()
    }
    override fun onResume() { super.onResume(); handler.removeCallbacks(refreshTask); handler.post(refreshTask) }
    override fun onPause() { handler.removeCallbacks(refreshTask); super.onPause() }
    override fun onDestroy() { ble.close(); super.onDestroy() }

    private fun renderSnapshot() {
        val hasSession = PlayerSnapshot.lastUpdated > 0L && (PlayerSnapshot.packageName.isNotBlank() || PlayerSnapshot.playback == "Нет доступа")
        status.text = if (hasSession) "Медиасессия обнаружена" else "Медиасессия пока не найдена"
        title.text = PlayerSnapshot.title; artist.text = PlayerSnapshot.artist
        playback.text = "Состояние: ${PlayerSnapshot.playback}"
        source.text = if (PlayerSnapshot.packageName.isNotBlank()) "Источник: ${PlayerSnapshot.packageName}" else "Источник не определён"
        val bitmap = PlayerSnapshot.artwork
        if (bitmap != null) art.setImageBitmap(bitmap) else { art.setImageDrawable(null); art.setBackgroundColor(Color.rgb(52, 37, 80)) }
        ble.send(PlayerSnapshot.title, PlayerSnapshot.artist, PlayerSnapshot.playback)
    }
}

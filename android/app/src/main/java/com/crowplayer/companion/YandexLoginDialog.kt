package com.crowplayer.companion

import android.app.Activity
import android.app.AlertDialog
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.widget.Toast
import java.util.concurrent.Executors
import java.util.concurrent.ExecutorService

/** Login credentials are entered on Yandex's page in the external browser. */
internal class YandexLoginDialog(
    private val activity: Activity,
    private val api: YandexMusicApi = YandexMusicApi(),
    private val store: YandexAccountStore = YandexAccountStore.get(activity),
    private val io: ExecutorService = Executors.newSingleThreadExecutor()
) {
    private val handler = Handler(Looper.getMainLooper())
    @Volatile private var generation = 0
    private var dialog: AlertDialog? = null

    fun show() {
        val account = store.read()
        if (account == null) begin()
        else AlertDialog.Builder(activity)
            .setTitle("Яндекс Музыка")
            .setMessage("Аккаунт: ${account.name}\n\nОн должен совпадать с аккаунтом в приложении Яндекс Музыки. Сердечко изменяет избранное этого аккаунта.")
            .setPositiveButton("Выйти") { _, _ ->
                try { store.clear(); toast("Аккаунт отключён") }
                catch (_: Exception) { toast("Не удалось отключить аккаунт") }
            }
            .setNeutralButton("Сменить аккаунт") { _, _ -> begin() }
            .setNegativeButton("Закрыть", null).show().also { dialog = it }
    }

    private fun begin() {
        dialog?.dismiss()
        val request = ++generation
        val window = AlertDialog.Builder(activity).setTitle("Вход в Яндекс Музыку")
            .setMessage("Получаем код входа…")
            .setPositiveButton("Открыть Яндекс", null)
            .setNeutralButton("Скопировать код", null)
            .setNegativeButton("Отмена", null).create()
        dialog = window
        window.setOnDismissListener { if (generation == request) generation++ }
        window.show()
        window.getButton(AlertDialog.BUTTON_POSITIVE).isEnabled = false
        window.getButton(AlertDialog.BUTTON_NEUTRAL).isEnabled = false
        io.execute {
            try {
                val code = api.beginLogin()
                val deadline = SystemClock.elapsedRealtime() + code.expires * 1000L
                handler.post {
                    if (!current(request)) return@post
                    window.setMessage("Код: ${code.userCode}\n\nОткрой страницу Яндекса, войди в тот же аккаунт, что и в Яндекс Музыке, и введи код. После подтверждения вернись сюда.")
                    window.getButton(AlertDialog.BUTTON_POSITIVE).apply {
                        isEnabled = true
                        setOnClickListener {
                            try { activity.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(code.url))) }
                            catch (_: Exception) { toast("Открой ${code.url} в браузере и введи код") }
                        }
                    }
                    window.getButton(AlertDialog.BUTTON_NEUTRAL).apply {
                        isEnabled = true
                        setOnClickListener {
                            (activity.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager)
                                .setPrimaryClip(ClipData.newPlainText("Код входа Яндекса", code.userCode))
                            toast("Код скопирован")
                        }
                    }
                    poll(request, code, deadline, code.interval)
                }
            } catch (e: Exception) { handler.post { failure(request, e) } }
        }
    }

    private fun poll(request: Int, code: YandexMusicApi.DeviceCode, deadline: Long, interval: Int) {
        if (!current(request)) return
        if (SystemClock.elapsedRealtime() >= deadline) { failure(request, YandexProblem(YandexProblem.Kind.EXPIRED)); return }
        io.execute {
            try {
                val token = api.pollLogin(code.code)
                if (!current(request)) return@execute
                val account = api.account(token.access)
                handler.post {
                    if (!current(request)) return@post
                    try {
                        store.save(token, account)
                        dialog?.dismiss()
                        toast("Подключён аккаунт: ${account.name}")
                    } catch (_: Exception) { failure(request, Exception()) }
                }
            } catch (e: Exception) {
                handler.post {
                    if (!current(request)) return@post
                    val pending = (e as? YandexProblem)?.kind
                    if (pending == YandexProblem.Kind.PENDING || pending == YandexProblem.Kind.SLOW_DOWN) {
                        val delay = if (pending == YandexProblem.Kind.SLOW_DOWN) (interval + 5).coerceAtMost(60) else interval
                        handler.postDelayed({ poll(request, code, deadline, delay) }, delay * 1000L)
                    } else failure(request, e)
                }
            }
        }
    }

    private fun failure(request: Int, error: Exception) {
        if (!current(request)) return
        generation++
        dialog?.setMessage((error as? YandexProblem)?.kind?.message ?: "Не удалось завершить вход. Проверь интернет и повтори попытку.")
        dialog?.getButton(AlertDialog.BUTTON_POSITIVE)?.apply {
            text = "Повторить"
            isEnabled = true
            setOnClickListener { begin() }
        }
        dialog?.getButton(AlertDialog.BUTTON_NEUTRAL)?.isEnabled = false
    }

    private fun current(request: Int) = generation == request && !activity.isDestroyed && !activity.isFinishing && dialog?.isShowing == true
    private fun toast(message: String) = Toast.makeText(activity, message, Toast.LENGTH_LONG).show()
    fun close() { generation++; handler.removeCallbacksAndMessages(null); dialog?.dismiss(); io.shutdownNow() }
}

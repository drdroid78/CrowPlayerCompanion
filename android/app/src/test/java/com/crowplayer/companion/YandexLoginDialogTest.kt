package com.crowplayer.companion

import android.app.Activity
import android.app.AlertDialog
import android.os.Looper
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.annotation.LooperMode
import org.robolectric.shadows.ShadowAlertDialog
import java.util.concurrent.TimeUnit

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28])
@LooperMode(LooperMode.Mode.PAUSED)
class YandexLoginDialogTest {
    private val activity = Robolectric.buildActivity(Activity::class.java).setup().get()
    private val server = FakeYandexServer()
    private val queue = TestQueue()
    private val store = YandexAccountStore(activity, object : AccountCipher {
        override fun encrypt(value: String) = value.reversed()
        override fun decrypt(value: String) = value.reversed()
    })
    private val login = YandexLoginDialog(activity, YandexMusicApi(server), store, queue)
    private val main get() = shadowOf(Looper.getMainLooper())
    private fun drain() { while (queue.hasTasks()) { queue.runOne(); main.idle() }; main.idle() }

    @Test fun `device flow opens Yandex and saves account only after token and account verification`() {
        login.show(); drain()
        assertNull(store.read())
        val dialog = ShadowAlertDialog.getLatestAlertDialog()
        dialog.getButton(AlertDialog.BUTTON_POSITIVE).performClick()
        val intent = shadowOf(activity).nextStartedActivity
        assertEquals("oauth.yandex.ru", intent.data!!.host)
        assertFalse(intent.dataString!!.contains("access_token"))
        assertTrue(dialog.isShowing)
        server.ready = true
        main.idleFor(5, TimeUnit.SECONDS); drain()
        assertEquals("1", store.read()!!.uid)
        assertEquals("test-account-token", store.read()!!.access)
        assertFalse(dialog.isShowing)
        login.close()
    }

    @Test fun `cancel stops polling without creating an account`() {
        login.show(); drain()
        val calls = server.requests.size
        ShadowAlertDialog.getLatestAlertDialog().dismiss()
        main.idleFor(15, TimeUnit.SECONDS); drain()
        assertEquals(calls, server.requests.size)
        assertNull(store.read())
        login.close()
    }

    @Test fun `late successful token response is discarded after cancel`() {
        login.show()
        queue.runOne(); main.idle() // Device code arrived; token request is queued.
        server.ready = true
        queue.runOne() // Token and account arrived, but UI has not processed them yet.
        ShadowAlertDialog.getLatestAlertDialog().dismiss()
        main.idle()
        assertNull(store.read())
        login.close()
    }
}

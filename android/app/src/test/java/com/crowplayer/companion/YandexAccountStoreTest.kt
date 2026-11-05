package com.crowplayer.companion

import android.content.Context
import android.util.Base64
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.spec.GCMParameterSpec

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28])
class YandexAccountStoreTest {
    private val context get() = RuntimeEnvironment.getApplication()
    // The JVM supplies AES for storage tests; production keys come from Android Keystore.
    private class TestCipher : AccountCipher {
        private val key = KeyGenerator.getInstance("AES").apply { init(256) }.generateKey()
        override fun encrypt(value: String): String {
            val cipher = Cipher.getInstance("AES/GCM/NoPadding").apply { init(Cipher.ENCRYPT_MODE, key) }
            return Base64.encodeToString(cipher.iv + cipher.doFinal(value.toByteArray()), Base64.NO_WRAP)
        }
        override fun decrypt(value: String): String {
            val bytes = Base64.decode(value, Base64.NO_WRAP)
            val cipher = Cipher.getInstance("AES/GCM/NoPadding").apply { init(Cipher.DECRYPT_MODE, key, GCMParameterSpec(128, bytes.copyOfRange(0, 12))) }
            return cipher.doFinal(bytes.copyOfRange(12, bytes.size)).toString(Charsets.UTF_8)
        }
    }

    @Test fun `stored credentials are encrypted and can be reopened`() {
        val cipher = TestCipher()
        val store = YandexAccountStore(context, cipher)
        store.save(YandexMusicApi.Token("private-access-token", 3600), YandexMusicApi.Account("1", "Name"))
        val persisted = context.getSharedPreferences("yandex_account_v1", Context.MODE_PRIVATE).all.toString()
        assertFalse(persisted.contains("private-access-token"))
        val reopened = YandexAccountStore(context, cipher).read()!!
        assertEquals("private-access-token", reopened.access)
        assertEquals("1", reopened.uid)
        assertTrue(reopened.expiresAt > System.currentTimeMillis())
        assertFalse(reopened.toString().contains("private-access-token"))
    }

    @Test fun `logout removes both cached and persisted credentials`() {
        val cipher = TestCipher()
        val store = YandexAccountStore(context, cipher)
        store.save(YandexMusicApi.Token("private", 100), YandexMusicApi.Account("1", "Name"))
        store.clear()
        assertNull(store.read())
        assertNull(YandexAccountStore(context, cipher).read())
    }

    @Test fun `corrupt storage does not produce an authenticated session`() {
        context.getSharedPreferences("yandex_account_v1", Context.MODE_PRIVATE).edit().putString("sealed", "damaged").commit()
        assertNull(YandexAccountStore(context, TestCipher()).read())
    }
}

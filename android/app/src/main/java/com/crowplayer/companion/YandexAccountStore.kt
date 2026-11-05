package com.crowplayer.companion

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import org.json.JSONObject
import java.security.KeyStore
import java.util.UUID
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

internal interface AccountCipher {
    fun encrypt(value: String): String
    fun decrypt(value: String): String
}

internal class AndroidAccountCipher : AccountCipher {
    private val alias = "crowplayer.yandex.account.v1"
    private fun key(): SecretKey {
        val store = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
        return (store.getKey(alias, null) as? SecretKey) ?: KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore").run {
            init(KeyGenParameterSpec.Builder(alias, KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM).setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE).build())
            generateKey()
        }
    }

    override fun encrypt(value: String): String {
        val cipher = Cipher.getInstance("AES/GCM/NoPadding").apply { init(Cipher.ENCRYPT_MODE, key()) }
        val bytes = cipher.doFinal(value.toByteArray(Charsets.UTF_8))
        return Base64.encodeToString(byteArrayOf(cipher.iv.size.toByte()) + cipher.iv + bytes, Base64.NO_WRAP)
    }

    override fun decrypt(value: String): String {
        val bytes = Base64.decode(value, Base64.NO_WRAP)
        val size = bytes.firstOrNull()?.toInt()?.and(255) ?: error("Empty account")
        require(size in 12..32 && bytes.size > size + 16)
        val cipher = Cipher.getInstance("AES/GCM/NoPadding").apply {
            init(Cipher.DECRYPT_MODE, key(), GCMParameterSpec(128, bytes.copyOfRange(1, 1 + size)))
        }
        return cipher.doFinal(bytes.copyOfRange(1 + size, bytes.size)).toString(Charsets.UTF_8)
    }
}

/** Only encrypted account data is written to preferences; the key stays in Android Keystore. */
internal class YandexAccountStore(context: Context, private val cipher: AccountCipher = AndroidAccountCipher()) {
    class Session(val access: String, val uid: String, val name: String, val expiresAt: Long, val key: String = UUID.randomUUID().toString()) {
        override fun toString() = "YandexAccount(uid=$uid)"
    }

    companion object {
        @Volatile private var instance: YandexAccountStore? = null
        fun get(context: Context): YandexAccountStore = instance ?: synchronized(this) {
            instance ?: YandexAccountStore(context.applicationContext).also { instance = it }
        }
    }

    private val prefs = context.getSharedPreferences("yandex_account_v1", Context.MODE_PRIVATE)
    private var loaded = false
    private var session: Session? = null

    @Synchronized fun read(): Session? {
        if (!loaded) {
            loaded = true
            session = try {
                prefs.getString("sealed", null)?.let { encrypted ->
                    val json = JSONObject(cipher.decrypt(encrypted))
                    Session(json.getString("access"), json.getString("uid"), json.getString("name"), json.getLong("expiresAt"), json.getString("key"))
                }
            } catch (_: Exception) { null }
        }
        return session
    }

    @Synchronized fun save(token: YandexMusicApi.Token, account: YandexMusicApi.Account) {
        require(token.access.isNotBlank() && token.access.length <= 4096 && !token.access.contains(Regex("[\\r\\n]")))
        val expiry = if (token.expires > 0) System.currentTimeMillis() + token.expires.coerceAtMost(10L * 366 * 86400) * 1000 else Long.MAX_VALUE
        val value = Session(token.access, account.uid, account.name, expiry)
        val json = JSONObject().put("access", value.access).put("uid", value.uid).put("name", value.name)
            .put("expiresAt", value.expiresAt).put("key", value.key)
        if (!prefs.edit().putString("sealed", cipher.encrypt(json.toString())).commit()) error("Account could not be saved")
        session = value
        loaded = true
    }

    @Synchronized fun clear() {
        if (!prefs.edit().clear().commit()) error("Account could not be removed")
        session = null
        loaded = true
    }
}

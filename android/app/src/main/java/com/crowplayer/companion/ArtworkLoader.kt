package com.crowplayer.companion

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import java.io.ByteArrayOutputStream
import java.net.HttpURLConnection
import java.net.URL

/** Loads the artwork URI published by a media session, off the main thread. */
internal object ArtworkLoader {
    fun load(context: Context, value: String): Bitmap? {
        val uri = runCatching { Uri.parse(value) }.getOrNull() ?: return null
        return when (uri.scheme?.lowercase()) {
            "content", "android.resource" -> context.contentResolver.openInputStream(uri)?.use(BitmapFactory::decodeStream)
            "https" -> loadYandexHttps(value)
            else -> null
        }
    }

    private fun loadYandexHttps(value: String): Bitmap? {
        var url = URL(value)
        repeat(6) { redirect ->
            if (url.protocol != "https" || !isYandexHost(url.host)) return null
            val connection = url.openConnection() as HttpURLConnection
            try {
                connection.connectTimeout = 5000
                connection.readTimeout = 5000
                connection.instanceFollowRedirects = false
                connection.setRequestProperty("User-Agent", "CrowPlayerCompanion/${BuildConfig.VERSION_NAME}")
                val status = connection.responseCode
                if (status in 300..399) {
                    if (redirect == 5) return null
                    val location = connection.getHeaderField("Location") ?: return null
                    url = URL(url, location)
                    return@repeat
                }
                if (status !in 200..299 || connection.contentLengthLong > MAX_ARTWORK_BYTES) return null
                val bytes = connection.inputStream.use { input ->
                    val output = ByteArrayOutputStream()
                    val buffer = ByteArray(16 * 1024)
                    while (true) {
                        val count = input.read(buffer)
                        if (count < 0) break
                        if (output.size() + count > MAX_ARTWORK_BYTES) return null
                        output.write(buffer, 0, count)
                    }
                    output.toByteArray()
                }
                val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
                BitmapFactory.decodeByteArray(bytes, 0, bytes.size, bounds)
                var sample = 1
                while (bounds.outWidth / sample > 512 || bounds.outHeight / sample > 512) sample *= 2
                return BitmapFactory.decodeByteArray(bytes, 0, bytes.size, BitmapFactory.Options().apply { inSampleSize = sample })
            } finally { connection.disconnect() }
        }
        return null
    }

    private fun isYandexHost(value: String): Boolean {
        val host = value.lowercase()
        return host == "yandex.net" || host.endsWith(".yandex.net") ||
            host == "yandex.ru" || host.endsWith(".yandex.ru")
    }

    private const val MAX_ARTWORK_BYTES = 8 * 1024 * 1024
}


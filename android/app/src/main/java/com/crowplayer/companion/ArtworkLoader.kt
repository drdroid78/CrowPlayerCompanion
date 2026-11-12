package com.crowplayer.companion

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import java.net.HttpURLConnection
import java.net.URL

/** Loads the artwork URI published by a media session, off the main thread. */
internal object ArtworkLoader {
    fun load(context: Context, value: String): Bitmap? {
        val uri = runCatching { Uri.parse(value) }.getOrNull() ?: return null
        return when (uri.scheme?.lowercase()) {
            "content", "android.resource" -> context.contentResolver.openInputStream(uri)?.use(BitmapFactory::decodeStream)
            "https" -> {
                val host = uri.host?.lowercase().orEmpty()
                if (host != "yandex.net" && !host.endsWith(".yandex.net") &&
                    host != "yandex.ru" && !host.endsWith(".yandex.ru")) return null
                val connection = URL(value).openConnection() as HttpURLConnection
                try {
                    connection.connectTimeout = 5000
                    connection.readTimeout = 5000
                    connection.instanceFollowRedirects = false
                    connection.setRequestProperty("User-Agent", "CrowPlayerCompanion/${BuildConfig.VERSION_NAME}")
                    if (connection.responseCode !in 200..299) null
                    else connection.inputStream.use(BitmapFactory::decodeStream)
                } finally { connection.disconnect() }
            }
            else -> null
        }
    }
}

package com.crowplayer.companion

import java.net.URI
import java.util.Locale
import kotlin.math.abs

internal object YandexTrackMatcher {
    fun mediaId(value: String?): String? {
        val raw = value?.trim() ?: return null
        if (raw.matches(Regex("[0-9]+(:[0-9]+)?"))) return raw.substringBefore(':')
        return try {
            val uri = URI(raw)
            if (uri.scheme !in setOf("https", "http") || uri.host !in setOf("music.yandex.ru", "music.yandex.com", "music.yandex.by", "music.yandex.kz")) null
            else Regex("/(?:album/[0-9]+/)?track/([0-9]+)/?").matchEntire(uri.path)?.groupValues?.get(1)
        } catch (_: Exception) { null }
    }

    fun matches(source: LikeActionPolicy.Track, album: String, candidate: YandexMusicApi.Track, hasId: Boolean): Boolean {
        if (normalize(source.title.orEmpty()) != normalize(candidate.title)) return false
        if (normalize(source.artist.orEmpty()) != normalize(candidate.artist)) return false
        if (source.duration > 0 && candidate.duration > 0 && abs(source.duration - candidate.duration) > 2500L) return false
        // IDs come from the session. Search results require duration and, when supplied, album too.
        if (!hasId && (source.duration <= 0 || candidate.duration <= 0)) return false
        return hasId || album.isBlank() || normalize(album) == normalize(candidate.album)
    }

    private fun normalize(value: String) = value.lowercase(Locale.ROOT).replace('ё', 'е')
        .replace(Regex("[^\\p{L}\\p{N}]+"), " ").trim()
}

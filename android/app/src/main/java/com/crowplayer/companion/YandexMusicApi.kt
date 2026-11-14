package com.crowplayer.companion

import org.json.JSONArray
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder
import java.util.UUID
import java.io.ByteArrayOutputStream

internal class YandexProblem(val kind: Kind) : Exception(kind.message) {
    enum class Kind(val message: String) {
        AUTH("Войди в аккаунт Яндекса заново"),
        NETWORK("Нет связи с Яндекс Музыкой. Проверь интернет и повтори действие."),
        PENDING("Ожидаем подтверждения входа"),
        SLOW_DOWN("Ожидаем подтверждения входа"),
        DENIED("Вход отменён на странице Яндекса"),
        EXPIRED("Время входа истекло. Начни вход заново."),
        RESPONSE("Яндекс вернул неожиданный ответ. Повтори действие позже."),
        TRACK("Не удалось точно определить трек в каталоге Яндекс Музыки"),
        AMBIGUOUS("Найдено несколько версий трека. Выбери нужную запись."),
        CANCELLED("Трек или аккаунт изменился. Действие отменено."),
        UNCONFIRMED("Яндекс пока не подтвердил изменение избранного. Проверь его и повтори действие.")
    }
}

internal data class YandexHttpRequest(
    val host: String, val path: String, val method: String = "GET",
    val fields: Map<String, String> = emptyMap(), val token: String? = null
) {
    override fun toString() = "YandexHttpRequest($method $host$path)"
}

internal fun interface YandexTransport { fun execute(request: YandexHttpRequest): JSONObject }

/** HTTPS only, no redirects carrying credentials, bounded responses and network timeouts. */
internal class YandexHttpsTransport(
    private val connectionFactory: (URL) -> HttpURLConnection = { it.openConnection() as HttpURLConnection }
) : YandexTransport {
    override fun execute(request: YandexHttpRequest): JSONObject {
        require(request.host in setOf("api.music.yandex.net", "oauth.yandex.ru"))
        require(request.path.startsWith('/') && !request.path.contains('?'))
        val encoded = request.fields.entries.joinToString("&") {
            "${URLEncoder.encode(it.key, "UTF-8")}=${URLEncoder.encode(it.value, "UTF-8")}"
        }
        val suffix = if (request.method == "GET" && encoded.isNotEmpty()) "?$encoded" else ""
        val connection = connectionFactory(URL("https://${request.host}${request.path}$suffix"))
        try {
            connection.connectTimeout = 4000
            connection.readTimeout = 4000
            connection.instanceFollowRedirects = false
            connection.useCaches = false
            connection.requestMethod = request.method
            connection.setRequestProperty("Accept", "application/json")
            connection.setRequestProperty("User-Agent", "CrowPlayerCompanion/${BuildConfig.VERSION_NAME}")
            if (request.host == "api.music.yandex.net") {
                connection.setRequestProperty("X-Yandex-Music-Client", "YandexMusicAndroid/24023621")
                request.token?.let { connection.setRequestProperty("Authorization", "OAuth $it") }
            }
            if (request.method == "POST") {
                val data = encoded.toByteArray(Charsets.UTF_8)
                connection.doOutput = true
                connection.setRequestProperty("Content-Type", "application/x-www-form-urlencoded; charset=UTF-8")
                connection.setFixedLengthStreamingMode(data.size)
                connection.outputStream.use { it.write(data) }
            }
            val status = connection.responseCode
            val stream = if (status in 200..299) connection.inputStream else connection.errorStream
            val bytes = stream?.use {
                val output = ByteArrayOutputStream()
                val buffer = ByteArray(8192)
                while (true) {
                    val count = it.read(buffer)
                    if (count < 0) break
                    output.write(buffer, 0, count)
                    if (output.size() > 8 * 1024 * 1024) throw YandexProblem(YandexProblem.Kind.RESPONSE)
                }
                output.toByteArray()
            } ?: byteArrayOf()
            if (bytes.size > 8 * 1024 * 1024) throw YandexProblem(YandexProblem.Kind.RESPONSE)
            val json = try { JSONObject(bytes.toString(Charsets.UTF_8)) }
                catch (_: Exception) { throw YandexProblem(if (status in listOf(401, 403)) YandexProblem.Kind.AUTH else YandexProblem.Kind.RESPONSE) }
            val error = json.opt("error")
            val code = if (error is JSONObject) error.optString("name") else error?.toString().orEmpty()
            val kind = when (code) {
                "authorization_pending" -> YandexProblem.Kind.PENDING
                "slow_down" -> YandexProblem.Kind.SLOW_DOWN
                "access_denied" -> YandexProblem.Kind.DENIED
                "expired_token" -> YandexProblem.Kind.EXPIRED
                "invalid_grant", "invalid_token", "session-expired" -> YandexProblem.Kind.AUTH
                else -> if (status in listOf(401, 403)) YandexProblem.Kind.AUTH else YandexProblem.Kind.RESPONSE
            }
            if (status !in 200..299 || (error != null && error != JSONObject.NULL)) throw YandexProblem(kind)
            return json
        } catch (e: YandexProblem) { throw e }
        catch (_: Exception) { throw YandexProblem(YandexProblem.Kind.NETWORK) }
        finally { connection.disconnect() }
    }
}

internal class YandexMusicApi(private val transport: YandexTransport = YandexHttpsTransport()) {
    data class DeviceCode(val code: String, val userCode: String, val url: String, val interval: Int, val expires: Int)
    data class Token(val access: String, val expires: Long) { override fun toString() = "YandexToken(redacted)" }
    data class Account(val uid: String, val name: String)
    data class Track(val id: String, val title: String, val artist: String, val album: String, val duration: Long)

    // Public client credentials documented by MarshalX/yandex-music-api, not account credentials.
    private val clientId = "23cabbbdc6cd418abb4b39c32c41195d"
    private val clientSecret = "53bc75238f0c4d08a118e51fe9203300"

    fun beginLogin(): DeviceCode {
        val json = transport.execute(YandexHttpRequest("oauth.yandex.ru", "/device/code", "POST", mapOf(
            "client_id" to clientId, "device_id" to UUID.randomUUID().toString(), "device_name" to "CrowPlayer Skoda"
        )))
        val url = json.getString("verification_url")
        val parsed = URL(url)
        if (parsed.protocol != "https" || parsed.host !in setOf("oauth.yandex.ru", "passport.yandex.ru", "ya.ru", "yandex.ru"))
            throw YandexProblem(YandexProblem.Kind.RESPONSE)
        return DeviceCode(json.getString("device_code"), json.getString("user_code"), url,
            json.optInt("interval", 5).coerceIn(1, 60), json.getInt("expires_in").coerceIn(1, 3600))
    }

    fun pollLogin(code: String): Token {
        val json = transport.execute(YandexHttpRequest("oauth.yandex.ru", "/token", "POST", mapOf(
            "grant_type" to "device_code", "code" to code, "client_id" to clientId, "client_secret" to clientSecret
        )))
        return Token(json.getString("access_token").also { if (it.isBlank()) throw YandexProblem(YandexProblem.Kind.RESPONSE) },
            json.optLong("expires_in", 0L).coerceAtLeast(0L))
    }

    fun account(token: String): Account {
        val account = get("/account/status", token).getJSONObject("result").getJSONObject("account")
        val uid = account.get("uid").toString()
        if (!uid.matches(Regex("[0-9]+"))) throw YandexProblem(YandexProblem.Kind.AUTH)
        val name = account.optString("displayName").ifBlank { account.optString("login") }.ifBlank { uid }
        return Account(uid, name)
    }

    fun likedTracks(token: String, uid: String): Set<String> {
        require(uid.matches(Regex("[0-9]+")))
        val library = get("/users/$uid/likes/tracks", token, mapOf("if-modified-since-revision" to "0"))
            .getJSONObject("result").getJSONObject("library")
        // A malformed/missing library is never interpreted as an empty favourite list.
        val tracks = library.getJSONArray("tracks")
        return (0 until tracks.length()).map { tracks.getJSONObject(it).get("id").toString().substringBefore(':') }.toSet()
    }

    fun setLike(token: String, uid: String, trackId: String, liked: Boolean): Boolean {
        require(uid.matches(Regex("[0-9]+")) && trackId.matches(Regex("[0-9]+")))
        val action = if (liked) "add-multiple" else "remove"
        val result = transport.execute(YandexHttpRequest("api.music.yandex.net", "/users/$uid/likes/tracks/$action", "POST",
            mapOf("track-ids" to trackId), token)).getJSONObject("result")
        return result.has("revision") && !result.isNull("revision")
    }

    fun candidates(token: String, track: LikeActionPolicy.Track, album: String): List<Track> {
        val id = YandexTrackMatcher.mediaId(track.mediaId)
        val json = if (id != null) transport.execute(YandexHttpRequest("api.music.yandex.net", "/tracks", "POST", mapOf("track-ids" to id), token))
            else get("/search", token, mapOf("type" to "track", "page" to "0", "text" to "${track.artist.orEmpty()} ${track.title.orEmpty()}"))
        val array = if (id != null) json.getJSONArray("result")
            else json.getJSONObject("result").optJSONObject("tracks")?.optJSONArray("results") ?: JSONArray()
        return (0 until array.length()).map { index ->
            val item = array.getJSONObject(index)
            val artists = item.optJSONArray("artists") ?: JSONArray()
            val albums = item.optJSONArray("albums") ?: JSONArray()
            val version = item.optString("version")
            val title = item.getString("title") + if (version.isNotBlank()) " ($version)" else ""
            Track(item.get("id").toString(), title,
                (0 until artists.length()).joinToString(", ") { artists.getJSONObject(it).optString("name") },
                if (albums.length() > 0) albums.getJSONObject(0).optString("title") else "", item.optLong("durationMs"))
        }.filter { it.id.matches(Regex("[0-9]+")) && (id == null || it.id == id) && YandexTrackMatcher.matches(track, album, it, id != null) }
            .distinctBy { it.id }
    }

    fun coverUri(token: String, trackId: String): String? {
        if (!trackId.matches(Regex("[0-9]+"))) return null
        val tracks = transport.execute(YandexHttpRequest("api.music.yandex.net", "/tracks", "POST",
            mapOf("track-ids" to trackId), token)).optJSONArray("result") ?: return null
        val track = tracks.optJSONObject(0) ?: return null
        val albums = track.optJSONArray("albums") ?: JSONArray()
        val uri = track.optString("coverUri").ifBlank {
            if (albums.length() > 0) albums.optJSONObject(0)?.optString("coverUri").orEmpty() else ""
        }.takeIf { it.isNotBlank() } ?: return null
        val sized = uri.replace("%%", "400x400")
        return if (sized.startsWith("https://")) sized else "https://$sized"
    }


    /** Resolve cover art even when the Yandex player does not publish a numeric media ID. */
    fun coverUriForTrack(token: String?, mediaId: String?, title: String, artist: String,
                         album: String, durationMs: Long): String? {
        val id = YandexTrackMatcher.mediaId(mediaId)
        if (id != null) return coverUriWithOptionalToken(token, id)
        if (title.isBlank() || artist.isBlank()) return null

        val response = transport.execute(YandexHttpRequest("api.music.yandex.net", "/search",
            fields = mapOf("type" to "track", "page" to "0", "text" to "$artist $title"), token = token))
        val tracks = response.optJSONObject("result")?.optJSONObject("tracks")?.optJSONArray("results") ?: return null
        val wantedTitle = normalizeCoverText(title)
        val wantedArtist = normalizeCoverText(artist)
        val matches = (0 until tracks.length()).mapNotNull { index ->
            val item = tracks.optJSONObject(index) ?: return@mapNotNull null
            if (normalizeCoverText(item.optString("title")) != wantedTitle) return@mapNotNull null
            val artists = item.optJSONArray("artists") ?: JSONArray()
            val itemArtist = normalizeCoverText((0 until artists.length()).joinToString(", ") {
                artists.optJSONObject(it)?.optString("name").orEmpty()
            })
            if (itemArtist != wantedArtist) return@mapNotNull null
            val itemDuration = item.optLong("durationMs")
            if (durationMs > 0 && itemDuration > 0 && kotlin.math.abs(durationMs - itemDuration) > 2500L)
                return@mapNotNull null
            val albums = item.optJSONArray("albums") ?: JSONArray()
            val itemAlbum = if (albums.length() > 0) albums.optJSONObject(0)?.optString("title").orEmpty() else ""
            val albumMatches = album.isBlank() || itemAlbum.isBlank() || normalizeCoverText(album) == normalizeCoverText(itemAlbum)
            Triple(item, albumMatches, itemDuration)
        }.sortedWith(compareByDescending<Triple<JSONObject, Boolean, Long>> { it.second }
            .thenBy { if (durationMs > 0 && it.third > 0) kotlin.math.abs(durationMs - it.third) else Long.MAX_VALUE })
        val selected = matches.firstOrNull()?.first ?: return null
        val albums = selected.optJSONArray("albums") ?: JSONArray()
        val uri = selected.optString("coverUri").ifBlank {
            if (albums.length() > 0) albums.optJSONObject(0)?.optString("coverUri").orEmpty() else ""
        }.takeIf { it.isNotBlank() } ?: return null
        return imageUrl(uri)
    }

    private fun coverUriWithOptionalToken(token: String?, trackId: String): String? {
        if (token != null) return coverUri(token, trackId)
        if (!trackId.matches(Regex("[0-9]+"))) return null
        val tracks = transport.execute(YandexHttpRequest("api.music.yandex.net", "/tracks", "POST",
            mapOf("track-ids" to trackId))).optJSONArray("result") ?: return null
        val track = tracks.optJSONObject(0) ?: return null
        val albums = track.optJSONArray("albums") ?: JSONArray()
        val uri = track.optString("coverUri").ifBlank {
            if (albums.length() > 0) albums.optJSONObject(0)?.optString("coverUri").orEmpty() else ""
        }.takeIf { it.isNotBlank() } ?: return null
        return imageUrl(uri)
    }

    private fun imageUrl(uri: String): String {
        val sized = uri.replace("%%", "400x400")
        return if (sized.startsWith("https://")) sized else "https://$sized"
    }

    private fun normalizeCoverText(value: String) = value.lowercase()
        .replace('ё', 'е').replace(Regex("[^\\p{L}\\p{N}]+"), " ").trim()

    private fun get(path: String, token: String, fields: Map<String, String> = emptyMap()) =
        transport.execute(YandexHttpRequest("api.music.yandex.net", path, fields = fields, token = token))
}


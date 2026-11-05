package com.crowplayer.companion

import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.net.HttpURLConnection
import java.net.URL

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28])
class YandexHttpsTransportTest {
    private class Connection(url: URL, val response: String = "{\"result\":{\"revision\":1}}", val status: Int = 200) : HttpURLConnection(url) {
        val sent = ByteArrayOutputStream()
        var closed = false
        override fun getResponseCode() = status
        override fun getInputStream() = ByteArrayInputStream(response.toByteArray())
        override fun getErrorStream() = inputStream
        override fun getOutputStream() = sent
        override fun connect() {}
        override fun disconnect() { closed = true }
        override fun usingProxy() = false
    }

    @Test fun `API token is sent only in the authorization header over HTTPS`() {
        lateinit var connection: Connection
        val transport = YandexHttpsTransport { url -> Connection(url).also { connection = it } }
        transport.execute(YandexHttpRequest("api.music.yandex.net", "/users/1/likes/tracks/add-multiple", "POST", mapOf("track-ids" to "101"), "private-token"))
        assertEquals("https", connection.url.protocol)
        assertEquals("OAuth private-token", connection.getRequestProperty("Authorization"))
        assertFalse(connection.url.toString().contains("private-token"))
        assertEquals("track-ids=101", connection.sent.toString("UTF-8"))
        assertFalse(connection.instanceFollowRedirects)
        assertFalse(connection.useCaches)
        assertTrue(connection.closed)
    }

    @Test fun `OAuth form values are encoded and an account token is never sent to OAuth`() {
        lateinit var connection: Connection
        val transport = YandexHttpsTransport { url -> Connection(url, "{\"access_token\":\"result\"}").also { connection = it } }
        transport.execute(YandexHttpRequest("oauth.yandex.ru", "/token", "POST", mapOf("code" to "a+b & c"), "private-token"))
        assertNull(connection.getRequestProperty("Authorization"))
        assertEquals("code=a%2Bb+%26+c", connection.sent.toString("UTF-8"))
    }

    @Test fun `redirects are rejected instead of forwarding credentials`() {
        val connection = Connection(URL("https://api.music.yandex.net/account/status"), status = 302)
        val transport = YandexHttpsTransport { connection }
        assertEquals(YandexProblem.Kind.RESPONSE, assertThrows(YandexProblem::class.java) {
            transport.execute(YandexHttpRequest("api.music.yandex.net", "/account/status", token = "private"))
        }.kind)
        assertFalse(connection.instanceFollowRedirects)
        assertTrue(connection.closed)
    }

    @Test fun `OAuth pending slow down and denial remain distinct`() {
        mapOf("authorization_pending" to YandexProblem.Kind.PENDING, "slow_down" to YandexProblem.Kind.SLOW_DOWN,
            "access_denied" to YandexProblem.Kind.DENIED, "expired_token" to YandexProblem.Kind.EXPIRED).forEach { (error, kind) ->
            val transport = YandexHttpsTransport { url -> Connection(url, "{\"error\":\"$error\"}", 400) }
            assertEquals(kind, assertThrows(YandexProblem::class.java) {
                transport.execute(YandexHttpRequest("oauth.yandex.ru", "/token", "POST"))
            }.kind)
        }
    }

    @Test fun `server error details cannot leak tokens into user messages`() {
        val transport = YandexHttpsTransport { url -> Connection(url, "{\"error\":{\"name\":\"session-expired\",\"message\":\"private-token\"}}", 403) }
        val error = assertThrows(YandexProblem::class.java) { transport.execute(YandexHttpRequest("api.music.yandex.net", "/account/status")) }
        assertEquals(YandexProblem.Kind.AUTH, error.kind)
        assertFalse(error.toString().contains("private-token"))
    }

    @Test fun `unknown hosts are refused before opening a connection`() {
        val transport = YandexHttpsTransport { error("Connection must not be opened") }
        assertThrows(IllegalArgumentException::class.java) { transport.execute(YandexHttpRequest("example.com", "/token", token = "secret")) }
    }
}

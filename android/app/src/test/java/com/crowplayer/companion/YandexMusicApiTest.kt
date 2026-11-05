package com.crowplayer.companion

import org.json.JSONException
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28])
class YandexMusicApiTest {
    private val server = FakeYandexServer()
    private val api = YandexMusicApi(server)

    @Test fun `add and remove use distinct documented endpoints and form body`() {
        assertTrue(api.setLike("secret", "1", "101", true))
        assertTrue(server.liked.contains("101"))
        assertEquals("/users/1/likes/tracks/add-multiple", server.requests.last().path)
        assertEquals(mapOf("track-ids" to "101"), server.requests.last().fields)
        assertEquals("secret", server.requests.last().token)
        assertTrue(api.setLike("secret", "1", "101", false))
        assertEquals("/users/1/likes/tracks/remove", server.requests.last().path)
        assertFalse(server.liked.contains("101"))
    }

    @Test fun `library reads request a complete snapshot`() {
        server.liked += listOf("101", "202")
        assertEquals(setOf("101", "202"), api.likedTracks("secret", "1"))
        assertEquals("0", server.requests.last().fields["if-modified-since-revision"])
    }

    @Test fun `missing library is an error rather than an empty favourite list`() {
        server.malformedLibrary = true
        assertThrows(JSONException::class.java) { api.likedTracks("secret", "1") }
    }

    @Test fun `device flow uses OAuth host and account validation reads numeric uid`() {
        val code = api.beginLogin()
        assertEquals("ABCD-EFGH", code.userCode)
        assertEquals("oauth.yandex.ru", server.requests.last().host)
        assertEquals("CrowPlayer Skoda", server.requests.last().fields["device_name"])
        assertNull(server.requests.last().token)
        assertEquals(YandexProblem.Kind.PENDING, assertThrows(YandexProblem::class.java) { api.pollLogin(code.code) }.kind)
        server.ready = true
        val token = api.pollLogin(code.code)
        assertEquals("device_code", server.requests.last().fields["grant_type"])
        assertEquals("device-test", server.requests.last().fields["code"])
        assertEquals("1", api.account(token.access).uid)
    }

    @Test fun `request and token descriptions redact credentials`() {
        val request = YandexHttpRequest("oauth.yandex.ru", "/token", "POST", mapOf("client_secret" to "private", "code" to "device-secret"), "access-secret")
        assertFalse(request.toString().contains("secret"))
        assertFalse(request.toString().contains("private"))
        assertFalse(YandexMusicApi.Token("access-secret", 10).toString().contains("access-secret"))
    }

    @Test fun `unknown verification host is rejected`() {
        val unsafe = YandexMusicApi(YandexTransport { org.json.JSONObject()
            .put("device_code", "test").put("user_code", "test").put("expires_in", 100)
            .put("verification_url", "https://example.com/login") })
        assertThrows(YandexProblem::class.java) { unsafe.beginLogin() }
    }
}

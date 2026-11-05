package com.crowplayer.companion

import android.os.Looper
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.annotation.LooperMode
import java.util.concurrent.TimeUnit

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28])
@LooperMode(LooperMode.Mode.PAUSED)
class YandexLikesTest {
    private val server = FakeYandexServer()
    private val queue = TestQueue()
    private val results = mutableListOf<YandexLikes.Outcome>()
    private var account: YandexAccountStore.Session? = YandexAccountStore.Session("secret", "1", "Test", Long.MAX_VALUE)
    private var target: YandexLikes.Target? = null
    private lateinit var likes: YandexLikes
    private val main get() = shadowOf(Looper.getMainLooper())

    @Before fun setUp() {
        target = YandexLikes.Target(testToken(), "ru.yandex.music", LikeActionPolicy.Track("101", "Track A", "Artist", 120000), "Album")
        likes = YandexLikes(YandexMusicApi(server), { account }, { target }, queue)
    }

    private fun drain() { while (queue.hasTasks()) { queue.runOne(); main.idle() }; main.idle() }
    private fun toggle() { assertTrue(likes.toggle { results += it }) }
    private fun establishRead() { likes.view(target!!); drain() }

    @Test fun `heart is confirmed from the library and repeated click removes the like`() {
        toggle()
        assertNull(likes.view(target!!)!!.liked)
        drain()
        assertEquals(true, results.single().liked)
        assertEquals(true, likes.view(target!!)!!.liked)
        toggle(); drain()
        assertEquals(false, results.last().liked)
        assertFalse(server.liked.contains("101"))
        assertEquals(2, server.writes)
    }

    @Test fun `a successful write response alone cannot fill the heart`() {
        server.acknowledgeOnly = true
        toggle(); drain()
        assertEquals(YandexProblem.Kind.UNCONFIRMED, results.single().problem)
        assertNull(likes.view(target!!)!!.liked)
    }

    @Test fun `network failure after a possible write is uncertain and is never retried`() {
        server.failAfterWrite = true
        toggle(); drain()
        assertEquals(YandexProblem.Kind.UNCONFIRMED, results.single().problem)
        assertEquals(1, server.writes)
        assertTrue(server.liked.contains("101"))
        assertNull(likes.view(target!!)!!.liked)
    }

    @Test fun `changing track before mutation prevents the write`() {
        establishRead()
        server.beforeLibrary = { target = target!!.copy(track = target!!.track.copy(mediaId = "202")) }
        toggle(); drain()
        assertEquals(YandexProblem.Kind.CANCELLED, results.single().problem)
        assertEquals(0, server.writes)
    }

    @Test fun `switching account before mutation prevents the write`() {
        establishRead()
        server.beforeLibrary = { account = YandexAccountStore.Session("other-secret", "2", "Other", Long.MAX_VALUE) }
        toggle(); drain()
        assertEquals(YandexProblem.Kind.CANCELLED, results.single().problem)
        assertEquals(0, server.writes)
    }

    @Test fun `confirmation from an old track cannot alter the new track`() {
        server.afterWrite = { target = target!!.copy(session = testToken(), track = target!!.track.copy(mediaId = "202")) }
        toggle(); drain()
        assertEquals(YandexProblem.Kind.CANCELLED, results.single().problem)
        assertNull(likes.view(target!!)!!.liked)
        assertEquals(1, server.writes)
    }

    @Test fun `an old background read cannot overwrite a newer confirmed like`() {
        likes.view(target!!)
        queue.runOne() // Its old library response is queued on the UI thread.
        toggle()
        queue.runOne()
        main.idle()
        assertEquals(true, likes.view(target!!)!!.liked)
    }

    @Test fun `missing id with multiple matching records needs an explicit selection`() {
        target = target!!.copy(track = target!!.track.copy(mediaId = null))
        server.catalog = server.catalog + server.catalog.single().copy(id = "102")
        toggle(); drain()
        assertEquals(YandexProblem.Kind.AMBIGUOUS, results.single().problem)
        assertEquals(0, server.writes)
        assertFalse(likes.choose("999"))
        assertTrue(likes.choose("102"))
        toggle(); drain()
        assertEquals(true, results.last().liked)
        assertEquals(setOf("102"), server.liked)
    }

    @Test fun `selection for a previous track is refused`() {
        target = target!!.copy(track = target!!.track.copy(mediaId = null))
        server.catalog = server.catalog + server.catalog.single().copy(id = "102")
        establishRead()
        target = target!!.copy(track = target!!.track.copy(title = "Next song"))
        assertFalse(likes.choose("102"))
    }

    @Test fun `timeout completes once and a queued command cannot write later`() {
        toggle()
        main.idleFor(46, TimeUnit.SECONDS)
        assertEquals(YandexProblem.Kind.UNCONFIRMED, results.single().problem)
        drain()
        assertEquals(1, results.size)
        assertEquals(0, server.writes)
    }

    @Test fun `disconnect cancels pending work and prevents queued account requests`() {
        toggle(); likes.close(); drain()
        assertEquals(YandexProblem.Kind.CANCELLED, results.single().problem)
        assertTrue(server.requests.isEmpty())
    }

    @Test fun `expired account requires login without sending a network request`() {
        account = YandexAccountStore.Session("secret", "1", "Test", 1L)
        toggle(); drain()
        assertEquals(YandexProblem.Kind.AUTH, results.single().problem)
        assertTrue(server.requests.isEmpty())
    }

    @Test fun `bad account and malformed libraries never create a guessed like`() {
        server.malformedLibrary = true
        toggle(); drain()
        assertEquals(YandexProblem.Kind.RESPONSE, results.single().problem)
        assertEquals(0, server.writes)
        server.malformedLibrary = false; server.rejectAuth = true
        toggle(); drain()
        assertEquals(YandexProblem.Kind.AUTH, results.last().problem)
    }

    @Test fun `another provider and a missing account leave the native route available`() {
        target = target!!.copy(packageName = "another.player")
        assertFalse(likes.toggle { results += it })
        assertNull(likes.view(target!!))
        target = target!!.copy(packageName = "ru.yandex.music")
        account = null
        assertFalse(likes.toggle { results += it })
        assertNull(likes.view(target!!))
        assertTrue(server.requests.isEmpty())
    }

    @Test fun `a second simultaneous command is rejected`() {
        toggle()
        assertFalse(likes.toggle { results += it })
        drain()
        assertEquals(1, server.writes)
        assertEquals(1, results.size)
    }
}

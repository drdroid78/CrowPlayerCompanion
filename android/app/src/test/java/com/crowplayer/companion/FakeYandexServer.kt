package com.crowplayer.companion

import android.media.session.MediaSession
import android.os.Binder
import android.os.Parcel
import org.json.JSONArray
import org.json.JSONObject
import java.util.ArrayDeque
import java.util.concurrent.AbstractExecutorService
import java.util.concurrent.TimeUnit

internal class TestQueue : AbstractExecutorService() {
    private val tasks = ArrayDeque<Runnable>()
    private var stopped = false
    override fun execute(command: Runnable) { check(!stopped); tasks.add(command) }
    fun runOne() { tasks.removeFirst().run() }
    fun hasTasks() = tasks.isNotEmpty()
    override fun shutdown() { stopped = true }
    override fun shutdownNow(): MutableList<Runnable> { stopped = true; return tasks.toMutableList().also { tasks.clear() } }
    override fun isShutdown() = stopped
    override fun isTerminated() = stopped && tasks.isEmpty()
    override fun awaitTermination(timeout: Long, unit: TimeUnit) = isTerminated
}

internal fun testToken(): MediaSession.Token {
    val parcel = Parcel.obtain()
    return try {
        parcel.writeStrongBinder(Binder()); parcel.setDataPosition(0)
        MediaSession.Token.CREATOR.createFromParcel(parcel)
    } finally { parcel.recycle() }
}

internal class FakeYandexServer : YandexTransport {
    val requests = mutableListOf<YandexHttpRequest>()
    val liked = mutableSetOf<String>()
    var catalog = listOf(YandexMusicApi.Track("101", "Track A", "Artist", "Album", 120000))
    var ready = false
    var rejectAuth = false
    var acknowledgeOnly = false
    var failAfterWrite = false
    var beforeLibrary: (() -> Unit)? = null
    var afterWrite: (() -> Unit)? = null
    var malformedLibrary = false
    var writes = 0

    override fun execute(request: YandexHttpRequest): JSONObject {
        requests += request
        if (request.path == "/device/code") return JSONObject()
            .put("device_code", "device-test").put("user_code", "ABCD-EFGH")
            .put("verification_url", "https://oauth.yandex.ru/device").put("interval", 5).put("expires_in", 120)
        if (request.path == "/token") {
            if (!ready) throw YandexProblem(YandexProblem.Kind.PENDING)
            return JSONObject().put("access_token", "test-account-token").put("expires_in", 3600)
        }
        if (rejectAuth) throw YandexProblem(YandexProblem.Kind.AUTH)
        return when {
            request.path == "/account/status" -> result(JSONObject().put("account", JSONObject().put("uid", 1).put("displayName", "Test account")))
            request.path == "/tracks" || request.path == "/search" -> {
                val tracks = if (request.path == "/tracks") catalog.filter { it.id == request.fields["track-ids"] } else catalog
                val array = JSONArray()
                tracks.forEach { track -> array.put(JSONObject().put("id", track.id).put("title", track.title)
                    .put("durationMs", track.duration).put("artists", JSONArray().put(JSONObject().put("name", track.artist)))
                    .put("albums", JSONArray().put(JSONObject().put("title", track.album)))) }
                if (request.path == "/tracks") result(array) else result(JSONObject().put("tracks", JSONObject().put("results", array)))
            }
            request.path.endsWith("/likes/tracks") -> {
                val hook = beforeLibrary; beforeLibrary = null; hook?.invoke()
                if (malformedLibrary) result(JSONObject()) else result(JSONObject().put("library", JSONObject()
                    .put("revision", 3).put("tracks", JSONArray().apply { liked.forEach { put(JSONObject().put("id", it)) } })))
            }
            request.path.endsWith("/add-multiple") || request.path.endsWith("/remove") -> {
                check(request.method == "POST")
                writes++
                if (!acknowledgeOnly) {
                    if (request.path.endsWith("/add-multiple")) liked.add(request.fields.getValue("track-ids"))
                    else liked.remove(request.fields.getValue("track-ids"))
                }
                afterWrite?.invoke()
                if (failAfterWrite) throw YandexProblem(YandexProblem.Kind.NETWORK)
                result(JSONObject().put("revision", 4))
            }
            else -> error("Unexpected request: $request")
        }
    }
    private fun result(value: Any) = JSONObject().put("result", value)
}

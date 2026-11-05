package com.crowplayer.companion

import android.content.Context
import android.media.session.MediaSession
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import java.util.concurrent.Executor
import java.util.concurrent.Executors

/** Account-based favourites. No optimistic heart and no fallback after an uncertain HTTP write. */
internal class YandexLikes(
    private val api: YandexMusicApi,
    private val account: () -> YandexAccountStore.Session?,
    private val targetNow: () -> Target?,
    private val io: Executor,
    private val handler: Handler = Handler(Looper.getMainLooper())
) {
    data class Target(val session: MediaSession.Token, val packageName: String, val track: LikeActionPolicy.Track, val album: String)
    data class View(val liked: Boolean?, val message: String, val candidates: List<YandexMusicApi.Track> = emptyList(), val id: String? = null)
    data class Outcome(val problem: YandexProblem.Kind? = null, val liked: Boolean? = null)
    private class Request(val target: Target, val accountKey: String, val callback: (Outcome) -> Unit) {
        lateinit var timeout: Runnable
    }

    companion object {
        private val network = Executors.newFixedThreadPool(2)
        fun create(context: Context, targetNow: () -> Target?) =
            YandexLikes(YandexMusicApi(), { YandexAccountStore.get(context).read() }, targetNow, network)
        fun isYandex(packageName: String) = packageName == "ru.yandex.music" || packageName.startsWith("ru.yandex.music.")
    }

    private var target: Target? = null
    private var accountKey: String? = null
    private var state = View(null, "Проверяем избранное Яндекса…")
    private var selectedId: String? = null
    private var lastRead = -60000L
    private var reading = false
    private var epoch = 0
    @Volatile private var pending: Request? = null
    @Volatile private var closed = false

    fun view(current: Target): View? {
        val session = account() ?: return null
        if (!isYandex(current.packageName) || closed) return null
        if (target != current || accountKey != session.key) {
            target = current
            accountKey = session.key
            epoch++
            selectedId = null
            state = View(null, "Проверяем избранное Яндекса…")
            lastRead = -60000L
        }
        if (!reading && pending == null && SystemClock.elapsedRealtime() - lastRead >= 30000L) refresh(current, session)
        return state
    }

    fun choices(): List<YandexMusicApi.Track> = if (safeTarget() == target && account()?.key == accountKey) state.candidates else emptyList()

    fun choose(id: String): Boolean {
        if (pending != null || choices().none { it.id == id }) return false
        selectedId = id
        epoch++
        state = View(null, "Запись выбрана", state.candidates, id)
        return true
    }

    private fun refresh(current: Target, session: YandexAccountStore.Session) {
        reading = true
        lastRead = SystemClock.elapsedRealtime()
        val revision = epoch
        val chosen = selectedId
        io.execute {
            val result = try {
                if (closed || safeTarget() != current || account()?.key != session.key) throw YandexProblem(YandexProblem.Kind.CANCELLED)
                ensureAccount(session)
                val candidates = api.candidates(session.access, current.track, current.album)
                if (closed || safeTarget() != current || account()?.key != session.key) throw YandexProblem(YandexProblem.Kind.CANCELLED)
                val track = candidates.firstOrNull { it.id == chosen } ?: candidates.singleOrNull()
                if (track == null) View(null, if (candidates.isEmpty()) YandexProblem.Kind.TRACK.message else YandexProblem.Kind.AMBIGUOUS.message, candidates)
                else View(api.likedTracks(session.access, session.uid).contains(track.id), "Избранное синхронизировано с Яндексом", candidates, track.id)
            } catch (e: Exception) { View(null, problem(e).message) }
            handler.post {
                reading = false
                if (!closed && epoch == revision && safeTarget() == current && account()?.key == session.key) state = result
            }
        }
    }

    fun toggle(callback: (Outcome) -> Unit): Boolean {
        val current = safeTarget() ?: return false
        val session = account() ?: return false
        if (!isYandex(current.packageName) || closed) return false
        if (pending != null) return false
        view(current)
        epoch++ // Discard a read started before this write, even if its HTTP reply arrives later.
        val candidatesBefore = state.candidates
        val choice = selectedId
        val request = Request(current, session.key, callback)
        request.timeout = Runnable { finish(request, Outcome(YandexProblem.Kind.UNCONFIRMED)) }
        pending = request
        handler.postDelayed(request.timeout, 45000L)
        io.execute {
            var candidates = candidatesBefore
            var resolved: YandexMusicApi.Track? = null
            var writeStarted = false
            val outcome = try {
                check(request, session)
                // Revalidate the record against the current catalog on every user command.
                candidates = api.candidates(session.access, current.track, current.album)
                resolved = candidates.firstOrNull { it.id == choice } ?: candidates.singleOrNull()
                val track = resolved ?: throw YandexProblem(if (candidates.isEmpty()) YandexProblem.Kind.TRACK else YandexProblem.Kind.AMBIGUOUS)
                check(request, session)
                val desired = !api.likedTracks(session.access, session.uid).contains(track.id)
                check(request, session) // Last guard before changing the account on the server.
                writeStarted = true
                if (!api.setLike(session.access, session.uid, track.id, desired)) throw YandexProblem(YandexProblem.Kind.UNCONFIRMED)
                check(request, session)
                val confirmed = api.likedTracks(session.access, session.uid).contains(track.id)
                if (confirmed != desired) throw YandexProblem(YandexProblem.Kind.UNCONFIRMED)
                Outcome(liked = confirmed)
            } catch (e: Exception) {
                val failure = problem(e)
                Outcome(if (writeStarted && failure !in setOf(YandexProblem.Kind.CANCELLED, YandexProblem.Kind.AUTH)) YandexProblem.Kind.UNCONFIRMED else failure)
            }
            handler.post {
                if (pending !== request) return@post
                if (!valid(request)) { finish(request, Outcome(YandexProblem.Kind.CANCELLED)); return@post }
                state = View(outcome.liked, outcome.problem?.message ?: "Избранное обновлено в Яндексе", candidates, resolved?.id)
                lastRead = SystemClock.elapsedRealtime()
                finish(request, outcome)
            }
        }
        return true
    }

    private fun safeTarget() = try { targetNow() } catch (_: Exception) { null }
    private fun valid(request: Request) = !closed && safeTarget() == request.target && account()?.key == request.accountKey
    private fun ensureAccount(session: YandexAccountStore.Session) {
        if (session.expiresAt <= System.currentTimeMillis()) throw YandexProblem(YandexProblem.Kind.AUTH)
    }
    private fun check(request: Request, session: YandexAccountStore.Session) {
        if (pending !== request || !valid(request)) throw YandexProblem(YandexProblem.Kind.CANCELLED)
        ensureAccount(session)
    }
    private fun problem(error: Exception) = (error as? YandexProblem)?.kind ?: YandexProblem.Kind.RESPONSE
    private fun finish(request: Request, result: Outcome) {
        if (pending !== request) return
        pending = null
        handler.removeCallbacks(request.timeout)
        if (result.problem != null && valid(request)) state = state.copy(liked = null, message = result.problem.message)
        request.callback(result)
    }
    fun cancelIfTargetChanged() { pending?.takeUnless { valid(it) }?.let { finish(it, Outcome(YandexProblem.Kind.CANCELLED)) } }
    fun close() {
        closed = true
        epoch++
        pending?.let { finish(it, Outcome(YandexProblem.Kind.CANCELLED)) }
    }
}

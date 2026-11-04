package com.crowplayer.companion

import android.app.Notification
import android.app.PendingIntent
import android.content.Intent
import android.media.MediaMetadata
import android.media.Rating
import android.media.session.MediaController
import android.media.session.MediaSession
import android.media.session.PlaybackState
import android.os.Looper
import android.os.Process
import android.os.Binder
import android.os.Parcel
import android.service.notification.StatusBarNotification
import com.crowplayer.companion.MusicNotificationListener.LikeResult
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.annotation.LooperMode
import org.robolectric.annotation.Implements
import org.robolectric.annotation.Implementation
import org.robolectric.shadows.ShadowTransportControls
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28], shadows = [MusicNotificationListenerTest.TestTransportControls::class])
@LooperMode(LooperMode.Mode.PAUSED)
class MusicNotificationListenerTest {
    private lateinit var service: MusicNotificationListener
    private lateinit var controller: MediaController
    private lateinit var controls: MediaController.TransportControls
    private lateinit var token: MediaSession.Token
    private val results = mutableListOf<LikeResult>()
    private val main get() = shadowOf(Looper.getMainLooper())

    @Before fun setUp() {
        service = Robolectric.buildService(MusicNotificationListener::class.java).create().get()
        TestTransportControls.onRating = null
        token = newToken()
        controller = MediaController(service, token)
        controls = controller.transportControls
        shadowOf(controller).setPackageName(service.packageName)
        shadowOf(controller).setMetadata(metadata())
        shadowOf(controller).setPlaybackState(playback())
        setStatic("activeController", controller)
        setStatic("listener", service)
        PlayerSnapshot.clear()
    }

    @After fun tearDown() { TestTransportControls.onRating = null; service.onDestroy(); main.idle() }

    @Test fun `uses advertised custom action when SET_RATING is absent`() {
        val add = action("player.LIKE", "Нравится")
        shadowOf(controller).setPlaybackState(playback(custom = add))
        request()
        awaitSent()
        assertEquals(add.action, shadowOf(controls).customAction)
        assertNull(shadowOf(controls).rating)
        assertFalse(PlayerSnapshot.isLiked)
        assertTrue(results.isEmpty())
        shadowOf(controller).setPlaybackState(playback(custom = action("player.UNLIKE", "Убрать из избранного")))
        main.idleFor(250, TimeUnit.MILLISECONDS)
        assertEquals(listOf(LikeResult.CONFIRMED), results)
        assertTrue(PlayerSnapshot.isLiked)
    }

    @Test fun `notification PendingIntent supports like and unlike without a rating`() {
        notification("Нравится", "test.LIKE")
        request()
        awaitSent()
        assertEquals("test.LIKE", shadowOf(service.application).broadcastIntents.last().action)
        notification("Убрать из избранного", "test.UNLIKE")
        main.idleFor(250, TimeUnit.MILLISECONDS)
        assertEquals(listOf(LikeResult.CONFIRMED), results)
        assertTrue(PlayerSnapshot.isLiked)
        request()
        awaitSent()
        assertEquals("test.UNLIKE", shadowOf(service.application).broadcastIntents.last().action)
        notification("Нравится", "test.LIKE")
        main.idleFor(250, TimeUnit.MILLISECONDS)
        assertEquals(listOf(LikeResult.CONFIRMED, LikeResult.CONFIRMED), results)
        assertFalse(PlayerSnapshot.isLiked)
    }

    @Test fun `notification from another session cannot control this player`() {
        notification("Нравится", "test.OTHER", otherToken = newToken())
        request()
        assertEquals(listOf(LikeResult.UNAVAILABLE), results)
        assertTrue(shadowOf(service.application).broadcastIntents.isEmpty())
    }

    @Test fun `notification for the previous track is ignored`() {
        notification("Нравится", "test.OLD", title = "Old track")
        request()
        assertEquals(listOf(LikeResult.UNAVAILABLE), results)
    }

    @Test fun `unrated heart can be sent but is not considered confirmed`() {
        shadowOf(controller).setMetadata(metadata(rating = Rating.newUnratedRating(Rating.RATING_HEART)))
        shadowOf(controller).setPlaybackState(playback(PlaybackState.ACTION_SET_RATING))
        request()
        awaitSent()
        main.idleFor(6, TimeUnit.SECONDS)
        assertEquals(listOf(LikeResult.UNCONFIRMED), results)
        assertFalse(PlayerSnapshot.isLiked)
    }

    @Test fun `rating needs metadata confirmation from the requested track`() {
        shadowOf(controller).setMetadata(metadata(rating = Rating.newHeartRating(false)))
        shadowOf(controller).setPlaybackState(playback(PlaybackState.ACTION_SET_RATING))
        request()
        awaitSent()
        shadowOf(controller).setMetadata(metadata(id = "next-track", rating = Rating.newHeartRating(true)))
        main.idleFor(250, TimeUnit.MILLISECONDS)
        assertEquals(listOf(LikeResult.TRACK_CHANGED), results)
        assertFalse(PlayerSnapshot.isLiked)
        main.idleFor(6, TimeUnit.SECONDS)
        assertEquals(1, results.size)
    }

    @Test fun `same package with a different session cannot confirm a request`() {
        shadowOf(controller).setMetadata(metadata(rating = Rating.newHeartRating(false)))
        shadowOf(controller).setPlaybackState(playback(PlaybackState.ACTION_SET_RATING))
        request()
        awaitSent()
        val other = MediaController(service, newToken())
        shadowOf(other).setPackageName(service.packageName)
        shadowOf(other).setMetadata(metadata(rating = Rating.newHeartRating(true)))
        setStatic("activeController", other)
        main.idleFor(250, TimeUnit.MILLISECONDS)
        assertEquals(listOf(LikeResult.TRACK_CHANGED), results)
    }

    @Test fun `watchdog completes once even if transport call stalls`() {
        val started = CountDownLatch(1)
        val release = CountDownLatch(1)
        val returned = CountDownLatch(1)
        shadowOf(controller).setMetadata(metadata(rating = Rating.newHeartRating(false)))
        shadowOf(controller).setPlaybackState(playback(PlaybackState.ACTION_SET_RATING))
        TestTransportControls.onRating = {
            started.countDown()
            release.await(3, TimeUnit.SECONDS)
            returned.countDown()
        }
        try {
            request()
            assertTrue(started.await(2, TimeUnit.SECONDS))
            main.idleFor(6, TimeUnit.SECONDS)
            assertEquals(listOf(LikeResult.UNCONFIRMED), results)
        } finally {
            release.countDown()
            assertTrue(returned.await(2, TimeUnit.SECONDS))
        }
        main.idle()
        service.onListenerDisconnected()
        assertEquals(1, results.size)
    }

    @Test fun `disconnect cancels a pending command and clears favourite state`() {
        shadowOf(controller).setPlaybackState(playback(custom = action("player.LIKE", "Like")))
        request()
        awaitSent()
        service.onListenerDisconnected()
        main.idleFor(6, TimeUnit.SECONDS)
        assertEquals(listOf(LikeResult.DISCONNECTED), results)
        assertFalse(PlayerSnapshot.isLiked)
        assertFalse(PlayerSnapshot.likeStateKnown)
        assertFalse(PlayerSnapshot.likeAvailable)
        assertNull(MusicNotificationListener.activeController)
    }

    private fun request() { MusicNotificationListener.toggleLike { results += it }; main.idle() }

    private fun newToken(): MediaSession.Token {
        val parcel = Parcel.obtain()
        return try {
            parcel.writeStrongBinder(Binder())
            parcel.setDataPosition(0)
            MediaSession.Token.CREATOR.createFromParcel(parcel)
        } finally { parcel.recycle() }
    }

    @Implements(MediaController.TransportControls::class)
    class TestTransportControls : ShadowTransportControls() {
        companion object { @Volatile var onRating: (() -> Unit)? = null }

        @Implementation
        override fun setRating(rating: Rating) {
            onRating?.invoke()
            super.setRating(rating)
        }
    }

    private fun awaitSent() {
        val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(2)
        val pendingField = MusicNotificationListener::class.java.getDeclaredField("pendingLike").apply { isAccessible = true }
        while (System.nanoTime() < deadline) {
            main.idle()
            val pending = pendingField.get(service)
            if (pending != null && pending.javaClass.getDeclaredField("sent").apply { isAccessible = true }.getBoolean(pending)) return
            Thread.sleep(5)
        }
        fail("Transport did not return within two seconds; results=$results")
    }

    private fun setStatic(name: String, value: Any?) {
        MusicNotificationListener::class.java.getDeclaredField(name).apply { isAccessible = true }.set(null, value)
    }

    private fun metadata(id: String = "track-a", rating: Rating? = null): MediaMetadata = MediaMetadata.Builder()
        .putString(MediaMetadata.METADATA_KEY_MEDIA_ID, id)
        .putString(MediaMetadata.METADATA_KEY_TITLE, "Track A")
        .putString(MediaMetadata.METADATA_KEY_ARTIST, "Artist")
        .putLong(MediaMetadata.METADATA_KEY_DURATION, 120000L)
        .apply { if (rating != null) putRating(MediaMetadata.METADATA_KEY_USER_RATING, rating) }
        .build()

    private fun action(id: String, label: String) = PlaybackState.CustomAction.Builder(id, label, android.R.drawable.btn_star).build()
    private fun playback(actions: Long = 0L, custom: PlaybackState.CustomAction? = null): PlaybackState = PlaybackState.Builder()
        .setState(PlaybackState.STATE_PLAYING, 0L, 1f).setActions(actions)
        .apply { if (custom != null) addCustomAction(custom) }.build()

    @Suppress("DEPRECATION", "UNCHECKED_CAST")
    private fun notification(label: String, intentAction: String, otherToken: MediaSession.Token = token, title: String = "Track A") {
        val intent = PendingIntent.getBroadcast(service, intentAction.hashCode(), Intent(intentAction).setPackage(service.packageName), PendingIntent.FLAG_IMMUTABLE)
        val notification = Notification.Builder(service, "music").setContentTitle(title)
            .addAction(Notification.Action.Builder(0, label, intent).build()).build()
        notification.extras.putParcelable(Notification.EXTRA_MEDIA_SESSION, otherToken)
        val sbn = StatusBarNotification(service.packageName, service.packageName, 1, null, 0, 0, 0, notification, Process.myUserHandle(), 100L)
        val cache = MusicNotificationListener::class.java.getDeclaredField("notifications").apply { isAccessible = true }
            .get(service) as MutableMap<String, StatusBarNotification>
        cache[sbn.key] = sbn
    }
}

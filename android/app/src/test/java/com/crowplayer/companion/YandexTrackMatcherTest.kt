package com.crowplayer.companion

import org.junit.Assert.*
import org.junit.Test

class YandexTrackMatcherTest {
    private val source = LikeActionPolicy.Track("101", "Track A", "Artist", 120000)
    private val track = YandexMusicApi.Track("101", "Track A", "Artist", "Album", 120000)

    @Test fun `parses numeric compound and Yandex URL media identifiers`() {
        assertEquals("101", YandexTrackMatcher.mediaId("101"))
        assertEquals("101", YandexTrackMatcher.mediaId("101:555"))
        assertEquals("101", YandexTrackMatcher.mediaId("https://music.yandex.ru/album/555/track/101"))
        assertEquals("101", YandexTrackMatcher.mediaId("https://music.yandex.com/track/101"))
    }

    @Test fun `does not extract arbitrary numbers or accept another provider`() {
        listOf("file-101", "local:101", "https://example.com/track/101", "https://music.yandex.ru.evil.org/track/101", "101:wrong").forEach {
            assertNull(it, YandexTrackMatcher.mediaId(it))
        }
    }

    @Test fun `exact id still requires matching artist title and duration`() {
        assertTrue(YandexTrackMatcher.matches(source, "", track, true))
        assertFalse(YandexTrackMatcher.matches(source, "", track.copy(title = "Track B"), true))
        assertFalse(YandexTrackMatcher.matches(source, "", track.copy(artist = "Another artist"), true))
        assertFalse(YandexTrackMatcher.matches(source, "", track.copy(duration = 180000), true))
    }

    @Test fun `search requires duration and an album match when provided`() {
        assertTrue(YandexTrackMatcher.matches(source, "Album", track, false))
        assertFalse(YandexTrackMatcher.matches(source, "Live", track, false))
        assertFalse(YandexTrackMatcher.matches(source.copy(duration = 0), "Album", track, false))
        assertFalse(YandexTrackMatcher.matches(source, "Album", track.copy(duration = 0), false))
    }

    @Test fun `formatting normalization preserves different versions`() {
        assertTrue(YandexTrackMatcher.matches(source.copy(title = "TRACK-A"), "Album", track, false))
        assertFalse(YandexTrackMatcher.matches(source, "Album", track.copy(title = "Track A (Live)"), false))
    }
}

package com.crowplayer.companion

import com.crowplayer.companion.LikeActionPolicy.Direction.ADD
import com.crowplayer.companion.LikeActionPolicy.Direction.REMOVE
import org.junit.Assert.*
import org.junit.Test

class LikeActionPolicyTest {
    @Test fun `recognises explicit Russian and English favourite commands`() {
        listOf("Мне нравится", "Добавить в избранное", "LIKE", "Add to favourites").forEach {
            assertEquals(it, ADD, LikeActionPolicy.direction(it))
        }
        listOf("Убрать из избранного", "Снять лайк", "Больше не нравится", "Unlike").forEach {
            assertEquals(it, REMOVE, LikeActionPolicy.direction(it))
        }
    }

    @Test fun `dislike and ban are never treated as unlike`() {
        listOf("Не нравится", "Мне не нравится", "Dislike", "Ban", "Block").forEach {
            assertNull(it, LikeActionPolicy.direction(it, "player.LIKE"))
        }
        assertNull(LikeActionPolicy.direction("Like", "player.DISLIKE"))
    }

    @Test fun `uses advertised namespaced identifiers without inventing commands`() {
        assertEquals(ADD, LikeActionPolicy.direction("", "player.action.ADD_TO_FAVORITES"))
        assertEquals(REMOVE, LikeActionPolicy.direction("", "player/removeFromFavorites"))
        assertNull(LikeActionPolicy.direction("", "player.toggle_like"))
        assertNull(LikeActionPolicy.direction("", "player.favorite"))
    }

    @Test fun `unknown labels and unrelated playback actions remain unsupported`() {
        listOf("Play", "Next", "Download", "Добавить в плейлист", "Похожее", "❤", "Liked").forEach {
            assertNull(it, LikeActionPolicy.direction(it))
        }
    }

    @Test fun `infers state only from a single direction`() {
        assertEquals(false, LikeActionPolicy.reportedState(listOf(ADD, ADD)))
        assertEquals(true, LikeActionPolicy.reportedState(listOf(REMOVE)))
        assertNull(LikeActionPolicy.reportedState(listOf(ADD, REMOVE)))
        assertNull(LikeActionPolicy.reportedState(emptyList()))
    }

    @Test fun `selects inverse command and preserves source priority`() {
        assertEquals(1, LikeActionPolicy.choose(listOf(ADD, REMOVE, REMOVE), true))
        assertEquals(0, LikeActionPolicy.choose(listOf(ADD, REMOVE), false))
        assertNull(LikeActionPolicy.choose(listOf(ADD), true))
    }

    @Test fun `does not guess when both directions exist and state is unknown`() {
        assertNull(LikeActionPolicy.choose(listOf(ADD, REMOVE), null))
        assertEquals(0, LikeActionPolicy.choose(listOf(ADD, ADD), null))
        assertNull(LikeActionPolicy.choose(emptyList(), null))
    }
}

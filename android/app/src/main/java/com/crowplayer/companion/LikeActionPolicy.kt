package com.crowplayer.companion

import java.util.Locale

/** Only explicit favourite actions are accepted. Dislike/ban is never unlike. */
internal object LikeActionPolicy {
    enum class Direction(val liked: Boolean) { ADD(true), REMOVE(false) }

    private val add = setOf(
        "like", "like track", "like song", "add like", "add to favorites", "add to favourites",
        "add to liked tracks", "add to my favorites", "add to my favourites",
        "нравится", "мне нравится", "лайк", "поставить лайк", "добавить в избранное",
        "добавить в любимое", "добавить в любимые треки"
    )
    private val remove = setOf(
        "unlike", "remove like", "remove from favorites", "remove from favourites",
        "remove from liked tracks", "remove from my favorites", "remove from my favourites",
        "убрать лайк", "снять лайк", "отменить лайк", "убрать из избранного",
        "удалить из избранного", "убрать из любимого", "удалить из любимого",
        "убрать из любимых треков", "больше не нравится"
    )
    private val negative = setOf("dislike", "не нравится", "мне не нравится", "ban", "block", "don t like")

    fun direction(label: CharSequence?, actionId: String? = null): Direction? {
        val title = normalize(label?.toString().orEmpty())
        val id = normalize(actionId.orEmpty().substringAfterLast('.').substringAfterLast('/').substringAfterLast(':'))
        if (id in negative) return null
        if (title in remove) return Direction.REMOVE
        if (title in negative) return null
        if (title in add) return Direction.ADD
        // Use only the advertised identifier, never invent a Yandex command.
        return when (id) {
            in remove -> Direction.REMOVE
            in add -> Direction.ADD
            else -> null
        }
    }

    private fun normalize(value: String): String = value
        .replace(Regex("([a-z])([A-Z])"), "$1 $2")
        .lowercase(Locale.ROOT).replace('ё', 'е')
        .replace(Regex("[^\\p{L}\\p{N}]+"), " ").trim()

    /** An inverse action reports current state only when it is unambiguous. */
    fun reportedState(directions: List<Direction>): Boolean? = when (directions.distinct().singleOrNull()) {
        Direction.ADD -> false
        Direction.REMOVE -> true
        null -> null
    }

    fun choose(directions: List<Direction>, current: Boolean?): Int? {
        if (current != null) return directions.indexOfFirst { it.liked != current }.takeIf { it >= 0 }
        return if (directions.distinct().size == 1) directions.indices.firstOrNull() else null
    }

    data class Track(val mediaId: String?, val title: String?, val artist: String?, val duration: Long)
}

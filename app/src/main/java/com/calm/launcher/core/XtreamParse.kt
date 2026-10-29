package com.calm.launcher.core

/**
 * Turns Xtream `player_api.php` JSON into categories and live channels.
 * Pure: no network, no Android. JsonMini returns numbers as Double, so IDs
 * are normalised here: 42.0 and "42" both become "42".
 */
object XtreamParse {

    fun idText(v: Any?): String? = when (v) {
        null -> null
        is Double -> if (v % 1.0 == 0.0) v.toLong().toString() else v.toString()
        is String -> v.trim().ifEmpty { null }
        else -> v.toString()
    }

    private fun longOf(v: Any?): Long? = when (v) {
        is Number -> v.toLong()
        is String -> v.trim().toLongOrNull()
        else -> null
    }

    private fun intOf(v: Any?): Int = (longOf(v) ?: 0L).coerceIn(0L, Int.MAX_VALUE.toLong()).toInt()

    /** `get_live_categories`: [{category_id, category_name}, ...]. */
    fun liveCategories(json: Any?): List<IptvLogic.Category> {
        val rows = json as? List<*> ?: return emptyList()
        return rows.mapNotNull { item ->
            val m = item as? Map<*, *> ?: return@mapNotNull null
            val id = idText(m["category_id"]) ?: return@mapNotNull null
            val name = (m["category_name"] as? String)?.trim().takeUnless { it.isNullOrEmpty() } ?: id
            IptvLogic.Category(id, name, IptvLogic.SRC_XTREAM, IptvLogic.KIND_LIVE)
        }.distinctBy { it.id }
    }

    /**
     * `get_live_streams`: channels with a usable stream_id and name. [liveUrl]
     * builds each channel's playback URL from its id.
     */
    fun liveStreams(json: Any?, categoryNames: Map<String, String>, liveUrl: (Long) -> String): List<IptvLogic.LiveChannel> {
        val rows = json as? List<*> ?: return emptyList()
        return rows.mapNotNull { item ->
            val m = item as? Map<*, *> ?: return@mapNotNull null
            val id = longOf(m["stream_id"]) ?: return@mapNotNull null
            val name = (m["name"] as? String)?.trim().takeUnless { it.isNullOrEmpty() } ?: return@mapNotNull null
            val catId = idText(m["category_id"]) ?: ""
            IptvLogic.LiveChannel(
                id = id,
                name = name,
                icon = (m["stream_icon"] as? String)?.trim().orEmpty(),
                categoryId = catId,
                categoryName = categoryNames[catId] ?: "Other",
                epgId = (m["epg_channel_id"] as? String)?.trim().orEmpty(),
                source = IptvLogic.SRC_XTREAM,
                directUrl = liveUrl(id),
                tvArchive = intOf(m["tv_archive"]),
                archiveDays = intOf(m["tv_archive_duration"])
            )
        }.distinctBy { it.id }
    }
}

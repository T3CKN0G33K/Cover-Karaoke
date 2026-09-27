package com.barton.dualscreenhost

import android.content.Context

object OffsetCacheManager {

    private fun getKey(title: String, artist: String): String {
        val cleanTitle = title.trim().lowercase().replace(Regex("[^a-z0-9]"), "")
        val cleanArtist = artist.trim().lowercase().replace(Regex("[^a-z0-9]"), "")
        return "offset_${cleanArtist}_${cleanTitle}"
    }

    fun saveOffset(context: Context, title: String, artist: String, offsetMs: Long) {
        if (title.isBlank()) return
        val key = getKey(title, artist)
        context.getSharedPreferences("LyricOffsets", Context.MODE_PRIVATE)
            .edit()
            .putLong(key, offsetMs)
            .apply()
    }

    fun getOffset(context: Context, title: String, artist: String): Long? {
        if (title.isBlank()) return null
        val prefs = context.getSharedPreferences("LyricOffsets", Context.MODE_PRIVATE)
        val key = getKey(title, artist)
        return if (prefs.contains(key)) prefs.getLong(key, 0L) else null
    }
}

package com.michalkulik.photogallery.syno

import org.json.JSONArray
import org.json.JSONObject

/**
 * Serialises the album list so it survives leaving the setup screen.
 *
 * The list is only fetched by an explicit connect, so without this the albums would disappear as
 * soon as the screen was closed and the user would have to sign in again just to pick one.
 * Kept free of Android APIs so it can be unit tested.
 */
object SynoAlbumCodec {

    fun encode(albums: List<SynoAlbum>): String {
        val array = JSONArray()
        albums.forEach { album ->
            array.put(
                JSONObject()
                    .put("id", album.id)
                    .put("name", album.name)
                    .put("count", album.itemCount)
                    .put("shared", album.isShared),
            )
        }
        return array.toString()
    }

    /** Decodes the stored list, skipping malformed entries. */
    fun decode(json: String?): List<SynoAlbum> {
        if (json.isNullOrBlank()) return emptyList()
        val array = runCatching { JSONArray(json) }.getOrNull() ?: return emptyList()
        val result = ArrayList<SynoAlbum>(array.length())
        for (index in 0 until array.length()) {
            val item = array.optJSONObject(index) ?: continue
            result += SynoAlbum(
                id = item.optInt("id", 0),
                name = item.optString("name"),
                itemCount = item.optInt("count", 0),
                isShared = item.optBoolean("shared", false),
            )
        }
        return result
    }
}

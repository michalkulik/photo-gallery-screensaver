package com.michalkulik.photogallery.data

import org.json.JSONArray
import org.json.JSONObject

/**
 * Serialises the configured [PhotoSource] list to JSON and back.
 * Kept free of Android APIs so it can be unit tested on the JVM.
 */
object SourceCodec {

    fun encode(sources: List<PhotoSource>): String {
        val array = JSONArray()
        sources.forEach { source ->
            array.put(
                JSONObject()
                    .put("id", source.id)
                    .put("kind", source.kind.name)
                    .put("name", source.name)
                    .put("ref", source.ref)
                    .put("count", source.photoCount),
            )
        }
        return array.toString()
    }

    /** Decodes the stored JSON, skipping entries that are malformed or unknown. */
    fun decode(json: String?): List<PhotoSource> {
        if (json.isNullOrBlank()) return emptyList()
        val array = try {
            JSONArray(json)
        } catch (_: Exception) {
            return emptyList()
        }
        val result = ArrayList<PhotoSource>(array.length())
        for (index in 0 until array.length()) {
            val item = array.optJSONObject(index) ?: continue
            val id = item.optString("id").takeIf { it.isNotBlank() } ?: continue
            val kind = runCatching { SourceKind.valueOf(item.optString("kind")) }.getOrNull() ?: continue
            result += PhotoSource(
                id = id,
                kind = kind,
                // Blank names are kept as-is; the UI substitutes a localised fallback.
                name = item.optString("name"),
                ref = item.optString("ref"),
                photoCount = item.optInt("count", 0),
            )
        }
        return result
    }
}

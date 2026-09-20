package com.jcversa.canta.model

import org.json.JSONArray
import org.json.JSONObject

/**
 * A language variant. Canta never guesses this value: on voir-anime.to it is
 * structural (a VF entry lives at a `-vf` slug, evidence: RECONNAISSANCE.md §2),
 * on nakanime it comes from the catalogue API's `languages` array.
 */
enum class Language(val tag: String, val label: String, val displayName: String) {
    VF("vf", "VF", "Français"),
    VOSTFR("vostfr", "VOSTFR", "Japonais sous-titré FR");

    companion object {
        fun fromTag(raw: String?): Language? {
            val v = raw?.trim()?.lowercase() ?: return null
            return entries.firstOrNull { it.tag == v || it.label.lowercase() == v }
        }
    }
}

/** Where a title was scraped from. Shown in the UI so the source is never implicit. */
enum class Source(val id: String, val host: String) {
    VOIRANIME("voir-anime", "voir-anime.to"),
    NAKANIME("nakanime", "nakanime.tv");

    companion object {
        fun fromId(raw: String?): Source? = entries.firstOrNull { it.id == raw }
    }
}

/**
 * A series as offered by a catalogue page or a search result.
 *
 * [id] is the stable key used by favourites, the watchlist and downloads; it is
 * built from the source and the provider's own identifier so it survives title
 * renames (voir-anime ships each season as its own entry with its own slug).
 */
data class Anime(
    val id: String,
    val title: String,
    val url: String,
    val source: Source,
    val languages: Set<Language> = emptySet(),
    val posterUrl: String? = null,
    val synopsis: String? = null,
    val episodeCount: Int? = null,
    val rating: Double? = null,
    val genres: List<String> = emptyList()
) {
    val isVf: Boolean get() = languages.contains(Language.VF)
    val isVostfr: Boolean get() = languages.contains(Language.VOSTFR)

    fun toJson(): JSONObject = JSONObject().apply {
        put("id", id)
        put("title", title)
        put("url", url)
        put("source", source.id)
        put("languages", JSONArray(languages.map { it.tag }))
        put("posterUrl", posterUrl ?: JSONObject.NULL)
        put("synopsis", synopsis ?: JSONObject.NULL)
        put("episodeCount", episodeCount ?: JSONObject.NULL)
        put("rating", rating ?: JSONObject.NULL)
        put("genres", JSONArray(genres))
    }

    companion object {
        fun fromJson(json: JSONObject): Anime? {
            val id = json.optString("id").takeIf { it.isNotEmpty() } ?: return null
            val url = json.optString("url").takeIf { it.isNotEmpty() } ?: return null
            val source = Source.fromId(json.optString("source")) ?: Source.VOIRANIME
            val langs = mutableSetOf<Language>()
            json.optJSONArray("languages")?.let { arr ->
                for (i in 0 until arr.length()) Language.fromTag(arr.optString(i))?.let { langs += it }
            }
            val genres = mutableListOf<String>()
            json.optJSONArray("genres")?.let { arr ->
                for (i in 0 until arr.length()) arr.optString(i).takeIf { it.isNotBlank() }?.let { genres += it }
            }
            return Anime(
                id = id,
                title = json.optString("title", id),
                url = url,
                source = source,
                languages = langs,
                posterUrl = json.optStringOrNull("posterUrl"),
                synopsis = json.optStringOrNull("synopsis"),
                episodeCount = if (json.isNull("episodeCount")) null else json.optInt("episodeCount"),
                rating = if (json.isNull("rating")) null else json.optDouble("rating").takeIf { !it.isNaN() },
                genres = genres
            )
        }
    }
}

/** Full series page: the [Anime] plus what only the detail page carries. */
data class AnimeDetail(
    val anime: Anime,
    val synopsis: String?,
    val posterUrl: String?,
    val genres: List<String>,
    val episodeCount: Int?,
    val episodes: List<Episode>
)

/** Null when the key is absent *or* explicitly null — org.json conflates both. */
internal fun JSONObject.optStringOrNull(key: String): String? {
    if (!has(key) || isNull(key)) return null
    val v = optString(key)
    return v.takeIf { it.isNotEmpty() && it != "null" }
}

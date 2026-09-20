package com.jcversa.canta.model

import org.json.JSONObject

/**
 * One episode of one series, in one language.
 *
 * [number] is 0 for films/OAVs on voir-anime.to (their slugs carry no number,
 * evidence: RECONNAISSANCE.md §2.3) — the label is then "Film"/"OAV" and the
 * UI must not print "Épisode 0".
 *
 * [releaseDate] is the raw date string the source publishes (Madara renders
 * `span.chapter-release-date`), kept verbatim rather than reformatted: guessing
 * a timezone or a locale for a scraped date is how you silently lie to a user.
 */
data class Episode(
    val number: Int,
    val title: String,
    val url: String,
    val source: Source,
    val language: Language,
    val releaseDate: String? = null,
    val seriesId: String = "",
    val seriesTitle: String = "",
    val seriesUrl: String = "",
    val posterUrl: String? = null,
    val isFiller: Boolean = false
) {
    val label: String
        get() = when {
            number > 0 -> "Épisode $number"
            title.isNotBlank() -> title
            else -> "Épisode spécial"
        }

    /** Stable key for history/downloads, including the language variant. */
    val key: String get() = "$source:$url#$language"

    fun toJson(): JSONObject = JSONObject().apply {
        put("number", number)
        put("title", title)
        put("url", url)
        put("source", source.id)
        put("language", language.tag)
        put("releaseDate", releaseDate ?: JSONObject.NULL)
        put("seriesId", seriesId)
        put("seriesTitle", seriesTitle)
        put("seriesUrl", seriesUrl)
        put("posterUrl", posterUrl ?: JSONObject.NULL)
        put("isFiller", isFiller)
    }

    companion object {
        fun fromJson(json: JSONObject): Episode? {
            val url = json.optString("url").takeIf { it.isNotEmpty() } ?: return null
            return Episode(
                number = json.optInt("number", 0),
                title = json.optString("title"),
                url = url,
                source = Source.fromId(json.optString("source")) ?: Source.VOIRANIME,
                language = Language.fromTag(json.optString("language")) ?: Language.VF,
                releaseDate = json.optStringOrNull("releaseDate"),
                seriesId = json.optString("seriesId"),
                seriesTitle = json.optString("seriesTitle"),
                seriesUrl = json.optString("seriesUrl"),
                posterUrl = json.optStringOrNull("posterUrl"),
                isFiller = json.optBoolean("isFiller", false)
            )
        }
    }
}

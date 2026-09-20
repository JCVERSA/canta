package com.jcversa.canta.manager

import android.content.Context
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import com.jcversa.canta.model.Anime
import com.jcversa.canta.model.Episode
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import org.json.JSONArray
import org.json.JSONObject

/** One watched episode, with where the user stopped. */
data class HistoryEntry(
    val seriesId: String,
    val seriesTitle: String,
    val seriesUrl: String,
    val posterUrl: String?,
    val episodeNumber: Int,
    val episodeTitle: String,
    val episodeUrl: String,
    val language: String,
    val positionMs: Long,
    val durationMs: Long,
    val updatedAt: Long
) {
    val progress: Float
        get() = if (durationMs > 0) (positionMs.toFloat() / durationMs.toFloat()).coerceIn(0f, 1f) else 0f

    fun toJson(): JSONObject = JSONObject().apply {
        put("seriesId", seriesId)
        put("seriesTitle", seriesTitle)
        put("seriesUrl", seriesUrl)
        put("posterUrl", posterUrl ?: JSONObject.NULL)
        put("episodeNumber", episodeNumber)
        put("episodeTitle", episodeTitle)
        put("episodeUrl", episodeUrl)
        put("language", language)
        put("positionMs", positionMs)
        put("durationMs", durationMs)
        put("updatedAt", updatedAt)
    }

    companion object {
        fun fromJson(json: JSONObject): HistoryEntry? {
            val seriesId = json.optString("seriesId").takeIf { it.isNotEmpty() } ?: return null
            return HistoryEntry(
                seriesId = seriesId,
                seriesTitle = json.optString("seriesTitle"),
                seriesUrl = json.optString("seriesUrl"),
                posterUrl = if (json.isNull("posterUrl")) null else json.optString("posterUrl").takeIf { it.isNotEmpty() },
                episodeNumber = json.optInt("episodeNumber", 0),
                episodeTitle = json.optString("episodeTitle"),
                episodeUrl = json.optString("episodeUrl"),
                language = json.optString("language", "vf"),
                positionMs = json.optLong("positionMs", 0L),
                durationMs = json.optLong("durationMs", 0L),
                updatedAt = json.optLong("updatedAt", 0L)
            )
        }
    }
}

/**
 * Watch history: one entry per series (the last episode watched), so "reprendre"
 * means the episode the user actually stopped in.
 */
class HistoryManager(private val context: Context) {

    private val key = stringPreferencesKey("history")

    val entries: Flow<List<HistoryEntry>> = context.cantaDataStore.data.map { prefs ->
        decode(prefs[key]).sortedByDescending { it.updatedAt }
    }

    fun entryFor(seriesId: String): Flow<HistoryEntry?> = entries.map { list -> list.firstOrNull { it.seriesId == seriesId } }

    suspend fun record(episode: Episode, positionMs: Long, durationMs: Long) {
        val entry = HistoryEntry(
            seriesId = episode.seriesId.ifEmpty { episode.seriesUrl },
            seriesTitle = episode.seriesTitle,
            seriesUrl = episode.seriesUrl,
            posterUrl = episode.posterUrl,
            episodeNumber = episode.number,
            episodeTitle = episode.title,
            episodeUrl = episode.url,
            language = episode.language.tag,
            positionMs = positionMs,
            durationMs = durationMs,
            updatedAt = System.currentTimeMillis()
        )
        context.cantaDataStore.edit { prefs ->
            val current = decode(prefs[key]).filterNot { it.seriesId == entry.seriesId }.toMutableList()
            current.add(0, entry)
            prefs[key] = encode(current.take(200))
        }
    }

    suspend fun clear() {
        context.cantaDataStore.edit { prefs -> prefs.remove(key) }
    }

    private fun decode(raw: String?): List<HistoryEntry> {
        if (raw.isNullOrBlank()) return emptyList()
        return try {
            val array = JSONArray(raw)
            (0 until array.length()).mapNotNull { index -> array.optJSONObject(index)?.let { HistoryEntry.fromJson(it) } }
        } catch (_: Exception) {
            emptyList()
        }
    }

    private fun encode(list: List<HistoryEntry>): String =
        JSONArray().apply { list.forEach { put(it.toJson()) } }.toString()
}

/** Small helper so the UI can show a series without re-scraping the catalogue. */
fun HistoryEntry.asAnime(source: com.jcversa.canta.model.Source = com.jcversa.canta.model.Source.VOIRANIME): Anime = Anime(
    id = seriesId,
    title = seriesTitle,
    url = seriesUrl,
    source = source,
    posterUrl = posterUrl
)

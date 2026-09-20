package com.jcversa.canta.manager

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import com.jcversa.canta.model.Anime
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import org.json.JSONArray
import org.json.JSONObject

/**
 * The single DataStore for the whole app. Declared once here and imported by
 * the other managers: a second `preferencesDataStore` delegate on the same
 * Context would throw at runtime.
 */
internal val Context.cantaDataStore: DataStore<Preferences> by preferencesDataStore(name = "canta_preferences")

/** Favourites: lightweight, local, no account anywhere in this app. */
class FavoritesManager(private val context: Context) {

    private val key = stringPreferencesKey("favorites")

    val favorites: Flow<List<Anime>> = context.cantaDataStore.data.map { prefs ->
        parseList(prefs[key])
    }

    suspend fun toggle(anime: Anime) {
        context.cantaDataStore.edit { prefs ->
            val current = parseList(prefs[key]).toMutableList()
            val existing = current.indexOfFirst { it.id == anime.id }
            if (existing >= 0) current.removeAt(existing) else current.add(0, anime)
            prefs[key] = encodeList(current)
        }
    }

    suspend fun remove(id: String) {
        context.cantaDataStore.edit { prefs ->
            val current = parseList(prefs[key]).filterNot { it.id == id }
            prefs[key] = encodeList(current)
        }
    }

    private fun parseList(raw: String?): List<Anime> {
        if (raw.isNullOrBlank()) return emptyList()
        return try {
            val array = JSONArray(raw)
            (0 until array.length()).mapNotNull { index ->
                array.optJSONObject(index)?.let { Anime.fromJson(it) }
            }
        } catch (_: Exception) {
            emptyList()
        }
    }

    private fun encodeList(list: List<Anime>): String {
        val array = JSONArray()
        list.forEach { array.put(it.toJson()) }
        return array.toString()
    }

    companion object {
        /** Serialises an arbitrary anime list — shared with the watchlist manager. */
        internal fun encode(list: List<Anime>): String = JSONArray().apply { list.forEach { put(it.toJson()) } }.toString()

        internal fun decode(raw: String?): List<Anime> {
            if (raw.isNullOrBlank()) return emptyList()
            return try {
                val array = JSONArray(raw)
                (0 until array.length()).mapNotNull { index -> array.optJSONObject(index)?.let { Anime.fromJson(it) } }
            } catch (_: Exception) {
                emptyList()
            }
        }

        internal fun encodeEpisodeJson(json: JSONObject): String = json.toString()
    }
}

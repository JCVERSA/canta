package com.jcversa.canta.manager

import android.content.Context
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import com.jcversa.canta.model.Anime
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map

/**
 * Series the background worker watches for new VF episodes.
 *
 * Two pieces of state per series: the [Anime] itself (enough to scrape the
 * episode list again without a catalogue round trip) and the highest episode
 * number already announced, so a worker run never re-notifies the same episode
 * and a first run never floods the notification shade with an entire backlog.
 */
class WatchlistManager(private val context: Context) {

    private val listKey = stringPreferencesKey("watchlist")
    private val seenPrefix = "watchlist_seen_"

    val series: Flow<List<Anime>> = context.cantaDataStore.data.map { prefs ->
        FavoritesManager.decode(prefs[listKey])
    }

    suspend fun watch(anime: Anime, lastSeenEpisode: Int? = null) {
        context.cantaDataStore.edit { prefs ->
            val current = FavoritesManager.decode(prefs[listKey]).filterNot { it.id == anime.id }.toMutableList()
            current.add(0, anime)
            prefs[listKey] = FavoritesManager.encode(current)
            if (lastSeenEpisode != null) {
                prefs[intPreferencesKey(seenPrefix + anime.id)] = lastSeenEpisode
            }
        }
    }

    suspend fun unwatch(id: String) {
        context.cantaDataStore.edit { prefs ->
            prefs[listKey] = FavoritesManager.encode(FavoritesManager.decode(prefs[listKey]).filterNot { it.id == id })
            prefs.remove(intPreferencesKey(seenPrefix + id))
        }
    }

    /** Highest episode number already announced for a series (null = never checked). */
    suspend fun lastSeenEpisode(seriesId: String): Int? =
        context.cantaDataStore.data.map { prefs -> prefs[intPreferencesKey(seenPrefix + seriesId)] }.first()

    suspend fun setLastSeenEpisode(seriesId: String, episodeNumber: Int) {
        context.cantaDataStore.edit { prefs ->
            prefs[intPreferencesKey(seenPrefix + seriesId)] = episodeNumber
        }
    }

    /** How many series a single worker run will look at — batches stay bounded. */
    val batchLimit: Int = 20
}

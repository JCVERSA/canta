package com.jcversa.canta.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.jcversa.canta.AppViewModel
import com.jcversa.canta.model.Anime
import com.jcversa.canta.ui.components.AnimeCard
import com.jcversa.canta.ui.components.EmptyState

/**
 * Favourites + watchlist + "reprendre" — all three are local-only state, which
 * is why this screen is instant and works offline.
 */
@Composable
fun FavoritesScreen(
    viewModel: AppViewModel,
    onOpenAnime: (Anime) -> Unit,
    modifier: Modifier = Modifier
) {
    val favorites by viewModel.favorites.collectAsStateWithLifecycle()
    val watchlist by viewModel.watchlist.collectAsStateWithLifecycle()
    val history by viewModel.history.collectAsStateWithLifecycle()

    if (favorites.isEmpty() && watchlist.isEmpty() && history.isEmpty()) {
        EmptyState("Aucun favori, aucune série surveillée, aucun historique pour l'instant.")
        return
    }

    LazyVerticalGrid(
        columns = GridCells.Adaptive(minSize = 140.dp),
        contentPadding = PaddingValues(16.dp),
        horizontalArrangement = Arrangement.spacedBy(12.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
        modifier = modifier.fillMaxSize()
    ) {
        if (history.isNotEmpty()) {
            item(span = { GridItemSpan(maxLineSpan) }) {
                Column {
                    Text("Reprendre", style = MaterialTheme.typography.titleLarge)
                    history.take(3).forEach { entry ->
                        Text(
                            text = "${entry.seriesTitle} — épisode ${entry.episodeNumber} (${entry.language.uppercase()}) · ${(entry.progress * 100).toInt()}%",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.padding(vertical = 4.dp)
                        )
                    }
                }
            }
        }

        if (favorites.isNotEmpty()) {
            item(span = { GridItemSpan(maxLineSpan) }) {
                Text("Favoris", style = MaterialTheme.typography.titleLarge)
            }
            items(favorites, key = { "fav-${it.id}" }) { anime ->
                AnimeCard(
                    anime = anime,
                    onClick = { onOpenAnime(anime) },
                    trailing = {
                        Text(
                            text = "Retirer",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.error,
                            modifier = Modifier.padding(top = 2.dp)
                        )
                    }
                )
            }
        }

        if (watchlist.isNotEmpty()) {
            item(span = { GridItemSpan(maxLineSpan) }) {
                Text(
                    "Surveillées (nouveaux épisodes VF)",
                    style = MaterialTheme.typography.titleLarge,
                    modifier = Modifier.padding(top = 12.dp)
                )
            }
            items(watchlist, key = { "watch-${it.id}" }) { anime ->
                AnimeCard(anime = anime, onClick = { onOpenAnime(anime) })
            }
        }
    }
}

package com.jcversa.canta.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Favorite
import androidx.compose.material.icons.filled.FavoriteBorder
import androidx.compose.material.icons.filled.Notifications
import androidx.compose.material.icons.filled.NotificationsNone
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material3.AssistChip
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.jcversa.canta.AppViewModel
import com.jcversa.canta.model.Episode
import com.jcversa.canta.model.Language
import com.jcversa.canta.ui.components.ErrorState
import com.jcversa.canta.ui.components.LanguageBadge
import com.jcversa.canta.ui.components.LanguageToggle
import com.jcversa.canta.ui.components.LoadingState
import com.jcversa.canta.ui.components.RemoteImage

/**
 * Series detail: cover, synopsis, the language toggle, and the episode list.
 *
 * The toggle is honest about availability: a language the sources do not have is
 * rendered as "indisponible" and selecting it produces the explicit message
 * "VOSTFR non disponible…" rather than an empty list.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun DetailScreen(
    viewModel: AppViewModel,
    onBack: () -> Unit,
    onPlayEpisode: (Episode) -> Unit,
    modifier: Modifier = Modifier
) {
    val detail by viewModel.detail.collectAsStateWithLifecycle()
    val loading by viewModel.detailLoading.collectAsStateWithLifecycle()
    val error by viewModel.detailError.collectAsStateWithLifecycle()
    val notes by viewModel.detailNotes.collectAsStateWithLifecycle()
    val language by viewModel.language.collectAsStateWithLifecycle()
    val favorites by viewModel.favorites.collectAsStateWithLifecycle()
    val watchlist by viewModel.watchlist.collectAsStateWithLifecycle()
    val history by viewModel.history.collectAsStateWithLifecycle()

    val anime = detail?.anime
    val isFavorite = anime != null && favorites.any { it.id == anime.id }
    val isWatched = anime != null && watchlist.any { it.id == anime.id }
    val lastWatched = anime?.let { series -> history.firstOrNull { it.seriesId == series.id } }

    Column(modifier = modifier.fillMaxSize()) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(8.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(4.dp)
        ) {
            IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Retour") }
            Text(
                text = anime?.title ?: "Détail",
                style = MaterialTheme.typography.titleMedium,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f)
            )
            if (anime != null) {
                IconButton(onClick = { viewModel.toggleFavorite(anime) }) {
                    Icon(
                        imageVector = if (isFavorite) Icons.Filled.Favorite else Icons.Filled.FavoriteBorder,
                        contentDescription = if (isFavorite) "Retirer des favoris" else "Ajouter aux favoris"
                    )
                }
                IconButton(onClick = { viewModel.toggleWatchlist(anime) }) {
                    Icon(
                        imageVector = if (isWatched) Icons.Filled.Notifications else Icons.Filled.NotificationsNone,
                        contentDescription = if (isWatched) "Ne plus surveiller" else "Surveiller les nouveaux épisodes"
                    )
                }
            }
        }

        when {
            loading -> LoadingState("Chargement de la fiche…")
            anime == null -> ErrorState(error ?: "Série indisponible.")
            else -> LazyColumn(
                contentPadding = androidx.compose.foundation.layout.PaddingValues(16.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                item {
                    Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                        RemoteImage(
                            url = detail?.posterUrl ?: anime.posterUrl,
                            contentDescription = anime.title,
                            referer = if (anime.source.id == "voir-anime") "https://voir-anime.to/" else null,
                            modifier = Modifier
                                .width(120.dp)
                                .height(180.dp)
                                .clip(RoundedCornerShape(12.dp))
                        )
                        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                            Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                                anime.languages.forEach { LanguageBadge(it) }
                            }
                            Text(
                                text = "Source : ${anime.source.host}",
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                            detail?.episodeCount?.let {
                                Text("$it épisodes", style = MaterialTheme.typography.bodyMedium)
                            }
                            anime.rating?.let {
                                Text("Note source : ${"%.1f".format(it)}", style = MaterialTheme.typography.labelSmall)
                            }
                            lastWatched?.let { entry ->
                                Text(
                                    text = "Reprendre — épisode ${entry.episodeNumber} (${entry.language.uppercase()})",
                                    style = MaterialTheme.typography.labelSmall,
                                    color = MaterialTheme.colorScheme.primary
                                )
                            }
                        }
                    }
                }

                item {
                    LanguageToggle(
                        selected = language,
                        available = anime.languages.takeIf { it.isNotEmpty() } ?: Language.entries.toSet(),
                        onSelect = { viewModel.switchDetailLanguage(it) }
                    )
                }

                if (notes.isNotEmpty()) {
                    item {
                        Surface(
                            color = MaterialTheme.colorScheme.surfaceContainerHigh,
                            shape = RoundedCornerShape(10.dp),
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Column(modifier = Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                                notes.forEach { note ->
                                    Text(
                                        text = note,
                                        style = MaterialTheme.typography.labelSmall,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant
                                    )
                                }
                            }
                        }
                    }
                }

                error?.let { message ->
                    item { ErrorState(message) }
                }

                detail?.genres?.takeIf { it.isNotEmpty() }?.let { genres ->
                    item {
                        FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                            genres.forEach { genre -> AssistChip(onClick = {}, label = { Text(genre) }) }
                        }
                    }
                }

                detail?.synopsis?.takeIf { it.isNotBlank() }?.let { synopsis ->
                    item {
                        Text(
                            text = synopsis,
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }

                item {
                    Text(
                        text = "Épisodes",
                        style = MaterialTheme.typography.titleMedium,
                        modifier = Modifier.padding(top = 8.dp)
                    )
                }

                items(detail?.episodes.orEmpty(), key = { it.key }) { episode ->
                    EpisodeRow(
                        episode = episode,
                        progress = lastWatched?.takeIf { it.episodeUrl == episode.url }?.progress,
                        onPlay = { onPlayEpisode(episode) }
                    )
                }

                if (detail?.episodes.isNullOrEmpty()) {
                    item {
                        ErrorState("Aucun épisode listé pour ${language.label} — la langue n'est peut-être pas disponible.")
                    }
                }
            }
        }
    }
}

@Composable
private fun EpisodeRow(episode: Episode, progress: Float?, onPlay: () -> Unit) {
    Card(
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainer),
        modifier = Modifier.fillMaxWidth()
    ) {
        Column {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(12.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    Text(
                        text = episode.title.ifBlank { episode.label },
                        style = MaterialTheme.typography.bodyMedium,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis
                    )
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        LanguageBadge(episode.language)
                        episode.releaseDate?.let {
                            Text(
                                text = it,
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                        if (episode.isFiller) {
                            Text("HS", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.tertiary)
                        }
                    }
                }
                TextButton(onClick = onPlay) {
                    Icon(Icons.Filled.PlayArrow, contentDescription = null)
                    Text("Lire")
                }
            }
            if (progress != null && progress > 0f) {
                LinearProgressIndicator(
                    progress = { progress },
                    modifier = Modifier.fillMaxWidth()
                )
            }
            HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
        }
    }
}

package com.jcversa.canta.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.PlayCircle
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.media3.exoplayer.offline.Download
import com.jcversa.canta.AppViewModel
import com.jcversa.canta.model.Episode
import com.jcversa.canta.model.formatBytes
import com.jcversa.canta.ui.components.EmptyState
import com.jcversa.canta.ui.components.LanguageBadge

/**
 * Offline library.
 *
 * Rows are the Media3 downloads themselves — the same objects the download
 * service writes — so what the screen shows and what the player will find in the
 * cache cannot drift apart.
 */
@Composable
fun DownloadsScreen(
    viewModel: AppViewModel,
    onPlay: (Episode) -> Unit
) {
    val downloads by viewModel.downloads.collectAsStateWithLifecycle()

    LaunchedEffect(Unit) { viewModel.refreshDownloads() }

    Column(modifier = Modifier.fillMaxSize()) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text("Hors ligne", style = MaterialTheme.typography.titleLarge)
            TextButton(onClick = { viewModel.downloads.value.firstOrNull() }) { Text("") }
        }

        if (downloads.isEmpty()) {
            EmptyState("Aucun épisode téléchargé. Depuis le lecteur, utilisez l'icône de téléchargement : Media3 met le flux en cache pour une lecture hors connexion.")
        } else {
            LazyColumn(
                contentPadding = androidx.compose.foundation.layout.PaddingValues(16.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                items(downloads, key = { it.id }) { item ->
                    Card(
                        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainer),
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Column(modifier = Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                            Row(
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(10.dp)
                            ) {
                                Column(modifier = Modifier.weight(1f)) {
                                    Text(
                                        text = item.episode?.seriesTitle ?: "Épisode",
                                        style = MaterialTheme.typography.bodyMedium
                                    )
                                    Text(
                                        text = item.episode?.label ?: item.id,
                                        style = MaterialTheme.typography.labelSmall,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant
                                    )
                                }
                                item.episode?.let { LanguageBadge(it.language) }
                            }

                            Text(
                                text = buildString {
                                    append(
                                        when {
                                            item.isComplete -> "Terminé"
                                            item.isFailed -> "Échec"
                                            item.isDownloading -> "Téléchargement ${item.percent.toInt()}%"
                                            else -> "En attente"
                                        }
                                    )
                                    item.quality?.let { append(" · $it") }
                                    item.host?.let { append(" · $it") }
                                    if (item.bytesDownloaded > 0) append(" · ${formatBytes(item.bytesDownloaded)}")
                                },
                                style = MaterialTheme.typography.labelSmall,
                                color = if (item.isFailed) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant
                            )

                            if (item.isDownloading) {
                                LinearProgressIndicator(
                                    progress = { (item.percent / 100f).coerceIn(0f, 1f) },
                                    modifier = Modifier.fillMaxWidth()
                                )
                            }

                            Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                                if (item.isComplete && item.episode != null) {
                                    TextButton(onClick = { onPlay(item.episode!!) }) {
                                        Icon(Icons.Filled.PlayCircle, contentDescription = null)
                                        Text("Lire hors ligne")
                                    }
                                }
                                if (item.isDownloading) {
                                    TextButton(onClick = { viewModel.downloads.value.firstOrNull() }) {
                                        Icon(Icons.Filled.Pause, contentDescription = null)
                                        Text("")
                                    }
                                }
                                IconButton(onClick = { viewModel.removeDownload(item.id) }) {
                                    Icon(Icons.Filled.Delete, contentDescription = "Supprimer")
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}

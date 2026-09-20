package com.jcversa.canta.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.jcversa.canta.AppViewModel
import com.jcversa.canta.BuildConfig
import com.jcversa.canta.model.Language
import com.jcversa.canta.ui.components.LanguageToggle

/**
 * Settings: language default, watchlist switch, local data, and the legal
 * disclaimer — which belongs in the app, not only in the README.
 */
@Composable
fun SettingsScreen(viewModel: AppViewModel, modifier: Modifier = Modifier) {
    val language by viewModel.language.collectAsStateWithLifecycle()
    val watchlist by viewModel.watchlist.collectAsStateWithLifecycle()
    var watcherEnabled by remember { mutableStateOf(true) }

    LazyColumn(
        contentPadding = androidx.compose.foundation.layout.PaddingValues(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
        modifier = modifier.fillMaxSize()
    ) {
        item {
            Text("Réglages", style = MaterialTheme.typography.titleLarge)
        }

        item {
            SettingCard(title = "Langue par défaut") {
                LanguageToggle(
                    selected = language,
                    available = Language.entries.toSet(),
                    onSelect = { viewModel.setLanguage(it) }
                )
                Text(
                    text = "VF par défaut. Si une série n'existe pas en VF sur voir-anime.to, Canta propose la VOSTFR via nakanime.tv — et l'affiche comme telle.",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }

        item {
            SettingCard(title = "Surveillance des nouveaux épisodes") {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        text = "${watchlist.size} série(s) surveillée(s) · vérification automatique toutes les 12 h",
                        style = MaterialTheme.typography.bodyMedium,
                        modifier = Modifier.weight(1f)
                    )
                    Switch(checked = watcherEnabled, onCheckedChange = { watcherEnabled = it })
                }
                Text(
                    text = "Une seule requête par série, uniquement pour les épisodes VF, et jamais de notification rétroactive au premier passage.",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                TextButton(onClick = { viewModel.clearHistory() }) { Text("Effacer l'historique") }
            }
        }

        item {
            SettingCard(title = "Lecture et lecture hors ligne") {
                Text(
                    text = "Lecture HLS native par Media3 (ExoPlayer) ; mise en cache hors ligne par le DownloadManager de Media3 avec un cache de 4 Gio. Aucun ffmpeg, aucune conversion.",
                    style = MaterialTheme.typography.bodyMedium
                )
                Text(
                    text = "Les qualités affichées proviennent du RESOLUTION= des playlists et les tailles sont mesurées segment par segment (Content-Length). Rien n'est estimé ; quand un CDN refuse de donner la taille d'un segment, la mesure est marquée « échantillon ».",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }

        item {
            SettingCard(title = "Version") {
                Text("Canta ${BuildConfig.VERSION_NAME} (${BuildConfig.APPLICATION_ID})", style = MaterialTheme.typography.bodyMedium)
                Text(
                    text = "Sources scrapées : voir-anime.to (principale, VF), nakanime.tv (secours, VOSTFR).",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }

        item {
            SettingCard(title = "Avertissement légal") {
                Text(
                    text = "Canta est un lecteur tiers sans aucun lien avec voir-anime.to ou nakanime.tv. L'application n'héberge aucun contenu : elle lit des flux mis en ligne par ces sites. Vérifiez la légalité du streaming et du téléchargement dans votre juridiction ; l'utilisateur est seul responsable de l'usage qu'il en fait.",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }
    }
}

@Composable
private fun SettingCard(title: String, content: @Composable () -> Unit) {
    Card(
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainer),
        modifier = Modifier.fillMaxWidth()
    ) {
        Column(
            modifier = Modifier.padding(14.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            Text(title, style = MaterialTheme.typography.titleMedium)
            content()
        }
    }
}

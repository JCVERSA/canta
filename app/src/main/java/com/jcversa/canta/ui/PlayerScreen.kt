package com.jcversa.canta.ui

import android.view.ViewGroup
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.AssistChip
import androidx.compose.material3.AssistChipDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.media3.common.MediaItem
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory
import androidx.media3.ui.PlayerView
import com.jcversa.canta.AppViewModel
import com.jcversa.canta.manager.CantaDownloadManager
import com.jcversa.canta.model.Episode
import com.jcversa.canta.model.MirrorResult
import com.jcversa.canta.model.formatBytes
import com.jcversa.canta.ui.components.ErrorState
import com.jcversa.canta.ui.components.LanguageBadge
import com.jcversa.canta.ui.components.LoadingState
import com.jcversa.canta.ui.components.QualityBadge
import kotlinx.coroutines.delay

/**
 * Playback screen.
 *
 * ExoPlayer plays the resolved HLS stream directly — no segment downloading, no
 * remux, no ffmpeg. The player is built with a [CantaDownloadManager] cache data
 * source whose upstream carries the stream's own headers, so a downloaded
 * episode keeps playing with the network off and the mirrors that 403 without a
 * Referer do not 403 here either.
 *
 * The player instance is keyed on the resolved URL: switching mirrors or
 * qualities tears down the old ExoPlayer (and its data source) and builds a new
 * one with the new headers. Reusing a player across header changes is how a
 * playback bug becomes a "stream not found" report.
 *
 * Everything under the video is a measurement, not a claim: host, label, the
 * measured size (marked « échantillon » when only part of the playlist could be
 * probed), and any note the resolver produced — including the fast-lane
 * downgrade decision when the quality guard fired.
 */
@UnstableApi
@Composable
fun PlayerScreen(
    viewModel: AppViewModel,
    onBack: () -> Unit,
    modifier: Modifier = Modifier
) {
    val context = LocalContext.current
    val episode by viewModel.currentEpisode.collectAsStateWithLifecycle()
    val stream by viewModel.stream.collectAsStateWithLifecycle()
    val resolving by viewModel.resolving.collectAsStateWithLifecycle()
    val error by viewModel.playerError.collectAsStateWithLifecycle()
    val selectedQuality by viewModel.qualityRequest.collectAsStateWithLifecycle()
    var playbackFailure by remember { mutableStateOf<String?>(null) }

    val streamUrl = stream?.streamUrl
    val offline = stream?.offlinePlayback == true
    val exoPlayer = remember(streamUrl) {
        val current = stream ?: return@remember null
        ExoPlayer.Builder(context)
            .setMediaSourceFactory(
                DefaultMediaSourceFactory(
                    CantaDownloadManager.playbackDataSourceFactory(
                        context,
                        current.playbackHeaders(),
                        current.offlinePlayback
                    )
                )
            )
            .build()
            .also { player ->
                player.setMediaItem(MediaItem.fromUri(current.streamUrl))
                player.prepare()
                player.playWhenReady = true
            }
    }

    DisposableEffect(exoPlayer) {
        val current = exoPlayer ?: return@DisposableEffect onDispose { }
        val listener = object : Player.Listener {
            override fun onPlayerError(playerError: PlaybackException) {
                // The player's own message, verbatim: "source error" hides whether
                // the CDN refused the request or the manifest was empty.
                playbackFailure = playerError.errorCodeName + " — " + (playerError.cause?.message ?: playerError.message)
            }
        }
        current.addListener(listener)
        onDispose {
            current.removeListener(listener)
            current.release()
        }
    }

    // Persist "where the user stopped" once a second while playing.
    LaunchedEffect(exoPlayer) {
        val player = exoPlayer ?: return@LaunchedEffect
        val current = episode ?: return@LaunchedEffect
        while (true) {
            delay(1_000)
            val duration = player.duration
            if (duration > 0) viewModel.recordProgress(current, player.currentPosition, duration)
        }
    }

    Column(modifier = modifier.fillMaxSize()) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(8.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(4.dp)
        ) {
            TextButton(onClick = onBack) { Text("← Retour") }
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = episode?.seriesTitle.orEmpty(),
                    style = MaterialTheme.typography.titleMedium
                )
                Text(
                    text = episode?.label.orEmpty(),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
            TextButton(
                onClick = { episode?.let(viewModel::download) },
                enabled = stream != null && !offline
            ) { Text(if (offline) "Téléchargé" else "Télécharger") }
        }

        AndroidView(
            factory = { ctx ->
                PlayerView(ctx).apply {
                    player = exoPlayer
                    layoutParams = ViewGroup.LayoutParams(
                        ViewGroup.LayoutParams.MATCH_PARENT,
                        ViewGroup.LayoutParams.MATCH_PARENT
                    )
                    useController = true
                }
            },
            modifier = Modifier
                .fillMaxWidth()
                .aspectRatio(16f / 9f)
        )

        when {
            resolving -> LoadingState("Résolution du flux (mirroirs par priorité, échec automatique)…")
            stream == null && error != null -> ErrorState(error!!)
            stream == null -> LoadingState("Préparation…")
            else -> Column(
                modifier = Modifier.padding(16.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                val current = stream!!
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                    LanguageBadge(current.language)
                    QualityBadge(current.quality)
                    Text(
                        text = current.hostName,
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }

                if (current.offlinePlayback) {
                    Text(
                        text = "Lecture hors ligne — épisode téléchargé, aucune connexion nécessaire. " +
                            "Le fichier se lit depuis le stockage de l'app, sans repasser par un miroir.",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.primary
                    )
                }

                Text(
                    text = buildString {
                        append("Taille mesurée : ")
                        append(formatBytes(current.measuredSizeBytes))
                        append(
                            if (current.sizeIsExact) " (tous les segments mesurés)"
                            else " (extrapolée d'un échantillon de segments)"
                        )
                        append(" · ${current.quality.segmentCount} segments")
                    },
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )

                current.downgradeNote?.let { note ->
                    Surface(
                        color = MaterialTheme.colorScheme.errorContainer,
                        shape = RoundedCornerShape(10.dp),
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Text(
                            text = note,
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onErrorContainer,
                            modifier = Modifier.padding(10.dp)
                        )
                    }
                }

                playbackFailure?.let { failure ->
                    ErrorState("Lecture impossible : $failure")
                }

                Text("Qualité", style = MaterialTheme.typography.titleMedium)
                LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    items(current.availableTracks, key = { it.url }) { track ->
                        AssistChip(
                            onClick = { viewModel.selectQuality(track.label) },
                            label = {
                                Text(
                                    buildString {
                                        append(track.label)
                                        track.measuredBytes?.let {
                                            append(" · ")
                                            append(formatBytes(it))
                                            if (!track.sizeIsExact) append(" ≈")
                                        }
                                    }
                                )
                            },
                            colors = if (track.label == selectedQuality) {
                                AssistChipDefaults.assistChipColors(containerColor = MaterialTheme.colorScheme.primaryContainer)
                            } else {
                                AssistChipDefaults.assistChipColors()
                            }
                        )
                    }
                }

                if (selectedQuality == null && !current.offlinePlayback) {
                    Text(
                        text = "Sélection automatique : 480P puis 360P, jamais une étiquette inventée. " +
                            "Si un flux « 480P » dépasse 200 Mo mesurés, l'app bascule sur la variante plus légère et l'écrit ici.",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }

                if (error != null) ErrorState(error!!)
            }
        }
    }
}

/** Only used to keep the episode reference explicit in this file's imports. */
private typealias PlayerEpisode = Episode

/** Only used to keep the resolved-stream type explicit in this file's imports. */
private typealias PlayerStream = MirrorResult

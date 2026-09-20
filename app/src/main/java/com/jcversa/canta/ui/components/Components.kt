package com.jcversa.canta.ui.components

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.util.LruCache
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.jcversa.canta.model.Anime
import com.jcversa.canta.model.Language
import com.jcversa.canta.model.QualityTrack
import com.jcversa.canta.model.formatBytes
import com.jcversa.canta.scraper.Http
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.util.concurrent.ConcurrentHashMap
import androidx.compose.foundation.Image as ComposeImage

/**
 * Poster loading without an image library.
 *
 * Deliberate choice: poster URLs come from two sources whose hotlink rules
 * differ (voir-anime serves from `wp-content` and expects a referer, nakanime
 * serves from TMDB), and the same OkHttp client is already in the app for
 * scraping. A dependency-free loader keeps the referer decision here, in one
 * place, and keeps the APK free of a second networking stack.
 */
private object BitmapCache {
    private val cache = object : LruCache<String, Bitmap>(24 * 1024 * 1024) {
        override fun sizeOf(key: String, value: Bitmap): Int = value.byteCount
    }
    private val inFlight = ConcurrentHashMap<String, Boolean>()

    suspend fun load(url: String, referer: String?): Bitmap? = withContext(Dispatchers.IO) {
        cache.get(url)?.let { return@withContext it }
        if (inFlight.putIfAbsent(url, true) != null) return@withContext null
        try {
            val headers = Http.htmlHeaders(referer = referer)
            val response = Http.get(url, headers)
            if (!response.isOk) return@withContext null
            val bitmap = BitmapFactory.decodeByteArray(response.bytes, 0, response.bytes.size)
            bitmap?.also { cache.put(url, it) }
        } catch (_: Exception) {
            null
        } finally {
            inFlight.remove(url)
        }
    }
}

@Composable
fun RemoteImage(
    url: String?,
    contentDescription: String?,
    modifier: Modifier = Modifier,
    referer: String? = null
) {
    var bitmap by remember(url) { mutableStateOf<Bitmap?>(null) }
    var failed by remember(url) { mutableStateOf(false) }

    LaunchedEffect(url) {
        if (url.isNullOrBlank()) {
            failed = true
            return@LaunchedEffect
        }
        val loaded = BitmapCache.load(url, referer)
        if (loaded == null) failed = true else bitmap = loaded
    }

    Box(
        modifier = modifier.background(MaterialTheme.colorScheme.surfaceContainerHigh),
        contentAlignment = Alignment.Center
    ) {
        val current = bitmap
        when {
            current != null -> ComposeImage(
                bitmap = current.asImageBitmap(),
                contentDescription = contentDescription,
                contentScale = ContentScale.Crop,
                modifier = Modifier.fillMaxSize()
            )
            failed -> Text(
                text = "—",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            else -> CircularProgressIndicator(
                modifier = Modifier.size(20.dp),
                strokeWidth = 2.dp,
                color = MaterialTheme.colorScheme.primary
            )
        }
    }
}

/** Honest language badge: it shows what the source actually offers. */
@Composable
fun LanguageBadge(language: Language, modifier: Modifier = Modifier) {
    val container = when (language) {
        Language.VF -> MaterialTheme.colorScheme.primary
        Language.VOSTFR -> MaterialTheme.colorScheme.secondary
    }
    Surface(
        color = container,
        shape = RoundedCornerShape(6.dp),
        modifier = modifier
    ) {
        Text(
            text = language.label,
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onPrimary,
            modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp)
        )
    }
}

/** Quality + measured size. Never rounds a sample into a certainty. */
@Composable
fun QualityBadge(track: QualityTrack?, modifier: Modifier = Modifier) {
    if (track == null) return
    val size = track.measuredBytes
    val text = buildString {
        append(track.label)
        if (size != null) {
            append(" · ")
            append(formatBytes(size))
            if (!track.sizeIsExact) append(" (échantillon)")
        } else {
            append(" · taille non mesurée")
        }
    }
    Surface(
        color = MaterialTheme.colorScheme.surfaceContainerHigh,
        shape = RoundedCornerShape(6.dp),
        modifier = modifier
    ) {
        Text(
            text = text,
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp)
        )
    }
}

@Composable
fun AnimeCard(
    anime: Anime,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    trailing: @Composable (() -> Unit)? = null
) {
    Card(
        onClick = onClick,
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainer),
        modifier = modifier.fillMaxWidth()
    ) {
        Column {
            RemoteImage(
                url = anime.posterUrl,
                contentDescription = anime.title,
                referer = if (anime.source.id == "voir-anime") "https://voir-anime.to/" else null,
                modifier = Modifier
                    .fillMaxWidth()
                    .aspectRatio(2f / 3f)
                    .clip(RoundedCornerShape(topStart = 12.dp, topEnd = 12.dp))
            )
            Column(modifier = Modifier.padding(10.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                Text(
                    text = anime.title,
                    style = MaterialTheme.typography.bodyMedium,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                    color = MaterialTheme.colorScheme.onSurface
                )
                Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    anime.languages.forEach { LanguageBadge(it) }
                    anime.episodeCount?.let {
                        Text(
                            text = "$it ép.",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }
                trailing?.invoke()
            }
        }
    }
}

@Composable
fun LoadingState(message: String = "Chargement…", modifier: Modifier = Modifier) {
    Box(modifier = modifier.fillMaxWidth().padding(32.dp), contentAlignment = Alignment.Center) {
        Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(12.dp)) {
            CircularProgressIndicator(color = MaterialTheme.colorScheme.primary)
            Text(message, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

/** Errors are shown verbatim: a generic "une erreur est survenue" hides the fix. */
@Composable
fun ErrorState(message: String, modifier: Modifier = Modifier) {
    Box(modifier = modifier.fillMaxWidth().padding(24.dp), contentAlignment = Alignment.Center) {
        Surface(
            color = MaterialTheme.colorScheme.errorContainer,
            shape = RoundedCornerShape(12.dp),
            modifier = Modifier.fillMaxWidth()
        ) {
            Text(
                text = message,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onErrorContainer,
                modifier = Modifier.padding(16.dp)
            )
        }
    }
}

@Composable
fun EmptyState(message: String, modifier: Modifier = Modifier) {
    Box(modifier = modifier.fillMaxWidth().padding(32.dp), contentAlignment = Alignment.Center) {
        Text(
            text = message,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
    }
}

/** Two-state language selector. Default is VF, per the product spec. */
@Composable
fun LanguageToggle(
    selected: Language,
    available: Set<Language>,
    onSelect: (Language) -> Unit,
    modifier: Modifier = Modifier
) {
    Row(
        modifier = modifier
            .clip(RoundedCornerShape(10.dp))
            .background(MaterialTheme.colorScheme.surfaceContainerHigh)
            .padding(4.dp),
        horizontalArrangement = Arrangement.spacedBy(4.dp)
    ) {
        Language.entries.forEach { language ->
            val enabled = available.contains(language)
            val isSelected = selected == language
            Surface(
                color = when {
                    isSelected -> MaterialTheme.colorScheme.primary
                    else -> MaterialTheme.colorScheme.surfaceContainerHigh
                },
                shape = RoundedCornerShape(8.dp),
                modifier = Modifier.clickable(enabled = enabled) { onSelect(language) }
            ) {
                Text(
                    text = if (enabled) language.label else "${language.label} — indisponible",
                    style = MaterialTheme.typography.labelSmall,
                    color = when {
                        isSelected -> MaterialTheme.colorScheme.onPrimary
                        enabled -> MaterialTheme.colorScheme.onSurfaceVariant
                        else -> MaterialTheme.colorScheme.outline
                    },
                    modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp)
                )
            }
        }
    }
}

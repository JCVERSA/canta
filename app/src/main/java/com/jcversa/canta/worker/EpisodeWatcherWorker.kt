package com.jcversa.canta.worker

import android.Manifest
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import com.jcversa.canta.App
import com.jcversa.canta.MainActivity
import com.jcversa.canta.R
import com.jcversa.canta.model.Anime
import com.jcversa.canta.model.Language
import com.jcversa.canta.model.Source
import com.jcversa.canta.scraper.NakanimeScraper
import com.jcversa.canta.scraper.VoirAnimeScraper
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first

/**
 * Background watchlist checker.
 *
 * Rules that keep it honest and cheap:
 *  - one request per series (not per episode), batched and sequential;
 *  - only VF episodes are announced, because that is what the user asked to be
 *    told about — a new VOSTFR row is not "a new VF episode";
 *  - the first run after a series is added records the current maximum and
 *    announces nothing, so adding a 1100-episode show does not fire 1100
 *    notifications;
 *  - failures are silent per series: one broken scrape must not kill the batch.
 */
class EpisodeWatcherWorker(
    appContext: Context,
    params: WorkerParameters
) : CoroutineWorker(appContext, params) {

    override suspend fun doWork(): Result {
        val app = applicationContext as? App ?: return Result.success()
        val watchlist = app.container.watchlist
        val series: List<Anime> = watchlist.series.first().take(watchlist.batchLimit)
        if (series.isEmpty()) return Result.success()

        var announced = 0
        for (anime in series) {
            val newEpisodes = runCatching { checkSeries(anime, watchlist) }.getOrDefault(emptyList())
            val latest = newEpisodes.firstOrNull()
            if (latest != null) {
                notifyNewEpisode(anime, latest.number)
                announced++
            }
            // Politeness delay: the source is a third-party site, not a CDN to hammer.
            delay(1_200)
        }
        return Result.success()
    }

    private suspend fun checkSeries(
        anime: Anime,
        watchlist: com.jcversa.canta.manager.WatchlistManager
    ): List<com.jcversa.canta.model.Episode> {
        val episodes = when (anime.source) {
            Source.VOIRANIME -> VoirAnimeScraper.detail(anime).episodes
            Source.NAKANIME -> NakanimeScraper.detail(anime).episodes
        }.filter { it.language == Language.VF && it.number > 0 }

        if (episodes.isEmpty()) return emptyList()
        val highest = episodes.maxOf { it.number }
        val lastSeen = watchlist.lastSeenEpisode(anime.id)
        if (lastSeen == null) {
            // First observation: remember where the series stands, announce nothing.
            watchlist.setLastSeenEpisode(anime.id, highest)
            return emptyList()
        }
        if (highest <= lastSeen) return emptyList()
        watchlist.setLastSeenEpisode(anime.id, highest)
        return episodes.filter { it.number > lastSeen }.sortedByDescending { it.number }
    }

    private fun notifyNewEpisode(anime: Anime, episodeNumber: Int) {
        val context = applicationContext
        if (ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS)
            != PackageManager.PERMISSION_GRANTED && android.os.Build.VERSION.SDK_INT >= 33
        ) {
            return
        }
        val intent = Intent(context, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
            putExtra(MainActivity.EXTRA_OPEN_SERIES_ID, anime.id)
            // The title travels too: if the id is no longer resolvable locally the
            // app searches for the series instead of dropping the tap.
            putExtra(MainActivity.EXTRA_OPEN_SERIES_TITLE, anime.title)
        }
        val pending = PendingIntent.getActivity(
            context,
            anime.id.hashCode(),
            intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        val notification = NotificationCompat.Builder(context, App.NewEpisodesChannelId)
            .setSmallIcon(android.R.drawable.stat_notify_sync)
            .setContentTitle("Nouvel épisode VF — ${anime.title}")
            .setContentText("Épisode $episodeNumber disponible (${anime.source.host})")
            .setAutoCancel(true)
            .setContentIntent(pending)
            .build()
        runCatching {
            NotificationManagerCompat.from(context).notify(anime.id.hashCode(), notification)
        }
    }

    companion object {
        const val UNIQUE_NAME = "canta_episode_watcher"
    }
}

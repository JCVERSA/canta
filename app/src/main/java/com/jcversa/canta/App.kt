package com.jcversa.canta

import android.app.Application
import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Context
import android.os.Build
import androidx.work.Constraints
import androidx.work.Configuration
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.NetworkType
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import com.jcversa.canta.manager.CantaDownloadManager
import com.jcversa.canta.manager.DOWNLOAD_CHANNEL_ID
import com.jcversa.canta.manager.FavoritesManager
import com.jcversa.canta.manager.HistoryManager
import com.jcversa.canta.manager.WatchlistManager
import com.jcversa.canta.worker.EpisodeWatcherWorker
import java.util.concurrent.TimeUnit

/** Everything the UI needs, created once. Mirrors SwiftSlate's app container. */
class AppContainer(context: Context) {
    val favorites = FavoritesManager(context)
    val history = HistoryManager(context)
    val watchlist = WatchlistManager(context)
    val downloads = CantaDownloadManager
}

class App : Application(), Configuration.Provider {

    lateinit var container: AppContainer
        private set

    override fun onCreate() {
        super.onCreate()
        container = AppContainer(applicationContext)
        createNotificationChannels()
        scheduleEpisodeWatcher()
        // Media3's caches and download index are opened here, on an IO thread,
        // rather than on the UI thread the first time the player or the download
        // button touches them.
        CantaDownloadManager.warmUp(this)
    }

    override val workManagerConfiguration: Configuration
        get() = Configuration.Builder().setMinimumLoggingLevel(android.util.Log.INFO).build()

    private fun createNotificationChannels() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
        val manager = getSystemService(NotificationManager::class.java) ?: return
        manager.createNotificationChannel(
            NotificationChannel(
                CantaDownloadServiceChannelId,
                getString(R.string.notif_channel_downloads),
                NotificationManager.IMPORTANCE_LOW
            ).apply { description = getString(R.string.notif_channel_downloads_desc) }
        )
        manager.createNotificationChannel(
            NotificationChannel(
                NewEpisodesChannelId,
                getString(R.string.notif_channel_new_episodes),
                NotificationManager.IMPORTANCE_DEFAULT
            ).apply { description = getString(R.string.notif_channel_new_episodes_desc) }
        )
    }

    /**
     * Watches the watchlist for new VF episodes. Deliberately infrequent: the
     * worker checks at most 20 series per run and only announces episodes whose
     * number is higher than the last one already seen, so the app never turns
     * into a polling client hammering the source.
     */
    private fun scheduleEpisodeWatcher() {
        val request = PeriodicWorkRequestBuilder<EpisodeWatcherWorker>(12, TimeUnit.HOURS)
            .setConstraints(
                Constraints.Builder()
                    .setRequiredNetworkType(NetworkType.CONNECTED)
                    .setRequiresBatteryNotLow(true)
                    .build()
            )
            .build()
        runCatching {
            WorkManager.getInstance(applicationContext).enqueueUniquePeriodicWork(
                EpisodeWatcherWorker.UNIQUE_NAME,
                ExistingPeriodicWorkPolicy.KEEP,
                request
            )
        }
    }

    companion object {
        /** Same id as the download service's own channel, declared in the manager. */
        const val CantaDownloadServiceChannelId = DOWNLOAD_CHANNEL_ID
        const val NewEpisodesChannelId = "canta_new_episodes"
    }
}

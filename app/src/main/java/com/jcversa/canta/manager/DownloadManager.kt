package com.jcversa.canta.manager

import android.app.Notification
import android.content.Context
import android.net.Uri
import androidx.core.app.NotificationCompat
import androidx.media3.common.MimeTypes
import androidx.media3.database.StandaloneDatabaseProvider
import androidx.media3.datasource.DefaultDataSource
import androidx.media3.datasource.DefaultHttpDataSource
import androidx.media3.datasource.cache.CacheDataSource
import androidx.media3.datasource.cache.LeastRecentlyUsedCacheEvictor
import androidx.media3.datasource.cache.SimpleCache
import androidx.media3.exoplayer.offline.Download
import androidx.media3.exoplayer.offline.DownloadManager
import androidx.media3.exoplayer.offline.DownloadRequest
import androidx.media3.exoplayer.offline.DownloadService
import com.jcversa.canta.R
import com.jcversa.canta.model.Episode
import com.jcversa.canta.model.MirrorResult
import com.jcversa.canta.scraper.Http
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import org.json.JSONObject
import java.io.File

/**
 * Offline downloads, Media3-native.
 *
 * The stream that ExoPlayer plays and the stream Media3 downloads are the same
 * URL served by the same [SimpleCache]: a downloaded episode therefore plays
 * back with the network off, with no conversion step and no ffmpeg — the
 * constraint that does not exist here (nebula-p needs ffmpeg only because
 * WhatsApp cannot read HLS; Media3 reads it natively).
 */
object CantaDownloadManager {

    /** Cache ceiling. Episodes are large; 4 GiB holds a comfortable season. */
    private const val CACHE_BYTES = 4L * 1024L * 1024L
    private val LOCK = Any()

    @Volatile private var downloadManager: DownloadManager? = null
    @Volatile private var simpleCache: SimpleCache? = null
    @Volatile private var databaseProvider: StandaloneDatabaseProvider? = null

    private val _downloads = MutableStateFlow<List<DownloadUi>>(emptyList())
    val downloads: StateFlow<List<DownloadUi>> = _downloads.asStateFlow()

    fun get(context: Context): DownloadManager = synchronized(LOCK) {
        downloadManager ?: DownloadManager.Builder(context.applicationContext)
            .setMaxParallelDownloads(2)
            .setMinRetryCount(3)
            .setExecutor(java.util.concurrent.Executors.newFixedThreadPool(2))
            .build()
            .also { manager ->
                downloadManager = manager
                manager.addListener(object : DownloadManager.Listener {
                    override fun onDownloadChanged(
                        downloadManager: DownloadManager,
                        download: Download,
                        finalException: Exception?
                    ) = refresh(context)

                    override fun onDownloadRemoved(downloadManager: DownloadManager, download: Download) = refresh(context)
                })
                refresh(context)
            }
    }

    fun cache(context: Context): SimpleCache = synchronized(LOCK) {
        simpleCache ?: SimpleCache(
            File(context.cacheDir, "canta_downloads"),
            LeastRecentlyUsedCacheEvictor(CACHE_BYTES),
            database(context)
        ).also { simpleCache = it }
    }

    private fun database(context: Context): StandaloneDatabaseProvider = synchronized(LOCK) {
        databaseProvider ?: StandaloneDatabaseProvider(context.applicationContext).also { databaseProvider = it }
    }

    /**
     * A data source that reads from the download cache first and only then goes
     * to the network, with the stream's own Referer/Origin — several mirrors
     * 403 without them, and a 403 mid-playback looks like a broken episode.
     */
    fun playbackDataSourceFactory(context: Context, headers: Map<String, String>): CacheDataSource.Factory {
        val upstream = DefaultHttpDataSource.Factory()
            .setUserAgent(Http.USER_AGENT)
            .setDefaultRequestProperties(headers)
        return CacheDataSource.Factory()
            .setCache(cache(context))
            .setUpstreamDataSourceFactory(DefaultDataSource.Factory(context, upstream))
            .setFlags(CacheDataSource.FLAG_IGNORE_CACHE_ON_ERROR)
    }

    /** Media item headers for a resolved stream, so playback matches extraction. */
    fun downloadDataSourceFactory(context: Context, headers: Map<String, String>): DefaultDataSource.Factory {
        val upstream = DefaultHttpDataSource.Factory()
            .setUserAgent(Http.USER_AGENT)
            .setDefaultRequestProperties(headers)
        return DefaultDataSource.Factory(context, upstream)
    }

    /**
     * Queues one episode. The request carries its own metadata so the Downloads
     * screen can render a title and an episode number after a process restart,
     * without a second database.
     */
    fun enqueue(context: Context, episode: Episode, result: MirrorResult): String {
        val id = downloadIdFor(episode)
        val request = DownloadRequest.Builder(id, Uri.parse(result.streamUrl))
            .setMimeType(if (result.isHls) MimeTypes.APPLICATION_M3U8 else MimeTypes.VIDEO_MP4)
            .setData(JSONObject().apply {
                put("episode", episode.toJson())
                put("language", result.language.tag)
                put("quality", result.quality.label)
                put("measuredSize", result.measuredSizeBytes ?: JSONObject.NULL)
                put("exact", result.sizeIsExact)
                put("host", result.hostName)
            }.toString().toByteArray())
            .build()
        get(context).addDownloadRequest(request)
        startService(context)
        refresh(context)
        return id
    }

    fun remove(context: Context, id: String) {
        get(context).removeDownload(id)
        refresh(context)
    }

    fun pauseAll(context: Context) {
        get(context).pauseDownloads()
        refresh(context)
    }

    fun resumeAll(context: Context) {
        get(context).resumeDownloads()
        startService(context)
        refresh(context)
    }

    /** Resumes whatever was queued, e.g. after the app was killed mid-download. */
    fun startService(context: Context) {
        runCatching {
            DownloadService.sendResumeDownloadsIntent(context, CantaDownloadService::class.java, true)
        }
    }

    fun downloadIdFor(episode: Episode): String = "canta-${episode.key.hashCode()}"

    fun refresh(context: Context) {
        val output = mutableListOf<DownloadUi>()
        val index = get(context).downloadIndex
        index.getDownloads().use { cursor ->
            while (cursor.moveToNext()) {
                val download = cursor.download
                output += DownloadUi(
                    id = download.request.id,
                    episode = parseEpisode(download.request.data),
                    quality = parseField(download.request.data, "quality"),
                    host = parseField(download.request.data, "host"),
                    percent = download.percentDownloaded.takeIf { it > 0f } ?: 0f,
                    state = download.state,
                    bytesDownloaded = download.bytesDownloaded
                )
            }
        }
        _downloads.value = output.sortedByDescending { it.id }
    }

    private fun parseEpisode(data: ByteArray?): Episode? = try {
        val json = String(data ?: ByteArray(0), Charsets.UTF_8)
        if (json.isBlank()) null else Episode.fromJson(JSONObject(json).optJSONObject("episode") ?: JSONObject())
    } catch (_: Exception) {
        null
    }

    private fun parseField(data: ByteArray?, field: String): String? = try {
        val json = String(data ?: ByteArray(0), Charsets.UTF_8)
        if (json.isBlank()) null else JSONObject(json).optString(field).takeIf { it.isNotEmpty() }
    } catch (_: Exception) {
        null
    }

}

/** One row of the Downloads screen. */
data class DownloadUi(
    val id: String,
    val episode: Episode?,
    val quality: String?,
    val host: String?,
    val percent: Float,
    val state: Int,
    val bytesDownloaded: Long
) {
    val isComplete: Boolean get() = state == Download.STATE_COMPLETED
    val isFailed: Boolean get() = state == Download.STATE_FAILED
    val isDownloading: Boolean get() = state == Download.STATE_DOWNLOADING
}

/**
 * Foreground service that keeps downloads alive when the app leaves the screen.
 * `dataSync` is the declared foreground-service type (see AndroidManifest).
 */
class CantaDownloadService : DownloadService(
    1001,
    DownloadService.DEFAULT_FOREGROUND_NOTIFICATION_UPDATE_INTERVAL,
    CHANNEL_ID,
    R.string.notif_channel_downloads,
    R.string.notif_channel_downloads_desc
) {

    override fun getDownloadManager(): DownloadManager = CantaDownloadManager.get(this)

    override fun getScheduler(): DownloadService.Scheduler? = null

    override fun getForegroundNotification(
        downloads: MutableList<Download>,
        notMetRequirements: Int
    ): Notification {
        val active = downloads.firstOrNull()
        val progress = active?.percentDownloaded?.takeIf { it > 0f } ?: 0f
        val text = when {
            active == null -> getString(R.string.notif_channel_downloads_desc)
            progress > 0f -> "Téléchargement hors ligne — ${progress.toInt()}%"
            else -> "Téléchargement hors ligne en préparation…"
        }
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(android.R.drawable.stat_sys_download)
            .setContentTitle(getString(R.string.app_name))
            .setContentText(text)
            .setProgress(100, progress.toInt(), progress <= 0f)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .build()
    }

    companion object {
        const val CHANNEL_ID = "canta_downloads"
    }
}

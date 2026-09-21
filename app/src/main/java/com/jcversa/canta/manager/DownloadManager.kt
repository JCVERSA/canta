@file:OptIn(androidx.media3.common.util.UnstableApi::class)

package com.jcversa.canta.manager

import android.app.Notification
import android.content.Context
import android.net.Uri
import androidx.core.app.NotificationCompat
import androidx.media3.common.MediaItem
import androidx.media3.common.MimeTypes
import androidx.media3.common.util.UnstableApi
import androidx.media3.database.StandaloneDatabaseProvider
import androidx.media3.datasource.DefaultDataSource
import androidx.media3.datasource.DefaultHttpDataSource
import androidx.media3.datasource.cache.CacheDataSource
import androidx.media3.datasource.cache.LeastRecentlyUsedCacheEvictor
import androidx.media3.datasource.cache.NoOpCacheEvictor
import androidx.media3.datasource.cache.SimpleCache
import androidx.media3.exoplayer.hls.offline.HlsDownloader
import androidx.media3.exoplayer.offline.DefaultDownloadIndex
import androidx.media3.exoplayer.offline.Download
import androidx.media3.exoplayer.offline.DownloadManager
import androidx.media3.exoplayer.offline.DownloadRequest
import androidx.media3.exoplayer.offline.DownloadService
import androidx.media3.exoplayer.offline.DownloaderFactory
import androidx.media3.exoplayer.offline.ProgressiveDownloader
import com.jcversa.canta.R
import com.jcversa.canta.model.Episode
import com.jcversa.canta.model.Language
import com.jcversa.canta.model.MirrorResult
import com.jcversa.canta.model.QualityTrack
import com.jcversa.canta.model.formatBytes
import com.jcversa.canta.scraper.Http
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import org.json.JSONObject
import java.io.File
import java.util.concurrent.Executor
import java.util.concurrent.Executors

/**
 * Offline downloads, Media3-native.
 *
 * Two caches, deliberately:
 *
 *  * [offlineCache] holds downloaded episodes. It uses a [NoOpCacheEvictor],
 *    which Media3 requires ("the cache should be configured with a
 *    CacheEvictor that will not evict downloaded content") — an episode the UI
 *    calls « téléchargé » must still be there tomorrow, so nothing removes it
 *    behind the user's back. The ceiling is enforced *before* a download is
 *    queued, by refusing it, rather than by silently deleting an old episode.
 *  * [streamCache] is the ordinary streaming buffer and may be evicted at will.
 *
 * A downloaded episode plays from [offlineCache] with the network off, with no
 * conversion step and no ffmpeg. That constraint does not exist here: nebula-p
 * needs ffmpeg only because WhatsApp cannot read HLS, and Media3 reads HLS
 * natively.
 */
object CantaDownloadManager {

    /**
     * Ceiling for downloaded episodes: 4 GiB, i.e. a real season of 480P
     * episodes (measured: ~92 MiB for 24 minutes) rather than a number that
     * would refuse every download. The cache never evicts; when it is full the
     * app refuses the next download and says how much is used.
     */
    private const val OFFLINE_CACHE_BYTES = 4L * 1024L * 1024L * 1024L

    /** Streaming buffer: only ever holds what is being watched right now. */
    private const val STREAM_CACHE_BYTES = 512L * 1024L * 1024L

    private val LOCK = Any()
    private val DOWNLOAD_EXECUTOR: Executor = Executors.newFixedThreadPool(2)

    /**
     * App-lifetime scope for work that must not run on the caller's thread.
     * A singleton object's lifetime is the process's, so nothing to cancel.
     */
    private val SCOPE = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    @Volatile private var downloadManager: DownloadManager? = null
    @Volatile private var offlineCacheRef: SimpleCache? = null
    @Volatile private var streamCacheRef: SimpleCache? = null
    @Volatile private var databaseProvider: StandaloneDatabaseProvider? = null

    private val _downloads = MutableStateFlow<List<DownloadUi>>(emptyList())
    val downloads: StateFlow<List<DownloadUi>> = _downloads.asStateFlow()

    fun get(context: Context): DownloadManager = synchronized(LOCK) {
        downloadManager ?: run {
            val appContext = context.applicationContext
            val cache = offlineCache(appContext)
            val manager = DownloadManager(
                appContext,
                DefaultDownloadIndex(database(appContext)),
                // One downloader per request, because the Referer/Origin are
                // per-stream and VidMoly's CDN answers 403 without them (audit
                // §8.43): a single shared upstream factory cannot carry them.
                DownloaderFactory { request ->
                    val upstream = DefaultHttpDataSource.Factory()
                        .setUserAgent(Http.USER_AGENT)
                        .setDefaultRequestProperties(headersOf(request))
                    val cacheDataSource = CacheDataSource.Factory()
                        .setCache(cache)
                        .setUpstreamDataSourceFactory(DefaultDataSource.Factory(appContext, upstream))
                    val mediaItem = MediaItem.Builder()
                        .setUri(request.uri)
                        .setMimeType(request.mimeType)
                        .build()
                    // The request URI is already the chosen variant's playlist
                    // (never the master), so an HLS download fetches exactly the
                    // quality the user picked and nothing else.
                    if (request.mimeType == MimeTypes.APPLICATION_M3U8) {
                        // Media3 deprecates this constructor in favour of
                        // HlsDownloader.Factory because it can be built around a
                        // StreamKey; this app never selects a variant inside the
                        // downloader (the request URI *is* the resolved variant's
                        // playlist), so the behaviour is identical and the hint
                        // stays a warning rather than turning into an error.
                        HlsDownloader(mediaItem, cacheDataSource, DOWNLOAD_EXECUTOR)
                    } else {
                        ProgressiveDownloader(mediaItem, cacheDataSource, DOWNLOAD_EXECUTOR)
                    }
                }
            )
            manager.setMaxParallelDownloads(2)
            manager.setMinRetryCount(3)
            manager.addListener(object : DownloadManager.Listener {
                override fun onDownloadChanged(
                    downloadManager: DownloadManager,
                    download: Download,
                    finalException: Exception?
                ) = refresh(appContext)

                override fun onDownloadRemoved(downloadManager: DownloadManager, download: Download) = refresh(appContext)
            })
            downloadManager = manager
            refresh(appContext)
            manager
        }
    }

    /**
     * Creates the manager, the index and both caches away from the caller's
     * thread.
     *
     * Media3 opens SQLite and the cache directories lazily on first use, and the
     * first use is otherwise the player screen building its data source during
     * composition, or the play button reading the index: disk I/O and a database
     * open on the UI thread. Warming them from [App]'s IO scope means those call
     * sites only ever read what already exists.
     */
    fun warmUp(context: Context) {
        SCOPE.launch {
            runCatching {
                get(context)
                offlineCache(context)
                streamCache(context)
            }
        }
    }

    /**
     * Resumes downloads left queued by an earlier process, and *only then* starts
     * the service.
     *
     * A foreground service started with nothing to do costs the user battery and
     * notification space for no benefit — and on Android 12+ a foreground service
     * that never becomes foreground is killed, so starting one unconditionally at
     * every app launch is a risk with no upside. The index is consulted first.
     */
    fun resumePendingIfAny(context: Context) {
        SCOPE.launch {
            val pending = runCatching {
                get(context).downloadIndex
                    .getDownloads(Download.STATE_QUEUED, Download.STATE_DOWNLOADING)
                    .use { cursor -> cursor.moveToNext() }
            }.getOrDefault(false)
            if (pending) {
                runCatching {
                    DownloadService.sendResumeDownloads(context, CantaDownloadService::class.java, true)
                }
            }
        }
    }

    /** Downloaded episodes. Never evicted automatically (Media3 requirement). */
    fun offlineCache(context: Context): SimpleCache = synchronized(LOCK) {
        offlineCacheRef ?: SimpleCache(
            File(context.filesDir, "canta_offline"),
            NoOpCacheEvictor(),
            database(context)
        ).also { offlineCacheRef = it }
    }

    /** Streaming buffer, safe to evict: it only ever duplicates the network. */
    fun streamCache(context: Context): SimpleCache = synchronized(LOCK) {
        streamCacheRef ?: SimpleCache(
            File(context.cacheDir, "canta_stream"),
            LeastRecentlyUsedCacheEvictor(STREAM_CACHE_BYTES),
            database(context)
        ).also { streamCacheRef = it }
    }

    private fun database(context: Context): StandaloneDatabaseProvider = synchronized(LOCK) {
        databaseProvider ?: StandaloneDatabaseProvider(context.applicationContext).also { databaseProvider = it }
    }

    /**
     * A data source that reads from the cache first and only then goes to the
     * network, with the stream's own Referer/Origin — several mirrors 403
     * without them, and a 403 mid-playback looks like a broken episode.
     *
     * [offline] selects the downloaded-episodes cache. Its entries are addressed
     * by their stream URL, and that URL is the one stored in the download
     * request, so a downloaded episode is found again with no mirror
     * re-resolution and no network at all.
     */
    fun playbackDataSourceFactory(
        context: Context,
        headers: Map<String, String>,
        offline: Boolean = false
    ): CacheDataSource.Factory {
        val upstream = DefaultHttpDataSource.Factory()
            .setUserAgent(Http.USER_AGENT)
            .setDefaultRequestProperties(headers)
        return CacheDataSource.Factory()
            .setCache(if (offline) offlineCache(context) else streamCache(context))
            .setUpstreamDataSourceFactory(DefaultDataSource.Factory(context, upstream))
            .setFlags(CacheDataSource.FLAG_IGNORE_CACHE_ON_ERROR)
    }

    /**
     * Queues one episode. The request carries its own metadata so the Downloads
     * screen can render a title and an episode number after a process restart,
     * without a second database.
     */
    fun enqueue(context: Context, episode: Episode, result: MirrorResult): String {
        // The guard only fires on a *measured* size: refusing on an estimate
        // would be exactly the invented number this app refuses to print.
        val measured = result.measuredSizeBytes
        if (measured != null && measured > 0) {
            val used = offlineCache(context).cacheSpace
            if (used + measured > OFFLINE_CACHE_BYTES) {
                throw IllegalStateException(
                    "espace hors ligne plein — ${formatBytes(used)} déjà téléchargés sur " +
                        "${formatBytes(OFFLINE_CACHE_BYTES)} : supprimez un épisode avant d'en ajouter un autre"
                )
            }
        }
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
                // Stored with the request: the downloader replays these headers
                // on every manifest and segment request, and they are still here
                // after a process restart.
                put("headers", JSONObject(result.playbackHeaders()))
            }.toString().toByteArray())
            .build()
        // Sent to the service rather than queued in-process: the service owns the
        // DownloadManager's lifetime, so a download queued while the UI is alive
        // survives the UI being killed.
        DownloadService.sendAddDownload(context, CantaDownloadService::class.java, request, false)
        refresh(context)
        return id
    }

    /**
     * The stream a *completed* download holds, played later from [offlineCache].
     *
     * Rebuilt from the request's own stored metadata, not re-resolved: that is
     * the whole point — with the network off there is no mirror to ask, and the
     * episode must still play. Every value shown is what was measured when the
     * download was queued (size, exactness, host), never a fresh guess.
     */
    fun offlineStream(context: Context, episode: Episode): MirrorResult? {
        val download = runCatching { get(context).downloadIndex.getDownload(downloadIdFor(episode)) }.getOrNull()
        if (download == null || download.state != Download.STATE_COMPLETED) return null
        val request = download.request
        val json = request.data?.let { runCatching { JSONObject(String(it, Charsets.UTF_8)) }.getOrNull() } ?: return null
        val url = request.uri.toString()
        val measured = if (json.isNull("measuredSize")) null else json.optLong("measuredSize")
        val exact = json.optBoolean("exact", false)
        return MirrorResult(
            streamUrl = url,
            language = Language.fromTag(json.optString("language")) ?: episode.language,
            quality = QualityTrack(
                label = json.optString("quality").takeIf { it.isNotBlank() } ?: "qualité d'origine",
                url = url,
                measuredBytes = measured,
                sizeIsExact = exact
            ),
            measuredSizeBytes = measured,
            sizeIsExact = exact,
            hostName = json.optString("host").takeIf { it.isNotBlank() } ?: "téléchargé",
            source = episode.source,
            headers = headersOf(request),
            isHls = request.mimeType == MimeTypes.APPLICATION_M3U8,
            offlinePlayback = true
        )
    }

    /** The per-stream headers captured at extraction time, kept inside the request. */
    private fun headersOf(request: DownloadRequest): Map<String, String> = try {
        val json = request.data?.let { JSONObject(String(it, Charsets.UTF_8)) } ?: return emptyMap()
        val headers = json.optJSONObject("headers") ?: return emptyMap()
        headers.keys().asSequence()
            .mapNotNull { key -> headers.optString(key).takeIf { it.isNotEmpty() }?.let { key to it } }
            .toMap()
    } catch (_: Exception) {
        emptyMap()
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
            DownloadService.sendResumeDownloads(context, CantaDownloadService::class.java, true)
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
 * Id of the download notification channel. Declared once, here, because the
 * service constructor needs it before the class body exists and [App] creates
 * the channel itself.
 */
const val DOWNLOAD_CHANNEL_ID = "canta_downloads"

/**
 * Foreground service that keeps downloads alive when the app leaves the screen.
 * `dataSync` is the declared foreground-service type (see AndroidManifest).
 */
class CantaDownloadService : DownloadService(
    1001,
    DownloadService.DEFAULT_FOREGROUND_NOTIFICATION_UPDATE_INTERVAL,
    DOWNLOAD_CHANNEL_ID,
    R.string.notif_channel_downloads,
    R.string.notif_channel_downloads_desc
) {

    override fun getDownloadManager(): DownloadManager = CantaDownloadManager.get(this)

    /**
     * No requirements-based scheduler: a queued episode should keep going while
     * the user is on Wi-Fi rather than wait for an "unmetered + charging" state
     * the app never states. Media3 tolerates null here.
     *
     * The return type is inferred on purpose: `DownloadService` changed the
     * type of this method between Media3 releases, and inferring it keeps the
     * override valid against either the nested or the standalone `Scheduler`.
     */
    override fun getScheduler() = null

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
        return NotificationCompat.Builder(this, DOWNLOAD_CHANNEL_ID)
            .setSmallIcon(android.R.drawable.stat_sys_download)
            .setContentTitle(getString(R.string.app_name))
            .setContentText(text)
            .setProgress(100, progress.toInt(), progress <= 0f)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .build()
    }
}

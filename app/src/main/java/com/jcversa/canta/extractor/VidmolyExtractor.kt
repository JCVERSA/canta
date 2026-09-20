package com.jcversa.canta.extractor

import com.jcversa.canta.model.MirrorRef
import com.jcversa.canta.scraper.Http
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * VidMoly (priority 1) — the quality reference of the whole mirror ecosystem.
 *
 * Two production lessons from nebula-p are encoded here:
 *
 *  - **The player host is not the file host.** `voembed.net/embed-<code>.html`
 *    (what voir-anime.to embeds, RECONNAISSANCE.md §2.4) serves its manifest
 *    from `prx-<n>-ant.vmget.online/hls2/…` — the VidMoly CDN family. So the
 *    family is matched by URL signature too, and the manifest is read from the
 *    player's own `sources:` array.
 *  - **`.urlset` masters 403 on some nodes while their variants answer fine**
 *    (audit §8.43: `box-1659-u.vmbox.space`). [resolveUrlset] walks the proven
 *    variant shapes × the referer matrix, bounded, and returns the playlist
 *    together with the header set that answered, so Media3 replays the winning
 *    pair on every segment request.
 */
object VidmolyExtractor : VideoExtractor {

    override val name = "VidMoly"

    private val HOST_MARKERS = listOf(
        "vidmoly", "vmpx", "voembed", "ansembed", "topembed", "staticmoly"
    ) + VIDMOLY_CDN_HOSTS

    /** Bound on the variant × referer matrix, as in production (26 attempts). */
    private const val ATTEMPT_BUDGET = 26
    private const val PER_ATTEMPT_TIMEOUT_MS = 6_000L

    override fun supports(mirror: MirrorRef): Boolean {
        val url = mirror.url.lowercase()
        return HOST_MARKERS.any { url.contains(it) } || url.contains("/hls2/") || url.contains(".urlset/")
    }

    override suspend fun extract(mirror: MirrorRef): ExtractedStream? = withContext(Dispatchers.IO) {
        val page = runCatching { Http.getAsync(mirror.url, Http.htmlHeaders(referer = SITE_REFERER)) }.getOrNull()
            ?: return@withContext null
        val origin = originOf(mirror.url)
        val referer = mirror.url
        var headers = browserHeaders(referer, origin)

        // 1. The player's own declaration, unpacked and scanned.
        val scanned = runCatching { StreamScanner.scan(page.body, origin) }.getOrNull()
            ?: return@withContext null

        var streamUrl = scanned.url

        // 2. `.urlset` masters: resolve to a playlist that actually answers.
        if (streamUrl.contains(".urlset/") || streamUrl.contains("master.txt")) {
            val resolved = resolveUrlset(streamUrl, headers)
            if (resolved != null) {
                streamUrl = resolved.mediaPlaylistUrl
                headers = resolved.headers
            }
        }

        val kind = if (streamUrl.contains(".mp4")) StreamKind.MP4 else StreamKind.HLS
        // A playlist we cannot even read is not a stream: fail over instead of
        // handing Media3 a URL that will 403 in the player.
        if (kind == StreamKind.HLS && fetchPlaylist(streamUrl, headers) == null) {
            val retried = resolveUrlset(streamUrl, headers)
            val retryUrl = retried?.mediaPlaylistUrl
            val retryHeaders = retried?.headers ?: headers
            if (retryUrl == null || fetchPlaylist(retryUrl, retryHeaders) == null) return@withContext null
            streamUrl = retryUrl
            headers = retryHeaders
        }

        ExtractedStream(
            hostName = "VidMoly",
            url = streamUrl,
            kind = kind,
            referer = headers["Referer"] ?: referer,
            origin = headers["Origin"] ?: origin,
            headers = headers
        )
    }

    data class ResolvedUrlset(val mediaPlaylistUrl: String, val headers: Map<String, String>)

    /**
     * Bounded brute force: the master with every referer first, then the derived
     * variant shapes (last urlset letter first) × the same referers. Returns the
     * first answer containing `#EXT`.
     */
    suspend fun resolveUrlset(
        masterUrl: String,
        baseHeaders: Map<String, String>
    ): ResolvedUrlset? = withContext(Dispatchers.IO) {
        var attempts = 0
        val candidates = refererCandidates(masterUrl, baseHeaders)

        for (headers in candidates) {
            if (attempts >= ATTEMPT_BUDGET) break
            attempts++
            if (fetchPlaylist(masterUrl, headers, PER_ATTEMPT_TIMEOUT_MS) != null) {
                return@withContext ResolvedUrlset(masterUrl, headers)
            }
        }

        for (variant in deriveSubVariantUrls(masterUrl).take(6)) {
            for (headers in candidates) {
                if (attempts >= ATTEMPT_BUDGET) break
                attempts++
                if (fetchPlaylist(variant, headers, PER_ATTEMPT_TIMEOUT_MS) != null) {
                    return@withContext ResolvedUrlset(variant, headers)
                }
            }
        }
        null
    }
}

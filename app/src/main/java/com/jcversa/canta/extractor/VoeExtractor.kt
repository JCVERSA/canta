package com.jcversa.canta.extractor

import com.jcversa.canta.model.MirrorRef
import com.jcversa.canta.scraper.Http
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject

/**
 * Voe / voembed-style players (priority 2).
 *
 * Voe rotates its domains (voe.sx today, `mfw09.org`, `voe.to`, …), so the
 * extractor keys on the payload shape rather than on the hostname: the page
 * carries one long base64 blob that decodes through
 *
 *     rot13 → strip noise pairs → base64 → (charCode − 3) → reverse → base64 → JSON
 *
 * (nebula-p's decodeVoePayload, audit §8.11). The decoded JSON's
 * `source`/`hls`/`file`/`direct_access_url` field is the *master* playlist —
 * which is the whole point of §8.11: returning a bare master and letting the
 * downloader guess produced a "720P" label on a 480p file, so the master is
 * always parsed for its real `RESOLUTION=` before anything is shown to a user.
 */
object VoeExtractor : VideoExtractor {

    override val name = "Voe"

    private const val MAX_REDIRECT_HOPS = 3
    private val PAYLOAD = Regex("""\["([A-Za-z0-9+/=_\-]{200,})"]""")
    private val REDIRECT = Regex("""window\.location\.href\s*=\s*['"]([^'"]+)['"]""")

    override fun supports(mirror: MirrorRef): Boolean {
        val url = mirror.url.lowercase()
        if (!url.startsWith("http")) return false
        if (isKnownOtherHost(url)) return false
        if (url.contains("voe") || url.contains("mfw")) return true
        // Rotating domains: a bare `/e/<code>` on an unknown host is voe-shaped.
        return Regex("""^https?://[^/]+/e/[a-z0-9]+""").containsMatchIn(url)
    }

    private fun isKnownOtherHost(url: String): Boolean = listOf(
        "vidmoly", "vmpx", "vmget", "vmeas", "vmnow", "vmbox", "vmcld", "voembed",
        "ansembed", "topembed", "sibnet", "sendvid", "streamtape", "uqload", "vidzy",
        "luluvdo", "lulustream", "oneupload", "filemoon", "dood", "vidoza"
    ).any { url.contains(it) }

    override suspend fun extract(mirror: MirrorRef): ExtractedStream? = withContext(Dispatchers.IO) {
        val streamUrl = resolveVoeStream(mirror.url) ?: return@withContext null
        val origin = originOf(mirror.url)
        ExtractedStream(
            hostName = "Voe",
            url = streamUrl,
            kind = if (streamUrl.contains(".mp4")) StreamKind.MP4 else StreamKind.HLS,
            referer = mirror.url,
            origin = origin,
            headers = browserHeaders(mirror.url, origin)
        )
    }

    /** Follows the in-page hop, decodes the payload, then falls back to a scan. */
    suspend fun resolveVoeStream(voeUrl: String, depth: Int = 0): String? = withContext(Dispatchers.IO) {
        if (depth > MAX_REDIRECT_HOPS) return@withContext null
        val page = runCatching { Http.getAsync(voeUrl, Http.htmlHeaders(referer = SITE_REFERER)) }.getOrNull()
            ?: return@withContext null
        if (!page.isOk) return@withContext null
        val origin = originOf(voeUrl)

        REDIRECT.find(page.body)?.groupValues?.get(1)?.let { next ->
            val target = absoluteUrl(voeUrl, next.replace("\\/", "/"))
            if (target != voeUrl && target.startsWith("http")) {
                resolveVoeStream(target, depth + 1)?.let { return@withContext it }
            }
        }

        for (match in PAYLOAD.findAll(page.body)) {
            val decoded = decodeVoePayload(match.groupValues[1])
            val candidate = sequenceOf("source", "hls", "direct_access_url", "file", "url")
                .mapNotNull { key -> decoded?.optString(key)?.takeIf { it.isNotBlank() } }
                .firstOrNull()
            if (candidate != null) {
                // Voe sometimes answers with a nested page URL rather than a media URL.
                val absolute = absoluteUrl(origin, candidate)
                return@withContext if (absolute.contains(".m3u8") || absolute.contains(".mp4")) {
                    absolute
                } else {
                    resolveVoeStream(absolute, depth + 1)
                }
            }
        }

        StreamScanner.scan(page.body, origin)?.url
    }

    /**
     * The Voe payload decoder. Pure and unit-tested against the captured pages
     * in `tools/recon/evidence/`.
     */
    fun decodeVoePayload(payload: String): JSONObject? {
        return try {
            var text = rot13(payload)
            for (noise in listOf("@$", "^^", "~@", "%?", "*~", "!!", "#&")) {
                text = text.replace(noise, "")
            }
            text = base64Decode(text)
            text = text.map { (it.code - 3).toChar() }.joinToString("")
            text = text.reversed()
            text = base64Decode(text)
            JSONObject(text)
        } catch (_: Exception) {
            null
        }
    }

    private fun rot13(input: String): String = input.map { ch ->
        when {
            ch in 'a'..'z' -> ((ch - 'a' + 13) % 26 + 'a'.code).toChar()
            ch in 'A'..'Z' -> ((ch - 'A' + 13) % 26 + 'A'.code).toChar()
            else -> ch
        }
    }.joinToString("")

    private fun base64Decode(input: String): String =
        String(Base64Codec.decode(input), Charsets.UTF_8)
}

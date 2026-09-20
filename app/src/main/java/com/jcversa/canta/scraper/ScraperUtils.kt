package com.jcversa.canta.scraper

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import okhttp3.Call
import okhttp3.Callback
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.jsoup.Jsoup
import org.jsoup.nodes.Document
import org.jsoup.nodes.Element
import java.io.IOException
import java.util.concurrent.TimeUnit
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

/**
 * Raised when a page answered 200 but nothing in it matches the selectors this
 * app was written against. That is a *different* failure from "offline" or
 * "403", and the UI says so instead of showing an empty catalogue — see
 * RECONNAISSANCE.md §6 for the fallback ladder that runs before this is thrown.
 */
class StructureChangedException(
    val layer: String,
    val url: String,
    message: String = "Structure inattendue ($layer) sur $url — sélecteurs à revérifier."
) : IOException(message)

/** Network/HTTP failure that is worth retrying on another mirror. */
class ScrapeException(message: String, cause: Throwable? = null) : IOException(message, cause)

/**
 * Shared HTTP layer for every scraper and extractor.
 *
 * Timeouts are deliberately short: the mirror ladder (VidMoly → Voe → generic
 * → nakanime) must fail fast instead of blocking the UI on a dead CDN, which is
 * the rule that nebula-p's production logs settled on (§8.5 of its audit).
 */
object Http {

    const val USER_AGENT =
        "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/124.0.0.0 Safari/537.36"

    private val client: OkHttpClient = OkHttpClient.Builder()
        .connectTimeout(10, TimeUnit.SECONDS)
        .readTimeout(15, TimeUnit.SECONDS)
        .callTimeout(20, TimeUnit.SECONDS)
        .retryOnConnectionFailure(true)
        .followRedirects(true)
        .build()

    fun client(): OkHttpClient = client

    /** Fixed browser-ish headers. `Referer` matters: several mirrors 403 without one. */
    fun htmlHeaders(referer: String? = null, extra: Map<String, String> = emptyMap()): Map<String, String> = buildMap {
        put("User-Agent", USER_AGENT)
        put("Accept", "text/html,application/xhtml+xml,application/xml;q=0.9,*/*;q=0.8")
        put("Accept-Language", "fr-FR,fr;q=0.9,en;q=0.8")
        referer?.let { put("Referer", it) }
        putAll(extra)
    }

    data class Response(
        val code: Int,
        val body: String,
        val bytes: ByteArray,
        val finalUrl: String
    ) {
        val isOk: Boolean get() = code in 200..299
    }

    fun get(url: String, headers: Map<String, String> = htmlHeaders()): Response {
        val request = Request.Builder().url(url).apply {
            headers.forEach { (k, v) -> header(k, v) }
        }.get().build()
        return execute(request, url)
    }

    fun postJson(url: String, json: String, headers: Map<String, String> = htmlHeaders()): Response {
        val request = Request.Builder().url(url).apply {
            headers.forEach { (k, v) -> header(k, v) }
            header("Content-Type", "application/json")
            header("Accept", "*/*")
        }.post(json.toRequestBody("application/json; charset=utf-8".toMediaType())).build()
        return execute(request, url)
    }

    /**
     * Cancellable GET.
     *
     * The mirror ladder relies on `withTimeoutOrNull`, which only interrupts at
     * a suspension point: a blocking OkHttp call would keep the UI waiting for
     * a dead CDN even after the timeout fired. Enqueueing the call and
     * cancelling it from `invokeOnCancellation` makes the abort real — the
     * socket is closed the moment the budget expires.
     */
    suspend fun getAsync(url: String, headers: Map<String, String> = htmlHeaders()): Response =
        enqueue(Request.Builder().url(url).apply { headers.forEach { (k, v) -> header(k, v) } }.get().build(), url)

    suspend fun postJsonAsync(url: String, json: String, headers: Map<String, String> = htmlHeaders()): Response =
        enqueue(
            Request.Builder().url(url).apply {
                headers.forEach { (k, v) -> header(k, v) }
                header("Content-Type", "application/json")
                header("Accept", "*/*")
            }.post(json.toRequestBody("application/json; charset=utf-8".toMediaType())).build(),
            url
        )

    private suspend fun enqueue(request: Request, url: String): Response =
        suspendCancellableCoroutine { continuation ->
            val call = client.newCall(request)
            continuation.invokeOnCancellation { runCatching { call.cancel() } }
            call.enqueue(object : Callback {
                override fun onFailure(call: Call, e: IOException) {
                    if (continuation.isActive) {
                        continuation.resumeWithException(ScrapeException("Requête échouée: $url (${e.message})", e))
                    }
                }

                override fun onResponse(call: Call, response: okhttp3.Response) {
                    response.use { res ->
                        val bytes = runCatching { res.body?.bytes() ?: ByteArray(0) }.getOrDefault(ByteArray(0))
                        if (continuation.isActive) {
                            continuation.resume(
                                Response(
                                    code = res.code,
                                    body = bytes.toString(Charsets.UTF_8),
                                    bytes = bytes,
                                    finalUrl = res.request.url.toString()
                                )
                            )
                        }
                    }
                }
            })
        }

    private fun execute(request: Request, url: String): Response {
        try {
            client.newCall(request).execute().use { response ->
                val bytes = response.body?.bytes() ?: ByteArray(0)
                return Response(
                    code = response.code,
                    body = bytes.toString(Charsets.UTF_8),
                    bytes = bytes,
                    finalUrl = response.request.url.toString()
                )
            }
        } catch (io: IOException) {
            throw ScrapeException("Requête échouée: $url (${io.message})", io)
        }
    }

    /**
     * Byte size of a resource, as reported by the server. Uses HEAD first and
     * falls back to a one-byte ranged GET, because several CDNs answer HEAD
     * with 200 and no `Content-Length` while answering `Range: bytes=0-0` with
     * a proper `Content-Range: bytes 0-0/<total>`.
     *
     * Returns null when the server refuses to state a size — never an estimate.
     */
    suspend fun contentLengthAsync(url: String, headers: Map<String, String>, timeoutMs: Long = 6_000): Long? =
        withContext(Dispatchers.IO) { contentLength(url, headers, timeoutMs) }

    fun contentLength(url: String, headers: Map<String, String>, timeoutMs: Long = 6_000): Long? {
        try {
            val head = Request.Builder().url(url).apply { headers.forEach { (k, v) -> header(k, v) } }.head().build()
            val call = client.newCall(head)
            call.timeout().timeout(timeoutMs, TimeUnit.MILLISECONDS)
            call.execute().use { response ->
                if (response.isSuccessful) {
                    val len = response.header("Content-Length")?.toLongOrNull()
                    if (len != null && len > 0) return len
                }
            }
        } catch (_: IOException) {
            // fall through to the ranged GET
        }
        try {
            val ranged = Request.Builder().url(url).apply {
                headers.forEach { (k, v) -> header(k, v) }
                header("Range", "bytes=0-0")
            }.get().build()
            val rangedCall = client.newCall(ranged)
            rangedCall.timeout().timeout(timeoutMs, TimeUnit.MILLISECONDS)
            rangedCall.execute().use { response ->
                val contentRange = response.header("Content-Range") ?: return null
                val total = contentRange.substringAfter('/', "").trim().toLongOrNull() ?: return null
                return total.takeIf { it > 0 }
            }
        } catch (_: IOException) {
            return null
        }
    }
}

/**
 * Every selector this app depends on, in one place, each one carrying the date
 * and the evidence file it was confirmed against. When a scrape fails, the
 * layer name in [StructureChangedException] points back here.
 */
object Selectors {

    // --- voir-anime.to (WordPress + Madara) — verified 2026-09-20 -------------
    const val VA_CARD = "div.page-item-detail"
    const val VA_CARD_TITLE_SEARCH = "h3.h4 > a[href]"
    const val VA_CARD_TITLE_HOME = "h3.h5 > a[href]"
    const val VA_CARD_LINK = "div.item-thumb a[href]"
    const val VA_CARD_IMAGE = "div.item-thumb img"
    const val VA_DETAIL_TITLE = "div.post-title h1"
    const val VA_DETAIL_COVER = "div.summary_image img"
    const val VA_DETAIL_SYNOPSIS = "div.description-summary div.summary__content"
    const val VA_DETAIL_SYNOPSIS_FALLBACK = "div.description-summary"
    const val VA_DETAIL_GENRE = "a[href*=anime-genre]"
    const val VA_EPISODE_ROW = "li.wp-manga-chapter"
    const val VA_EPISODE_LINK = "a[href]"
    const val VA_EPISODE_DATE = "span.chapter-release-date"
    const val VA_EPISODE_CONTAINER = "div.page-content-listing"
    const val VA_PLAYER_FRAME = "div#chapter-video-frame iframe[src]"
    const val VA_PLAYER_ANY_IFRAME = "div.reading-content iframe[src]"

    /** `var thisChapterSources = {"LECTEUR myTV":"<iframe src=\"…\">", …};` */
    val VA_CHAPTER_SOURCES = Regex("""var\s+thisChapterSources\s*=\s*(\{[\s\S]*?\});""")
    val VA_EMBED_URL = Regex("""https?://[^\s"'<>\\]+/embed-[a-z0-9-]+\.html""", RegexOption.IGNORE_CASE)

    // --- nakanime.tv (JSON API + server-rendered episode pages) — 2026-09-20 --
    const val NK_SEASONS_SCRIPT = "script"
    const val NK_DATA_EPISODE_ID = """data-episode-id=["'](\d+)["']"""

    /** Absolute `/anime/<slug>/` entry links, used when card markup moves. */
    val VA_ANIME_LINK = Regex("""https?://voir-anime\.to/anime/([a-z0-9-]+)/?""", RegexOption.IGNORE_CASE)

    /** `-NN-vf` / `-NN-vostfr` episode slugs (films/OAVs carry no number). */
    val VA_EPISODE_SLUG_NUMBER = Regex("""-(\d+)-(vf|vostfr)/?$""", RegexOption.IGNORE_CASE)
    val VA_EPISODE_SLUG_MOVIE = Regex("""^(film|oav|movie)-|-(film|oav|movie)$""", RegexOption.IGNORE_CASE)

    fun parse(html: String, baseUrl: String): Document = Jsoup.parse(html, baseUrl)
}

/** Empty-string-tolerant absolute URL resolution. */
fun Element.absSrc(): String? {
    val raw = when {
        hasAttr("data-src") && attr("data-src").isNotBlank() -> attr("data-src")
        hasAttr("src") -> attr("src")
        else -> ""
    }
    if (raw.isBlank()) return null
    return try {
        absUrl("src").ifBlank { java.net.URI(baseUri()).resolve(raw).toString() }
    } catch (_: Exception) {
        raw.takeIf { it.startsWith("http") }
    }
}

/** `One Piece (VF)` → `One Piece`, plus the language that the suffix proves. */
fun splitVfSuffix(title: String, href: String): Pair<String, Boolean> {
    val isVf = href.trimEnd('/').lowercase().endsWith("-vf")
    val clean = title.replace(Regex("""\s*\(VF\)\s*$""", RegexOption.IGNORE_CASE), "").trim()
    return clean to isVf
}

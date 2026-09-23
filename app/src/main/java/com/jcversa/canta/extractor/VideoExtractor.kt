package com.jcversa.canta.extractor

import android.util.Log

import com.jcversa.canta.model.Language
import com.jcversa.canta.model.MirrorRef
import com.jcversa.canta.model.MirrorResult
import com.jcversa.canta.model.Source
import com.jcversa.canta.scraper.Http
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull

/** What an extractor produced, before quality/size inspection. */
data class ExtractedStream(
    val hostName: String,
    val url: String,
    val kind: StreamKind,
    val referer: String? = null,
    val origin: String? = null,
    val headers: Map<String, String> = emptyMap(),
    val notes: List<String> = emptyList()
)

enum class StreamKind { HLS, MP4 }

/** One mirror host. Implementations must be side-effect free and cancellable. */
interface VideoExtractor {
    val name: String
    fun supports(mirror: MirrorRef): Boolean

    /** Null means "this mirror did not yield a stream" — never a thrown lie. */
    suspend fun extract(mirror: MirrorRef): ExtractedStream?
}

/**
 * Mirror reliability order.
 *
 * VidMoly first, Voe second — the order the task specifies, and the order
 * nebula-p settled on after two production incidents: a Sibnet branch that
 * *fabricated* "480P"/"360P" tracks above VidMoly in the list produced a
 * 299.2 MB file labelled 360P (audit §8.4), and voe/voembed needed its master
 * playlist parsed before it could report a real quality (§8.11).
 *
 * The VidMoly family is matched by host *and* by CDN signature, because the
 * player host and the file host differ: voir-anime's `voembed.net` player
 * serves from `prx-*-ant.vmget.online/hls2/…` (evidence: RECONNAISSANCE.md §4).
 */
fun hostPriority(url: String): Int {
    val lower = url.lowercase()
    return when {
        lower.contains("vidmoly") || lower.contains("vmpx") || lower.contains("topembed") -> 1
        lower.contains("voembed") -> 1 // voir-anime's VidMoly-family player (verified)
        lower.contains("/hls2/") || VIDMOLY_CDN_HOSTS.any { lower.contains(it) } -> 1
        lower.contains("ansembed") -> 2
        lower.contains("voe") || lower.contains("mfw") -> 3
        lower.contains("sibnet") -> 4
        lower.contains("sendvid") -> 5
        lower.contains("streamtape") -> 7
        else -> 6
    }
}

/**
 * One tag for every mirror failure, so a run's logcat can be read for exactly this
 * and nothing else. `adb logcat -s CantaResolve` is the whole recipe.
 */
const val LOG_TAG_RESOLVE = "CantaResolve"

/** Host fragments of the rotating VidMoly CDN family (audit §8.43). */
val VIDMOLY_CDN_HOSTS = listOf("vmeas.", "vmget.", "vmnow.", "vmbox.", "vmcld.")

/**
 * Resolves an episode by walking the mirror list in priority order and
 * failing over on the *first* failure — no retry storm, no blocking UI.
 */
object MirrorResolver {

    /** Per-mirror budget. A dead CDN must not hold the player screen hostage. */
    private const val PER_MIRROR_TIMEOUT_MS = 14_000L

    /**
     * Budget for the *whole* resolution, inspection and size measurement
     * included.
     *
     * The per-mirror timeout only bounds one extractor: `QualityGuard.resolve`
     * then inspects the playlist and measures the candidate variants (up to
     * `MEASURE_BUDGET_MS` each), so a series of half-dead mirrors could still
     * leave the user on the spinner well past 14 s. This is the outer bound, and
     * hitting it is reported with the mirrors that were tried.
     */
    private const val TOTAL_RESOLVE_BUDGET_MS = 25_000L

    private val extractors: List<VideoExtractor> = listOf(VidmolyExtractor, VoeExtractor, GenericExtractor)

    /**
     * Tries every mirror in priority order until one yields a stream, then
     * inspects it and applies the quality guard.
     *
     * @param requestedQuality honest label ("1080P", "480P") or null for the default policy.
     */
    suspend fun resolve(
        mirrors: List<MirrorRef>,
        language: Language,
        source: Source,
        requestedQuality: String? = null
    ): MirrorResult? = withContext(Dispatchers.IO) {
        val ordered = mirrors.sortedBy { hostPriority(it.url) }
        val failures = mutableListOf<String>()
        val result = withTimeoutOrNull(TOTAL_RESOLVE_BUDGET_MS) {
            attemptAll(ordered, language, source, requestedQuality, failures)
        }
        if (result == null) {
            // Honest failure: the UI prints which mirrors were tried and whether
            // the budget ran out, not a generic "erreur".
            lastFailure = when {
                ordered.isEmpty() -> "Aucun lecteur exploitable pour cet épisode."
                failures.isEmpty() -> "Résolution interrompue après " +
                    "${TOTAL_RESOLVE_BUDGET_MS / 1000} s sans qu'aucun lecteur ait abouti."
                else -> "Tous les lecteurs ont échoué ou dépassé les " +
                    "${TOTAL_RESOLVE_BUDGET_MS / 1000} s: ${failures.joinToString(", ")}"
            }
        }
        result
    }

    /** The ordered attempt loop, kept separate so the caller can bound it. */
    private suspend fun attemptAll(
        ordered: List<MirrorRef>,
        language: Language,
        source: Source,
        requestedQuality: String?,
        failures: MutableList<String>
    ): MirrorResult? {
        for (mirror in ordered) {
            val extractor = extractors.firstOrNull { it.supports(mirror) } ?: continue
            // Why it failed matters as much as that it failed: the message this
            // produces is shown to the user, and "voembed.net (VidMoly)" alone is not
            // something anyone can act on. The reason is carried out of the swallowed
            // exception (and out of the cancellation, where the exception never
            // reaches us) and capped, so one verbose cause cannot turn the error card
            // into a wall of text.
            var reason: String? = null
            val stream = withTimeoutOrNull(PER_MIRROR_TIMEOUT_MS) {
                runCatching { extractor.extract(mirror) }
                    .onFailure { failure ->
                        // The UI gets the class and the message; the full throwable goes
                        // to logcat under one tag, because a stack trace belongs in a
                        // log and not in an error card. Without this, a failure whose
                        // message is something like a bare class name is undiagnosable
                        // from the screen - which is exactly what run 23 showed.
                        reason = buildString {
                            append(failure.javaClass.simpleName)
                            failure.message?.takeIf { it.isNotBlank() }?.let { append(": ").append(it) }
                        }
                        Log.w(LOG_TAG_RESOLVE, "mirror ${mirror.host} via ${extractor.name} failed", failure)
                    }
                    .getOrNull()
            }
            if (stream == null || stream.url.isBlank()) {
                // A captured `var` cannot be smart-cast, so it is read into a local
                // instead of asserted with `!!`.
                val caught = reason
                val why = when {
                    caught != null -> caught
                    stream == null -> "aucune réponse en ${PER_MIRROR_TIMEOUT_MS / 1000} s"
                    else -> "flux sans URL"
                }.replace('\n', ' ').take(60)
                failures += "${mirror.host} (${extractor.name}: $why)"
                continue
            }
            val resolved = QualityGuard.resolve(stream, requestedQuality, stream.playbackHeaders())
            if (resolved == null) {
                failures += "${mirror.host} (aucune qualité lisible)"
                continue
            }
            val (tracks, decision) = resolved
            return MirrorResult(
                streamUrl = decision.track.url,
                language = mirror.language,
                quality = decision.track,
                measuredSizeBytes = decision.track.measuredBytes,
                sizeIsExact = decision.track.sizeIsExact,
                hostName = stream.hostName,
                source = source,
                referer = stream.referer,
                origin = stream.origin,
                headers = stream.headers,
                availableTracks = tracks,
                downgradeNote = decision.note,
                isHls = stream.kind == StreamKind.HLS
            )
        }
        return null
    }

    /** Why the last [resolve] returned null. Read by the UI for an honest message. */
    @Volatile
    var lastFailure: String? = null
        private set

    /** Priority ladder used by tests and by the settings screen. */
    fun order(mirrors: List<MirrorRef>): List<MirrorRef> = mirrors.sortedBy { hostPriority(it.url) }
}

/**
 * Generic last-resort probe: any host nobody has a recipe for. It unpacks
 * Dean-Edwards payloads and looks for a playable stream in reliability order —
 * absolute `.m3u8`/`.txt`, relative `/…m3u8` against the player origin, then an
 * absolute `.mp4`.
 */
object GenericExtractor : VideoExtractor {

    override val name = "Generic"

    override fun supports(mirror: MirrorRef): Boolean = true

    override suspend fun extract(mirror: MirrorRef): ExtractedStream? {
        val response = runCatching { Http.get(mirror.url, Http.htmlHeaders(referer = SITE_REFERER)) }.getOrNull() ?: return null
        if (!response.isOk) return null
        val origin = originOf(mirror.url)
        val found = StreamScanner.scan(response.body, origin) ?: return null
        return ExtractedStream(
            hostName = mirror.host,
            url = found.url,
            kind = found.kind,
            referer = mirror.url,
            origin = origin,
            headers = browserHeaders(mirror.url, origin)
        )
    }
}

/** Absolute-vs-relative URL helper shared by every extractor. */
fun absoluteUrl(base: String, candidate: String): String = try {
    if (candidate.startsWith("//")) "https:$candidate"
    else if (candidate.startsWith("http")) candidate
    else java.net.URI(base).resolve(candidate).toString()
} catch (_: Exception) {
    candidate
}

fun originOf(url: String): String = try {
    val uri = java.net.URI(url)
    "${uri.scheme}://${uri.host}"
} catch (_: Exception) {
    url.substringBefore('/', url)
}

/** Referer a mirror expects: its own page, which is what a browser would send. */
fun browserHeaders(playerUrl: String, origin: String): Map<String, String> = mapOf(
    "Referer" to playerUrl,
    "Origin" to origin
)

const val SITE_REFERER = "https://voir-anime.to/"

/** Result of scanning a player page. */
data class ScannedStream(val url: String, val kind: StreamKind)

/**
 * The shared player-page scanner. Pure function of (html, playerOrigin) so it
 * can be unit-tested against captured pages.
 */
object StreamScanner {

    private val ABSOLUTE = Regex("""https?://[^\s"'<>|]+\.(m3u8|txt|mp4)(\?[^\s"'<>|]*)?""", RegexOption.IGNORE_CASE)
    private val RELATIVE = Regex("""["'(\s](/[^\s"'<>|]+\.(m3u8|txt)(\?[^\s"'<>|]*)?)""", RegexOption.IGNORE_CASE)
    private val SOURCES_ARRAY = Regex("""sources\s*:\s*\[([\s\S]{0,600}?)]""", RegexOption.IGNORE_CASE)
    private val FILE_KEY = Regex("""file\s*:\s*["']([^"']+)["']""", RegexOption.IGNORE_CASE)

    fun scan(html: String, playerOrigin: String): ScannedStream? {
        if (html.isBlank()) return null
        val combined = html + "\n" + DeanEdwards.unpack(html)

        // 1. An explicit `sources: [{ file: … }]` (voembed/jwplayer) wins: it is
        //    the player's own declaration, not a URL that merely looks like HLS.
        SOURCES_ARRAY.find(combined)?.let { match ->
            val inner = match.groupValues[1]
            val file = if (inner.trimStart().startsWith("http")) {
                Regex("""https?://[^\s"']+""").find(inner)?.value
            } else {
                FILE_KEY.find(inner)?.groupValues?.get(1)
            }
            if (!file.isNullOrBlank() && looksPlayable(file)) {
                return ScannedStream(absoluteUrl(playerOrigin, file.trim()), kindOf(file))
            }
        }

        // 2. An advertised `file: "…"` anywhere in the page.
        FILE_KEY.findAll(combined).forEach { match ->
            val candidate = match.groupValues[1].trim()
            if (looksPlayable(candidate) && !candidate.contains("/api/v1/slides")) {
                return ScannedStream(absoluteUrl(playerOrigin, candidate), kindOf(candidate))
            }
        }

        // 3. Any absolute HLS URL, master playlist preferred.
        val absolute = ABSOLUTE.findAll(combined).map { it.value }.filter { looksPlayable(it) }.toList()
        absolute.firstOrNull { it.contains("master.m3u8", ignoreCase = true) }?.let {
            return ScannedStream(it, StreamKind.HLS)
        }
        absolute.firstOrNull { it.endsWith(".m3u8") || it.endsWith(".txt") }?.let {
            return ScannedStream(it, StreamKind.HLS)
        }
        absolute.firstOrNull { it.endsWith(".mp4") }?.let { return ScannedStream(it, StreamKind.MP4) }

        // 4. A player-relative playlist (movearnpre-style).
        RELATIVE.find(combined)?.groupValues?.get(1)?.let { relative ->
            return ScannedStream(absoluteUrl(playerOrigin, relative), StreamKind.HLS)
        }
        return null
    }

    private fun kindOf(url: String): StreamKind =
        if (url.contains(".mp4")) StreamKind.MP4 else StreamKind.HLS

    private fun looksPlayable(url: String): Boolean {
        val lower = url.lowercase()
        if (lower.contains("/api/v1/slides")) return false
        return lower.contains(".m3u8") || lower.contains(".txt") || lower.contains(".mp4")
    }
}

/**
 * Dean-Edwards unpacker, without eval.
 *
 * The tail is `(?:,\s*[^)]*)?` on purpose: the canonical form ends
 * `.split('|'),0,{}))` and a regex that stops at `.split('|'))` silently
 * matches nothing on live pages — nebula-p audit finding R4, with a test
 * fixture for both forms in this repo.
 */
object DeanEdwards {

    // `internal`, not `private`, so a unit test can assert the pattern's shape: the
    // bug this guards against (a bare `}`) compiles on the JVM but throws
    // PatternSyntaxException on Android, where ICU parses the pattern - so a test
    // that only *uses* the regex cannot catch it on a JVM-only CI run. See
    // StreamScannerTest.
    internal val PACKED = Regex(
        """eval\(function\(p,a,c,k,e,d\)\{[\s\S]*?return\s+p;?\}\((?:'((?:[^'\\]|\\.)*)'|"((?:[^"\\]|\\.)*)")\s*,\s*(\d+)\s*,\s*(\d+)\s*,\s*(?:'((?:[^'\\]|\\.)*)'|"((?:[^"\\]|\\.)*)")\.split\(['"]\|['"]\)(?:\s*,\s*[^)]*)?\)""",
        RegexOption.IGNORE_CASE
    )

    fun unpack(html: String): String {
        if (html.isBlank()) return ""
        val out = StringBuilder()
        for (match in PACKED.findAll(html)) {
            val payload = decodeStringLiteral("'" + (match.groupValues[1].ifEmpty { match.groupValues[2] }) + "'")
            val radix = match.groupValues[3].toIntOrNull() ?: continue
            val count = match.groupValues[4].toIntOrNull() ?: continue
            val words = match.groupValues[5].ifEmpty { match.groupValues[6] }.split('|')
            var unpacked = payload
            for (i in count - 1 downTo 0) {
                val word = words.getOrNull(i) ?: continue
                if (word.isEmpty()) continue
                unpacked = unpacked.replace(Regex("\\b${i.toString(radix)}\\b"), word)
            }
            out.append('\n').append(unpacked)
        }
        return out.toString()
    }

    /** Decodes the escapes packers actually emit, without running any code. */
    fun decodeStringLiteral(literal: String): String {
        if (literal.length < 2) return literal
        val quote = literal.first()
        if ((quote != '\'' && quote != '"') || literal.last() != quote) return literal
        val body = literal.substring(1, literal.length - 1)
        val out = StringBuilder(body.length)
        var i = 0
        while (i < body.length) {
            val ch = body[i]
            if (ch != '\\' || i == body.length - 1) {
                out.append(ch); i++; continue
            }
            when (val next = body[i + 1]) {
                'n' -> { out.append('\n'); i += 2 }
                'r' -> { out.append('\r'); i += 2 }
                't' -> { out.append('\t'); i += 2 }
                '\\' -> { out.append('\\'); i += 2 }
                '\'' -> { out.append('\''); i += 2 }
                '"' -> { out.append('"'); i += 2 }
                'x' -> {
                    val hex = body.substring(i + 2, minOf(i + 4, body.length))
                    val code = hex.toIntOrNull(16)
                    if (code != null) { out.append(code.toChar()); i += 4 } else { i += 2 }
                }
                'u' -> {
                    val hex = body.substring(i + 2, minOf(i + 6, body.length))
                    val code = hex.toIntOrNull(16)
                    if (code != null) { out.append(code.toChar()); i += 6 } else { i += 2 }
                }
                else -> { out.append(next); i += 2 }
            }
        }
        return out.toString()
    }
}

/**
 * Variant derivation for multi-quality `.urlset` masters (nebula-p audit
 * §8.40/§8.43). When the master 403s, the individual renditions usually still
 * answer; the last letter of the urlset is the rendition every single-quality
 * file uses, so it is tried first.
 */
fun deriveSubVariantUrls(masterUrl: String): List<String> {
    val out = mutableListOf<String>()
    val urlset = Regex("""([^/]+)_([a-zA-Z0-9,]+)\.urlset/master\.(m3u8|txt)(\?.*)?$""").find(masterUrl)
    if (urlset != null) {
        val prefix = urlset.groupValues[1]
        val qualities = urlset.groupValues[2].split(',').filter { it.isNotEmpty() }
        val ext = urlset.groupValues[3]
        val query = urlset.groupValues[4]
        val base = masterUrl.substring(0, masterUrl.indexOf("$prefix-_").let { idx ->
            if (idx >= 0) idx else masterUrl.indexOf(prefix)
        })
        for (quality in qualities.reversed()) {
            out += "${base}${prefix}_$quality/index-v1-a1.$ext$query"
            out += "${base}${prefix}_$quality/index-f1-v1-a1.$ext$query"
            out += "${base}${prefix}_$quality/index.$ext$query"
            out += "${base}${prefix}_$quality.$ext$query"
        }
        out += "${base}${prefix}/index-v1-a1.$ext$query"
        out += "${base}${prefix}/index.$ext$query"
    }
    if (masterUrl.contains("master.txt")) {
        val base = masterUrl.substringBeforeLast('/') + "/"
        val query = if (masterUrl.contains("?")) masterUrl.substringAfter("?") .let { "?$it" } else ""
        out += "${base}index-f1-v1-a1.txt$query"
        out += "${base}index-f2-v1-a1.txt$query"
        out += "${base}index-f3-v1-a1.txt$query"
    }
    return out
}

/**
 * Referer/Origin matrix for a challenging CDN (nebula-p audit §8.43): the file
 * host is not enough to know which referer a node accepts, so the proven pairs
 * are tried in order, VidMoly's own embed host first.
 */
fun refererCandidates(url: String, base: Map<String, String>): List<Map<String, String>> {
    val browser = mapOf(
        "User-Agent" to Http.USER_AGENT,
        "Accept" to "*/*",
        "Accept-Language" to "fr-FR,fr;q=0.9,en;q=0.8"
    )
    val out = mutableListOf<Map<String, String>>()
    out += browser + base
    val isCdn = url.contains("/hls2/") || VIDMOLY_CDN_HOSTS.any { url.lowercase().contains(it) } ||
        listOf("vidmoly.", "vmpx.", "ansembed.", "topembed.").any { url.lowercase().contains(it) }
    if (isCdn) {
        listOf(
            "https://vidmoly.biz/" to "https://vidmoly.biz",
            "https://vidmoly.to/" to "https://vidmoly.to",
            "https://vidmoly.net/" to "https://vidmoly.net",
            "https://vmpx.online/" to "https://vmpx.online",
            "https://ansembed.net/" to "https://ansembed.net"
        ).forEach { (referer, origin) ->
            out += browser + mapOf("Referer" to referer, "Origin" to origin)
        }
    } else {
        originOf(url).let { origin ->
            out += browser + mapOf("Referer" to "$origin/", "Origin" to origin)
        }
    }
    out += mapOf("User-Agent" to Http.USER_AGENT, "Accept" to "*/*")
    return out
}

/** Small helper used by the resolvers: the first answer that is actually a playlist. */
internal suspend fun fetchPlaylist(url: String, headers: Map<String, String>, timeoutMs: Long = 8_000): String? =
    withTimeoutOrNull(timeoutMs) {
        coroutineScope {
            val response = async { runCatching { Http.get(url, headers) }.getOrNull() } .await() ?: return@coroutineScope null
            if (response.isOk && response.body.contains("#EXT")) response.body else null
        }
    }

/** Every quality label the app knows about, for the player's quality menu. */
val KNOWN_QUALITY_LABELS = listOf("1080P", "720P", "480P", "360P")

/**
 * Minimal Base64 decoder (standard and URL-safe alphabets, padding optional).
 *
 * Deliberately hand-rolled: `android.util.Base64` cannot run in a JVM unit test,
 * and `java.util.Base64` needs API 26 while Canta supports API 23. The Voe
 * payload decoder is one of the pieces that *must* be covered by tests, since a
 * wrong alphabet or pad silently yields "no stream" in production.
 */
internal object Base64Codec {

    private const val ALPHABET = "ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789+/"

    private val LOOKUP = IntArray(128) { -1 }.also { table ->
        ALPHABET.forEachIndexed { index, char -> table[char.code] = index }
    }

    fun decode(input: String): ByteArray {
        val out = java.io.ByteArrayOutputStream(input.length / 4 * 3 + 3)
        var buffer = 0
        var bits = 0
        for (char in input) {
            val value = when {
                char.code < 128 -> LOOKUP[char.code]
                else -> -1
            }.let { if (it >= 0) it else when (char) { '-' -> 62; '_' -> 63; else -> -1 } }
            if (value < 0) {
                if (char == '=') break else continue
            }
            buffer = (buffer shl 6) or value
            bits += 6
            if (bits >= 8) {
                bits -= 8
                out.write((buffer shr bits) and 0xFF)
            }
        }
        return out.toByteArray()
    }
}

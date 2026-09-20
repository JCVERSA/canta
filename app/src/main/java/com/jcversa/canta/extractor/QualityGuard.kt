package com.jcversa.canta.extractor

import com.jcversa.canta.model.QualityTrack
import com.jcversa.canta.scraper.Http
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import java.util.concurrent.ConcurrentHashMap

/**
 * Quality labels and sizes you can defend.
 *
 * Two rules, both ported from nebula-p's production audit:
 *
 *  1. **The label comes from the playlist, never from the CDN.** `RESOLUTION=`
 *     of the variant stream is the only evidence. Audit §8.11: a Voe/voembed
 *     master whose variants had not been parsed got labelled "720P" for a
 *     480p file; §8.13: a file labelled "480P" that was 403 MB.
 *
 *  2. **The size is measured, not estimated.** nebula-p computes
 *     `bandwidth × duration / 8`, which its own live data contradicts
 *     (RECONNAISSANCE.md §4: 597.2 MB advertised vs 823.8 MB measured on the
 *     first segments alone). Canta HEADs the segments themselves and sums real
 *     `Content-Length` values. When a CDN refuses to state sizes for every
 *     segment, the track is marked [QualityTrack.sizeIsExact] = false and the
 *     UI says "échantillon" instead of pretending the number is exact.
 *
 * The **fast-lane size guard** then does the thing the task asks for: a
 * variant advertised as 480P whose *measured* size is abnormally large
 * (> 200 MiB) is downgraded to the lightest ≤ 480P variant, and the decision
 * is returned as a note so the screen can log it visibly.
 */
object QualityGuard {

    /** The ceiling above which an "480P" label is not credible (audit §8.13). */
    const val FAST_LANE_MAX_BYTES: Long = 200L * 1024 * 1024

    private const val SEGMENT_PROBE_CONCURRENCY = 6
    private const val MEASURE_BUDGET_MS = 12_000L
    private const val MAX_SEGMENTS_MEASURED = 400
    private const val SAMPLE_SIZE = 48

    data class Decision(val track: QualityTrack, val downgraded: Boolean, val note: String?)

    data class Variant(
        val label: String,
        val url: String,
        val bandwidth: Long?,
        val width: Int?,
        val height: Int?
    )

    // ------------------------------------------------------------------ parsing

    /** `#EXT-X-STREAM-INF` lines → variants, label derived from `RESOLUTION=`. */
    fun parseMaster(text: String, baseUrl: String): List<Variant> {
        if (text.isBlank()) return emptyList()
        val lines = text.split('\n').map { it.trim() }
        val out = mutableListOf<Variant>()
        for (i in lines.indices) {
            val line = lines[i]
            if (!line.startsWith("#EXT-X-STREAM-INF")) continue
            val next = lines.getOrNull(i + 1)?.trim() ?: continue
            if (next.isEmpty() || next.startsWith("#")) continue
            val bandwidth = Regex("BANDWIDTH=(\\d+)").find(line)?.groupValues?.get(1)?.toLongOrNull()
            val resolution = Regex("RESOLUTION=(\\d+)x(\\d+)", RegexOption.IGNORE_CASE).find(line)
            val width = resolution?.groupValues?.get(1)?.toIntOrNull()
            val height = resolution?.groupValues?.get(2)?.toIntOrNull()
            out += Variant(
                label = labelFor(height),
                url = absoluteUrl(baseUrl, next),
                bandwidth = bandwidth,
                width = width,
                height = height
            )
        }
        return out
    }

    /**
     * Height → quality label. 0/unknown stays honest: it is not called "480P".
     */
    fun labelFor(height: Int?): String = when {
        height == null || height <= 0 -> "Originale"
        height >= 1000 -> "1080P"
        height >= 700 -> "720P"
        height >= 450 -> "480P"
        height >= 300 -> "360P"
        else -> "240P"
    }

    /** Media-playlist segment URLs, resolved against the playlist URL. */
    fun parseSegmentUrls(text: String, playlistUrl: String): List<String> =
        text.split('\n')
            .map { it.trim() }
            .filter { it.isNotEmpty() && !it.startsWith("#") }
            .map { absoluteUrl(playlistUrl, it) }

    /** Segment durations from `#EXTINF:` — the measured length of the episode. */
    fun parseDurations(text: String): List<Double> =
        Regex("#EXTINF:([\\d.]+)").findAll(text).mapNotNull { it.groupValues[1].toDoubleOrNull() }.toList()

    // --------------------------------------------------------------- inspection

    /**
     * Turns an extracted stream into quality tracks. Sizes are *not* filled in
     * here: only [resolve] decides which tracks are worth measuring.
     */
    suspend fun inspect(stream: ExtractedStream): List<QualityTrack> = withContext(Dispatchers.IO) {
        val headers = stream.playbackHeaders()
        if (stream.kind == StreamKind.MP4) {
            val size = Http.contentLengthAsync(stream.url, headers)
            return@withContext listOf(
                QualityTrack(
                    label = "Originale",
                    url = stream.url,
                    measuredBytes = size,
                    sizeIsExact = size != null,
                    segmentCount = 1
                )
            )
        }
        val master = fetchPlaylist(stream.url, headers) ?: return@withContext emptyList()
        val variants = parseMaster(master, stream.url)
        if (variants.isEmpty()) {
            // Single-rendition playlist: no RESOLUTION to report, so no invented label.
            val segments = parseSegmentUrls(master, stream.url)
            return@withContext listOf(
                QualityTrack(
                    label = "Originale",
                    url = stream.url,
                    segmentCount = segments.size
                )
            )
        }
        variants.map { variant ->
            val playlist = runCatching { fetchPlaylist(variant.url, headers) }.getOrNull()
            QualityTrack(
                label = variant.label,
                url = variant.url,
                bandwidth = variant.bandwidth,
                resolutionWidth = variant.width,
                resolutionHeight = variant.height,
                segmentCount = if (playlist != null) parseSegmentUrls(playlist, variant.url).size else 0
            )
        }
    }

    /**
     * The full pipeline: inspect, measure what the decision depends on, pick,
     * and report whether the fast-lane guard fired.
     */
    suspend fun resolve(
        stream: ExtractedStream,
        requestedQuality: String?,
        headers: Map<String, String> = stream.playbackHeaders()
    ): Pair<List<QualityTrack>, Decision>? = withContext(Dispatchers.IO) {
        val tracks = inspect(stream)
        if (tracks.isEmpty()) return@withContext null

        val fastLane = requestedQuality?.uppercase() in FAST_LANES
        val toMeasure = if (fastLane) tracks.filter { it.height in 1..480 }.ifEmpty { tracks } else listOf()
        val measured = toMeasure.associate { it.url to measure(it, headers) }
        val merged = tracks.map { measured[it.url] ?: it }

        // With sizes known for the fast lanes, the guard can fire; otherwise the
        // picked track is measured afterwards so the reported size is still real.
        var decision = pick(merged, requestedQuality)
        if (decision.track.measuredBytes == null) {
            val measuredPicked = measure(decision.track, headers)
            decision = pick(merged.map { if (it.url == measuredPicked.url) measuredPicked else it }, requestedQuality)
        }
        decision to merged.map { track -> if (track.url == decision.track.url) decision.track else track }
    }

    // --------------------------------------------------------------- selection

    val FAST_LANES = setOf("480P", "360P")

    /**
     * Picks the track to play. Pure function of the tracks + request, so the
     * guard's behaviour is unit-tested without a network.
     *
     * `requested == null` applies the default policy — 480P, then 360P, then
     * 720P, then whatever exists (nebula-p `pickOptimalStream`).
     */
    fun pick(tracks: List<QualityTrack>, requestedQuality: String?): Decision {
        if (tracks.isEmpty()) {
            return Decision(QualityTrack(label = "Originale", url = ""), false, null)
        }
        val wanted = requestedQuality?.uppercase()?.takeIf { it.isNotBlank() }

        if (wanted != null) {
            val exact = tracks.firstOrNull { it.label.uppercase() == wanted }
            if (exact != null) {
                if (wanted in FAST_LANES && exact.measuredBytes != null && exact.measuredBytes > FAST_LANE_MAX_BYTES) {
                    val alternative = fastLaneDowngrade(tracks, exact)
                    if (alternative != null) {
                        val note = "Garde qualité: le flux « $wanted » annoncé pèse " +
                            "${exact.measuredBytes / (1024 * 1024)} Mo (> 200 Mo) — " +
                            "bascule sur ${alternative.label} (${(alternative.measuredBytes ?: 0) / (1024 * 1024)} Mo)."
                        return Decision(alternative, true, note)
                    }
                }
                return Decision(exact, false, null)
            }
            // Requested quality is not on this mirror: never go *up* silently.
            val wantedHeight = wanted.filter { it.isDigit() }.toIntOrNull() ?: 0
            val under = tracks.filter { it.height in 1..wantedHeight }.maxByOrNull { it.height }
            val fallback = under ?: tracks.minByOrNull { if (it.height == 0) Int.MAX_VALUE else it.height } ?: tracks.first()
            val note = "Qualité $wanted indisponible sur ce lecteur — lecture en ${fallback.label}."
            return Decision(fallback, false, note)
        }

        val preferred = tracks.firstOrNull { it.label.uppercase() == "480P" }
            ?: tracks.firstOrNull { it.label.uppercase() == "360P" }
            ?: tracks.firstOrNull { it.label.uppercase() == "720P" }
            ?: tracks.first()
        return Decision(preferred, false, null)
    }

    /**
     * The lightest ≤ 480P variant with a *known* size, when the exact fast-lane
     * match is suspiciously heavy. Returns null when nothing lighter is known —
     * an unknown size never wins over a measured one.
     */
    fun fastLaneDowngrade(tracks: List<QualityTrack>, match: QualityTrack): QualityTrack? {
        val candidates = tracks.filter { it.url.isNotBlank() && it.height in 1..480 }
        if (candidates.isEmpty()) return null
        val lightest = candidates
            .sortedBy { it.measuredBytes ?: Long.MAX_VALUE }
            .firstOrNull() ?: return null
        if (lightest.url == match.url) return null
        if (lightest.measuredBytes == null) return null
        return lightest
    }

    // ---------------------------------------------------------------- measuring

    /**
     * Real byte size of a variant: every segment is asked for its length. If the
     * CDN will not answer for all of them (or the playlist is very long), the
     * measured sample is scaled by its share of the total duration and the
     * result is flagged as a sample.
     */
    suspend fun measure(
        track: QualityTrack,
        headers: Map<String, String>,
        budgetMs: Long = MEASURE_BUDGET_MS
    ): QualityTrack = withContext(Dispatchers.IO) {
        if (track.segmentCount == 1 && track.measuredBytes != null) return@withContext track
        val playlist = runCatching { fetchPlaylist(track.url, headers) }.getOrNull() ?: return@withContext track
        val segments = parseSegmentUrls(playlist, track.url)
        if (segments.isEmpty()) return@withContext track
        val durations = parseDurations(playlist)
        val totalDuration = durations.sum()
        val fullPass = segments.size <= MAX_SEGMENTS_MEASURED
        val indexes = if (fullPass) segments.indices.toList() else (0 until SAMPLE_SIZE).toList()

        val sizes = ConcurrentHashMap<Int, Long>()
        val semaphore = Semaphore(SEGMENT_PROBE_CONCURRENCY)
        withTimeoutOrNull(budgetMs) {
            coroutineScope {
                for (index in indexes) {
                    launch {
                        semaphore.withPermit {
                            Http.contentLengthAsync(segments[index], headers)?.let { sizes[index] = it }
                        }
                    }
                }
            }
        }

        val measuredBytes = sizes.values.sum()
        if (measuredBytes <= 0L) {
            return@withContext track.copy(segmentCount = segments.size, measuredBytes = null, sizeIsExact = false)
        }

        val exact = fullPass && sizes.size == segments.size
        val estimated = if (exact) {
            measuredBytes
        } else {
            val measuredDuration = indexes.filter { sizes.containsKey(it) }
                .mapNotNull { durations.getOrNull(it) }
                .sum()
            if (measuredDuration > 0 && totalDuration > 0) {
                (measuredBytes.toDouble() * totalDuration / measuredDuration).toLong()
            } else {
                measuredBytes
            }
        }

        track.copy(
            segmentCount = segments.size,
            measuredBytes = estimated,
            sizeIsExact = exact
        )
    }
}

/** Headers Media3 must replay on every manifest and segment request. */
fun ExtractedStream.playbackHeaders(): Map<String, String> = buildMap {
    put("User-Agent", Http.USER_AGENT)
    put("Accept", "*/*")
    putAll(headers)
    referer?.let { put("Referer", it) }
    origin?.let { put("Origin", it) }
}

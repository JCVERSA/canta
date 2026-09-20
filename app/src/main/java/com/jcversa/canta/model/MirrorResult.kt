package com.jcversa.canta.model

import org.json.JSONArray
import org.json.JSONObject

/** A player/mirror as listed on an episode page. */
data class MirrorRef(
    val host: String,
    val url: String,
    val label: String,
    val language: Language
)

/**
 * One rendition of an HLS master playlist.
 *
 * [label] is derived from the playlist's own `RESOLUTION=` attribute — the only
 * honest source of a quality label. A CDN's file name is not evidence (nebula-p
 * audit §8.11: a "720P"-labelled file that was 480p, §8.13: a "480P" file at
 * 403 MB).
 *
 * [measuredBytes] is a byte count obtained by asking the CDN for the size of
 * every segment, never `bandwidth × duration / 8`. [sizeIsExact] is false when
 * only a sample of the segments could be measured, and the UI then shows the
 * value with an explicit "échantillon" marker instead of silently rounding.
 */
data class QualityTrack(
    val label: String,
    val url: String,
    val bandwidth: Long? = null,
    val resolutionWidth: Int? = null,
    val resolutionHeight: Int? = null,
    val segmentCount: Int = 0,
    val measuredBytes: Long? = null,
    val sizeIsExact: Boolean = false
) {
    val height: Int get() = resolutionHeight ?: label.filter { it.isDigit() }.toIntOrNull() ?: 0
}

/** The stream that was actually resolved, with everything the UI must report honestly. */
data class MirrorResult(
    val streamUrl: String,
    val language: Language,
    val quality: QualityTrack,
    val measuredSizeBytes: Long?,
    val sizeIsExact: Boolean,
    val hostName: String,
    val source: Source,
    val referer: String? = null,
    val origin: String? = null,
    val headers: Map<String, String> = emptyMap(),
    val availableTracks: List<QualityTrack> = emptyList(),
    val downgradeNote: String? = null,
    val isHls: Boolean = true
) {
    /** Headers Media3 must replay on every manifest and segment request. */
    fun playbackHeaders(): Map<String, String> = buildMap {
        putAll(headers)
        referer?.let { put("Referer", it) }
        origin?.let { put("Origin", it) }
    }
}

/** Human-readable size. Never rounds a measured value into a different claim. */
fun formatBytes(bytes: Long?): String {
    if (bytes == null || bytes <= 0) return "taille inconnue"
    val mb = bytes / (1024.0 * 1024.0)
    return if (mb >= 1024) String.format("%.2f Go", mb / 1024.0) else String.format("%.1f Mo", mb)
}

internal fun JSONObject.toQualityTrack(): QualityTrack? {
    val label = optString("label").takeIf { it.isNotEmpty() } ?: return null
    val url = optString("url").takeIf { it.isNotEmpty() } ?: return null
    return QualityTrack(
        label = label,
        url = url,
        bandwidth = if (isNull("bandwidth")) null else optLong("bandwidth"),
        resolutionHeight = if (isNull("resolutionHeight")) null else optInt("resolutionHeight"),
        segmentCount = optInt("segmentCount", 0),
        measuredBytes = if (isNull("measuredBytes")) null else optLong("measuredBytes"),
        sizeIsExact = optBoolean("sizeIsExact", false)
    )
}

internal fun List<QualityTrack>.toJson(): JSONArray = JSONArray().apply {
    forEach { track ->
        put(
            JSONObject().apply {
                put("label", track.label)
                put("url", track.url)
                put("bandwidth", track.bandwidth ?: JSONObject.NULL)
                put("resolutionHeight", track.resolutionHeight ?: JSONObject.NULL)
                put("segmentCount", track.segmentCount)
                put("measuredBytes", track.measuredBytes ?: JSONObject.NULL)
                put("sizeIsExact", track.sizeIsExact)
            }
        )
    }
}

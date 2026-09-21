package com.jcversa.canta.extractor

import com.jcversa.canta.model.Language
import com.jcversa.canta.model.MirrorRef
import com.jcversa.canta.model.QualityTrack
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The quality guard is where "never mislead the user" is implemented, so it is
 * tested without a network: [QualityGuard.pick] is a pure function of the tracks
 * and the request, and the parse helpers run on real playlist text.
 */
class QualityGuardTest {

    private fun track(label: String, height: Int, bytes: Long?, url: String = "https://cdn/$label.m3u8") =
        QualityTrack(label = label, url = url, resolutionHeight = height, measuredBytes = bytes, sizeIsExact = bytes != null)

    private val master = """
        #EXTM3U
        #EXT-X-VERSION:3
        #EXT-X-STREAM-INF:BANDWIDTH=3338770,RESOLUTION=1920x1080,FRAME-RATE=23.974,CODECS="avc1.640028,mp4a.40.2",VIDEO-RANGE=SDR
        index-v1-a1.m3u8
        #EXT-X-STREAM-INF:BANDWIDTH=900000,RESOLUTION=852x480,CODECS="avc1.4d401e,mp4a.40.2"
        index-v2-a1.m3u8
        #EXT-X-I-FRAME-STREAM-INF:BANDWIDTH=100000,RESOLUTION=1920x1080,URI="iframes-v1-a1.m3u8"
    """.trimIndent()

    private val variant = """
        #EXTM3U
        #EXT-X-TARGETDURATION:10
        #EXTINF:10.0,
        seg-1.ts
        #EXTINF:8.0,
        https://other.cdn/seg-2.ts
    """.trimIndent()

    @Test
    fun `labels come from RESOLUTION only`() {
        val variants = QualityGuard.parseMaster(master, "https://cdn.example/hls/master.m3u8")
        assertEquals(2, variants.size)
        assertEquals(listOf("1080P", "480P"), variants.map { it.label })
        assertEquals(1920, variants[0].width)
        assertEquals(1080, variants[0].height)
        assertEquals(3338770L, variants[0].bandwidth)
        // The I-frame playlist is not a rendition the user can watch.
        assertFalse(variants.any { it.url.endsWith("iframes-v1-a1.m3u8") })
        assertEquals("https://cdn.example/hls/index-v1-a1.m3u8", variants[0].url)
    }

    @Test
    fun `an unknown resolution is never promoted to a quality claim`() {
        assertEquals("Originale", QualityGuard.labelFor(null))
        assertEquals("Originale", QualityGuard.labelFor(0))
        assertEquals("240P", QualityGuard.labelFor(240))
        assertEquals("360P", QualityGuard.labelFor(360))
        assertEquals("480P", QualityGuard.labelFor(480))
        assertEquals("720P", QualityGuard.labelFor(720))
        assertEquals("1080P", QualityGuard.labelFor(1080))
    }

    @Test
    fun `segments and durations are read from the media playlist`() {
        val segments = QualityGuard.parseSegmentUrls(variant, "https://cdn.example/hls/index-v2-a1.m3u8")
        assertEquals(2, segments.size)
        assertEquals("https://cdn.example/hls/seg-1.ts", segments[0])
        assertEquals("https://other.cdn/seg-2.ts", segments[1])
        assertEquals(listOf(10.0, 8.0), QualityGuard.parseDurations(variant))
    }

    @Test
    fun `default policy prefers the lightest watchable lane`() {
        val tracks = listOf(
            track("1080P", 1080, 800_000_000),
            track("720P", 720, 200_000_000),
            track("480P", 480, 92_000_000),
            track("360P", 360, 60_000_000)
        )
        assertEquals("480P", QualityGuard.pick(tracks, null).track.label)
        assertEquals("360P", QualityGuard.pick(tracks.filterNot { it.label == "480P" }, null).track.label)
        assertEquals("720P", QualityGuard.pick(tracks.filterNot { it.label in setOf("480P", "360P") }, null).track.label)
    }

    @Test
    fun `the fast lane guard fires when the measured size exceeds the ceiling`() {
        val bloated = track("480P", 480, 403L * 1024 * 1024, "https://cdn/480-heavy.m3u8")
        val light = track("360P", 360, 60L * 1024 * 1024, "https://cdn/360.m3u8")
        val tracks = listOf(track("1080P", 1080, 823_836_484), bloated, light)

        val decision = QualityGuard.pick(tracks, "480P")
        assertTrue(decision.downgraded)
        assertEquals("360P", decision.track.label)
        assertNotNull(decision.note)
        // The note is what the player screen shows; it must state the numbers.
        assertTrue(decision.note!!.contains("403 Mo"))
        assertTrue(decision.note.contains("360P"))
    }

    @Test
    fun `an exact fast lane under the ceiling is kept exactly as requested`() {
        val tracks = listOf(track("1080P", 1080, 800_000_000), track("480P", 480, 92L * 1024 * 1024))
        val decision = QualityGuard.pick(tracks, "480P")
        assertFalse(decision.downgraded)
        assertEquals("480P", decision.track.label)
        assertNull(decision.note)
    }

    @Test
    fun `720P and 1080P are never downgraded`() {
        val tracks = listOf(track("1080P", 1080, 900L * 1024 * 1024), track("720P", 720, 500L * 1024 * 1024), track("480P", 480, 92L * 1024 * 1024))
        assertEquals("1080P", QualityGuard.pick(tracks, "1080P").track.label)
        assertEquals("720P", QualityGuard.pick(tracks, "720P").track.label)
    }

    @Test
    fun `an unmeasured fast lane is played rather than silently replaced`() {
        val tracks = listOf(track("480P", 480, null), track("360P", 360, 60L * 1024 * 1024))
        val decision = QualityGuard.pick(tracks, "480P")
        assertEquals("480P", decision.track.label)
        assertFalse(decision.downgraded)
    }

    // The guard is only useful if it also covers the automatic choice: with no
    // requested quality (the default, since no screen sets one before the user
    // taps a chip) the automatic pick *is* the decision the user lives with.

    @Test
    fun `the guard also fires on the automatic choice`() {
        val tracks = listOf(
            track("1080P", 1080, 800L * 1024 * 1024),
            track("480P", 480, 403L * 1024 * 1024, "https://cdn/480-heavy.m3u8"),
            track("360P", 360, 60L * 1024 * 1024, "https://cdn/360.m3u8")
        )
        val decision = QualityGuard.pick(tracks, null)
        assertTrue(decision.downgraded)
        assertEquals("360P", decision.track.label)
        assertNotNull(decision.note)
        assertTrue(decision.note!!.contains("403 Mo"))
        // The ceiling is printed from the constant, so the note cannot drift.
        assertTrue(decision.note.contains("200 Mo"))
    }

    @Test
    fun `an automatic fast lane under the ceiling is kept`() {
        val tracks = listOf(
            track("1080P", 1080, 800L * 1024 * 1024),
            track("480P", 480, 92L * 1024 * 1024, "https://cdn/480.m3u8"),
            track("360P", 360, 60L * 1024 * 1024, "https://cdn/360.m3u8")
        )
        val decision = QualityGuard.pick(tracks, null)
        assertFalse(decision.downgraded)
        assertEquals("480P", decision.track.label)
        assertNull(decision.note)
    }

    @Test
    fun `an automatic unmeasured fast lane is kept and never guessed about`() {
        val tracks = listOf(track("480P", 480, null), track("360P", 360, 60L * 1024 * 1024))
        val decision = QualityGuard.pick(tracks, null)
        assertFalse(decision.downgraded)
        assertEquals("480P", decision.track.label)
    }

    @Test
    fun `the automatic policy never downgrades a 720P`() {
        val tracks = listOf(track("1080P", 1080, 900L * 1024 * 1024), track("720P", 720, 500L * 1024 * 1024))
        val decision = QualityGuard.pick(tracks, null)
        assertFalse(decision.downgraded)
        assertEquals("720P", decision.track.label)
    }

    @Test
    fun `a missing quality downgrades and says so instead of jumping up`() {
        val tracks = listOf(track("1080P", 1080, 800_000_000), track("720P", 720, 200_000_000))
        val decision = QualityGuard.pick(tracks, "480P")
        assertEquals("720P", decision.track.label)
        assertNotNull(decision.note)
        assertTrue(decision.note!!.contains("480P"))
    }

    @Test
    fun `mirror order puts vidmoly first and voe next`() {
        val voe = MirrorRef(host = "voe.sx", url = "https://voe.sx/e/abc", label = "VOE", language = Language.VF)
        val vidmoly = MirrorRef(host = "voembed.net", url = "https://voembed.net/embed-abc.html", label = "myTV", language = Language.VF)
        val streamtape = MirrorRef(host = "streamtape.com", url = "https://streamtape.com/e/abc", label = "Stape", language = Language.VF)
        val sibnet = MirrorRef(host = "video.sibnet.ru", url = "https://video.sibnet.ru/shell.php?videoid=1", label = "Sibnet", language = Language.VF)

        val ordered = MirrorResolver.order(listOf(streamtape, sibnet, voe, vidmoly))
        assertEquals(listOf("voembed.net", "voe.sx", "video.sibnet.ru", "streamtape.com"), ordered.map { it.host })
    }
}

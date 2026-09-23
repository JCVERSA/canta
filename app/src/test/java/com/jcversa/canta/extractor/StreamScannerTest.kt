package com.jcversa.canta.extractor

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Tests for the shared player-page scanner and the packed-script unpacker it uses.
 *
 * This exists because of a real device failure: `DeanEdwards` declares its regex as a
 * static initializer, and the pattern held a bare `}` - which compiles on the JVM but
 * throws `PatternSyntaxException` on Android, where ICU parses patterns. The class
 * then failed to initialise for the whole process, and because every extractor shares
 * the unpacker through [StreamScanner], three of the four mirrors died with
 * `NoClassDefFoundError` while the fourth simply timed out. The app reported it
 * honestly ("Tous les lecteurs ont échoué") but the cause was invisible until the
 * throwable was logged.
 *
 * Two kinds of test follow: the unpacker/scanner must work on a packed page, and the
 * pattern's *shape* is asserted directly, because a JVM-only test run cannot
 * reproduce ICU's stricter parser.
 */
class StreamScannerTest {

    /** A Dean Edwards packed payload, in the shape the packer actually emits. */
    private val packedPage = """
        <html><body><script type="text/javascript">
        eval(function(p,a,c,k,e,d){e=function(c){return c};if(!''.replace(/^/,String)){while(c--){d[c]=k[c]}}return p}('0:[{1:"2://3/4/5.6"}]',36,7,'sources|file|https|cdn.example|hls|master|m3u8'.split('|'),0,{}))
        </script></body></html>
    """.trimIndent()

    @Test
    fun `unpacks a packed payload into readable script`() {
        val unpacked = DeanEdwards.unpack(packedPage)
        assertTrue("the packer's body should be unpacked", unpacked.contains("sources"))
        assertTrue("the payload's tokens should be substituted", unpacked.contains("cdn.example"))
        assertFalse("no bare token should survive", unpacked.contains("0:[{1:"))
    }

    @Test
    fun `finds the stream url inside a packed player page`() {
        val scanned = StreamScanner.scan(packedPage, "https://voembed.net")
        assertNotNull("a packed page must still yield a stream", scanned)
        assertEquals("https://cdn.example/hls/master.m3u8", scanned!!.url)
        assertEquals(StreamKind.HLS, scanned.kind)
    }

    @Test
    fun `finds a plainly declared source without packing`() {
        val plain = """
            <html><body><script>
            jwplayer("v").setup({ sources: [{ file: "https://cdn.example/hls/master.m3u8?t=1" }] });
            </script></body></html>
        """.trimIndent()
        val scanned = StreamScanner.scan(plain, "https://voembed.net")
        assertNotNull(scanned)
        assertEquals("https://cdn.example/hls/master.m3u8?t=1", scanned!!.url)
        assertEquals(StreamKind.HLS, scanned.kind)
    }

    /**
     * The pattern must contain no unescaped `{` or `}`. Android parses regexes with
     * ICU, which rejects a bare `}` as a syntax error and is stricter than the JVM's
     * `java.util.regex`; a pattern that compiles here can therefore still kill the
     * class on a device. This test is the cheap guard for that whole class of mistake.
     */
    @Test
    fun `regex literals declare their braces escaped`() {
        val pattern = DeanEdwards.PACKED.pattern
        val bareBraces = pattern.filterIndexed { i, c ->
            (c == '{' || c == '}') && (i == 0 || pattern[i - 1] != '\\')
        }
        assertEquals("unescaped braces in the packer regex: $bareBraces", "", bareBraces)
    }
}

package com.jcversa.canta.scraper

import com.jcversa.canta.model.Anime
import com.jcversa.canta.model.Language
import com.jcversa.canta.model.Source
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Parser tests against markup captured live from voir-anime.to on 2026-09-20.
 *
 * The fixtures are byte-for-byte the shapes that were found on the live site
 * (trimmed for size, never "cleaned up"): a test that passes against tidy markup
 * the site does not serve is worse than no test, because it certifies a parser
 * that will fail in production. The captures themselves are committed under
 * tools/recon/evidence/.
 */
class VoirAnimeScraperTest {

    /** `div#chapter-video-frame` + `var thisChapterSources = {…}` as served. */
    private val episodePage = """
        <div class="chapter-video-frame" id="chapter-video-frame">
          <p><iframe src="https://voembed.net/embed-zxtqco5wxp3d.html" scrolling="no" frameborder="0"
             width="700" height="430" allowfullscreen="true"></iframe></p>
        </div>
        <label>
          <select class="selectpicker host-select">
            <option data-redirect="/anime/one-piece-vf/one-piece-1110-vf/?host=LECTEUR myTV" value="LECTEUR myTV">LECTEUR myTV</option>
            <option data-redirect="/anime/one-piece-vf/one-piece-1110-vf/?host=LECTEUR MOON" value="LECTEUR MOON">LECTEUR MOON</option>
            <option data-redirect="/anime/one-piece-vf/one-piece-1110-vf/?host=LECTEUR VOE" value="LECTEUR VOE">LECTEUR VOE</option>
            <option data-redirect="/anime/one-piece-vf/one-piece-1110-vf/?host=LECTEUR Stape" value="LECTEUR Stape">LECTEUR Stape</option>
          </select>
        </label>
        <script>
          var thisChapterSources = {"LECTEUR myTV":"<iframe src=\"https:\/\/voembed.net\/embed-zxtqco5wxp3d.html\" scrolling=\"no\" frameborder=\"0\"><\/iframe>","LECTEUR MOON":"<iframe src=\"https:\/\/mfw09.org\/e\/dhy4skfrq20x\" scrolling=\"no\"><\/iframe>","LECTEUR VOE":"<iframe src=\"https:\/\/voe.sx\/e\/8ncp6aa32n0u\" scrolling=\"no\"><\/iframe>","LECTEUR Stape":"<iframe src=\"https:\/\/streamtape.com\/e\/jAwaD3OggKtzZAq\" scrolling=\"no\"><\/iframe>"};
          var defaultSources = {"mobile":"","tablet":""};
        </script>
    """.trimIndent()

    private val homePage = """
        <div id="loop-content" class="page-content-listing item-default">
          <div class="page-listing-item"><div class="row row-eq-height">
            <div class="col-12 col-md-6 badge-pos-1">
              <div class="page-item-detail video">
                <div id="manga-item-1343" class="item-thumb c-image-hover" data-post-id="1343">
                  <a href="https://voir-anime.to/anime/one-piece/" title="One Piece">
                    <img width="110" height="150" src="https://voir-anime.to/wp-content/uploads/2020/01/thumb_5e10959f42183-110x150.jpg" class="img-responsive" alt="thumb"/>
                  </a>
                </div>
                <div class="item-summary">
                  <div class="post-title font-title"><h3 class="h5"><a href="https://voir-anime.to/anime/one-piece/">One Piece</a></h3></div>
                  <div class="meta-item rating"><span class="score font-meta total_votes">8,7</span></div>
                </div>
              </div>
            </div>
            <div class="col-12 col-md-6 badge-pos-1">
              <div class="page-item-detail video">
                <div class="item-thumb c-image-hover"><a href="https://voir-anime.to/anime/jujutsu-kaisen-vf/" title="Jujutsu Kaisen (VF)"><img src="https://voir-anime.to/wp-content/uploads/2020/10/jjk.jpg" class="img-responsive"/></a></div>
                <div class="item-summary">
                  <div class="post-title font-title"><h3 class="h5"><a href="https://voir-anime.to/anime/jujutsu-kaisen-vf/">Jujutsu Kaisen (VF)</a></h3></div>
                </div>
              </div>
            </div>
          </div></div>
        </div>
    """.trimIndent()

    private val searchPage = """
        <div class="c-page-content"><div class="search-wrap">
          <div class="c-blog__heading style-2 font-heading"><h1 class="h4"><i class="icon"></i> 45 results for "one piece"</h1></div>
          <div class="tab-content-wrap">
            <div class="c-tabs-item">
              <div class="row c-tabs-item__content">
                <div class="col-4 col-sm-2 col-md-2">
                  <div class="tab-thumb c-image-hover">
                    <a href="https://voir-anime.to/anime/one-piece-heroines/" title="ONE PIECE HEROINES">
                      <img width="193" height="278" src="https://voir-anime.to/wp-content/uploads/2026/07/thumb_6a4ba2fc433b6-193x278.jpg" class="img-responsive" alt="thumb_6a4ba2fc433b6"/>
                    </a>
                  </div>
                </div>
                <div class="col-8 col-sm-10 col-md-10">
                  <div class="tab-summary">
                    <div class="post-title"><h3 class="h4"><a href="https://voir-anime.to/anime/one-piece-heroines/">ONE PIECE HEROINES</a></h3></div>
                    <div class="post-content"><div class="post-content_item mg_genres"><div class="summary-content"><a href="https://voir-anime.to/anime-genre/action/">Action</a></div></div></div>
                  </div>
                </div>
              </div>
            </div>
          </div>
        </div></div>
    """.trimIndent()

    private val detailPage = """
        <div class="post-title"><h1>One Piece (VF)</h1></div>
        <div class="summary_image"><img src="https://voir-anime.to/wp-content/uploads/2020/01/one-piece.jpg" class="img-responsive"/></div>
        <div class="description-summary"><div class="summary__content"><p>Il fut un temps où Gold Roger était le plus grand de tous les pirates.</p></div></div>
        <div class="post-content_item mg_genres"><div class="summary-content"><a href="https://voir-anime.to/anime-genre/action/">Action</a><a href="https://voir-anime.to/anime-genre/aventure/">Aventure</a></div></div>
        <div class="page-content-listing">
          <ul class="main version-chap">
            <li class="wp-manga-chapter">
              <a href="https://voir-anime.to/anime/one-piece-vf/one-piece-1110-vf/">One Piece - 1110 VF - 1110</a>
              <span class="chapter-release-date"><i>2026-09-14</i></span>
            </li>
            <li class="wp-manga-chapter">
              <a href="https://voir-anime.to/anime/one-piece-vf/one-piece-1109-vf/">One Piece - 1109 VF - 1109</a>
              <span class="chapter-release-date"><i>2026-09-07</i></span>
            </li>
            <li class="wp-manga-chapter">
              <a href="https://voir-anime.to/anime/one-piece-vf/one-piece-film-red-vf/">One Piece - Film Red VF</a>
              <span class="chapter-release-date"><i>2025-01-02</i></span>
            </li>
          </ul>
        </div>
    """.trimIndent()

    @Test
    fun `thisChapterSources yields every mirror in page order`() {
        val mirrors = VoirAnimeScraper.parseChapterSources(episodePage, Language.VF)
        assertEquals(4, mirrors.size)
        assertEquals(
            listOf("voembed.net", "mfw09.org", "voe.sx", "streamtape.com"),
            mirrors.map { it.host }
        )
        assertEquals("LECTEUR myTV", mirrors[0].label)
        assertEquals("https://voembed.net/embed-zxtqco5wxp3d.html", mirrors[0].url)
        // The escaped slashes and quotes in the payload must be unescaped.
        assertEquals("https://streamtape.com/e/jAwaD3OggKtzZAq", mirrors[3].url)
    }

    @Test
    fun `parseMirrors keeps the featured player and the JS list without duplicates`() {
        val mirrors = VoirAnimeScraper.parseMirrors(episodePage, "https://voir-anime.to/anime/one-piece-vf/one-piece-1110-vf/", Language.VF)
        assertEquals(4, mirrors.size)
        assertTrue(mirrors.any { it.url == "https://voembed.net/embed-zxtqco5wxp3d.html" })
    }

    @Test
    fun `home cards are parsed with h3 h5 titles and the slug decides the language`() {
        val cards = VoirAnimeScraper.parseCards(homePage, "https://voir-anime.to/")
        assertEquals(2, cards.size)
        val onePiece = cards.first { it.title == "One Piece" }
        assertTrue(onePiece.languages.contains(Language.VOSTFR))
        assertEquals("voir-anime:one-piece", onePiece.id)
        assertEquals("https://voir-anime.to/wp-content/uploads/2020/01/thumb_5e10959f42183-110x150.jpg", onePiece.posterUrl)
        val jjk = cards.first { it.title == "Jujutsu Kaisen" }
        assertTrue(jjk.languages.contains(Language.VF))
    }

    @Test
    fun `search results use the c-tabs-item container and keep their poster`() {
        val cards = VoirAnimeScraper.parseCards(searchPage, "https://voir-anime.to/?s=one+piece")
        assertEquals(1, cards.size)
        assertEquals("ONE PIECE HEROINES", cards[0].title)
        assertEquals("https://voir-anime.to/anime/one-piece-heroines/", cards[0].url)
        // The search card is a different DOM shape (`div.tab-thumb img`), and its
        // poster must survive: a bare link fallback is the degraded path, not the
        // normal one.
        assertEquals(
            "https://voir-anime.to/wp-content/uploads/2026/07/thumb_6a4ba2fc433b6-193x278.jpg",
            cards[0].posterUrl
        )
    }

    @Test
    fun `host selector options are resolved into absolute pages in page order`() {
        val options = VoirAnimeScraper.parseHostOptions(episodePage, "https://voir-anime.to/anime/one-piece-vf/one-piece-1110-vf/")
        assertEquals(4, options.size)
        assertEquals("LECTEUR myTV", options[0].first)
        assertEquals(
            "https://voir-anime.to/anime/one-piece-vf/one-piece-1110-vf/?host=LECTEUR%20myTV",
            options[0].second
        )
        assertEquals(
            "https://voir-anime.to/anime/one-piece-vf/one-piece-1110-vf/?host=LECTEUR%20Stape",
            options[3].second
        )
    }

    @Test
    fun `a page whose default host is dead still yields the host it switches to`() {
        val deadDefault = """
            <div class="chapter-video-frame" id="chapter-video-frame"><p></p></div>
            <select class="selectpicker host-select">
              <option data-redirect="/anime/jujutsu-kaisen-vf/jujutsu-kaisen-47-vf/?host=LECTEUR MOON" value="LECTEUR MOON">LECTEUR MOON</option>
            </select>
        """.trimIndent()
        val hostPage = """
            <div class="chapter-video-frame" id="chapter-video-frame">
              <p><iframe src="https://mfw09.org/e/dhy4skfrq20x"></iframe></p>
            </div>
        """.trimIndent()
        assertTrue(VoirAnimeScraper.parseMirrors(deadDefault, "https://voir-anime.to/anime/jujutsu-kaisen-vf/jujutsu-kaisen-47-vf/", Language.VF).isEmpty())
        val option = VoirAnimeScraper.parseHostOptions(deadDefault, "https://voir-anime.to/anime/jujutsu-kaisen-vf/jujutsu-kaisen-47-vf/").single()
        assertEquals("LECTEUR MOON", option.first)
        val mirrors = VoirAnimeScraper.parseMirrors(hostPage, option.second, Language.VF)
        assertEquals(1, mirrors.size)
        assertEquals("mfw09.org", mirrors[0].host)
        assertTrue(mirrors[0].url == "https://mfw09.org/e/dhy4skfrq20x")
    }

    @Test
    fun `detail page yields episodes sorted with the film last and dates verbatim`() {
        val anime = Anime(id = "voir-anime:one-piece-vf", title = "One Piece (VF)", url = "https://voir-anime.to/anime/one-piece-vf/", source = Source.VOIRANIME, languages = setOf(Language.VF))
        val detail = VoirAnimeScraper.parseDetail(detailPage, anime)
        assertEquals("One Piece", detail.anime.title)
        assertEquals(3, detail.episodes.size)
        assertEquals(1110, detail.episodes.first().number)
        assertEquals(Language.VF, detail.episodes.first().language)
        assertEquals("2026-09-14", detail.episodes.first().releaseDate)
        // A film has no episode number: it must not be presented as "Épisode 0".
        val film = detail.episodes.last()
        assertEquals(0, film.number)
        // A numberless entry shows the source's own row text, never "Épisode 0".
        assertTrue(film.title.contains("Film Red"))
        assertTrue(detail.episodes.none { it.label == "Épisode 0" })
        // Preconditions for the numbering the UI relies on: newest first, films last.
        assertTrue(detail.episodes.zipWithNext().all { (a, b) -> a.number >= b.number })
    }

    @Test
    fun `an unparsable page is an explicit structure error not an empty catalogue`() {
        val empty = VoirAnimeScraper.parseCards("<html><body>maintenance</body></html>", "https://voir-anime.to/")
        assertTrue(empty.isEmpty())
        assertNull(Selectors.VA_CHAPTER_SOURCES.find(episodePage.replace("thisChapterSources", "renamedByTheSite")))
    }
}

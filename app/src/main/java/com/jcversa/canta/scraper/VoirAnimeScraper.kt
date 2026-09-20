package com.jcversa.canta.scraper

import com.jcversa.canta.model.Anime
import com.jcversa.canta.model.AnimeDetail
import com.jcversa.canta.model.Episode
import com.jcversa.canta.model.Language
import com.jcversa.canta.model.MirrorRef
import com.jcversa.canta.model.Source
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.jsoup.nodes.Document
import org.jsoup.nodes.Element

/**
 * voir-anime.to — the primary source, VF-first.
 *
 * Confirmed live on 2026-09-20 (see RECONNAISSANCE.md §2 and
 * `tools/recon/evidence/va-*.html`); the last confirmation before this build is
 * nebula-p's audit §8.9, which is what the selector set below is ported from:
 *
 *  - WordPress "Madara" theme, no Cloudflare challenge from datacenter IPs.
 *  - A VF entry is *structural*: its slug ends with `-vf` and its title ends
 *    with " (VF)". The French dub is therefore guaranteed by construction, not
 *    by a label that could be wrong.
 *  - Catalogue cards: `div.page-item-detail` → `div.item-thumb a[href]` +
 *    `div.post-title h3` (home uses `h3.h5`, search results `h3.h4`).
 *  - Detail page: `div.post-title h1`, `div.summary_image img`,
 *    `div.description-summary div.summary__content`, episodes in
 *    `div.page-content-listing li.wp-manga-chapter`.
 *  - Episode page: player in `div#chapter-video-frame iframe` plus the full
 *    mirror list in `var thisChapterSources = {"LECTEUR myTV": "<iframe …>"}`.
 */
object VoirAnimeScraper {

    const val ORIGIN = "https://voir-anime.to"

    private fun headers(referer: String = "$ORIGIN/") = Http.htmlHeaders(referer)

    // ---------------------------------------------------------------- catalogue

    /**
     * One catalogue page. [page] 1 is the home page, later pages use Madara's
     * `/page/N/` route. The `?filter=dubbed|subbed` views exist (verified 200),
     * but paginating *within* a filtered view is not something this app relies
     * on: the language filter is applied here, from each entry's own slug, so a
     * card is never shown as VF because a tab said so.
     */
    suspend fun catalogue(page: Int = 1, language: Language? = null): List<Anime> = withContext(Dispatchers.IO) {
        val url = if (page <= 1) "$ORIGIN/" else "$ORIGIN/page/$page/"
        val html = fetch(url)
        val cards = parseCards(html, url)
        if (cards.isEmpty()) throw StructureChangedException("catalogue", url)
        filterByLanguage(cards, language)
    }

    suspend fun search(query: String): List<Anime> = withContext(Dispatchers.IO) {
        val url = "$ORIGIN/?s=" + java.net.URLEncoder.encode(query, "UTF-8")
        val html = fetch(url)
        val cards = parseCards(html, url)
        // An empty result set is legitimate for a search; an unparsable page is not.
        if (cards.isEmpty() && !html.contains("/anime/")) throw StructureChangedException("recherche", url)
        cards
    }

    suspend fun detail(anime: Anime): AnimeDetail = withContext(Dispatchers.IO) {
        val html = fetch(anime.url)
        val detail = parseDetail(html, anime)
        if (detail.episodes.isEmpty() && detail.anime.title.isBlank()) {
            throw StructureChangedException("fiche série", anime.url)
        }
        detail
    }

    /**
     * The entry that actually carries the requested language, or null when the
     * source simply does not have it. Returning null is a feature: the UI then
     * prints "VOSTFR non disponible" instead of opening a VF page and calling
     * it VOSTFR (the exact mistake nebula-p's audit §8.6 documents).
     */
    suspend fun resolveLanguageEntry(anime: Anime, wanted: Language): Anime? = withContext(Dispatchers.IO) {
        if (anime.languages.contains(wanted)) return@withContext anime
        val root = anime.title.trim()
        if (root.isEmpty()) return@withContext null
        val results = try {
            search(root)
        } catch (_: Exception) {
            return@withContext null
        }
        val wantedVf = wanted == Language.VF
        results.firstOrNull { candidate ->
            candidate.languages.contains(wanted) && normalizeTitle(candidate.title) == normalizeTitle(root)
        } ?: results.firstOrNull { candidate ->
            // Same franchise, slightly different title (season markers, "VF" suffix).
            candidate.languages.contains(wanted) &&
                (normalizeTitle(candidate.title).contains(normalizeTitle(root)) ||
                    normalizeTitle(root).contains(normalizeTitle(candidate.title)))
        } ?: results.firstOrNull { it.languages.contains(wanted) && it.url.trimEnd('/').endsWith("-vf") == wantedVf }
    }

    // ------------------------------------------------------------------ parsing

    /** Catalogue cards. Throws nothing: callers decide whether an empty list is fatal. */
    fun parseCards(html: String, baseUrl: String): List<Anime> {
        val doc = Selectors.parse(html, baseUrl)
        // Home/catalogue uses `div.page-item-detail`; the search page uses a
        // different card (`div.row.c-tabs-item__content`). Trying both keeps the
        // search results' posters and ratings instead of falling back to bare links.
        val cards = doc.select(Selectors.VA_CARD).ifEmpty { doc.select(Selectors.VA_CARD_SEARCH) }
        val out = LinkedHashMap<String, Anime>()
        for (card in cards) {
            val anchor = card.selectFirst(Selectors.VA_CARD_TITLE_SEARCH)
                ?: card.selectFirst(Selectors.VA_CARD_TITLE_HOME)
                ?: card.selectFirst(Selectors.VA_CARD_LINK)
                ?: continue
            val href = anchor.absUrl("href").ifBlank { anchor.attr("href") }
            val entry = buildAnime(href, card.selectFirst(Selectors.VA_CARD_IMAGE), anchor.text(), card) ?: continue
            out[entry.id] = entry
        }
        if (out.isNotEmpty()) return out.values.toList()
        return parseCardsByLinks(doc, html)
    }

    /**
     * Structural fallback: Madara markup moves between theme releases, but the
     * `/anime/<slug>/` URL shape has held for years. This keeps the catalogue
     * alive with less metadata rather than throwing a structure error.
     */
    private fun parseCardsByLinks(doc: Document, html: String): List<Anime> {
        val out = LinkedHashMap<String, Anime>()
        for (element in doc.select("a[href*=/anime/]")) {
            val href = element.absUrl("href").ifBlank { element.attr("href") }
            val match = Selectors.VA_ANIME_LINK.find(href) ?: continue
            if (match.value.trimEnd('/') != href.trimEnd('/')) continue
            val title = element.attr("title").ifBlank { element.text() }.trim()
            if (title.length < 2 || title.length > 160) continue
            val entry = buildAnime(href, null, title, null) ?: continue
            out.putIfAbsent(entry.id, entry)
            if (out.size >= 60) break
        }
        return out.values.toList()
    }

    private fun buildAnime(
        href: String,
        image: Element?,
        rawTitle: String,
        card: Element?
    ): Anime? {
        val match = Selectors.VA_ANIME_LINK.find(href) ?: return null
        val slug = match.groupValues[1]
        if (slug.isBlank() || slug == "anime") return null
        val canonical = "$ORIGIN/anime/$slug/"
        val (title, isVf) = splitVfSuffix(if (rawTitle.isBlank()) slug.replace('-', ' ') else rawTitle, canonical)
        if (title.isBlank()) return null
        val rating = card?.selectFirst("span.score, div.score, span.total_votes")
            ?.text()?.replace(',', '.')?.toDoubleOrNull()
        return Anime(
            id = "${Source.VOIRANIME.id}:$slug",
            title = title,
            url = canonical,
            source = Source.VOIRANIME,
            // Honest by construction: the slug decides, nothing else.
            languages = setOf(if (isVf) Language.VF else Language.VOSTFR),
            posterUrl = image?.absSrc() ?: card?.selectFirst("img")?.absSrc(),
            rating = rating
        )
    }

    fun parseDetail(html: String, anime: Anime): AnimeDetail {
        val doc = Selectors.parse(html, anime.url)
        val title = doc.selectFirst(Selectors.VA_DETAIL_TITLE)?.text()?.trim()
            ?.takeIf { it.isNotEmpty() }
            ?: doc.title().substringBefore('|').trim()
        val cover = doc.selectFirst(Selectors.VA_DETAIL_COVER)?.absSrc()
        val synopsis = (doc.selectFirst(Selectors.VA_DETAIL_SYNOPSIS)
            ?: doc.selectFirst(Selectors.VA_DETAIL_SYNOPSIS_FALLBACK))
            ?.text()?.trim()?.takeIf { it.isNotEmpty() }
        val genres = doc.select(Selectors.VA_DETAIL_GENRE).mapNotNull { it.text().trim().takeIf { g -> g.isNotEmpty() } }
            .distinct()
        val (cleanTitle, isVf) = splitVfSuffix(title, anime.url)
        val language = if (isVf) Language.VF else anime.languages.firstOrNull() ?: Language.VOSTFR
        val episodes = parseEpisodes(doc, anime, cleanTitle, language, cover)
        val enriched = anime.copy(
            title = cleanTitle.ifBlank { anime.title },
            posterUrl = cover ?: anime.posterUrl,
            synopsis = synopsis,
            genres = genres,
            episodeCount = episodes.count { it.number > 0 }.takeIf { it > 0 } ?: anime.episodeCount,
            languages = setOf(language)
        )
        return AnimeDetail(
            anime = enriched,
            synopsis = synopsis,
            posterUrl = cover,
            genres = genres,
            episodeCount = enriched.episodeCount,
            episodes = episodes
        )
    }

    private fun parseEpisodes(
        doc: Document,
        anime: Anime,
        seriesTitle: String,
        language: Language,
        poster: String?
    ): List<Episode> {
        val rows = doc.select("${Selectors.VA_EPISODE_CONTAINER} ${Selectors.VA_EPISODE_ROW}")
            .ifEmpty { doc.select(Selectors.VA_EPISODE_ROW) }
        val out = LinkedHashMap<String, Episode>()
        for (row in rows) {
            val anchor = row.selectFirst(Selectors.VA_EPISODE_LINK) ?: continue
            val href = anchor.absUrl("href").ifBlank { anchor.attr("href") }
            if (href.isBlank()) continue
            val slug = href.trimEnd('/').substringAfterLast('/')
            val number = Selectors.VA_EPISODE_SLUG_NUMBER.find(slug)?.groupValues?.get(1)?.toIntOrNull()
            val isMovie = Selectors.VA_EPISODE_SLUG_MOVIE.containsMatchIn(slug)
            if (number == null && !isMovie) continue
            // The slug's own suffix is the language of this row; it can differ
            // from the series entry when a show mixes variants.
            val rowLanguage = when {
                slug.endsWith("-vf", true) -> Language.VF
                slug.endsWith("-vostfr", true) -> Language.VOSTFR
                else -> language
            }
            val rawText = anchor.text().trim()
            val label = when {
                number != null && number > 0 -> "Épisode $number"
                slug.contains("film") || slug.contains("movie") -> "Film"
                else -> "OAV"
            }
            val episode = Episode(
                number = number ?: 0,
                title = rawText.ifBlank { "$seriesTitle — $label" },
                url = href,
                source = Source.VOIRANIME,
                language = rowLanguage,
                releaseDate = row.selectFirst(Selectors.VA_EPISODE_DATE)?.text()?.trim()?.takeIf { it.isNotEmpty() },
                seriesId = anime.id,
                seriesTitle = seriesTitle.ifBlank { anime.title },
                seriesUrl = anime.url,
                posterUrl = poster
            )
            out.putIfAbsent(episode.url, episode)
        }
        // Newest first (the source's own order), films and OAVs last: on a
        // 1100-episode series the episode the user wants is at the top, and the
        // two entries without a number cannot interleave into the list.
        return out.values.sortedWith(compareBy({ it.number == 0 }, { -it.number }))
    }

    // ------------------------------------------------------------------ mirrors

    suspend fun mirrors(episode: Episode): List<MirrorRef> = withContext(Dispatchers.IO) {
        val html = fetch(episode.url)
        val direct = parseMirrors(html, episode.url, episode.language)
        if (direct.isNotEmpty()) return@withContext direct

        // Some episodes (verified: `jujutsu-kaisen-47-vf`) ship no iframe at all
        // and only expose the host switcher. Every `?host=` page renders a
        // different player, so walk them in the page's own order and stop at the
        // first page that yields a mirror — the site's failover, not an invented
        // one. Bounded work: at most one extra page fetch per host, sequential.
        for ((label, url) in parseHostOptions(html, episode.url)) {
            val page = runCatching { fetch(url) }.getOrNull() ?: continue
            val found = parseMirrors(page, url, episode.language)
            if (found.isNotEmpty()) return@withContext found.map { it.copy(label = label) }
        }
        throw StructureChangedException("page épisode (aucun lecteur, sélecteur d'hôte inclus)", episode.url)
    }

    /**
     * `select.host-select option[data-redirect]` → `label to absolute page URL`.
     *
     * The redirect is relative (`?host=<label>` or `/anime/<slug>/<ep>/?host=<label>`),
     * and the label itself can contain spaces, so it is resolved — and only then
     * percent-encoded — against the episode page URL.
     */
    fun parseHostOptions(html: String, episodeUrl: String): List<Pair<String, String>> {
        val doc = Selectors.parse(html, episodeUrl)
        return doc.select(Selectors.VA_HOST_OPTION).mapNotNull { option ->
            val label = option.text().trim().ifBlank { option.attr("value").trim() }
            val redirect = option.attr("data-redirect").trim()
            if (label.isBlank() || redirect.isBlank()) return@mapNotNull null
            val absolute = absoluteHostPage(episodeUrl, redirect) ?: return@mapNotNull null
            label to absolute
        }.distinctBy { it.second }
    }

    /**
     * Builds the absolute host page without handing the label to `java.net.URI`:
     * the real `data-redirect` values contain spaces (`?host=LECTEUR MOON`), which
     * a URI parser rejects — and a rejected redirect is a silently lost mirror.
     */
    private fun absoluteHostPage(episodeUrl: String, redirect: String): String? = when {
        redirect.startsWith("http") -> redirect
        redirect.startsWith("?") -> (episodeUrl.substringBefore('?').substringBefore('#') + redirect)
        redirect.startsWith("/") -> {
            val uri = runCatching { java.net.URI(episodeUrl) }.getOrNull() ?: return null
            val authority = uri.rawAuthority ?: return null
            "${uri.scheme}://$authority$redirect"
        }
        else -> {
            val base = episodeUrl.substringBeforeLast('/', "") + "/"
            runCatching { java.net.URI(base).resolve(redirect).toString() }.getOrNull() ?: (base + redirect)
        }
    }.replace(" ", "%20")

    fun parseMirrors(html: String, episodeUrl: String, language: Language): List<MirrorRef> {
        val out = LinkedHashMap<String, MirrorRef>()
        // 1. The full mirror list, in the page's own order and with its labels.
        for (mirror in parseChapterSources(html, language)) out.putIfAbsent(mirror.url, mirror)
        // 2. The featured player iframe (always present, even when the JS list is not).
        val doc = Selectors.parse(html, episodeUrl)
        val frame = doc.selectFirst(Selectors.VA_PLAYER_FRAME) ?: doc.selectFirst(Selectors.VA_PLAYER_ANY_IFRAME)
        val src = frame?.absUrl("src")?.ifBlank { frame.attr("src") }
        if (!src.isNullOrBlank() && src.startsWith("http")) {
            out.putIfAbsent(src, MirrorRef(host = hostOf(src), url = src, label = "Lecteur principal", language = language))
        }
        // 3. Last resort: any embed URL mentioned in the page source.
        if (out.isEmpty()) {
            Selectors.VA_EMBED_URL.find(html)?.value?.let { url ->
                out[url] = MirrorRef(host = hostOf(url), url = url, label = "Lecteur", language = language)
            }
        }
        return out.values.toList()
    }

    /**
     * Parses `var thisChapterSources = {"LECTEUR myTV":"<iframe src=\"…\">", …}`
     * — the mirror list as the site itself builds it. Payload strings contain
     * escaped quotes, so they are read with an escape-aware scan rather than a
     * naive split (a naive regex silently returns nothing, which is how the
     * first reconnaissance pass reported "no mirrors").
     */
    fun parseChapterSources(html: String, language: Language): List<MirrorRef> {
        val block = Selectors.VA_CHAPTER_SOURCES.find(html)?.groupValues?.get(1) ?: return emptyList()
        val entry = Regex(""""([^"]+)"\s*:\s*"((?:[^"\\]|\\.)*)"""")
        val out = LinkedHashMap<String, MirrorRef>()
        for (match in entry.findAll(block)) {
            val label = match.groupValues[1].trim()
            val payload = match.groupValues[2].replace("\\\"", "\"").replace("\\/", "/")
            val src = Regex("""src="([^"]+)"""").find(payload)?.groupValues?.get(1) ?: continue
            if (!src.startsWith("http")) continue
            out.putIfAbsent(src, MirrorRef(host = hostOf(src), url = src, label = label, language = language))
        }
        return out.values.toList()
    }

    // ------------------------------------------------------------------ helpers

    /** `https://voembed.net/embed-x.html` → `voembed.net`. */
    fun hostOf(url: String): String = try {
        java.net.URI(url).host ?: url
    } catch (_: Exception) {
        url
    }

    private fun filterByLanguage(cards: List<Anime>, language: Language?): List<Anime> =
        if (language == null) cards else cards.filter { it.languages.contains(language) }

    private fun normalizeTitle(raw: String): String = raw.lowercase()
        .replace(Regex("""\s*\((vf|vostfr)\)"""), "")
        .replace(Regex("""[^a-z0-9]+"""), " ")
        .trim()

    private suspend fun fetch(url: String): String {
        val response = Http.getAsync(url, headers())
        if (response.code == 403 || response.code == 503) {
            throw ScrapeException("voir-anime.to a répondu ${response.code} sur $url (Cloudflare/anti-bot)")
        }
        if (!response.isOk) throw ScrapeException("voir-anime.to a répondu ${response.code} sur $url")
        return response.body
    }
}

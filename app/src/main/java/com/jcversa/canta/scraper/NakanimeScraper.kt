package com.jcversa.canta.scraper

import com.jcversa.canta.model.Anime
import com.jcversa.canta.model.AnimeDetail
import com.jcversa.canta.model.Episode
import com.jcversa.canta.model.Language
import com.jcversa.canta.model.MirrorRef
import com.jcversa.canta.model.Source
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject

/**
 * nakanime.tv — the VOSTFR fallback source.
 *
 * The three catalogue layers were confirmed live on 2026-09-20
 * (RECONNAISSANCE.md §3, evidence `tools/recon/evidence/nk-*.json`):
 *
 *  1. **Catalogue / search** — `/api/catalog/search`, a JSON body XOR-encrypted
 *     with a key derived from the request path. The public HTML pages are a
 *     React shell (`<div id="seo-content">` and no card markup), so there is
 *     nothing to parse with Jsoup there; the API *is* the catalogue layer.
 *  2. **Detail** — the same API payload carries title, synopsis, poster,
 *     genres, episode count and the declared languages.
 *  3. **Episodes** — the server-rendered episode page
 *     (`/anime/<id>/season/<s>/episode/<n>`) embeds
 *     `{"animeId":…,"seasons":[{"number":…,"episodes":[{"number":…}]}]}`.
 *     `/api/anime/<id>/episodes` is the fallback when that script moves.
 *  4. **Mirrors** — `POST /api/sources/anime` returns the player list, each
 *     entry carrying its own language label.
 *
 * Honesty note that matters in the UI: nakanime's per-mirror language labels
 * are known to be wrong sometimes (nebula-p audit §8.6 — a player labelled VF
 * that was actually VOSTFR). Canta therefore treats nakanime languages as
 * *declared by the source*, and `MirrorResult.source` lets the UI say so
 * instead of claiming a verified language.
 */
object NakanimeScraper {

    const val ORIGIN = "https://nakanime.tv"
    private const val XOR_MAGIC = "nkapiv1"
    private const val SEARCH_PATH = "/api/catalog/search"
    private const val SOURCES_PATH = "/api/sources/anime"

    private fun headers() = Http.htmlHeaders("$ORIGIN/")

    // ------------------------------------------------------------------- crypto

    /** 32-byte XOR key for a request path — the path must match byte-for-byte. */
    fun deriveKey(pathWithQuery: String): ByteArray {
        val material = XOR_MAGIC + pathWithQuery
        val key = ByteArray(32)
        for (v in 0 until 32) {
            var g = 0
            for (ch in material) {
                g = (g * 31 + ch.code + v) and 0xFF
            }
            key[v] = g.toByte()
        }
        return key
    }

    fun decode(bytes: ByteArray, pathWithQuery: String): String {
        val key = deriveKey(pathWithQuery)
        val out = ByteArray(bytes.size)
        for (i in bytes.indices) {
            out[i] = (bytes[i].toInt() xor key[i % key.size].toInt()).toByte()
        }
        return out.toString(Charsets.UTF_8)
    }

    // ---------------------------------------------------------------- catalogue

    suspend fun search(query: String): List<Anime> = withContext(Dispatchers.IO) {
        val path = "$SEARCH_PATH?q=${enc(query.trim())}&sort=relevance&page=1&per_page=32"
        parseCatalogue(fetchDecoded(path), path)
    }

    /**
     * The catalogue layer. Uses the API's own sort; an empty answer is not an
     * error (the UI then simply shows the primary source).
     */
    suspend fun catalogue(page: Int = 1): List<Anime> = withContext(Dispatchers.IO) {
        val path = "$SEARCH_PATH?q=&sort=trending&page=$page&per_page=24"
        runCatching { parseCatalogue(fetchDecoded(path), path) }.getOrDefault(emptyList())
    }

    suspend fun detail(anime: Anime): AnimeDetail = withContext(Dispatchers.IO) {
        val id = animeIdOf(anime.url) ?: throw ScrapeException("Identifiant nakanime illisible: ${anime.url}")
        val seasons = seasonIndex(id)
        val episodes = seasons.flatMap { season ->
            season.episodes.mapNotNull { ref -> episodeFrom(anime, season.number, ref) }
        }
        if (episodes.isEmpty()) throw StructureChangedException("saisons nakanime", anime.url)
        val episodeCount = episodes.count { it.number > 0 }.takeIf { it > 0 } ?: anime.episodeCount
        AnimeDetail(
            anime = anime.copy(episodeCount = episodeCount),
            synopsis = anime.synopsis,
            posterUrl = anime.posterUrl,
            genres = anime.genres,
            episodeCount = episodeCount,
            episodes = episodes
        )
    }

    /**
     * Mirrors for one episode, each with the language the source declares for it.
     * The episode id comes from the seasons index when it is present, and from
     * the episode page's `data-episode-id` otherwise (the two disagree in
     * practice: the embedded seasons script frequently omits ids).
     */
    suspend fun mirrors(anime: Anime, season: Int, episodeNumber: Int): List<MirrorRef> = withContext(Dispatchers.IO) {
        val id = animeIdOf(anime.url) ?: return@withContext emptyList()
        val episodeId = seasonIndex(id)
            .firstOrNull { it.number == season }
            ?.episodes?.firstOrNull { it.number == episodeNumber }?.id
            ?: episodePageId(id, season, episodeNumber)
            ?: return@withContext emptyList()
        val payload = JSONObject().apply {
            put("anime_id", id)
            put("episode_id", episodeId)
            put("turnstile_token", "")
        }.toString()
        val response = Http.postJsonAsync("$ORIGIN$SOURCES_PATH", payload, headers())
        if (!response.isOk) throw ScrapeException("nakanime /sources a répondu ${response.code}")
        val decoded = decode(response.bytes, SOURCES_PATH)
        parseSources(decoded)
    }

    fun parseSources(decoded: String): List<MirrorRef> {
        val root = runCatching { JSONObject(decoded) }.getOrNull()
        val array: JSONArray = when {
            root != null -> root.optJSONArray("data") ?: root.optJSONArray("sources") ?: JSONArray()
            else -> runCatching { JSONArray(decoded) }.getOrDefault(JSONArray())
        }
        val out = LinkedHashMap<String, MirrorRef>()
        for (i in 0 until array.length()) {
            val item = array.optJSONObject(i) ?: continue
            val url = firstString(item, "url", "link", "file", "source", "player_url") ?: continue
            if (!url.startsWith("http")) continue
            val label = firstString(item, "host", "player", "name", "label") ?: VoirAnimeScraper.hostOf(url)
            val language = Language.fromTag(firstString(item, "language", "lang")) ?: Language.VOSTFR
            out.putIfAbsent(url, MirrorRef(host = label, url = url, label = label, language = language))
        }
        return out.values.toList()
    }

    // -------------------------------------------------------------- season index

    data class SeasonEpisode(val number: Int, val title: String?, val id: Int?, val isFiller: Boolean)

    data class Season(val number: Int, val name: String?, val episodes: List<SeasonEpisode>)

    /** Seasons of a series: embedded script first, encrypted API second. */
    suspend fun seasonIndex(animeId: Int): List<Season> = withContext(Dispatchers.IO) {
        runCatching {
            val page = Http.getAsync("$ORIGIN/anime/$animeId/season/1/episode/1", headers())
            if (page.isOk) parseSeasonsScript(page.body) else emptyList()
        }.getOrDefault(emptyList()).ifEmpty {
            runCatching { seasonsFromApi(animeId) }.getOrDefault(emptyList())
        }
    }

    /**
     * `{"animeId":16,"seasons":[{"number":1,"name":"East Blue","episodes":[… ]}]}`
     * lives in one of the page's `<script>` tags.
     */
    fun parseSeasonsScript(html: String): List<Season> {
        val doc = Selectors.parse(html, ORIGIN)
        for (script in doc.select("script")) {
            val body = script.data().trim()
            if (!body.contains("\"animeId\"") || !body.contains("\"seasons\"")) continue
            runCatching {
                val root = JSONObject(body)
                val seasons = root.optJSONArray("seasons") ?: return@runCatching emptyList()
                return parseSeasons(seasons)
            }
        }
        return emptyList()
    }

    private fun parseSeasons(seasons: JSONArray): List<Season> {
        val out = mutableListOf<Season>()
        for (i in 0 until seasons.length()) {
            val season = seasons.optJSONObject(i) ?: continue
            val number = season.optInt("number", i + 1)
            val episodes = mutableListOf<SeasonEpisode>()
            val array = season.optJSONArray("episodes") ?: JSONArray()
            for (j in 0 until array.length()) {
                val ep = array.optJSONObject(j) ?: continue
                val epNumber = ep.optInt("number", j + 1)
                if (epNumber <= 0) continue
                episodes += SeasonEpisode(
                    number = epNumber,
                    title = ep.optString("title").takeIf { it.isNotBlank() },
                    id = if (ep.isNull("id")) null else ep.optInt("id").takeIf { it > 0 },
                    isFiller = ep.optBoolean("isFiller", false)
                )
            }
            out += Season(
                number = number,
                name = season.optString("name").takeIf { it.isNotBlank() },
                // Positional indexing must not depend on the DOM/JSON order: the
                // player's own episode numbering is ascending (audit §8.2).
                episodes = episodes.distinctBy { it.number }.sortedBy { it.number }
            )
        }
        return out.sortedBy { it.number }
    }

    private suspend fun seasonsFromApi(animeId: Int): List<Season> {
        val path = "/api/anime/$animeId/episodes"
        val decoded = fetchDecoded(path)
        val root = runCatching { JSONObject(decoded) }.getOrNull() ?: return emptyList()
        val data = root.optJSONArray("data") ?: return emptyList()
        val bySeason = LinkedHashMap<Int, MutableList<SeasonEpisode>>()
        for (i in 0 until data.length()) {
            val item = data.optJSONObject(i) ?: continue
            val season = item.optInt("seasonNumber", 1).takeIf { it > 0 } ?: 1
            val number = item.optInt("number", 0)
            if (number <= 0) continue
            bySeason.getOrPut(season) { mutableListOf() } += SeasonEpisode(
                number = number,
                title = item.optString("title").takeIf { it.isNotBlank() },
                id = if (item.isNull("id")) null else item.optInt("id").takeIf { it > 0 },
                isFiller = item.optBoolean("isFiller", false)
            )
        }
        return bySeason.entries
            .map { (number, eps) -> Season(number, null, eps.distinctBy { it.number }.sortedBy { it.number }) }
            .sortedBy { it.number }
    }

    private suspend fun episodePageId(animeId: Int, season: Int, episode: Int): Int? {
        val page = runCatching {
            Http.getAsync("$ORIGIN/anime/$animeId/season/$season/episode/$episode", headers())
        }.getOrNull() ?: return null
        if (!page.isOk) return null
        return Regex(Selectors.NK_DATA_EPISODE_ID).find(page.body)?.groupValues?.get(1)?.toIntOrNull()
    }

    // ------------------------------------------------------------------ helpers

    private fun episodeFrom(anime: Anime, season: Int, ref: SeasonEpisode): Episode? {
        if (ref.number <= 0) return null
        return Episode(
            number = ref.number,
            title = ref.title ?: "${anime.title} — Épisode ${ref.number}",
            url = "$ORIGIN/anime/${animeIdOf(anime.url)}/season/$season/episode/${ref.number}",
            source = Source.NAKANIME,
            language = anime.languages.firstOrNull() ?: Language.VOSTFR,
            releaseDate = null,
            seriesId = anime.id,
            seriesTitle = anime.title,
            seriesUrl = anime.url,
            posterUrl = anime.posterUrl,
            isFiller = ref.isFiller
        )
    }

    fun parseCatalogue(decoded: String, pathWithQuery: String): List<Anime> {
        val root = runCatching { JSONObject(decoded) }.getOrNull() ?: return emptyList()
        val data = root.optJSONArray("data") ?: return emptyList()
        val out = mutableListOf<Anime>()
        for (i in 0 until data.length()) {
            val item = data.optJSONObject(i) ?: continue
            val id = item.optString("id").takeIf { it.isNotBlank() } ?: continue
            val slug = item.optString("slug").takeIf { it.isNotBlank() } ?: continue
            val languages = mutableSetOf<Language>()
            item.optJSONArray("languages")?.let { arr ->
                for (j in 0 until arr.length()) Language.fromTag(arr.optString(j))?.let { languages += it }
            }
            val genres = mutableListOf<String>()
            item.optJSONArray("genres")?.let { arr ->
                for (j in 0 until arr.length()) arr.optString(j).takeIf { it.isNotBlank() }?.let { genres += it }
            }
            out += Anime(
                id = "${Source.NAKANIME.id}:$id",
                title = item.optString("title").ifBlank { slug },
                url = "$ORIGIN/anime/$id/$slug",
                source = Source.NAKANIME,
                languages = languages,
                posterUrl = firstString(item, "poster_url", "backdrop_url"),
                synopsis = firstString(item, "overview"),
                episodeCount = item.optInt("episode_count", 0).takeIf { it > 0 },
                // nakanime exposes "popularity", not a rating: showing it as a
                // score would be inventing a number, so the field stays empty.
                rating = null,
                genres = genres
            )
        }
        return out
    }

    /** `/anime/16/season/2/episode/5` → `2`. */
    fun seasonOf(episodeUrl: String): Int =
        Regex("""/season/(\d+)/""").find(episodeUrl)?.groupValues?.get(1)?.toIntOrNull() ?: 1

    /**
     * Which season an episode number belongs to, for the cross-source wheel:
     * the same episode on nakanime may live in a different season than the one
     * the user was browsing on the primary source.
     */
    suspend fun locateEpisode(anime: Anime, episodeNumber: Int): Pair<Int, Int>? {
        val id = animeIdOf(anime.url) ?: return null
        val seasons = seasonIndex(id)
        seasons.firstOrNull { season -> season.episodes.any { it.number == episodeNumber } }
            ?.let { return it.number to episodeNumber }
        return seasons.firstOrNull()?.number?.let { it to episodeNumber }
    }

    /** `/anime/16/one-piece` → `16` (both id-first and slug-first URLs are accepted). */
    fun animeIdOf(url: String): Int? {
        val segments = url.trimEnd('/').split('/')
        val index = segments.indexOf("anime")
        val candidate = segments.getOrNull(index + 1)?.takeIf { index >= 0 } ?: return null
        return candidate.toIntOrNull()
    }

    private suspend fun fetchDecoded(pathWithQuery: String): String {
        val response = Http.getAsync("$ORIGIN$pathWithQuery", headers() + mapOf("Accept" to "*/*"))
        if (!response.isOk) throw ScrapeException("nakanime a répondu ${response.code} sur $pathWithQuery")
        return decode(response.bytes, pathWithQuery)
    }

    private fun firstString(json: JSONObject, vararg keys: String): String? {
        for (key in keys) {
            if (json.has(key) && !json.isNull(key)) {
                val value = json.optString(key)
                if (value.isNotBlank() && value != "null") return value
            }
        }
        return null
    }

    private fun enc(raw: String): String = java.net.URLEncoder.encode(raw, "UTF-8")
}

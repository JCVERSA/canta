package com.jcversa.canta

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import com.jcversa.canta.extractor.MirrorResolver
import com.jcversa.canta.manager.DownloadUi
import com.jcversa.canta.manager.HistoryEntry
import com.jcversa.canta.model.Anime
import com.jcversa.canta.model.AnimeDetail
import com.jcversa.canta.model.Episode
import com.jcversa.canta.model.Language
import com.jcversa.canta.model.MirrorRef
import com.jcversa.canta.model.MirrorResult
import com.jcversa.canta.model.Source
import com.jcversa.canta.scraper.NakanimeScraper
import com.jcversa.canta.scraper.ScrapeException
import com.jcversa.canta.scraper.StructureChangedException
import com.jcversa.canta.scraper.VoirAnimeScraper
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

/**
 * Root app state, mirroring SwiftSlate's single-root-viewmodel layout: the
 * screens hold no scraping logic, they render what this exposes.
 *
 * The fallback wheel lives here as well, because it is a *language and mirror
 * policy* decision rather than a UI one:
 *   voir-anime (VF-first) → its VOSTFR entry → nakanime (VOSTFR).
 * Every hop records a note that the UI displays, so a user always knows which
 * source answered and whether the language is verified or merely declared.
 */
class AppViewModel(private val app: App) : ViewModel() {

    private val container = app.container

    // ---------------------------------------------------------------- catalogue

    private val _catalogue = MutableStateFlow<List<Anime>>(emptyList())
    val catalogue: StateFlow<List<Anime>> = _catalogue.asStateFlow()

    private val _catalogueLoading = MutableStateFlow(false)
    val catalogueLoading: StateFlow<Boolean> = _catalogueLoading.asStateFlow()

    private val _catalogueError = MutableStateFlow<String?>(null)
    val catalogueError: StateFlow<String?> = _catalogueError.asStateFlow()

    private val _cataloguePage = MutableStateFlow(1)
    val cataloguePage: StateFlow<Int> = _cataloguePage.asStateFlow()

    /** VF by default, exactly as specified. */
    private val _language = MutableStateFlow(Language.VF)
    val language: StateFlow<Language> = _language.asStateFlow()

    private val _query = MutableStateFlow("")
    val query: StateFlow<String> = _query.asStateFlow()

    private var catalogueJob: Job? = null

    // ------------------------------------------------------------------- detail

    private val _detail = MutableStateFlow<AnimeDetail?>(null)
    val detail: StateFlow<AnimeDetail?> = _detail.asStateFlow()

    private val _detailLoading = MutableStateFlow(false)
    val detailLoading: StateFlow<Boolean> = _detailLoading.asStateFlow()

    private val _detailError = MutableStateFlow<String?>(null)
    val detailError: StateFlow<String?> = _detailError.asStateFlow()

    private val _detailNotes = MutableStateFlow<List<String>>(emptyList())
    val detailNotes: StateFlow<List<String>> = _detailNotes.asStateFlow()

    private var selectedAnime: Anime? = null

    // ------------------------------------------------------------------- player

    private val _currentEpisode = MutableStateFlow<Episode?>(null)
    val currentEpisode: StateFlow<Episode?> = _currentEpisode.asStateFlow()

    private val _stream = MutableStateFlow<MirrorResult?>(null)
    val stream: StateFlow<MirrorResult?> = _stream.asStateFlow()

    private val _resolving = MutableStateFlow(false)
    val resolving: StateFlow<Boolean> = _resolving.asStateFlow()

    private val _playerError = MutableStateFlow<String?>(null)
    val playerError: StateFlow<String?> = _playerError.asStateFlow()

    private val _qualityRequest = MutableStateFlow<String?>(null)
    val qualityRequest: StateFlow<String?> = _qualityRequest.asStateFlow()

    // -------------------------------------------------------- persisted managers

    val favorites: StateFlow<List<Anime>> = container.favorites.favorites
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    val history: StateFlow<List<HistoryEntry>> = container.history.entries
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    val watchlist: StateFlow<List<Anime>> = container.watchlist.series
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    val downloads: StateFlow<List<DownloadUi>> = container.downloads.downloads

    init {
        loadCatalogue(reset = true)
    }

    // --------------------------------------------------------------- catalogue

    fun setLanguage(language: Language) {
        _language.value = language
        loadCatalogue(reset = true)
    }

    fun onQueryChange(query: String) {
        _query.value = query
    }

    fun submitSearch() {
        val query = _query.value.trim()
        if (query.isEmpty()) {
            loadCatalogue(reset = true)
            return
        }
        catalogueJob?.cancel()
        catalogueJob = viewModelScope.launch {
            _catalogueLoading.value = true
            _catalogueError.value = null
            try {
                val results = runCatching { VoirAnimeScraper.search(query) }.getOrElse { emptyList() }
                if (results.isNotEmpty()) {
                    _catalogue.value = results
                } else {
                    // Honest fallback: the VOSTFR catalogue is the second source.
                    val nakanime = NakanimeScraper.search(query)
                    _catalogue.value = nakanime
                    if (nakanime.isEmpty()) _catalogueError.value = "Aucun résultat pour « $query »."
                }
                _cataloguePage.value = 1
            } catch (e: Exception) {
                _catalogueError.value = describe(e)
            } finally {
                _catalogueLoading.value = false
            }
        }
    }

    fun loadCatalogue(reset: Boolean = false) {
        catalogueJob?.cancel()
        catalogueJob = viewModelScope.launch {
            _catalogueLoading.value = true
            _catalogueError.value = null
            val page = if (reset) 1 else _cataloguePage.value + 1
            try {
                val items = VoirAnimeScraper.catalogue(page = page, language = _language.value)
                _catalogue.value = if (reset) items else _catalogue.value + items
                _cataloguePage.value = page
                if (items.isEmpty() && reset) {
                    _catalogueError.value = "Aucune série en ${_language.value.label} sur cette page."
                }
            } catch (e: Exception) {
                if (reset) _catalogue.value = emptyList()
                _catalogueError.value = describe(e)
            } finally {
                _catalogueLoading.value = false
            }
        }
    }

    // ------------------------------------------------------------------ detail

    /**
     * Opens a series in the requested language, walking the fallback ladder and
     * recording every hop as a note the detail screen shows.
     */
    fun openAnime(anime: Anime, wanted: Language = _language.value) {
        selectedAnime = anime
        viewModelScope.launch {
            _detailLoading.value = true
            _detailError.value = null
            _detailNotes.value = emptyList()
            val notes = mutableListOf<String>()
            try {
                var target = anime
                var detail: AnimeDetail? = null

                if (target.source == Source.VOIRANIME) {
                    if (!target.languages.contains(wanted)) {
                        val counterpart = runCatching { VoirAnimeScraper.resolveLanguageEntry(target, wanted) }.getOrNull()
                        if (counterpart != null) {
                            notes += "${wanted.label} : entrée « ${counterpart.title} » sur voir-anime.to."
                            target = counterpart
                        }
                    }
                    detail = runCatching { VoirAnimeScraper.detail(target) }.getOrNull()
                    if (detail == null) {
                        notes += "voir-anime.to n'a pas répondu correctement — bascule sur nakanime.tv."
                    }
                }

                if (detail == null) {
                    val fallback = findOnNakanime(target, wanted)
                    if (fallback != null) {
                        val fallbackDetail = runCatching { NakanimeScraper.detail(fallback) }.getOrNull()
                        if (fallbackDetail != null) {
                            detail = fallbackDetail
                            target = fallback
                            notes += if (wanted == Language.VF) {
                                "VF indisponible sur voir-anime.to — proposé via nakanime.tv (langue déclarée par la source, non vérifiable)."
                            } else {
                                "Source de secours: nakanime.tv (langue déclarée par la source, non vérifiable)."
                            }
                        }
                    }
                }

                if (detail == null) {
                    val available = target.languages.joinToString(", ") { it.label }
                    _detailError.value = if (available.isBlank()) {
                        "Série indisponible pour le moment sur les deux sources."
                    } else {
                        "${wanted.label} non disponible pour cette série (disponible: $available)."
                    }
                    _detail.value = null
                    return@launch
                }

                selectedAnime = target
                _detail.value = detail
                _detailNotes.value = notes
                _language.value = wanted
            } catch (e: Exception) {
                _detailError.value = describe(e)
            } finally {
                _detailLoading.value = false
            }
        }
    }

    /** Re-opens the currently selected series in the other language. */
    fun switchDetailLanguage(language: Language) {
        val anime = selectedAnime ?: return
        openAnime(anime, language)
    }

    // ------------------------------------------------------------------ player

    /**
     * Resolves a playable stream for one episode: mirror priority (VidMoly →
     * Voe → generic), fast failover, then the cross-source rescue on nakanime
     * when every mirror of the primary source fails.
     */
    fun playEpisode(episode: Episode, quality: String? = _qualityRequest.value) {
        _currentEpisode.value = episode
        _qualityRequest.value = quality
        viewModelScope.launch {
            _resolving.value = true
            _playerError.value = null
            _stream.value = null
            try {
                var result = resolveLocally(episode, quality)
                if (result == null && episode.source == Source.VOIRANIME) {
                    // Cross-source wheel: the same episode on the secondary catalogue.
                    result = resolveViaNakanime(episode, quality)
                }
                _stream.value = result
                if (result == null) {
                    _playerError.value = MirrorResolver.lastFailure
                        ?: "Aucun lecteur n'a fourni de flux lisible pour cet épisode."
                }
            } catch (e: Exception) {
                _playerError.value = describe(e)
            } finally {
                _resolving.value = false
            }
        }
    }

    fun selectQuality(label: String?) {
        val episode = _currentEpisode.value ?: return
        playEpisode(episode, label)
    }

    private suspend fun resolveLocally(episode: Episode, quality: String?): MirrorResult? {
        val mirrors: List<MirrorRef> = when (episode.source) {
            Source.VOIRANIME -> VoirAnimeScraper.mirrors(episode)
            // nakanime episode URLs carry their own season, so no lookup is needed.
            Source.NAKANIME -> {
                val anime = selectedAnime ?: return null
                NakanimeScraper.mirrors(anime, NakanimeScraper.seasonOf(episode.url), episode.number)
            }
        }
        if (mirrors.isEmpty()) return null
        return MirrorResolver.resolve(mirrors, episode.language, episode.source, quality)
    }

    /** Cross-source rescue: the same episode, on the secondary catalogue. */
    private suspend fun resolveViaNakanime(episode: Episode, quality: String?): MirrorResult? {
        val anime = selectedAnime ?: return null
        val fallback = findOnNakanime(anime, episode.language) ?: return null
        val located = NakanimeScraper.locateEpisode(fallback, episode.number) ?: return null
        val mirrors = runCatching { NakanimeScraper.mirrors(fallback, located.first, located.second) }
            .getOrDefault(emptyList())
        if (mirrors.isEmpty()) return null
        return MirrorResolver.resolve(mirrors, episode.language, Source.NAKANIME, quality)
    }

    private suspend fun findOnNakanime(anime: Anime, wanted: Language): Anime? {
        val results = runCatching { NakanimeScraper.search(anime.title) }.getOrDefault(emptyList())
        if (results.isEmpty()) return null
        val normalized = anime.title.lowercase().trim()
        val exact = results.firstOrNull { it.title.lowercase().trim() == normalized }
        val candidate = exact ?: results.firstOrNull { it.title.lowercase().contains(normalized) } ?: results.first()
        if (candidate.languages.isNotEmpty() && !candidate.languages.contains(wanted)) return null
        return candidate
    }

    // ----------------------------------------------------------------- actions

    fun toggleFavorite(anime: Anime) {
        viewModelScope.launch { container.favorites.toggle(anime) }
    }

    fun toggleWatchlist(anime: Anime) {
        viewModelScope.launch {
            val current = watchlist.value.any { it.id == anime.id }
            if (current) {
                container.watchlist.unwatch(anime.id)
            } else {
                // Remember where the series stands so the first check announces nothing.
                val highest = _detail.value?.episodes?.filter { it.language == Language.VF }?.maxOfOrNull { it.number }
                container.watchlist.watch(anime, highest)
            }
        }
    }

    fun download(episode: Episode) {
        val stream = _stream.value ?: run {
            _playerError.value = "Résolvez d'abord le flux avant de télécharger."
            return
        }
        runCatching {
            container.downloads.enqueue(app, episode, stream)
        }.onFailure { _playerError.value = "Téléchargement impossible: ${it.message}" }
    }

    fun removeDownload(id: String) {
        container.downloads.remove(app, id)
    }

    fun recordProgress(episode: Episode, positionMs: Long, durationMs: Long) {
        viewModelScope.launch { container.history.record(episode, positionMs, durationMs) }
    }

    fun clearHistory() {
        viewModelScope.launch { container.history.clear() }
    }

    fun refreshDownloads() {
        container.downloads.refresh(app)
    }

    /** Media3 pauses/resumes the whole queue; the UI exposes it as one switch. */
    fun pauseAllDownloads() {
        container.downloads.pauseAll(app)
    }

    fun resumeAllDownloads() {
        container.downloads.resumeAll(app)
    }

    fun currentDetailAnime(): Anime? = selectedAnime

    private fun describe(error: Throwable): String = when (error) {
        is StructureChangedException -> "${error.message} (les sélecteurs de la source ont changé)"
        is ScrapeException -> error.message ?: "Erreur réseau"
        else -> error.message ?: error::class.java.simpleName
    }

    companion object {
        fun factory(app: App): ViewModelProvider.Factory = viewModelFactory {
            initializer { AppViewModel(app) }
        }
    }
}

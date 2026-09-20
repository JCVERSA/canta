package com.jcversa.canta.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.grid.rememberLazyGridState
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.jcversa.canta.AppViewModel
import com.jcversa.canta.model.Anime
import com.jcversa.canta.model.Language
import com.jcversa.canta.ui.components.AnimeCard
import com.jcversa.canta.ui.components.EmptyState
import com.jcversa.canta.ui.components.ErrorState
import com.jcversa.canta.ui.components.LanguageToggle
import com.jcversa.canta.ui.components.LoadingState

/**
 * Catalogue & search.
 *
 * The language toggle filters by what each entry actually is (a `-vf` slug is
 * a French dub, everything else is VOSTFR), and the header states where the
 * rows come from, so "VF" on screen is never a guess.
 */
@Composable
fun CatalogueScreen(
    viewModel: AppViewModel,
    onOpenAnime: (Anime) -> Unit
) {
    val items by viewModel.catalogue.collectAsStateWithLifecycle()
    val loading by viewModel.catalogueLoading.collectAsStateWithLifecycle()
    val error by viewModel.catalogueError.collectAsStateWithLifecycle()
    val query by viewModel.query.collectAsStateWithLifecycle()
    val language by viewModel.language.collectAsStateWithLifecycle()
    val page by viewModel.cataloguePage.collectAsStateWithLifecycle()
    val gridState = rememberLazyGridState()

    val shouldLoadMore by remember {
        derivedStateOf {
            val last = gridState.layoutInfo.visibleItemsInfo.lastOrNull()?.index ?: 0
            last >= gridState.layoutInfo.totalItemsCount - 4 && gridState.layoutInfo.totalItemsCount > 0
        }
    }

    LaunchedEffect(shouldLoadMore) {
        if (shouldLoadMore && !loading && query.isBlank()) viewModel.loadCatalogue(reset = false)
    }

    Column(modifier = Modifier.fillMaxSize()) {
        OutlinedTextField(
            value = query,
            onValueChange = viewModel::onQueryChange,
            placeholder = { Text("Rechercher une série") },
            leadingIcon = { Icon(Icons.Filled.Search, contentDescription = null) },
            trailingIcon = {
                IconButton(onClick = viewModel::submitSearch) {
                    Icon(Icons.Filled.Search, contentDescription = "Chercher")
                }
            },
            singleLine = true,
            keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
            keyboardActions = KeyboardActions(onSearch = { viewModel.submitSearch() }),
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 8.dp)
        )

        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 4.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            LanguageToggle(
                selected = language,
                available = Language.entries.toSet(),
                onSelect = { viewModel.setLanguage(it) }
            )
            Text(
                text = if (query.isBlank()) "Source : voir-anime.to · page $page" else "Recherche",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }

        when {
            loading && items.isEmpty() -> LoadingState("Chargement du catalogue…")
            error != null && items.isEmpty() -> ErrorState(error!!)
            items.isEmpty() -> EmptyState("Aucune série en ${language.label} pour le moment.")
            else -> LazyVerticalGrid(
                columns = GridCells.Adaptive(minSize = 140.dp),
                state = gridState,
                contentPadding = androidx.compose.foundation.layout.PaddingValues(16.dp),
                horizontalArrangement = Arrangement.spacedBy(12.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp),
                modifier = Modifier.fillMaxSize()
            ) {
                items(items, key = { it.id }) { anime ->
                    AnimeCard(
                        anime = anime,
                        onClick = { onOpenAnime(anime) }
                    )
                }
                if (loading) {
                    item(span = { GridItemSpan(maxLineSpan) }) {
                        LoadingState("Page suivante…")
                    }
                }
                error?.let { message ->
                    item(span = { GridItemSpan(maxLineSpan) }) { ErrorState(message) }
                }
            }
        }
    }
}

package com.jcversa.canta

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Download
import androidx.compose.material.icons.filled.Favorite
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.VideoLibrary
import androidx.compose.material3.Icon
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.core.content.ContextCompat
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.jcversa.canta.manager.CantaDownloadManager
import com.jcversa.canta.ui.CatalogueScreen
import com.jcversa.canta.ui.DetailScreen
import com.jcversa.canta.ui.DownloadsScreen
import com.jcversa.canta.ui.FavoritesScreen
import com.jcversa.canta.ui.PlayerScreen
import com.jcversa.canta.ui.SettingsScreen
import com.jcversa.canta.ui.theme.CantaTheme
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/** A series a notification asked the app to open. */
data class SeriesLaunch(val id: String, val title: String?)

class MainActivity : ComponentActivity() {

    private val viewModel: AppViewModel by viewModels { AppViewModel.factory(application as App) }

    private val notificationPermission =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) { }

    /**
     * Series the launching intent asked to open, consumed once by the UI.
     *
     * It is an event rather than a screen argument because it can arrive at any
     * time: the episode watcher posts its notification while the app may already
     * be running (`onNewIntent`), and an event that is a *value* would otherwise
     * re-open the same series on every recomposition.
     */
    private val _pendingSeries = MutableStateFlow<SeriesLaunch?>(null)
    private val pendingSeries: StateFlow<SeriesLaunch?> = _pendingSeries.asStateFlow()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        askForNotificationPermission()
        handleLaunchIntent(intent)
        setContent {
            CantaTheme {
                CantaRoot(viewModel, pendingSeries) { _pendingSeries.value = null }
            }
        }
    }

    /**
     * The episode watcher's notification carries the series it announced. With
     * `singleTop`-style reuse of the running activity this is the path taken;
     * `onCreate` covers a cold start.
     */
    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        handleLaunchIntent(intent)
    }

    private fun handleLaunchIntent(intent: Intent?) {
        val source = intent ?: return
        val id = source.getStringExtra(EXTRA_OPEN_SERIES_ID) ?: return
        // Consumed and removed: a configuration change or a re-delivery must not
        // replay a navigation the user already saw.
        source.removeExtra(EXTRA_OPEN_SERIES_ID)
        val title = source.getStringExtra(EXTRA_OPEN_SERIES_TITLE)
        source.removeExtra(EXTRA_OPEN_SERIES_TITLE)
        _pendingSeries.value = SeriesLaunch(id, title)
    }

    override fun onStart() {
        super.onStart()
        // Resume anything left queued by a killed process — but only if there is
        // something to resume: the service is not started just to sit idle.
        CantaDownloadManager.resumePendingIfAny(this)
    }

    private fun askForNotificationPermission() {
        if (Build.VERSION.SDK_INT < 33) return
        val granted = ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS) ==
            PackageManager.PERMISSION_GRANTED
        if (!granted) notificationPermission.launch(Manifest.permission.POST_NOTIFICATIONS)
    }

    companion object {
        const val EXTRA_OPEN_SERIES_ID = "open_series_id"

        /** Carried as well, so a series this install no longer knows can still be found. */
        const val EXTRA_OPEN_SERIES_TITLE = "open_series_title"
    }
}

/**
 * Screens without a navigation library.
 *
 * The app has seven destinations and one branch point; `androidx.navigation`
 * would add a dependency whose current release requires minSdk 24, while this
 * app supports 23 (Android 6). A small explicit stack is the honest trade: the
 * back behaviour is one function, and there is no deep-link surface to support.
 */
private enum class Screen { CATALOGUE, FAVORITES, DOWNLOADS, SETTINGS, DETAIL, PLAYER }

private data class Tab(val screen: Screen, val label: String, val icon: ImageVector)

private val TABS = listOf(
    Tab(Screen.CATALOGUE, "Catalogue", Icons.Filled.VideoLibrary),
    Tab(Screen.FAVORITES, "Favoris", Icons.Filled.Favorite),
    Tab(Screen.DOWNLOADS, "Hors ligne", Icons.Filled.Download),
    Tab(Screen.SETTINGS, "Réglages", Icons.Filled.Settings)
)

@Composable
private fun CantaRoot(
    viewModel: AppViewModel,
    pendingSeries: StateFlow<SeriesLaunch?>,
    onSeriesLaunchConsumed: () -> Unit
) {
    val stack = remember { mutableStateListOf(Screen.CATALOGUE) }
    val current = stack.last()

    fun go(screen: Screen) {
        stack.add(screen)
    }

    fun goTab(screen: Screen) {
        // Tabs are roots: selecting one clears whatever was pushed on top.
        stack.clear()
        stack.add(screen)
    }

    fun back() {
        if (stack.size > 1) stack.removeAt(stack.lastIndex)
    }

    // A tap on "new VF episode" opens that series. Resolved from local state
    // first; when this install no longer knows the id (series unwatched, app
    // reinstalled) the title from the notification is searched instead, so the
    // tap still leads somewhere real rather than to an empty catalogue.
    val launch by pendingSeries.collectAsStateWithLifecycle()
    LaunchedEffect(launch) {
        val target = launch ?: return@LaunchedEffect
        onSeriesLaunchConsumed()
        val anime = viewModel.resolveSeriesById(target.id)
        if (anime != null) {
            viewModel.openAnime(anime)
            goTab(Screen.CATALOGUE)
            go(Screen.DETAIL)
        } else {
            val title = target.title?.takeIf { it.isNotBlank() }
            goTab(Screen.CATALOGUE)
            if (title != null) {
                viewModel.onQueryChange(title)
                viewModel.submitSearch()
            }
        }
    }

    BackHandler(enabled = stack.size > 1) { back() }

    Scaffold(
        bottomBar = {
            if (TABS.any { it.screen == current }) {
                NavigationBar {
                    TABS.forEach { tab ->
                        NavigationBarItem(
                            selected = current == tab.screen,
                            onClick = { if (current != tab.screen) goTab(tab.screen) },
                            icon = { Icon(tab.icon, contentDescription = tab.label) },
                            label = { Text(tab.label) }
                        )
                    }
                }
            }
        }
    ) { padding ->
        val modifier = Modifier.padding(padding)
        when (current) {
            Screen.CATALOGUE -> CatalogueScreen(
                viewModel = viewModel,
                onOpenAnime = { anime ->
                    viewModel.openAnime(anime)
                    go(Screen.DETAIL)
                },
                modifier = modifier
            )

            Screen.DETAIL -> DetailScreen(
                viewModel = viewModel,
                onBack = { back() },
                onPlayEpisode = { episode ->
                    viewModel.playEpisode(episode)
                    go(Screen.PLAYER)
                },
                modifier = modifier
            )

            Screen.PLAYER -> PlayerScreen(
                viewModel = viewModel,
                onBack = { back() },
                modifier = modifier
            )

            Screen.FAVORITES -> FavoritesScreen(
                viewModel = viewModel,
                onOpenAnime = { anime ->
                    viewModel.openAnime(anime)
                    go(Screen.DETAIL)
                },
                modifier = modifier
            )

            Screen.DOWNLOADS -> DownloadsScreen(
                viewModel = viewModel,
                onPlay = { episode ->
                    viewModel.playEpisode(episode)
                    go(Screen.PLAYER)
                },
                modifier = modifier
            )

            Screen.SETTINGS -> SettingsScreen(viewModel = viewModel, modifier = modifier)
        }
    }
}

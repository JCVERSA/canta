package com.jcversa.canta

import android.Manifest
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import androidx.core.content.ContextCompat
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
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.navigation.NavDestination.Companion.hierarchy
import androidx.navigation.NavGraph.Companion.findStartDestination
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
import com.jcversa.canta.manager.CantaDownloadManager
import com.jcversa.canta.ui.CatalogueScreen
import com.jcversa.canta.ui.DetailScreen
import com.jcversa.canta.ui.DownloadsScreen
import com.jcversa.canta.ui.FavoritesScreen
import com.jcversa.canta.ui.PlayerScreen
import com.jcversa.canta.ui.SettingsScreen
import com.jcversa.canta.ui.theme.CantaTheme

class MainActivity : ComponentActivity() {

    private val viewModel: AppViewModel by viewModels { AppViewModel.factory(application as App) }

    private val notificationPermission = registerForActivityResult(ActivityResultContracts.RequestPermission()) { }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        askForNotificationPermission()
        setContent {
            CantaTheme {
                CantaRoot(viewModel, openSeriesId = intent?.getStringExtra(EXTRA_OPEN_SERIES_ID))
            }
        }
    }

    override fun onStart() {
        super.onStart()
        // Resume anything the download service left queued (e.g. process death).
        CantaDownloadManager.startService(this)
    }

    private fun askForNotificationPermission() {
        if (Build.VERSION.SDK_INT < 33) return
        val granted = ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS) ==
            android.content.pm.PackageManager.PERMISSION_GRANTED
        if (!granted) notificationPermission.launch(Manifest.permission.POST_NOTIFICATIONS)
    }

    companion object {
        const val EXTRA_OPEN_SERIES_ID = "open_series_id"
    }
}

private data class Tab(val route: String, val label: String, val icon: ImageVector)

private val TABS = listOf(
    Tab("catalogue", "Catalogue", Icons.Filled.VideoLibrary),
    Tab("favorites", "Favoris", Icons.Filled.Favorite),
    Tab("downloads", "Hors ligne", Icons.Filled.Download),
    Tab("settings", "Réglages", Icons.Filled.Settings)
)

@Composable
private fun CantaRoot(viewModel: AppViewModel, openSeriesId: String?) {
    val navController = rememberNavController()
    val backStackEntry by navController.currentBackStackEntryAsState()
    val currentDestination = backStackEntry?.destination
    val showBar = currentDestination?.route in TABS.map { it.route }

    Scaffold(
        bottomBar = {
            if (showBar) {
                NavigationBar {
                    TABS.forEach { tab ->
                        val selected = currentDestination?.hierarchy?.any { it.route == tab.route } == true
                        NavigationBarItem(
                            selected = selected,
                            onClick = {
                                navController.navigate(tab.route) {
                                    popUpTo(navController.graph.findStartDestination().id) { saveState = true }
                                    launchSingleTop = true
                                    restoreState = true
                                }
                            },
                            icon = { Icon(tab.icon, contentDescription = tab.label) },
                            label = { Text(tab.label) }
                        )
                    }
                }
            }
        }
    ) { padding ->
        NavHost(
            navController = navController,
            startDestination = "catalogue",
            modifier = Modifier.padding(padding)
        ) {
            composable("catalogue") {
                CatalogueScreen(
                    viewModel = viewModel,
                    onOpenAnime = { anime ->
                        viewModel.openAnime(anime)
                        navController.navigate("detail")
                    }
                )
            }
            composable("detail") {
                DetailScreen(
                    viewModel = viewModel,
                    onBack = { navController.popBackStack() },
                    onPlayEpisode = { episode ->
                        viewModel.playEpisode(episode)
                        navController.navigate("player")
                    }
                )
            }
            composable("player") {
                PlayerScreen(
                    viewModel = viewModel,
                    onBack = { navController.popBackStack() }
                )
            }
            composable("favorites") {
                FavoritesScreen(
                    viewModel = viewModel,
                    onOpenAnime = { anime ->
                        viewModel.openAnime(anime)
                        navController.navigate("detail")
                    }
                )
            }
            composable("downloads") {
                DownloadsScreen(
                    viewModel = viewModel,
                    onPlay = { episode ->
                        viewModel.playEpisode(episode)
                        navController.navigate("player")
                    }
                )
            }
            composable("settings") {
                SettingsScreen(viewModel = viewModel)
            }
        }
    }
}

package de.guenthers.certcheck

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.History
import androidx.compose.material.icons.filled.Home
import androidx.compose.material.icons.filled.Security
import androidx.compose.material.icons.filled.Star
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.lifecycle.viewmodel.compose.viewModel
import de.guenthers.certcheck.ui.screens.DashboardContent
import de.guenthers.certcheck.ui.screens.FavoritesContent
import de.guenthers.certcheck.ui.screens.HistoryContent
import de.guenthers.certcheck.ui.screens.ResultScreen
import de.guenthers.certcheck.ui.theme.CertCheckTheme

class MainActivity : ComponentActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()

        setContent {
            CertCheckTheme {
                CertCheckApp()
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun CertCheckApp(viewModel: MainViewModel = viewModel(factory = MainViewModel.Factory)) {
    val uiState by viewModel.uiState.collectAsState()
    val favorites by viewModel.favorites.collectAsState()
    val checkHistory by viewModel.checkHistory.collectAsState()
    val latestChecks by viewModel.latestChecks.collectAsState()

    if (uiState.result != null && !uiState.isLoading) {
        ResultScreen(
            result = uiState.result!!,
            onBack = { viewModel.clearResult() },
            onRecheck = { viewModel.checkCertificate() },
            isFavorite = uiState.isFavorite,
            onToggleFavorite = viewModel::toggleFavorite
        )
    } else {
        var selectedTab by remember { mutableIntStateOf(0) }

        val tabTitles = listOf("Accueil", "Historique", "Favoris")

        Scaffold(
            topBar = {
                TopAppBar(
                    title = {
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            Icon(
                                Icons.Filled.Security,
                                contentDescription = null,
                                tint = MaterialTheme.colorScheme.primary
                            )
                            Text("CertCheck — ${tabTitles[selectedTab]}")
                        }
                    },
                    colors = TopAppBarDefaults.topAppBarColors(
                        containerColor = MaterialTheme.colorScheme.surface
                    )
                )
            },
            bottomBar = {
                NavigationBar {
                    NavigationBarItem(
                        selected = selectedTab == 0,
                        onClick = { selectedTab = 0 },
                        icon = { Icon(Icons.Filled.Home, contentDescription = "Accueil") },
                        label = { Text("Accueil") }
                    )
                    NavigationBarItem(
                        selected = selectedTab == 1,
                        onClick = { selectedTab = 1 },
                        icon = { Icon(Icons.Filled.History, contentDescription = "Historique") },
                        label = { Text("Historique") }
                    )
                    NavigationBarItem(
                        selected = selectedTab == 2,
                        onClick = { selectedTab = 2 },
                        icon = { Icon(Icons.Filled.Star, contentDescription = "Favoris") },
                        label = { Text("Favoris") }
                    )
                }
            }
        ) { padding ->
            when (selectedTab) {
                0 -> DashboardContent(
                    uiState = uiState,
                    onHostnameChanged = viewModel::onHostnameChanged,
                    onCheck = viewModel::checkCertificate,
                    latestChecks = latestChecks,
                    recentHistory = checkHistory,
                    onDomainCheck = viewModel::checkDomain,
                    modifier = Modifier.padding(padding),
                )
                1 -> HistoryContent(
                    history = checkHistory,
                    onDomainCheck = viewModel::checkDomain,
                    modifier = Modifier.padding(padding),
                )
                2 -> FavoritesContent(
                    favorites = favorites,
                    latestChecks = latestChecks,
                    onFavoriteClick = viewModel::loadFromFavorite,
                    onFavoriteDelete = viewModel::removeFavorite,
                    onFavoriteRefresh = viewModel::refreshFavorite,
                    modifier = Modifier.padding(padding),
                )
            }
        }
    }
}

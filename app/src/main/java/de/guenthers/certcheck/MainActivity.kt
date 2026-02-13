package de.guenthers.certcheck

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.History
import androidx.compose.material.icons.filled.Home
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Security
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.Star
import androidx.compose.material.icons.filled.StarBorder
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
import de.guenthers.certcheck.ui.screens.DashboardContent
import de.guenthers.certcheck.ui.screens.FavoritesContent
import de.guenthers.certcheck.ui.screens.HistoryContent
import de.guenthers.certcheck.ui.screens.ResultContent
import de.guenthers.certcheck.ui.screens.SettingsContent
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

    val checkHour by viewModel.preferences.checkHour.collectAsState()
    val alertThresholdDays by viewModel.preferences.alertThresholdDays.collectAsState()
    val notificationsEnabled by viewModel.preferences.notificationsEnabled.collectAsState()

    var selectedTab by remember { mutableIntStateOf(0) }
    var showSettings by remember { mutableStateOf(false) }
    val showResult = uiState.result != null && !uiState.isLoading
    val tabTitles = listOf("Accueil", "Historique", "Favoris")

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    if (showResult) {
                        Text(uiState.result!!.hostname)
                    } else if (showSettings) {
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            Icon(
                                Icons.Filled.Settings,
                                contentDescription = null,
                                tint = MaterialTheme.colorScheme.primary
                            )
                            Text("CertCheck — Paramètres")
                        }
                    } else {
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
                    }
                },
                navigationIcon = {
                    if (showResult || showSettings) {
                        IconButton(onClick = {
                            if (showResult) viewModel.clearResult()
                            if (showSettings) showSettings = false
                        }) {
                            Icon(
                                Icons.AutoMirrored.Filled.ArrowBack,
                                contentDescription = "Retour"
                            )
                        }
                    }
                },
                actions = {
                    if (showResult) {
                        IconButton(onClick = viewModel::toggleFavorite) {
                            Icon(
                                imageVector = if (uiState.isFavorite) Icons.Filled.Star
                                    else Icons.Filled.StarBorder,
                                contentDescription = if (uiState.isFavorite) "Retirer des favoris"
                                    else "Ajouter aux favoris",
                                tint = if (uiState.isFavorite) MaterialTheme.colorScheme.primary
                                    else MaterialTheme.colorScheme.onSurface
                            )
                        }
                        IconButton(onClick = { viewModel.checkCertificate() }) {
                            Icon(Icons.Filled.Refresh, contentDescription = "Revérifier")
                        }
                    } else if (!showSettings) {
                        IconButton(onClick = { showSettings = true }) {
                            Icon(
                                Icons.Filled.Settings,
                                contentDescription = "Paramètres",
                            )
                        }
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
                    selected = selectedTab == 0 && !showResult && !showSettings,
                    onClick = {
                        selectedTab = 0
                        showSettings = false
                        if (showResult) viewModel.clearResult()
                    },
                    icon = { Icon(Icons.Filled.Home, contentDescription = "Accueil") },
                    label = { Text("Accueil") }
                )
                NavigationBarItem(
                    selected = selectedTab == 1 && !showResult && !showSettings,
                    onClick = {
                        selectedTab = 1
                        showSettings = false
                        if (showResult) viewModel.clearResult()
                    },
                    icon = { Icon(Icons.Filled.History, contentDescription = "Historique") },
                    label = { Text("Historique") }
                )
                NavigationBarItem(
                    selected = selectedTab == 2 && !showResult && !showSettings,
                    onClick = {
                        selectedTab = 2
                        showSettings = false
                        if (showResult) viewModel.clearResult()
                    },
                    icon = { Icon(Icons.Filled.Star, contentDescription = "Favoris") },
                    label = { Text("Favoris") }
                )
            }
        }
    ) { padding ->
        if (showSettings) {
            SettingsContent(
                checkHour = checkHour,
                alertThresholdDays = alertThresholdDays,
                notificationsEnabled = notificationsEnabled,
                onCheckHourChanged = viewModel::updateCheckHour,
                onAlertThresholdChanged = viewModel::updateAlertThreshold,
                onNotificationsToggled = viewModel::updateNotificationsEnabled,
                modifier = Modifier.padding(padding),
            )
        } else if (showResult) {
            ResultContent(
                result = uiState.result!!,
                modifier = Modifier.padding(padding),
            )
        } else {
            when (selectedTab) {
                0 -> DashboardContent(
                    uiState = uiState,
                    onHostnameChanged = viewModel::onHostnameChanged,
                    onCheck = viewModel::checkCertificate,
                    latestChecks = latestChecks,
                    recentHistory = checkHistory,
                    onDomainCheck = viewModel::checkDomain,
                    alertThresholdDays = alertThresholdDays,
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
                    onFavoriteClick = { favorite ->
                        viewModel.selectFavoriteHostname(favorite)
                        selectedTab = 0
                    },
                    onFavoriteDelete = viewModel::removeFavorite,
                    onFavoriteRefresh = viewModel::refreshFavorite,
                    modifier = Modifier.padding(padding),
                )
            }
        }
    }
}

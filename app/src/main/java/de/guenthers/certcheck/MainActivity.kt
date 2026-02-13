package de.guenthers.certcheck

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
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
import androidx.compose.material.icons.filled.Share
import androidx.compose.material.icons.filled.Star
import androidx.compose.material.icons.filled.StarBorder
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
import de.guenthers.certcheck.model.CertCheckResult
import de.guenthers.certcheck.model.CheckStatus
import de.guenthers.certcheck.ui.screens.DashboardContent
import de.guenthers.certcheck.ui.screens.FavoritesContent
import de.guenthers.certcheck.ui.screens.HistoryContent
import de.guenthers.certcheck.ui.screens.ResultContent
import de.guenthers.certcheck.ui.screens.SettingsContent
import de.guenthers.certcheck.ui.theme.CertCheckTheme
import java.text.SimpleDateFormat
import java.util.Locale

class MainActivity : ComponentActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        requestNotificationPermission()

        setContent {
            CertCheckTheme {
                CertCheckApp()
            }
        }
    }

    private fun requestNotificationPermission() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            if (ContextCompat.checkSelfPermission(
                    this, Manifest.permission.POST_NOTIFICATIONS
                ) != PackageManager.PERMISSION_GRANTED
            ) {
                ActivityCompat.requestPermissions(
                    this, arrayOf(Manifest.permission.POST_NOTIFICATIONS), 1001
                )
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
    val widgetColor by viewModel.preferences.widgetColor.collectAsState()
    val widgetOpacity by viewModel.preferences.widgetOpacity.collectAsState()

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
                        val context = LocalContext.current
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
                        IconButton(onClick = {
                            uiState.result?.let { result ->
                                val report = generateReport(result)
                                val sendIntent = Intent().apply {
                                    action = Intent.ACTION_SEND
                                    putExtra(Intent.EXTRA_TEXT, report)
                                    putExtra(Intent.EXTRA_SUBJECT, "CertCheck — ${result.hostname}")
                                    type = "text/plain"
                                }
                                context.startActivity(
                                    Intent.createChooser(sendIntent, "Partager le rapport")
                                )
                            }
                        }) {
                            Icon(Icons.Filled.Share, contentDescription = "Partager")
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
                widgetColor = widgetColor,
                widgetOpacity = widgetOpacity,
                onWidgetColorChanged = viewModel::updateWidgetColor,
                onWidgetOpacityChanged = viewModel::updateWidgetOpacity,
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
                        viewModel.checkDomain(favorite.hostname, favorite.port)
                    },
                    onFavoriteDelete = viewModel::removeFavorite,
                    onFavoriteRefresh = viewModel::refreshFavorite,
                    onFavoriteToggleNotifications = viewModel::toggleFavoriteNotifications,
                    modifier = Modifier.padding(padding),
                )
            }
        }
    }
}

private fun generateReport(result: CertCheckResult): String {
    val dateFormat = SimpleDateFormat("dd/MM/yyyy HH:mm:ss", Locale.getDefault())
    val sb = StringBuilder()

    sb.appendLine("=== CertCheck — Rapport SSL/TLS ===")
    sb.appendLine()
    sb.appendLine("Domaine : ${result.hostname}:${result.port}")
    sb.appendLine("Date    : ${dateFormat.format(result.timestamp)}")
    sb.appendLine("Status  : ${result.overallStatus}")
    sb.appendLine()

    if (result.error != null) {
        sb.appendLine("ERREUR : ${result.error}")
        sb.appendLine()
    }

    if (result.tlsVersion != null) {
        sb.appendLine("--- Connexion ---")
        sb.appendLine("TLS          : ${result.tlsVersion}")
        sb.appendLine("Cipher Suite : ${result.cipherSuite ?: "N/A"}")
        sb.appendLine("Confiance Android : ${if (result.trustedByAndroid) "Oui" else "Non"}")
        sb.appendLine("Hostname match    : ${if (result.hostnameMatches) "Oui" else "Non"}")
        sb.appendLine("Chaîne valide     : ${if (result.chainValid) "Oui" else "Non"}")
        sb.appendLine()
    }

    if (result.issues.isNotEmpty()) {
        sb.appendLine("--- Problèmes détectés (${result.issues.size}) ---")
        result.issues.forEach { issue ->
            val severity = when (issue.severity) {
                de.guenthers.certcheck.model.IssueSeverity.CRITICAL -> "CRITIQUE"
                de.guenthers.certcheck.model.IssueSeverity.WARNING -> "ATTENTION"
                de.guenthers.certcheck.model.IssueSeverity.INFO -> "INFO"
            }
            sb.appendLine("[$severity] ${issue.title}")
            sb.appendLine("  ${issue.description}")
            sb.appendLine()
        }
    }

    if (result.certificates.isNotEmpty()) {
        sb.appendLine("--- Certificats (${result.certificates.size}) ---")
        result.certificates.forEach { cert ->
            sb.appendLine("[${cert.label}]")
            sb.appendLine("  Sujet     : ${cert.subject}")
            sb.appendLine("  Émetteur  : ${cert.issuer}")
            sb.appendLine("  Valide du : ${dateFormat.format(cert.notBefore)}")
            sb.appendLine("  Expire le : ${dateFormat.format(cert.notAfter)} (${cert.daysUntilExpiry}j)")
            sb.appendLine("  Algo      : ${cert.signatureAlgorithm}")
            sb.appendLine("  Clé       : ${cert.publicKeyAlgorithm} ${cert.publicKeySize} bits")
            sb.appendLine("  SHA-256   : ${cert.fingerprints.sha256}")
            if (cert.subjectAlternativeNames.isNotEmpty()) {
                sb.appendLine("  SANs      : ${cert.subjectAlternativeNames.joinToString(", ")}")
            }
            sb.appendLine()
        }
    }

    sb.appendLine("--- Généré par CertCheck ---")
    return sb.toString()
}

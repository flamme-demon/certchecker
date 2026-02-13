package de.guenthers.certcheck.ui.screens

import androidx.compose.animation.*
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import de.guenthers.certcheck.MainUiState
import de.guenthers.certcheck.database.CheckHistoryEntity
import de.guenthers.certcheck.database.FavoriteEntity
import de.guenthers.certcheck.model.*
import de.guenthers.certcheck.ui.components.*
import de.guenthers.certcheck.ui.theme.*
import java.text.SimpleDateFormat
import java.util.Date
import java.util.*

// ============================================================================
// Dashboard (Home Tab)
// ============================================================================

@Composable
fun DashboardContent(
    uiState: MainUiState,
    onHostnameChanged: (String) -> Unit,
    onCheck: () -> Unit,
    latestChecks: List<CheckHistoryEntity>,
    onHistoryItemClick: (CertCheckResult) -> Unit,
    onDomainCheck: (String, Int) -> Unit,
    modifier: Modifier = Modifier,
) {
    val expiringDomains = remember(latestChecks) {
        latestChecks.filter { it.daysUntilExpiry != null && it.daysUntilExpiry!! <= 30 }
    }
    val totalChecked = latestChecks.size
    val okCount = latestChecks.count { it.overallStatus == "OK" }
    val warningCount = latestChecks.count { it.overallStatus == "WARNING" }
    val errorCount = latestChecks.count { it.overallStatus in listOf("CRITICAL", "ERROR") }

    LazyColumn(
        modifier = modifier.fillMaxSize(),
        contentPadding = PaddingValues(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        // Description
        item {
            Text(
                text = "Vérifiez les certificats SSL/TLS du point de vue d'Android.",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }

        // Search input
        item {
            OutlinedTextField(
                value = uiState.hostname,
                onValueChange = onHostnameChanged,
                modifier = Modifier.fillMaxWidth(),
                label = { Text("Nom de domaine") },
                placeholder = { Text("exemple.com") },
                leadingIcon = {
                    Icon(Icons.Filled.Language, contentDescription = null)
                },
                trailingIcon = {
                    if (uiState.hostname.isNotEmpty()) {
                        IconButton(onClick = { onHostnameChanged("") }) {
                            Icon(Icons.Filled.Clear, contentDescription = "Effacer")
                        }
                    }
                },
                singleLine = true,
                keyboardOptions = KeyboardOptions(
                    keyboardType = KeyboardType.Uri,
                    imeAction = ImeAction.Go
                ),
                keyboardActions = KeyboardActions(onGo = { onCheck() }),
                shape = MaterialTheme.shapes.medium,
            )
        }

        // Check button
        item {
            Button(
                onClick = onCheck,
                modifier = Modifier.fillMaxWidth(),
                enabled = uiState.hostname.isNotBlank() && !uiState.isLoading,
                shape = MaterialTheme.shapes.medium,
            ) {
                if (uiState.isLoading) {
                    CircularProgressIndicator(
                        modifier = Modifier.size(20.dp),
                        color = MaterialTheme.colorScheme.onPrimary,
                        strokeWidth = 2.dp
                    )
                    Spacer(modifier = Modifier.width(8.dp))
                    Text("Vérification en cours…")
                } else {
                    Icon(Icons.Filled.Search, contentDescription = null)
                    Spacer(modifier = Modifier.width(8.dp))
                    Text("Vérifier le certificat")
                }
            }
        }

        // Quick test chips
        item {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                listOf("google.com", "expired.badssl.com", "self-signed.badssl.com").forEach { host ->
                    SuggestionChip(
                        onClick = { onHostnameChanged(host) },
                        label = { Text(host, style = MaterialTheme.typography.labelSmall) },
                    )
                }
            }
        }

        // Expiring domains card
        if (expiringDomains.isNotEmpty()) {
            item {
                Spacer(modifier = Modifier.height(4.dp))
                ExpiringDomainsCard(
                    domains = expiringDomains,
                    onDomainClick = onDomainCheck,
                )
            }
        }

        // Stats card
        if (latestChecks.isNotEmpty()) {
            item {
                StatsCard(
                    total = totalChecked,
                    ok = okCount,
                    warning = warningCount,
                    error = errorCount,
                )
            }
        }

        // Recent session checks
        if (uiState.history.isNotEmpty()) {
            item {
                Spacer(modifier = Modifier.height(4.dp))
                Text(
                    text = "Dernières vérifications",
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Bold,
                )
            }

            items(uiState.history) { result ->
                RecentCheckItem(result = result, onClick = { onHistoryItemClick(result) })
            }
        }

        item { Spacer(modifier = Modifier.height(8.dp)) }
    }
}

@Composable
private fun ExpiringDomainsCard(
    domains: List<CheckHistoryEntity>,
    onDomainClick: (String, Int) -> Unit,
) {
    OutlinedCard(
        modifier = Modifier.fillMaxWidth(),
        shape = MaterialTheme.shapes.medium,
    ) {
        Column(modifier = Modifier.padding(16.dp)) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                Icon(
                    Icons.Filled.Warning,
                    contentDescription = null,
                    tint = OrangeWarning,
                    modifier = Modifier.size(20.dp)
                )
                Text(
                    text = "Expirent dans moins de 30 jours",
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.Bold,
                    color = OrangeWarning,
                )
            }
            Spacer(modifier = Modifier.height(12.dp))

            domains.forEach { check ->
                val hostDisplay = if (check.port == 443) check.hostname
                    else "${check.hostname}:${check.port}"
                val daysText = when {
                    check.daysUntilExpiry == null -> "?"
                    check.daysUntilExpiry!! <= 0 -> "Expiré"
                    check.daysUntilExpiry!! == 1L -> "1 jour"
                    else -> "${check.daysUntilExpiry} jours"
                }
                val daysColor = when {
                    check.daysUntilExpiry == null -> MaterialTheme.colorScheme.onSurfaceVariant
                    check.daysUntilExpiry!! <= 7 -> RedCritical
                    else -> OrangeWarning
                }

                Surface(
                    onClick = { onDomainClick(check.hostname, check.port) },
                    shape = MaterialTheme.shapes.small,
                ) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(vertical = 8.dp, horizontal = 4.dp),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(
                            text = hostDisplay,
                            style = MaterialTheme.typography.bodyMedium,
                            modifier = Modifier.weight(1f),
                        )
                        Text(
                            text = daysText,
                            style = MaterialTheme.typography.labelMedium,
                            fontWeight = FontWeight.Bold,
                            color = daysColor,
                        )
                    }
                }
                if (check != domains.last()) {
                    HorizontalDivider(modifier = Modifier.padding(vertical = 2.dp))
                }
            }
        }
    }
}

@Composable
private fun StatsCard(total: Int, ok: Int, warning: Int, error: Int) {
    OutlinedCard(
        modifier = Modifier.fillMaxWidth(),
        shape = MaterialTheme.shapes.medium,
    ) {
        Column(modifier = Modifier.padding(16.dp)) {
            Text(
                text = "Résumé des domaines",
                style = MaterialTheme.typography.titleSmall,
                fontWeight = FontWeight.Bold,
            )
            Spacer(modifier = Modifier.height(12.dp))
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceEvenly,
            ) {
                StatItem(value = total, label = "Vérifiés", color = MaterialTheme.colorScheme.primary)
                StatItem(value = ok, label = "Valides", color = GreenOk)
                StatItem(value = warning, label = "Attention", color = OrangeWarning)
                StatItem(value = error, label = "Erreurs", color = RedCritical)
            }
        }
    }
}

@Composable
private fun StatItem(value: Int, label: String, color: androidx.compose.ui.graphics.Color) {
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        Text(
            text = value.toString(),
            style = MaterialTheme.typography.headlineMedium,
            fontWeight = FontWeight.Bold,
            color = color,
        )
        Text(
            text = label,
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.Center,
        )
    }
}

@Composable
private fun RecentCheckItem(result: CertCheckResult, onClick: () -> Unit) {
    val dateFormat = remember { SimpleDateFormat("dd/MM HH:mm", Locale.getDefault()) }
    val statusColor = when (result.overallStatus) {
        CheckStatus.OK -> GreenOk
        CheckStatus.WARNING -> OrangeWarning
        CheckStatus.CRITICAL -> RedCritical
        CheckStatus.ERROR -> RedCritical
    }
    val leafCert = result.certificates.firstOrNull()
    val validityText = when {
        leafCert == null -> ""
        leafCert.isExpired -> "Expiré"
        leafCert.daysUntilExpiry <= 30 -> "Expire dans ${leafCert.daysUntilExpiry}j"
        else -> {
            val df = remember { SimpleDateFormat("dd/MM/yyyy", Locale.getDefault()) }
            "Valide jusqu'au ${df.format(leafCert.notAfter)}"
        }
    }

    OutlinedCard(
        modifier = Modifier.fillMaxWidth(),
        onClick = onClick,
        shape = MaterialTheme.shapes.medium,
    ) {
        Row(
            modifier = Modifier.padding(12.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            Icon(
                imageVector = when (result.overallStatus) {
                    CheckStatus.OK -> Icons.Filled.CheckCircle
                    CheckStatus.WARNING -> Icons.Filled.Warning
                    else -> Icons.Filled.Error
                },
                contentDescription = null,
                tint = statusColor,
                modifier = Modifier.size(24.dp)
            )
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = result.hostname,
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.Medium
                )
                if (validityText.isNotEmpty()) {
                    Text(
                        text = validityText,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
            Text(
                text = dateFormat.format(result.timestamp),
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

// ============================================================================
// History Tab
// ============================================================================

@Composable
fun HistoryContent(
    history: List<CheckHistoryEntity>,
    onDomainCheck: (String, Int) -> Unit,
    modifier: Modifier = Modifier,
) {
    if (history.isEmpty()) {
        Box(
            modifier = modifier.fillMaxSize(),
            contentAlignment = Alignment.Center
        ) {
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                Icon(
                    Icons.Filled.History,
                    contentDescription = null,
                    modifier = Modifier.size(64.dp),
                    tint = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.5f)
                )
                Spacer(modifier = Modifier.height(16.dp))
                Text(
                    text = "Aucun historique",
                    style = MaterialTheme.typography.bodyLarge,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Text(
                    text = "Les vérifications de vos favoris apparaîtront ici",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.7f),
                )
            }
        }
    } else {
        LazyColumn(
            modifier = modifier.fillMaxSize(),
            contentPadding = PaddingValues(16.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            items(history) { check ->
                HistoryEntityItem(
                    check = check,
                    onClick = { onDomainCheck(check.hostname, check.port) }
                )
            }
            item { Spacer(modifier = Modifier.height(8.dp)) }
        }
    }
}

@Composable
private fun HistoryEntityItem(check: CheckHistoryEntity, onClick: () -> Unit) {
    val dateFormat = remember { SimpleDateFormat("dd/MM/yyyy HH:mm", Locale.getDefault()) }
    val statusColor = when (check.overallStatus) {
        "OK" -> GreenOk
        "WARNING" -> OrangeWarning
        else -> RedCritical
    }
    val hostDisplay = if (check.port == 443) check.hostname
        else "${check.hostname}:${check.port}"

    val validityText = when {
        check.error != null -> check.error
        check.daysUntilExpiry == null -> "Inconnu"
        check.daysUntilExpiry!! <= 0 -> "Expiré"
        check.daysUntilExpiry!! <= 30 -> "Expire dans ${check.daysUntilExpiry}j"
        else -> "Valide (${check.daysUntilExpiry}j)"
    }

    OutlinedCard(
        modifier = Modifier.fillMaxWidth(),
        onClick = onClick,
        shape = MaterialTheme.shapes.medium,
    ) {
        Row(
            modifier = Modifier.padding(12.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            Icon(
                imageVector = when (check.overallStatus) {
                    "OK" -> Icons.Filled.CheckCircle
                    "WARNING" -> Icons.Filled.Warning
                    else -> Icons.Filled.Error
                },
                contentDescription = null,
                tint = statusColor,
                modifier = Modifier.size(24.dp)
            )
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = hostDisplay,
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.Medium
                )
                Text(
                    text = validityText,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
            Text(
                text = dateFormat.format(Date(check.checkedAt)),
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

// ============================================================================
// Favorites Tab
// ============================================================================

@Composable
fun FavoritesContent(
    favorites: List<FavoriteEntity>,
    onFavoriteClick: (FavoriteEntity) -> Unit,
    onFavoriteDelete: (Long) -> Unit,
    onFavoriteRefresh: (Long) -> Unit,
    modifier: Modifier = Modifier,
) {
    if (favorites.isEmpty()) {
        Box(
            modifier = modifier.fillMaxSize(),
            contentAlignment = Alignment.Center
        ) {
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                Icon(
                    Icons.Filled.StarBorder,
                    contentDescription = null,
                    modifier = Modifier.size(64.dp),
                    tint = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.5f)
                )
                Spacer(modifier = Modifier.height(16.dp))
                Text(
                    text = "Aucun favori",
                    style = MaterialTheme.typography.bodyLarge,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Text(
                    text = "Ajoutez des domaines en favoris pour les surveiller",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.7f),
                )
            }
        }
    } else {
        LazyColumn(
            modifier = modifier.fillMaxSize(),
            contentPadding = PaddingValues(16.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            items(favorites) { favorite ->
                FavoriteItem(
                    favorite = favorite,
                    onClick = { onFavoriteClick(favorite) },
                    onDelete = { onFavoriteDelete(favorite.id) },
                    onRefresh = { onFavoriteRefresh(favorite.id) }
                )
            }
            item { Spacer(modifier = Modifier.height(8.dp)) }
        }
    }
}

@Composable
private fun FavoriteItem(
    favorite: FavoriteEntity,
    onClick: () -> Unit,
    onDelete: () -> Unit,
    onRefresh: () -> Unit,
) {
    val hostWithPort = if (favorite.port == 443) {
        favorite.hostname
    } else {
        "${favorite.hostname}:${favorite.port}"
    }

    OutlinedCard(
        modifier = Modifier.fillMaxWidth(),
        onClick = onClick,
        shape = MaterialTheme.shapes.medium,
    ) {
        Row(
            modifier = Modifier.padding(12.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            Icon(
                imageVector = Icons.Filled.Star,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.primary,
                modifier = Modifier.size(24.dp)
            )
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = hostWithPort,
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.Medium
                )
                favorite.lastCheckedAt?.let { timestamp ->
                    Text(
                        text = "Dernière vérification: ${SimpleDateFormat("dd/MM/yyyy HH:mm", Locale.getDefault()).format(Date(timestamp))}",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
            IconButton(onClick = onRefresh) {
                Icon(Icons.Filled.Refresh, contentDescription = "Vérifier")
            }
            IconButton(onClick = onDelete) {
                Icon(
                    Icons.Filled.Delete,
                    contentDescription = "Supprimer",
                    tint = MaterialTheme.colorScheme.error
                )
            }
        }
    }
}

// ============================================================================
// Result Screen - Détails complets du résultat
// ============================================================================

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ResultScreen(
    result: CertCheckResult,
    onBack: () -> Unit,
    onRecheck: () -> Unit,
    isFavorite: Boolean,
    onToggleFavorite: () -> Unit,
) {
    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(result.hostname) },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Retour")
                    }
                },
                actions = {
                    IconButton(onClick = onToggleFavorite) {
                        Icon(
                            imageVector = if (isFavorite) Icons.Filled.Star else Icons.Filled.StarBorder,
                            contentDescription = if (isFavorite) "Retirer des favoris" else "Ajouter aux favoris",
                            tint = if (isFavorite) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurface
                        )
                    }
                    IconButton(onClick = onRecheck) {
                        Icon(Icons.Filled.Refresh, contentDescription = "Revérifier")
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = MaterialTheme.colorScheme.surface
                )
            )
        }
    ) { padding ->
        LazyColumn(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding),
            contentPadding = PaddingValues(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            // Status global
            item {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.Center
                ) {
                    StatusBadge(status = result.overallStatus)
                }
            }

            // Erreur globale
            if (result.error != null) {
                item {
                    IssueCard(
                        CertIssue(
                            type = IssueType.ANDROID_SPECIFIC_TRUST_ISSUE,
                            severity = IssueSeverity.CRITICAL,
                            title = "Erreur de connexion",
                            description = result.error
                        )
                    )
                }
            }

            // Infos de connexion
            if (result.tlsVersion != null) {
                item {
                    ConnectionInfoCard(result = result)
                }
            }

            // Problèmes détectés
            if (result.issues.isNotEmpty()) {
                item {
                    Text(
                        text = "Problèmes détectés (${result.issues.size})",
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.Bold,
                    )
                }
                items(result.issues) { issue ->
                    IssueCard(issue = issue)
                }
            }

            // Chaîne de certificats
            if (result.certificates.isNotEmpty()) {
                item {
                    Spacer(modifier = Modifier.height(4.dp))
                    Text(
                        text = "Chaîne de certificats (${result.certificates.size})",
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.Bold,
                    )
                }
                items(result.certificates) { cert ->
                    CertificateCard(certInfo = cert)
                }
            }

            // Spacer en bas
            item { Spacer(modifier = Modifier.height(16.dp)) }
        }
    }
}

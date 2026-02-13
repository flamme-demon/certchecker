package de.guenthers.certcheck.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import coil.compose.AsyncImage
import coil.request.ImageRequest
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
    recentHistory: List<CheckHistoryEntity>,
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

        // Recent checks from database
        if (recentHistory.isNotEmpty()) {
            item {
                Spacer(modifier = Modifier.height(4.dp))
                Text(
                    text = "Dernières vérifications",
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Bold,
                )
            }

            items(recentHistory.take(10)) { check ->
                HistoryEntityItem(
                    check = check,
                    onClick = { onDomainCheck(check.hostname, check.port) }
                )
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

// ============================================================================
// Shared history item composable
// ============================================================================

@Composable
fun HistoryEntityItem(check: CheckHistoryEntity, onClick: () -> Unit) {
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
            horizontalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            // Favicon
            Box(
                modifier = Modifier
                    .size(32.dp)
                    .clip(CircleShape)
                    .background(MaterialTheme.colorScheme.surfaceVariant),
                contentAlignment = Alignment.Center
            ) {
                AsyncImage(
                    model = ImageRequest.Builder(LocalContext.current)
                        .data("https://www.google.com/s2/favicons?domain=${check.hostname}&sz=64")
                        .crossfade(true)
                        .build(),
                    contentDescription = null,
                    modifier = Modifier
                        .size(22.dp)
                        .clip(CircleShape),
                    contentScale = ContentScale.Fit,
                )
            }
            // Status icon
            Icon(
                imageVector = when (check.overallStatus) {
                    "OK" -> Icons.Filled.CheckCircle
                    "WARNING" -> Icons.Filled.Warning
                    else -> Icons.Filled.Error
                },
                contentDescription = null,
                tint = statusColor,
                modifier = Modifier.size(18.dp)
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
                    text = "Vos vérifications apparaîtront ici",
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

// ============================================================================
// Favorites Tab
// ============================================================================

@Composable
fun FavoritesContent(
    favorites: List<FavoriteEntity>,
    latestChecks: List<CheckHistoryEntity>,
    onFavoriteClick: (FavoriteEntity) -> Unit,
    onFavoriteDelete: (Long) -> Unit,
    onFavoriteRefresh: (Long) -> Unit,
    modifier: Modifier = Modifier,
) {
    val checksByFavoriteId = remember(latestChecks) {
        latestChecks.associateBy { it.favoriteId }
    }

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
                    lastCheck = checksByFavoriteId[favorite.id],
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
    lastCheck: CheckHistoryEntity?,
    onClick: () -> Unit,
    onDelete: () -> Unit,
    onRefresh: () -> Unit,
) {
    val hostWithPort = if (favorite.port == 443) {
        favorite.hostname
    } else {
        "${favorite.hostname}:${favorite.port}"
    }

    val statusColor = when (lastCheck?.overallStatus) {
        "OK" -> GreenOk
        "WARNING" -> OrangeWarning
        "CRITICAL", "ERROR" -> RedCritical
        else -> MaterialTheme.colorScheme.onSurfaceVariant
    }

    val statusIcon = when (lastCheck?.overallStatus) {
        "OK" -> Icons.Filled.CheckCircle
        "WARNING" -> Icons.Filled.Warning
        "CRITICAL", "ERROR" -> Icons.Filled.Error
        else -> Icons.Filled.HelpOutline
    }

    val expiryText = when {
        lastCheck == null -> null
        lastCheck.daysUntilExpiry == null -> null
        lastCheck.daysUntilExpiry!! <= 0 -> "Expiré"
        lastCheck.daysUntilExpiry!! == 1L -> "Expire dans 1 jour"
        lastCheck.daysUntilExpiry!! <= 30 -> "Expire dans ${lastCheck.daysUntilExpiry}j"
        else -> "Valide (${lastCheck.daysUntilExpiry}j)"
    }

    val expiryColor = when {
        lastCheck?.daysUntilExpiry == null -> MaterialTheme.colorScheme.onSurfaceVariant
        lastCheck.daysUntilExpiry!! <= 7 -> RedCritical
        lastCheck.daysUntilExpiry!! <= 30 -> OrangeWarning
        else -> GreenOk
    }

    val dateFormat = remember { SimpleDateFormat("dd/MM/yyyy HH:mm", Locale.getDefault()) }

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
            // Favicon
            Box(
                modifier = Modifier
                    .size(40.dp)
                    .clip(CircleShape)
                    .background(MaterialTheme.colorScheme.surfaceVariant),
                contentAlignment = Alignment.Center
            ) {
                AsyncImage(
                    model = ImageRequest.Builder(LocalContext.current)
                        .data("https://www.google.com/s2/favicons?domain=${favorite.hostname}&sz=64")
                        .crossfade(true)
                        .build(),
                    contentDescription = null,
                    modifier = Modifier
                        .size(28.dp)
                        .clip(CircleShape),
                    contentScale = ContentScale.Fit,
                )
            }

            // Info
            Column(modifier = Modifier.weight(1f)) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(6.dp)
                ) {
                    Text(
                        text = hostWithPort,
                        style = MaterialTheme.typography.titleSmall,
                        fontWeight = FontWeight.Bold
                    )
                    Icon(
                        imageVector = statusIcon,
                        contentDescription = null,
                        tint = statusColor,
                        modifier = Modifier.size(16.dp)
                    )
                }

                if (expiryText != null) {
                    Text(
                        text = expiryText,
                        style = MaterialTheme.typography.bodySmall,
                        fontWeight = FontWeight.Medium,
                        color = expiryColor,
                    )
                }

                favorite.lastCheckedAt?.let { timestamp ->
                    Text(
                        text = "Testé le ${dateFormat.format(Date(timestamp))}",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                } ?: run {
                    if (lastCheck == null) {
                        Text(
                            text = "Jamais vérifié",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.7f),
                        )
                    }
                }
            }

            // Actions
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
// Result Content - Détails complets du résultat (sans Scaffold propre)
// ============================================================================

@Composable
fun ResultContent(
    result: CertCheckResult,
    modifier: Modifier = Modifier,
) {
    LazyColumn(
        modifier = modifier.fillMaxSize(),
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

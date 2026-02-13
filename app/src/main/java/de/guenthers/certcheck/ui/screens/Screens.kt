package de.guenthers.certcheck.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
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
    alertThresholdDays: Int = 30,
    modifier: Modifier = Modifier,
) {
    val expiringDomains = remember(latestChecks, alertThresholdDays) {
        latestChecks.filter { it.daysUntilExpiry != null && it.daysUntilExpiry!! <= alertThresholdDays }
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
        // Refresh progress
        if (uiState.isRefreshingFavorites) {
            item {
                OutlinedCard(
                    modifier = Modifier.fillMaxWidth(),
                    shape = MaterialTheme.shapes.medium,
                ) {
                    Row(
                        modifier = Modifier.padding(12.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(12.dp),
                    ) {
                        CircularProgressIndicator(
                            modifier = Modifier.size(20.dp),
                            strokeWidth = 2.dp,
                        )
                        Text(
                            text = "Mise à jour des favoris… ${uiState.refreshProgress}/${uiState.refreshTotal}",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
            }
        }

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
                    alertThresholdDays = alertThresholdDays,
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
    alertThresholdDays: Int = 30,
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
                    text = "Expirent dans moins de $alertThresholdDays jours",
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
    onFavoriteToggleNotifications: (Long) -> Unit,
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
                    onRefresh = { onFavoriteRefresh(favorite.id) },
                    onToggleNotifications = { onFavoriteToggleNotifications(favorite.id) },
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
    onToggleNotifications: () -> Unit,
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
            IconButton(onClick = onToggleNotifications) {
                Icon(
                    imageVector = if (favorite.notificationsEnabled) Icons.Filled.Notifications
                        else Icons.Filled.NotificationsOff,
                    contentDescription = if (favorite.notificationsEnabled) "Désactiver les notifications"
                        else "Activer les notifications",
                    tint = if (favorite.notificationsEnabled) MaterialTheme.colorScheme.primary
                        else MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.5f),
                    modifier = Modifier.size(20.dp),
                )
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
// Settings Screen
// ============================================================================

@Composable
fun SettingsContent(
    checkHour: Int,
    alertThresholdDays: Int,
    notificationsEnabled: Boolean,
    onCheckHourChanged: (Int) -> Unit,
    onAlertThresholdChanged: (Int) -> Unit,
    onNotificationsToggled: (Boolean) -> Unit,
    widgetColor: Int,
    widgetOpacity: Int,
    onWidgetColorChanged: (Int) -> Unit,
    onWidgetOpacityChanged: (Int) -> Unit,
    modifier: Modifier = Modifier,
) {
    var showTimePicker by remember { mutableStateOf(false) }

    LazyColumn(
        modifier = modifier.fillMaxSize(),
        contentPadding = PaddingValues(16.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp)
    ) {
        // Section: Notifications
        item {
            Text(
                text = "Notifications",
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.Bold,
                color = MaterialTheme.colorScheme.primary,
            )
        }

        item {
            OutlinedCard(
                modifier = Modifier.fillMaxWidth(),
                shape = MaterialTheme.shapes.medium,
            ) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(16.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween,
                ) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            text = "Activer les notifications",
                            style = MaterialTheme.typography.bodyLarge,
                        )
                        Text(
                            text = "Recevoir des alertes pour les certificats",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    Switch(
                        checked = notificationsEnabled,
                        onCheckedChange = onNotificationsToggled,
                    )
                }
            }
        }

        // Section: Vérification automatique
        item {
            Text(
                text = "Vérification automatique",
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.Bold,
                color = MaterialTheme.colorScheme.primary,
            )
        }

        item {
            OutlinedCard(
                modifier = Modifier.fillMaxWidth(),
                shape = MaterialTheme.shapes.medium,
            ) {
                Column(modifier = Modifier.padding(16.dp)) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.SpaceBetween,
                    ) {
                        Column(modifier = Modifier.weight(1f)) {
                            Text(
                                text = "Heure de vérification",
                                style = MaterialTheme.typography.bodyLarge,
                            )
                            Text(
                                text = "Les favoris seront vérifiés chaque jour à cette heure",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                        FilledTonalButton(onClick = { showTimePicker = true }) {
                            Icon(
                                Icons.Filled.Schedule,
                                contentDescription = null,
                                modifier = Modifier.size(18.dp),
                            )
                            Spacer(modifier = Modifier.width(6.dp))
                            Text(String.format(Locale.getDefault(), "%02d:00", checkHour))
                        }
                    }
                }
            }
        }

        // Section: Seuil d'alerte
        item {
            Text(
                text = "Seuil d'alerte",
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.Bold,
                color = MaterialTheme.colorScheme.primary,
            )
        }

        item {
            OutlinedCard(
                modifier = Modifier.fillMaxWidth(),
                shape = MaterialTheme.shapes.medium,
            ) {
                Column(modifier = Modifier.padding(16.dp)) {
                    Text(
                        text = "Alerte avant expiration",
                        style = MaterialTheme.typography.bodyLarge,
                    )
                    Text(
                        text = "Recevoir une alerte quand un certificat expire dans moins de $alertThresholdDays jours",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Spacer(modifier = Modifier.height(16.dp))

                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Text(
                            text = "7j",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                        Slider(
                            value = alertThresholdDays.toFloat(),
                            onValueChange = { onAlertThresholdChanged(it.toInt()) },
                            valueRange = 7f..90f,
                            steps = 82,
                            modifier = Modifier
                                .weight(1f)
                                .padding(horizontal = 8.dp),
                        )
                        Text(
                            text = "90j",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }

                    Text(
                        text = "$alertThresholdDays jours",
                        style = MaterialTheme.typography.titleLarge,
                        fontWeight = FontWeight.Bold,
                        color = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.fillMaxWidth(),
                        textAlign = TextAlign.Center,
                    )
                }
            }
        }

        // Section: Widget
        item {
            Text(
                text = "Widget",
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.Bold,
                color = MaterialTheme.colorScheme.primary,
            )
        }

        item {
            OutlinedCard(
                modifier = Modifier.fillMaxWidth(),
                shape = MaterialTheme.shapes.medium,
            ) {
                Column(modifier = Modifier.padding(16.dp)) {
                    Text(
                        text = "Couleur de fond",
                        style = MaterialTheme.typography.bodyLarge,
                    )
                    Text(
                        text = "Choisissez la couleur d'arrière-plan du widget",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Spacer(modifier = Modifier.height(12.dp))

                    val colorOptions = listOf(
                        0x000000 to "Noir",
                        0x1A1A2E to "Bleu nuit",
                        0x1B2631 to "Ardoise",
                        0x0D1B2A to "Marine",
                        0x2C2C2C to "Gris foncé",
                        0xFFFFFF to "Blanc",
                    )

                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceEvenly,
                    ) {
                        colorOptions.forEach { (color, label) ->
                            val isSelected = widgetColor == color
                            val bgColor = Color(color or (0xFF shl 24))
                            val borderColor = if (isSelected) MaterialTheme.colorScheme.primary
                                else MaterialTheme.colorScheme.outline.copy(alpha = 0.3f)

                            Column(
                                horizontalAlignment = Alignment.CenterHorizontally,
                                modifier = Modifier
                                    .clip(RoundedCornerShape(8.dp))
                                    .clickable { onWidgetColorChanged(color) }
                                    .padding(4.dp),
                            ) {
                                Box(
                                    modifier = Modifier
                                        .size(36.dp)
                                        .clip(CircleShape)
                                        .background(bgColor)
                                        .border(
                                            width = if (isSelected) 3.dp else 1.dp,
                                            color = borderColor,
                                            shape = CircleShape,
                                        ),
                                )
                                Text(
                                    text = label,
                                    style = MaterialTheme.typography.labelSmall,
                                    color = if (isSelected) MaterialTheme.colorScheme.primary
                                        else MaterialTheme.colorScheme.onSurfaceVariant,
                                    fontSize = 9.sp,
                                )
                            }
                        }
                    }

                    Spacer(modifier = Modifier.height(20.dp))

                    Text(
                        text = "Opacité",
                        style = MaterialTheme.typography.bodyLarge,
                    )
                    Text(
                        text = "Réglez la transparence du widget",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Spacer(modifier = Modifier.height(8.dp))

                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Text(
                            text = "0%",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                        Slider(
                            value = widgetOpacity.toFloat(),
                            onValueChange = { onWidgetOpacityChanged(it.toInt()) },
                            valueRange = 0f..100f,
                            modifier = Modifier
                                .weight(1f)
                                .padding(horizontal = 8.dp),
                        )
                        Text(
                            text = "100%",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }

                    Text(
                        text = "${widgetOpacity}%",
                        style = MaterialTheme.typography.titleLarge,
                        fontWeight = FontWeight.Bold,
                        color = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.fillMaxWidth(),
                        textAlign = TextAlign.Center,
                    )

                    // Preview
                    Spacer(modifier = Modifier.height(12.dp))
                    Text(
                        text = "Aperçu",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Spacer(modifier = Modifier.height(4.dp))
                    val alpha = (widgetOpacity * 255 / 100)
                    val previewColor = Color((alpha shl 24) or widgetColor)
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(48.dp)
                            .clip(RoundedCornerShape(16.dp))
                            .background(previewColor),
                        contentAlignment = Alignment.Center,
                    ) {
                        Text(
                            text = "CertCheck",
                            color = if (widgetColor == 0xFFFFFF) Color(0xFF333333) else Color(0x90CAF9.toInt() or (0xFF shl 24)),
                            style = MaterialTheme.typography.bodyMedium,
                            fontWeight = FontWeight.Bold,
                        )
                    }
                }
            }
        }

        // Section: Informations
        item {
            Text(
                text = "Informations",
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.Bold,
                color = MaterialTheme.colorScheme.primary,
            )
        }

        item {
            OutlinedCard(
                modifier = Modifier.fillMaxWidth(),
                shape = MaterialTheme.shapes.medium,
            ) {
                Column(modifier = Modifier.padding(16.dp)) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        Icon(
                            Icons.Filled.Info,
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.primary,
                            modifier = Modifier.size(20.dp),
                        )
                        Text(
                            text = "Comment ça marche ?",
                            style = MaterialTheme.typography.bodyMedium,
                            fontWeight = FontWeight.Bold,
                        )
                    }
                    Spacer(modifier = Modifier.height(8.dp))
                    Text(
                        text = "CertCheck vérifie automatiquement les certificats SSL/TLS de vos domaines favoris une fois par jour. " +
                                "Vous recevrez une notification si un certificat expire bientôt, si un changement est détecté, " +
                                "ou si une erreur de connexion survient.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        }

        item { Spacer(modifier = Modifier.height(8.dp)) }
    }

    // Time picker dialog
    if (showTimePicker) {
        TimePickerDialog(
            currentHour = checkHour,
            onHourSelected = { hour ->
                onCheckHourChanged(hour)
                showTimePicker = false
            },
            onDismiss = { showTimePicker = false },
        )
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun TimePickerDialog(
    currentHour: Int,
    onHourSelected: (Int) -> Unit,
    onDismiss: () -> Unit,
) {
    val timePickerState = rememberTimePickerState(
        initialHour = currentHour,
        initialMinute = 0,
        is24Hour = true,
    )

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Heure de vérification") },
        text = {
            Box(
                modifier = Modifier.fillMaxWidth(),
                contentAlignment = Alignment.Center,
            ) {
                TimePicker(state = timePickerState)
            }
        },
        confirmButton = {
            TextButton(onClick = { onHourSelected(timePickerState.hour) }) {
                Text("Confirmer")
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text("Annuler")
            }
        },
    )
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

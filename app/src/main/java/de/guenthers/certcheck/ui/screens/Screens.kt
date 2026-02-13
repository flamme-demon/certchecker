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
import androidx.compose.ui.unit.dp
import de.guenthers.certcheck.MainUiState
import de.guenthers.certcheck.database.FavoriteEntity
import de.guenthers.certcheck.model.*
import de.guenthers.certcheck.ui.components.*
import de.guenthers.certcheck.ui.theme.*
import java.text.SimpleDateFormat
import java.util.Date
import java.util.*

// ============================================================================
// Home Screen - Saisie du hostname + historique
// ============================================================================

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun HomeScreen(
    uiState: MainUiState,
    onHostnameChanged: (String) -> Unit,
    onCheck: () -> Unit,
    onHistoryItemClick: (CertCheckResult) -> Unit,
    favorites: List<de.guenthers.certcheck.database.FavoriteEntity>,
    onFavoriteClick: (de.guenthers.certcheck.database.FavoriteEntity) -> Unit,
    onFavoriteDelete: (Long) -> Unit,
    onFavoriteRefresh: (Long) -> Unit,
) {
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
                        Text("CertCheck")
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
            // Description
            item {
                Text(
                    text = "Vérifiez les certificats SSL/TLS du point de vue d'Android. " +
                            "Certains certificats fonctionnent dans un navigateur mais pas dans une app Android.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }

            // Input
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

            // Button
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

            // Quick test buttons
            item {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    listOf("google.com", "expired.badssl.com", "self-signed.badssl.com").forEach { host ->
                        SuggestionChip(
                            onClick = {
                                onHostnameChanged(host)
                            },
                            label = { Text(host, style = MaterialTheme.typography.labelSmall) },
                        )
                    }
                }
            }

            // History
            if (uiState.history.isNotEmpty()) {
                item {
                    Spacer(modifier = Modifier.height(8.dp))
                    Text(
                        text = "Historique",
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.Bold,
                    )
                }

                items(uiState.history) { result ->
                    HistoryItem(result = result, onClick = { onHistoryItemClick(result) })
                }
            }

            // Favorites
            if (favorites.isNotEmpty()) {
                item {
                    Spacer(modifier = Modifier.height(8.dp))
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(
                            text = "Favoris",
                            style = MaterialTheme.typography.titleMedium,
                            fontWeight = FontWeight.Bold,
                        )
                    }
                }

                items(favorites) { favorite ->
                    FavoriteItem(
                        favorite = favorite,
                        onClick = { onFavoriteClick(favorite) },
                        onDelete = { onFavoriteDelete(favorite.id) },
                        onRefresh = { onFavoriteRefresh(favorite.id) }
                    )
                }
            }
        }
    }
}

@Composable
private fun HistoryItem(result: CertCheckResult, onClick: () -> Unit) {
    val dateFormat = remember { SimpleDateFormat("HH:mm:ss", Locale.getDefault()) }
    val statusColor = when (result.overallStatus) {
        CheckStatus.OK -> GreenOk
        CheckStatus.WARNING -> OrangeWarning
        CheckStatus.CRITICAL -> RedCritical
        CheckStatus.ERROR -> RedCritical
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
                Text(
                    text = "${result.issues.size} problème(s) • ${dateFormat.format(result.timestamp)}",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
            Icon(
                Icons.Filled.ChevronRight,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.onSurfaceVariant
            )
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

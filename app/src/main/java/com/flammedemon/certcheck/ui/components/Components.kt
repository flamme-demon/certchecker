package com.flammedemon.certcheck.ui.components

import androidx.compose.animation.animateContentSize
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material.icons.outlined.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.flammedemon.certcheck.model.*
import com.flammedemon.certcheck.ui.theme.*
import java.text.SimpleDateFormat
import java.util.*

// ============================================================================
// Status Badge
// ============================================================================

@Composable
fun StatusBadge(status: CheckStatus, modifier: Modifier = Modifier) {
    val (icon, color, bgColor, label) = when (status) {
        CheckStatus.OK -> listOf(Icons.Filled.CheckCircle, GreenOk, GreenOkLight, "OK")
        CheckStatus.WARNING -> listOf(Icons.Filled.Warning, OrangeWarning, OrangeWarningLight, "ATTENTION")
        CheckStatus.CRITICAL -> listOf(Icons.Filled.Error, RedCritical, RedCriticalLight, "CRITIQUE")
        CheckStatus.ERROR -> listOf(Icons.Filled.ErrorOutline, RedCritical, RedCriticalLight, "ERREUR")
    }

    Surface(
        modifier = modifier,
        shape = RoundedCornerShape(12.dp),
        color = bgColor as Color,
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 16.dp, vertical = 10.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            Icon(
                imageVector = icon as androidx.compose.ui.graphics.vector.ImageVector,
                contentDescription = null,
                tint = color as Color,
                modifier = Modifier.size(24.dp)
            )
            Text(
                text = label as String,
                color = color,
                fontWeight = FontWeight.Bold,
                fontSize = 14.sp
            )
        }
    }
}

// ============================================================================
// Connection Info Card
// ============================================================================

@Composable
fun ConnectionInfoCard(result: CertCheckResult) {
    OutlinedCard(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(12.dp)
    ) {
        Column(modifier = Modifier.padding(16.dp)) {
            Text(
                text = "Connexion",
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.Bold
            )
            Spacer(modifier = Modifier.height(12.dp))

            InfoRow("Hôte", result.hostname)
            InfoRow("Port", result.port.toString())
            result.tlsVersion?.let { InfoRow("TLS", it) }
            result.cipherSuite?.let { InfoRow("Cipher", it, mono = true) }

            Spacer(modifier = Modifier.height(8.dp))
            Divider()
            Spacer(modifier = Modifier.height(8.dp))

            Row(horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                TrustIndicator(
                    label = "Trust Android",
                    trusted = result.trustedByAndroid,
                    modifier = Modifier.weight(1f)
                )
                TrustIndicator(
                    label = "Hostname",
                    trusted = result.hostnameMatches,
                    modifier = Modifier.weight(1f)
                )
                TrustIndicator(
                    label = "Chaîne",
                    trusted = result.chainValid,
                    modifier = Modifier.weight(1f)
                )
            }
        }
    }
}

@Composable
private fun TrustIndicator(label: String, trusted: Boolean, modifier: Modifier = Modifier) {
    Column(
        modifier = modifier,
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Box(
            modifier = Modifier
                .size(32.dp)
                .clip(CircleShape)
                .background(if (trusted) GreenOkLight else RedCriticalLight),
            contentAlignment = Alignment.Center
        ) {
            Icon(
                imageVector = if (trusted) Icons.Filled.Check else Icons.Filled.Close,
                contentDescription = null,
                tint = if (trusted) GreenOk else RedCritical,
                modifier = Modifier.size(18.dp)
            )
        }
        Spacer(modifier = Modifier.height(4.dp))
        Text(
            text = label,
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
    }
}

// ============================================================================
// Certificate Card
// ============================================================================

@Composable
fun CertificateCard(certInfo: CertificateInfo) {
    var expanded by remember { mutableStateOf(false) }
    val dateFormat = remember { SimpleDateFormat("dd MMM yyyy HH:mm", Locale.getDefault()) }

    OutlinedCard(
        modifier = Modifier
            .fillMaxWidth()
            .animateContentSize(),
        shape = RoundedCornerShape(12.dp)
    ) {
        Column(
            modifier = Modifier
                .clickable { expanded = !expanded }
                .padding(16.dp)
        ) {
            // Header
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                Icon(
                    imageVector = when {
                        certInfo.isTrustAnchor -> Icons.Filled.Shield
                        certInfo.position == 0 -> Icons.Filled.Language
                        else -> Icons.Filled.AccountTree
                    },
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.size(20.dp)
                )
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = certInfo.label,
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.primary
                    )
                    Text(
                        text = extractCN(certInfo.subject) ?: certInfo.subject,
                        style = MaterialTheme.typography.titleSmall,
                        fontWeight = FontWeight.Bold,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                }

                // Status chip
                val statusColor = when {
                    certInfo.isExpired -> RedCritical
                    certInfo.isNotYetValid -> RedCritical
                    certInfo.daysUntilExpiry <= 30 -> OrangeWarning
                    else -> GreenOk
                }
                Surface(
                    shape = RoundedCornerShape(8.dp),
                    color = statusColor.copy(alpha = 0.1f)
                ) {
                    Text(
                        text = when {
                            certInfo.isExpired -> "EXPIRÉ"
                            certInfo.isNotYetValid -> "PAS ENCORE VALIDE"
                            certInfo.daysUntilExpiry <= 30 -> "${certInfo.daysUntilExpiry}j"
                            else -> "VALIDE"
                        },
                        modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp),
                        color = statusColor,
                        fontSize = 11.sp,
                        fontWeight = FontWeight.Bold
                    )
                }

                Icon(
                    imageVector = if (expanded) Icons.Filled.ExpandLess else Icons.Filled.ExpandMore,
                    contentDescription = if (expanded) "Réduire" else "Développer",
                    tint = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }

            // Expanded details
            if (expanded) {
                Spacer(modifier = Modifier.height(12.dp))
                HorizontalDivider()
                Spacer(modifier = Modifier.height(12.dp))

                InfoRow("Émetteur", extractCN(certInfo.issuer) ?: certInfo.issuer)
                InfoRow("Valide du", dateFormat.format(certInfo.notBefore))
                InfoRow("Valide jusqu'au", dateFormat.format(certInfo.notAfter))
                InfoRow("Algorithme", certInfo.signatureAlgorithm)
                InfoRow(
                    "Clé publique",
                    "${certInfo.publicKeyAlgorithm} ${certInfo.publicKeySize} bits"
                )
                InfoRow("N° série", certInfo.serialNumber, mono = true)
                InfoRow("Version", "X.509 v${certInfo.version}")

                if (certInfo.subjectAlternativeNames.isNotEmpty()) {
                    Spacer(modifier = Modifier.height(8.dp))
                    Text(
                        text = "Subject Alternative Names",
                        style = MaterialTheme.typography.labelMedium,
                        fontWeight = FontWeight.Bold,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    Spacer(modifier = Modifier.height(4.dp))
                    certInfo.subjectAlternativeNames.forEach { san ->
                        Text(
                            text = "• $san",
                            style = MaterialTheme.typography.bodySmall,
                            fontFamily = FontFamily.Monospace,
                            color = MaterialTheme.colorScheme.onSurface,
                            modifier = Modifier.padding(start = 8.dp)
                        )
                    }
                }

                // Fingerprints
                if (certInfo.fingerprints.sha256.isNotEmpty()) {
                    Spacer(modifier = Modifier.height(8.dp))
                    Text(
                        text = "Empreintes",
                        style = MaterialTheme.typography.labelMedium,
                        fontWeight = FontWeight.Bold,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    Spacer(modifier = Modifier.height(4.dp))
                    FingerprintRow("SHA-256", certInfo.fingerprints.sha256)
                    FingerprintRow("SHA-1", certInfo.fingerprints.sha1)
                }
            }
        }
    }
}

// ============================================================================
// Issue Card
// ============================================================================

@Composable
fun IssueCard(issue: CertIssue) {
    val (bgColor, borderColor, icon) = when (issue.severity) {
        IssueSeverity.CRITICAL -> Triple(RedCriticalLight, RedCritical, Icons.Filled.Error)
        IssueSeverity.WARNING -> Triple(OrangeWarningLight, OrangeWarning, Icons.Filled.Warning)
        IssueSeverity.INFO -> Triple(BlueInfoLight, BlueInfo, Icons.Outlined.Info)
    }

    Surface(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(12.dp),
        color = bgColor,
        tonalElevation = 0.dp,
    ) {
        Row(
            modifier = Modifier.padding(12.dp),
            horizontalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            Icon(
                imageVector = icon,
                contentDescription = null,
                tint = borderColor,
                modifier = Modifier
                    .size(20.dp)
                    .padding(top = 2.dp)
            )
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = issue.title,
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.Bold,
                    color = borderColor
                )
                Spacer(modifier = Modifier.height(4.dp))
                Text(
                    text = issue.description,
                    style = MaterialTheme.typography.bodySmall,
                    color = borderColor.copy(alpha = 0.85f),
                    lineHeight = 18.sp
                )
            }
        }
    }
}

// ============================================================================
// Helpers
// ============================================================================

@Composable
fun InfoRow(label: String, value: String, mono: Boolean = false) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 3.dp),
        horizontalArrangement = Arrangement.SpaceBetween
    ) {
        Text(
            text = label,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.weight(0.35f)
        )
        Text(
            text = value,
            style = MaterialTheme.typography.bodySmall,
            fontWeight = FontWeight.Medium,
            fontFamily = if (mono) FontFamily.Monospace else FontFamily.Default,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(0.65f)
        )
    }
}

@Composable
private fun FingerprintRow(label: String, value: String) {
    Column(modifier = Modifier.padding(start = 8.dp, bottom = 4.dp)) {
        Text(
            text = label,
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        Text(
            text = value,
            style = MaterialTheme.typography.bodySmall,
            fontFamily = FontFamily.Monospace,
            fontSize = 10.sp,
            lineHeight = 14.sp,
            color = MaterialTheme.colorScheme.onSurface
        )
    }
}

private fun extractCN(dn: String): String? {
    return dn.split(",")
        .map { it.trim() }
        .firstOrNull { it.startsWith("CN=", ignoreCase = true) }
        ?.substringAfter("=")
}

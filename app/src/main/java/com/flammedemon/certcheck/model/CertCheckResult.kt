package com.flammedemon.certcheck.model

import java.security.cert.X509Certificate
import java.util.Date

/**
 * Résultat complet d'une vérification SSL depuis le point de vue Android.
 */
data class CertCheckResult(
    val hostname: String,
    val port: Int = 443,
    val timestamp: Date = Date(),
    val tlsVersion: String? = null,
    val cipherSuite: String? = null,
    val cipherAnalysis: CipherAnalysis? = null,
    val certificates: List<CertificateInfo> = emptyList(),
    val chainValid: Boolean = false,
    val trustedByAndroid: Boolean = false,
    val hostnameMatches: Boolean = false,
    val issues: List<CertIssue> = emptyList(),
    val error: String? = null,
) {
    val overallStatus: CheckStatus
        get() = when {
            error != null -> CheckStatus.ERROR
            issues.any { it.severity == IssueSeverity.CRITICAL } -> CheckStatus.CRITICAL
            issues.any { it.severity == IssueSeverity.WARNING } -> CheckStatus.WARNING
            else -> CheckStatus.OK
        }
}

/**
 * Informations sur un certificat individuel dans la chaîne.
 */
data class CertificateInfo(
    val position: Int,
    val subject: String,
    val issuer: String,
    val serialNumber: String,
    val notBefore: Date,
    val notAfter: Date,
    val signatureAlgorithm: String,
    val publicKeyAlgorithm: String,
    val publicKeySize: Int,
    val subjectAlternativeNames: List<String> = emptyList(),
    val isExpired: Boolean = false,
    val isNotYetValid: Boolean = false,
    val isSelfSigned: Boolean = false,
    val isTrustAnchor: Boolean = false,
    val fingerprints: CertFingerprints = CertFingerprints(),
    val version: Int = 3,
) {
    val daysUntilExpiry: Long
        get() {
            val diff = notAfter.time - System.currentTimeMillis()
            return diff / (1000 * 60 * 60 * 24)
        }

    val label: String
        get() = when (position) {
            0 -> "Leaf (Server)"
            else -> if (isTrustAnchor) "Root CA" else "Intermediate CA #$position"
        }
}

data class CertFingerprints(
    val sha256: String = "",
    val sha1: String = "",
)

/**
 * Problème détecté lors de la vérification.
 */
data class CertIssue(
    val type: IssueType,
    val severity: IssueSeverity,
    val title: String,
    val description: String,
)

enum class IssueType {
    EXPIRED,
    NOT_YET_VALID,
    EXPIRING_SOON,
    SELF_SIGNED,
    UNTRUSTED_ROOT,
    HOSTNAME_MISMATCH,
    WEAK_SIGNATURE,
    WEAK_KEY,
    INCOMPLETE_CHAIN,
    TLS_VERSION_OLD,
    NO_SANS,
    CHAIN_TOO_LONG,
    ANDROID_SPECIFIC_TRUST_ISSUE,
    CIPHER_WEAK,
    CIPHER_NO_FORWARD_SECRECY,
}

enum class IssueSeverity {
    INFO,
    WARNING,
    CRITICAL,
}

enum class CheckStatus {
    OK,
    WARNING,
    CRITICAL,
    ERROR,
}

/**
 * Analyse détaillée du cipher suite négocié.
 */
data class CipherAnalysis(
    val fullName: String,
    val keyExchange: String,
    val encryption: String,
    val mac: String,
    val strength: CipherStrength,
    val hasForwardSecrecy: Boolean,
    val isTls13: Boolean,
    val isAead: Boolean,
    val compatibility: List<CipherCompatibility>,
)

enum class CipherStrength {
    STRONG,
    ACCEPTABLE,
    WEAK,
}

data class CipherCompatibility(
    val platform: String,
    val supported: Boolean,
    val detail: String,
)

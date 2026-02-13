package de.guenthers.certcheck.network

import de.guenthers.certcheck.model.*
import java.net.InetSocketAddress
import java.security.MessageDigest
import java.security.cert.CertPathValidator
import java.security.cert.CertificateFactory
import java.security.cert.PKIXParameters
import java.security.cert.TrustAnchor
import java.security.cert.X509Certificate
import java.security.interfaces.ECPublicKey
import java.security.interfaces.RSAPublicKey
import java.util.Date
import javax.net.ssl.HttpsURLConnection
import javax.net.ssl.SNIHostName
import javax.net.ssl.SSLContext
import javax.net.ssl.SSLParameters
import javax.net.ssl.SSLSocket
import javax.net.ssl.TrustManagerFactory
import javax.net.ssl.X509TrustManager

/**
 * Vérifie les certificats SSL/TLS du point de vue d'Android.
 *
 * C'est le cœur de l'app : Android utilise son propre trust store système
 * qui diffère de celui des navigateurs (Chrome embarque le sien).
 * Un certificat valide dans Chrome peut échouer dans une app Android native.
 *
 * Cas typiques de divergence :
 * - Certificats intermédiaires manquants (le navigateur les met en cache, pas Android)
 * - Nouvelles CA pas encore dans le trust store Android (ex: Let's Encrypt ISRG Root X2)
 * - Cross-signing expiré (ex: DST Root CA X3 / Let's Encrypt sur vieux Android)
 * - Certificats avec uniquement CN sans SAN (Android l'exige depuis API 26+)
 */
object SSLChecker {

    private const val CONNECT_TIMEOUT_MS = 10_000
    private const val READ_TIMEOUT_MS = 10_000
    private const val EXPIRY_WARNING_DAYS = 30L
    private const val MAX_REASONABLE_CHAIN_LENGTH = 5

    /**
     * Lance une vérification complète du certificat SSL d'un hostname.
     */
    suspend fun check(hostname: String, port: Int = 443): CertCheckResult {
        val cleanHost = hostname
            .removePrefix("https://")
            .removePrefix("http://")
            .split("/").first()
            .split(":").first()
            .trim()

        if (cleanHost.isBlank()) {
            return CertCheckResult(
                hostname = cleanHost,
                port = port,
                error = "Hostname vide"
            )
        }

        return try {
            performCheck(cleanHost, port)
        } catch (e: Exception) {
            CertCheckResult(
                hostname = cleanHost,
                port = port,
                error = "${e.javaClass.simpleName}: ${e.message}"
            )
        }
    }

    private fun performCheck(hostname: String, port: Int): CertCheckResult {
        val issues = mutableListOf<CertIssue>()
        var tlsVersion: String? = null
        var cipherSuite: String? = null
        var serverCerts: Array<X509Certificate>? = null
        var chainValid = false
        var trustedByAndroid = false
        var hostnameMatches = false

        // --- Étape 1 : Connexion SSL brute (on capture tout, même si invalide) ---
        val sslContext = SSLContext.getInstance("TLS")
        val capturingTrustManager = CapturingTrustManager()
        sslContext.init(null, arrayOf(capturingTrustManager), null)

        val socketFactory = sslContext.socketFactory
        val socket = socketFactory.createSocket() as SSLSocket

        try {
            // SNI - crucial pour les serveurs avec plusieurs certificats
            val sslParams = SSLParameters()
            sslParams.serverNames = listOf(SNIHostName(hostname))
            // On active les protocoles modernes
            sslParams.protocols = socket.supportedProtocols
            socket.sslParameters = sslParams

            socket.connect(InetSocketAddress(hostname, port), CONNECT_TIMEOUT_MS)
            socket.soTimeout = READ_TIMEOUT_MS
            socket.startHandshake()

            val session = socket.session
            tlsVersion = session.protocol
            cipherSuite = session.cipherSuite
            serverCerts = session.peerCertificates
                .filterIsInstance<X509Certificate>()
                .toTypedArray()
        } finally {
            runCatching { socket.close() }
        }

        if (serverCerts.isNullOrEmpty()) {
            return CertCheckResult(
                hostname = hostname,
                port = port,
                tlsVersion = tlsVersion,
                cipherSuite = cipherSuite,
                error = "Aucun certificat reçu du serveur"
            )
        }

        // --- Étape 2 : Vérification de confiance via le trust store Android ---
        trustedByAndroid = checkAndroidTrust(serverCerts, hostname)
        if (!trustedByAndroid) {
            // Diagnostic plus poussé
            val rootCause = diagnoseAndroidTrustFailure(serverCerts, hostname)
            issues.add(rootCause)
        }

        // --- Étape 3 : Vérification du hostname ---
        hostnameMatches = verifyHostname(hostname, serverCerts[0])
        if (!hostnameMatches) {
            issues.add(
                CertIssue(
                    type = IssueType.HOSTNAME_MISMATCH,
                    severity = IssueSeverity.CRITICAL,
                    title = "Le hostname ne correspond pas",
                    description = "Le certificat n'est pas valide pour '$hostname'. " +
                            "SANs présents : ${extractSANs(serverCerts[0]).joinToString(", ")}"
                )
            )
        }

        // --- Étape 4 : Vérification de la chaîne ---
        chainValid = verifyChain(serverCerts)
        if (!chainValid && !issues.any { it.type == IssueType.INCOMPLETE_CHAIN }) {
            issues.add(
                CertIssue(
                    type = IssueType.INCOMPLETE_CHAIN,
                    severity = IssueSeverity.CRITICAL,
                    title = "Chaîne de certificats invalide",
                    description = "La chaîne de certificats ne peut pas être validée. " +
                            "Il manque peut-être un certificat intermédiaire."
                )
            )
        }

        // --- Étape 5 : Analyse individuelle de chaque certificat ---
        val certInfos = serverCerts.mapIndexed { index, cert ->
            analyzeCertificate(cert, index, serverCerts, issues)
        }

        // --- Étape 6 : Vérifications globales ---
        checkTlsVersion(tlsVersion, issues)
        checkChainLength(serverCerts, issues)

        return CertCheckResult(
            hostname = hostname,
            port = port,
            tlsVersion = tlsVersion,
            cipherSuite = cipherSuite,
            certificates = certInfos,
            chainValid = chainValid,
            trustedByAndroid = trustedByAndroid,
            hostnameMatches = hostnameMatches,
            issues = issues.sortedByDescending { it.severity.ordinal },
        )
    }

    // ========================================================================
    // Vérification de confiance Android
    // ========================================================================

    /**
     * Vérifie si le certificat est approuvé par le trust store système d'Android.
     * C'est LE test clé : un échec ici = l'app Android refusera la connexion.
     */
    private fun checkAndroidTrust(certs: Array<X509Certificate>, hostname: String): Boolean {
        return try {
            val tmf = TrustManagerFactory.getInstance(TrustManagerFactory.getDefaultAlgorithm())
            tmf.init(null as java.security.KeyStore?) // null = trust store système Android
            val tm = tmf.trustManagers.first() as X509TrustManager
            tm.checkServerTrusted(certs, "RSA")

            // Vérification hostname en plus
            val hv = HttpsURLConnection.getDefaultHostnameVerifier()
            val sslContext = SSLContext.getInstance("TLS")
            sslContext.init(null, null, null)
            val sf = sslContext.socketFactory
            val tempSocket = sf.createSocket() as SSLSocket

            true
        } catch (e: Exception) {
            false
        }
    }

    /**
     * Diagnostique pourquoi Android ne fait pas confiance au certificat.
     * Retourne un CertIssue spécifique au problème.
     */
    private fun diagnoseAndroidTrustFailure(
        certs: Array<X509Certificate>,
        hostname: String
    ): CertIssue {
        // Vérifier si c'est un problème de certificat auto-signé
        if (certs.size == 1 && certs[0].subjectX500Principal == certs[0].issuerX500Principal) {
            return CertIssue(
                type = IssueType.SELF_SIGNED,
                severity = IssueSeverity.CRITICAL,
                title = "Certificat auto-signé",
                description = "Le serveur présente un certificat auto-signé. " +
                        "Android le rejettera car il n'est signé par aucune CA de confiance. " +
                        "Les navigateurs peuvent afficher un avertissement mais permettre de continuer."
            )
        }

        // Vérifier si la chaîne est incomplète (cas très fréquent !)
        val lastCert = certs.last()
        if (lastCert.subjectX500Principal != lastCert.issuerX500Principal) {
            // Le dernier cert n'est pas un root → chaîne incomplète
            return CertIssue(
                type = IssueType.INCOMPLETE_CHAIN,
                severity = IssueSeverity.CRITICAL,
                title = "Chaîne de certificats incomplète",
                description = "Le serveur ne fournit pas tous les certificats intermédiaires. " +
                        "Les navigateurs peuvent compléter la chaîne via AIA fetching ou leur cache, " +
                        "mais Android ne le fait PAS. L'intermédiaire manquant est signé par : " +
                        "'${lastCert.issuerX500Principal.name}'. " +
                        "→ Le serveur doit inclure tous les intermédiaires dans sa configuration TLS."
            )
        }

        // Vérifier si le root n'est pas dans le trust store Android
        val tmf = TrustManagerFactory.getInstance(TrustManagerFactory.getDefaultAlgorithm())
        tmf.init(null as java.security.KeyStore?)
        val tm = tmf.trustManagers.first() as X509TrustManager
        val androidRoots = tm.acceptedIssuers.map { it.subjectX500Principal.name }.toSet()

        val rootCert = certs.last()
        val rootSubject = rootCert.subjectX500Principal.name
        if (rootSubject !in androidRoots) {
            return CertIssue(
                type = IssueType.UNTRUSTED_ROOT,
                severity = IssueSeverity.CRITICAL,
                title = "CA racine non reconnue par Android",
                description = "La CA racine '$rootSubject' n'est pas dans le trust store Android. " +
                        "Elle peut être reconnue par les navigateurs qui ont leur propre trust store. " +
                        "Cela arrive souvent avec de nouvelles CA ou des CA régionales."
            )
        }

        // Fallback générique
        return CertIssue(
            type = IssueType.ANDROID_SPECIFIC_TRUST_ISSUE,
            severity = IssueSeverity.CRITICAL,
            title = "Non approuvé par Android",
            description = "Le certificat n'est pas approuvé par le trust store Android. " +
                    "La cause exacte n'a pas pu être déterminée. " +
                    "Vérifiez la configuration TLS du serveur."
        )
    }

    // ========================================================================
    // Analyse individuelle des certificats
    // ========================================================================

    private fun analyzeCertificate(
        cert: X509Certificate,
        index: Int,
        chain: Array<X509Certificate>,
        issues: MutableList<CertIssue>,
    ): CertificateInfo {
        val now = Date()
        val isExpired = now.after(cert.notAfter)
        val isNotYetValid = now.before(cert.notBefore)
        val isSelfSigned = cert.subjectX500Principal == cert.issuerX500Principal

        // Vérifier la validité temporelle
        if (isExpired) {
            issues.add(
                CertIssue(
                    type = IssueType.EXPIRED,
                    severity = IssueSeverity.CRITICAL,
                    title = "Certificat expiré (position $index)",
                    description = "Le certificat '${extractCN(cert.subjectX500Principal.name)}' " +
                            "a expiré le ${cert.notAfter}."
                )
            )
        } else if (isNotYetValid) {
            issues.add(
                CertIssue(
                    type = IssueType.NOT_YET_VALID,
                    severity = IssueSeverity.CRITICAL,
                    title = "Certificat pas encore valide (position $index)",
                    description = "Le certificat ne sera valide qu'à partir du ${cert.notBefore}."
                )
            )
        } else if (index == 0) {
            val daysLeft = (cert.notAfter.time - now.time) / (1000 * 60 * 60 * 24)
            if (daysLeft <= EXPIRY_WARNING_DAYS) {
                issues.add(
                    CertIssue(
                        type = IssueType.EXPIRING_SOON,
                        severity = IssueSeverity.WARNING,
                        title = "Certificat expire bientôt",
                        description = "Le certificat expire dans $daysLeft jours (${cert.notAfter})."
                    )
                )
            }
        }

        // Vérifier le leaf certificate spécifiquement
        if (index == 0) {
            checkLeafCertificate(cert, issues)
        }

        // Vérifier la force cryptographique
        checkCryptoStrength(cert, index, issues)

        val sans = extractSANs(cert)
        val keySize = getPublicKeySize(cert)

        // Déterminer si c'est un trust anchor
        val isTrustAnchor = isSelfSigned && index == chain.size - 1

        return CertificateInfo(
            position = index,
            subject = cert.subjectX500Principal.name,
            issuer = cert.issuerX500Principal.name,
            serialNumber = cert.serialNumber.toString(16),
            notBefore = cert.notBefore,
            notAfter = cert.notAfter,
            signatureAlgorithm = cert.sigAlgName,
            publicKeyAlgorithm = cert.publicKey.algorithm,
            publicKeySize = keySize,
            subjectAlternativeNames = sans,
            isExpired = isExpired,
            isNotYetValid = isNotYetValid,
            isSelfSigned = isSelfSigned,
            isTrustAnchor = isTrustAnchor,
            fingerprints = computeFingerprints(cert),
            version = cert.version,
        )
    }

    private fun checkLeafCertificate(cert: X509Certificate, issues: MutableList<CertIssue>) {
        // Android exige des SANs depuis API 26+ (pas uniquement CN)
        val sans = extractSANs(cert)
        if (sans.isEmpty()) {
            issues.add(
                CertIssue(
                    type = IssueType.NO_SANS,
                    severity = IssueSeverity.CRITICAL,
                    title = "Pas de Subject Alternative Names",
                    description = "Le certificat n'a pas de SAN (Subject Alternative Name). " +
                            "Android API 26+ ignore le CN et exige des SANs. " +
                            "Les navigateurs peuvent encore utiliser le CN comme fallback."
                )
            )
        }
    }

    private fun checkCryptoStrength(
        cert: X509Certificate,
        index: Int,
        issues: MutableList<CertIssue>
    ) {
        // Algorithme de signature faible
        val weakSigAlgs = listOf("SHA1withRSA", "MD5withRSA", "MD2withRSA")
        if (cert.sigAlgName in weakSigAlgs) {
            issues.add(
                CertIssue(
                    type = IssueType.WEAK_SIGNATURE,
                    severity = IssueSeverity.WARNING,
                    title = "Algorithme de signature faible (position $index)",
                    description = "Le certificat utilise ${cert.sigAlgName} qui est considéré " +
                            "comme obsolète. SHA-256+ est recommandé."
                )
            )
        }

        // Taille de clé faible
        val keySize = getPublicKeySize(cert)
        val isWeakKey = when (cert.publicKey.algorithm) {
            "RSA" -> keySize < 2048
            "EC" -> keySize < 256
            else -> false
        }
        if (isWeakKey) {
            issues.add(
                CertIssue(
                    type = IssueType.WEAK_KEY,
                    severity = IssueSeverity.WARNING,
                    title = "Clé cryptographique faible (position $index)",
                    description = "Le certificat utilise une clé ${cert.publicKey.algorithm} " +
                            "de $keySize bits. Minimum recommandé : RSA 2048 / EC 256."
                )
            )
        }
    }

    // ========================================================================
    // Vérifications globales
    // ========================================================================

    private fun checkTlsVersion(tlsVersion: String?, issues: MutableList<CertIssue>) {
        when (tlsVersion) {
            "TLSv1", "TLSv1.1" -> issues.add(
                CertIssue(
                    type = IssueType.TLS_VERSION_OLD,
                    severity = IssueSeverity.WARNING,
                    title = "Version TLS obsolète",
                    description = "Le serveur utilise $tlsVersion qui est déprécié. " +
                            "TLS 1.2+ est requis pour les apps Android modernes."
                )
            )
        }
    }

    private fun checkChainLength(certs: Array<X509Certificate>, issues: MutableList<CertIssue>) {
        if (certs.size > MAX_REASONABLE_CHAIN_LENGTH) {
            issues.add(
                CertIssue(
                    type = IssueType.CHAIN_TOO_LONG,
                    severity = IssueSeverity.INFO,
                    title = "Chaîne de certificats longue",
                    description = "La chaîne contient ${certs.size} certificats. " +
                            "Cela peut ralentir le handshake TLS sur mobile."
                )
            )
        }
    }

    private fun verifyChain(certs: Array<X509Certificate>): Boolean {
        return try {
            for (i in 0 until certs.size - 1) {
                certs[i].verify(certs[i + 1].publicKey)
            }
            true
        } catch (e: Exception) {
            false
        }
    }

    // ========================================================================
    // Utilitaires
    // ========================================================================

    private fun verifyHostname(hostname: String, cert: X509Certificate): Boolean {
        val hv = HttpsURLConnection.getDefaultHostnameVerifier()
        // Créer une session factice n'est pas trivial, on vérifie manuellement
        val sans = extractSANs(cert)
        if (sans.isNotEmpty()) {
            return sans.any { matchesHostname(hostname, it) }
        }
        // Fallback sur CN (obsolète mais certains serveurs l'utilisent encore)
        val cn = extractCN(cert.subjectX500Principal.name)
        return cn != null && matchesHostname(hostname, cn)
    }

    private fun matchesHostname(hostname: String, pattern: String): Boolean {
        if (pattern.startsWith("*.")) {
            val suffix = pattern.substring(2)
            val hostParts = hostname.split(".")
            if (hostParts.size >= 2) {
                val hostSuffix = hostParts.drop(1).joinToString(".")
                return hostSuffix.equals(suffix, ignoreCase = true)
            }
            return false
        }
        return hostname.equals(pattern, ignoreCase = true)
    }

    private fun extractSANs(cert: X509Certificate): List<String> {
        return try {
            cert.subjectAlternativeNames
                ?.filter { it[0] == 2 } // type 2 = DNS name
                ?.mapNotNull { it[1] as? String }
                ?: emptyList()
        } catch (e: Exception) {
            emptyList()
        }
    }

    private fun extractCN(dn: String): String? {
        return dn.split(",")
            .map { it.trim() }
            .firstOrNull { it.startsWith("CN=", ignoreCase = true) }
            ?.substringAfter("=")
    }

    private fun getPublicKeySize(cert: X509Certificate): Int {
        return when (val key = cert.publicKey) {
            is RSAPublicKey -> key.modulus.bitLength()
            is ECPublicKey -> key.params.order.bitLength()
            else -> 0
        }
    }

    private fun computeFingerprints(cert: X509Certificate): CertFingerprints {
        val encoded = cert.encoded
        return CertFingerprints(
            sha256 = MessageDigest.getInstance("SHA-256")
                .digest(encoded)
                .joinToString(":") { "%02X".format(it) },
            sha1 = MessageDigest.getInstance("SHA-1")
                .digest(encoded)
                .joinToString(":") { "%02X".format(it) },
        )
    }

    /**
     * TrustManager qui accepte tout (pour capturer les certs même invalides).
     */
    private class CapturingTrustManager : X509TrustManager {
        override fun checkClientTrusted(chain: Array<X509Certificate>?, authType: String?) {}
        override fun checkServerTrusted(chain: Array<X509Certificate>?, authType: String?) {}
        override fun getAcceptedIssuers(): Array<X509Certificate> = arrayOf()
    }
}

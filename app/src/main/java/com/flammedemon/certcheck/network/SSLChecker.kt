package com.flammedemon.certcheck.network

import android.os.Build
import com.flammedemon.certcheck.model.*
import java.net.HttpURLConnection
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.Socket
import java.net.URL
import java.security.KeyStore
import java.security.MessageDigest
import java.security.cert.CertificateFactory
import java.security.cert.X509Certificate
import java.security.interfaces.ECPublicKey
import java.security.interfaces.RSAPublicKey
import java.util.Calendar
import java.util.Date
import javax.net.ssl.SNIHostName
import javax.net.ssl.SSLContext
import javax.net.ssl.SSLParameters
import javax.net.ssl.SSLSocket
import javax.net.ssl.TrustManagerFactory
import javax.net.ssl.X509TrustManager
import javax.security.auth.x500.X500Principal

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
    suspend fun check(input: String, defaultPort: Int = 443): CertCheckResult {
        val cleanInput = input
            .removePrefix("https://")
            .removePrefix("http://")
            .trim()

        val (hostname, port) = parseHostnameAndPort(cleanInput, defaultPort)

        if (cleanInput.isBlank()) {
            return CertCheckResult(
                hostname = "",
                port = defaultPort,
                error = "Hostname vide"
            )
        }

        return try {
            performCheck(hostname, port)
        } catch (e: Exception) {
            CertCheckResult(
                hostname = hostname,
                port = port,
                error = "${e.javaClass.simpleName}: ${e.message}"
            )
        }
    }

    private fun parseHostnameAndPort(input: String, defaultPort: Int): Pair<String, Int> {
        val pathIndex = input.indexOf('/')
        val hostPart = if (pathIndex > 0) input.substring(0, pathIndex) else input
        
        val portIndex = hostPart.lastIndexOf(':')
        return if (portIndex > 0 && portIndex < hostPart.length - 1) {
            val host = hostPart.substring(0, portIndex)
            val port = hostPart.substring(portIndex + 1).toIntOrNull() ?: defaultPort
            Pair(host, port)
        } else {
            Pair(hostPart, defaultPort)
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
        var trustFailureReason: String? = null

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
        val trust = checkAndroidTrust(serverCerts, cipherSuite)
        trustedByAndroid = trust.trusted
        if (!trustedByAndroid) {
            trustFailureReason = trust.reason
            // Diagnostic plus poussé
            issues.add(diagnoseAndroidTrustFailure(serverCerts, trust.reason))
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

        // --- Étape 3 bis : dépendance au SNI ---
        detectSniDependency(hostname, port, serverCerts[0])?.let { issues.add(it) }

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

        // --- Étape 7 : Analyse du cipher suite ---
        val cipherAnalysis = cipherSuite?.let { analyzeCipherSuite(it, tlsVersion, issues) }

        return CertCheckResult(
            hostname = hostname,
            port = port,
            tlsVersion = tlsVersion,
            cipherSuite = cipherSuite,
            cipherAnalysis = cipherAnalysis,
            certificates = certInfos,
            chainValid = chainValid,
            trustedByAndroid = trustedByAndroid,
            hostnameMatches = hostnameMatches,
            issues = issues.sortedByDescending { it.severity.ordinal },
            trustFailureReason = trustFailureReason,
            deviceApiLevel = Build.VERSION.SDK_INT,
            deviceAndroidVersion = Build.VERSION.RELEASE,
            deviceSecurityPatch = Build.VERSION.SECURITY_PATCH,
        )
    }

    // ========================================================================
    // Vérification de confiance Android
    // ========================================================================

    /** Résultat d'une vérification de confiance, avec le détail de l'échec éventuel. */
    data class TrustCheckOutcome(
        val trusted: Boolean,
        val reason: String? = null,
    )

    /**
     * Vérifie si la chaîne est approuvée par le trust store système d'Android.
     * C'est LE test clé : un échec ici = l'app Android refusera la connexion.
     *
     * L'exception est conservée et déroulée : c'est elle qui contient la cause
     * réelle (CertPathValidatorException <- "Trust anchor for certification
     * path not found", expiration, etc.).
     */
    private fun checkAndroidTrust(
        certs: Array<X509Certificate>,
        cipherSuite: String?,
    ): TrustCheckOutcome {
        if (certs.isEmpty()) {
            return TrustCheckOutcome(trusted = false, reason = "Aucun certificat à valider")
        }
        return try {
            val tmf = TrustManagerFactory.getInstance(TrustManagerFactory.getDefaultAlgorithm())
            tmf.init(null as KeyStore?) // null = trust store système Android
            val tm = tmf.trustManagers.filterIsInstance<X509TrustManager>().first()
            tm.checkServerTrusted(certs, authTypeOf(cipherSuite, certs[0]))
            TrustCheckOutcome(trusted = true)
        } catch (e: Exception) {
            TrustCheckOutcome(trusted = false, reason = describeCauseChain(e))
        }
    }

    /**
     * authType = algorithme d'authentification négocié, déduit du cipher suite
     * réellement négocié (TLS_ECDHE_RSA_WITH_... -> "ECDHE_RSA"). Pour TLS 1.3,
     * le nom du cipher ne le porte pas : on retombe sur la clé du leaf.
     * Jamais "UNKNOWN" : un TrustManager strict le refuserait, et cet échec
     * masquerait la vraie cause derrière un problème d'authType.
     */
    private fun authTypeOf(cipherSuite: String?, leaf: X509Certificate): String {
        if (cipherSuite != null && cipherSuite.contains("_WITH_")) {
            val kx = cipherSuite.substringAfter("TLS_").substringBefore("_WITH_")
            if (kx.isNotEmpty()) return kx
        }
        return when (leaf.publicKey.algorithm) {
            "EC" -> "ECDHE_ECDSA"
            else -> "RSA"
        }
    }

    /**
     * Déroule toute la chaîne de causes. C'est là que se trouve le détail utile :
     * CertificateException <- CertPathValidatorException <- "Trust anchor for
     * certification path not found".
     */
    private fun describeCauseChain(t: Throwable): String {
        val parts = mutableListOf<String>()
        val seen = mutableSetOf<Throwable>()
        var cur: Throwable? = t
        while (cur != null && seen.add(cur)) {
            parts += "${cur.javaClass.simpleName}: ${cur.message ?: "(sans message)"}"
            cur = cur.cause
        }
        return parts.joinToString("\n  ← ")
    }

    // ========================================================================
    // Ancres de confiance du terminal (mémoïsées : le chargement est coûteux)
    // ========================================================================

    private val anchorCerts: List<X509Certificate> by lazy {
        val tmf = TrustManagerFactory.getInstance(TrustManagerFactory.getDefaultAlgorithm())
        tmf.init(null as KeyStore?)
        tmf.trustManagers.filterIsInstance<X509TrustManager>()
            .firstOrNull()
            ?.acceptedIssuers
            ?.toList()
            ?: emptyList()
    }

    private val anchorSubjects: Set<X500Principal> by lazy {
        anchorCerts.map { it.subjectX500Principal }.toSet()
    }

    /**
     * Vrai si [candidate] a bien été émis par l'ancre [anchor]. Compare la clé
     * (vérification de signature), pas seulement le DN : un certificat
     * cross-signé porte le DN de la racine sans en être la clé.
     */
    private fun signedByAnchor(candidate: X509Certificate, anchor: X509Certificate): Boolean =
        runCatching { candidate.verify(anchor.publicKey); true }.getOrDefault(false)

    /**
     * Diagnostique pourquoi Android ne fait pas confiance à la chaîne servie.
     *
     * Le point de bascule n'est pas « le dernier certificat est-il auto-signé »
     * (ne pas servir la racine est la configuration recommandée), mais :
     * l'émetteur du dernier certificat servi est-il une ancre du trust store ?
     */
    private fun diagnoseAndroidTrustFailure(
        certs: Array<X509Certificate>,
        reason: String?,
    ): CertIssue {
        val top = certs.last()
        val topIsSelfSigned = top.subjectX500Principal == top.issuerX500Principal

        // --- Cas A : certificat auto-signé isolé ---
        if (certs.size == 1 && topIsSelfSigned) {
            return CertIssue(
                type = IssueType.SELF_SIGNED,
                severity = IssueSeverity.CRITICAL,
                title = "Certificat auto-signé",
                description = "Le serveur présente un certificat auto-signé. " +
                        "Android le rejettera car il n'est signé par aucune CA de confiance. " +
                        "Les navigateurs peuvent afficher un avertissement mais permettre " +
                        "de continuer.\n\n$reason"
            )
        }

        // --- Cas B : le serveur sert la racine ---
        if (topIsSelfSigned) {
            val anchor = anchorCerts.firstOrNull { it.subjectX500Principal == top.subjectX500Principal }
            return when {
                anchor == null -> unknownRootIssue(top.subjectX500Principal, top, reason)
                signedByAnchor(top, anchor) -> CertIssue(
                    type = IssueType.ANDROID_SPECIFIC_TRUST_ISSUE,
                    severity = IssueSeverity.CRITICAL,
                    title = "Racine connue, validation pourtant en échec",
                    description = "La racine « ${dn(top.subjectX500Principal)} » est dans le " +
                            "trust store Android, avec la même clé. La cause est ailleurs : " +
                            "expiration, contrainte de nom, algorithme de signature refusé, " +
                            "ou chaînon intermédiaire incorrect.\n\n$reason"
                )
                else -> CertIssue(
                    type = IssueType.UNTRUSTED_ROOT,
                    severity = IssueSeverity.CRITICAL,
                    title = "Racine homonyme d'une ancre Android",
                    description = "Un certificat du trust store porte le même nom " +
                            "« ${dn(top.subjectX500Principal)} » mais une clé différente " +
                            "(cross-signature ou homonymie). Ce n'est pas cette racine-là " +
                            "qu'Android connaît : elle restera refusée.\n\n$reason"
                )
            }
        }

        // --- Cas C : le serveur ne sert pas la racine (configuration recommandée) ---
        // Le test n'est pas « le dernier cert est auto-signé » mais « son émetteur
        // est-il une ancre Android ? ».
        val neededAnchor = top.issuerX500Principal
        val anchor = anchorCerts.firstOrNull { it.subjectX500Principal == neededAnchor }
        if (anchor != null) {
            return if (signedByAnchor(top, anchor)) {
                CertIssue(
                    type = IssueType.ANDROID_SPECIFIC_TRUST_ISSUE,
                    severity = IssueSeverity.CRITICAL,
                    title = "Ancre présente, validation en échec",
                    description = "La racine « ${dn(neededAnchor)} » est bien dans le trust " +
                            "store Android et a bien émis le dernier certificat servi. La chaîne " +
                            "servie est complète. Chercher du côté des dates de validité, des " +
                            "contraintes de base ou de l'usage de clé.\n\n$reason"
                )
            } else {
                CertIssue(
                    type = IssueType.UNTRUSTED_ROOT,
                    severity = IssueSeverity.CRITICAL,
                    title = "Ancre homonyme, clé différente",
                    description = "Une ancre du trust store s'appelle « ${dn(neededAnchor)} » " +
                            "mais n'a pas émis le dernier certificat servi (clé différente). " +
                            "Android ne remontera pas jusqu'à cette ancre.\n\n$reason"
                )
            }
        }

        // L'émetteur du dernier certificat n'est pas une ancre : deux causes possibles.
        // L'AIA (caIssuers) permet de trancher — sinon, message à double hypothèse.
        val fetched = fetchIssuerViaAia(top)
        return when {
            // Le chaînon manquant existe mais n'est pas servi : c'est un intermédiaire.
            fetched != null && fetched.subjectX500Principal != fetched.issuerX500Principal ->
                incompleteChainIssue(missing = fetched, top = top, reason = reason)
            // Le chaînon manquant est auto-signé : c'est la racine, inconnue d'Android.
            fetched != null ->
                unknownRootIssue(fetched.subjectX500Principal, rootCert = fetched, reason = reason)
            // AIA inaccessible : on ne peut pas trancher localement.
            else ->
                ambiguousIssuerIssue(top, leafOnly = certs.size == 1, reason = reason)
        }
    }

    /**
     * Il manque un chaînon dans la chaîne servie : le fullchain est tronqué.
     * Contrairement aux navigateurs, Android ne fait ni résolution AIA ni cache.
     */
    private fun incompleteChainIssue(
        missing: X509Certificate,
        top: X509Certificate,
        reason: String?,
    ): CertIssue {
        val rootAlsoMissing = missing.issuerX500Principal !in anchorSubjects
        val description = buildString {
            append("Il manque un certificat dans la chaîne servie :\n")
            append("« ${dn(missing.subjectX500Principal)} »\n")
            append("(n° ${missing.serialNumber.toString(16)}, valide du ${missing.notBefore} ")
            append("au ${missing.notAfter})\n\n")
            append("C'est l'émetteur du certificat « ${dn(top.subjectX500Principal)} » servi en ")
            append("dernier. Le serveur ne sert que la partie basse de la chaîne : le fullchain ")
            append("doit comporter cet intermédiaire.\n")
            if (rootAlsoMissing) {
                append("\nAttention : sa racine « ${dn(missing.issuerX500Principal)} » est elle ")
                append("aussi absente du magasin. Après ajout de l'intermédiaire, il restera à ")
                append("traiter la racine (cross-signature).")
            } else {
                append("\nUne fois l'intermédiaire servi, la chaîne aboutira à une ancre déjà ")
                append("présente : le problème sera corrigé.")
            }
            if (reason != null) append("\n\n$reason")
        }
        return CertIssue(
            type = IssueType.INCOMPLETE_CHAIN,
            severity = IssueSeverity.CRITICAL,
            title = "Chaîne de certificats incomplète",
            description = description
        )
    }

    /**
     * Ni ancre connue, ni chaînon résolvable via AIA : intermédiaire manquant ou
     * racine inconnue, impossible à trancher localement. Les deux hypothèses sont
     * données avec leur correctif — affirmer l'une ou l'autre serait un faux positif.
     */
    private fun ambiguousIssuerIssue(
        top: X509Certificate,
        leafOnly: Boolean,
        reason: String?,
    ): CertIssue {
        val issuerDn = dn(top.issuerX500Principal)
        val description = buildString {
            append("Le dernier certificat servi est émis par « $issuerDn », qui n'est ni une ")
            append("ancre Android, ni résolvable via l'AIA (caIssuers inaccessible). Deux causes ")
            append("possibles :\n\n")
            append("1. Il manque un intermédiaire émis par « $issuerDn » dans le fullchain servi.")
            if (leafOnly) {
                append(" Le serveur ne sert que le certificat serveur : c'est l'hypothèse la ")
                append("plus probable. Compléter le fullchain côté serveur.")
            }
            append("\n2. « $issuerDn » est une racine absente du magasin (racine récente ou CA ")
            append("privée). Il faut alors demander une chaîne cross-signée par une racine plus ")
            append("ancienne.\n\n")
            append("À noter : ne pas servir la racine est la configuration recommandée ; ce ")
            append("n'est donc pas l'absence de racine en fin de chaîne qui pose problème ici.")
            if (reason != null) append("\n\n$reason")
        }
        return CertIssue(
            type = if (leafOnly) IssueType.INCOMPLETE_CHAIN else IssueType.UNTRUSTED_ROOT,
            severity = IssueSeverity.CRITICAL,
            title = "Émetteur du dernier certificat inconnu d'Android",
            description = description
        )
    }

    /**
     * Racine absente du magasin. Corrèle l'âge de la racine avec l'image du
     * terminal : une racine n'arrive dans le magasin qu'avec une image construite
     * après son intégration au bundle de référence (Mozilla/ccadb), et le magasin
     * n'est devenu mettable à jour (Google Play system updates) qu'à partir
     * d'Android 14.
     */
    private fun unknownRootIssue(
        rootDn: X500Principal,
        rootCert: X509Certificate?,
        reason: String?,
    ): CertIssue {
        val sdk = Build.VERSION.SDK_INT
        val release = Build.VERSION.RELEASE
        val patch = Build.VERSION.SECURITY_PATCH // fraîcheur réelle de l'image
        val imageYear = securityPatchYear(patch) ?: androidReleaseYear(sdk)
        val storeIsUpdatable = sdk >= 34 // Android 14 : magasin mis à jour via Google Play
        val rootYear = rootCert?.notBefore?.let {
            Calendar.getInstance().apply { time = it }.get(Calendar.YEAR)
        }

        val verdict = buildString {
            append("Ancre de confiance absente du trust store système :\n« ${dn(rootDn)} »\n\n")
            append("Terminal : Android $release (API $sdk")
            if (patch != null) append(", patch système $patch")
            append(").\n")

            if (rootYear != null) {
                // Corrélation en indice, pas en fait : la date qui compte est
                // l'intégration au bundle de référence, inconnue du certificat.
                append("Racine émise en $rootYear")
                if (imageYear != null) {
                    if (rootYear >= imageYear) {
                        append(", au mieux contemporaine de l'image du terminal (~$imageYear). ")
                        append("Elle n'a probablement jamais figuré dans ce magasin : une racine ")
                        append("n'y arrive qu'avec une image construite après son intégration au ")
                        append("bundle de référence (Mozilla/ccadb), qui suit souvent sa création ")
                        append("de un à deux ans.")
                    } else {
                        append(", antérieure à l'image (~$imageYear). Ça ne garantit pas sa ")
                        append("présence : son intégration au bundle de référence (Mozilla/ccadb) ")
                        append("peut être postérieure à la construction de l'image. Son absence ")
                        append("du magasin, elle, est constatée.")
                    }
                }
                append("\n\n")
            }

            if (storeIsUpdatable) {
                append("Android 14+ : le magasin est mis à jour via Google Play system updates. ")
                append("Vérifier Paramètres → Sécurité → Mise à jour du système Google Play. ")
                append("Si le terminal est à jour et que l'AC est récente, la racine n'est pas ")
                append("encore distribuée.\n\n")
            } else {
                append("Android 13 et antérieur : le magasin est figé dans l'image système ")
                append("(/system/etc/security/cacerts), en lecture seule. Aucune mise à jour ")
                append("possible sans changement de version d'OS.\n\n")
            }

            append("Correctif côté serveur : demander à l'autorité une chaîne cross-signée par ")
            append("une racine plus ancienne déjà présente dans les magasins, et servir ce ")
            append("chaînon à la place de la racine dans le fullchain. Le certificat serveur ")
            append("n'a pas à être réémis.")

            if (reason != null) append("\n\n$reason")
        }

        return CertIssue(
            type = IssueType.UNTRUSTED_ROOT,
            severity = IssueSeverity.CRITICAL,
            title = "CA racine absente du magasin Android",
            description = verdict
        )
    }

    /** Année d'une date de patch sécurité Android ("2023-05-05"). */
    private fun securityPatchYear(patch: String?): Int? = patch?.take(4)?.toIntOrNull()

    /** Année de publication approximative de chaque niveau d'API (repli). */
    private fun androidReleaseYear(sdk: Int): Int? = when (sdk) {
        26, 27 -> 2017   // 8.0 / 8.1
        28 -> 2018       // 9
        29 -> 2019       // 10
        30 -> 2020       // 11
        31 -> 2021       // 12
        32, 33 -> 2022   // 12L / 13
        34 -> 2023       // 14
        35 -> 2024       // 15
        36 -> 2025       // 16
        else -> null
    }

    private fun dn(p: X500Principal): String = p.getName(X500Principal.RFC1779)

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

    // ========================================================================
    // Analyse du cipher suite
    // ========================================================================

    /**
     * Analyse le cipher suite négocié et évalue sa force, sa compatibilité
     * multi-plateforme, et signale les problèmes potentiels.
     */
    private fun analyzeCipherSuite(
        cipher: String,
        tlsVersion: String?,
        issues: MutableList<CertIssue>
    ): CipherAnalysis {
        val isTls13 = tlsVersion == "TLSv1.3" || cipher.startsWith("TLS_") && !cipher.contains("WITH")
        val parsed = parseCipherComponents(cipher, isTls13)
        val strength = evaluateCipherStrength(parsed)
        val hasFs = parsed.keyExchange in listOf("ECDHE", "DHE", "N/A (TLS 1.3)")
        val isAead = parsed.encryption.contains("GCM") ||
                parsed.encryption.contains("CCM") ||
                parsed.encryption.contains("CHACHA20") ||
                parsed.encryption.contains("POLY1305")

        // Vérifier la force
        if (strength == CipherStrength.WEAK) {
            issues.add(
                CertIssue(
                    type = IssueType.CIPHER_WEAK,
                    severity = IssueSeverity.WARNING,
                    title = "Cipher suite faible",
                    description = "Le cipher '$cipher' utilise des algorithmes considérés " +
                            "comme faibles. Un cipher plus moderne (AES-GCM, ChaCha20) est recommandé."
                )
            )
        }

        // Vérifier forward secrecy
        if (!hasFs && !isTls13) {
            issues.add(
                CertIssue(
                    type = IssueType.CIPHER_NO_FORWARD_SECRECY,
                    severity = IssueSeverity.WARNING,
                    title = "Pas de Forward Secrecy",
                    description = "Le cipher '$cipher' n'utilise pas ECDHE ou DHE. " +
                            "Sans Forward Secrecy, si la clé privée du serveur est compromise, " +
                            "tout le trafic passé peut être déchiffré."
                )
            )
        }

        val compatibility = evaluateCompatibility(cipher, isTls13, parsed)

        return CipherAnalysis(
            fullName = cipher,
            keyExchange = parsed.keyExchange,
            encryption = parsed.encryption,
            mac = parsed.mac,
            strength = strength,
            hasForwardSecrecy = hasFs,
            isTls13 = isTls13,
            isAead = isAead,
            compatibility = compatibility,
        )
    }

    private data class CipherComponents(
        val keyExchange: String,
        val encryption: String,
        val mac: String,
    )

    /**
     * Parse un cipher suite IANA (format Java/Android) en ses composants.
     *
     * TLS 1.3 : TLS_AES_256_GCM_SHA384
     * TLS 1.2 : TLS_ECDHE_RSA_WITH_AES_128_GCM_SHA256
     */
    private fun parseCipherComponents(cipher: String, isTls13: Boolean): CipherComponents {
        if (isTls13) {
            // TLS 1.3 ciphers : TLS_<encryption>_<mac>
            // Ex: TLS_AES_256_GCM_SHA384, TLS_CHACHA20_POLY1305_SHA256, TLS_AES_128_GCM_SHA256
            val parts = cipher.removePrefix("TLS_")
            val mac = when {
                parts.endsWith("SHA384") -> "SHA384"
                parts.endsWith("SHA256") -> "SHA256"
                else -> parts.substringAfterLast("_")
            }
            val enc = parts.removeSuffix("_$mac")
            return CipherComponents(
                keyExchange = "N/A (TLS 1.3)",
                encryption = enc.replace("_", "-"),
                mac = mac,
            )
        }

        // TLS 1.2 et antérieur : TLS_<KX>_WITH_<ENC>_<MAC>
        val withIndex = cipher.indexOf("_WITH_")
        if (withIndex > 0) {
            val kxPart = cipher.substring(4, withIndex) // skip "TLS_"
            val rest = cipher.substring(withIndex + 6) // skip "_WITH_"

            val kx = when {
                kxPart.startsWith("ECDHE") -> "ECDHE"
                kxPart.startsWith("DHE") -> "DHE"
                kxPart.startsWith("RSA") -> "RSA"
                kxPart.startsWith("ECDH_") -> "ECDH"
                kxPart.startsWith("DH_") -> "DH"
                else -> kxPart.substringBefore("_")
            }

            // MAC est le dernier segment : SHA256, SHA384, SHA, MD5
            val mac = when {
                rest.endsWith("SHA384") -> "SHA384"
                rest.endsWith("SHA256") -> "SHA256"
                rest.endsWith("SHA") -> "SHA"
                rest.endsWith("MD5") -> "MD5"
                else -> rest.substringAfterLast("_")
            }

            val enc = rest.removeSuffix("_$mac")

            return CipherComponents(
                keyExchange = kx,
                encryption = enc.replace("_", "-"),
                mac = mac,
            )
        }

        // Fallback pour formats non standard
        return CipherComponents(
            keyExchange = "Inconnu",
            encryption = cipher,
            mac = "Inconnu",
        )
    }

    private fun evaluateCipherStrength(parsed: CipherComponents): CipherStrength {
        val enc = parsed.encryption.uppercase()
        val mac = parsed.mac.uppercase()

        // Faible : RC4, DES, 3DES, export, NULL, MD5
        if (enc.contains("RC4") || enc.contains("DES") || enc.contains("3DES") ||
            enc.contains("NULL") || enc.contains("EXPORT") || mac == "MD5") {
            return CipherStrength.WEAK
        }

        // Fort : AES-GCM, AES-CCM, ChaCha20-Poly1305
        if (enc.contains("GCM") || enc.contains("CCM") ||
            enc.contains("CHACHA20") || enc.contains("POLY1305")) {
            return CipherStrength.STRONG
        }

        // Acceptable : AES-CBC avec SHA256+
        return CipherStrength.ACCEPTABLE
    }

    /**
     * Évalue la compatibilité du cipher suite avec les principales plateformes.
     */
    private fun evaluateCompatibility(
        cipher: String,
        isTls13: Boolean,
        parsed: CipherComponents
    ): List<CipherCompatibility> {
        val compatibility = mutableListOf<CipherCompatibility>()

        // --- Android ---
        // Android 8+ (API 26+) supporte TLS 1.2 et la plupart des ciphers modernes
        // Android 10+ (API 29+) supporte TLS 1.3
        compatibility.add(
            if (isTls13) {
                CipherCompatibility(
                    platform = "Android 10+",
                    supported = true,
                    detail = "TLS 1.3 supporté nativement depuis Android 10 (API 29)"
                )
            } else {
                val isModernCipher = parsed.encryption.uppercase().let {
                    it.contains("AES") || it.contains("CHACHA20")
                }
                CipherCompatibility(
                    platform = "Android 8+",
                    supported = isModernCipher,
                    detail = if (isModernCipher) "Cipher supporté sur Android 8+ (API 26+)"
                    else "Ce cipher peut ne pas être disponible sur certaines versions Android"
                )
            }
        )

        // --- iOS / Safari ---
        // iOS 12.2+ supporte TLS 1.3
        // iOS est strict sur les ciphers : supporte principalement ECDHE avec AES-GCM/ChaCha20
        val enc = parsed.encryption.uppercase()
        val kx = parsed.keyExchange.uppercase()
        if (isTls13) {
            compatibility.add(
                CipherCompatibility(
                    platform = "iOS 12.2+ / Safari",
                    supported = true,
                    detail = "TLS 1.3 supporté depuis iOS 12.2"
                )
            )
        } else {
            // iOS supporte ECDHE mais pas DHE depuis iOS 10+
            val iosDheUnsupported = kx == "DHE"
            // iOS ne supporte pas ARIA, CAMELLIA, SEED, CCM8
            val iosUnsupportedEnc = enc.contains("ARIA") || enc.contains("CAMELLIA") ||
                    enc.contains("SEED") || enc.contains("CCM8")

            val iosSupported = !iosDheUnsupported && !iosUnsupportedEnc &&
                    (enc.contains("AES") || enc.contains("CHACHA20"))

            val detail = when {
                iosDheUnsupported -> "iOS ne supporte pas les ciphers DHE (uniquement ECDHE). " +
                        "Cela bloquera Safari et les apps iOS natives"
                iosUnsupportedEnc -> "iOS ne supporte pas ${parsed.encryption}. " +
                        "Cela bloquera Safari et les apps iOS natives"
                !iosSupported -> "Ce cipher peut ne pas être supporté par iOS"
                else -> "Cipher supporté sur iOS / Safari"
            }

            compatibility.add(
                CipherCompatibility(
                    platform = "iOS / Safari",
                    supported = iosSupported,
                    detail = detail,
                )
            )
        }

        // --- Chrome / Navigateurs modernes ---
        val chromeSupported = isTls13 || (
                (enc.contains("AES") || enc.contains("CHACHA20")) &&
                (kx == "ECDHE" || kx == "DHE" || kx == "N/A (TLS 1.3)")
        )
        compatibility.add(
            CipherCompatibility(
                platform = "Chrome / Edge / Firefox",
                supported = chromeSupported,
                detail = if (chromeSupported) "Supporté par les navigateurs modernes"
                else "Ce cipher peut être rejeté par les navigateurs récents"
            )
        )

        // --- Anciens navigateurs / systèmes ---
        val legacyNote = when {
            isTls13 -> "Les anciens systèmes (< Windows 10, < Android 10, IE 11) ne supportent pas TLS 1.3"
            kx == "RSA" && enc.contains("AES") -> "Compatible avec les anciens systèmes (IE 11, Java 7, etc.)"
            kx == "ECDHE" && enc.contains("AES") -> "Compatible avec la plupart des systèmes (Java 8+, Windows 7+)"
            else -> "Compatibilité variable selon les systèmes"
        }
        compatibility.add(
            CipherCompatibility(
                platform = "Systèmes anciens",
                supported = !isTls13,
                detail = legacyNote,
            )
        )

        return compatibility
    }

    /**
     * TrustManager qui accepte tout (pour capturer les certs même invalides).
     */
    private class CapturingTrustManager : X509TrustManager {
        override fun checkClientTrusted(chain: Array<X509Certificate>?, authType: String?) {}
        override fun checkServerTrusted(chain: Array<X509Certificate>?, authType: String?) {}
        override fun getAcceptedIssuers(): Array<X509Certificate> = arrayOf()
    }

    // ========================================================================
    // Détection d'une dépendance au SNI
    // ========================================================================

    /**
     * Rejoue la connexion SANS SNI. Si le certificat présenté diffère, le serveur
     * héberge plusieurs vhosts et sert un certificat par défaut — souvent
     * auto-signé — aux clients qui n'envoient pas le SNI. Certaines piles HTTP
     * anciennes embarquées dans des apps sont dans ce cas.
     */
    private fun detectSniDependency(
        hostname: String,
        port: Int,
        leafWithSni: X509Certificate,
    ): CertIssue? {
        val leafWithoutSni = runCatching {
            val ctx = SSLContext.getInstance("TLS")
            ctx.init(null, arrayOf(CapturingTrustManager()), null)
            (ctx.socketFactory.createSocket() as SSLSocket).use { s ->
                // Connexion sur IP + serverNames explicitement vide : un
                // SSLParameters neuf peut laisser le SNI implicite (peer hostname)
                // actif selon l'implémentation, ce qui fausserait le test.
                s.sslParameters = SSLParameters().apply {
                    serverNames = emptyList()
                    protocols = s.supportedProtocols
                }
                s.connect(InetSocketAddress(InetAddress.getByName(hostname), port), CONNECT_TIMEOUT_MS)
                s.startHandshake()
                s.session.peerCertificates.filterIsInstance<X509Certificate>().firstOrNull()
            }
        }.getOrNull() ?: return null

        if (leafWithoutSni.encoded.contentEquals(leafWithSni.encoded)) return null

        return CertIssue(
            type = IssueType.SNI_DEPENDENT,
            severity = IssueSeverity.WARNING,
            title = "Le certificat dépend du SNI",
            description = "Sans SNI, le serveur présente « " +
                    "${dn(leafWithoutSni.subjectX500Principal)} » au lieu de « " +
                    "${dn(leafWithSni.subjectX500Principal)} » (empreintes différentes). " +
                    "Un client qui n'envoie pas l'extension SNI reçoit le certificat du " +
                    "vhost par défaut et échouera avec la même exception. À vérifier si " +
                    "l'application fautive utilise une pile HTTP ancienne."
        )
    }

    // ========================================================================
    // Résolution de l'émetteur via l'AIA (caIssuers, RFC 5280 §4.2.2.1)
    // ========================================================================

    private const val AIA_EXTENSION_OID = "1.3.6.1.5.5.7.1.1"
    private val CA_ISSUERS_ACCESS_METHOD = byteArrayOf(0x2B, 0x06, 0x01, 0x05, 0x05, 0x07, 0x30, 0x02) // 1.3.6.1.5.5.7.48.2

    /**
     * Tente de récupérer le certificat émetteur de [cert] via son extension AIA.
     * C'est ce qui permet de distinguer « il manque un intermédiaire » (le
     * chaînon existe mais n'est pas servi) de « racine inconnue d'Android ».
     * Retourne null si l'AIA est absent ou inaccessible — le diagnostic
     * retombe alors sur un message à double hypothèse.
     */
    private fun fetchIssuerViaAia(cert: X509Certificate): X509Certificate? = runCatching {
        val ext = cert.getExtensionValue(AIA_EXTENSION_OID)
        val url = ext?.let { parseAiaCaIssuersUrl(it) }
        val der = url?.let { httpGetBytes(it) }
        der?.let {
            CertificateFactory.getInstance("X.509").generateCertificate(it.inputStream()) as X509Certificate
        }
    }.getOrNull()

    /** Extrait l'URL caIssuers d'une extension AuthorityInfoAccess (DER). */
    private fun parseAiaCaIssuersUrl(extValue: ByteArray): String? {
        // Valeur d'extension = OCTET STRING enveloppant AuthorityInfoAccessSyntax,
        // soit SEQUENCE SIZE (1..MAX) OF AccessDescription.
        if (extValue.isEmpty() || extValue[0] != 0x04.toByte()) return null
        val (len, content) = readDerLength(extValue, 1) ?: return null
        val end = content + len
        if (end > extValue.size || extValue[content] != 0x30.toByte()) return null
        val (aadLen, aadStart) = readDerLength(extValue, content + 1) ?: return null
        val aadEnd = minOf(aadStart + aadLen, end)
        var idx = aadStart
        while (idx < aadEnd) {
            // AccessDescription ::= SEQUENCE { accessMethod OID, accessLocation GeneralName }
            val tag = extValue[idx]
            val (seqLen, seqStart) = readDerLength(extValue, idx + 1) ?: return null
            val seqEnd = seqStart + seqLen
            if (tag != 0x30.toByte() || seqEnd > aadEnd) return null
            // accessMethod : OID
            if (extValue[seqStart] != 0x06.toByte()) { idx = seqEnd; continue }
            val (oidLen, oidStart) = readDerLength(extValue, seqStart + 1) ?: return null
            val oid = extValue.copyOfRange(oidStart, oidStart + oidLen)
            val gnStart = oidStart + oidLen
            // accessLocation : GeneralName — [6] IA5String pour uniformResourceIdentifier
            if (gnStart + 1 >= seqEnd) { idx = seqEnd; continue }
            val gnTag = extValue[gnStart]
            val (gnLen, gnContent) = readDerLength(extValue, gnStart + 1) ?: return null
            if (oid.contentEquals(CA_ISSUERS_ACCESS_METHOD) && gnTag == 0x86.toByte()) {
                return String(extValue, gnContent, gnLen, Charsets.US_ASCII)
            }
            idx = seqEnd
        }
        return null
    }

    /** Lit une longueur DER (courte ou longue). Renvoie (longueur, index du contenu). */
    private fun readDerLength(buf: ByteArray, start: Int): Pair<Int, Int>? {
        if (start >= buf.size) return null
        val first = buf[start].toInt() and 0xFF
        if (first < 0x80) return first to (start + 1)
        val count = first and 0x7F
        if (count == 0 || count > 4 || start + 1 + count > buf.size) return null
        var len = 0
        for (i in 1..count) len = (len shl 8) or (buf[start + i].toInt() and 0xFF)
        return len to (start + 1 + count)
    }

    /**
     * GET HTTP minimal, retourne le corps de la réponse.
     * En clair, on passe par un Socket brut : les URL caIssuers sont presque
     * toujours en http://, et NetworkSecurityPolicy bloque HttpURLConnection
     * en clair sur targetSdk 28+.
     */
    private fun httpGetBytes(urlString: String, timeoutMs: Int = 5_000): ByteArray? = runCatching {
        if (urlString.startsWith("https://")) {
            val conn = URL(urlString).openConnection()
            conn.connectTimeout = timeoutMs
            conn.readTimeout = timeoutMs
            if (conn is HttpURLConnection && conn.responseCode != 200) return@runCatching null
            conn.getInputStream().use { it.readBytes() }
        } else {
            val url = URL(urlString)
            Socket().use { s ->
                s.connect(InetSocketAddress(url.host, if (url.port == -1) 80 else url.port), timeoutMs)
                s.soTimeout = timeoutMs
                val path = url.file.ifEmpty { "/" }
                s.getOutputStream().apply {
                    write(("GET $path HTTP/1.0\r\nHost: ${url.host}\r\nConnection: close\r\n\r\n")
                        .toByteArray(Charsets.US_ASCII))
                    flush()
                }
                parseHttpBody(s.getInputStream().readBytes())
            }
        }
    }.getOrNull()

    private fun parseHttpBody(response: ByteArray): ByteArray? {
        val sep = "\r\n\r\n".toByteArray(Charsets.US_ASCII)
        val headerEnd = indexOf(response, sep) ?: return null
        val head = String(response, 0, headerEnd, Charsets.US_ASCII)
        val status = head.lineSequence().firstOrNull() ?: return null
        if (!status.contains(" 200")) return null
        return response.copyOfRange(headerEnd + sep.size, response.size)
    }

    private fun indexOf(haystack: ByteArray, needle: ByteArray): Int? {
        outer@ for (i in 0..haystack.size - needle.size) {
            for (j in needle.indices) if (haystack[i + j] != needle[j]) continue@outer
            return i
        }
        return null
    }
}

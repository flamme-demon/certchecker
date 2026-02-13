package de.guenthers.certcheck.database

import de.guenthers.certcheck.model.CertCheckResult
import de.guenthers.certcheck.model.CheckStatus
import de.guenthers.certcheck.network.SSLChecker
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first

class CertCheckRepository(private val database: CertCheckDatabase) {
    private val favoriteDao = database.favoriteDao()
    private val historyDao = database.checkHistoryDao()

    fun getAllFavorites(): Flow<List<FavoriteEntity>> = favoriteDao.getAllFavorites()

    fun getAllRecentHistory(limit: Int = 50): Flow<List<CheckHistoryEntity>> = 
        historyDao.getAllRecentHistory(limit)

    fun getHistoryForFavorite(favoriteId: Long): Flow<List<CheckHistoryEntity>> =
        historyDao.getHistoryForFavorite(favoriteId)

    suspend fun addFavorite(hostname: String, port: Int = 443): Long {
        val favorite = FavoriteEntity(hostname = hostname, port = port)
        return favoriteDao.insertFavorite(favorite)
    }

    suspend fun removeFavorite(id: Long) {
        favoriteDao.deleteFavoriteById(id)
    }

    suspend fun isFavorite(hostname: String, port: Int): Boolean {
        return favoriteDao.getFavoriteByHostAndPort(hostname, port) != null
    }

    suspend fun checkAndSaveResult(favoriteId: Long): CheckHistoryEntity {
        val favorite = favoriteDao.getFavoriteById(favoriteId) ?: throw IllegalArgumentException("Favorite not found")
        
        val result = SSLChecker.check(favorite.hostname, favorite.port)
        val historyEntity = result.toHistoryEntity(favoriteId)
        
        historyDao.insertHistory(historyEntity)
        favoriteDao.updateLastChecked(favoriteId, System.currentTimeMillis())
        
        return historyEntity
    }

    suspend fun checkChanges(favoriteId: Long): ChangeDetection? {
        val history = historyDao.getLastTwoChecks(favoriteId)
        if (history.size < 2) return null

        val previous = history[1]
        val current = history[0]

        val changes = mutableListOf<String>()

        if (previous.overallStatus != current.overallStatus) {
            changes.add("Status changed from ${previous.overallStatus} to ${current.overallStatus}")
        }

        if (previous.trustedByAndroid != current.trustedByAndroid) {
            changes.add("Android trust changed: ${previous.trustedByAndroid} -> ${current.trustedByAndroid}")
        }

        if (previous.certificateFingerprint != current.certificateFingerprint) {
            changes.add("Certificate fingerprint changed")
        }

        if (previous.daysUntilExpiry != current.daysUntilExpiry) {
            changes.add("Days until expiry changed: ${previous.daysUntilExpiry} -> ${current.daysUntilExpiry}")
        }

        return if (changes.isNotEmpty()) {
            ChangeDetection(
                favoriteId = favoriteId,
                hostname = current.hostname,
                changes = changes,
                newStatus = current.overallStatus,
                previousStatus = previous.overallStatus
            )
        } else null
    }

    suspend fun getAllFavoritesSync(): List<FavoriteEntity> {
        return kotlinx.coroutines.flow.first(favoriteDao.getAllFavorites())
    }

    private fun CertCheckResult.toHistoryEntity(favoriteId: Long): CheckHistoryEntity {
        val leafCert = certificates.firstOrNull()
        return CheckHistoryEntity(
            favoriteId = favoriteId,
            hostname = hostname,
            port = port,
            overallStatus = overallStatus.name,
            trustedByAndroid = trustedByAndroid,
            hostnameMatches = hostnameMatches,
            chainValid = chainValid,
            issuesSummary = issues.joinToString("; ") { "${it.type}: ${it.title}" },
            certificateFingerprint = leafCert?.fingerprints?.sha256,
            daysUntilExpiry = leafCert?.daysUntilExpiry,
            error = error
        )
    }
}

data class ChangeDetection(
    val favoriteId: Long,
    val hostname: String,
    val changes: List<String>,
    val newStatus: String,
    val previousStatus: String
)

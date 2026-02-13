package de.guenthers.certcheck.database

import androidx.room.*
import kotlinx.coroutines.flow.Flow

@Dao
interface FavoriteDao {
    @Query("SELECT * FROM favorites ORDER BY addedAt DESC")
    fun getAllFavorites(): Flow<List<FavoriteEntity>>

    @Query("SELECT * FROM favorites WHERE id = :id")
    suspend fun getFavoriteById(id: Long): FavoriteEntity?

    @Query("SELECT * FROM favorites WHERE hostname = :hostname AND port = :port LIMIT 1")
    suspend fun getFavoriteByHostAndPort(hostname: String, port: Int): FavoriteEntity?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertFavorite(favorite: FavoriteEntity): Long

    @Delete
    suspend fun deleteFavorite(favorite: FavoriteEntity)

    @Query("DELETE FROM favorites WHERE id = :id")
    suspend fun deleteFavoriteById(id: Long)

    @Query("UPDATE favorites SET lastCheckedAt = :timestamp WHERE id = :id")
    suspend fun updateLastChecked(id: Long, timestamp: Long)
}

@Dao
interface CheckHistoryDao {
    @Query("SELECT * FROM check_history WHERE favoriteId = :favoriteId ORDER BY checkedAt DESC")
    fun getHistoryForFavorite(favoriteId: Long): Flow<List<CheckHistoryEntity>>

    @Query("SELECT * FROM check_history WHERE favoriteId = :favoriteId ORDER BY checkedAt DESC LIMIT :limit")
    suspend fun getRecentHistory(favoriteId: Long, limit: Int): List<CheckHistoryEntity>

    @Query("SELECT * FROM check_history ORDER BY checkedAt DESC LIMIT :limit")
    fun getAllRecentHistory(limit: Int): Flow<List<CheckHistoryEntity>>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertHistory(history: CheckHistoryEntity): Long

    @Query("DELETE FROM check_history WHERE favoriteId = :favoriteId")
    suspend fun deleteHistoryForFavorite(favoriteId: Long)

    @Query("SELECT * FROM check_history WHERE favoriteId = :favoriteId ORDER BY checkedAt DESC LIMIT 2")
    suspend fun getLastTwoChecks(favoriteId: Long): List<CheckHistoryEntity>
}

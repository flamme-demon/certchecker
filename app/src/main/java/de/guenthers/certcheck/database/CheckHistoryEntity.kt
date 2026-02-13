package de.guenthers.certcheck.database

import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.PrimaryKey

@Entity(
    tableName = "check_history",
    foreignKeys = [
        ForeignKey(
            entity = FavoriteEntity::class,
            parentColumns = ["id"],
            childColumns = ["favoriteId"],
            onDelete = ForeignKey.CASCADE
        )
    ],
    indices = [Index("favoriteId")]
)
data class CheckHistoryEntity(
    @PrimaryKey(autoGenerate = true)
    val id: Long = 0,
    val favoriteId: Long,
    val hostname: String,
    val port: Int,
    val checkedAt: Long = System.currentTimeMillis(),
    val overallStatus: String,
    val trustedByAndroid: Boolean,
    val hostnameMatches: Boolean,
    val chainValid: Boolean,
    val issuesSummary: String,
    val certificateFingerprint: String?,
    val daysUntilExpiry: Long?,
    val error: String?,
)

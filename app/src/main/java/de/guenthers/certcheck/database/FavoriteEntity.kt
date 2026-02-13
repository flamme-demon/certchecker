package de.guenthers.certcheck.database

import androidx.room.Entity
import androidx.room.PrimaryKey

@Entity(tableName = "favorites")
data class FavoriteEntity(
    @PrimaryKey(autoGenerate = true)
    val id: Long = 0,
    val hostname: String,
    val port: Int = 443,
    val addedAt: Long = System.currentTimeMillis(),
    val lastCheckedAt: Long? = null,
)

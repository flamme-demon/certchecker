package com.flammedemon.certcheck.database

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase

@Database(
    entities = [FavoriteEntity::class, CheckHistoryEntity::class],
    version = 3,
    exportSchema = false
)
abstract class CertCheckDatabase : RoomDatabase() {
    abstract fun favoriteDao(): FavoriteDao
    abstract fun checkHistoryDao(): CheckHistoryDao

    companion object {
        @Volatile
        private var INSTANCE: CertCheckDatabase? = null

        fun getDatabase(context: Context): CertCheckDatabase {
            return INSTANCE ?: synchronized(this) {
                val instance = Room.databaseBuilder(
                    context.applicationContext,
                    CertCheckDatabase::class.java,
                    "certcheck_database"
                ).fallbackToDestructiveMigration().build()
                INSTANCE = instance
                instance
            }
        }
    }
}

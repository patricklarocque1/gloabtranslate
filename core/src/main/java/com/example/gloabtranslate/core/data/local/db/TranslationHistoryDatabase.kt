package com.example.gloabtranslate.core.data.local.db

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.TypeConverters

@Database(
    entities = [TranslationHistoryEntity::class],
    version = 1,
    exportSchema = true
)
@TypeConverters(TranslationHistoryTypeConverters::class)
abstract class TranslationHistoryDatabase : RoomDatabase() {

    abstract fun translationHistoryDao(): TranslationHistoryDao

    companion object {
        @Volatile
        private var INSTANCE: TranslationHistoryDatabase? = null

        fun getInstance(context: Context): TranslationHistoryDatabase {
            return INSTANCE ?: synchronized(this) {
                INSTANCE ?: buildDatabase(context.applicationContext).also { INSTANCE = it }
            }
        }

        private fun buildDatabase(context: Context): TranslationHistoryDatabase {
            return Room.databaseBuilder(
                context,
                TranslationHistoryDatabase::class.java,
                "translation_history.db"
            ).fallbackToDestructiveMigrationOnDowngrade(dropAllTables = true)
                .build()
        }
    }
}

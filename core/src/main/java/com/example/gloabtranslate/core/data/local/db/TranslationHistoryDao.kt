package com.example.gloabtranslate.core.data.local.db

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.RawQuery
import androidx.room.Update
import androidx.sqlite.db.SupportSQLiteQuery
import kotlinx.coroutines.flow.Flow

@Dao
interface TranslationHistoryDao {

    @Query("SELECT * FROM translation_history ORDER BY timestamp DESC")
    fun observeHistory(): Flow<List<TranslationHistoryEntity>>

    @RawQuery(observedEntities = [TranslationHistoryEntity::class])
    suspend fun getHistoryFiltered(query: SupportSQLiteQuery): List<TranslationHistoryEntity>

    @Query("SELECT * FROM translation_history ORDER BY timestamp DESC")
    suspend fun getHistory(): List<TranslationHistoryEntity>

    @Query("SELECT * FROM translation_history WHERE id = :id LIMIT 1")
    suspend fun getById(id: String): TranslationHistoryEntity?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(entity: TranslationHistoryEntity)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertAll(entities: List<TranslationHistoryEntity>)

    @Update
    suspend fun update(entity: TranslationHistoryEntity)

    @Query("UPDATE translation_history SET favorite = :isFavorite WHERE id = :id")
    suspend fun updateFavorite(id: String, isFavorite: Boolean)

    @Query("DELETE FROM translation_history WHERE id = :id")
    suspend fun deleteById(id: String): Int

    @Query("DELETE FROM translation_history WHERE id IN (:ids)")
    suspend fun deleteByIds(ids: List<String>): Int

    @Query("DELETE FROM translation_history")
    suspend fun clear(): Int

    @Query("DELETE FROM translation_history WHERE timestamp < :threshold")
    suspend fun deleteOlderThan(threshold: Long): Int

    @Query("SELECT COUNT(*) FROM translation_history")
    suspend fun count(): Long
}

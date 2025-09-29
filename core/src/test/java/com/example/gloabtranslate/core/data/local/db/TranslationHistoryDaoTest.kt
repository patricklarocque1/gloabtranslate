package com.example.gloabtranslate.core.data.local.db

import androidx.room.Room
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

@RunWith(RobolectricTestRunner::class)
class TranslationHistoryDaoTest {

    private lateinit var database: TranslationHistoryDatabase
    private lateinit var dao: TranslationHistoryDao

    @Before
    fun setUp() {
        val context = RuntimeEnvironment.getApplication()
        database = Room.inMemoryDatabaseBuilder(context, TranslationHistoryDatabase::class.java)
            .allowMainThreadQueries()
            .build()
        dao = database.translationHistoryDao()
    }

    @After
    fun tearDown() {
        database.close()
    }

    @Test
    fun upsertAndQueryReturnsSavedEntity() = runBlocking {
        val entity = sampleEntity(id = "id-1", originalText = "Hola", translatedText = "Hello")

        dao.upsert(entity)
        val history = dao.getHistory()

        assertEquals(1, history.size)
        assertEquals(entity, history.first())
    }

    @Test
    fun observeHistoryReflectsUpserts() = runBlocking {
        val first = sampleEntity(id = "id-1")
        val second = sampleEntity(id = "id-2", timestamp = 200L)

        dao.upsert(first)
        dao.upsert(second)

        val observed = dao.observeHistory().first()

        assertEquals(2, observed.size)
        assertEquals(listOf(second.id, first.id), observed.map { it.id })
    }

    @Test
    fun deleteByIdRemovesEntity() = runBlocking {
        val entity = sampleEntity(id = "id-1")
        dao.upsert(entity)
        assertTrue(dao.getHistory().isNotEmpty())

        val deleted = dao.deleteById(entity.id)
        val remaining = dao.getHistory()

        assertEquals(1, deleted)
        assertTrue(remaining.isEmpty())
    }

    @Test
    fun updateFavoriteTogglesFlag() = runBlocking {
        val entity = sampleEntity(id = "favorite-1")
        dao.upsert(entity)

        dao.updateFavorite(entity.id, true)
        val updated = dao.getById(entity.id)

        assertNotNull(updated)
        assertTrue(updated.favorite)
    }

    private fun sampleEntity(
        id: String,
        originalText: String = "sample",
        translatedText: String? = "translated",
        timestamp: Long = 100L
    ): TranslationHistoryEntity {
        return TranslationHistoryEntity(
            id = id,
            originalText = originalText,
            translatedText = translatedText,
            sourceLanguage = "en",
            targetLanguage = "es",
            confidence = 0.9f,
            isPartial = false,
            isOnDevice = true,
            timestamp = timestamp,
            duration = 42L,
            success = true,
            error = null,
            metadata = mapOf("key" to "value"),
            tags = listOf("tag"),
            favorite = false
        )
    }
}

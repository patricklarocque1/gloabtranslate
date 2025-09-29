package com.example.gloabtranslate.core.data.repository

import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import java.io.File
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

@RunWith(RobolectricTestRunner::class)
class TranslationHistoryRepositoryTest {

    private val context = RuntimeEnvironment.getApplication()
    private lateinit var repository: TranslationHistoryRepository

    private val legacyFile: File
        get() = File(context.filesDir, LEGACY_FILE_NAME)

    @Before
    fun setUp() = runBlocking {
        repository = TranslationHistoryRepository.getInstance(context)
        repository.cleanup()
        deleteLegacyFile()

        repository = TranslationHistoryRepository.getInstance(context)
        repository.initialize()
        repository.clearAllHistory()
        repository.cleanup()
    }

    @After
    fun tearDown() {
        deleteLegacyFile()
        repository.cleanup()
    }

    @Test
    fun migratesLegacyJsonOnInitialize() = runBlocking {
        val legacyEntry = TranslationHistoryRepository.TranslationHistoryEntry(
            id = "legacy-1",
            originalText = "Hello",
            translatedText = "Hola",
            sourceLanguage = "en",
            targetLanguage = "es",
            confidence = 0.92f,
            isPartial = false,
            isOnDevice = true,
            timestamp = 1234L,
            duration = 55L,
            success = true,
            error = null,
            metadata = mapOf("source" to "legacy"),
            tags = listOf("migration"),
            favorite = true
        )

        writeLegacyFile(LegacyHistoryData(entries = listOf(legacyEntry)))

        val initialized = repository.initialize()
        assertTrue(initialized)

        val history = repository.getTranslationHistory()
        assertEquals(1, history.size)
        assertEquals(legacyEntry, history.first())
        assertFalse(legacyFile.exists())

        val fromFlow = repository.historyFlow.first { it.isNotEmpty() }
        assertEquals(legacyEntry.id, fromFlow.first().id)
    }

    private fun writeLegacyFile(data: LegacyHistoryData) {
        val json = Json { prettyPrint = true; ignoreUnknownKeys = true; encodeDefaults = true }
        legacyFile.parentFile?.mkdirs()
        legacyFile.writeText(json.encodeToString(data))
    }

    private fun deleteLegacyFile() {
        if (legacyFile.exists()) {
            legacyFile.delete()
        }
    }

    companion object {
        private const val LEGACY_FILE_NAME = "translation_history.json"
    }

    @Serializable
    private data class LegacyHistoryData(
        val version: Int = 1,
        val timestamp: Long = System.currentTimeMillis(),
        val entries: List<TranslationHistoryRepository.TranslationHistoryEntry>,
        val nextId: Long? = null
    )
}

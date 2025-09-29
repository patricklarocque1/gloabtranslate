package com.example.gloabtranslate.core

import com.example.gloabtranslate.core.data.models.TranslationResult
import com.example.gloabtranslate.core.data.models.LanguagePair
import com.example.gloabtranslate.core.data.models.SupportedLanguage
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlin.test.assertNull // Added this import

@RunWith(RobolectricTestRunner::class)
class TranslationResultTest {

    @Test
    fun `TranslationResult should have correct default values`() {
        // When
        val result = TranslationResult(success = true)

        // Then
        assertTrue(result.success)
        assertNull(result.originalText)
        assertNull(result.translatedText)
        assertNull(result.sourceLanguage)
        assertNull(result.targetLanguage)
        assertNull(result.confidence)
        assertFalse(result.isPartial)
        assertFalse(result.isOnDevice)
        assertTrue(result.timestamp > 0)
        assertNull(result.error)
    }

    @Test
    fun `TranslationResult should accept all parameters`() {
        // Given
        val originalText = "Hello world"
        val translatedText = "Hola mundo"
        val sourceLanguage = "en"
        val targetLanguage = "es"
        val confidence = 0.95f
        val isPartial = false
        val isOnDevice = true
        val timestamp = System.currentTimeMillis()
        val error = "Test error"

        // When
        val result = TranslationResult(
            success = true,
            originalText = originalText,
            translatedText = translatedText,
            sourceLanguage = sourceLanguage,
            targetLanguage = targetLanguage,
            confidence = confidence,
            isPartial = isPartial,
            isOnDevice = isOnDevice,
            timestamp = timestamp,
            error = error
        )

        // Then
        assertTrue(result.success)
        assertEquals(originalText, result.originalText)
        assertEquals(translatedText, result.translatedText)
        assertEquals(sourceLanguage, result.sourceLanguage)
        assertEquals(targetLanguage, result.targetLanguage)
        assertEquals(confidence, result.confidence)
        assertEquals(isPartial, result.isPartial)
        assertEquals(isOnDevice, result.isOnDevice)
        assertEquals(timestamp, result.timestamp)
        assertEquals(error, result.error)
    }

    @Test
    fun `TranslationResult should handle success case`() {
        // When
        val result = TranslationResult(
            success = true,
            originalText = "Hello",
            translatedText = "Hola",
            sourceLanguage = "en",
            targetLanguage = "es",
            confidence = 0.98f,
            isOnDevice = true
        )

        // Then
        assertTrue(result.success)
        assertEquals("Hello", result.originalText)
        assertEquals("Hola", result.translatedText)
        assertEquals("en", result.sourceLanguage)
        assertEquals("es", result.targetLanguage)
        assertEquals(0.98f, result.confidence)
        assertFalse(result.isPartial)
        assertTrue(result.isOnDevice)
        assertNull(result.error)
    }

    @Test
    fun `TranslationResult should handle error case`() {
        // When
        val result = TranslationResult(
            success = false,
            error = "Translation failed"
        )

        // Then
        assertFalse(result.success)
        assertNull(result.originalText)
        assertNull(result.translatedText)
        assertNull(result.sourceLanguage)
        assertNull(result.targetLanguage)
        assertNull(result.confidence)
        assertFalse(result.isPartial)
        assertFalse(result.isOnDevice)
        assertEquals("Translation failed", result.error)
    }

    @Test
    fun `TranslationResult should handle partial result`() {
        // When
        val result = TranslationResult(
            success = true,
            originalText = "Hello",
            translatedText = "Hola",
            sourceLanguage = "en",
            targetLanguage = "es",
            confidence = 0.75f,
            isPartial = true,
            isOnDevice = false
        )

        // Then
        assertTrue(result.success)
        assertEquals("Hello", result.originalText)
        assertEquals("Hola", result.translatedText)
        assertEquals("en", result.sourceLanguage)
        assertEquals("es", result.targetLanguage)
        assertEquals(0.75f, result.confidence)
        assertTrue(result.isPartial)
        assertFalse(result.isOnDevice)
        assertNull(result.error)
    }

    @Test
    fun `TranslationResult should have timestamp close to current time`() {
        // Given
        val beforeTime = System.currentTimeMillis()

        // When
        val result = TranslationResult(success = true)
        val afterTime = System.currentTimeMillis()

        // Then
        assertTrue(result.timestamp >= beforeTime)
        assertTrue(result.timestamp <= afterTime)
    }

    @Test
    fun `LanguagePair should have correct properties`() {
        // When
        val languagePair = LanguagePair(
            sourceLanguage = "en",
            targetLanguage = "es",
            displayName = "English to Spanish"
        )

        // Then
        assertEquals("en", languagePair.sourceLanguage)
        assertEquals("es", languagePair.targetLanguage)
        assertEquals("English to Spanish", languagePair.displayName)
    }

    @Test
    fun `LanguagePair should handle different language codes`() {
        // When
        val languagePair = LanguagePair(
            sourceLanguage = "fr",
            targetLanguage = "de",
            displayName = "French to German"
        )

        // Then
        assertEquals("fr", languagePair.sourceLanguage)
        assertEquals("de", languagePair.targetLanguage)
        assertEquals("French to German", languagePair.displayName)
    }

    @Test
    fun `SupportedLanguage should have correct default values`() {
        // When
        val supportedLanguage = SupportedLanguage(
            code = "en",
            displayName = "English",
            nativeName = "English"
        )

        // Then
        assertEquals("en", supportedLanguage.code)
        assertEquals("English", supportedLanguage.displayName)
        assertEquals("English", supportedLanguage.nativeName)
        assertFalse(supportedLanguage.isOnDeviceSupported)
    }

    @Test
    fun `SupportedLanguage should accept all parameters`() {
        // When
        val supportedLanguage = SupportedLanguage(
            code = "es",
            displayName = "Spanish",
            nativeName = "Español",
            isOnDeviceSupported = true
        )

        // Then
        assertEquals("es", supportedLanguage.code)
        assertEquals("Spanish", supportedLanguage.displayName)
        assertEquals("Español", supportedLanguage.nativeName)
        assertTrue(supportedLanguage.isOnDeviceSupported)
    }

    @Test
    fun `SupportedLanguage should handle different languages`() {
        // When
        val supportedLanguage = SupportedLanguage(
            code = "zh",
            displayName = "Chinese",
            nativeName = "中文",
            isOnDeviceSupported = false
        )

        // Then
        assertEquals("zh", supportedLanguage.code)
        assertEquals("Chinese", supportedLanguage.displayName)
        assertEquals("中文", supportedLanguage.nativeName)
        assertFalse(supportedLanguage.isOnDeviceSupported)
    }

    @Test
    fun `TranslationResult should be immutable`() {
        // Given
        val result = TranslationResult(
            success = true,
            originalText = "Hello",
            translatedText = "Hola",
            sourceLanguage = "en",
            targetLanguage = "es",
            confidence = 0.95f,
            isPartial = false,
            isOnDevice = true,
            timestamp = 1234567890L,
            error = null
        )

        // When
        val modifiedResult = result.copy(
            success = false,
            error = "Modified error"
        )

        // Then
        // Original result should remain unchanged
        assertTrue(result.success)
        assertNull(result.error)
        
        // Modified result should have new values
        assertFalse(modifiedResult.success)
        assertEquals("Modified error", modifiedResult.error)
        
        // Other properties should remain the same
        assertEquals(result.originalText, modifiedResult.originalText)
        assertEquals(result.translatedText, modifiedResult.translatedText)
        assertEquals(result.sourceLanguage, modifiedResult.sourceLanguage)
        assertEquals(result.targetLanguage, modifiedResult.targetLanguage)
        assertEquals(result.confidence, modifiedResult.confidence)
        assertEquals(result.isPartial, modifiedResult.isPartial)
        assertEquals(result.isOnDevice, modifiedResult.isOnDevice)
        assertEquals(result.timestamp, modifiedResult.timestamp)
    }

    @Test
    fun `LanguagePair should be immutable`() {
        // Given
        val languagePair = LanguagePair(
            sourceLanguage = "en",
            targetLanguage = "es",
            displayName = "English to Spanish"
        )

        // When
        val modifiedPair = languagePair.copy(
            sourceLanguage = "fr",
            displayName = "French to Spanish"
        )

        // Then
        // Original pair should remain unchanged
        assertEquals("en", languagePair.sourceLanguage)
        assertEquals("English to Spanish", languagePair.displayName)
        
        // Modified pair should have new values
        assertEquals("fr", modifiedPair.sourceLanguage)
        assertEquals("French to Spanish", modifiedPair.displayName)
        
        // Other properties should remain the same
        assertEquals(languagePair.targetLanguage, modifiedPair.targetLanguage)
    }

    @Test
    fun `SupportedLanguage should be immutable`() {
        // Given
        val supportedLanguage = SupportedLanguage(
            code = "en",
            displayName = "English",
            nativeName = "English",
            isOnDeviceSupported = false
        )

        // When
        val modifiedLanguage = supportedLanguage.copy(
            displayName = "English (US)",
            isOnDeviceSupported = true
        )

        // Then
        // Original language should remain unchanged
        assertEquals("English", supportedLanguage.displayName)
        assertFalse(supportedLanguage.isOnDeviceSupported)
        
        // Modified language should have new values
        assertEquals("English (US)", modifiedLanguage.displayName)
        assertTrue(modifiedLanguage.isOnDeviceSupported)
        
        // Other properties should remain the same
        assertEquals(supportedLanguage.code, modifiedLanguage.code)
        assertEquals(supportedLanguage.nativeName, modifiedLanguage.nativeName)
    }

    @Test
    fun `TranslationResult should handle edge cases`() {
        // When
        val result = TranslationResult(
            success = true,
            originalText = "",
            translatedText = "",
            sourceLanguage = "",
            targetLanguage = "",
            confidence = 0.0f,
            isPartial = true,
            isOnDevice = false,
            timestamp = 0L,
            error = ""
        )

        // Then
        assertTrue(result.success)
        assertEquals("", result.originalText)
        assertEquals("", result.translatedText)
        assertEquals("", result.sourceLanguage)
        assertEquals("", result.targetLanguage)
        assertEquals(0.0f, result.confidence)
        assertTrue(result.isPartial)
        assertFalse(result.isOnDevice)
        assertEquals(0L, result.timestamp)
        assertEquals("", result.error)
    }

    @Test
    fun `TranslationResult should handle null values`() {
        // When
        val result = TranslationResult(
            success = false,
            originalText = null,
            translatedText = null,
            sourceLanguage = null,
            targetLanguage = null,
            confidence = null,
            isPartial = false,
            isOnDevice = false,
            timestamp = 0L,
            error = null
        )

        // Then
        assertFalse(result.success)
        assertNull(result.originalText)
        assertNull(result.translatedText)
        assertNull(result.sourceLanguage)
        assertNull(result.targetLanguage)
        assertNull(result.confidence)
        assertFalse(result.isPartial)
        assertFalse(result.isOnDevice)
        assertEquals(0L, result.timestamp)
        assertNull(result.error)
    }
}

package com.example.gloabtranslate.tts

import android.content.Context
import android.speech.tts.TextToSpeech
import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.withContext
import java.util.Locale
import java.util.UUID

/**
 * Voice selector for managing and selecting TTS voices.
 * Provides comprehensive voice selection functionality including voice discovery,
 * filtering, ranking, and selection based on various criteria.
 */
class VoiceSelector(private val context: Context) {
    
    companion object {
        private const val TAG = "VoiceSelector"
        private const val DEFAULT_VOICE_QUALITY_THRESHOLD = 0.7f
        private const val MAX_VOICE_CACHE_SIZE = 100
        private const val VOICE_DISCOVERY_TIMEOUT_MS = 5000L
    }
    
    private val voiceCache = mutableMapOf<String, VoiceInfo>()
    private val voiceRankings = mutableMapOf<String, Float>()
    private val voicePreferences = mutableMapOf<String, VoicePreference>()
    private val voiceFilters = mutableMapOf<String, VoiceFilter>()
    
    // Voice selection state
    private var currentVoice: VoiceInfo? = null
    private var currentLanguage: Locale = Locale.ENGLISH
    private var voiceSelectionMode = VoiceSelectionMode.AUTOMATIC
    
    // Voice selection listeners
    private val voiceSelectionListeners = mutableListOf<VoiceSelectionListener>()
    
    /**
     * Voice information with extended metadata
     */
    data class VoiceInfo(
        val id: String = UUID.randomUUID().toString(),
        val name: String,
        val displayName: String,
        val locale: Locale,
        val quality: VoiceQuality,
        val gender: VoiceGender,
        val age: VoiceAge,
        val accent: VoiceAccent,
        val characteristics: VoiceCharacteristics,
        val isNetworkRequired: Boolean = false,
        val isInstalled: Boolean = true,
        val isDefault: Boolean = false,
        val isPremium: Boolean = false,
        val fileSize: Long = 0L,
        val downloadSize: Long = 0L,
        val version: String = "1.0",
        val engine: String = "default",
        val sampleRate: Int = 22050,
        val bitRate: Int = 128,
        val supportedFeatures: Set<VoiceFeature> = emptySet(),
        val metadata: Map<String, Any> = emptyMap()
    )
    
    /**
     * Voice quality levels
     */
    enum class VoiceQuality {
        VERY_HIGH, HIGH, MEDIUM, LOW, VERY_LOW
    }
    
    /**
     * Voice gender types
     */
    enum class VoiceGender {
        MALE, FEMALE, NEUTRAL, UNKNOWN
    }
    
    /**
     * Voice age ranges
     */
    enum class VoiceAge {
        CHILD, YOUNG_ADULT, ADULT, MIDDLE_AGED, SENIOR, UNKNOWN
    }
    
    /**
     * Voice accent types
     */
    enum class VoiceAccent {
        AMERICAN, BRITISH, AUSTRALIAN, CANADIAN, IRISH, SCOTTISH, WELSH,
        FRENCH, GERMAN, ITALIAN, SPANISH, PORTUGUESE, RUSSIAN, JAPANESE,
        CHINESE, KOREAN, ARABIC, HINDI, BRAZILIAN, MEXICAN, ARGENTINEAN,
        NEUTRAL, UNKNOWN
    }
    
    /**
     * Voice characteristics
     */
    data class VoiceCharacteristics(
        val isWarm: Boolean = false,
        val isProfessional: Boolean = false,
        val isFriendly: Boolean = false,
        val isAuthoritative: Boolean = false,
        val isCalm: Boolean = false,
        val isEnergetic: Boolean = false,
        val isExpressive: Boolean = false,
        val isMonotone: Boolean = false,
        val isClear: Boolean = true,
        val isNatural: Boolean = true,
        val isRobotic: Boolean = false,
        val isEmotional: Boolean = false,
        val isFormal: Boolean = false,
        val isCasual: Boolean = false,
        val isSlow: Boolean = false,
        val isFast: Boolean = false,
        val isHighPitched: Boolean = false,
        val isLowPitched: Boolean = false,
        val isRaspy: Boolean = false,
        val isSmooth: Boolean = true
    )
    
    /**
     * Voice features
     */
    enum class VoiceFeature {
        SSML, EMOTIONS, MULTILINGUAL, VOICE_CLONING, CUSTOM_PRONUNCIATION,
        BACKGROUND_MUSIC, ECHO, REVERB, NOISE_REDUCTION, VOICE_MODULATION,
        REAL_TIME, OFFLINE, STREAMING, BATCH_PROCESSING
    }
    
    /**
     * Voice selection modes
     */
    enum class VoiceSelectionMode {
        AUTOMATIC, MANUAL, SMART, CUSTOM
    }
    
    /**
     * Voice preference
     */
    data class VoicePreference(
        val userId: String,
        val language: Locale,
        val preferredGender: VoiceGender? = null,
        val preferredAge: VoiceAge? = null,
        val preferredAccent: VoiceAccent? = null,
        val preferredQuality: VoiceQuality? = null,
        val preferredCharacteristics: VoiceCharacteristics? = null,
        val excludedVoices: Set<String> = emptySet(),
        val preferredVoices: Set<String> = emptySet(),
        val customRankings: Map<String, Float> = emptyMap(),
        val lastUsed: Long = System.currentTimeMillis()
    )
    
    /**
     * Voice filter
     */
    data class VoiceFilter(
        val name: String,
        val language: Locale? = null,
        val gender: VoiceGender? = null,
        val age: VoiceAge? = null,
        val accent: VoiceAccent? = null,
        val quality: VoiceQuality? = null,
        val characteristics: VoiceCharacteristics? = null,
        val features: Set<VoiceFeature> = emptySet(),
        val isInstalled: Boolean? = null,
        val isNetworkRequired: Boolean? = null,
        val isPremium: Boolean? = null,
        val minQuality: Float = 0.0f,
        val maxFileSize: Long = Long.MAX_VALUE,
        val customCriteria: (VoiceInfo) -> Boolean = { true }
    )
    
    /**
     * Voice selection criteria
     */
    data class VoiceSelectionCriteria(
        val language: Locale,
        val gender: VoiceGender? = null,
        val age: VoiceAge? = null,
        val accent: VoiceAccent? = null,
        val quality: VoiceQuality? = null,
        val characteristics: VoiceCharacteristics? = null,
        val features: Set<VoiceFeature> = emptySet(),
        val isInstalled: Boolean = true,
        val isNetworkRequired: Boolean = false,
        val isPremium: Boolean = false,
        val minQuality: Float = DEFAULT_VOICE_QUALITY_THRESHOLD,
        val maxFileSize: Long = Long.MAX_VALUE,
        val customCriteria: (VoiceInfo) -> Boolean = { true }
    )
    
    /**
     * Voice selection result
     */
    data class VoiceSelectionResult(
        val success: Boolean,
        val selectedVoice: VoiceInfo? = null,
        val alternatives: List<VoiceInfo> = emptyList(),
        val reason: String? = null,
        val confidence: Float = 0.0f
    )
    
    /**
     * Voice selection event
     */
    data class VoiceSelectionEvent(
        val type: VoiceSelectionEventType,
        val message: String,
        val voiceId: String? = null,
        val language: Locale? = null,
        val timestamp: Long = System.currentTimeMillis()
    )
    
    /**
     * Voice selection event types
     */
    enum class VoiceSelectionEventType {
        VOICE_DISCOVERED, VOICE_SELECTED, VOICE_CHANGED, VOICE_FILTERED,
        PREFERENCES_UPDATED, RANKINGS_UPDATED, CACHE_UPDATED
    }
    
    /**
     * Voice selection listener interface
     */
    interface VoiceSelectionListener {
        fun onVoiceDiscovered(voice: VoiceInfo)
        fun onVoiceSelected(voice: VoiceInfo, reason: String)
        fun onVoiceChanged(oldVoice: VoiceInfo?, newVoice: VoiceInfo)
        fun onVoiceFiltered(voices: List<VoiceInfo>)
        fun onPreferencesUpdated(preferences: VoicePreference)
        fun onRankingsUpdated(rankings: Map<String, Float>)
        fun onCacheUpdated(cacheSize: Int)
    }
    
    /**
     * Discovers available voices
     */
    suspend fun discoverVoices(language: Locale? = null): List<VoiceInfo> = withContext(Dispatchers.IO) {
        try {
            val targetLanguage = language ?: currentLanguage
            val voices = mutableListOf<VoiceInfo>()
            
            // Discover system voices
            val systemVoices = discoverSystemVoices(targetLanguage)
            voices.addAll(systemVoices)
            
            // Discover engine-specific voices
            val engineVoices = discoverEngineVoices(targetLanguage)
            voices.addAll(engineVoices)
            
            // Discover network voices
            val networkVoices = discoverNetworkVoices(targetLanguage)
            voices.addAll(networkVoices)
            
            // Cache discovered voices
            voices.forEach { voice ->
                voiceCache[voice.id] = voice
                notifyVoiceSelectionEvent(VoiceSelectionEvent(
                    type = VoiceSelectionEventType.VOICE_DISCOVERED,
                    message = "Voice discovered: ${voice.name}",
                    voiceId = voice.id,
                    language = voice.locale
                ))
            }
            
            // Limit cache size
            if (voiceCache.size > MAX_VOICE_CACHE_SIZE) {
                val sortedVoices = voiceCache.values.sortedByDescending { it.quality.ordinal }
                val voicesToRemove = sortedVoices.drop(MAX_VOICE_CACHE_SIZE)
                voicesToRemove.forEach { voice ->
                    voiceCache.remove(voice.id)
                }
            }
            
            notifyVoiceSelectionEvent(VoiceSelectionEvent(
                type = VoiceSelectionEventType.CACHE_UPDATED,
                message = "Voice cache updated",
                language = targetLanguage
            ))
            
            Log.d(TAG, "Discovered ${voices.size} voices for language: ${targetLanguage.displayName}")
            voices
            
        } catch (e: Exception) {
            Log.e(TAG, "Failed to discover voices", e)
            emptyList()
        }
    }
    
    /**
     * Discovers system voices
     */
    private suspend fun discoverSystemVoices(language: Locale): List<VoiceInfo> = withContext(Dispatchers.IO) {
        try {
            // This is a simplified implementation
            // In a real implementation, you would query the TTS engine for available voices
            listOf(
                VoiceInfo(
                    name = "Default ${language.displayName}",
                    displayName = "Default ${language.displayName} Voice",
                    locale = language,
                    quality = VoiceQuality.HIGH,
                    gender = VoiceGender.NEUTRAL,
                    age = VoiceAge.ADULT,
                    accent = VoiceAccent.NEUTRAL,
                    characteristics = VoiceCharacteristics(
                        isClear = true,
                        isNatural = true,
                        isProfessional = true
                    ),
                    isInstalled = true,
                    isDefault = true,
                    supportedFeatures = setOf(VoiceFeature.SSML, VoiceFeature.OFFLINE)
                )
            )
        } catch (e: Exception) {
            Log.e(TAG, "Failed to discover system voices", e)
            emptyList()
        }
    }
    
    /**
     * Discovers engine-specific voices
     */
    private suspend fun discoverEngineVoices(language: Locale): List<VoiceInfo> = withContext(Dispatchers.IO) {
        try {
            // This would query specific TTS engines for available voices
            emptyList()
        } catch (e: Exception) {
            Log.e(TAG, "Failed to discover engine voices", e)
            emptyList()
        }
    }
    
    /**
     * Discovers network voices
     */
    private suspend fun discoverNetworkVoices(language: Locale): List<VoiceInfo> = withContext(Dispatchers.IO) {
        try {
            // This would query network-based TTS services for available voices
            emptyList()
        } catch (e: Exception) {
            Log.e(TAG, "Failed to discover network voices", e)
            emptyList()
        }
    }
    
    /**
     * Selects the best voice based on criteria
     */
    suspend fun selectVoice(criteria: VoiceSelectionCriteria): VoiceSelectionResult = withContext(Dispatchers.IO) {
        try {
            // Discover voices if not cached
            if (voiceCache.isEmpty()) {
                discoverVoices(criteria.language)
            }
            
            // Filter voices based on criteria
            val filteredVoices = filterVoices(criteria)
            
            if (filteredVoices.isEmpty()) {
                return@withContext VoiceSelectionResult(
                    success = false,
                    reason = "No voices found matching criteria"
                )
            }
            
            // Rank voices
            val rankedVoices = rankVoices(filteredVoices, criteria)
            
            // Select the best voice
            val selectedVoice = rankedVoices.first()
            val alternatives = rankedVoices.drop(1)
            
            // Update current voice
            val oldVoice = currentVoice
            currentVoice = selectedVoice
            currentLanguage = selectedVoice.locale
            
            // Update preferences
            updateVoicePreferences(selectedVoice, criteria)
            
            // Notify listeners
            notifyVoiceSelectionEvent(VoiceSelectionEvent(
                type = VoiceSelectionEventType.VOICE_SELECTED,
                message = "Voice selected: ${selectedVoice.name}",
                voiceId = selectedVoice.id,
                language = selectedVoice.locale
            ))
            
            notifyVoiceSelectionEvent(VoiceSelectionEvent(
                type = VoiceSelectionEventType.VOICE_CHANGED,
                message = "Voice changed",
                voiceId = selectedVoice.id,
                language = selectedVoice.locale
            ))
            
            Log.d(TAG, "Selected voice: ${selectedVoice.name} for language: ${criteria.language.displayName}")
            
            VoiceSelectionResult(
                success = true,
                selectedVoice = selectedVoice,
                alternatives = alternatives,
                reason = "Voice selected based on criteria",
                confidence = calculateConfidence(selectedVoice, criteria)
            )
            
        } catch (e: Exception) {
            Log.e(TAG, "Failed to select voice", e)
            VoiceSelectionResult(
                success = false,
                reason = "Voice selection failed: ${e.message}"
            )
        }
    }
    
    /**
     * Filters voices based on criteria
     */
    private fun filterVoices(criteria: VoiceSelectionCriteria): List<VoiceInfo> {
        return voiceCache.values.filter { voice ->
            // Language filter
            if (voice.locale != criteria.language) return@filter false
            
            // Gender filter
            criteria.gender?.let { if (voice.gender != it) return@filter false }
            
            // Age filter
            criteria.age?.let { if (voice.age != it) return@filter false }
            
            // Accent filter
            criteria.accent?.let { if (voice.accent != it) return@filter false }
            
            // Quality filter
            criteria.quality?.let { if (voice.quality != it) return@filter false }
            
            // Characteristics filter
            criteria.characteristics?.let { char ->
                if (char.isWarm && !voice.characteristics.isWarm) return@filter false
                if (char.isProfessional && !voice.characteristics.isProfessional) return@filter false
                if (char.isFriendly && !voice.characteristics.isFriendly) return@filter false
                if (char.isAuthoritative && !voice.characteristics.isAuthoritative) return@filter false
                if (char.isCalm && !voice.characteristics.isCalm) return@filter false
                if (char.isEnergetic && !voice.characteristics.isEnergetic) return@filter false
                if (char.isExpressive && !voice.characteristics.isExpressive) return@filter false
                if (char.isMonotone && !voice.characteristics.isMonotone) return@filter false
                if (char.isClear && !voice.characteristics.isClear) return@filter false
                if (char.isNatural && !voice.characteristics.isNatural) return@filter false
                if (char.isRobotic && !voice.characteristics.isRobotic) return@filter false
                if (char.isEmotional && !voice.characteristics.isEmotional) return@filter false
                if (char.isFormal && !voice.characteristics.isFormal) return@filter false
                if (char.isCasual && !voice.characteristics.isCasual) return@filter false
                if (char.isSlow && !voice.characteristics.isSlow) return@filter false
                if (char.isFast && !voice.characteristics.isFast) return@filter false
                if (char.isHighPitched && !voice.characteristics.isHighPitched) return@filter false
                if (char.isLowPitched && !voice.characteristics.isLowPitched) return@filter false
                if (char.isRaspy && !voice.characteristics.isRaspy) return@filter false
                if (char.isSmooth && !voice.characteristics.isSmooth) return@filter false
            }
            
            // Features filter
            if (criteria.features.isNotEmpty()) {
                if (!criteria.features.all { it in voice.supportedFeatures }) return@filter false
            }
            
            // Installation filter
            if (criteria.isInstalled && !voice.isInstalled) return@filter false
            
            // Network requirement filter
            if (criteria.isNetworkRequired && !voice.isNetworkRequired) return@filter false
            
            // Premium filter
            if (criteria.isPremium && !voice.isPremium) return@filter false
            
            // Quality threshold filter
            if (voice.quality.ordinal < criteria.minQuality) return@filter false
            
            // File size filter
            if (voice.fileSize > criteria.maxFileSize) return@filter false
            
            // Custom criteria filter
            criteria.customCriteria(voice)
        }
    }
    
    /**
     * Ranks voices based on criteria and preferences
     */
    private fun rankVoices(voices: List<VoiceInfo>, criteria: VoiceSelectionCriteria): List<VoiceInfo> {
        return voices.sortedByDescending { voice ->
            var score = 0.0f
            
            // Quality score
            score += voice.quality.ordinal * 0.3f
            
            // Default voice bonus
            if (voice.isDefault) score += 0.2f
            
            // Installed voice bonus
            if (voice.isInstalled) score += 0.1f
            
            // Network requirement penalty
            if (voice.isNetworkRequired) score -= 0.1f
            
            // Premium penalty (if not specifically requested)
            if (voice.isPremium && !criteria.isPremium) score -= 0.05f
            
            // Characteristics score
            criteria.characteristics?.let { char ->
                if (char.isWarm && voice.characteristics.isWarm) score += 0.05f
                if (char.isProfessional && voice.characteristics.isProfessional) score += 0.05f
                if (char.isFriendly && voice.characteristics.isFriendly) score += 0.05f
                if (char.isAuthoritative && voice.characteristics.isAuthoritative) score += 0.05f
                if (char.isCalm && voice.characteristics.isCalm) score += 0.05f
                if (char.isEnergetic && voice.characteristics.isEnergetic) score += 0.05f
                if (char.isExpressive && voice.characteristics.isExpressive) score += 0.05f
                if (char.isClear && voice.characteristics.isClear) score += 0.05f
                if (char.isNatural && voice.characteristics.isNatural) score += 0.05f
            }
            
            // User preferences score
            val preferences = voicePreferences[criteria.language.toString()]
            preferences?.let { pref ->
                if (pref.preferredGender == voice.gender) score += 0.1f
                if (pref.preferredAge == voice.age) score += 0.1f
                if (pref.preferredAccent == voice.accent) score += 0.1f
                if (pref.preferredQuality == voice.quality) score += 0.1f
                if (voice.id in pref.preferredVoices) score += 0.2f
                if (voice.id in pref.excludedVoices) score -= 0.5f
                pref.customRankings[voice.id]?.let { customScore ->
                    score += customScore
                }
            }
            
            // Cache the ranking
            voiceRankings[voice.id] = score
            
            score
        }
    }
    
    /**
     * Calculates confidence score for voice selection
     */
    private fun calculateConfidence(voice: VoiceInfo, criteria: VoiceSelectionCriteria): Float {
        var confidence = 0.5f
        
        // Quality confidence
        confidence += voice.quality.ordinal * 0.1f
        
        // Criteria match confidence
        if (criteria.gender == voice.gender) confidence += 0.1f
        if (criteria.age == voice.age) confidence += 0.1f
        if (criteria.accent == voice.accent) confidence += 0.1f
        if (criteria.quality == voice.quality) confidence += 0.1f
        
        // Default voice confidence
        if (voice.isDefault) confidence += 0.1f
        
        // Installed voice confidence
        if (voice.isInstalled) confidence += 0.1f
        
        return confidence.coerceIn(0.0f, 1.0f)
    }
    
    /**
     * Updates voice preferences
     */
    private fun updateVoicePreferences(voice: VoiceInfo, criteria: VoiceSelectionCriteria) {
        val languageKey = criteria.language.toString()
        val existingPrefs = voicePreferences[languageKey]
        
        val updatedPrefs = existingPrefs?.copy(
            preferredGender = criteria.gender ?: existingPrefs.preferredGender,
            preferredAge = criteria.age ?: existingPrefs.preferredAge,
            preferredAccent = criteria.accent ?: existingPrefs.preferredAccent,
            preferredQuality = criteria.quality ?: existingPrefs.preferredQuality,
            preferredCharacteristics = criteria.characteristics ?: existingPrefs.preferredCharacteristics,
            lastUsed = System.currentTimeMillis()
        ) ?: VoicePreference(
            userId = "default",
            language = criteria.language,
            preferredGender = criteria.gender,
            preferredAge = criteria.age,
            preferredAccent = criteria.accent,
            preferredQuality = criteria.quality,
            preferredCharacteristics = criteria.characteristics,
            lastUsed = System.currentTimeMillis()
        )
        
        voicePreferences[languageKey] = updatedPrefs
        
        notifyVoiceSelectionEvent(VoiceSelectionEvent(
            type = VoiceSelectionEventType.PREFERENCES_UPDATED,
            message = "Preferences updated for ${criteria.language.displayName}",
            language = criteria.language
        ))
    }
    
    /**
     * Gets available voices for a language
     */
    suspend fun getAvailableVoices(language: Locale): List<VoiceInfo> = withContext(Dispatchers.IO) {
        try {
            if (voiceCache.isEmpty()) {
                discoverVoices(language)
            }
            
            voiceCache.values.filter { it.locale == language }
        } catch (e: Exception) {
            Log.e(TAG, "Failed to get available voices", e)
            emptyList()
        }
    }
    
    /**
     * Gets current voice
     */
    fun getCurrentVoice(): VoiceInfo? = currentVoice
    
    /**
     * Gets current language
     */
    fun getCurrentLanguage(): Locale = currentLanguage
    
    /**
     * Sets voice selection mode
     */
    fun setVoiceSelectionMode(mode: VoiceSelectionMode) {
        voiceSelectionMode = mode
        Log.d(TAG, "Voice selection mode set to: $mode")
    }
    
    /**
     * Gets voice selection mode
     */
    fun getVoiceSelectionMode(): VoiceSelectionMode = voiceSelectionMode
    
    /**
     * Adds a voice filter
     */
    fun addVoiceFilter(filter: VoiceFilter) {
        voiceFilters[filter.name] = filter
        Log.d(TAG, "Voice filter added: ${filter.name}")
    }
    
    /**
     * Removes a voice filter
     */
    fun removeVoiceFilter(filterName: String) {
        voiceFilters.remove(filterName)
        Log.d(TAG, "Voice filter removed: $filterName")
    }
    
    /**
     * Gets voice statistics
     */
    fun getVoiceStats(): Map<String, Any> {
        return mapOf(
            "totalVoices" to voiceCache.size,
            "currentVoice" to (currentVoice?.name ?: "None"),
            "currentLanguage" to currentLanguage.displayName,
            "selectionMode" to voiceSelectionMode.name,
            "filtersCount" to voiceFilters.size,
            "preferencesCount" to voicePreferences.size,
            "rankingsCount" to voiceRankings.size
        )
    }
    
    /**
     * Adds a voice selection listener
     */
    fun addVoiceSelectionListener(listener: VoiceSelectionListener) {
        voiceSelectionListeners.add(listener)
    }
    
    /**
     * Removes a voice selection listener
     */
    fun removeVoiceSelectionListener(listener: VoiceSelectionListener) {
        voiceSelectionListeners.remove(listener)
    }
    
    /**
     * Creates a Flow for voice selection events
     */
    fun createVoiceSelectionEventFlow(): Flow<VoiceSelectionEvent> = flow {
        // This would typically emit events as they occur
        // For now, we'll emit the current state
        emit(VoiceSelectionEvent(
            type = VoiceSelectionEventType.CACHE_UPDATED,
            message = "Voice selection event flow created"
        ))
    }.flowOn(Dispatchers.Default)
    
    /**
     * Notifies voice selection event
     */
    private fun notifyVoiceSelectionEvent(event: VoiceSelectionEvent) {
        voiceSelectionListeners.forEach { listener ->
            try {
                when (event.type) {
                    VoiceSelectionEventType.VOICE_DISCOVERED -> {
                        event.voiceId?.let { voiceId ->
                            voiceCache[voiceId]?.let { voice ->
                                listener.onVoiceDiscovered(voice)
                            }
                        }
                    }
                    VoiceSelectionEventType.VOICE_SELECTED -> {
                        event.voiceId?.let { voiceId ->
                            voiceCache[voiceId]?.let { voice ->
                                listener.onVoiceSelected(voice, event.message)
                            }
                        }
                    }
                    VoiceSelectionEventType.VOICE_CHANGED -> {
                        event.voiceId?.let { voiceId ->
                            voiceCache[voiceId]?.let { voice ->
                                listener.onVoiceChanged(currentVoice, voice)
                            }
                        }
                    }
                    VoiceSelectionEventType.VOICE_FILTERED -> {
                        // This would need to be implemented based on filtered voices
                    }
                    VoiceSelectionEventType.PREFERENCES_UPDATED -> {
                        event.language?.let { language ->
                            voicePreferences[language.toString()]?.let { prefs ->
                                listener.onPreferencesUpdated(prefs)
                            }
                        }
                    }
                    VoiceSelectionEventType.RANKINGS_UPDATED -> {
                        listener.onRankingsUpdated(voiceRankings)
                    }
                    VoiceSelectionEventType.CACHE_UPDATED -> {
                        listener.onCacheUpdated(voiceCache.size)
                    }
                }
            } catch (e: Exception) {
                Log.e(TAG, "Error notifying voice selection event", e)
            }
        }
    }
    
    /**
     * Clears voice cache
     */
    fun clearCache() {
        voiceCache.clear()
        voiceRankings.clear()
        
        notifyVoiceSelectionEvent(VoiceSelectionEvent(
            type = VoiceSelectionEventType.CACHE_UPDATED,
            message = "Voice cache cleared"
        ))
        
        Log.d(TAG, "Voice cache cleared")
    }
    
    /**
     * Cleans up the voice selector
     */
    fun cleanup() {
        try {
            clearCache()
            voicePreferences.clear()
            voiceFilters.clear()
            voiceSelectionListeners.clear()
            
            Log.d(TAG, "Voice selector cleaned up")
            
        } catch (e: Exception) {
            Log.e(TAG, "Error cleaning up voice selector", e)
        }
    }
}

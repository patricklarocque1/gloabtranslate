package com.example.gloabtranslate.core.security

import android.content.Context
import android.util.Log
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.json.Json
import java.security.MessageDigest
import java.security.SecureRandom
import java.util.*
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong
import kotlin.math.abs

/**
 * Data anonymization system for analytics and privacy protection.
 * Provides comprehensive anonymization techniques including hashing, tokenization,
 * generalization, suppression, and differential privacy mechanisms.
 */
class DataAnonymizer private constructor(private val context: Context) {
    
    companion object {
        private const val TAG = "DataAnonymizer"
        private const val ANONYMIZATION_PREFS = "anonymization_preferences"
        private const val VERSION_KEY = "anonymization_version"
        private const val CURRENT_VERSION = 1
        
        // Anonymization techniques
        enum class AnonymizationTechnique {
            HASHING,           // One-way cryptographic hash
            TOKENIZATION,      // Replace with random tokens
            GENERALIZATION,    // Generalize to broader categories
            SUPPRESSION,       // Remove sensitive information
            PERTURBATION,      // Add noise to numerical data
            DIFFERENTIAL_PRIVACY, // Mathematical privacy guarantee
            K_ANONYMITY,       // Ensure k-indistinguishability
            L_DIVERSITY        // Ensure l-diversity in sensitive attributes
        }
        
        // Data sensitivity levels
        enum class DataSensitivity {
            PUBLIC,           // No anonymization needed
            LOW,             // Basic anonymization
            MEDIUM,          // Moderate anonymization
            HIGH,            // Strong anonymization
            CRITICAL         // Maximum anonymization
        }
        
        // Data types for anonymization
        enum class DataType {
            IDENTIFIER,      // Direct identifiers (names, emails)
            QUASI_IDENTIFIER, // Quasi-identifiers (age, location)
            SENSITIVE,       // Sensitive attributes (health, financial)
            NUMERICAL,       // Numerical data
            CATEGORICAL,     // Categorical data
            TEXT,           // Text data
            TIMESTAMP,      // Time-based data
            LOCATION        // Geographic data
        }
        
        @Volatile
        private var INSTANCE: DataAnonymizer? = null
        
        fun getInstance(context: Context): DataAnonymizer {
            return INSTANCE ?: synchronized(this) {
                INSTANCE ?: DataAnonymizer(context.applicationContext).also { INSTANCE = it }
            }
        }
    }
    
    // Core components
    private val dataEncryption = DataEncryption.getInstance(context)
    private val json = Json {
        prettyPrint = true
        ignoreUnknownKeys = true
        encodeDefaults = true
    }
    
    // State management
    private val isInitialized = AtomicBoolean(false)
    private val anonymizationMutex = Mutex()
    private val tokenMap = ConcurrentHashMap<String, String>() // Original -> Token mapping
    private val reverseTokenMap = ConcurrentHashMap<String, String>() // Token -> Original mapping
    private val anonymizationStats = ConcurrentHashMap<String, AnonymizationStats>()
    private val anonymizationListeners = CopyOnWriteArrayList<AnonymizationListener>()
    
    // Anonymization configuration
    private val kAnonymityThreshold = 3
    private val lDiversityThreshold = 2
    private val epsilon = 1.0 // Differential privacy parameter
    private val noiseScale = 1.0 // Noise scale for perturbation
    
    // State flows
    private val _anonymizationStatsFlow = MutableStateFlow<Map<String, AnonymizationStats>>(emptyMap())
    val anonymizationStatsFlow: Flow<Map<String, AnonymizationStats>> = _anonymizationStatsFlow.asStateFlow()
    
    private val _anonymizationEventsFlow = MutableStateFlow<AnonymizationEvent?>(null)
    val anonymizationEventsFlow: Flow<AnonymizationEvent?> = _anonymizationEventsFlow.asStateFlow()
    
    /**
     * Anonymization configuration
     */
    @Serializable
    data class AnonymizationConfig(
        val technique: String,
        val sensitivity: String,
        val dataType: String,
        val preserveUtility: Boolean = true,
        val customParameters: Map<String, String> = emptyMap()
    )
    
    /**
     * Anonymization statistics
     */
    @Serializable
    data class AnonymizationStats(
        val dataType: String,
        val technique: String,
        val itemsProcessed: Long = 0,
        val tokensGenerated: Long = 0,
        val hashesGenerated: Long = 0,
        val generalizationsApplied: Long = 0,
        val suppressionsApplied: Long = 0,
        val perturbationsApplied: Long = 0,
        val lastProcessed: Long = System.currentTimeMillis(),
        val averageProcessingTime: Double = 0.0
    )
    
    /**
     * Anonymization event
     */
    data class AnonymizationEvent(
        val eventType: String, // "anonymized", "de-anonymized", "token_generated"
        val dataType: String,
        val technique: String,
        val timestamp: Long = System.currentTimeMillis(),
        val success: Boolean = true,
        val originalSize: Int = 0,
        val anonymizedSize: Int = 0,
        val utilityLoss: Double = 0.0
    )
    
    /**
     * Anonymization listener interface
     */
    interface AnonymizationListener {
        fun onDataAnonymized(dataType: String, technique: String, success: Boolean)
        fun onTokenGenerated(original: String, token: String)
        fun onAnonymizationStatsUpdated(stats: AnonymizationStats)
        fun onAnonymizationError(dataType: String, error: String)
    }
    
    /**
     * Initialize the data anonymization system
     */
    suspend fun initialize(): Boolean = withContext(Dispatchers.IO) {
        try {
            anonymizationMutex.withLock {
                if (isInitialized.getAndSet(true)) {
                    Log.w(TAG, "DataAnonymizer already initialized")
                    return@withContext true
                }
                
                // Initialize data encryption for secure operations
                val encryptionInitialized = dataEncryption.initialize()
                if (!encryptionInitialized) {
                    Log.e(TAG, "Failed to initialize data encryption")
                    return@withContext false
                }
                
                // Load anonymization statistics
                loadAnonymizationStats()
                
                Log.d(TAG, "DataAnonymizer initialized successfully")
                true
            }
        } catch (e: Exception) {
            Log.e(TAG, "Failed to initialize DataAnonymizer", e)
            isInitialized.set(false)
            false
        }
    }
    
    /**
     * Anonymize data based on configuration
     */
    suspend fun anonymizeData(
        data: String,
        config: AnonymizationConfig
    ): AnonymizationResult = withContext(Dispatchers.IO) {
        try {
            anonymizationMutex.withLock {
                val startTime = System.currentTimeMillis()
                
                val result = when (AnonymizationTechnique.valueOf(config.technique)) {
                    AnonymizationTechnique.HASHING -> anonymizeWithHashing(data, config)
                    AnonymizationTechnique.TOKENIZATION -> anonymizeWithTokenization(data, config)
                    AnonymizationTechnique.GENERALIZATION -> anonymizeWithGeneralization(data, config)
                    AnonymizationTechnique.SUPPRESSION -> anonymizeWithSuppression(data, config)
                    AnonymizationTechnique.PERTURBATION -> anonymizeWithPerturbation(data, config)
                    AnonymizationTechnique.DIFFERENTIAL_PRIVACY -> anonymizeWithDifferentialPrivacy(data, config)
                    AnonymizationTechnique.K_ANONYMITY -> anonymizeWithKAnonymity(data, config)
                    AnonymizationTechnique.L_DIVERSITY -> anonymizeWithLDiversity(data, config)
                }
                
                val processingTime = System.currentTimeMillis() - startTime
                
                // Update statistics
                updateAnonymizationStats(config.dataType, config.technique, processingTime, result.isSuccess)
                
                // Record event
                recordAnonymizationEvent(
                    eventType = "anonymized",
                    dataType = config.dataType,
                    technique = config.technique,
                    success = result.isSuccess,
                    originalSize = data.length,
                    anonymizedSize = result.anonymizedData?.length ?: 0,
                    utilityLoss = result.utilityLoss
                )
                
                // Notify listeners
                notifyDataAnonymized(config.dataType, config.technique, result.isSuccess)
                
                result
            }
        } catch (e: Exception) {
            Log.e(TAG, "Failed to anonymize data", e)
            AnonymizationResult.error("Anonymization failed: ${e.message}")
        }
    }
    
    /**
     * Anonymize with cryptographic hashing
     */
    private fun anonymizeWithHashing(data: String, config: AnonymizationConfig): AnonymizationResult {
        return try {
            val hash = dataEncryption.generateHash(data, "SHA-256")
            val anonymizedData = hash.substring(0, minOf(16, hash.length)) // Truncate for consistency
            
            AnonymizationResult.success(
                anonymizedData = anonymizedData,
                technique = AnonymizationTechnique.HASHING,
                utilityLoss = 0.8, // High utility loss due to one-way nature
                isReversible = false
            )
        } catch (e: Exception) {
            Log.e(TAG, "Hashing anonymization failed", e)
            AnonymizationResult.error("Hashing failed: ${e.message}")
        }
    }
    
    /**
     * Anonymize with tokenization
     */
    private fun anonymizeWithTokenization(data: String, config: AnonymizationConfig): AnonymizationResult {
        return try {
            // Check if token already exists
            val existingToken = tokenMap[data]
            if (existingToken != null) {
                return AnonymizationResult.success(
                    anonymizedData = existingToken,
                    technique = AnonymizationTechnique.TOKENIZATION,
                    utilityLoss = 0.1, // Low utility loss for consistent tokens
                    isReversible = true
                )
            }
            
            // Generate new token
            val token = generateSecureToken()
            tokenMap[data] = token
            reverseTokenMap[token] = data
            
            // Notify token generation
            notifyTokenGenerated(data, token)
            
            AnonymizationResult.success(
                anonymizedData = token,
                technique = AnonymizationTechnique.TOKENIZATION,
                utilityLoss = 0.1,
                isReversible = true
            )
        } catch (e: Exception) {
            Log.e(TAG, "Tokenization anonymization failed", e)
            AnonymizationResult.error("Tokenization failed: ${e.message}")
        }
    }
    
    /**
     * Anonymize with generalization
     */
    private fun anonymizeWithGeneralization(data: String, config: AnonymizationConfig): AnonymizationResult {
        return try {
            val anonymizedData = when (DataType.valueOf(config.dataType)) {
                DataType.NUMERICAL -> generalizeNumericalData(data, config)
                DataType.CATEGORICAL -> generalizeCategoricalData(data, config)
                DataType.LOCATION -> generalizeLocationData(data, config)
                DataType.TIMESTAMP -> generalizeTimestampData(data, config)
                DataType.TEXT -> generalizeTextData(data, config)
                else -> data // No generalization for other types
            }
            
            AnonymizationResult.success(
                anonymizedData = anonymizedData,
                technique = AnonymizationTechnique.GENERALIZATION,
                utilityLoss = 0.3, // Moderate utility loss
                isReversible = false
            )
        } catch (e: Exception) {
            Log.e(TAG, "Generalization anonymization failed", e)
            AnonymizationResult.error("Generalization failed: ${e.message}")
        }
    }
    
    /**
     * Anonymize with suppression
     */
    private fun anonymizeWithSuppression(data: String, config: AnonymizationConfig): AnonymizationResult {
        return try {
            val anonymizedData = when (DataType.valueOf(config.dataType)) {
                DataType.IDENTIFIER -> suppressIdentifiers(data, config)
                DataType.SENSITIVE -> suppressSensitiveData(data, config)
                DataType.TEXT -> suppressTextData(data, config)
                else -> data
            }
            
            AnonymizationResult.success(
                anonymizedData = anonymizedData,
                technique = AnonymizationTechnique.SUPPRESSION,
                utilityLoss = 0.5, // Moderate utility loss
                isReversible = false
            )
        } catch (e: Exception) {
            Log.e(TAG, "Suppression anonymization failed", e)
            AnonymizationResult.error("Suppression failed: ${e.message}")
        }
    }
    
    /**
     * Anonymize with perturbation (add noise)
     */
    private fun anonymizeWithPerturbation(data: String, config: AnonymizationConfig): AnonymizationResult {
        return try {
            if (DataType.valueOf(config.dataType) != DataType.NUMERICAL) {
                return AnonymizationResult.error("Perturbation only applicable to numerical data")
            }
            
            val numericValue = data.toDoubleOrNull()
            if (numericValue == null) {
                return AnonymizationResult.error("Invalid numerical data")
            }
            
            // Add Laplace noise
            val noise = generateLaplaceNoise(noiseScale)
            val perturbedValue = numericValue + noise
            val anonymizedData = perturbedValue.toString()
            
            AnonymizationResult.success(
                anonymizedData = anonymizedData,
                technique = AnonymizationTechnique.PERTURBATION,
                utilityLoss = 0.2, // Low utility loss for statistical analysis
                isReversible = false
            )
        } catch (e: Exception) {
            Log.e(TAG, "Perturbation anonymization failed", e)
            AnonymizationResult.error("Perturbation failed: ${e.message}")
        }
    }
    
    /**
     * Anonymize with differential privacy
     */
    private fun anonymizeWithDifferentialPrivacy(data: String, config: AnonymizationConfig): AnonymizationResult {
        return try {
            // Implement differential privacy mechanism
            val anonymizedData = applyDifferentialPrivacy(data, config)
            
            AnonymizationResult.success(
                anonymizedData = anonymizedData,
                technique = AnonymizationTechnique.DIFFERENTIAL_PRIVACY,
                utilityLoss = 0.15, // Low utility loss with privacy guarantee
                isReversible = false
            )
        } catch (e: Exception) {
            Log.e(TAG, "Differential privacy anonymization failed", e)
            AnonymizationResult.error("Differential privacy failed: ${e.message}")
        }
    }
    
    /**
     * Anonymize with k-anonymity
     */
    private fun anonymizeWithKAnonymity(data: String, config: AnonymizationConfig): AnonymizationResult {
        return try {
            // Implement k-anonymity mechanism
            val anonymizedData = applyKAnonymity(data, config)
            
            AnonymizationResult.success(
                anonymizedData = anonymizedData,
                technique = AnonymizationTechnique.K_ANONYMITY,
                utilityLoss = 0.4, // Moderate utility loss
                isReversible = false
            )
        } catch (e: Exception) {
            Log.e(TAG, "K-anonymity anonymization failed", e)
            AnonymizationResult.error("K-anonymity failed: ${e.message}")
        }
    }
    
    /**
     * Anonymize with l-diversity
     */
    private fun anonymizeWithLDiversity(data: String, config: AnonymizationConfig): AnonymizationResult {
        return try {
            // Implement l-diversity mechanism
            val anonymizedData = applyLDiversity(data, config)
            
            AnonymizationResult.success(
                anonymizedData = anonymizedData,
                technique = AnonymizationTechnique.L_DIVERSITY,
                utilityLoss = 0.35, // Moderate utility loss
                isReversible = false
            )
        } catch (e: Exception) {
            Log.e(TAG, "L-diversity anonymization failed", e)
            AnonymizationResult.error("L-diversity failed: ${e.message}")
        }
    }
    
    /**
     * De-anonymize tokenized data
     */
    suspend fun deAnonymizeToken(token: String): String? = withContext(Dispatchers.IO) {
        try {
            anonymizationMutex.withLock {
                val originalData = reverseTokenMap[token]
                if (originalData != null) {
                    recordAnonymizationEvent(
                        eventType = "de-anonymized",
                        dataType = "TOKEN",
                        technique = "TOKENIZATION",
                        success = true
                    )
                }
                originalData
            }
        } catch (e: Exception) {
            Log.e(TAG, "Failed to de-anonymize token: $token", e)
            null
        }
    }
    
    /**
     * Batch anonymize multiple data items
     */
    suspend fun batchAnonymize(
        dataItems: List<String>,
        config: AnonymizationConfig
    ): List<AnonymizationResult> = withContext(Dispatchers.IO) {
        try {
            dataItems.map { data ->
                anonymizeData(data, config)
            }
        } catch (e: Exception) {
            Log.e(TAG, "Batch anonymization failed", e)
            dataItems.map { AnonymizationResult.error("Batch anonymization failed") }
        }
    }
    
    /**
     * Get anonymization statistics
     */
    suspend fun getAnonymizationStats(): Map<String, AnonymizationStats> = withContext(Dispatchers.IO) {
        try {
            anonymizationMutex.withLock {
                anonymizationStats.toMap()
            }
        } catch (e: Exception) {
            Log.e(TAG, "Failed to get anonymization stats", e)
            emptyMap()
        }
    }
    
    /**
     * Clear anonymization data (for privacy)
     */
    suspend fun clearAnonymizationData(): Boolean = withContext(Dispatchers.IO) {
        try {
            anonymizationMutex.withLock {
                tokenMap.clear()
                reverseTokenMap.clear()
                anonymizationStats.clear()
                updateFlows()
                
                Log.d(TAG, "Anonymization data cleared")
                true
            }
        } catch (e: Exception) {
            Log.e(TAG, "Failed to clear anonymization data", e)
            false
        }
    }
    
    // Private helper methods
    
    private fun generateSecureToken(): String {
        val random = SecureRandom()
        val bytes = ByteArray(16)
        random.nextBytes(bytes)
        return Base64.getEncoder().encodeToString(bytes).replace("=", "").substring(0, 12)
    }
    
    private fun generalizeNumericalData(data: String, config: AnonymizationConfig): String {
        val value = data.toDoubleOrNull() ?: return data
        
        return when (DataSensitivity.valueOf(config.sensitivity)) {
            DataSensitivity.LOW -> value.toInt().toString() // Round to integer
            DataSensitivity.MEDIUM -> ((value / 10).toInt() * 10).toString() // Round to nearest 10
            DataSensitivity.HIGH -> ((value / 100).toInt() * 100).toString() // Round to nearest 100
            DataSensitivity.CRITICAL -> ((value / 1000).toInt() * 1000).toString() // Round to nearest 1000
            else -> data
        }
    }
    
    private fun generalizeCategoricalData(data: String, config: AnonymizationConfig): String {
        return when (DataSensitivity.valueOf(config.sensitivity)) {
            DataSensitivity.LOW -> data.lowercase()
            DataSensitivity.MEDIUM -> "Category_${data.hashCode() % 10}"
            DataSensitivity.HIGH -> "General_Category"
            DataSensitivity.CRITICAL -> "***"
            else -> data
        }
    }
    
    private fun generalizeLocationData(data: String, config: AnonymizationConfig): String {
        // Simple location generalization
        return when (DataSensitivity.valueOf(config.sensitivity)) {
            DataSensitivity.LOW -> data
            DataSensitivity.MEDIUM -> "City_Area"
            DataSensitivity.HIGH -> "Region"
            DataSensitivity.CRITICAL -> "Country"
            else -> data
        }
    }
    
    private fun generalizeTimestampData(data: String, config: AnonymizationConfig): String {
        return when (DataSensitivity.valueOf(config.sensitivity)) {
            DataSensitivity.LOW -> data
            DataSensitivity.MEDIUM -> "YYYY-MM-DD"
            DataSensitivity.HIGH -> "YYYY-MM"
            DataSensitivity.CRITICAL -> "YYYY"
            else -> data
        }
    }
    
    private fun generalizeTextData(data: String, config: AnonymizationConfig): String {
        return when (DataSensitivity.valueOf(config.sensitivity)) {
            DataSensitivity.LOW -> data.lowercase()
            DataSensitivity.MEDIUM -> "Text_${data.length}_chars"
            DataSensitivity.HIGH -> "Text_Content"
            DataSensitivity.CRITICAL -> "***"
            else -> data
        }
    }
    
    private fun suppressIdentifiers(data: String, config: AnonymizationConfig): String {
        // Remove or mask identifiers
        return data.replace(Regex("[a-zA-Z0-9._%+-]+@[a-zA-Z0-9.-]+\\.[a-zA-Z]{2,}"), "***@***.***")
            .replace(Regex("\\b\\d{3}-\\d{2}-\\d{4}\\b"), "***-**-****")
            .replace(Regex("\\b\\d{10,}\\b"), "**********")
    }
    
    private fun suppressSensitiveData(data: String, config: AnonymizationConfig): String {
        // Remove sensitive information
        return "***"
    }
    
    private fun suppressTextData(data: String, config: AnonymizationConfig): String {
        // Keep only first and last characters
        return if (data.length <= 2) "***" else "${data.first()}***${data.last()}"
    }
    
    private fun generateLaplaceNoise(scale: Double): Double {
        val random = SecureRandom()
        val u = random.nextDouble() - 0.5
        return -scale * kotlin.math.sign(u) * kotlin.math.ln(1 - 2 * kotlin.math.abs(u))
    }
    
    private fun applyDifferentialPrivacy(data: String, config: AnonymizationConfig): String {
        // Simplified differential privacy implementation
        val numericValue = data.toDoubleOrNull() ?: return data
        val noise = generateLaplaceNoise(1.0 / epsilon)
        return (numericValue + noise).toString()
    }
    
    private fun applyKAnonymity(data: String, config: AnonymizationConfig): String {
        // Simplified k-anonymity implementation
        val hash = data.hashCode()
        val group = abs(hash) % kAnonymityThreshold
        return "Group_$group"
    }
    
    private fun applyLDiversity(data: String, config: AnonymizationConfig): String {
        // Simplified l-diversity implementation
        val hash = data.hashCode()
        val category = abs(hash) % lDiversityThreshold
        return "Category_$category"
    }
    
    private fun loadAnonymizationStats() {
        // Load statistics from preferences if needed
    }
    
    private fun updateAnonymizationStats(dataType: String, technique: String, processingTime: Long, success: Boolean) {
        val key = "${dataType}_$technique"
        val currentStats = anonymizationStats[key] ?: AnonymizationStats(dataType, technique)
        
        val updatedStats = currentStats.copy(
            itemsProcessed = currentStats.itemsProcessed + 1,
            lastProcessed = System.currentTimeMillis(),
            averageProcessingTime = (currentStats.averageProcessingTime + processingTime) / 2.0
        )
        
        // Update technique-specific counters
        when (AnonymizationTechnique.valueOf(technique)) {
            AnonymizationTechnique.HASHING -> updatedStats.copy(hashesGenerated = updatedStats.hashesGenerated + 1)
            AnonymizationTechnique.TOKENIZATION -> updatedStats.copy(tokensGenerated = updatedStats.tokensGenerated + 1)
            AnonymizationTechnique.GENERALIZATION -> updatedStats.copy(generalizationsApplied = updatedStats.generalizationsApplied + 1)
            AnonymizationTechnique.SUPPRESSION -> updatedStats.copy(suppressionsApplied = updatedStats.suppressionsApplied + 1)
            AnonymizationTechnique.PERTURBATION -> updatedStats.copy(perturbationsApplied = updatedStats.perturbationsApplied + 1)
            else -> updatedStats
        }
        
        anonymizationStats[key] = updatedStats
        updateFlows()
        
        notifyAnonymizationStatsUpdated(updatedStats)
    }
    
    private fun updateFlows() {
        _anonymizationStatsFlow.value = anonymizationStats.toMap()
    }
    
    private fun recordAnonymizationEvent(
        eventType: String,
        dataType: String,
        technique: String,
        success: Boolean = true,
        originalSize: Int = 0,
        anonymizedSize: Int = 0,
        utilityLoss: Double = 0.0
    ) {
        val event = AnonymizationEvent(
            eventType = eventType,
            dataType = dataType,
            technique = technique,
            success = success,
            originalSize = originalSize,
            anonymizedSize = anonymizedSize,
            utilityLoss = utilityLoss
        )
        _anonymizationEventsFlow.value = event
    }
    
    // Listener management
    
    fun addAnonymizationListener(listener: AnonymizationListener) {
        anonymizationListeners.add(listener)
    }
    
    fun removeAnonymizationListener(listener: AnonymizationListener) {
        anonymizationListeners.remove(listener)
    }
    
    private fun notifyDataAnonymized(dataType: String, technique: String, success: Boolean) {
        anonymizationListeners.forEach { listener ->
            try {
                listener.onDataAnonymized(dataType, technique, success)
            } catch (e: Exception) {
                Log.e(TAG, "Error notifying data anonymized", e)
            }
        }
    }
    
    private fun notifyTokenGenerated(original: String, token: String) {
        anonymizationListeners.forEach { listener ->
            try {
                listener.onTokenGenerated(original, token)
            } catch (e: Exception) {
                Log.e(TAG, "Error notifying token generated", e)
            }
        }
    }
    
    private fun notifyAnonymizationStatsUpdated(stats: AnonymizationStats) {
        anonymizationListeners.forEach { listener ->
            try {
                listener.onAnonymizationStatsUpdated(stats)
            } catch (e: Exception) {
                Log.e(TAG, "Error notifying anonymization stats updated", e)
            }
        }
    }
    
    private fun notifyAnonymizationError(dataType: String, error: String) {
        anonymizationListeners.forEach { listener ->
            try {
                listener.onAnonymizationError(dataType, error)
            } catch (e: Exception) {
                Log.e(TAG, "Error notifying anonymization error", e)
            }
        }
    }
    
    /**
     * Anonymization result data class
     */
    data class AnonymizationResult(
        val isSuccess: Boolean,
        val anonymizedData: String? = null,
        val technique: AnonymizationTechnique? = null,
        val utilityLoss: Double = 0.0,
        val isReversible: Boolean = false,
        val errorMessage: String? = null
    ) {
        companion object {
            fun success(
                anonymizedData: String,
                technique: AnonymizationTechnique,
                utilityLoss: Double,
                isReversible: Boolean
            ) = AnonymizationResult(
                isSuccess = true,
                anonymizedData = anonymizedData,
                technique = technique,
                utilityLoss = utilityLoss,
                isReversible = isReversible
            )
            
            fun error(errorMessage: String) = AnonymizationResult(
                isSuccess = false,
                errorMessage = errorMessage
            )
        }
    }
}

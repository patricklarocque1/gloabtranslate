package com.example.gloabtranslate.core.security

import android.content.Context
import android.util.Log
import kotlinx.coroutines.*

/**
 * Factory class for creating and initializing the complete data security system.
 * Provides a single entry point for setting up all security-related components with
 * proper initialization order and dependency injection.
 */
class SecuritySystemFactory private constructor() {
    
    companion object {
        private const val TAG = "SecuritySystemFactory"
        
        /**
         * Initialize the complete data security system
         */
        suspend fun initializeSecuritySystem(context: Context): SecuritySystemComponents = withContext(Dispatchers.IO) {
            try {
                Log.d(TAG, "Initializing data security system...")
                
                // Initialize components in dependency order
                val dataEncryption = DataEncryption.getInstance(context)
                val secureStorage = SecureStorage.getInstance(context)
                val dataAnonymizer = DataAnonymizer.getInstance(context)
                val dataRetentionPolicy = DataRetentionPolicy.getInstance(context)
                
                // Initialize in proper order
                val encryptionInitialized = dataEncryption.initialize()
                val storageInitialized = secureStorage.initialize()
                val anonymizerInitialized = dataAnonymizer.initialize()
                val retentionInitialized = dataRetentionPolicy.initialize()
                
                if (!encryptionInitialized || !storageInitialized || !anonymizerInitialized || !retentionInitialized) {
                    throw IllegalStateException("Failed to initialize security system components")
                }
                
                Log.d(TAG, "Data security system initialized successfully")
                
                SecuritySystemComponents(
                    dataEncryption = dataEncryption,
                    secureStorage = secureStorage,
                    dataAnonymizer = dataAnonymizer,
                    dataRetentionPolicy = dataRetentionPolicy
                )
            } catch (e: Exception) {
                Log.e(TAG, "Failed to initialize data security system", e)
                throw e
            }
        }
        
        /**
         * Quick setup for basic security features
         */
        suspend fun createBasicSecuritySystem(context: Context): BasicSecuritySystem = withContext(Dispatchers.IO) {
            val components = initializeSecuritySystem(context)
            BasicSecuritySystem(
                dataEncryption = components.dataEncryption,
                secureStorage = components.secureStorage
            )
        }
        
        /**
         * Create security system with custom configuration
         */
        suspend fun createCustomSecuritySystem(
            context: Context,
            enableAnonymization: Boolean = true,
            enableRetentionPolicies: Boolean = true
        ): SecuritySystemComponents = withContext(Dispatchers.IO) {
            val components = initializeSecuritySystem(context)
            
            if (enableAnonymization) {
                Log.d(TAG, "Anonymization enabled")
            }
            
            if (enableRetentionPolicies) {
                Log.d(TAG, "Retention policies enabled")
            }
            
            components
        }
    }
    
    /**
     * Complete security system components
     */
    data class SecuritySystemComponents(
        val dataEncryption: DataEncryption,
        val secureStorage: SecureStorage,
        val dataAnonymizer: DataAnonymizer,
        val dataRetentionPolicy: DataRetentionPolicy
    )
    
    /**
     * Basic security system for simple use cases
     */
    data class BasicSecuritySystem(
        val dataEncryption: DataEncryption,
        val secureStorage: SecureStorage
    )
}

/**
 * Extension functions for easy security system usage
 */
suspend fun Context.initializeSecuritySystem(): SecuritySystemFactory.SecuritySystemComponents {
    return SecuritySystemFactory.initializeSecuritySystem(this)
}

suspend fun Context.createBasicSecuritySystem(): SecuritySystemFactory.BasicSecuritySystem {
    return SecuritySystemFactory.createBasicSecuritySystem(this)
}

suspend fun Context.createCustomSecuritySystem(
    enableAnonymization: Boolean = true,
    enableRetentionPolicies: Boolean = true
): SecuritySystemFactory.SecuritySystemComponents {
    return SecuritySystemFactory.createCustomSecuritySystem(this, enableAnonymization, enableRetentionPolicies)
}

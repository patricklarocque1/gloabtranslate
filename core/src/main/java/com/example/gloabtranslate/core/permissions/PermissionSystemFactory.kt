package com.example.gloabtranslate.core.permissions

import android.content.Context
import android.util.Log
import com.example.gloabtranslate.core.data.preferences.PermissionPreferences
import com.example.gloabtranslate.core.privacy.PrivacyManager
import kotlinx.coroutines.*

/**
 * Factory class for creating and initializing the complete permission management system.
 * Provides a single entry point for setting up all permission-related components with
 * proper initialization order and dependency injection.
 */
class PermissionSystemFactory private constructor() {
    
    companion object {
        private const val TAG = "PermissionSystemFactory"
        
        /**
         * Initialize the complete permission management system
         */
        suspend fun initializePermissionSystem(context: Context): PermissionSystemComponents = withContext(Dispatchers.IO) {
            try {
                Log.d(TAG, "Initializing permission management system...")
                
                // Initialize components in dependency order
                val permissionPreferences = PermissionPreferences.getInstance(context)
                val permissionManager = PermissionManager.getInstance(context)
                val permissionRecovery = PermissionRecovery.getInstance(context, permissionManager, permissionPreferences)
                val privacyManager = PrivacyManager.getInstance(context)
                
                // Initialize in proper order
                val prefsInitialized = permissionPreferences.initialize()
                val managerInitialized = permissionManager.initialize()
                val recoveryInitialized = permissionRecovery.initialize()
                val privacyInitialized = privacyManager.initialize()
                
                if (!prefsInitialized || !managerInitialized || !recoveryInitialized || !privacyInitialized) {
                    throw IllegalStateException("Failed to initialize permission system components")
                }
                
                Log.d(TAG, "Permission management system initialized successfully")
                
                PermissionSystemComponents(
                    permissionManager = permissionManager,
                    permissionPreferences = permissionPreferences,
                    permissionRecovery = permissionRecovery,
                    privacyManager = privacyManager
                )
            } catch (e: Exception) {
                Log.e(TAG, "Failed to initialize permission management system", e)
                throw e
            }
        }
        
        /**
         * Quick setup for basic permission handling
         */
        suspend fun createBasicPermissionSystem(context: Context): BasicPermissionSystem = withContext(Dispatchers.IO) {
            val components = initializePermissionSystem(context)
            BasicPermissionSystem(
                permissionManager = components.permissionManager,
                privacyManager = components.privacyManager
            )
        }
    }
    
    /**
     * Complete permission system components
     */
    data class PermissionSystemComponents(
        val permissionManager: PermissionManager,
        val permissionPreferences: PermissionPreferences,
        val permissionRecovery: PermissionRecovery,
        val privacyManager: PrivacyManager
    )
    
    /**
     * Basic permission system for simple use cases
     */
    data class BasicPermissionSystem(
        val permissionManager: PermissionManager,
        val privacyManager: PrivacyManager
    )
}

/**
 * Extension functions for easy permission system usage
 */
suspend fun Context.initializePermissionSystem(): PermissionSystemFactory.PermissionSystemComponents {
    return PermissionSystemFactory.initializePermissionSystem(this)
}

suspend fun Context.createBasicPermissionSystem(): PermissionSystemFactory.BasicPermissionSystem {
    return PermissionSystemFactory.createBasicPermissionSystem(this)
}

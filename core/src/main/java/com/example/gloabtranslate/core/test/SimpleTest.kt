package com.example.gloabtranslate.core.test

import android.content.Context
import com.example.gloabtranslate.core.permissions.PermissionManager
import com.example.gloabtranslate.core.security.DataEncryption

/**
 * Simple test to check if classes can be imported
 */
class SimpleTest {
    fun testImports(context: Context) {
        // Test if PermissionManager can be imported
        val manager = PermissionManager.getInstance(context)
        
        // Test if DataEncryption can be imported
        val encryption = DataEncryption.getInstance(context)
    }
}

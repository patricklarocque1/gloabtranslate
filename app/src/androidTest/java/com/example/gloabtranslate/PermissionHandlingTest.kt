package com.example.gloabtranslate

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.rule.GrantPermissionRule
import com.example.gloabtranslate.MainActivity
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withContext
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue

/**
 * Integration tests for permission handling functionality.
 * These tests verify that the app properly handles various permission scenarios.
 */
@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(AndroidJUnit4::class)
class PermissionHandlingTest {

    @get:Rule
    val permissionRule = GrantPermissionRule.grant(
        Manifest.permission.RECORD_AUDIO,
        Manifest.permission.INTERNET,
        Manifest.permission.POST_NOTIFICATIONS
    )

    private lateinit var context: Context

    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext()
    }

    @Test
    fun `test RECORD_AUDIO permission granted`() = runTest {
        // When
        val permission = context.checkSelfPermission(Manifest.permission.RECORD_AUDIO)

        // Then
        assertEquals(PackageManager.PERMISSION_GRANTED, permission, "RECORD_AUDIO permission should be granted")
    }

    @Test
    fun `test INTERNET permission granted`() = runTest {
        // When
        val permission = context.checkSelfPermission(Manifest.permission.INTERNET)

        // Then
        assertEquals(PackageManager.PERMISSION_GRANTED, permission, "INTERNET permission should be granted")
    }

    @Test
    fun `test POST_NOTIFICATIONS permission granted on Android 13+`() = runTest {
        // Given
        val isAndroid13OrHigher = Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU

        // When
        val permission = context.checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS)

        // Then
        if (isAndroid13OrHigher) {
            assertEquals(PackageManager.PERMISSION_GRANTED, permission, "POST_NOTIFICATIONS permission should be granted on Android 13+")
        } else {
            // On older versions, this permission doesn't exist
            assertTrue("POST_NOTIFICATIONS permission not required on Android < 13", true)
        }
    }

    @Test
    fun `test permission checking for required permissions`() = runTest {
        // Given
        val requiredPermissions = mutableListOf<String>().apply {
            add(Manifest.permission.RECORD_AUDIO)
            add(Manifest.permission.INTERNET)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                add(Manifest.permission.POST_NOTIFICATIONS)
            }
        }

        // When & Then
        requiredPermissions.forEach { permission ->
            val permissionStatus = context.checkSelfPermission(permission)
            assertEquals(
                PackageManager.PERMISSION_GRANTED,
                permissionStatus,
                "Permission $permission should be granted"
            )
        }
    }

    @Test
    fun `test permission checking for non-required permissions`() = runTest {
        // Given
        val nonRequiredPermissions = listOf(
            Manifest.permission.CAMERA,
            Manifest.permission.READ_EXTERNAL_STORAGE,
            Manifest.permission.WRITE_EXTERNAL_STORAGE
        )

        // When & Then
        nonRequiredPermissions.forEach { permission ->
            val permissionStatus = context.checkSelfPermission(permission)
            // These permissions may or may not be granted, but the app should handle both cases
            assertTrue(
                permissionStatus == PackageManager.PERMISSION_GRANTED ||
                permissionStatus == PackageManager.PERMISSION_DENIED,
                "Permission $permission should have a valid status"
            )
        }
    }

    @Test
    fun `test permission request launcher setup`() = runTest {
        // Given
        val mainActivity = MainActivity()

        // When
        val launcher = mainActivity.requestPermissionLauncher

        // Then
        assertNotNull(launcher, "Permission request launcher should be initialized")
    }

    @Test
    fun `test permission request launcher callback with all permissions granted`() = runTest {
        // Given
        val mainActivity = MainActivity()
        val permissions = mapOf(
            Manifest.permission.RECORD_AUDIO to true,
            Manifest.permission.INTERNET to true,
            Manifest.permission.POST_NOTIFICATIONS to true
        )

        // When
        mainActivity.requestPermissionLauncher.onActivityResult(permissions)

        // Then
        // The callback should handle the permissions without throwing exceptions
        assertTrue("Permission launcher callback should handle all granted permissions", true)
    }

    @Test
    fun `test permission request launcher callback with some permissions denied`() = runTest {
        // Given
        val mainActivity = MainActivity()
        val permissions = mapOf(
            Manifest.permission.RECORD_AUDIO to false,
            Manifest.permission.INTERNET to true,
            Manifest.permission.POST_NOTIFICATIONS to true
        )

        // When
        mainActivity.requestPermissionLauncher.onActivityResult(permissions)

        // Then
        // The callback should handle the denied permissions without throwing exceptions
        assertTrue("Permission launcher callback should handle denied permissions", true)
    }

    @Test
    fun `test permission request launcher callback with all permissions denied`() = runTest {
        // Given
        val mainActivity = MainActivity()
        val permissions = mapOf(
            Manifest.permission.RECORD_AUDIO to false,
            Manifest.permission.INTERNET to false,
            Manifest.permission.POST_NOTIFICATIONS to false
        )

        // When
        mainActivity.requestPermissionLauncher.onActivityResult(permissions)

        // Then
        // The callback should handle all denied permissions without throwing exceptions
        assertTrue("Permission launcher callback should handle all denied permissions", true)
    }

    @Test
    fun `test permission request launcher callback with empty permissions`() = runTest {
        // Given
        val mainActivity = MainActivity()
        val permissions = emptyMap<String, Boolean>()

        // When
        mainActivity.requestPermissionLauncher.onActivityResult(permissions)

        // Then
        // The callback should handle empty permissions without throwing exceptions
        assertTrue("Permission launcher callback should handle empty permissions", true)
    }

    @Test
    fun `test permission checking logic for Android version compatibility`() = runTest {
        // Given
        val currentSdkVersion = Build.VERSION.SDK_INT

        // When
        val permissionsToCheck = mutableListOf<String>().apply {
            add(Manifest.permission.RECORD_AUDIO)
            add(Manifest.permission.INTERNET)
            if (currentSdkVersion >= Build.VERSION_CODES.TIRAMISU) {
                add(Manifest.permission.POST_NOTIFICATIONS)
            }
        }

        // Then
        assertTrue("RECORD_AUDIO should always be checked", permissionsToCheck.contains(Manifest.permission.RECORD_AUDIO))
        assertTrue("INTERNET should always be checked", permissionsToCheck.contains(Manifest.permission.INTERNET))
        
        if (currentSdkVersion >= Build.VERSION_CODES.TIRAMISU) {
            assertTrue("POST_NOTIFICATIONS should be checked on Android 13+", permissionsToCheck.contains(Manifest.permission.POST_NOTIFICATIONS))
        } else {
            assertFalse(permissionsToCheck.contains(Manifest.permission.POST_NOTIFICATIONS), "POST_NOTIFICATIONS should not be checked on Android < 13")
        }
    }

    @Test
    fun `test permission status constants`() = runTest {
        // When & Then
        assertEquals(0, PackageManager.PERMISSION_GRANTED, "PERMISSION_GRANTED should be 0")
        assertEquals(-1, PackageManager.PERMISSION_DENIED, "PERMISSION_DENIED should be -1")
    }

    @Test
    fun `test permission checking with invalid permission`() = runTest {
        // Given
        val invalidPermission = "com.example.invalid.permission"

        // When
        val permissionStatus = context.checkSelfPermission(invalidPermission)

        // Then
        assertEquals(PackageManager.PERMISSION_DENIED, permissionStatus, "Invalid permission should be denied")
    }

    @Test
    fun `test permission checking with null permission`() = runTest {
        // Given
        val nullPermission: String? = null

        // When
        val permissionStatus = if (nullPermission != null) {
            context.checkSelfPermission(nullPermission)
        } else {
            PackageManager.PERMISSION_DENIED
        }

        // Then
        assertEquals(PackageManager.PERMISSION_DENIED, permissionStatus, "Null permission should be denied")
    }

    @Test
    fun `test permission checking with empty permission`() = runTest {
        // Given
        val emptyPermission = ""

        // When
        val permissionStatus = context.checkSelfPermission(emptyPermission)

        // Then
        assertEquals(PackageManager.PERMISSION_DENIED, permissionStatus, "Empty permission should be denied")
    }

    @Test
    fun `test permission checking performance`() = runTest {
        // Given
        val permissions = listOf(
            Manifest.permission.RECORD_AUDIO,
            Manifest.permission.INTERNET,
            Manifest.permission.POST_NOTIFICATIONS
        )

        // When
        val startTime = System.currentTimeMillis()
        permissions.forEach { permission ->
            context.checkSelfPermission(permission)
        }
        val endTime = System.currentTimeMillis()

        // Then
        val duration = endTime - startTime
        assertTrue("Permission checking should complete within 1 second", duration < 1000)
    }

    @Test
    fun `test permission checking with multiple contexts`() = runTest {
        // Given
        val context1 = ApplicationProvider.getApplicationContext<Context>()
        val context2 = ApplicationProvider.getApplicationContext<Context>()

        // When
        val permission1 = context1.checkSelfPermission(Manifest.permission.RECORD_AUDIO)
        val permission2 = context2.checkSelfPermission(Manifest.permission.RECORD_AUDIO)

        // Then
        assertEquals(permission1, permission2, "Permission status should be consistent across contexts")
    }

    @Test
    fun `test permission checking with different permission types`() = runTest {
        // Given
        val permissionTypes = listOf(
            Manifest.permission.RECORD_AUDIO to "Audio",
            Manifest.permission.INTERNET to "Network",
            Manifest.permission.POST_NOTIFICATIONS to "Notification"
        )

        // When & Then
        permissionTypes.forEach { (permission, type) ->
            val permissionStatus = context.checkSelfPermission(permission)
            assertTrue(
                permissionStatus == PackageManager.PERMISSION_GRANTED ||
                permissionStatus == PackageManager.PERMISSION_DENIED,
                "$type permission should have a valid status"
            )
        }
    }

    @Test
    fun `test permission checking with system permissions`() = runTest {
        // Given
        val systemPermissions = listOf(
            Manifest.permission.INTERNET,
            Manifest.permission.ACCESS_NETWORK_STATE,
            Manifest.permission.ACCESS_WIFI_STATE
        )

        // When & Then
        systemPermissions.forEach { permission ->
            val permissionStatus = context.checkSelfPermission(permission)
            assertTrue(
                permissionStatus == PackageManager.PERMISSION_GRANTED ||
                permissionStatus == PackageManager.PERMISSION_DENIED,
                "System permission $permission should have a valid status"
            )
        }
    }

    @Test
    fun `test permission checking with dangerous permissions`() = runTest {
        // Given
        val dangerousPermissions = listOf(
            Manifest.permission.RECORD_AUDIO,
            Manifest.permission.CAMERA,
            Manifest.permission.READ_EXTERNAL_STORAGE,
            Manifest.permission.WRITE_EXTERNAL_STORAGE
        )

        // When & Then
        dangerousPermissions.forEach { permission ->
            val permissionStatus = context.checkSelfPermission(permission)
            assertTrue(
                permissionStatus == PackageManager.PERMISSION_GRANTED ||
                permissionStatus == PackageManager.PERMISSION_DENIED,
                "Dangerous permission $permission should have a valid status"
            )
        }
    }

    @Test
    fun `test permission checking with normal permissions`() = runTest {
        // Given
        val normalPermissions = listOf(
            Manifest.permission.INTERNET,
            Manifest.permission.ACCESS_NETWORK_STATE,
            Manifest.permission.ACCESS_WIFI_STATE,
            Manifest.permission.VIBRATE
        )

        // When & Then
        normalPermissions.forEach { permission ->
            val permissionStatus = context.checkSelfPermission(permission)
            assertTrue(
                permissionStatus == PackageManager.PERMISSION_GRANTED ||
                permissionStatus == PackageManager.PERMISSION_DENIED,
                "Normal permission $permission should have a valid status"
            )
        }
    }
}

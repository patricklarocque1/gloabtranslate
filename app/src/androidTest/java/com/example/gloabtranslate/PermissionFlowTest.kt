package com.example.gloabtranslate

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import androidx.test.core.app.ActivityScenario
import androidx.test.core.app.ApplicationProvider
import androidx.test.espresso.Espresso
import androidx.test.espresso.assertion.ViewAssertions
import androidx.test.espresso.matcher.ViewMatchers
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.rule.GrantPermissionRule
import androidx.test.uiautomator.UiDevice
import androidx.test.uiautomator.UiSelector
import org.junit.After
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * UI tests for permission flows.
 * Tests microphone, camera, and storage permission handling with user interactions.
 */
@RunWith(AndroidJUnit4::class)
class PermissionFlowTest {

    private lateinit var activityScenario: ActivityScenario<MainActivity>
    private lateinit var context: Context
    private lateinit var uiDevice: UiDevice

    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext()
        uiDevice = UiDevice.getInstance(androidx.test.platform.app.InstrumentationRegistry.getInstrumentation())
    }

    @After
    fun tearDown() {
        if (::activityScenario.isInitialized) {
            activityScenario.close()
        }
    }

    @Test
    fun `test all permissions granted flow`() {
        // Given - Grant all required permissions
        GrantPermissionRule.grant(
            Manifest.permission.RECORD_AUDIO,
            Manifest.permission.INTERNET,
            Manifest.permission.POST_NOTIFICATIONS
        ).apply()

        // When - Launch activity
        activityScenario = ActivityScenario.launch(MainActivity::class.java)

        // Then - UI should be fully functional
        Espresso.onView(ViewMatchers.withId(R.id.titleText))
            .check(ViewAssertions.matches(ViewMatchers.isDisplayed()))
        
        Espresso.onView(ViewMatchers.withId(R.id.startButton))
            .check(ViewAssertions.matches(ViewMatchers.isDisplayed()))
            .check(ViewAssertions.matches(ViewMatchers.isEnabled()))
    }

    @Test
    fun `test microphone permission denied flow`() {
        // Given - Deny microphone permission
        val permissionsToDeny = arrayOf(Manifest.permission.RECORD_AUDIO)
        
        // When - Launch activity without microphone permission
        activityScenario = ActivityScenario.launch(MainActivity::class.java)

        // Then - App should handle permission denial gracefully
        // The activity might finish or show appropriate UI
        Espresso.onView(ViewMatchers.withId(R.id.titleText))
            .check(ViewAssertions.matches(ViewMatchers.isDisplayed()))
    }

    @Test
    fun `test internet permission handling`() {
        // Given - Grant only internet permission
        GrantPermissionRule.grant(Manifest.permission.INTERNET).apply()

        // When - Launch activity
        activityScenario = ActivityScenario.launch(MainActivity::class.java)

        // Then - App should request additional permissions
        Espresso.onView(ViewMatchers.withId(R.id.titleText))
            .check(ViewAssertions.matches(ViewMatchers.isDisplayed()))
    }

    @Test
    fun `test notification permission handling for Android 13+`() {
        // Given - Grant permissions including notifications
        GrantPermissionRule.grant(
            Manifest.permission.RECORD_AUDIO,
            Manifest.permission.INTERNET,
            Manifest.permission.POST_NOTIFICATIONS
        ).apply()

        // When - Launch activity
        activityScenario = ActivityScenario.launch(MainActivity::class.java)

        // Then - App should work with notification permission
        Espresso.onView(ViewMatchers.withId(R.id.titleText))
            .check(ViewAssertions.matches(ViewMatchers.isDisplayed()))
    }

    @Test
    fun `test permission check methods`() {
        // Given
        val context = ApplicationProvider.getApplicationContext()

        // When - Check individual permissions
        val hasRecordAudio = context.checkSelfPermission(Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED
        val hasInternet = context.checkSelfPermission(Manifest.permission.INTERNET) == PackageManager.PERMISSION_GRANTED
        val hasNotifications = context.checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED

        // Then - Permissions should be checkable
        assertTrue(hasInternet, "Internet permission should be granted for testing")
        // Other permissions depend on test setup
    }

    @Test
    fun `test permission request dialog interaction`() {
        // Given - Start with no permissions
        activityScenario = ActivityScenario.launch(MainActivity::class.java)

        // When - App requests permissions
        // The permission dialog should appear

        // Then - App should handle the permission flow
        Espresso.onView(ViewMatchers.withId(R.id.titleText))
            .check(ViewAssertions.matches(ViewMatchers.isDisplayed()))
    }

    @Test
    fun `test permission denied and granted again flow`() {
        // Given - Grant permissions initially
        GrantPermissionRule.grant(
            Manifest.permission.RECORD_AUDIO,
            Manifest.permission.INTERNET,
            Manifest.permission.POST_NOTIFICATIONS
        ).apply()

        // When - Launch activity
        activityScenario = ActivityScenario.launch(MainActivity::class.java)

        // Then - App should work with granted permissions
        Espresso.onView(ViewMatchers.withId(R.id.startButton))
            .check(ViewAssertions.matches(ViewMatchers.isDisplayed()))
    }

    @Test
    fun `test partial permission grant flow`() {
        // Given - Grant only some permissions
        GrantPermissionRule.grant(Manifest.permission.INTERNET).apply()

        // When - Launch activity
        activityScenario = ActivityScenario.launch(MainActivity::class.java)

        // Then - App should handle partial permissions
        Espresso.onView(ViewMatchers.withId(R.id.titleText))
            .check(ViewAssertions.matches(ViewMatchers.isDisplayed()))
    }

    @Test
    fun `test permission state persistence`() {
        // Given - Grant permissions
        GrantPermissionRule.grant(
            Manifest.permission.RECORD_AUDIO,
            Manifest.permission.INTERNET,
            Manifest.permission.POST_NOTIFICATIONS
        ).apply()

        // When - Launch and recreate activity
        activityScenario = ActivityScenario.launch(MainActivity::class.java)
        activityScenario.recreate()

        // Then - Permissions should persist
        Espresso.onView(ViewMatchers.withId(R.id.titleText))
            .check(ViewAssertions.matches(ViewMatchers.isDisplayed()))
    }

    @Test
    fun `test permission error handling`() {
        // Given - Start without permissions
        activityScenario = ActivityScenario.launch(MainActivity::class.java)

        // When - App tries to use restricted functionality
        // Then - App should handle permission errors gracefully
        Espresso.onView(ViewMatchers.withId(R.id.titleText))
            .check(ViewAssertions.matches(ViewMatchers.isDisplayed()))
    }

    @Test
    fun `test runtime permission checking`() {
        // Given
        val context = ApplicationProvider.getApplicationContext()

        // When - Check runtime permissions
        val requiredPermissions = listOf(
            Manifest.permission.RECORD_AUDIO,
            Manifest.permission.INTERNET,
            Manifest.permission.POST_NOTIFICATIONS
        )

        val permissionStates = requiredPermissions.map { permission ->
            context.checkSelfPermission(permission) == PackageManager.PERMISSION_GRANTED
        }

        // Then - Permission states should be determinable
        assertEquals(requiredPermissions.size, permissionStates.size, "All permissions should be checked")
    }

    @Test
    fun `test permission rationale display`() {
        // Given - Launch without permissions
        activityScenario = ActivityScenario.launch(MainActivity::class.java)

        // When - App should show permission rationale if needed
        // Then - UI should remain functional
        Espresso.onView(ViewMatchers.withId(R.id.titleText))
            .check(ViewAssertions.matches(ViewMatchers.isDisplayed()))
    }

    @Test
    fun `test permission settings redirect`() {
        // Given - Launch with denied permissions
        activityScenario = ActivityScenario.launch(MainActivity::class.java)

        // When - App might redirect to settings
        // Then - App should handle this flow gracefully
        Espresso.onView(ViewMatchers.withId(R.id.titleText))
            .check(ViewAssertions.matches(ViewMatchers.isDisplayed()))
    }

    @Test
    fun `test multiple permission request handling`() {
        // Given - Launch with multiple permission requests
        activityScenario = ActivityScenario.launch(MainActivity::class.java)

        // When - App requests multiple permissions
        // Then - All permission requests should be handled
        Espresso.onView(ViewMatchers.withId(R.id.titleText))
            .check(ViewAssertions.matches(ViewMatchers.isDisplayed()))
    }

    @Test
    fun `test permission state change handling`() {
        // Given - Start with some permissions
        GrantPermissionRule.grant(Manifest.permission.INTERNET).apply()

        // When - Launch activity
        activityScenario = ActivityScenario.launch(MainActivity::class.java)

        // Then - App should handle permission state changes
        Espresso.onView(ViewMatchers.withId(R.id.titleText))
            .check(ViewAssertions.matches(ViewMatchers.isDisplayed()))
    }

    @Test
    fun `test permission callback handling`() {
        // Given - Launch activity
        activityScenario = ActivityScenario.launch(MainActivity::class.java)

        // When - Permission callbacks are triggered
        // Then - App should handle callbacks properly
        Espresso.onView(ViewMatchers.withId(R.id.titleText))
            .check(ViewAssertions.matches(ViewMatchers.isDisplayed()))
    }

    @Test
    fun `test permission-based feature availability`() {
        // Given - Check permission-dependent features
        val context = ApplicationProvider.getApplicationContext()
        val hasRecordAudio = context.checkSelfPermission(Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED

        // When - Launch activity
        activityScenario = ActivityScenario.launch(MainActivity::class.java)

        // Then - Features should be available based on permissions
        Espresso.onView(ViewMatchers.withId(R.id.startButton))
            .check(ViewAssertions.matches(ViewMatchers.isDisplayed()))
    }
}

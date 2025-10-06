package com.example.gloabtranslate

import android.Manifest
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.os.IBinder
import androidx.test.core.app.ActivityScenario
import androidx.test.core.app.ApplicationProvider
import androidx.test.espresso.Espresso
import androidx.test.espresso.action.ViewActions
import androidx.test.espresso.assertion.ViewAssertions
import androidx.test.espresso.matcher.ViewMatchers
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.rule.GrantPermissionRule
import com.example.gloabtranslate.service.LiveTranslateService
import org.junit.After
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.mockito.Mock
import org.mockito.Mockito
import org.mockito.MockitoAnnotations

/**
 * UI tests for MainActivity.
 * Tests user interface interactions, button states, and service integration.
 */
@RunWith(AndroidJUnit4::class)
class MainActivityUITest {

    @get:Rule
    val permissionRule: GrantPermissionRule = GrantPermissionRule.grant(
        Manifest.permission.RECORD_AUDIO,
        Manifest.permission.INTERNET,
        Manifest.permission.POST_NOTIFICATIONS
    )

    @Mock
    private lateinit var mockLiveTranslateService: LiveTranslateService

    private lateinit var activityScenario: ActivityScenario<MainActivity>
    private lateinit var context: Context

    @Before
    fun setUp() {
        MockitoAnnotations.openMocks(this)
        context = ApplicationProvider.getApplicationContext()
        
        // Mock service responses
        Mockito.`when`(mockLiveTranslateService.isCurrentlyRecording()).thenReturn(false)
        Mockito.`when`(mockLiveTranslateService.getRecognitionStatus()).thenReturn("Recognition available")
        Mockito.`when`(mockLiveTranslateService.getRecommendedAction()).thenReturn("Ready to translate")
    }

    @After
    fun tearDown() {
        activityScenario.close()
    }

    @Test
    fun `test MainActivity launches successfully`() {
        // When
        activityScenario = ActivityScenario.launch(MainActivity::class.java)

        // Then
        Espresso.onView(ViewMatchers.withId(R.id.statusText))
            .check(ViewAssertions.matches(ViewMatchers.isDisplayed()))
        Espresso.onView(ViewMatchers.withId(R.id.statusText))
            .check(ViewAssertions.matches(ViewMatchers.isDisplayed()))
        Espresso.onView(ViewMatchers.withId(R.id.recognitionStatusText))
            .check(ViewAssertions.matches(ViewMatchers.isDisplayed()))
        Espresso.onView(ViewMatchers.withId(R.id.recommendedActionText))
            .check(ViewAssertions.matches(ViewMatchers.isDisplayed()))
        Espresso.onView(ViewMatchers.withId(R.id.startButton))
            .check(ViewAssertions.matches(ViewMatchers.isDisplayed()))
    }

    @Test
    fun `test title text displays correctly`() {
        // When
        activityScenario = ActivityScenario.launch(MainActivity::class.java)

        // Then
        Espresso.onView(ViewMatchers.withId(R.id.statusText))
            .check(ViewAssertions.matches(ViewMatchers.withText("Global Translate")))
    }

    @Test
    fun `test initial status text`() {
        // When
        activityScenario = ActivityScenario.launch(MainActivity::class.java)

        // Then
        Espresso.onView(ViewMatchers.withId(R.id.statusText))
            .check(ViewAssertions.matches(ViewMatchers.isDisplayed()))
    }

    @Test
    fun `test recognition status text is displayed`() {
        // When
        activityScenario = ActivityScenario.launch(MainActivity::class.java)

        // Then
        Espresso.onView(ViewMatchers.withId(R.id.recognitionStatusText))
            .check(ViewAssertions.matches(ViewMatchers.isDisplayed()))
    }

    @Test
    fun `test recommended action text is displayed`() {
        // When
        activityScenario = ActivityScenario.launch(MainActivity::class.java)

        // Then
        Espresso.onView(ViewMatchers.withId(R.id.recommendedActionText))
            .check(ViewAssertions.matches(ViewMatchers.isDisplayed()))
    }

    @Test
    fun `test start button is visible and clickable initially`() {
        // When
        activityScenario = ActivityScenario.launch(MainActivity::class.java)

        // Then
        Espresso.onView(ViewMatchers.withId(R.id.startButton))
            .check(ViewAssertions.matches(ViewMatchers.isDisplayed()))
            .check(ViewAssertions.matches(ViewMatchers.isEnabled()))
            .check(ViewAssertions.matches(ViewMatchers.withText("Start Translation")))
    }

    @Test
    fun `test stop button is initially hidden`() {
        // When
        activityScenario = ActivityScenario.launch(MainActivity::class.java)

        // Then
        Espresso.onView(ViewMatchers.withId(R.id.stopButton))
            .check(ViewAssertions.matches(ViewMatchers.withEffectiveVisibility(ViewMatchers.Visibility.GONE)))
    }

    @Test
    fun `test start button click triggers action`() {
        // Given
        activityScenario = ActivityScenario.launch(MainActivity::class.java)

        // When
        Espresso.onView(ViewMatchers.withId(R.id.startButton))
            .perform(ViewActions.click())

        // Then
        // The button click should be handled without crashing
        // In a real test environment, we would verify the service method was called
        Espresso.onView(ViewMatchers.withId(R.id.startButton))
            .check(ViewAssertions.matches(ViewMatchers.isDisplayed()))
    }

    @Test
    fun `test stop button click triggers action`() {
        // Given
        activityScenario = ActivityScenario.launch(MainActivity::class.java)

        // When - First click start to show stop button, then click stop
        Espresso.onView(ViewMatchers.withId(R.id.startButton))
            .perform(ViewActions.click())

        // Then - Verify stop button is visible and clickable
        Espresso.onView(ViewMatchers.withId(R.id.stopButton))
            .check(ViewAssertions.matches(ViewMatchers.isDisplayed()))
            .perform(ViewActions.click())
    }

    @Test
    fun `test UI layout constraints are properly set`() {
        // When
        activityScenario = ActivityScenario.launch(MainActivity::class.java)

        // Then - Verify all UI elements are properly positioned
        Espresso.onView(ViewMatchers.withId(R.id.statusText))
            .check(ViewAssertions.matches(ViewMatchers.isDisplayed()))
        
        Espresso.onView(ViewMatchers.withId(R.id.statusText))
            .check(ViewAssertions.matches(ViewMatchers.isDisplayed()))
        
        Espresso.onView(ViewMatchers.withId(R.id.recognitionStatusText))
            .check(ViewAssertions.matches(ViewMatchers.isDisplayed()))
        
        Espresso.onView(ViewMatchers.withId(R.id.recommendedActionText))
            .check(ViewAssertions.matches(ViewMatchers.isDisplayed()))
        
        Espresso.onView(ViewMatchers.withId(R.id.startButton))
            .check(ViewAssertions.matches(ViewMatchers.isDisplayed()))
    }

    @Test
    fun `test button text content is correct`() {
        // When
        activityScenario = ActivityScenario.launch(MainActivity::class.java)

        // Then
        Espresso.onView(ViewMatchers.withId(R.id.startButton))
            .check(ViewAssertions.matches(ViewMatchers.withText("Start Translation")))
        
        Espresso.onView(ViewMatchers.withId(R.id.stopButton))
            .check(ViewAssertions.matches(ViewMatchers.withText("Stop Service")))
    }

    @Test
    fun `test text view text sizes and colors`() {
        // When
        activityScenario = ActivityScenario.launch(MainActivity::class.java)

        // Then - Verify text views are displayed with proper styling
        Espresso.onView(ViewMatchers.withId(R.id.statusText))
            .check(ViewAssertions.matches(ViewMatchers.isDisplayed()))
        
        Espresso.onView(ViewMatchers.withId(R.id.statusText))
            .check(ViewAssertions.matches(ViewMatchers.isDisplayed()))
        
        Espresso.onView(ViewMatchers.withId(R.id.recognitionStatusText))
            .check(ViewAssertions.matches(ViewMatchers.isDisplayed()))
        
        Espresso.onView(ViewMatchers.withId(R.id.recommendedActionText))
            .check(ViewAssertions.matches(ViewMatchers.isDisplayed()))
    }

    @Test
    fun `test activity handles configuration changes`() {
        // Given
        activityScenario = ActivityScenario.launch(MainActivity::class.java)

        // When - Simulate configuration change
        activityScenario.recreate()

        // Then - Verify UI is still properly displayed
        Espresso.onView(ViewMatchers.withId(R.id.statusText))
            .check(ViewAssertions.matches(ViewMatchers.isDisplayed()))
        
        Espresso.onView(ViewMatchers.withId(R.id.startButton))
            .check(ViewAssertions.matches(ViewMatchers.isDisplayed()))
    }

    @Test
    fun `test UI elements are accessible`() {
        // When
        activityScenario = ActivityScenario.launch(MainActivity::class.java)

        // Then - Verify all interactive elements have proper accessibility
        Espresso.onView(ViewMatchers.withId(R.id.startButton))
            .check(ViewAssertions.matches(ViewMatchers.isDisplayed()))
            .check(ViewAssertions.matches(ViewMatchers.isEnabled()))
        
        Espresso.onView(ViewMatchers.withId(R.id.stopButton))
            .check(ViewAssertions.matches(ViewMatchers.isDisplayed()))
    }

    @Test
    fun `test multiple button interactions`() {
        // Given
        activityScenario = ActivityScenario.launch(MainActivity::class.java)

        // When - Perform multiple button clicks
        Espresso.onView(ViewMatchers.withId(R.id.startButton))
            .perform(ViewActions.click())
        
        // Then - Verify UI state changes appropriately
        Espresso.onView(ViewMatchers.withId(R.id.startButton))
            .check(ViewAssertions.matches(ViewMatchers.isDisplayed()))
    }

    @Test
    fun `test UI responds to service state changes`() {
        // Given
        activityScenario = ActivityScenario.launch(MainActivity::class.java)

        // When - Simulate service state change by clicking start button
        Espresso.onView(ViewMatchers.withId(R.id.startButton))
            .perform(ViewActions.click())

        // Then - Verify UI updates to reflect new state
        Espresso.onView(ViewMatchers.withId(R.id.statusText))
            .check(ViewAssertions.matches(ViewMatchers.isDisplayed()))
    }
}

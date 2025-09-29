package com.example.gloabtranslate

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.rule.GrantPermissionRule
import com.example.gloabtranslate.service.LiveTranslateService
import com.example.gloabtranslate.speech.AudioRecordingService
import org.junit.Assert.*
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows
import org.robolectric.annotation.Config
import org.robolectric.shadows.ShadowNotificationManager

/**
 * Comprehensive tests for notification functionality across the app.
 * Tests notification channels, notification content, actions, and permissions.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [Build.VERSION_CODES.P])
class NotificationTest {

    private lateinit var context: Context
    private lateinit var notificationManager: NotificationManager
    private lateinit var shadowNotificationManager: ShadowNotificationManager

    @get:Rule
    val permissionRule = GrantPermissionRule.grant(
        android.Manifest.permission.RECORD_AUDIO,
        android.Manifest.permission.POST_NOTIFICATIONS
    )

    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext()
        notificationManager = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        shadowNotificationManager = Shadows.shadowOf(notificationManager)
    }

    /**
     * Tests for LiveTranslateService Notifications
     */
    @Test
    fun `LiveTranslateService notification channel is created with correct properties`() {
        // Given
        val service = LiveTranslateService()

        // When
        service.onCreate()

        // Then
        val channel = notificationManager.getNotificationChannel("live_translate_channel")
        assertNotNull("Notification channel should be created", channel)
        assertEquals("Channel ID should match", "live_translate_channel", channel.id)
        assertEquals("Channel name should match", "Live Translation Service", channel.name)
        assertEquals("Channel importance should be LOW", NotificationManager.IMPORTANCE_LOW, channel.importance)
        assertEquals("Channel description should match", "Live translation service notification", channel.description)
        assertFalse("Channel should not show badge", channel.canShowBadge())
    }

    @Test
    fun `LiveTranslateService notification has correct basic properties`() {
        // Given
        val service = LiveTranslateService()
        service.onCreate()

        // When
        val notification = createLiveTranslateNotification(service, "Test content")

        // Then
        assertEquals("Notification title should match", "Live Translation", notification.extras.getCharSequence(Notification.EXTRA_TITLE))
        assertEquals("Notification content should match", "Test content", notification.extras.getCharSequence(Notification.EXTRA_TEXT))
        assertTrue("Notification should be ongoing", notification.flags and Notification.FLAG_ONGOING_EVENT != 0)
        assertTrue("Notification should be silent", notification.extras.getBoolean(Notification.EXTRA_SILENT))
        assertEquals("Notification priority should be LOW", NotificationCompat.PRIORITY_LOW, notification.priority)
        assertEquals("Notification category should be SERVICE", NotificationCompat.CATEGORY_SERVICE, notification.category)
    }

    @Test
    fun `LiveTranslateService notification has correct actions when not recording`() {
        // Given
        val service = LiveTranslateService()
        service.onCreate()

        // When
        val notification = createLiveTranslateNotification(service, "Listening for speech to translate...")

        // Then
        assertEquals("Should have 2 actions", 2, notification.actions.size)
        
        val toggleAction = notification.actions[0]
        assertEquals("Toggle action title should be 'Start Recording'", "Start Recording", toggleAction.title)
        
        val stopAction = notification.actions[1]
        assertEquals("Stop action title should be 'Stop Service'", "Stop Service", stopAction.title)
    }

    @Test
    fun `LiveTranslateService notification actions have correct pending intents`() {
        // Given
        val service = LiveTranslateService()
        service.onCreate()

        // When
        val notification = createLiveTranslateNotification(service, "Test content")

        // Then
        val toggleAction = notification.actions[0]
        val togglePendingIntent = toggleAction.actionIntent
        assertNotNull("Toggle action should have pending intent", togglePendingIntent)
        
        val stopAction = notification.actions[1]
        val stopPendingIntent = stopAction.actionIntent
        assertNotNull("Stop action should have pending intent", stopPendingIntent)
    }

    @Test
    fun `LiveTranslateService notification content intent opens MainActivity`() {
        // Given
        val service = LiveTranslateService()
        service.onCreate()

        // When
        val notification = createLiveTranslateNotification(service, "Test content")

        // Then
        val contentIntent = notification.contentIntent
        assertNotNull("Notification should have content intent", contentIntent)
        
        val shadowPendingIntent = Shadows.shadowOf(contentIntent)
        val intent = shadowPendingIntent.savedIntent
        assertEquals("Intent should target MainActivity", "com.example.gloabtranslate.MainActivity", intent.component?.className)
        assertTrue("Intent should have FLAG_ACTIVITY_NEW_TASK", intent.flags and Intent.FLAG_ACTIVITY_NEW_TASK != 0)
        assertTrue("Intent should have FLAG_ACTIVITY_CLEAR_TASK", intent.flags and Intent.FLAG_ACTIVITY_CLEAR_TASK != 0)
    }

    /**
     * Tests for AudioRecordingService Notifications
     */
    @Test
    fun `AudioRecordingService notification has correct basic properties`() {
        // Given
        val service = AudioRecordingService()

        // When
        val notification = createAudioRecordingNotification(service, "Recording...")

        // Then
        assertEquals("Notification title should match", "Audio Recording", notification.extras.getCharSequence(Notification.EXTRA_TITLE))
        assertEquals("Notification content should match", "Recording...", notification.extras.getCharSequence(Notification.EXTRA_TEXT))
        assertTrue("Notification should be ongoing", notification.flags and Notification.FLAG_ONGOING_EVENT != 0)
        assertEquals("Notification priority should be LOW", NotificationCompat.PRIORITY_LOW, notification.priority)
        assertEquals("Notification category should be SERVICE", NotificationCompat.CATEGORY_SERVICE, notification.category)
    }

    @Test
    fun `AudioRecordingService notification has correct actions`() {
        // Given
        val service = AudioRecordingService()

        // When
        val notification = createAudioRecordingNotification(service, "Recording...")

        // Then
        assertEquals("Should have 2 actions", 2, notification.actions.size)
        
        val pauseAction = notification.actions[0]
        assertEquals("Pause action title should be 'Pause'", "Pause", pauseAction.title)
        
        val stopAction = notification.actions[1]
        assertEquals("Stop action title should be 'Stop'", "Stop", stopAction.title)
    }

    @Test
    fun `AudioRecordingService notification uses correct channel ID`() {
        // Given
        val service = AudioRecordingService()

        // When
        val notification = createAudioRecordingNotification(service, "Recording...")

        // Then
        assertEquals("Notification should use correct channel ID", "audio_recording_channel", notification.extras.getString(Notification.EXTRA_CHANNEL_ID))
    }

    /**
     * Tests for Notification Manager Integration
     */
    @Test
    fun `notification manager can create and retrieve notification channels`() {
        // Given
        val channelId = "test_channel"
        val channelName = "Test Channel"
        val channel = NotificationChannel(
            channelId,
            channelName,
            NotificationManager.IMPORTANCE_HIGH
        ).apply {
            description = "Test notification channel"
            setShowBadge(true)
        }

        // When
        notificationManager.createNotificationChannel(channel)

        // Then
        val createdChannel = notificationManager.getNotificationChannel(channelId)
        assertNotNull("Channel should be created", createdChannel)
        assertEquals("Channel ID should match", channelId, createdChannel.id)
        assertEquals("Channel name should match", channelName, createdChannel.name)
        assertEquals("Channel importance should match", NotificationManager.IMPORTANCE_HIGH, createdChannel.importance)
        assertEquals("Channel description should match", "Test notification channel", createdChannel.description)
        assertTrue("Channel should show badge", createdChannel.canShowBadge())
    }

    @Test
    fun `notification manager can notify with correct notification ID`() {
        // Given
        val notificationId = 1001
        val notification = NotificationCompat.Builder(context, "test_channel")
            .setContentTitle("Test Title")
            .setContentText("Test Content")
            .setSmallIcon(android.R.drawable.ic_dialog_info)
            .build()

        // When
        notificationManager.notify(notificationId, notification)

        // Then
        val shadowManager = Shadows.shadowOf(notificationManager)
        assertTrue("Notification should be posted", shadowManager.hasNotification(notificationId))
        
        val postedNotification = shadowManager.getNotification(notificationId)
        assertNotNull("Posted notification should not be null", postedNotification)
        assertEquals("Notification title should match", "Test Title", postedNotification.extras.getCharSequence(Notification.EXTRA_TITLE))
        assertEquals("Notification content should match", "Test Content", postedNotification.extras.getCharSequence(Notification.EXTRA_TEXT))
    }

    @Test
    fun `notification manager can cancel notifications`() {
        // Given
        val notificationId = 1001
        val notification = NotificationCompat.Builder(context, "test_channel")
            .setContentTitle("Test Title")
            .setContentText("Test Content")
            .setSmallIcon(android.R.drawable.ic_dialog_info)
            .build()
        
        notificationManager.notify(notificationId, notification)

        // When
        notificationManager.cancel(notificationId)

        // Then
        val shadowManager = Shadows.shadowOf(notificationManager)
        assertFalse("Notification should be cancelled", shadowManager.hasNotification(notificationId))
    }

    /**
     * Tests for Notification Permissions and Runtime Behavior
     */
    @Test
    fun `notification manager respects notification importance levels`() {
        // Given
        val lowChannel = NotificationChannel("low_channel", "Low Channel", NotificationManager.IMPORTANCE_LOW)
        val highChannel = NotificationChannel("high_channel", "High Channel", NotificationManager.IMPORTANCE_HIGH)

        // When
        notificationManager.createNotificationChannel(lowChannel)
        notificationManager.createNotificationChannel(highChannel)

        // Then
        val retrievedLowChannel = notificationManager.getNotificationChannel("low_channel")
        val retrievedHighChannel = notificationManager.getNotificationChannel("high_channel")
        
        assertNotNull("Low channel should exist", retrievedLowChannel)
        assertNotNull("High channel should exist", retrievedHighChannel)
        assertEquals("Low channel importance should be LOW", NotificationManager.IMPORTANCE_LOW, retrievedLowChannel.importance)
        assertEquals("High channel importance should be HIGH", NotificationManager.IMPORTANCE_HIGH, retrievedHighChannel.importance)
    }

    @Test
    fun `notification channels are persistent across manager instances`() {
        // Given
        val channelId = "persistent_channel"
        val channel = NotificationChannel(
            channelId,
            "Persistent Channel",
            NotificationManager.IMPORTANCE_DEFAULT
        )

        // When
        notificationManager.createNotificationChannel(channel)
        val newNotificationManager = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager

        // Then
        val retrievedChannel = newNotificationManager.getNotificationChannel(channelId)
        assertNotNull("Channel should persist across manager instances", retrievedChannel)
        assertEquals("Channel ID should match", channelId, retrievedChannel.id)
    }

    /**
     * Tests for Notification Content Validation
     */
    @Test
    fun `notification with empty content text is handled gracefully`() {
        // Given
        val service = LiveTranslateService()
        service.onCreate()

        // When
        val notification = createLiveTranslateNotification(service, "")

        // Then
        assertNotNull("Notification should be created even with empty content", notification)
        assertEquals("Empty content should be preserved", "", notification.extras.getCharSequence(Notification.EXTRA_TEXT))
    }

    @Test
    fun `notification with long content text is handled correctly`() {
        // Given
        val service = LiveTranslateService()
        service.onCreate()
        val longText = "This is a very long notification text that might exceed normal notification display limits but should still be handled gracefully by the notification system without causing any crashes or issues"

        // When
        val notification = createLiveTranslateNotification(service, longText)

        // Then
        assertNotNull("Notification should be created with long content", notification)
        assertEquals("Long content should be preserved", longText, notification.extras.getCharSequence(Notification.EXTRA_TEXT))
    }

    /**
     * Helper methods for creating notifications
     */
    private fun createLiveTranslateNotification(service: LiveTranslateService, contentText: String): Notification {
        // Use reflection to access private method
        val method = LiveTranslateService::class.java.getDeclaredMethod("createNotification", String::class.java)
        method.isAccessible = true
        return method.invoke(service, contentText) as Notification
    }

    private fun createAudioRecordingNotification(service: AudioRecordingService, contentText: String): Notification {
        // Use reflection to access private method
        val method = AudioRecordingService::class.java.getDeclaredMethod("createNotification", String::class.java)
        method.isAccessible = true
        return method.invoke(service, contentText) as Notification
    }
}

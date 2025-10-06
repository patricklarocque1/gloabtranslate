package com.example.gloabtranslate.service

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Binder
import android.os.Build
import android.os.IBinder
import androidx.core.app.NotificationCompat
import com.example.gloabtranslate.MainActivity
import com.example.gloabtranslate.R
import com.example.gloabtranslate.nlp.RecognitionService
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.cancel
import dagger.android.DaggerService
import javax.inject.Inject

/**
 * Foreground service for live translation functionality.
 * Handles continuous audio recording and translation processing.
 */
class LiveTranslateService : DaggerService() {

    private val binder = LocalBinder()
    private var isRecording = false
    
    @Inject
    lateinit var recognitionService: RecognitionService
    
    @Inject  
    lateinit var serviceCoordinator: ServiceCoordinator
    
    private val serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.Main)

    companion object {
        private const val NOTIFICATION_ID = 1001
        private const val CHANNEL_ID = "live_translate_channel"
        private const val CHANNEL_NAME = "Live Translation Service"
        
        // Action constants for notification actions
        private const val ACTION_STOP_SERVICE = "com.example.gloabtranslate.STOP_SERVICE"
        private const val ACTION_TOGGLE_RECORDING = "com.example.gloabtranslate.TOGGLE_RECORDING"
    }

    inner class LocalBinder : Binder() {
        fun getService(): LiveTranslateService = this@LiveTranslateService
    }

    override fun onCreate() {
        super.onCreate()
        createNotificationChannel()
        initializeRecognitionService()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_STOP_SERVICE -> {
                stopSelf()
                return START_NOT_STICKY
            }
            ACTION_TOGGLE_RECORDING -> {
                if (isRecording) {
                    stopRecording()
                } else {
                    startRecording()
                }
            }
            else -> {
                startForegroundService()
            }
        }
        return START_STICKY
    }

    override fun onBind(intent: Intent?): IBinder = binder

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID,
                CHANNEL_NAME,
                NotificationManager.IMPORTANCE_LOW
            ).apply {
                description = "Live translation service notification"
                setShowBadge(false)
            }

            val notificationManager = getSystemService(NotificationManager::class.java)
            notificationManager.createNotificationChannel(channel)
        }
    }

    private fun startForegroundService() {
        val notification = createNotification("Listening for speech to translate...")

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            startForeground(
                NOTIFICATION_ID,
                notification,
                ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE
            )
        } else {
            startForeground(NOTIFICATION_ID, notification)
        }
    }

    private fun createNotification(contentText: String): Notification {
        val notificationIntent = Intent(this, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK
        }

        val pendingIntent = PendingIntent.getActivity(
            this, 0, notificationIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        // Create stop service intent
        val stopIntent = Intent(this, LiveTranslateService::class.java).apply {
            action = ACTION_STOP_SERVICE
        }
        val stopPendingIntent = PendingIntent.getService(
            this, 1, stopIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        // Create toggle recording intent
        val toggleIntent = Intent(this, LiveTranslateService::class.java).apply {
            action = ACTION_TOGGLE_RECORDING
        }
        val togglePendingIntent = PendingIntent.getService(
            this, 2, toggleIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val builder = NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("Live Translation")
            .setContentText(contentText)
            .setSmallIcon(R.drawable.ic_launcher_foreground)
            .setContentIntent(pendingIntent)
            .setOngoing(true)
            .setSilent(true)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .setCategory(NotificationCompat.CATEGORY_SERVICE)
            .addAction(
                R.drawable.ic_launcher_foreground,
                if (isRecording) "Stop Recording" else "Start Recording",
                togglePendingIntent
            )
            .addAction(
                R.drawable.ic_launcher_foreground,
                "Stop Service",
                stopPendingIntent
            )

        // Add recognition capability status if available
        if (::recognitionService.isInitialized) {
            val capabilityStatus = recognitionService.getCapabilityStatus()
            builder.setSubText(capabilityStatus)
        }

        return builder.build()
    }

    fun startRecording() {
        isRecording = true
        updateNotification("Recording and translating...")
    }

    fun stopRecording() {
        isRecording = false
        updateNotification("Listening for speech to translate...")
    }

    private fun updateNotification(text: String) {
        val notification = createNotification(text)
        val notificationManager = getSystemService(NotificationManager::class.java)
        notificationManager.notify(NOTIFICATION_ID, notification)
    }

    fun isCurrentlyRecording(): Boolean = isRecording

    /**
     * Initializes the recognition service and checks availability
     */
    private fun initializeRecognitionService() {
        serviceScope.launch {
            try {
                // Check if services are already ready via coordinator
                if (serviceCoordinator.areCriticalServicesReady()) {
                    updateNotification("Recognition service ready")
                } else {
                    updateNotification("Initializing services...")
                    
                    // Monitor service states
                    serviceCoordinator.serviceStates.collect { states ->
                        val recognitionState = states[ServiceCoordinator.ServiceType.RECOGNITION_SERVICE]
                        
                        when (recognitionState?.status) {
                            ServiceCoordinator.ServiceStatus.READY -> {
                                updateNotification("Recognition service ready")
                            }
                            ServiceCoordinator.ServiceStatus.ERROR -> {
                                updateNotification("Recognition service error - ${recognitionState.lastError}")
                            }
                            ServiceCoordinator.ServiceStatus.INITIALIZING -> {
                                updateNotification("Initializing recognition service...")
                            }
                            else -> {
                                updateNotification("Recognition service status unknown")
                            }
                        }
                    }
                }
            } catch (e: Exception) {
                updateNotification("Failed to initialize recognition service")
            }
        }
    }

    /**
     * Gets the current recognition capability status
     */
    fun getRecognitionStatus(): String {
        return if (::recognitionService.isInitialized) {
            recognitionService.getCapabilityStatus()
        } else {
            "Recognition service not initialized"
        }
    }

    /**
     * Gets the recommended action based on current capability
     */
    fun getRecommendedAction(): String {
        return if (::recognitionService.isInitialized) {
            recognitionService.getRecommendedAction()
        } else {
            "Please wait for recognition service to initialize"
        }
    }

    /**
     * Checks if on-device recognition is available
     */
    fun isOnDeviceRecognitionAvailable(): Boolean {
        return ::recognitionService.isInitialized && recognitionService.isOnDeviceAvailable()
    }

    /**
     * Checks if cloud-based recognition is available
     */
    fun isCloudRecognitionAvailable(): Boolean {
        return ::recognitionService.isInitialized && recognitionService.isCloudAvailable()
    }

    override fun onDestroy() {
        super.onDestroy()
        if (::recognitionService.isInitialized) {
            recognitionService.cleanup()
        }
        serviceScope.coroutineContext.cancel()
    }
}

package com.example.gloabtranslate

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.os.IBinder
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.example.gloabtranslate.service.LiveTranslateService
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.delay
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withContext
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue

/**
 * Integration tests for service binding functionality.
 * These tests verify that the LiveTranslateService can be properly bound and unbound.
 */
@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(AndroidJUnit4::class)
class ServiceBindingTest {

    private lateinit var context: Context
    private lateinit var serviceIntent: Intent
    private var serviceConnection: ServiceConnection? = null
    private var serviceBound = false
    private var liveTranslateService: LiveTranslateService? = null

    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext()
        serviceIntent = Intent(context, LiveTranslateService::class.java)
    }

    @After
    fun tearDown() {
        if (serviceBound && serviceConnection != null) {
            context.unbindService(serviceConnection!!)
            serviceBound = false
        }
    }

    @Test
    fun `test service binding`() = runTest {
        // Given
        var connectionResult: Boolean? = null

        // When
        serviceConnection = object : ServiceConnection {
            override fun onServiceConnected(name: ComponentName?, service: IBinder?) {
                val binder = service as LiveTranslateService.LocalBinder
                liveTranslateService = binder.getService()
                serviceBound = true
                connectionResult = true
            }

            override fun onServiceDisconnected(name: ComponentName?) {
                serviceBound = false
                connectionResult = false
            }
        }

        val bindResult = context.bindService(serviceIntent, serviceConnection!!, Context.BIND_AUTO_CREATE)

        // Wait for connection
        delay(1000)

        // Then
        assertTrue("Service should bind successfully", bindResult)
        assertTrue("Service should be bound", serviceBound)
        assertNotNull(liveTranslateService, "LiveTranslateService should not be null")
        assertTrue("Service connection should succeed", connectionResult == true)
    }

    @Test
    fun `test service unbinding`() = runTest {
        // Given
        serviceConnection = object : ServiceConnection {
            override fun onServiceConnected(name: ComponentName?, service: IBinder?) {
                val binder = service as LiveTranslateService.LocalBinder
                liveTranslateService = binder.getService()
                serviceBound = true
            }

            override fun onServiceDisconnected(name: ComponentName?) {
                serviceBound = false
            }
        }

        context.bindService(serviceIntent, serviceConnection!!, Context.BIND_AUTO_CREATE)
        delay(1000) // Wait for connection

        // When
        context.unbindService(serviceConnection!!)

        // Then
        assertFalse(serviceBound, "Service should be unbound")
    }

    @Test
    fun `test service connection lifecycle`() = runTest {
        // Given
        var connectionCount = 0
        var disconnectionCount = 0

        serviceConnection = object : ServiceConnection {
            override fun onServiceConnected(name: ComponentName?, service: IBinder?) {
                val binder = service as LiveTranslateService.LocalBinder
                liveTranslateService = binder.getService()
                serviceBound = true
                connectionCount++
            }

            override fun onServiceDisconnected(name: ComponentName?) {
                serviceBound = false
                disconnectionCount++
            }
        }

        // When
        context.bindService(serviceIntent, serviceConnection!!, Context.BIND_AUTO_CREATE)
        delay(1000) // Wait for connection
        context.unbindService(serviceConnection!!)

        // Then
        assertEquals(1, connectionCount, "Service should connect once")
        assertEquals(1, disconnectionCount, "Service should disconnect once")
    }

    @Test
    fun `test service method calls after binding`() = runTest {
        // Given
        serviceConnection = object : ServiceConnection {
            override fun onServiceConnected(name: ComponentName?, service: IBinder?) {
                val binder = service as LiveTranslateService.LocalBinder
                liveTranslateService = binder.getService()
                serviceBound = true
            }

            override fun onServiceDisconnected(name: ComponentName?) {
                serviceBound = false
            }
        }

        context.bindService(serviceIntent, serviceConnection!!, Context.BIND_AUTO_CREATE)
        delay(1000) // Wait for connection

        // When
        val isRecording = liveTranslateService?.isCurrentlyRecording()
        val recognitionStatus = liveTranslateService?.getRecognitionStatus()
        val recommendedAction = liveTranslateService?.getRecommendedAction()

        // Then
        assertNotNull(isRecording, "isCurrentlyRecording should not be null")
        assertNotNull(recognitionStatus, "getRecognitionStatus should not be null")
        assertNotNull(recommendedAction, "getRecommendedAction should not be null")
        assertFalse(isRecording!!, "Service should not be recording initially")
    }

    @Test
    fun `test service recording state changes`() = runTest {
        // Given
        serviceConnection = object : ServiceConnection {
            override fun onServiceConnected(name: ComponentName?, service: IBinder?) {
                val binder = service as LiveTranslateService.LocalBinder
                liveTranslateService = binder.getService()
                serviceBound = true
            }

            override fun onServiceDisconnected(name: ComponentName?) {
                serviceBound = false
            }
        }

        context.bindService(serviceIntent, serviceConnection!!, Context.BIND_AUTO_CREATE)
        delay(1000) // Wait for connection

        // When
        val initialState = liveTranslateService?.isCurrentlyRecording()
        liveTranslateService?.startRecording()
        val recordingState = liveTranslateService?.isCurrentlyRecording()
        liveTranslateService?.stopRecording()
        val stoppedState = liveTranslateService?.isCurrentlyRecording()

        // Then
        assertFalse(initialState!!, "Initial state should not be recording")
        assertTrue("State should be recording after start", recordingState!!)
        assertFalse(stoppedState!!, "State should not be recording after stop")
    }

    @Test
    fun `test service binding with different flags`() = runTest {
        // Given
        val flags = listOf(
            Context.BIND_AUTO_CREATE,
            Context.BIND_IMPORTANT,
            Context.BIND_ABOVE_CLIENT
        )

        flags.forEach { flag ->
            // When
            serviceConnection = object : ServiceConnection {
                override fun onServiceConnected(name: ComponentName?, service: IBinder?) {
                    val binder = service as LiveTranslateService.LocalBinder
                    liveTranslateService = binder.getService()
                    serviceBound = true
                }

                override fun onServiceDisconnected(name: ComponentName?) {
                    serviceBound = false
                }
            }

            val bindResult = context.bindService(serviceIntent, serviceConnection!!, flag)
            delay(500) // Wait for connection

            // Then
            assertTrue("Service should bind with flag: $flag", bindResult)
            assertTrue("Service should be bound with flag: $flag", serviceBound)

            // Cleanup
            context.unbindService(serviceConnection!!)
            serviceBound = false
        }
    }

    @Test
    fun `test service binding multiple times`() = runTest {
        // Given
        val connections = mutableListOf<ServiceConnection>()

        // When
        repeat(3) { index ->
            val connection = object : ServiceConnection {
                override fun onServiceConnected(name: ComponentName?, service: IBinder?) {
                    val binder = service as LiveTranslateService.LocalBinder
                    liveTranslateService = binder.getService()
                    serviceBound = true
                }

                override fun onServiceDisconnected(name: ComponentName?) {
                    serviceBound = false
                }
            }

            connections.add(connection)
            context.bindService(serviceIntent, connection, Context.BIND_AUTO_CREATE)
            delay(200) // Wait for connection
        }

        // Then
        assertTrue("Service should be bound after multiple bindings", serviceBound)

        // Cleanup
        connections.forEach { connection ->
            context.unbindService(connection)
        }
        serviceBound = false
    }

    @Test
    fun `test service binding with null service`() = runTest {
        // Given
        var connectionResult: Boolean? = null

        serviceConnection = object : ServiceConnection {
            override fun onServiceConnected(name: ComponentName?, service: IBinder?) {
                if (service != null) {
                    val binder = service as LiveTranslateService.LocalBinder
                    liveTranslateService = binder.getService()
                    serviceBound = true
                    connectionResult = true
                } else {
                    connectionResult = false
                }
            }

            override fun onServiceDisconnected(name: ComponentName?) {
                serviceBound = false
                connectionResult = false
            }
        }

        // When
        val bindResult = context.bindService(serviceIntent, serviceConnection!!, Context.BIND_AUTO_CREATE)
        delay(1000) // Wait for connection

        // Then
        assertTrue("Service should bind successfully", bindResult)
        assertTrue("Service connection should succeed with non-null service", connectionResult == true)
    }

    @Test
    fun `test service binding timeout`() = runTest {
        // Given
        var connectionResult: Boolean? = null

        serviceConnection = object : ServiceConnection {
            override fun onServiceConnected(name: ComponentName?, service: IBinder?) {
                val binder = service as LiveTranslateService.LocalBinder
                liveTranslateService = binder.getService()
                serviceBound = true
                connectionResult = true
            }

            override fun onServiceDisconnected(name: ComponentName?) {
                serviceBound = false
                connectionResult = false
            }
        }

        // When
        val bindResult = context.bindService(serviceIntent, serviceConnection!!, Context.BIND_AUTO_CREATE)
        delay(5000) // Wait longer for connection

        // Then
        assertTrue("Service should bind successfully", bindResult)
        assertTrue("Service should be bound", serviceBound)
        assertTrue("Service connection should succeed within timeout", connectionResult == true)
    }

    @Test
    fun `test service binding with invalid intent`() = runTest {
        // Given
        val invalidIntent = Intent(context, String::class.java) // Invalid service class

        serviceConnection = object : ServiceConnection {
            override fun onServiceConnected(name: ComponentName?, service: IBinder?) {
                serviceBound = true
            }

            override fun onServiceDisconnected(name: ComponentName?) {
                serviceBound = false
            }
        }

        // When
        val bindResult = context.bindService(invalidIntent, serviceConnection!!, Context.BIND_AUTO_CREATE)
        delay(1000) // Wait for connection

        // Then
        assertFalse(bindResult, "Service should not bind with invalid intent")
        assertFalse(serviceBound, "Service should not be bound with invalid intent")
    }

    @Test
    fun `test service binding cleanup`() = runTest {
        // Given
        serviceConnection = object : ServiceConnection {
            override fun onServiceConnected(name: ComponentName?, service: IBinder?) {
                val binder = service as LiveTranslateService.LocalBinder
                liveTranslateService = binder.getService()
                serviceBound = true
            }

            override fun onServiceDisconnected(name: ComponentName?) {
                serviceBound = false
            }
        }

        context.bindService(serviceIntent, serviceConnection!!, Context.BIND_AUTO_CREATE)
        delay(1000) // Wait for connection

        // When
        context.unbindService(serviceConnection!!)

        // Then
        assertFalse(serviceBound, "Service should be unbound after cleanup")
    }
}

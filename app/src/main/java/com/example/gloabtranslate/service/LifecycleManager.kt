package com.example.gloabtranslate.service

import android.app.ActivityManager
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.os.Binder
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.util.Log
import androidx.lifecycle.DefaultLifecycleObserver
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.ProcessLifecycleOwner
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong
import java.util.concurrent.atomic.AtomicReference
import java.util.concurrent.CopyOnWriteArrayList

/**
 * Comprehensive service lifecycle management system.
 * Handles service binding, unbinding, state monitoring, dependency management,
 * and automatic recovery for the translation application services.
 */
class LifecycleManager private constructor(private val context: Context) : DefaultLifecycleObserver {
    
    companion object {
        private const val TAG = "LifecycleManager"
        private const val HEALTH_CHECK_INTERVAL_MS = 30000L // 30 seconds
        private const val SERVICE_STARTUP_TIMEOUT_MS = 10000L // 10 seconds
        private const val MAX_RETRY_ATTEMPTS = 3
        private const val RETRY_DELAY_MS = 2000L
        
        @Volatile
        private var INSTANCE: LifecycleManager? = null
        
        fun getInstance(context: Context): LifecycleManager {
            return INSTANCE ?: synchronized(this) {
                INSTANCE ?: LifecycleManager(context.applicationContext).also { INSTANCE = it }
            }
        }
    }
    
    // Service registry and management
    private val registeredServices = ConcurrentHashMap<String, ServiceInfo>()
    private val boundServices = ConcurrentHashMap<String, BoundServiceInfo>()
    private val serviceConnections = ConcurrentHashMap<String, ServiceConnection>()
    private val serviceDependencies = ConcurrentHashMap<String, MutableSet<String>>()
    private val serviceListeners = CopyOnWriteArrayList<ServiceLifecycleListener>()
    
    // State management
    private val isInitialized = AtomicBoolean(false)
    private val isAppInForeground = AtomicBoolean(false)
    private val healthCheckJob = AtomicReference<Job?>()
    private val lifecycleScope = CoroutineScope(Dispatchers.Main + SupervisorJob())
    
    // Statistics and monitoring
    private val serviceStartCount = AtomicLong(0)
    private val serviceStopCount = AtomicLong(0)
    private val totalBindTime = AtomicLong(0)
    private val totalUnbindTime = AtomicLong(0)
    
    /**
     * Service information container
     */
    data class ServiceInfo(
        val serviceClass: Class<*>,
        val isForegroundService: Boolean = false,
        val autoStart: Boolean = false,
        val dependencies: Set<String> = emptySet(),
        val priority: ServicePriority = ServicePriority.NORMAL,
        val configuration: Map<String, Any> = emptyMap(),
        var isRunning: Boolean = false
    )
    
    /**
     * Bound service information
     */
    data class BoundServiceInfo(
        val serviceInfo: ServiceInfo,
        val binder: IBinder? = null,
        val isBound: Boolean = false,
        val bindTime: Long = 0L,
        val lastHealthCheck: Long = 0L,
        val isHealthy: Boolean = true,
        val retryCount: Int = 0
    )
    
    /**
     * Service priority levels
     */
    enum class ServicePriority {
        CRITICAL,   // Must start first, critical for app functionality
        HIGH,       // High priority services
        NORMAL,     // Normal priority services
        LOW         // Low priority services, can be delayed
    }
    
    /**
     * Service state enumeration
     */
    enum class ServiceState {
        UNKNOWN,
        REGISTERED,
        STARTING,
        RUNNING,
        BOUND,
        STOPPING,
        STOPPED,
        FAILED,
        RECOVERING
    }
    
    /**
     * Service lifecycle event
     */
    data class ServiceLifecycleEvent(
        val serviceName: String,
        val eventType: ServiceEventType,
        val timestamp: Long = System.currentTimeMillis(),
        val details: String? = null,
        val error: String? = null
    )
    
    /**
     * Service event types
     */
    enum class ServiceEventType {
        REGISTERED,
        START_REQUESTED,
        STARTED,
        STOP_REQUESTED,
        STOPPED,
        BOUND,
        UNBOUND,
        HEALTH_CHECK_PASSED,
        HEALTH_CHECK_FAILED,
        RECOVERY_STARTED,
        RECOVERY_COMPLETED,
        RECOVERY_FAILED,
        DEPENDENCY_UNAVAILABLE,
        PRIORITY_CHANGED,
        SERVICE_OPERATION_FAILED
    }
    
    /**
     * Service lifecycle listener interface
     */
    interface ServiceLifecycleListener {
        fun onServiceLifecycleEvent(event: ServiceLifecycleEvent)
        fun onServiceHealthChanged(serviceName: String, isHealthy: Boolean)
        fun onServiceDependencyChanged(serviceName: String, dependencies: Set<String>)
    }
    
    /**
     * Service health check interface
     */
    interface ServiceHealthCheck {
        fun isHealthy(): Boolean
        fun getHealthStatus(): String
        fun performHealthCheck(): Boolean
    }
    
    /**
     * Initializes the lifecycle manager
     */
    fun initialize() {
        if (isInitialized.getAndSet(true)) {
            Log.w(TAG, "LifecycleManager already initialized")
            return
        }
        
        try {
            // Register for app lifecycle events
            ProcessLifecycleOwner.get().lifecycle.addObserver(this)
            
            // Start health monitoring
            startHealthMonitoring()
            
            Log.d(TAG, "LifecycleManager initialized successfully")
            
        } catch (e: Exception) {
            Log.e(TAG, "Failed to initialize LifecycleManager", e)
            isInitialized.set(false)
        }
    }
    
    /**
     * Registers a service with the lifecycle manager
     */
    fun registerService(
        serviceName: String,
        serviceClass: Class<*>,
        isForegroundService: Boolean = false,
        autoStart: Boolean = false,
        dependencies: Set<String> = emptySet(),
        priority: ServicePriority = ServicePriority.NORMAL,
        configuration: Map<String, Any> = emptyMap()
    ) {
        try {
            val serviceInfo = ServiceInfo(
                serviceClass = serviceClass,
                isForegroundService = isForegroundService,
                autoStart = autoStart,
                dependencies = dependencies,
                priority = priority,
                configuration = configuration
            )
            
            registeredServices[serviceName] = serviceInfo
            
            // Update dependency mapping
            dependencies.forEach { dependency ->
                serviceDependencies.computeIfAbsent(dependency) { mutableSetOf() }.add(serviceName)
            }
            
            notifyServiceLifecycleEvent(ServiceLifecycleEvent(
                serviceName = serviceName,
                eventType = ServiceEventType.REGISTERED,
                details = "Service registered with priority: $priority"
            ))
            
            // Auto-start if configured
            if (autoStart) {
                lifecycleScope.launch {
                    startService(serviceName)
                }
            }
            
            Log.d(TAG, "Service registered: $serviceName")
            
        } catch (e: Exception) {
            Log.e(TAG, "Failed to register service: $serviceName", e)
        }
    }
    
    /**
     * Starts a registered service
     */
    suspend fun startService(serviceName: String): Boolean = withContext(Dispatchers.IO) {
        try {
            val serviceInfo = registeredServices[serviceName]
                ?: throw IllegalStateException("Service not registered: $serviceName")
            
            // Check dependencies
            if (!checkDependencies(serviceName)) {
                notifyServiceLifecycleEvent(ServiceLifecycleEvent(
                    serviceName = serviceName,
                    eventType = ServiceEventType.DEPENDENCY_UNAVAILABLE,
                    details = "Dependencies not met"
                ))
                return@withContext false
            }
            
            notifyServiceLifecycleEvent(ServiceLifecycleEvent(
                serviceName = serviceName,
                eventType = ServiceEventType.START_REQUESTED
            ))
            
            val startTime = System.currentTimeMillis()
            val success = startServiceInternal(serviceName, serviceInfo)
            val duration = System.currentTimeMillis() - startTime
            
            if (success) {
                serviceInfo.isRunning = true
                serviceStartCount.incrementAndGet()
                notifyServiceLifecycleEvent(ServiceLifecycleEvent(
                    serviceName = serviceName,
                    eventType = ServiceEventType.STARTED,
                    details = "Started in ${duration}ms"
                ))
                Log.d(TAG, "Service started: $serviceName (${duration}ms)")
            } else {
                notifyServiceLifecycleEvent(ServiceLifecycleEvent(
                    serviceName = serviceName,
                    eventType = ServiceEventType.SERVICE_OPERATION_FAILED,
                    error = "Failed to start service"
                ))
                Log.e(TAG, "Failed to start service: $serviceName")
            }
            
            success
            
        } catch (e: Exception) {
            Log.e(TAG, "Error starting service: $serviceName", e)
            notifyServiceLifecycleEvent(ServiceLifecycleEvent(
                serviceName = serviceName,
                eventType = ServiceEventType.SERVICE_OPERATION_FAILED,
                error = e.message
            ))
            false
        }
    }
    
    /**
     * Stops a registered service
     */
    suspend fun stopService(serviceName: String): Boolean = withContext(Dispatchers.IO) {
        try {
            val serviceInfo = registeredServices[serviceName]
                ?: throw IllegalStateException("Service not registered: $serviceName")
            
            notifyServiceLifecycleEvent(ServiceLifecycleEvent(
                serviceName = serviceName,
                eventType = ServiceEventType.STOP_REQUESTED
            ))
            
            val stopTime = System.currentTimeMillis()
            val success = stopServiceInternal(serviceName, serviceInfo)
            val duration = System.currentTimeMillis() - stopTime
            
            if (success) {
                serviceInfo.isRunning = false
                serviceStopCount.incrementAndGet()
                notifyServiceLifecycleEvent(ServiceLifecycleEvent(
                    serviceName = serviceName,
                    eventType = ServiceEventType.STOPPED,
                    details = "Stopped in ${duration}ms"
                ))
                Log.d(TAG, "Service stopped: $serviceName (${duration}ms)")
            } else {
                notifyServiceLifecycleEvent(ServiceLifecycleEvent(
                    serviceName = serviceName,
                    eventType = ServiceEventType.SERVICE_OPERATION_FAILED,
                    error = "Failed to stop service"
                ))
                Log.e(TAG, "Failed to stop service: $serviceName")
            }
            
            success
            
        } catch (e: Exception) {
            Log.e(TAG, "Error stopping service: $serviceName", e)
            notifyServiceLifecycleEvent(ServiceLifecycleEvent(
                serviceName = serviceName,
                eventType = ServiceEventType.SERVICE_OPERATION_FAILED,
                error = e.message
            ))
            false
        }
    }
    
    /**
     * Binds to a service
     */
    suspend fun bindService(serviceName: String): Boolean = withContext(Dispatchers.Main) {
        try {
            val serviceInfo = registeredServices[serviceName]
                ?: throw IllegalStateException("Service not registered: $serviceName")
            
            val bindTime = System.currentTimeMillis()
            
            val connection = object : ServiceConnection {
                override fun onServiceConnected(name: ComponentName?, service: IBinder?) {
                    val boundInfo = BoundServiceInfo(
                        serviceInfo = serviceInfo,
                        binder = service,
                        isBound = true,
                        bindTime = bindTime,
                        lastHealthCheck = System.currentTimeMillis(),
                        isHealthy = true
                    )
                    
                    boundServices[serviceName] = boundInfo
                    totalBindTime.addAndGet(System.currentTimeMillis() - bindTime)
                    
                    notifyServiceLifecycleEvent(ServiceLifecycleEvent(
                        serviceName = serviceName,
                        eventType = ServiceEventType.BOUND,
                        details = "Service bound successfully"
                    ))
                    
                    Log.d(TAG, "Service bound: $serviceName")
                }
                
                override fun onServiceDisconnected(name: ComponentName?) {
                    boundServices.remove(serviceName)
                    totalUnbindTime.addAndGet(System.currentTimeMillis() - bindTime)
                    
                    notifyServiceLifecycleEvent(ServiceLifecycleEvent(
                        serviceName = serviceName,
                        eventType = ServiceEventType.UNBOUND,
                        details = "Service disconnected"
                    ))
                    
                    Log.d(TAG, "Service unbound: $serviceName")
                }
            }
            
            serviceConnections[serviceName] = connection
            
            val intent = Intent(context, serviceInfo.serviceClass)
            val bindResult = context.bindService(intent, connection, Context.BIND_AUTO_CREATE)
            
            if (!bindResult) {
                notifyServiceLifecycleEvent(ServiceLifecycleEvent(
                    serviceName = serviceName,
                    eventType = ServiceEventType.SERVICE_OPERATION_FAILED,
                    error = "Failed to bind service"
                ))
            }
            
            bindResult
            
        } catch (e: Exception) {
            Log.e(TAG, "Error binding service: $serviceName", e)
            notifyServiceLifecycleEvent(ServiceLifecycleEvent(
                serviceName = serviceName,
                eventType = ServiceEventType.SERVICE_OPERATION_FAILED,
                error = e.message
            ))
            false
        }
    }
    
    /**
     * Unbinds from a service
     */
    fun unbindService(serviceName: String): Boolean {
        try {
            val connection = serviceConnections[serviceName] ?: return false
            
            context.unbindService(connection)
            serviceConnections.remove(serviceName)
            boundServices.remove(serviceName)
            
            notifyServiceLifecycleEvent(ServiceLifecycleEvent(
                serviceName = serviceName,
                eventType = ServiceEventType.UNBOUND,
                details = "Service unbound"
            ))
            
            Log.d(TAG, "Service unbound: $serviceName")
            return true
            
        } catch (e: Exception) {
            Log.e(TAG, "Error unbinding service: $serviceName", e)
            return false
        }
    }
    
    /**
     * Gets the current state of a service
     */
    fun getServiceState(serviceName: String): ServiceState {
        return when {
            !registeredServices.containsKey(serviceName) -> ServiceState.UNKNOWN
            boundServices.containsKey(serviceName) -> {
                val boundInfo = boundServices[serviceName]
                when {
                    boundInfo?.isHealthy == false -> ServiceState.FAILED
                    else -> ServiceState.BOUND
                }
            }
            isServiceRunning(serviceName) -> ServiceState.RUNNING
            else -> ServiceState.REGISTERED
        }
    }
    
    /**
     * Gets all registered services
     */
    fun getRegisteredServices(): Map<String, ServiceInfo> = registeredServices.toMap()
    
    /**
     * Gets all bound services
     */
    fun getBoundServices(): Map<String, BoundServiceInfo> = boundServices.toMap()
    
    /**
     * Adds a service lifecycle listener
     */
    fun addServiceLifecycleListener(listener: ServiceLifecycleListener) {
        serviceListeners.add(listener)
    }
    
    /**
     * Removes a service lifecycle listener
     */
    fun removeServiceLifecycleListener(listener: ServiceLifecycleListener) {
        serviceListeners.remove(listener)
    }
    
    /**
     * Gets lifecycle manager statistics
     */
    fun getStatistics(): Map<String, Any> {
        return mapOf(
            "isInitialized" to isInitialized.get(),
            "isAppInForeground" to isAppInForeground.get(),
            "registeredServicesCount" to registeredServices.size,
            "boundServicesCount" to boundServices.size,
            "serviceStartCount" to serviceStartCount.get(),
            "serviceStopCount" to serviceStopCount.get(),
            "totalBindTime" to totalBindTime.get(),
            "totalUnbindTime" to totalUnbindTime.get(),
            "healthCheckActive" to (healthCheckJob.get()?.isActive == true)
        )
    }
    
    /**
     * Performs emergency shutdown of all services
     */
    suspend fun emergencyShutdown() = withContext(Dispatchers.IO) {
        Log.w(TAG, "Emergency shutdown initiated")
        
        // Stop health monitoring
        healthCheckJob.get()?.cancel()
        
        // Unbind all services
        boundServices.keys.forEach { serviceName ->
            unbindService(serviceName)
        }
        
        // Stop all services
        registeredServices.keys.forEach { serviceName ->
            stopService(serviceName)
        }
        
        Log.d(TAG, "Emergency shutdown completed")
    }
    
    // Lifecycle callbacks
    override fun onStart(owner: LifecycleOwner) {
        super.onStart(owner)
        isAppInForeground.set(true)
        Log.d(TAG, "App moved to foreground")
        
        // Resume critical services if needed
        lifecycleScope.launch {
            resumeCriticalServices()
        }
    }
    
    override fun onStop(owner: LifecycleOwner) {
        super.onStop(owner)
        isAppInForeground.set(false)
        Log.d(TAG, "App moved to background")
        
        // Handle background transition
        lifecycleScope.launch {
            handleBackgroundTransition()
        }
    }
    
    // Internal methods
    private suspend fun startServiceInternal(serviceName: String, serviceInfo: ServiceInfo): Boolean {
        return withContext(Dispatchers.IO) {
            try {
                val intent = Intent(context, serviceInfo.serviceClass)
                
                if (serviceInfo.isForegroundService) {
                    // Since minSdk is 34 (API 34+), we always use startForegroundService
                    context.startForegroundService(intent)
                } else {
                    // For non-foreground services, still use regular startService
                    context.startService(intent)
                }
                
                // Wait for service to start
                delay(1000) // Consider a more robust way to confirm service start
                isServiceRunning(serviceName)
                
            } catch (e: Exception) {
                Log.e(TAG, "Failed to start service: $serviceName", e)
                false
            }
        }
    }
    
    private suspend fun stopServiceInternal(serviceName: String, serviceInfo: ServiceInfo): Boolean {
        return withContext(Dispatchers.IO) {
            try {
                val intent = Intent(context, serviceInfo.serviceClass)
                context.stopService(intent)
                
                // Wait for service to stop
                delay(1000) // Consider a more robust way to confirm service stop
                !isServiceRunning(serviceName)
                
            } catch (e: Exception) {
                Log.e(TAG, "Failed to stop service: $serviceName", e)
                false
            }
        }
    }
    
    private fun isServiceRunning(serviceName: String): Boolean {
        val serviceInfo = registeredServices[serviceName]
        return serviceInfo?.isRunning ?: false
    }
    
    private fun checkDependencies(serviceName: String): Boolean {
        val serviceInfo = registeredServices[serviceName] ?: return false // Service not registered
        
        return serviceInfo.dependencies.all { dependency ->
            // A dependency is met if it's running OR bound (for services that might not be 'running' but are bound and active)
            isServiceRunning(dependency) || boundServices.containsKey(dependency)
        }
    }
    
    private fun startHealthMonitoring() {
        healthCheckJob.set(lifecycleScope.launch {
            while (isActive) {
                try {
                    performHealthChecks()
                    delay(HEALTH_CHECK_INTERVAL_MS)
                } catch (e: Exception) { // Catch more specific exceptions if possible
                    Log.e(TAG, "Error in health monitoring", e)
                    // Consider whether to stop monitoring or retry with backoff
                }
            }
        })
    }
    
    private suspend fun performHealthChecks() {
        boundServices.forEach { (serviceName, boundInfo) ->
            try {
                // Ensure the service is still supposed to be bound before checking
                if (!serviceConnections.containsKey(serviceName)) {
                    Log.w(TAG, "Health check for $serviceName skipped: service no longer bound.")
                    boundServices.remove(serviceName) // Clean up if connection is gone
                    return@forEach
                }

                val isHealthy = checkServiceHealth(serviceName, boundInfo)
                val wasHealthy = boundInfo.isHealthy
                
                if (isHealthy != wasHealthy || !isHealthy) { // Also notify if still unhealthy but check passed previously (e.g. state changed)
                    val updatedInfo = boundInfo.copy(
                        isHealthy = isHealthy,
                        lastHealthCheck = System.currentTimeMillis()
                        // Reset retryCount if it becomes healthy again?
                        // retryCount = if (isHealthy) 0 else boundInfo.retryCount
                    )
                    boundServices[serviceName] = updatedInfo
                    
                    val eventType = if (isHealthy) ServiceEventType.HEALTH_CHECK_PASSED else ServiceEventType.HEALTH_CHECK_FAILED
                    notifyServiceLifecycleEvent(ServiceLifecycleEvent(
                        serviceName = serviceName,
                        eventType = eventType,
                        details = "Health check ${if (isHealthy) "passed" else "failed"}"
                    ))
                    
                    serviceListeners.forEach { it.onServiceHealthChanged(serviceName, isHealthy) }
                    
                    // Attempt recovery if unhealthy
                    if (!isHealthy && boundInfo.retryCount < MAX_RETRY_ATTEMPTS) {
                        // Consider a small delay before retrying recovery
                        // delay(RETRY_DELAY_MS / 2) 
                        attemptServiceRecovery(serviceName)
                    } else if (!isHealthy) {
                        Log.w(TAG, "Max recovery attempts reached for $serviceName or recovery not configured.")
                        // Potentially notify of permanent failure after max retries
                    }
                }
                
            } catch (e: Exception) {
                Log.e(TAG, "Health check failed for service: $serviceName", e)
                // Update service health to false if an exception occurs during check
                 boundServices[serviceName]?.let {
                    if (it.isHealthy) { // Only update and notify if it was previously healthy
                        boundServices[serviceName] = it.copy(isHealthy = false, lastHealthCheck = System.currentTimeMillis())
                        notifyServiceLifecycleEvent(ServiceLifecycleEvent(
                            serviceName = serviceName,
                            eventType = ServiceEventType.HEALTH_CHECK_FAILED,
                            details = "Health check threw an exception: ${e.message}"
                        ))
                        serviceListeners.forEach { listener -> listener.onServiceHealthChanged(serviceName, false) }
                    }
                }
            }
        }
    }
    
    private fun checkServiceHealth(serviceName: String, boundInfo: BoundServiceInfo): Boolean {
        return try {
            // Basic health check - service is bound and binder is available and alive
            val isBoundAndAlive = boundInfo.isBound && boundInfo.binder != null && boundInfo.binder.pingBinder()
            
            // Additional health checks can be added here
            // For example, calling a health check method on the service if the binder is of a specific type
            // if (isBoundAndAlive && boundInfo.binder is MyServiceBinder) {
            //     return (boundInfo.binder as MyServiceBinder).isServiceHealthy()
            // }
            
            isBoundAndAlive
        } catch (e: Exception) { // Catch DeadObjectException specifically if that's a concern
            Log.e(TAG, "Health check error for service: $serviceName", e)
            false
        }
    }
    
    private suspend fun attemptServiceRecovery(serviceName: String) {
        // Ensure we don't attempt recovery if already recovering or max retries hit
        val currentBoundInfo = boundServices[serviceName]
        if (currentBoundInfo == null || currentBoundInfo.retryCount >= MAX_RETRY_ATTEMPTS) {
            Log.w(TAG, "Skipping recovery for $serviceName: not bound or max retries reached.")
            return
        }

        try {
            notifyServiceLifecycleEvent(ServiceLifecycleEvent(
                serviceName = serviceName,
                eventType = ServiceEventType.RECOVERY_STARTED,
                details = "Attempting service recovery (attempt ${currentBoundInfo.retryCount + 1})"
            ))
            
            // Increment retry count before attempting
            boundServices[serviceName] = currentBoundInfo.copy(retryCount = currentBoundInfo.retryCount + 1)

            // Unbind and rebind the service
            unbindService(serviceName) // This should be synchronous or we need to await its completion
            delay(RETRY_DELAY_MS) // Delay before rebinding
            
            val success = bindService(serviceName) // This is suspend, so it will wait
            
            if (success) {
                // If successful, reset retry count for this service in boundServices
                 boundServices[serviceName]?.let {
                    boundServices[serviceName] = it.copy(retryCount = 0, isHealthy = true, lastHealthCheck = System.currentTimeMillis())
                }
                notifyServiceLifecycleEvent(ServiceLifecycleEvent(
                    serviceName = serviceName,
                    eventType = ServiceEventType.RECOVERY_COMPLETED,
                    details = "Service recovery completed successfully"
                ))
            } else {
                // Recovery attempt failed, health remains false.
                // The health check loop will eventually pick it up again if it's still unhealthy.
                notifyServiceLifecycleEvent(ServiceLifecycleEvent(
                    serviceName = serviceName,
                    eventType = ServiceEventType.RECOVERY_FAILED,
                    error = "Service recovery attempt ${currentBoundInfo.retryCount + 1} failed"
                ))
            }
            
        } catch (e: Exception) {
            Log.e(TAG, "Service recovery error for $serviceName", e)
            notifyServiceLifecycleEvent(ServiceLifecycleEvent(
                serviceName = serviceName,
                eventType = ServiceEventType.RECOVERY_FAILED,
                error = "Exception during recovery: ${e.message}"
            ))
            // Ensure health status is marked as false if an exception occurs during recovery
            boundServices[serviceName]?.let {
                 boundServices[serviceName] = it.copy(isHealthy = false, lastHealthCheck = System.currentTimeMillis())
            }
        }
    }
    
    private suspend fun resumeCriticalServices() {
        registeredServices.values
            .filter { it.priority == ServicePriority.CRITICAL && it.autoStart }
            .forEach { serviceInfo ->
                // Find service name from serviceInfo (safer than relying on iteration order)
                val serviceName = registeredServices.entries.find { it.value == serviceInfo }?.key
                serviceName?.let {
                    if (getServiceState(it) != ServiceState.RUNNING && getServiceState(it) != ServiceState.BOUND) {
                        Log.d(TAG, "Resuming critical service: $it")
                        startService(it) // This is suspend
                    }
                }
            }
    }
    
    private suspend fun handleBackgroundTransition() {
        // Stop or pause non-critical services to save resources, unless they are foreground services
        registeredServices.values
            .filter { it.priority < ServicePriority.HIGH && !it.isForegroundService } // Example: Stop LOW and NORMAL
            .forEach { serviceInfo ->
                val serviceName = registeredServices.entries.find { it.value == serviceInfo }?.key
                serviceName?.let {
                    val currentState = getServiceState(it)
                    if (currentState == ServiceState.RUNNING || currentState == ServiceState.BOUND) {
                         Log.d(TAG, "Stopping non-critical service in background: $it")
                        if (boundServices.containsKey(it)) {
                            unbindService(it) // Unbind first if bound
                        }
                        if (isServiceRunning(it)){ // Check again if it was only bound but not running
                            stopService(it) // Then stop if still running
                        }
                    }
                }
            }
    }
    
    private fun notifyServiceLifecycleEvent(event: ServiceLifecycleEvent) {
        // Consider running on Main dispatcher if listeners expect UI updates
        // lifecycleScope.launch(Dispatchers.Main) {
            serviceListeners.forEach { listener ->
                try {
                    listener.onServiceLifecycleEvent(event)
                } catch (e: Exception) {
                    Log.e(TAG, "Error notifying lifecycle listener ${listener.javaClass.simpleName}", e)
                }
            }
        // }
    }
    
    /**
     * Cleans up resources
     */
    fun cleanup() {
        try {
            Log.d(TAG, "LifecycleManager cleanup initiated")
            // Cancel all coroutines started by this manager's scope
            lifecycleScope.cancel("LifecycleManager cleanup") 
            healthCheckJob.get()?.cancel("LifecycleManager cleanup") // Explicitly cancel health check job
            
            // Gracefully stop and unbind services
            // Consider doing this in a specific order if necessary
            // Run on a background thread if these operations are blocking
            // GlobalScope.launch(Dispatchers.IO) { // Or a dedicated cleanup coroutine scope
                 registeredServices.keys.toList().forEach { serviceName -> // Use toList to avoid ConcurrentModificationException
                    if (serviceConnections.containsKey(serviceName)) {
                        unbindService(serviceName)
                    }
                    if (isServiceRunning(serviceName)) {
                       // stopService(serviceName) // This is suspend, cannot call from non-suspend cleanup directly
                       // For a non-suspend cleanup, you might need to send a request or use runBlocking (carefully)
                       // Or make cleanup suspend fun
                       Log.d(TAG, "Requesting stop for service $serviceName during cleanup.")
                       // Consider a simplified stop mechanism here if full stopService is too complex for cleanup
                       val serviceInfo = registeredServices[serviceName]
                       if (serviceInfo != null) {
                           try {
                               context.stopService(Intent(context, serviceInfo.serviceClass))
                           } catch (e: Exception) {
                               Log.e(TAG, "Error stopping $serviceName during cleanup", e)
                           }
                       }
                    }
                }
            // }

            serviceConnections.clear()
            boundServices.clear()
            registeredServices.clear()
            serviceDependencies.clear()
            serviceListeners.clear()
            
            // Unregister app lifecycle observer
            // Ensure this runs on the main thread if ProcessLifecycleOwner requires it
            // Handler(Looper.getMainLooper()).post {
                 ProcessLifecycleOwner.get().lifecycle.removeObserver(this)
            // }
            
            isInitialized.set(false)
            INSTANCE = null // Allow re-initialization if needed later
            Log.d(TAG, "LifecycleManager cleaned up successfully")
            
        } catch (e: Exception) {
            Log.e(TAG, "Error during LifecycleManager cleanup", e)
        }
    }
}

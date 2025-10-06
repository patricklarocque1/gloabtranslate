package com.example.gloabtranslate

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.appcompat.app.AppCompatDelegate
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import androidx.core.content.ContextCompat
import androidx.navigation.fragment.NavHostFragment
import androidx.navigation.ui.setupActionBarWithNavController
import com.example.gloabtranslate.core.data.config.ConfigurationManager
import com.example.gloabtranslate.core.data.config.ThemeConfig
import com.example.gloabtranslate.service.LiveTranslateService
import com.example.gloabtranslate.service.ServiceCoordinator
import dagger.android.AndroidInjection
import dagger.android.DispatchingAndroidInjector
import dagger.android.HasAndroidInjector
import javax.inject.Inject
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking

class MainActivity : AppCompatActivity(), HasAndroidInjector {

    @Inject
    lateinit var androidInjector: DispatchingAndroidInjector<Any>

    // Inject configuration manager instead of using singleton accessor
    @Inject
    lateinit var configurationManager: ConfigurationManager

    @Inject
    lateinit var serviceCoordinator: ServiceCoordinator
    @javax.inject.Inject lateinit var errorRecoverySystem: com.example.gloabtranslate.core.error.ErrorRecoverySystem

    private lateinit var liveTranslateService: LiveTranslateService
    private var serviceBound = false

    private val serviceStateListeners = mutableSetOf<ServiceStateListener>()

    private var themeObserverJob: Job? = null
    private var uiBehaviorJob: Job? = null
    @javax.inject.Inject lateinit var uiBehaviorController: com.example.gloabtranslate.ui.uibehavior.UiBehaviorController
    private var appliedNightMode: Int? = null

    private val requestPermissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { permissions ->
        val allGranted = permissions.values.all { it }
        if (allGranted) {
            startLiveTranslationService()
        } else {
            finish()
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        applyThemeFromPreferences(initial = true)
        AndroidInjection.inject(this)
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        val toolbar = findViewById<com.google.android.material.appbar.MaterialToolbar>(R.id.toolbar)
        setSupportActionBar(toolbar)
        toolbar.inflateMenu(R.menu.main_overflow)
        toolbar.setOnMenuItemClickListener { item ->
            when (item.itemId) {
                R.id.menu_diagnostics -> {
                    startActivity(android.content.Intent(this, com.example.gloabtranslate.ui.debug.DiagnosticsActivity::class.java))
                    true
                }
                else -> false
            }
        }

        startThemeObserver()
        startUiBehaviorObserver()
        startHealthIndicator()
        setupNavigation()
        checkPermissionsAndStartService()
    }

    override fun androidInjector(): DispatchingAndroidInjector<Any> = androidInjector

    private fun applyThemeFromPreferences(initial: Boolean = false) {
        val (mode, style) = runBlocking {
            runCatching {
                val themeConfig = configurationManager.currentThemeConfig()
                resolveNightMode(themeConfig) to resolveThemeStyle(themeConfig)
            }.getOrElse { AppCompatDelegate.MODE_NIGHT_FOLLOW_SYSTEM to R.style.Theme_Gloabtranslate_NoActionBar }
        }
        appliedNightMode = mode
        AppCompatDelegate.setDefaultNightMode(mode)
        if (initial) {
            setTheme(style)
        }
    }


    private fun startThemeObserver() {
        themeObserverJob?.cancel()
        themeObserverJob = lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                configurationManager.themeConfig.collectLatest { config ->
                    val newMode = resolveNightMode(config)
                    if (appliedNightMode != newMode) {
                        appliedNightMode = newMode
                        AppCompatDelegate.setDefaultNightMode(newMode)
                        delegate.applyDayNight()
                    }
                }
            }
        }
    }

    private fun startUiBehaviorObserver() {
        uiBehaviorJob?.cancel()
        uiBehaviorController.start()
        uiBehaviorJob = lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                // Poll lightweight (controller already collects) every 500ms; could be replaced with callback if needed
                while (true) {
                    val state = uiBehaviorController.currentState()
                    applyFontScale(state.fontScale)
                    kotlinx.coroutines.delay(500)
                }
            }
        }
    }

    private var lastFontScale: Float = 1.0f
    private fun applyFontScale(scale: Float) {
        if (kotlin.math.abs(scale - lastFontScale) < 0.01f) return
        lastFontScale = scale
        val configuration = resources.configuration
        if (configuration.fontScale == scale) return
        configuration.fontScale = scale
        @Suppress("DEPRECATION")
        resources.updateConfiguration(configuration, resources.displayMetrics)
        // NOTE: For full activity-wide refresh: recreate() could be called, but we avoid jank; rely on views respecting scaled density.
    }

    private fun resolveNightMode(themeConfig: ThemeConfig): Int {
        return when {
            themeConfig.enableDarkMode -> AppCompatDelegate.MODE_NIGHT_YES
            themeConfig.theme.equals("light", ignoreCase = true) -> AppCompatDelegate.MODE_NIGHT_NO
            themeConfig.theme.equals("dark", ignoreCase = true) -> AppCompatDelegate.MODE_NIGHT_YES
            else -> AppCompatDelegate.MODE_NIGHT_FOLLOW_SYSTEM
        }
    }

    private fun resolveThemeStyle(themeConfig: ThemeConfig): Int {
        return when {
            themeConfig.enableDarkMode -> R.style.Theme_Gloabtranslate_Dark
            themeConfig.theme.equals("light", ignoreCase = true) -> R.style.Theme_Gloabtranslate_Light
            themeConfig.theme.equals("dark", ignoreCase = true) -> R.style.Theme_Gloabtranslate_Dark
            else -> R.style.Theme_Gloabtranslate
        }
    }

    private fun setupNavigation() {
        val navHostFragment = supportFragmentManager
            .findFragmentById(R.id.nav_host_fragment) as NavHostFragment
        val navController = navHostFragment.navController

        setupActionBarWithNavController(navController)
    }

    private fun checkPermissionsAndStartService() {
        val permissions = mutableListOf<String>().apply {
            add(Manifest.permission.RECORD_AUDIO)
            add(Manifest.permission.INTERNET)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                add(Manifest.permission.POST_NOTIFICATIONS)
            }
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
                add(Manifest.permission.FOREGROUND_SERVICE_MICROPHONE)
            }
        }

        val permissionsToRequest = permissions.filter {
            ContextCompat.checkSelfPermission(this, it) != PackageManager.PERMISSION_GRANTED
        }

        if (permissionsToRequest.isEmpty()) {
            startLiveTranslationService()
        } else {
            requestPermissionLauncher.launch(permissionsToRequest.toTypedArray())
        }
    }

    private fun startLiveTranslationService() {
        val serviceIntent = Intent(this, LiveTranslateService::class.java)
        // Since minSdk is 34 (API 34+), we always use startForegroundService
        startForegroundService(serviceIntent)

        bindService(serviceIntent, serviceConnection, BIND_AUTO_CREATE)
    }

    override fun onDestroy() {
        themeObserverJob?.cancel()
        if (serviceBound) {
            unbindService(serviceConnection)
            serviceBound = false
        }
        super.onDestroy()
    }

    private val serviceConnection = object : android.content.ServiceConnection {
        override fun onServiceConnected(name: android.content.ComponentName?, service: android.os.IBinder?) {
            val binder = service as LiveTranslateService.LocalBinder
            liveTranslateService = binder.getService()
            serviceBound = true
            notifyServiceBoundChanged()
            notifyServiceStatusChanged()
        }

        override fun onServiceDisconnected(name: android.content.ComponentName?) {
            serviceBound = false
            notifyServiceBoundChanged()
            notifyServiceStatusChanged()
        }
    }

    private fun updateUI() {
        notifyServiceStatusChanged()
    }

    override fun onSupportNavigateUp(): Boolean {
        val navHostFragment = supportFragmentManager
            .findFragmentById(R.id.nav_host_fragment) as NavHostFragment
        val navController = navHostFragment.navController
        return navController.navigateUp() || super.onSupportNavigateUp()
    }

    fun isServiceBound(): Boolean = serviceBound
    fun getLiveTranslateService(): LiveTranslateService? = if (serviceBound) liveTranslateService else null

    fun registerServiceStateListener(listener: ServiceStateListener) {
        if (serviceStateListeners.add(listener)) {
            listener.onServiceStateChanged(serviceBound)
            listener.onServiceStatusChanged()
        }
    }

    fun unregisterServiceStateListener(listener: ServiceStateListener) {
        serviceStateListeners.remove(listener)
    }

    fun notifyServiceStatusChanged() {
        serviceStateListeners.forEach { it.onServiceStatusChanged() }
    }

    private fun notifyServiceBoundChanged() {
        serviceStateListeners.forEach { it.onServiceStateChanged(serviceBound) }
    }

    private fun startHealthIndicator() {
        val toolbar = findViewById<com.google.android.material.appbar.MaterialToolbar>(R.id.toolbar)
        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                serviceCoordinator.systemHealth.collectLatest { health ->
                    val (label, colorRes) = when (health) {
                        ServiceCoordinator.SystemHealth.HEALTHY -> "Healthy" to android.R.color.holo_green_light
                        ServiceCoordinator.SystemHealth.DEGRADED -> "Degraded" to android.R.color.holo_orange_light
                        ServiceCoordinator.SystemHealth.CRITICAL -> "Critical" to android.R.color.holo_red_light
                        ServiceCoordinator.SystemHealth.UNKNOWN -> "Starting..." to android.R.color.darker_gray
                    }
                    // Preserve any existing breaker indicator when updating base health label
                    val existing = toolbar.subtitle?.toString() ?: ""
                    val hadBreaker = existing.contains("Breakers Open")
                    toolbar.subtitle = if (hadBreaker) "$label | Breakers Open" else label
                    toolbar.setSubtitleTextColor(ContextCompat.getColor(this@MainActivity, colorRes))
                }
            }
        }
        // Circuit breaker indicator collector
        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                errorRecoverySystem.circuitBreakerStates.collectLatest { breakers ->
                    val anyOpen = breakers.any { it.value.isOpen }
                    val current = toolbar.subtitle?.toString() ?: ""
                    val base = current.replace(" | Breakers Open", "").replace("Breakers Open", "").trim()
                    toolbar.subtitle = when {
                        anyOpen && base.isNotBlank() -> "$base | Breakers Open"
                        anyOpen && base.isBlank() -> "Breakers Open"
                        !anyOpen -> base
                        else -> base
                    }
                }
            }
        }
    }

    interface ServiceStateListener {
        fun onServiceStateChanged(isBound: Boolean)
        fun onServiceStatusChanged()
    }
}

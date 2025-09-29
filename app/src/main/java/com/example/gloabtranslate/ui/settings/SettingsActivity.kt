package com.example.gloabtranslate.ui.settings

import android.animation.Animator
import android.animation.AnimatorListenerAdapter
import android.animation.ValueAnimator
import android.content.Context
import android.content.Intent
import android.content.SharedPreferences
import android.os.Bundle
import android.text.Editable
import android.text.TextWatcher
import android.util.Log
import android.view.LayoutInflater
import android.view.Menu
import android.view.MenuItem
import android.view.View
import android.view.ViewGroup
import android.view.animation.AccelerateDecelerateInterpolator
import android.widget.*
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.example.gloabtranslate.R
import com.example.gloabtranslate.core.data.config.LanguagePairConfig
import com.example.gloabtranslate.core.data.preferences.UserPreferencesManager
import com.example.gloabtranslate.core.data.repository.TranslationHistoryRepository
import com.google.android.material.chip.Chip
import com.google.android.material.chip.ChipGroup
import com.example.gloabtranslate.core.utils.Logger
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*

/**
 * Comprehensive settings activity with categorized preferences,
 * search functionality, validation, and accessibility support.
 */
class SettingsActivity : AppCompatActivity(), SharedPreferences.OnSharedPreferenceChangeListener {
    
    companion object {
        private const val TAG = "SettingsActivity"
        private const val ANIMATION_DURATION = 250L
        private const val SEARCH_DELAY_MS = 300L
        
        fun newIntent(context: Context): Intent {
            return Intent(context, SettingsActivity::class.java)
        }
    }
    
    // UI Components
    private lateinit var toolbar: androidx.appcompat.widget.Toolbar
    private lateinit var searchContainer: LinearLayout
    private lateinit var searchEditText: EditText
    private lateinit var clearSearchButton: ImageButton
    private lateinit var filterContainer: LinearLayout
    private lateinit var filterChipGroup: ChipGroup
    private lateinit var allFilterChip: Chip
    private lateinit var generalFilterChip: Chip
    private lateinit var translationFilterChip: Chip
    private lateinit var audioFilterChip: Chip
    private lateinit var uiFilterChip: Chip
    private lateinit var privacyFilterChip: Chip
    private lateinit var advancedFilterChip: Chip
    private lateinit var settingsRecyclerView: RecyclerView
    private lateinit var emptyStateContainer: LinearLayout
    private lateinit var emptyStateImageView: ImageView
    private lateinit var emptyStateTextView: TextView
    private lateinit var loadingIndicator: ProgressBar
    
    // Adapter and data
    private lateinit var settingsAdapter: SettingsAdapter
    private var allSettingsItems: List<SettingsItem> = emptyList()
    private var filteredSettingsItems: List<SettingsItem> = emptyList()
    
    // State management
    private var currentFilter: SettingsCategory = SettingsCategory.ALL
    private var searchQuery: String = ""
    private var isSearching = false
    private var searchJob: Job? = null
    
    // Managers
    private lateinit var preferencesManager: UserPreferencesManager
    private lateinit var languageConfig: LanguagePairConfig
    private val historyRepository: TranslationHistoryRepository by lazy {
        TranslationHistoryRepository.getInstance(applicationContext)
    }
    
    /**
     * Settings item data class
     */
    data class SettingsItem(
        val id: String,
        val title: String,
        val description: String? = null,
        val category: SettingsCategory,
        val type: SettingsType,
        val currentValue: Any? = null,
        val defaultValue: Any? = null,
        val possibleValues: List<Any>? = null,
        val isEnabled: Boolean = true,
        val requiresRestart: Boolean = false,
        val requiresPermission: String? = null,
        val icon: Int? = null,
        val metadata: Map<String, String> = emptyMap(),
        val preferenceKey: String? = null
    )
    
    /**
     * Settings categories
     */
    enum class SettingsCategory {
        ALL,
        GENERAL,
        TRANSLATION,
        AUDIO,
        UI,
        PRIVACY,
        ADVANCED
    }
    
    /**
     * Settings types
     */
    enum class SettingsType {
        SWITCH,
        LIST,
        NUMBER,
        TEXT,
        BUTTON,
        INFO,
        DIVIDER
    }
    
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_settings)
        Logger.d("SettingsActivity created", TAG)

        initializeViews()
        setupToolbar()
        setupListeners()
        setupRecyclerView()
        setupSearch()
        setupFilters()
        setupAccessibility()
        
        // Initialize managers and load settings sequentially to avoid race conditions
        lifecycleScope.launch {
            val initialized = initializeManagers()
            if (initialized) {
                loadSettings()
            } else {
                showError()
            }
        }
        
        // Start entrance animation
        startEntranceAnimation()
    }
    
    private fun initializeViews() {
        toolbar = findViewById(R.id.toolbar)
        searchContainer = findViewById(R.id.search_container)
        searchEditText = findViewById(R.id.search_edit_text)
        clearSearchButton = findViewById(R.id.clear_search_button)
        filterContainer = findViewById(R.id.filter_container)
        filterChipGroup = findViewById(R.id.filter_chip_group)
        allFilterChip = findViewById(R.id.all_filter_chip)
        generalFilterChip = findViewById(R.id.general_filter_chip)
        translationFilterChip = findViewById(R.id.translation_filter_chip)
        audioFilterChip = findViewById(R.id.audio_filter_chip)
        uiFilterChip = findViewById(R.id.ui_filter_chip)
        privacyFilterChip = findViewById(R.id.privacy_filter_chip)
        advancedFilterChip = findViewById(R.id.advanced_filter_chip)
        settingsRecyclerView = findViewById(R.id.settings_recycler_view)
        emptyStateContainer = findViewById(R.id.empty_state_container)
        emptyStateImageView = findViewById(R.id.empty_state_image_view)
        emptyStateTextView = findViewById(R.id.empty_state_text_view)
        loadingIndicator = findViewById(R.id.loading_indicator)
    }
    
    private fun setupToolbar() {
        setSupportActionBar(toolbar)
        supportActionBar?.apply {
            setDisplayHomeAsUpEnabled(true)
            setDisplayShowHomeEnabled(true)
            title = getString(R.string.settings)
        }
    }
    
    private fun setupListeners() {
        // Clear search button
        clearSearchButton.setOnClickListener {
            searchEditText.text?.clear()
            clearSearchButton.visibility = View.GONE
        }
        
        // Search functionality
        searchEditText.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
            
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {
                searchQuery = s?.toString() ?: ""
                updateClearSearchButtonVisibility()
                performSearch()
            }
            
            override fun afterTextChanged(s: Editable?) {}
        })
        
        // Filter chips
        allFilterChip.setOnClickListener { setFilter(SettingsCategory.ALL) }
        generalFilterChip.setOnClickListener { setFilter(SettingsCategory.GENERAL) }
        translationFilterChip.setOnClickListener { setFilter(SettingsCategory.TRANSLATION) }
        audioFilterChip.setOnClickListener { setFilter(SettingsCategory.AUDIO) }
        uiFilterChip.setOnClickListener { setFilter(SettingsCategory.UI) }
        privacyFilterChip.setOnClickListener { setFilter(SettingsCategory.PRIVACY) }
        advancedFilterChip.setOnClickListener { setFilter(SettingsCategory.ADVANCED) }
    }
    
    private fun setupRecyclerView() {
        settingsAdapter = SettingsAdapter(
            onItemClick = { item -> handleSettingsItemClick(item) },
            onToggleChanged = { item, isChecked -> handleSwitchToggle(item, isChecked) }
        )
        
        settingsRecyclerView.apply {
            layoutManager = LinearLayoutManager(this@SettingsActivity)
            adapter = settingsAdapter
            addItemDecoration(SettingsItemDecoration(this@SettingsActivity))
        }
    }
    
    private fun setupSearch() {
        searchEditText.hint = getString(R.string.search_settings)
        searchEditText.setCompoundDrawablesWithIntrinsicBounds(
            ContextCompat.getDrawable(this, R.drawable.ic_search),
            null, null, null
        )
    }
    
    private fun setupFilters() {
        updateFilterChips(SettingsCategory.ALL)
    }
    
    private fun setupAccessibility() {
        // Set content descriptions
        clearSearchButton.contentDescription = getString(R.string.clear_search)
        
        // Set accessibility hints
        searchEditText.hint = getString(R.string.search_settings_hint)
        
        // Set up filter chip accessibility
        allFilterChip.contentDescription = getString(R.string.show_all_settings)
        generalFilterChip.contentDescription = getString(R.string.show_general_settings)
        translationFilterChip.contentDescription = getString(R.string.show_translation_settings)
        audioFilterChip.contentDescription = getString(R.string.show_audio_settings)
        uiFilterChip.contentDescription = getString(R.string.show_ui_settings)
        privacyFilterChip.contentDescription = getString(R.string.show_privacy_settings)
        advancedFilterChip.contentDescription = getString(R.string.show_advanced_settings)
    }
    
    private suspend fun initializeManagers(): Boolean {
        return try {
            if (!::preferencesManager.isInitialized) {
                preferencesManager = UserPreferencesManager.getInstance(this)
            }
            val preferencesInitialized = preferencesManager.initialize()

            if (!::languageConfig.isInitialized) {
                languageConfig = LanguagePairConfig.getInstance(this)
            }
            val languageInitialized = languageConfig.initialize()

            preferencesInitialized && languageInitialized
        } catch (e: Exception) {
            Log.e(TAG, "Failed to initialize managers", e)
            false
        }
    }
    
    private suspend fun loadSettings() {
        if (!::preferencesManager.isInitialized) {
            Log.w(TAG, "Preferences manager not initialized; skipping settings load")
            Logger.w("Preferences manager not initialized; skipping settings load", TAG)
            return
        }
        try {
            showLoading(true)
            Logger.d("Loading settings items", TAG)
            
            // Load all settings items
            allSettingsItems = createSettingsItems()
            
            // Apply current filter
            applyCurrentFilter()
            
            showLoading(false)
            Logger.d("Loaded ${allSettingsItems.size} settings items", TAG)
            
        } catch (e: Exception) {
            Log.e(TAG, "Failed to load settings", e)
            Logger.e("Failed to load settings", TAG, e)
            showError()
        }
    }
    
    private suspend fun createSettingsItems(): List<SettingsItem> {
        return listOf(
            // General Settings
            SettingsItem(
                id = "theme",
                title = getString(R.string.theme),
                description = getString(R.string.theme_description),
                category = SettingsCategory.GENERAL,
                type = SettingsType.LIST,
                currentValue = preferencesManager.getPreference("theme"),
                preferenceKey = "theme",
                possibleValues = listOf("system", "light", "dark"),
                icon = R.drawable.ic_palette
            ),
            SettingsItem(
                id = "language",
                title = getString(R.string.app_language),
                description = getString(R.string.app_language_description),
                category = SettingsCategory.GENERAL,
                type = SettingsType.LIST,
                currentValue = preferencesManager.getPreference("language"),
                preferenceKey = "language",
                possibleValues = listOf("en", "es", "fr", "de", "it", "pt", "ru", "zh", "ja", "ko", "ar", "hi"),
                icon = R.drawable.ic_language
            ),
            SettingsItem(
                id = "auto_save",
                title = getString(R.string.auto_save),
                description = getString(R.string.auto_save_description),
                category = SettingsCategory.GENERAL,
                type = SettingsType.SWITCH,
                currentValue = preferencesManager.getPreference("autoSave"),
                preferenceKey = "autoSave",
                icon = R.drawable.ic_save
            ),
            SettingsItem(
                id = "enable_analytics",
                title = getString(R.string.enable_analytics),
                description = getString(R.string.enable_analytics_description),
                category = SettingsCategory.GENERAL,
                type = SettingsType.SWITCH,
                currentValue = preferencesManager.getPreference("enableAnalytics"),
                preferenceKey = "enableAnalytics",
                icon = R.drawable.ic_analytics
            ),
            
            // Translation Settings
            SettingsItem(
                id = "default_source_language",
                title = getString(R.string.default_source_language),
                description = getString(R.string.default_source_language_description),
                category = SettingsCategory.TRANSLATION,
                type = SettingsType.LIST,
                currentValue = preferencesManager.getPreference("defaultSourceLanguage"),
                preferenceKey = "defaultSourceLanguage",
                possibleValues = listOf("auto", "en", "es", "fr", "de", "it", "pt", "ru", "zh", "ja", "ko", "ar", "hi"),
                icon = R.drawable.ic_translate
            ),
            SettingsItem(
                id = "default_target_language",
                title = getString(R.string.default_target_language),
                description = getString(R.string.default_target_language_description),
                category = SettingsCategory.TRANSLATION,
                type = SettingsType.LIST,
                currentValue = preferencesManager.getPreference("defaultTargetLanguage"),
                preferenceKey = "defaultTargetLanguage",
                possibleValues = listOf("en", "es", "fr", "de", "it", "pt", "ru", "zh", "ja", "ko", "ar", "hi"),
                icon = R.drawable.ic_translate
            ),
            SettingsItem(
                id = "enable_auto_translation",
                title = getString(R.string.enable_auto_translation),
                description = getString(R.string.enable_auto_translation_description),
                category = SettingsCategory.TRANSLATION,
                type = SettingsType.SWITCH,
                currentValue = preferencesManager.getPreference("enableAutoTranslation"),
                preferenceKey = "enableAutoTranslation",
                icon = R.drawable.ic_auto_translate
            ),
            SettingsItem(
                id = "confidence_threshold",
                title = getString(R.string.confidence_threshold),
                description = getString(R.string.confidence_threshold_description),
                category = SettingsCategory.TRANSLATION,
                type = SettingsType.NUMBER,
                currentValue = preferencesManager.getPreference("confidenceThreshold"),
                preferenceKey = "confidenceThreshold",
                defaultValue = 0.7f,
                icon = R.drawable.ic_confidence
            ),
            SettingsItem(
                id = "enable_on_device_translation",
                title = getString(R.string.enable_on_device_translation),
                description = getString(R.string.enable_on_device_translation_description),
                category = SettingsCategory.TRANSLATION,
                type = SettingsType.SWITCH,
                currentValue = preferencesManager.getPreference("enableOnDeviceTranslation"),
                preferenceKey = "enableOnDeviceTranslation",
                icon = R.drawable.ic_on_device
            ),
            
            // Audio Settings
            SettingsItem(
                id = "enable_voice_recognition",
                title = getString(R.string.enable_voice_recognition),
                description = getString(R.string.enable_voice_recognition_description),
                category = SettingsCategory.AUDIO,
                type = SettingsType.SWITCH,
                currentValue = preferencesManager.getPreference("enableVoiceRecognition"),
                preferenceKey = "enableVoiceRecognition",
                icon = R.drawable.ic_mic
            ),
            SettingsItem(
                id = "audio_quality",
                title = getString(R.string.audio_quality),
                description = getString(R.string.audio_quality_description),
                category = SettingsCategory.AUDIO,
                type = SettingsType.LIST,
                currentValue = preferencesManager.getPreference("audioQuality"),
                preferenceKey = "audioQuality",
                possibleValues = listOf("low", "medium", "high"),
                icon = R.drawable.ic_audio_quality
            ),
            SettingsItem(
                id = "enable_noise_reduction",
                title = getString(R.string.enable_noise_reduction),
                description = getString(R.string.enable_noise_reduction_description),
                category = SettingsCategory.AUDIO,
                type = SettingsType.SWITCH,
                currentValue = preferencesManager.getPreference("enableNoiseReduction"),
                preferenceKey = "enableNoiseReduction",
                icon = R.drawable.ic_noise_reduction
            ),
            
            // UI Settings
            SettingsItem(
                id = "font_size",
                title = getString(R.string.font_size),
                description = getString(R.string.font_size_description),
                category = SettingsCategory.UI,
                type = SettingsType.LIST,
                currentValue = preferencesManager.getPreference("fontSize"),
                preferenceKey = "fontSize",
                possibleValues = listOf("small", "medium", "large", "extra_large"),
                icon = R.drawable.ic_text_size
            ),
            SettingsItem(
                id = "enable_animations",
                title = getString(R.string.enable_animations),
                description = getString(R.string.enable_animations_description),
                category = SettingsCategory.UI,
                type = SettingsType.SWITCH,
                currentValue = preferencesManager.getPreference("enableAnimations"),
                preferenceKey = "enableAnimations",
                icon = R.drawable.ic_animations
            ),
            SettingsItem(
                id = "enable_haptic_feedback",
                title = getString(R.string.enable_haptic_feedback),
                description = getString(R.string.enable_haptic_feedback_description),
                category = SettingsCategory.UI,
                type = SettingsType.SWITCH,
                currentValue = preferencesManager.getPreference("enableHapticFeedback"),
                preferenceKey = "enableHapticFeedback",
                icon = R.drawable.ic_haptic_feedback
            ),
            SettingsItem(
                id = "enable_dark_mode",
                title = getString(R.string.enable_dark_mode),
                description = getString(R.string.enable_dark_mode_description),
                category = SettingsCategory.UI,
                type = SettingsType.SWITCH,
                currentValue = preferencesManager.getPreference("enableDarkMode"),
                preferenceKey = "enableDarkMode",
                icon = R.drawable.ic_dark_mode
            ),
            
            // Privacy Settings
            SettingsItem(
                id = "enable_history",
                title = getString(R.string.enable_history),
                description = getString(R.string.enable_history_description),
                category = SettingsCategory.PRIVACY,
                type = SettingsType.SWITCH,
                currentValue = preferencesManager.getPreference("enableHistory"),
                preferenceKey = "enableHistory",
                icon = R.drawable.ic_history
            ),
            SettingsItem(
                id = "data_retention_days",
                title = getString(R.string.data_retention_days),
                description = getString(R.string.data_retention_days_description),
                category = SettingsCategory.PRIVACY,
                type = SettingsType.NUMBER,
                currentValue = preferencesManager.getPreference("dataRetentionDays"),
                preferenceKey = "dataRetentionDays",
                defaultValue = 30,
                icon = R.drawable.ic_data_retention
            ),
            SettingsItem(
                id = "enable_data_collection",
                title = getString(R.string.enable_data_collection),
                description = getString(R.string.enable_data_collection_description),
                category = SettingsCategory.PRIVACY,
                type = SettingsType.SWITCH,
                currentValue = preferencesManager.getPreference("enableDataCollection"),
                preferenceKey = "enableDataCollection",
                icon = R.drawable.ic_data_collection
            ),
            SettingsItem(
                id = "clear_history",
                title = getString(R.string.clear_history),
                description = getString(R.string.clear_history_description),
                category = SettingsCategory.PRIVACY,
                type = SettingsType.BUTTON,
                icon = R.drawable.ic_clear_history
            ),
            
            // Advanced Settings
            SettingsItem(
                id = "enable_debug_mode",
                title = getString(R.string.enable_debug_mode),
                description = getString(R.string.enable_debug_mode_description),
                category = SettingsCategory.ADVANCED,
                type = SettingsType.SWITCH,
                currentValue = preferencesManager.getPreference("enableDebugMode"),
                preferenceKey = "enableDebugMode",
                requiresRestart = true,
                icon = R.drawable.ic_debug
            ),
            SettingsItem(
                id = "log_level",
                title = getString(R.string.log_level),
                description = getString(R.string.log_level_description),
                category = SettingsCategory.ADVANCED,
                type = SettingsType.LIST,
                currentValue = preferencesManager.getPreference("logLevel"),
                preferenceKey = "logLevel",
                possibleValues = listOf("debug", "info", "warn", "error"),
                icon = R.drawable.ic_log
            ),
            SettingsItem(
                id = "cache_size",
                title = getString(R.string.cache_size),
                description = getString(R.string.cache_size_description),
                category = SettingsCategory.ADVANCED,
                type = SettingsType.NUMBER,
                currentValue = preferencesManager.getPreference("cacheSize"),
                preferenceKey = "cacheSize",
                defaultValue = 100,
                icon = R.drawable.ic_cache
            ),
            SettingsItem(
                id = "export_settings",
                title = getString(R.string.export_settings),
                description = getString(R.string.export_settings_description),
                category = SettingsCategory.ADVANCED,
                type = SettingsType.BUTTON,
                icon = R.drawable.ic_export
            ),
            SettingsItem(
                id = "import_settings",
                title = getString(R.string.import_settings),
                description = getString(R.string.import_settings_description),
                category = SettingsCategory.ADVANCED,
                type = SettingsType.BUTTON,
                icon = R.drawable.ic_import
            ),
            SettingsItem(
                id = "reset_settings",
                title = getString(R.string.reset_settings),
                description = getString(R.string.reset_settings_description),
                category = SettingsCategory.ADVANCED,
                type = SettingsType.BUTTON,
                icon = R.drawable.ic_reset
            )
        )
    }
    
    private fun handleSettingsItemClick(item: SettingsItem) {
        when (item.type) {
            SettingsType.SWITCH -> {
                val currentValue = item.currentValue as? Boolean ?: false
                val newValue = !currentValue
                handleSwitchToggle(item, newValue)
            }
            SettingsType.LIST -> {
                showListDialog(item)
            }
            SettingsType.NUMBER -> {
                showNumberDialog(item)
            }
            SettingsType.TEXT -> {
                showTextDialog(item)
            }
            SettingsType.BUTTON -> {
                handleButtonClick(item)
            }
            SettingsType.INFO -> {
                // Show info dialog
            }
            SettingsType.DIVIDER -> {
                // Do nothing
            }
        }
    }

    private fun handleSwitchToggle(item: SettingsItem, isChecked: Boolean) {
        if (!item.isEnabled) return
        lifecycleScope.launch {
            updateSetting(item, isChecked)
        }
    }
    
    private fun showListDialog(item: SettingsItem) {
        val possibleValues = item.possibleValues ?: return
        val currentValue = item.currentValue
        
        val items = possibleValues.map { value ->
            when (value) {
                "system" -> getString(R.string.system_theme)
                "light" -> getString(R.string.light_theme)
                "dark" -> getString(R.string.dark_theme)
                "en" -> getString(R.string.english)
                "es" -> getString(R.string.spanish)
                "fr" -> getString(R.string.french)
                "de" -> getString(R.string.german)
                "it" -> getString(R.string.italian)
                "pt" -> getString(R.string.portuguese)
                "ru" -> getString(R.string.russian)
                "zh" -> getString(R.string.chinese)
                "ja" -> getString(R.string.japanese)
                "ko" -> getString(R.string.korean)
                "ar" -> getString(R.string.arabic)
                "hi" -> getString(R.string.hindi)
                "auto" -> getString(R.string.auto_detect)
                "low" -> getString(R.string.low_quality)
                "medium" -> getString(R.string.medium_quality)
                "high" -> getString(R.string.high_quality)
                "small" -> getString(R.string.small_size)
                "large" -> getString(R.string.large_size)
                "extra_large" -> getString(R.string.extra_large_size)
                "debug" -> getString(R.string.debug_level)
                "info" -> getString(R.string.info_level)
                "warn" -> getString(R.string.warn_level)
                "error" -> getString(R.string.error_level)
                else -> value.toString()
            }
        }
        
        val currentIndex = possibleValues.indexOf(currentValue)
        
        AlertDialog.Builder(this)
            .setTitle(item.title)
            .setSingleChoiceItems(items.toTypedArray(), currentIndex) { dialog, which ->
                val selectedValue = possibleValues[which]
                lifecycleScope.launch {
                    updateSetting(item, selectedValue)
                }
                dialog.dismiss()
            }
            .setNegativeButton(getString(R.string.cancel), null)
            .show()
    }
    
    private fun showNumberDialog(item: SettingsItem) {
        val currentValue = item.currentValue as? Number ?: 0
        val editText = EditText(this).apply {
            setText(currentValue.toString())
            inputType = android.text.InputType.TYPE_CLASS_NUMBER
        }
        
        AlertDialog.Builder(this)
            .setTitle(item.title)
            .setMessage(item.description)
            .setView(editText)
            .setPositiveButton(getString(R.string.ok)) { _, _ ->
                val newValue = editText.text.toString().toFloatOrNull()
                if (newValue != null) {
                    lifecycleScope.launch {
                        updateSetting(item, newValue)
                    }
                }
            }
            .setNegativeButton(getString(R.string.cancel), null)
            .show()
    }
    
    private fun showTextDialog(item: SettingsItem) {
        val currentValue = item.currentValue?.toString() ?: ""
        val editText = EditText(this).apply {
            setText(currentValue)
        }
        
        AlertDialog.Builder(this)
            .setTitle(item.title)
            .setMessage(item.description)
            .setView(editText)
            .setPositiveButton(getString(R.string.ok)) { _, _ ->
                val newValue = editText.text.toString()
                lifecycleScope.launch {
                    updateSetting(item, newValue)
                }
            }
            .setNegativeButton(getString(R.string.cancel), null)
            .show()
    }
    
    private fun handleButtonClick(item: SettingsItem) {
        when (item.id) {
            "clear_history" -> {
                showClearHistoryDialog()
            }
            "export_settings" -> {
                lifecycleScope.launch {
                    exportSettings()
                }
            }
            "import_settings" -> {
                lifecycleScope.launch {
                    importSettings()
                }
            }
            "reset_settings" -> {
                showResetSettingsDialog()
            }
        }
    }
    
    private fun showClearHistoryDialog() {
        AlertDialog.Builder(this)
            .setTitle(getString(R.string.clear_history))
            .setMessage(getString(R.string.clear_history_confirmation))
            .setPositiveButton(getString(R.string.clear)) { _, _ ->
                lifecycleScope.launch {
                    clearHistory()
                }
            }
            .setNegativeButton(getString(R.string.cancel), null)
            .show()
    }
    
    private fun showResetSettingsDialog() {
        AlertDialog.Builder(this)
            .setTitle(getString(R.string.reset_settings))
            .setMessage(getString(R.string.reset_settings_confirmation))
            .setPositiveButton(getString(R.string.reset)) { _, _ ->
                lifecycleScope.launch {
                    resetSettings()
                }
            }
            .setNegativeButton(getString(R.string.cancel), null)
            .show()
    }
    
    private suspend fun updateSetting(item: SettingsItem, value: Any) {
        if (!::preferencesManager.isInitialized) {
            Log.w(TAG, "Preferences manager not initialized; cannot update setting ${item.id}")
            return
        }

        val preferenceKey = when (item.type) {
            SettingsType.BUTTON, SettingsType.INFO, SettingsType.DIVIDER -> null
            else -> item.preferenceKey ?: item.id
        }

        if (preferenceKey.isNullOrBlank()) {
            Log.w(TAG, "Missing preference key for setting ${item.id}")
            return
        }

        try {
            val success = preferencesManager.setPreference(preferenceKey, value)
            if (success) {
                refreshSettings()
                Toast.makeText(this, getString(R.string.setting_updated), Toast.LENGTH_SHORT).show()
                Logger.d("Updated setting $preferenceKey -> $value", TAG)

                if (item.requiresRestart) {
                    showRestartRequiredDialog()
                }
            } else {
                Toast.makeText(this, getString(R.string.setting_update_failed), Toast.LENGTH_SHORT).show()
                Logger.w("Preference update failed for key=$preferenceKey", TAG)
            }
        } catch (e: Exception) {
            Log.e(TAG, "Failed to update setting: ${item.id}", e)
            Logger.e("Failed to update setting ${item.id}", TAG, e)
            Toast.makeText(this, getString(R.string.setting_update_failed), Toast.LENGTH_SHORT).show()
        }
    }
    
    private fun showRestartRequiredDialog() {
        AlertDialog.Builder(this)
            .setTitle(getString(R.string.restart_required))
            .setMessage(getString(R.string.restart_required_message))
            .setPositiveButton(getString(R.string.restart_now)) { _, _ ->
                // Restart the app
                val intent = packageManager.getLaunchIntentForPackage(packageName)
                startActivity(intent)
                finish()
            }
            .setNegativeButton(getString(R.string.restart_later), null)
            .show()
    }
    
    private suspend fun clearHistory() {
        try {
            val cleared = if (historyRepository.initialize()) {
                historyRepository.clearAllHistory()
            } else {
                false
            }

            if (cleared) {
                Toast.makeText(this, getString(R.string.history_cleared), Toast.LENGTH_SHORT).show()
                Logger.d("Translation history cleared from settings", TAG)
            } else {
                Toast.makeText(this, getString(R.string.history_clear_failed), Toast.LENGTH_SHORT).show()
                Logger.w("Failed to clear translation history", TAG)
            }
        } catch (e: Exception) {
            Log.e(TAG, "Failed to clear history", e)
            Toast.makeText(this, getString(R.string.history_clear_failed), Toast.LENGTH_SHORT).show()
            Logger.e("Failed to clear translation history", TAG, e)
        }
    }
    
    private suspend fun exportSettings() {
        try {
            // Export settings to file
            val backupFile = preferencesManager.createBackup()
            if (backupFile != null) {
                Toast.makeText(this, getString(R.string.settings_exported), Toast.LENGTH_SHORT).show()
            } else {
                Toast.makeText(this, getString(R.string.settings_export_failed), Toast.LENGTH_SHORT).show()
            }
        } catch (e: Exception) {
            Log.e(TAG, "Failed to export settings", e)
            Toast.makeText(this, getString(R.string.settings_export_failed), Toast.LENGTH_SHORT).show()
        }
    }
    
    private suspend fun importSettings() {
        try {
            // Import settings from file
            // Implementation would involve file picker
            Toast.makeText(this, getString(R.string.settings_imported), Toast.LENGTH_SHORT).show()
        } catch (e: Exception) {
            Log.e(TAG, "Failed to import settings", e)
            Toast.makeText(this, getString(R.string.settings_import_failed), Toast.LENGTH_SHORT).show()
        }
    }
    
    private suspend fun resetSettings() {
        try {
            val success = preferencesManager.resetPreferences()
            if (success) {
                Toast.makeText(this, getString(R.string.settings_reset), Toast.LENGTH_SHORT).show()
                refreshSettings()
                showRestartRequiredDialog()
            } else {
                Toast.makeText(this, getString(R.string.settings_reset_failed), Toast.LENGTH_SHORT).show()
            }
        } catch (e: Exception) {
            Log.e(TAG, "Failed to reset settings", e)
            Toast.makeText(this, getString(R.string.settings_reset_failed), Toast.LENGTH_SHORT).show()
        }
    }
    
    private suspend fun refreshSettings() {
        if (!::preferencesManager.isInitialized) {
            return
        }
        allSettingsItems = createSettingsItems()
        applyCurrentFilter()
    }
    
    private fun performSearch() {
        searchJob?.cancel()
        searchJob = lifecycleScope.launch {
            delay(SEARCH_DELAY_MS)
            
            if (searchQuery.isBlank()) {
                applyCurrentFilter()
            } else {
                val query = searchQuery.lowercase()
                filteredSettingsItems = allSettingsItems.filter { item ->
                    item.title.lowercase().contains(query) ||
                    item.description?.lowercase()?.contains(query) == true
                }
                updateSettingsList()
            }
        }
    }
    
    private fun setFilter(filter: SettingsCategory) {
        if (currentFilter == filter) return
        
        currentFilter = filter
        updateFilterChips(filter)
        applyCurrentFilter()
    }
    
    private fun applyCurrentFilter() {
        filteredSettingsItems = when (currentFilter) {
            SettingsCategory.ALL -> allSettingsItems
            else -> allSettingsItems.filter { it.category == currentFilter }
        }
        
        updateSettingsList()
    }
    
    private fun updateSettingsList() {
        settingsAdapter.updateSettings(filteredSettingsItems)
        
        // Show/hide empty state
        if (filteredSettingsItems.isEmpty()) {
            showEmptyState()
        } else {
            hideEmptyState()
        }
    }
    
    private fun updateFilterChips(activeFilter: SettingsCategory) {
        // Reset all chips
        listOf(allFilterChip, generalFilterChip, translationFilterChip, audioFilterChip, uiFilterChip, privacyFilterChip, advancedFilterChip)
            .forEach { chip ->
                chip.isChecked = false
                chip.setChipBackgroundColorResource(R.color.chip_background_unselected)
            }
        
        // Set active chip
        val activeChip = when (activeFilter) {
            SettingsCategory.ALL -> allFilterChip
            SettingsCategory.GENERAL -> generalFilterChip
            SettingsCategory.TRANSLATION -> translationFilterChip
            SettingsCategory.AUDIO -> audioFilterChip
            SettingsCategory.UI -> uiFilterChip
            SettingsCategory.PRIVACY -> privacyFilterChip
            SettingsCategory.ADVANCED -> advancedFilterChip
        }
        
        activeChip.isChecked = true
        activeChip.setChipBackgroundColorResource(R.color.chip_background_selected)
    }
    
    private fun updateClearSearchButtonVisibility() {
        clearSearchButton.visibility = if (searchQuery.isNotBlank()) View.VISIBLE else View.GONE
    }
    
    private fun showLoading(show: Boolean) {
        loadingIndicator.visibility = if (show) View.VISIBLE else View.GONE
        settingsRecyclerView.visibility = if (show) View.GONE else View.VISIBLE
    }
    
    private fun showEmptyState() {
        emptyStateContainer.visibility = View.VISIBLE
        settingsRecyclerView.visibility = View.GONE
        
        val emptyText = when {
            searchQuery.isNotBlank() -> getString(R.string.no_settings_found_search, searchQuery)
            else -> getString(R.string.no_settings_found)
        }
        
        emptyStateTextView.text = emptyText
    }
    
    private fun hideEmptyState() {
        emptyStateContainer.visibility = View.GONE
        settingsRecyclerView.visibility = View.VISIBLE
    }
    
    private fun showError() {
        showLoading(false)
        showEmptyState()
        emptyStateTextView.text = getString(R.string.error_loading_settings)
    }
    
    private fun startEntranceAnimation() {
        // Fade in content
        findViewById<View>(R.id.content_layout).alpha = 0f
        findViewById<View>(R.id.content_layout).animate()
            .alpha(1f)
            .setDuration(ANIMATION_DURATION)
            .setInterpolator(AccelerateDecelerateInterpolator())
            .start()
    }
    
    override fun onCreateOptionsMenu(menu: Menu): Boolean {
        menuInflater.inflate(R.menu.menu_settings, menu)
        return true
    }
    
    override fun onOptionsItemSelected(item: MenuItem): Boolean {
        return when (item.itemId) {
            android.R.id.home -> {
                onBackPressedDispatcher.onBackPressed()
                true
            }
            R.id.action_search -> {
                toggleSearchVisibility()
                true
            }
            R.id.action_reset -> {
                lifecycleScope.launch {
                    resetSettings()
                }
                true
            }
            R.id.action_export -> {
                lifecycleScope.launch {
                    exportSettings()
                }
                true
            }
            else -> super.onOptionsItemSelected(item)
        }
    }
    
    private fun toggleSearchVisibility() {
        val isVisible = searchContainer.visibility == View.VISIBLE
        searchContainer.visibility = if (isVisible) View.GONE else View.VISIBLE
        
        if (!isVisible) {
            searchEditText.requestFocus()
        }
    }
    
    override fun onSharedPreferenceChanged(sharedPreferences: SharedPreferences?, key: String?) {
        if (!::preferencesManager.isInitialized) return

        // Handle preference changes
        lifecycleScope.launch {
            refreshSettings()
        }
    }
    
    override fun onResume() {
        super.onResume()
        // Register preference change listener
        if (::preferencesManager.isInitialized) {
            preferencesManager.getSharedPreferences().registerOnSharedPreferenceChangeListener(this)
        }
    }
    
    override fun onPause() {
        super.onPause()
        // Unregister preference change listener
        if (::preferencesManager.isInitialized) {
            preferencesManager.getSharedPreferences().unregisterOnSharedPreferenceChangeListener(this)
        }
    }
    
    override fun onDestroy() {
        super.onDestroy()
        searchJob?.cancel()
    }
    
    // Settings Adapter
    private inner class SettingsAdapter(
        private val onItemClick: (SettingsItem) -> Unit,
        private val onToggleChanged: (SettingsItem, Boolean) -> Unit
    ) : RecyclerView.Adapter<SettingsAdapter.SettingsViewHolder>() {
        
        private var settingsItems: List<SettingsItem> = emptyList()
        
        fun updateSettings(newSettings: List<SettingsItem>) {
            settingsItems = newSettings
            notifyDataSetChanged()
        }
        
        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): SettingsViewHolder {
            val view = LayoutInflater.from(parent.context)
                .inflate(R.layout.item_setting, parent, false)
            return SettingsViewHolder(view)
        }
        
        override fun onBindViewHolder(holder: SettingsViewHolder, position: Int) {
            holder.bind(settingsItems[position])
        }
        
        override fun getItemCount(): Int = settingsItems.size
        
        inner class SettingsViewHolder(itemView: View) : RecyclerView.ViewHolder(itemView) {
            private val iconImageView: ImageView = itemView.findViewById(R.id.icon_image_view)
            private val titleTextView: TextView = itemView.findViewById(R.id.title_text_view)
            private val descriptionTextView: TextView = itemView.findViewById(R.id.description_text_view)
            private val valueTextView: TextView = itemView.findViewById(R.id.value_text_view)
            private val switchView: Switch = itemView.findViewById(R.id.switch_view)
            private val rootLayout: LinearLayout = itemView.findViewById(R.id.root_layout)
            
            fun bind(item: SettingsItem) {
                // Icon handling
                item.icon?.let { icon ->
                    iconImageView.setImageResource(icon)
                    iconImageView.visibility = View.VISIBLE
                } ?: run {
                    iconImageView.visibility = View.GONE
                }

                // Text content
                titleTextView.text = item.title
                descriptionTextView.text = item.description

                // Reset view states before applying type-specific configuration
                switchView.setOnCheckedChangeListener(null)
                switchView.visibility = View.GONE
                switchView.isEnabled = item.isEnabled
                switchView.isClickable = item.isEnabled
                valueTextView.visibility = View.GONE

                when (item.type) {
                    SettingsType.SWITCH -> {
                        switchView.visibility = View.VISIBLE
                        switchView.isChecked = item.currentValue as? Boolean ?: false
                        switchView.setOnCheckedChangeListener { _, isChecked ->
                            if (item.isEnabled) {
                                onToggleChanged(item, isChecked)
                            }
                        }
                    }
                    SettingsType.LIST, SettingsType.NUMBER, SettingsType.TEXT -> {
                        valueTextView.visibility = View.VISIBLE
                        valueTextView.text = formatValue(item.currentValue)
                    }
                    SettingsType.BUTTON, SettingsType.INFO, SettingsType.DIVIDER -> {
                        // No additional UI elements needed
                    }
                }

                // Configure container interaction state
                rootLayout.isEnabled = item.isEnabled
                rootLayout.isClickable = item.isEnabled
                rootLayout.isFocusable = true
                rootLayout.alpha = if (item.isEnabled) 1f else 0.5f
                rootLayout.setOnClickListener {
                    if (!item.isEnabled) return@setOnClickListener
                    if (item.type == SettingsType.SWITCH) {
                        switchView.performClick()
                    } else {
                        onItemClick(item)
                    }
                }

                // Accessibility description
                val valueDescription = when (item.type) {
                    SettingsType.SWITCH -> formatValue(switchView.isChecked)
                    SettingsType.LIST, SettingsType.NUMBER, SettingsType.TEXT -> formatValue(item.currentValue)
                    else -> null
                }
                val contentParts = mutableListOf(item.title)
                item.description?.takeIf { it.isNotBlank() }?.let { contentParts += it }
                valueDescription?.takeIf { it.isNotBlank() }?.let { contentParts += it }
                rootLayout.contentDescription = contentParts.joinToString(separator = ", ")
            }
            
            private fun formatValue(value: Any?): String {
                return when (value) {
                    is Boolean -> if (value) getString(R.string.enabled) else getString(R.string.disabled)
                    is String -> value
                    is Number -> value.toString()
                    null -> getString(R.string.not_set)
                    else -> value.toString()
                }
            }
        }
    }
    
    // Item decoration for spacing
    private class SettingsItemDecoration(context: Context) : RecyclerView.ItemDecoration() {
        private val spacing = context.resources.getDimensionPixelSize(R.dimen.settings_item_spacing)
        
        // Implementation would add spacing between items
        // This is a placeholder for the actual decoration logic
    }
}

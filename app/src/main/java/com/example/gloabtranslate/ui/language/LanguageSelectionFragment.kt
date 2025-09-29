package com.example.gloabtranslate.ui.language

import android.animation.Animator
import android.animation.AnimatorListenerAdapter
import android.animation.ValueAnimator
import android.content.Context
import android.os.Bundle
import android.text.Editable
import android.text.TextWatcher
import android.util.Log
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.view.animation.AccelerateDecelerateInterpolator
import android.view.animation.AnimationUtils
import android.widget.*
import androidx.core.content.ContextCompat
import androidx.fragment.app.Fragment
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.example.gloabtranslate.R
import com.example.gloabtranslate.core.data.config.LanguagePairConfig
import com.google.android.material.chip.Chip
import dagger.android.support.AndroidSupportInjection
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import java.util.*

/**
 * Fragment for language selection with search, filtering, categorization,
 * and accessibility support. Provides comprehensive language selection interface.
 */
class LanguageSelectionFragment : Fragment() {
    
    override fun onAttach(context: android.content.Context) {
        AndroidSupportInjection.inject(this)
        super.onAttach(context)
    }
    
    companion object {
        private const val TAG = "LanguageSelectionFragment"
        private const val ARG_SELECTION_TYPE = "selection_type"
        private const val ARG_CURRENT_LANGUAGE = "current_language"
        private const val ARG_EXCLUDED_LANGUAGES = "excluded_languages"
        private const val ARG_ALLOW_AUTO_DETECT = "allow_auto_detect"
        
        const val SELECTION_TYPE_SOURCE = "source"
        const val SELECTION_TYPE_TARGET = "target"
        
        private const val ANIMATION_DURATION = 250L
        private const val SEARCH_DELAY_MS = 300L
        
        fun newInstance(
            selectionType: String,
            currentLanguage: String? = null,
            excludedLanguages: List<String> = emptyList(),
            allowAutoDetect: Boolean = false
        ): LanguageSelectionFragment {
            return LanguageSelectionFragment().apply {
                arguments = Bundle().apply {
                    putString(ARG_SELECTION_TYPE, selectionType)
                    putString(ARG_CURRENT_LANGUAGE, currentLanguage)
                    putStringArrayList(ARG_EXCLUDED_LANGUAGES, ArrayList(excludedLanguages))
                    putBoolean(ARG_ALLOW_AUTO_DETECT, allowAutoDetect)
                }
            }
        }
    }
    
    // UI Components
    private lateinit var rootLayout: LinearLayout
    private lateinit var headerContainer: LinearLayout
    private lateinit var titleTextView: TextView
    private lateinit var subtitleTextView: TextView
    private lateinit var closeButton: ImageButton
    private lateinit var searchContainer: LinearLayout
    private lateinit var searchEditText: EditText
    private lateinit var clearSearchButton: ImageButton
    private lateinit var filterContainer: LinearLayout
    private lateinit var filterChipGroup: LinearLayout
    private lateinit var allFilterChip: Chip
    private lateinit var recentFilterChip: Chip
    private lateinit var favoritesFilterChip: Chip
    private lateinit var onDeviceFilterChip: Chip
    private lateinit var cloudFilterChip: Chip
    private lateinit var languagesRecyclerView: RecyclerView
    private lateinit var emptyStateContainer: LinearLayout
    private lateinit var emptyStateImageView: ImageView
    private lateinit var emptyStateTextView: TextView
    private lateinit var loadingIndicator: ProgressBar
    
    // Adapter and data
    private lateinit var languageAdapter: LanguageAdapter
    private var allLanguages: List<LanguageItem> = emptyList()
    private var filteredLanguages: List<LanguageItem> = emptyList()
    private var recentLanguages: List<String> = emptyList()
    private var favoriteLanguages: List<String> = emptyList()
    
    // State management
    private var currentFilter: FilterType = FilterType.ALL
    private var searchQuery: String = ""
    private var isSearching = false
    private var searchJob: Job? = null
    
    // Configuration
    private var selectionType: String = SELECTION_TYPE_SOURCE
    private var currentLanguage: String? = null
    private var excludedLanguages: List<String> = emptyList()
    private var allowAutoDetect: Boolean = false
    
    // Listeners
    private var onLanguageSelectedListener: ((LanguageItem) -> Unit)? = null
    private var onCloseListener: (() -> Unit)? = null
    
    /**
     * Language item data class
     */
    data class LanguageItem(
        val code: String,
        val name: String,
        val nativeName: String,
        val flag: String,
        val isSupported: Boolean = true,
        val isOnDeviceSupported: Boolean = false,
        val isCloudSupported: Boolean = true,
        val isRecent: Boolean = false,
        val isFavorite: Boolean = false,
        val isAutoDetect: Boolean = false,
        val usageCount: Long = 0,
        val lastUsed: Long = 0L,
        val confidence: Float = 1.0f
    )
    
    /**
     * Filter types for language selection
     */
    enum class FilterType {
        ALL,
        RECENT,
        FAVORITES,
        ON_DEVICE,
        CLOUD
    }
    
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        
        // Extract arguments
        selectionType = arguments?.getString(ARG_SELECTION_TYPE) ?: SELECTION_TYPE_SOURCE
        currentLanguage = arguments?.getString(ARG_CURRENT_LANGUAGE)
        excludedLanguages = arguments?.getStringArrayList(ARG_EXCLUDED_LANGUAGES) ?: emptyList()
        allowAutoDetect = arguments?.getBoolean(ARG_ALLOW_AUTO_DETECT) ?: false
    }
    
    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View? {
        return inflater.inflate(R.layout.fragment_language_selection, container, false)
    }
    
    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        
        initializeViews(view)
        setupListeners()
        setupRecyclerView()
        setupSearch()
        setupFilters()
        setupAccessibility()
        
        // Load languages
        lifecycleScope.launch {
            loadLanguages()
        }
        
        // Start entrance animation
        startEntranceAnimation()
    }
    
    private fun initializeViews(view: View) {
        // Main containers
        rootLayout = view.findViewById(R.id.root_layout)
        headerContainer = view.findViewById(R.id.header_container)
        searchContainer = view.findViewById(R.id.search_container)
        filterContainer = view.findViewById(R.id.filter_container)
        emptyStateContainer = view.findViewById(R.id.empty_state_container)
        
        // Header components
        titleTextView = view.findViewById(R.id.title_text_view)
        subtitleTextView = view.findViewById(R.id.subtitle_text_view)
        closeButton = view.findViewById(R.id.close_button)
        
        // Search components
        searchEditText = view.findViewById(R.id.search_edit_text)
        clearSearchButton = view.findViewById(R.id.clear_search_button)
        
        // Filter components
        filterChipGroup = view.findViewById(R.id.filter_chip_group)
        allFilterChip = view.findViewById(R.id.all_filter_chip)
        recentFilterChip = view.findViewById(R.id.recent_filter_chip)
        favoritesFilterChip = view.findViewById(R.id.favorites_filter_chip)
        onDeviceFilterChip = view.findViewById(R.id.on_device_filter_chip)
        cloudFilterChip = view.findViewById(R.id.cloud_filter_chip)
        
        // List components
        languagesRecyclerView = view.findViewById(R.id.languages_recycler_view)
        
        // Empty state components
        emptyStateImageView = view.findViewById(R.id.empty_state_image_view)
        emptyStateTextView = view.findViewById(R.id.empty_state_text_view)
        loadingIndicator = view.findViewById(R.id.loading_indicator)
    }
    
    private fun setupListeners() {
        // Close button
        closeButton.setOnClickListener {
            onCloseListener?.invoke()
            animateExit()
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
        
        clearSearchButton.setOnClickListener {
            searchEditText.text?.clear()
            clearSearchButton.visibility = View.GONE
        }
        
        // Filter chips
        allFilterChip.setOnClickListener { setFilter(FilterType.ALL) }
        recentFilterChip.setOnClickListener { setFilter(FilterType.RECENT) }
        favoritesFilterChip.setOnClickListener { setFilter(FilterType.FAVORITES) }
        onDeviceFilterChip.setOnClickListener { setFilter(FilterType.ON_DEVICE) }
        cloudFilterChip.setOnClickListener { setFilter(FilterType.CLOUD) }
    }
    
    private fun setupRecyclerView() {
        languageAdapter = LanguageAdapter { language ->
            onLanguageSelectedListener?.invoke(language)
            animateLanguageSelection(language)
        }
        
        languagesRecyclerView.apply {
            layoutManager = LinearLayoutManager(context)
            adapter = languageAdapter
            addItemDecoration(LanguageItemDecoration(requireContext()))
        }
    }
    
    private fun setupSearch() {
        // Set up search hint based on selection type
        val searchHint = if (selectionType == SELECTION_TYPE_SOURCE) {
            getString(R.string.search_source_languages)
        } else {
            getString(R.string.search_target_languages)
        }
        searchEditText.hint = searchHint
        
        // Set up search icon
        searchEditText.setCompoundDrawablesWithIntrinsicBounds(
            ContextCompat.getDrawable(requireContext(), R.drawable.ic_search),
            null, null, null
        )
    }
    
    private fun setupFilters() {
        // Set initial filter state
        updateFilterChips(FilterType.ALL)
        
        // Hide filters that might not be relevant
        if (!allowAutoDetect) {
            // Hide certain filters if auto-detect is not allowed
        }
    }
    
    private fun setupAccessibility() {
        // Set content descriptions
        closeButton.contentDescription = getString(R.string.close_language_selection)
        clearSearchButton.contentDescription = getString(R.string.clear_search)
        
        // Set accessibility hints
        searchEditText.hint = getString(R.string.search_languages_hint)
        
        // Set up filter chip accessibility
        allFilterChip.contentDescription = getString(R.string.show_all_languages)
        recentFilterChip.contentDescription = getString(R.string.show_recent_languages)
        favoritesFilterChip.contentDescription = getString(R.string.show_favorite_languages)
        onDeviceFilterChip.contentDescription = getString(R.string.show_on_device_languages)
        cloudFilterChip.contentDescription = getString(R.string.show_cloud_languages)
    }
    
    private suspend fun loadLanguages() {
        try {
            showLoading(true)
            
            // Load supported languages from configuration
            val languageConfig = LanguagePairConfig.getInstance(requireContext())
            val supportedLanguages = languageConfig.getSupportedLanguages()
            
            // Convert to LanguageItem objects
            allLanguages = supportedLanguages.map { lang ->
                LanguageItem(
                    code = lang.code,
                    name = lang.name,
                    nativeName = lang.nativeName,
                    flag = getLanguageFlag(lang.code),
                    isSupported = lang.isSupported,
                    isOnDeviceSupported = lang.isOnDeviceSupported,
                    isCloudSupported = lang.isCloudSupported,
                    isRecent = recentLanguages.contains(lang.code),
                    isFavorite = favoriteLanguages.contains(lang.code),
                    isAutoDetect = lang.code == "auto",
                    usageCount = 0L, // Would be loaded from usage statistics
                    lastUsed = 0L,
                    confidence = lang.confidence
                )
            }.filter { language ->
                // Filter out excluded languages
                !excludedLanguages.contains(language.code)
            }.filter { language ->
                // Filter out auto-detect if not allowed
                if (!allowAutoDetect) language.code != "auto" else true
            }.sortedWith(compareBy<LanguageItem> { !it.isAutoDetect }
                .thenBy { !it.isRecent }
                .thenBy { !it.isFavorite }
                .thenBy { it.name }
            )
            
            // Apply current filter
            applyCurrentFilter()
            
            showLoading(false)
            
        } catch (e: Exception) {
            Log.e(TAG, "Failed to load languages", e)
            showError()
        }
    }
    
    private fun performSearch() {
        searchJob?.cancel()
        searchJob = lifecycleScope.launch {
            delay(SEARCH_DELAY_MS)
            
            if (searchQuery.isBlank()) {
                applyCurrentFilter()
            } else {
                val query = searchQuery.lowercase()
                filteredLanguages = allLanguages.filter { language ->
                    language.name.lowercase().contains(query) ||
                    language.nativeName.lowercase().contains(query) ||
                    language.code.lowercase().contains(query)
                }
                updateLanguagesList()
            }
        }
    }
    
    private fun setFilter(filter: FilterType) {
        if (currentFilter == filter) return
        
        currentFilter = filter
        updateFilterChips(filter)
        applyCurrentFilter()
    }
    
    private fun applyCurrentFilter() {
        filteredLanguages = when (currentFilter) {
            FilterType.ALL -> allLanguages
            FilterType.RECENT -> allLanguages.filter { it.isRecent }
            FilterType.FAVORITES -> allLanguages.filter { it.isFavorite }
            FilterType.ON_DEVICE -> allLanguages.filter { it.isOnDeviceSupported }
            FilterType.CLOUD -> allLanguages.filter { it.isCloudSupported }
        }
        
        updateLanguagesList()
    }
    
    private fun updateLanguagesList() {
        languageAdapter.updateLanguages(filteredLanguages, currentLanguage)
        
        // Show/hide empty state
        if (filteredLanguages.isEmpty()) {
            showEmptyState()
        } else {
            hideEmptyState()
        }
    }
    
    private fun updateFilterChips(activeFilter: FilterType) {
        // Reset all chips
        listOf(allFilterChip, recentFilterChip, favoritesFilterChip, onDeviceFilterChip, cloudFilterChip)
            .forEach { chip ->
                chip.isChecked = false
                chip.setChipBackgroundColorResource(R.color.chip_background_unselected)
            }
        
        // Set active chip
        val activeChip = when (activeFilter) {
            FilterType.ALL -> allFilterChip
            FilterType.RECENT -> recentFilterChip
            FilterType.FAVORITES -> favoritesFilterChip
            FilterType.ON_DEVICE -> onDeviceFilterChip
            FilterType.CLOUD -> cloudFilterChip
        }
        
        activeChip.isChecked = true
        activeChip.setChipBackgroundColorResource(R.color.chip_background_selected)
    }
    
    private fun updateClearSearchButtonVisibility() {
        clearSearchButton.visibility = if (searchQuery.isNotBlank()) View.VISIBLE else View.GONE
    }
    
    private fun showLoading(show: Boolean) {
        loadingIndicator.visibility = if (show) View.VISIBLE else View.GONE
        languagesRecyclerView.visibility = if (show) View.GONE else View.VISIBLE
    }
    
    private fun showEmptyState() {
        emptyStateContainer.visibility = View.VISIBLE
        languagesRecyclerView.visibility = View.GONE
        
        val emptyText = when {
            searchQuery.isNotBlank() -> getString(R.string.no_languages_found_search, searchQuery)
            currentFilter == FilterType.RECENT -> getString(R.string.no_recent_languages)
            currentFilter == FilterType.FAVORITES -> getString(R.string.no_favorite_languages)
            currentFilter == FilterType.ON_DEVICE -> getString(R.string.no_on_device_languages)
            currentFilter == FilterType.CLOUD -> getString(R.string.no_cloud_languages)
            else -> getString(R.string.no_languages_available)
        }
        
        emptyStateTextView.text = emptyText
    }
    
    private fun hideEmptyState() {
        emptyStateContainer.visibility = View.GONE
        languagesRecyclerView.visibility = View.VISIBLE
    }
    
    private fun showError() {
        showLoading(false)
        showEmptyState()
        emptyStateTextView.text = getString(R.string.error_loading_languages)
    }
    
    private fun startEntranceAnimation() {
        // Fade in root layout
        rootLayout.alpha = 0f
        rootLayout.animate()
            .alpha(1f)
            .setDuration(ANIMATION_DURATION)
            .setInterpolator(AccelerateDecelerateInterpolator())
            .start()
        
        // Slide up header
        headerContainer.translationY = -100f
        headerContainer.animate()
            .translationY(0f)
            .setDuration(ANIMATION_DURATION)
            .setStartDelay(50L)
            .setInterpolator(AccelerateDecelerateInterpolator())
            .start()
        
        // Slide up search
        searchContainer.translationY = -50f
        searchContainer.animate()
            .translationY(0f)
            .setDuration(ANIMATION_DURATION)
            .setStartDelay(100L)
            .setInterpolator(AccelerateDecelerateInterpolator())
            .start()
        
        // Slide up filters
        filterContainer.translationY = -50f
        filterContainer.animate()
            .translationY(0f)
            .setDuration(ANIMATION_DURATION)
            .setStartDelay(150L)
            .setInterpolator(AccelerateDecelerateInterpolator())
            .start()
    }
    
    private fun animateExit() {
        rootLayout.animate()
            .alpha(0f)
            .setDuration(ANIMATION_DURATION)
            .setInterpolator(AccelerateDecelerateInterpolator())
            .withEndAction {
                parentFragmentManager.popBackStack()
            }
            .start()
    }
    
    private fun animateLanguageSelection(language: LanguageItem) {
        // Find the view holder for the selected language
        val position = filteredLanguages.indexOf(language)
        if (position >= 0) {
            val viewHolder = languagesRecyclerView.findViewHolderForAdapterPosition(position)
            viewHolder?.itemView?.let { itemView ->
                // Scale animation
                itemView.animate()
                    .scaleX(0.95f)
                    .scaleY(0.95f)
                    .setDuration(100L)
                    .withEndAction {
                        itemView.animate()
                            .scaleX(1f)
                            .scaleY(1f)
                            .setDuration(100L)
                            .start()
                    }
                    .start()
            }
        }
    }
    
    private fun getLanguageFlag(languageCode: String): String {
        return when (languageCode.lowercase()) {
            "en" -> "🇺🇸"
            "es" -> "🇪🇸"
            "fr" -> "🇫🇷"
            "de" -> "🇩🇪"
            "it" -> "🇮🇹"
            "pt" -> "🇵🇹"
            "ru" -> "🇷🇺"
            "zh" -> "🇨🇳"
            "ja" -> "🇯🇵"
            "ko" -> "🇰🇷"
            "ar" -> "🇸🇦"
            "hi" -> "🇮🇳"
            "auto" -> "🌐"
            else -> "🌐"
        }
    }
    
    // Public methods for external interaction
    
    fun setOnLanguageSelectedListener(listener: (LanguageItem) -> Unit) {
        onLanguageSelectedListener = listener
    }
    
    fun setOnCloseListener(listener: () -> Unit) {
        onCloseListener = listener
    }
    
    fun updateRecentLanguages(recent: List<String>) {
        recentLanguages = recent
        // Reload languages if needed
        lifecycleScope.launch {
            loadLanguages()
        }
    }
    
    fun updateFavoriteLanguages(favorites: List<String>) {
        favoriteLanguages = favorites
        // Reload languages if needed
        lifecycleScope.launch {
            loadLanguages()
        }
    }
    
    fun setSearchQuery(query: String) {
        searchEditText.setText(query)
    }
    
    
    override fun onDestroy() {
        super.onDestroy()
        searchJob?.cancel()
    }
    
    // Language Adapter
    private inner class LanguageAdapter(
        private val onLanguageClick: (LanguageItem) -> Unit
    ) : RecyclerView.Adapter<LanguageAdapter.LanguageViewHolder>() {
        
        private var languages: List<LanguageItem> = emptyList()
        private var selectedLanguage: String? = null
        
        fun updateLanguages(newLanguages: List<LanguageItem>, selected: String? = null) {
            languages = newLanguages
            selectedLanguage = selected
            notifyDataSetChanged()
        }
        
        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): LanguageViewHolder {
            val view = LayoutInflater.from(parent.context)
                .inflate(R.layout.item_language_selection, parent, false)
            return LanguageViewHolder(view)
        }
        
        override fun onBindViewHolder(holder: LanguageViewHolder, position: Int) {
            holder.bind(languages[position])
        }
        
        override fun getItemCount(): Int = languages.size
        
        inner class LanguageViewHolder(itemView: View) : RecyclerView.ViewHolder(itemView) {
            private val flagTextView: TextView = itemView.findViewById(R.id.flag_text_view)
            private val nameTextView: TextView = itemView.findViewById(R.id.name_text_view)
            private val nativeNameTextView: TextView = itemView.findViewById(R.id.native_name_text_view)
            private val codeTextView: TextView = itemView.findViewById(R.id.code_text_view)
            private val supportIndicator: ImageView = itemView.findViewById(R.id.support_indicator)
            private val favoriteIndicator: ImageView = itemView.findViewById(R.id.favorite_indicator)
            private val selectedIndicator: ImageView = itemView.findViewById(R.id.selected_indicator)
            private val rootLayout: LinearLayout = itemView.findViewById(R.id.root_layout)
            
            fun bind(language: LanguageItem) {
                // Set text content
                flagTextView.text = language.flag
                nameTextView.text = language.name
                nativeNameTextView.text = language.nativeName
                codeTextView.text = language.code.uppercase()
                
                // Set indicators
                supportIndicator.visibility = if (language.isOnDeviceSupported) View.VISIBLE else View.GONE
                favoriteIndicator.visibility = if (language.isFavorite) View.VISIBLE else View.GONE
                selectedIndicator.visibility = if (language.code == selectedLanguage) View.VISIBLE else View.GONE
                
                // Set click listener
                rootLayout.setOnClickListener {
                    onLanguageClick(language)
                }
                
                // Set accessibility
                rootLayout.contentDescription = "${language.name} (${language.nativeName})"
                rootLayout.isFocusable = true
            }
        }
    }
    
    // Item decoration for spacing
    private class LanguageItemDecoration(context: Context) : RecyclerView.ItemDecoration() {
        private val spacing = context.resources.getDimensionPixelSize(R.dimen.language_item_spacing)
        
        // Implementation would add spacing between items
        // This is a placeholder for the actual decoration logic
    }
}

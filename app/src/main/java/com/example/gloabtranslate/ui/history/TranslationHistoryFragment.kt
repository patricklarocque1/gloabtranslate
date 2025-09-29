package com.example.gloabtranslate.ui.history

import android.animation.Animator
import android.animation.AnimatorListenerAdapter
import android.animation.ValueAnimator
import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.text.Editable
import android.text.TextWatcher
import android.util.Log
import android.view.LayoutInflater
import android.view.Menu
import android.view.MenuInflater
import android.view.MenuItem
import android.view.View
import android.view.ViewGroup
import android.view.animation.AccelerateDecelerateInterpolator
import android.widget.*
import androidx.appcompat.app.AlertDialog
import androidx.core.content.ContextCompat
import androidx.core.view.MenuHost
import androidx.core.view.MenuProvider
import androidx.fragment.app.Fragment
import androidx.activity.OnBackPressedCallback
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.navigation.fragment.findNavController
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.example.gloabtranslate.R
import com.example.gloabtranslate.core.data.repository.TranslationHistoryRepository
import com.example.gloabtranslate.core.data.models.TranslationResult
import com.google.android.material.chip.Chip
import dagger.android.support.AndroidSupportInjection
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*

/**
 * Fragment for displaying translation history with search, filtering, sorting,
 * analytics, and export capabilities.
 */
class TranslationHistoryFragment : Fragment() {
    
    override fun onAttach(context: android.content.Context) {
        AndroidSupportInjection.inject(this)
        super.onAttach(context)
    }
    
    companion object {
        private const val TAG = "TranslationHistoryFragment"
        private const val ANIMATION_DURATION = 250L
        private const val SEARCH_DELAY_MS = 300L
        private const val MAX_HISTORY_ITEMS = 1000
        
        fun newInstance(): TranslationHistoryFragment {
            return TranslationHistoryFragment()
        }
    }
    
    // UI Components
    private lateinit var rootLayout: LinearLayout
    private lateinit var toolbar: androidx.appcompat.widget.Toolbar
    private lateinit var searchContainer: LinearLayout
    private lateinit var searchEditText: EditText
    private lateinit var clearSearchButton: ImageButton
    private lateinit var filterContainer: LinearLayout
    private lateinit var filterChipGroup: LinearLayout
    private lateinit var allFilterChip: Chip
    private lateinit var favoritesFilterChip: Chip
    private lateinit var successfulFilterChip: Chip
    private lateinit var onDeviceFilterChip: Chip
    private lateinit var cloudFilterChip: Chip
    // private var sortContainer: LinearLayout? = null // Not used in current layout
    private lateinit var sortSpinner: Spinner
    private lateinit var historyRecyclerView: RecyclerView
    private lateinit var emptyStateContainer: LinearLayout
    private lateinit var emptyStateImageView: ImageView
    private lateinit var emptyStateTextView: TextView
    private lateinit var emptyStateButton: Button
    private lateinit var loadingIndicator: ProgressBar
    private lateinit var statsContainer: LinearLayout
    private lateinit var totalTranslationsTextView: TextView
    private lateinit var successRateTextView: TextView
    private lateinit var averageConfidenceTextView: TextView
    // Adapter and data
    private lateinit var historyAdapter: TranslationHistoryAdapter
    private var allHistoryItems: List<HistoryItem> = emptyList()
    private var filteredHistoryItems: List<HistoryItem> = emptyList()
    
    // State management
    private var currentFilter: HistoryFilter = HistoryFilter.ALL
    private var currentSort: HistorySort = HistorySort.DATE_DESC
    private var searchQuery: String = ""
    private var isSearching = false
    private var searchJob: Job? = null
    private var isSelectionMode = false
    private var selectedItems = mutableSetOf<String>()
    
    // Repository
    private lateinit var historyRepository: TranslationHistoryRepository
    
    // Listeners
    private var onHistoryItemClickListener: ((HistoryItem) -> Unit)? = null
    private var onHistoryItemLongClickListener: ((HistoryItem) -> Unit)? = null
    private var onExportClickListener: (() -> Unit)? = null
    private var onClearHistoryClickListener: (() -> Unit)? = null
    
    /**
     * History item wrapper
     */
    data class HistoryItem(
        val id: String,
        val originalText: String,
        val translatedText: String?,
        val sourceLanguage: String,
        val targetLanguage: String,
        val sourceLanguageName: String,
        val targetLanguageName: String,
        val confidence: Float?,
        val isPartial: Boolean,
        val isOnDevice: Boolean,
        val timestamp: Long,
        val duration: Long,
        val success: Boolean,
        val error: String?,
        val isFavorite: Boolean,
        val tags: List<String>,
        val metadata: Map<String, String>
    )
    
    /**
     * History filter types
     */
    enum class HistoryFilter {
        ALL,
        FAVORITES,
        SUCCESSFUL,
        ON_DEVICE,
        CLOUD
    }
    
    /**
     * History sort options
     */
    enum class HistorySort(val displayName: String) {
        DATE_DESC("Most Recent"),
        DATE_ASC("Oldest First"),
        CONFIDENCE_DESC("Highest Confidence"),
        CONFIDENCE_ASC("Lowest Confidence"),
        DURATION_DESC("Longest Duration"),
        DURATION_ASC("Shortest Duration"),
        SOURCE_LANG("Source Language"),
        TARGET_LANG("Target Language")
    }
    
    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View? {
        return inflater.inflate(R.layout.fragment_translation_history, container, false)
    }
    
    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        
        initializeViews(view)
        setupToolbar()
        setupListeners()
        setupRecyclerView()
        setupSearch()
        setupFilters()
        setupSort()
        setupAccessibility()
        
        lifecycleScope.launch {
            initializeRepository()
            loadHistory()
        }
        
        // Start entrance animation
        startEntranceAnimation()

        setupMenu()
        setupBackPressHandling()
    }
    
    private fun initializeViews(view: View) {
        // Main containers
        rootLayout = view.findViewById(R.id.root_layout)
        toolbar = view.findViewById(R.id.toolbar)
        searchContainer = view.findViewById(R.id.search_container)
        filterContainer = view.findViewById(R.id.filter_container)
        // Note: sort_container may not exist in the layout, so we make it nullable
        // sortContainer = try {
        //     view.findViewById<LinearLayout>(R.id.sort_container)
        // } catch (e: Exception) {
        //     null
        // } 
        emptyStateContainer = view.findViewById(R.id.empty_state_container)
        statsContainer = view.findViewById(R.id.stats_container)
        
        // Header components
        toolbar = view.findViewById(R.id.toolbar)
        
        // Search components
        searchEditText = view.findViewById(R.id.search_edit_text)
        clearSearchButton = view.findViewById(R.id.clear_search_button)
        
        // Filter components
        filterChipGroup = view.findViewById(R.id.filter_chip_group)
        allFilterChip = view.findViewById(R.id.all_filter_chip)
        favoritesFilterChip = view.findViewById(R.id.favorites_filter_chip)
        successfulFilterChip = view.findViewById(R.id.successful_filter_chip)
        onDeviceFilterChip = view.findViewById(R.id.on_device_filter_chip)
        cloudFilterChip = view.findViewById(R.id.cloud_filter_chip)
        
        // Sort components
        sortSpinner = view.findViewById(R.id.sort_spinner)
        
        // List components
        historyRecyclerView = view.findViewById(R.id.history_recycler_view)
        
        // Empty state components
        emptyStateImageView = view.findViewById(R.id.empty_state_image_view)
        emptyStateTextView = view.findViewById(R.id.empty_state_text_view)
        emptyStateButton = view.findViewById(R.id.empty_state_button)
        loadingIndicator = view.findViewById(R.id.loading_indicator)
        
        // Stats components
        totalTranslationsTextView = view.findViewById(R.id.total_translations_text_view)
        successRateTextView = view.findViewById(R.id.success_rate_text_view)
        averageConfidenceTextView = view.findViewById(R.id.average_confidence_text_view)
    }
    
    private fun setupToolbar() {
        (activity as? androidx.appcompat.app.AppCompatActivity)?.setSupportActionBar(toolbar)
        (activity as? androidx.appcompat.app.AppCompatActivity)?.supportActionBar?.apply {
            setDisplayHomeAsUpEnabled(true)
            setDisplayShowHomeEnabled(true)
            title = getString(R.string.translation_history)
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
        allFilterChip.setOnClickListener { applyFilterInternal(HistoryFilter.ALL) }
        favoritesFilterChip.setOnClickListener { applyFilterInternal(HistoryFilter.FAVORITES) }
        successfulFilterChip.setOnClickListener { applyFilterInternal(HistoryFilter.SUCCESSFUL) }
        onDeviceFilterChip.setOnClickListener { applyFilterInternal(HistoryFilter.ON_DEVICE) }
        cloudFilterChip.setOnClickListener { applyFilterInternal(HistoryFilter.CLOUD) }
        
        // Empty state button
        emptyStateButton.setOnClickListener {
            onClearHistoryClickListener?.invoke()
        }
    }
    
    private fun setupRecyclerView() {
        historyAdapter = TranslationHistoryAdapter(
            onItemClick = { item ->
                if (isSelectionMode) {
                    toggleItemSelection(item.id)
                } else {
                    onHistoryItemClickListener?.invoke(item)
                }
            },
            onItemLongClick = { item ->
                if (!isSelectionMode) {
                    enterSelectionMode(item.id)
                }
                onHistoryItemLongClickListener?.invoke(item)
            },
            onFavoriteClick = { item ->
                lifecycleScope.launch { toggleFavorite(item) }
            },
            onShareClick = { item ->
                shareTranslation(item)
            },
            onCopyClick = { item ->
                copyTranslation(item)
            }
        )
        
        historyRecyclerView.apply {
            layoutManager = LinearLayoutManager(context)
            adapter = historyAdapter
            addItemDecoration(HistoryItemDecoration(requireContext()))
        }
    }
    
    private fun setupSearch() {
        searchEditText.hint = getString(R.string.search_history)
        searchEditText.setCompoundDrawablesWithIntrinsicBounds(
            ContextCompat.getDrawable(requireContext(), R.drawable.ic_search),
            null, null, null
        )
    }
    
    private fun setupFilters() {
        updateFilterChips(HistoryFilter.ALL)
    }
    
    private fun setupSort() {
        val sortOptions = HistorySort.values().map { it.displayName }
        val adapter = ArrayAdapter(requireContext(), android.R.layout.simple_spinner_item, sortOptions)
        adapter.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item)
        sortSpinner.adapter = adapter
        
        sortSpinner.onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
            override fun onItemSelected(parent: AdapterView<*>?, view: View?, position: Int, id: Long) {
                val selectedSort = HistorySort.values()[position]
                applySortInternal(selectedSort)
            }
            
            override fun onNothingSelected(parent: AdapterView<*>?) {}
        }
    }
    
    private fun setupAccessibility() {
        // Set content descriptions
        clearSearchButton.contentDescription = getString(R.string.clear_search)
        emptyStateButton.contentDescription = getString(R.string.clear_history)
        
        // Set accessibility hints
        searchEditText.hint = getString(R.string.search_history_hint)
        
        // Set up filter chip accessibility
        allFilterChip.contentDescription = getString(R.string.show_all_history)
        favoritesFilterChip.contentDescription = getString(R.string.show_favorite_history)
        successfulFilterChip.contentDescription = getString(R.string.show_successful_history)
        onDeviceFilterChip.contentDescription = getString(R.string.show_on_device_history)
        cloudFilterChip.contentDescription = getString(R.string.show_cloud_history)
    }

    private fun setupMenu() {
        val menuHost: MenuHost = requireActivity()
        menuHost.addMenuProvider(object : MenuProvider {
            override fun onCreateMenu(menu: Menu, menuInflater: MenuInflater) {
                menuInflater.inflate(R.menu.menu_translation_history, menu)
            }

            override fun onMenuItemSelected(item: MenuItem): Boolean = handleMenuItemSelected(item)
        }, viewLifecycleOwner, Lifecycle.State.RESUMED)
    }

    private fun handleMenuItemSelected(item: MenuItem): Boolean {
        return when (item.itemId) {
            R.id.action_search -> {
                toggleSearchVisibility()
                true
            }
            R.id.action_export -> {
                onExportClickListener?.invoke()
                true
            }
            R.id.action_clear -> {
                showClearHistoryDialog()
                true
            }
            R.id.action_select_all -> {
                selectAllItems()
                true
            }
            R.id.action_delete_selected -> {
                lifecycleScope.launch { deleteSelectedItems() }
                true
            }
            R.id.action_exit_selection -> {
                exitSelectionMode()
                true
            }
            else -> false
        }
    }

    private fun setupBackPressHandling() {
        val callback = object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() {
                if (isSelectionMode) {
                    exitSelectionMode()
                } else {
                    navigateUp()
                }
            }
        }
        requireActivity().onBackPressedDispatcher.addCallback(viewLifecycleOwner, callback)
        toolbar.setNavigationOnClickListener { callback.handleOnBackPressed() }
    }

    private fun navigateUp() {
        if (!findNavController().navigateUp()) {
            requireActivity().finish()
        }
    }
    
    private suspend fun initializeRepository() {
        historyRepository = TranslationHistoryRepository.getInstance(requireContext())
        historyRepository.initialize()
    }
    
    private suspend fun loadHistory() {
        try {
            showLoading(true)
            
            // Load history from repository
            val historyEntries = historyRepository.getTranslationHistory()
            
            // Convert to HistoryItem objects
            allHistoryItems = historyEntries.map { entry ->
                HistoryItem(
                    id = entry.id,
                    originalText = entry.originalText,
                    translatedText = entry.translatedText,
                    sourceLanguage = entry.sourceLanguage,
                    targetLanguage = entry.targetLanguage,
                    sourceLanguageName = getLanguageName(entry.sourceLanguage),
                    targetLanguageName = getLanguageName(entry.targetLanguage),
                    confidence = entry.confidence,
                    isPartial = entry.isPartial,
                    isOnDevice = entry.isOnDevice,
                    timestamp = entry.timestamp,
                    duration = entry.duration,
                    success = entry.success,
                    error = entry.error,
                    isFavorite = entry.favorite,
                    tags = entry.tags,
                    metadata = entry.metadata
                )
            }
            
            // Load statistics
            loadStatistics()
            
            // Apply current filter and sort
            applyCurrentFilterAndSort()
            
            showLoading(false)
            
        } catch (e: Exception) {
            Log.e(TAG, "Failed to load history", e)
            showError()
        }
    }
    
    private suspend fun loadStatistics() {
        try {
            val statistics = historyRepository.getTranslationStatistics()
            
            // Update stats UI
            totalTranslationsTextView.text = getString(R.string.total_translations, statistics.totalTranslations)
            
            val successRate = (statistics.successRate * 100).toInt()
            successRateTextView.text = getString(R.string.success_rate, successRate)
            
            val avgConfidence = (statistics.averageConfidence * 100).toInt()
            averageConfidenceTextView.text = getString(R.string.average_confidence, avgConfidence)
            
            statsContainer.visibility = View.VISIBLE
            
        } catch (e: Exception) {
            Log.e(TAG, "Failed to load statistics", e)
            statsContainer.visibility = View.GONE
        }
    }
    
    private fun performSearch() {
        searchJob?.cancel()
        searchJob = lifecycleScope.launch {
            delay(SEARCH_DELAY_MS)
            
            if (searchQuery.isBlank()) {
                applyCurrentFilterAndSort()
            } else {
                val query = searchQuery.lowercase()
                filteredHistoryItems = allHistoryItems.filter { item ->
                    item.originalText.lowercase().contains(query) ||
                    item.translatedText?.lowercase()?.contains(query) == true ||
                    item.sourceLanguageName.lowercase().contains(query) ||
                    item.targetLanguageName.lowercase().contains(query)
                }
                applySorting()
                updateHistoryList()
            }
        }
    }
    
    private fun applyFilterInternal(filter: HistoryFilter) {
        if (currentFilter == filter) return
        
        currentFilter = filter
        updateFilterChips(filter)
        applyCurrentFilterAndSort()
    }
    
    private fun applySortInternal(sort: HistorySort) {
        if (currentSort == sort) return
        
        currentSort = sort
        applySorting()
        updateHistoryList()
    }
    
    private fun applyCurrentFilterAndSort() {
        applyFiltering()
        applySorting()
        updateHistoryList()
    }
    
    private fun applyFiltering() {
        filteredHistoryItems = when (currentFilter) {
            HistoryFilter.ALL -> allHistoryItems
            HistoryFilter.FAVORITES -> allHistoryItems.filter { it.isFavorite }
            HistoryFilter.SUCCESSFUL -> allHistoryItems.filter { it.success }
            HistoryFilter.ON_DEVICE -> allHistoryItems.filter { it.isOnDevice }
            HistoryFilter.CLOUD -> allHistoryItems.filter { !it.isOnDevice }
        }
    }
    
    private fun applySorting() {
        filteredHistoryItems = when (currentSort) {
            HistorySort.DATE_DESC -> filteredHistoryItems.sortedByDescending { it.timestamp }
            HistorySort.DATE_ASC -> filteredHistoryItems.sortedBy { it.timestamp }
            HistorySort.CONFIDENCE_DESC -> filteredHistoryItems.sortedByDescending { it.confidence ?: 0f }
            HistorySort.CONFIDENCE_ASC -> filteredHistoryItems.sortedBy { it.confidence ?: 0f }
            HistorySort.DURATION_DESC -> filteredHistoryItems.sortedByDescending { it.duration }
            HistorySort.DURATION_ASC -> filteredHistoryItems.sortedBy { it.duration }
            HistorySort.SOURCE_LANG -> filteredHistoryItems.sortedBy { it.sourceLanguageName }
            HistorySort.TARGET_LANG -> filteredHistoryItems.sortedBy { it.targetLanguageName }
        }
    }
    
    private fun updateHistoryList() {
        historyAdapter.updateHistory(filteredHistoryItems, selectedItems, isSelectionMode)
        
        // Show/hide empty state
        if (filteredHistoryItems.isEmpty()) {
            showEmptyState()
        } else {
            hideEmptyState()
        }
    }
    
    private fun updateFilterChips(activeFilter: HistoryFilter) {
        // Reset all chips
        listOf(allFilterChip, favoritesFilterChip, successfulFilterChip, onDeviceFilterChip, cloudFilterChip)
            .forEach { chip ->
                chip.isChecked = false
                chip.setChipBackgroundColorResource(R.color.chip_background_unselected)
            }
        
        // Set active chip
        val activeChip = when (activeFilter) {
            HistoryFilter.ALL -> allFilterChip
            HistoryFilter.FAVORITES -> favoritesFilterChip
            HistoryFilter.SUCCESSFUL -> successfulFilterChip
            HistoryFilter.ON_DEVICE -> onDeviceFilterChip
            HistoryFilter.CLOUD -> cloudFilterChip
        }
        
        activeChip.isChecked = true
        activeChip.setChipBackgroundColorResource(R.color.chip_background_selected)
    }
    
    private fun updateClearSearchButtonVisibility() {
        clearSearchButton.visibility = if (searchQuery.isNotBlank()) View.VISIBLE else View.GONE
    }
    
    private fun showLoading(show: Boolean) {
        loadingIndicator.visibility = if (show) View.VISIBLE else View.GONE
        historyRecyclerView.visibility = if (show) View.GONE else View.VISIBLE
    }
    
    private fun showEmptyState() {
        emptyStateContainer.visibility = View.VISIBLE
        historyRecyclerView.visibility = View.GONE
        
        val emptyText = when {
            searchQuery.isNotBlank() -> getString(R.string.no_history_found_search, searchQuery)
            currentFilter == HistoryFilter.FAVORITES -> getString(R.string.no_favorite_history)
            currentFilter == HistoryFilter.SUCCESSFUL -> getString(R.string.no_successful_history)
            currentFilter == HistoryFilter.ON_DEVICE -> getString(R.string.no_on_device_history)
            currentFilter == HistoryFilter.CLOUD -> getString(R.string.no_cloud_history)
            else -> getString(R.string.no_translation_history)
        }
        
        emptyStateTextView.text = emptyText
    }
    
    private fun hideEmptyState() {
        emptyStateContainer.visibility = View.GONE
        historyRecyclerView.visibility = View.VISIBLE
    }
    
    private fun showError() {
        showLoading(false)
        showEmptyState()
        emptyStateTextView.text = getString(R.string.error_loading_history)
    }
    
    private fun startEntranceAnimation() {
        // Fade in content
        rootLayout.alpha = 0f
        rootLayout.animate()
            .alpha(1f)
            .setDuration(ANIMATION_DURATION)
            .setInterpolator(AccelerateDecelerateInterpolator())
            .start()
    }
    
    private fun enterSelectionMode(itemId: String) {
        isSelectionMode = true
        selectedItems.add(itemId)
        updateHistoryList()
        updateToolbarForSelectionMode()
    }
    
    private fun exitSelectionMode() {
        isSelectionMode = false
        selectedItems.clear()
        updateHistoryList()
        updateToolbarForNormalMode()
    }
    
    private fun toggleItemSelection(itemId: String) {
        if (selectedItems.contains(itemId)) {
            selectedItems.remove(itemId)
        } else {
            selectedItems.add(itemId)
        }
        
        if (selectedItems.isEmpty()) {
            exitSelectionMode()
        } else {
            updateHistoryList()
            updateToolbarForSelectionMode()
        }
    }
    
    private fun updateToolbarForSelectionMode() {
        (activity as? androidx.appcompat.app.AppCompatActivity)?.supportActionBar?.apply {
            title = getString(R.string.selected_items, selectedItems.size)
        }
    }
    
    private fun updateToolbarForNormalMode() {
        (activity as? androidx.appcompat.app.AppCompatActivity)?.supportActionBar?.apply {
            title = getString(R.string.translation_history)
        }
    }
    
    private suspend fun toggleFavorite(item: HistoryItem) {
        try {
            val repository = TranslationHistoryRepository.getInstance(requireContext())
            val entry = repository.getTranslationById(item.id)
            
            if (entry != null) {
                val updatedEntry = entry.copy(favorite = !entry.favorite)
                repository.updateTranslation(updatedEntry)
                
                // Update local data
                val index = allHistoryItems.indexOfFirst { it.id == item.id }
                if (index >= 0) {
                    val updatedItem = item.copy(isFavorite = !item.isFavorite)
                    allHistoryItems = allHistoryItems.toMutableList().apply { set(index, updatedItem) }
                    applyCurrentFilterAndSort()
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "Failed to toggle favorite", e)
        }
    }
    
    private fun shareTranslation(item: HistoryItem) {
        val shareText = "${item.originalText}\n\n${item.translatedText}"
        val shareIntent = Intent().apply {
            action = Intent.ACTION_SEND
            putExtra(Intent.EXTRA_TEXT, shareText)
            type = "text/plain"
        }
        startActivity(Intent.createChooser(shareIntent, getString(R.string.share_translation)))
    }
    
    private fun copyTranslation(item: HistoryItem) {
        val clipboard = requireContext().getSystemService(Context.CLIPBOARD_SERVICE) as android.content.ClipboardManager
        val clip = android.content.ClipData.newPlainText("Translation", item.translatedText ?: item.originalText)
        clipboard.setPrimaryClip(clip)
        
        Toast.makeText(requireContext(), getString(R.string.text_copied), Toast.LENGTH_SHORT).show()
    }
    
    private fun getLanguageName(languageCode: String): String {
        return when (languageCode.lowercase()) {
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
            else -> languageCode.uppercase()
        }
    }
    
    private fun toggleSearchVisibility() {
        val isVisible = searchContainer.visibility == View.VISIBLE
        searchContainer.visibility = if (isVisible) View.GONE else View.VISIBLE
        
        if (!isVisible) {
            searchEditText.requestFocus()
        }
    }
    
    private fun showClearHistoryDialog() {
        AlertDialog.Builder(requireContext())
            .setTitle(getString(R.string.clear_history))
            .setMessage(getString(R.string.clear_history_confirmation))
            .setPositiveButton(getString(R.string.clear)) { _, _ ->
                lifecycleScope.launch { clearHistory() }
            }
            .setNegativeButton(getString(R.string.cancel), null)
            .show()
    }
    
    private suspend fun clearHistory() {
        try {
            val success = historyRepository.clearAllHistory()
            if (success) {
                allHistoryItems = emptyList()
                filteredHistoryItems = emptyList()
                updateHistoryList()
                loadStatistics()
                Toast.makeText(requireContext(), getString(R.string.history_cleared), Toast.LENGTH_SHORT).show()
            } else {
                Toast.makeText(requireContext(), getString(R.string.history_clear_failed), Toast.LENGTH_SHORT).show()
            }
        } catch (e: Exception) {
            Log.e(TAG, "Failed to clear history", e)
            Toast.makeText(requireContext(), getString(R.string.history_clear_failed), Toast.LENGTH_SHORT).show()
        }
    }
    
    private fun selectAllItems() {
        selectedItems.clear()
        selectedItems.addAll(filteredHistoryItems.map { it.id })
        updateHistoryList()
        updateToolbarForSelectionMode()
    }
    
    private suspend fun deleteSelectedItems() {
        try {
            val success = historyRepository.deleteTranslations(selectedItems.toList())
            if (success > 0) {
                // Remove deleted items from local data
                allHistoryItems = allHistoryItems.filter { it.id !in selectedItems }
                exitSelectionMode()
                loadStatistics()
                Toast.makeText(requireContext(), getString(R.string.items_deleted, success), Toast.LENGTH_SHORT).show()
            } else {
                Toast.makeText(requireContext(), getString(R.string.delete_failed), Toast.LENGTH_SHORT).show()
            }
        } catch (e: Exception) {
            Log.e(TAG, "Failed to delete selected items", e)
            Toast.makeText(requireContext(), getString(R.string.delete_failed), Toast.LENGTH_SHORT).show()
        }
    }
    
    // Public methods for external interaction
    
    fun setOnHistoryItemClickListener(listener: (HistoryItem) -> Unit) {
        onHistoryItemClickListener = listener
    }
    
    fun setOnHistoryItemLongClickListener(listener: (HistoryItem) -> Unit) {
        onHistoryItemLongClickListener = listener
    }
    
    fun setOnExportClickListener(listener: () -> Unit) {
        onExportClickListener = listener
    }
    
    fun setOnClearHistoryClickListener(listener: () -> Unit) {
        onClearHistoryClickListener = listener
    }
    
    fun refreshHistory() {
        lifecycleScope.launch {
            loadHistory()
        }
    }
    
    fun setSearchQuery(query: String) {
        searchEditText.setText(query)
    }
    
    fun setFilter(filter: HistoryFilter) {
        applyFilterInternal(filter)
    }
    
    fun setSort(sort: HistorySort) {
        val index = HistorySort.values().indexOf(sort)
        if (index >= 0) {
            sortSpinner.setSelection(index)
        }
    }
    
    override fun onDestroy() {
        super.onDestroy()
        searchJob?.cancel()
    }
    
    // History Adapter
    private inner class TranslationHistoryAdapter(
        private val onItemClick: (HistoryItem) -> Unit,
        private val onItemLongClick: (HistoryItem) -> Unit,
        private val onFavoriteClick: (HistoryItem) -> Unit,
        private val onShareClick: (HistoryItem) -> Unit,
        private val onCopyClick: (HistoryItem) -> Unit
    ) : RecyclerView.Adapter<TranslationHistoryAdapter.HistoryViewHolder>() {
        
        private var historyItems: List<HistoryItem> = emptyList()
        private var selectedItems: Set<String> = emptySet()
        private var isSelectionMode: Boolean = false
        
        fun updateHistory(newHistory: List<HistoryItem>, newSelectedItems: Set<String>, newSelectionMode: Boolean) {
            historyItems = newHistory
            selectedItems = newSelectedItems
            isSelectionMode = newSelectionMode
            notifyDataSetChanged()
        }
        
        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): HistoryViewHolder {
            val view = LayoutInflater.from(parent.context)
                .inflate(R.layout.item_translation_history, parent, false)
            return HistoryViewHolder(view)
        }
        
        override fun onBindViewHolder(holder: HistoryViewHolder, position: Int) {
            holder.bind(historyItems[position])
        }
        
        override fun getItemCount(): Int = historyItems.size
        
        inner class HistoryViewHolder(itemView: View) : RecyclerView.ViewHolder(itemView) {
            private val rootLayout: LinearLayout = itemView.findViewById(R.id.root_layout)
            private val selectionIndicator: ImageView = itemView.findViewById(R.id.selection_indicator)
            private val originalTextView: TextView = itemView.findViewById(R.id.original_text_view)
            private val translatedTextView: TextView = itemView.findViewById(R.id.translated_text_view)
            private val languageTextView: TextView = itemView.findViewById(R.id.language_text_view)
            private val timestampTextView: TextView = itemView.findViewById(R.id.timestamp_text_view)
            private val confidenceBar: ProgressBar = itemView.findViewById(R.id.confidence_progress_bar)
            private val confidenceTextView: TextView = itemView.findViewById(R.id.confidence_text_view)
            private val statusIndicator: ImageView = itemView.findViewById(R.id.status_indicator)
            private val favoriteButton: ImageButton = itemView.findViewById(R.id.favorite_button)
            private val shareButton: ImageButton = itemView.findViewById(R.id.share_button)
            private val copyButton: ImageButton = itemView.findViewById(R.id.copy_button)
            
            fun bind(item: HistoryItem) {
                // Set text content
                originalTextView.text = item.originalText
                translatedTextView.text = item.translatedText ?: getString(R.string.translation_failed)
                languageTextView.text = "${item.sourceLanguageName} → ${item.targetLanguageName}"
                timestampTextView.text = formatTimestamp(item.timestamp)
                
                // Set confidence
                item.confidence?.let { confidence ->
                    val percentage = (confidence * 100).toInt()
                    confidenceBar.progress = percentage
                    confidenceTextView.text = getString(R.string.confidence_percentage, percentage)
                    confidenceBar.visibility = View.VISIBLE
                    confidenceTextView.visibility = View.VISIBLE
                } ?: run {
                    confidenceBar.visibility = View.GONE
                    confidenceTextView.visibility = View.GONE
                }
                
                // Set status indicator
                statusIndicator.setImageResource(
                    when {
                        item.success -> R.drawable.ic_check_circle
                        else -> R.drawable.ic_error
                    }
                )
                statusIndicator.setColorFilter(
                    ContextCompat.getColor(requireContext(), if (item.success) R.color.success_color else R.color.error_color)
                )
                
                // Set favorite button
                favoriteButton.setImageResource(
                    if (item.isFavorite) R.drawable.ic_favorite_filled else R.drawable.ic_favorite_border
                )
                favoriteButton.setOnClickListener { onFavoriteClick(item) }
                
                // Set action buttons
                shareButton.setOnClickListener { onShareClick(item) }
                copyButton.setOnClickListener { onCopyClick(item) }
                
                // Set selection mode
                if (isSelectionMode) {
                    selectionIndicator.visibility = View.VISIBLE
                    selectionIndicator.setImageResource(
                        if (selectedItems.contains(item.id)) R.drawable.ic_check_circle else R.drawable.ic_radio_button_unchecked
                    )
                } else {
                    selectionIndicator.visibility = View.GONE
                }
                
                // Set click listeners
                rootLayout.setOnClickListener {
                    onItemClick(item)
                }
                
                rootLayout.setOnLongClickListener {
                    onItemLongClick(item)
                    true
                }
                
                // Set accessibility
                rootLayout.contentDescription = "${item.originalText} translated to ${item.translatedText}"
                rootLayout.isFocusable = true
            }
            
            private fun formatTimestamp(timestamp: Long): String {
                val now = System.currentTimeMillis()
                val diff = now - timestamp
                
                return when {
                    diff < 60_000 -> getString(R.string.just_now)
                    diff < 3600_000 -> getString(R.string.minutes_ago, (diff / 60_000).toInt())
                    diff < 86400_000 -> getString(R.string.hours_ago, (diff / 3600_000).toInt())
                    else -> getString(R.string.days_ago, (diff / 86400_000).toInt())
                }
            }
        }
    }
    
    // Item decoration for spacing
    private class HistoryItemDecoration(context: Context) : RecyclerView.ItemDecoration() {
        private val spacing = context.resources.getDimensionPixelSize(R.dimen.history_item_spacing)
        
        // Implementation would add spacing between items
        // This is a placeholder for the actual decoration logic
    }
}

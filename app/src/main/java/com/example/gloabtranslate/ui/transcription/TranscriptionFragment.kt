package com.example.gloabtranslate.ui.transcription

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
import com.example.gloabtranslate.core.data.repository.TranslationRepository
import com.example.gloabtranslate.speech.SpeechRecognitionService
import dagger.android.support.AndroidSupportInjection
import javax.inject.Inject
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*

/**
 * Fragment for real-time transcription display with streaming text,
 * confidence visualization, and transcription management.
 */
class TranscriptionFragment : Fragment() {

    @Inject
    lateinit var speechRecognitionService: SpeechRecognitionService

    @Inject
    lateinit var translationRepository: TranslationRepository

    @Inject
    lateinit var translationHistoryRepository: TranslationHistoryRepository
    
    override fun onAttach(context: android.content.Context) {
        AndroidSupportInjection.inject(this)
        super.onAttach(context)
    }
    
    companion object {
        private const val TAG = "TranscriptionFragment"
        private const val ANIMATION_DURATION = 250L
        private const val TYPING_DELAY_MS = 50L
        private const val MAX_DISPLAYED_CHARS = 5000
        private const val CONFIDENCE_THRESHOLD_HIGH = 0.8f
        private const val CONFIDENCE_THRESHOLD_MEDIUM = 0.6f
        
        fun newInstance(): TranscriptionFragment {
            return TranscriptionFragment()
        }
    }
    
    // UI Components
    private lateinit var rootLayout: LinearLayout
    private lateinit var toolbar: androidx.appcompat.widget.Toolbar
    private lateinit var statusContainer: LinearLayout
    private lateinit var statusIndicator: ImageView
    private lateinit var statusTextView: TextView
    private lateinit var languageIndicator: TextView
    private lateinit var confidenceIndicator: TextView
    private lateinit var controlContainer: LinearLayout
    private lateinit var startStopButton: Button
    private lateinit var pauseResumeButton: Button
    private lateinit var clearButton: Button
    private lateinit var exportButton: Button
    private lateinit var transcriptionContainer: LinearLayout
    private lateinit var transcriptionTextView: TextView
    private lateinit var confidenceBar: ProgressBar
    private lateinit var realTimeContainer: LinearLayout
    private lateinit var realTimeTextView: TextView
    private lateinit var partialTextView: TextView
    private lateinit var historyContainer: LinearLayout
    private lateinit var historyRecyclerView: RecyclerView
    private lateinit var emptyStateContainer: LinearLayout
    private lateinit var emptyStateImageView: ImageView
    private lateinit var emptyStateTextView: TextView
    private lateinit var loadingIndicator: ProgressBar
    // Adapter and data
    private lateinit var historyAdapter: TranscriptionHistoryAdapter
    private var transcriptionHistory: List<TranscriptionItem> = emptyList()
    private var currentTranscription = ""
    private var currentPartialText = ""
    private var currentConfidence = 0f
    private var currentLanguage = "auto"
    
    // State management
    private var isTranscribing = false
    private var isPaused = false
    private var startTime = 0L
    private var pauseTime = 0L
    private var totalPauseDuration = 0L
    private var typingJob: Job? = null
    
    // Repository
    private lateinit var historyRepository: TranslationHistoryRepository
    
    // Listeners
    private var onStartTranscriptionListener: (() -> Unit)? = null
    private var onStopTranscriptionListener: (() -> Unit)? = null
    private var onPauseTranscriptionListener: (() -> Unit)? = null
    private var onResumeTranscriptionListener: (() -> Unit)? = null
    private var onClearTranscriptionListener: (() -> Unit)? = null
    private var onExportTranscriptionListener: (() -> Unit)? = null
    private var onTranscriptionItemClickListener: ((TranscriptionItem) -> Unit)? = null
    
    /**
     * Transcription item data class
     */
    data class TranscriptionItem(
        val id: String,
        val text: String,
        val confidence: Float,
        val language: String,
        val languageName: String,
        val timestamp: Long,
        val duration: Long,
        val isPartial: Boolean,
        val isFinal: Boolean,
        val metadata: Map<String, String>
    )
    
    /**
     * Transcription status
     */
    enum class TranscriptionStatus {
        IDLE,
        LISTENING,
        PROCESSING,
        PAUSED,
        ERROR
    }
    
    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View? {
        return inflater.inflate(R.layout.fragment_transcription, container, false)
    }
    
    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        
        initializeViews(view)
        setupToolbar()
        setupListeners()
        setupRecyclerView()
        setupAccessibility()
        
        // Initialize repository
        lifecycleScope.launch {
            initializeRepository()
        }
        
        // Load transcription history
        lifecycleScope.launch {
            loadTranscriptionHistory()
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
        statusContainer = view.findViewById(R.id.status_container)
        controlContainer = view.findViewById(R.id.control_container)
        transcriptionContainer = view.findViewById(R.id.transcription_container)
        realTimeContainer = view.findViewById(R.id.real_time_container)
        historyContainer = view.findViewById(R.id.history_container)
        emptyStateContainer = view.findViewById(R.id.empty_state_container)
        
        // Header components
        toolbar = view.findViewById(R.id.toolbar)
        
        // Status components
        statusIndicator = view.findViewById(R.id.status_indicator)
        statusTextView = view.findViewById(R.id.status_text_view)
        languageIndicator = view.findViewById(R.id.language_indicator)
        confidenceIndicator = view.findViewById(R.id.confidence_indicator)
        
        // Control components
        startStopButton = view.findViewById(R.id.start_stop_button)
        pauseResumeButton = view.findViewById(R.id.pause_resume_button)
        clearButton = view.findViewById(R.id.clear_button)
        exportButton = view.findViewById(R.id.export_button)
        
        // Transcription components
        transcriptionTextView = view.findViewById(R.id.transcription_text_view)
        confidenceBar = view.findViewById(R.id.confidence_progress_bar)
        realTimeTextView = view.findViewById(R.id.real_time_text_view)
        partialTextView = view.findViewById(R.id.partial_text_view)
        
        // History components
        historyRecyclerView = view.findViewById(R.id.history_recycler_view)
        emptyStateImageView = view.findViewById(R.id.empty_state_image_view)
        emptyStateTextView = view.findViewById(R.id.empty_state_text_view)
        loadingIndicator = view.findViewById(R.id.loading_indicator)
    }
    
    private fun setupToolbar() {
        toolbar.title = getString(R.string.real_time_transcription)
        toolbar.navigationIcon = ContextCompat.getDrawable(requireContext(), R.drawable.ic_chevron_left)
    }
    
    private fun setupListeners() {
        // Control buttons
        startStopButton.setOnClickListener {
            if (isTranscribing) {
                stopTranscription()
            } else {
                startTranscription()
            }
        }
        
        pauseResumeButton.setOnClickListener {
            if (isPaused) {
                resumeTranscription()
            } else {
                pauseTranscription()
            }
        }
        
        clearButton.setOnClickListener {
            clearTranscription()
        }
        
        exportButton.setOnClickListener {
            exportTranscription()
        }
        
        // Transcription text click
        transcriptionTextView.setOnClickListener {
            copyTranscription()
        }
        
        // Real-time text click
        realTimeTextView.setOnClickListener {
            copyRealTimeText()
        }
    }
    
    private fun setupRecyclerView() {
        historyAdapter = TranscriptionHistoryAdapter(
            onItemClick = { item ->
                onTranscriptionItemClickListener?.invoke(item)
            },
            onItemLongClick = { item ->
                showTranscriptionOptions(item)
            }
        )
        
        historyRecyclerView.apply {
            layoutManager = LinearLayoutManager(context)
            adapter = historyAdapter
            addItemDecoration(TranscriptionItemDecoration(requireContext()))
        }
    }
    
    private fun setupAccessibility() {
        // Set content descriptions
        startStopButton.contentDescription = getString(R.string.start_transcription)
        pauseResumeButton.contentDescription = getString(R.string.pause_transcription)
        clearButton.contentDescription = getString(R.string.clear_transcription)
        exportButton.contentDescription = getString(R.string.export_transcription)
        transcriptionTextView.contentDescription = getString(R.string.transcription_text)
        realTimeTextView.contentDescription = getString(R.string.real_time_text)
        
        // Set accessibility hints
        transcriptionTextView.hint = getString(R.string.transcription_text_hint)
        realTimeTextView.hint = getString(R.string.real_time_text_hint)
    }

    private fun setupMenu() {
        val menuHost: MenuHost = requireActivity()
        menuHost.addMenuProvider(object : MenuProvider {
            override fun onCreateMenu(menu: Menu, menuInflater: MenuInflater) {
                menuInflater.inflate(R.menu.menu_transcription, menu)
            }

            override fun onMenuItemSelected(item: MenuItem): Boolean = handleMenuItemSelected(item)
        }, viewLifecycleOwner, Lifecycle.State.RESUMED)
    }

    private fun handleMenuItemSelected(item: MenuItem): Boolean {
        return when (item.itemId) {
            R.id.action_export -> {
                exportTranscription()
                true
            }
            R.id.action_clear -> {
                clearTranscription()
                true
            }
            R.id.action_settings -> {
                showTranscriptionSettings()
                true
            }
            else -> false
        }
    }

    private fun setupBackPressHandling() {
        val callback = object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() {
                navigateBack()
            }
        }
        requireActivity().onBackPressedDispatcher.addCallback(viewLifecycleOwner, callback)
        toolbar.setNavigationOnClickListener { callback.handleOnBackPressed() }
    }

    private fun navigateBack() {
        if (!findNavController().navigateUp()) {
            requireActivity().finish()
        }
    }
    
    private suspend fun initializeRepository() {
        historyRepository = TranslationHistoryRepository.getInstance(requireContext())
        historyRepository.initialize()
    }
    
    private suspend fun loadTranscriptionHistory() {
        try {
            showLoading(true)
            
            // Load transcription history from repository
            val historyEntries = historyRepository.getTranslationHistory()
            
            // Convert to TranscriptionItem objects
            transcriptionHistory = historyEntries.map { entry ->
                TranscriptionItem(
                    id = entry.id,
                    text = entry.originalText,
                    confidence = entry.confidence ?: 0f,
                    language = entry.sourceLanguage,
                    languageName = getLanguageName(entry.sourceLanguage),
                    timestamp = entry.timestamp,
                    duration = entry.duration,
                    isPartial = entry.isPartial,
                    isFinal = !entry.isPartial,
                    metadata = entry.metadata
                )
            }
            
            updateHistoryList()
            showLoading(false)
            
        } catch (e: Exception) {
            Log.e(TAG, "Failed to load transcription history", e)
            showError()
        }
    }
    
    private fun startTranscription() {
        isTranscribing = true
        isPaused = false
        startTime = System.currentTimeMillis()
        totalPauseDuration = 0L
        
        updateStatus(TranscriptionStatus.LISTENING)
        updateControls()
        
        onStartTranscriptionListener?.invoke()
        
        // Start typing animation for real-time display
        startTypingAnimation()
    }
    
    private fun stopTranscription() {
        isTranscribing = false
        isPaused = false
        
        updateStatus(TranscriptionStatus.IDLE)
        updateControls()
        
        onStopTranscriptionListener?.invoke()
        
        // Stop typing animation
        stopTypingAnimation()
        
        // Save transcription if there's content
        if (currentTranscription.isNotBlank()) {
            lifecycleScope.launch {
                saveTranscription()
            }
        }
    }
    
    private fun pauseTranscription() {
        isPaused = true
        pauseTime = System.currentTimeMillis()
        
        updateStatus(TranscriptionStatus.PAUSED)
        updateControls()
        
        onPauseTranscriptionListener?.invoke()
    }
    
    private fun resumeTranscription() {
        isPaused = false
        totalPauseDuration += System.currentTimeMillis() - pauseTime
        
        updateStatus(TranscriptionStatus.LISTENING)
        updateControls()
        
        onResumeTranscriptionListener?.invoke()
    }
    
    private fun clearTranscription() {
        if (currentTranscription.isNotBlank()) {
            AlertDialog.Builder(requireContext())
                .setTitle(getString(R.string.clear_transcription))
                .setMessage(getString(R.string.clear_transcription_confirmation))
                .setPositiveButton(getString(R.string.clear)) { _, _ ->
                    performClearTranscription()
                }
                .setNegativeButton(getString(R.string.cancel), null)
                .show()
        }
    }
    
    private fun performClearTranscription() {
        currentTranscription = ""
        currentPartialText = ""
        currentConfidence = 0f
        
        updateTranscriptionDisplay()
        updateRealTimeDisplay()
        updateConfidenceDisplay()
        
        onClearTranscriptionListener?.invoke()
    }
    
    private fun exportTranscription() {
        if (currentTranscription.isNotBlank()) {
            onExportTranscriptionListener?.invoke()
        } else {
            Toast.makeText(requireContext(), getString(R.string.no_transcription_to_export), Toast.LENGTH_SHORT).show()
        }
    }
    
    private fun showTranscriptionSettings() {
        val settingsOptions = arrayOf(
            "Audio Quality",
            "Auto-save",
            "Language Detection",
            "Punctuation",
            "Display Options"
        )
        
        AlertDialog.Builder(requireContext())
            .setTitle("Transcription Settings")
            .setItems(settingsOptions) { _, which ->
                when (which) {
                    0 -> showAudioQualitySettings()
                    1 -> toggleAutoSave()
                    2 -> toggleLanguageDetection()
                    3 -> togglePunctuation()
                    4 -> showDisplayOptions()
                }
            }
            .setNegativeButton("Close", null)
            .show()
    }
    
    private fun showAudioQualitySettings() {
        Toast.makeText(requireContext(), "Audio quality settings", Toast.LENGTH_SHORT).show()
    }
    
    private fun toggleAutoSave() {
        Toast.makeText(requireContext(), "Auto-save toggled", Toast.LENGTH_SHORT).show()
    }
    
    private fun toggleLanguageDetection() {
        Toast.makeText(requireContext(), "Language detection toggled", Toast.LENGTH_SHORT).show()
    }
    
    private fun togglePunctuation() {
        Toast.makeText(requireContext(), "Punctuation toggled", Toast.LENGTH_SHORT).show()
    }
    
    private fun showDisplayOptions() {
        Toast.makeText(requireContext(), "Display options", Toast.LENGTH_SHORT).show()
    }
    
    private fun copyTranscription() {
        if (currentTranscription.isNotBlank()) {
            val clipboard = requireContext().getSystemService(Context.CLIPBOARD_SERVICE) as android.content.ClipboardManager
            val clip = android.content.ClipData.newPlainText("Transcription", currentTranscription)
            clipboard.setPrimaryClip(clip)
            Toast.makeText(requireContext(), getString(R.string.transcription_copied), Toast.LENGTH_SHORT).show()
        }
    }
    
    private fun copyRealTimeText() {
        val textToCopy = if (currentPartialText.isNotBlank()) currentPartialText else currentTranscription
        if (textToCopy.isNotBlank()) {
            val clipboard = requireContext().getSystemService(Context.CLIPBOARD_SERVICE) as android.content.ClipboardManager
            val clip = android.content.ClipData.newPlainText("Real-time Transcription", textToCopy)
            clipboard.setPrimaryClip(clip)
            Toast.makeText(requireContext(), getString(R.string.real_time_text_copied), Toast.LENGTH_SHORT).show()
        }
    }
    
    private fun showTranscriptionOptions(item: TranscriptionItem) {
        val options = arrayOf(
            getString(R.string.copy_text),
            getString(R.string.share_transcription),
            getString(R.string.delete_transcription)
        )
        
        AlertDialog.Builder(requireContext())
            .setTitle(item.text.take(50) + if (item.text.length > 50) "..." else "")
            .setItems(options) { _, which ->
                when (which) {
                    0 -> copyTranscriptionItem(item)
                    1 -> shareTranscriptionItem(item)
                    2 -> lifecycleScope.launch {
                        deleteTranscriptionItem(item)
                    }
                }
            }
            .show()
    }
    
    private fun copyTranscriptionItem(item: TranscriptionItem) {
        val clipboard = requireContext().getSystemService(Context.CLIPBOARD_SERVICE) as android.content.ClipboardManager
        val clip = android.content.ClipData.newPlainText("Transcription", item.text)
        clipboard.setPrimaryClip(clip)
        Toast.makeText(requireContext(), getString(R.string.transcription_copied), Toast.LENGTH_SHORT).show()
    }
    
    private fun shareTranscriptionItem(item: TranscriptionItem) {
        val shareText = "${item.text}\n\n${getString(R.string.transcribed_on)} ${formatTimestamp(item.timestamp)}"
        val shareIntent = Intent().apply {
            action = Intent.ACTION_SEND
            putExtra(Intent.EXTRA_TEXT, shareText)
            type = "text/plain"
        }
        startActivity(Intent.createChooser(shareIntent, getString(R.string.share_transcription)))
    }
    
    private suspend fun deleteTranscriptionItem(item: TranscriptionItem) {
        try {
            val success = historyRepository.deleteTranslation(item.id)
            if (success) {
                transcriptionHistory = transcriptionHistory.filter { it.id != item.id }
                updateHistoryList()
                Toast.makeText(requireContext(), getString(R.string.transcription_deleted), Toast.LENGTH_SHORT).show()
            } else {
                Toast.makeText(requireContext(), getString(R.string.delete_failed), Toast.LENGTH_SHORT).show()
            }
        } catch (e: Exception) {
            Log.e(TAG, "Failed to delete transcription", e)
            Toast.makeText(requireContext(), getString(R.string.delete_failed), Toast.LENGTH_SHORT).show()
        }
    }
    
    private suspend fun saveTranscription() {
        try {
            val entry = com.example.gloabtranslate.core.data.models.TranslationResult(
                success = true,
                originalText = currentTranscription,
                translatedText = null,
                sourceLanguage = currentLanguage,
                targetLanguage = "auto",
                confidence = currentConfidence,
                isPartial = false,
                isOnDevice = true,
                timestamp = System.currentTimeMillis(),
                error = null
            )
            
            historyRepository.addTranslation(entry)
            
            // Refresh history
            loadTranscriptionHistory()
            
        } catch (e: Exception) {
            Log.e(TAG, "Failed to save transcription", e)
        }
    }
    
    private fun generateTranscriptionId(): String {
        return "transcription_${System.currentTimeMillis()}_${(0..999).random()}"
    }
    
    private fun updateStatus(status: TranscriptionStatus) {
        val (indicatorRes, statusText, statusColor) = when (status) {
            TranscriptionStatus.IDLE -> Triple(
                R.drawable.ic_mic_off,
                getString(R.string.status_idle),
                R.color.text_secondary
            )
            TranscriptionStatus.LISTENING -> Triple(
                R.drawable.ic_mic,
                getString(R.string.status_listening),
                R.color.success_color
            )
            TranscriptionStatus.PROCESSING -> Triple(
                R.drawable.ic_processing,
                getString(R.string.status_processing),
                R.color.confidence_medium
            )
            TranscriptionStatus.PAUSED -> Triple(
                R.drawable.ic_pause,
                getString(R.string.status_paused),
                R.color.confidence_medium
            )
            TranscriptionStatus.ERROR -> Triple(
                R.drawable.ic_error,
                getString(R.string.status_error),
                R.color.error_color
            )
        }
        
        statusIndicator.setImageResource(indicatorRes)
        statusTextView.text = statusText
        statusTextView.setTextColor(ContextCompat.getColor(requireContext(), statusColor))
        
        // Animate status indicator for listening state
        if (status == TranscriptionStatus.LISTENING) {
            animateStatusIndicator()
        } else {
            statusIndicator.clearAnimation()
        }
    }
    
    private fun updateControls() {
        startStopButton.text = if (isTranscribing) {
            getString(R.string.stop_transcription)
        } else {
            getString(R.string.start_transcription)
        }
        
        startStopButton.setBackgroundColor(
            ContextCompat.getColor(
                requireContext(),
                if (isTranscribing) R.color.error_color else R.color.success_color
            )
        )
        
        pauseResumeButton.isEnabled = isTranscribing
        pauseResumeButton.text = if (isPaused) {
            getString(R.string.resume_transcription)
        } else {
            getString(R.string.pause_transcription)
        }
        
        clearButton.isEnabled = currentTranscription.isNotBlank()
        exportButton.isEnabled = currentTranscription.isNotBlank()
    }
    
    private fun updateTranscriptionDisplay() {
        transcriptionTextView.text = currentTranscription
        transcriptionTextView.visibility = if (currentTranscription.isNotBlank()) View.VISIBLE else View.GONE
    }
    
    private fun updateRealTimeDisplay() {
        val displayText = if (currentPartialText.isNotBlank()) {
            currentTranscription + currentPartialText
        } else {
            currentTranscription
        }
        
        realTimeTextView.text = displayText
        realTimeTextView.visibility = if (displayText.isNotBlank()) View.VISIBLE else View.GONE
        
        // Update partial text display
        partialTextView.text = currentPartialText
        partialTextView.visibility = if (currentPartialText.isNotBlank()) View.VISIBLE else View.GONE
    }
    
    private fun updateConfidenceDisplay() {
        val confidencePercentage = (currentConfidence * 100).toInt()
        confidenceBar.progress = confidencePercentage
        confidenceIndicator.text = getString(R.string.confidence_percentage, confidencePercentage)
        
        // Update confidence color
        val confidenceColor = when {
            currentConfidence >= CONFIDENCE_THRESHOLD_HIGH -> R.color.confidence_high
            currentConfidence >= CONFIDENCE_THRESHOLD_MEDIUM -> R.color.confidence_medium
            else -> R.color.confidence_low
        }
        
        confidenceBar.progressTintList = ContextCompat.getColorStateList(requireContext(), confidenceColor)
        confidenceIndicator.setTextColor(ContextCompat.getColor(requireContext(), confidenceColor))
    }
    
    private fun updateLanguageDisplay(language: String) {
        currentLanguage = language
        languageIndicator.text = getLanguageName(language)
    }
    
    private fun updateHistoryList() {
        historyAdapter.updateHistory(transcriptionHistory)
        
        // Show/hide empty state
        if (transcriptionHistory.isEmpty()) {
            showEmptyState()
        } else {
            hideEmptyState()
        }
    }
    
    private fun showEmptyState() {
        emptyStateContainer.visibility = View.VISIBLE
        historyRecyclerView.visibility = View.GONE
        emptyStateTextView.text = getString(R.string.no_transcription_history)
    }
    
    private fun hideEmptyState() {
        emptyStateContainer.visibility = View.GONE
        historyRecyclerView.visibility = View.VISIBLE
    }
    
    private fun showLoading(show: Boolean) {
        loadingIndicator.visibility = if (show) View.VISIBLE else View.GONE
        historyRecyclerView.visibility = if (show) View.GONE else View.VISIBLE
    }
    
    private fun showError() {
        showLoading(false)
        showEmptyState()
        emptyStateTextView.text = getString(R.string.error_loading_transcription_history)
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
    
    private fun animateStatusIndicator() {
        val animator = ValueAnimator.ofFloat(1f, 1.2f, 1f)
        animator.duration = 1000
        animator.repeatCount = ValueAnimator.INFINITE
        animator.addUpdateListener { animation ->
            val scale = animation.animatedValue as Float
            statusIndicator.scaleX = scale
            statusIndicator.scaleY = scale
        }
        animator.start()
    }
    
    private fun startTypingAnimation() {
        // This would be called when new text is received
        // Implementation would show typing animation for real-time updates
    }
    
    private fun stopTypingAnimation() {
        typingJob?.cancel()
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
    
    // Public methods for external interaction
    
    fun setOnStartTranscriptionListener(listener: () -> Unit) {
        onStartTranscriptionListener = listener
    }
    
    fun setOnStopTranscriptionListener(listener: () -> Unit) {
        onStopTranscriptionListener = listener
    }
    
    fun setOnPauseTranscriptionListener(listener: () -> Unit) {
        onPauseTranscriptionListener = listener
    }
    
    fun setOnResumeTranscriptionListener(listener: () -> Unit) {
        onResumeTranscriptionListener = listener
    }
    
    fun setOnClearTranscriptionListener(listener: () -> Unit) {
        onClearTranscriptionListener = listener
    }
    
    fun setOnExportTranscriptionListener(listener: () -> Unit) {
        onExportTranscriptionListener = listener
    }
    
    fun setOnTranscriptionItemClickListener(listener: (TranscriptionItem) -> Unit) {
        onTranscriptionItemClickListener = listener
    }
    
    fun updateTranscriptionText(text: String, isPartial: Boolean = false) {
        if (isPartial) {
            currentPartialText = text
        } else {
            currentTranscription += text
            currentPartialText = ""
        }
        
        updateTranscriptionDisplay()
        updateRealTimeDisplay()
    }
    
    fun updateConfidence(confidence: Float) {
        currentConfidence = confidence
        updateConfidenceDisplay()
    }
    
    fun updateLanguage(language: String) {
        updateLanguageDisplay(language)
    }
    
    fun setError(error: String) {
        updateStatus(TranscriptionStatus.ERROR)
        Toast.makeText(requireContext(), error, Toast.LENGTH_SHORT).show()
    }
    
    fun refreshHistory() {
        lifecycleScope.launch {
            loadTranscriptionHistory()
        }
    }
    
    override fun onDestroy() {
        super.onDestroy()
        typingJob?.cancel()
    }
    
    // Transcription History Adapter
    private inner class TranscriptionHistoryAdapter(
        private val onItemClick: (TranscriptionItem) -> Unit,
        private val onItemLongClick: (TranscriptionItem) -> Unit
    ) : RecyclerView.Adapter<TranscriptionHistoryAdapter.HistoryViewHolder>() {
        
        private var historyItems: List<TranscriptionItem> = emptyList()
        
        fun updateHistory(newHistory: List<TranscriptionItem>) {
            historyItems = newHistory
            notifyDataSetChanged()
        }
        
        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): HistoryViewHolder {
            val view = LayoutInflater.from(parent.context)
                .inflate(R.layout.item_transcription_history, parent, false)
            return HistoryViewHolder(view)
        }
        
        override fun onBindViewHolder(holder: HistoryViewHolder, position: Int) {
            holder.bind(historyItems[position])
        }
        
        override fun getItemCount(): Int = historyItems.size
        
        inner class HistoryViewHolder(itemView: View) : RecyclerView.ViewHolder(itemView) {
            private val rootLayout: LinearLayout = itemView.findViewById(R.id.root_layout)
            private val transcriptionTextView: TextView = itemView.findViewById(R.id.transcription_text_view)
            private val languageTextView: TextView = itemView.findViewById(R.id.language_text_view)
            private val timestampTextView: TextView = itemView.findViewById(R.id.timestamp_text_view)
            private val confidenceBar: ProgressBar = itemView.findViewById(R.id.confidence_progress_bar)
            private val confidenceTextView: TextView = itemView.findViewById(R.id.confidence_text_view)
            private val statusIndicator: ImageView = itemView.findViewById(R.id.status_indicator)
            
            fun bind(item: TranscriptionItem) {
                // Set text content
                transcriptionTextView.text = item.text
                languageTextView.text = item.languageName
                timestampTextView.text = formatTimestamp(item.timestamp)
                
                // Set confidence
                val confidencePercentage = (item.confidence * 100).toInt()
                confidenceBar.progress = confidencePercentage
                confidenceTextView.text = getString(R.string.confidence_percentage, confidencePercentage)
                
                // Set status indicator
                statusIndicator.setImageResource(
                    if (item.isFinal) R.drawable.ic_check_circle else R.drawable.ic_processing
                )
                statusIndicator.setColorFilter(
                    ContextCompat.getColor(requireContext(), if (item.isFinal) R.color.success_color else R.color.confidence_medium)
                )
                
                // Set click listeners
                rootLayout.setOnClickListener {
                    onItemClick(item)
                }
                
                rootLayout.setOnLongClickListener {
                    onItemLongClick(item)
                    true
                }
                
                // Set accessibility
                rootLayout.contentDescription = "${item.text} in ${item.languageName}"
                rootLayout.isFocusable = true
            }
        }
    }
    
    // Item decoration for spacing
    private class TranscriptionItemDecoration(context: Context) : RecyclerView.ItemDecoration() {
        private val spacing = context.resources.getDimensionPixelSize(R.dimen.transcription_item_spacing)
        
        // Implementation would add spacing between items
        // This is a placeholder for the actual decoration logic
    }
}

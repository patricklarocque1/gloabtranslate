package com.example.gloabtranslate.ui.translation

import android.animation.Animator
import android.animation.AnimatorListenerAdapter
import android.animation.ValueAnimator
import android.content.Context
import android.content.Intent
import android.graphics.Typeface
import android.os.Bundle
import android.speech.tts.TextToSpeech
import android.text.Spannable
import android.text.SpannableString
import android.text.Spanned
import android.text.style.ForegroundColorSpan
import android.text.style.StyleSpan
import android.text.style.UnderlineSpan
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
import com.example.gloabtranslate.core.data.models.TranslationResult
import dagger.android.support.AndroidSupportInjection
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import java.util.*

/**
 * Fragment for displaying translation results with interactive features,
 * animations, and accessibility support.
 * Provides comprehensive translation result visualization and interaction.
 */
class TranslationResultFragment : Fragment(), TextToSpeech.OnInitListener {
    
    override fun onAttach(context: android.content.Context) {
        AndroidSupportInjection.inject(this)
        super.onAttach(context)
    }
    
    companion object {
        private const val TAG = "TranslationResultFragment"
        private const val ARG_TRANSLATION_RESULT = "translation_result"
        private const val ARG_SOURCE_LANGUAGE = "source_language"
        private const val ARG_TARGET_LANGUAGE = "target_language"
        private const val ANIMATION_DURATION = 300L
        private const val TYPING_ANIMATION_DELAY = 50L
        
        fun newInstance(
            translationResult: TranslationResult,
            sourceLanguage: String,
            targetLanguage: String
        ): TranslationResultFragment {
            return TranslationResultFragment().apply {
                arguments = Bundle().apply {
                    putSerializable(ARG_TRANSLATION_RESULT, translationResult)
                    putString(ARG_SOURCE_LANGUAGE, sourceLanguage)
                    putString(ARG_TARGET_LANGUAGE, targetLanguage)
                }
            }
        }
    }
    
    // UI Components
    private lateinit var rootLayout: LinearLayout
    private lateinit var originalTextContainer: LinearLayout
    private lateinit var translatedTextContainer: LinearLayout
    private lateinit var originalTextView: TextView
    private lateinit var translatedTextView: TextView
    private lateinit var sourceLanguageTextView: TextView
    private lateinit var targetLanguageTextView: TextView
    private lateinit var confidenceBar: ProgressBar
    private lateinit var confidenceTextView: TextView
    private lateinit var translationStatusTextView: TextView
    private lateinit var translationTimeTextView: TextView
    private lateinit var translationModeTextView: TextView
    
    // Action buttons
    private lateinit var copyButton: ImageButton
    private lateinit var shareButton: ImageButton
    private lateinit var favoriteButton: ImageButton
    private lateinit var ttsButton: ImageButton
    private lateinit var historyButton: ImageButton
    private lateinit var settingsButton: ImageButton
    
    // Additional features
    private lateinit var alternativesRecyclerView: RecyclerView
    private lateinit var alternativesAdapter: TranslationAlternativesAdapter
    private lateinit var expandCollapseButton: Button
    private lateinit var additionalInfoContainer: LinearLayout
    
    // Text-to-Speech
    private var textToSpeech: TextToSpeech? = null
    private var isTtsInitialized = false
    
    // Animation and interaction state
    private var isExpanded = false
    private var isAnimating = false
    private var typingJob: Job? = null
    
    // Data
    private var translationResult: TranslationResult? = null
    private var sourceLanguage: String? = null
    private var targetLanguage: String? = null
    private var alternatives: List<String> = emptyList()
    
    // Listeners
    private var onTranslationResultClickListener: ((TranslationResult) -> Unit)? = null
    private var onAlternativeClickListener: ((String) -> Unit)? = null
    private var onCopyClickListener: ((String) -> Unit)? = null
    private var onShareClickListener: ((TranslationResult) -> Unit)? = null
    private var onFavoriteClickListener: ((TranslationResult) -> Unit)? = null
    
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        
        // Extract arguments
        translationResult = arguments?.getSerializable(ARG_TRANSLATION_RESULT) as? TranslationResult
        sourceLanguage = arguments?.getString(ARG_SOURCE_LANGUAGE)
        targetLanguage = arguments?.getString(ARG_TARGET_LANGUAGE)
        
        // Initialize Text-to-Speech
        textToSpeech = TextToSpeech(requireContext(), this)
    }
    
    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View? {
        return inflater.inflate(R.layout.fragment_translation_result, container, false)
    }
    
    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        
        initializeViews(view)
        setupListeners()
        setupRecyclerView()
        setupAccessibility()
        
        // Display translation result
        translationResult?.let { result ->
            displayTranslationResult(result)
        }
    }
    
    private fun initializeViews(view: View) {
        // Main containers
        rootLayout = view.findViewById(R.id.root_layout)
        originalTextContainer = view.findViewById(R.id.original_text_container)
        translatedTextContainer = view.findViewById(R.id.translated_text_container)
        additionalInfoContainer = view.findViewById(R.id.additional_info_container)
        
        // Text views
        originalTextView = view.findViewById(R.id.original_text_view)
        translatedTextView = view.findViewById(R.id.translated_text_view)
        sourceLanguageTextView = view.findViewById(R.id.source_language_text_view)
        targetLanguageTextView = view.findViewById(R.id.target_language_text_view)
        confidenceTextView = view.findViewById(R.id.confidence_text_view)
        translationStatusTextView = view.findViewById(R.id.translation_status_text_view)
        translationTimeTextView = view.findViewById(R.id.translation_time_text_view)
        translationModeTextView = view.findViewById(R.id.translation_mode_text_view)
        
        // Progress bar
        confidenceBar = view.findViewById(R.id.confidence_progress_bar)
        
        // Action buttons
        copyButton = view.findViewById(R.id.copy_button)
        shareButton = view.findViewById(R.id.share_button)
        favoriteButton = view.findViewById(R.id.favorite_button)
        ttsButton = view.findViewById(R.id.tts_button)
        historyButton = view.findViewById(R.id.history_button)
        settingsButton = view.findViewById(R.id.settings_button)
        
        // Additional features
        alternativesRecyclerView = view.findViewById(R.id.alternatives_recycler_view)
        expandCollapseButton = view.findViewById(R.id.expand_collapse_button)
    }
    
    private fun setupListeners() {
        // Text click listeners
        originalTextView.setOnClickListener {
            onTranslationResultClickListener?.invoke(translationResult ?: return@setOnClickListener)
        }
        
        translatedTextView.setOnClickListener {
            onTranslationResultClickListener?.invoke(translationResult ?: return@setOnClickListener)
        }
        
        // Action button listeners
        copyButton.setOnClickListener {
            val text = translationResult?.translatedText ?: return@setOnClickListener
            onCopyClickListener?.invoke(text)
            showCopyFeedback()
        }
        
        shareButton.setOnClickListener {
            translationResult?.let { result ->
                onShareClickListener?.invoke(result)
            }
        }
        
        favoriteButton.setOnClickListener {
            translationResult?.let { result ->
                onFavoriteClickListener?.invoke(result)
                toggleFavoriteState()
            }
        }
        
        ttsButton.setOnClickListener {
            playTextToSpeech()
        }
        
        historyButton.setOnClickListener {
            // Navigate to translation history
            navigateToTranslationHistory()
        }
        
        settingsButton.setOnClickListener {
            // Navigate to translation settings
            navigateToTranslationSettings()
        }
        
        // Expand/collapse button
        expandCollapseButton.setOnClickListener {
            toggleAdditionalInfo()
        }
    }
    
    private fun setupRecyclerView() {
        alternativesAdapter = TranslationAlternativesAdapter { alternative ->
            onAlternativeClickListener?.invoke(alternative)
            updateTranslatedText(alternative)
        }
        
        alternativesRecyclerView.apply {
            layoutManager = LinearLayoutManager(context, LinearLayoutManager.HORIZONTAL, false)
            adapter = alternativesAdapter
        }
    }
    
    private fun setupAccessibility() {
        // Set content descriptions
        copyButton.contentDescription = getString(R.string.copy_translation)
        shareButton.contentDescription = getString(R.string.share_translation)
        favoriteButton.contentDescription = getString(R.string.add_to_favorites)
        ttsButton.contentDescription = getString(R.string.play_audio)
        historyButton.contentDescription = getString(R.string.view_history)
        settingsButton.contentDescription = getString(R.string.translation_settings)
        
        // Set accessibility focus
        originalTextView.isFocusable = true
        translatedTextView.isFocusable = true
        
        // Set accessibility hints
        originalTextView.hint = getString(R.string.original_text_hint)
        translatedTextView.hint = getString(R.string.translated_text_hint)
    }
    
    private fun displayTranslationResult(result: TranslationResult) {
        // Display original text
        displayOriginalText(result.originalText)
        
        // Display translated text with typing animation
        displayTranslatedTextWithAnimation(result.translatedText)
        
        // Display language information
        displayLanguageInfo(result.sourceLanguage, result.targetLanguage)
        
        // Display confidence information
        displayConfidenceInfo(result.confidence)
        
        // Display translation status
        displayTranslationStatus(result.success, result.error)
        
        // Display translation metadata
        displayTranslationMetadata(result)
        
        // Display alternatives if available
        displayAlternatives(result.alternatives)
        
        // Set initial button states
        updateButtonStates(result)
        
        // Start entrance animation
        startEntranceAnimation()
    }
    
    private fun displayOriginalText(originalText: String?) {
        originalText?.let { text ->
            originalTextView.text = text
            originalTextContainer.visibility = View.VISIBLE
        } ?: run {
            originalTextContainer.visibility = View.GONE
        }
    }
    
    private fun displayTranslatedTextWithAnimation(translatedText: String?) {
        translatedText?.let { text ->
            // Cancel any existing typing animation
            typingJob?.cancel()
            
            // Clear the text view
            translatedTextView.text = ""
            
            // Start typing animation
            typingJob = lifecycleScope.launch {
                for (i in 0..text.length) {
                    if (isActive) {
                        val partialText = text.substring(0, i)
                        translatedTextView.text = partialText
                        delay(TYPING_ANIMATION_DELAY)
                    }
                }
                
                // Add emphasis to the final text
                addTextEmphasis(translatedTextView, text)
            }
            
            translatedTextContainer.visibility = View.VISIBLE
        } ?: run {
            translatedTextContainer.visibility = View.GONE
        }
    }
    
    private fun displayLanguageInfo(sourceLang: String?, targetLang: String?) {
        sourceLanguageTextView.text = sourceLang ?: getString(R.string.unknown_language)
        targetLanguageTextView.text = targetLang ?: getString(R.string.unknown_language)
        
        // Add language flags or icons if available
        addLanguageVisualIndicators(sourceLang, targetLang)
    }
    
    private fun displayConfidenceInfo(confidence: Float?) {
        confidence?.let { conf ->
            val percentage = (conf * 100).toInt()
            
            // Update progress bar
            confidenceBar.progress = percentage
            
            // Update text
            confidenceTextView.text = getString(R.string.confidence_percentage, percentage)
            
            // Set color based on confidence level
            val color = when {
                conf >= 0.9f -> ContextCompat.getColor(requireContext(), R.color.confidence_high)
                conf >= 0.7f -> ContextCompat.getColor(requireContext(), R.color.confidence_medium)
                else -> ContextCompat.getColor(requireContext(), R.color.confidence_low)
            }
            
            confidenceTextView.setTextColor(color)
            confidenceBar.progressTintList = ContextCompat.getColorStateList(requireContext(), color)
            
            // Show confidence info
            confidenceBar.visibility = View.VISIBLE
            confidenceTextView.visibility = View.VISIBLE
        } ?: run {
            confidenceBar.visibility = View.GONE
            confidenceTextView.visibility = View.GONE
        }
    }
    
    private fun displayTranslationStatus(success: Boolean, error: String?) {
        if (success) {
            translationStatusTextView.text = getString(R.string.translation_successful)
            translationStatusTextView.setTextColor(
                ContextCompat.getColor(requireContext(), R.color.success_color)
            )
        } else {
            translationStatusTextView.text = error ?: getString(R.string.translation_failed)
            translationStatusTextView.setTextColor(
                ContextCompat.getColor(requireContext(), R.color.error_color)
            )
        }
        
        translationStatusTextView.visibility = View.VISIBLE
    }
    
    private fun displayTranslationMetadata(result: TranslationResult) {
        // Display translation time
        result.timestamp?.let { timestamp ->
            val timeAgo = getTimeAgo(timestamp)
            translationTimeTextView.text = getString(R.string.translated_time_ago, timeAgo)
            translationTimeTextView.visibility = View.VISIBLE
        }
        
        // Display translation mode
        val mode = if (result.isOnDevice) {
            getString(R.string.on_device_translation)
        } else {
            getString(R.string.cloud_translation)
        }
        translationModeTextView.text = mode
        translationModeTextView.visibility = View.VISIBLE
    }
    
    private fun displayAlternatives(alternatives: List<String>?) {
        this.alternatives = alternatives ?: emptyList()
        
        if (this.alternatives.isNotEmpty()) {
            alternativesAdapter.updateAlternatives(this.alternatives)
            alternativesRecyclerView.visibility = View.VISIBLE
        } else {
            alternativesRecyclerView.visibility = View.GONE
        }
    }
    
    private fun updateButtonStates(result: TranslationResult) {
        // Enable/disable buttons based on result state
        copyButton.isEnabled = !result.translatedText.isNullOrEmpty()
        shareButton.isEnabled = result.success
        ttsButton.isEnabled = !result.translatedText.isNullOrEmpty() && isTtsInitialized
        
        // Update favorite button state
        updateFavoriteButtonState(result.isFavorite)
    }
    
    private fun updateFavoriteButtonState(isFavorite: Boolean) {
        val icon = if (isFavorite) {
            R.drawable.ic_favorite_filled
        } else {
            R.drawable.ic_favorite_border
        }
        favoriteButton.setImageResource(icon)
        favoriteButton.contentDescription = if (isFavorite) {
            getString(R.string.remove_from_favorites)
        } else {
            getString(R.string.add_to_favorites)
        }
    }
    
    private fun startEntranceAnimation() {
        // Fade in animation
        rootLayout.alpha = 0f
        rootLayout.animate()
            .alpha(1f)
            .setDuration(ANIMATION_DURATION)
            .setInterpolator(AccelerateDecelerateInterpolator())
            .start()
        
        // Slide up animation for containers
        originalTextContainer.translationY = 50f
        translatedTextContainer.translationY = 50f
        
        originalTextContainer.animate()
            .translationY(0f)
            .setDuration(ANIMATION_DURATION)
            .setStartDelay(100L)
            .setInterpolator(AccelerateDecelerateInterpolator())
            .start()
        
        translatedTextContainer.animate()
            .translationY(0f)
            .setDuration(ANIMATION_DURATION)
            .setStartDelay(200L)
            .setInterpolator(AccelerateDecelerateInterpolator())
            .start()
    }
    
    private fun toggleAdditionalInfo() {
        if (isAnimating) return
        
        isAnimating = true
        
        val targetHeight = if (isExpanded) 0 else getAdditionalInfoHeight()
        val targetAlpha = if (isExpanded) 0f else 1f
        
        // Animate height
        val heightAnimator = ValueAnimator.ofInt(
            additionalInfoContainer.height,
            targetHeight
        )
        heightAnimator.addUpdateListener { animator ->
            val height = animator.animatedValue as Int
            additionalInfoContainer.layoutParams.height = height
            additionalInfoContainer.requestLayout()
        }
        
        // Animate alpha
        val alphaAnimator = ValueAnimator.ofFloat(
            additionalInfoContainer.alpha,
            targetAlpha
        )
        alphaAnimator.addUpdateListener { animator ->
            additionalInfoContainer.alpha = animator.animatedValue as Float
        }
        
        // Start animations
        heightAnimator.duration = ANIMATION_DURATION
        alphaAnimator.duration = ANIMATION_DURATION
        
        heightAnimator.addListener(object : AnimatorListenerAdapter() {
            override fun onAnimationEnd(animation: Animator) {
                isAnimating = false
                isExpanded = !isExpanded
                updateExpandCollapseButton()
            }
        })
        
        heightAnimator.start()
        alphaAnimator.start()
    }
    
    private fun getAdditionalInfoHeight(): Int {
        additionalInfoContainer.measure(
            View.MeasureSpec.makeMeasureSpec(0, View.MeasureSpec.UNSPECIFIED),
            View.MeasureSpec.makeMeasureSpec(0, View.MeasureSpec.UNSPECIFIED)
        )
        return additionalInfoContainer.measuredHeight
    }
    
    private fun updateExpandCollapseButton() {
        val text = if (isExpanded) {
            getString(R.string.show_less)
        } else {
            getString(R.string.show_more)
        }
        expandCollapseButton.text = text
        
        val icon = if (isExpanded) {
            R.drawable.ic_expand_less
        } else {
            R.drawable.ic_expand_more
        }
        expandCollapseButton.setCompoundDrawablesWithIntrinsicBounds(0, 0, icon, 0)
    }
    
    private fun addTextEmphasis(textView: TextView, text: String) {
        val spannable = SpannableString(text)
        
        // Add underline to important words (simple heuristic)
        val words = text.split(" ")
        words.forEach { word ->
            if (word.length > 4) {
                val start = text.indexOf(word)
                val end = start + word.length
                spannable.setSpan(
                    UnderlineSpan(),
                    start,
                    end,
                    Spanned.SPAN_EXCLUSIVE_EXCLUSIVE
                )
            }
        }
        
        textView.text = spannable
    }
    
    private fun addLanguageVisualIndicators(sourceLang: String?, targetLang: String?) {
        // Add language flags or icons
        // This would typically involve loading flag images or using icon fonts
        // For now, we'll add visual indicators using text styling
        
        sourceLang?.let { lang ->
            val flag = getLanguageFlag(lang)
            sourceLanguageTextView.text = "$flag $lang"
        }
        
        targetLang?.let { lang ->
            val flag = getLanguageFlag(lang)
            targetLanguageTextView.text = "$flag $lang"
        }
    }
    
    private fun getLanguageFlag(languageCode: String): String {
        // Simple flag emoji mapping
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
            else -> "🌐"
        }
    }
    
    private fun getTimeAgo(timestamp: Long): String {
        val now = System.currentTimeMillis()
        val diff = now - timestamp
        
        return when {
            diff < 60_000 -> getString(R.string.just_now)
            diff < 3600_000 -> getString(R.string.minutes_ago, (diff / 60_000).toInt())
            diff < 86400_000 -> getString(R.string.hours_ago, (diff / 3600_000).toInt())
            else -> getString(R.string.days_ago, (diff / 86400_000).toInt())
        }
    }
    
    private fun showCopyFeedback() {
        val toast = Toast.makeText(context, getString(R.string.text_copied), Toast.LENGTH_SHORT)
        toast.show()
        
        // Add haptic feedback
        copyButton.performHapticFeedback(android.view.HapticFeedbackConstants.VIRTUAL_KEY)
    }
    
    private fun toggleFavoriteState() {
        val currentState = translationResult?.isFavorite ?: false
        updateFavoriteButtonState(!currentState)
        
        // Add haptic feedback
        favoriteButton.performHapticFeedback(android.view.HapticFeedbackConstants.VIRTUAL_KEY)
    }
    
    private fun updateTranslatedText(newText: String) {
        // Update the translated text with animation
        translatedTextView.text = newText
        addTextEmphasis(translatedTextView, newText)
        
        // Update the translation result
        translationResult = translationResult?.copy(translatedText = newText)
    }
    
    private fun playTextToSpeech() {
        val text = translationResult?.translatedText ?: return
        
        if (isTtsInitialized) {
            textToSpeech?.speak(text, TextToSpeech.QUEUE_FLUSH, null, null)
        }
    }
    
    private fun navigateToTranslationHistory() {
        // Navigate to translation history
        // Implementation would depend on your navigation setup
    }
    
    private fun navigateToTranslationSettings() {
        // Navigate to translation settings
        // Implementation would depend on your navigation setup
    }
    
    // TextToSpeech.OnInitListener
    override fun onInit(status: Int) {
        isTtsInitialized = status == TextToSpeech.SUCCESS
        if (isTtsInitialized) {
            // Set language for TTS
            targetLanguage?.let { lang ->
                val locale = Locale.forLanguageTag(lang)
                textToSpeech?.language = locale
            }
        }
        
        // Update TTS button state
        ttsButton.isEnabled = isTtsInitialized && !translationResult?.translatedText.isNullOrEmpty()
    }
    
    // Public methods for external interaction
    
    fun setOnTranslationResultClickListener(listener: (TranslationResult) -> Unit) {
        onTranslationResultClickListener = listener
    }
    
    fun setOnAlternativeClickListener(listener: (String) -> Unit) {
        onAlternativeClickListener = listener
    }
    
    fun setOnCopyClickListener(listener: (String) -> Unit) {
        onCopyClickListener = listener
    }
    
    fun setOnShareClickListener(listener: (TranslationResult) -> Unit) {
        onShareClickListener = listener
    }
    
    fun setOnFavoriteClickListener(listener: (TranslationResult) -> Unit) {
        onFavoriteClickListener = listener
    }
    
    fun updateTranslationResult(newResult: TranslationResult) {
        translationResult = newResult
        displayTranslationResult(newResult)
    }
    
    fun setExpanded(expanded: Boolean) {
        if (isExpanded != expanded) {
            toggleAdditionalInfo()
        }
    }
    
    override fun onDestroy() {
        super.onDestroy()
        
        // Cancel typing animation
        typingJob?.cancel()
        
        // Shutdown Text-to-Speech
        textToSpeech?.shutdown()
    }
    
    // Adapter for translation alternatives
    private inner class TranslationAlternativesAdapter(
        private val onAlternativeClick: (String) -> Unit
    ) : RecyclerView.Adapter<TranslationAlternativesAdapter.AlternativeViewHolder>() {
        
        private var alternatives: List<String> = emptyList()
        
        fun updateAlternatives(newAlternatives: List<String>) {
            alternatives = newAlternatives
            notifyDataSetChanged()
        }
        
        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): AlternativeViewHolder {
            val view = LayoutInflater.from(parent.context)
                .inflate(R.layout.item_translation_alternative, parent, false)
            return AlternativeViewHolder(view)
        }
        
        override fun onBindViewHolder(holder: AlternativeViewHolder, position: Int) {
            holder.bind(alternatives[position])
        }
        
        override fun getItemCount(): Int = alternatives.size
        
        inner class AlternativeViewHolder(itemView: View) : RecyclerView.ViewHolder(itemView) {
            private val textView: TextView = itemView.findViewById(R.id.alternative_text_view)
            
            fun bind(alternative: String) {
                textView.text = alternative
                textView.setOnClickListener {
                    onAlternativeClick(alternative)
                }
            }
        }
    }
}

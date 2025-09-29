package com.example.gloabtranslate.ui.components

import android.animation.Animator
import android.animation.AnimatorListenerAdapter
import android.animation.AnimatorSet
import android.animation.ObjectAnimator
import android.animation.ValueAnimator
import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.RectF
import android.util.AttributeSet
import android.util.Log
import android.view.View
import android.view.animation.AccelerateDecelerateInterpolator
import android.view.animation.LinearInterpolator
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.TextView
import androidx.core.content.ContextCompat
import com.example.gloabtranslate.R

/**
 * Comprehensive progress indicator component for long operations
 * with multiple display types, animations, and customization options.
 */
class ProgressIndicator @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0
) : FrameLayout(context, attrs, defStyleAttr) {
    
    companion object {
        private const val TAG = "ProgressIndicator"
        private const val DEFAULT_ANIMATION_DURATION = 300L
        private const val DEFAULT_SPIN_DURATION = 1000L
        private const val DEFAULT_PULSE_DURATION = 1500L
        private const val DEFAULT_PROGRESS_UPDATE_INTERVAL = 100L
    }
    
    // UI Components
    private lateinit var progressContainer: LinearLayout
    private lateinit var progressBar: ProgressBar
    private lateinit var circularProgressView: CircularProgressView
    private lateinit var indeterminateProgressView: IndeterminateProgressView
    private lateinit var progressImageView: ImageView
    private lateinit var progressTextView: TextView
    private lateinit var progressSubTextView: TextView
    private lateinit var progressPercentageTextView: TextView
    private lateinit var progressCancelButton: TextView
    
    // State management
    private var progressType: ProgressType = ProgressType.LINEAR
    private var progressValue: Int = 0
    private var maxProgress: Int = 100
    private var isIndeterminate: Boolean = false
    private var isVisible: Boolean = false
    private var isCancellable: Boolean = false
    private var progressText: String = ""
    private var progressSubText: String = ""
    private var progressIcon: Int = 0
    
    // Animation management
    private var currentAnimator: Animator? = null
    private var spinAnimator: Animator? = null
    private var pulseAnimator: Animator? = null
    private var fadeAnimator: Animator? = null
    
    // Callbacks
    private var onProgressUpdateListener: ((Int, Int) -> Unit)? = null
    private var onProgressCompleteListener: (() -> Unit)? = null
    private var onProgressCancelListener: (() -> Unit)? = null
    private var onVisibilityChangeListener: ((Boolean) -> Unit)? = null
    
    /**
     * Progress display types
     */
    enum class ProgressType {
        LINEAR,
        CIRCULAR,
        INDETERMINATE,
        PULSE,
        CUSTOM
    }
    
    /**
     * Progress states
     */
    enum class ProgressState {
        HIDDEN,
        SHOWING,
        VISIBLE,
        HIDING,
        COMPLETED,
        ERROR,
        CANCELLED
    }
    
    init {
        initializeView()
        setupAttributes(attrs)
        setupListeners()
    }
    
    private fun initializeView() {
        // Inflate the layout
        View.inflate(context, R.layout.component_progress_indicator, this)
        
        // Initialize UI components
        progressContainer = findViewById(R.id.progress_container)
        progressBar = findViewById(R.id.progress_bar)
        circularProgressView = findViewById(R.id.circular_progress_view)
        indeterminateProgressView = findViewById(R.id.indeterminate_progress_view)
        progressImageView = findViewById(R.id.progress_image_view)
        progressTextView = findViewById(R.id.progress_text_view)
        progressSubTextView = findViewById(R.id.progress_sub_text_view)
        progressPercentageTextView = findViewById(R.id.progress_percentage_text_view)
        progressCancelButton = findViewById(R.id.progress_cancel_button)
        
        // Set initial visibility
        visibility = View.GONE
        isVisible = false
    }
    
    private fun setupAttributes(attrs: AttributeSet?) {
        attrs?.let { attributeSet ->
            val typedArray = context.obtainStyledAttributes(attributeSet, R.styleable.ProgressIndicator)
            
            try {
                // Progress type
                val typeOrdinal = typedArray.getInt(R.styleable.ProgressIndicator_progressType, 0)
                progressType = ProgressType.values()[typeOrdinal]
                
                // Progress values
                progressValue = typedArray.getInt(R.styleable.ProgressIndicator_progress, 0)
                maxProgress = typedArray.getInt(R.styleable.ProgressIndicator_max, 100)
                isIndeterminate = typedArray.getBoolean(R.styleable.ProgressIndicator_indeterminate, false)
                isCancellable = typedArray.getBoolean(R.styleable.ProgressIndicator_cancellable, false)
                
                // Progress text
                progressText = typedArray.getString(R.styleable.ProgressIndicator_progressText) ?: ""
                progressSubText = typedArray.getString(R.styleable.ProgressIndicator_progressSubText) ?: ""
                
                // Progress icon
                progressIcon = typedArray.getResourceId(R.styleable.ProgressIndicator_progressIcon, 0)
                
                // Apply attributes
                updateProgressType()
                updateProgressValues()
                updateProgressText()
                updateProgressIcon()
                updateCancellableState()
                
            } finally {
                typedArray.recycle()
            }
        }
    }
    
    private fun setupListeners() {
        progressCancelButton.setOnClickListener {
            onProgressCancelListener?.invoke()
            cancelProgress()
        }
    }
    
    // Public API methods
    
    /**
     * Show the progress indicator with animation
     */
    fun show(animated: Boolean = true) {
        if (isVisible) return
        
        isVisible = true
        onVisibilityChangeListener?.invoke(true)
        
        if (animated) {
            animateIn()
        } else {
            visibility = View.VISIBLE
            alpha = 1f
        }
    }
    
    /**
     * Hide the progress indicator with animation
     */
    fun hide(animated: Boolean = true) {
        if (!isVisible) return
        
        isVisible = false
        onVisibilityChangeListener?.invoke(false)
        
        if (animated) {
            animateOut()
        } else {
            visibility = View.GONE
            alpha = 0f
        }
    }
    
    /**
     * Set progress value (0-100)
     */
    fun setProgress(progress: Int, animated: Boolean = true) {
        val clampedProgress = progress.coerceIn(0, maxProgress)
        
        if (progressValue != clampedProgress) {
            progressValue = clampedProgress
            updateProgressDisplay(animated)
            onProgressUpdateListener?.invoke(progressValue, maxProgress)
            
            // Check if completed
            if (progressValue >= maxProgress && !isIndeterminate) {
                completeProgress()
            }
        }
    }
    
    /**
     * Set progress with text
     */
    fun setProgress(progress: Int, text: String, animated: Boolean = true) {
        setProgressText(text)
        setProgress(progress, animated)
    }
    
    /**
     * Set progress with text and subtext
     */
    fun setProgress(progress: Int, text: String, subText: String, animated: Boolean = true) {
        setProgressText(text)
        setProgressSubText(subText)
        setProgress(progress, animated)
    }
    
    /**
     * Set indeterminate progress
     */
    fun setIndeterminate(indeterminate: Boolean) {
        if (isIndeterminate != indeterminate) {
            isIndeterminate = indeterminate
            updateProgressType()
            
            if (indeterminate) {
                startIndeterminateAnimation()
            } else {
                stopIndeterminateAnimation()
            }
        }
    }
    
    /**
     * Set progress text
     */
    fun setProgressText(text: String) {
        if (progressText != text) {
            progressText = text
            updateProgressText()
        }
    }
    
    /**
     * Set progress subtext
     */
    fun setProgressSubText(subText: String) {
        if (progressSubText != subText) {
            progressSubText = subText
            updateProgressSubText()
        }
    }
    
    /**
     * Set progress icon
     */
    fun setProgressIcon(iconRes: Int) {
        if (progressIcon != iconRes) {
            progressIcon = iconRes
            updateProgressIcon()
        }
    }
    
    /**
     * Set progress type
     */
    fun setProgressType(type: ProgressType) {
        if (progressType != type) {
            progressType = type
            updateProgressType()
        }
    }
    
    /**
     * Set cancellable state
     */
    fun setCancellable(cancellable: Boolean) {
        if (isCancellable != cancellable) {
            isCancellable = cancellable
            updateCancellableState()
        }
    }
    
    /**
     * Complete the progress
     */
    fun completeProgress() {
        setProgress(maxProgress)
        onProgressCompleteListener?.invoke()
        
        // Auto-hide after completion
        postDelayed({
            hide()
        }, 1000)
    }
    
    /**
     * Cancel the progress
     */
    fun cancelProgress() {
        hide()
        onProgressCancelListener?.invoke()
    }
    
    /**
     * Set error state
     */
    fun setError(errorText: String) {
        setProgressText(errorText)
        setProgressIcon(R.drawable.ic_error)
        progressTextView.setTextColor(ContextCompat.getColor(context, R.color.error_color))
        
        // Auto-hide after error
        postDelayed({
            hide()
        }, 3000)
    }
    
    // Callback setters
    
    fun setOnProgressUpdateListener(listener: (Int, Int) -> Unit) {
        onProgressUpdateListener = listener
    }
    
    fun setOnProgressCompleteListener(listener: () -> Unit) {
        onProgressCompleteListener = listener
    }
    
    fun setOnProgressCancelListener(listener: () -> Unit) {
        onProgressCancelListener = listener
    }
    
    fun setOnVisibilityChangeListener(listener: (Boolean) -> Unit) {
        onVisibilityChangeListener = listener
    }
    
    // Private helper methods
    
    private fun updateProgressType() {
        // Hide all progress views
        progressBar.visibility = View.GONE
        circularProgressView.visibility = View.GONE
        indeterminateProgressView.visibility = View.GONE
        progressImageView.visibility = View.GONE
        
        // Show appropriate progress view
        when (progressType) {
            ProgressType.LINEAR -> {
                progressBar.visibility = View.VISIBLE
                progressBar.max = maxProgress
                progressBar.progress = progressValue
            }
            ProgressType.CIRCULAR -> {
                circularProgressView.visibility = View.VISIBLE
                circularProgressView.setMaxProgress(maxProgress)
                circularProgressView.setProgress(progressValue)
            }
            ProgressType.INDETERMINATE -> {
                indeterminateProgressView.visibility = View.VISIBLE
                startIndeterminateAnimation()
            }
            ProgressType.PULSE -> {
                progressImageView.visibility = View.VISIBLE
                startPulseAnimation()
            }
            ProgressType.CUSTOM -> {
                // Custom progress view - handled by external implementation
            }
        }
    }
    
    private fun updateProgressValues() {
        when (progressType) {
            ProgressType.LINEAR -> {
                progressBar.max = maxProgress
                progressBar.progress = progressValue
            }
            ProgressType.CIRCULAR -> {
                circularProgressView.setMaxProgress(maxProgress)
                circularProgressView.setProgress(progressValue)
            }
            ProgressType.INDETERMINATE, ProgressType.PULSE, ProgressType.CUSTOM -> {
                // No specific value updates for these types here
            }
        }
        
        updateProgressPercentage()
    }
    
    private fun updateProgressDisplay(animated: Boolean) {
        when (progressType) {
            ProgressType.LINEAR -> {
                if (animated) {
                    animateProgressBar(progressBar.progress, progressValue)
                } else {
                    progressBar.progress = progressValue
                }
            }
            ProgressType.CIRCULAR -> {
                circularProgressView.setProgress(progressValue, animated)
            }
            ProgressType.INDETERMINATE -> {
                // Indeterminate progress doesn't have specific values
            }
            ProgressType.PULSE -> {
                // Pulse animation is continuous
            }
            ProgressType.CUSTOM -> {
                // Custom progress handling
            }
        }
        
        updateProgressPercentage()
    }
    
    private fun updateProgressText() {
        progressTextView.text = progressText
        progressTextView.visibility = if (progressText.isBlank()) View.GONE else View.VISIBLE
    }
    
    private fun updateProgressSubText() {
        progressSubTextView.text = progressSubText
        progressSubTextView.visibility = if (progressSubText.isBlank()) View.GONE else View.VISIBLE
    }
    
    private fun updateProgressIcon() {
        if (progressIcon != 0) {
            progressImageView.setImageResource(progressIcon)
            progressImageView.visibility = View.VISIBLE
        } else {
            progressImageView.visibility = View.GONE
        }
    }
    
    private fun updateProgressPercentage() {
        if (!isIndeterminate && progressType != ProgressType.PULSE) {
            val percentage = ((progressValue.toFloat() / maxProgress) * 100).toInt()
            progressPercentageTextView.text = "$percentage%"
            progressPercentageTextView.visibility = View.VISIBLE
        } else {
            progressPercentageTextView.visibility = View.GONE
        }
    }
    
    private fun updateCancellableState() {
        progressCancelButton.visibility = if (isCancellable) View.VISIBLE else View.GONE
    }
    
    // Animation methods
    
    private fun animateIn() {
        visibility = View.VISIBLE
        alpha = 0f
        scaleX = 0.8f
        scaleY = 0.8f
        
        val alphaAnimator = ObjectAnimator.ofFloat(this, "alpha", 0f, 1f)
        val scaleXAnimator = ObjectAnimator.ofFloat(this, "scaleX", 0.8f, 1f)
        val scaleYAnimator = ObjectAnimator.ofFloat(this, "scaleY", 0.8f, 1f)
        
        val animatorSet = AnimatorSet()
        animatorSet.playTogether(alphaAnimator, scaleXAnimator, scaleYAnimator)
        animatorSet.duration = DEFAULT_ANIMATION_DURATION
        animatorSet.interpolator = AccelerateDecelerateInterpolator()
        
        animatorSet.addListener(object : AnimatorListenerAdapter() {
            override fun onAnimationEnd(animation: Animator) {
                fadeAnimator = null
            }
        })
        
        fadeAnimator?.cancel()
        fadeAnimator = animatorSet
        animatorSet.start()
    }
    
    private fun animateOut() {
        val alphaAnimator = ObjectAnimator.ofFloat(this, "alpha", 1f, 0f)
        val scaleXAnimator = ObjectAnimator.ofFloat(this, "scaleX", 1f, 0.8f)
        val scaleYAnimator = ObjectAnimator.ofFloat(this, "scaleY", 1f, 0.8f)
        
        val animatorSet = AnimatorSet()
        animatorSet.playTogether(alphaAnimator, scaleXAnimator, scaleYAnimator)
        animatorSet.duration = DEFAULT_ANIMATION_DURATION
        animatorSet.interpolator = AccelerateDecelerateInterpolator()
        
        animatorSet.addListener(object : AnimatorListenerAdapter() {
            override fun onAnimationEnd(animation: Animator) {
                visibility = View.GONE
                fadeAnimator = null
            }
        })
        
        fadeAnimator?.cancel()
        fadeAnimator = animatorSet
        animatorSet.start()
    }
    
    private fun animateProgressBar(fromProgress: Int, toProgress: Int) {
        val animator = ValueAnimator.ofInt(fromProgress, toProgress)
        animator.duration = DEFAULT_PROGRESS_UPDATE_INTERVAL
        animator.addUpdateListener { animation ->
            val progress = animation.animatedValue as Int
            progressBar.progress = progress
        }
        animator.start()
    }
    
    private fun startIndeterminateAnimation() {
        when (progressType) {
            ProgressType.INDETERMINATE -> {
                indeterminateProgressView.startAnimation()
            }
            else -> {
                // Spin animation for other types
                startSpinAnimation()
            }
        }
    }
    
    private fun stopIndeterminateAnimation() {
        when (progressType) {
            ProgressType.INDETERMINATE -> {
                indeterminateProgressView.stopAnimation()
            }
            else -> {
                stopSpinAnimation()
            }
        }
    }
    
    private fun startSpinAnimation() {
        val rotationAnimator = ObjectAnimator.ofFloat(progressImageView, "rotation", 0f, 360f)
        rotationAnimator.duration = DEFAULT_SPIN_DURATION
        rotationAnimator.repeatCount = ValueAnimator.INFINITE // This line seems fine for ObjectAnimator
        rotationAnimator.interpolator = LinearInterpolator()
        
        spinAnimator?.cancel()
        spinAnimator = rotationAnimator
        rotationAnimator.start()
    }
    
    private fun stopSpinAnimation() {
        spinAnimator?.cancel()
        spinAnimator = null
    }
    
    private fun startPulseAnimation() {
        val scaleXAnimator = ObjectAnimator.ofFloat(progressImageView, "scaleX", 1f, 1.2f, 1f)
        val scaleYAnimator = ObjectAnimator.ofFloat(progressImageView, "scaleY", 1f, 1.2f, 1f)
        val alphaAnimator = ObjectAnimator.ofFloat(progressImageView, "alpha", 1f, 0.7f, 1f)
        
        // Set repeat count on individual animators
        scaleXAnimator.repeatCount = ValueAnimator.INFINITE
        scaleYAnimator.repeatCount = ValueAnimator.INFINITE
        alphaAnimator.repeatCount = ValueAnimator.INFINITE
        
        val animatorSet = AnimatorSet()
        animatorSet.playTogether(scaleXAnimator, scaleYAnimator, alphaAnimator)
        animatorSet.duration = DEFAULT_PULSE_DURATION
        animatorSet.interpolator = AccelerateDecelerateInterpolator()
        
        pulseAnimator?.cancel()
        pulseAnimator = animatorSet
        animatorSet.start()
    }
    
    private fun stopPulseAnimation() {
        pulseAnimator?.cancel()
        pulseAnimator = null
    }
    
    // Cleanup
    
    override fun onDetachedFromWindow() {
        super.onDetachedFromWindow()
        cleanupAnimations()
    }
    
    private fun cleanupAnimations() {
        fadeAnimator?.cancel()
        spinAnimator?.cancel()
        pulseAnimator?.cancel()
        currentAnimator?.cancel()
        
        fadeAnimator = null
        spinAnimator = null
        pulseAnimator = null
        currentAnimator = null
    }
    
    // Custom progress views
    
    /**
     * Circular progress view with custom drawing
     */
    private class CircularProgressView @JvmOverloads constructor(
        context: Context,
        attrs: AttributeSet? = null,
        defStyleAttr: Int = 0
    ) : View(context, attrs, defStyleAttr) {
        
        private val backgroundPaint = Paint(Paint.ANTI_ALIAS_FLAG)
        private val progressPaint = Paint(Paint.ANTI_ALIAS_FLAG)
        private val textPaint = Paint(Paint.ANTI_ALIAS_FLAG)
        private val rectF = RectF()
        
        private var maxProgress = 100
        private var progress = 0
        private var strokeWidth = 8f
        private var centerX = 0f
        private var centerY = 0f
        private var radius = 0f
        
        init {
            setupPaints()
        }
        
        private fun setupPaints() {
            backgroundPaint.apply {
                color = ContextCompat.getColor(context, R.color.surface_variant)
                style = Paint.Style.STROKE
                strokeWidth = this@CircularProgressView.strokeWidth
            }
            
            progressPaint.apply {
                color = ContextCompat.getColor(context, R.color.primary)
                style = Paint.Style.STROKE
                strokeWidth = this@CircularProgressView.strokeWidth
                strokeCap = Paint.Cap.ROUND
            }
            
            textPaint.apply {
                color = ContextCompat.getColor(context, R.color.text_primary)
                textSize = 24f
                textAlign = Paint.Align.CENTER
            }
        }
        
        override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
            super.onSizeChanged(w, h, oldw, oldh)
            
            centerX = w / 2f
            centerY = h / 2f
            radius = minOf(w, h) / 2f - strokeWidth / 2f
            
            rectF.set(
                centerX - radius,
                centerY - radius,
                centerX + radius,
                centerY + radius
            )
        }
        
        override fun onDraw(canvas: Canvas) {
            super.onDraw(canvas)
            
            // Draw background circle
            canvas.drawCircle(centerX, centerY, radius, backgroundPaint)
            
            // Draw progress arc
            val sweepAngle = (progress.toFloat() / maxProgress) * 360f
            canvas.drawArc(rectF, -90f, sweepAngle, false, progressPaint)
            
            // Draw progress text
            val percentage = ((progress.toFloat() / maxProgress) * 100).toInt()
            val textY = centerY + (textPaint.descent() + textPaint.ascent()) / 2
            canvas.drawText("$percentage%", centerX, textY, textPaint)
        }
        
        fun setMaxProgress(max: Int) {
            maxProgress = max
            invalidate()
        }
        
        fun setProgress(progress: Int, animated: Boolean = false) {
            this.progress = progress.coerceIn(0, maxProgress)
            
            if (animated) {
                // Animate progress change
                val animator = ValueAnimator.ofInt(0, this.progress)
                animator.duration = 500
                animator.addUpdateListener { animation ->
                    val animatedProgress = animation.animatedValue as Int
                    this.progress = animatedProgress
                    invalidate()
                }
                animator.start()
            } else {
                invalidate()
            }
        }
    }
    
    /**
     * Indeterminate progress view with animated dots
     */
    private class IndeterminateProgressView @JvmOverloads constructor(
        context: Context,
        attrs: AttributeSet? = null,
        defStyleAttr: Int = 0
    ) : View(context, attrs, defStyleAttr) {
        
        private val dotPaint = Paint(Paint.ANTI_ALIAS_FLAG)
        private val dots = mutableListOf<Dot>()
        private var animationRunning = false
        
        private data class Dot(
            var x: Float,
            var y: Float,
            var radius: Float,
            var alpha: Float
        )
        
        init {
            setupPaint()
            setupDots()
        }
        
        private fun setupPaint() {
            dotPaint.apply {
                color = ContextCompat.getColor(context, R.color.primary)
                style = Paint.Style.FILL
            }
        }
        
        private fun setupDots() {
            val dotCount = 3
            val spacing = 20f
            val totalWidth = (dotCount - 1) * spacing
            val startX = (width - totalWidth) / 2f
            val centerY = height / 2f
            
            for (i in 0 until dotCount) {
                dots.add(
                    Dot(
                        x = startX + i * spacing,
                        y = centerY,
                        radius = 6f,
                        alpha = 0.3f
                    )
                )
            }
        }
        
        override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
            super.onSizeChanged(w, h, oldw, oldh)
            setupDots()
        }
        
        override fun onDraw(canvas: Canvas) {
            super.onDraw(canvas)
            
            dots.forEach { dot ->
                dotPaint.alpha = (dot.alpha * 255).toInt()
                canvas.drawCircle(dot.x, dot.y, dot.radius, dotPaint)
            }
        }
        
        fun startAnimation() {
            if (animationRunning) return
            
            animationRunning = true
            animateDots()
        }
        
        fun stopAnimation() {
            animationRunning = false
        }
        
        private fun animateDots() {
            if (!animationRunning) return
            
            val animator = ValueAnimator.ofFloat(0f, 1f)
            animator.duration = 1000
            animator.repeatCount = ValueAnimator.INFINITE
            animator.addUpdateListener { animation ->
                val progress = animation.animatedValue as Float
                
                dots.forEachIndexed { index, dot ->
                    val dotProgress = (progress + index * 0.33f) % 1f
                    dot.alpha = when {
                        dotProgress < 0.33f -> 0.3f + (dotProgress / 0.33f) * 0.7f
                        dotProgress < 0.66f -> 1f
                        else -> 1f - ((dotProgress - 0.66f) / 0.34f) * 0.7f
                    }
                }
                
                invalidate()
            }
            
            animator.start()
        }
    }
}

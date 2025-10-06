package com.example.gloabtranslate

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.TextView
import androidx.fragment.app.Fragment
import androidx.navigation.fragment.findNavController
import com.example.gloabtranslate.service.LiveTranslateService
import dagger.android.support.AndroidSupportInjection
import javax.inject.Inject

/**
 * Main fragment that serves as the home screen for the Global Translate app.
 * Provides access to all main features and shows service status.
 */
class MainFragment : Fragment() {

    // Service access through MainActivity
    private val liveTranslateService: LiveTranslateService?
        get() = (activity as? MainActivity)?.getLiveTranslateService()

    // UI elements
    private lateinit var statusText: TextView
    private lateinit var recognitionStatusText: TextView
    private lateinit var recommendedActionText: TextView
    private lateinit var startButton: Button
    private lateinit var stopButton: Button
    private lateinit var transcriptionButton: Button
    private lateinit var historyButton: Button
    private lateinit var settingsButton: Button

    override fun onAttach(context: android.content.Context) {
        AndroidSupportInjection.inject(this)
        super.onAttach(context)
    }

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View? {
        return inflater.inflate(R.layout.fragment_main, container, false)
    }
    
    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        
        initializeViews(view)
        setupClickListeners()
        updateUI()
    }
    
    private fun initializeViews(view: View) {
        statusText = view.findViewById(R.id.statusText)
        recognitionStatusText = view.findViewById(R.id.recognitionStatusText)
        recommendedActionText = view.findViewById(R.id.recommendedActionText)
        startButton = view.findViewById(R.id.startButton)
        stopButton = view.findViewById(R.id.stopButton)
        transcriptionButton = view.findViewById(R.id.transcriptionButton)
        historyButton = view.findViewById(R.id.historyButton)
        settingsButton = view.findViewById(R.id.settingsButton)
    }
    
    private fun setupClickListeners() {
        startButton.setOnClickListener {
            val mainActivity = requireActivity() as MainActivity
            if (mainActivity.isServiceBound()) {
                mainActivity.getLiveTranslateService()?.startRecording()
                mainActivity.notifyServiceStatusChanged()
                updateUI()
            }
        }

        stopButton.setOnClickListener {
            val mainActivity = requireActivity() as MainActivity
            if (mainActivity.isServiceBound()) {
                mainActivity.getLiveTranslateService()?.stopRecording()
                mainActivity.notifyServiceStatusChanged()
                updateUI()
            }
        }

        transcriptionButton.setOnClickListener {
            findNavController().navigate(R.id.transcriptionFragment)
        }

        historyButton.setOnClickListener {
            findNavController().navigate(R.id.translationHistoryFragment)
        }

        settingsButton.setOnClickListener {
            findNavController().navigate(R.id.settingsActivity)
        }
    }

    
    private fun updateUI() {
        if (!isAdded || !this::statusText.isInitialized) return
        val mainActivity = requireActivity() as MainActivity
        if (mainActivity.isServiceBound()) {
            val service = mainActivity.getLiveTranslateService()
            if (service != null) {
                statusText.text = if (service.isCurrentlyRecording()) {
                    "Recording and translating..."
                } else {
                    "Live Translation Service Ready"
                }
                
                recognitionStatusText.text = service.getRecognitionStatus()
                recommendedActionText.text = service.getRecommendedAction()
                
                // Show/hide buttons based on service state
                if (service.isCurrentlyRecording()) {
                    startButton.visibility = View.GONE
                    stopButton.visibility = View.VISIBLE
                } else {
                    startButton.visibility = View.VISIBLE
                    stopButton.visibility = View.GONE
                }
            }
        } else {
            statusText.text = "Service not connected"
            recognitionStatusText.text = "Connecting to service..."
            recommendedActionText.text = "Please wait..."
            startButton.visibility = View.GONE
            stopButton.visibility = View.GONE
        }
    }

    override fun onResume() {
        super.onResume()
        (requireActivity() as? MainActivity)?.registerServiceStateListener(serviceStateListener)
        updateUI()
    }

    override fun onPause() {
        (requireActivity() as? MainActivity)?.unregisterServiceStateListener(serviceStateListener)
        super.onPause()
    }

    private val serviceStateListener = object : MainActivity.ServiceStateListener {
        override fun onServiceStateChanged(isBound: Boolean) {
            updateUI()
        }

        override fun onServiceStatusChanged() {
            updateUI()
        }
    }
}

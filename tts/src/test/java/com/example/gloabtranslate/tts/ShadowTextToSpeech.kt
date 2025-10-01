package com.example.gloabtranslate.tts

import android.content.Context
import android.speech.tts.TextToSpeech
import org.robolectric.annotation.Implementation
import org.robolectric.annotation.Implements

@Implements(TextToSpeech::class)
class ShadowTextToSpeech {
    private var listener: TextToSpeech.OnInitListener? = null
    
    @Implementation
    fun __constructor__(context: Context, initListener: TextToSpeech.OnInitListener?) {
        this.listener = initListener
        // Immediately call the listener with success
        initListener?.onInit(TextToSpeech.SUCCESS)
    }
}
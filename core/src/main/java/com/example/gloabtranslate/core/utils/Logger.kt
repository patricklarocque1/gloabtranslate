package com.example.gloabtranslate.core.utils

import android.util.Log
import com.example.gloabtranslate.core.BuildConfig

/**
 * Lightweight logging helper that centralizes debug gating and formatting.
 */
object Logger {
    private const val DEFAULT_TAG = "GloabTranslate"

    fun d(message: String, tag: String = DEFAULT_TAG, throwable: Throwable? = null) {
        if (BuildConfig.DEBUG) {
            if (throwable != null) {
                Log.d(tag, message, throwable)
            } else {
                Log.d(tag, message)
            }
        }
    }

    fun i(message: String, tag: String = DEFAULT_TAG, throwable: Throwable? = null) {
        if (BuildConfig.DEBUG) {
            if (throwable != null) {
                Log.i(tag, message, throwable)
            } else {
                Log.i(tag, message)
            }
        }
    }

    fun w(message: String, tag: String = DEFAULT_TAG, throwable: Throwable? = null) {
        if (BuildConfig.DEBUG) {
            if (throwable != null) {
                Log.w(tag, message, throwable)
            } else {
                Log.w(tag, message)
            }
        }
    }

    fun e(message: String, tag: String = DEFAULT_TAG, throwable: Throwable? = null) {
        if (throwable != null) {
            Log.e(tag, message, throwable)
        } else {
            Log.e(tag, message)
        }
    }
}

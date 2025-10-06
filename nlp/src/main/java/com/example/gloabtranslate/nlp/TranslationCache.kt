package com.example.gloabtranslate.nlp

import com.example.gloabtranslate.core.data.models.TranslationResult
import java.util.LinkedHashMap

/**
 * Simple LRU cache for translation results keyed by composite (source|target|text).
 * Size limited by entry count (not memory). Thread-safe via synchronized methods.
 */
class TranslationCache(
    private var maxEntries: Int = 100,
) {
    private val lock = Any()
    private val map: LinkedHashMap<String, TranslationResult> = object : LinkedHashMap<String, TranslationResult>(16, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, TranslationResult>?): Boolean {
            return size > maxEntries
        }
    }

    data class Stats(
        val hits: Long,
        val misses: Long,
        val size: Int,
        val capacity: Int
    )

    private var hits = 0L
    private var misses = 0L

    fun get(key: String): TranslationResult? = synchronized(lock) {
        val v = map[key]
        if (v != null) hits++ else misses++
        v
    }

    fun put(key: String, value: TranslationResult) = synchronized(lock) {
        map[key] = value
    }

    fun resize(newMax: Int) = synchronized(lock) {
        if (newMax <= 0) {
            map.clear()
            maxEntries = 0
            return
        }
        maxEntries = newMax
        // Trigger eviction if needed
        while (map.size > maxEntries) {
            val it = map.entries.iterator()
            if (it.hasNext()) {
                it.next()
                it.remove()
            } else break
        }
    }

    fun clear() = synchronized(lock) { map.clear() }

    fun stats(): Stats = synchronized(lock) { Stats(hits, misses, map.size, maxEntries) }
}
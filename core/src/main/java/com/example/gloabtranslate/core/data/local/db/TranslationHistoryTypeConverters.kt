package com.example.gloabtranslate.core.data.local.db

import androidx.room.TypeConverter
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.builtins.MapSerializer
import kotlinx.serialization.builtins.serializer
import kotlinx.serialization.json.Json

/**
 * Type converters for complex fields stored with Room.
 */
class TranslationHistoryTypeConverters {

    private val json = Json {
        ignoreUnknownKeys = true
        encodeDefaults = true
    }

    @TypeConverter
    fun fromStringList(value: List<String>?): String? {
        return value?.let { json.encodeToString(ListSerializer(String.serializer()), it) }
    }

    @TypeConverter
    fun toStringList(value: String?): List<String> {
        return value?.let { json.decodeFromString(ListSerializer(String.serializer()), it) } ?: emptyList()
    }

    @TypeConverter
    fun fromStringMap(value: Map<String, String>?): String? {
        return value?.let {
            json.encodeToString(MapSerializer(String.serializer(), String.serializer()), it)
        }
    }

    @TypeConverter
    fun toStringMap(value: String?): Map<String, String> {
        return value?.let {
            json.decodeFromString(MapSerializer(String.serializer(), String.serializer()), it)
        } ?: emptyMap()
    }
}

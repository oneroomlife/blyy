package com.example.blyy.data.local

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.MutablePreferences
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import com.example.blyy.viewmodel.PlayMode
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import javax.inject.Inject
import javax.inject.Singleton

private val Context.dataStore: DataStore<Preferences> by preferencesDataStore(name = "player_settings")

@Singleton
class PlayerSettingsDataStore @Inject constructor(
    private val context: Context
) {
    companion object {
        private val PLAY_MODE_KEY = stringPreferencesKey("play_mode")
        private val FAVORITES_KEY = stringPreferencesKey("favorites")
        private val PLAY_LATER_KEY = stringPreferencesKey("play_later_v2")
        private const val FIGURE_PREFIX = "fig_"
    }

    val playMode: Flow<PlayMode> = context.dataStore.data
        .map { preferences: Preferences ->
            val modeName = preferences[PLAY_MODE_KEY] ?: PlayMode.PLAY_ONCE.name
            try {
                PlayMode.valueOf(modeName)
            } catch (e: Exception) {
                PlayMode.PLAY_ONCE
            }
        }

    val favorites: Flow<Set<String>> = context.dataStore.data
        .map { preferences: Preferences ->
            preferences[FAVORITES_KEY]?.split(",")?.filter { it.isNotEmpty() }?.toSet() ?: emptySet()
        }

    val playLaterList: Flow<List<PlayLaterItem>> = context.dataStore.data
        .map { preferences: Preferences ->
            val json = preferences[PLAY_LATER_KEY] ?: "[]"
            try {
                Json.decodeFromString<List<PlayLaterItem>>(json)
            } catch (e: Exception) {
                emptyList()
            }
        }

    fun getSavedFigure(shipName: String): Flow<String?> = context.dataStore.data
        .map { it[stringPreferencesKey(FIGURE_PREFIX + shipName)] }

    suspend fun saveFigure(shipName: String, figureUrl: String) {
        context.dataStore.edit { it[stringPreferencesKey(FIGURE_PREFIX + shipName)] = figureUrl }
    }

    suspend fun savePlayMode(mode: PlayMode) {
        context.dataStore.edit { preferences: MutablePreferences ->
            preferences[PLAY_MODE_KEY] = mode.name
        }
    }

    suspend fun toggleFavorite(voiceUrl: String) {
        context.dataStore.edit { preferences: MutablePreferences ->
            val currentString = preferences[FAVORITES_KEY] ?: ""
            val currentSet = currentString.split(",").filter { it.isNotEmpty() }.toMutableSet()
            if (voiceUrl in currentSet) {
                currentSet.remove(voiceUrl)
            } else {
                currentSet.add(voiceUrl)
            }
            preferences[FAVORITES_KEY] = currentSet.joinToString(",")
        }
    }

    suspend fun addToPlayLater(item: PlayLaterItem) {
        context.dataStore.edit { preferences: MutablePreferences ->
            val json = preferences[PLAY_LATER_KEY] ?: "[]"
            val currentList = try {
                Json.decodeFromString<List<PlayLaterItem>>(json).toMutableList()
            } catch (e: Exception) {
                mutableListOf()
            }
            if (currentList.none { it.voiceUrl == item.voiceUrl }) {
                currentList.add(0, item) // Add to top
                preferences[PLAY_LATER_KEY] = Json.encodeToString(currentList)
            }
        }
    }

    suspend fun removeFromPlayLater(voiceUrl: String) {
        context.dataStore.edit { preferences: MutablePreferences ->
            val json = preferences[PLAY_LATER_KEY] ?: "[]"
            val currentList = try {
                Json.decodeFromString<List<PlayLaterItem>>(json).toMutableList()
            } catch (e: Exception) {
                mutableListOf()
            }
            currentList.removeAll { it.voiceUrl == voiceUrl }
            preferences[PLAY_LATER_KEY] = Json.encodeToString(currentList)
        }
    }

    suspend fun clearPlayLaterList() {
        context.dataStore.edit { preferences: MutablePreferences ->
            preferences[PLAY_LATER_KEY] = "[]"
        }
    }

    suspend fun markAsPlayed(voiceUrl: String) {
        context.dataStore.edit { preferences: MutablePreferences ->
            val json = preferences[PLAY_LATER_KEY] ?: "[]"
            val currentList = try {
                Json.decodeFromString<List<PlayLaterItem>>(json).toMutableList()
            } catch (e: Exception) {
                mutableListOf()
            }
            val updatedList = currentList.map { item ->
                if (item.voiceUrl == voiceUrl) {
                    item.copy(hasPlayed = true)
                } else {
                    item
                }
            }
            preferences[PLAY_LATER_KEY] = Json.encodeToString(updatedList)
        }
    }
}

@kotlinx.serialization.Serializable
data class PlayLaterItem(
    val voiceUrl: String,
    val shipName: String,
    val scene: String,
    val dialogue: String,
    val skinName: String = "",
    val avatarUrl: String = "",
    val addedTime: Long = System.currentTimeMillis(),
    val hasPlayed: Boolean = false
)

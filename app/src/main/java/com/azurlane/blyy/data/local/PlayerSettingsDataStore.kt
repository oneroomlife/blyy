package com.azurlane.blyy.data.local

import android.os.Build
import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.floatPreferencesKey
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.MutablePreferences
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import com.azurlane.blyy.data.model.VoiceLanguage
import com.azurlane.blyy.ui.theme.UiStyle
import com.azurlane.blyy.viewmodel.PlayMode
import android.util.Log
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import javax.inject.Inject
import javax.inject.Singleton

/** 容错 JSON 实例：忽略未知字段，避免模型升级后反序列化崩溃导致数据"静默消失" */
private val lenientJson = Json {
    ignoreUnknownKeys = true
    isLenient = true
    coerceInputValues = true
}

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

        // 今日秘书舰
        private val SECRETARY_SHIP_NAME_KEY = stringPreferencesKey("secretary_ship_name")
        private val SECRETARY_FIGURE_URL_KEY = stringPreferencesKey("secretary_figure_url")
        private val SECRETARY_AVATAR_URL_KEY = stringPreferencesKey("secretary_avatar_url")
        private val SECRETARY_AUTO_PLAY_ENABLED_KEY = booleanPreferencesKey("secretary_auto_play_enabled")
        private val SECRETARY_AUTO_PLAY_INTERVAL_KEY = intPreferencesKey("secretary_auto_play_interval")
        
        // 语音语言
        private val VOICE_LANGUAGE_KEY = stringPreferencesKey("voice_language")
        
        // 悬浮窗状态
        private val SECRETARY_OVERLAY_ENABLED_KEY = booleanPreferencesKey("secretary_overlay_enabled")

        // 外观设置
        private val UI_STYLE_KEY = stringPreferencesKey("ui_style")
        private val FORCE_DARK_THEME_KEY = booleanPreferencesKey("force_dark_theme")
        private val DYNAMIC_COLOR_KEY = booleanPreferencesKey("dynamic_color_enabled")
        private val HIDE_STATUS_BAR_KEY = booleanPreferencesKey("hide_status_bar")

        // Live2D 证书信任（仅限 l2d.su 域名）
        private val LIVE2D_SSL_TRUSTED_KEY = booleanPreferencesKey("live2d_ssl_trusted")

        // 自动检测更新
        private val AUTO_CHECK_UPDATE_ENABLED_KEY = booleanPreferencesKey("auto_check_update_enabled")
        private val SKIPPED_UPDATE_VERSION_KEY = stringPreferencesKey("skipped_update_version")

        // 小助手配置
        private val ASSISTANT_DEFAULT_UID_KEY = stringPreferencesKey("assistant_default_uid")
        private val ASSISTANT_DEFAULT_SERVER_KEY = stringPreferencesKey("assistant_default_server")

        // 排行榜自定义昵称：当无法通过 UID/服务器查询到玩家信息时，使用该昵称上传排行榜
        private val LEADERBOARD_NICKNAME_KEY = stringPreferencesKey("leaderboard_nickname")

        // AI 配置
        private val AI_API_KEY_KEY = stringPreferencesKey("ai_api_key")
        private val AI_CUSTOM_BASE_URL_KEY = stringPreferencesKey("ai_custom_base_url")
        private val AI_SYSTEM_PROMPT_KEY = stringPreferencesKey("ai_system_prompt")
        private val AI_NAME_KEY = stringPreferencesKey("ai_name")
        private val AI_AVATAR_URL_KEY = stringPreferencesKey("ai_avatar_url")
        private val AI_VOICE_ENABLED_KEY = booleanPreferencesKey("ai_voice_enabled")
        private val AI_VOICE_RANDOM_CHANCE_KEY = floatPreferencesKey("ai_voice_random_chance")
        private val AI_VOICE_KEYWORDS_KEY = stringPreferencesKey("ai_voice_keywords")
        private val AI_CHAT_HISTORY_KEY = stringPreferencesKey("ai_chat_history")
        private val AI_MODEL_KEY = stringPreferencesKey("ai_model")
        private val AI_VOICE_SHIP_NAME_KEY = stringPreferencesKey("ai_voice_ship_name")
        private val AI_VOICE_SHIP_AVATAR_KEY = stringPreferencesKey("ai_voice_ship_avatar")
        private val AI_STICKERS_ENABLED_KEY = booleanPreferencesKey("ai_stickers_enabled")
        private val AI_STICKER_CHANCE_KEY = floatPreferencesKey("ai_sticker_chance")

        // 历史对话会话管理
        private val AI_CHAT_SESSIONS_KEY = stringPreferencesKey("ai_chat_sessions")
        private val AI_CURRENT_SESSION_ID_KEY = stringPreferencesKey("ai_current_session_id")

        // 啾信预设配置列表（多套 API + 舰娘人格）
        private val AI_JIUXIN_PRESETS_KEY = stringPreferencesKey("ai_jiuxin_presets")
        // 会话列表自定义排序（用户长按拖动后的顺序，存储 session id 列表）
        private val AI_SESSION_ORDER_KEY = stringPreferencesKey("ai_session_order")
        // 聊天背景图片 URL（空字符串表示使用默认纯色背景）
        private val AI_CHAT_BACKGROUND_URL_KEY = stringPreferencesKey("ai_chat_background_url")
        // 多套 API 配置列表（独立于舰娘人格，可复用）
        private val AI_API_CONFIGS_KEY = stringPreferencesKey("ai_api_configs")
        // 多套舰娘人格配置列表（独立于 API 配置，可组合）
        private val AI_PERSONA_CONFIGS_KEY = stringPreferencesKey("ai_persona_configs")
        // 用户（指挥官）配置
        private val USER_NAME_KEY = stringPreferencesKey("user_name")
        private val USER_AVATAR_URL_KEY = stringPreferencesKey("user_avatar_url")

        // 排行榜本地缓存（JSON + 时间戳），用于避免每次进入页面都重新加载
        private val LEADERBOARD_CACHE_JSON_KEY = stringPreferencesKey("leaderboard_cache_json")
        private val LEADERBOARD_CACHE_TIMESTAMP_KEY = androidx.datastore.preferences.core.longPreferencesKey("leaderboard_cache_ts")

        // 档案缓存时间戳（DOCK/STUDENT 各一份），用于多级缓存过期策略
        private val ARCHIVE_CACHE_TIMESTAMP_DOCK_KEY = androidx.datastore.preferences.core.longPreferencesKey("archive_cache_ts_dock")
        private val ARCHIVE_CACHE_TIMESTAMP_STUDENT_KEY = androidx.datastore.preferences.core.longPreferencesKey("archive_cache_ts_student")

        // ── 自定义 App 图标 ──
        /** 当前图标类型（DEFAULT / CLASSIC / BLUE / PURPLE / CUSTOM），对应 AppIconType 枚举 */
        private val APP_ICON_TYPE_KEY = stringPreferencesKey("app_icon_type")
        /** 自定义图标的内部存储路径（仅 CUSTOM 类型有效，空表示未设置） */
        private val APP_CUSTOM_ICON_PATH_KEY = stringPreferencesKey("app_custom_icon_path")
        /** 图标选择时间戳（用于升级后保持设置、排查切换问题） */
        private val APP_ICON_SELECTED_AT_KEY = androidx.datastore.preferences.core.longPreferencesKey("app_icon_selected_at")

        /** 档案缓存过期时间：5 分钟（毫秒） */
        const val ARCHIVE_CACHE_EXPIRY_MS = 5 * 60 * 1000L
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

    // 今日秘书舰
    val secretaryShipName: Flow<String> = context.dataStore.data.map { it[SECRETARY_SHIP_NAME_KEY] ?: "" }
    val secretaryFigureUrl: Flow<String> = context.dataStore.data.map { it[SECRETARY_FIGURE_URL_KEY] ?: "" }
    val secretaryAvatarUrl: Flow<String> = context.dataStore.data.map { it[SECRETARY_AVATAR_URL_KEY] ?: "" }
    val secretaryAutoPlayEnabled: Flow<Boolean> = context.dataStore.data.map { it[SECRETARY_AUTO_PLAY_ENABLED_KEY] ?: false }
    val secretaryAutoPlayIntervalMinutes: Flow<Int> = context.dataStore.data.map { it[SECRETARY_AUTO_PLAY_INTERVAL_KEY] ?: 5 }
    
    // 悬浮窗状态
    val secretaryOverlayEnabled: Flow<Boolean> = context.dataStore.data.map { it[SECRETARY_OVERLAY_ENABLED_KEY] ?: false }

    // 外观
    val uiStyle: Flow<UiStyle> = context.dataStore.data.map { prefs ->
        when (prefs[UI_STYLE_KEY]) {
            UiStyle.CLASSIC.name -> UiStyle.CLASSIC
            else -> UiStyle.COMMAND_CENTER
        }
    }

    /** 默认 false — 跟随系统深浅色设置 */
    val forceDarkTheme: Flow<Boolean> = context.dataStore.data.map { it[FORCE_DARK_THEME_KEY] ?: false }

    /** Material You 动态取色 — Android 12+ 默认开启 */
    val dynamicColorEnabled: Flow<Boolean> = context.dataStore.data.map { prefs ->
        prefs[DYNAMIC_COLOR_KEY] ?: (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S)
    }

    /** 沉浸式：隐藏状态栏，默认开启。用户可从屏幕顶部下滑临时呼出（sticky immersive） */
    val hideStatusBar: Flow<Boolean> = context.dataStore.data.map { it[HIDE_STATUS_BAR_KEY] ?: true }

    /** Live2D 域名证书信任标记，仅对 l2d.su 生效 */
    val live2dSslTrusted: Flow<Boolean> = context.dataStore.data.map { it[LIVE2D_SSL_TRUSTED_KEY] ?: false }

    /** 自动检测更新开关，默认开启 */
    val autoCheckUpdateEnabled: Flow<Boolean> = context.dataStore.data.map { it[AUTO_CHECK_UPDATE_ENABLED_KEY] ?: true }

    /** 用户跳过的更新版本号，用于"稍后提醒"功能 */
    val skippedUpdateVersion: Flow<String> = context.dataStore.data.map { it[SKIPPED_UPDATE_VERSION_KEY] ?: "" }

    // 语音语言
    val voiceLanguage: Flow<VoiceLanguage> = context.dataStore.data
        .map { preferences: Preferences ->
            val langName = preferences[VOICE_LANGUAGE_KEY] ?: VoiceLanguage.CN.name
            try {
                VoiceLanguage.valueOf(langName)
            } catch (e: Exception) {
                VoiceLanguage.CN
            }
        }

    suspend fun saveSecretaryShip(shipName: String, figureUrl: String, avatarUrl: String) {
        context.dataStore.edit { prefs ->
            prefs[SECRETARY_SHIP_NAME_KEY] = shipName
            prefs[SECRETARY_FIGURE_URL_KEY] = figureUrl
            prefs[SECRETARY_AVATAR_URL_KEY] = avatarUrl
        }
    }

    suspend fun clearSecretaryShip() {
        context.dataStore.edit { prefs ->
            prefs.remove(SECRETARY_SHIP_NAME_KEY)
            prefs.remove(SECRETARY_FIGURE_URL_KEY)
            prefs.remove(SECRETARY_AVATAR_URL_KEY)
        }
    }

    suspend fun setSecretaryAutoPlay(enabled: Boolean, intervalMinutes: Int = 5) {
        context.dataStore.edit { prefs ->
            prefs[SECRETARY_AUTO_PLAY_ENABLED_KEY] = enabled
            prefs[SECRETARY_AUTO_PLAY_INTERVAL_KEY] = intervalMinutes.coerceIn(1, 60)
        }
    }
    
    // 悬浮窗状态
    suspend fun setSecretaryOverlayEnabled(enabled: Boolean) {
        context.dataStore.edit { prefs ->
            prefs[SECRETARY_OVERLAY_ENABLED_KEY] = enabled
        }
    }

    suspend fun setUiStyle(style: UiStyle) {
        context.dataStore.edit { prefs ->
            prefs[UI_STYLE_KEY] = style.name
        }
    }

    suspend fun setForceDarkTheme(force: Boolean) {
        context.dataStore.edit { prefs ->
            prefs[FORCE_DARK_THEME_KEY] = force
        }
    }

    suspend fun setDynamicColorEnabled(enabled: Boolean) {
        context.dataStore.edit { prefs ->
            prefs[DYNAMIC_COLOR_KEY] = enabled
        }
    }

    suspend fun setHideStatusBar(hide: Boolean) {
        context.dataStore.edit { prefs ->
            prefs[HIDE_STATUS_BAR_KEY] = hide
        }
    }

    suspend fun setLive2dSslTrusted(trusted: Boolean) {
        context.dataStore.edit { prefs ->
            prefs[LIVE2D_SSL_TRUSTED_KEY] = trusted
        }
    }

    suspend fun setAutoCheckUpdateEnabled(enabled: Boolean) {
        context.dataStore.edit { prefs ->
            prefs[AUTO_CHECK_UPDATE_ENABLED_KEY] = enabled
        }
    }

    suspend fun setSkippedUpdateVersion(version: String) {
        context.dataStore.edit { prefs ->
            prefs[SKIPPED_UPDATE_VERSION_KEY] = version
        }
    }

    suspend fun setVoiceLanguage(language: VoiceLanguage) {
        context.dataStore.edit { prefs ->
            prefs[VOICE_LANGUAGE_KEY] = language.name
        }
    }

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

    // ── 小助手配置 ──

    /** 小助手默认 UID */
    val assistantDefaultUid: Flow<String> = context.dataStore.data.map { it[ASSISTANT_DEFAULT_UID_KEY] ?: "" }

    /** 小助手默认服务器名/ID */
    val assistantDefaultServer: Flow<String> = context.dataStore.data.map { it[ASSISTANT_DEFAULT_SERVER_KEY] ?: "" }

    suspend fun setAssistantDefaultUid(uid: String) {
        context.dataStore.edit { it[ASSISTANT_DEFAULT_UID_KEY] = uid }
    }

    suspend fun setAssistantDefaultServer(server: String) {
        context.dataStore.edit { it[ASSISTANT_DEFAULT_SERVER_KEY] = server }
    }

    // ── 排行榜自定义昵称 ──

    /** 排行榜自定义昵称（无法通过助手查询到玩家信息时使用） */
    val leaderboardNickname: Flow<String> = context.dataStore.data.map { it[LEADERBOARD_NICKNAME_KEY] ?: "" }

    suspend fun setLeaderboardNickname(nickname: String) {
        context.dataStore.edit { it[LEADERBOARD_NICKNAME_KEY] = nickname.trim() }
    }

    // ── AI 配置 ──

    val aiApiKey: Flow<String> = context.dataStore.data.map { it[AI_API_KEY_KEY] ?: "" }
    val aiCustomBaseUrl: Flow<String> = context.dataStore.data.map { it[AI_CUSTOM_BASE_URL_KEY] ?: "" }
    val aiSystemPrompt: Flow<String> = context.dataStore.data.map { it[AI_SYSTEM_PROMPT_KEY] ?: "" }
    val aiName: Flow<String> = context.dataStore.data.map { it[AI_NAME_KEY] ?: "" }
    val aiAvatarUrl: Flow<String> = context.dataStore.data.map { it[AI_AVATAR_URL_KEY] ?: "" }
    val aiVoiceEnabled: Flow<Boolean> = context.dataStore.data.map { it[AI_VOICE_ENABLED_KEY] ?: true }
    val aiVoiceRandomChance: Flow<Float> = context.dataStore.data.map {
        it[AI_VOICE_RANDOM_CHANCE_KEY] ?: 0.1f
    }
    val aiVoiceKeywords: Flow<String> = context.dataStore.data.map { it[AI_VOICE_KEYWORDS_KEY] ?: "你好;早安;晚安;加油;辛苦了" }
    val aiChatHistory: Flow<String> = context.dataStore.data.map { it[AI_CHAT_HISTORY_KEY] ?: "[]" }
    val aiModel: Flow<String> = context.dataStore.data.map { it[AI_MODEL_KEY] ?: "" }
    val aiVoiceShipName: Flow<String> = context.dataStore.data.map { it[AI_VOICE_SHIP_NAME_KEY] ?: "" }
    val aiVoiceShipAvatar: Flow<String> = context.dataStore.data.map { it[AI_VOICE_SHIP_AVATAR_KEY] ?: "" }
    val aiStickersEnabled: Flow<Boolean> = context.dataStore.data.map { it[AI_STICKERS_ENABLED_KEY] ?: true }
    val aiStickerChance: Flow<Float> = context.dataStore.data.map { it[AI_STICKER_CHANCE_KEY] ?: 0.8f }

    suspend fun setAiApiKey(key: String) { context.dataStore.edit { it[AI_API_KEY_KEY] = key } }
    suspend fun setAiCustomBaseUrl(url: String) { context.dataStore.edit { it[AI_CUSTOM_BASE_URL_KEY] = url } }
    suspend fun setAiSystemPrompt(prompt: String) { context.dataStore.edit { it[AI_SYSTEM_PROMPT_KEY] = prompt } }
    suspend fun setAiName(name: String) { context.dataStore.edit { it[AI_NAME_KEY] = name } }
    suspend fun setAiAvatarUrl(url: String) { context.dataStore.edit { it[AI_AVATAR_URL_KEY] = url } }
    suspend fun setAiVoiceEnabled(enabled: Boolean) { context.dataStore.edit { it[AI_VOICE_ENABLED_KEY] = enabled } }
    suspend fun setAiVoiceRandomChance(chance: Float) { context.dataStore.edit { it[AI_VOICE_RANDOM_CHANCE_KEY] = chance.coerceIn(0f, 1f) } }
    suspend fun setAiVoiceKeywords(keywords: String) { context.dataStore.edit { it[AI_VOICE_KEYWORDS_KEY] = keywords } }
    suspend fun setAiChatHistory(history: String) { context.dataStore.edit { it[AI_CHAT_HISTORY_KEY] = history } }
    suspend fun setAiModel(model: String) { context.dataStore.edit { it[AI_MODEL_KEY] = model } }
    suspend fun setAiVoiceShipName(name: String) { context.dataStore.edit { it[AI_VOICE_SHIP_NAME_KEY] = name } }
    suspend fun setAiVoiceShipAvatar(avatar: String) { context.dataStore.edit { it[AI_VOICE_SHIP_AVATAR_KEY] = avatar } }
    suspend fun setAiStickersEnabled(enabled: Boolean) { context.dataStore.edit { it[AI_STICKERS_ENABLED_KEY] = enabled } }
    suspend fun setAiStickerChance(chance: Float) { context.dataStore.edit { it[AI_STICKER_CHANCE_KEY] = chance.coerceIn(0f, 1f) } }
    suspend fun clearAiChatHistory() { context.dataStore.edit { it[AI_CHAT_HISTORY_KEY] = "[]" } }

    // ── 历史对话会话管理 ──

    /** 所有会话元数据列表 */
    val aiChatSessions: Flow<List<com.azurlane.blyy.data.model.ChatSession>> = context.dataStore.data.map { prefs ->
        val json = prefs[AI_CHAT_SESSIONS_KEY] ?: "[]"
        try { lenientJson.decodeFromString<List<com.azurlane.blyy.data.model.ChatSession>>(json) } catch (e: Exception) { Log.w("DataStore", "Failed to decode chat sessions", e); emptyList() }
    }

    /** 当前会话 ID */
    val aiCurrentSessionId: Flow<String> = context.dataStore.data.map { it[AI_CURRENT_SESSION_ID_KEY] ?: "" }

    /** 保存会话列表 */
    suspend fun setAiChatSessions(sessions: List<com.azurlane.blyy.data.model.ChatSession>) { context.dataStore.edit { it[AI_CHAT_SESSIONS_KEY] = lenientJson.encodeToString(sessions) } }

    /** 设置当前会话 ID */
    suspend fun setAiCurrentSessionId(id: String) {
        context.dataStore.edit { it[AI_CURRENT_SESSION_ID_KEY] = id }
    }

    /** 获取指定会话的消息 */
    fun aiSessionMessages(sessionId: String): Flow<List<com.azurlane.blyy.data.model.ChatMessage>> = context.dataStore.data.map { prefs ->
        val key = stringPreferencesKey("ai_session_msgs_$sessionId")
        val json = prefs[key] ?: "[]"
        try { lenientJson.decodeFromString<List<com.azurlane.blyy.data.model.ChatMessage>>(json) } catch (e: Exception) { Log.w("DataStore", "Failed to decode session messages for $sessionId", e); emptyList() }
    }

    /** 保存指定会话的消息 */
    suspend fun setAiSessionMessages(sessionId: String, messages: List<com.azurlane.blyy.data.model.ChatMessage>) {
        context.dataStore.edit { it[stringPreferencesKey("ai_session_msgs_$sessionId")] = lenientJson.encodeToString(messages) }
    }

    /** 删除指定会话的消息数据 */
    suspend fun deleteAiSessionMessages(sessionId: String) {
        context.dataStore.edit { it.remove(stringPreferencesKey("ai_session_msgs_$sessionId")) }
    }

    // ── 啾信预设配置管理 ──

    /** 所有已保存的啾信预设列表 */
    val aiJiuxinPresets: Flow<List<com.azurlane.blyy.data.model.JiuxinPreset>> = context.dataStore.data.map { prefs ->
        val json = prefs[AI_JIUXIN_PRESETS_KEY] ?: "[]"
        try { lenientJson.decodeFromString<List<com.azurlane.blyy.data.model.JiuxinPreset>>(json) } catch (e: Exception) { Log.w("DataStore", "Failed to decode presets", e); emptyList() }
    }

    /** 保存预设列表（覆盖） */
    suspend fun setAiJiuxinPresets(presets: List<com.azurlane.blyy.data.model.JiuxinPreset>) {
        context.dataStore.edit { it[AI_JIUXIN_PRESETS_KEY] = lenientJson.encodeToString(presets) }
    }

    /** 会话列表自定义排序（session id 列表，用户拖动后的顺序） */
    val aiSessionOrder: Flow<List<String>> = context.dataStore.data.map { prefs ->
        val json = prefs[AI_SESSION_ORDER_KEY] ?: "[]"
        try { lenientJson.decodeFromString<List<String>>(json) } catch (e: Exception) { Log.w("DataStore", "Failed to decode session order", e); emptyList() }
    }

    /** 保存会话列表自定义排序 */
    suspend fun setAiSessionOrder(order: List<String>) {
        context.dataStore.edit { it[AI_SESSION_ORDER_KEY] = lenientJson.encodeToString(order) }
    }

    /** 聊天背景图片 URL（空表示使用默认纯色背景） */
    val aiChatBackgroundUrl: Flow<String> = context.dataStore.data.map { it[AI_CHAT_BACKGROUND_URL_KEY] ?: "" }

    /** 保存聊天背景图片 URL */
    suspend fun setAiChatBackgroundUrl(url: String) {
        context.dataStore.edit { it[AI_CHAT_BACKGROUND_URL_KEY] = url }
    }

    // ── 多套 API 配置管理 ──

    /** 所有已保存的 API 配置列表 */
    val aiApiConfigs: Flow<List<com.azurlane.blyy.data.model.ApiConfig>> = context.dataStore.data.map { prefs ->
        val json = prefs[AI_API_CONFIGS_KEY] ?: "[]"
        try { lenientJson.decodeFromString<List<com.azurlane.blyy.data.model.ApiConfig>>(json) } catch (e: Exception) { Log.w("DataStore", "Failed to decode API configs", e); emptyList() }
    }

    /** 保存 API 配置列表（覆盖） */
    suspend fun setAiApiConfigs(configs: List<com.azurlane.blyy.data.model.ApiConfig>) {
        context.dataStore.edit { it[AI_API_CONFIGS_KEY] = lenientJson.encodeToString(configs) }
    }

    // ── 多套舰娘人格配置管理 ──

    /** 所有已保存的舰娘人格配置列表 */
    val aiPersonaConfigs: Flow<List<com.azurlane.blyy.data.model.PersonaConfig>> = context.dataStore.data.map { prefs ->
        val json = prefs[AI_PERSONA_CONFIGS_KEY] ?: "[]"
        try { lenientJson.decodeFromString<List<com.azurlane.blyy.data.model.PersonaConfig>>(json) } catch (e: Exception) { Log.w("DataStore", "Failed to decode persona configs", e); emptyList() }
    }

    /** 保存舰娘人格配置列表（覆盖） */
    suspend fun setAiPersonaConfigs(configs: List<com.azurlane.blyy.data.model.PersonaConfig>) {
        context.dataStore.edit { it[AI_PERSONA_CONFIGS_KEY] = lenientJson.encodeToString(configs) }
    }

    // ── 用户（指挥官）配置 ──
    val userName: Flow<String> = context.dataStore.data.map { it[USER_NAME_KEY] ?: "指挥官" }
    val userAvatarUrl: Flow<String> = context.dataStore.data.map { it[USER_AVATAR_URL_KEY] ?: "" }

    suspend fun setUserName(name: String) { context.dataStore.edit { it[USER_NAME_KEY] = name } }
    suspend fun setUserAvatarUrl(url: String) { context.dataStore.edit { it[USER_AVATAR_URL_KEY] = url } }

    // ── 排行榜本地缓存 ──

    /**
     * 读取排行榜本地缓存。
     * @return Pair<JSON 字符串, 缓存写入时间戳>，若不存在返回 null
     */
    suspend fun getLeaderboardCache(): Pair<String, Long>? {
        val prefs = context.dataStore.data.first()
        val json = prefs[LEADERBOARD_CACHE_JSON_KEY] ?: return null
        val ts = prefs[LEADERBOARD_CACHE_TIMESTAMP_KEY] ?: 0L
        return json to ts
    }

    /** 写入排行榜本地缓存（JSON + 当前时间戳） */
    suspend fun setLeaderboardCache(json: String) {
        context.dataStore.edit { prefs ->
            prefs[LEADERBOARD_CACHE_JSON_KEY] = json
            prefs[LEADERBOARD_CACHE_TIMESTAMP_KEY] = System.currentTimeMillis()
        }
    }

    /** 清空排行榜本地缓存 */
    suspend fun clearLeaderboardCache() {
        context.dataStore.edit { prefs ->
            prefs.remove(LEADERBOARD_CACHE_JSON_KEY)
            prefs.remove(LEADERBOARD_CACHE_TIMESTAMP_KEY)
        }
    }

    // ── 档案缓存时间戳 ──

    /** 获取档案缓存时间戳，返回 0 表示无缓存 */
    suspend fun getArchiveCacheTimestamp(archiveType: String): Long {
        val prefs = context.dataStore.data.first()
        val key = if (archiveType == "STUDENT") ARCHIVE_CACHE_TIMESTAMP_STUDENT_KEY else ARCHIVE_CACHE_TIMESTAMP_DOCK_KEY
        return prefs[key] ?: 0L
    }

    /** 写入档案缓存时间戳 */
    suspend fun setArchiveCacheTimestamp(archiveType: String) {
        val key = if (archiveType == "STUDENT") ARCHIVE_CACHE_TIMESTAMP_STUDENT_KEY else ARCHIVE_CACHE_TIMESTAMP_DOCK_KEY
        context.dataStore.edit { it[key] = System.currentTimeMillis() }
    }

    /** 检查档案缓存是否过期（超过 5 分钟视为过期） */
    suspend fun isArchiveCacheExpired(archiveType: String): Boolean {
        val timestamp = getArchiveCacheTimestamp(archiveType)
        if (timestamp == 0L) return true
        return System.currentTimeMillis() - timestamp > ARCHIVE_CACHE_EXPIRY_MS
    }

    // ── 自定义 App 图标偏好 ──

    /** 当前图标类型（DEFAULT / CLASSIC / BLUE / PURPLE / CUSTOM） */
    val appIconType: Flow<String> = context.dataStore.data
        .map { it[APP_ICON_TYPE_KEY] ?: "DEFAULT" }

    /** 自定义图标内部存储路径（仅 CUSTOM 类型有效） */
    val appCustomIconPath: Flow<String> = context.dataStore.data
        .map { it[APP_CUSTOM_ICON_PATH_KEY] ?: "" }

    /** 图标选择时间戳 */
    val appIconSelectedAt: Flow<Long> = context.dataStore.data
        .map { it[APP_ICON_SELECTED_AT_KEY] ?: 0L }

    /**
     * 保存图标选择。
     *
     * @param type 图标类型枚举名（见 [com.azurlane.blyy.util.AppIconType]）
     * @param customPath 自定义图标路径（仅 CUSTOM 类型需要，其它传空）
     */
    suspend fun setAppIcon(type: String, customPath: String = "") {
        context.dataStore.edit {
            it[APP_ICON_TYPE_KEY] = type
            it[APP_CUSTOM_ICON_PATH_KEY] = customPath
            it[APP_ICON_SELECTED_AT_KEY] = System.currentTimeMillis()
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

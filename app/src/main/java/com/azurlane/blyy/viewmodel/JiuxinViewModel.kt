package com.azurlane.blyy.viewmodel

import android.content.Context
import android.media.AudioAttributes
import android.media.MediaPlayer
import android.util.Log
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.azurlane.blyy.data.local.PlayerSettingsDataStore
import com.azurlane.blyy.data.local.ShipDao
import com.azurlane.blyy.data.model.ChatMessage
import com.azurlane.blyy.data.model.ChatMessageType
import com.azurlane.blyy.data.model.ChatSession
import com.azurlane.blyy.data.model.JiuxinChatUiState
import com.azurlane.blyy.data.model.Ship
import com.azurlane.blyy.data.model.VoiceLanguage
import com.azurlane.blyy.data.model.VoiceTagMapping
import com.azurlane.blyy.data.repository.ShipRepository
import com.azurlane.blyy.domain.GetVoicesUseCase
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.withContext
import kotlinx.coroutines.launch
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.UUID
import javax.inject.Inject

sealed class ConnectionTestState {
    object Idle : ConnectionTestState()
    object Testing : ConnectionTestState()
    object Success : ConnectionTestState()
    data class Error(val message: String) : ConnectionTestState()
}

@HiltViewModel
class JiuxinViewModel @Inject constructor(
    private val settings: PlayerSettingsDataStore,
    private val shipRepository: ShipRepository,
    private val shipDao: ShipDao,
    private val getVoicesUseCase: GetVoicesUseCase,
    private val client: OkHttpClient,
    @ApplicationContext private val context: Context
) : ViewModel() {

    private val json = Json { ignoreUnknownKeys = true; isLenient = true }

    companion object {
        private const val TAG = "JiuxinViewModel"
        private const val DEFAULT_MODEL = "gpt-4o-mini"
        private const val MAX_CHAT_HISTORY = 200
        private const val MAX_SESSIONS = 50
    }

    // Voice player
    private var voicePlayer: MediaPlayer? = null
    private val _currentlyPlayingId = MutableStateFlow<String?>(null)
    val currentlyPlayingId: StateFlow<String?> = _currentlyPlayingId.asStateFlow()

    // ── Ship list for picker ──
    private val _shipList = MutableStateFlow<List<Ship>>(emptyList())
    val shipList: StateFlow<List<Ship>> = _shipList.asStateFlow()

    private val _shipSearchQuery = MutableStateFlow("")
    val shipSearchQuery: StateFlow<String> = _shipSearchQuery.asStateFlow()

    val filteredShipList: StateFlow<List<Ship>> = combine(_shipList, _shipSearchQuery) { ships, query ->
        if (query.isBlank()) ships
        else ships.filter { it.name.contains(query, ignoreCase = true) }
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    // ── 啾信配置 StateFlows ──
    val apiKey = settings.aiApiKey.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), "")
    val apiUrl = settings.aiCustomBaseUrl.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), "")
    val systemPrompt = settings.aiSystemPrompt.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), "")
    val jiuxinName = settings.aiName.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), "啾信助手")
    val avatarUrl = settings.aiAvatarUrl.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), "")
    val voiceEnabled = settings.aiVoiceEnabled.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), true)
    val voiceRandomChance = settings.aiVoiceRandomChance.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), 0.1f)
    val voiceKeywords = settings.aiVoiceKeywords.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), "你好;早安;晚安;加油;辛苦了")
    val selectedModel = settings.aiModel.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), "")
    val userName = settings.userName.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), "指挥官")
    val userAvatarUrl = settings.userAvatarUrl.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), "")

    private val _availableModels = MutableStateFlow<List<String>>(emptyList())
    val availableModels: StateFlow<List<String>> = _availableModels.asStateFlow()

    val voiceShipName = settings.aiVoiceShipName.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), "")
    val voiceShipAvatar = settings.aiVoiceShipAvatar.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), "")

    // ── Connection Test ──
    private val _connectionTestState = MutableStateFlow<ConnectionTestState>(ConnectionTestState.Idle)
    val connectionTestState: StateFlow<ConnectionTestState> = _connectionTestState.asStateFlow()

    // ── Chat State ──
    private val _chatState = MutableStateFlow(JiuxinChatUiState())
    val chatUiState: StateFlow<JiuxinChatUiState> = _chatState.asStateFlow()

    // ── 会话管理 ──
    private val _sessions = MutableStateFlow<List<ChatSession>>(emptyList())
    val sessions: StateFlow<List<ChatSession>> = _sessions.asStateFlow()

    private val _currentSessionId = MutableStateFlow("")
    val currentSessionId: StateFlow<String> = _currentSessionId.asStateFlow()

    // ── 语音标签映射 ──
    private val defaultVoiceTagMappings = listOf(
        VoiceTagMapping("你好", listOf("主界面", "问候"), priority = 5),
        VoiceTagMapping("早安", listOf("主界面", "问候"), priority = 5),
        VoiceTagMapping("晚安", listOf("主界面", "问候"), priority = 5),
        VoiceTagMapping("加油", listOf("鼓励", "战斗", "强化"), priority = 3),
        VoiceTagMapping("辛苦了", listOf("主界面", "摸头"), priority = 4),
        VoiceTagMapping("战斗", listOf("战斗", "战斗开始", "胜利"), priority = 3),
        VoiceTagMapping("胜利", listOf("胜利", "MVP"), priority = 4),
        VoiceTagMapping("失败", listOf("失败", "战斗失败"), priority = 2),
        VoiceTagMapping("誓约", listOf("誓约", "誓约之约"), priority = 5),
        VoiceTagMapping("结婚", listOf("誓约", "誓约之约"), priority = 5),
        VoiceTagMapping("喜欢", listOf("誓约", "好感度", "爱"), priority = 4),
        VoiceTagMapping("爱", listOf("誓约", "好感度", "爱"), priority = 4),
        VoiceTagMapping("获得", listOf("获得"), priority = 3),
        VoiceTagMapping("登录", listOf("登录", "回港"), priority = 3),
        VoiceTagMapping("回港", listOf("回港", "登录"), priority = 3),
        VoiceTagMapping("出征", listOf("出征", "战斗开始"), priority = 3),
        VoiceTagMapping("受伤", listOf("受伤", "战斗"), priority = 2),
        VoiceTagMapping("生日", listOf("生日", "誓约"), priority = 5),
        VoiceTagMapping("情人节", listOf("情人节", "誓约"), priority = 5),
        VoiceTagMapping("圣诞", listOf("圣诞", "节日"), priority = 4),
        VoiceTagMapping("新年", listOf("新年", "节日"), priority = 4),
        VoiceTagMapping("触摸", listOf("触摸", "摸头", "特殊触摸"), priority = 3),
        VoiceTagMapping("摸头", listOf("摸头", "触摸"), priority = 3),
        VoiceTagMapping("技能", listOf("技能", "战斗"), priority = 2),
        VoiceTagMapping("建造", listOf("建造", "获得"), priority = 2),
        VoiceTagMapping("探索", listOf("探索", "出征"), priority = 2),
        VoiceTagMapping("委托", listOf("委托", "回港"), priority = 2),
        VoiceTagMapping("强化", listOf("强化", "鼓励"), priority = 3),
        VoiceTagMapping("心情", listOf("主界面", "触摸"), priority = 2),
        VoiceTagMapping("谢谢", listOf("鼓励", "主界面"), priority = 3),
        VoiceTagMapping("再见", listOf("回港", "登录"), priority = 2),
        VoiceTagMapping("开心", listOf("主界面", "MVP", "胜利"), priority = 3),
        VoiceTagMapping("难过", listOf("受伤", "失败"), priority = 2),
        VoiceTagMapping("想念", listOf("誓约", "好感度"), priority = 4)
    )

    init {
        // 加载会话列表和当前会话
        viewModelScope.launch {
            settings.aiChatSessions.collect { sessionList ->
                _sessions.value = sessionList
            }
        }
        viewModelScope.launch {
            settings.aiCurrentSessionId.collect { sessionId ->
                _currentSessionId.value = sessionId
            }
        }
        // 加载当前会话消息
        viewModelScope.launch {
            _currentSessionId.flatMapLatest { sessionId ->
                if (sessionId.isNotBlank()) {
                    settings.aiSessionMessages(sessionId)
                } else {
                    flowOf(emptyList())
                }
            }.collect { messages ->
                _chatState.update { it.copy(messages = messages) }
            }
        }
        // 旧数据迁移：如果存在旧的 aiChatHistory 且无会话，自动迁移
        viewModelScope.launch {
            val existingSessions = settings.aiChatSessions.first()
            if (existingSessions.isEmpty()) {
                val oldHistory = settings.aiChatHistory.first()
                if (oldHistory != "[]") {
                    try {
                        val messages = json.decodeFromString<List<ChatMessage>>(oldHistory)
                        if (messages.isNotEmpty()) {
                            val session = ChatSession(
                                name = generateDefaultName(),
                                createdAt = messages.first().timestamp,
                                updatedAt = messages.last().timestamp
                            )
                            settings.setAiChatSessions(listOf(session))
                            settings.setAiSessionMessages(session.id, messages)
                            settings.setAiCurrentSessionId(session.id)
                            settings.clearAiChatHistory()
                            Log.d(TAG, "Migrated old chat history to session ${session.id}")
                        }
                    } catch (e: Exception) {
                        Log.w(TAG, "Failed to migrate old chat history", e)
                    }
                }
            }
        }
        viewModelScope.launch {
            voiceKeywords.collect { keywords ->
                val list = keywords.split(";").filter { k -> k.isNotBlank() }
                val userMappings = list.map { keyword ->
                    defaultVoiceTagMappings.find { it.keyword == keyword }
                        ?: VoiceTagMapping(keyword, listOf("主界面"), priority = 1)
                }
                val allMappings = (defaultVoiceTagMappings + userMappings)
                    .distinctBy { it.keyword }
                    .sortedByDescending { it.priority }
                _chatState.update { it.copy(voiceKeywords = list, voiceTagMappings = allMappings) }
            }
        }
        viewModelScope.launch {
            shipRepository.allShips.collect { ships ->
                _shipList.value = ships
            }
        }
    }

    // ── 配置保存方法 ──
    fun saveApiKey(key: String) { viewModelScope.launch { settings.setAiApiKey(key) } }
    fun saveApiUrl(url: String) { viewModelScope.launch { settings.setAiCustomBaseUrl(url.trim()) } }
    fun saveSystemPrompt(prompt: String) { viewModelScope.launch { settings.setAiSystemPrompt(prompt) } }
    fun saveJiuxinName(name: String) { viewModelScope.launch { settings.setAiName(name) } }
    fun saveAvatarUrl(url: String) {
        viewModelScope.launch { settings.setAiAvatarUrl(normalizeUrl(url)) }
    }
    fun saveVoiceEnabled(enabled: Boolean) { viewModelScope.launch { settings.setAiVoiceEnabled(enabled) } }
    fun saveVoiceRandomChance(chance: Float) { viewModelScope.launch { settings.setAiVoiceRandomChance(chance) } }
    fun saveVoiceKeywords(keywords: String) { viewModelScope.launch { settings.setAiVoiceKeywords(keywords) } }
    fun saveModel(model: String) { viewModelScope.launch { settings.setAiModel(model) } }
    fun saveVoiceShipName(name: String) { viewModelScope.launch { settings.setAiVoiceShipName(name) } }
    fun saveVoiceShipAvatar(avatar: String) { viewModelScope.launch { settings.setAiVoiceShipAvatar(normalizeUrl(avatar)) } }

    fun saveUserName(name: String) { viewModelScope.launch { settings.setUserName(name) } }
    fun saveUserAvatarUrl(url: String) { viewModelScope.launch { settings.setUserAvatarUrl(normalizeUrl(url)) } }

    fun setInputText(text: String) { _chatState.update { it.copy(inputText = text) } }
    fun setSelectedShip(name: String, avatar: String) { _chatState.update { it.copy(selectedShipName = name, selectedShipAvatar = avatar) } }
    fun setShipSearchQuery(query: String) { _shipSearchQuery.value = query }

    // ── API URL 自动补全 ──

    /**
     * 将用户输入的 Base URL 自动补全为完整的 Chat Completions 请求地址
     * 例如: https://api.example.com/v1 → https://api.example.com/v1/chat/completions
     * 如果已经包含 /chat/completions 则不再重复添加
     */
    fun buildFullApiUrl(baseUrl: String): String {
        val trimmed = baseUrl.trim().trimEnd('/')
        if (trimmed.isBlank()) return ""
        if (trimmed.endsWith("/chat/completions", ignoreCase = true)) return trimmed
        return "$trimmed/chat/completions"
    }

    // ── 会话管理 ──

    /** 生成默认会话名称: 对话-YYYYMMDD-HHMMSS */
    private fun generateDefaultName(): String {
        val sdf = SimpleDateFormat("yyyyMMdd-HHmmss", Locale.getDefault())
        return "对话-${sdf.format(Date())}"
    }

    /** 创建新会话 */
    fun createNewSession() {
        viewModelScope.launch {
            val session = ChatSession(name = generateDefaultName())
            val updated = listOf(session) + _sessions.value
            // 限制最大会话数
            val trimmed = if (updated.size > MAX_SESSIONS) updated.dropLast(1) else updated
            settings.setAiChatSessions(trimmed)
            settings.setAiCurrentSessionId(session.id)
            settings.setAiSessionMessages(session.id, emptyList())
            _chatState.update { it.copy(messages = emptyList(), error = null, isLoading = false) }
            Log.d(TAG, "Created new session: ${session.id} - ${session.name}")
        }
    }

    /** 切换到指定会话 */
    fun switchToSession(sessionId: String) {
        if (_currentSessionId.value == sessionId) return
        viewModelScope.launch {
            // 先保存当前会话消息
            saveCurrentSessionMessages()
            settings.setAiCurrentSessionId(sessionId)
            _chatState.update { it.copy(error = null, isLoading = false) }
            Log.d(TAG, "Switched to session: $sessionId")
        }
    }

    /** 删除指定会话 */
    fun deleteSession(sessionId: String) {
        viewModelScope.launch {
            val currentList = _sessions.value.toMutableList()
            currentList.removeAll { it.id == sessionId }
            settings.setAiChatSessions(currentList)
            settings.deleteAiSessionMessages(sessionId)

            // 如果删除的是当前会话，切换到最新的或创建新的
            if (_currentSessionId.value == sessionId) {
                if (currentList.isNotEmpty()) {
                    settings.setAiCurrentSessionId(currentList.first().id)
                } else {
                    // 没有会话了，创建新的
                    val newSession = ChatSession(name = generateDefaultName())
                    settings.setAiChatSessions(listOf(newSession))
                    settings.setAiCurrentSessionId(newSession.id)
                    settings.setAiSessionMessages(newSession.id, emptyList())
                }
                _chatState.update { it.copy(error = null, isLoading = false) }
            }
            Log.d(TAG, "Deleted session: $sessionId")
        }
    }

    /** 重命名会话 */
    fun renameSession(sessionId: String, newName: String) {
        viewModelScope.launch {
            val updated = _sessions.value.map {
                if (it.id == sessionId) it.copy(name = newName.trim()) else it
            }
            settings.setAiChatSessions(updated)
            Log.d(TAG, "Renamed session $sessionId to: $newName")
        }
    }

    // ── 连接测试 ──
    fun testConnection() {
        viewModelScope.launch {
            _connectionTestState.value = ConnectionTestState.Testing
            try {
                val key = settings.aiApiKey.first()
                val baseUrl = settings.aiCustomBaseUrl.first().trim()
                val modelStr = settings.aiModel.first()

                if (baseUrl.isBlank()) {
                    _connectionTestState.value = ConnectionTestState.Error("请先配置 API Base URL")
                    return@launch
                }
                if (key.isBlank()) {
                    _connectionTestState.value = ConnectionTestState.Error("请先配置 API Key")
                    return@launch
                }

                val model = modelStr.ifBlank { DEFAULT_MODEL }
                val url = buildFullApiUrl(baseUrl)

                val testRequestJson = buildJsonObject {
                    put("model", JsonPrimitive(model))
                    put("messages", buildJsonArray {
                        add(buildJsonObject {
                            put("role", JsonPrimitive("user"))
                            put("content", JsonPrimitive("Hi"))
                        })
                    })
                    put("max_tokens", JsonPrimitive(5))
                    put("enable_thinking", JsonPrimitive(false))
                    put("thinking", buildJsonObject { put("type", JsonPrimitive("disabled")) })
                }

                val requestBody = json.encodeToString(testRequestJson)
                    .toRequestBody("application/json".toMediaType())

                val request = Request.Builder()
                    .url(url)
                    .addHeader("Authorization", "Bearer $key")
                    .addHeader("Content-Type", "application/json")
                    .addHeader("Accept", "application/json")
                    .post(requestBody)
                    .build()

                Log.d(TAG, "Testing connection to: $url, model=$model")

                val result = withContext(Dispatchers.IO) {
                    try {
                        val response = client.newCall(request).execute()
                        val body = response.body?.string()

                        if (response.isSuccessful) {
                            Log.d(TAG, "Connection test successful")
                            ConnectionTestState.Success
                        } else {
                            val errorDetail = if (!body.isNullOrBlank()) parseErrorMessage(body) else "HTTP ${response.code}"
                            Log.e(TAG, "Connection test failed: ${response.code} - $errorDetail")
                            ConnectionTestState.Error("连接失败 (${response.code}): $errorDetail")
                        }
                    } catch (e: java.net.UnknownHostException) {
                        ConnectionTestState.Error("无法连接服务器，请检查网络和 URL 地址")
                    } catch (e: java.net.SocketTimeoutException) {
                        ConnectionTestState.Error("连接超时，请检查网络或稍后重试")
                    } catch (e: java.net.MalformedURLException) {
                        ConnectionTestState.Error("URL 格式错误: ${e.message ?: "无效地址"}")
                    } catch (e: java.net.ConnectException) {
                        ConnectionTestState.Error("连接被拒绝，请检查 URL 和端口是否正确")
                    } catch (e: javax.net.ssl.SSLException) {
                        ConnectionTestState.Error("SSL 错误: ${e.message ?: "证书验证失败"}")
                    } catch (e: java.io.IOException) {
                        ConnectionTestState.Error("网络错误: ${e.message ?: "IO异常"}")
                    } catch (e: Exception) {
                        Log.e(TAG, "Connection test error", e)
                        ConnectionTestState.Error("连接失败: ${e.message ?: e.javaClass.simpleName}")
                    }
                }

                _connectionTestState.value = result
            } catch (e: Exception) {
                Log.e(TAG, "Connection test outer error", e)
                _connectionTestState.value = ConnectionTestState.Error("连接失败: ${e.message ?: e.javaClass.simpleName}")
            }
        }
    }

    fun resetConnectionTest() { _connectionTestState.value = ConnectionTestState.Idle }

    /**
     * 获取可用模型列表 (OpenAI 兼容接口)
     */
    fun fetchModels() {
        viewModelScope.launch {
            val key = settings.aiApiKey.first()
            val baseUrl = settings.aiCustomBaseUrl.first().trim().trimEnd('/')
            if (key.isBlank() || baseUrl.isBlank()) return@launch

            val url = "$baseUrl/models"
            val request = Request.Builder()
                .url(url)
                .addHeader("Authorization", "Bearer $key")
                .get()
                .build()

            withContext(Dispatchers.IO) {
                try {
                    val response = client.newCall(request).execute()
                    val body = response.body?.string()
                    if (response.isSuccessful && body != null) {
                        val jsonElement = json.parseToJsonElement(body)
                        val data = jsonElement.jsonObject["data"]?.jsonArray
                        val modelList = data?.mapNotNull {
                            it.jsonObject["id"]?.jsonPrimitive?.content
                        }?.sorted() ?: emptyList()
                        _availableModels.value = modelList
                        Log.d(TAG, "Fetched ${modelList.size} models from $url")
                    } else {
                        Log.e(TAG, "Failed to fetch models: ${response.code}")
                    }
                } catch (e: Exception) {
                    Log.e(TAG, "Exception fetching models", e)
                }
            }
        }
    }

    // ── 发送消息 ──
    fun sendMessage(text: String) {
        if (text.isBlank()) return

        // 确保有当前会话，没有则自动创建
        val sessionId = _currentSessionId.value
        if (sessionId.isBlank()) {
            viewModelScope.launch {
                val session = ChatSession(name = generateDefaultName())
                settings.setAiChatSessions(listOf(session) + _sessions.value)
                settings.setAiCurrentSessionId(session.id)
            }
        }

        val userMessage = ChatMessage(
            id = UUID.randomUUID().toString(),
            type = ChatMessageType.USER.name,
            content = text,
            timestamp = System.currentTimeMillis()
        )

        _chatState.update { it.copy(inputText = "", isLoading = true, error = null) }
        addMessage(userMessage)

        // 语音触发 + API 调用
        viewModelScope.launch {
            var selectedShip = settings.aiVoiceShipName.first()
            var shipAvatar = settings.aiVoiceShipAvatar.first()
            val voiceEnabledSetting = settings.aiVoiceEnabled.first()
            val chance = settings.aiVoiceRandomChance.first()

            // 回退：如果 DataStore 中头像为空，从舰娘数据库查找
            if (selectedShip.isNotBlank() && shipAvatar.isBlank()) {
                shipAvatar = shipDao.getShipByName(selectedShip)?.avatarUrl?.let { normalizeUrl(it) } ?: ""
                Log.d(TAG, "Avatar fallback from DB: $shipAvatar")
            }

            if (voiceEnabledSetting && selectedShip.isNotBlank()) {
                val tagMappings = _chatState.value.voiceTagMappings
                val matchedMapping = findBestTagMatch(text, tagMappings)

                if (matchedMapping != null) {
                    triggerVoiceByTags(selectedShip, shipAvatar, matchedMapping.sceneTags)
                } else if (chance > 0f && kotlin.random.Random.nextDouble() < chance) {
                    triggerVoiceRandom(selectedShip, shipAvatar)
                }
            }

            // 调用啾信 API
            try {
                val aiResponse = callApi(text)
                val aiMessage = ChatMessage(
                    id = UUID.randomUUID().toString(),
                    type = ChatMessageType.AI.name,
                    content = aiResponse,
                    timestamp = System.currentTimeMillis()
                )
                addMessage(aiMessage)
            } catch (e: Exception) {
                _chatState.update { it.copy(error = e.message ?: e.javaClass.simpleName) }
            } finally {
                finishLoading()
            }
        }
    }

    // ── 智能语音标签匹配 ──
    private fun findBestTagMatch(text: String, mappings: List<VoiceTagMapping>): VoiceTagMapping? =
        mappings.filter { text.contains(it.keyword, ignoreCase = true) }.maxByOrNull { it.priority }

    private fun triggerVoiceByTags(shipName: String, shipAvatar: String, sceneTags: List<String>) {
        viewModelScope.launch {
            try {
                val (voices, _, _) = getVoicesUseCase(shipName)
                if (voices.isNotEmpty()) {
                    val taggedVoice = sceneTags.firstNotNullOfOrNull { tag ->
                        voices.firstOrNull { it.scene.contains(tag, ignoreCase = true) }
                    } ?: voices.random()
                    val audioUrl = taggedVoice.getActiveAudioUrl(VoiceLanguage.CN)
                    sendVoiceMessage(shipName, audioUrl, taggedVoice.dialogue, shipAvatar)
                }
            } catch (e: Exception) {
                Log.w(TAG, "Failed to load voice: ${e.message}")
            }
        }
    }

    private fun triggerVoiceRandom(shipName: String, shipAvatar: String) {
        viewModelScope.launch {
            try {
                val (voices, _, _) = getVoicesUseCase(shipName)
                if (voices.isNotEmpty()) {
                    val voice = voices.random()
                    val audioUrl = voice.getActiveAudioUrl(VoiceLanguage.CN)
                    sendVoiceMessage(shipName, audioUrl, voice.dialogue, shipAvatar)
                }
            } catch (e: Exception) {
                Log.w(TAG, "Failed to load voice: ${e.message}")
            }
        }
    }

    fun sendVoiceMessage(shipName: String, voiceUrl: String, dialogue: String, avatarUrl: String = "") {
        val voiceMessage = ChatMessage(
            id = UUID.randomUUID().toString(),
            type = ChatMessageType.VOICE.name,
            content = dialogue,
            timestamp = System.currentTimeMillis(),
            shipName = shipName,
            voiceUrl = voiceUrl,
            dialogue = dialogue,
            avatarUrl = avatarUrl
        )
        addMessage(voiceMessage)
    }

    // ── 语音播放 ──
    fun playVoice(messageId: String, voiceUrl: String) {
        stopVoice()
        try {
            val player = MediaPlayer().apply {
                setAudioAttributes(
                    AudioAttributes.Builder()
                        .setContentType(AudioAttributes.CONTENT_TYPE_MUSIC)
                        .setUsage(AudioAttributes.USAGE_MEDIA)
                        .build()
                )
                setDataSource(voiceUrl)
                setOnPreparedListener { start() }
                setOnCompletionListener { _currentlyPlayingId.value = null; release() }
                setOnErrorListener { _, _, _ -> _currentlyPlayingId.value = null; release(); true }
                prepareAsync()
            }
            voicePlayer = player
            _currentlyPlayingId.value = messageId
        } catch (e: Exception) {
            Log.e(TAG, "Failed to play voice", e)
            _currentlyPlayingId.value = null
        }
    }

    fun stopVoice() {
        voicePlayer?.let {
            try { it.stop() } catch (_: Exception) { }
            try { it.release() } catch (_: Exception) { }
        }
        voicePlayer = null
        _currentlyPlayingId.value = null
    }

    fun toggleVoicePlayback(messageId: String, voiceUrl: String) {
        if (_currentlyPlayingId.value == messageId) stopVoice() else playVoice(messageId, voiceUrl)
    }

    fun clearChat() {
        viewModelScope.launch {
            _chatState.update { it.copy(messages = emptyList()) }
            val sessionId = _currentSessionId.value
            if (sessionId.isNotBlank()) {
                settings.setAiSessionMessages(sessionId, emptyList())
            }
        }
    }

    fun clearError() { _chatState.update { it.copy(error = null) } }

    override fun onCleared() { super.onCleared(); stopVoice() }

    // ── 私有方法 ──

    private fun addMessage(message: ChatMessage) {
        _chatState.update { state ->
            val allMessages = state.messages + message
            val trimmed = if (allMessages.size > MAX_CHAT_HISTORY) allMessages.takeLast(MAX_CHAT_HISTORY) else allMessages
            state.copy(messages = trimmed)
        }
        saveCurrentSessionMessages()
    }

    private fun finishLoading() {
        _chatState.update { it.copy(isLoading = false) }
    }

    private fun saveCurrentSessionMessages() {
        viewModelScope.launch {
            val sessionId = _currentSessionId.value
            if (sessionId.isNotBlank()) {
                val messages = _chatState.value.messages
                settings.setAiSessionMessages(sessionId, messages)
                val updated = _sessions.value.map {
                    if (it.id == sessionId) it.copy(updatedAt = System.currentTimeMillis()) else it
                }
                settings.setAiChatSessions(updated)
            }
        }
    }

    private suspend fun callApi(userMessage: String): String {
        val key = settings.aiApiKey.first()
        if (key.isBlank()) throw Exception("未配置 API 密钥，请先在啾信配置中设置")

        val baseUrl = settings.aiCustomBaseUrl.first().trim()
        if (baseUrl.isBlank()) throw Exception("未配置 API URL，请先在啾信配置中设置")

        val url = buildFullApiUrl(baseUrl)
        val prompt = settings.aiSystemPrompt.first()
        val modelStr = settings.aiModel.first()
        val model = modelStr.ifBlank { DEFAULT_MODEL }

        val recentMessages = _chatState.value.messages.takeLast(20)
        val messages = buildJsonArray {
            add(buildJsonObject {
                put("role", JsonPrimitive("system"))
                put("content", JsonPrimitive(prompt))
            })
            recentMessages.forEach { msg ->
                val role = when (msg.type) {
                    ChatMessageType.USER.name -> "user"
                    ChatMessageType.AI.name -> "assistant"
                    else -> null
                }
                if (role != null) {
                    add(buildJsonObject {
                        put("role", JsonPrimitive(role))
                        put("content", JsonPrimitive(msg.content))
                    })
                }
            }
            add(buildJsonObject {
                put("role", JsonPrimitive("user"))
                put("content", JsonPrimitive(userMessage))
            })
        }

        val requestJson = buildJsonObject {
            put("model", JsonPrimitive(model))
            put("messages", messages)
            put("max_tokens", JsonPrimitive(1024))
            put("temperature", JsonPrimitive(0.7))
            put("enable_thinking", JsonPrimitive(false))
            put("thinking", buildJsonObject { put("type", JsonPrimitive("disabled")) })
        }

        val requestBody = json.encodeToString(requestJson)
            .toRequestBody("application/json".toMediaType())

        val request = Request.Builder()
            .url(url)
            .addHeader("Authorization", "Bearer $key")
            .addHeader("Content-Type", "application/json")
            .addHeader("Accept", "application/json")
            .post(requestBody)
            .build()

        Log.d(TAG, "Calling API: $url, model=$model, history=${recentMessages.size} messages")

        return withContext(Dispatchers.IO) {
            val requestTime = System.currentTimeMillis()
            val response = client.newCall(request).execute()
            val body = response.body?.string() ?: throw Exception("空响应")
            val duration = System.currentTimeMillis() - requestTime

            if (!response.isSuccessful) {
                val errorDetail = parseErrorMessage(body)
                Log.e(TAG, "API error: ${response.code} ($duration ms) - $errorDetail")
                throw Exception("API 错误 (${response.code}): $errorDetail")
            }

            Log.d(TAG, "API success: ${response.code} ($duration ms), body_length=${body.length}")

            parseChatCompletionResponse(body)
        }
    }

    private fun parseChatCompletionResponse(responseBody: String): String {
        try {
            val jsonElement = json.parseToJsonElement(responseBody)
            val choices = jsonElement.jsonObject["choices"]?.jsonArray
            if (choices != null && choices.isNotEmpty()) {
                val firstChoice = choices[0].jsonObject
                val message = firstChoice["message"]?.jsonObject
                val content = message?.get("content")?.jsonPrimitive?.content
                if (!content.isNullOrBlank()) return content
            }
        } catch (e: Exception) {
            Log.w(TAG, "JSON parse failed for chat response", e)
        }
        throw Exception("无法解析啾信响应")
    }

    private fun parseErrorMessage(body: String): String {
        return try {
            val jsonElement = json.parseToJsonElement(body)
            val message = jsonElement.jsonObject["error"]?.jsonObject?.get("message")?.jsonPrimitive?.content
            message?.take(100) ?: body.take(100)
        } catch (_: Exception) { body.take(100) }
    }

    /**
     * 规范化 URL：处理协议相对 URL (//xxx)、缺少协议等情况
     */
    private fun normalizeUrl(url: String): String {
        val trimmed = url.trim()
        if (trimmed.isBlank()) return ""
        if (trimmed.startsWith("//")) return "https:$trimmed"
        if (!trimmed.startsWith("http://") && !trimmed.startsWith("https://") && trimmed.contains(".")) {
            return "https://$trimmed"
        }
        return trimmed
    }
}

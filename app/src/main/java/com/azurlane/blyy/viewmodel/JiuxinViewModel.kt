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

    // ── 语音标签映射优化 (日常口语对应专业台词标签) ──
    private val defaultVoiceTagMappings = listOf(
        // 1. 登录界面 & 登录台词 (映射: 登录台词, 登录界面)
        VoiceTagMapping("你好", listOf("主界面", "登录台词", "登录界面"), priority = 5),
        VoiceTagMapping("早安", listOf("主界面", "登录台词"), priority = 5),
        VoiceTagMapping("午安", listOf("主界面", "登录台词"), priority = 5),
        VoiceTagMapping("晚安", listOf("主界面", "登录台词"), priority = 5),
        VoiceTagMapping("进游戏", listOf("登录界面", "登录台词"), priority = 7),
        VoiceTagMapping("启动", listOf("登录界面", "登录台词"), priority = 6),
        VoiceTagMapping("开服", listOf("登录界面"), priority = 8),
        VoiceTagMapping("登录台词", listOf("登录台词", "登录界面"), priority = 9),
        VoiceTagMapping("登录界面", listOf("登录界面", "登录台词"), priority = 9),

        // 2. 舰船型号 & 自我介绍 (映射: 舰船型号, 自我介绍)
        VoiceTagMapping("自我介绍", listOf("自我介绍", "舰船型号"), priority = 9),
        VoiceTagMapping("你是谁", listOf("自我介绍", "舰船型号"), priority = 7),
        VoiceTagMapping("叫什么名字", listOf("自我介绍", "舰船型号"), priority = 7),
        VoiceTagMapping("介绍一下", listOf("自我介绍", "舰船型号"), priority = 8),
        VoiceTagMapping("舰船型号", listOf("舰船型号", "自我介绍"), priority = 9),
        VoiceTagMapping("什么船", listOf("舰船型号"), priority = 7),
        VoiceTagMapping("什么舰种", listOf("舰船型号"), priority = 7),

        // 3. 获取台词 & 查看详情 (映射: 获取台词, 查看详情)
        VoiceTagMapping("获取台词", listOf("获取台词", "获得"), priority = 9),
        VoiceTagMapping("出了", listOf("获取台词", "获得"), priority = 7),
        VoiceTagMapping("捞到了", listOf("获取台词", "获得"), priority = 8),
        VoiceTagMapping("查看详情", listOf("查看详情", "获取台词"), priority = 9),
        VoiceTagMapping("详情", listOf("查看详情", "获取台词"), priority = 6),
        VoiceTagMapping("资料", listOf("查看详情", "获取台词"), priority = 7),
        VoiceTagMapping("属性", listOf("查看详情"), priority = 7),

        // 4. 主界面 (映射: 主界面)
        VoiceTagMapping("主界面", listOf("主界面"), priority = 9),
        VoiceTagMapping("主页", listOf("主界面"), priority = 7),
        VoiceTagMapping("看板娘", listOf("主界面"), priority = 8),
        VoiceTagMapping("在干嘛", listOf("主界面"), priority = 6),

        // 5. 触摸、特殊触摸、摸头 (映射: 触摸台词, 特殊触摸, 摸头台词)
        VoiceTagMapping("触摸台词", listOf("触摸台词"), priority = 9),
        VoiceTagMapping("碰你", listOf("触摸台词"), priority = 6),
        VoiceTagMapping("特殊触摸", listOf("特殊触摸"), priority = 9),
        VoiceTagMapping("别碰那里", listOf("特殊触摸"), priority = 8),
        VoiceTagMapping("色狼", listOf("特殊触摸"), priority = 8),
        VoiceTagMapping("变态", listOf("特殊触摸"), priority = 8),
        VoiceTagMapping("摸头台词", listOf("摸头台词", "触摸台词"), priority = 9),
        VoiceTagMapping("摸摸头", listOf("摸头台词", "触摸台词"), priority = 8),
        VoiceTagMapping("乖", listOf("摸头台词", "触摸台词"), priority = 7),

        // 6. 任务与邮件 (映射: 任务提醒, 任务完成, 鄌件提酲)
        VoiceTagMapping("任务提醒", listOf("任务提醒", "任务完成"), priority = 9),
        VoiceTagMapping("任务", listOf("任务提醒", "任务完成"), priority = 6),
        VoiceTagMapping("有任务吗", listOf("任务提醒"), priority = 7),
        VoiceTagMapping("任务完成", listOf("任务完成", "任务提醒"), priority = 9),
        VoiceTagMapping("做完了", listOf("任务完成", "任务提醒"), priority = 8),
        VoiceTagMapping("领奖励", listOf("任务完成", "任务提醒"), priority = 7),
        VoiceTagMapping("报酬", listOf("任务完成"), priority = 7),
        VoiceTagMapping("鄌件提酲", listOf("鄌件提酲", "邮件提醒"), priority = 9), // 兼容 typo 标签
        VoiceTagMapping("邮件提醒", listOf("邮件提醒", "鄌件提酲"), priority = 9),
        VoiceTagMapping("有信吗", listOf("邮件提醒", "鄌件提酲"), priority = 7),
        VoiceTagMapping("收邮件", listOf("邮件提醒", "鄌件提酲"), priority = 8),

        // 7. 回港 (映射: 回港台词)
        VoiceTagMapping("回港台词", listOf("回港台词", "登录台词"), priority = 9),
        VoiceTagMapping("我回来了", listOf("回港台词", "登录台词"), priority = 8),
        VoiceTagMapping("到家了", listOf("回港台词", "登录台词"), priority = 8),
        VoiceTagMapping("辛苦了", listOf("回港台词", "主界面"), priority = 7),

        // 8. 好感度与誓约 (映射: 好感度-友好, 好感度-喜欢, 好感度-爱, 誓约台词)
        VoiceTagMapping("好感度-友好", listOf("好感度-友好", "主界面"), priority = 9),
        VoiceTagMapping("友好", listOf("好感度-友好"), priority = 7),
        VoiceTagMapping("朋友", listOf("好感度-友好"), priority = 6),
        VoiceTagMapping("好感度-喜欢", listOf("好感度-喜欢", "好感度-爱"), priority = 9),
        VoiceTagMapping("喜欢", listOf("好感度-喜欢", "好感度-爱"), priority = 6),
        VoiceTagMapping("好感度-爱", listOf("好感度-爱", "誓约台词"), priority = 9, preferredSkinName = "誓约"),
        VoiceTagMapping("爱", listOf("好感度-爱", "誓约台词"), priority = 5, preferredSkinName = "誓约"),
        VoiceTagMapping("最喜欢了", listOf("好感度-爱", "誓约台词"), priority = 8, preferredSkinName = "誓约"),
        VoiceTagMapping("誓约台词", listOf("誓约台词", "好感度-爱"), priority = 9, preferredSkinName = "誓约"),
        VoiceTagMapping("结婚", listOf("誓约台词", "好感度-爱"), priority = 8, preferredSkinName = "誓约"),
        VoiceTagMapping("戒指", listOf("誓约台词", "好感度-爱"), priority = 8, preferredSkinName = "誓约"),
        VoiceTagMapping("嫁给我", listOf("誓约台词", "好感度-爱"), priority = 8, preferredSkinName = "誓约"),

        // 9. 强化与改造 (映射: 强化成功, 改造完成)
        VoiceTagMapping("强化", listOf("强化成功", "主界面"), priority = 8),
        VoiceTagMapping("升级", listOf("强化成功", "主界面"), priority = 7),
        VoiceTagMapping("改造", listOf("改造完成", "强化成功"), priority = 9),
        VoiceTagMapping("变强了", listOf("强化成功", "改造完成"), priority = 7),

        // 10. 战斗相关 (映射: 战斗台词, 技能触发, 胜利台词, 失败台词, 低血量)
        VoiceTagMapping("出击", listOf("战斗台词", "主界面"), priority = 9),
        VoiceTagMapping("开火", listOf("战斗台词", "技能触发"), priority = 7),
        VoiceTagMapping("技能", listOf("技能触发", "战斗台词"), priority = 9),
        VoiceTagMapping("胜利", listOf("胜利台词", "回港台词"), priority = 9),
        VoiceTagMapping("赢了", listOf("胜利台词", "回港台词"), priority = 8),
        VoiceTagMapping("失败", listOf("失败台词", "回港台词"), priority = 9),
        VoiceTagMapping("没血了", listOf("低血量", "主界面"), priority = 8),
        VoiceTagMapping("快不行了", listOf("低血量"), priority = 8),

        // 11. 委派与建造 (映射: 委派完成, 建造完成)
        VoiceTagMapping("委派", listOf("委派完成", "任务完成"), priority = 8),
        VoiceTagMapping("远征", listOf("委派完成", "任务完成"), priority = 8),
        VoiceTagMapping("建造", listOf("建造完成", "获取台词"), priority = 8),
        VoiceTagMapping("造好了", listOf("建造完成"), priority = 8),

        // 12. 状态与好感度保底 (映射: 旗舰台词, 失望, 陌生)
        VoiceTagMapping("旗舰", listOf("旗舰台词", "主界面"), priority = 8),
        VoiceTagMapping("失望", listOf("好感度-失望"), priority = 9),
        VoiceTagMapping("讨厌你", listOf("好感度-失望"), priority = 8),
        VoiceTagMapping("不认识", listOf("好感度-陌生"), priority = 8)
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
                    triggerVoiceByTags(selectedShip, shipAvatar, matchedMapping.sceneTags, matchedMapping.preferredSkinName)
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

    /**
     * 在用户文本中查找最佳匹配的语音标签映射
     * 优先匹配更长的关键词（避免"爱"误匹配"可爱"等），
     * 长度相同时取优先级更高的
     */
    private fun findBestTagMatch(text: String, mappings: List<VoiceTagMapping>): VoiceTagMapping? =
        mappings
            .filter { text.contains(it.keyword, ignoreCase = true) }
            .maxWithOrNull(compareBy({ it.keyword.length }, { it.priority }))

    private fun triggerVoiceByTags(shipName: String, shipAvatar: String, sceneTags: List<String>, preferredSkinName: String = "") {
        viewModelScope.launch {
            try {
                val (voices, _, _) = getVoicesUseCase(shipName)
                if (voices.isNotEmpty()) {
                    val taggedVoice = findBestVoice(voices, sceneTags, preferredSkinName)
                    val audioUrl = taggedVoice.getActiveAudioUrl(VoiceLanguage.CN)
                    sendVoiceMessage(shipName, audioUrl, taggedVoice.dialogue, shipAvatar)
                }
            } catch (e: Exception) {
                Log.w(TAG, "Failed to load voice: ${e.message}")
            }
        }
    }

    /**
     * 根据场景标签和偏好皮肤查找最佳语音
     * 解决“同一关键词可对应触发不同皮肤的同一标签语音”问题。
     */
    private fun findBestVoice(
        voices: List<com.azurlane.blyy.data.model.VoiceLine>,
        sceneTags: List<String>,
        preferredSkinName: String = ""
    ): com.azurlane.blyy.data.model.VoiceLine {
        val defaultSkinNames = setOf("默认装扮", "默认", "通常", "原装")

        // 1. 策略：如果指定了偏好皮肤（如：好感度-爱强制要求“誓约”皮肤台词）
        if (preferredSkinName.isNotBlank()) {
            sceneTags.firstNotNullOfOrNull { tag ->
                voices.filter {
                    it.skinName.contains(preferredSkinName, ignoreCase = true) &&
                    it.scene.contains(tag, ignoreCase = true)
                }.randomOrNull() // 随机抽取该皮肤下的对应标签台词
            }?.let { return it }
        }

        // 2. 策略：跨皮肤随机池
        // 依次检查场景标签，收集所有皮肤中匹配该标签的台词，实现“同一标签、不同皮肤”的惊喜感
        sceneTags.forEach { tag ->
            val pool = voices.filter { it.scene.contains(tag, ignoreCase = true) }
            if (pool.isNotEmpty()) {
                // 优先考虑：默认皮肤或无皮肤台词（权重稍大），但包含所有其他换装皮肤
                val defaultPool = pool.filter { it.skinName in defaultSkinNames || it.skinName.isEmpty() }
                // 80% 概率触发默认/通常语音，20% 概率触发已拥有的其他皮肤语音
                return if (defaultPool.isNotEmpty() && kotlin.random.Random.nextFloat() < 0.8f) {
                    defaultPool.random()
                } else {
                    pool.random()
                }
            }
        }

        // 3. 保底：优先从默认皮肤中随机选一条
        val defaultVoice = voices.filter { it.skinName in defaultSkinNames }.randomOrNull()
        return defaultVoice ?: voices.random()
    }




    private fun triggerVoiceRandom(shipName: String, shipAvatar: String) {
        viewModelScope.launch {
            try {
                val (voices, _, _) = getVoicesUseCase(shipName)
                if (voices.isNotEmpty()) {
                    val defaultSkinNames = setOf("默认装扮", "默认", "通常")
                    val defaultVoices = voices.filter { it.skinName in defaultSkinNames }
                    val voice = if (defaultVoices.isNotEmpty()) defaultVoices.random() else voices.random()
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
     * 注意：content:// 和 file:// 开头的本地 URI 不做任何处理
     */
    private fun normalizeUrl(url: String): String {
        val trimmed = url.trim()
        if (trimmed.isBlank()) return ""
        // 本地 content:// URI（相册选择等）和 file:// URI 不做处理
        if (trimmed.startsWith("content://") || trimmed.startsWith("file://")) return trimmed
        if (trimmed.startsWith("//")) return "https:$trimmed"
        if (!trimmed.startsWith("http://") && !trimmed.startsWith("https://") && trimmed.contains(".")) {
            return "https://$trimmed"
        }
        return trimmed
    }
}

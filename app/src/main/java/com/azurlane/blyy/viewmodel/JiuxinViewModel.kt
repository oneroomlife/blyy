package com.azurlane.blyy.viewmodel

import android.content.Context
import android.media.AudioAttributes
import android.media.MediaPlayer
import android.net.Uri
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
import com.azurlane.blyy.data.model.StickerResource
import com.azurlane.blyy.util.LocalAvatarResolver
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
import java.io.File
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
    val stickersEnabled = settings.aiStickersEnabled.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), true)
    val stickerChance = settings.aiStickerChance.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), 0.8f)

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

    /**
     * 将相册选择的 content:// URI 图片复制到应用内部存储，返回 file:// 路径。
     *
     * 解决 content:// URI 在应用重启后可能丢失读取权限的问题：
     * - 某些 ContentProvider 不支持 takePersistableUriPermission
     * - 部分 ROM 的相册应用返回的 URI 带有临时 token，会话结束即失效
     *
     * 复制到 filesDir/avatars/ 目录后，file:// 路径始终可靠可用。
     */
    suspend fun copyAvatarToInternalStorage(uri: Uri): String? {
        return withContext(Dispatchers.IO) {
            try {
                val avatarDir = File(context.filesDir, "avatars").apply { mkdirs() }
                val destFile = File(avatarDir, "avatar_${System.currentTimeMillis()}.jpg")
                context.contentResolver.openInputStream(uri)?.use { input ->
                    destFile.outputStream().use { output ->
                        input.copyTo(output)
                    }
                } ?: run {
                    Log.e(TAG, "openInputStream returned null for $uri")
                    return@withContext null
                }
                "file://${destFile.absolutePath}"
            } catch (e: Exception) {
                Log.e(TAG, "Failed to copy avatar to internal storage", e)
                null
            }
        }
    }

    /**
     * 解析舰娘头像并复制到内部存储，返回可靠的 file:// 路径。
     *
     * 解决 file:///android_asset/ URI 在某些设备/Coil 版本上加载失败的问题：
     * - 将 asset 文件复制到 filesDir/avatars/ 目录
     * - 返回的 file:// 路径可被 Coil 的 FileUriFetcher 稳定加载
     * - 若 LocalAvatarResolver 未匹配到本地资源，回退到网络 URL
     *
     * @param shipName 舰娘名称
     * @param networkUrl 网络头像 URL（作为回退）
     * @return 复合格式头像 URL（primary||fallback），本地文件优先，网络 URL 作为回退
     */
    suspend fun resolveAndCopyShipAvatar(shipName: String, networkUrl: String): String {
        val normalizedNetworkUrl = normalizeUrl(networkUrl)
        val assetUri = LocalAvatarResolver.resolve(context, shipName)
        if (assetUri == null) {
            return normalizedNetworkUrl
        }

        // 从 URI 提取 asset 路径：Uri.Builder().scheme("file").path("/android_asset/xxx")
        // 生成的字符串为 "file:/android_asset/xxx"（单斜杠），需用 Uri.parse().path 获取解码路径
        val uriPath = Uri.parse(assetUri).path
        val assetPath = uriPath?.removePrefix("/android_asset/")
        if (assetPath.isNullOrBlank()) {
            Log.e(TAG, "Cannot extract asset path from: $assetUri")
            return normalizedNetworkUrl
        }

        return withContext(Dispatchers.IO) {
            try {
                val avatarDir = File(context.filesDir, "avatars").apply { mkdirs() }
                // 用舰娘名作为文件名，避免重复复制
                val safeFileName = shipName.replace(Regex("[^\\w.·\\-()μ★]"), "_")
                val destFile = File(avatarDir, "ship_$safeFileName.jpg")

                // 如果已复制过且文件存在，直接复用
                if (!destFile.exists()) {
                    context.assets.open(assetPath).use { input ->
                        destFile.outputStream().use { output ->
                            input.copyTo(output)
                        }
                    }
                }
                val localPath = "file://${destFile.absolutePath}"
                // 复合格式：本地文件优先，网络 URL 作为回退
                if (normalizedNetworkUrl.isNotBlank() && normalizedNetworkUrl != localPath) {
                    "$localPath||$normalizedNetworkUrl"
                } else {
                    localPath
                }
            } catch (e: Exception) {
                Log.e(TAG, "Failed to copy ship avatar from assets: $assetPath", e)
                // 复制失败时直接使用网络 URL
                normalizedNetworkUrl
            }
        }
    }
    fun saveVoiceEnabled(enabled: Boolean) { viewModelScope.launch { settings.setAiVoiceEnabled(enabled) } }
    fun saveVoiceRandomChance(chance: Float) { viewModelScope.launch { settings.setAiVoiceRandomChance(chance) } }
    fun saveVoiceKeywords(keywords: String) { viewModelScope.launch { settings.setAiVoiceKeywords(keywords) } }
    fun saveModel(model: String) { viewModelScope.launch { settings.setAiModel(model) } }
    fun saveVoiceShipName(name: String) { viewModelScope.launch { settings.setAiVoiceShipName(name) } }
    fun saveVoiceShipAvatar(avatar: String) { viewModelScope.launch { settings.setAiVoiceShipAvatar(normalizeUrl(avatar)) } }
    fun saveStickersEnabled(enabled: Boolean) { viewModelScope.launch { settings.setAiStickersEnabled(enabled) } }
    fun saveStickerChance(chance: Float) { viewModelScope.launch { settings.setAiStickerChance(chance) } }

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

    // ── 表情包资源库 ──
    // 来源: https://wiki.biligame.com/blhx/%E8%A1%A8%E6%83%85%E5%8C%85
    // 使用 patchwiki.biligame.com 直链，Coil + OkHttp 加载
    private val stickerLibrary = listOf(
        // ═══════════════════════════════════════════════════
        // 新表情（19 项）
        // ═══════════════════════════════════════════════════
        StickerResource("https://patchwiki.biligame.com/images/blhx/1/1a/pplmi3rbpprvizqr5lgotrss43ff14i.png", listOf("惊了", "震惊", "不会吧"), "惊了"),
        StickerResource("https://patchwiki.biligame.com/images/blhx/1/12/etkhxbpwfb5dsx46zbocml1sr8mnusn.png", listOf("快住手", "住手", "停下"), "快住手"),
        StickerResource("https://patchwiki.biligame.com/images/blhx/e/e9/stps3z3zhm1u0ezc520jtmecuyksii1.png", listOf("抓到你了", "抓到", "逮到"), "抓到你了~"),
        StickerResource("https://patchwiki.biligame.com/images/blhx/8/87/5jlkxre04bblkmo5v7al36d9nrin3g7.png", listOf("好热", "热", "流汗"), "好热啊…"),
        StickerResource("https://patchwiki.biligame.com/images/blhx/a/a6/g50cq4ezrqqs1sifa9qw8hn8qno0k2f.png", listOf("还不睡", "睡觉", "该睡了"), "还不睡"),
        StickerResource("https://patchwiki.biligame.com/images/blhx/5/50/7wpm0szjxcjuy4pwvexznnwqyueo9nk.png", listOf("眠眠", "困", "想睡"), "眠眠"),
        StickerResource("https://patchwiki.biligame.com/images/blhx/1/12/njet0s7923g93o1v0zdk7ilkl953e47.png", listOf("准备万全", "准备好了", "就绪"), "准备万全"),
        StickerResource("https://patchwiki.biligame.com/images/blhx/9/96/qxxput26d347lfj0alotoupif7klrpy.png", listOf("再等等", "等等", "稍等"), "再等等"),
        StickerResource("https://patchwiki.biligame.com/images/blhx/5/5c/hcbu91tgnwlqrwkynoyupu7064mmokf.png", listOf("开饭", "吃饭", "饿了"), "开饭啦"),
        StickerResource("https://patchwiki.biligame.com/images/blhx/6/62/7o9s2yss4eoysh4dlj4m2z5abt40uqs.png", listOf("闪亮登场", "登场", "出场"), "闪亮登场"),
        StickerResource("https://patchwiki.biligame.com/images/blhx/e/e5/absxsrd2sos5hd8allacjr2hrbtfb13.png", listOf("吃什么", "吃啥", "想吃"), "吃什么呢？"),
        StickerResource("https://patchwiki.biligame.com/images/blhx/c/c9/kyipp7y5nd6xezu9pkswxkcoravvhz7.png", listOf("让我看看", "看看", "瞅瞅"), "让我看看"),
        StickerResource("https://patchwiki.biligame.com/images/blhx/a/a8/b0nsyeoe2athrm1q1xlqrl1u4xhtn4g.png", listOf("按摩", "舒服", "放松"), "按摩按摩"),
        StickerResource("https://patchwiki.biligame.com/images/blhx/c/c8/jd1vczvnf05wpzv9sju02kgx6jefwb3.png", listOf("乖巧", "乖", "听话"), "乖巧"),
        StickerResource("https://patchwiki.biligame.com/images/blhx/d/d0/8ok459s13l6gqe3m8dxfi5ycbiu2cym.png", listOf("别看", "不许看", "转过去"), "别看"),
        StickerResource("https://patchwiki.biligame.com/images/blhx/0/06/2ol7wblu8xusvoiwgtxqx820g02d5on.png", listOf("大大", "好大", "这么大"), "大大"),
        StickerResource("https://patchwiki.biligame.com/images/blhx/2/2a/1h51txw82zkkn9qam59ydx8zmb6sbus.png", listOf("惊", "吓", "哇"), "惊"),
        StickerResource("https://patchwiki.biligame.com/images/blhx/c/c6/6kf9qgrfswon1uh5z7axzy0b5ilygzi.gif", listOf("怒了", "生气", "发怒"), "怒了！"),
        StickerResource("https://patchwiki.biligame.com/images/blhx/9/95/5tzutvjr6imqdpte369qmqtq4q20a14.gif", listOf("看这里", "看这", "注意"), "看这里！"),

        // ═══════════════════════════════════════════════════
        // 官方动态表情包第三弹 (by Seseren)（24 项）
        // ═══════════════════════════════════════════════════
        StickerResource("https://patchwiki.biligame.com/images/blhx/5/51/kztm6swwpg3aslxiupb9who1dw5m72x.gif", listOf("点这里", "过来", "喂", "嘿"), "点这里"),
        StickerResource("https://patchwiki.biligame.com/images/blhx/5/58/7w3ob2nzbwgagqaypbsuto1lrp2wisg.gif", listOf("灵魂出窍", "震惊", "吓死", "晕"), "灵魂出窍"),
        StickerResource("https://patchwiki.biligame.com/images/blhx/b/b4/aefbc7eowvprfjhi3nf0c85jgdgn1j4.gif", listOf("嘲讽", "鄙视", "看不起"), "嘲讽"),
        StickerResource("https://patchwiki.biligame.com/images/blhx/d/d3/akqa32ui49nov2bpnrknrxrwcbxp6w8.gif", listOf("吃", "美食", "好吃", "饿"), "吃披萨"),
        StickerResource("https://patchwiki.biligame.com/images/blhx/9/94/gp1qbm03ohim6tx2nn9wf16ulnl5on7.gif", listOf("登场", "闪亮", "出场", "锵"), "锵"),
        StickerResource("https://patchwiki.biligame.com/images/blhx/8/8f/2ohgz0kzdke7bx1vtmwvefz1e5alw7h.gif", listOf("柠檬", "酸", "羡慕", "嫉妒"), "柠檬"),
        StickerResource("https://patchwiki.biligame.com/images/blhx/3/3c/0n2lucjg028ocqcnhz57e0lzax7fkii.gif", listOf("收拾你", "打你", "揍", "惩罚"), "收拾你"),
        StickerResource("https://patchwiki.biligame.com/images/blhx/6/62/bv6mkviiu19pmmowds9izb3xgy5vkvl.gif", listOf("双重闪亮", "闪亮", "耀眼"), "双重闪亮"),
        StickerResource("https://patchwiki.biligame.com/images/blhx/c/c0/hmilsh86dr8o5l2atdr5zft7nsoe0mj.gif", listOf("我来了", "来了", "到", "报到"), "我来了"),
        StickerResource("https://patchwiki.biligame.com/images/blhx/5/5b/fjiics3pfj893996b0ihskj45b1hltg.gif", listOf("砰砰", "爆炸", "轰", "开火"), "砰砰"),
        StickerResource("https://patchwiki.biligame.com/images/blhx/0/05/ggcamyow5jmoslw03az2ihayz08rgwm.gif", listOf("唱歌", "歌唱", "音乐", "啦啦"), "歌唱生命"),
        StickerResource("https://patchwiki.biligame.com/images/blhx/c/c7/4uxymdps8g137nzjtdaswg9nliyuoha.gif", listOf("标枪", "兔耳"), "标枪"),
        StickerResource("https://patchwiki.biligame.com/images/blhx/6/6e/9vqa8n47ucdl6wpoljjmkveulmt9fht.gif", listOf("兔耳飞", "飞", "跳"), "兔耳飞"),
        StickerResource("https://patchwiki.biligame.com/images/blhx/c/c6/3kyx6e0vsugwc2wdjgnitqk6q2jq78x.gif", listOf("哼哼", "傲娇", "才不是"), "哼哼"),
        StickerResource("https://patchwiki.biligame.com/images/blhx/8/8f/fed208x01eggp3vafij5oc7kgfho20h.gif", listOf("加班", "工作", "忙", "奋斗"), "加班"),
        StickerResource("https://patchwiki.biligame.com/images/blhx/1/13/18qigej0wvge94ftlejga55czdp1avt.gif", listOf("加班", "工作", "忙"), "加班-2"),
        StickerResource("https://patchwiki.biligame.com/images/blhx/0/07/mcnrhekyoniggr55iy7veud9yovxwhl.gif", listOf("微笑", "靠近", "笑"), "微笑靠近"),
        StickerResource("https://patchwiki.biligame.com/images/blhx/f/f3/oumyt46hnvrh6a7laognpi5bh1ffbth.gif", listOf("海豹", "萌", "小动物"), "小海豹"),
        StickerResource("https://patchwiki.biligame.com/images/blhx/c/c4/3jtnxaqmrewp27wovhl0fnj4b17bda6.gif", listOf("愤怒", "暴怒", "气死", "极限愤怒"), "极限愤怒"),
        StickerResource("https://patchwiki.biligame.com/images/blhx/8/83/kga3atemix1zil2hkkpt3q22kzgj1ad.gif", listOf("花", "漂亮", "美丽", "好看"), "花花"),
        StickerResource("https://patchwiki.biligame.com/images/blhx/5/5e/gch50jl7gmnfo29ius5vfvpz7sbr1eu.gif", listOf("没办法", "无奈", "算了"), "真没办法"),
        StickerResource("https://patchwiki.biligame.com/images/blhx/6/6d/0ve9q4m5njt48wjppew1jwr1i8ewmpl.gif", listOf("哈哈", "哇哈哈", "大笑", "笑死"), "哇哈哈"),
        StickerResource("https://patchwiki.biligame.com/images/blhx/e/e5/k6vbtfy1310ezogrcgpgonk50ge02z5.gif", listOf("美味", "超好吃", "馋"), "超美味"),
        StickerResource("https://patchwiki.biligame.com/images/blhx/c/ca/nz6i1pu837ae0gr4d6s7e47btdrwpz3.gif", listOf("停下", "快停下", "住手"), "快停下啊"),

        // ═══════════════════════════════════════════════════
        // 官方动态表情包第二弹（24 项）
        // ═══════════════════════════════════════════════════
        StickerResource("https://patchwiki.biligame.com/images/blhx/5/5e/8f7i8j706ddsdwot5yia3g9heyiyrnh.gif", listOf("吓晕", "吓死", "晕倒"), "吓晕"),
        StickerResource("https://patchwiki.biligame.com/images/blhx/7/7e/k42ea9g8vzhqbuj81cu6mfm8rcin4bv.gif", listOf("危险", "救命", "要掉了"), "要掉了"),
        StickerResource("https://patchwiki.biligame.com/images/blhx/8/81/4ydtk0sc6rljrj36j3turjucxm3l3au.gif", listOf("喝", "干杯", "一起喝", "饮料"), "一起喝？"),
        StickerResource("https://patchwiki.biligame.com/images/blhx/e/e5/p7k1r0gd5pbdspfjshsoi4u09kufj5s.gif", listOf("晕头", "转圈", "迷糊", "头晕"), "晕头转向"),
        StickerResource("https://patchwiki.biligame.com/images/blhx/1/1e/46bbzxcjmvju5xx04z719f8x4aksqv8.gif", listOf("嘿嘿", "欸嘿嘿", "坏笑", "偷笑"), "欸嘿嘿"),
        StickerResource("https://patchwiki.biligame.com/images/blhx/4/47/mxzsjoagp8sg5nwm8klguhgzbs1iq67.gif", listOf("被戳", "戳", "碰我"), "被戳"),
        StickerResource("https://patchwiki.biligame.com/images/blhx/2/2e/r4xh6cgowytsopa6jk0rxkkdsaoo0q3.gif", listOf("魔术", "惊喜", "变魔术"), "变魔术"),
        StickerResource("https://patchwiki.biligame.com/images/blhx/b/b1/avec8c9zh7xslwr9aheb5dr2bl7b8sr.gif", listOf("唱歌", "歌唱", "音乐"), "唱歌"),
        StickerResource("https://patchwiki.biligame.com/images/blhx/e/e8/th7uhaufwqeama9y55way3a3vuq37sx.gif", listOf("充电", "充能", "满血"), "充电ing"),
        StickerResource("https://patchwiki.biligame.com/images/blhx/9/99/gs81rdp8by6uer5sxg8klm9l10id4sk.gif", listOf("吹奏", "演奏", "乐器"), "吹奏"),
        StickerResource("https://patchwiki.biligame.com/images/blhx/c/cd/4qoqj4v3i50t64s7t0ns1nxl2sg8gbl.gif", listOf("打call", "应援", "支持", "加油"), "打call"),
        StickerResource("https://patchwiki.biligame.com/images/blhx/f/fa/7cqrz6b9g9dw4w0q6880h7f7y3ecz85.gif", listOf("大哭", "哭", "呜呜", "伤心", "难过"), "大哭"),
        StickerResource("https://patchwiki.biligame.com/images/blhx/7/77/ebfs7mjj6dse5ksjnanddhe9a967rbd.gif", listOf("点赞", "赞", "好"), "点赞"),
        StickerResource("https://patchwiki.biligame.com/images/blhx/9/95/g9t40q44bqvc96maec8ogy661i93e3q.gif", listOf("咕嘟", "喝", "喝水"), "咕嘟咕嘟"),
        StickerResource("https://patchwiki.biligame.com/images/blhx/e/e0/66yvzc6gngtdgtekb1fdu95fp8p6qpy.gif", listOf("咔嚓", "拍照", "照片"), "咔嚓"),
        StickerResource("https://patchwiki.biligame.com/images/blhx/9/94/cbnov0uo8tvgzue7ovke8zo53bi67r2.gif", listOf("脸红", "害羞", "不好意思", "羞"), "脸红"),
        StickerResource("https://patchwiki.biligame.com/images/blhx/a/aa/betnh2iiu6pqznd9bwro1seb86of5o1.gif", listOf("跑来跑去", "跑", "跑路"), "跑来跑去"),
        StickerResource("https://patchwiki.biligame.com/images/blhx/9/9d/f68fs0s62yl1v9higzbvqvwl9zuy5ca.gif", listOf("气", "生气", "怒"), "气"),
        StickerResource("https://patchwiki.biligame.com/images/blhx/d/d0/hgax0kjpdg52q3f0x47hyeg2t5d6gel.gif", listOf("去吧", "上", "冲"), "去吧！"),
        StickerResource("https://patchwiki.biligame.com/images/blhx/3/33/awpd12qfttm7znaokzhqvt9s2enqovn.gif", listOf("闪闪", "红花", "奖励"), "闪闪红花"),
        StickerResource("https://patchwiki.biligame.com/images/blhx/7/7f/pqh045mzn5zpumw3gj2x4u1d9gmjz2g.gif", listOf("失落", "沮丧", "低落"), "失落"),
        StickerResource("https://patchwiki.biligame.com/images/blhx/5/54/24vvrmnw5fvlyh6tkq5ctxq0cuurmwk.gif", listOf("水管", "喷水", "水"), "水管"),
        StickerResource("https://patchwiki.biligame.com/images/blhx/0/0b/rgbnm43k7sb1ea1oxs891c27d4uzkpo.gif", listOf("上课", "听讲", "学习"), "听我上课！"),
        StickerResource("https://patchwiki.biligame.com/images/blhx/b/b0/411flib2mjo38m3j0meyqwegrv5tm9c.gif", listOf("停下来", "停下啊", "住手"), "停下来啊！"),

        // ═══════════════════════════════════════════════════
        // 官方表情包第二弹（精选 20 项）
        // ═══════════════════════════════════════════════════
        StickerResource("https://patchwiki.biligame.com/images/blhx/d/d1/hyzl8slvtk791vtg4xwmqo58teke77h.gif", listOf("？！", "震惊", "不会吧"), "？！？！"),
        StickerResource("https://patchwiki.biligame.com/images/blhx/9/9a/fyumt24usynofuxl5ngiffzkgwyzwdx.gif", listOf("excellent", "优秀", "完美"), "Excellent"),
        StickerResource("https://patchwiki.biligame.com/images/blhx/0/0c/56ijalb2wh324jaalwmlohqi5qi1rme.gif", listOf("victory", "胜利", "赢了"), "Victory"),
        StickerResource("https://patchwiki.biligame.com/images/blhx/e/ec/mdzkf52gyh46ve85imzejy8aa3yuobs.gif", listOf("爱情", "爱意", "喜欢"), "爱情表现"),
        StickerResource("https://patchwiki.biligame.com/images/blhx/7/78/smthc7atl7mikgtuz9e3m1e66z9p7l3.gif", listOf("抱抱", "拥抱", "贴贴", "抱"), "抱抱"),
        StickerResource("https://patchwiki.biligame.com/images/blhx/c/cd/2749okiuv71ln6xohgxluhmpfftuwgk.gif", listOf("不准看", "不许看", "别看"), "不准看"),
        StickerResource("https://patchwiki.biligame.com/images/blhx/9/96/ktbpt7t4dx351l9176utwdj1xb3ksrn.gif", listOf("超可爱", "可爱", "萌"), "超可爱"),
        StickerResource("https://patchwiki.biligame.com/images/blhx/d/d8/44rjib91jpynpcd8ybkvx7iize3uycd.gif", listOf("超辣", "辣", "好辣"), "超辣"),
        StickerResource("https://patchwiki.biligame.com/images/blhx/0/08/des07if98h4m1j4pr53pzn8w0x86juf.gif", listOf("逮捕", "抓", "拘留"), "逮捕"),
        StickerResource("https://patchwiki.biligame.com/images/blhx/5/55/0jtn4h8p01875t4buyaqx3pwczn1xtl.gif", listOf("刚起床", "起床", "早安"), "刚起床"),
        StickerResource("https://patchwiki.biligame.com/images/blhx/4/45/keengam0uut4y7661d06nvabc4td5kn.gif", listOf("好吃", "美味", "好吃…"), "好吃…"),
        StickerResource("https://patchwiki.biligame.com/images/blhx/4/48/4njzmpmzn1dzpu0okx2f8mo925rm202.gif", listOf("呵呵呵", "呵呵", "冷笑"), "呵呵呵…"),
        StickerResource("https://patchwiki.biligame.com/images/blhx/a/ad/cv8zdzt58duk7k3gbk49vp30ighdxyt.gif", listOf("欢迎回来", "欢迎", "回来"), "欢迎回来"),
        StickerResource("https://patchwiki.biligame.com/images/blhx/5/58/sun2w5kz5656n2jknerspqhwzkr6glg.gif", listOf("见敌必杀", "必杀", "战斗"), "见敌必杀"),
        StickerResource("https://patchwiki.biligame.com/images/blhx/1/18/0ludph3elesw2rcoh3d8rwi07tb6ae5.gif", listOf("笨蛋", "你是笨蛋吗", "傻"), "你是笨蛋吗？"),
        StickerResource("https://patchwiki.biligame.com/images/blhx/9/99/2jl1makx7q6j4w9gp7s4lp5sxvqnl3f.gif", listOf("期待", "期待哦", "盼望"), "期待哦"),
        StickerResource("https://patchwiki.biligame.com/images/blhx/a/a3/bjer4epw0342p5n4xu8666z8ungmny8.gif", listOf("任务完成", "完成", "搞定"), "任务完成"),
        StickerResource("https://patchwiki.biligame.com/images/blhx/d/d4/mz3nvjx6sl7d6i6vn3pzdd8psao3ha7.gif", listOf("太慢了", "速度慢", "慢"), "速度太慢了"),
        StickerResource("https://patchwiki.biligame.com/images/blhx/a/a2/475hwv07p7s7kr3th64sgks75jzhz5c.gif", listOf("提不起劲", "没动力", "懒"), "提不起劲"),
        StickerResource("https://patchwiki.biligame.com/images/blhx/2/2d/5ktivqoca1pt97gsbxma7qj98d5u2u4.gif", listOf("休息中", "休息", "歇会"), "休息中"),

        // ═══════════════════════════════════════════════════
        // 官方像素表情包（15 项）
        // ═══════════════════════════════════════════════════
        StickerResource("https://patchwiki.biligame.com/images/blhx/2/22/d9oilk04vn7vl1u8iyivp961lwr5kyf.gif", listOf("唔嗯", "嗯", "思考"), "唔嗯"),
        StickerResource("https://patchwiki.biligame.com/images/blhx/4/44/dhl8nayk4e287n24ar5a26w7d1aqs7g.gif", listOf("今晚", "来点什么", "吃什么"), "今天晚上来点什么"),
        StickerResource("https://patchwiki.biligame.com/images/blhx/2/21/5q9389v3atiwck6pndjjc7cikvurur1.gif", listOf("沉思", "想", "思考"), "沉思ing"),
        StickerResource("https://patchwiki.biligame.com/images/blhx/8/85/n2kct18jnb72u8b2e8a5r4pv29jb55l.gif", listOf("改造", "等我", "等一下"), "等我改造完"),
        StickerResource("https://patchwiki.biligame.com/images/blhx/9/98/9bpz69c5cwq1sx4r113wzh2mr0eyihk.gif", listOf("锉刀", "锉两刀", "修理"), "要不要我锉两刀"),
        StickerResource("https://patchwiki.biligame.com/images/blhx/f/f4/h53cuk7w3u5crxb8it59b0zd6ai1yya.gif", listOf("泪流", "哭", "流泪"), "泪流不止"),
        StickerResource("https://patchwiki.biligame.com/images/blhx/7/78/mt4fkye20zi6msxzyqq34chvc5jd6cr.gif", listOf("萌萌哒", "萌", "可爱"), "萌萌哒"),
        StickerResource("https://patchwiki.biligame.com/images/blhx/4/41/rgx7j6j0qasl2k0v0cnm20bwv9npi1k.gif", listOf("完蛋", "哦豁", "糟了"), "哦豁，完蛋"),
        StickerResource("https://patchwiki.biligame.com/images/blhx/1/1d/5y9c562o3osla7m7qjhyjovzzj1ihad.gif", listOf("热情", "火热", "热情似火"), "热情似火"),
        StickerResource("https://patchwiki.biligame.com/images/blhx/4/40/4eeeqbrbm6l5h9awzoev2du8qlpw8vy.gif", listOf("没生气", "生气", "哼"), "我没生气"),
        StickerResource("https://patchwiki.biligame.com/images/blhx/6/6b/1iq3mkjyl0ktzymy63bcwfgkx7y8ss5.gif", listOf("拉菲", "来点"), "要来点拉菲嘛"),
        StickerResource("https://patchwiki.biligame.com/images/blhx/1/1e/gfy8nz6muis25rtujb4v1vu4dab4lql.gif", listOf("啊我死了", "死了", "不行了"), "啊我死了"),
        StickerResource("https://patchwiki.biligame.com/images/blhx/8/8c/dmeo4jtqcqw8j4ca135fbdjtp859tmu.gif", listOf("睡觉", "困", "zzz", "晚安"), "ZZZ"),
        StickerResource("https://patchwiki.biligame.com/images/blhx/7/78/3xomzem65o0f5mc2671ubf61dkz34cf.gif", listOf("？？", "疑惑", "什么"), "？？？？？"),
        StickerResource("https://patchwiki.biligame.com/images/blhx/7/73/mh4fnn3g43l8b9vl9uhe3iwtyevci1j.gif", listOf("揍你", "打你", "揍"), "揍你哦"),

        // ═══════════════════════════════════════════════════
        // 官方表情包（精选 20 项）
        // ═══════════════════════════════════════════════════
        StickerResource("https://patchwiki.biligame.com/images/blhx/a/af/7wdz38nj335rsuj5enqpamgeocm0fop.png", listOf("不可以", "不行", "禁止"), "不可以！"),
        StickerResource("https://patchwiki.biligame.com/images/blhx/b/b9/sud52ogw7cdm9fh5z0ojouu7h7bfwlu.png", listOf("开玩笑", "开玩笑的", "逗你"), "开玩笑的"),
        StickerResource("https://patchwiki.biligame.com/images/blhx/f/f1/2x6g4u1t1oewytpodfb2xrfdfc2ihzn.png", listOf("胆小鬼", "胆小", "怕"), "胆小鬼"),
        StickerResource("https://patchwiki.biligame.com/images/blhx/3/3c/bsuoe7i0d9sgusotzf41j508zlh4zxq.png", listOf("跪下", "跪", "惩罚"), "给我跪下"),
        StickerResource("https://patchwiki.biligame.com/images/blhx/9/94/j20uxqzi2to6bgtsp2f2mtg7djihy1n.png", listOf("通宵", "熬夜", "肝"), "通宵"),
        StickerResource("https://patchwiki.biligame.com/images/blhx/b/b7/f1686kxd3ufa1s62jqxv2egw6f9e4k7.png", listOf("要来吗", "来吗", "邀请"), "要来吗？"),
        StickerResource("https://patchwiki.biligame.com/images/blhx/4/49/90ma8mb0tprmafe9gjpffjgvzk01p6v.png", listOf("笨蛋", "笨", "傻"), "笨蛋"),
        StickerResource("https://patchwiki.biligame.com/images/blhx/5/5e/7xsy9cdbjr4tba956zfe49863dv9pq1.png", listOf("萌", "可爱"), "萌"),
        StickerResource("https://patchwiki.biligame.com/images/blhx/b/b0/a3g5pj3uw0wdnmgvsx5dl7da14p1445.png", listOf("早安", "早上好", "早"), "早安"),
        StickerResource("https://patchwiki.biligame.com/images/blhx/2/24/qqu4swk7jm0em1ds3b5afsmh85fra4l.png", listOf("ok", "好的", "没问题"), "OK"),
        StickerResource("https://patchwiki.biligame.com/images/blhx/4/4c/iqtb6zp49sgqxfd1zpsk8uey2j67wx7.png", listOf("变态", "色狼", "hentai"), "变态！"),
        StickerResource("https://patchwiki.biligame.com/images/blhx/0/06/cd6lvph1a1pbd162rtd5whnkhp0jd6m.png", listOf("优雅", "优美", "端庄"), "优雅"),
        StickerResource("https://patchwiki.biligame.com/images/blhx/9/95/6fxn9n7fjh5o6i1lhy186dpylurn7gn.png", listOf("晚安", "睡了", "好梦"), "晚安"),
        StickerResource("https://patchwiki.biligame.com/images/blhx/0/09/czcxfudb7vw1mfo3okfnwnudqd2661h.png", listOf("发现猎物", "猎物", "发现"), "发现猎物"),
        StickerResource("https://patchwiki.biligame.com/images/blhx/2/28/6jqlmo28ymrrerny5jks8omojq5t4qm.png", listOf("突破", "限界突破", "升级"), "限界突破"),
        StickerResource("https://patchwiki.biligame.com/images/blhx/9/9f/rdfy116m9affa0z8ppnh25lxr89q6ax.png", listOf("揍你", "打你", "揍"), "揍你哦"),
        StickerResource("https://patchwiki.biligame.com/images/blhx/e/e9/axe34i7436utl16ehoxu28sqsvz3tvi.png", listOf("请多指教", "指教", "多多指教"), "请多指教"),
        StickerResource("https://patchwiki.biligame.com/images/blhx/4/47/fnoovu1qq3vn7040hoobwmc7n0huxm4.png", listOf("！？", "震惊", "诶"), "！？"),
        StickerResource("https://patchwiki.biligame.com/images/blhx/4/4c/5vi3b4ep1y7957ccwsimise7nw78km3.png", listOf("走运", "幸运", "运气好"), "真走运！"),
        StickerResource("https://patchwiki.biligame.com/images/blhx/8/81/4edmny47r0ig7l0rw3g51hbcolhnc6w.png", listOf("就是这样", "没错", "对"), "就是这样")
    )

    // ── 表情包语境匹配机制 ──
    /**
     * 根据 AI 回复文本匹配最合适的表情包
     * 策略：
     * 1. 标签精确匹配（优先匹配更长的标签，避免短标签误匹配）
     * 2. 情绪倾向分析（基于关键词组合判断整体情绪）
     * 3. 保底：不返回表情包（避免无关表情包干扰对话）
     */
    private fun findBestSticker(text: String): StickerResource? {
        // 1. 标签精确匹配：计算每个表情包的匹配得分
        val scored = stickerLibrary.mapNotNull { sticker ->
            val bestTag = sticker.tags
                .filter { tag -> text.contains(tag, ignoreCase = true) }
                .maxByOrNull { it.length } // 优先匹配更长的标签
            if (bestTag != null) sticker to bestTag.length else null
        }

        if (scored.isNotEmpty()) {
            // 同分中随机选一个，增加多样性
            val maxScore = scored.maxOf { it.second }
            val topMatches = scored.filter { it.second == maxScore }
            return topMatches.random().first
        }

        // 2. 情绪倾向分析（基于多关键词组合判断）
        val emotionSticker = analyzeEmotionTendency(text)
        if (emotionSticker != null) return emotionSticker

        // 3. 不强制返回表情包，避免无关表情包
        return null
    }

    /**
     * 情绪倾向分析：根据文本中的关键词组合判断整体情绪
     * 返回最符合情绪的表情包，或 null 表示无法判断
     */
    private fun analyzeEmotionTendency(text: String): StickerResource? {
        // 定义情绪关键词组（需同时满足正向/负向来避免误判）
        return when {
            // ── 积极情绪 ──
            hasAny(text, "好耶", "太棒了", "太好了", "好开心", "嘻嘻", "开心") ->
                stickerLibrary.find { it.name == "哇哈哈" }

            hasAny(text, "嘿嘿", "坏笑") ->
                stickerLibrary.find { it.name == "欸嘿嘿" }

            hasAny(text, "喜欢你", "最喜欢", "好喜欢", "爱") ->
                stickerLibrary.find { it.name == "爱情表现" }

            hasAny(text, "谢谢", "感谢") ->
                stickerLibrary.find { it.name == "点赞" }

            hasAny(text, "加油", "你可以的", "一起努力") ->
                stickerLibrary.find { it.name == "打call" }

            hasAny(text, "可爱", "萌") ->
                stickerLibrary.find { it.name == "超可爱" }

            hasAny(text, "欢迎回来", "欢迎") ->
                stickerLibrary.find { it.name == "欢迎回来" }

            // ── 消极情绪 ──
            hasAny(text, "好难过", "好伤心", "好委屈", "哭") ->
                stickerLibrary.find { it.name == "大哭" }

            hasAny(text, "好生气", "气死了", "好气", "怒") ->
                stickerLibrary.find { it.name == "极限愤怒" }

            hasAny(text, "好累", "好困", "想睡觉", "困") ->
                stickerLibrary.find { it.name == "ZZZ" }

            hasAny(text, "完蛋", "糟了", "哦豁") ->
                stickerLibrary.find { it.name == "哦豁，完蛋" }

            hasAny(text, "失落", "沮丧", "低落") ->
                stickerLibrary.find { it.name == "失落" }

            // ── 傲娇 ──
            hasAny(text, "才不是", "哼，才", "笨蛋指挥官", "笨蛋") ->
                stickerLibrary.find { it.name == "你是笨蛋吗？" }

            // ── 亲密 ──
            hasAny(text, "贴贴", "抱抱你", "摸摸头", "抱") ->
                stickerLibrary.find { it.name == "抱抱" }

            hasAny(text, "害羞", "脸红", "不好意思") ->
                stickerLibrary.find { it.name == "脸红" }

            // ── 惊讶 ──
            hasAny(text, "诶", "不会吧", "震惊", "？！") ->
                stickerLibrary.find { it.name == "？！？！" }

            else -> null
        }
    }

    /** 检查文本是否包含任一关键词 */
    private fun hasAny(text: String, vararg keywords: String): Boolean =
        keywords.any { text.contains(it, ignoreCase = true) }

    private fun addStickerMessage(sticker: StickerResource, shipName: String, avatarUrl: String) {
        val stickerMessage = ChatMessage(
            id = UUID.randomUUID().toString(),
            type = ChatMessageType.STICKER.name,
            content = sticker.name, // 存储表情包名称，加载失败时作为文字回退
            timestamp = System.currentTimeMillis(),
            shipName = shipName,
            stickerUrl = sticker.url,
            avatarUrl = avatarUrl
        )
        addMessage(stickerMessage)
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
                shipAvatar = shipDao.getShipByName(selectedShip, ArchiveType.DOCK.name)?.avatarUrl?.let { normalizeUrl(it) } ?: ""
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

                // 表情包逻辑：开关开启 + 概率命中 + 匹配到表情包
                if (settings.aiStickersEnabled.first()) {
                    val chance = settings.aiStickerChance.first()
                    if (kotlin.random.Random.nextFloat() < chance) {
                        val sticker = findBestSticker(aiResponse)
                        if (sticker != null) {
                            // 稍微延迟发送表情包，模拟输入感
                            kotlinx.coroutines.delay(300)
                            addStickerMessage(sticker, selectedShip.ifBlank { settings.aiName.first() }, shipAvatar.ifBlank { settings.aiAvatarUrl.first() })
                        }
                    }
                }
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
     * 注意：content:// 和 file: 开头的本地 URI 不做任何处理
     *
     * file: URI 有两种形式：
     * - file:///path（三斜杠，有 authority）— 标准 file URI
     * - file:/path（单斜杠，无 authority）— Uri.Builder().scheme("file").path(...) 生成
     * 两者都是本地文件 URI，不应被当作网络 URL 处理。
     *
     * 复合格式 (primary||fallback) 的两部分各自独立规范化。
     */
    private fun normalizeUrl(url: String): String {
        val trimmed = url.trim()
        if (trimmed.isBlank()) return ""
        // 复合格式：分别规范化 primary 和 fallback
        val delimiterIdx = trimmed.indexOf("||")
        if (delimiterIdx >= 0) {
            val primary = normalizeUrl(trimmed.substring(0, delimiterIdx))
            val fallback = normalizeUrl(trimmed.substring(delimiterIdx + 2))
            return if (fallback.isBlank()) primary else "$primary||$fallback"
        }
        // 本地 content:// URI（相册选择等）和 file: URI 不做处理
        if (trimmed.startsWith("content://") || trimmed.startsWith("file:")) return trimmed
        if (trimmed.startsWith("//")) return "https:$trimmed"
        if (!trimmed.startsWith("http://") && !trimmed.startsWith("https://") && trimmed.contains(".")) {
            return "https://$trimmed"
        }
        return trimmed
    }
}

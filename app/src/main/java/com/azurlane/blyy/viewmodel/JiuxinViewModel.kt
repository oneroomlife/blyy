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
import com.azurlane.blyy.data.model.JiuxinPreset
import com.azurlane.blyy.data.model.ApiConfig
import com.azurlane.blyy.data.model.PersonaConfig
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

    companion object {
        private const val TAG = "JiuxinViewModel"
        private const val DEFAULT_MODEL = "gpt-4o-mini"
        private const val MAX_CHAT_HISTORY = 200
        private const val MAX_SESSIONS = 50

        /** 语音触发关键词默认值 — 与 PlayerSettingsDataStore 默认保持一致 */
        private const val DEFAULT_VOICE_KEYWORDS = "你好;早安;晚安;加油;辛苦了"

        /** 默认皮肤名集合（用于语音随机池优先匹配）— 统一定义，避免多处硬编码不一致 */
        private val DEFAULT_SKIN_NAMES = setOf("默认装扮", "默认", "通常", "原装")

        /** 容错 JSON 实例：忽略未知字段，避免模型升级后反序列化崩溃 */
        private val json = Json { ignoreUnknownKeys = true; isLenient = true }
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
    val voiceKeywords = settings.aiVoiceKeywords.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), DEFAULT_VOICE_KEYWORDS)
    val selectedModel = settings.aiModel.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), "")
    val userName = settings.userName.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), "指挥官")
    val userAvatarUrl = settings.userAvatarUrl.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), "")

    private val _availableModels = MutableStateFlow<List<String>>(emptyList())
    val availableModels: StateFlow<List<String>> = _availableModels.asStateFlow()

    val voiceShipName = settings.aiVoiceShipName.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), "")
    val voiceShipAvatar = settings.aiVoiceShipAvatar.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), "")
    val stickersEnabled = settings.aiStickersEnabled.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), true)
    val stickerChance = settings.aiStickerChance.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), 0.8f)
    val chatBackgroundUrl = settings.aiChatBackgroundUrl.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), "")

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

    /**
     * 当前活动会话（派生自 [_sessions] + [_currentSessionId]）。
     *
     * 聊天界面和舰娘设置界面通过此 StateFlow 读取当前会话的配置快照，
     * 而非全局 StateFlow —— 实现会话级配置隔离。
     * 全局 StateFlow 仅作为新建会话的默认值模板，不再被聊天界面直接读取。
     */
    val currentSession: StateFlow<ChatSession?> = combine(_sessions, _currentSessionId) { sessions, id ->
        sessions.firstOrNull { it.id == id }
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), null)

    /**
     * 聊天状态代际计数器：每次清空/删除/切换会话时递增。
     * 用于在 [sendMessage] 的异步 API 调用返回后，检测聊天状态是否已变更，
     * 若已变更则丢弃过期的 AI 回复和表情包，避免已删除/已清空的内容重新出现。
     */
    private val _chatGeneration = java.util.concurrent.atomic.AtomicInteger(0)

    // ── 啾信预设配置 ──
    val presets: StateFlow<List<JiuxinPreset>> = settings.aiJiuxinPresets
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    // ── 多套 API 配置（独立于舰娘人格，可复用） ──
    val apiConfigs: StateFlow<List<ApiConfig>> = settings.aiApiConfigs
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    // ── 多套舰娘人格配置（独立于 API 配置，可组合） ──
    val personaConfigs: StateFlow<List<PersonaConfig>> = settings.aiPersonaConfigs
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    /**
     * 按舰娘去重后的会话列表：同一舰娘的多个会话只保留最新一条。
     *
     * 排序规则（优先级）：
     * 1. 用户自定义排序（[settings.aiSessionOrder]）—— 长按拖动后的顺序
     * 2. 无自定义排序时按 updatedAt 倒序（默认）
     *
     * 去重 key 优先级（多级回退）：
     * 1. presetId 非空 → 按预设 ID 分组
     * 2. avatarUrl 非空 → 按头像 URL 分组（同一头像视为同一舰娘）
     * 3. jiuxinName 非空且非默认格式 → 按舰娘名分组
     * 4. 以上均无 → 统一归入 "default" 分组（避免无标识会话全部独立显示）
     */
    val uniqueConversations: StateFlow<List<ChatSession>> = combine(_sessions, settings.aiSessionOrder) { sessions, order ->
        val deduped = sessions
            .groupBy { session -> computeShipKey(session) }
            .values
            .map { group -> group.maxByOrNull { it.updatedAt } ?: group.first() }
        if (order.isEmpty()) {
            // 无自定义排序：按更新时间倒序
            deduped.sortedByDescending { it.updatedAt }
        } else {
            // 有自定义排序：按 order 中 id 的顺序排列，未包含的追加到末尾（按 updatedAt 倒序）
            val orderMap = order.withIndex().associate { it.value to it.index }
            val ordered = deduped.filter { it.id in orderMap }
                .sortedBy { orderMap[it.id] ?: Int.MAX_VALUE }
            val remaining = deduped.filterNot { it.id in orderMap }
                .sortedByDescending { it.updatedAt }
            ordered + remaining
        }
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    /**
     * 当前舰娘的历史对话列表：仅包含与当前会话同一舰娘的会话。
     *
     * 用于聊天界面的历史对话面板（HistoryPanel），实现"每个舰娘只能查看对应的历史对话"的隔离。
     * 隔离 key 与 [uniqueConversations] 一致（[computeShipKey]）。
     *
     * 当当前会话不存在（如全部删除后）时返回空列表。
     */
    val currentShipSessions: StateFlow<List<ChatSession>> = combine(_sessions, _currentSessionId) { sessions, currentId ->
        val currentSession = sessions.firstOrNull { it.id == currentId } ?: return@combine emptyList()
        val shipKey = computeShipKey(currentSession)
        sessions.filter { computeShipKey(it) == shipKey }
            .sortedByDescending { it.updatedAt }
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    /**
     * 计算舰娘去重 key（统一隔离标识）。
     *
     * 优先级（多级回退）：
     * 1. presetId 非空 → 按预设 ID 分组
     * 2. avatarUrl 非空 → 按头像 URL 分组（同一头像视为同一舰娘）
     * 3. jiuxinName 非空且非默认格式（不以"对话-"开头）→ 按舰娘名分组
     * 4. 以上均无 → 统一归入 "default" 分组
     */
    private fun computeShipKey(session: ChatSession): String = when {
        session.presetId.isNotBlank() -> "preset:${session.presetId}"
        session.avatarUrl.isNotBlank() -> "avatar:${session.avatarUrl}"
        session.jiuxinName.isNotBlank() && !session.jiuxinName.startsWith("对话-") -> "name:${session.jiuxinName}"
        else -> "default"
    }

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

    /**
     * 默认语音标签映射的关键词索引（O(1) 查找）。
     *
     * 用于 [buildVoiceTagMappings] 中判断用户自定义关键词是否已存在默认映射，
     * 替代原先 `defaultVoiceTagMappings.find { it.keyword == kw }` 的 O(n) 线性扫描。
     */
    private val defaultVoiceTagByKeyword: Map<String, VoiceTagMapping> by lazy {
        defaultVoiceTagMappings.associateBy { it.keyword }
    }

    /**
     * 根据语音触发关键词列表构建完整的 voice tag mappings（去重 + 按优先级降序）。
     *
     * 消除三处重复代码（init / switchToSession / sendMessage），统一构建逻辑：
     * - 默认映射全部保留
     * - 用户自定义关键词若与默认重名则跳过（默认优先级更高，避免 distinctBy 后丢失默认映射）
     * - 用户自定义关键词若无对应默认映射，创建 fallback 映射：sceneTags=["主界面"]，priority=1
     *   （"主界面"是最通用的语音类别，确保用户自定义关键词能触发有意义的语音，
     *   而非 [findBestVoice] 的保底随机池；priority=1 确保默认关键词优先匹配）
     * - 按 priority 降序排列，确保 [findBestTagMatch] 在长度相同时优先取高优先级
     *
     * @param keywords 已分词的关键词列表（不含空串）
     */
    private fun buildVoiceTagMappings(keywords: List<String>): List<VoiceTagMapping> {
        val userMappings = keywords.mapNotNull { kw ->
            if (defaultVoiceTagByKeyword.containsKey(kw)) null
            else VoiceTagMapping(keyword = kw, sceneTags = listOf("主界面"), priority = 1)
        }
        return (defaultVoiceTagMappings + userMappings)
            .distinctBy { it.keyword }
            .sortedByDescending { it.priority }
    }




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
                _chatState.update { it.copy(voiceKeywords = list, voiceTagMappings = buildVoiceTagMappings(list)) }
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
    suspend fun resolveAndCopyShipAvatar(shipName: String, networkUrl: String, archiveType: String = "DOCK"): String {
        val normalizedNetworkUrl = normalizeUrl(networkUrl)
        val assetUri = LocalAvatarResolver.resolve(context, shipName, archiveType)
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
                // 文件名包含 archiveType，避免同名不同类型舰娘的头像文件碰撞
                val safeFileName = shipName.replace(Regex("[^\\w.·\\-()μ★]"), "_")
                val safeArchiveType = archiveType.replace(Regex("[^\\w]"), "_")
                val destFile = File(avatarDir, "ship_${safeFileName}_$safeArchiveType.jpg")

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
    fun saveChatBackgroundUrl(url: String) { viewModelScope.launch { settings.setAiChatBackgroundUrl(url) } }

    /**
     * 复制用户选择的背景图片到内部存储，返回可靠的 file:// 路径
     *
     * 与 [copyAvatarToInternalStorage] 类似，但存储到 backgrounds/ 目录。
     * 避免使用 content:// URI，因为其在应用重启后可能丢失访问权限。
     */
    suspend fun copyBackgroundToInternalStorage(uri: Uri): String? {
        return withContext(Dispatchers.IO) {
            try {
                val bgDir = File(context.filesDir, "backgrounds").apply { mkdirs() }
                val destFile = File(bgDir, "bg_${System.currentTimeMillis()}.jpg")
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
                Log.e(TAG, "Failed to copy background to internal storage", e)
                null
            }
        }
    }

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

    /**
     * 构建 POST JSON 请求（统一 Authorization + Content-Type + Accept 头）。
     *
     * 消除 [performConnectionTest] 和 [callApi] 中重复的请求头构建代码。
     */
    private fun buildJsonPostRequest(url: String, key: String, requestBody: okhttp3.RequestBody): Request =
        Request.Builder()
            .url(url)
            .addHeader("Authorization", "Bearer $key")
            .addHeader("Content-Type", "application/json")
            .addHeader("Accept", "application/json")
            .post(requestBody)
            .build()

    /**
     * 构建 GET 请求（统一 Authorization 头）。
     *
     * 消除 [performFetchModels] 中重复的请求头构建代码。
     */
    private fun buildAuthGetRequest(url: String, key: String): Request =
        Request.Builder()
            .url(url)
            .addHeader("Authorization", "Bearer $key")
            .get()
            .build()

    // ── 会话管理 ──

    /** 生成默认会话名称: 对话-YYYYMMDD-HHMMSS */
    private fun generateDefaultName(): String {
        val sdf = SimpleDateFormat("yyyyMMdd-HHmmss", Locale.getDefault())
        return "对话-${sdf.format(Date())}"
    }

    /**
     * 持久化新会话并同步内存状态（消除 createNewSession / startChatWithApiAndPersona / ensureCurrentSession 三处重复代码）。
     *
     * 统一流程：
     * 1. 将新会话插入到列表头部
     * 2. 应用 [MAX_SESSIONS] 上限裁剪
     * 3. 先持久化到 DataStore（确保 collector 触发时数据已就绪，避免竞态覆盖）
     * 4. 再同步更新内存 [_sessions] / [_currentSessionId] / [_chatState]，确保 UI 和在途请求立即感知
     *
     * 注意：调用方需在调用前执行 [_chatGeneration.incrementAndGet]()，使在途的 sendMessage 请求返回后丢弃旧会话的回复。
     *
     * @param session 待激活的新会话（已包含完整配置快照）
     */
    private suspend fun persistAndActivateSession(session: ChatSession) {
        val updated = listOf(session) + _sessions.value
        val trimmed = if (updated.size > MAX_SESSIONS) updated.dropLast(1) else updated
        // 先持久化到 DataStore：确保 collector 触发时 DataStore 已有正确数据
        settings.setAiChatSessions(trimmed)
        settings.setAiCurrentSessionId(session.id)
        settings.setAiSessionMessages(session.id, emptyList())
        // 再同步更新内存状态：确保 UI 和在途请求能立即感知新会话
        _sessions.value = trimmed
        _currentSessionId.value = session.id
        _chatState.update { it.copy(messages = emptyList(), error = null, isLoading = false) }
    }

    /**
     * 创建新会话（可关联预设）
     *
     * 去重一致性保证：如果未显式指定 [presetId]，则继承当前会话的 presetId。
     * 这确保同一舰娘的新旧会话在 [uniqueConversations] 中拥有相同的去重 key，
     * 避免会话列表出现重复的舰娘条目。
     */
    fun createNewSession(presetId: String = "", sessionName: String = "") {
        // 递增代际计数器，使在途的 sendMessage 请求返回后丢弃旧会话的回复
        _chatGeneration.incrementAndGet()
        // 未指定预设时，继承当前会话的 presetId，保持同一舰娘去重 key 一致
        val effectivePresetId = presetId.ifBlank {
            _sessions.value.firstOrNull { it.id == _currentSessionId.value }?.presetId ?: ""
        }
        viewModelScope.launch {
            val preset = if (effectivePresetId.isNotBlank()) presets.value.firstOrNull { it.id == effectivePresetId } else null
            val effectiveName = sessionName.ifBlank {
                // 若关联预设，使用预设名；否则使用默认名
                preset?.name?.ifBlank { generateDefaultName() } ?: generateDefaultName()
            }
            // 保存完整配置快照到会话（用于会话级配置隔离和列表去重）
            val session = ChatSession(
                name = effectiveName,
                presetId = effectivePresetId,
                avatarUrl = preset?.avatarUrl ?: avatarUrl.value,
                jiuxinName = preset?.jiuxinName ?: jiuxinName.value,
                apiUrl = preset?.apiUrl ?: apiUrl.value,
                apiKey = preset?.apiKey ?: apiKey.value,
                model = preset?.model ?: selectedModel.value,
                systemPrompt = preset?.systemPrompt ?: systemPrompt.value,
                voiceShipName = preset?.voiceShipName ?: voiceShipName.value,
                voiceShipAvatar = preset?.voiceShipAvatar ?: voiceShipAvatar.value,
                voiceEnabled = preset?.voiceEnabled ?: voiceEnabled.value,
                voiceRandomChance = preset?.voiceRandomChance ?: voiceRandomChance.value,
                voiceKeywords = preset?.voiceKeywords ?: voiceKeywords.value,
                stickersEnabled = preset?.stickersEnabled ?: stickersEnabled.value,
                stickerChance = preset?.stickerChance ?: stickerChance.value
            )
            persistAndActivateSession(session)
            Log.d(TAG, "Created new session: ${session.id} - ${session.name} (preset=$presetId)")
        }
    }

    /**
     * 为当前舰娘创建新会话（用于历史对话面板的"+"按钮）。
     *
     * 与 [createNewSession] 的关键区别：继承当前会话的**完整配置快照**，
     * 而非回退到全局 StateFlow 默认值。这确保新会话与原会话拥有相同的
     * [computeShipKey]，从而出现在同一舰娘的历史面板中，不会在会话列表
     * 产生"陌生"舰娘条目。
     *
     * 适用场景：用户在某个舰娘的聊天中打开历史面板，点击"+"希望为
     * **同一舰娘**开启一段新对话。
     *
     * - 若当前会话关联了预设：委托 [createNewSession] 走预设路径（预设本身
     *   就携带了完整配置，且 presetId 一致保证去重 key 相同）
     * - 若当前会话无预设：直接复制当前会话的全部配置字段到新会话，
     *   仅生成新的默认名称（"对话-时间戳"），与原会话区分
     * - 若无当前会话：回退到 [createNewSession] 使用全局默认配置
     */
    fun createNewSessionForCurrentShip() {
        val currentSession = _sessions.value.firstOrNull { it.id == _currentSessionId.value }

        // 关联预设的会话：走预设路径，presetId 一致即保证去重 key 相同
        if (currentSession != null && currentSession.presetId.isNotBlank()) {
            createNewSession(presetId = currentSession.presetId)
            return
        }

        // 无当前会话：回退到全局默认
        if (currentSession == null) {
            createNewSession()
            return
        }

        // 无预设但有当前会话：继承当前会话的完整配置快照
        _chatGeneration.incrementAndGet()
        viewModelScope.launch {
            val session = ChatSession(
                name = generateDefaultName(),
                presetId = "", // 无预设
                avatarUrl = currentSession.avatarUrl,
                jiuxinName = currentSession.jiuxinName,
                apiUrl = currentSession.apiUrl,
                apiKey = currentSession.apiKey,
                model = currentSession.model,
                systemPrompt = currentSession.systemPrompt,
                voiceShipName = currentSession.voiceShipName,
                voiceShipAvatar = currentSession.voiceShipAvatar,
                voiceEnabled = currentSession.voiceEnabled,
                voiceRandomChance = currentSession.voiceRandomChance,
                voiceKeywords = currentSession.voiceKeywords,
                stickersEnabled = currentSession.stickersEnabled,
                stickerChance = currentSession.stickerChance
            )
            persistAndActivateSession(session)
            Log.d(TAG, "Created new session for current ship: ${session.id} - ${session.name} (ship=${currentSession.jiuxinName})")
        }
    }

    // ── 啾信预设管理 ──

    /**
     * 将当前 ViewModel 中的全部配置（API + 人格 + 语音 + 表情包）保存为预设。
     * @param presetName 预设名称（通常为舰娘名）。为空时回退到啾信名称。
     * @param existingId 若提供，则更新该 id 对应的预设；否则新增。
     * @return 已保存预设的 id
     */
    fun saveCurrentAsPreset(presetName: String, existingId: String? = null): String {
        val now = System.currentTimeMillis()
        val resolvedId = existingId ?: UUID.randomUUID().toString()
        val preset = JiuxinPreset(
            id = resolvedId,
            name = presetName.ifBlank { jiuxinName.value.ifBlank { "未命名预设" } },
            jiuxinName = jiuxinName.value,
            avatarUrl = avatarUrl.value,
            apiUrl = apiUrl.value,
            apiKey = apiKey.value,
            model = selectedModel.value,
            systemPrompt = systemPrompt.value,
            voiceShipName = voiceShipName.value,
            voiceShipAvatar = voiceShipAvatar.value,
            voiceEnabled = voiceEnabled.value,
            voiceRandomChance = voiceRandomChance.value,
            voiceKeywords = voiceKeywords.value,
            stickersEnabled = stickersEnabled.value,
            stickerChance = stickerChance.value,
            createdAt = if (existingId != null) presets.value.firstOrNull { it.id == existingId }?.createdAt ?: now else now,
            updatedAt = now
        )
        viewModelScope.launch {
            val updated = if (existingId != null) {
                presets.value.map { if (it.id == existingId) preset else it }
            } else {
                presets.value + preset
            }
            settings.setAiJiuxinPresets(updated)
            Log.d(TAG, "Saved preset: ${preset.id} - ${preset.name}")
        }
        return resolvedId
    }

    /** 应用指定预设到当前配置（覆盖当前所有相关设置） */
    fun applyPreset(preset: JiuxinPreset) {
        viewModelScope.launch {
            settings.setAiCustomBaseUrl(preset.apiUrl)
            settings.setAiApiKey(preset.apiKey)
            settings.setAiModel(preset.model)
            settings.setAiSystemPrompt(preset.systemPrompt)
            settings.setAiName(preset.jiuxinName)
            settings.setAiAvatarUrl(normalizeUrl(preset.avatarUrl))
            settings.setAiVoiceShipName(preset.voiceShipName)
            settings.setAiVoiceShipAvatar(normalizeUrl(preset.voiceShipAvatar))
            settings.setAiVoiceEnabled(preset.voiceEnabled)
            settings.setAiVoiceRandomChance(preset.voiceRandomChance)
            settings.setAiVoiceKeywords(preset.voiceKeywords)
            settings.setAiStickersEnabled(preset.stickersEnabled)
            settings.setAiStickerChance(preset.stickerChance)
            Log.d(TAG, "Applied preset: ${preset.id} - ${preset.name}")
        }
    }

    /** 删除指定预设 */
    fun deletePreset(presetId: String) {
        viewModelScope.launch {
            val updated = presets.value.filter { it.id != presetId }
            settings.setAiJiuxinPresets(updated)
            Log.d(TAG, "Deleted preset: $presetId")
        }
    }

    // ── API 配置管理（独立多套） ──

    /**
     * 保存当前 API 配置为新的 API 配置项
     *
     * @param configName 配置名称（如"OpenAI 官方"）
     * @param existingId 非空时更新已有配置，空时新建
     * @return 配置 ID
     */
    fun saveApiConfig(configName: String, existingId: String? = null): String {
        val resolvedId = existingId ?: java.util.UUID.randomUUID().toString()
        val now = System.currentTimeMillis()
        val config = ApiConfig(
            id = resolvedId,
            name = configName.ifBlank { "API 配置" },
            apiUrl = apiUrl.value,
            apiKey = apiKey.value,
            model = selectedModel.value,
            createdAt = if (existingId != null) apiConfigs.value.firstOrNull { it.id == existingId }?.createdAt ?: now else now,
            updatedAt = now
        )
        viewModelScope.launch {
            val updated = if (existingId != null) {
                apiConfigs.value.map { if (it.id == existingId) config else it }
            } else {
                apiConfigs.value + config
            }
            settings.setAiApiConfigs(updated)
            Log.d(TAG, "Saved API config: ${config.id} - ${config.name}")
        }
        return resolvedId
    }

    /** 应用指定 API 配置到当前全局配置 */
    fun applyApiConfig(config: ApiConfig) {
        viewModelScope.launch {
            settings.setAiCustomBaseUrl(config.apiUrl)
            settings.setAiApiKey(config.apiKey)
            settings.setAiModel(config.model)
            Log.d(TAG, "Applied API config: ${config.id} - ${config.name}")
        }
    }

    /** 删除指定 API 配置 */
    fun deleteApiConfig(configId: String) {
        viewModelScope.launch {
            val updated = apiConfigs.value.filter { it.id != configId }
            settings.setAiApiConfigs(updated)
            Log.d(TAG, "Deleted API config: $configId")
        }
    }

    // ── 舰娘人格配置管理（独立多套） ──

    /**
     * 保存当前舰娘人格配置为新的人格配置项
     *
     * @param personaName 人格名称（如"标枪"）
     * @param existingId 非空时更新已有配置，空时新建
     * @return 配置 ID
     */
    fun savePersonaConfig(personaName: String, existingId: String? = null): String {
        val resolvedId = existingId ?: java.util.UUID.randomUUID().toString()
        val now = System.currentTimeMillis()
        val config = PersonaConfig(
            id = resolvedId,
            name = personaName.ifBlank { jiuxinName.value.ifBlank { "未命名舰娘" } },
            jiuxinName = jiuxinName.value,
            avatarUrl = avatarUrl.value,
            systemPrompt = systemPrompt.value,
            voiceShipName = voiceShipName.value,
            voiceShipAvatar = voiceShipAvatar.value,
            voiceEnabled = voiceEnabled.value,
            voiceRandomChance = voiceRandomChance.value,
            voiceKeywords = voiceKeywords.value,
            stickersEnabled = stickersEnabled.value,
            stickerChance = stickerChance.value,
            createdAt = if (existingId != null) personaConfigs.value.firstOrNull { it.id == existingId }?.createdAt ?: now else now,
            updatedAt = now
        )
        viewModelScope.launch {
            val updated = if (existingId != null) {
                personaConfigs.value.map { if (it.id == existingId) config else it }
            } else {
                personaConfigs.value + config
            }
            settings.setAiPersonaConfigs(updated)
            Log.d(TAG, "Saved persona config: ${config.id} - ${config.name}")
        }
        return resolvedId
    }

    /** 应用指定舰娘人格配置到当前全局配置 */
    fun applyPersonaConfig(config: PersonaConfig) {
        viewModelScope.launch {
            settings.setAiSystemPrompt(config.systemPrompt)
            settings.setAiName(config.jiuxinName)
            settings.setAiAvatarUrl(normalizeUrl(config.avatarUrl))
            settings.setAiVoiceShipName(config.voiceShipName)
            settings.setAiVoiceShipAvatar(normalizeUrl(config.voiceShipAvatar))
            settings.setAiVoiceEnabled(config.voiceEnabled)
            settings.setAiVoiceRandomChance(config.voiceRandomChance)
            settings.setAiVoiceKeywords(config.voiceKeywords)
            settings.setAiStickersEnabled(config.stickersEnabled)
            settings.setAiStickerChance(config.stickerChance)
            Log.d(TAG, "Applied persona config: ${config.id} - ${config.name}")
        }
    }

    /** 删除指定舰娘人格配置 */
    fun deletePersonaConfig(configId: String) {
        viewModelScope.launch {
            val updated = personaConfigs.value.filter { it.id != configId }
            settings.setAiPersonaConfigs(updated)
            Log.d(TAG, "Deleted persona config: $configId")
        }
    }

    /** 一键清空当前舰娘人格所有字段（头像、名称、提示词、语音、表情包），方便重新填写 */
    fun clearPersonaFields() {
        viewModelScope.launch {
            settings.setAiName("")
            settings.setAiAvatarUrl("")
            settings.setAiSystemPrompt("")
            settings.setAiVoiceShipName("")
            settings.setAiVoiceShipAvatar("")
            settings.setAiVoiceKeywords(DEFAULT_VOICE_KEYWORDS)
            settings.setAiVoiceEnabled(true)
            settings.setAiVoiceRandomChance(0.35f)
            settings.setAiStickersEnabled(true)
            settings.setAiStickerChance(0.8f)
            Log.d(TAG, "Cleared all persona fields")
        }
    }

    /** 根据预设 id 创建新会话并应用预设配置 */
    fun startChatWithPreset(presetId: String) {
        val preset = presets.value.firstOrNull { it.id == presetId } ?: run {
            Log.w(TAG, "Preset not found: $presetId, fallback to default new session")
            createNewSession()
            return
        }
        applyPreset(preset)
        createNewSession(presetId = preset.id, sessionName = preset.name)
    }

    /**
     * 根据指定的 API 配置 ID 和舰娘人格配置 ID 创建新会话
     *
     * 与 [startChatWithPreset] 不同，本方法支持 API 配置与舰娘人格的**自由组合**：
     * - 用户可只选 API 配置（保持当前人格）
     * - 用户可只选舰娘人格（保持当前 API）
     * - 用户可两者都选（组合应用）
     * - 用户可都不选（同 [createNewSession] 默认行为）
     *
     * 实现要点：
     * 1. 同步解析最终配置（避免 StateFlow 异步更新导致会话快照取到旧值）
     * 2. 异步持久化配置到 DataStore（让全局设置也同步更新）
     * 3. 使用解析后的快照创建会话（保证会话级配置隔离立即生效）
     *
     * @param apiConfigId API 配置 ID，null 表示使用当前 API 配置
     * @param personaConfigId 舰娘人格配置 ID，null 表示使用当前舰娘人格
     * @param sessionName 会话名称，为空时自动生成
     */
    fun startChatWithApiAndPersona(
        apiConfigId: String?,
        personaConfigId: String?,
        sessionName: String = ""
    ) {
        val api = apiConfigId?.let { id -> apiConfigs.value.firstOrNull { it.id == id } }
        val persona = personaConfigId?.let { id -> personaConfigs.value.firstOrNull { it.id == id } }

        // 解析最终配置：优先使用选择项，回退到当前全局值
        val resolvedApiUrl = api?.apiUrl ?: apiUrl.value
        val resolvedApiKey = api?.apiKey ?: apiKey.value
        val resolvedModel = api?.model ?: selectedModel.value
        val resolvedSystemPrompt = persona?.systemPrompt ?: systemPrompt.value
        val resolvedJiuxinName = persona?.jiuxinName ?: jiuxinName.value
        val resolvedAvatarUrl = persona?.avatarUrl?.let { normalizeUrl(it) } ?: avatarUrl.value
        val resolvedVoiceShipName = persona?.voiceShipName ?: voiceShipName.value
        val resolvedVoiceShipAvatar = persona?.voiceShipAvatar?.let { normalizeUrl(it) } ?: voiceShipAvatar.value
        val resolvedVoiceEnabled = persona?.voiceEnabled ?: voiceEnabled.value
        val resolvedVoiceRandomChance = persona?.voiceRandomChance ?: voiceRandomChance.value
        val resolvedVoiceKeywords = persona?.voiceKeywords ?: voiceKeywords.value
        val resolvedStickersEnabled = persona?.stickersEnabled ?: stickersEnabled.value
        val resolvedStickerChance = persona?.stickerChance ?: stickerChance.value

        // 递增代际计数器，使在途的 sendMessage 请求返回后丢弃旧会话的回复
        _chatGeneration.incrementAndGet()
        viewModelScope.launch {
            // 1. 持久化选择的配置到全局设置（让全局状态与新建会话一致）
            if (api != null) {
                settings.setAiCustomBaseUrl(api.apiUrl)
                settings.setAiApiKey(api.apiKey)
                settings.setAiModel(api.model)
            }
            if (persona != null) {
                settings.setAiSystemPrompt(persona.systemPrompt)
                settings.setAiName(persona.jiuxinName)
                settings.setAiAvatarUrl(normalizeUrl(persona.avatarUrl))
                settings.setAiVoiceShipName(persona.voiceShipName)
                settings.setAiVoiceShipAvatar(normalizeUrl(persona.voiceShipAvatar))
                settings.setAiVoiceEnabled(persona.voiceEnabled)
                settings.setAiVoiceRandomChance(persona.voiceRandomChance)
                settings.setAiVoiceKeywords(persona.voiceKeywords)
                settings.setAiStickersEnabled(persona.stickersEnabled)
                settings.setAiStickerChance(persona.stickerChance)
            }

            // 2. 使用解析后的配置快照创建会话（保证会话级隔离立即生效）
            val effectiveName = sessionName.ifBlank {
                persona?.name?.ifBlank { resolvedJiuxinName.ifBlank { generateDefaultName() } }
                    ?: resolvedJiuxinName.ifBlank { generateDefaultName() }
            }
            val session = ChatSession(
                name = effectiveName,
                avatarUrl = resolvedAvatarUrl,
                jiuxinName = resolvedJiuxinName,
                apiUrl = resolvedApiUrl,
                apiKey = resolvedApiKey,
                model = resolvedModel,
                systemPrompt = resolvedSystemPrompt,
                voiceShipName = resolvedVoiceShipName,
                voiceShipAvatar = resolvedVoiceShipAvatar,
                voiceEnabled = resolvedVoiceEnabled,
                voiceRandomChance = resolvedVoiceRandomChance,
                voiceKeywords = resolvedVoiceKeywords,
                stickersEnabled = resolvedStickersEnabled,
                stickerChance = resolvedStickerChance
            )
            persistAndActivateSession(session)
            Log.d(TAG, "Started chat with api=${api?.name ?: "current"}, persona=${persona?.name ?: "current"}")
        }
    }

    /**
     * 切换到指定会话
     *
     * 多对话隔离核心：切换会话时自动加载该会话的配置快照（API、人格、语音等），
     * 确保不同舰娘的对话使用各自的配置，而非共用全局配置。
     * - 有关联预设：优先应用完整预设配置（预设可能已被更新）
     * - 无预设但有快照：应用会话创建时保存的完整配置快照
     * - 无任何快照：保持当前全局配置
     *
     * 竞态修复：先捕获旧会话 ID，使用它保存旧会话消息，
     * 避免子协程读取到已变更的 [_currentSessionId] 导致消息保存到错误会话。
     */
    fun switchToSession(sessionId: String) {
        if (_currentSessionId.value == sessionId) return
        // 递增代际计数器，使在途的 sendMessage 请求返回后丢弃旧会话的回复
        _chatGeneration.incrementAndGet()
        // 捕获旧会话 ID：确保 saveCurrentSessionMessages 保存到正确的会话
        val oldSessionId = _currentSessionId.value
        // 关键：在清空消息之前先捕获当前消息列表，否则 saveCurrentSessionMessages 会保存空列表
        val messagesToSave = _chatState.value.messages
        // 立即清空消息并显示加载态：避免异步切换期间闪现旧会话内容
        _chatState.update { it.copy(messages = emptyList(), error = null, isLoading = true, inputText = "") }
        viewModelScope.launch {
            // 先保存当前会话消息（使用捕获的旧会话 ID 和消息列表，避免竞态和数据丢失）
            // 不更新时间戳：切换会话不应改变会话列表顺序
            if (oldSessionId.isNotBlank() && messagesToSave.isNotEmpty()) {
                settings.setAiSessionMessages(oldSessionId, messagesToSave)
            }

            // 配置隔离：不再把会话快照回写到全局 StateFlow。
            // 聊天界面和 callApi 通过 currentSession StateFlow 读取会话快照，
            // 全局 StateFlow 保持独立，仅作为新建会话的默认值模板。

            // 先持久化新会话 ID 到 DataStore，再同步更新内存
            settings.setAiCurrentSessionId(sessionId)
            _currentSessionId.value = sessionId
            // 同步当前会话的语音关键词到 chatState（用于语音触发匹配）
            val targetSession = _sessions.value.firstOrNull { it.id == sessionId }
            if (targetSession != null) {
                val keywords = targetSession.voiceKeywords.split(";").filter { it.isNotBlank() }
                _chatState.update {
                    it.copy(
                        voiceKeywords = keywords,
                        voiceTagMappings = buildVoiceTagMappings(keywords),
                        error = null,
                        isLoading = false
                    )
                }
            } else {
                _chatState.update { it.copy(error = null, isLoading = false) }
            }
            Log.d(TAG, "Switched to session: $sessionId (config isolated, no global writeback)")
        }
    }

    /**
     * 更新当前会话的配置快照（会话级隔离）。
     *
     * 仅修改当前 ChatSession 的字段，不写入全局 StateFlow。
     * 聊天界面和舰娘设置界面通过 [currentSession] StateFlow 观察变更。
     *
     * @param updater 返回新的 ChatSession 的转换函数
     */
    fun updateCurrentSessionConfig(updater: (ChatSession) -> ChatSession) {
        val currentId = _currentSessionId.value
        if (currentId.isBlank()) return
        viewModelScope.launch {
            val updated = _sessions.value.map {
                if (it.id == currentId) updater(it) else it
            }
            settings.setAiChatSessions(updated)
            _sessions.value = updated
        }
    }

    /**
     * 应用 PersonaConfig 到当前会话（会话级隔离，不写全局）。
     *
     * 将人格配置的舰娘名/头像/提示词/语音/表情包字段写入当前会话快照，
     * 不影响全局 StateFlow 和其他会话。
     */
    fun applyPersonaConfigToCurrentSession(config: PersonaConfig) {
        updateCurrentSessionConfig { session ->
            session.copy(
                jiuxinName = config.jiuxinName.ifBlank { session.jiuxinName },
                avatarUrl = config.avatarUrl.ifBlank { session.avatarUrl },
                systemPrompt = config.systemPrompt,
                voiceShipName = config.voiceShipName,
                voiceShipAvatar = config.voiceShipAvatar,
                voiceEnabled = config.voiceEnabled,
                voiceRandomChance = config.voiceRandomChance,
                voiceKeywords = config.voiceKeywords,
                stickersEnabled = config.stickersEnabled,
                stickerChance = config.stickerChance
            )
        }
    }

    /**
     * 应用 API 配置到当前会话（会话级隔离，不写全局）。
     */
    fun applyApiConfigToCurrentSession(config: ApiConfig) {
        updateCurrentSessionConfig { session ->
            session.copy(
                apiUrl = config.apiUrl,
                apiKey = config.apiKey,
                model = config.model
            )
        }
    }

    /**
     * 清除当前会话的 API 配置，回退到全局默认值。
     */
    fun clearApiConfigForCurrentSession() {
        updateCurrentSessionConfig { session ->
            session.copy(apiUrl = "", apiKey = "", model = "")
        }
    }

    /**
     * 应用预设到当前会话（会话级隔离，不写全局）。
     *
     * 将完整预设配置写入当前会话快照，不影响全局 StateFlow。
     */
    fun applyPresetToCurrentSession(preset: JiuxinPreset) {
        updateCurrentSessionConfig { session ->
            session.copy(
                presetId = preset.id,
                jiuxinName = preset.jiuxinName,
                avatarUrl = preset.avatarUrl,
                apiUrl = preset.apiUrl,
                apiKey = preset.apiKey,
                model = preset.model,
                systemPrompt = preset.systemPrompt,
                voiceShipName = preset.voiceShipName,
                voiceShipAvatar = preset.voiceShipAvatar,
                voiceEnabled = preset.voiceEnabled,
                voiceRandomChance = preset.voiceRandomChance,
                voiceKeywords = preset.voiceKeywords,
                stickersEnabled = preset.stickersEnabled,
                stickerChance = preset.stickerChance
            )
        }
    }

    /** 删除指定会话 */
    fun deleteSession(sessionId: String) {
        viewModelScope.launch {
            // 递增代际计数器，使所有在途的 sendMessage 请求返回后能检测到状态变更并丢弃结果
            _chatGeneration.incrementAndGet()

            // 先捕获被删除会话的舰娘标识（用于自动创建新会话时继承）
            val deletedSession = _sessions.value.firstOrNull { it.id == sessionId }

            val currentList = _sessions.value.toMutableList()
            currentList.removeAll { it.id == sessionId }
            // 先持久化到 DataStore
            settings.setAiChatSessions(currentList)
            settings.deleteAiSessionMessages(sessionId)

            // 如果删除的是当前会话，切换到最新的；若无会话则自动创建新会话
            if (_currentSessionId.value == sessionId) {
                if (currentList.isNotEmpty()) {
                    // 先持久化新会话 ID，再同步更新内存，防止在途 API 回调将消息写入已删除的会话
                    settings.setAiCurrentSessionId(currentList.first().id)
                    _currentSessionId.value = currentList.first().id
                } else {
                    // 所有会话已删除：自动创建新空会话，继承当前舰娘标识和配置，保证聊天依旧存在
                    // 继承被删除会话的舰娘标识（presetId/avatarUrl/jiuxinName）和配置快照，
                    // 使新会话与原舰娘保持同一去重 key，用户可继续与同一舰娘对话。
                    val newSession = ChatSession(
                        name = generateDefaultName(),
                        presetId = deletedSession?.presetId ?: "",
                        avatarUrl = deletedSession?.avatarUrl ?: avatarUrl.value,
                        jiuxinName = deletedSession?.jiuxinName ?: jiuxinName.value,
                        apiUrl = deletedSession?.apiUrl ?: apiUrl.value,
                        apiKey = deletedSession?.apiKey ?: apiKey.value,
                        model = deletedSession?.model ?: selectedModel.value,
                        systemPrompt = deletedSession?.systemPrompt ?: systemPrompt.value,
                        voiceShipName = deletedSession?.voiceShipName ?: voiceShipName.value,
                        voiceShipAvatar = deletedSession?.voiceShipAvatar ?: voiceShipAvatar.value,
                        voiceEnabled = deletedSession?.voiceEnabled ?: voiceEnabled.value,
                        voiceRandomChance = deletedSession?.voiceRandomChance ?: voiceRandomChance.value,
                        voiceKeywords = deletedSession?.voiceKeywords ?: voiceKeywords.value,
                        stickersEnabled = deletedSession?.stickersEnabled ?: stickersEnabled.value,
                        stickerChance = deletedSession?.stickerChance ?: stickerChance.value
                    )
                    settings.setAiChatSessions(listOf(newSession))
                    settings.setAiSessionMessages(newSession.id, emptyList())
                    settings.setAiCurrentSessionId(newSession.id)
                    currentList.clear()
                    currentList.add(newSession)
                    _currentSessionId.value = newSession.id
                    _chatState.update { it.copy(messages = emptyList(), error = null, isLoading = false) }
                }
            }
            // 同步更新会话列表内存状态
            _sessions.value = currentList
            Log.d(TAG, "Deleted session: $sessionId, remaining: ${currentList.size}")
        }
    }

    /**
     * 批量删除同一舰娘的所有会话
     *
     * 用于会话列表界面（ConversationListScreen）的长按删除：
     * 用户长按某个舰娘的会话条目时，删除该舰娘的所有历史对话。
     * 去重标识与 [uniqueConversations] 一同：presetId → avatarUrl → jiuxinName → "default"。
     *
     * @param sessionId 触发删除的会话 ID（用于确定舰娘标识）
     */
    fun deleteSessionsByShip(sessionId: String) {
        viewModelScope.launch {
            _chatGeneration.incrementAndGet()

            val triggerSession = _sessions.value.firstOrNull { it.id == sessionId } ?: return@launch
            val shipKey = computeShipKey(triggerSession)

            // 找到同一舰娘的所有会话
            val idsToDelete = _sessions.value.filter { computeShipKey(it) == shipKey }
                .map { it.id }.toSet()
            val currentList = _sessions.value.filterNot { it.id in idsToDelete }

            // 先持久化到 DataStore
            settings.setAiChatSessions(currentList)
            idsToDelete.forEach { settings.deleteAiSessionMessages(it) }

            // 如果当前会话被删除，切换到剩余最新会话；无剩余会话则清空当前会话 ID
            // 注意：不自动创建新会话——会话列表界面允许空状态，用户可通过"+"按钮新建
            if (_currentSessionId.value in idsToDelete) {
                if (currentList.isNotEmpty()) {
                    settings.setAiCurrentSessionId(currentList.first().id)
                    _currentSessionId.value = currentList.first().id
                } else {
                    // 所有会话已删除：清空当前会话 ID，会话列表显示空状态
                    settings.setAiCurrentSessionId("")
                    _currentSessionId.value = ""
                    _chatState.update { it.copy(messages = emptyList(), error = null, isLoading = false, inputText = "") }
                }
            }
            _sessions.value = currentList
            Log.d(TAG, "Deleted ${idsToDelete.size} sessions for ship key: $shipKey, remaining: ${currentList.size}")
        }
    }

    /**
     * 重命名会话（仅修改对话名称 [ChatSession.name]）
     *
     * **名称职责隔离：**
     * - [ChatSession.name]：对话名称（历史对话面板显示），可由用户重命名修改
     * - [ChatSession.jiuxinName]：啾信名称/聊天名称（会话列表显示），来自配置，不被重命名修改
     *
     * 重命名只更新 [ChatSession.name]，确保修改对话名称不会影响会话列表的聊天名称。
     */
    fun renameSession(sessionId: String, newName: String) {
        viewModelScope.launch {
            val trimmedName = newName.trim()
            val updated = _sessions.value.map {
                if (it.id == sessionId) {
                    it.copy(name = trimmedName)
                } else it
            }
            // 先持久化到 DataStore，再同步更新内存状态，确保 UI 立即刷新
            settings.setAiChatSessions(updated)
            _sessions.value = updated
            Log.d(TAG, "Renamed session $sessionId to: $trimmedName")
        }
    }

    /**
     * 保存用户拖动排序后的会话列表顺序
     *
     * 用户长按拖动会话卡片后调用，将新的 id 顺序持久化到 DataStore。
     * [uniqueConversations] 会自动按此顺序显示。
     *
     * @param orderedSessionIds 排序后的会话 ID 列表（去重后的列表顺序）
     */
    fun reorderConversations(orderedSessionIds: List<String>) {
        viewModelScope.launch {
            settings.setAiSessionOrder(orderedSessionIds)
            Log.d(TAG, "Saved session order: ${orderedSessionIds.size} items")
        }
    }

    // ── 连接测试 ──
    fun testConnection() {
        viewModelScope.launch {
            val key = settings.aiApiKey.first()
            val baseUrl = settings.aiCustomBaseUrl.first().trim()
            val modelStr = settings.aiModel.first()
            _connectionTestState.value = performConnectionTest(baseUrl, key, modelStr)
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
            performFetchModels(baseUrl, key)
        }
    }

    /**
     * 针对当前会话快照测试连接（会话级隔离）。
     *
     * 读取当前会话的 apiUrl/apiKey/model，回退到全局默认值，
     * 结果写入共享的 [connectionTestState]（同一时刻仅一个界面可见，无冲突）。
     */
    fun testConnectionForCurrentSession() {
        val session = _sessions.value.firstOrNull { it.id == _currentSessionId.value }
        if (session == null) {
            _connectionTestState.value = ConnectionTestState.Error("当前无活跃会话")
            return
        }
        viewModelScope.launch {
            val key = session.apiKey.ifBlank { settings.aiApiKey.first() }
            val baseUrl = session.apiUrl.ifBlank { settings.aiCustomBaseUrl.first() }.trim()
            val modelStr = session.model.ifBlank { settings.aiModel.first() }
            _connectionTestState.value = performConnectionTest(baseUrl, key, modelStr)
        }
    }

    /**
     * 针对当前会话快照拉取模型列表（会话级隔离）。
     *
     * 读取当前会话的 apiUrl/apiKey，回退到全局默认值，
     * 结果写入共享的 [availableModels]（同一时刻仅一个界面可见，无冲突）。
     */
    fun fetchModelsForCurrentSession() {
        val session = _sessions.value.firstOrNull { it.id == _currentSessionId.value }
        if (session == null) return
        viewModelScope.launch {
            val key = session.apiKey.ifBlank { settings.aiApiKey.first() }
            val baseUrl = session.apiUrl.ifBlank { settings.aiCustomBaseUrl.first() }.trim().trimEnd('/')
            performFetchModels(baseUrl, key)
        }
    }

    /** 连接测试通用实现 — 消除全局/会话级重复代码 */
    private suspend fun performConnectionTest(baseUrl: String, key: String, modelStr: String): ConnectionTestState {
        _connectionTestState.value = ConnectionTestState.Testing
        if (baseUrl.isBlank()) return ConnectionTestState.Error("请先配置 API Base URL")
        if (key.isBlank()) return ConnectionTestState.Error("请先配置 API Key")

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

        val request = buildJsonPostRequest(url, key, requestBody)

        Log.d(TAG, "Testing connection to: $url, model=$model")

        return withContext(Dispatchers.IO) {
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
    }

    /** 模型列表拉取通用实现 — 消除全局/会话级重复代码 */
    private suspend fun performFetchModels(baseUrl: String, key: String) {
        if (key.isBlank() || baseUrl.isBlank()) return
        val url = "$baseUrl/models"
        val request = buildAuthGetRequest(url, key)

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

    /**
     * 表情包名称索引（O(1) 查找）。
     *
     * [analyzeEmotionTendency] 中多处 `stickerLibrary.find { it.name == "xxx" }` 是 O(n) 线性扫描，
     * 每次情绪分析最坏需要扫描 100+ 项。此 Map 一次性构建索引，将每次查找降为 O(1)。
     */
    private val stickerByName: Map<String, StickerResource> by lazy {
        stickerLibrary.associateBy { it.name }
    }

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
        // 通过 stickerByName 索引 O(1) 查找，替代原先 stickerLibrary.find { it.name == ... } 的 O(n) 扫描
        return when {
            // ── 积极情绪 ──
            hasAny(text, "好耶", "太棒了", "太好了", "好开心", "嘻嘻", "开心") -> stickerByName["哇哈哈"]
            hasAny(text, "嘿嘿", "坏笑") -> stickerByName["欸嘿嘿"]
            hasAny(text, "喜欢你", "最喜欢", "好喜欢", "爱") -> stickerByName["爱情表现"]
            hasAny(text, "谢谢", "感谢") -> stickerByName["点赞"]
            hasAny(text, "加油", "你可以的", "一起努力") -> stickerByName["打call"]
            hasAny(text, "可爱", "萌") -> stickerByName["超可爱"]
            hasAny(text, "欢迎回来", "欢迎") -> stickerByName["欢迎回来"]

            // ── 消极情绪 ──
            hasAny(text, "好难过", "好伤心", "好委屈", "哭") -> stickerByName["大哭"]
            hasAny(text, "好生气", "气死了", "好气", "怒") -> stickerByName["极限愤怒"]
            hasAny(text, "好累", "好困", "想睡觉", "困") -> stickerByName["ZZZ"]
            hasAny(text, "完蛋", "糟了", "哦豁") -> stickerByName["哦豁，完蛋"]
            hasAny(text, "失落", "沮丧", "低落") -> stickerByName["失落"]

            // ── 傲娇 ──
            hasAny(text, "才不是", "哼，才", "笨蛋指挥官", "笨蛋") -> stickerByName["你是笨蛋吗？"]

            // ── 亲密 ──
            hasAny(text, "贴贴", "抱抱你", "摸摸头", "抱") -> stickerByName["抱抱"]
            hasAny(text, "害羞", "脸红", "不好意思") -> stickerByName["脸红"]

            // ── 惊讶 ──
            hasAny(text, "诶", "不会吧", "震惊", "？！") -> stickerByName["？！？！"]

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

        val userMessage = ChatMessage(
            id = UUID.randomUUID().toString(),
            type = ChatMessageType.USER.name,
            content = text,
            timestamp = System.currentTimeMillis()
        )

        _chatState.update { it.copy(inputText = "", isLoading = true, error = null) }

        // 语音触发 + API 调用（含会话确保和消息添加，全部在协程内顺序执行避免竞态）
        viewModelScope.launch {
            // 确保有当前会话，没有则同步创建（保存完整配置快照，避免竞态条件导致消息丢失）
            val sessionId = ensureCurrentSession()
            // 捕获当前代际，用于 API 返回后检测聊天状态是否已变更（清空/删除/切换）
            val generation = _chatGeneration.get()

            // 添加用户消息（确保会话已创建后再添加，避免消息丢失）
            addMessage(userMessage)

            // 配置隔离：从当前会话快照读取语音配置，回退到全局默认值
            val currentSessionSnapshot = _sessions.value.firstOrNull { it.id == sessionId }
            var selectedShip = currentSessionSnapshot?.voiceShipName?.ifBlank { null } ?: settings.aiVoiceShipName.first()
            var shipAvatar = currentSessionSnapshot?.voiceShipAvatar?.ifBlank { null } ?: settings.aiVoiceShipAvatar.first()
            val voiceEnabledSetting = currentSessionSnapshot?.voiceEnabled ?: settings.aiVoiceEnabled.first()
            val chance = currentSessionSnapshot?.voiceRandomChance ?: settings.aiVoiceRandomChance.first()

            // 回退：如果 DataStore 中头像为空，从舰娘数据库查找
            if (selectedShip.isNotBlank() && shipAvatar.isBlank()) {
                shipAvatar = shipDao.getShipByName(selectedShip, ArchiveType.DOCK.name)?.avatarUrl?.let { normalizeUrl(it) } ?: ""
                Log.d(TAG, "Avatar fallback from DB: $shipAvatar")
            }

            if (voiceEnabledSetting && selectedShip.isNotBlank()) {
                // 从会话快照读取 voiceKeywords 并重建 tagMappings（会话级隔离）
                // 不能直接用 _chatState.value.voiceTagMappings，因为它只在 switchToSession 时同步，
                // 用户在 JiuxinShipConfigScreen 修改关键词后 chatState 不会自动更新
                val sessionKeywords = currentSessionSnapshot?.voiceKeywords
                    ?.split(";")?.filter { it.isNotBlank() }
                    ?: settings.aiVoiceKeywords.first().split(";").filter { it.isNotBlank() }
                val tagMappings = buildVoiceTagMappings(sessionKeywords)

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
                // 检查会话是否已被清除/删除/切换（代际计数器 + 会话 ID 双重校验）
                if (_chatGeneration.get() != generation || _currentSessionId.value != sessionId) {
                    Log.d(TAG, "Chat state changed during API call (gen=%d→%d, session=%s), discarding response"
                        .format(generation, _chatGeneration.get(), _currentSessionId.value))
                    return@launch
                }
                val aiMessage = ChatMessage(
                    id = UUID.randomUUID().toString(),
                    type = ChatMessageType.AI.name,
                    content = aiResponse,
                    timestamp = System.currentTimeMillis()
                )
                addMessage(aiMessage)

                // 表情包逻辑：开关开启 + 概率命中 + 匹配到表情包（从会话快照读取配置）
                val stickersEnabledSetting = currentSessionSnapshot?.stickersEnabled ?: settings.aiStickersEnabled.first()
                if (stickersEnabledSetting) {
                    val stickerChance = currentSessionSnapshot?.stickerChance ?: settings.aiStickerChance.first()
                    if (kotlin.random.Random.nextFloat() < stickerChance) {
                        val sticker = findBestSticker(aiResponse)
                        if (sticker != null) {
                            // 稍微延迟发送表情包，模拟输入感
                            kotlinx.coroutines.delay(300)
                            // 三重校验：代际 + 会话 ID + AI 消息仍存在
                            // 防止清空/删除/切换后表情包重新出现，以及用户在 delay 期间删除 AI 消息后表情包复活
                            if (_chatGeneration.get() != generation || _currentSessionId.value != sessionId) {
                                Log.d(TAG, "Chat state changed before sticker, discarding")
                                return@launch
                            }
                            if (!_chatState.value.messages.any { it.id == aiMessage.id }) {
                                Log.d(TAG, "AI message was deleted during sticker delay, skipping sticker")
                                return@launch
                            }
                            val stickerShipName = selectedShip.ifBlank { currentSessionSnapshot?.jiuxinName?.ifBlank { settings.aiName.first() } ?: settings.aiName.first() }
                            addStickerMessage(sticker, stickerShipName, shipAvatar)
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

    /**
     * 确保有当前会话，没有则创建并保存完整配置快照。
     *
     * 关键点：先持久化到 DataStore（会话列表、当前会话 ID、空消息列表），
     * 再同步更新内存中的 [_sessions] 和 [_currentSessionId]。
     * 这样当消息加载 collector 被 [_currentSessionId] 变化触发时，
     * DataStore 中已有正确的空消息列表，不会因竞态覆盖后续 [addMessage] 添加的消息。
     *
     * @return 当前会话 ID
     */
    private suspend fun ensureCurrentSession(): String {
        val current = _currentSessionId.value
        if (current.isNotBlank()) return current

        val session = ChatSession(
            name = generateDefaultName(),
            avatarUrl = avatarUrl.value,
            jiuxinName = jiuxinName.value,
            apiUrl = apiUrl.value,
            apiKey = apiKey.value,
            model = selectedModel.value,
            systemPrompt = systemPrompt.value,
            voiceShipName = voiceShipName.value,
            voiceShipAvatar = voiceShipAvatar.value,
            voiceEnabled = voiceEnabled.value,
            voiceRandomChance = voiceRandomChance.value,
            voiceKeywords = voiceKeywords.value,
            stickersEnabled = stickersEnabled.value,
            stickerChance = stickerChance.value
        )
        // 复用统一的会话激活逻辑（包含 MAX_SESSIONS 裁剪，修复原先 ensureCurrentSession 不裁剪的 bug）
        persistAndActivateSession(session)
        Log.d(TAG, "Auto-created session on sendMessage: ${session.id} - ${session.name}")
        return session.id
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
                val defaultPool = pool.filter { it.skinName in DEFAULT_SKIN_NAMES || it.skinName.isEmpty() }
                // 80% 概率触发默认/通常语音，20% 概率触发已拥有的其他皮肤语音
                return if (defaultPool.isNotEmpty() && kotlin.random.Random.nextFloat() < 0.8f) {
                    defaultPool.random()
                } else {
                    pool.random()
                }
            }
        }

        // 3. 保底：优先从默认皮肤中随机选一条
        val defaultVoice = voices.filter { it.skinName in DEFAULT_SKIN_NAMES }.randomOrNull()
        return defaultVoice ?: voices.random()
    }




    private fun triggerVoiceRandom(shipName: String, shipAvatar: String) {
        viewModelScope.launch {
            try {
                val (voices, _, _) = getVoicesUseCase(shipName)
                if (voices.isNotEmpty()) {
                    val defaultVoices = voices.filter { it.skinName in DEFAULT_SKIN_NAMES }
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
        // 递增代际计数器，使在途的 sendMessage 请求返回后丢弃 AI 回复和表情包
        _chatGeneration.incrementAndGet()
        // 捕获当前会话 ID：避免异步清空时 _currentSessionId 已被切换到新会话
        val sessionId = _currentSessionId.value
        // 同步清空内存消息，确保 UI 立即更新
        _chatState.update { it.copy(messages = emptyList()) }
        viewModelScope.launch {
            if (sessionId.isNotBlank()) {
                settings.setAiSessionMessages(sessionId, emptyList())
            }
        }
    }

    fun clearError() { _chatState.update { it.copy(error = null) } }

    /**
     * 删除单条消息（包括文字、语音、表情包/绘画等所有类型）。
     *
     * 一次性原子删除：同步从内存 [_chatState] 和持久化 [settings] 中移除目标消息，
     * 确保删除操作即时生效且不会因异步回调导致消息复活。
     *
     * 竞态修复：捕获当前 sessionId，避免删除后快速切换会话导致消息保存到错误会话。
     * 这解决了"删除表情包后切换对话再切回，表情包复活"的问题。
     *
     * @param messageId 要删除的消息 ID
     */
    fun deleteMessage(messageId: String) {
        // 捕获当前会话 ID：避免异步保存时 _currentSessionId 已被切换到新会话
        val sessionId = _currentSessionId.value
        // 同步从内存中移除，确保 UI 立即更新
        _chatState.update { state ->
            state.copy(messages = state.messages.filterNot { it.id == messageId })
        }
        // 异步持久化到 DataStore（使用捕获的 sessionId）
        viewModelScope.launch {
            if (sessionId.isNotBlank()) {
                val messages = _chatState.value.messages
                settings.setAiSessionMessages(sessionId, messages)
                Log.d(TAG, "Deleted message: $messageId from session: $sessionId")
            }
        }
    }

    /**
     * 编辑用户消息并重新发送。
     *
     * 删除从 [messageId] 开始的所有后续消息（包括该用户消息及其后的 AI 回复/语音/表情包），
     * 然后以 [newContent] 作为新的用户消息发送，触发新的 AI 回复。
     */
    fun editAndResendMessage(messageId: String, newContent: String) {
        if (newContent.isBlank()) return
        val sessionId = _currentSessionId.value
        // 找到目标消息的索引，截断该位置及之后的所有消息
        val messages = _chatState.value.messages
        val targetIndex = messages.indexOfFirst { it.id == messageId }
        if (targetIndex < 0) return

        // 代际+1：使正在进行的 API 调用失效（如果有的话）
        _chatGeneration.incrementAndGet()
        // 截断消息列表
        val truncated = messages.subList(0, targetIndex)
        _chatState.update { it.copy(messages = truncated, isLoading = false, error = null) }

        // 持久化截断后的消息
        viewModelScope.launch {
            if (sessionId.isNotBlank()) {
                settings.setAiSessionMessages(sessionId, truncated)
                Log.d(TAG, "Truncated messages from: $messageId, session: $sessionId")
            }
        }

        // 发送新消息（复用 sendMessage 逻辑）
        sendMessage(newContent)
    }

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

    /**
     * 保存当前会话消息到 DataStore
     *
     * 竞态修复：接受 [sessionId] 参数，而非从 [_currentSessionId] 读取。
     * 调用方在切换会话前传入旧会话 ID，避免子协程读到已变更的 [_currentSessionId]
     * 导致消息保存到错误会话（这是"删除后切换再切回，消息复活"的根因）。
     */
    private fun saveCurrentSessionMessages(
        sessionId: String = _currentSessionId.value,
        updateTimestamp: Boolean = true,
        messages: List<ChatMessage>? = null
    ) {
        viewModelScope.launch {
            if (sessionId.isNotBlank()) {
                val msgs = messages ?: _chatState.value.messages
                settings.setAiSessionMessages(sessionId, msgs)
                if (updateTimestamp) {
                    val updated = _sessions.value.map {
                        if (it.id == sessionId) it.copy(updatedAt = System.currentTimeMillis()) else it
                    }
                    settings.setAiChatSessions(updated)
                }
            }
        }
    }

    private suspend fun callApi(userMessage: String): String {
        // 配置隔离：从当前会话快照读取 API 配置，回退到全局默认值
        val session = _sessions.value.firstOrNull { it.id == _currentSessionId.value }
        val key = session?.apiKey?.ifBlank { settings.aiApiKey.first() } ?: settings.aiApiKey.first()
        if (key.isBlank()) throw Exception("未配置 API 密钥，请先在啾信配置中设置")

        val baseUrl = (session?.apiUrl?.ifBlank { settings.aiCustomBaseUrl.first() } ?: settings.aiCustomBaseUrl.first()).trim()
        if (baseUrl.isBlank()) throw Exception("未配置 API URL，请先在啾信配置中设置")

        val url = buildFullApiUrl(baseUrl)
        val prompt = session?.systemPrompt?.ifBlank { settings.aiSystemPrompt.first() } ?: settings.aiSystemPrompt.first()
        val modelStr = session?.model?.ifBlank { settings.aiModel.first() } ?: settings.aiModel.first()
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

        val request = buildJsonPostRequest(url, key, requestBody)

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
                if (!content.isNullOrBlank()) {
                    // trim 消除 API 返回内容中的前导/尾部空行和空白字符
                    // 解决 AI 回复消息首尾出现错误空行的问题
                    return content.trim()
                }
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

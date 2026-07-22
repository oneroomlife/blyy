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
import com.azurlane.blyy.data.model.GroupMember
import com.azurlane.blyy.data.model.JiuxinChatUiState
import com.azurlane.blyy.data.model.JiuxinPreset
import com.azurlane.blyy.data.model.ApiConfig
import com.azurlane.blyy.data.model.MessageStatus
import com.azurlane.blyy.data.model.PersonaConfig
import com.azurlane.blyy.data.model.SessionType
import com.azurlane.blyy.data.model.Ship
import com.azurlane.blyy.data.model.TypingMember
import com.azurlane.blyy.data.model.VoiceLanguage
import com.azurlane.blyy.data.model.VoiceTagMapping
import com.azurlane.blyy.data.repository.ChatCompletionRequest
import com.azurlane.blyy.data.repository.ChatRoleMessage
import com.azurlane.blyy.data.repository.JiuxinApiRepository
import com.azurlane.blyy.data.repository.JiuxinApiResult
import com.azurlane.blyy.data.repository.ShipRepository
import com.azurlane.blyy.domain.GetVoicesUseCase
import com.azurlane.blyy.data.model.StickerResource
import com.azurlane.blyy.util.LocalAvatarResolver
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
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

/**
 * 模型列表拉取状态机
 *
 * 区分"未拉取 / 拉取中 / 拉取成功 / 拉取失败 / 成功但为空"五种状态，
 * 替代原先只能表达 List<String> 的粗放设计，UI 可据此展示加载动画与具体错误。
 */
sealed class ModelListState {
    /** 初始空闲：尚未发起拉取 */
    object Idle : ModelListState()
    /** 拉取中：正在请求 API */
    object Loading : ModelListState()
    /** 拉取成功：[models] 为模型 ID 列表（已排序去重） */
    data class Success(val models: List<String>) : ModelListState()
    /** 拉取成功但列表为空：API 返回了合法响应但没有可用模型 */
    object Empty : ModelListState()
    /** 拉取失败：[message] 为对用户友好的错误原因 */
    data class Error(val message: String) : ModelListState()
}

@HiltViewModel
class JiuxinViewModel @Inject constructor(
    private val settings: PlayerSettingsDataStore,
    private val shipRepository: ShipRepository,
    private val shipDao: ShipDao,
    private val getVoicesUseCase: GetVoicesUseCase,
    private val client: OkHttpClient,
    private val apiRepository: JiuxinApiRepository,
    @ApplicationContext private val context: Context
) : ViewModel() {

    companion object {
        private const val TAG = "JiuxinViewModel"
        private const val DEFAULT_MODEL = "gpt-4o-mini"
        private const val MAX_CHAT_HISTORY = 200
        private const val MAX_SESSIONS = 50
        /** 群聊互回轮触发概率（每次用户消息后，有 85% 概率触发成员间互回） */
        private const val REACTION_PROBABILITY = 0.85f
        /** 群聊互回轮中，AI 输出该标记表示主动放弃回应 */
        private const val SKIP_TOKEN = "[SKIP]"
        /**
         * 群聊单次用户消息触发的最大互回轮数上限（防止无限循环）。
         * 实际互回轮数 = min(群成员数, MAX_REACTION_ROUNDS)，确保所有成员都有机会参与互回，
         * 同时避免大群消息刷屏。3 人群聊时最多 3 轮互回，每位成员都有机会回应他人。
         */
        private const val MAX_REACTION_ROUNDS = 3
        /** 群聊上下文消息历史最大条数（硬上限） */
        private const val MAX_GROUP_CONTEXT_MESSAGES = 30
        /** 群聊上下文 token 估算上限（1 token ≈ 2-3 个中文字符，按保守 2 计算）
         *  预留 model 上下文窗口（通常 4K-8K）给 system prompt + 输出 */
        private const val MAX_GROUP_CONTEXT_CHARS = 6000

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

    private val _modelListState = MutableStateFlow<ModelListState>(ModelListState.Idle)
    val modelListState: StateFlow<ModelListState> = _modelListState.asStateFlow()

    /**
     * 向后兼容：从 [modelListState] 派生的纯模型列表。
     *
     * 仅在 [ModelListState.Success] 时返回非空列表，其它状态返回空列表。
     * 新代码应直接观察 [modelListState] 以获取加载/错误状态。
     */
    val availableModels: StateFlow<List<String>> = _modelListState
        .map { (it as? ModelListState.Success)?.models ?: emptyList() }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

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
     *
     * 使用 [SharingStarted.Eagerly] 而非 WhileSubscribed(5000) 的关键原因：
     * WhileSubscribed 在最后一个订阅者取消后 5 秒停止收集上游 combine，
     * 此时 StateFlow.value 会保留旧会话（如 A）的缓存值。
     * 当 switchToSession(B) 同步设置 _currentSessionId=B 时，combine 已停止收集，
     * 无法感知此变化，currentSession.value 仍是 A。
     * ChatScreen 重新订阅时会先收到 A 的缓存值（显示上一个舰娘），
     * 然后 combine 重新收集才派发 B —— 这就是"跳转到上一个舰娘"的根因。
     * Eagerly 让 combine 始终保持收集，_currentSessionId 的变化能立即反映到 currentSession.value。
     */
    val currentSession: StateFlow<ChatSession?> = combine(_sessions, _currentSessionId) { sessions, id ->
        sessions.firstOrNull { it.id == id }
    }.stateIn(viewModelScope, SharingStarted.Eagerly, null)

    /**
     * 聊天状态代际计数器：每次清空/删除/切换会话时递增。
     * 用于在 [sendMessage] 的异步 API 调用返回后，检测聊天状态是否已变更，
     * 若已变更则丢弃过期的 AI 回复和表情包，避免已删除/已清空的内容重新出现。
     */
    private val _chatGeneration = java.util.concurrent.atomic.AtomicInteger(0)

    /**
     * 会话消息持久化锁：序列化所有 DataStore 写入，避免并发 saveCurrentSessionMessages
     * 各自捕获不同快照导致后执行者覆盖先执行者造成消息丢失。
     *
     * 场景：群聊第一轮并行 launch，成员 A、B 几乎同时 addMessage → 同时触发 save。
     * A 捕获 [m1, mA]，B 捕获 [m1, mA, mB]，若 A 后执行会以 [m1, mA] 覆盖 [m1, mA, mB]，
     * 消息 mB 永久丢失。引入此锁后在锁内重新读取最新快照即可避免。
     *
     * 注意：锁内禁止执行任何会回调 addMessage 的操作（防止死锁）。
     */
    private val saveMutex = Mutex()

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
     *
     * 使用 [SharingStarted.Eagerly] 与 [currentSession] 保持一致：避免 ChatScreen 重新订阅时
     * 收到旧会话的缓存值导致历史面板显示错误舰娘的对话。
     */
    val currentShipSessions: StateFlow<List<ChatSession>> = combine(_sessions, _currentSessionId) { sessions, currentId ->
        val currentSession = sessions.firstOrNull { it.id == currentId } ?: return@combine emptyList()
        val shipKey = computeShipKey(currentSession)
        sessions.filter { computeShipKey(it) == shipKey }
            .sortedByDescending { it.updatedAt }
    }.stateIn(viewModelScope, SharingStarted.Eagerly, emptyList())

    /**
     * 计算舰娘去重 key（统一隔离标识）。
     *
     * 优先级（多级回退）：
     * 0. 群聊会话 → 优先按 [ChatSession.groupId] 分组（稳定标识，重命名/改成员不变）；
     *    groupId 为空（旧数据兼容）时回退到群名 + 成员哈希
     * 1. presetId 非空 → 按预设 ID 分组
     * 2. avatarUrl 非空 → 按头像 URL 分组（同一头像视为同一舰娘）
     * 3. jiuxinName 非空且非默认格式（不以"对话-"开头）→ 按舰娘名分组
     * 4. 以上均无 → 统一归入 "default" 分组
     *
     * P2 修复：群聊 key 改用 groupId（稳定标识），不再包含 session.name。
     * 原实现使用 "group:${session.name}|$memberHash" 作为 key，
     * 导致群聊重命名（renameGroupSession）后 key 变化，
     * 历史面板聚合断裂（重命名后的会话不再显示在同一群聊的历史列表中）。
     */
    private fun computeShipKey(session: ChatSession): String = when {
        session.isGroup -> {
            if (session.groupId.isNotBlank()) {
                "group:${session.groupId}"
            } else {
                // 兼容旧数据：groupId 为空时回退到原逻辑
                val memberHash = session.groupMembers
                    .map { it.personaId.ifBlank { it.jiuxinName } }
                    .sorted()
                    .joinToString(",")
                "group:${session.name}|$memberHash"
            }
        }
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
        // 加载会话列表
        // 持续监听 DataStore：会话列表可能在其他界面（如设置页）被修改，需要同步
        viewModelScope.launch {
            settings.aiChatSessions.collect { sessionList ->
                _sessions.value = sessionList
            }
        }
        // 加载当前会话 ID：仅在初始化时从 DataStore 读取一次，用于恢复上次的会话
        // 之后内存 [_currentSessionId] 是单一真相源，[switchToSession] 等方法同步更新内存，
        // 异步持久化到 DataStore。持续监听 DataStore 会导致竞态：
        //   switchToSession 同步设置 _currentSessionId = B
        //   → 异步 settings.setAiCurrentSessionId(B) 未完成
        //   → init collector 派发旧值 A → 覆盖 _currentSessionId 回 A
        //   → ChatScreen 显示旧会话 A
        // 因此这里只用 first() 读取一次初始值，后续由内存状态驱动
        viewModelScope.launch {
            val initialSessionId = settings.aiCurrentSessionId.first()
            // 仅在内存值为空时初始化，避免覆盖已通过其他路径设置的值
            if (_currentSessionId.value.isBlank() && initialSessionId.isNotBlank()) {
                _currentSessionId.value = initialSessionId
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
                // 仅当消息列表实际变化时更新状态并关闭 isLoading
                // 避免 sendMessage 中的 addMessage → saveCurrentSessionMessages → DataStore 派发
                // 触发 collector 关闭 isLoading，导致 TypingIndicator 动画提前消失
                //
                // 场景分析：
                // 1. switchToSession 切换到有消息的会话：state.messages=[]（已清空）vs DataStore=[m1,m2]
                //    → 不相等 → 关闭 isLoading ✅
                // 2. switchToSession 切换到空会话：state.messages=[] vs DataStore=[]
                //    → 相等但空 → 关闭 isLoading ✅（空会话无需 loading）
                // 3. sendMessage 的 addMessage(userMessage)：state.messages 已含 userMessage
                //    vs DataStore 派发相同列表 → 相等且非空 → 保留 isLoading ✅（TypingIndicator 继续）
                // 4. API 返回 addMessage(aiMessage) + finishLoading：finishLoading 已关闭 isLoading
                //    DataStore 随后派发相同列表 → 相等 → 保留当前 false ✅
                _chatState.update { state ->
                    if (state.messages == messages && messages.isNotEmpty()) {
                        // 消息未变化（DataStore 重新派发相同数据，如 addMessage 持久化回写）：
                        // 保留当前 isLoading 状态，不干扰 sendMessage 的 TypingIndicator
                        state
                    } else {
                        // 消息变化（会话切换加载）或空会话：更新 messages 并关闭 isLoading
                        // 同时清空 typingMembers，防止上一会话的群聊打字指示器残留显示
                        state.copy(messages = messages, isLoading = false, typingMembers = emptyList())
                    }
                }
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
     * 保存会话级聊天背景 URL
     *
     * 与全局 [saveChatBackgroundUrl] 配合实现多级背景：
     * - 会话级 [backgroundUrl] 非空 → 仅当前会话使用该背景
     * - 会话级为空 → 回退到全局 [aiChatBackgroundUrl]
     * - 全局也为空 → 使用默认纯色背景
     *
     * @param sessionId 目标会话 ID
     * @param url 背景图片 URL（file:// 路径），空字符串表示清除会话级背景
     */
    fun saveSessionBackgroundUrl(sessionId: String, url: String) {
        viewModelScope.launch(NonCancellable) {
            val updated = _sessions.value.map { session ->
                if (session.id == sessionId) session.copy(backgroundUrl = url) else session
            }
            _sessions.value = updated
            settings.setAiChatSessions(updated)
            Log.d(TAG, "Saved session background: sessionId=$sessionId, url=$url")
        }
    }

    /**
     * 清除所有会话级背景（统一恢复到全局背景或默认背景）。
     */
    fun clearAllSessionBackgrounds() {
        viewModelScope.launch(NonCancellable) {
            val updated = _sessions.value.map { it.copy(backgroundUrl = "") }
            _sessions.value = updated
            settings.setAiChatSessions(updated)
            Log.d(TAG, "Cleared all session backgrounds")
        }
    }

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
        _chatState.update { it.copy(messages = emptyList(), error = null, isLoading = false, typingMembers = emptyList()) }
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

        // 群聊会话：复制完整群聊配置（含成员列表）创建新群聊
        if (currentSession != null && currentSession.isGroup) {
            _chatGeneration.incrementAndGet()
            viewModelScope.launch {
                val session = ChatSession(
                    name = currentSession.name,
                    jiuxinName = currentSession.jiuxinName,
                    apiUrl = currentSession.apiUrl,
                    apiKey = currentSession.apiKey,
                    model = currentSession.model,
                    systemPrompt = currentSession.systemPrompt,
                    sessionType = SessionType.GROUP.name,
                    groupMembers = currentSession.groupMembers,
                    // 继承稳定群聊标识：新建的历史对话与原会话归属同一群聊
                    groupId = currentSession.groupId,
                    // 继承会话级聊天背景
                    backgroundUrl = currentSession.backgroundUrl
                )
                persistAndActivateSession(session)
                Log.d(TAG, "Created new group session for current group: ${session.id} (${session.groupMembers.size} members, groupId=${currentSession.groupId})")
            }
            return
        }

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

    // ── 群聊管理 ──

    /**
     * 创建群聊会话
     *
     * 完整流程：群聊名称设置 + 成员选择 + 群聊创建 + 持久化存储。
     * 群聊使用全局 API 配置（apiUrl/apiKey/model），成员各自携带人格配置快照。
     *
     * @param groupName 群聊名称。为空时使用默认名称（"群聊-时间戳"）
     * @param memberPersonaIds 选中的舰娘人格配置 ID 列表（至少 2 个，否则退化为私聊无意义）
     * @return 新创建的群聊会话 ID；若成员不足或配置缺失则返回空串
     */
    fun createGroupSession(groupName: String, memberPersonaIds: List<String>): String {
        // 成员去重并保持选择顺序
        val distinctIds = memberPersonaIds.distinct()
        if (distinctIds.size < 2) {
            Log.w(TAG, "createGroupSession: need at least 2 members, got ${distinctIds.size}")
            return ""
        }
        // 解析成员配置：仅保留存在的 persona
        val members = distinctIds.mapNotNull { id ->
            personaConfigs.value.firstOrNull { it.id == id }?.let { persona ->
                GroupMember(
                    personaId = persona.id,
                    jiuxinName = persona.jiuxinName.ifBlank { persona.name },
                    avatarUrl = normalizeUrl(persona.avatarUrl),
                    systemPrompt = persona.systemPrompt,
                    voiceShipName = persona.voiceShipName,
                    voiceShipAvatar = normalizeUrl(persona.voiceShipAvatar),
                    voiceEnabled = persona.voiceEnabled,
                    voiceRandomChance = persona.voiceRandomChance,
                    voiceKeywords = persona.voiceKeywords,
                    stickersEnabled = persona.stickersEnabled,
                    stickerChance = persona.stickerChance
                )
            }
        }
        if (members.size < 2) {
            Log.w(TAG, "createGroupSession: resolved members < 2 (${members.size})")
            return ""
        }

        // P2 防御：校验 API 配置有效性。UI 层（ConversationListScreen）已做校验，
        // 此处为兜底防御，避免后续 sendMessage 时才发现配置缺失导致体验受损。
        if (apiUrl.value.isBlank() || apiKey.value.isBlank()) {
            Log.w(TAG, "createGroupSession: API config missing (url=${apiUrl.value.isNotBlank()}, key=${apiKey.value.isNotBlank()})")
            return ""
        }

        // 递增代际计数器，使在途的 sendMessage 请求返回后丢弃旧会话的回复
        _chatGeneration.incrementAndGet()
        val newSessionId = UUID.randomUUID().toString()
        // 生成稳定的群聊标识：用于 computeShipKey 去重，重命名/改成员后保持不变，
        // 确保历史面板聚合不断裂。同一群聊的新建会话/自动继承会话都复用此 ID。
        val newGroupId = UUID.randomUUID().toString()
        viewModelScope.launch {
            val effectiveName = groupName.trim().ifBlank {
                "群聊-${SimpleDateFormat("MMdd-HHmm", Locale.getDefault()).format(Date())}"
            }
            val session = ChatSession(
                id = newSessionId,
                name = effectiveName,
                jiuxinName = effectiveName,
                // 群聊使用全局 API 配置快照
                apiUrl = apiUrl.value,
                apiKey = apiKey.value,
                model = selectedModel.value,
                // 群聊级 systemPrompt 仅作兜底：实际 API 调用以各成员 prompt 构造群聊上下文
                systemPrompt = systemPrompt.value,
                sessionType = SessionType.GROUP.name,
                groupMembers = members,
                groupId = newGroupId
            )
            persistAndActivateSession(session)
            Log.d(TAG, "Created group session: ${session.id} - ${session.name} (${members.size} members, groupId=$newGroupId)")
        }
        return newSessionId
    }

    /**
     * 更新群聊成员（群成员管理）。
     *
     * @param sessionId 群聊会话 ID
     * @param memberPersonaIds 新的成员 personaId 列表
     */
    fun updateGroupMembers(sessionId: String, memberPersonaIds: List<String>) {
        val distinctIds = memberPersonaIds.distinct()
        if (distinctIds.size < 2) {
            Log.w(TAG, "updateGroupMembers: need at least 2 members")
            return
        }
        val members = distinctIds.mapNotNull { id ->
            personaConfigs.value.firstOrNull { it.id == id }?.let { persona ->
                GroupMember(
                    personaId = persona.id,
                    jiuxinName = persona.jiuxinName.ifBlank { persona.name },
                    avatarUrl = normalizeUrl(persona.avatarUrl),
                    systemPrompt = persona.systemPrompt,
                    voiceShipName = persona.voiceShipName,
                    voiceShipAvatar = normalizeUrl(persona.voiceShipAvatar),
                    voiceEnabled = persona.voiceEnabled,
                    voiceRandomChance = persona.voiceRandomChance,
                    voiceKeywords = persona.voiceKeywords,
                    stickersEnabled = persona.stickersEnabled,
                    stickerChance = persona.stickerChance
                )
            }
        }
        if (members.size < 2) return
        viewModelScope.launch {
            val updated = _sessions.value.map { session ->
                if (session.id == sessionId && session.isGroup) {
                    session.copy(groupMembers = members, updatedAt = System.currentTimeMillis())
                } else session
            }
            settings.setAiChatSessions(updated)
            _sessions.value = updated
            Log.d(TAG, "Updated group members: $sessionId (${members.size} members)")
        }
    }

    /**
     * 重命名群聊
     */
    fun renameGroupSession(sessionId: String, newName: String) {
        val trimmed = newName.trim()
        if (trimmed.isBlank()) return
        viewModelScope.launch {
            val updated = _sessions.value.map { session ->
                if (session.id == sessionId && session.isGroup) {
                    session.copy(name = trimmed, jiuxinName = trimmed, updatedAt = System.currentTimeMillis())
                } else session
            }
            settings.setAiChatSessions(updated)
            _sessions.value = updated
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
     *
     * 响应性优化：内存状态（[_currentSessionId]、[_chatState] 语音配置）在主线程同步更新，
     * 仅 DataStore 持久化在协程中异步执行。这样从会话列表点击切换并立即导航到聊天页时，
     * [currentSession] StateFlow 能立即反映新会话，避免"切换不及时需返回再点"的问题。
     */
    fun switchToSession(sessionId: String) {
        if (_currentSessionId.value == sessionId) return
        // 递增代际计数器，使在途的 sendMessage 请求返回后丢弃旧会话的回复
        _chatGeneration.incrementAndGet()
        // 捕获旧会话 ID：确保 saveCurrentSessionMessages 保存到正确的会话
        val oldSessionId = _currentSessionId.value
        // 关键：在清空消息之前先捕获当前消息列表，否则 saveCurrentSessionMessages 会保存空列表
        val messagesToSave = _chatState.value.messages

        // ── 同步更新内存状态（主线程立即生效）──
        // 立即更新 _currentSessionId：currentSession StateFlow 会即时派发新会话快照给 UI
        // 这是从会话列表点击切换后立即导航到聊天页能正确显示新会话的关键
        _currentSessionId.value = sessionId
        // 立即清空消息并显示加载态：避免异步切换期间闪现旧会话内容
        // 消息会由 init 中的 _currentSessionId.flatMapLatest collector 异步加载
        _chatState.update { it.copy(messages = emptyList(), error = null, isLoading = true, inputText = "", typingMembers = emptyList()) }
        // 同步当前会话的语音关键词到 chatState（用于语音触发匹配）
        // 注意：不在此处关闭 isLoading，由 _currentSessionId.flatMapLatest collector
        // 加载完消息后自然更新 chatState.messages（此时 isLoading 仍为 true，
        // 但 messages 已显示，UI 可基于 messages 非空判断显示内容）
        val targetSession = _sessions.value.firstOrNull { it.id == sessionId }
        if (targetSession != null) {
            val keywords = targetSession.voiceKeywords.split(";").filter { it.isNotBlank() }
            _chatState.update {
                it.copy(
                    voiceKeywords = keywords,
                    voiceTagMappings = buildVoiceTagMappings(keywords),
                    error = null
                    // 不重置 isLoading：让消息加载 collector 负责最终状态
                )
            }
        } else {
            // 会话不存在（异常情况）：关闭加载态
            _chatState.update { it.copy(error = null, isLoading = false) }
        }

        // ── 异步持久化（不阻塞 UI）──
        viewModelScope.launch {
            // 保存旧会话消息（使用捕获的旧会话 ID 和消息列表，避免竞态和数据丢失）
            // 不更新时间戳：切换会话不应改变会话列表顺序
            if (oldSessionId.isNotBlank() && messagesToSave.isNotEmpty()) {
                settings.setAiSessionMessages(oldSessionId, messagesToSave)
            }
            // 持久化新会话 ID 到 DataStore
            settings.setAiCurrentSessionId(sessionId)
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

    /**
     * 删除指定会话。
     *
     * 切换/创建策略（当删除的是当前会话时）：
     * 1. **同舰娘还有其他会话** → 切换到同舰娘最新的会话（保持 HistoryPanel 仍在原舰娘上下文，
     *    用户可继续删除/查看该舰娘的其他对话）
     * 2. **同舰娘无其他会话** → 自动创建新会话继承原舰娘标识和配置快照（保持该舰娘在会话列表
     *    中存在，与"只有一个舰娘时删除所有会话"的行为一致，避免舰娘被"误删除"）
     *
     * 非当前会话被删除时无需切换，仅更新会话列表。
     *
     * @param sessionId 要删除的会话 ID
     */
    fun deleteSession(sessionId: String) {
        viewModelScope.launch {
            // 递增代际计数器，使所有在途的 sendMessage 请求返回后能检测到状态变更并丢弃结果
            _chatGeneration.incrementAndGet()

            // 先捕获被删除会话的舰娘标识（用于同舰娘会话查找和自动创建新会话时继承）
            val deletedSession = _sessions.value.firstOrNull { it.id == sessionId } ?: return@launch
            val deletedShipKey = computeShipKey(deletedSession)

            val currentList = _sessions.value.toMutableList()
            currentList.removeAll { it.id == sessionId }
            // 先持久化会话列表到 DataStore
            settings.setAiChatSessions(currentList)
            settings.deleteAiSessionMessages(sessionId)

            // 删除的不是当前会话：无需切换，仅更新会话列表内存状态
            if (_currentSessionId.value != sessionId) {
                _sessions.value = currentList
                Log.d(TAG, "Deleted non-current session: $sessionId, remaining: ${currentList.size}")
                return@launch
            }

            // 删除的是当前会话：按优先级决定切换/创建策略

            // 策略 1：同舰娘还有其他会话 → 切换到同舰娘最新的会话
            // 保持 HistoryPanel 上下文不变，用户可继续操作该舰娘的其他对话
            val sameShipLatest = currentList
                .filter { computeShipKey(it) == deletedShipKey }
                .maxByOrNull { it.updatedAt }

            if (sameShipLatest != null) {
                // 先持久化新会话 ID，再同步更新内存
                settings.setAiCurrentSessionId(sameShipLatest.id)
                _currentSessionId.value = sameShipLatest.id
                // 立即清空消息并显示加载态：避免闪现被删除会话的内容
                // 同步目标会话的语音关键词（与 switchToSession 行为一致）
                // L388-399 的 collector 会自动加载 sameShipLatest 的消息
                val keywords = sameShipLatest.voiceKeywords.split(";").filter { it.isNotBlank() }
                _chatState.update {
                    it.copy(
                        messages = emptyList(),
                        voiceKeywords = keywords,
                        voiceTagMappings = buildVoiceTagMappings(keywords),
                        error = null,
                        isLoading = true,
                        inputText = "",
                        typingMembers = emptyList()
                    )
                }
                _sessions.value = currentList
                Log.d(TAG, "Deleted session: $sessionId, switched to same ship: ${sameShipLatest.id}")
                return@launch
            }

            // 群聊会话删除后：同组无其他会话时，自动创建继承群聊标识的空会话
            // 修复"群聊历史对话全部删除导致整个群聊被删除"问题：
            // 历史对话面板（deleteSession）的语义是"删除一条历史对话"，
            // 不应导致整个群聊从会话列表中消失。与私聊"策略2"保持一致，
            // 同组会话全部被删除后自动创建继承群聊名+成员配置的空会话。
            if (deletedSession.isGroup) {
                val newGroupSession = ChatSession(
                    name = deletedSession.name,
                    presetId = deletedSession.presetId,
                    avatarUrl = deletedSession.avatarUrl,
                    jiuxinName = deletedSession.jiuxinName,
                    apiUrl = deletedSession.apiUrl,
                    apiKey = deletedSession.apiKey,
                    model = deletedSession.model,
                    systemPrompt = deletedSession.systemPrompt,
                    voiceShipName = deletedSession.voiceShipName,
                    voiceShipAvatar = deletedSession.voiceShipAvatar,
                    voiceEnabled = deletedSession.voiceEnabled,
                    voiceRandomChance = deletedSession.voiceRandomChance,
                    voiceKeywords = deletedSession.voiceKeywords,
                    stickersEnabled = deletedSession.stickersEnabled,
                    stickerChance = deletedSession.stickerChance,
                    sessionType = deletedSession.sessionType,
                    groupMembers = deletedSession.groupMembers,
                    // P2 修复：继承稳定群聊标识，重命名后新建会话仍归属同一群聊
                    groupId = deletedSession.groupId,
                    // P2 修复：继承会话级聊天背景，避免删除历史对话后背景丢失
                    backgroundUrl = deletedSession.backgroundUrl
                )
                val newList = listOf(newGroupSession) + currentList
                settings.setAiChatSessions(newList)
                settings.setAiSessionMessages(newGroupSession.id, emptyList())
                settings.setAiCurrentSessionId(newGroupSession.id)
                _currentSessionId.value = newGroupSession.id
                _sessions.value = newList
                _chatState.update { it.copy(messages = emptyList(), error = null, isLoading = false, inputText = "", typingMembers = emptyList()) }
                Log.d(TAG, "Deleted group session: $sessionId, auto-created new group session: ${newGroupSession.id}")
                return@launch
            }

            // 策略 2：同舰娘无其他会话 → 自动创建新会话继承原舰娘标识和配置
            // 无论是否有其他舰娘的会话，都为原舰娘保留一个空会话，避免舰娘从列表中"消失"
            // 这与"只有一个舰娘时删除所有会话"的行为一致
            val newSession = ChatSession(
                name = generateDefaultName(),
                presetId = deletedSession.presetId,
                avatarUrl = deletedSession.avatarUrl,
                jiuxinName = deletedSession.jiuxinName,
                apiUrl = deletedSession.apiUrl,
                apiKey = deletedSession.apiKey,
                model = deletedSession.model,
                systemPrompt = deletedSession.systemPrompt,
                voiceShipName = deletedSession.voiceShipName,
                voiceShipAvatar = deletedSession.voiceShipAvatar,
                voiceEnabled = deletedSession.voiceEnabled,
                voiceRandomChance = deletedSession.voiceRandomChance,
                voiceKeywords = deletedSession.voiceKeywords,
                stickersEnabled = deletedSession.stickersEnabled,
                stickerChance = deletedSession.stickerChance
            )
            // 新会话插入到列表头部，使其在会话列表中优先显示
            val newList = listOf(newSession) + currentList
            settings.setAiChatSessions(newList)
            settings.setAiSessionMessages(newSession.id, emptyList())
            settings.setAiCurrentSessionId(newSession.id)
            _currentSessionId.value = newSession.id
            _sessions.value = newList
            _chatState.update { it.copy(messages = emptyList(), error = null, isLoading = false, typingMembers = emptyList()) }
            Log.d(TAG, "Deleted session: $sessionId, auto-created new session for ship: ${newSession.id} (ship=$deletedShipKey)")
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
                    _chatState.update { it.copy(messages = emptyList(), error = null, isLoading = false, inputText = "", typingMembers = emptyList()) }
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
                    // 群聊：名称即群名，name 与 jiuxinName 同步更新（会话列表用 jiuxinName 显示）
                    if (it.isGroup) it.copy(name = trimmedName, jiuxinName = trimmedName)
                    else it.copy(name = trimmedName)
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
     * 获取可用模型列表。
     *
     * 委托 [JiuxinApiRepository.fetchModelList] 执行，采用多端点回退 + 多格式解析策略，
     * 兼容各类 OpenAI 兼容 / 第三方代理 API。结果写入 [modelListState]。
     */
    fun fetchModels() {
        viewModelScope.launch {
            val key = settings.aiApiKey.first()
            val baseUrl = settings.aiCustomBaseUrl.first().trim()
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
     * 结果写入共享的 [modelListState]（同一时刻仅一个界面可见，无冲突）。
     */
    fun fetchModelsForCurrentSession() {
        val session = _sessions.value.firstOrNull { it.id == _currentSessionId.value }
        if (session == null) return
        viewModelScope.launch {
            val key = session.apiKey.ifBlank { settings.aiApiKey.first() }
            val baseUrl = session.apiUrl.ifBlank { settings.aiCustomBaseUrl.first() }.trim()
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

    /**
     * 模型列表拉取通用实现 — 委托 [JiuxinApiRepository.fetchModelList]。
     *
     * 核心改进：
     * - 多端点回退：`/v1/models` → `/models` → 剥离 `/chat/completions` 后追加 `/models`
     * - 多格式解析：OpenAI `data[].id` / Anthropic `models[].id` / 裸数组 / 字符串数组 / 对象键名
     * - 多鉴权头：同时发送 `Authorization: Bearer` + `x-api-key`
     * - 状态机驱动：UI 可据此展示加载动画与具体错误原因
     */
    private suspend fun performFetchModels(baseUrl: String, key: String) {
        if (key.isBlank()) {
            _modelListState.value = ModelListState.Error("请先配置 API 密钥")
            return
        }
        if (baseUrl.isBlank()) {
            _modelListState.value = ModelListState.Error("请先配置 API Base URL")
            return
        }

        _modelListState.value = ModelListState.Loading
        Log.i(TAG, "Fetching model list from baseUrl=$baseUrl")

        val result = apiRepository.fetchModelList(baseUrl, key)
        _modelListState.value = when (result) {
            is JiuxinApiResult.Success -> {
                if (result.content.isEmpty()) {
                    Log.w(TAG, "Model list fetched but empty from $baseUrl")
                    ModelListState.Empty
                } else {
                    Log.i(TAG, "Model list fetched: ${result.content.size} models")
                    ModelListState.Success(result.content)
                }
            }
            is JiuxinApiResult.Failure -> {
                Log.e(TAG, "Model list fetch failed: ${result.error.userMessage}")
                ModelListState.Error(result.error.userMessage)
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
        // P0-4 防御：正在进行中的请求时拒绝新发送，避免并发 handleGroupMessage
        // 造成上下文混乱、typingMembers 残留、消息保存竞态等问题。
        // UI 层 Send 按钮已基于 isLoading 禁用，此处为兜底防御。
        if (_chatState.value.isLoading) {
            Log.d(TAG, "sendMessage ignored: another request is in progress")
            return
        }

        val userMessage = ChatMessage(
            id = UUID.randomUUID().toString(),
            type = ChatMessageType.USER.name,
            content = text,
            timestamp = System.currentTimeMillis(),
            // 用户消息本地添加，状态立即 SUCCESS（无需网络等待）
            status = MessageStatus.SUCCESS.name
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

            // ── 群聊分支：多舰娘依次回复 ──
            if (currentSessionSnapshot?.isGroup == true) {
                try {
                    handleGroupMessage(text, sessionId, generation, currentSessionSnapshot)
                } catch (e: Exception) {
                    Log.e(TAG, "Group message handling failed", e)
                    // 标记用户消息为失败，允许重试
                    markMessageFailed(userMessage.id)
                    _chatState.update { it.copy(error = e.message ?: e.javaClass.simpleName, isLoading = false, typingMembers = emptyList()) }
                } finally {
                    finishLoading()
                }
                return@launch
            }

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
                    timestamp = System.currentTimeMillis(),
                    status = MessageStatus.SUCCESS.name
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
                // 标记用户消息为失败，允许重试
                markMessageFailed(userMessage.id)
                _chatState.update { it.copy(error = e.message ?: e.javaClass.simpleName) }
            } finally {
                finishLoading()
            }
        }
    }

    /**
     * 将指定消息标记为发送失败（状态机：SUCCESS → FAILED）。
     *
     * 用于 API 调用失败时标记用户消息，UI 可显示重试按钮。
     */
    private fun markMessageFailed(messageId: String) {
        _chatState.update { state ->
            state.copy(
                messages = state.messages.map { msg ->
                    if (msg.id == messageId) msg.copy(status = MessageStatus.FAILED.name) else msg
                }
            )
        }
        saveCurrentSessionMessages()
    }

    /**
     * 重试发送失败的消息。
     *
     * 状态机流转：FAILED → SENDING → SUCCESS / FAILED
     * - 将消息状态重置为 SUCCESS（本地消息内容不变）
     * - 重新调用 [sendMessage] 以该消息内容触发 API
     *
     * @param messageId 失败消息的 ID
     */
    fun retryMessage(messageId: String) {
        val failedMsg = _chatState.value.messages.firstOrNull { it.id == messageId }
            ?: return
        if (failedMsg.type != ChatMessageType.USER.name) return
        if (failedMsg.status != MessageStatus.FAILED.name) return

        // 重置状态为 SUCCESS（用户消息本地存在），然后重新发送
        _chatState.update { state ->
            state.copy(
                messages = state.messages.map { msg ->
                    if (msg.id == messageId) msg.copy(status = MessageStatus.SUCCESS.name) else msg
                }
            )
        }
        // 重新发送消息内容
        sendMessage(failedMsg.content)
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

    // ── 群聊消息处理 ──

    /**
     * 处理群聊消息：多个舰娘成员**并行**回复用户消息，并可选地互相回应。
     *
     * 实现策略（优化版）：
     *
     * **第一轮（并行回复用户）**：
     * - 从群成员中随机选择 1~2 位回复（避免每次全员回复造成刷屏和 API 浪费）
     * - 使用 `coroutineScope { launch { ... } }` 并发发起 API 请求
     * - 每位成员的回复**流式显示**（谁先返回谁先 addMessage + 移除对应 TypingIndicator）
     * - 使用 [Mutex] 保护 typingSet / successCount / spokenMembers 等共享状态
     * - 表情包/语音按各成员配置独立触发，与回复显示并行不阻塞其他成员
     *
     * **第二轮（成员间互回，可选）**：
     * - 仅当第一轮至少 1 位成员成功回复时，按 [REACTION_PROBABILITY] 概率触发
     * - 每轮选 1 位成员（优先选未参与前序轮次的成员）对刚才的对话做出反应
     * - AI 可输出 `[SKIP]` 标记表示无需回应，由本方法识别并跳过
     *
     * **死循环防护**：
     * - 严格限定最多 [MAX_REACTION_ROUNDS] 轮互回，无更深递归 → 物理上不可能死循环
     * - 概率衰减：每轮互回触发概率 [REACTION_PROBABILITY] = 85%
     * - AI 自决：通过 `[SKIP]` 标记让模型主动放弃回应
     * - 用户介入：发送新消息 → 代际计数器递增 → 所有在途回复被丢弃
     * - 单次用户消息触发的 AI 回复上限 = 3（并行初始） + min(成员数, MAX_REACTION_ROUNDS)（互回） ≤ 6 条
     *
     * 竞态防护：与私聊一致，使用代际计数器 + 会话 ID 双重校验，
     * 中途清空/删除/切换会话时丢弃后续回复。
     *
     * @param text 用户发送的文本
     * @param sessionId 群聊会话 ID
     * @param generation 发送时的代际计数
     * @param session 群聊会话快照（含 groupMembers）
     */
    private suspend fun handleGroupMessage(
        text: String,
        sessionId: String,
        generation: Int,
        session: ChatSession
    ) {
        val members = session.groupMembers
        if (members.isEmpty()) {
            _chatState.update { it.copy(error = "群聊暂无成员", isLoading = false, typingMembers = emptyList()) }
            return
        }

        // 选择回复成员：根据成员数动态选择并发数，保证群聊活跃度与可读性平衡
        // - 成员数 <= 2：全部参与（每人必回）
        // - 成员数 3-4：选 3 位（多数参与，活跃度高）
        // - 成员数 >= 5：选 3 位（避免消息过多刷屏，同时保持并发活跃度）
        val initialResponders = when {
            members.size <= 2 -> members.toList()
            members.size <= 4 -> members.shuffled().take(3).toList()
            else -> members.shuffled().take(3).toList()
        }

        // ── 设置初始打字指示器：所有初始回复成员同时显示 ──
        val typingSet = mutableSetOf<TypingMember>()
        initialResponders.forEach { typingSet.add(TypingMember(it.jiuxinName, it.avatarUrl)) }
        updateTypingMembers(typingSet.toList())

        // 共享状态（由 mutex 保护）：跨多个 launch 协程访问
        var anyError: String? = null
        var successCount = 0
        val spokenMembers = mutableListOf<GroupMember>()
        val stateMutex = Mutex()

        // ── 第一轮：并行调用 API + 流式显示 ──
        // 每位成员独立 launch，谁先完成谁先 addMessage，无需等待其他成员
        // coroutineScope 会等待所有 launch 完成，确保进入第二轮前第一轮已结束
        coroutineScope {
            initialResponders.forEach { member ->
                launch {
                    try {
                        val reply = callGroupApi(text, member, session, isReaction = false)

                        // API 返回后立即检查代际（避免在已切换会话后还显示旧回复）
                        if (_chatGeneration.get() != generation || _currentSessionId.value != sessionId) {
                            Log.d(TAG, "Group chat state changed, abort reply for ${member.jiuxinName}")
                            // P1 修复：代际变更后必须清理 typingSet，否则该成员的 TypingIndicator
                            // 会残留在内存 typingSet 中。虽然 switchToSession/clearChat 会清空 UI 的
                            // typingMembers，但本函数末尾的 updateTypingMembers(typingSet.toList())
                            // 可能将残留成员重新写回 UI。
                            stateMutex.withLock {
                                typingSet.remove(TypingMember(member.jiuxinName, member.avatarUrl))
                                if (_chatGeneration.get() == generation && _currentSessionId.value == sessionId) {
                                    updateTypingMembers(typingSet.toList())
                                }
                            }
                            return@launch
                        }

                        val aiMessage = ChatMessage(
                            id = UUID.randomUUID().toString(),
                            type = ChatMessageType.AI.name,
                            content = reply,
                            timestamp = System.currentTimeMillis(),
                            shipName = member.jiuxinName,
                            avatarUrl = member.avatarUrl
                        )

                        // 加锁保护共享状态：addMessage/typingSet/spokenMembers/successCount
                        // 锁内操作极短（仅状态更新 + StateFlow update），不影响其他成员并发
                        stateMutex.withLock {
                            if (_chatGeneration.get() != generation || _currentSessionId.value != sessionId) {
                                // P1 修复：锁内二次检查失败时也需清理 typingSet
                                typingSet.remove(TypingMember(member.jiuxinName, member.avatarUrl))
                                Log.d(TAG, "Group member ${member.jiuxinName} aborted: state changed in lock")
                                return@launch
                            }
                            addMessage(aiMessage)
                            successCount++
                            spokenMembers.add(member)
                            typingSet.remove(TypingMember(member.jiuxinName, member.avatarUrl))
                            updateTypingMembers(typingSet.toList())
                            Log.d(TAG, "Group member ${member.jiuxinName} replied successfully (successCount=$successCount, replyLen=${reply.length})")
                        }

                        // ── 表情包逻辑（锁外执行，不阻塞其他成员显示） ──
                        if (member.stickersEnabled && kotlin.random.Random.nextFloat() < member.stickerChance) {
                            findBestSticker(reply)?.let { sticker ->
                                kotlinx.coroutines.delay(300) // 模拟输入感
                                if (_chatGeneration.get() != generation || _currentSessionId.value != sessionId) return@launch
                                if (!_chatState.value.messages.any { it.id == aiMessage.id }) return@launch
                                addStickerMessage(sticker, member.jiuxinName, member.avatarUrl)
                            }
                        }

                        // ── 语音触发（锁外执行，按成员配置独立触发） ──
                        triggerGroupMemberVoice(text, member)
                    } catch (e: Exception) {
                        Log.e(TAG, "Group member ${member.jiuxinName} reply failed", e)
                        stateMutex.withLock {
                            if (anyError == null) anyError = e.message ?: e.javaClass.simpleName
                            typingSet.remove(TypingMember(member.jiuxinName, member.avatarUrl))
                            if (_chatGeneration.get() == generation && _currentSessionId.value == sessionId) {
                                updateTypingMembers(typingSet.toList())
                            }
                        }
                    }
                }
            }
        }
        // coroutineScope 返回时所有 launch 已完成，spokenMembers/successCount 已稳定
        Log.d(TAG, "Group first round done: successCount=$successCount, spokenMembers=${spokenMembers.map { it.jiuxinName }}, members=${members.size}")

        // ── 第二轮起：成员间互回循环（受概率与代际双重限制） ──
        // 死循环防护：最多 min(成员数, MAX_REACTION_ROUNDS) 轮互回，每轮都需要概率命中 + 代际未变更
        // 每轮选择不同的成员回应，形成"用户→A→B→C"的连续群聊互动
        // 动态轮数：3 人群聊最多 3 轮互回，确保每位成员都有机会回应他人
        val maxRounds = minOf(members.size, MAX_REACTION_ROUNDS)
        var reactionRound = 0
        // 仅记录参与过互回轮的成员（不包含第一轮并发成员），让 freshCandidates 能选到第一轮已发言的成员
        // 这样 3 人群聊中 3 人都参与第一轮后，互回轮仍能让每位成员都有机会回应
        val reactionParticipatedIds = mutableSetOf<String>()

        while (reactionRound < maxRounds &&
            successCount >= 1 &&
            kotlin.random.Random.nextFloat() < REACTION_PROBABILITY &&
            _chatGeneration.get() == generation && _currentSessionId.value == sessionId
        ) {
            reactionRound++

            // 选择本轮回应成员：
            // - 优先选未参与过互回轮的成员（让更多成员有机会发言）
            // - 若所有成员都已参与过互回，则排除最后一位发言者（避免自言自语）
            val lastSpoken = spokenMembers.lastOrNull()
            val freshCandidates = members.filter { it.id !in reactionParticipatedIds }
            val reactorPool = if (freshCandidates.isNotEmpty()) {
                freshCandidates
            } else {
                members.filter { it.id != lastSpoken?.id }
            }

            if (reactorPool.isEmpty()) break

            val reactor = reactorPool.shuffled().first()
            reactionParticipatedIds.add(reactor.id)
            Log.d(TAG, "Group reaction round=$reactionRound/$maxRounds, selected reactor: ${reactor.jiuxinName}, freshCandidates=${freshCandidates.size}")

            // 标记打字
            typingSet.add(TypingMember(reactor.jiuxinName, reactor.avatarUrl))
            updateTypingMembers(typingSet.toList())

            try {
                val reactionReply = callGroupApi(
                    userMessage = "",
                    member = reactor,
                    session = session,
                    isReaction = true
                )
                if (_chatGeneration.get() != generation || _currentSessionId.value != sessionId) break

                // 检测 [SKIP] 标记：AI 主动放弃回应
                val trimmed = reactionReply.trim()
                val isSkip = trimmed == SKIP_TOKEN ||
                    trimmed.startsWith(SKIP_TOKEN) ||
                    trimmed.isEmpty()

                if (isSkip) {
                    Log.d(TAG, "Group member ${reactor.jiuxinName} skipped reaction (round=$reactionRound/$maxRounds)")
                    // 本轮无新增消息，但仍计入参与（避免下轮再次选中）
                } else {
                    // 清理可能残留的 [SKIP] 前缀（兼容模型输出 "[SKIP] ..." 这种半格式）
                    val cleanedReply = trimmed.removePrefix(SKIP_TOKEN).trim()
                    val aiMessage = ChatMessage(
                        id = UUID.randomUUID().toString(),
                        type = ChatMessageType.AI.name,
                        content = cleanedReply,
                        timestamp = System.currentTimeMillis(),
                        shipName = reactor.jiuxinName,
                        avatarUrl = reactor.avatarUrl
                    )
                    addMessage(aiMessage)
                    successCount++
                    spokenMembers.add(reactor)
                    Log.d(TAG, "Group member ${reactor.jiuxinName} reaction replied (round=$reactionRound/$maxRounds, successCount=$successCount, replyLen=${cleanedReply.length})")

                    // 互回轮的表情包触发（语音跳过，避免与第一轮重叠）
                    if (reactor.stickersEnabled && kotlin.random.Random.nextFloat() < reactor.stickerChance) {
                        findBestSticker(cleanedReply)?.let { sticker ->
                            kotlinx.coroutines.delay(300)
                            if (_chatGeneration.get() == generation && _currentSessionId.value == sessionId) {
                                if (_chatState.value.messages.any { it.id == aiMessage.id }) {
                                    addStickerMessage(sticker, reactor.jiuxinName, reactor.avatarUrl)
                                }
                            }
                        }
                    }

                    // 模拟群聊节奏：成员间回复间隔
                    kotlinx.coroutines.delay(500)
                }
            } catch (e: Exception) {
                Log.e(TAG, "Group member ${reactor.jiuxinName} reaction failed (round=$reactionRound)", e)
                if (anyError == null) anyError = e.message ?: e.javaClass.simpleName
            } finally {
                typingSet.remove(TypingMember(reactor.jiuxinName, reactor.avatarUrl))
                updateTypingMembers(typingSet.toList())
            }
        }

        // 仅当全部成员回复失败时展示错误；部分成功则不打扰用户
        if (anyError != null && successCount == 0) {
            _chatState.update { it.copy(error = anyError) }
        }
        updateTypingMembers(emptyList())
        finishLoading()
    }

    /**
     * 更新群聊打字成员列表（触发 UI 渲染多位 TypingIndicator）。
     */
    private fun updateTypingMembers(members: List<TypingMember>) {
        _chatState.update { it.copy(typingMembers = members) }
    }

    /**
     * 动态选择上下文消息：基于字符数估算 token，从最新消息向前累计。
     *
     * 策略：
     * 1. 从列表末尾（最新消息）向前遍历
     * 2. 累计消息内容的字符数（含身份前缀）
     * 3. 当累计字符数超过 [maxChars] 或消息条数超过 [maxMessages] 时停止
     * 4. 返回选中的消息列表（保持时间正序，即最旧在前最新在后）
     *
     * 这样保证：
     * - 最近对话优先纳入上下文
     * - 长消息场景不会超出模型上下文窗口
     * - 短消息场景可纳入更多历史（最多 [maxMessages] 条）
     *
     * @param allMessages 全部消息列表（时间正序）
     * @param maxMessages 最大消息条数
     * @param maxChars 最大字符数（token 估算）
     * @return 选中的消息列表（时间正序）
     */
    private fun selectContextMessages(
        allMessages: List<ChatMessage>,
        maxMessages: Int,
        maxChars: Int
    ): List<ChatMessage> {
        if (allMessages.isEmpty()) return emptyList()
        val selected = mutableListOf<ChatMessage>()
        var totalChars = 0
        // 从最新消息向前遍历
        for ((index, i) in allMessages.indices.reversed().withIndex()) {
            val msg = allMessages[i]
            // 估算单条消息字符数：内容 + 身份前缀（约 10 字符）+ role 标记开销
            val estimatedChars = msg.content.length + 10
            if (totalChars + estimatedChars > maxChars) {
                // P1 修复：保底机制——即使首条（最新）消息超过 maxChars 也必须包含它，
                // 否则互回轮上下文为空导致 AI 无法理解当前对话，initialReply 轮用户消息丢失。
                // 后续更长历史直接截断即可。
                if (index == 0) {
                    // 首条超长时截断内容，确保至少有最新消息作为上下文
                    val budget = maxChars - totalChars - 10
                    if (budget > 0) {
                        val truncated = msg.copy(content = msg.content.take(budget) + "…")
                        selected.add(0, truncated)
                    }
                }
                break
            }
            selected.add(0, msg)  // 插入到列表头部，保持时间正序
            totalChars += estimatedChars
            if (selected.size >= maxMessages) break
        }
        return selected
    }

    /**
     * 触发群成员的语音回复（按成员自身的语音配置）。
     *
     * P1 修复：语音消息归属到群成员（[member.jiuxinName] + [member.avatarUrl]），
     * 而非语音来源舰船（[member.voiceShipName] + [member.voiceShipAvatar]）。
     *
     * 原实现将 voiceShipName 作为 sendVoiceMessage 的 shipName 参数，导致群聊界面
     * 显示语音消息时归属到错误的舰娘（如成员"赤诚"的语音配置为"赤诚.改"时，
     * 语音消息会显示为"赤诚.改"发送的，与群聊成员身份不一致）。
     *
     * 现拆分为两层：
     * - 语音查询：使用 [member.voiceShipName] 调用 [getVoicesUseCase] 获取语音资源
     * - 消息归属：使用 [member.jiuxinName] 和 [member.avatarUrl] 作为消息的发送者标识
     */
    private fun triggerGroupMemberVoice(text: String, member: GroupMember) {
        if (!member.voiceEnabled || member.voiceShipName.isBlank()) return
        val keywords = member.voiceKeywords.split(";").filter { it.isNotBlank() }
        val tagMappings = buildVoiceTagMappings(keywords)
        val matched = findBestTagMatch(text, tagMappings)
        when {
            matched != null -> triggerVoiceByTagsForMember(
                voiceShipName = member.voiceShipName,
                memberName = member.jiuxinName,
                memberAvatar = member.avatarUrl,
                sceneTags = matched.sceneTags,
                preferredSkinName = matched.preferredSkinName
            )
            member.voiceRandomChance > 0f && kotlin.random.Random.nextDouble() < member.voiceRandomChance ->
                triggerVoiceRandomForMember(
                    voiceShipName = member.voiceShipName,
                    memberName = member.jiuxinName,
                    memberAvatar = member.avatarUrl
                )
        }
    }

    /**
     * 群成员语音触发（带标签匹配）：语音查询与消息归属分离。
     * 与 [triggerVoiceByTags] 的区别：voiceShipName 仅用于语音资源查询，
     * 消息归属使用 [memberName] 和 [memberAvatar]（群成员身份）。
     */
    private fun triggerVoiceByTagsForMember(
        voiceShipName: String,
        memberName: String,
        memberAvatar: String,
        sceneTags: List<String>,
        preferredSkinName: String = ""
    ) {
        viewModelScope.launch {
            try {
                val (voices, _, _) = getVoicesUseCase(voiceShipName)
                if (voices.isNotEmpty()) {
                    val taggedVoice = findBestVoice(voices, sceneTags, preferredSkinName)
                    val audioUrl = taggedVoice.getActiveAudioUrl(VoiceLanguage.CN)
                    sendVoiceMessage(memberName, audioUrl, taggedVoice.dialogue, memberAvatar)
                }
            } catch (e: Exception) {
                Log.w(TAG, "Failed to load group member voice: ${e.message}")
            }
        }
    }

    /**
     * 群成员随机语音触发：语音查询与消息归属分离。
     */
    private fun triggerVoiceRandomForMember(
        voiceShipName: String,
        memberName: String,
        memberAvatar: String
    ) {
        viewModelScope.launch {
            try {
                val (voices, _, _) = getVoicesUseCase(voiceShipName)
                if (voices.isNotEmpty()) {
                    val defaultVoices = voices.filter { it.skinName in DEFAULT_SKIN_NAMES }
                    val voice = if (defaultVoices.isNotEmpty()) defaultVoices.random() else voices.random()
                    val audioUrl = voice.getActiveAudioUrl(VoiceLanguage.CN)
                    sendVoiceMessage(memberName, audioUrl, voice.dialogue, memberAvatar)
                }
            } catch (e: Exception) {
                Log.w(TAG, "Failed to load group member voice: ${e.message}")
            }
        }
    }

    /**
     * 群聊 API 调用：以某位成员的视角构造群聊上下文。
     *
     * 身份鉴别机制（v3 - ASCII name + content 标签双轨方案）：
     *
     * **问题背景**：
     * - OpenAI API 的 name 字段要求匹配 `^[a-zA-Z0-9_-]+$`，中文会触发 400 错误
     * - 纯 content 前缀方案（v1）会让 AI 模仿格式，输出带 `[舰娘名]:` 前缀
     * - 纯 name 字段方案（v2）对不支持 name 的第三方 API 无效
     *
     * **v3 双轨方案**：
     * 1. name 字段使用 ASCII 安全标识：`user` / `ship_<hash>` / `sys`
     * 2. content 开头添加轻量身份标签 `(指挥官)` / `(赤城)` / `(系统)`，与正文以空格分隔
     *    - 标签使用圆括号而非方括号，避免 AI 模仿 `[xxx]:` 输出格式
     *    - 标签简短，不显著增加 token
     * 3. system prompt 明确告知"content 开头的 (名称) 标识发言者，回复时不要添加此类标签"
     *
     * **关键修复**：不在 messages 末尾重复添加用户消息（P0-1）
     * - 用户消息已通过 addMessage 加入 _chatState.messages
     * - selectContextMessages 会包含最新用户消息
     * - 历史消息自己承担传递用户消息的职责，无需重复
     *
     * @param isReaction 是否为成员间互回模式。
     * - false（默认）：成员回复用户消息，userMessage 为用户原文（仅用于校验，不重复加入 messages）
     * - true：成员对其他成员的最近发言做出反应，userMessage 为空，
     *   系统在消息历史末尾追加"请决定是否回应"指令，允许模型输出 [SKIP] 放弃
     */
    private suspend fun callGroupApi(
        userMessage: String,
        member: GroupMember,
        session: ChatSession,
        isReaction: Boolean = false
    ): String {
        val key = session.apiKey.ifBlank { settings.aiApiKey.first() }
        if (key.isBlank()) throw Exception("未配置 API 密钥，请先在啾信配置中设置")
        val baseUrl = session.apiUrl.ifBlank { settings.aiCustomBaseUrl.first() }.trim()
        if (baseUrl.isBlank()) throw Exception("未配置 API URL，请先在啾信配置中设置")
        val url = buildFullApiUrl(baseUrl)
        val modelStr = session.model.ifBlank { settings.aiModel.first() }
        val model = modelStr.ifBlank { DEFAULT_MODEL }

        val otherMembers = session.groupMembers.filter { it.id != member.id }
            .joinToString("、") { it.jiuxinName }
        val groupContext = buildString {
            if (member.systemPrompt.isNotBlank()) append(member.systemPrompt).append("\n\n")
            append("【群聊场景】你正在一个群聊中，群名为「").append(session.name).append("」。")
            if (otherMembers.isNotBlank()) {
                append("群内其他成员有：").append(otherMembers).append("。")
            }
            append("你是 ").append(member.jiuxinName).append("，正在参与群聊。\n\n")

            // 身份鉴别说明：告知模型 content 开头的 (名称) 标签含义
            append("【发言者标识】群聊中有多位发言者，每条消息 content 开头的括号标签标识发言者：\n")
            append("- (指挥官) 开头的是指挥官（用户）的发言\n")
            append("- (舰娘名) 开头的是对应舰娘的发言，不是指挥官\n")
            append("- (系统) 开头的是系统对你的指示\n")
            append("- 无标签的 assistant 消息是你自己的历史回复\n")
            append("请根据标签区分发言者，不要将其他舰娘的发言误认为指挥官的。\n\n")

            // 输出格式约束：避免 AI 模仿输入格式
            append("【输出要求】\n")
            append("- 直接输出你的回复内容，不要添加任何括号标签或名字前缀\n")
            append("- 不要在回复开头添加 (").append(member.jiuxinName).append(") 或类似标记\n")
            append("- 不要模仿消息历史中的标签格式\n\n")

            // 互动引导：让 AI 更自然地参与群聊，形成多人聊天室氛围
            append("【互动准则】\n")
            append("- 保持你的角色设定和语言风格，不要脱离人格\n")
            append("- 可以主动回应其他舰娘的观点，形成自然的对话接力\n")
            append("- 对其他舰娘的发言可以表示赞同、质疑、补充或调侃，展现角色间的关系\n")
            append("- 回复简短自然（通常 1-2 句话），像真实群聊一样避免长篇大论\n")
            append("- 不要@其他成员，不要代替其他成员发言，不要重复他人说过的话\n\n")

            if (isReaction) {
                append("【当前任务】请观察群内其他成员的对话，基于你的人格和兴趣，主动参与讨论或回应其他成员的观点。")
                append("可以附和、反驳、补充、提问，或分享你的看法，让群聊形成自然的多人互动。")
                append("如果当前话题与你无关、你毫无兴趣，或无法自然插话，请只输出 [SKIP] 表示不发言。")
            } else {
                append("【当前任务】请以你自己的身份和语气回复指挥官的最新消息。")
                append("回复需简短自然，符合群聊氛围。")
            }
        }

        // 动态上下文截取：基于字符数估算 token，避免长消息超出模型上下文窗口
        val recentMessages = selectContextMessages(
            _chatState.value.messages,
            maxMessages = MAX_GROUP_CONTEXT_MESSAGES,
            maxChars = MAX_GROUP_CONTEXT_CHARS
        )
        // 构造 OpenAI 兼容 messages 数组（system + 带标识的历史消息）
        val roleMessages = buildList {
            add(ChatRoleMessage(role = "system", content = groupContext))
            recentMessages.forEach { msg ->
                when (msg.type) {
                    ChatMessageType.USER.name -> {
                        // 指挥官消息：name 用 ASCII "user"，content 开头加轻量标签
                        add(ChatRoleMessage(
                            role = "user",
                            name = "user",
                            content = "(指挥官) ${msg.content}"
                        ))
                    }
                    ChatMessageType.AI.name -> {
                        if (msg.shipName == member.jiuxinName) {
                            // 自己的历史回复：assistant 角色，无标签
                            add(ChatRoleMessage(role = "assistant", content = msg.content))
                        } else if (msg.shipName.isNotBlank()) {
                            // 其他舰娘的回复：name 用 ASCII hash，content 开头加 (舰娘名) 标签
                            val safeName = "ship_" + msg.shipName.hashCode().toString(16)
                            add(ChatRoleMessage(
                                role = "user",
                                name = safeName,
                                content = "(${msg.shipName}) ${msg.content}"
                            ))
                        }
                    }
                    ChatMessageType.STICKER.name -> {
                        val senderLabel = if (msg.shipName.isNotBlank()) "(${msg.shipName})" else "(指挥官)"
                        val safeName = if (msg.shipName.isNotBlank()) "ship_" + msg.shipName.hashCode().toString(16) else "user"
                        add(ChatRoleMessage(
                            role = "user",
                            name = safeName,
                            content = "$senderLabel [发送了表情包]"
                        ))
                    }
                }
            }
            if (isReaction) {
                // 互回模式：末尾追加系统指令（用户消息已在历史中，无需重复）
                add(ChatRoleMessage(
                    role = "user",
                    name = "sys",
                    content = "(系统) 请决定是否回应以上对话，无需回应请只输出 [SKIP]"
                ))
            }
            // 默认模式：不追加用户消息——用户消息已在 recentMessages 中（P0-1 修复）
        }

        Log.d(TAG, "Group API call for ${member.jiuxinName} (reaction=$isReaction): $url, model=$model")
        val request = ChatCompletionRequest(
            url = url,
            apiKey = key,
            model = model,
            messages = roleMessages
        )
        // 委托仓储层执行：含细粒度异常分类、自动重试、多格式响应解析
        return when (val result = apiRepository.chatCompletion(request, tag = member.jiuxinName)) {
            is JiuxinApiResult.Success -> result.content
            is JiuxinApiResult.Failure -> throw result.error
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
        // 同时清空 typingMembers，防止群聊打字指示器残留显示
        _chatState.update { it.copy(messages = emptyList(), typingMembers = emptyList()) }
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
        // 关键修复：不在此处单独异步持久化 truncated。
        // 原实现先异步写 truncated 到 DataStore，再调用 sendMessage 的 addMessage(userMessage)，
        // 两者产生交叉：若 truncated 的 DataStore 写入在 addMessage 之后派发，
        // collector 会收到不含 userMessage 的 truncated 列表 → state.messages != messages
        // → 执行 state.copy(messages = truncated, isLoading = false)
        // → userMessage 被丢弃 + TypingIndicator 动画消失。
        // 现在只同步更新内存状态，最终的 truncated + userMessage 由 sendMessage 的 addMessage
        // → saveCurrentSessionMessages 统一持久化，避免竞态。
        _chatState.update { it.copy(messages = truncated, isLoading = false, error = null) }

        // 发送新消息（复用 sendMessage 逻辑，addMessage 会持久化最终状态）
        sendMessage(newContent)
    }

    override fun onCleared() {
        // ViewModel 销毁时同步保存当前会话消息
        // 注意：ViewModel 现在绑定到 Activity 作用域，onCleared 仅在 Activity 销毁时触发
        // （不再是每次页面切换都触发），所以 runBlocking 不会引起 ANR
        val currentSessionId = _currentSessionId.value
        val currentMessages = _chatState.value.messages
        if (currentSessionId.isNotBlank() && currentMessages.isNotEmpty()) {
            runBlocking(NonCancellable) {
                settings.setAiSessionMessages(currentSessionId, currentMessages)
            }
        }
        stopVoice()
        super.onCleared()
    }

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
     * 竞态修复（P0-3）：使用 [saveMutex] 序列化所有 DataStore 写入。
     * 在锁内**重新读取**最新的 [_chatState.value].messages 快照，避免并发场景下
     * 多个 launch 各自捕获不同快照、后执行者覆盖先执行者导致消息丢失。
     *
     * 竞态修复（P0-旧）：接受 [sessionId] 参数，而非从 [_currentSessionId] 读取。
     * 调用方在切换会话前传入旧会话 ID，避免子协程读到已变更的 [_currentSessionId]
     * 导致消息保存到错误会话（这是"删除后切换再切回，消息复活"的根因）。
     *
     * 显式传入 messages 的场景（如 onCleared 中同步捕获）：跳过锁内重读，
     * 直接使用传入的快照，但仍受锁保护避免与其他写入交错。
     *
     * 持久化可靠性：使用 [NonCancellable] 上下文执行 DataStore 写入，
     * 确保即使 viewModelScope 被取消（如 Activity 销毁触发 onCleared），
     * 写入仍会完成。修复"聊天记录退出后丢失"问题。
     */
    fun saveCurrentSessionMessages(
        sessionId: String = _currentSessionId.value,
        updateTimestamp: Boolean = true,
        messages: List<ChatMessage>? = null
    ) {
        // 调用方未显式传入 messages 时，记录"需要在锁内重新读取"的意图；
        // 调用方显式传入 messages 时（如 onCleared），优先使用传入的快照
        val callerSnapshot = messages
        val sessionIdCopy = sessionId
        val updateTs = updateTimestamp
        viewModelScope.launch(NonCancellable) {
            if (sessionIdCopy.isBlank()) return@launch
            saveMutex.withLock {
                // 锁内重新读取最新消息快照：避免并发 save 之间相互覆盖
                // 显式传入的快照优先（onCleared 场景已经同步捕获，不可能被并发更新）
                val msgs = callerSnapshot ?: _chatState.value.messages
                val sessionsSnapshot = _sessions.value
                settings.setAiSessionMessages(sessionIdCopy, msgs)
                if (updateTs) {
                    val updated = sessionsSnapshot.map {
                        if (it.id == sessionIdCopy) it.copy(updatedAt = System.currentTimeMillis()) else it
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

        // 构造 OpenAI 兼容 messages 数组（system + history）
        // 注意：recentMessages 已包含最新用户消息（sendMessage 中 addMessage 在 callApi 之前），
        // 不再重复追加，避免用户消息在上下文中出现两次（与 callGroupApi 的 P0-1 修复保持一致）。
        val roleMessages = buildList {
            add(ChatRoleMessage(role = "system", content = prompt))
            recentMessages.forEach { msg ->
                val role = when (msg.type) {
                    ChatMessageType.USER.name -> "user"
                    ChatMessageType.AI.name -> "assistant"
                    else -> null
                }
                if (role != null) {
                    add(ChatRoleMessage(role = role, content = msg.content))
                }
            }
        }

        Log.d(TAG, "Calling API: $url, model=$model, history=${recentMessages.size} messages")

        val request = ChatCompletionRequest(
            url = url,
            apiKey = key,
            model = model,
            messages = roleMessages
        )

        // 委托仓储层执行：含细粒度异常分类、自动重试、多格式响应解析
        return when (val result = apiRepository.chatCompletion(request)) {
            is JiuxinApiResult.Success -> result.content
            is JiuxinApiResult.Failure -> throw result.error
        }
    }

    /**
     * @deprecated 保留向后兼容，内部已委托 [JiuxinResponseParser]。
     * 新代码应直接使用 [JiuxinApiRepository.chatCompletion]。
     */
    private fun parseChatCompletionResponse(responseBody: String): String {
        return when (val result = com.azurlane.blyy.data.repository.JiuxinResponseParser.parseChatCompletion(responseBody)) {
            is JiuxinApiResult.Success -> result.content
            is JiuxinApiResult.Failure -> throw result.error
        }
    }

    /**
     * @deprecated 保留向后兼容，内部已委托 [JiuxinResponseParser]。
     */
    private fun parseErrorMessage(body: String): String =
        com.azurlane.blyy.data.repository.JiuxinResponseParser.parseErrorMessage(body)

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

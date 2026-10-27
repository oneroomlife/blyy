package com.azurlane.blyy.viewmodel

import android.util.Log
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import androidx.media3.common.MediaItem
import androidx.media3.common.Player
import com.azurlane.blyy.data.local.PlayerSettingsDataStore
import com.azurlane.blyy.data.model.VoiceLanguage
import com.azurlane.blyy.data.model.VoiceLine
import com.azurlane.blyy.domain.GetVoicesUseCase
import com.azurlane.blyy.service.PlaybackServiceConnection
import com.azurlane.blyy.service.whenReady
import com.azurlane.blyy.util.CacheManager
import com.azurlane.blyy.util.CacheNamespaces
import com.azurlane.blyy.util.Live2dLibrary
import com.azurlane.blyy.util.Live2dModelInfo
import com.azurlane.blyy.util.Live2dNameResolver
import com.azurlane.blyy.util.Live2dSkinNameRegistry
import com.azurlane.blyy.util.Live2dWebViewClient
import com.azurlane.blyy.util.SkinVoiceIndex
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import javax.inject.Inject

/**
 * Live2D 查看器状态：按导航参数 modelId 载入模型元信息，
 * 接收 WebView 渲染端回传的缩略图并持久化；
 * 点击皮肤互动时按动作组播放舰娘对应语音（复用 PlaybackService 单发模式）。
 */
@androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)
@HiltViewModel
class Live2dViewerViewModel @Inject constructor(
    savedStateHandle: SavedStateHandle,
    private val library: Live2dLibrary,
    private val nameResolver: Live2dNameResolver,
    private val skinNameRegistry: Live2dSkinNameRegistry,
    private val getVoicesUseCase: GetVoicesUseCase,
    private val playbackServiceConnection: PlaybackServiceConnection,
    private val settingsDataStore: PlayerSettingsDataStore
) : ViewModel() {

    val modelId: String = savedStateHandle.get<String>("modelId").orEmpty()

    /** 库界面解析好的中文显示名（导航参数，仅作初始回退） */
    val modelName: String = savedStateHandle.get<String>("name").orEmpty()

    /**
     * 实时显示名：皮肤名注册表就绪后把「换装N」升级为真实皮肤名
     * （如 埃尔德里奇 · 换装4 → 埃尔德里奇 · 正月的牵手）
     */
    private val _displayName = MutableStateFlow(modelName.ifBlank { modelId })
    val displayName: StateFlow<String> = _displayName.asStateFlow()

    data class State(
        val loading: Boolean = true,
        val info: Live2dModelInfo? = null,
        /** 模型库层错误（模型不存在/损坏） */
        val error: String? = null,
        /** 互动语音开关（持久化） */
        val voiceEnabled: Boolean = true,
        /** 语音台词气泡开关（持久化，独立于语音开关） */
        val bubbleEnabled: Boolean = true,
        /** 语音台词加载中（与模型加载 loading 分离，避免互相阻塞） */
        val voiceLoading: Boolean = false,
        /** 语音台词是否已加载完成 */
        val voiceLoaded: Boolean = false,
        /** 可播放的台词（空 = 该舰无语音数据，功能静默降级） */
        val voiceLines: List<VoiceLine> = emptyList(),
        /** 当前展示的台词气泡（null = 隐藏） */
        val voiceBubble: VoiceBubble? = null,
        /** 陪伴模式开关（运行时态：每次进查看器默认关闭，不跨会话记忆） */
        val companionEnabled: Boolean = false,
        /** 陪伴模式：随机动作触发间隔（秒，持久化） */
        val companionIntervalSec: Int = 180,
        /** 陪伴模式：右下角显示当前时间日期（持久化） */
        val companionShowClock: Boolean = true,
        /** 陪伴模式：右下角显示设备电量（持久化） */
        val companionShowBattery: Boolean = true,
        /** 陀螺仪注视/视差开关（持久化，默认开启；无传感器设备渲染端自动静默） */
        val gyroEnabled: Boolean = true,
        /** 陀螺仪灵敏度百分比（40..220，持久化） */
        val gyroGainPercent: Int = 100
    )

    /** 语音台词气泡：随互动语音同步显示，超时/点按/关闭后消失 */
    data class VoiceBubble(
        val dialogue: String,
        val scene: String,
        /** 显示时间戳：同一台词重复触发时也能重放入场动画 */
        val shownAt: Long = System.currentTimeMillis()
    )

    private val _state = MutableStateFlow(State())
    val state: StateFlow<State> = _state.asStateFlow()

    /** 上一条已播放台词，随机时避免连续重复 */
    private var lastPlayedLine: VoiceLine? = null

    /** 模型皮肤序号（目录尾部 _N；null = 默认装扮） */
    private val skinIndex: Int? = modelId.substringAfterLast('_').toIntOrNull()

    /** 台词按皮肤名的分组（键为 wiki 表名） */
    private var linesBySkin: Map<String, List<VoiceLine>> = emptyMap()

    /** 皮肤表分类（默认/换装序列/誓约/改造），规则见 [SkinVoiceIndex] */
    private var skinTables: SkinVoiceIndex.SkinTables = SkinVoiceIndex.classify(emptyList())

    init {
        load()
        viewModelScope.launch {
            settingsDataStore.live2dVoiceEnabled.collect { enabled ->
                _state.update { it.copy(voiceEnabled = enabled) }
                if (enabled) ensureVoicesLoaded() else dismissVoiceBubble()
            }
        }
        viewModelScope.launch {
            settingsDataStore.live2dBubbleEnabled.collect { enabled ->
                _state.update { it.copy(bubbleEnabled = enabled) }
                if (!enabled) dismissVoiceBubble()
            }
        }
        // 陪伴模式三选项（持久化）：进页即恢复上次配置，开关本身是运行时态
        viewModelScope.launch {
            combine(
                settingsDataStore.live2dCompanionIntervalSec,
                settingsDataStore.live2dCompanionShowClock,
                settingsDataStore.live2dCompanionShowBattery
            ) { interval, clock, battery ->
                Triple(interval, clock, battery)
            }.collect { (interval, clock, battery) ->
                _state.update {
                    it.copy(
                        companionIntervalSec = interval,
                        companionShowClock = clock,
                        companionShowBattery = battery
                    )
                }
            }
        }
        // 陀螺仪注视/视差两选项（持久化）：进页即恢复，变化即时下发渲染端
        viewModelScope.launch {
            combine(
                settingsDataStore.live2dGyroEnabled,
                settingsDataStore.live2dGyroGainPercent
            ) { enabled, gain ->
                enabled to gain
            }.collect { (enabled, gain) ->
                _state.update { it.copy(gyroEnabled = enabled, gyroGainPercent = gain) }
            }
        }
        // 舰名反查就绪后补拉台词（查台词用的舰名此时才可解析）
        viewModelScope.launch {
            nameResolver.pinyinMap.collect {
                ensureVoicesLoaded()
            }
        }
        // 标题不依赖语音开关：注册表就绪后即把「换装N」升级为真实皮肤名
        viewModelScope.launch {
            skinNameRegistry.skinSequences.collect {
                _displayName.value = computeDisplayName()
            }
        }
        // 首次进入也拉一次（幂等，走 SHIP_VOICES 缓存），保证标题能升级
        viewModelScope.launch {
            _displayName.value = computeDisplayName()
        }
    }

    /** 标题显示名：基础舰名 + 真实皮肤名（换装序列第 N-1 位） */
    private fun computeDisplayName(): String {
        val skinIndex = modelId.substringAfterLast('_').toIntOrNull()
        val base = currentShipName().ifBlank {
            modelName.substringBefore(" · ").ifBlank { modelId }
        }
        skinNameRegistry.request(base)
        val realSkin = skinIndex?.takeIf { it >= 2 }?.let { skinNameRegistry.skinNameFor(base, it) }
        return when {
            skinIndex == null || skinIndex < 2 -> base
            realSkin != null -> "$base · $realSkin"
            else -> "$base · 换装$skinIndex"
        }
    }

    fun load() {
        viewModelScope.launch {
            _state.update { it.copy(loading = true, error = null) }
            val info = try {
                library.findById(modelId)
            } catch (e: Exception) {
                null
            }
            _state.update {
                if (info == null) {
                    it.copy(loading = false, info = null, error = "模型不存在或已损坏（缺少 moc3/model3.json）")
                } else {
                    it.copy(loading = false, info = info, error = null)
                }
            }
        }
    }

    /** 渲染端截图回传 → 持久化到 .thumbs/<id>.jpg */
    fun saveThumb(bytes: ByteArray) {
        viewModelScope.launch {
            library.saveThumb(modelId, bytes)
            _state.update { s ->
                s.info?.let { info ->
                    s.copy(info = info.copy(thumbFile = library.thumbFileFor(modelId)))
                } ?: s
            }
        }
    }

    /** 构建 WebView 拦截器（assets 运行时 + 本地模型文件的虚拟域映射） */
    fun createWebViewClient(
        context: android.content.Context,
        infoProvider: () -> Live2dModelInfo?
    ): android.webkit.WebViewClient = Live2dWebViewClient(context.applicationContext, library, infoProvider)

    // ---------- 互动语音 ----------

    /** 切换互动语音开关（持久化；开启时顺带确保台词已加载） */
    fun setVoiceEnabled(enabled: Boolean) {
        viewModelScope.launch {
            settingsDataStore.setLive2dVoiceEnabled(enabled)
        }
    }

    /** 切换台词气泡显示开关（持久化；关闭时立即收起当前气泡） */
    fun setBubbleEnabled(enabled: Boolean) {
        viewModelScope.launch {
            settingsDataStore.setLive2dBubbleEnabled(enabled)
        }
    }

    // ---------- 陪伴模式 ----------

    /** 陪伴模式开关（运行时态：调度在渲染端 ticker 上，退后台自动暂停） */
    fun setCompanionEnabled(enabled: Boolean) {
        _state.update { it.copy(companionEnabled = enabled) }
    }

    /**
     * 陪伴模式设置落盘（间隔/时钟/电量三选项一次成组原子写入）。
     * 调用方必须传全量三项（读取自同一快照），避免单项更新与 DataStore 回流竞态互相覆盖。
     */
    fun updateCompanionConfig(intervalSec: Int, showClock: Boolean, showBattery: Boolean) {
        viewModelScope.launch {
            settingsDataStore.setLive2dCompanionConfig(intervalSec, showClock, showBattery)
        }
    }

    /**
     * 陀螺仪注视/视差设置落盘（开关 + 灵敏度成组原子写入）。
     * 同样必须传全量两项，理由同 [updateCompanionConfig]。
     */
    fun updateGyroConfig(enabled: Boolean, gainPercent: Int) {
        viewModelScope.launch {
            settingsDataStore.setLive2dGyroConfig(enabled, gainPercent)
        }
    }

    /** 点按气泡立即收起 */
    fun dismissVoiceBubble() {
        bubbleHideJob?.cancel()
        bubbleHideJob = null
        if (_state.value.voiceBubble != null) {
            _state.update { it.copy(voiceBubble = null) }
        }
    }

    /** 气泡自动消失任务（新气泡出现时顶替旧任务） */
    private var bubbleHideJob: Job? = null

    /** 语音触发 → 同步弹出台词气泡；显示时长随文本长度伸缩，超时自动收起 */
    private fun showVoiceBubble(line: VoiceLine) {
        if (!_state.value.bubbleEnabled) return
        bubbleHideJob?.cancel()
        val bubble = VoiceBubble(
            dialogue = line.dialogue.replace("\n", " ").replace("\r", "").trim(),
            scene = line.scene
        )
        _state.update { it.copy(voiceBubble = bubble) }
        bubbleHideJob = viewModelScope.launch {
            // 基础 3.2s + 每字 85ms（照顾长句阅读），上限 8.5s
            delay((3200 + bubble.dialogue.length * 85L).coerceAtMost(8500))
            _state.update { if (it.voiceBubble === bubble) it.copy(voiceBubble = null) else it }
        }
    }

    /**
     * 点击皮肤/播放动作时的语音入口：按动作组映射场景关键词挑一条台词播放。
     * 开关关闭、该动作组无对应场景（如待机）时静默跳过；
     * 台词未就绪时触发加载，本次静默（后续点击即有语音）。
     */
    fun playInteractionVoice(motionGroup: String?) {
        val st = _state.value
        if (!st.voiceEnabled) return
        if (!st.voiceLoaded) {
            ensureVoicesLoaded()
            return
        }
        val line = pickVoice(st.voiceLines, motionGroup) ?: return
        // 气泡与语音同步弹出（台词选择成功即显示，不等待音频就绪）
        showVoiceBubble(line)
        viewModelScope.launch {
            val language = settingsDataStore.voiceLanguage.first()
            playLine(line, language)
        }
    }

    /** 语音查询用的基础中文舰名（调用时即时反查，规避舰船库异步加载竞态） */
    private fun currentShipName(): String = nameResolver.baseShipName(modelId).orEmpty()

    /** 上次语音加载失败时间：冷却窗口内不重试（避免断网时每次点击都打 wiki） */
    private var lastVoiceLoadFailedAt = 0L

    /** 后台加载台词（wiki 数据，30 分钟缓存；失败静默并允许冷却后重试） */
    private fun ensureVoicesLoaded() {
        if (_state.value.voiceLoaded || _state.value.voiceLoading) return
        if (System.currentTimeMillis() - lastVoiceLoadFailedAt < VOICE_RETRY_COOLDOWN_MS) return
        val ship = currentShipName()
        if (ship.isBlank()) return
        _state.update { it.copy(voiceLoading = true) }
        viewModelScope.launch {
            val result = runCatching {
                CacheManager.getOrPutSuspend(CacheNamespaces.SHIP_VOICES, ship) {
                    val (voices, _, _) = getVoicesUseCase(ship)
                    voices
                }
            }
            if (result.isFailure) {
                // 加载失败 ≠ 无语音：不置 voiceLoaded，60 秒冷却后下次触发自动重试
                Log.w(TAG, "加载 $ship 语音失败：${result.exceptionOrNull()?.message}")
                lastVoiceLoadFailedAt = System.currentTimeMillis()
                _state.update { it.copy(voiceLoading = false) }
                return@launch
            }
            val lines = result.getOrDefault(emptyList())
            // 按皮肤分组 + 分类：首表/未命名表 = 默认装扮，换装N = 第 N 张实名表，
            // 誓约/改造表各自单独对应（规则详见 SkinVoiceIndex）
            linesBySkin = lines.groupBy { it.skinName }
            skinTables = SkinVoiceIndex.classify(lines.map { it.skinName })
            Log.d(TAG, "语音台词就绪: $ship, ${lines.size} 条, 换装序列: ${skinTables.skinSequence} (目标序号: $skinIndex)")
            _state.update {
                it.copy(voiceLoading = false, voiceLoaded = true, voiceLines = lines)
            }
        }
    }

    /**
     * 动作组 → 台词场景关键词（wiki 表头原文做 contains 匹配）。
     * 无映射的动作组（如待机循环）返回 null = 不配音。
     */
    private val GROUP_SCENE_KEYWORDS = mapOf(
        "touch_head" to listOf("摸头"),
        // 资源包直读产物里的拖拽反应（touch_dragN）与触摸待机（touch_idleN）
        "touch_drag" to listOf("普通触摸", "触摸"),
        "touch_idle" to listOf("普通触摸", "触摸"),
        "touch_special" to listOf("特殊触摸"),
        "touch_body" to listOf("普通触摸", "触摸"),
        "main_1" to listOf("普通触摸", "触摸"),
        "main_2" to listOf("普通触摸", "触摸"),
        "main_3" to listOf("普通触摸", "触摸"),
        "effect" to listOf("特殊触摸", "触摸"),
        "login" to listOf("登录"),
        "wedding" to listOf("誓约"),
        "home" to listOf("主界面", "大厅"),
        "complete" to listOf("完成"),
        "mail" to listOf("邮件"),
        "mission" to listOf("任务提醒", "任务"),
        "mission_complete" to listOf("任务完成", "完成")
    )

    private val FALLBACK_KEYWORDS = listOf("普通触摸", "触摸")

    /**
     * 皮肤 × 场景双维选台词：
     * 优先级 = 目标皮肤(精确关键词) → 默认装扮(精确关键词) → 全部(精确关键词)
     * → 目标皮肤(触摸系兜底) → 默认装扮(兜底) → 全部(兜底)。
     * 目标皮肤 = 模型目录 _N 对应换装序列第 N 位；誓约动作优先走【誓约】表。
     */
    private fun pickVoice(lines: List<VoiceLine>, group: String?): VoiceLine? {
        if (lines.isEmpty()) return null
        val keywords = group?.let { GROUP_SCENE_KEYWORDS[it] } ?: return null
        // 资源目录 _N：_1=通常，_2 起对应换装1、换装2…（换装k = 换装序列第 k-1 位）
        val targetSkin = skinIndex?.let { idx ->
            if (idx >= 2) skinTables.skinSequence.getOrNull(idx - 2) else null
        }
        val oathSkin = if (group == "wedding") {
            skinTables.oathNames.firstOrNull()
        } else null

        val pools = buildList {
            oathSkin?.let { add(linesBySkin[it].orEmpty()) }
            targetSkin?.let { add(linesBySkin[it].orEmpty()) }
            add(lines.filter { it.skinName in skinTables.defaultNames })
            add(lines)
        }
        for (pool in pools) {
            pickByKeywords(pool, keywords)?.let { return it }
        }
        for (pool in pools) {
            pickByKeywords(pool, FALLBACK_KEYWORDS)?.let { return it }
        }
        return null
    }

    private fun pickByKeywords(pool: List<VoiceLine>, keywords: List<String>): VoiceLine? {
        val valid = pool.filter { it.audioUrl.isNotBlank() }
        if (valid.isEmpty()) return null
        val matched = keywords.firstNotNullOfOrNull { k ->
            valid.filter { it.scene.contains(k) }.takeIf { it.isNotEmpty() }
        } ?: return null
        val candidate = matched.random()
        val picked = if (candidate == lastPlayedLine && matched.size > 1) {
            matched.filter { it != candidate }.random()
        } else {
            candidate
        }
        lastPlayedLine = picked
        return picked
    }

    /** 经共享 PlaybackService 播放单条语音（setMediaItem 顶替上一条，无需手动停止） */
    private fun playLine(line: VoiceLine, language: VoiceLanguage) {
        val url = line.getActiveAudioUrl(language)
        if (url.isBlank()) return
        Log.d(TAG, "播放互动语音: ${line.scene} · ${line.skinName}")
        val cleanDialogue = line.dialogue.replace("\n", " ").replace("\r", "").trim()
        val metadata = androidx.media3.common.MediaMetadata.Builder()
            .setTitle(cleanDialogue)
            .setArtist(currentShipName().ifEmpty { "Live2D" })
            .setAlbumTitle(line.scene)
            .build()
        val item = MediaItem.Builder()
            .setMediaId(url)
            .setUri(url)
            .setMediaMetadata(metadata)
            .build()
        playbackServiceConnection.mediaController.whenReady { player ->
            player.setMediaItem(item)
            player.repeatMode = Player.REPEAT_MODE_OFF
            player.prepare()
            player.play()
        }
    }

    companion object {
        private const val TAG = "Live2dViewerVM"

        /** 语音加载失败后的重试冷却（毫秒） */
        private const val VOICE_RETRY_COOLDOWN_MS = 60_000L
    }
}

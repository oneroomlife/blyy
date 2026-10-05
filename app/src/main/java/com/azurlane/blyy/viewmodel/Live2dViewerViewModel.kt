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
import com.azurlane.blyy.util.Live2dWebViewClient
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
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
    private val getVoicesUseCase: GetVoicesUseCase,
    private val playbackServiceConnection: PlaybackServiceConnection,
    private val settingsDataStore: PlayerSettingsDataStore
) : ViewModel() {

    val modelId: String = savedStateHandle.get<String>("modelId").orEmpty()

    /** 库界面解析好的中文显示名（导航参数，仅展示用） */
    val modelName: String = savedStateHandle.get<String>("name").orEmpty()

    data class State(
        val loading: Boolean = true,
        val info: Live2dModelInfo? = null,
        /** 模型库层错误（模型不存在/损坏） */
        val error: String? = null,
        /** 互动语音开关（持久化） */
        val voiceEnabled: Boolean = true,
        /** 语音台词加载中（与模型加载 loading 分离，避免互相阻塞） */
        val voiceLoading: Boolean = false,
        /** 语音台词是否已加载完成 */
        val voiceLoaded: Boolean = false,
        /** 可播放的台词（空 = 该舰无语音数据，功能静默降级） */
        val voiceLines: List<VoiceLine> = emptyList()
    )

    private val _state = MutableStateFlow(State())
    val state: StateFlow<State> = _state.asStateFlow()

    /** 上一条已播放台词，随机时避免连续重复 */
    private var lastPlayedLine: VoiceLine? = null

    init {
        load()
        viewModelScope.launch {
            settingsDataStore.live2dVoiceEnabled.collect { enabled ->
                _state.update { it.copy(voiceEnabled = enabled) }
                if (enabled) ensureVoicesLoaded()
            }
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
        viewModelScope.launch {
            val language = settingsDataStore.voiceLanguage.first()
            playLine(line, language)
        }
    }

    /** 语音查询用的基础中文舰名（调用时即时反查，规避舰船库异步加载竞态） */
    private fun currentShipName(): String = nameResolver.baseShipName(modelId).orEmpty()

    /** 后台加载台词（wiki 数据，30 分钟缓存；失败静默，仅影响语音可用性） */
    private fun ensureVoicesLoaded() {
        if (_state.value.voiceLoaded || _state.value.voiceLoading) return
        val ship = currentShipName()
        if (ship.isBlank()) return
        _state.update { it.copy(voiceLoading = true) }
        viewModelScope.launch {
            val lines = runCatching {
                CacheManager.getOrPutSuspend(CacheNamespaces.SHIP_VOICES, ship) {
                    val (voices, _, _) = getVoicesUseCase(ship)
                    voices
                }
            }.onFailure { Log.w(TAG, "加载 $ship 语音失败：${it.message}") }.getOrDefault(emptyList())
            Log.d(TAG, "语音台词就绪: $ship, ${lines.size} 条")
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

    private fun pickVoice(lines: List<VoiceLine>, group: String?): VoiceLine? {
        if (lines.isEmpty()) return null
        val keywords = group?.let { GROUP_SCENE_KEYWORDS[it] } ?: return null
        // 依次尝试关键词（"特殊触摸"先于"触摸"，避免子串误匹配）；无效 URL 的台词剔除
        val matched = keywords.firstNotNullOfOrNull { k ->
            lines.filter { it.scene.contains(k) && it.audioUrl.isNotBlank() }
                .takeIf { it.isNotEmpty() }
        }.orEmpty()
        val pool = matched.ifEmpty {
            lines.filter { l -> FALLBACK_KEYWORDS.any { it in l.scene } && l.audioUrl.isNotBlank() }
        }
        if (pool.isEmpty()) return null
        val candidate = pool.random()
        val picked = if (candidate == lastPlayedLine && pool.size > 1) {
            pool.filter { it != candidate }.random()
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
    }
}

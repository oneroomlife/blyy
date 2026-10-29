package com.azurlane.blyy.viewmodel

import android.content.Context
import android.util.Log
import androidx.annotation.OptIn
import androidx.core.net.toUri
import androidx.media3.common.MediaItem
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import com.azurlane.blyy.SecretaryOverlayService
import com.azurlane.blyy.data.local.PlayerSettingsDataStore
import com.azurlane.blyy.data.local.ShipDao
import com.azurlane.blyy.data.model.Ship
import com.azurlane.blyy.data.model.VoiceLanguage
import com.azurlane.blyy.data.model.VoiceLine
import com.azurlane.blyy.domain.GetVoicesUseCase
import com.azurlane.blyy.domain.SelectSecretaryUseCase
import com.azurlane.blyy.service.PlaybackServiceConnection
import androidx.compose.runtime.snapshotFlow
import com.azurlane.blyy.util.LocalSdResolver
import com.azurlane.blyy.util.PinyinHelper
import com.azurlane.blyy.util.SDResourceManager
import com.google.common.util.concurrent.MoreExecutors
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import javax.inject.Inject
import javax.inject.Singleton

data class SecretaryManagerState(
    val shipName: String = "",
    val figureUrl: String = "",
    val avatarUrl: String = "",
    val voices: List<VoiceLine> = emptyList(),
    val isLoadingVoices: Boolean = false,
    val autoPlayEnabled: Boolean = false,
    val autoPlayIntervalMinutes: Int = 5,
    val voiceLanguage: VoiceLanguage = VoiceLanguage.CN,
    /** 当前播放语音的台词文本（null 表示无台词显示） */
    val currentDialogue: String? = null,
    /** 台词弹窗开关（true=显示，false=隐藏） */
    val dialogueEnabled: Boolean = true,
    /** SD 小人皮肤名（"" / "default" 用默认皮肤，"gai" / "skin2" 等为其他皮肤） */
    val sdSkin: String = "",
    /** SD 小人显示缩放倍率（1.0 = 默认大小，0.5 = 半尺寸，1.5 = 放大 50%） */
    val sdScale: Float = 1.0f,
    /** 自定义 SD 资源 ID（空=跟随舰名匹配，非空=直接按 ID 加载该资源，解除舰名限制） */
    val sdResourceId: String = "",
    /**
     * 当前秘书舰是否**能解析出 SD 资源**（pin 的资源，或按舰名命中的本地/游戏导入资源）。
     *
     * 悬浮窗与主界面的显示门槛过去只看 `figureUrl / webChibiUrl / sdResourceId`，
     * 于是「只有游戏导入的 SD、没有设置 pin、也没有网络立绘」时整个小人不显示 ——
     * 明明资源就在本地。现在这个标志由主线程外算好，作为第四条显示依据。
     */
    val sdAvailable: Boolean = false,
    /** 悬浮窗触摸穿透（true=触摸事件穿透到下层应用，false=正常响应触摸交互） */
    val overlayTouchPassthrough: Boolean = false,
    /**
     * wiki 网页端 Q版小人（SD 静态图）URL。
     *
     * 本地 SD 资源未匹配到时的回退显示图：
     * - 优先取「查看立绘」中用户选择的 SD 小人（savedFigure）
     * - 未选择时取 wiki 页面默认小人
     *
     * 已持久化到 DataStore：冷启动零网络即可恢复悬浮小人，
     * 后台抓取仅在恢复值为空时作补齐兜底。
     */
    val webChibiUrl: String = ""
)

@Singleton
// 注入的 PlaybackServiceConnection 标注 @UnstableApi（media3 非稳定 API），此处显式 opt-in，
// 不向上游（SecretaryManager 的注入方）传播不稳定性
@OptIn(UnstableApi::class)
class SecretaryManager @Inject constructor(
    @ApplicationContext private val appContext: Context,
    private val settingsDataStore: PlayerSettingsDataStore,
    private val selectSecretaryUseCase: SelectSecretaryUseCase,
    private val getVoicesUseCase: GetVoicesUseCase,
    private val playbackServiceConnection: PlaybackServiceConnection,
    private val shipDao: ShipDao
) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)

    /**
     * 单一状态容器：所有字段经 [updateState] 的 `copy()` 更新。
     *
     * 替代旧的"16 个 MutableStateFlow + combine + args 下标转型"方案：
     * - 新增字段只改 data class，不再有 args 顺序错位风险
     * - 无 combine 跨流订阅开销，也无冷启动组合延迟
     */
    private val _state = MutableStateFlow(SecretaryManagerState())
    val state: StateFlow<SecretaryManagerState> = _state.asStateFlow()

    /** 状态更新唯一入口（主线程约束与旧方案一致：scope = Main，collector 也在 Main） */
    private inline fun updateState(transform: (SecretaryManagerState) -> SecretaryManagerState) {
        _state.update(transform)
    }

    /**
     * voices 归属舰名：voices 列表当前对应的舰娘。
     *
     * 【P0 竞态修复】此前 loadVoicesInBackground 中 voices 写在舰名守卫之外：
     * 快速切换舰娘时，旧舰的在途加载完成后会直接覆盖新舰的 voices，
     * 表现为"新秘书舰播旧秘书舰的语音/台词"。现在 voices 的写入以归属舰名为令牌，
     * 只在当前舰名仍等于加载目标时落盘。
     */
    private var voicesOwnerShip: String = ""

    private var autoPlayJob: Job? = null

    /** 「SD 是否可渲染」重算任务（防抖：资源修订号/舰名变化时合并） */
    private var sdAvailabilityJob: Job? = null
    private var dialogueClearJob: Job? = null

    // ── MediaController 解析缓存 ──
    // 【连发/监听器泄漏修复】此前每次播放都往 ListenableFuture 上 addListener：
    // 连接未就绪期内的多次播放会在连接建立瞬间连发执行；且监听器对象随调用无界累积。
    // 现在只挂一个监听器解析 Future 一次，controller 就绪后直接命中缓存执行。
    @Volatile private var cachedController: Player? = null
    private val pendingControllerCommands = ArrayDeque<(Player) -> Unit>()
    private var controllerListenerAttached = false

    init {
        resolveControllerWhenReady()

        // SD 资源索引变化（游戏导入完成 / 删除 / 改名）后重算「是否可渲染」，
        // 否则刚导入的小人要等下一次秘书舰切换才会出现。
        scope.launch {
            snapshotFlow { LocalSdResolver.revision.value }.collect { refreshSdAvailability() }
        }

        scope.launch {
            settingsDataStore.secretaryShipName.collect { name ->
                updateState { it.copy(shipName = name) }
                refreshSdAvailability()
                // 冷启动恢复：语音缺失（旧版本升级/数据异常）时后台补载，
                // 内部会顺带补齐空缺的立绘与 webChibiUrl（见守卫逻辑）
                if (name.isNotEmpty() && _state.value.voices.isEmpty()) {
                    loadVoicesInBackground(name)
                }
            }
        }
        scope.launch {
            settingsDataStore.secretaryFigureUrl.collect { url -> updateState { it.copy(figureUrl = url) } }
        }
        scope.launch {
            settingsDataStore.secretaryAvatarUrl.collect { url -> updateState { it.copy(avatarUrl = url) } }
        }
        scope.launch {
            settingsDataStore.secretaryWebChibiUrl.collect { url -> updateState { it.copy(webChibiUrl = url) } }
        }
        scope.launch {
            // distinctUntilChanged：safeData 在任意 key 的 edit 后都会重新发射整包
            // Preferences（包括聊天消息保存等其他功能的高频写入）。不加去重时，
            // 每次写入都会走到 startAutoPlay → stop+restart，自动播放的 delay 定时器
            // 被频繁重置，表现为什么时候都不会触发自动播放。
            settingsDataStore.secretaryAutoPlayEnabled.distinctUntilChanged().collect { enabled ->
                updateState { it.copy(autoPlayEnabled = enabled) }
                if (enabled && _state.value.shipName.isNotEmpty()) {
                    if (autoPlayJob?.isActive != true) startAutoPlay()
                } else {
                    stopAutoPlay()
                }
            }
        }
        scope.launch {
            settingsDataStore.secretaryAutoPlayIntervalMinutes.collect { minutes ->
                updateState { it.copy(autoPlayIntervalMinutes = minutes) }
            }
        }
        scope.launch {
            settingsDataStore.voiceLanguage.collect { lang ->
                updateState { it.copy(voiceLanguage = lang) }
            }
        }
        scope.launch {
            settingsDataStore.secretaryDialogueEnabled.collect { enabled ->
                updateState { it.copy(dialogueEnabled = enabled) }
                // 开关关闭时立即清除残留台词
                if (!enabled) {
                    dialogueClearJob?.cancel()
                    updateState { it.copy(currentDialogue = null) }
                }
            }
        }
        scope.launch {
            settingsDataStore.secretarySdSkin.collect { skin ->
                updateState { it.copy(sdSkin = skin) }
                refreshSdAvailability()
            }
        }
        scope.launch {
            settingsDataStore.secretarySdScale.collect { scale ->
                updateState { it.copy(sdScale = scale) }
            }
        }
        scope.launch {
            settingsDataStore.secretarySdResourceId.collect { id ->
                updateState { it.copy(sdResourceId = id) }
                // 自愈：pin 的可能是「游戏资源导入」解出的 `game/<id>`。
                // 用户在导入页删除/改名该资源后，pin 就成了悬空引用 —— 悬浮窗与主界面
                // 会静默不渲染（看起来像「小人不显示」，但设置里仍显示"已选择"）。
                refreshSdAvailability()
                if (id.isNotBlank()) healDanglingSdResource(id)
            }
        }
        scope.launch {
            settingsDataStore.secretaryOverlayTouchPassthrough.collect { enabled ->
                updateState { it.copy(overlayTouchPassthrough = enabled) }
            }
        }
    }

    /**
     * 挂一次 Future 监听器解析 MediaController：完成后写入 [cachedController]
     * 并按序消费排队命令。多场景（init + onController 未就绪路径）重复调用也只挂一次。
     */
    private fun resolveControllerWhenReady() {
        if (controllerListenerAttached) return
        controllerListenerAttached = true
        val future = playbackServiceConnection.mediaController
        future.addListener({
            try {
                val controller = future.get()
                val toRun: List<(Player) -> Unit>
                synchronized(pendingControllerCommands) {
                    cachedController = controller
                    toRun = pendingControllerCommands.toList()
                    pendingControllerCommands.clear()
                }
                for (cmd in toRun) cmd(controller)
            } catch (e: Exception) {
                Log.e(TAG, "MediaController resolve failed", e)
            }
        }, MoreExecutors.directExecutor())
    }

    /**
     * 系统悬浮窗运行状态（替代旧的 MainActivity.companion 静态字段事件总线）。
     *
     * Service onCreate/onDestroy 写入，设置页开关与应用内小人互斥逻辑读取，
     * 三方统一从本单例读写；进程重启后初值取服务的静态运行标记。
     */
    private val _overlayRunning = MutableStateFlow(SecretaryOverlayService.isServiceRunning())
    val overlayRunning: StateFlow<Boolean> = _overlayRunning.asStateFlow()

    fun updateOverlayRunning(running: Boolean) {
        _overlayRunning.value = running
    }

    suspend fun selectRandom(): Ship? {
        val startTime = System.currentTimeMillis()
        val ship = selectSecretaryUseCase.selectRandomSecretary() ?: return null
        applyShipSelection(ship)
        Log.d(TAG, "selectRandom: 耗时 ${System.currentTimeMillis() - startTime}ms")
        return ship
    }

    suspend fun selectShip(ship: Ship, figureUrl: String) {
        applyShipSelection(ship)
    }

    /**
     * 选舰核心路径（selectShip 与随机翻牌 selectRandom 的去重提取）。
     *
     * 统一图源优先级：历史保存立绘(savedFigure) > 皮肤立绘 > 默认立绘 > 头像；
     * webChibiUrl 跟随 savedFigure，未选择时用 wiki 默认小人。
     */
    private suspend fun applyShipSelection(ship: Ship) {
        val startTime = System.currentTimeMillis()
        Log.d(TAG, "applyShipSelection: 开始选择 ${ship.name}")

        // 【切舰即时更新】换舰时旧舰的立绘/静态小人图源必须立即失效——否则 wiki 抓取
        // 完成前（弱网可达数秒）小人/立绘一直显示旧舰，表现为"切换后不更新"。
        // figureUrl 先降级为新舰头像做占位，抓取完成后按优先级链升级。
        val shipChanged = _state.value.shipName != ship.name

        // 【SD 钉选重估】自定义 SD 资源钉的是选它时的那艘舰；换舰后若该资源不属于
        // 新舰（拼音反查不一致），解除钉选让舰名匹配置换新舰资源——否则小人会一直
        // 停留在旧舰的静态 SD 资源上，表现为"SD 静态小人切换后不更新"
        val pinnedSdId = _state.value.sdResourceId
        var clearSdPin = false
        if (shipChanged && pinnedSdId.isNotBlank()) {
            val pinnedOwner = resolveShipNameByPinyin(pinnedSdId)
            if (pinnedOwner != ship.name) {
                clearSdPin = true
                Log.d(TAG, "applyShipSelection: 解除 SD 钉选 '$pinnedSdId'（不属于新舰 ${ship.name}）")
            }
        }

        updateState {
            // 立即更新舰名/头像让 UI 先反馈"正在加载"（语音/图源随后就位）；
            // 同步作废旧舰语音归属，防止在途旧舰加载结果落盘
            it.copy(
                shipName = ship.name,
                avatarUrl = ship.avatarUrl,
                figureUrl = if (shipChanged) ship.avatarUrl else it.figureUrl,
                webChibiUrl = if (shipChanged) "" else it.webChibiUrl,
                voices = emptyList(),
                isLoadingVoices = true,
                sdResourceId = if (clearSdPin) "" else it.sdResourceId,
                sdSkin = if (clearSdPin) "" else it.sdSkin
            )
        }
        if (clearSdPin) {
            scope.launch {
                withContext(NonCancellable) {
                    settingsDataStore.setSecretarySdResourceId("")
                    settingsDataStore.setSecretarySdSkin("")
                }
            }
        }
        voicesOwnerShip = ship.name

        try {
            val voicesData = getVoicesUseCase(ship.name)
            val savedFigure = settingsDataStore.getSavedFigure(ship.name).first()
            val finalFigureUrl = savedFigure
                ?: voicesData.skinFigureMap.values.firstOrNull()
                ?: voicesData.defaultFigureUrl
                ?: ship.avatarUrl
            val webChibi = savedFigure ?: voicesData.defaultChibiUrl.orEmpty()

            updateState {
                it.copy(
                    figureUrl = finalFigureUrl,
                    webChibiUrl = webChibi,
                    voices = voicesData.voices,
                    isLoadingVoices = false
                )
            }
            voicesOwnerShip = ship.name

            persistSecretary(ship.name, finalFigureUrl, ship.avatarUrl, webChibi)

            // 已在运行则不打断（与滑块调整同策略）：切舰不重置自动播放倒计时
            if (_state.value.autoPlayEnabled && autoPlayJob?.isActive != true) startAutoPlay()

            Log.d(TAG, "applyShipSelection: 完成，立绘URL=$finalFigureUrl，耗时 ${System.currentTimeMillis() - startTime}ms")
        } catch (e: Exception) {
            Log.e(TAG, "applyShipSelection: 加载失败，使用头像降级", e)
            updateState {
                it.copy(
                    figureUrl = ship.avatarUrl,
                    webChibiUrl = "",
                    voices = emptyList(),
                    isLoadingVoices = false
                )
            }
            persistSecretary(ship.name, ship.avatarUrl, ship.avatarUrl, "")
        }
    }

    /** 选舰结果持久化（NonCancellable：即使 scope 被取消也需完成写入，避免状态丢失） */
    private fun persistSecretary(
        shipName: String,
        figureUrl: String,
        avatarUrl: String,
        webChibiUrl: String
    ) {
        scope.launch {
            withContext(NonCancellable) {
                settingsDataStore.saveSecretaryShip(shipName, figureUrl, avatarUrl, webChibiUrl)
            }
        }
    }

    fun clearSecretary() {
        Log.d(TAG, "clearSecretary: 清除秘书舰")
        updateState {
            it.copy(
                shipName = "",
                figureUrl = "",
                avatarUrl = "",
                webChibiUrl = "",
                voices = emptyList(),
                currentDialogue = null
            )
        }
        voicesOwnerShip = ""
        stopAutoPlay()
        // 持久化清理（NonCancellable：即使 scope 被取消也需完成写入，避免重启后残留旧数据）
        scope.launch {
            withContext(NonCancellable) {
                settingsDataStore.clearSecretaryShip()
            }
        }
    }

    @androidx.annotation.OptIn(UnstableApi::class)
    fun playRandomVoice() {
        // 点击即播语义：语音未就绪时先等待在途/新发起的加载完成再播。
        // 此前调用方（悬浮窗 onTap）先 ensureVoicesLoaded（异步）后立刻 playRandomVoice，
        // voices 仍为空 → 本次点击只触发后台加载、不出声，要点两次才有声音
        scope.launch {
            if (_state.value.voices.isEmpty()) {
                loadVoicesInBackground(_state.value.shipName)?.join()
            }
            playRandomVoiceNow()
        }
    }

    @androidx.annotation.OptIn(UnstableApi::class)
    private fun playRandomVoiceNow() {
        val voices = _state.value.voices
        if (voices.isEmpty()) {
            // 等待后仍为空（加载失败/无语音数据）：再触发一次后台加载供下次播放
            if (_state.value.shipName.isNotEmpty()) loadVoicesInBackground(_state.value.shipName)
            return
        }
        val randomVoice = voices.random()
        // 更新台词弹窗内容，8 秒后自动清除（仅在开关开启时显示）
        val cleanDialogue = randomVoice.dialogue.replace("\n", " ").replace("\r", "").trim()
        if (_state.value.dialogueEnabled) {
            updateState { it.copy(currentDialogue = cleanDialogue.ifEmpty { null }) }
            dialogueClearJob?.cancel()
            dialogueClearJob = scope.launch {
                delay(DIALOGUE_CLEAR_DELAY_MS)
                updateState { it.copy(currentDialogue = null) }
            }
        }
        onController { player ->
            val mediaItem = createMediaItem(randomVoice)
            player.setMediaItem(mediaItem)
            player.repeatMode = Player.REPEAT_MODE_OFF
            player.prepare()
            player.play()
            Log.d(TAG, "playRandomVoice: 播放语音 ${randomVoice.scene}, dialogue=$cleanDialogue")
        }
    }

    @androidx.annotation.OptIn(UnstableApi::class)
    private fun createMediaItem(voice: VoiceLine): MediaItem {
        val activeAudioUrl = voice.getActiveAudioUrl(_state.value.voiceLanguage)
        val cleanDialogue = voice.dialogue.replace("\n", " ").replace("\r", "").trim()
        val metadata = androidx.media3.common.MediaMetadata.Builder()
            .setTitle(cleanDialogue)
            .setArtist("${_state.value.shipName} · ${voice.skinName}")
            .setAlbumTitle(voice.scene)
            .setArtworkUri(_state.value.avatarUrl.let { if (it.isNotEmpty()) it.toUri() else null })
            .build()
        return MediaItem.Builder()
            .setMediaId(activeAudioUrl)
            .setUri(activeAudioUrl)
            .setMediaMetadata(metadata)
            .build()
    }

    /**
     * 在 MediaController 上执行命令。
     *
     * 命中 [cachedController] 缓存直接执行；未就绪时排队，
     * 由 init 中挂载的单次 Future 监听器在连接建立后按序消费。
     */
    @androidx.annotation.OptIn(UnstableApi::class)
    private fun onController(command: (Player) -> Unit) {
        cachedController?.let {
            command(it)
            return
        }
        synchronized(pendingControllerCommands) {
            pendingControllerCommands.addLast(command)
        }
        resolveControllerWhenReady()
    }

    fun ensureVoicesLoaded(shipName: String) {
        // 舰名为空时不加载语音（自定义非舰娘资源无语音，避免错误触发网络请求）
        if (shipName.isBlank()) return
        if (_state.value.shipName == shipName && _state.value.voices.isEmpty() && !_state.value.isLoadingVoices) {
            loadVoicesInBackground(shipName)
        }
    }

    // 语音后台加载 Job 与目标舰名：点击播放等待它完成；并发触发（点击/自动播放/
    // ensureVoicesLoaded）复用在途请求，且切换舰娘后不会误挂到旧舰名的加载上。
    // 仅在主线程（scope = Main）读写，无并发问题。
    private var voicesLoadJob: Job? = null
    private var voicesLoadTarget: String? = null

    private fun loadVoicesInBackground(shipName: String): Job? {
        // 舰名为空时不加载语音（自定义非舰娘资源无对应舰娘，不应触发语音加载）
        if (shipName.isBlank()) {
            updateState { it.copy(voices = emptyList()) }
            return null
        }
        voicesLoadJob?.takeIf { it.isActive && voicesLoadTarget == shipName }?.let { return it }
        val job = scope.launch {
            updateState { it.copy(isLoadingVoices = true) }
            try {
                if (_state.value.voices.isNotEmpty() && voicesOwnerShip == shipName) {
                    // applyShipSelection 等路径已加载过语音并同步补齐图源，无需重复网络请求
                    return@launch
                }
                val voicesData = getVoicesUseCase(shipName)
                // 【P0 竞态修复】归属守卫：加载期间舰名可能已切换（快速切舰），
                // 旧舰的在途结果不得写入新舰状态——voices 与图源一并守卫
                if (_state.value.shipName == shipName) {
                    var chibiToPersist: String? = null
                    updateState {
                        val newChibi = it.webChibiUrl.ifBlank { voicesData.defaultChibiUrl.orEmpty() }
                        if (it.webChibiUrl.isBlank() && newChibi.isNotEmpty()) chibiToPersist = newChibi
                        it.copy(
                            voices = voicesData.voices,
                            // 补齐缺失的立绘与网页 Q版小人 URL（冷启动恢复/SD 资源联动清空场景）
                            figureUrl = it.figureUrl.ifBlank {
                                voicesData.skinFigureMap.values.firstOrNull()
                                    ?: voicesData.defaultFigureUrl ?: ""
                            },
                            webChibiUrl = newChibi
                        )
                    }
                    voicesOwnerShip = shipName
                    // 补齐的小人 URL 同步持久化，下次冷启动零网络恢复
                    chibiToPersist?.let { settingsDataStore.setSecretaryWebChibiUrl(it) }
                    Log.d(TAG, "loadVoicesInBackground: 加载了 ${voicesData.voices.size} 条语音（$shipName）")
                } else {
                    Log.d(TAG, "loadVoicesInBackground: 加载完成时舰名已切换为 ${_state.value.shipName}，丢弃 $shipName 的结果")
                }
            } catch (e: Exception) {
                Log.e(TAG, "loadVoicesInBackground: 加载失败", e)
                if (_state.value.shipName == shipName) {
                    updateState { it.copy(voices = emptyList()) }
                }
            } finally {
                if (_state.value.shipName == shipName) {
                    updateState { it.copy(isLoadingVoices = false) }
                }
            }
        }
        voicesLoadJob = job
        voicesLoadTarget = shipName
        return job
    }

    fun setAutoPlay(enabled: Boolean, intervalMinutes: Int) {
        scope.launch {
            withContext(NonCancellable) {
                settingsDataStore.setSecretaryAutoPlay(enabled, intervalMinutes)
            }
            if (enabled && _state.value.shipName.isNotEmpty()) {
                // 已在运行则不打断：间隔由循环每轮自适应读取，滑块连发不再重启定时器
                if (autoPlayJob?.isActive != true) startAutoPlay()
            } else {
                stopAutoPlay()
            }
        }
    }

    fun setVoiceLanguage(language: VoiceLanguage) {
        scope.launch {
            withContext(NonCancellable) {
                settingsDataStore.setVoiceLanguage(language)
            }
        }
    }

    fun setDialogueEnabled(enabled: Boolean) {
        scope.launch {
            withContext(NonCancellable) {
                settingsDataStore.setSecretaryDialogueEnabled(enabled)
            }
        }
    }

    /**
     * 设置悬浮窗触摸穿透开关。
     *
     * 触摸穿透开启后，悬浮窗主窗口添加 [android.view.WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE]，
     * 所有触摸事件直接穿透到下层 App；关闭后恢复正常点击/拖动/缩放交互。
     *
     * 仅写入 DataStore，[SecretaryOverlayService] 通过 collect 状态实时更新窗口 flags，
     * 无需重启 App，多角色切换保持统一设置。
     */
    fun setOverlayTouchPassthrough(enabled: Boolean) {
        scope.launch {
            withContext(NonCancellable) {
                settingsDataStore.setSecretaryOverlayTouchPassthrough(enabled)
            }
        }
    }

    /** 重算 [SecretaryManagerState.sdAvailable]（IO 线程解析，主线程只写状态） */
    private fun refreshSdAvailability() {
        sdAvailabilityJob?.cancel()
        sdAvailabilityJob = scope.launch {
            val current = _state.value
            if (current.shipName.isBlank() && current.sdResourceId.isBlank()) {
                if (current.sdAvailable) updateState { it.copy(sdAvailable = false) }
                return@launch
            }
            val available = withContext(Dispatchers.IO) {
                runCatching {
                    SDResourceManager.resolve(
                        appContext,
                        current.sdResourceId,
                        current.shipName,
                        current.sdSkin
                    ) != null
                }.getOrDefault(false)
            }
            if (_state.value.sdAvailable != available) {
                Log.d(TAG, "sdAvailable=$available（ship=${current.shipName}, pin=${current.sdResourceId}）")
                updateState { it.copy(sdAvailable = available) }
            }
        }
    }

    /**
     * 校验当前 pin 的 SD 资源是否还存在；不存在就清空选择并回退到舰名匹配。
     *
     * 只清 pin，不动舰名/语音：资源没了不代表用户不想要这个秘书舰，
     * 回退到「按舰名匹配」是更合理的降级（可能命中 wiki 小人或 `blhx_sd` 里的同名资源）。
     */
    private suspend fun healDanglingSdResource(resourceId: String) {
        val exists = withContext(Dispatchers.IO) {
            runCatching { SDResourceManager.resolveById(appContext, resourceId) != null }
                .getOrDefault(true) // 扫描本身出错时不要误清
        }
        if (exists) return
        Log.w(TAG, "SD 资源 $resourceId 已不存在（删除/改名？），清除选择并回退舰名匹配")
        updateState { it.copy(sdResourceId = "", sdSkin = "") }
        withContext(NonCancellable) {
            settingsDataStore.setSecretarySdResourceId("")
            settingsDataStore.setSecretarySdSkin("")
        }
    }

    fun setSdSkin(skin: String) {
        // 立即更新内存状态，确保 UI 实时响应（皮肤切换无需等待 DataStore 异步写入）
        updateState { it.copy(sdSkin = skin) }
        scope.launch {
            withContext(NonCancellable) {
                settingsDataStore.setSecretarySdSkin(skin)
            }
        }
    }

    fun setSdScale(scale: Float) {
        // 立即更新内存状态，确保 UI 实时响应滑块拖动（避免等待 DataStore 异步写入 + collect 回调的延迟）
        updateState { it.copy(sdScale = scale) }
        scope.launch {
            withContext(NonCancellable) {
                settingsDataStore.setSecretarySdScale(scale)
            }
        }
    }

    fun setSdResourceId(resourceId: String) {
        // 立即更新内存状态，确保 UI 实时响应；切换资源时重置皮肤为默认
        updateState { it.copy(sdResourceId = resourceId, sdSkin = "") }
        scope.launch {
            withContext(NonCancellable) {
                settingsDataStore.setSecretarySdResourceId(resourceId)
                settingsDataStore.setSecretarySdSkin("")
            }
            // 联动更新语音和立绘：如果选择的 SD 资源对应一个舰娘，
            // 同时更新秘书舰名和语音列表，确保皮肤切换和语音与 SD 小人保持一致
            if (resourceId.isNotBlank()) {
                updateShipFromResourceId(resourceId)
            }
        }
    }

    /**
     * 从 SD 资源 ID 反查中文舰名，联动更新秘书舰名和语音。
     *
     * 反查策略：
     * 1. 先通过 [SDResourceManager.resolveById] 获取资源，检查是否有 shipName 字段
     * 2. 如果没有 shipName，但资源来源是舰娘，遍历舰娘数据库用拼音匹配
     * 3. 如果找到对应舰娘且与当前舰名不同，更新舰名、语音和立绘
     * 4. 如果资源无对应舰娘（自定义非舰娘资源），清空舰名和语音，
     *    避免错误使用原舰娘语音
     */
    private suspend fun updateShipFromResourceId(resourceId: String) {
        // 策略1：通过 SDResourceManager 获取资源的 shipName（resolveByShipName 构建的资源有此字段）
        val resource = withContext(Dispatchers.IO) {
            SDResourceManager.resolveById(appContext, resourceId)
        }
        val shipName = when {
            // 资源自带 shipName 字段（resolveByShipName 构建的资源）
            !resource?.shipName.isNullOrBlank() -> resource!!.shipName
            // 资源来源是舰娘但没有 shipName，遍历数据库拼音匹配
            resource?.source == com.azurlane.blyy.util.SDResourceSource.SHIP ||
                resource?.source == com.azurlane.blyy.util.SDResourceSource.GAME_IMPORT ->
                resolveShipNameByPinyin(resourceId.removePrefix("game/"))
            // 自定义资源，无对应舰娘
            else -> null
        }

        if (shipName.isNullOrBlank()) {
            // 无对应舰娘的自定义资源：清空舰名和语音，避免错误使用原语音
            // 即使原秘书舰有语音，切换到无语音资源后也不应继续播放原语音
            if (_state.value.shipName.isNotEmpty() || _state.value.voices.isNotEmpty()) {
                Log.d(TAG, "updateShipFromResourceId: 资源 $resourceId 无对应舰娘，清空舰名和语音（原 ${_state.value.shipName}）")
                updateState {
                    it.copy(
                        shipName = "",
                        voices = emptyList(),
                        avatarUrl = "",
                        figureUrl = "",
                        webChibiUrl = "",
                        currentDialogue = null
                    )
                }
                voicesOwnerShip = ""
                stopAutoPlay()
                // 停止当前正在播放的语音
                onController { it.pause() }
                // 持久化清空，避免重启后残留原舰名导致错误加载语音
                withContext(NonCancellable) {
                    settingsDataStore.saveSecretaryShip("", "", "", "")
                }
            }
            return
        }

        // 找到对应舰名，且与当前舰名不同，则更新秘书舰和语音
        if (shipName != _state.value.shipName) {
            Log.d(TAG, "updateShipFromResourceId: 资源 $resourceId → 舰名 $shipName（原 ${_state.value.shipName}）")
            updateState {
                it.copy(
                    shipName = shipName,
                    voices = emptyList(),
                    avatarUrl = "",
                    figureUrl = "",
                    currentDialogue = null
                )
            }
            voicesOwnerShip = ""
            stopAutoPlay()
            // 停止当前正在播放的旧语音，避免新语音加载期间继续播放旧语音
            onController { it.pause() }
            // 持久化新舰名，立绘和头像清空让后续加载流程重新获取
            withContext(NonCancellable) {
                settingsDataStore.saveSecretaryShip(shipName, "", "", "")
            }
            // 后台加载新舰娘的语音；webChibiUrl 跟随「查看立绘」选择，
            // 为空时由加载流程顺带抓取 wiki 默认小人并持久化（见归属守卫内逻辑）
            loadVoicesInBackground(shipName)
        }
    }

    /**
     * 通过拼音匹配从舰娘数据库反查中文舰名。
     *
     * 遍历所有 DOCK 类型舰娘，用 [PinyinHelper.toPinyin] 将舰名转拼音后与资源 ID 匹配。
     * 同时支持首字母缩写匹配和前缀匹配，覆盖 LocalSdResolver 的多种匹配策略。
     */
    private suspend fun resolveShipNameByPinyin(resourceId: String): String? {
        return withContext(Dispatchers.IO) {
            try {
                val ships = shipDao.getShipsByArchiveType(ArchiveType.DOCK.name).first()
                // 1. 拼音全拼精确匹配
                ships.firstOrNull { PinyinHelper.toPinyin(it.name) == resourceId }?.name
                    // 2. 拼音首字母缩写匹配
                    ?: ships.firstOrNull { PinyinHelper.toPinyinInitials(it.name) == resourceId }?.name
                    // 3. 资源ID是舰名全拼的前缀（资源用简写命名）
                    ?: ships.firstOrNull {
                        val pinyin = PinyinHelper.toPinyin(it.name)
                        pinyin.length >= 3 && pinyin.startsWith(resourceId)
                    }?.name
                    // 4. 舰名全拼是资源ID的前缀（资源用完整命名）
                    ?: ships.firstOrNull {
                        val pinyin = PinyinHelper.toPinyin(it.name)
                        resourceId.length >= pinyin.length && resourceId.startsWith(pinyin)
                    }?.name
            } catch (e: Exception) {
                Log.e(TAG, "resolveShipNameByPinyin: 反查舰名失败 $resourceId", e)
                null
            }
        }
    }

    private fun startAutoPlay() {
        stopAutoPlay()
        autoPlayJob = scope.launch {
            while (isActive && _state.value.shipName.isNotEmpty()) {
                // 每轮循环读取最新间隔：滑块调整对下一轮生效，无需重启循环
                val intervalMs = _state.value.autoPlayIntervalMinutes * 60 * 1000L
                delay(intervalMs)
                if (isActive && _state.value.autoPlayEnabled) playRandomVoice()
            }
        }
    }

    private fun stopAutoPlay() {
        autoPlayJob?.cancel()
        autoPlayJob = null
    }

    companion object {
        private const val TAG = "SecretaryManager"
        /** 台词自动清除延迟（ms） */
        private const val DIALOGUE_CLEAR_DELAY_MS = 8000L
    }
}

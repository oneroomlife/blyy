package com.azurlane.blyy.viewmodel

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.azurlane.blyy.data.model.Ship
import com.azurlane.blyy.data.model.ShipCharacterInfo
import com.azurlane.blyy.data.model.ShipGallery
import com.azurlane.blyy.data.model.SkillRow
import com.azurlane.blyy.data.model.VoiceLine
import com.azurlane.blyy.data.local.GuessHistoryDao
import com.azurlane.blyy.data.model.GuessHistory
import com.azurlane.blyy.data.repository.ShipDetailRepository
import com.azurlane.blyy.data.repository.ShipRepository
import com.azurlane.blyy.domain.GetVoicesUseCase
import com.azurlane.blyy.util.CacheManager
import com.azurlane.blyy.util.CacheNamespaces
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.delay
import kotlin.random.Random
import java.util.UUID
import javax.inject.Inject

enum class GuessMode {
    IMAGE,
    VOICE,

    /**
     * 技能识舰娘：题面是「某艘舰娘的技能图标」，玩家要答出这艘舰娘。
     *
     * 与 IMAGE/VOICE 的关键差别：技能在 wiki 上是**按舰娘页**组织的，一艘船 1–3 个技能，
     * 且通用技能可能被多艘船共享（详见 [GuessShipViewModel.checkAnswer] 的共享技能容错）。
     */
    SKILL
}

enum class GuessResult {
    CORRECT,
    WRONG,
    SKIPPED
}

enum class ImageDifficulty {
    EASY,
    HARD
}

enum class VoiceDifficulty {
    EASY,
    HARD
}

/** 识技能难度：EASY 展示技能名+描述，HARD 只给图标 */
enum class SkillDifficulty {
    EASY,
    HARD
}

data class CropRegion(
    val startX: Float = 0f,
    val startY: Float = 0f,
    val endX: Float = 1f,
    val endY: Float = 1f,
    val cropType: CropType = CropType.CENTER
)

enum class CropType {
    CENTER,
    UPPER,
    LOWER,
    LEFT,
    RIGHT,
    UPPER_LEFT,
    UPPER_RIGHT,
    LOWER_LEFT,
    LOWER_RIGHT,
    RANDOM_OFFSET
}

data class HintItem(
    val id: String,
    val label: String,
    val value: String
)

data class GameScore(
    val totalQuestions: Int = 0,
    val correctAnswers: Int = 0,
    val totalScore: Int = 0,
    val hintsUsedTotal: Int = 0,
    val skippedQuestions: Int = 0,
    val totalPossibleScore: Int = 0
) {
    val accuracy: Float
        get() = if (totalQuestions > 0) correctAnswers.toFloat() / totalQuestions else 0f
    
    val averageScore: Float
        get() = if (totalQuestions > 0) totalScore.toFloat() / totalQuestions else 0f
}

data class GuessGameUiState(
    val isActive: Boolean = false,
    val mode: GuessMode = GuessMode.IMAGE,
    val difficulty: ImageDifficulty = ImageDifficulty.EASY,
    val voiceDifficulty: VoiceDifficulty = VoiceDifficulty.EASY,
    val skillDifficulty: SkillDifficulty = SkillDifficulty.EASY,
    val currentShip: Ship? = null,
    val currentImageUrl: String? = null,
    val cropRegion: CropRegion? = null,
    val currentVoice: VoiceLine? = null,
    val currentDialogueId: Long = 0,
    /** 识技能模式的题面：该舰娘**全部**可用技能（含图标 URL）；其余模式为空 */
    val currentSkills: List<SkillRow> = emptyList(),
    /**
     * 识技能模式：玩家答的是"另一艘同样拥有该技能的舰娘"时记其名字，
     * 用于答对卡片上的说明文案（不参与判分，分数照给）。
     */
    val sharedSkillOwner: String? = null,
    val rewardImageUrl: String? = null,
    val inputText: String = "",
    val lastResult: GuessResult? = null,
    val errorMessage: String? = null,
    val isChecking: Boolean = false,
    val characterInfo: ShipCharacterInfo? = null,
    val hints: List<HintItem> = emptyList(),
    val usedHintLabels: Set<String> = emptySet(),
    val isLoadingHint: Boolean = false,
    val showAnswer: Boolean = false,
    val score: GameScore = GameScore(),
    val showSettlement: Boolean = false,
    val currentQuestionScore: Int = 10,
    /** 本题满分（简单 10 / 困难 50）；结算页的「满分」按它累加，不能写死 10 */
    val currentQuestionPossibleScore: Int = 10,
    val noMoreHints: Boolean = false,
    /** 当前题目是否已计入总分（防止双重计数） */
    val currentQuestionCounted: Boolean = false
) {
    /**
     * 当前题号（从 1 开始），供题面元信息条展示。
     *
     * 不能用 `totalQuestions + 1` 直接算：点过「结算 → 继续游戏」时当前题已被计入
     * `totalQuestions`，再 +1 就会显示成下一题（实测出现过"停在第 1 题却写着第 2 题"）。
     */
    val currentQuestionNumber: Int
        get() = if (currentQuestionCounted) score.totalQuestions else score.totalQuestions + 1
}

@HiltViewModel
class GuessShipViewModel @Inject constructor(
    private val repository: ShipRepository,
    private val getVoicesUseCase: GetVoicesUseCase,
    private val historyDao: GuessHistoryDao,
    private val shipDetailRepository: ShipDetailRepository
) : ViewModel() {

    private companion object {
        // 裁剪区域宽高随机变化量上限（占图片宽高的比例），用于 generateRandomCrop 各分支
        const val CROP_VARIATION_MAX = 0.15f

        /**
         * 识技能单题最多展示的技能数。
         *
         * 正常舰娘 1–3 个技能（改造前/后两套表里可见的那一套）；极端页面（强化变体重复行）
         * 可能更多，超过这个数就只取前 N 个，避免题面卡片被图标挤爆。
         */
        const val MAX_QUESTION_SKILLS = 5
    }

    private val _uiState = MutableStateFlow(GuessGameUiState())
    val uiState: StateFlow<GuessGameUiState> = _uiState.asStateFlow()

    private var allShipsCache: List<Ship> = emptyList()
    private val usedShipIndices = mutableSetOf<Int>()
    private var isRefreshingShips = false

    /**
     * 出题代际令牌：每次 [loadNextQuestion] 递增。
     *
     * 题目生成是异步协程（需网络拉取立绘/语音后才写状态），期间任何第二次出题触发
     * （双击"下一题"、Room 数据补载）都会再启动一个出题协程——若不设防，两个协程
     * 先后写入状态就会造成"点一次下一题连跳两题"。协程落地前校验代际，只有仍是
     * 最新一代才允许写入状态，旧一代的结果全部作废。
     */
    private var questionGeneration = 0L

    /** 当前游戏会话 ID（每局唯一，防止重复录入历史记录） */
    private var gameSessionId: String = UUID.randomUUID().toString()
    /** 标记当前局是否已保存历史记录，防止 showSettlement 被多次调用导致重复录入 */
    private var hasSavedHistory: Boolean = false

    init {
        viewModelScope.launch {
            repository.allShips.collectLatest { ships ->
                allShipsCache = ships
                usedShipIndices.clear()
                // 冷启动竞态修复：startImageGame/startVoiceGame 可能先于 Room 首次发射执行，
                // 此时 allShipsCache 仍为空，loadNextQuestion 落入"正在同步"分支且不会自动重试。
                // 数据到位后若题目尚未生成，则自动补载一次；delay + collectLatest 取消语义
                // 将 upsert/delete 两次相邻发射合并，避免重复加载。
                val state = _uiState.value
                if (ships.isNotEmpty() && state.isActive && state.currentShip == null && !state.showSettlement) {
                    delay(250)
                    // delay 期间用户可能已手动出题、或上一个出题协程已落地——二次检查，
                    // 避免补载与进行中的出题并发（两个出题协程竞争 = 连跳两题）
                    val latest = _uiState.value
                    if (latest.isActive && latest.currentShip == null && !latest.showSettlement) {
                        loadNextQuestion()
                    }
                }
            }
        }
    }

    fun setDifficulty(difficulty: ImageDifficulty) {
        _uiState.update { it.copy(difficulty = difficulty) }
    }

    fun setVoiceDifficulty(difficulty: VoiceDifficulty) {
        _uiState.update { it.copy(voiceDifficulty = difficulty) }
    }

    fun setSkillDifficulty(difficulty: SkillDifficulty) {
        _uiState.update { it.copy(skillDifficulty = difficulty) }
    }

    fun startImageGame() = startGame(GuessMode.IMAGE)

    fun startVoiceGame() = startGame(GuessMode.VOICE)

    fun startSkillGame() = startGame(GuessMode.SKILL)

    /**
     * 三种玩法的开局复位逻辑完全同构（会话换代 → 清空上一局残留 → 出第一题），
     * 因此收敛成一个入口：新增玩法时只要在 [loadNextQuestion] 里补一条出题分支，
     * 不会出现"某玩法忘了清 currentSkills/sharedSkillOwner"这类漏改。
     */
    private fun startGame(mode: GuessMode) {
        gameSessionId = UUID.randomUUID().toString()
        hasSavedHistory = false
        _uiState.update {
            it.copy(
                isActive = true,
                mode = mode,
                lastResult = null,
                inputText = "",
                errorMessage = null,
                rewardImageUrl = null,
                characterInfo = null,
                hints = emptyList(),
                usedHintLabels = emptySet(),
                cropRegion = null,
                currentSkills = emptyList(),
                sharedSkillOwner = null,
                showAnswer = false,
                score = GameScore(),
                showSettlement = false,
                currentQuestionScore = 10,
                currentQuestionPossibleScore = 10,
                noMoreHints = false,
                isChecking = false
            )
        }
        loadNextQuestion()
    }

    fun closeGame() {
        _uiState.update { GuessGameUiState() }
    }

    fun onInputChanged(text: String) {
        _uiState.update { it.copy(inputText = text) }
    }

    fun clearResult() {
        _uiState.update { it.copy(lastResult = null) }
    }

    /**
     * 当前玩法的候选舰娘池。
     *
     * 识技能的题面来自 wiki 舰船页的技能表，而学生档案（蔚蓝档案）根本没有技能表；
     * 混进来只会白白多一次网络请求再被判定"无技能"，因此这里先按档案类型收窄。
     */
    private fun candidateShips(): List<Ship> {
        val all = allShipsCache
        if (all.isEmpty()) return all
        return when (_uiState.value.mode) {
            GuessMode.SKILL -> all.filter { it.archiveType == ArchiveType.DOCK.name }.ifEmpty { all }
            else -> all
        }
    }

    fun loadNextQuestion() {
        // 代际递增：使仍在飞行中的旧出题协程作废（见 questionGeneration 注释）
        questionGeneration++
        val generation = questionGeneration

        // 出题即作废上一题可能仍在飞的"共享技能校验"：它的协程会因代际不符丢弃结果，
        // 但 isChecking 标志必须在这里清掉，否则新题的提交按钮会一直是禁用态（软锁）
        _uiState.update { it.copy(isChecking = false) }

        val ships = candidateShips()
        if (ships.isEmpty()) {
            if (!isRefreshingShips) {
                isRefreshingShips = true
                viewModelScope.launch {
                    try {
                        repository.refreshShipsFromWiki(ArchiveType.DOCK)
                    } catch (e: Exception) {
                        _uiState.update {
                            it.copy(
                                errorMessage = e.message ?: "同步舰娘数据失败，请稍后重试。"
                            )
                        }
                    } finally {
                        isRefreshingShips = false
                    }
                }
            }
            _uiState.update {
                it.copy(
                    errorMessage = "正在同步舰娘数据，请点击下一题重试。",
                    currentShip = null,
                    currentImageUrl = null,
                    currentVoice = null,
                    currentSkills = emptyList(),
                    sharedSkillOwner = null,
                    lastResult = null,
                    characterInfo = null,
                    hints = emptyList(),
                    usedHintLabels = emptySet(),
                    showAnswer = false,
                    noMoreHints = false
                )
            }
            return
        }

        val ship = pickUnusedShip(ships)
        when (_uiState.value.mode) {
            GuessMode.IMAGE -> prepareImageQuestion(ship, ships, 0, generation)
            GuessMode.VOICE -> prepareVoiceQuestion(ship, ships, 0, generation)
            GuessMode.SKILL -> prepareSkillQuestion(ship, ships, 0, generation)
        }
    }

    /**
     * 从尚未出过题的舰娘中随机选一艘并标记；全部用过则重置使用记录后重选。
     * 主出题路径与失败重试路径共用，保证重试选中的船同样被标记，
     * 不会在下一题再次抽到已知无立绘/无语音的舰娘。
     */
    private fun pickUnusedShip(ships: List<Ship>): Ship {
        val availableIndices = ships.indices.filter { it !in usedShipIndices }
        val index = if (availableIndices.isEmpty()) {
            usedShipIndices.clear()
            ships.indices.random()
        } else {
            availableIndices.random()
        }
        usedShipIndices.add(index)
        return ships[index]
    }

    private fun loadNextQuestionInternal(ships: List<Ship>, generation: Long, attempt: Int) {
        if (attempt >= 5) {
            // 代际已过期说明新一轮出题已接管，旧一代的重试不得再清空/覆盖状态
            if (generation != questionGeneration) return
            _uiState.update {
                it.copy(
                    errorMessage = "未能生成有效题目，请稍后再试。",
                    currentShip = null,
                    currentImageUrl = null,
                    currentVoice = null,
                    currentSkills = emptyList(),
                    sharedSkillOwner = null,
                    lastResult = null,
                    characterInfo = null,
                    hints = emptyList(),
                    usedHintLabels = emptySet(),
                    showAnswer = false,
                    noMoreHints = false
                )
            }
            return
        }

        val ship = pickUnusedShip(ships)
        when (_uiState.value.mode) {
            GuessMode.IMAGE -> prepareImageQuestion(ship, ships, attempt, generation)
            GuessMode.VOICE -> prepareVoiceQuestion(ship, ships, attempt, generation)
            GuessMode.SKILL -> prepareSkillQuestion(ship, ships, attempt, generation)
        }
    }

    private fun prepareImageQuestion(ship: Ship, ships: List<Ship>, attempt: Int, generation: Long) {
        viewModelScope.launch {
            try {
                val gallery = getGalleryForShip(ship)

                val candidates = gallery.illustrations.filter { it.second.isNotBlank() }

                if (candidates.isEmpty()) {
                    if (generation == questionGeneration) {
                        loadNextQuestionInternal(ships, generation, attempt + 1)
                    }
                    return@launch
                }

                val chosen = candidates.random()
                val cropRegion = if (_uiState.value.difficulty == ImageDifficulty.HARD) {
                    generateRandomCropRegion()
                } else {
                    null
                }

                val questionScore = if (_uiState.value.difficulty == ImageDifficulty.HARD) 50 else 10

                // 网络等待期间代际可能已递增（用户连点/补载触发了新一轮出题），
                // 旧一代的结果直接丢弃，避免两个出题协程先后写状态造成题目闪跳
                if (generation != questionGeneration) return@launch

                _uiState.update {
                    it.copy(
                        currentShip = ship,
                        currentImageUrl = chosen.second,
                        currentVoice = null,
                        currentSkills = emptyList(),
                        sharedSkillOwner = null,
                        rewardImageUrl = null,
                        inputText = "",
                        lastResult = null,
                        errorMessage = null,
                        characterInfo = null,
                        hints = emptyList(),
                        usedHintLabels = emptySet(),
                        cropRegion = cropRegion,
                        showAnswer = false,
                        currentQuestionScore = questionScore,
                        currentQuestionPossibleScore = questionScore,
                        noMoreHints = _uiState.value.difficulty == ImageDifficulty.HARD,
                        currentQuestionCounted = false
                    )
                }
            } catch (e: Exception) {
                if (generation == questionGeneration) {
                    loadNextQuestionInternal(ships, generation, attempt + 1)
                }
            }
        }
    }

    private fun generateRandomCropRegion(): CropRegion {
        val cropTypes = CropType.entries
        val selectedType = cropTypes.random()
        
        val minCropSize = 0.40f
        val maxCropSize = 0.60f
        val baseCropSize = Random.nextFloat() * (maxCropSize - minCropSize) + minCropSize
        
        val aspectVariation = Random.nextFloat() * CROP_VARIATION_MAX
        val cropWidth = baseCropSize + if (Random.nextBoolean()) aspectVariation else -aspectVariation
        val cropHeight = baseCropSize + if (Random.nextBoolean()) aspectVariation else -aspectVariation
        
        val finalCropWidth = cropWidth.coerceIn(minCropSize, maxCropSize)
        val finalCropHeight = cropHeight.coerceIn(minCropSize, maxCropSize)
        
        val (startX, startY) = when (selectedType) {
            CropType.CENTER -> {
                val maxStartX = (1f - finalCropWidth) / 2f
                val maxStartY = (1f - finalCropHeight) / 2f
                val offsetX = Random.nextFloat() * 0.1f - 0.05f
                val offsetY = Random.nextFloat() * 0.1f - 0.05f
                Pair(
                    (maxStartX + offsetX).coerceIn(0f, 1f - finalCropWidth),
                    (maxStartY + offsetY).coerceIn(0f, 1f - finalCropHeight)
                )
            }
            CropType.UPPER -> {
                val maxStartX = 1f - finalCropWidth
                Pair(
                    Random.nextFloat() * maxStartX,
                    Random.nextFloat() * CROP_VARIATION_MAX
                )
            }
            CropType.LOWER -> {
                val maxStartX = 1f - finalCropWidth
                val maxStartY = 1f - finalCropHeight
                Pair(
                    Random.nextFloat() * maxStartX,
                    maxStartY - Random.nextFloat() * CROP_VARIATION_MAX
                )
            }
            CropType.LEFT -> {
                val maxStartY = 1f - finalCropHeight
                Pair(
                    Random.nextFloat() * CROP_VARIATION_MAX,
                    Random.nextFloat() * maxStartY
                )
            }
            CropType.RIGHT -> {
                val maxStartX = 1f - finalCropWidth
                val maxStartY = 1f - finalCropHeight
                Pair(
                    maxStartX - Random.nextFloat() * CROP_VARIATION_MAX,
                    Random.nextFloat() * maxStartY
                )
            }
            CropType.UPPER_LEFT -> {
                Pair(
                    Random.nextFloat() * CROP_VARIATION_MAX,
                    Random.nextFloat() * CROP_VARIATION_MAX
                )
            }
            CropType.UPPER_RIGHT -> {
                val maxStartX = 1f - finalCropWidth
                Pair(
                    maxStartX - Random.nextFloat() * CROP_VARIATION_MAX,
                    Random.nextFloat() * CROP_VARIATION_MAX
                )
            }
            CropType.LOWER_LEFT -> {
                val maxStartY = 1f - finalCropHeight
                Pair(
                    Random.nextFloat() * CROP_VARIATION_MAX,
                    maxStartY - Random.nextFloat() * CROP_VARIATION_MAX
                )
            }
            CropType.LOWER_RIGHT -> {
                val maxStartX = 1f - finalCropWidth
                val maxStartY = 1f - finalCropHeight
                Pair(
                    maxStartX - Random.nextFloat() * CROP_VARIATION_MAX,
                    maxStartY - Random.nextFloat() * CROP_VARIATION_MAX
                )
            }
            CropType.RANDOM_OFFSET -> {
                val maxStartX = 1f - finalCropWidth
                val maxStartY = 1f - finalCropHeight
                Pair(
                    Random.nextFloat() * maxStartX,
                    Random.nextFloat() * maxStartY
                )
            }
        }
        
        return CropRegion(
            startX = startX.coerceIn(0f, 1f - finalCropWidth),
            startY = startY.coerceIn(0f, 1f - finalCropHeight),
            endX = (startX + finalCropWidth).coerceAtMost(1f),
            endY = (startY + finalCropHeight).coerceAtMost(1f),
            cropType = selectedType
        )
    }

    private fun prepareVoiceQuestion(ship: Ship, ships: List<Ship>, attempt: Int, generation: Long) {
        viewModelScope.launch {
            try {
                val voices = getVoicesForShip(ship)
                if (voices.isEmpty()) {
                    if (generation == questionGeneration) {
                        loadNextQuestionInternal(ships, generation, attempt + 1)
                    }
                    return@launch
                }

                val voice = voices.random()
                val questionScore = if (_uiState.value.voiceDifficulty == VoiceDifficulty.HARD) 50 else 10
                val dialogueId = System.currentTimeMillis()

                // 网络等待期间代际可能已递增（用户连点/补载触发了新一轮出题），
                // 旧一代的结果直接丢弃，避免两个出题协程先后写状态造成题目闪跳
                if (generation != questionGeneration) return@launch

                _uiState.update {
                    it.copy(
                        currentShip = ship,
                        currentVoice = voice,
                        currentDialogueId = dialogueId,
                        currentImageUrl = null,
                        currentSkills = emptyList(),
                        sharedSkillOwner = null,
                        rewardImageUrl = null,
                        inputText = "",
                        lastResult = null,
                        errorMessage = null,
                        characterInfo = null,
                        hints = emptyList(),
                        usedHintLabels = emptySet(),
                        cropRegion = null,
                        showAnswer = false,
                        currentQuestionScore = questionScore,
                        currentQuestionPossibleScore = questionScore,
                        noMoreHints = _uiState.value.voiceDifficulty == VoiceDifficulty.HARD,
                        currentQuestionCounted = false
                    )
                }
            } catch (_: Exception) {
                // 与 prepareImageQuestion 一致：单艘舰娘语音拉取失败（如 404）时换一艘重试，
                // 由 loadNextQuestionInternal 在重试耗尽后给出统一友好文案，
                // 避免把原始 HTTP 异常消息直接透给用户。
                if (generation == questionGeneration) {
                    loadNextQuestionInternal(ships, generation, attempt + 1)
                }
            }
        }
    }

    /**
     * 出「识技能」题：取该舰娘 wiki 技能表里的**全部技能**，把它们的图标一起作为题面。
     *
     * 为什么不是只取一个：一艘船 1–3 个技能，只看一枚图标时"专属弹幕/阵营通用技能"这类
     * 图标会退化成纯猜；整组图标（通常是"专属技能 + 两个被动"）才构成这艘船的技能指纹。
     * 判分仍以舰娘为准，但"共享技能"容错升级为"必须同样持有整套技能"（见 [checkAnswer]）。
     *
     * 与看图/听音的两个关键差异：
     * - **纯缓存优先**：技能数据来自 `ship_cache.detailJson`（详情页/上一次游戏已抓过），
     *   命中缓存时出题是同步的，不会像语音那样必须等 WebView；
     * - **无技能即换船**：wiki 页面没有 `table.sv-skill`（极少数旧页面）或技能全是空槽时，
     *   这艘船不能出题，直接按 [loadNextQuestionInternal] 的重试链换一艘。
     */
    private fun prepareSkillQuestion(ship: Ship, ships: List<Ship>, attempt: Int, generation: Long) {
        viewModelScope.launch {
            try {
                val skills = getSkillsForShip(ship)
                if (skills.isEmpty()) {
                    if (generation == questionGeneration) {
                        loadNextQuestionInternal(ships, generation, attempt + 1)
                    }
                    return@launch
                }

                // 全部技能一起出题；上限只为防极端页面（4 改造技能 + 强化变体）把卡片撑爆
                val questionSkills = skills.take(MAX_QUESTION_SKILLS)
                val isHard = _uiState.value.skillDifficulty == SkillDifficulty.HARD
                val questionScore = if (isHard) 50 else 10

                if (generation != questionGeneration) return@launch

                _uiState.update {
                    it.copy(
                        currentShip = ship,
                        currentSkills = questionSkills,
                        currentImageUrl = null,
                        currentVoice = null,
                        sharedSkillOwner = null,
                        rewardImageUrl = null,
                        inputText = "",
                        lastResult = null,
                        errorMessage = null,
                        characterInfo = null,
                        hints = emptyList(),
                        usedHintLabels = emptySet(),
                        cropRegion = null,
                        showAnswer = false,
                        currentQuestionScore = questionScore,
                        currentQuestionPossibleScore = questionScore,
                        // 困难模式只给图标，提示按钮直接关掉（与听音 HARD 一致的扣分保护）
                        noMoreHints = isHard,
                        currentQuestionCounted = false
                    )
                }
            } catch (_: Exception) {
                // 单艘船抓取失败（404/反爬/解析空）→ 换一艘重试，重试耗尽后统一文案
                if (generation == questionGeneration) {
                    loadNextQuestionInternal(ships, generation, attempt + 1)
                }
            }
        }
    }

    fun requestHint() {
        val ship = _uiState.value.currentShip ?: return
        val currentScore = _uiState.value.currentQuestionScore
        val currentHints = _uiState.value.hints
        val usedLabels = _uiState.value.usedHintLabels
        
        val isHardMode = when (_uiState.value.mode) {
            GuessMode.IMAGE -> _uiState.value.difficulty == ImageDifficulty.HARD
            GuessMode.VOICE -> _uiState.value.voiceDifficulty == VoiceDifficulty.HARD
            GuessMode.SKILL -> _uiState.value.skillDifficulty == SkillDifficulty.HARD
        }
        
        if (isHardMode) return
        
        viewModelScope.launch {
            _uiState.update { it.copy(isLoadingHint = true) }
            
            try {
                val info = getCharacterInfoForShip(ship)
                val availableHints = info.getAvailableHints(usedLabels)
                
                if (availableHints.isEmpty()) {
                    _uiState.update {
                        it.copy(
                            isLoadingHint = false,
                            noMoreHints = true
                        )
                    }
                    return@launch
                }
                
                val newHint = availableHints.random()
                val hintItem = HintItem(
                    id = "${newHint.first}_${System.currentTimeMillis()}",
                    label = newHint.first,
                    value = newHint.second
                )
                
                _uiState.update {
                    it.copy(
                        characterInfo = info,
                        hints = currentHints + hintItem,
                        usedHintLabels = usedLabels + newHint.first,
                        isLoadingHint = false,
                        currentQuestionScore = maxOf(0, currentScore - 1),
                        noMoreHints = false
                    )
                }
            } catch (e: Exception) {
                _uiState.update { 
                    it.copy(
                        isLoadingHint = false,
                        errorMessage = "获取提示失败"
                    ) 
                }
            }
        }
    }

    fun showAnswer() {
        val state = _uiState.value
        val ship = state.currentShip ?: return

        // 看图模式的题面本身就是立绘，揭示答案时直接复用；听音/识技能没有题面图，
        // 需要一个揭示用的立绘（异步补，落地由代际守卫）
        if (state.mode != GuessMode.IMAGE) {
            prepareRewardImage(ship, questionGeneration)
        }
        
        _uiState.update {
            it.copy(
                showAnswer = true,
                lastResult = GuessResult.SKIPPED,
                currentQuestionScore = 0,
                rewardImageUrl = if (state.mode == GuessMode.IMAGE) state.currentImageUrl else state.rewardImageUrl
            )
        }
    }

    fun checkAnswer() {
        val state = _uiState.value
        // 共享技能校验在飞时忽略重复提交（软键盘 Done 可以绕过按钮的 enabled 态）
        if (state.isChecking) return
        val ship = state.currentShip ?: return

        val userInput = normalizeName(state.inputText)
        if (userInput.isBlank()) {
            _uiState.update { it.copy(errorMessage = "请输入舰娘名字后再提交") }
            return
        }

        _uiState.update { it.copy(lastResult = null, errorMessage = null) }

        if (matchesShip(userInput, ship)) {
            onAnswerCorrect(ship)
            return
        }

        // 识技能题面是"一组技能图标"，通用技能会被多艘舰娘共享（专属弹幕/阵营技能尤其常见）。
        // 玩家答出另一艘**同样持有整套技能**的舰娘，语义上是正确答案 —— 但必须**实际校验**
        // 对方技能表里确实覆盖了题面全部技能才判对，网络失败一律按答错处理，不做无条件放行。
        val questionSkills = state.currentSkills
        if (state.mode == GuessMode.SKILL && questionSkills.isNotEmpty()) {
            val guessed = findShipByInput(userInput)
            if (guessed != null && guessed.name != ship.name) {
                verifySharedSkillOwner(guessed, questionSkills, ship)
                return
            }
        }

        _uiState.update { it.copy(lastResult = GuessResult.WRONG) }
    }

    /**
     * 名字是否指向 [ship]。
     *
     * 判分口径与旧版完全一致：官方名 / 去改造后缀的基名 / 别名表，四路任一命中即算对。
     * 抽成函数是为了让"正确答案"与"共享技能校验里识别另一位持有者"用同一套规则，
     * 否则两条路径对同一个输入可能给出不同结论。
     */
    private fun matchesShip(normalizedInput: String, ship: Ship): Boolean {
        val official = normalizeName(ship.name)
        val userBase = getBaseName(normalizedInput)
        val officialBase = getBaseName(official)
        val aliases = NAME_ALIASES[ship.name].orEmpty().map(::normalizeName)
        val aliasBaseNames = aliases.map(::getBaseName)

        return normalizedInput == official ||
            userBase == officialBase ||
            aliases.contains(normalizedInput) ||
            aliasBaseNames.contains(userBase)
    }

    /** 在当前候选池里按玩家输入反查舰娘（共享技能校验用） */
    private fun findShipByInput(normalizedInput: String): Ship? {
        if (normalizedInput.isBlank()) return null
        return candidateShips().firstOrNull { matchesShip(normalizedInput, it) }
    }

    /**
     * 校验 [guessed] 是否同样持有题面的**整套**技能；通过则按答对结算，并标注"共享技能"。
     *
     * 为什么要整套：题面已经展示该舰娘的 1–3 个图标，只命中其中任意一个（例如人人有的
     * "专用弹幕"）就把别家舰娘判对，会让题面变成噪声。要求覆盖全部技能，才等价于
     * "你答的这艘船在技能上与答案一致"。
     *
     * 代际守卫：校验要走一次网络（缓存命中则是同步的），期间玩家可能已点"下一题"，
     * 迟到结果不得污染新题状态。
     */
    private fun verifySharedSkillOwner(guessed: Ship, requiredSkills: List<SkillRow>, targetShip: Ship) {
        val generation = questionGeneration
        val required = requiredSkills.map { normalizeSkillName(it.name) }.toSet()
        viewModelScope.launch {
            _uiState.update { it.copy(isChecking = true) }
            val shares = try {
                val guessedSkills = getSkillsForShip(guessed)
                    .map { normalizeSkillName(it.name) }
                    .toSet()
                guessedSkills.containsAll(required)
            } catch (_: Exception) {
                false
            }
            if (generation != questionGeneration) return@launch
            _uiState.update { it.copy(isChecking = false) }
            if (shares) {
                onAnswerCorrect(targetShip)
                _uiState.update { it.copy(sharedSkillOwner = guessed.name) }
            } else {
                _uiState.update { it.copy(lastResult = GuessResult.WRONG) }
            }
        }
    }

    private fun onAnswerCorrect(ship: Ship) {
        val currentQScore = _uiState.value.currentQuestionScore
        val currentGameState = _uiState.value.score

        // 奖励素材协程携带当前代际：若用户答对后立即点"下一题"，代际递增会使
        // 迟到的奖励协程作废，避免旧舰娘的奖励语音/立绘覆盖新题状态
        val generation = questionGeneration

        when (_uiState.value.mode) {
            GuessMode.IMAGE -> prepareRewardVoice(ship, generation)
            GuessMode.VOICE -> prepareRewardImage(ship, generation)
            // 识技能：题面是图标，答对后用立绘揭晓这位舰娘
            GuessMode.SKILL -> prepareRewardImage(ship, generation)
        }

        // 仅更新得分相关字段，totalQuestions 在 goToNextQuestion/showSettlement 中统一计数
        _uiState.update {
            it.copy(
                lastResult = GuessResult.CORRECT,
                errorMessage = null,
                score = currentGameState.copy(
                    correctAnswers = currentGameState.correctAnswers + 1,
                    totalScore = currentGameState.totalScore + currentQScore
                )
            )
        }
    }

    /**
     * 统一的"下一题"入口（替代原 skipToNextQuestion）。
     *
     * 在 ViewModel 内部读取最新状态（避免 Compose 闭包捕获陈旧状态），
     * 先将当前题目计入总分（如果尚未计入），再加载下一题。
     *
     * 此方法修复了原 onNext 闭包因 state.lastResult 陈旧导致的双重计数问题：
     * - 答对时 onAnswerCorrect 只更新 correctAnswers/totalScore，不再 +1 totalQuestions
     * - 答错/跳过/未作答均在此方法统一计数，由 [currentQuestionCounted] 标志保证每题只计一次
     */
    fun goToNextQuestion() {
        countCurrentQuestionIfNeeded()
        clearResult()
        loadNextQuestion()
    }

    /**
     * 将当前题目计入总分（如果尚未计入）。
     *
     * 计数规则（每题只计一次，由 [GuessGameUiState.currentQuestionCounted] 标志保证）：
     * - CORRECT：totalQuestions +1（correctAnswers/totalScore 已在 onAnswerCorrect 中更新）
     * - WRONG：totalQuestions +1
     * - SKIPPED：totalQuestions +1, skippedQuestions +1
     * - null（未作答直接下一题）：totalQuestions +1, skippedQuestions +1
     *
     * 同时累计 hintsUsedTotal 和 totalPossibleScore（每题固定 +10）。
     */
    private fun countCurrentQuestionIfNeeded() {
        val state = _uiState.value
        // 已计入或无当前题目 → 跳过
        if (state.currentQuestionCounted || state.currentShip == null) return

        val lastResult = state.lastResult
        val isSkipped = lastResult == GuessResult.SKIPPED || lastResult == null
        val hintsUsedThisQuestion = state.hints.size
        // 满分按"本题实际可得"累加（简单 10 / 困难 50）：
        // 旧实现写死 +10，困难模式的结算会显示"总得分 150 / 满分 30"
        val possibleThisQuestion = state.currentQuestionPossibleScore

        _uiState.update {
            it.copy(
                currentQuestionCounted = true,
                score = it.score.copy(
                    totalQuestions = it.score.totalQuestions + 1,
                    skippedQuestions = it.score.skippedQuestions + (if (isSkipped) 1 else 0),
                    hintsUsedTotal = it.score.hintsUsedTotal + hintsUsedThisQuestion,
                    totalPossibleScore = it.score.totalPossibleScore + possibleThisQuestion
                )
            )
        }
    }

    /**
     * 显示结算弹窗（不保存历史记录）。
     *
     * 仅完成当前题目的计数，并展示结算 UI。
     * 历史记录的保存推迟到用户在结算弹窗中"确认退出"时执行（见 [confirmExitAndSave]），
     * 确保用户点击"继续游戏"后不会产生历史记录，且继续作答的成绩能在最终退出时被正确更新。
     */
    fun showSettlement() {
        // 先将当前未计数的题目计入总分（例如答对后直接退出，未点"下一题"）
        countCurrentQuestionIfNeeded()
        _uiState.update { it.copy(showSettlement = true) }
    }

    fun hideSettlement() {
        _uiState.update { it.copy(showSettlement = false) }
    }

    /**
     * 用户在结算弹窗中"确认退出"时调用：保存（或更新）历史记录。
     *
     * 保存策略：
     * - 首次退出：通过 [GuessHistoryDao.upsertBySession] 走 insert 路径
     * - 继续作答后再次退出：同一 sessionId 走 update 路径，更新成绩而非重复插入
     *
     * 边界条件处理：
     * - 使用 [NonCancellable] 上下文，确保即使用户快速返回或页面刷新导致
     *   ViewModel 即将销毁，保存操作也能完整执行，避免数据丢失
     * - 仅当 totalQuestions > 0 时保存，避免空对局产生无意义记录
     * - 网络异常不影响本地保存（历史记录仅写入本地 Room 数据库，无网络依赖）
     *
     * @return 本次会话的 sessionId（供调用方做后续处理，如跳转历史记录）
     */
    fun confirmExitAndSave() {
        val state = _uiState.value
        val score = state.score
        if (score.totalQuestions == 0) return

        val mode = when (state.mode) {
            GuessMode.IMAGE -> "IMAGE"
            GuessMode.VOICE -> "VOICE"
            GuessMode.SKILL -> "SKILL"
        }
        val difficulty = when (state.mode) {
            GuessMode.IMAGE -> if (state.difficulty == ImageDifficulty.HARD) "HARD" else "EASY"
            GuessMode.VOICE -> if (state.voiceDifficulty == VoiceDifficulty.HARD) "HARD" else "EASY"
            GuessMode.SKILL -> if (state.skillDifficulty == SkillDifficulty.HARD) "HARD" else "EASY"
        }
        val currentSessionId = gameSessionId
        val record = GuessHistory(
            mode = mode,
            difficulty = difficulty,
            totalQuestions = score.totalQuestions,
            correctAnswers = score.correctAnswers,
            totalScore = score.totalScore,
            hintsUsedTotal = score.hintsUsedTotal,
            skippedQuestions = score.skippedQuestions,
            totalPossibleScore = score.totalPossibleScore,
            timestamp = System.currentTimeMillis(),
            accuracy = score.accuracy,
            averageScore = score.averageScore,
            sessionId = currentSessionId
        )
        // NonCancellable：保证保存操作在 ViewModel 销毁前完成（页面刷新/快速返回场景）
        viewModelScope.launch(NonCancellable) {
            try {
                historyDao.upsertBySession(record)
                hasSavedHistory = true
            } catch (_: Exception) {
                // 本地数据库写入失败时静默处理，避免阻塞退出流程
                // 数据丢失风险已通过 sessionId 唯一索引 + upsert 机制最小化
            }
        }
    }

    private suspend fun getGalleryForShip(ship: Ship): ShipGallery {
        return CacheManager.getOrPutSuspend(CacheNamespaces.SHIP_GALLERY, ship.name) {
            repository.fetchShipGallery(ship.name, ship.archiveType)
        }
    }

    private suspend fun getVoicesForShip(ship: Ship): List<VoiceLine> {
        return CacheManager.getOrPutSuspend(CacheNamespaces.SHIP_VOICES, ship.name) {
            val (voices, _, _) = getVoicesUseCase(ship.name)
            voices
        }
    }

    /**
     * 舰娘技能表（识技能题库）。
     *
     * 缓存键带 archiveType：同名舰娘在不同档案下是两条记录（DOCK/STUDENT），
     * 共用 key 会让某一边的空结果覆盖另一边。30 分钟 TTL 与语音/立绘一致；
     * 期间"这艘船没有可用技能"的负结果也被缓存，避免出题失败时反复打网络。
     */
    private suspend fun getSkillsForShip(ship: Ship): List<SkillRow> {
        val cacheKey = "${ship.archiveType}:${ship.name}"
        return CacheManager.getOrPutSuspend(CacheNamespaces.SHIP_SKILLS, cacheKey) {
            shipDetailRepository.loadSkills(
                shipName = ship.name,
                archiveType = ship.archiveType,
                requireIcons = true
            )
        }
    }

    private suspend fun getCharacterInfoForShip(ship: Ship): ShipCharacterInfo {
        val cacheKey = "char_${ship.name}"
        CacheManager.remove(CacheNamespaces.SHIP_LIST, cacheKey)
        
        val info = repository.fetchCharacterInfo(ship.name, ship.archiveType)
        CacheManager.put(CacheNamespaces.SHIP_LIST, cacheKey, info)
        return info
    }

    private fun prepareRewardVoice(ship: Ship, generation: Long) {
        viewModelScope.launch {
            try {
                val voices = getVoicesForShip(ship)
                if (voices.isEmpty()) return@launch
                val voice = voices.random()
                // 迟到的奖励协程不得覆盖新题：currentVoice 被覆盖会导致听音界面
                // 自动播放旧舰娘的语音，而题目已是新舰娘
                if (generation != questionGeneration) return@launch
                _uiState.update {
                    it.copy(
                        currentVoice = voice,
                        errorMessage = null
                    )
                }
            } catch (_: Exception) {
            }
        }
    }

    private fun prepareRewardImage(ship: Ship, generation: Long) {
        viewModelScope.launch {
            try {
                val gallery = getGalleryForShip(ship)
                val candidates = gallery.illustrations.filter { it.second.isNotBlank() }
                if (candidates.isEmpty()) return@launch
                val chosen = candidates.random()
                // 迟到的奖励协程不得把旧舰娘立绘写进新一轮题目的状态
                if (generation != questionGeneration) return@launch
                _uiState.update {
                    it.copy(
                        rewardImageUrl = chosen.second,
                        errorMessage = null
                    )
                }
            } catch (_: Exception) {
            }
        }
    }

    /**
     * 随机切换当前舰娘的另一条语音（"换一条语音"按钮）。
     *
     * 只排除当前正在播的这条语音本身（VoiceLine 是 data class，按整条相等比较），
     * 同一场景（scene）下的其他条目也参与随机，随机池更大、更不容易重复；
     * 该舰娘只有一条语音时回退为重播当前条。
     *
     * 选中后仅更新 [GuessGameUiState.currentVoice] 与 [GuessGameUiState.currentDialogueId]，
     * 实际播放由界面监听 dialogueId 变化统一触发，避免回调+自动播双重播放。
     */
    fun playRandomVoiceForCurrentShip(onVoiceSelected: (String, String) -> Unit) {
        val ship = _uiState.value.currentShip ?: return
        val currentVoice = _uiState.value.currentVoice

        viewModelScope.launch {
            try {
                val voices = getVoicesForShip(ship)
                if (voices.isEmpty()) return@launch

                val availableVoices = voices.filter { it != currentVoice }

                val candidates = if (availableVoices.isNotEmpty()) availableVoices else voices
                val selectedVoice = candidates.random()
                val newDialogueId = System.currentTimeMillis()

                _uiState.update {
                    it.copy(
                        currentVoice = selectedVoice,
                        currentDialogueId = newDialogueId
                    )
                }

                if (selectedVoice.audioUrl.isNotBlank()) {
                    onVoiceSelected(selectedVoice.audioUrl, selectedVoice.dialogue)
                }
            } catch (_: Exception) {
            }
        }
    }
}

private fun normalizeName(raw: String): String {
    return raw
        .trim()
        .lowercase()
        .replace("\\s+".toRegex(), "")
        .replace("(", "（")
        .replace(")", "）")
        .replace("[", "［")
        .replace("]", "］")
        .replace("{", "｛")
        .replace("}", "｝")
        .replace(",", "，")
        .replace(";", "；")
        .replace(":", "：")
        .replace("?", "？")
        .replace("!", "！")
        .replace("-", "")
        .replace("－", "")
        .replace("—", "")
        .replace("─", "")
        .replace("_", "")
        .replace("＿", "")
        .replace("·", "")
        .replace("・", "")
        .replace("•", "")
        .replace("‧", "")
        .replace(".", "")
        .replace("。", "")
        .replace(" ", "")
        .replace("　", "")
        .replace("\t", "")
        .replace("\n", "")
        .replace("\r", "")
}

private fun getBaseName(name: String): String {
    val normalized = normalizeName(name)
    
    val suffixPatterns = listOf(
        "（μ兵装）" to "",
        "（μ兵裝）" to "",
        "μ兵装" to "",
        "μ兵裝" to "",
        "改" to "",
        "ii" to "",
        "Ⅱ" to "",
        "ⅱ" to "",
        "（改造）" to "",
        ".改" to "",
    )
    
    var result = normalized
    for ((suffix, replacement) in suffixPatterns) {
        result = result.replace(suffix, replacement)
    }
    
    return result
}

private val NAME_ALIASES: Map<String, List<String>> = mapOf(
    "萨拉托加" to listOf("钢板", "saratoga", "小加加")
)

/**
 * 技能名归一化（"共享技能"判定用）。
 *
 * 只削空白与大小写，**不做**去括号/去罗马数字：`专属弹幕-Z23I（II）` 与 `专属弹幕-Z23I`
 * 在 wiki 上是两个不同条目，宽松匹配会把"相近但不同"的技能误判成同一个。
 */
private fun normalizeSkillName(raw: String): String =
    raw.replace("\\s+".toRegex(), "").replace("　", "").lowercase()

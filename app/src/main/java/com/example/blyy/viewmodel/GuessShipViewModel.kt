package com.example.blyy.viewmodel

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.example.blyy.data.model.Ship
import com.example.blyy.data.model.ShipCharacterInfo
import com.example.blyy.data.model.ShipGallery
import com.example.blyy.data.model.VoiceLine
import com.example.blyy.data.repository.ShipRepository
import com.example.blyy.domain.GetVoicesUseCase
import com.example.blyy.util.CacheManager
import com.example.blyy.util.CacheNamespaces
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlin.random.Random
import javax.inject.Inject

enum class GuessMode {
    IMAGE,
    VOICE
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
    val currentShip: Ship? = null,
    val currentImageUrl: String? = null,
    val cropRegion: CropRegion? = null,
    val currentVoice: VoiceLine? = null,
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
    val noMoreHints: Boolean = false
)

@HiltViewModel
class GuessShipViewModel @Inject constructor(
    private val repository: ShipRepository,
    private val getVoicesUseCase: GetVoicesUseCase
) : ViewModel() {

    private val _uiState = MutableStateFlow(GuessGameUiState())
    val uiState: StateFlow<GuessGameUiState> = _uiState.asStateFlow()

    private var allShipsCache: List<Ship> = emptyList()
    private val usedShipIndices = mutableSetOf<Int>()
    private var isRefreshingShips = false
    private val playedVoiceKeys = mutableSetOf<String>()

    init {
        viewModelScope.launch {
            repository.allShips.collectLatest { ships ->
                allShipsCache = ships
                usedShipIndices.clear()
            }
        }
    }

    fun setDifficulty(difficulty: ImageDifficulty) {
        _uiState.update { it.copy(difficulty = difficulty) }
    }

    fun setVoiceDifficulty(difficulty: VoiceDifficulty) {
        _uiState.update { it.copy(voiceDifficulty = difficulty) }
    }

    fun startImageGame() {
        _uiState.update {
            it.copy(
                isActive = true,
                mode = GuessMode.IMAGE,
                lastResult = null,
                inputText = "",
                errorMessage = null,
                rewardImageUrl = null,
                characterInfo = null,
                hints = emptyList(),
                usedHintLabels = emptySet(),
                cropRegion = null,
                showAnswer = false,
                score = GameScore(),
                showSettlement = false,
                currentQuestionScore = 10,
                noMoreHints = false
            )
        }
        loadNextQuestion()
    }

    fun startVoiceGame() {
        _uiState.update {
            it.copy(
                isActive = true,
                mode = GuessMode.VOICE,
                lastResult = null,
                inputText = "",
                errorMessage = null,
                rewardImageUrl = null,
                characterInfo = null,
                hints = emptyList(),
                usedHintLabels = emptySet(),
                cropRegion = null,
                showAnswer = false,
                score = GameScore(),
                showSettlement = false,
                currentQuestionScore = 10,
                noMoreHints = false
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

    fun loadNextQuestion() {
        val ships = allShipsCache
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

        val availableIndices = ships.indices.filter { it !in usedShipIndices }
        if (availableIndices.isEmpty()) {
            usedShipIndices.clear()
            loadNextQuestionInternal(ships, attempt = 0)
        } else {
            val randomIndex = availableIndices.random()
            usedShipIndices.add(randomIndex)
            val ship = ships[randomIndex]
            when (_uiState.value.mode) {
                GuessMode.IMAGE -> prepareImageQuestion(ship, ships, 0)
                GuessMode.VOICE -> prepareVoiceQuestion(ship, ships, 0)
            }
        }
    }

    private fun loadNextQuestionInternal(ships: List<Ship>, attempt: Int) {
        if (attempt >= 5) {
            _uiState.update {
                it.copy(
                    errorMessage = "未能生成有效题目，请稍后再试。",
                    currentShip = null,
                    currentImageUrl = null,
                    currentVoice = null,
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

        val ship = ships.random()
        when (_uiState.value.mode) {
            GuessMode.IMAGE -> prepareImageQuestion(ship, ships, attempt)
            GuessMode.VOICE -> prepareVoiceQuestion(ship, ships, attempt)
        }
    }

    private fun prepareImageQuestion(ship: Ship, ships: List<Ship>, attempt: Int) {
        viewModelScope.launch {
            try {
                val gallery = getGalleryForShip(ship)
                val candidates = gallery.illustrations.filter { it.second.isNotBlank() }

                if (candidates.isEmpty()) {
                    loadNextQuestionInternal(ships, attempt + 1)
                    return@launch
                }

                val chosen = candidates.random()
                val cropRegion = if (_uiState.value.difficulty == ImageDifficulty.HARD) {
                    generateRandomCropRegion()
                } else {
                    null
                }
                
                val questionScore = if (_uiState.value.difficulty == ImageDifficulty.HARD) 50 else 10
                
                _uiState.update {
                    it.copy(
                        currentShip = ship,
                        currentImageUrl = chosen.second,
                        currentVoice = null,
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
                        noMoreHints = _uiState.value.difficulty == ImageDifficulty.HARD
                    )
                }
            } catch (e: Exception) {
                loadNextQuestionInternal(ships, attempt + 1)
            }
        }
    }

    private fun generateRandomCropRegion(): CropRegion {
        val cropTypes = CropType.entries
        val selectedType = cropTypes.random()
        
        val minCropSize = 0.40f
        val maxCropSize = 0.60f
        val baseCropSize = Random.nextFloat() * (maxCropSize - minCropSize) + minCropSize
        
        val aspectVariation = Random.nextFloat() * 0.15f
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
                    Random.nextFloat() * 0.15f
                )
            }
            CropType.LOWER -> {
                val maxStartX = 1f - finalCropWidth
                val maxStartY = 1f - finalCropHeight
                Pair(
                    Random.nextFloat() * maxStartX,
                    maxStartY - Random.nextFloat() * 0.15f
                )
            }
            CropType.LEFT -> {
                val maxStartY = 1f - finalCropHeight
                Pair(
                    Random.nextFloat() * 0.15f,
                    Random.nextFloat() * maxStartY
                )
            }
            CropType.RIGHT -> {
                val maxStartX = 1f - finalCropWidth
                val maxStartY = 1f - finalCropHeight
                Pair(
                    maxStartX - Random.nextFloat() * 0.15f,
                    Random.nextFloat() * maxStartY
                )
            }
            CropType.UPPER_LEFT -> {
                Pair(
                    Random.nextFloat() * 0.15f,
                    Random.nextFloat() * 0.15f
                )
            }
            CropType.UPPER_RIGHT -> {
                val maxStartX = 1f - finalCropWidth
                Pair(
                    maxStartX - Random.nextFloat() * 0.15f,
                    Random.nextFloat() * 0.15f
                )
            }
            CropType.LOWER_LEFT -> {
                val maxStartY = 1f - finalCropHeight
                Pair(
                    Random.nextFloat() * 0.15f,
                    maxStartY - Random.nextFloat() * 0.15f
                )
            }
            CropType.LOWER_RIGHT -> {
                val maxStartX = 1f - finalCropWidth
                val maxStartY = 1f - finalCropHeight
                Pair(
                    maxStartX - Random.nextFloat() * 0.15f,
                    maxStartY - Random.nextFloat() * 0.15f
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

    private fun prepareVoiceQuestion(ship: Ship, ships: List<Ship>, attempt: Int) {
        viewModelScope.launch {
            try {
                val voices = getVoicesForShip(ship)
                if (voices.isEmpty()) {
                    loadNextQuestionInternal(ships, attempt + 1)
                    return@launch
                }

                val voice = voices.random()
                val questionScore = if (_uiState.value.voiceDifficulty == VoiceDifficulty.HARD) 50 else 10
                
                _uiState.update {
                    it.copy(
                        currentShip = ship,
                        currentVoice = voice,
                        currentImageUrl = null,
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
                        noMoreHints = _uiState.value.voiceDifficulty == VoiceDifficulty.HARD
                    )
                }
            } catch (e: Exception) {
                _uiState.update {
                    it.copy(
                        errorMessage = e.message ?: "加载语音失败",
                        currentShip = null,
                        currentVoice = null
                    )
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
        
        if (state.mode == GuessMode.VOICE) {
            prepareRewardImage(ship)
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
        val ship = state.currentShip ?: return

        val userInput = normalizeName(state.inputText)
        if (userInput.isBlank()) {
            _uiState.update { it.copy(errorMessage = "请输入舰娘名字后再提交") }
            return
        }

        _uiState.update { it.copy(lastResult = null, errorMessage = null) }

        val official = normalizeName(ship.name)
        val aliases = NAME_ALIASES[ship.name].orEmpty().map(::normalizeName)

        val isCorrect = userInput == official || aliases.contains(userInput)

        if (isCorrect) {
            onAnswerCorrect(ship)
        } else {
            _uiState.update { it.copy(lastResult = GuessResult.WRONG) }
        }
    }

    private fun onAnswerCorrect(ship: Ship) {
        val currentQScore = _uiState.value.currentQuestionScore
        val currentGameState = _uiState.value.score
        val hintsUsedThisQuestion = _uiState.value.hints.size
        
        when (_uiState.value.mode) {
            GuessMode.IMAGE -> prepareRewardVoice(ship)
            GuessMode.VOICE -> prepareRewardImage(ship)
        }
        
        _uiState.update { 
            it.copy(
                lastResult = GuessResult.CORRECT, 
                errorMessage = null,
                score = currentGameState.copy(
                    totalQuestions = currentGameState.totalQuestions + 1,
                    correctAnswers = currentGameState.correctAnswers + 1,
                    totalScore = currentGameState.totalScore + currentQScore,
                    hintsUsedTotal = currentGameState.hintsUsedTotal + hintsUsedThisQuestion,
                    totalPossibleScore = currentGameState.totalPossibleScore + 10
                )
            )
        }
    }

    fun skipToNextQuestion() {
        val currentGameState = _uiState.value.score
        val wasSkipped = _uiState.value.lastResult == GuessResult.SKIPPED
        val hintsUsedThisQuestion = _uiState.value.hints.size
        
        _uiState.update {
            it.copy(
                score = currentGameState.copy(
                    totalQuestions = currentGameState.totalQuestions + 1,
                    skippedQuestions = currentGameState.skippedQuestions + (if (wasSkipped) 1 else 0),
                    hintsUsedTotal = currentGameState.hintsUsedTotal + hintsUsedThisQuestion,
                    totalPossibleScore = currentGameState.totalPossibleScore + 10
                )
            )
        }
        
        loadNextQuestion()
    }

    fun showSettlement() {
        _uiState.update { it.copy(showSettlement = true) }
    }

    fun hideSettlement() {
        _uiState.update { it.copy(showSettlement = false) }
    }

    private suspend fun getGalleryForShip(ship: Ship): ShipGallery {
        return CacheManager.getOrPutSuspend(CacheNamespaces.SHIP_GALLERY, ship.name) {
            repository.fetchShipGallery(ship.name)
        }
    }

    private suspend fun getVoicesForShip(ship: Ship): List<VoiceLine> {
        return CacheManager.getOrPutSuspend(CacheNamespaces.SHIP_VOICES, ship.name) {
            val (voices, _, _) = getVoicesUseCase(ship.name)
            voices
        }
    }

    private suspend fun getCharacterInfoForShip(ship: Ship): ShipCharacterInfo {
        val cacheKey = "char_${ship.name}"
        CacheManager.remove(CacheNamespaces.SHIP_LIST, cacheKey)
        
        val info = repository.fetchCharacterInfo(ship.name)
        CacheManager.put(CacheNamespaces.SHIP_LIST, cacheKey, info)
        return info
    }

    private fun prepareRewardVoice(ship: Ship) {
        viewModelScope.launch {
            try {
                val voices = getVoicesForShip(ship)
                if (voices.isEmpty()) return@launch
                val voice = voices.random()
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

    private fun prepareRewardImage(ship: Ship) {
        viewModelScope.launch {
            try {
                val gallery = getGalleryForShip(ship)
                val candidates = gallery.illustrations.filter { it.second.isNotBlank() }
                if (candidates.isEmpty()) return@launch
                val chosen = candidates.random()
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

    fun playRandomVoiceForCurrentShip(onVoiceSelected: (String, String) -> Unit) {
        val ship = _uiState.value.currentShip ?: return
        val currentVoiceKey = _uiState.value.currentVoice?.let { "${ship.name}_${it.scene}" }
        
        viewModelScope.launch {
            try {
                val voices = getVoicesForShip(ship)
                if (voices.isEmpty()) return@launch
                
                val availableVoices = voices.filter { voice ->
                    val key = "${ship.name}_${voice.scene}"
                    key != currentVoiceKey
                }
                
                val candidates = if (availableVoices.isNotEmpty()) availableVoices else voices
                val selectedVoice = candidates.random()
                
                _uiState.update {
                    it.copy(currentVoice = selectedVoice)
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
        .replace("-", "")
        .replace("−", "")
        .replace("–", "")
        .replace("—", "")
        .replace("_", "")
        .replace(" ", "")
        .replace(" ", "")
        .replace("·", "")
        .replace("・", "")
        .replace("•", "")
        .replace("·", "")
        .replace("‑", "")
        .replace(".", "")
        .replace("。", "")
        .replace("(", "")
        .replace(")", "")
        .replace("（", "")
        .replace("）", "")
        .replace("[", "")
        .replace("]", "")
        .replace("【", "")
        .replace("】", "")
        .replace("/", "")
        .replace("／", "")
        .replace("\\", "")
        .replace("、", "")
        .replace(",", "")
        .replace("，", "")
        .replace(":", "")
        .replace("：", "")
        .replace(";", "")
        .replace("；", "")
        .replace("!", "")
        .replace("！", "")
        .replace("?", "")
        .replace("？", "")
        .replace("\"", "")
        .replace(""", "")
        .replace(""", "")
        .replace("'", "")
        .replace("'", "")
        .replace("'", "")
}

private val NAME_ALIASES: Map<String, List<String>> = mapOf(
    "拉菲" to listOf("拉菲改", "拉菲II", "拉菲μ兵装", "拉菲μ", "laffey", "laffeyii"),
    "企业" to listOf("企业改", "enterprise"),
    "赤城" to listOf("赤城改", "akagi"),
    "加贺" to listOf("加贺改", "kaga"),
    "苍龙" to listOf("苍龙改", "souryuu"),
    "飞龙" to listOf("飞龙改", "hiryuu"),
    "翔鹤" to listOf("翔鹤改", "shoukaku"),
    "瑞鹤" to listOf("瑞鹤改", "zuikaku"),
    "大凤" to listOf("大凤改", "taihou"),
    "俾斯麦" to listOf("俾斯麦改", "bismarck"),
    "提尔比茨" to listOf("提尔比茨改", "tirpitz"),
    "欧根亲王" to listOf("欧根亲王改", "prinz eugen", "prinz"),
    "胡德" to listOf("胡德改", "hood"),
    "威尔士亲王" to listOf("威尔士亲王改", "prince of wales"),
    "伊丽莎白女王" to listOf("伊丽莎白女王改", "queen elizabeth"),
    "厌战" to listOf("厌战改", "warspite"),
    "光辉" to listOf("光辉改", "illustrious"),
    "胜利" to listOf("胜利改", "victorious"),
    "皇家方舟" to listOf("皇家方舟改", "ark royal", "ark royal"),
    "独角兽" to listOf("独角兽改", "unicorn"),
    "光辉" to listOf("光辉改", "illustrious"),
    "可畏" to listOf("可畏改", "formidable"),
    "不挠" to listOf("不挠改", "indomitable"),
    "埃塞克斯" to listOf("埃塞克斯改", "essex"),
    "约克城" to listOf("约克城改", "yorktown"),
    "企业" to listOf("企业改", "enterprise"),
    "大黄蜂" to listOf("大黄蜂改", "hornet"),
    "列克星敦" to listOf("列克星敦改", "lexington"),
    "萨拉托加" to listOf("萨拉托加改", "saratoga"),
    "长岛" to listOf("长岛改", "long island"),
    "突击者" to listOf("突击者改", "ranger"),
    "胡蜂" to listOf("胡蜂改", "wasp"),
    "香格里拉" to listOf("香格里拉改", "shangri la"),
    "本宁顿" to listOf("本宁顿改", "bennett"),
    "邦克山" to listOf("邦克山改", "bunker hill"),
    "蒙特雷" to listOf("蒙特雷改", "monterey"),
    "卡萨布兰卡" to listOf("卡萨布兰卡改", "casablanca"),
    "赤城" to listOf("赤城改", "akagi"),
    "加贺" to listOf("加贺改", "kaga"),
    "苍龙" to listOf("苍龙改", "souryuu"),
    "飞龙" to listOf("飞龙改", "hiryuu"),
    "翔鹤" to listOf("翔鹤改", "shoukaku"),
    "瑞鹤" to listOf("瑞鹤改", "zuikaku"),
    "大凤" to listOf("大凤改", "taihou"),
    "云龙" to listOf("云龙改", "unryuu"),
    "天城" to listOf("天城改", "amagi"),
    "葛城" to listOf("葛城改", "katsuragi"),
    "信浓" to listOf("信浓改", "shinano"),
    "凤翔" to listOf("凤翔改", "hosho"),
    "龙骧" to listOf("龙骧改", "ryuujou"),
    "祥凤" to listOf("祥凤改", "shouhou"),
    "飞鹰" to listOf("飞鹰改", "hiyou"),
    "隼鹰" to listOf("隼鹰改", "junyou"),
    "千岁" to listOf("千岁改", "chitose"),
    "千代田" to listOf("千代田改", "chiyoda"),
    "最上" to listOf("最上改", "mogami"),
    "三隈" to listOf("三隈改", "mikuma"),
    "铃谷" to listOf("铃谷改", "suzuya"),
    "熊野" to listOf("熊野改", "kumano"),
    "利根" to listOf("利根改", "tone"),
    "筑摩" to listOf("筑摩改", "chikuma"),
    "古鹰" to listOf("古鹰改", "furutaka"),
    "加古" to listOf("加古改", "kako"),
    "青叶" to listOf("青叶改", "aoba"),
    "衣笠" to listOf("衣笠改", "kinugasa"),
    "妙高" to listOf("妙高改", "myoukou"),
    "那智" to listOf("那智改", "nachi"),
    "足柄" to listOf("足柄改", "ashigara"),
    "羽黑" to listOf("羽黑改", "haguro"),
    "高雄" to listOf("高雄改", "takao"),
    "爱宕" to listOf("爱宕改", "atago"),
    "摩耶" to listOf("摩耶改", "maya"),
    "鸟海" to listOf("鸟海改", "choukai"),
    "伊势" to listOf("伊势改", "ise"),
    "日向" to listOf("日向改", "hyuuga"),
    "扶桑" to listOf("扶桑改", "fusou"),
    "山城" to listOf("山城改", "yamashiro"),
    "金刚" to listOf("金刚改", "kongou"),
    "比睿" to listOf("比睿改", "hiei"),
    "榛名" to listOf("榛名改", "haruna"),
    "雾岛" to listOf("雾岛改", "kirishima"),
    "长门" to listOf("长门改", "nagato"),
    "陆奥" to listOf("陆奥改", "mutsu"),
    "大和" to listOf("大和改", "yamato"),
    "武藏" to listOf("武藏改", "musashi"),
    "伊13" to listOf("伊13改", "i13"),
    "伊19" to listOf("伊19改", "i19"),
    "伊25" to listOf("伊25改", "i25"),
    "伊26" to listOf("伊26改", "i26"),
    "伊56" to listOf("伊56改", "i56"),
    "伊58" to listOf("伊58改", "i58"),
    "伊168" to listOf("伊168改", "i168"),
    "U-81" to listOf("U81", "u81"),
    "U-73" to listOf("U73", "u73"),
    "U-101" to listOf("U101", "u101"),
    "U-522" to listOf("U522", "u522"),
    "U-556" to listOf("U556", "u556"),
    "U-557" to listOf("U557", "u557"),
    "U-110" to listOf("U110", "u110"),
    "U-81" to listOf("U81改", "u81"),
    "U-47" to listOf("U47", "u47"),
    "U-73" to listOf("U73改", "u73"),
    "U-101" to listOf("U101改", "u101"),
    "U-522" to listOf("U522改", "u522"),
    "U-556" to listOf("U556改", "u556"),
    "U-557" to listOf("U557改", "u557"),
    "U-110" to listOf("U110改", "u110"),
    "U-47" to listOf("U47改", "u47"),
    "吸血鬼" to listOf("吸血鬼改", "vampire"),
    "吸血鬼" to listOf("吸血鬼μ兵装", "vampire"),
    "标枪" to listOf("标枪改", "javelin", "标枪μ兵装"),
    "Z23" to listOf("Z23改", "z23", "Z23μ兵装"),
    "绫波" to listOf("绫波改", "ayanami", "绫波μ兵装"),
    "拉菲" to listOf("拉菲改", "laffey", "拉菲μ兵装"),
    "不知火" to listOf("不知火改", "shirayuki"),
    "黑云" to listOf("黑云改", "shirayuki"),
    "白雪" to listOf("白雪改", "shirayuki"),
    "初雪" to listOf("初雪改", "hatsuyuki"),
    "深雪" to listOf("深雪改", "miyuki"),
    "丛云" to listOf("丛云改", "murakumo"),
    "矶波" to listOf("矶波改", "isonami"),
    "绫波" to listOf("绫波改", "ayanami"),
    "敷波" to listOf("敷波改", "shikinami"),
    "吹雪" to listOf("吹雪改", "fubuki"),
    "白雪" to listOf("白雪改", "shirayuki"),
    "初雪" to listOf("初雪改", "hatsuyuki"),
    "深雪" to listOf("深雪改", "miyuki"),
    "丛云" to listOf("丛云改", "murakumo"),
    "矶波" to listOf("矶波改", "isonami"),
    "绫波" to listOf("绫波改", "ayanami"),
    "敷波" to listOf("敷波改", "shikinami"),
    "晓" to listOf("晓改", "akatsuki"),
    "响" to listOf("响改", "hibiki"),
    "雷" to listOf("雷改", "ikazuchi"),
    "电" to listOf("电改", "inazuma"),
    "白露" to listOf("白露改", "shiratsuyu"),
    "时雨" to listOf("时雨改", "shigure"),
    "村雨" to listOf("村雨改", "muratsuyu"),
    "夕立" to listOf("夕立改", "yuudachi"),
    "五月雨" to listOf("五月雨改", "samidare"),
    "春雨" to listOf("春雨改", "harusame"),
    "海风" to listOf("海风改", "umikaze"),
    "山风" to listOf("山风改", "yamakaze"),
    "江风" to listOf("江风改", "kawakaze"),
    "凉月" to listOf("凉月改", "suzutsuki"),
    "秋月" to listOf("秋月改", "akizuki"),
    "初月" to listOf("初月改", "hatsuzuki"),
    "若月" to listOf("若月改", "wakatsuki"),
    "霜月" to listOf("霜月改", "shimotsuki"),
    "冬月" to listOf("冬月改", "fuyutsuki"),
    "春月" to listOf("春月改", "harutsuki"),
    "宵月" to listOf("宵月改", "yoizuki"),
    "花月" to listOf("花月改", "hanazuki"),
    "满潮" to listOf("满潮改", "michishio"),
    "朝潮" to listOf("朝潮改", "asashio"),
    "大潮" to listOf("大潮改", "ooshio"),
    "荒潮" to listOf("荒潮改", "arashio"),
    "霞" to listOf("霞改", "kasumi"),
    "阳炎" to listOf("阳炎改", "kagerou"),
    "不知火" to listOf("不知火改", "shirayuki"),
    "黑云" to listOf("黑云改", "kuroshio"),
    "亲潮" to listOf("亲潮改", "oyashio"),
    "早潮" to listOf("早潮改", "hayashio"),
    "夏潮" to listOf("夏潮改", "natsushio"),
    "雪风" to listOf("雪风改", "yukikaze"),
    "初风" to listOf("初风改", "hatsukaze"),
    "舞风" to listOf("舞风改", "maikaze"),
    "矶风" to listOf("矶风改", "isokaze"),
    "滨风" to listOf("滨风改", "hamakaze"),
    "谷风" to listOf("谷风改", "tanikaze"),
    "浦风" to listOf("浦风改", "urakaze"),
    "浜风" to listOf("浜风改", "hamakaze"),
    "时津风" to listOf("时津风改", "tokitsukaze"),
    "天津风" to listOf("天津风改", "amatsukaze"),
    "秋云" to listOf("秋云改", "akigumo"),
    "夕云" to listOf("夕云改", "yuugumo"),
    "卷云" to listOf("卷云改", "makigumo"),
    "风云" to listOf("风云改", "kazagumo"),
    "长波" to listOf("长波改", "naganami"),
    "卷波" to listOf("卷波改", "makinami"),
    "高波" to listOf("高波改", "takanami"),
    "凉波" to listOf("凉波改", "suzunami"),
    "藤波" to listOf("藤波改", "fujinami"),
    "早波" to listOf("早波改", "hayanami"),
    "滨波" to listOf("滨波改", "hamanami"),
    "冲波" to listOf("冲波改", "okinami"),
    "岸波" to listOf("岸波改", "kishinami"),
    "朝霜" to listOf("朝霜改", "asashimo"),
    "早霜" to listOf("早霜改", "hayashimo"),
    "清霜" to listOf("清霜改", "kiyoshimo"),
    "岛风" to listOf("岛风改", "shimakaze"),
    "Z1" to listOf("Z1改", "z1"),
    "Z2" to listOf("Z2改", "z2"),
    "Z3" to listOf("Z3改", "z3"),
    "Z18" to listOf("Z18改", "z18"),
    "Z19" to listOf("Z19改", "z19"),
    "Z20" to listOf("Z20改", "z20"),
    "Z21" to listOf("Z21改", "z21"),
    "Z22" to listOf("Z22改", "z22"),
    "Z23" to listOf("Z23改", "z23"),
    "Z24" to listOf("Z24改", "z24"),
    "Z25" to listOf("Z25改", "z25"),
    "Z26" to listOf("Z26改", "z26"),
    "Z28" to listOf("Z28改", "z28"),
    "Z30" to listOf("Z30改", "z30"),
    "Z31" to listOf("Z31改", "z31"),
    "Z32" to listOf("Z32改", "z32"),
    "Z33" to listOf("Z33改", "z33"),
    "Z34" to listOf("Z34改", "z34"),
    "Z35" to listOf("Z35改", "z35"),
    "Z36" to listOf("Z36改", "z36"),
    "Z37" to listOf("Z37改", "z37"),
    "Z38" to listOf("Z38改", "z38"),
    "Z39" to listOf("Z39改", "z39"),
    "Z40" to listOf("Z40改", "z40"),
    "Z41" to listOf("Z41改", "z41"),
    "Z42" to listOf("Z42改", "z42"),
    "Z43" to listOf("Z43改", "z43"),
    "Z44" to listOf("Z44改", "z44"),
    "Z45" to listOf("Z45改", "z45"),
    "Z46" to listOf("Z46改", "z46"),
    "Z47" to listOf("Z47改", "z47"),
    "Z48" to listOf("Z48改", "z48"),
    "Z49" to listOf("Z49改", "z49"),
    "Z50" to listOf("Z50改", "z50"),
    "Z51" to listOf("Z51改", "z51"),
    "卡辛" to listOf("卡辛改", "cassin"),
    "唐斯" to listOf("唐斯改", "downes"),
    "麦考尔" to listOf("麦考尔改", "mccall"),
    "克雷文" to listOf("克雷文改", "craven"),
    "莫里" to listOf("莫里改", "maury"),
    "格里德利" to listOf("格里德利改", "gridley"),
    "弗莱彻" to listOf("弗莱彻改", "fletcher"),
    "拉德福特" to listOf("拉德福特改", "radford"),
    "杰金斯" to listOf("杰金斯改", "jenkins"),
    "尼古拉斯" to listOf("尼古拉斯改", "nicholas"),
    "贝利" to listOf("贝利改", "bailey"),
    "撒切尔" to listOf("撒切尔改", "thatcher"),
    "沙利文" to listOf("沙利文改", "the sullivans"),
    "西格斯比" to listOf("西格斯比改", "sigsbee"),
    "布什" to listOf("布什改", "bush"),
    "威廉·D·波特" to listOf("威廉d波特", "william d porter", "william"),
    "哈尔福德" to listOf("哈尔福德改", "halford"),
    "埃尔德里奇" to listOf("埃尔德里奇改", "eldridge"),
    "弗莱彻" to listOf("弗莱彻改", "fletcher"),
    "查尔斯·奥斯本" to listOf("查尔斯奥斯本", "charles ausburne"),
    "撒切尔" to listOf("撒切尔改", "thatcher"),
    "希尔曼" to listOf("希尔曼改", "hillman"),
    "拉德福特" to listOf("拉德福特改", "radford"),
    "杰金斯" to listOf("杰金斯改", "jenkins"),
    "尼古拉斯" to listOf("尼古拉斯改", "nicholas"),
    "贝利" to listOf("贝利改", "bailey"),
    "布什" to listOf("布什改", "bush"),
    "黑泽伍德" to listOf("黑泽伍德改", "haze"),
    "约翰斯顿" to listOf("约翰斯顿改", "johnston"),
    "弗莱彻" to listOf("弗莱彻改", "fletcher"),
    "斯彭斯" to listOf("斯彭斯改", "spence"),
    "英格拉罕" to listOf("英格拉罕改", "ingraham"),
    "布里斯托尔" to listOf("布里斯托尔改", "bristol"),
    "桑普森" to listOf("桑普森改", "sampson"),
    "艾伦·M·萨姆纳" to listOf("艾伦m萨姆纳", "allen m sumner"),
    "英格拉罕" to listOf("英格拉罕改", "ingraham"),
    "库珀" to listOf("库珀改", "cooper"),
    "艾伦·M·萨姆纳" to listOf("艾伦m萨姆纳", "allen m sumner"),
    "莫里森" to listOf("莫里森改", "morrison"),
    "波特兰" to listOf("波特兰改", "portland"),
    "印第安纳波利斯" to listOf("印第安纳波利斯改", "indianapolis"),
    "印第安纳波利斯" to listOf("印第安纳波利斯μ兵装", "indianapolis"),
    "芝加哥" to listOf("芝加哥改", "chicago"),
    "北安普顿" to listOf("北安普顿改", "northampton"),
    "休斯顿" to listOf("休斯顿改", "houston"),
    "彭萨科拉" to listOf("彭萨科拉改", "pensacola"),
    "盐湖城" to listOf("盐湖城改", "salt lake city"),
    "新奥尔良" to listOf("新奥尔良改", "new orleans"),
    "明尼阿波利斯" to listOf("明尼阿波利斯改", "minneapolis"),
    "阿斯托利亚" to listOf("阿斯托利亚改", "astoria"),
    "昆西" to listOf("昆西改", "quincy"),
    "文森斯" to listOf("文森斯改", "vincennes"),
    "威奇塔" to listOf("威奇塔改", "wichita"),
    "巴尔的摩" to listOf("巴尔的摩改", "baltimore"),
    "波士顿" to listOf("波士顿改", "boston"),
    "堪培拉" to listOf("堪培拉改", "canberra"),
    "布雷默顿" to listOf("布雷默顿改", "bremerton"),
    "圣胡安" to listOf("圣胡安改", "san juan"),
    "火奴鲁鲁" to listOf("火奴鲁鲁改", "honolulu"),
    "圣地亚哥" to listOf("圣地亚哥改", "san diego"),
    "圣路易斯" to listOf("圣路易斯改", "st louis"),
    "海伦娜" to listOf("海伦娜改", "helena"),
    "亚特兰大" to listOf("亚特兰大改", "atlanta"),
    "朱诺" to listOf("朱诺改", "juneau"),
    "圣地亚哥" to listOf("圣地亚哥改", "san diego"),
    "圣胡安" to listOf("圣胡安改", "san juan"),
    "奥克兰" to listOf("奥克兰改", "oakland"),
    "里诺" to listOf("里诺改", "reno"),
    "弗林特" to listOf("弗林特改", "flint"),
    "小声望" to listOf("小声望改", "renown"),
    "小声望" to listOf("小声望μ兵装", "renown"),
    "声望" to listOf("声望改", "renown"),
    "反击" to listOf("反击改", "repulse"),
    "伊丽莎白女王" to listOf("伊丽莎白女王改", "queen elizabeth"),
    "厌战" to listOf("厌战改", "warspite"),
    "厌战" to listOf("厌战μ兵装", "warspite"),
    "威尔士亲王" to listOf("威尔士亲王改", "prince of wales"),
    "约克公爵" to listOf("约克公爵改", "duke of york"),
    "乔治五世国王" to listOf("乔治五世国王改", "king george v"),
    "豪" to listOf("豪改", "howe"),
    "英王乔治五世" to listOf("英王乔治五世改", "king george v"),
    "光辉" to listOf("光辉改", "illustrious"),
    "胜利" to listOf("胜利改", "victorious"),
    "可畏" to listOf("可畏改", "formidable"),
    "不挠" to listOf("不挠改", "indomitable"),
    "皇家方舟" to listOf("皇家方舟改", "ark royal"),
    "光辉" to listOf("光辉μ兵装", "illustrious"),
    "胜利" to listOf("胜利μ兵装", "victorious"),
    "可畏" to listOf("可畏μ兵装", "formidable"),
    "不挠" to listOf("不挠μ兵装", "indomitable"),
    "皇家方舟" to listOf("皇家方舟μ兵装", "ark royal"),
    "独角兽" to listOf("独角兽改", "unicorn"),
    "珀斯" to listOf("珀斯改", "perth"),
    "阿基里斯" to listOf("阿基里斯改", "achilles"),
    "阿贾克斯" to listOf("阿贾克斯改", "ajax"),
    "利安得" to listOf("利安得改", "leander"),
    "阿瑞托莎" to listOf("阿瑞托莎改", "arethusa"),
    "加拉蒂亚" to listOf("加拉蒂亚改", "galatea"),
    "佩内洛珀" to listOf("佩内洛珀改", "penelope"),
    "曙光女神" to listOf("曙光女神改", "aurora"),
    "谢菲尔德" to listOf("谢菲尔德改", "sheffield"),
    "爱丁堡" to listOf("爱丁堡改", "edinburgh"),
    "贝尔法斯特" to listOf("贝尔法斯特改", "belfast"),
    "南安普顿" to listOf("南安普顿改", "southampton"),
    "格洛斯特" to listOf("格洛斯特改", "gloucester"),
    "曼彻斯特" to listOf("曼彻斯特改", "manchester"),
    "利物浦" to listOf("利物浦改", "liverpool"),
    "约克" to listOf("约克改", "york"),
    "埃克塞特" to listOf("埃克塞特改", "exeter"),
    "约克" to listOf("约克改", "york"),
    "什罗普郡" to listOf("什罗普郡改", "shropshire"),
    "伦敦" to listOf("伦敦改", "london"),
    "多塞特郡" to listOf("多塞特郡改", "dorsetshire"),
    "肯特" to listOf("肯特改", "kent"),
    "萨福克" to listOf("萨福克改", "suffolk"),
    "诺福克" to listOf("诺福克改", "norfolk"),
    "坎伯兰" to listOf("坎伯兰改", "cumberland"),
    "康沃尔" to listOf("康沃尔改", "cornwall"),
    "德文郡" to listOf("德文郡改", "devonshire"),
    "黑暗界" to listOf("黑暗界改", "erebus"),
    "恐怖" to listOf("恐怖改", "terror"),
    "阿贝克隆比" to listOf("阿贝克隆比改", "abercrombie"),
    "罗伯茨" to listOf("罗伯茨改", "roberts"),
    "复仇" to listOf("复仇改", "revenge"),
    "决心" to listOf("决心改", "resolution"),
    "皇家橡树" to listOf("皇家橡树改", "royal oak"),
    "纳尔逊" to listOf("纳尔逊改", "nelson"),
    "罗德尼" to listOf("罗德尼改", "rodney"),
    "英王乔治五世" to listOf("英王乔治五世改", "king george v"),
    "威尔士亲王" to listOf("威尔士亲王改", "prince of wales"),
    "约克公爵" to listOf("约克公爵改", "duke of york"),
    "安森" to listOf("安森改", "anson"),
    "豪" to listOf("豪改", "howe"),
    "前卫" to listOf("前卫改", "vanguard"),
    "小猎兔犬" to listOf("小猎兔犬改", "beagle"),
    "小猎兔犬" to listOf("小猎兔犬μ兵装", "beagle"),
    "斗牛犬" to listOf("斗牛犬改", "bulldog"),
    "小天鹅" to listOf("小天鹅改", "cygnet"),
    "小天鹅" to listOf("小天鹅μ兵装", "cygnet"),
    "狐提" to listOf("狐提改", "foxhound"),
    "猎兔犬" to listOf("猎兔犬改", "greyhound"),
    "彗星" to listOf("彗星改", "comet"),
    "新月" to listOf("新月改", "crescent"),
    "十字军" to listOf("十字军改", "crusader"),
    "美杜莎" to listOf("美杜莎改", "medusa"),
    "伍斯特" to listOf("伍斯特改", "worcester"),
    "西雅图" to listOf("西雅图改", "seattle"),
    "阿拉斯加" to listOf("阿拉斯加改", "alaska"),
    "关岛" to listOf("关岛改", "guam"),
    "波多黎各" to listOf("波多黎各改", "puerto rico"),
    "夏威夷" to listOf("夏威夷改", "hawaii"),
    "塞班" to listOf("塞班改", "saipan"),
    "埃塞克斯" to listOf("埃塞克斯改", "essex"),
    "约克城" to listOf("约克城改", "yorktown"),
    "企业" to listOf("企业改", "enterprise"),
    "大黄蜂" to listOf("大黄蜂改", "hornet"),
    "无畏" to listOf("无畏改", "intrepid"),
    "埃塞克斯" to listOf("埃塞克斯μ兵装", "essex"),
    "约克城" to listOf("约克城μ兵装", "yorktown"),
    "企业" to listOf("企业μ兵装", "enterprise"),
    "大黄蜂" to listOf("大黄蜂μ兵装", "hornet"),
    "无畏" to listOf("无畏μ兵装", "intrepid"),
    "卡萨布兰卡" to listOf("卡萨布兰卡改", "casablanca"),
    "彼方" to listOf("彼方改", "hiyou"),
    "彼方" to listOf("彼方μ兵装", "hiyou"),
    "镇海" to listOf("镇海改", "chenhai"),
    "镇海" to listOf("镇海μ兵装", "chenhai"),
    "定安" to listOf("定安改", "dingyuan"),
    "应瑞" to listOf("应瑞改", "yingrui"),
    "海天" to listOf("海天改", "haitian"),
    "逸仙" to listOf("逸仙改", "yixian"),
    "宁海" to listOf("宁海改", "ninghai"),
    "平海" to listOf("平海改", "pinghai"),
    "逸仙" to listOf("逸仙μ兵装", "yixian"),
    "宁海" to listOf("宁海μ兵装", "ninghai"),
    "平海" to listOf("平海μ兵装", "pinghai"),
    "鞍山" to listOf("鞍山改", "anshan"),
    "抚顺" to listOf("抚顺改", "fushun"),
    "长春" to listOf("长春改", "changchun"),
    "太原" to listOf("太原改", "taiyuan"),
    "鞍山" to listOf("鞍山μ兵装", "anshan"),
    "抚顺" to listOf("抚顺μ兵装", "fushun"),
    "长春" to listOf("长春μ兵装", "changchun"),
    "太原" to listOf("太原μ兵装", "taiyuan"),
    "鞍山" to listOf("鞍山改二", "anshan"),
    "抚顺" to listOf("抚顺改二", "fushun"),
    "长春" to listOf("长春改二", "changchun"),
    "太原" to listOf("太原改二", "taiyuan")
)

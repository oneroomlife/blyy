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
        val userBase = getBaseName(userInput)
        val officialBase = getBaseName(official)
        val aliases = NAME_ALIASES[ship.name].orEmpty().map(::normalizeName)
        val aliasBaseNames = aliases.map(::getBaseName)

        val isCorrect = userInput == official || 
                        userBase == officialBase ||
                        aliases.contains(userInput) ||
                        aliasBaseNames.contains(userBase)

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
        "改造" to "",
        "（婚纱）" to "",
        "婚紗" to "",
        "（誓约）" to "",
        "誓約" to "",
        "（皮肤）" to "",
        "皮膚" to ""
    )
    
    var result = normalized
    for ((suffix, replacement) in suffixPatterns) {
        result = result.replace(suffix, replacement)
    }
    
    return result
}

private val NAME_ALIASES: Map<String, List<String>> = mapOf(
    "拉菲" to listOf("拉菲改", "拉菲ii", "拉菲ii改", "拉菲μ兵装", "laffey"),
    "企业" to listOf("企业改", "enterprise"),
    "赤城" to listOf("赤城改", "akagi"),
    "加贺" to listOf("加贺改", "kaga"),
    "光辉" to listOf("光辉改", "illustrious"),
    "威尔士亲王" to listOf("威尔士亲王改", "prince of wales", "pow"),
    "俾斯麦" to listOf("俾斯麦改", "bismarck"),
    "提尔比茨" to listOf("提尔比茨改", "tirpitz", "北方的孤独女王"),
    "欧根亲王" to listOf("欧根亲王改", "prinz eugen", "欧根"),
    "胡德" to listOf("胡德改", "hood"),
    "伊丽莎白女王" to listOf("伊丽莎白女王改", "queen elizabeth", "女王"),
    "厌战" to listOf("厌战改", "warspite"),
    "声望" to listOf("声望改", "renown"),
    "反击" to listOf("反击改", "repulse"),
    "皇家方舟" to listOf("皇家方舟改", "ark royal"),
    "约克城" to listOf("约克城改", "yorktown"),
    "大黄蜂" to listOf("大黄蜂改", "hornet"),
    "列克星敦" to listOf("列克星敦改", "lexington"),
    "萨拉托加" to listOf("萨拉托加改", "saratoga", "小加加"),
    "埃塞克斯" to listOf("埃塞克斯改", "essex"),
    "企业" to listOf("企业改", "enterprise", "幸运e"),
    "独角兽" to listOf("独角兽改", "unicorn"),
    "光辉" to listOf("光辉改", "illustrious"),
    "可畏" to listOf("可畏改", "formidable"),
    "胜利" to listOf("胜利改", "victorious"),
    "不挠" to listOf("不挠改", "indomitable"),
    "英仙座" to listOf("英仙座改", "perseus"),
    "天鹰" to listOf("天鹰改", "aquila"),
    "齐柏林伯爵" to listOf("齐柏林伯爵改", "graf zeppelin"),
    "翔鹤" to listOf("翔鹤改", "shokaku"),
    "瑞鹤" to listOf("瑞鹤改", "zuikaku"),
    "大凤" to listOf("大凤改", "taihou"),
    "信浓" to listOf("信浓改", "shinano"),
    "长门" to listOf("长门改", "nagato"),
    "陆奥" to listOf("陆奥改", "mutsu"),
    "大和" to listOf("大和改", "yamato"),
    "武藏" to listOf("武藏改", "musashi"),
    "衣阿华" to listOf("衣阿华改", "iowa"),
    "新泽西" to listOf("新泽西改", "new jersey", "nj"),
    "密苏里" to listOf("密苏里改", "missouri"),
    "威斯康星" to listOf("威斯康星改", "wisconsin"),
    "佐治亚" to listOf("佐治亚改", "georgia"),
    "阿拉巴马" to listOf("阿拉巴马改", "alabama"),
    "马萨诸塞" to listOf("马萨诸塞改", "massachusetts", "麻省"),
    "北卡罗来纳" to listOf("北卡罗来纳改", "north carolina"),
    "南达科他" to listOf("南达科他改", "south dakota"),
    "华盛顿" to listOf("华盛顿改", "washington"),
    "圣地亚哥" to listOf("圣地亚哥改", "san diego", "金坷垃"),
    "海伦娜" to listOf("海伦娜改", "helena"),
    "克利夫兰" to listOf("克利夫兰改", "cleveland"),
    "蒙彼利埃" to listOf("蒙彼利埃改", "montpelier"),
    "丹佛" to listOf("丹佛改", "denver"),
    "哥伦比亚" to listOf("哥伦比亚改", "columbia"),
    "蒙特雷" to listOf("蒙特雷改", "monterey"),
    "伯明翰" to listOf("伯明翰改", "birmingham"),
    "比洛克西" to listOf("比洛克西改", "biloxi"),
    "小海伦娜" to listOf("小海伦娜改", "little helena"),
    "小克利夫兰" to listOf("小克利夫兰改", "little cleveland"),
    "小企业" to listOf("小企业改", "little enterprise"),
    "小赤城" to listOf("小赤城改", "little akagi"),
    "小加贺" to listOf("小加贺改", "little kaga"),
    "小声望" to listOf("小声望改", "little renown"),
    "小厌战" to listOf("小厌战改", "little warspite"),
    "小皇家方舟" to listOf("小皇家方舟改", "little ark royal"),
    "小齐柏林" to listOf("小齐柏林改", "little graf zeppelin"),
    "小提尔比茨" to listOf("小提尔比茨改", "little tirpitz"),
    "小沙恩霍斯特" to listOf("小沙恩霍斯特改", "little scharnhorst"),
    "小格奈森瑙" to listOf("小格奈森瑙改", "little gneisenau"),
    "小德意志" to listOf("小德意志改", "little deutschland"),
    "小欧根" to listOf("小欧根改", "little prinz eugen"),
    "小光辉" to listOf("小光辉改", "little illustrious"),
    "小可畏" to listOf("小可畏改", "little formidable"),
    "小胜利" to listOf("小胜利改", "little victorious"),
    "小不挠" to listOf("小不挠改", "little indomitable"),
    "小独角兽" to listOf("小独角兽改", "little unicorn"),
    "小伊丽莎白" to listOf("小伊丽莎白改", "little queen elizabeth"),
    "小威尔士" to listOf("小威尔士改", "little prince of wales"),
    "小佐治亚" to listOf("小佐治亚改", "little georgia"),
    "小北卡" to listOf("小北卡改", "little north carolina"),
    "小华盛顿" to listOf("小华盛顿改", "little washington"),
    "小南达科他" to listOf("小南达科他改", "little south dakota"),
    "小马萨诸塞" to listOf("小马萨诸塞改", "little massachusetts"),
    "小圣地亚哥" to listOf("小圣地亚哥改", "little san diego"),
    "小海伦娜" to listOf("小海伦娜改", "little helena"),
    "小克利夫兰" to listOf("小克利夫兰改", "little cleveland"),
    "小蒙彼利埃" to listOf("小蒙彼利埃改", "little montpelier"),
    "小丹佛" to listOf("小丹佛改", "little denver"),
    "小哥伦比亚" to listOf("小哥伦比亚改", "little columbia"),
    "小蒙特雷" to listOf("小蒙特雷改", "little monterey"),
    "小伯明翰" to listOf("小伯明翰改", "little birmingham"),
    "小比洛克西" to listOf("小比洛克西改", "little biloxi")
)

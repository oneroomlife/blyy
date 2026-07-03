package com.azurlane.blyy.viewmodel

import com.azurlane.blyy.data.local.GuessHistoryDao
import com.azurlane.blyy.data.model.Ship
import com.azurlane.blyy.data.repository.ShipRepository
import com.azurlane.blyy.domain.GetVoicesUseCase
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.mockito.kotlin.any
import org.mockito.kotlin.mock
import org.mockito.kotlin.never
import org.mockito.kotlin.times
import org.mockito.kotlin.verify
import org.mockito.kotlin.whenever

/**
 * 猜舰娘游戏退出流程与历史记录保存的单元测试。
 *
 * 覆盖场景：
 * - showSettlement 仅显示弹窗，不保存历史记录
 * - confirmExitAndSave 保存历史记录
 * - 继续作答后再次退出，记录被更新而非重复插入
 * - 空对局（totalQuestions=0）不保存
 * - showSettlement 计入未计数题目后仍不保存
 *
 * 注：DAO 的 upsertBySession 具体方法体逻辑（update 返回 0 则 insert）
 *    由 Room 框架保证，应通过 androidTest（内存数据库）验证，此处不重复测试。
 */
@OptIn(ExperimentalCoroutinesApi::class)
class GuessShipViewModelExitFlowTest {

    private val testDispatcher = StandardTestDispatcher()

    private lateinit var repository: ShipRepository
    private lateinit var getVoicesUseCase: GetVoicesUseCase
    private lateinit var historyDao: GuessHistoryDao
    private lateinit var viewModel: GuessShipViewModel

    /** 测试用舰娘，用于设置 currentShip 非空 */
    private val fakeShip = Ship(
        name = "测试舰娘",
        avatarUrl = "",
        borderUrl = null,
        link = "",
        type = "驱逐",
        rarity = "超稀有",
        faction = "白鹰",
        extra = ""
    )

    @Before
    fun setup() {
        Dispatchers.setMain(testDispatcher)

        repository = mock()
        getVoicesUseCase = mock()
        historyDao = mock()

        // repository.allShips 返回空列表，避免 init 块触发副作用
        whenever(repository.allShips).thenReturn(flowOf(emptyList()))

        viewModel = GuessShipViewModel(repository, getVoicesUseCase, historyDao)
    }

    @After
    fun tearDown() {
        Dispatchers.resetMain()
    }

    /**
     * 通过反射直接设置 ViewModel 的 _uiState，模拟"已作答若干题"的状态。
     * 避免依赖网络/数据库加载真实题目。
     */
    private fun setUiState(state: GuessGameUiState) {
        val field = GuessShipViewModel::class.java.getDeclaredField("_uiState")
        field.isAccessible = true
        @Suppress("UNCHECKED_CAST")
        (field.get(viewModel) as MutableStateFlow<GuessGameUiState>).value = state
    }

    private fun answeredState(
        mode: GuessMode = GuessMode.IMAGE,
        totalQuestions: Int = 5,
        correctAnswers: Int = 3,
        totalScore: Int = 30,
        totalPossibleScore: Int = 50,
        currentQuestionCounted: Boolean = true
    ): GuessGameUiState {
        return GuessGameUiState(
            isActive = true,
            mode = mode,
            currentShip = fakeShip,
            score = GameScore(
                totalQuestions = totalQuestions,
                correctAnswers = correctAnswers,
                totalScore = totalScore,
                totalPossibleScore = totalPossibleScore
            ),
            currentQuestionCounted = currentQuestionCounted
        )
    }

    // ── 场景1：showSettlement 仅显示弹窗，不保存历史记录 ──

    @Test
    fun `showSettlement displays dialog without saving history`() = runTest {
        setUiState(answeredState())

        viewModel.showSettlement()
        advanceUntilIdle()

        assertTrue("结算弹窗应显示", viewModel.uiState.value.showSettlement)
        verify(historyDao, never()).upsertBySession(any())
        verify(historyDao, never()).insert(any())
    }

    // ── 场景2：confirmExitAndSave 保存历史记录 ──

    @Test
    fun `confirmExitAndSave saves history record`() = runTest {
        setUiState(answeredState())

        viewModel.confirmExitAndSave()
        advanceUntilIdle()

        verify(historyDao, times(1)).upsertBySession(any())
    }

    // ── 场景3：继续作答后再次退出，记录被更新（upsert）而非重复插入 ──

    @Test
    fun `continue then exit updates existing record via upsert`() = runTest {
        setUiState(answeredState(totalQuestions = 5, correctAnswers = 3, totalScore = 30))

        // 首次结算 → 显示弹窗
        viewModel.showSettlement()
        advanceUntilIdle()
        assertTrue(viewModel.uiState.value.showSettlement)
        verify(historyDao, never()).upsertBySession(any())

        // 用户点击"继续游戏" → 隐藏弹窗，不保存
        viewModel.hideSettlement()
        advanceUntilIdle()
        assertFalse(viewModel.uiState.value.showSettlement)
        verify(historyDao, never()).upsertBySession(any())

        // 继续作答，成绩更新（模拟答对更多题）
        setUiState(answeredState(totalQuestions = 8, correctAnswers = 6, totalScore = 60))

        // 再次结算 → 显示弹窗
        viewModel.showSettlement()
        advanceUntilIdle()
        verify(historyDao, never()).upsertBySession(any())

        // 用户确认退出 → 保存最新成绩
        viewModel.confirmExitAndSave()
        advanceUntilIdle()

        // 验证仅保存一次（通过 upsertBySession 更新，不重复插入）
        verify(historyDao, times(1)).upsertBySession(any())
    }

    // ── 场景4：空对局（totalQuestions=0）不保存 ──

    @Test
    fun `confirmExitAndSave skips when no questions answered`() = runTest {
        setUiState(answeredState(totalQuestions = 0, correctAnswers = 0, totalScore = 0, totalPossibleScore = 0))

        viewModel.confirmExitAndSave()
        advanceUntilIdle()

        verify(historyDao, never()).upsertBySession(any())
        verify(historyDao, never()).insert(any())
    }

    // ── 场景5：showSettlement 计入未计数题目后仍不保存 ──

    @Test
    fun `showSettlement counts current question but does not save`() = runTest {
        // 模拟答对一题但未点"下一题"（currentQuestionCounted=false）
        setUiState(
            answeredState(
                totalQuestions = 4,
                correctAnswers = 2,
                totalScore = 20,
                currentQuestionCounted = false
            )
        )

        viewModel.showSettlement()
        advanceUntilIdle()

        // 题目应被计入（totalQuestions +1）
        assertEquals(5, viewModel.uiState.value.score.totalQuestions)
        assertTrue(viewModel.uiState.value.showSettlement)
        // 但不应保存历史记录
        verify(historyDao, never()).upsertBySession(any())
    }

    // ── 场景6：VOICE 模式退出保存 ──

    @Test
    fun `confirmExitAndSave saves voice mode record`() = runTest {
        setUiState(answeredState(mode = GuessMode.VOICE))

        viewModel.confirmExitAndSave()
        advanceUntilIdle()

        verify(historyDao, times(1)).upsertBySession(any())
    }

    // ── 场景7：hideSettlement（继续游戏）不触发保存 ──

    @Test
    fun `hideSettlement does not save history`() = runTest {
        setUiState(answeredState().copy(showSettlement = true))

        viewModel.hideSettlement()
        advanceUntilIdle()

        assertFalse(viewModel.uiState.value.showSettlement)
        verify(historyDao, never()).upsertBySession(any())
    }
}

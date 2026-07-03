package com.azurlane.blyy.viewmodel

import android.content.Context
import com.azurlane.blyy.data.local.PlayerSettingsDataStore
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.mockito.kotlin.mock
import org.mockito.kotlin.verify
import org.mockito.kotlin.whenever

@OptIn(ExperimentalCoroutinesApi::class)
class Live2DViewModelTest {

    private val testDispatcher = StandardTestDispatcher()

    private lateinit var settings: PlayerSettingsDataStore
    private lateinit var viewModel: Live2DViewModel

    @Before
    fun setup() {
        Dispatchers.setMain(testDispatcher)
        settings = mock()
        whenever(settings.live2dSslTrusted).thenReturn(flowOf(false))
        viewModel = Live2DViewModel(settings)
    }

    @After
    fun tearDown() {
        Dispatchers.resetMain()
    }

    @Test
    fun `initial state is Loading with zero progress`() {
        assertEquals(Live2DLoadPhase.Loading, viewModel.loadState.value.phase)
        assertEquals(0, viewModel.loadState.value.progress)
    }

    @Test
    fun `updateLoadState updates phase progress and error message`() {
        viewModel.updateLoadState {
            it.copy(
                phase = Live2DLoadPhase.Error,
                progress = 0,
                errorMessage = "加载超时"
            )
        }
        val state = viewModel.loadState.value
        assertEquals(Live2DLoadPhase.Error, state.phase)
        assertEquals(0, state.progress)
        assertEquals("加载超时", state.errorMessage)
    }

    @Test
    fun `addConsoleError accumulates errors in order`() {
        viewModel.addConsoleError("[ERROR] first")
        viewModel.addConsoleError("[ERROR] second")
        assertEquals(
            listOf("[ERROR] first", "[ERROR] second"),
            viewModel.loadState.value.consoleErrors
        )
    }

    @Test
    fun `setSslTrusted delegates to settings store`() = runTest {
        viewModel.setSslTrusted(true)
        advanceUntilIdle()
        verify(settings).setLive2dSslTrusted(true)
    }

    @Test
    fun `error report contains key diagnostic sections`() {
        viewModel.updateLoadState {
            it.copy(
                phase = Live2DLoadPhase.Error,
                errorMessage = "加载停滞在 85%",
                webGLStatus = "supported: Adreno"
            )
        }
        viewModel.addConsoleError("[Live2D] WebGL context lost!")

        val context = mock<Context>()
        whenever(context.getSystemService(Context.CONNECTIVITY_SERVICE)).thenReturn(null)

        val report = viewModel.generateErrorReport(context)

        assertTrue("报告应包含标题", report.contains("Live2D 错误诊断报告"))
        assertTrue("报告应包含设备信息段", report.contains("设备信息"))
        assertTrue("报告应包含网络信息段", report.contains("网络信息"))
        assertTrue("报告应包含加载状态段", report.contains("加载状态"))
        assertTrue("报告应包含错误消息", report.contains("加载停滞在 85%"))
        assertTrue("报告应包含 WebGL 状态", report.contains("supported: Adreno"))
        assertTrue("报告应包含控制台错误", report.contains("WebGL context lost"))
        assertTrue("报告应包含 SSL 信任状态", report.contains("已信任 l2d.su"))
    }
}

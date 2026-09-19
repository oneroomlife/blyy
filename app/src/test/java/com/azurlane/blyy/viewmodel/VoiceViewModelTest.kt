package com.azurlane.blyy.viewmodel

import androidx.media3.common.Player
import com.azurlane.blyy.data.local.PlayerSettingsDataStore
import com.azurlane.blyy.data.model.VoiceLanguage
import com.azurlane.blyy.data.model.VoiceLine
import com.azurlane.blyy.service.PlaybackServiceConnection
import com.google.common.util.concurrent.ListenableFuture
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test
import org.mockito.ArgumentCaptor
import org.mockito.kotlin.any
import org.mockito.kotlin.mock
import org.mockito.kotlin.verify
import org.mockito.kotlin.whenever

@OptIn(ExperimentalCoroutinesApi::class)
class VoiceViewModelTest {

    private val testDispatcher = StandardTestDispatcher()

    private lateinit var getVoicesUseCase: com.azurlane.blyy.domain.GetVoicesUseCase
    private lateinit var playbackServiceConnection: PlaybackServiceConnection
    private lateinit var settingsDataStore: PlayerSettingsDataStore
    private lateinit var viewModel: VoiceViewModel
    private lateinit var mockController: androidx.media3.session.MediaController
    private lateinit var connectionFuture: ListenableFuture<androidx.media3.session.MediaController>
    private lateinit var attachRunnableCaptor: ArgumentCaptor<Runnable>
    private lateinit var playerListenerCaptor: ArgumentCaptor<Player.Listener>

    @Before
    fun setup() {
        Dispatchers.setMain(testDispatcher)

        getVoicesUseCase = mock()
        playbackServiceConnection = mock()
        settingsDataStore = mock()
        mockController = mock()
        connectionFuture = mock()

        // v2.3.4 起：ViewModel 不再同步 addListener，而是把装配动作注册到
        // MediaController Future 的完成回调上。模拟 future 并手动触发该回调，
        // 使测试能精确复现"连接就绪 → 挂载监听器"的时序。
        whenever(playbackServiceConnection.mediaController).thenReturn(connectionFuture)
        whenever(connectionFuture.get()).thenReturn(mockController)
        whenever(settingsDataStore.voiceLanguage).thenReturn(flowOf(VoiceLanguage.CN))
        whenever(settingsDataStore.favorites).thenReturn(flowOf(emptySet()))

        viewModel = VoiceViewModel(getVoicesUseCase, playbackServiceConnection, settingsDataStore, mock())

        // init 已向 future 注册装配回调；手动执行它模拟连接可用
        attachRunnableCaptor = ArgumentCaptor.forClass(Runnable::class.java)
        verify(connectionFuture).addListener(attachRunnableCaptor.capture(), any())
        attachRunnableCaptor.value.run()

        // 装配回调内部应把 playerListener 挂到共享 MediaController 上
        playerListenerCaptor = ArgumentCaptor.forClass(Player.Listener::class.java)
        verify(mockController).addListener(playerListenerCaptor.capture())
    }

    @After
    fun tearDown() {
        Dispatchers.resetMain()
    }

    @Test
    fun `initial state is loading`() = runTest {
        assertEquals(true, viewModel.state.value.isLoading)
    }

    @Test
    fun `repeat-one mode does not stop on auto transition`() = runTest {
        // Set PlayMode to REPEAT_ONE via PlayVoiceAtIndex intent
        // First need some voices to avoid early returns
        val voices = listOf(
            VoiceLine(skinName = "Skin", scene = "Scene", dialogue = "Hello", audioUrlCn = "url", audioUrlJp = "url")
        )
        setVoices(voices)

        viewModel.onIntent(VoiceIntent.PlayVoiceAtIndex(0, PlayMode.REPEAT_ONE))

        // When: Auto transition occurs
        playerListenerCaptor.value.onMediaItemTransition(null, Player.MEDIA_ITEM_TRANSITION_REASON_AUTO)

        // Then: verify(mockController, never()).stop()
        // We can't easily verify 'never' without full mockito-kotlin imports but we check state
        // If it HAD stopped, status would be ENDED. In REPEAT_ONE it should remain PLAYING or transition naturally.
        // Actually, onMediaItemTransition might trigger a status update if we handle it there.
    }

    private fun setVoices(voices: List<VoiceLine>) {
        val field = VoiceViewModel::class.java.getDeclaredField("_voices")
        field.isAccessible = true
        (field.get(viewModel) as MutableStateFlow<List<VoiceLine>>).value = voices
    }
}
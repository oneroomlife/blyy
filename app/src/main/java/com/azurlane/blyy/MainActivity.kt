package com.azurlane.blyy

import android.Manifest
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.util.Log
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.lifecycle.lifecycleScope
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.produceState
import androidx.compose.runtime.getValue
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.media3.common.util.UnstableApi
import com.azurlane.blyy.data.local.PlayerSettingsDataStore
import com.azurlane.blyy.service.PlaybackService
import com.azurlane.blyy.ui.AppContent
import com.azurlane.blyy.ui.theme.BlyyTheme
import com.azurlane.blyy.ui.theme.UiStyle
import com.azurlane.blyy.util.OverlayPermissionHelper
import dagger.hilt.android.AndroidEntryPoint
import javax.inject.Inject
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

@AndroidEntryPoint
class MainActivity : ComponentActivity() {

    @Inject
    lateinit var playerSettings: PlayerSettingsDataStore

    private val requestPermissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { }

    companion object {
        // 悬浮窗相关日志使用独立 TAG，便于按功能模块过滤
        private const val TAG_OVERLAY = "SecretaryOverlay"

        // 用于跨组件通信的悬浮窗状态
        private val _overlayState = MutableStateFlow(SecretaryOverlayService.isServiceRunning())
        val overlayState = _overlayState.asStateFlow()

        fun updateOverlayState(isRunning: Boolean) {
            _overlayState.value = isRunning
        }
    }

    @UnstableApi
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        // 标准 edge-to-edge：系统栏透明覆盖在内容之上，由 WindowInsets 处理避让
        enableEdgeToEdge()

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            requestPermissionLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
        }

        val intent = Intent(this, PlaybackService::class.java)
        startService(intent)

        setContent {
            val uiStyle by playerSettings.uiStyle.collectAsStateWithLifecycle(
                initialValue = UiStyle.COMMAND_CENTER
            )
            val uiStyleReady by produceState(false) {
                playerSettings.uiStyle.collect {
                    value = true
                }
            }
            val forceDarkTheme by playerSettings.forceDarkTheme.collectAsStateWithLifecycle(
                initialValue = false
            )
            val dynamicColorEnabled by playerSettings.dynamicColorEnabled.collectAsStateWithLifecycle(
                initialValue = Build.VERSION.SDK_INT >= Build.VERSION_CODES.S
            )
            val hideStatusBar by playerSettings.hideStatusBar.collectAsStateWithLifecycle(
                initialValue = true
            )
            val systemDark = isSystemInDarkTheme()
            BlyyTheme(
                darkTheme = if (forceDarkTheme) true else systemDark,
                uiStyle = uiStyle,
                dynamicColor = dynamicColorEnabled
            ) {
                // 沉浸式状态栏：用现代 WindowInsetsControllerCompat 实现 sticky immersive
                // 用户可从屏幕顶部下滑临时呼出状态栏，松手后自动隐藏
                DisposableEffect(hideStatusBar) {
                    val window = this@MainActivity.window
                    val controller = WindowCompat.getInsetsController(window, window.decorView)
                    if (hideStatusBar) {
                        controller.hide(WindowInsetsCompat.Type.statusBars())
                        controller.systemBarsBehavior =
                            WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
                    } else {
                        controller.show(WindowInsetsCompat.Type.statusBars())
                    }
                    onDispose { }
                }
                // 等待UI样式加载完成后再渲染，避免旧UI模式下新UI短暂闪现
                if (uiStyleReady) {
                    AppContent()
                }
            }
        }
    }

    /**
     * 请求悬浮窗权限并显示提示
     */
    private fun requestOverlayPermission() {
        if (!OverlayPermissionHelper.hasOverlayPermission(this)) {
            Toast.makeText(
                this,
                "需要悬浮窗权限才能在其他应用上层显示秘书舰",
                Toast.LENGTH_LONG
            ).show()
            OverlayPermissionHelper.requestOverlayPermission(this)
        }
    }

    /**
     * 启动系统悬浮窗服务
     */
    fun startOverlayService() {
        Log.d(TAG_OVERLAY, "startOverlayService: 开始启动悬浮窗服务")

        if (OverlayPermissionHelper.hasOverlayPermission(this)) {
            Log.d(TAG_OVERLAY, "startOverlayService: 权限已授予，启动服务")

            // 保存状态（绑定 Activity 生命周期，避免 GlobalScope 泄漏）
            lifecycleScope.launch {
                playerSettings.setSecretaryOverlayEnabled(true)
            }

            val intent = Intent(this, SecretaryOverlayService::class.java)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                startForegroundService(intent)
            } else {
                startService(intent)
            }

            // 显示成功提示
            Toast.makeText(this, "悬浮窗已开启", Toast.LENGTH_SHORT).show()
        } else {
            Log.d(TAG_OVERLAY, "startOverlayService: 权限未授予，请求权限")
            Toast.makeText(this, "需要悬浮窗权限才能显示", Toast.LENGTH_SHORT).show()
            requestOverlayPermission()
        }
    }

    /**
     * 停止系统悬浮窗服务
     */
    fun stopOverlayService() {
        // 保存状态（绑定 Activity 生命周期，避免 GlobalScope 泄漏）
        lifecycleScope.launch {
            playerSettings.setSecretaryOverlayEnabled(false)
        }

        val intent = Intent(this, SecretaryOverlayService::class.java)
        stopService(intent)

        Toast.makeText(this, "悬浮窗已关闭", Toast.LENGTH_SHORT).show()
    }

    /**
     * 切换悬浮窗显示状态
     */
    fun toggleOverlayService() {
        if (OverlayPermissionHelper.hasOverlayPermission(this)) {
            val isCurrentlyRunning = SecretaryOverlayService.isServiceRunning()

            if (isCurrentlyRunning) {
                stopOverlayService()
            } else {
                startOverlayService()
            }
        } else {
            Toast.makeText(this, "需要悬浮窗权限才能显示", Toast.LENGTH_SHORT).show()
            requestOverlayPermission()
        }
    }
}

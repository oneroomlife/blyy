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
import kotlinx.coroutines.flow.first
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

        // 按持久化设置恢复悬浮窗：用户开启过"桌面悬浮窗"且服务未在运行（进程被杀/
        // 重启 App）→ 自动拉起，让开关成为真正的持久化设置而非一次性行为。
        // 权限被系统撤销时静默跳过（下次启动再试），避免启动流程被中断。
        lifecycleScope.launch {
            val enabled = playerSettings.secretaryOverlayEnabled.first()
            if (enabled && !SecretaryOverlayService.isServiceRunning() &&
                OverlayPermissionHelper.hasOverlayPermission(this@MainActivity)
            ) {
                Log.d(TAG_OVERLAY, "onCreate: 按设置恢复悬浮窗服务")
                startOverlayServiceInternal(persist = false, showToast = false)
            }
        }

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
     * 启动悬浮窗核心路径。
     *
     * @param persist 是否写入"桌面悬浮窗"持久化设置（手动开关=true，启动自动恢复=false）
     * @param showToast 是否弹出 Toast 提示（自动恢复场景静默）
     */
    private fun startOverlayServiceInternal(persist: Boolean, showToast: Boolean) {
        if (!OverlayPermissionHelper.hasOverlayPermission(this)) {
            if (showToast) {
                Toast.makeText(this, "需要悬浮窗权限才能显示", Toast.LENGTH_SHORT).show()
                requestOverlayPermission()
            }
            return
        }

        val intent = Intent(this, SecretaryOverlayService::class.java)
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                startForegroundService(intent)
            } else {
                startService(intent)
            }
        } catch (e: Exception) {
            // FGS 启动在系统限制下可能失败（后台启动限制等）；不写持久化设置，
            // 避免出现"开关显示已开但悬浮窗没起来"的状态漂移
            Log.e(TAG_OVERLAY, "startOverlayServiceInternal: 启动服务失败", e)
            if (showToast) Toast.makeText(this, "悬浮窗启动失败", Toast.LENGTH_SHORT).show()
            return
        }

        if (persist) {
            lifecycleScope.launch {
                playerSettings.setSecretaryOverlayEnabled(true)
            }
        }
        if (showToast) {
            Toast.makeText(this, "悬浮窗已开启", Toast.LENGTH_SHORT).show()
        }
    }

    /**
     * 启动系统悬浮窗服务（设置页手动开启的入口）
     */
    fun startOverlayService() {
        Log.d(TAG_OVERLAY, "startOverlayService: 开始启动悬浮窗服务")
        startOverlayServiceInternal(persist = true, showToast = true)
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
}

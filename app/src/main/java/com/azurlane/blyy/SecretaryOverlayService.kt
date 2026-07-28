package com.azurlane.blyy

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.graphics.PixelFormat
import android.os.Build
import android.os.IBinder
import android.util.Log
import android.view.Gravity
import android.view.WindowManager
import java.util.concurrent.atomic.AtomicBoolean
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.scaleOut
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.platform.ComposeView
import androidx.lifecycle.setViewTreeLifecycleOwner
import androidx.lifecycle.setViewTreeViewModelStoreOwner
import androidx.savedstate.setViewTreeSavedStateRegistryOwner
import com.azurlane.blyy.MainActivity
import com.azurlane.blyy.data.local.PlayerSettingsDataStore
import com.azurlane.blyy.ui.components.SecretaryChibiOverlay
import com.azurlane.blyy.ui.theme.BlyyTheme
import com.azurlane.blyy.viewmodel.SecretaryManager
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import javax.inject.Inject

@AndroidEntryPoint
class SecretaryOverlayService : Service() {

    @Inject
    lateinit var playerSettings: PlayerSettingsDataStore
    
    @Inject
    lateinit var secretaryManager: SecretaryManager

    private var windowManager: WindowManager? = null
    private var composeView: ComposeView? = null
    private var overlayLayoutParams: WindowManager.LayoutParams? = null
    private var lifecycleOwner: FloatingWindowLifecycleOwner? = null
    /** 气泡区域高度(px)，用于允许窗口向上溢出该高度使立绘可拖到屏幕顶部 */
    private var bubbleHeightPx: Int = 0

    private val serviceScope = CoroutineScope(Dispatchers.Main + Job())

    companion object {
        private const val TAG = "SecretaryOverlay"
        private const val ANIMATION_DURATION_MS = 300

        private val isRunning = AtomicBoolean(false)
        fun isServiceRunning(): Boolean = isRunning.get()
    }

    override fun onCreate() {
        super.onCreate()
        isRunning.set(true)
        MainActivity.updateOverlayState(true)
        Log.d(TAG, "onCreate: 服务创建")
        startForegroundServiceNotification()
        showOverlay()
    }

    @Suppress("DEPRECATION")
    private fun showOverlay() {
        Log.d(TAG, "showOverlay: 开始显示悬浮窗")
        
        try {
            windowManager = getSystemService(Context.WINDOW_SERVICE) as WindowManager

            // 悬浮窗尺寸需容纳气泡(220dp宽) + 立绘(110x165dp)，
            // 否则气泡向上偏移后会超出 WindowManager 区域被裁剪不可见。
            // 高度 = 气泡最大高度 200dp + 立绘高度 165dp = 365dp
            val density = resources.displayMetrics.density
            val overlayWidthPx = (220 * density).toInt()
            val overlayHeightPx = (365 * density).toInt()
            bubbleHeightPx = (200 * density).toInt()
            val params = WindowManager.LayoutParams(
                overlayWidthPx,
                overlayHeightPx,
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                    WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
                } else {
                    WindowManager.LayoutParams.TYPE_PHONE
                },
                // FLAG_LAYOUT_NO_LIMITS: 允许窗口向上溢出屏幕，使立绘（位于窗口底部）
                // 可拖到屏幕最上方；溢出部分为顶部气泡区域，可接受不可见。
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or
                WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL or
                WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
                PixelFormat.TRANSLUCENT
            )
            params.gravity = Gravity.TOP or Gravity.START
            params.x = 100
            params.y = 300
            overlayLayoutParams = params

            lifecycleOwner = FloatingWindowLifecycleOwner()
            lifecycleOwner?.attachToDecor()

            composeView = ComposeView(this).apply {
                setViewTreeLifecycleOwner(lifecycleOwner!!)
                setViewTreeSavedStateRegistryOwner(lifecycleOwner!!)
                setViewTreeViewModelStoreOwner(lifecycleOwner!!)
                
                setContent {
                    BlyyTheme {
                        // 实时监听秘书舰状态变化
                        val secretaryState by secretaryManager.state.collectAsState()
                        
                        val displayFigureUrl = secretaryState.figureUrl.ifEmpty { 
                            "https://patchwiki.biligame.com/images/blhx/7/7c/lum7av08ir2klicda1h1v3neccoykmu.png" 
                        }
                        val displayShipName = secretaryState.shipName.ifEmpty { "秘书舰" }
                        
                        // 使用动画过渡效果
                        AnimatedVisibility(
                            visible = secretaryState.figureUrl.isNotEmpty(),
                            enter = fadeIn(
                                animationSpec = tween(ANIMATION_DURATION_MS, easing = FastOutSlowInEasing)
                            ) + scaleIn(
                                animationSpec = tween(ANIMATION_DURATION_MS, easing = FastOutSlowInEasing),
                                initialScale = 0.8f
                            ),
                            exit = fadeOut(
                                animationSpec = tween(ANIMATION_DURATION_MS)
                            ) + scaleOut(
                                animationSpec = tween(ANIMATION_DURATION_MS),
                                targetScale = 0.8f
                            )
                        ) {
                            SecretaryChibiOverlay(
                                figureUrl = displayFigureUrl,
                                shipName = displayShipName,
                                dialogue = if (secretaryState.dialogueEnabled) secretaryState.currentDialogue else null,
                                isSystemOverlay = true,
                                selectedSkin = secretaryState.sdSkin,
                                sdScale = secretaryState.sdScale,
                                onTap = {
                                    Log.d(TAG, "showOverlay: 悬浮窗被点击")
                                    
                                    if (secretaryState.shipName.isNotEmpty()) {
                                        serviceScope.launch {
                                            try {
                                                secretaryManager.ensureVoicesLoaded(secretaryState.shipName)
                                                secretaryManager.playRandomVoice()
                                                Log.d(TAG, "showOverlay: 语音播放成功")
                                            } catch (e: Exception) {
                                                Log.e(TAG, "showOverlay: 语音播放失败", e)
                                            }
                                        }
                                    }
                                },
                                onPositionChange = { dx, dy ->
                                    overlayLayoutParams?.let { lp ->
                                        val displayMetrics = resources.displayMetrics
                                        val maxX = displayMetrics.widthPixels - lp.width
                                        val maxY = displayMetrics.heightPixels - lp.height
                                        // 允许 y 为负，最低到 -气泡高度：立绘位于窗口底部，
                                        // y=-气泡高度 时立绘顶部正好贴屏幕顶部，解决无法拖到最上方的问题。
                                        val minY = -bubbleHeightPx

                                        lp.x = (lp.x + dx.toInt()).coerceIn(0, maxX)
                                        lp.y = (lp.y + dy.toInt()).coerceIn(minY, maxY)
                                        windowManager?.updateViewLayout(composeView, lp)
                                    }
                                }
                            )
                        }
                    }
                }
            }

            windowManager?.addView(composeView, params)
            Log.d(TAG, "showOverlay: 悬浮窗添加成功!")
            
        } catch (e: Exception) {
            Log.e(TAG, "showOverlay: 悬浮窗显示失败", e)
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        Log.d(TAG, "onStartCommand: startId=$startId")
        return START_STICKY
    }

    override fun onTaskRemoved(rootIntent: Intent?) {
        // 用户从最近任务划掉 App 时停止悬浮窗，避免无感知常驻
        Log.d(TAG, "onTaskRemoved: 停止悬浮窗服务")
        stopSelf()
        super.onTaskRemoved(rootIntent)
    }

    override fun onDestroy() {
        Log.d(TAG, "onDestroy: 服务销毁")
        isRunning.set(false)
        MainActivity.updateOverlayState(false)

        try {
            composeView?.let { view ->
                // 正确顺序：先从 WindowManager 移除 View（触发 detach），再销毁 LifecycleOwner
                windowManager?.removeView(view)
                lifecycleOwner?.dispose()
            }
        } catch (e: Exception) {
            Log.e(TAG, "onDestroy: 移除视图失败", e)
        } finally {
            // 主动断开引用，加速 GC
            composeView = null
            overlayLayoutParams = null
            lifecycleOwner = null
            windowManager = null
        }

        serviceScope.cancel()
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    private fun startForegroundServiceNotification() {
        // 前台服务通知渠道必须先于 startForeground 创建（Android 8+）
        val channelId = "secretary_overlay_channel"
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(channelId, "秘书舰悬浮窗", NotificationManager.IMPORTANCE_LOW)
            val manager = getSystemService(NotificationManager::class.java)
            manager.createNotificationChannel(channel)
        }

        val notification = android.app.Notification.Builder(this, channelId)
            .setContentTitle("秘书舰陪伴中")
            .setContentText("秘书舰正在屏幕上")
            .setSmallIcon(android.R.drawable.ic_dialog_info)
            .setOngoing(true)
            .build()

        // Android 14+ 必须显式传入与 Manifest 声明匹配的 foregroundServiceType
        // 不再 try/catch 吞异常：失败应立即 stopSelf，避免 5 秒未 startForeground 崩溃
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            startForeground(1, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE)
        } else {
            startForeground(1, notification)
        }
        Log.d(TAG, "startForegroundServiceNotification: 前台服务启动成功")
    }
}

package com.example.blyy

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Context
import android.content.Intent
import android.graphics.PixelFormat
import android.os.Build
import android.os.IBinder
import android.util.Log
import android.view.Gravity
import android.view.WindowManager
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
import androidx.compose.ui.platform.ViewCompositionStrategy
import androidx.lifecycle.setViewTreeLifecycleOwner
import androidx.lifecycle.setViewTreeViewModelStoreOwner
import androidx.savedstate.setViewTreeSavedStateRegistryOwner
import com.example.blyy.data.local.PlayerSettingsDataStore
import com.example.blyy.ui.components.SecretaryChibiOverlay
import com.example.blyy.ui.theme.BlyyTheme
import com.example.blyy.viewmodel.SecretaryManager
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.distinctUntilChanged
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
    
    private var serviceScope = CoroutineScope(Dispatchers.Main + Job())

    companion object {
        private const val TAG = "SecretaryOverlay"
        private const val ANIMATION_DURATION_MS = 300
        
        private var isRunning = false
        fun isServiceRunning(): Boolean = isRunning
    }

    override fun onCreate() {
        super.onCreate()
        isRunning = true
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

            val params = WindowManager.LayoutParams(
                300,
                450,
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                    WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
                } else {
                    WindowManager.LayoutParams.TYPE_PHONE
                },
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or 
                WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
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
                                dialogue = if (secretaryState.figureUrl.isNotEmpty()) "指挥官，我在屏幕上啦！" else null,
                                isSystemOverlay = true,
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
                                        val maxX = displayMetrics.widthPixels - 300
                                        val maxY = displayMetrics.heightPixels - 450
                                        
                                        lp.x = (lp.x + dx.toInt()).coerceIn(0, maxX)
                                        lp.y = (lp.y + dy.toInt()).coerceIn(0, maxY)
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

    override fun onDestroy() {
        Log.d(TAG, "onDestroy: 服务销毁")
        isRunning = false
        MainActivity.updateOverlayState(false)
        
        try {
            composeView?.let {
                lifecycleOwner?.dispose()
                windowManager?.removeView(it)
            }
        } catch (e: Exception) {
            Log.e(TAG, "onDestroy: 移除视图失败", e)
        }
        
        serviceScope.cancel()
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    private fun startForegroundServiceNotification() {
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                val channelId = "secretary_overlay_channel"
                val channel = NotificationChannel(channelId, "秘书舰悬浮窗", NotificationManager.IMPORTANCE_LOW)
                val manager = getSystemService(NotificationManager::class.java)
                manager.createNotificationChannel(channel)

                val notification = android.app.Notification.Builder(this, channelId)
                    .setContentTitle("秘书舰陪伴中")
                    .setContentText("秘书舰正在屏幕上")
                    .setSmallIcon(android.R.drawable.ic_dialog_info)
                    .setOngoing(true)
                    .build()

                startForeground(1, notification)
                Log.d(TAG, "startForegroundServiceNotification: 前台服务启动成功")
            }
        } catch (e: Exception) {
            Log.e(TAG, "startForegroundServiceNotification: 前台服务启动失败", e)
        }
    }
}

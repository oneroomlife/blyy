package com.example.blyy.service

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Intent
import android.graphics.PixelFormat
import android.os.Build
import android.os.IBinder
import android.view.Gravity
import android.view.View
import android.view.WindowManager
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.platform.ComposeView
import androidx.core.app.NotificationCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.LifecycleRegistry
import androidx.lifecycle.MutableLiveData
import androidx.lifecycle.ViewModelStore
import androidx.lifecycle.ViewModelStoreOwner
import androidx.lifecycle.setViewTreeLifecycleOwner
import androidx.lifecycle.setViewTreeViewModelStoreOwner
import androidx.savedstate.SavedStateRegistry
import androidx.savedstate.SavedStateRegistryController
import androidx.savedstate.SavedStateRegistryOwner
import androidx.savedstate.setViewTreeSavedStateRegistryOwner
import com.example.blyy.R
import com.example.blyy.ui.components.SecretaryChibiOverlay
import com.example.blyy.ui.theme.BlyyTheme
import com.example.blyy.viewmodel.SecretaryManager
import dagger.hilt.android.AndroidEntryPoint
import javax.inject.Inject

/**
 * 系统级悬浮窗服务
 * 优化：支持前台服务，完整的生命周期管理，以及 Compose 环境配置
 * 解决全屏拦截问题，通过 WRAP_CONTENT 减小窗口体积
 */
@AndroidEntryPoint
class OverlayService : Service(), LifecycleOwner, ViewModelStoreOwner, SavedStateRegistryOwner {

    @Inject
    lateinit var secretaryManager: SecretaryManager

    private var windowManager: WindowManager? = null
    private var overlayView: ComposeView? = null
    private var layoutParams: WindowManager.LayoutParams? = null

    private val _isShowing = MutableLiveData<Boolean>()
    val isShowing: MutableLiveData<Boolean> = _isShowing

    private val lifecycleRegistry = LifecycleRegistry(this)
    private val mViewModelStore = ViewModelStore()
    private val savedStateRegistryController = SavedStateRegistryController.create(this)

    override val lifecycle: Lifecycle get() = lifecycleRegistry
    override val viewModelStore: ViewModelStore get() = mViewModelStore
    override val savedStateRegistry: SavedStateRegistry get() = savedStateRegistryController.savedStateRegistry

    companion object {
        const val ACTION_SHOW = "com.example.blyy.action.SHOW_OVERLAY"
        const val ACTION_HIDE = "com.example.blyy.action.HIDE_OVERLAY"
        const val ACTION_TOGGLE = "com.example.blyy.action.TOGGLE_OVERLAY"
        private const val NOTIFICATION_ID = 1001
        private const val CHANNEL_ID = "overlay_service_channel"
        
        private var isRunning = false
        fun isServiceRunning(): Boolean = isRunning
    }

    override fun onCreate() {
        super.onCreate()
        isRunning = true
        savedStateRegistryController.performRestore(null)
        lifecycleRegistry.handleLifecycleEvent(Lifecycle.Event.ON_CREATE)
        windowManager = getSystemService(WINDOW_SERVICE) as WindowManager
        createNotificationChannel()
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID,
                "悬浮窗服务",
                NotificationManager.IMPORTANCE_LOW
            ).apply {
                description = "保持悬浮窗在后台运行"
            }
            val manager = getSystemService(NotificationManager::class.java)
            manager.createNotificationChannel(channel)
        }
    }

    private fun getNotification(): Notification {
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("秘书舰正在值勤")
            .setContentText("点击进入应用管理悬浮窗")
            .setSmallIcon(R.mipmap.cf)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .setOngoing(true)
            .build()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        lifecycleRegistry.handleLifecycleEvent(Lifecycle.Event.ON_START)
        
        startForeground(NOTIFICATION_ID, getNotification())

        when (intent?.action) {
            ACTION_SHOW -> showOverlay()
            ACTION_HIDE -> stopSelf()
            ACTION_TOGGLE -> toggleOverlay()
            else -> showOverlay()
        }
        return START_STICKY
    }

    override fun onBind(intent: Intent?): IBinder? = null

    private fun showOverlay() {
        if (overlayView != null) return

        layoutParams = WindowManager.LayoutParams().apply {
            type = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
            } else {
                @Suppress("DEPRECATION")
                WindowManager.LayoutParams.TYPE_PHONE
            }
            // FLAG_NOT_FOCUSABLE 保证不拦截外部的返回键和输入法
            flags = (
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS
            )
            // 优化：将窗口大小限制为 WRAP_CONTENT，避免全屏透明层拦截事件
            width = WindowManager.LayoutParams.WRAP_CONTENT
            height = WindowManager.LayoutParams.WRAP_CONTENT
            gravity = Gravity.TOP or Gravity.START
            format = PixelFormat.TRANSLUCENT
            
            // 初始位置
            x = 100
            y = 300
        }

        overlayView = ComposeView(this).apply {
            setViewTreeLifecycleOwner(this@OverlayService)
            setViewTreeViewModelStoreOwner(this@OverlayService)
            setViewTreeSavedStateRegistryOwner(this@OverlayService)
            
            setContent {
                // 旧的悬浮窗服务已禁用，立绘显示功能移至 SecretaryOverlayService
                // 此处保留仅用于通知服务
            }
        }

        try {
            windowManager?.addView(overlayView, layoutParams)
            _isShowing.postValue(true)
            lifecycleRegistry.handleLifecycleEvent(Lifecycle.Event.ON_RESUME)
        } catch (e: Exception) {
            e.printStackTrace()
            stopSelf()
        }
    }

    private fun updateWindowPosition(dx: Float, dy: Float) {
        layoutParams?.let { lp ->
            lp.x += dx.toInt()
            lp.y += dy.toInt()
            windowManager?.updateViewLayout(overlayView, lp)
        }
    }

    fun hideOverlay() {
        overlayView?.let {
            try {
                windowManager?.removeView(it)
            } catch (e: Exception) {
                e.printStackTrace()
            }
            overlayView = null
            _isShowing.postValue(false)
        }
    }

    private fun toggleOverlay() {
        if (overlayView == null) {
            showOverlay()
        } else {
            stopSelf()
        }
    }

    override fun onDestroy() {
        lifecycleRegistry.handleLifecycleEvent(Lifecycle.Event.ON_PAUSE)
        lifecycleRegistry.handleLifecycleEvent(Lifecycle.Event.ON_STOP)
        hideOverlay()
        lifecycleRegistry.handleLifecycleEvent(Lifecycle.Event.ON_DESTROY)
        mViewModelStore.clear()
        isRunning = false
        super.onDestroy()
    }
}

package com.example.blyy.utils

import android.content.Context
import android.content.Intent
import android.os.Build
import android.widget.Toast
import com.example.blyy.SecretaryOverlayService

/**
 * 悬浮窗服务管理器
 * 提供启动、停止和控制悬浮窗服务的便捷方法
 */
object OverlayServiceManager {

    /**
     * 启动悬浮窗服务
     * @param context 上下文
     */
    fun startOverlayService(context: Context) {
        if (!OverlayPermissionUtils.hasOverlayPermission(context)) {
            Toast.makeText(
                context,
                "请先授予悬浮窗权限",
                Toast.LENGTH_SHORT
            ).show()
            OverlayPermissionUtils.requestOverlayPermission(context)
            return
        }

        val intent = Intent(context, SecretaryOverlayService::class.java)

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            context.startForegroundService(intent)
        } else {
            context.startService(intent)
        }

        Toast.makeText(context, "悬浮窗已启动", Toast.LENGTH_SHORT).show()
    }

    /**
     * 停止悬浮窗服务
     * @param context 上下文
     */
    fun stopOverlayService(context: Context) {
        val intent = Intent(context, SecretaryOverlayService::class.java)
        context.stopService(intent)
        Toast.makeText(context, "悬浮窗已关闭", Toast.LENGTH_SHORT).show()
    }

    /**
     * 切换悬浮窗显示状态
     * @param context 上下文
     */
    fun toggleOverlayService(context: Context) {
        if (!OverlayPermissionUtils.hasOverlayPermission(context)) {
            Toast.makeText(
                context,
                "请先授予悬浮窗权限",
                Toast.LENGTH_SHORT
            ).show()
            OverlayPermissionUtils.requestOverlayPermission(context)
            return
        }

        if (SecretaryOverlayService.isServiceRunning()) {
            stopOverlayService(context)
        } else {
            startOverlayService(context)
        }
    }

    /**
     * 检查悬浮窗服务是否正在运行
     * @return true 如果正在运行
     */
    fun isServiceRunning(): Boolean {
        return SecretaryOverlayService.isServiceRunning()
    }

    /**
     * 检查并请求权限
     * @param context 上下文
     * @return true 如果已授权，false 如果未授权
     */
    fun checkAndRequestPermission(context: Context): Boolean {
        return OverlayPermissionUtils.checkAndRequestPermission(context)
    }
}

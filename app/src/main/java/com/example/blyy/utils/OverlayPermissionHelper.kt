package com.example.blyy.utils

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.provider.Settings

/**
 * 悬浮窗权限管理工具类
 * 提供悬浮窗权限检查、请求功能
 */
object OverlayPermissionHelper {

    /**
     * 检查是否具有悬浮窗权限
     * @param context 上下文
     * @return true 如果已授权，false 如果未授权
     */
    fun hasOverlayPermission(context: Context): Boolean {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            Settings.canDrawOverlays(context)
        } else {
            true
        }
    }

    /**
     * 请求悬浮窗权限
     * 会打开系统设置页面让用户手动授权
     * @param context 上下文
     */
    fun requestOverlayPermission(context: Context) {
        try {
            val intent = Intent(
                Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                Uri.parse("package:${context.packageName}")
            )
            intent.flags = Intent.FLAG_ACTIVITY_NEW_TASK
            context.startActivity(intent)
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }

    /**
     * 检查并请求权限（如果未授权）
     * @param context 上下文
     * @return true 如果已授权，false 如果未授权并跳转设置页面
     */
    fun checkAndRequestPermission(context: Context): Boolean {
        if (hasOverlayPermission(context)) {
            return true
        }
        
        requestOverlayPermission(context)
        return false
    }
}

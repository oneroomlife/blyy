package com.example.blyy.utils

import android.app.AppOpsManager
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.provider.Settings
import android.util.Log

/**
 * 悬浮窗权限管理工具类
 * 提供悬浮窗权限检查、请求和设置功能
 */
object OverlayPermissionUtils {

    private const val TAG = "OverlayPermissionUtils"

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
            Log.e(TAG, "Failed to open overlay permission settings", e)
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

    /**
     * 获取悬浮窗权限状态描述
     * @param context 上下文
     * @return 权限状态描述字符串
     */
    fun getPermissionStatusText(context: Context): String {
        return if (hasOverlayPermission(context)) {
            "悬浮窗权限已授权"
        } else {
            "悬浮窗权限未授权，需要在系统设置中手动开启"
        }
    }

    /**
     * 检查是否需要显示权限请求提示
     * @param context 上下文
     * @return true 如果需要显示提示
     */
    fun shouldShowPermissionRationale(context: Context): Boolean {
        return !hasOverlayPermission(context)
    }

    /**
     * 获取 AppOpsManager 中悬浮窗权限的状态
     * @param context 上下文
     * @return 权限状态码
     */
    fun getOverlayPermissionMode(context: Context): Int {
        val appOps = context.getSystemService(Context.APP_OPS_SERVICE) as AppOpsManager
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            appOps.checkOpNoThrow(
                AppOpsManager.OPSTR_SYSTEM_ALERT_WINDOW,
                android.os.Process.myUid(),
                context.packageName
            )
        } else {
            AppOpsManager.MODE_ALLOWED
        }
    }

    /**
     * 判断悬浮窗权限是否被允许（通过 AppOpsManager）
     * @param context 上下文
     * @return true 如果允许
     */
    fun isOverlayPermissionAllowed(context: Context): Boolean {
        val mode = getOverlayPermissionMode(context)
        return mode == AppOpsManager.MODE_ALLOWED
    }
}

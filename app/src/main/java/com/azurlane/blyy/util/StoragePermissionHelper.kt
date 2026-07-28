package com.azurlane.blyy.util

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.provider.DocumentsContract
import android.util.Log

/**
 * SD 小人资源存储权限助手（v2，SAF + 应用专属目录）。
 *
 * 自 v2 起不再请求 [android.Manifest.permission.MANAGE_EXTERNAL_STORAGE]（"所有文件访问"）权限，
 * 改用 Android 规范的两种方式访问 SD 资源：
 *
 * 1. **应用专属外部存储**（默认，免权限）：
 *    `Android/data/<pkg>/files/blhx_sd/`，应用直接读写，无需任何系统权限。
 *    通过 [SdResourceOrganizer] 整理后的资源即存放于此。
 *
 * 2. **SAF 文档树授权**（用户主动选择，免权限）：
 *    通过 [Intent.ACTION_OPEN_DOCUMENT_TREE] 让用户选择包含 SD 资源的源目录，
 *    应用获得该目录的持久化只读 URI（[ContentResolver.takePersistableUriPermission]），
 *    后续可读取该目录整理资源到应用专属目录。
 *
 * 老版本残留的 [android.os.Environment.isExternalStorageManager] 检测仅作为"已存在公共 Download 资源"
 * 的兼容性回退路径，不再主动请求该权限。
 */
object StoragePermissionHelper {

    private const val TAG = "StoragePermissionHelper"

    /** 持久化 SAF URI 的 SharedPreferences 文件名 */
    private const val PREFS_NAME = "sd_storage_prefs"

    /** 持久化的 SAF tree URI key */
    private const val KEY_SAF_TREE_URI = "saf_tree_uri"

    /**
     * 是否具备访问 SD 资源的权限。
     *
     * v2 起应用专属目录始终可访问，所以此处返回 true 表示：
     * - 已授权 SAF 文档树（可读取用户选择的外部源目录），或
     * - 已有遗留的"所有文件访问"权限（兼容旧版用户的 Download 资源），或
     * - 应用专属目录已有资源（无需任何系统权限即可访问）
     *
     * UI 调用此方法仅用于显示"是否可读取外部源目录"的状态，
     * 资源加载本身永远可用（应用专属目录兜底）。
     */
    fun hasStoragePermission(context: Context): Boolean {
        return hasPersistedSafUri(context) || hasLegacyAllFilesAccess()
    }

    /**
     * 是否已持久化 SAF 文档树 URI。
     */
    fun hasPersistedSafUri(context: Context): Boolean {
        return getPersistedSafUri(context) != null
    }

    /**
     * 获取持久化的 SAF 文档树 URI（用户曾授权过的源目录）。
     *
     * 返回 null 表示用户尚未通过 SAF 选择过源目录。
     */
    fun getPersistedSafUri(context: Context): Uri? {
        val str = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .getString(KEY_SAF_TREE_URI, null) ?: return null
        return try {
            val uri = Uri.parse(str)
            // 验证 URI 仍然有效（未被用户撤销授权）
            if (isUriStillGranted(context, uri)) uri else {
                Log.w(TAG, "getPersistedSafUri: URI 已被撤销，清除持久化记录")
                clearPersistedSafUri(context)
                null
            }
        } catch (_: Exception) { null }
    }

    /**
     * 持久化 SAF 文档树 URI（在用户授权后调用）。
     *
     * 同时调用 [ContentResolver.takePersistableUriPermission] 申请持久化读权限，
     * 使 URI 在 App 重启后仍然有效。
     */
    fun persistSafUri(context: Context, uri: Uri) {
        try {
            context.contentResolver.takePersistableUriPermission(
                uri,
                Intent.FLAG_GRANT_READ_URI_PERMISSION
            )
        } catch (e: SecurityException) {
            Log.e(TAG, "persistSafUri: 无法获取持久化权限", e)
        }
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .edit()
            .putString(KEY_SAF_TREE_URI, uri.toString())
            .apply()
        Log.i(TAG, "persistSafUri: 已持久化 SAF URI $uri")
    }

    /**
     * 清除持久化的 SAF URI（用于用户撤销授权或重新选择目录）。
     */
    fun clearPersistedSafUri(context: Context) {
        getPersistedSafUri(context)?.let { uri ->
            try {
                context.contentResolver.releasePersistableUriPermission(
                    uri,
                    Intent.FLAG_GRANT_READ_URI_PERMISSION
                )
            } catch (_: SecurityException) {
                // 已被撤销或权限不存在，忽略
            }
        }
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .edit()
            .remove(KEY_SAF_TREE_URI)
            .apply()
        Log.i(TAG, "clearPersistedSafUri: 已清除 SAF URI")
    }

    /**
     * 创建用于选择源目录的 SAF Intent。
     *
     * 调用方通过 [androidx.activity.result.ActivityResultContracts.OpenDocumentTree]
     * 启动该 Intent，用户选择目录后回调返回 tree URI。
     */
    fun createOpenDocumentTreeIntent(): Intent {
        return Intent(Intent.ACTION_OPEN_DOCUMENT_TREE).apply {
            addFlags(
                Intent.FLAG_GRANT_READ_URI_PERMISSION or
                Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION
            )
        }
    }

    /**
     * 检查 SAF URI 是否仍然有效（用户未在系统设置中撤销授权）。
     */
    private fun isUriStillGranted(context: Context, uri: Uri): Boolean {
        val perms = context.contentResolver.persistedUriPermissions
        return perms.any { it.uri == uri && it.isReadPermission }
    }

    /**
     * 兼容性检查：是否仍有遗留的"所有文件访问"权限。
     *
     * 仅用于检测旧版用户已授权的情况，不再主动请求该权限。
     */
    private fun hasLegacyAllFilesAccess(): Boolean {
        return android.os.Environment.isExternalStorageManager()
    }

    /**
     * 检查指定 SAF tree URI 对应的目录是否仍然可访问。
     *
     * 用于 UI 显示"上次选择的源目录是否仍可用"。
     * 不依赖 DocumentFile 库，直接用 ContentResolver 查询 Document 列。
     */
    fun isSourceTreeAccessible(context: Context, uri: Uri): Boolean {
        return try {
            context.contentResolver.query(
                uri,
                arrayOf(DocumentsContract.Document.COLUMN_DOCUMENT_ID),
                null, null, null
            )?.use { c -> c.moveToFirst() } == true
        } catch (_: Exception) { false }
    }
}

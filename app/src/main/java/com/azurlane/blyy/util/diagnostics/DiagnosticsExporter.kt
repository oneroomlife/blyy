package com.azurlane.blyy.util.diagnostics

import android.content.ClipData
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import androidx.core.content.FileProvider
import com.azurlane.blyy.BuildConfig
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

/**
 * 诊断包导出：把崩溃现场、运行日志、设备信息与**脱敏后**的设置快照打成一个 zip，
 * 通过系统分享面板交给用户自行发送。
 *
 * 隐私边界（重要）：
 * - 设置快照由 `PlayerSettingsDataStore.diagnosticSnapshot()` 生成，凭证类 key 已打码；
 * - 不包含图片、音频、游戏资源包、AI 对话正文；
 * - 文件写在 `cacheDir/diagnostics/`，随缓存清理消失，不会长期留存。
 */
object DiagnosticsExporter {

    private const val DIR_NAME = "diagnostics"
    private const val KEEP_FILES = 3

    /**
     * 生成诊断 zip。
     * @param settingsSnapshot 已脱敏的设置快照文本
     * @return 生成的 zip 文件（位于 cacheDir/diagnostics）
     */
    fun build(context: Context, settingsSnapshot: String): File {
        val appContext = context.applicationContext
        val dir = File(appContext.cacheDir, DIR_NAME).apply { mkdirs() }
        val stamp = SimpleDateFormat("yyyyMMdd-HHmmss", Locale.US).format(Date())
        val zipFile = File(dir, "blyy-diag-$stamp.zip")

        ZipOutputStream(zipFile.outputStream().buffered()).use { zip ->
            zip.put("device.txt", deviceInfo(context))
            zip.put("settings.txt", settingsSnapshot)
            zip.put("logs.txt", AppLog.dump())

            CrashReporter.listCrashes(appContext).forEach { crash ->
                // 单个文件损坏/被占用时跳过，不让整包导出失败
                val text = runCatching { crash.readText() }.getOrNull() ?: return@forEach
                zip.put("crashes/${crash.name}", text)
            }
        }

        prune(dir)
        AppLog.i("DiagnosticsExporter", "diagnostics exported: ${zipFile.name} (${zipFile.length()}B)")
        return zipFile
    }

    /** 分享诊断包的系统 Intent（调用方负责 startActivity） */
    fun shareIntent(context: Context, file: File): Intent {
        val uri: Uri = FileProvider.getUriForFile(
            context,
            "${context.packageName}.fileprovider",
            file
        )
        return Intent(Intent.ACTION_SEND).apply {
            type = "application/zip"
            putExtra(Intent.EXTRA_SUBJECT, "BLYY 诊断包 ${BuildConfig.VERSION_NAME}")
            putExtra(Intent.EXTRA_STREAM, uri)
            clipData = ClipData.newUri(context.contentResolver, "diagnostics", uri)
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
    }

    private fun ZipOutputStream.put(name: String, content: String) {
        putNextEntry(ZipEntry(name))
        write(content.toByteArray(Charsets.UTF_8))
        closeEntry()
    }

    private fun deviceInfo(context: Context): String = buildString {
        appendLine("# BLYY device info")
        appendLine("app        = ${BuildConfig.APPLICATION_ID} ${BuildConfig.VERSION_NAME} (${BuildConfig.VERSION_CODE})")
        appendLine("buildType  = ${BuildConfig.BUILD_TYPE} (debug=${BuildConfig.DEBUG})")
        appendLine("android    = ${Build.VERSION.RELEASE} / API ${Build.VERSION.SDK_INT}")
        appendLine("manufacturer = ${Build.MANUFACTURER}")
        appendLine("model      = ${Build.MODEL}")
        appendLine("device     = ${Build.DEVICE}")
        appendLine("abis       = ${Build.SUPPORTED_ABIS.joinToString()}")
        appendLine("locale     = ${Locale.getDefault()}")
        appendLine("timezone   = ${java.util.TimeZone.getDefault().id}")
        appendLine("packageDir = ${context.packageResourcePath}")
        appendLine("crashCount = ${CrashReporter.crashCount(context)}")
        appendLine("logBytes   = ${AppLog.totalBytes()}")
    }

    private fun prune(dir: File) {
        dir.listFiles()
            ?.sortedByDescending { it.lastModified() }
            ?.drop(KEEP_FILES)
            ?.forEach { it.delete() }
    }
}

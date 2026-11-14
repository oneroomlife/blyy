package com.azurlane.blyy.util.diagnostics

import android.content.Context
import android.os.Build
import android.util.Log
import com.azurlane.blyy.BuildConfig
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlin.system.exitProcess

/**
 * 全局未捕获异常兜底：把崩溃现场写进 `filesDir/crashes/`，再交给系统默认处理器。
 *
 * 关键点：
 * 1. **必须链式调用上一个 handler**，否则系统不再弹「应用已停止」、也不会完成收尾，
 *    表现为黑屏卡死——这是自建崩溃处理最常见的坑。
 * 2. 写盘在独立线程完成并 `join(1500ms)` 兜底，避免主线程已死导致文件写一半。
 * 3. 只保留最近 [MAX_CRASH_FILES] 份，防止反复崩溃把存储写满。
 * 4. 崩溃本身绝不二次抛出：包一层 `runCatching`，失败就安静放行。
 */
object CrashReporter {

    private const val TAG = "CrashReporter"
    private const val DIR_NAME = "crashes"
    private const val MAX_CRASH_FILES = 5

    @Volatile
    private var installed = false

    fun install(context: Context) {
        if (installed) return
        installed = true
        val appContext = context.applicationContext
        val previous = Thread.getDefaultUncaughtExceptionHandler()

        Thread.setDefaultUncaughtExceptionHandler { thread, throwable ->
            runCatching { persist(appContext, thread, throwable) }
                .onFailure { Log.e(TAG, "persist crash failed", it) }

            if (previous != null) {
                previous.uncaughtException(thread, throwable)
            } else {
                // 没有前置 handler 时按系统默认语义退出进程
                exitProcess(1)
            }
        }
        AppLog.i(TAG, "CrashReporter installed")
    }

    fun crashDir(context: Context): File =
        File(context.applicationContext.filesDir, DIR_NAME).apply { mkdirs() }

    fun listCrashes(context: Context): List<File> =
        crashDir(context).listFiles()
            ?.sortedByDescending { it.lastModified() }
            .orEmpty()

    fun latest(context: Context): File? = listCrashes(context).firstOrNull()

    fun crashCount(context: Context): Int = crashDir(context).listFiles()?.size ?: 0

    fun clear(context: Context) {
        runCatching { crashDir(context).listFiles()?.forEach { it.delete() } }
    }

    private fun persist(context: Context, thread: Thread, throwable: Throwable) {
        val dir = crashDir(context)
        val stamp = SimpleDateFormat("yyyyMMdd-HHmmss", Locale.US).format(Date())
        val file = File(dir, "crash-$stamp.txt")
        val text = buildString {
            appendLine("=== BLYY crash report ===")
            appendLine("time      : ${SimpleDateFormat("yyyy-MM-dd HH:mm:ss.SSS", Locale.US).format(Date())}")
            appendLine("app       : ${BuildConfig.APPLICATION_ID} ${BuildConfig.VERSION_NAME} (${BuildConfig.VERSION_CODE})")
            appendLine("build     : ${BuildConfig.BUILD_TYPE} debug=${BuildConfig.DEBUG}")
            appendLine("android   : ${Build.VERSION.RELEASE} (API ${Build.VERSION.SDK_INT})")
            appendLine("device    : ${Build.MANUFACTURER} ${Build.MODEL} / ${Build.DEVICE}")
            appendLine("abis      : ${Build.SUPPORTED_ABIS.joinToString()}")
            appendLine("locale    : ${Locale.getDefault()}")
            appendLine("thread    : ${thread.name} (id=${thread.id})")
            appendLine("memory    : max=${Runtime.getRuntime().maxMemory() / 1024 / 1024}MB " +
                "total=${Runtime.getRuntime().totalMemory() / 1024 / 1024}MB " +
                "free=${Runtime.getRuntime().freeMemory() / 1024 / 1024}MB")
            appendLine()
            appendLine("=== stack trace ===")
            appendLine(throwable.stackTraceToString())
            throwable.cause?.let {
                appendLine("=== cause ===")
                appendLine(it.stackTraceToString())
            }
            appendLine()
            appendLine(AppLog.dump())
        }

        // 在独立线程写，主线程已处于异常收尾流程，最多等 1.5s
        val writer = Thread {
            runCatching { file.writeText(text) }
                .onFailure { Log.e(TAG, "write crash file failed", it) }
        }.apply {
            name = "blyy-crash-writer"
            isDaemon = true
            start()
        }
        runCatching { writer.join(1500) }

        prune(dir)
        Log.e(TAG, "crash persisted: ${file.name}")
    }

    private fun prune(dir: File) {
        dir.listFiles()
            ?.sortedByDescending { it.lastModified() }
            ?.drop(MAX_CRASH_FILES)
            ?.forEach { it.delete() }
    }
}

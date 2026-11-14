package com.azurlane.blyy.util.diagnostics

import android.content.Context
import android.util.Log
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * 轻量运行日志：内存环形缓冲 + 文件落盘。
 *
 * 为什么需要它（对应 docs/FEATURE_EXPANSION_2026-11.md P0-4）：
 * 项目此前**零可观测**——线上崩溃只能靠用户截图，抓取失败/导入失败/AI 报错
 * 全部躺在 logcat 里随进程消失。这里不引 Sentry/Crashlytics（隐私 + 体积 + 自托管成本），
 * 而是把「最近发生过什么」留在本机，崩溃时由 [CrashReporter] 一起打包。
 *
 * 设计取舍：
 * - **只记关键事件**，不做全量日志镜像（避免 IO 抖动与存储膨胀）；
 * - 内存只留 [MAX_MEMORY_LINES] 行，文件按天分片 + 超限滚动，最多 [KEEP_FILES] 个；
 * - 所有写操作串行在 [lock] 内，崩溃线程（可能已处于半死状态）也能安全写一次；
 * - 任何 IO 异常都吞掉并 `Log.w`，日志组件绝不能成为新的崩溃源。
 */
object AppLog {

    private const val TAG = "AppLog"
    private const val DIR_NAME = "logs"
    private const val MAX_MEMORY_LINES = 400
    private const val MAX_FILE_BYTES = 512L * 1024L
    private const val KEEP_FILES = 3

    private val lock = Any()
    private val ring = ArrayDeque<String>(MAX_MEMORY_LINES)
    private val timeFormat = SimpleDateFormat("MM-dd HH:mm:ss.SSS", Locale.US)

    /**
     * 凭证脱敏规则。
     *
     * 诊断包是**用户主动分享出去**的：日志里一旦出现 `?api_key=xxx`、`Authorization: Bearer xxx`
     * 或 `sk-...`，所谓"导出日志求助"就变成了把凭证贴到公开 Issue。
     */
    private val QUERY_SECRET = Regex("""(?i)((?:api[_-]?key|access[_-]?token|token|key))\s*=\s*[^&\s"',]+""")
    private val BEARER = Regex("""(?i)bearer\s+[A-Za-z0-9._\-]+""")
    private val SK_KEY = Regex("""\bsk-[A-Za-z0-9]{8,}""")

    @Volatile
    private var logDir: File? = null

    /**
     * 在 Application.onCreate 调用一次。
     * 这里刻意**不写首行日志**：安装发生在主线程启动阶段，任何磁盘 IO 都会被
     * StrictMode 记一笔；第一条真正的日志会在业务代码里自然产生。
     */
    fun install(context: Context) {
        synchronized(lock) {
            logDir = File(context.applicationContext.filesDir, DIR_NAME).apply { mkdirs() }
            ring.clear()
        }
    }

    fun d(tag: String, message: String) = append("D", tag, message, null)
    fun i(tag: String, message: String) = append("I", tag, message, null)
    fun w(tag: String, message: String, throwable: Throwable? = null) =
        append("W", tag, message, throwable)

    fun e(tag: String, message: String, throwable: Throwable? = null) =
        append("E", tag, message, throwable)

    /** 内存环形缓冲 + 当日文件尾部，用于诊断包与崩溃报告 */
    fun dump(): String = synchronized(lock) {
        buildString {
            appendLine("── 内存日志（最近 ${ring.size} 条）──")
            ring.forEach { appendLine(it) }
            val dir = logDir
            if (dir != null) {
                val files = dir.listFiles()?.sortedByDescending { it.lastModified() }.orEmpty()
                files.take(2).forEach { file ->
                    appendLine("── 文件日志 ${file.name}（尾部 200 行）──")
                    runCatching {
                        file.readLines().takeLast(200).forEach { appendLine(it) }
                    }.onFailure { appendLine("<读取失败: ${it.message}>") }
                }
            }
        }
    }

    fun logFiles(): List<File> =
        logDir?.listFiles()?.sortedByDescending { it.lastModified() }.orEmpty()

    /** 设置页「清空诊断日志」 */
    fun clear() = synchronized(lock) {
        ring.clear()
        runCatching { logDir?.listFiles()?.forEach { it.delete() } }
    }

    /** 日志目录总大小（字节），用于设置页展示 */
    fun totalBytes(): Long = logDir?.listFiles()?.sumOf { it.length() } ?: 0L

    private fun append(level: String, tag: String, message: String, throwable: Throwable?) {
        // 诊断包会被用户主动分享出去：先脱敏再落盘/进 logcat
        val safeMessage = redact(message)
        val line = buildString {
            append(timeFormat.format(Date()))
            append(' ')
            append(level)
            append('/')
            append(tag)
            append(": ")
            append(safeMessage)
            if (throwable != null) {
                append(" | ")
                append(throwable.javaClass.simpleName)
                append(": ")
                append(throwable.message)
            }
        }
        synchronized(lock) {
            ring.addLast(line)
            while (ring.size > MAX_MEMORY_LINES) ring.removeFirst()
            writeToFile(line, throwable)
        }
        // 同时进 logcat：接 debugger 时不丢上下文
        when (level) {
            "E" -> Log.e(tag, safeMessage, throwable)
            "W" -> Log.w(tag, safeMessage, throwable)
            "D" -> Log.d(tag, safeMessage, throwable)
            else -> Log.i(tag, safeMessage, throwable)
        }
    }

    /** 把三类常见凭证形态打码（查询参数 / Bearer 头 / sk- 前缀） */
    private fun redact(raw: String): String {
        var result = QUERY_SECRET.replace(raw, "$1=***")
        result = BEARER.replace(result, "Bearer ***")
        result = SK_KEY.replace(result, "sk-***")
        return result
    }

    private fun writeToFile(line: String, throwable: Throwable?) {
        val dir = logDir ?: return
        try {
            val day = SimpleDateFormat("yyyyMMdd", Locale.US).format(Date())
            val file = File(dir, "blyy-$day.log")
            if (file.length() > MAX_FILE_BYTES) {
                val rolled = File(dir, "blyy-$day-${System.currentTimeMillis()}.log.bak")
                file.renameTo(rolled)
                pruneOldBackups(dir)
            }
            file.appendText(
                buildString {
                    appendLine(line)
                    throwable?.let { append(it.stackTraceToString()) }
                }
            )
        } catch (t: Throwable) {
            Log.w(TAG, "writeToFile failed: ${t.message}")
        }
    }

    private fun pruneOldBackups(dir: File) {
        dir.listFiles { f -> f.name.endsWith(".bak") }
            ?.sortedByDescending { it.lastModified() }
            ?.drop(KEEP_FILES)
            ?.forEach { it.delete() }
    }
}
